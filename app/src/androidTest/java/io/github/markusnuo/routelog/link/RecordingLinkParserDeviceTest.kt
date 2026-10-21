package io.github.markusnuo.routelog.link

import io.github.markusnuo.routelog.link.LinkParseResult.Reason
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/**
 * The link rules again, on Android: java.net.URI, the regex engine and the character tables of
 * libcore differ from the JVM the unit tests run on (RecordingLinkParserTest has the full set).
 */
@RunWith(AndroidJUnit4::class)
class RecordingLinkParserDeviceTest {
    private val now = Instant.parse("2026-10-17T10:00:00Z").toEpochMilli()

    private fun parse(upload: String, label: String = "x", expires: String = "2026-10-18T22:00:00Z", token: String? = "t", allowHttp: Boolean = true) =
        RecordingLinkParser.parse("routelog://record?upload=$upload&label=$label&expires=$expires", token, now, allowHttp)

    private fun valid(upload: String, label: String = "x") = (parse(upload, label) as LinkParseResult.Valid).link

    private fun assertBad(upload: String) = assertEquals(LinkParseResult.Invalid(Reason.BAD_PARAMETERS), parse(upload))

    @Test
    fun hostsPortsAndAddresses() {
        assertEquals("app.example.com", valid("https%3A%2F%2FApp.Example.COM.%3A8443%2Fx").host)
        assertEquals("10.0.2.2", valid("http%3A%2F%2F10.0.2.2%3A8080%2Fx").host)
        assertEquals("[::1]", valid("http%3A%2F%2F%5B%3A%3A1%5D%3A8080%2Fx").host)
        for (bad in listOf(
            "https%3A%2F%2Fapp.example.com%3A0%2Fx",
            "https%3A%2F%2Fapp.example.com%3A65536%2Fx",
            "https%3A%2F%2Fapp.example.com%3A99999999999%2Fx",
            "https%3A%2F%2Fmy_host.example%2Fx",
            "https%3A%2F%2Fa..b.example%2Fx",
            "https%3A%2F%2F%D0%B0pp.example.com%2Fx",
            "https%3A%2F%2Fapp.example.com%2Fa%20b",
            "https%3A%2F%2Fapp.example.com%2Fx%E2%80%AE",
            "https%3A%2F%2Fuser%3Apw%40app.example.com%2Fx",
            "https%3A%2F%2F%5Bevil.example%5D%2Fx",
        )) {
            assertBad(bad)
        }
    }

    @Test
    fun expiresOutOfRangeIsRejectedNotACrash() {
        val up = "https%3A%2F%2Fapp.example.com%2Fx"
        for (expires in listOf("%2B300000000-01-01T00:00:00Z", "-300000000-01-01T00:00:00Z", "%2B999999999-12-31T23:59:59Z")) {
            assertEquals(LinkParseResult.Invalid(Reason.BAD_PARAMETERS), parse(up, expires = expires))
        }
        assertTrue(parse(up, expires = "9999-12-31T23:59:59Z") is LinkParseResult.Valid)
    }

    @Test
    fun labelsAreCleanedWithTheCharacterTablesOfThisAndroid() {
        assertEquals("ab", valid("https%3A%2F%2Fa.example%2Fx", label = "a%E2%80%AEb%E2%80%8B%E2%80%A8").label)
        assertEquals("ab", valid("https%3A%2F%2Fa.example%2Fx", label = "a%EE%80%80b%EF%BF%BF").label)
        val grin = "%F0%9F%98%80"
        val label = valid("https%3A%2F%2Fa.example%2Fx", label = grin.repeat(100)).label!!
        assertEquals(80, label.codePointCount(0, label.length))
        assertTrue(label.endsWith("…"))
        assertTrue("no lone surrogate", label.dropLast(1).chunked(2).all { it.length == 2 && Character.isHighSurrogate(it[0]) && Character.isLowSurrogate(it[1]) })
    }
}
