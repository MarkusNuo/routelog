package io.github.markusnuo.routelog.upload

import android.app.job.JobParameters
import android.app.job.JobService
import android.util.Log
import io.github.markusnuo.routelog.RoutelogCore

/** Runs the jobs of [UploadScheduler] on the [UploadWorker] thread, one after the other. */
class UploadJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        val uploads = RoutelogCore.get(this).uploads
        val recordingId = params.extras.getLong(UploadScheduler.EXTRA_RECORDING_ID, -1)
        val kind = params.extras.getInt(UploadScheduler.EXTRA_KIND)
        UploadWorker.execute {
            val tryAgain = try {
                run(uploads, kind, recordingId)
            } catch (e: RuntimeException) {
                // An uncaught exception here would crash the app, recording included.
                Log.e(TAG, "Upload job failed: ${e.javaClass.simpleName}")
                kind == UploadScheduler.KIND_UPLOAD
            }
            jobFinished(params, tryAgain)
        }
        return true
    }

    private fun run(uploads: UploadManager, kind: Int, recordingId: Long): Boolean = when (kind) {
        UploadScheduler.KIND_UPLOAD -> uploads.runUpload(recordingId)
        UploadScheduler.KIND_EXPIRY -> {
            uploads.expireIfDue(recordingId)
            false
        }
        UploadScheduler.KIND_MAINTENANCE -> {
            uploads.maintenance()
            false
        }
        else -> false
    }

    // Stopped early (e.g. the network went away): end the attempt that is on the wire, so the
    // worker is free again, and try again later. A second attempt after a successful one only gets
    // 409 from the server, which counts as done. Expiry and maintenance are planned again by
    // their own code (expireIfDue, the periodic job), not by a retry.
    override fun onStopJob(params: JobParameters): Boolean {
        if (params.extras.getInt(UploadScheduler.EXTRA_KIND) != UploadScheduler.KIND_UPLOAD) return false
        RoutelogCore.get(this).uploads.cancelInFlight(params.extras.getLong(UploadScheduler.EXTRA_RECORDING_ID, -1))
        return true
    }

    private companion object {
        const val TAG = "UploadJobService"
    }
}
