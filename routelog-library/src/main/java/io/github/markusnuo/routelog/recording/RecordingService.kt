package io.github.markusnuo.routelog.recording

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import io.github.markusnuo.routelog.RoutelogCore
import io.github.markusnuo.routelog.link.RecordingLink
import io.github.markusnuo.routelog.recording.Recorder.StartResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Location foreground service that keeps the recording alive with the screen off. Only started
 * by a user action while the app is visible (Android 14 requires that for location); the GPS work
 * itself happens in [Recorder].
 */
class RecordingService : Service() {
    private lateinit var recorder: Recorder
    private lateinit var notifications: RecordingNotifications
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var inForeground = false
    private var stopRequested = false

    // Start commands whose result has not come back from the recorder yet. Main thread only.
    private var startsInFlight = 0

    // The id of the command that arrived last (main thread only): what leave() stops the service with.
    private var lastStartId = 0

    override fun onCreate() {
        super.onCreate()
        running = true
        val graph = RoutelogCore.get(this)
        recorder = graph.recorder
        notifications = graph.notifications
        scope.launch {
            // The visible text changes far less often than the state (every fix); only redraw
            // the notification when it does. Only a running recording is drawn: while stopping,
            // the state passes through Idle, and drawing that would post "Starting…" again after
            // the notification was removed. The recording's id is part of the key, so a second
            // recording draws even if it looks like the first and the collector never saw the Idle
            // in between (the state flow conflates it): the notification must show its start time.
            recorder.state
                .map { NotificationKey.of(it) }
                .distinctUntilChanged()
                .collect { key -> if (key != null) showRecording() }
        }
        scope.launch {
            // From Android 14 on, the user can swipe the foreground service's notification away.
            // It is mandatory while recording, so put it back.
            while (true) {
                delay(NOTIFICATION_CHECK_MILLIS)
                if (inForeground && !stopRequested && !notifications.recordingNotificationShown()) showRecording()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        when (intent?.action) {
            ACTION_START -> beginRecording { recorder.start(onResult = it) }
            ACTION_START_LINKED -> {
                val link = linkOf(intent)
                if (link == null) {
                    // Nothing to start: give back the claim Routelog made, or leave() would wait for it.
                    releaseClaim()
                    leave()
                } else {
                    beginRecording { recorder.start(link, it) }
                }
            }
            ACTION_RESUME -> {
                val id = intent.getLongExtra(EXTRA_RECORDING_ID, -1)
                notifications.cancelInterrupted()
                beginRecording { recorder.resume(id, it) }
            }
            ACTION_STOP -> {
                stopRequested = true
                recorder.stop { leave() }
            }
            null -> {
                // The system restarted the service after the app's process died while recording.
                // GPS can't be restarted from the background without background location, so
                // tell the user and let them resume with a tap.
                recorder.sweepOrphans { orphans ->
                    orphans.maxByOrNull { it.startedAtMillis }?.let(notifications::showInterrupted)
                    if (!inForeground) stopSelfResult(startId)
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        scope.cancel()
        val state = recorder.state.value
        // A start that is still on its way through the recorder counts too: the state may say Idle
        // while it is carried out, and its recording would then run without the service.
        if (!stopRequested && (startsInFlight > 0 || state is RecorderState.Recording || state is RecorderState.Starting)) {
            // Destroyed without a stop request: GPS would keep running without a foreground
            // service. End it and offer to resume.
            recorder.interrupt { recording -> recording?.let(notifications::showInterrupted) }
        }
        super.onDestroy()
    }

    private fun beginRecording(start: ((StartResult) -> Unit) -> Unit) {
        stopRequested = false
        if (!enterForeground()) {
            // The start was claimed (Starting) before this command; with nothing else under way the
            // state must go back to Idle, or leave() would wait for it and no start could follow.
            // Before the event: whoever hears it may try again at once.
            releaseClaim()
            recorder.reportStartFailed()
            // Not stopSelfResult: a recording that runs, or a start that is still on its way, needs the service.
            leave()
            return
        }
        startsInFlight++
        start { result ->
            startsInFlight--
            // ALREADY_RECORDING: the recording that runs keeps the service; leave() would not end it anyway.
            if (result != StartResult.STARTED && result != StartResult.ALREADY_RECORDING) leave()
        }
    }

    // A start that is still on its way through the recorder owns the state Starting: leave it alone.
    private fun releaseClaim() {
        if (startsInFlight == 0) recorder.releaseStartClaim()
    }

    private fun enterForeground(): Boolean = try {
        ServiceCompat.startForeground(
            this,
            notifications.recordingNotificationId,
            notifications.recording(recorder.state.value),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0,
        )
        inForeground = true
        true
    } catch (e: Exception) {
        // SecurityException without location permission; ForegroundServiceStartNotAllowedException
        // when not started from the visible app.
        Log.w(TAG, "Could not enter the foreground: ${e.javaClass.simpleName}")
        false
    }

    /** Draws the notification of the running recording, unless the service is on its way out. */
    private fun showRecording() {
        if (!inForeground || stopRequested) return
        (recorder.state.value as? RecorderState.Recording)?.let(notifications::updateRecording)
    }

    /**
     * Ends the foreground state and the service after a stop or a failed start, unless a recording
     * runs or a start is still on its way through the recorder, which reports Idle in between: its
     * recording will need the foreground service. Harmless if the service is not in the foreground
     * (nothing to stop, no notification to cancel).
     *
     * The service ends with the id of the latest command, not of the one that led here: a command
     * that is still queued keeps it alive, but a later command that was already answered (an
     * immediate failure that had to leave this to the stop or start before it) does not.
     */
    private fun leave() {
        val state = recorder.state.value
        if (startsInFlight > 0 || state is RecorderState.Recording || state is RecorderState.Starting) return
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        notifications.cancelRecording()
        inForeground = false
        stopSelfResult(lastStartId)
    }

    /** The parts of a running recording that the notification shows; null if nothing is recording. */
    private data class NotificationKey(val recordingId: Long, val tenthKm: Long, val text: Int) {
        companion object {
            fun of(state: RecorderState): NotificationKey? = (state as? RecorderState.Recording)?.let {
                NotificationKey(
                    recordingId = it.recordingId,
                    tenthKm = Math.round(it.distanceMeters / 100),
                    text = when {
                        it.storageError -> 0
                        it.gps is GpsStatus.Off -> 1
                        it.gps is GpsStatus.Searching -> 2
                        else -> 3
                    },
                )
            }
        }
    }

    companion object {
        private const val TAG = "RecordingService"
        private const val NOTIFICATION_CHECK_MILLIS = 5_000L
        private const val ACTION_START = "io.github.markusnuo.routelog.action.START"
        private const val ACTION_RESUME = "io.github.markusnuo.routelog.action.RESUME"
        private const val ACTION_STOP = "io.github.markusnuo.routelog.action.STOP"
        private const val ACTION_START_LINKED = "io.github.markusnuo.routelog.action.START_LINKED"
        private const val EXTRA_RECORDING_ID = "recording_id"
        private const val EXTRA_UPLOAD_URL = "upload_url"
        private const val EXTRA_HOST = "host"
        private const val EXTRA_SECURE = "secure"
        private const val EXTRA_TOKEN = "token"
        private const val EXTRA_LABEL = "label"
        private const val EXTRA_EXPIRES = "expires"

        @Volatile
        private var running = false

        /** Whether the service exists in this process (between onCreate and onDestroy). */
        val isRunning: Boolean get() = running

        fun startIntent(context: Context) = Intent(context, RecordingService::class.java).setAction(ACTION_START)

        /**
         * Only for the app's own, non-exported service, as it carries the token. The link or upload
         * request was validated, and the user confirmed it: in the link dialog, by trusting the
         * server before, or with the host app's own start button.
         */
        fun linkedStartIntent(context: Context, link: RecordingLink) =
            Intent(context, RecordingService::class.java)
                .setAction(ACTION_START_LINKED)
                .putExtra(EXTRA_UPLOAD_URL, link.uploadUrl)
                .putExtra(EXTRA_HOST, link.host)
                .putExtra(EXTRA_SECURE, link.secure)
                .putExtra(EXTRA_TOKEN, link.token)
                .putExtra(EXTRA_LABEL, link.label)
                .putExtra(EXTRA_EXPIRES, link.expiresAtMillis)

        private fun linkOf(intent: Intent): RecordingLink? {
            val url = intent.getStringExtra(EXTRA_UPLOAD_URL) ?: return null
            val host = intent.getStringExtra(EXTRA_HOST) ?: return null
            val token = intent.getStringExtra(EXTRA_TOKEN) ?: return null
            if (!intent.hasExtra(EXTRA_EXPIRES)) return null
            return RecordingLink(
                uploadUrl = url,
                host = host,
                secure = intent.getBooleanExtra(EXTRA_SECURE, false),
                token = token,
                label = intent.getStringExtra(EXTRA_LABEL),
                expiresAtMillis = intent.getLongExtra(EXTRA_EXPIRES, 0),
            )
        }

        fun resumeIntent(context: Context, recordingId: Long) =
            Intent(context, RecordingService::class.java)
                .setAction(ACTION_RESUME)
                .putExtra(EXTRA_RECORDING_ID, recordingId)

        fun stopIntent(context: Context) = Intent(context, RecordingService::class.java).setAction(ACTION_STOP)
    }
}
