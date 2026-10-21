package io.github.markusnuo.routelog

import android.content.Context
import android.content.Intent

/**
 * How the library fits into the app that hosts it. Pass it to [Routelog.configure] once, in
 * `Application.onCreate`, before anything of the library runs (a boot or a restart of the recording
 * service can start the process). Without it the defaults apply.
 *
 * Notification ids, channel ids, JobScheduler job ids and the settings and token files are shared
 * with everything else the host app does. The defaults are chosen not to clash with what a host is
 * likely to use; the routelog app keeps its older values ([legacy]) so that the channel settings
 * of its users and the jobs already scheduled on their phones stay as they are.
 */
data class RoutelogConfig(
    /** Put in front of the channel ids `recording`, `interruptions` and `uploads`. */
    val channelIdPrefix: String = "routelog_",
    /** Recording notification: base + 1; "recording interrupted": base + 2; upload problems: base + 100 + recording id. */
    val notificationIdBase: Int = DEFAULT_ID_BASE,
    /**
     * Daily maintenance job: base + 1; upload and expiry of a recording: base + 16 + 2 × id and
     * base + 17 + 2 × id. A host that uses WorkManager should keep its job id range below this
     * (`Configuration.Builder.setJobSchedulerJobIdRange`).
     */
    val jobIdBase: Int = DEFAULT_ID_BASE,
    /** Name of the shared-preferences file of the library's settings (upload retention). */
    val settingsName: String = "routelog_settings",
    /**
     * Name of the file in `noBackupFilesDir` that holds the upload tokens (`<name>.properties`). The
     * token store also moves tokens out of a shared-preferences file of this name once and deletes
     * that file, so the name must be one only the library uses.
     */
    val tokenFileName: String = "routelog_upload_tokens",
    /**
     * What a tap on the recording or an upload notification opens, and what the library's resume
     * target opens after it; null: the host's launcher activity.
     */
    val openAppIntent: ((Context) -> Intent)? = null,
    /**
     * What a tap on "recording interrupted" opens to continue the recording. Null: the library's
     * own [io.github.markusnuo.routelog.recording.RoutelogResumeActivity], which resumes and then
     * opens the host app. If a host sets its own, it must be an activity no other app can start,
     * and it calls [Routelog.resume] once it is visible (resumed).
     */
    val resumeIntent: ((context: Context, recordingId: Long) -> Intent)? = null,
) {
    fun recordingNotificationId() = notificationIdBase + 1

    fun interruptedNotificationId() = notificationIdBase + 2

    fun uploadProblemNotificationId(recordingId: Long) = (notificationIdBase + 100 + recordingId).toInt()

    fun maintenanceJobId() = jobIdBase + 1

    fun uploadJobId(recordingId: Long) = (jobIdBase + 16 + recordingId * 2).toInt()

    fun expiryJobId(recordingId: Long) = (jobIdBase + 17 + recordingId * 2).toInt()

    companion object {
        /** "RL" in the upper half: far above the small numbers apps and WorkManager start with. */
        const val DEFAULT_ID_BASE = 0x524C_0000

        /** The values the routelog app used before the library existed. */
        fun legacy(
            openAppIntent: ((Context) -> Intent)? = null,
            resumeIntent: ((context: Context, recordingId: Long) -> Intent)? = null,
        ) = RoutelogConfig(
            channelIdPrefix = "",
            notificationIdBase = 0,
            jobIdBase = 0,
            settingsName = "settings",
            tokenFileName = "upload_tokens",
            openAppIntent = openAppIntent,
            resumeIntent = resumeIntent,
        )
    }
}
