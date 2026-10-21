package io.github.markusnuo.routelog.link

import java.net.URI
import java.net.URISyntaxException
import java.time.DateTimeException
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A validated "record and upload" link. Holds the token: never log it, never show it. */
data class RecordingLink(
    val uploadUrl: String,
    /** Lower-case host of [uploadUrl], as shown to the user and used for trust. */
    val host: String,
    val secure: Boolean,
    val token: String,
    val label: String?,
    /** The grant is valid before this instant, not at it. */
    val expiresAtMillis: Long,
) {
    override fun toString() = "RecordingLink(host=$host, secure=$secure, label=$label, expiresAtMillis=$expiresAtMillis)"
}

/**
 * What arrived with a link intent: the `routelog://` address and the token extra. Neither is
 * validated yet. The token is an extra and not part of the address on purpose: Android 8 to 12
 * write the address of every opened link to the system log, and "recent apps" keeps it.
 */
class IncomingLink(val address: String, val token: String?) {
    override fun toString() = "IncomingLink"
}

sealed interface LinkParseResult {
    data class Valid(val link: RecordingLink) : LinkParseResult
    data class Invalid(val reason: Reason) : LinkParseResult

    enum class Reason {
        /** Links only: not a `routelog://record` address. */
        NOT_A_RECORDING_LINK,

        /** The upload address, token, expiry or another parameter breaks the rules. */
        BAD_PARAMETERS,

        /** A plain `http` upload address where only `https` is allowed. */
        INSECURE_UPLOAD,

        /** The expiry is not in the future. */
        EXPIRED,
    }
}

/**
 * Reads a `routelog://record?upload=…&label=…&expires=…` link and its token (docs/EXTERNAL_RECORDING.md).
 * Links come from outside the app and are treated like any untrusted input: anything that does not
 * match the rules exactly is rejected rather than repaired.
 */
object RecordingLinkParser {
    const val SCHEME = "routelog"
    const val HOST = "record"
    private const val MAX_URL_LENGTH = 2048
    private const val MAX_TOKEN_LENGTH = 4096
    const val MAX_LABEL_LENGTH = 80

    // No "token": it travels as an extra. A token in the address is an unknown parameter and
    // rejects the link, so a sender can't leave it in the system log by mistake.
    private val KNOWN = setOf("upload", "label", "expires")

    private val HOST_NAME = Regex("[a-z0-9-]+(\\.[a-z0-9-]+)*")
    private val IPV6_LITERAL = Regex("\\[[0-9a-f]*:[0-9a-f:.]*]")

    fun parse(link: String?, token: String?, nowMillis: Long, allowHttp: Boolean): LinkParseResult {
        if (link == null) return invalid(LinkParseResult.Reason.NOT_A_RECORDING_LINK)
        val uri = try {
            URI(link)
        } catch (e: URISyntaxException) {
            return invalid(LinkParseResult.Reason.NOT_A_RECORDING_LINK)
        }
        if (!SCHEME.equals(uri.scheme, ignoreCase = true) ||
            !HOST.equals(uri.rawAuthority, ignoreCase = true) ||
            !(uri.rawPath.isNullOrEmpty() || uri.rawPath == "/") ||
            uri.rawFragment != null
        ) {
            return invalid(LinkParseResult.Reason.NOT_A_RECORDING_LINK)
        }
        val params = queryParameters(uri.rawQuery) ?: return invalid(LinkParseResult.Reason.BAD_PARAMETERS)
        if (!KNOWN.containsAll(params.keys)) return invalid(LinkParseResult.Reason.BAD_PARAMETERS)

        val upload = params["upload"]?.let(::uploadTarget) ?: return invalid(LinkParseResult.Reason.BAD_PARAMETERS)
        if (!upload.secure && !allowHttp) return invalid(LinkParseResult.Reason.INSECURE_UPLOAD)
        val validToken = token?.takeIf(::isValidToken) ?: return invalid(LinkParseResult.Reason.BAD_PARAMETERS)
        val expires = params["expires"]?.let(::instantMillis) ?: return invalid(LinkParseResult.Reason.BAD_PARAMETERS)
        if (nowMillis >= expires) return invalid(LinkParseResult.Reason.EXPIRED)

        return LinkParseResult.Valid(
            RecordingLink(
                uploadUrl = upload.url,
                host = upload.host,
                secure = upload.secure,
                token = validToken,
                label = params["label"]?.let(::cleanLabel),
                expiresAtMillis = expires,
            ),
        )
    }

    /**
     * The same rules for an upload a host app passes directly instead of through a link
     * ([io.github.markusnuo.routelog.Routelog.startWithUpload]): upload address, token, label and expiry.
     */
    fun validate(uploadUrl: String, token: String, label: String?, expiresAtMillis: Long, nowMillis: Long, allowHttp: Boolean): LinkParseResult {
        val upload = uploadTarget(uploadUrl) ?: return invalid(LinkParseResult.Reason.BAD_PARAMETERS)
        if (!upload.secure && !allowHttp) return invalid(LinkParseResult.Reason.INSECURE_UPLOAD)
        if (!isValidToken(token)) return invalid(LinkParseResult.Reason.BAD_PARAMETERS)
        if (nowMillis >= expiresAtMillis) return invalid(LinkParseResult.Reason.EXPIRED)
        return LinkParseResult.Valid(
            RecordingLink(
                uploadUrl = upload.url,
                host = upload.host,
                secure = upload.secure,
                token = token,
                label = label?.let(::cleanLabel),
                expiresAtMillis = expiresAtMillis,
            ),
        )
    }

    private fun invalid(reason: LinkParseResult.Reason) = LinkParseResult.Invalid(reason)

    /** Name → decoded value; null if the query is malformed or a name appears twice. */
    private fun queryParameters(rawQuery: String?): Map<String, String>? {
        if (rawQuery.isNullOrEmpty()) return null
        val result = HashMap<String, String>()
        for (pair in rawQuery.split('&')) {
            val eq = pair.indexOf('=')
            if (eq <= 0) return null
            val name = percentDecode(pair.substring(0, eq)) ?: return null
            val value = percentDecode(pair.substring(eq + 1)) ?: return null
            if (result.put(name, value) != null) return null
        }
        return result
    }

    /** RFC 3986 percent-decoding as UTF-8. Unlike form decoding, '+' stays '+' (tokens may use it). */
    internal fun percentDecode(text: String): String? {
        val bytes = java.io.ByteArrayOutputStream(text.length)
        var i = 0
        while (i < text.length) {
            if (text[i] == '%') {
                if (i + 2 >= text.length) return null
                val hi = hexValue(text[i + 1])
                val lo = hexValue(text[i + 2])
                if (hi < 0 || lo < 0) return null
                bytes.write(hi * 16 + lo)
                i += 3
            } else {
                val end = text.indexOf('%', i).let { if (it < 0) text.length else it }
                bytes.write(text.substring(i, end).toByteArray(Charsets.UTF_8))
                i = end
            }
        }
        return try {
            Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes.toByteArray())).toString()
        } catch (e: java.nio.charset.CharacterCodingException) {
            null
        }
    }

    /** Only ASCII hex digits; Character.digit would also accept other scripts' digits. */
    private fun hexValue(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }

    private class UploadTarget(val url: String, val host: String, val secure: Boolean)

    private fun uploadTarget(value: String): UploadTarget? {
        // Visible ASCII only: no spaces, control or bidi characters, no look-alike letters.
        if (value.length > MAX_URL_LENGTH || !value.all { it in '!'..'~' }) return null
        val uri = try {
            URI(value)
        } catch (e: URISyntaxException) {
            return null
        }
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
        if (scheme != "https" && scheme != "http") return null
        if (uri.rawUserInfo != null || uri.rawFragment != null) return null
        // The host is read from the authority text and checked here, not taken from URI.getHost:
        // what that accepts differs between the JVM and Android's libcore.
        val host = hostOf(uri.rawAuthority ?: return null) ?: return null
        return UploadTarget(value, host, secure = scheme == "https")
    }

    /**
     * The lower-case host of a `host[:port]` authority, or null if it has user info, a port
     * outside 1..65535, or a host that is not a plain DNS name, IPv4 address or bracketed IPv6 address.
     */
    private fun hostOf(authority: String): String? {
        val hostPart: String
        val portPart: String?
        if (authority.startsWith("[")) {
            val close = authority.indexOf(']')
            if (close < 0) return null
            hostPart = authority.substring(0, close + 1)
            val rest = authority.substring(close + 1)
            portPart = when {
                rest.isEmpty() -> null
                rest.startsWith(":") -> rest.substring(1)
                else -> return null
            }
        } else {
            val colon = authority.indexOf(':')
            hostPart = if (colon < 0) authority else authority.substring(0, colon)
            portPart = if (colon < 0) null else authority.substring(colon + 1)
        }
        if (portPart != null && !isValidPort(portPart)) return null
        val host = hostPart.lowercase(Locale.ROOT).trimEnd('.')
        return host.takeIf { HOST_NAME.matches(it) || IPV6_LITERAL.matches(it) }
    }

    private fun isValidPort(port: String) =
        port.length in 1..5 && port.all { it in '0'..'9' } && port.toInt() in 1..65535

    private fun isValidToken(token: String) =
        token.length in 1..MAX_TOKEN_LENGTH && token.all { it in '!'..'~' }

    /** Null if [value] is not an ISO-8601 instant with offset, or lies outside what a Long of milliseconds holds. */
    private fun instantMillis(value: String): Long? = try {
        OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant().toEpochMilli()
    } catch (e: DateTimeException) {
        null
    } catch (e: ArithmeticException) {
        // Years beyond about 292 million: the instant is valid, its millisecond count is not.
        null
    }

    /**
     * The label for display: without control, formatting (bidi, zero-width), separator, private-use
     * and unassigned characters, at most [MAX_LABEL_LENGTH] characters, cut between code points.
     */
    private fun cleanLabel(value: String): String? {
        val kept = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val codePoint = value.codePointAt(i)
            i += Character.charCount(codePoint)
            if (isAllowedInLabel(codePoint)) kept.appendCodePoint(codePoint)
        }
        val cleaned = kept.toString().trim()
        if (cleaned.isEmpty()) return null
        if (cleaned.codePointCount(0, cleaned.length) <= MAX_LABEL_LENGTH) return cleaned
        val cut = cleaned.offsetByCodePoints(0, MAX_LABEL_LENGTH - 1)
        return cleaned.substring(0, cut).trimEnd() + "…"
    }

    private fun isAllowedInLabel(codePoint: Int): Boolean {
        if (codePoint == 0xFFFE || codePoint == 0xFFFF) return false
        return when (Character.getType(codePoint).toByte()) {
            Character.CONTROL, Character.FORMAT, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR,
            Character.PRIVATE_USE, Character.UNASSIGNED, Character.SURROGATE,
            -> false
            else -> true
        }
    }
}
