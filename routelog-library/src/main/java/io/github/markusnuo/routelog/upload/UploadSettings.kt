package io.github.markusnuo.routelog.upload

import android.content.Context
import androidx.core.content.edit

/** Upload settings that belong to the phone, not to a link or an upload request. */
class UploadSettings(context: Context, name: String) {
    // Opened on first use, not at app start: before the first unlock after a restart, the app's
    // credential-encrypted storage can't be read.
    private val prefs by lazy { context.getSharedPreferences(name, Context.MODE_PRIVATE) }

    var retention: UploadRetention
        get() = UploadRetention.fromName(prefs.getString(KEY_RETENTION, null))
        set(value) = prefs.edit(commit = true) { putString(KEY_RETENTION, value.name) }

    private companion object {
        const val KEY_RETENTION = "upload_retention"
    }
}
