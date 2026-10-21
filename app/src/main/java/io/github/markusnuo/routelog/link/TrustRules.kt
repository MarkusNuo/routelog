package io.github.markusnuo.routelog.link

import java.util.Locale

/**
 * Trust is given to the host name of a link and covers its subdomains, for https only. Trusting
 * `example.com` covers `customer.example.com`; trusting `app.example.com` does not cover
 * `customer.example.com`.
 *
 * Only real domain names can be trusted: one label alone (`localhost`, `intranet`) or an IP
 * address would let one tap trust a whole local network, or leave nothing to tell hosts apart.
 */
object TrustRules {
    fun canBeTrusted(link: RecordingLink): Boolean {
        if (!link.secure) return false
        val host = normalize(link.host)
        return '.' in host && !isIpLiteral(host)
    }

    fun isTrusted(link: RecordingLink, trustedHosts: Collection<String>): Boolean {
        if (!canBeTrusted(link)) return false
        val host = normalize(link.host)
        return trustedHosts.any { trusted ->
            val t = normalize(trusted)
            t.isNotEmpty() && (host == t || host.endsWith(".$t"))
        }
    }

    fun normalize(host: String): String = host.lowercase(Locale.ROOT).trimEnd('.')

    // A bracketed IPv6 address, or a name whose last label is all digits: no domain ends in
    // digits, so this also catches short IPv4 forms such as 127.1.
    private fun isIpLiteral(host: String) =
        host.startsWith("[") || host.substringAfterLast('.').all { it in '0'..'9' }
}
