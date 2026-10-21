package io.github.markusnuo.routelog

import android.content.Context
import android.content.Intent
import io.github.markusnuo.routelog.data.RoutelogDatabase
import io.github.markusnuo.routelog.export.GpxExporter
import io.github.markusnuo.routelog.recording.Recorder
import io.github.markusnuo.routelog.recording.RecordingNotifications
import io.github.markusnuo.routelog.recording.RoutelogResumeActivity
import io.github.markusnuo.routelog.upload.TokenStore
import io.github.markusnuo.routelog.upload.UploadManager
import io.github.markusnuo.routelog.upload.UploadScheduler
import io.github.markusnuo.routelog.upload.UploadSettings
import io.github.markusnuo.routelog.upload.UploadWorker

/**
 * The library's process-wide objects. The recording service, the upload jobs, the boot receiver and
 * the app that hosts the library all share the one [Recorder] and database. Hosts use [Routelog].
 */
class RoutelogCore private constructor(context: Context, val config: RoutelogConfig) {
    val database = RoutelogDatabase.get(context)
    val notifications = RecordingNotifications(context, config).also { it.createChannels() }
    val exporter = GpxExporter(context, database.recordingDao())
    val uploadSettings = UploadSettings(context, config.settingsName)
    val uploadScheduler = UploadScheduler(context, config)
    val tokens = TokenStore(context, config.tokenFileName)
    val uploads = UploadManager(database, exporter, uploadScheduler, notifications, tokens, retention = { uploadSettings.retention })
    val recorder = Recorder(context, database, listener = uploads, tokens = tokens)

    init {
        // Whatever app hosts the library, and whether or not it shows a screen: the daily
        // maintenance (expired grants, routes past their keep time, uploads whose job was lost)
        // runs and is scheduled. On the thread of the upload jobs, so it never runs in parallel
        // with one. Recordings a dead process left running are NOT swept here: the boot receiver
        // and the restarted service sweep them and show "recording interrupted"; a sweep here
        // would get there first and the notice would never come.
        UploadWorker.execute { uploads.maintenance() }
    }

    companion object {
        @Volatile
        private var instance: RoutelogCore? = null

        @Volatile
        private var configured: RoutelogConfig? = null

        fun get(context: Context): RoutelogCore =
            instance ?: synchronized(this) {
                instance ?: RoutelogCore(context.applicationContext, configured ?: RoutelogConfig()).also { instance = it }
            }

        /** See [Routelog.configure]. */
        internal fun configure(config: RoutelogConfig) {
            synchronized(this) {
                check(instance == null || instance?.config == config) {
                    "Routelog.configure must be called before the library is used (in Application.onCreate)"
                }
                configured = config
            }
        }

        /** What a tap on the recording or an upload notification opens. */
        internal fun openAppIntent(context: Context): Intent =
            get(context).config.openAppIntent?.invoke(context) ?: launchIntent(context)

        /** What a tap on "recording interrupted" opens to continue [recordingId]. */
        internal fun resumeIntent(context: Context, recordingId: Long): Intent =
            get(context).config.resumeIntent?.invoke(context, recordingId) ?: RoutelogResumeActivity.intent(context, recordingId)

        internal fun launchIntent(context: Context): Intent {
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
                ?: Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(context.packageName)
            return launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
    }
}
