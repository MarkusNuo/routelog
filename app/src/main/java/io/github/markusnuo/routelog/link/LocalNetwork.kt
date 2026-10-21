package io.github.markusnuo.routelog.link

import java.util.Locale

/**
 * From Android 17 on (targetSdk 37), connections into the local network need the runtime
 * permission ACCESS_LOCAL_NETWORK. Only debug builds declare it, for uploads to a development PC;
 * release builds upload to public servers only.
 *
 * Android blocks by address, not by name, and a name can't be resolved here. So [needsPermission]
 * asks in two cases: the host is [isLocalHost], i.e. a private, link-local, unique-local or
 * carrier-grade NAT (100.64.0.0/10) address literal (also IPv4-mapped in IPv6) or a name only a
 * local network resolves; or the link is plain `http://` to anything but [isLoopback]. Plain http
 * exists only in debug builds, and in practice always goes to a development PC, whatever its name
 * (`devpc.fritz.box`, `nas.localdomain`). A name with https that resolves to a private address is
 * not recognised; the upload then times out.
 *
 * Loopback (127.0.0.1, `adb reverse`) is not affected.
 */
object LocalNetwork {
    const val PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

    /** First Android version that enforces the local network permission. */
    const val MIN_SDK = 37

    private val LOCAL_SUFFIXES = listOf(".local", ".lan", ".home.arpa", ".internal")

    /** Whether an upload to [host] (as in `RecordingLink.host`) needs the permission. */
    fun needsPermission(host: String, secure: Boolean): Boolean = isLocalHost(host) || (!secure && !isLoopback(host))

    /** A private address literal or a name only a local network resolves. Loopback is not local. */
    fun isLocalHost(host: String): Boolean {
        val h = normalise(host)
        if (h.startsWith("[") && h.endsWith("]")) {
            val groups = ipv6(h.substring(1, h.length - 1)) ?: return false
            return isLocalIpv6(groups)
        }
        ipv4(h)?.let { return isLocalIpv4(it) }
        if (h == "localhost") return false
        // A name without dots ("devpc") resolves only inside a local network.
        return '.' !in h || LOCAL_SUFFIXES.any { h.endsWith(it) }
    }

    /** `localhost`, 127.0.0.0/8, `[::1]` or an IPv4-mapped 127.0.0.0/8 address. */
    fun isLoopback(host: String): Boolean {
        val h = normalise(host)
        if (h.startsWith("[") && h.endsWith("]")) {
            val groups = ipv6(h.substring(1, h.length - 1)) ?: return false
            mappedIpv4(groups)?.let { return isLoopbackIpv4(it) }
            return groups.take(7).all { it == 0 } && groups[7] == 1
        }
        ipv4(h)?.let { return isLoopbackIpv4(it) }
        return h == "localhost"
    }

    private fun normalise(host: String) = host.lowercase(Locale.ROOT).trimEnd('.')

    private fun ipv4(host: String): IntArray? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val octets = parts.map { part -> part.toIntOrNull()?.takeIf { part.isNotEmpty() && part.length <= 3 && it in 0..255 } ?: return null }
        return octets.toIntArray()
    }

    private fun isLoopbackIpv4(o: IntArray) = o[0] == 127

    private fun isLocalIpv4(o: IntArray): Boolean = when {
        o[0] == 10 -> true
        o[0] == 172 && o[1] in 16..31 -> true
        o[0] == 192 && o[1] == 168 -> true
        o[0] == 169 && o[1] == 254 -> true // link-local
        o[0] == 100 && o[1] in 64..127 -> true // carrier-grade NAT, 100.64.0.0/10 (Android counts it as local)
        else -> false // 127.x is loopback, which is allowed
    }

    private fun isLocalIpv6(groups: IntArray): Boolean {
        mappedIpv4(groups)?.let { return isLocalIpv4(it) }
        val first = groups[0]
        return (first and 0xfe00) == 0xfc00 || // unique local fc00::/7
            (first and 0xffc0) == 0xfe80 // link-local fe80::/10
    }

    /** The IPv4 address inside `::ffff:a.b.c.d` (or `::ffff:hhhh:hhhh`), else null. */
    private fun mappedIpv4(groups: IntArray): IntArray? {
        if (groups.take(5).any { it != 0 } || groups[5] != 0xffff) return null
        return intArrayOf(groups[6] shr 8, groups[6] and 0xff, groups[7] shr 8, groups[7] and 0xff)
    }

    /** The eight 16-bit groups of an IPv6 address without brackets, or null if it isn't one. */
    private fun ipv6(address: String): IntArray? {
        val halves = address.split("::")
        if (halves.size > 2) return null
        val compressed = halves.size == 2
        // A dotted IPv4 address may only end the whole address.
        val head = groupsOf(halves[0], dottedAllowed = !compressed) ?: return null
        val tail = if (compressed) groupsOf(halves[1], dottedAllowed = true) ?: return null else emptyList()
        // "::" stands for at least one group of zeros.
        val missing = 8 - head.size - tail.size
        if (!(if (compressed) missing >= 1 else missing == 0)) return null
        return (head + List(missing) { 0 } + tail).toIntArray()
    }

    private fun groupsOf(text: String, dottedAllowed: Boolean): List<Int>? {
        if (text.isEmpty()) return emptyList()
        val items = text.split(':')
        val groups = mutableListOf<Int>()
        for ((index, item) in items.withIndex()) {
            if ('.' in item) {
                if (!dottedAllowed || index != items.lastIndex) return null
                val o = ipv4(item) ?: return null
                groups += (o[0] shl 8) or o[1]
                groups += (o[2] shl 8) or o[3]
            } else {
                if (item.length !in 1..4 || !item.all { it in '0'..'9' || it in 'a'..'f' }) return null
                groups += item.toInt(16)
            }
        }
        return groups
    }
}
