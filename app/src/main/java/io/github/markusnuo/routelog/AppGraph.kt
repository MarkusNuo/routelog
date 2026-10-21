package io.github.markusnuo.routelog

import android.content.Context
import io.github.markusnuo.routelog.backup.BackupConsent
import io.github.markusnuo.routelog.link.TrustedHosts
import io.github.markusnuo.routelog.recording.BatteryOptimization

/**
 * Process-wide objects of the app: the library's (one [RoutelogCore] for activity, service,
 * receiver and jobs) and what only the app has (backup question, trusted servers, battery hint).
 */
class AppGraph private constructor(context: Context) {
    private val core = RoutelogCore.get(context)
    val database = core.database
    val notifications = core.notifications
    val exporter = core.exporter
    val uploadSettings = core.uploadSettings
    val uploadScheduler = core.uploadScheduler
    val tokens = core.tokens
    val uploads = core.uploads
    val recorder = core.recorder
    val backupConsent = BackupConsent(context)
    val battery = BatteryOptimization(context)
    val trustedHosts = TrustedHosts(context)

    companion object {
        @Volatile
        private var instance: AppGraph? = null

        fun get(context: Context): AppGraph =
            instance ?: synchronized(this) {
                instance ?: AppGraph(context.applicationContext).also { instance = it }
            }
    }
}
