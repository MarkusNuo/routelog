package io.github.markusnuo.routelog.link

import android.content.Context
import androidx.core.content.edit

/** Host names the user chose to trust ("Always allow …"); see [TrustRules]. */
class TrustedHosts(context: Context) {
    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    fun all(): List<String> = prefs.getStringSet(KEY, emptySet()).orEmpty().sorted()

    fun add(host: String) = update { it + TrustRules.normalize(host) }

    fun remove(host: String) = update { it - TrustRules.normalize(host) }

    private fun update(change: (Set<String>) -> Set<String>) {
        // Copy: the set returned by getStringSet must not be modified.
        val current = prefs.getStringSet(KEY, emptySet()).orEmpty().toSet()
        prefs.edit(commit = true) { putStringSet(KEY, change(current)) }
    }

    private companion object {
        const val PREFS = "settings"
        const val KEY = "trusted_hosts"
    }
}
