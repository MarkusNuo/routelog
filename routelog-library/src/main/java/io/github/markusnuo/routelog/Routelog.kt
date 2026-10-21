package io.github.markusnuo.routelog

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.database.SQLException
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import io.github.markusnuo.routelog.data.RecordingListItem
import io.github.markusnuo.routelog.link.GrantDecision
import io.github.markusnuo.routelog.link.GrantHash
import io.github.markusnuo.routelog.link.LinkGrantAction
import io.github.markusnuo.routelog.link.LinkParseResult
import io.github.markusnuo.routelog.link.RecordingLinkParser
import io.github.markusnuo.routelog.recording.Recorder
import io.github.markusnuo.routelog.recording.RecorderEvent
import io.github.markusnuo.routelog.recording.RecorderState
import io.github.markusnuo.routelog.recording.RecordingService
import io.github.markusnuo.routelog.start.StartBlocker
import io.github.markusnuo.routelog.start.StartCheck
import io.github.markusnuo.routelog.start.StartConditions
import io.github.markusnuo.routelog.upload.UploadRetention
import io.github.markusnuo.routelog.upload.UploadWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * What an app that hosts the library calls. The host brings the UI; the library records, stores,
 * shows the mandatory notification and uploads.
 *
 * - Call [configure] once in `Application.onCreate` if the defaults don't fit.
 * - Before a start, the host asks for the runtime permissions [startProblem] reports
 *   (precise location together with approximate, notifications on Android 13+).
 * - Start only from a visible activity, right after a user action: Android lets only a visible
 *   app start recording location. [StartOutcome.Started] only says that the recording service was
 *   asked; it can still fail afterwards ([RecorderEvent.StartFailed], [RecorderEvent.LinkUsed], ...
 *   on [events]; [state] then goes back to Idle).
 * - The host's own "record" button is the user's confirmation: unlike a link, the library shows
 *   no question about the upload target.
 * - [recordings] when the host's list opens: it marks recordings that a dead process left running
 *   as interrupted and removes the "recording interrupted" notice.
 *
 * See docs/LIBRARY.md. Never log a token.
 */
object Routelog {
    /**
     * The extra of the intent that opens the host app after a tap on "recording interrupted"
     * ([RoutelogConfig.openAppIntent]) when the resume did not start (a permission, the
     * notifications or location are missing, another recording runs, ...). Its value is the id of
     * the recording (a `Long`). The notice is posted again if the recording can still be resumed
     * (within 12 hours); the host should tell the user why and offer "Resume" in its list. Only the
     * library's own resume target sets it, not a target the host sets with [RoutelogConfig.resumeIntent].
     */
    const val EXTRA_RESUME_FAILED = "io.github.markusnuo.routelog.extra.RESUME_FAILED"

    /**
     * See [RoutelogConfig]. Call once, in `Application.onCreate`, before the library is used: a
     * call with other values after the first use throws [IllegalStateException].
     */
    fun configure(config: RoutelogConfig) = RoutelogCore.configure(config)

    /**
     * What still prevents a recording from starting, or null if nothing does. Only the first
     * blocker, in the order the user has to fix them; ask again after each fix. Never
     * [StartBlocker.LOCAL_NETWORK_PERMISSION]: the library does not check that one.
     */
    fun startProblem(context: Context): StartBlocker? = StartCheck.firstBlocker(startConditions(context))

    /**
     * Records a route that is uploaded after the stop to [upload]'s address with its one-time
     * token. Only `https`, unless the host app itself is a debug build. One token gives at most
     * one recording, as with a link: the same token again keeps, resumes or refuses, but never
     * starts a second one, not even while the first one is still starting.
     *
     * An address, token or expiry that breaks the rules of [UploadRequest] gives
     * [StartOutcome.Invalid] before anything else is looked at. Then what routelog already knows
     * about the token is answered ([StartOutcome.AlreadyRecording], [StartOutcome.AlreadyUsed],
     * [StartOutcome.TooLateToResume]); only a start or a resume looks at the conditions
     * ([StartOutcome.Blocked]). A start is claimed atomically: of two quick calls, one gets
     * [StartOutcome.Started] and the other [StartOutcome.AlreadyRecording] (same token) or
     * [StartOutcome.Busy] (another token). The local database failing gives [StartOutcome.Failed].
     */
    suspend fun startWithUpload(context: Context, upload: UploadRequest): StartOutcome {
        val validated = RecordingLinkParser.validate(
            uploadUrl = upload.uploadUrl,
            token = upload.token,
            label = upload.label,
            expiresAtMillis = upload.expiresAtMillis,
            nowMillis = System.currentTimeMillis(),
            allowHttp = isDebuggable(context),
        )
        val link = when (validated) {
            is LinkParseResult.Invalid -> return StartOutcome.Invalid(validated.reason)
            is LinkParseResult.Valid -> validated.link
        }
        val core = RoutelogCore.get(context)
        val tokenHash = GrantHash.of(link.token)
        // The grant first, as for a link: what routelog knows about this token decides before any
        // condition is looked at (a token that was used needs no permission to be told so).
        val (grant, recording) = try {
            withContext(Dispatchers.IO) {
                val grant = core.database.grantDao().grant(tokenHash)
                grant to grant?.recordingId?.let(core.database.recordingDao()::recording)
            }
        } catch (e: SQLException) {
            Log.e(TAG, "The token could not be checked: ${e.javaClass.simpleName}")
            return StartOutcome.Failed
        }
        // Read as late as possible, back on the caller's thread.
        val running = (core.recorder.state.value as? RecorderState.Recording)?.recordingId
        return when (val action = GrantDecision.decide(grant, recording, running, System.currentTimeMillis())) {
            LinkGrantAction.KeepRecording -> StartOutcome.AlreadyRecording
            LinkGrantAction.AlreadyUsed -> StartOutcome.AlreadyUsed
            LinkGrantAction.TooLateToResume -> StartOutcome.TooLateToResume
            is LinkGrantAction.Resume ->
                startClaimed(context, core, tokenHash) { RecordingService.resumeIntent(context, action.recordingId) }
            LinkGrantAction.NewLink ->
                startClaimed(context, core, tokenHash) { RecordingService.linkedStartIntent(context, link) }
        }
    }

    /**
     * Records a route that stays on the phone (no upload). [StartOutcome.Started],
     * [StartOutcome.Busy], [StartOutcome.Blocked], [StartOutcome.NotVisible] or [StartOutcome.Failed].
     */
    fun start(context: Context): StartOutcome =
        startClaimed(context, RoutelogCore.get(context), tokenHash = null) { RecordingService.startIntent(context) }

    /**
     * Continues the interrupted recording [recordingId] in a new track segment, within 12 hours of
     * the interruption. Outcomes as for [start]; whether the recording can still be resumed is only
     * known in the service: if not, [events] gets [RecorderEvent.ResumeTooLate] or
     * [RecorderEvent.NotResumable] (it is gone or not interrupted).
     */
    fun resume(context: Context, recordingId: Long): StartOutcome =
        startClaimed(context, RoutelogCore.get(context), tokenHash = null) { RecordingService.resumeIntent(context, recordingId) }

    /**
     * Stops the running recording; one with an upload is uploaded afterwards. Does nothing if
     * nothing records. Android can refuse to reach the service from the background (the Stop
     * button of the notification still works then); that is logged, not thrown.
     */
    fun stop(context: Context) {
        // Nothing to stop: don't start the service just to have it end again.
        if (RoutelogCore.get(context).recorder.state.value is RecorderState.Idle && !RecordingService.isRunning) return
        try {
            context.startService(RecordingService.stopIntent(context))
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Stop could not be passed on: ${e.javaClass.simpleName}")
        }
    }

    /**
     * Ends an interrupted recording without resuming it; one with an upload is then uploaded
     * ("Finish & upload"). [onDone] runs on the main thread; `false` if the recording was not
     * interrupted (anymore).
     * A recording that a dead process left running counts as interrupted.
     */
    fun finishAndUpload(context: Context, recordingId: Long, onDone: (finished: Boolean) -> Unit) {
        val core = RoutelogCore.get(context)
        // Same thread, in order: a recording a dead process left as RECORDING is interrupted by then.
        core.recorder.sweepOrphans { }
        core.recorder.finishInterrupted(recordingId) { finished ->
            // It can't be resumed anymore, so the notice would be wrong. (The notice is for the
            // latest interruption only; the host's list shows the others.)
            if (finished) core.notifications.cancelInterrupted()
            onDone(finished)
        }
    }

    /** Idle, starting, or recording (with distance, points, GPS status). */
    fun state(context: Context): StateFlow<RecorderState> = RoutelogCore.get(context).recorder.state

    /**
     * Why a start or resume didn't happen after the service was asked ([StartOutcome.Started]),
     * for a message to the user. Only what happens while somebody collects is delivered.
     */
    fun events(context: Context): SharedFlow<RecorderEvent> = RoutelogCore.get(context).recorder.events

    /**
     * All recordings, newest first, with the state of their upload. Call it when the host's UI
     * opens, not only when a list is shown: recordings a dead process left running are marked
     * interrupted first (so they show as interrupted, with resume, not as recording), and the
     * "recording interrupted" notice is removed, as the list shows what it tells.
     */
    fun recordings(context: Context): Flow<List<RecordingListItem>> {
        val core = RoutelogCore.get(context)
        core.recorder.sweepOrphans { }
        core.notifications.cancelInterrupted()
        return core.database.recordingDao().observeRecordingList()
    }

    /**
     * Deletes a recording that is not running, with its route and open upload. False if it
     * couldn't (it runs, it is gone, or the database refused). A recording a dead process left
     * running counts as interrupted and can be deleted.
     */
    suspend fun delete(context: Context, recordingId: Long): Boolean {
        val core = RoutelogCore.get(context)
        sweepOrphans(core)
        val deleted = try {
            withContext(Dispatchers.IO) {
                if (core.database.recordingDao().deleteUnlessRecording(recordingId) != 1) return@withContext false
                // Its upload went with it (cascade); nothing left to try or to expire.
                core.uploads.forget(recordingId)
                try {
                    // Deleted positions must not stay in the write-ahead log.
                    core.database.checkpoint()
                } catch (e: SQLException) {
                    Log.w(TAG, "Checkpoint after delete failed: ${e.javaClass.simpleName}")
                }
                true
            }
        } catch (e: SQLException) {
            Log.e(TAG, "Recording could not be deleted: ${e.javaClass.simpleName}")
            false
        }
        if (deleted) {
            // A recording that is gone can't be resumed: its notice would lead nowhere. (The notice
            // is for the latest interruption only; the host's list shows the others.)
            core.notifications.cancelInterrupted()
            core.exporter.clearIfFor(listOf(recordingId))
        }
        return deleted
    }

    /**
     * Writes the route as a GPX 1.1 file and returns a `content://` URI to share it (the share
     * intent needs `FLAG_GRANT_READ_URI_PERMISSION`). Null if that failed (the recording is gone,
     * or the file could not be written). The track is named after the start time, not the upload's
     * label. Only the latest export is kept: the next export, or deleting its recording, removes
     * the file. After an upload's keep time the points are gone and the track is empty.
     */
    suspend fun exportGpx(context: Context, recordingId: Long): Uri? = try {
        RoutelogCore.get(context).exporter.export(recordingId)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Never the recording: only what kind of failure it was.
        Log.e(TAG, "Export failed: ${e.javaClass.simpleName}")
        null
    }

    /** What happens to a route after its upload (default: deleted right away). */
    fun uploadRetention(context: Context): UploadRetention = RoutelogCore.get(context).uploadSettings.retention

    /**
     * Sets what happens to a route after its upload. Applies to recordings uploaded before, too: a
     * shorter time deletes their routes at once (in the background). Writes a small file on the
     * calling thread.
     */
    fun setUploadRetention(context: Context, retention: UploadRetention) {
        val core = RoutelogCore.get(context)
        core.uploadSettings.retention = retention
        // On the upload worker like every other cleanup, so it never overlaps with an upload job.
        UploadWorker.execute { core.uploads.applyRetention() }
    }

    // Marks recordings a dead process left as RECORDING as interrupted, and waits until that is done.
    private suspend fun sweepOrphans(core: RoutelogCore) = suspendCancellableCoroutine { continuation ->
        core.recorder.sweepOrphans { continuation.resume(Unit) }
    }

    // Who holds the claim on the start: the hash of the token, or null for a start without one. Only
    // meaningful while the state is Starting or Recording, and only if every start goes through this
    // object, as it does for a host. Guarded by [claimLock].
    private val claimLock = Any()
    private var claimedToken: String? = null

    /**
     * Claims the start before the service is asked (so two quick calls can't both pass), then asks
     * for it. The claim is given back if the service can't be reached; if the start fails later, the
     * service or the recorder return the state to Idle.
     */
    private fun startClaimed(context: Context, core: RoutelogCore, tokenHash: String?, intent: () -> Intent): StartOutcome {
        startProblem(context)?.let { return StartOutcome.Blocked(it) }
        synchronized(claimLock) {
            if (!core.recorder.claimStart()) {
                // The same token is the recording that starts or runs: nothing new, not a conflict.
                return if (tokenHash != null && tokenHash == claimedToken) StartOutcome.AlreadyRecording else StartOutcome.Busy
            }
            claimedToken = tokenHash
        }
        return startService(context, core.recorder, intent())
    }

    private fun startService(context: Context, recorder: Recorder, intent: Intent): StartOutcome {
        var asked = false
        try {
            // Not startForegroundService: the service goes foreground in onStartCommand, and a service
            // that is stopped before it did would make Android end the app. A plain start is allowed
            // while the host app is visible.
            // null: the service does not exist (the host's manifest lost it).
            if (context.startService(intent) == null) return StartOutcome.Failed
            asked = true
            return StartOutcome.Started
        } catch (e: IllegalStateException) {
            return StartOutcome.NotVisible
        } finally {
            // Whatever went wrong, the claim must not stay: nothing would ever give it back.
            if (!asked) recorder.releaseStartClaim()
        }
    }

    private fun startConditions(context: Context): StartConditions {
        val locationManager = context.getSystemService(LocationManager::class.java)
        return StartConditions(
            hasGpsProvider = LocationManager.GPS_PROVIDER in locationManager.allProviders,
            fineLocationGranted = granted(context, Manifest.permission.ACCESS_FINE_LOCATION),
            coarseLocationGranted = granted(context, Manifest.permission.ACCESS_COARSE_LOCATION),
            notificationPermissionGranted = Build.VERSION.SDK_INT < 33 || granted(context, Manifest.permission.POST_NOTIFICATIONS),
            recordingNotificationEnabled = RoutelogCore.get(context).notifications.recordingNotificationEnabled(),
            gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER),
        )
    }

    private fun granted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    // Plain http only in a debug build of the host app, as for links.
    private fun isDebuggable(context: Context) = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    private const val TAG = "Routelog"
}

/**
 * Where and with what permission a route goes after the stop. The app that starts the recording
 * gets these from its server; docs/EXTERNAL_RECORDING.md describes the server side.
 */
class UploadRequest(
    /**
     * `https://` address the GPX is sent to with `PUT` (`http://` only in a debug build of the host
     * app): at most 2048 visible ASCII characters (an international domain name in its `xn--`
     * form), without user info or fragment. Redirects are never followed: a 3xx answer is a final
     * failure.
     */
    val uploadUrl: String,
    /** One-time permission, sent as `Authorization: Bearer`; 1–4096 visible ASCII characters. */
    val token: String,
    /**
     * Shown with the recording ([RecordingListItem.upload]) and used as the track name of the
     * uploaded GPX, e.g. "Lesson 14:00"; an export is named after the start time instead. Control
     * and invisible formatting characters are removed, and a label longer than 80 characters is
     * cut to 79 and "…". It goes to the server: no names of people in it.
     */
    val label: String?,
    /**
     * The token is valid before this instant (exclusive); it must lie in the future at the start.
     * The upload is tried until then; afterwards the route stays on the phone.
     */
    val expiresAtMillis: Long,
) {
    // Never the token or the address.
    override fun toString() = "UploadRequest(label=$label, expiresAtMillis=$expiresAtMillis)"
}

/** What became of a start, resume or start with upload. */
sealed interface StartOutcome {
    /**
     * The recording service was asked to start; [Routelog.state] and [Routelog.events] tell how it
     * goes. It can still fail afterwards: [RecorderEvent.StartFailed] (e.g. Android refused the
     * foreground state, the GPS could not be started), [RecorderEvent.LinkUsed],
     * [RecorderEvent.LinkExpired], [RecorderEvent.NotResumable], [RecorderEvent.ResumeTooLate].
     * [Routelog.state] is then back to Idle.
     */
    data object Started : StartOutcome

    /** The recording of this token starts or runs already; nothing changed. */
    data object AlreadyRecording : StartOutcome

    /** Another recording runs or starts; it must be stopped first. */
    data object Busy : StartOutcome

    /** This token started a recording before (stopped, uploaded or deleted); a new one needs a new token. */
    data object AlreadyUsed : StartOutcome

    /**
     * This token's recording was interrupted more than 12 hours ago; it can be finished and
     * uploaded ([Routelog.finishAndUpload]), not resumed.
     */
    data object TooLateToResume : StartOutcome

    /** A permission, the notifications or location are missing; the host asks the user, then tries again. */
    data class Blocked(val blocker: StartBlocker) : StartOutcome

    /**
     * The upload address, token or expiry is not acceptable: [LinkParseResult.Reason.INSECURE_UPLOAD]
     * for `http` in a host app that is not a debug build, [LinkParseResult.Reason.EXPIRED] for an
     * expiry that is not in the future, [LinkParseResult.Reason.BAD_PARAMETERS] for anything else.
     */
    data class Invalid(val reason: LinkParseResult.Reason) : StartOutcome

    /**
     * Android threw when the service was asked, because the host app was not visible. Not a
     * reliable test the other way round: for a short time after the app left the foreground a start
     * may still return [Started] and then fail ([RecorderEvent.StartFailed]). Start only from a
     * visible activity, right after the user's tap.
     */
    data object NotVisible : StartOutcome

    /**
     * Nothing was started: the local database could not be read (only [Routelog.startWithUpload]),
     * or the recording service was not found (a broken manifest merge). Trying again later may
     * work in the first case.
     */
    data object Failed : StartOutcome
}
