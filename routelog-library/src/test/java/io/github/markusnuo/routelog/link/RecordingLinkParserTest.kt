package io.github.markusnuo.routelog.link

import io.github.markusnuo.routelog.link.LinkParseResult.Reason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class RecordingLinkParserTest {
    private val now = Instant.parse("2026-10-17T10:00:00Z").toEpochMilli()
    private val upload = "https%3A%2F%2Fapp.example.com%2Fapi%2Froutes%2F42"

    /** The address of the link; the token is not part of it, it comes as an extra of the intent. */
    private fun link(
        upload: String? = this.upload,
        label: String? = "Lesson%2014%3A00",
        expires: String? = "2026-10-18T22:00:00Z",
        extra: String = "",
    ) = "routelog://record?" + listOfNotNull(
        upload?.let { "upload=$it" },
        label?.let { "label=$it" },
        expires?.let { "expires=$it" },
    ).joinToString("&") + extra

    private fun parse(link: String?, token: String? = TOKEN, allowHttp: Boolean = false) =
        RecordingLinkParser.parse(link, token, now, allowHttp)

    private fun valid(link: String, token: String? = TOKEN, allowHttp: Boolean = false): RecordingLink {
        val result = parse(link, token, allowHttp)
        assertTrue("expected valid, got $result", result is LinkParseResult.Valid)
        return (result as LinkParseResult.Valid).link
    }

    private fun assertInvalid(reason: Reason, link: String?, token: String? = TOKEN, allowHttp: Boolean = false) =
        assertEquals(LinkParseResult.Invalid(reason), parse(link, token, allowHttp))

    @Test
    fun readsAllParameters() {
        val link = valid(link())
        assertEquals("https://app.example.com/api/routes/42", link.uploadUrl)
        assertEquals("app.example.com", link.host)
        assertTrue(link.secure)
        assertEquals(TOKEN, link.token)
        assertEquals("Lesson 14:00", link.label)
        assertEquals(Instant.parse("2026-10-18T22:00:00Z").toEpochMilli(), link.expiresAtMillis)
    }

    @Test
    fun plusIsNotASpace() {
        assertEquals("a b", valid(link(label = "a%20b")).label)
        assertEquals("a+b", valid(link(label = "a+b")).label)
    }

    @Test
    fun theTokenNeverAppearsInToString() {
        assertFalse(valid(link(), token = "secret-token").toString().contains("secret-token"))
        assertFalse(IncomingLink(link(), "secret-token").toString().contains("secret-token"))
    }

    @Test
    fun aTokenInTheAddressIsRejected() {
        // The token is an extra. One in the address would reach the system log of Android 8 to 12,
        // so such a link is refused even if a valid token comes as an extra, too.
        assertInvalid(Reason.BAD_PARAMETERS, link(extra = "&token=abc"))
        assertInvalid(Reason.BAD_PARAMETERS, link(extra = "&token=abc"), token = null)
        assertInvalid(Reason.BAD_PARAMETERS, link(extra = "&token="))
    }

    @Test
    fun theTokenIsRequired() {
        assertInvalid(Reason.BAD_PARAMETERS, link(), token = null)
        assertInvalid(Reason.BAD_PARAMETERS, link(), token = "")
    }

    @Test
    fun expiresWithOffsetAndExclusive() {
        assertEquals(
            Instant.parse("2026-10-18T22:00:00Z").toEpochMilli(),
            valid(link(expires = "2026-10-19T00:00:00%2B02:00")).expiresAtMillis,
        )
        assertInvalid(Reason.EXPIRED, link(expires = "2026-10-17T10:00:00Z")) // exactly now
        assertInvalid(Reason.EXPIRED, link(expires = "2026-10-16T22:00:00Z"))
        assertInvalid(Reason.BAD_PARAMETERS, link(expires = "2026-10-18T22:00:00")) // no offset
        assertInvalid(Reason.BAD_PARAMETERS, link(expires = "tomorrow"))
    }

    @Test
    fun expiresFarOutsideWhatAMillisecondCountHoldsIsRejectedNotACrash() {
        // The instant parses, but toEpochMilli overflows: that used to throw ArithmeticException.
        assertInvalid(Reason.BAD_PARAMETERS, link(expires = "%2B300000000-01-01T00:00:00Z"))
        assertInvalid(Reason.BAD_PARAMETERS, link(expires = "-300000000-01-01T00:00:00Z"))
        assertInvalid(Reason.BAD_PARAMETERS, link(expires = "%2B999999999-12-31T23:59:59Z"))
        // Beyond what the ISO formatter parses at all.
        assertInvalid(Reason.BAD_PARAMETERS, link(expires = "%2B1000000000-01-01T00:00:00Z"))
        assertInvalid(Reason.BAD_PARAMETERS, link(expires = "2026-13-45T25:61:61Z"))
    }

    @Test
    fun expiresHasNoUpperLimit() {
        // The time limit is for the app that issues the link to set, routelog adds none.
        assertEquals(
            Instant.parse("9999-12-31T23:59:59Z").toEpochMilli(),
            valid(link(expires = "9999-12-31T23:59:59Z")).expiresAtMillis,
        )
        assertEquals(
            Instant.parse("+292000000-01-01T00:00:00Z").toEpochMilli(),
            valid(link(expires = "%2B292000000-01-01T00:00:00Z")).expiresAtMillis,
        )
    }

    @Test
    fun requiredParameters() {
        assertInvalid(Reason.BAD_PARAMETERS, link(upload = null))
        assertInvalid(Reason.BAD_PARAMETERS, link(expires = null))
        assertNull(valid(link(label = null)).label)
    }

    @Test
    fun duplicateOrUnknownParametersAreRejected() {
        assertInvalid(Reason.BAD_PARAMETERS, link(extra = "&label=other"))
        assertInvalid(Reason.BAD_PARAMETERS, link(extra = "&upload=https%3A%2F%2Fevil.example%2F"))
        assertInvalid(Reason.BAD_PARAMETERS, link(extra = "&callback=x"))
        assertInvalid(Reason.BAD_PARAMETERS, link(extra = "&"))
    }

    @Test
    fun onlyRecordLinksOfThisApp() {
        assertInvalid(Reason.NOT_A_RECORDING_LINK, null)
        assertInvalid(Reason.NOT_A_RECORDING_LINK, link().replace("routelog://record", "https://record"))
        assertInvalid(Reason.NOT_A_RECORDING_LINK, link().replace("routelog://record", "routelog://other"))
        assertInvalid(Reason.NOT_A_RECORDING_LINK, link().replace("routelog://record?", "routelog://record/x?"))
        assertInvalid(Reason.NOT_A_RECORDING_LINK, link() + "#fragment")
        assertTrue(parse(link().replace("routelog://record?", "routelog://record/?")) is LinkParseResult.Valid)
        assertTrue(parse(link().replace("routelog://record", "ROUTELOG://RECORD")) is LinkParseResult.Valid)
    }

    @Test
    fun httpOnlyWhenAllowed() {
        val http = link(upload = "http%3A%2F%2F192.168.1.20%3A5000%2Fapi%2Froutes%2F42")
        assertInvalid(Reason.INSECURE_UPLOAD, http, allowHttp = false)
        val link = valid(http, allowHttp = true)
        assertFalse(link.secure)
        assertEquals("192.168.1.20", link.host)
    }

    @Test
    fun uploadUrlMustBePlainAndAbsolute() {
        assertInvalid(Reason.BAD_PARAMETERS, link(upload = "ftp%3A%2F%2Fapp.example.com%2Fx"))
        assertInvalid(Reason.BAD_PARAMETERS, link(upload = "%2Fapi%2Froutes")) // relative
        assertInvalid(Reason.BAD_PARAMETERS, link(upload = "https%3A%2F%2Fuser%3Apw%40app.example.com%2Fx")) // credentials
        assertInvalid(Reason.BAD_PARAMETERS, link(upload = "https%3A%2F%2Fapp.example.com%2Fx%23frag"))
        assertInvalid(Reason.BAD_PARAMETERS, link(upload = "https%3A%2F%2F%2Fx")) // no host
        assertInvalid(Reason.BAD_PARAMETERS, link(upload = "https%3A%2F%2Fapp.n" + "o".repeat(2100) + ".ch%2F"))
    }

    @Test
    fun uploadUrlPortIsAbsentOrInRange() {
        assertEquals("app.example.com", valid(link(upload = "https%3A%2F%2Fapp.example.com%3A8443%2Fx")).host)
        assertEquals("app.example.com", valid(link(upload = "https%3A%2F%2Fapp.example.com%3A1%2Fx")).host)
        assertEquals("app.example.com", valid(link(upload = "https%3A%2F%2Fapp.example.com%3A65535%2Fx")).host)
        for (port in listOf("0", "65536", "99999", "123456", "99999999999", "abc", "-1", "", "80a", "8%200")) {
            assertInvalid(Reason.BAD_PARAMETERS, link(upload = "https%3A%2F%2Fapp.example.com%3A$port%2Fx"))
        }
        // No port, but a path that looks like one.
        assertEquals("app.example.com", valid(link(upload = "https%3A%2F%2Fapp.example.com%2F%3A99999")).host)
    }

    @Test
    fun uploadUrlIsVisibleAsciiOnly() {
        for (bad in listOf("%20", "%09", "%0A", "%00", "%7F", "%C3%A4", "%E2%80%AE", "%E2%80%8B", "%EF%BF%BF")) {
            assertInvalid(Reason.BAD_PARAMETERS, link(upload = "https%3A%2F%2Fapp.example.com%2Fx$bad"))
            assertInvalid(Reason.BAD_PARAMETERS, link(upload = "https%3A%2F%2Fapp$bad.example.com%2Fx"))
        }
        // A space in the middle, not only at the ends.
        assertInvalid(Reason.BAD_PARAMETERS, link(upload = "https%3A%2F%2Fapp.example.com%2Fa%20b"))
        // A percent sign in the URL itself (encoded twice in the link) is fine.
        assertEquals("https://app.example.com/a%20b", valid(link(upload = "https%3A%2F%2Fapp.example.com%2Fa%2520b")).uploadUrl)
    }

    @Test
    fun uploadHostIsADomainNameOrAnIpAddress() {
        for (host in listOf("my_host.example", "a..b.ch", ".example.com", "example,com", "a:b")) {
            assertInvalid(Reason.BAD_PARAMETERS, link(upload = "https%3A%2F%2F$host%2Fx"), allowHttp = true)
        }
        assertEquals("xn--bcher-kva.example", valid(link(upload = "https%3A%2F%2Fxn--bcher-kva.example%2Fx")).host)
        assertEquals("localhost", valid(link(upload = "http%3A%2F%2Flocalhost%3A8080%2Fx"), allowHttp = true).host)
        assertEquals("10.0.2.2", valid(link(upload = "http%3A%2F%2F10.0.2.2%3A8080%2Fx"), allowHttp = true).host)
        val v6 = valid(link(upload = "http%3A%2F%2F%5B%3A%3A1%5D%3A8080%2Fx"), allowHttp = true)
        assertEquals("[::1]", v6.host)
        assertEquals("http://[::1]:8080/x", v6.uploadUrl)
        assertEquals("[2001:db8::1]", valid(link(upload = "https%3A%2F%2F%5B2001%3ADB8%3A%3A1%5D%2Fx")).host)
        // Brackets around something that is no IPv6 address.
        assertInvalid(Reason.BAD_PARAMETERS, link(upload = "https%3A%2F%2F%5Bevil.example%5D%2Fx"))
        assertInvalid(Reason.BAD_PARAMETERS, link(upload = "https%3A%2F%2F%5B%3A%3A1%5Dx%2Fx"))
        assertInvalid(Reason.BAD_PARAMETERS, link(upload = "https%3A%2F%2F%5B%3A%3A1%5D%3A0%2Fx"))
    }

    @Test
    fun aLookAlikeHostNameIsRejected() {
        // "аpp.example.com" with a Cyrillic а: not shown to the user, rejected.
        assertInvalid(Reason.BAD_PARAMETERS, link(upload = "https%3A%2F%2F%D0%B0pp.example.com%2Fx"))
    }

    @Test
    fun hostIsLowerCaseWithoutTrailingDot() {
        assertEquals("app.example.com", valid(link(upload = "https%3A%2F%2FApp.Example.COM.%2Fx")).host)
    }

    @Test
    fun queryOfTheUploadUrlIsKept() {
        val link = valid(link(upload = "https%3A%2F%2Fapp.example.com%2Fr%3Flesson%3D7%26a%3D1"))
        assertEquals("https://app.example.com/r?lesson=7&a=1", link.uploadUrl)
    }

    @Test
    fun tokenMustBeHeaderSafe() {
        assertInvalid(Reason.BAD_PARAMETERS, link(), token = "")
        assertInvalid(Reason.BAD_PARAMETERS, link(), token = "a b")
        assertInvalid(Reason.BAD_PARAMETERS, link(), token = "a\r\nX-Evil: 1")
        assertInvalid(Reason.BAD_PARAMETERS, link(), token = "ä")
        assertInvalid(Reason.BAD_PARAMETERS, link(), token = "x".repeat(4097))
        assertEquals("x".repeat(4096), valid(link(), token = "x".repeat(4096)).token)
    }

    @Test
    fun theTokenIsTakenAsItIs() {
        // An extra is not percent-encoded: nothing is decoded, '+' and '%' stay what they are.
        assertEquals("a+b", valid(link(), token = "a+b").token)
        assertEquals("a%20b", valid(link(), token = "a%20b").token)
        assertEquals("abc.DEF-123_+/=", valid(link(), token = "abc.DEF-123_+/=").token)
    }

    @Test
    fun labelIsCleanedAndShortened() {
        assertEquals("ab", valid(link(label = "a%0Ab%07")).label)
        assertNull(valid(link(label = "%20%20")).label)
        val long = valid(link(label = "x".repeat(200))).label!!
        assertEquals(RecordingLinkParser.MAX_LABEL_LENGTH, long.length)
        assertTrue(long.endsWith("…"))
    }

    @Test
    fun labelLosesCharactersThatCanHideOrReorderText() {
        // bidi override and isolate, zero-width space and joiner, BOM (all FORMAT)
        assertEquals("ab", valid(link(label = "a%E2%80%AEb")).label)
        assertEquals("ab", valid(link(label = "a%E2%81%A6b%E2%81%A9")).label)
        assertEquals("ab", valid(link(label = "a%E2%80%8Bb%E2%80%8D")).label)
        assertEquals("ab", valid(link(label = "%EF%BB%BFab")).label)
        // line and paragraph separator
        assertEquals("ab", valid(link(label = "a%E2%80%A8b%E2%80%A9")).label)
        // control characters incl. NEL and DEL
        assertEquals("ab", valid(link(label = "a%C2%85b%7F")).label)
        // private use, unassigned, and the noncharacters U+FFFE and U+FFFF
        assertEquals("ab", valid(link(label = "a%EE%80%80b")).label)
        assertEquals("ab", valid(link(label = "a%CD%B8b")).label)
        assertEquals("ab", valid(link(label = "a%EF%BF%BEb%EF%BF%BF")).label)
        // Letters and symbols stay, also outside the Latin script and beyond the BMP.
        assertEquals("Lesson Zürich 🚗", valid(link(label = "Lesson%20Z%C3%BCrich%20%F0%9F%9A%97")).label)
        // A label that is only such characters is no label.
        assertNull(valid(link(label = "%E2%80%AE%E2%80%8B")).label)
    }

    @Test
    fun labelIsCutBetweenCodePointsNeverInsideASurrogatePair() {
        val grin = "%F0%9F%98%80" // U+1F600, two UTF-16 chars
        val many = valid(link(label = grin.repeat(100))).label!!
        assertEquals(RecordingLinkParser.MAX_LABEL_LENGTH, many.codePointCount(0, many.length))
        assertTrue(many.endsWith("…"))
        assertNoLoneSurrogate(many)

        // The 79th character is the pair: a cut by chars would end on its first half.
        val pairAtTheCut = valid(link(label = "x".repeat(78) + grin + "yyyy")).label!!
        assertEquals("x".repeat(78) + "😀…", pairAtTheCut)
        assertNoLoneSurrogate(pairAtTheCut)

        // Exactly 80 code points (160 chars) is not shortened.
        val exact = valid(link(label = grin.repeat(80))).label!!
        assertEquals(80, exact.codePointCount(0, exact.length))
        assertFalse(exact.endsWith("…"))
    }

    private fun assertNoLoneSurrogate(text: String) {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (Character.isHighSurrogate(c)) {
                assertTrue("lone high surrogate at $i", i + 1 < text.length && Character.isLowSurrogate(text[i + 1]))
                i += 2
            } else {
                assertFalse("lone low surrogate at $i", Character.isLowSurrogate(c))
                i++
            }
        }
    }

    @Test
    fun brokenPercentEncodingIsRejected() {
        // Some of these fail already as a URI, some only when decoding; rejected is what counts,
        // the user sees the same message for both reasons.
        for (label in listOf("50%", "%G1", "%C3" /* incomplete UTF-8 */, "%١٢" /* not ASCII hex */)) {
            assertTrue(label, parse(link(label = label)) is LinkParseResult.Invalid)
        }
    }

    private companion object {
        const val TOKEN = "abc.DEF-123_+/="
    }
}
