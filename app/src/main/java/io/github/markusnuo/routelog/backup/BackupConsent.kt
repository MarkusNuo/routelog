package io.github.markusnuo.routelog.backup

import android.app.backup.BackupManager
import android.content.Context
import androidx.core.content.edit

/** The user's answer to "Back up recordings?"; null until they answered. */
class BackupConsent(private val context: Context) {
    // Opened on first use, not when the app starts: before the first unlock after a restart, app
    // settings can't be read and the app process would crash.
    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    fun get(): Boolean? = if (prefs.contains(KEY)) prefs.getBoolean(KEY, false) else null

    fun set(enabled: Boolean) {
        prefs.edit(commit = true) { putBoolean(KEY, enabled) }
        // Only a hint to the backup manager that the app's data changed. Full backups run on
        // Android's own schedule; the backup agent reads this answer when one does.
        BackupManager(context).dataChanged()
    }

    private companion object {
        const val PREFS = "settings"
        const val KEY = "backup_consent"
    }
}
