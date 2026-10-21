package io.github.markusnuo.routelog.recording

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.markusnuo.routelog.RoutelogConfig
import io.github.markusnuo.routelog.RoutelogCore
import io.github.markusnuo.routelog.data.RecordingEntity
import io.github.markusnuo.routelog.data.UploadState
import io.github.markusnuo.routelog.format.formatDateTime
import io.github.markusnuo.routelog.library.R
import io.github.markusnuo.routelog.upload.UploadNotices

class RecordingNotifications(private val context: Context, private val config: RoutelogConfig) : UploadNotices {
    // Channel ids with the host-specific prefix (RoutelogConfig.channelIdPrefix).
    val channelRecording = config.channelIdPrefix + "recording"
    val channelInterruptions = config.channelIdPrefix + "interruptions"
    val channelUploads = config.channelIdPrefix + "uploads"
    val recordingNotificationId = config.recordingNotificationId()
    val interruptedNotificationId = config.interruptedNotificationId()

    private val manager = NotificationManagerCompat.from(context)

    fun createChannels() {
        manager.createNotificationChannelsCompat(
            listOf(
                // Default importance, not low: Android hides "silent" notifications on the lock
                // screen, and this one must be visible there. No sound, vibration or light, so it
                // stays unobtrusive. (A channel's importance can't change once it exists.)
                NotificationChannelCompat.Builder(channelRecording, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                    .setName(context.getString(R.string.routelog_channel_recording_name))
                    .setDescription(context.getString(R.string.routelog_channel_recording_description))
                    .setShowBadge(false)
                    .setSound(null, null)
                    .setVibrationEnabled(false)
                    .setLightsEnabled(false)
                    .build(),
                NotificationChannelCompat.Builder(channelInterruptions, NotificationManagerCompat.IMPORTANCE_HIGH)
                    .setName(context.getString(R.string.routelog_channel_interruptions_name))
                    .setDescription(context.getString(R.string.routelog_channel_interruptions_description))
                    .build(),
                NotificationChannelCompat.Builder(channelUploads, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                    .setName(context.getString(R.string.routelog_channel_uploads_name))
                    .setDescription(context.getString(R.string.routelog_channel_uploads_description))
                    .build(),
            ),
        )
    }

    /** Whether the recording notification would actually be shown (permission, app and channel). */
    fun recordingNotificationEnabled(): Boolean {
        if (!manager.areNotificationsEnabled()) return false
        val channel = manager.getNotificationChannelCompat(channelRecording) ?: return true
        return channel.importance != NotificationManagerCompat.IMPORTANCE_NONE
    }

    fun recording(state: RecorderState): Notification {
        val text = when (state) {
            is RecorderState.Recording -> context.getString(
                R.string.routelog_notification_recording_text,
                context.getString(R.string.routelog_distance_km, state.distanceMeters / 1000),
                context.getString(statusText(state)),
            )
            else -> context.getString(R.string.routelog_status_starting)
        }
        val builder = NotificationCompat.Builder(context, channelRecording)
            .setSmallIcon(R.drawable.routelog_ic_stat_recording)
            .setContentTitle(context.getString(R.string.routelog_notification_recording_title))
            .setContentText(text)
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            // Shows that a route is being recorded, never where.
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(
                NotificationCompat.Action.Builder(
                    0,
                    context.getString(R.string.routelog_notification_action_stop),
                    PendingIntent.getService(
                        context,
                        REQUEST_STOP,
                        RecordingService.stopIntent(context),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    ),
                )
                    // Stop works from the lock screen without unlocking.
                    .setAuthenticationRequired(false)
                    .build(),
            )
        if (state is RecorderState.Recording) {
            builder.setWhen(state.startedAtMillis).setShowWhen(true).setUsesChronometer(true)
        }
        return builder.build()
    }

    fun updateRecording(state: RecorderState) = notify(recordingNotificationId, recording(state))

    fun cancelRecording() = manager.cancel(recordingNotificationId)

    /** Whether the recording notification is still there: Android 14+ lets the user swipe it away. */
    fun recordingNotificationShown(): Boolean =
        manager.activeNotifications.any { it.id == recordingNotificationId }

    fun showInterrupted(recording: RecordingEntity) {
        // After 12 hours the recording can't be resumed anymore. A notice that says "tap to resume"
        // would be wrong, and it would still sound: nothing to tell then.
        val now = System.currentTimeMillis()
        if (!ResumeWindow.isOpen(recording, now)) return
        // What the lock screen shows: that a recording was interrupted, not when the route was driven.
        val lockScreenVersion = NotificationCompat.Builder(context, channelInterruptions)
            .setSmallIcon(R.drawable.routelog_ic_stat_recording)
            .setContentTitle(context.getString(R.string.routelog_notification_interrupted_title))
            .build()
        val notification = NotificationCompat.Builder(context, channelInterruptions)
            .setSmallIcon(R.drawable.routelog_ic_stat_recording)
            .setContentTitle(context.getString(R.string.routelog_notification_interrupted_title))
            .setContentText(
                context.getString(R.string.routelog_notification_interrupted_text, formatDateTime(recording.startedAtMillis)),
            )
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    REQUEST_RESUME,
                    RoutelogCore.resumeIntent(context, recording.id),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .setAutoCancel(true)
            // The notice goes away when the window closes (at least 1 ms: 0 would mean no timeout).
            .setTimeoutAfter(ResumeWindow.remainingMillis(recording, now).coerceAtLeast(1))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(lockScreenVersion)
            .build()
        notify(interruptedNotificationId, notification)
    }

    fun cancelInterrupted() = manager.cancel(interruptedNotificationId)

    /**
     * An upload ended without success; a success only shows in the list. The route stays on the
     * phone; the text says so. [httpStatus] is shown for refusals and failures.
     */
    override fun showUploadProblem(recordingId: Long, host: String, state: UploadState, httpStatus: Int?) {
        val (title, text) = when (state) {
            UploadState.EXPIRED ->
                R.string.routelog_notification_upload_expired_title to context.getString(R.string.routelog_notification_upload_expired_text, host)
            UploadState.REJECTED ->
                R.string.routelog_notification_upload_rejected_title to
                    context.getString(R.string.routelog_notification_upload_rejected_text, host, httpStatus ?: 0)
            UploadState.FAILED ->
                R.string.routelog_notification_upload_failed_title to
                    context.getString(R.string.routelog_notification_upload_failed_text, host, httpStatus ?: 0)
            UploadState.NOTHING_TO_UPLOAD ->
                R.string.routelog_notification_upload_empty_title to context.getString(R.string.routelog_notification_upload_empty_text, host)
            UploadState.NO_GRANT ->
                R.string.routelog_notification_upload_no_grant_title to context.getString(R.string.routelog_notification_upload_no_grant_text, host)
            UploadState.WAITING, UploadState.PENDING, UploadState.DONE -> return
        }
        // The lock screen shows that something went wrong, not where to.
        val lockScreenVersion = NotificationCompat.Builder(context, channelUploads)
            .setSmallIcon(R.drawable.routelog_ic_stat_recording)
            .setContentTitle(context.getString(title))
            .build()
        val notification = NotificationCompat.Builder(context, channelUploads)
            .setSmallIcon(R.drawable.routelog_ic_stat_recording)
            .setContentTitle(context.getString(title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(lockScreenVersion)
            .build()
        notify(config.uploadProblemNotificationId(recordingId), notification)
    }

    override fun cancelUploadProblem(recordingId: Long) {
        manager.cancel(config.uploadProblemNotificationId(recordingId))
    }

    private fun statusText(state: RecorderState.Recording): Int = when {
        state.storageError -> R.string.routelog_storage_error
        state.gps is GpsStatus.Off -> R.string.routelog_gps_off
        state.gps is GpsStatus.Searching -> R.string.routelog_gps_searching
        else -> R.string.routelog_gps_fix
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        context,
        REQUEST_OPEN,
        RoutelogCore.openAppIntent(context),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun notify(id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        try {
            manager.notify(id, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "Notification not shown: ${e.javaClass.simpleName}")
        }
    }

    companion object {
        private const val TAG = "RecordingNotifications"
        private const val REQUEST_OPEN = 1
        private const val REQUEST_STOP = 2
        private const val REQUEST_RESUME = 3
    }
}
