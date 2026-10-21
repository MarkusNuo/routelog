package io.github.markusnuo.routelog.upload

import android.database.SQLException
import android.util.Log
import io.github.markusnuo.routelog.data.RecordingState
import io.github.markusnuo.routelog.data.RoutelogDatabase
import io.github.markusnuo.routelog.data.UploadEntity
import io.github.markusnuo.routelog.data.UploadState
import io.github.markusnuo.routelog.export.GpxExporter
import io.github.markusnuo.routelog.recording.Recorder
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap

/**
 * Uploads of recordings started with an upload target (docs/EXTERNAL_RECORDING.md). Every state
 * change is a conditional update in the database, so the recorder thread, the job thread and the
 * UI can't end an upload twice or revive one that has ended. Never logs a URL, token or position.
 *
 * Everything except [onLinkedRecordingCreated], [onUploadQueued], [forget] and [cancelInFlight]
 * runs on [UploadWorker].
 */
class UploadManager(
    private val database: RoutelogDatabase,
    private val exporter: GpxExporter,
    private val scheduler: UploadJobs,
    private val notifications: UploadNotices,
    private val tokens: TokenStore,
    private val retention: () -> UploadRetention,
    private val uploader: Uploader = Uploader(),
    private val clock: () -> Long = System::currentTimeMillis,
) : Recorder.Listener {
    private val uploads = database.uploadDao()
    private val recordings = database.recordingDao()
    private val grants = database.grantDao()

    // The upload attempt that is on the wire per recording, so a delete or a stopped job can end it.
    private val inFlight = ConcurrentHashMap<Long, UploadCall>()

    // Recorder thread: the recording, its upload row and its token were just written together.
    override fun onLinkedRecordingCreated(recordingId: Long, expiresAtMillis: Long) {
        scheduler.scheduleExpiry(recordingId, expiresAtMillis, clock())
    }

    // Recorder thread: the recording was stopped (or finished from the list) and its upload is now PENDING.
    override fun onUploadQueued(recordingId: Long) {
        scheduler.scheduleUpload(recordingId)
    }

    /** One upload attempt (job thread). Returns true if it should be tried again later. */
    fun runUpload(recordingId: Long): Boolean {
        val upload = try {
            uploads.upload(recordingId)
        } catch (e: SQLException) {
            Log.e(TAG, "Upload could not be read: ${e.javaClass.simpleName}")
            return true
        }
        if (upload == null || upload.state != UploadState.PENDING) return false
        if (tokens.get(recordingId) == null) {
            end(upload, UploadState.NO_GRANT, httpStatus = null)
            return false
        }
        if (UploadPolicy.isExpired(upload.expiresAtMillis, clock())) {
            end(upload, UploadState.EXPIRED, httpStatus = null)
            return false
        }
        val recording = recordings.recording(recordingId) ?: return false
        if (recording.pointCount == 0) {
            end(upload, UploadState.NOTHING_TO_UPLOAD, httpStatus = null)
            return false
        }

        val body = ByteArrayOutputStream().also { bytes ->
            bytes.bufferedWriter(Charsets.UTF_8).use { exporter.writeGpx(recording, upload.label, it) }
        }.toByteArray()

        // Reading the points took a while: the recording may have been deleted meanwhile, or the
        // upload ended some other way. Only a still pending upload with a token is sent.
        val current = uploads.upload(recordingId)
        if (current == null || current.state != UploadState.PENDING) return false
        val token = tokens.get(recordingId)
        if (token == null) {
            end(current, UploadState.NO_GRANT, httpStatus = null)
            return false
        }

        val call = UploadCall()
        inFlight[recordingId] = call
        val status = try {
            uploader.put(current.uploadUrl, token, body, call)
        } finally {
            inFlight.remove(recordingId, call)
        }
        // Deleted or stopped while on the wire: not an answer of the server, and not an attempt.
        if (call.isCancelled) return true
        Log.i(TAG, "Upload attempt ${current.attempts + 1}: ${status ?: "no answer"}")
        uploads.recordAttempt(recordingId, status)

        return when (UploadPolicy.classify(status)) {
            UploadOutcome.DONE -> {
                end(current, UploadState.DONE, status)
                applyRetention()
                false
            }
            UploadOutcome.REJECTED -> end(current, UploadState.REJECTED, status).let { false }
            UploadOutcome.FAILED -> end(current, UploadState.FAILED, status).let { false }
            UploadOutcome.RETRY -> if (UploadPolicy.isExpired(current.expiresAtMillis, clock())) {
                end(current, UploadState.EXPIRED, status)
                false
            } else {
                true
            }
        }
    }

    /** The expiry job: end the grant if the upload is still open and its time is up, else plan the check again. */
    fun expireIfDue(recordingId: Long) {
        val upload = uploads.upload(recordingId) ?: return
        if (!isOpen(upload)) return
        val now = clock()
        if (UploadPolicy.isExpired(upload.expiresAtMillis, now)) {
            end(upload, UploadState.EXPIRED, httpStatus = null)
        } else {
            // The job ran early (the clock was set back, or the grant lies beyond the longest wait).
            scheduler.scheduleExpiry(recordingId, upload.expiresAtMillis, now)
        }
    }

    /**
     * Daily and at app start: end expired grants and uploads without a token, delete the routes of
     * uploaded recordings past the keep time, and make sure every open upload still has its jobs
     * (e.g. after a restore from backup, or a job that JobScheduler refused earlier).
     */
    fun maintenance() {
        try {
            val now = clock()
            grants.deleteExpired(now)
            for (upload in uploads.open()) {
                when {
                    UploadPolicy.isExpired(upload.expiresAtMillis, now) -> end(upload, UploadState.EXPIRED, httpStatus = null)
                    tokens.get(upload.recordingId) == null -> end(upload, UploadState.NO_GRANT, httpStatus = null)
                    else -> planJobs(upload, now)
                }
            }
            applyRetention()
        } catch (e: SQLException) {
            Log.e(TAG, "Upload maintenance failed: ${e.javaClass.simpleName}")
        }
        scheduler.scheduleMaintenance()
    }

    private fun planJobs(upload: UploadEntity, now: Long) {
        val id = upload.recordingId
        if (!scheduler.isExpiryScheduled(id)) scheduler.scheduleExpiry(id, upload.expiresAtMillis, now)
        var pending = upload.state == UploadState.PENDING
        // A waiting upload of a finished recording was never queued (the stop writes both in one
        // transaction, so this only catches what an earlier version left behind): queue it now.
        if (!pending && recordings.recording(id)?.state == RecordingState.FINISHED) {
            pending = uploads.markPending(id) == 1
        }
        if (pending && !scheduler.isUploadScheduled(id)) scheduler.scheduleUpload(id)
    }

    /**
     * Deletes the route (the points) of uploaded recordings that are past the keep time chosen in
     * the settings. The entry itself, with date, duration and distance, stays until the user deletes it.
     */
    fun applyRetention() {
        val cutoff = retention().cutoff(clock()) ?: return
        val removed = ArrayList<Long>()
        try {
            for (id in uploads.doneBefore(cutoff)) {
                val done = database.runInTransaction(
                    Callable {
                        recordings.deletePoints(id)
                        uploads.markRouteDeleted(id, clock()) == 1
                    },
                )
                if (done) removed += id
            }
        } catch (e: SQLException) {
            Log.w(TAG, "Cleanup of uploaded routes failed: ${e.javaClass.simpleName}")
        }
        if (removed.isEmpty()) return
        try {
            // As after a manual delete: no copy of the route stays in the log.
            database.checkpoint()
        } catch (e: SQLException) {
            Log.w(TAG, "Checkpoint after cleanup failed: ${e.javaClass.simpleName}")
        }
        // An export file that holds one of these routes goes too; one of another recording stays.
        runBlocking { exporter.clearIfFor(removed) }
    }

    /** The user deleted the recording: nothing left to upload or expire. */
    fun forget(recordingId: Long) {
        tokens.remove(recordingId)
        inFlight[recordingId]?.cancel()
        scheduler.cancelUpload(recordingId)
        scheduler.cancelExpiry(recordingId)
        notifications.cancelUploadProblem(recordingId)
    }

    /** The system stopped the upload job: don't keep the worker waiting for the network. */
    fun cancelInFlight(recordingId: Long) {
        inFlight[recordingId]?.cancel()
    }

    private fun isOpen(upload: UploadEntity) = upload.state == UploadState.WAITING || upload.state == UploadState.PENDING

    /** Ends an open upload and forgets the token; reports a problem only once. */
    private fun end(upload: UploadEntity, state: UploadState, httpStatus: Int?) {
        val ended = try {
            uploads.finish(upload.recordingId, state, httpStatus, clock()) == 1
        } catch (e: SQLException) {
            Log.e(TAG, "Upload could not be ended: ${e.javaClass.simpleName}")
            false
        }
        if (!ended) return
        tokens.remove(upload.recordingId)
        scheduler.cancelExpiry(upload.recordingId)
        // From the expiry or maintenance job; within the upload job itself returning false ends it.
        if (state == UploadState.EXPIRED || state == UploadState.NO_GRANT) scheduler.cancelUpload(upload.recordingId)
        if (state != UploadState.DONE) notifications.showUploadProblem(upload.recordingId, upload.host, state, httpStatus)
    }

    private companion object {
        const val TAG = "Uploads"
    }
}

/** The jobs [UploadManager] needs; [UploadScheduler] runs them with Android's JobScheduler. */
interface UploadJobs {
    fun scheduleUpload(recordingId: Long)
    fun scheduleExpiry(recordingId: Long, expiresAtMillis: Long, nowMillis: Long)
    fun scheduleMaintenance()
    fun isUploadScheduled(recordingId: Long): Boolean
    fun isExpiryScheduled(recordingId: Long): Boolean
    fun cancelUpload(recordingId: Long)
    fun cancelExpiry(recordingId: Long)
}

/** How the user hears about uploads that ended without success. */
interface UploadNotices {
    fun showUploadProblem(recordingId: Long, host: String, state: UploadState, httpStatus: Int?)
    fun cancelUploadProblem(recordingId: Long)
}
