package io.github.markusnuo.routelog.recording

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.edit

/**
 * Battery optimisation of some manufacturers ends a recording without any notice Android would
 * allow. routelog asks once for the exemption (when the first recording starts); while it is
 * missing, the app shows a hint with a way to allow it.
 */
class BatteryOptimization(private val context: Context) {
    private val power = context.getSystemService(PowerManager::class.java)

    // Opened on first use, not at app start (before the first unlock after a restart, app storage
    // can't be read). Its own file, not the settings: the exemption belongs to this phone, so the
    // file is left out of a backup (a restored flag would keep routelog from asking on the new phone).
    private val prefs by lazy { context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE) }

    /** True if Android doesn't optimise routelog, i.e. the exemption is granted. */
    fun isExempt(): Boolean = power?.isIgnoringBatteryOptimizations(context.packageName) ?: true

    /** routelog asked once already; it doesn't ask again on its own, only the hint offers it. */
    var asked: Boolean
        get() = prefs.getBoolean(KEY_ASKED, false)
        set(value) = prefs.edit { putBoolean(KEY_ASKED, value) }

    /** The user hid the hint. */
    var hintHidden: Boolean
        get() = prefs.getBoolean(KEY_HINT_HIDDEN, false)
        set(value) = prefs.edit { putBoolean(KEY_HINT_HIDDEN, value) }

    /** The system dialog "Let app always run in background?" for routelog. */
    // REQUEST_IGNORE_BATTERY_OPTIMIZATIONS is declared on purpose: without the exemption some
    // phones end a recording. Play policy limits it for store apps, a matter for a store release.
    @SuppressLint("BatteryLife")
    fun requestIntent(): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.fromParts("package", context.packageName, null))

    /** The system list of battery optimisation, if the dialog above is not available. */
    fun settingsIntent(): Intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

    companion object {
        /** Name of the preferences file without `.xml`; the backup rules name `device_state.xml`. */
        const val FILE_NAME = "device_state"

        private const val KEY_ASKED = "battery_exemption_asked"
        private const val KEY_HINT_HIDDEN = "battery_hint_hidden"
    }
}
