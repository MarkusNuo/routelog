package io.github.markusnuo.routelog.upload

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.os.PersistableBundle
import android.util.Log
import io.github.markusnuo.routelog.RoutelogConfig
import java.util.concurrent.TimeUnit

/**
 * Android's JobScheduler runs the uploads, without a scheduling library. Jobs survive a restart
 * and only carry the recording id; the grant stays in the token store.
 *
 * JobScheduler can refuse (too many jobs) or throw; none of that may reach a recording or the
 * list. A job that could not be scheduled is planned again by the next maintenance, which runs at
 * every app start and daily.
 */
class UploadScheduler(private val context: Context, private val config: RoutelogConfig) : UploadJobs {
    private val jobs = context.getSystemService(JobScheduler::class.java)
    private val service = ComponentName(context, UploadJobService::class.java)

    /** Upload as soon as there is any network (mobile data too), retrying with growing pauses. */
    override fun scheduleUpload(recordingId: Long) {
        schedule(
            "upload",
            JobInfo.Builder(config.uploadJobId(recordingId), service)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setBackoffCriteria(FIRST_RETRY_MILLIS, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .setPersisted(true)
                .setExtras(extras(KIND_UPLOAD, recordingId))
                .build(),
        )
    }

    /**
     * Ends the grant when it runs out, even without network, so the user hears about it. A grant
     * that runs out far away is checked again after [MAX_EXPIRY_LATENCY_MILLIS]: the job then finds
     * it not due yet and schedules itself again.
     */
    override fun scheduleExpiry(recordingId: Long, expiresAtMillis: Long, nowMillis: Long) {
        schedule(
            "expiry",
            JobInfo.Builder(config.expiryJobId(recordingId), service)
                .setMinimumLatency((expiresAtMillis - nowMillis).coerceIn(0, MAX_EXPIRY_LATENCY_MILLIS))
                .setPersisted(true)
                .setExtras(extras(KIND_EXPIRY, recordingId))
                .build(),
        )
    }

    /** Daily [UploadManager.maintenance]: expired grants, routes past the keep time, lost jobs. */
    override fun scheduleMaintenance() {
        if (guarded("maintenance check", false) { isOurs(jobs.getPendingJob(config.maintenanceJobId())) }) return
        schedule(
            "maintenance",
            JobInfo.Builder(config.maintenanceJobId(), service)
                .setPeriodic(TimeUnit.DAYS.toMillis(1))
                .setPersisted(true)
                .setExtras(extras(KIND_MAINTENANCE, 0))
                .build(),
        )
    }

    override fun isUploadScheduled(recordingId: Long) =
        guarded("upload check", false) { isOurs(jobs.getPendingJob(config.uploadJobId(recordingId))) }

    override fun isExpiryScheduled(recordingId: Long) =
        guarded("expiry check", false) { isOurs(jobs.getPendingJob(config.expiryJobId(recordingId))) }

    override fun cancelUpload(recordingId: Long) = guarded("upload cancel", Unit) { jobs.cancel(config.uploadJobId(recordingId)) }

    override fun cancelExpiry(recordingId: Long) = guarded("expiry cancel", Unit) { jobs.cancel(config.expiryJobId(recordingId)) }

    // Job ids are shared with everything else the host app schedules; a job with our id that runs
    // another service is not ours (RoutelogConfig.jobIdBase keeps them apart).
    private fun isOurs(job: JobInfo?): Boolean {
        if (job == null) return false
        if (job.service == service) return true
        // Only the class name of the other service: it says whose job it is, nothing about a recording.
        Log.w(TAG, "A job with one of our ids belongs to another service: ${job.service.className}")
        return false
    }

    private fun schedule(what: String, info: JobInfo) {
        val result = guarded("$what schedule", JobScheduler.RESULT_FAILURE) { jobs.schedule(info) }
        if (result != JobScheduler.RESULT_SUCCESS) Log.w(TAG, "The $what job was not scheduled (result $result)")
    }

    private inline fun <T> guarded(what: String, fallback: T, call: () -> T): T = try {
        call()
    } catch (e: RuntimeException) {
        // IllegalStateException when the app has too many jobs, SecurityException, and the like.
        Log.w(TAG, "JobScheduler failed for $what: ${e.javaClass.simpleName}")
        fallback
    }

    private fun extras(kind: Int, recordingId: Long) = PersistableBundle().apply {
        putInt(EXTRA_KIND, kind)
        putLong(EXTRA_RECORDING_ID, recordingId)
    }

    companion object {
        private const val TAG = "UploadScheduler"
        const val EXTRA_KIND = "kind"
        const val EXTRA_RECORDING_ID = "recording_id"
        const val KIND_UPLOAD = 1
        const val KIND_EXPIRY = 2
        const val KIND_MAINTENANCE = 3
        private val FIRST_RETRY_MILLIS = TimeUnit.SECONDS.toMillis(30)
        private val MAX_EXPIRY_LATENCY_MILLIS = TimeUnit.DAYS.toMillis(30)
    }
}
