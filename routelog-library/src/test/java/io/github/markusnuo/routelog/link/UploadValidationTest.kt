package io.github.markusnuo.routelog.link

import io.github.markusnuo.routelog.link.LinkParseResult.Reason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rules for an upload a host app passes directly: the same as for a link. */
class UploadValidationTest {
    private val now = 1_800_000_000_000L
    private val later = now + 3_600_000

    private fun validate(
        uploadUrl: String = "https://app.example.com/api/routes/42",
        token: String = "t0k3n-ABC.def_~",
        label: String? = "Lesson 14:00",
        expires: Long = later,
        allowHttp: Boolean = false,
    ) = RecordingLinkParser.validate(uploadUrl, token, label, expires, now, allowHttp)

    private fun assertInvalid(reason: Reason, result: LinkParseResult) =
        assertEquals(LinkParseResult.Invalid(reason), result)

    @Test
    fun acceptsAnHttpsUpload() {
        val result = validate()
        assertTrue("$result", result is LinkParseResult.Valid)
        val link = (result as LinkParseResult.Valid).link
        assertEquals("https://app.example.com/api/routes/42", link.uploadUrl)
        assertEquals("app.example.com", link.host)
        assertTrue(link.secure)
        assertEquals("Lesson 14:00", link.label)
        assertEquals(later, link.expiresAtMillis)
    }

    @Test
    fun plainHttpOnlyWhenTheHostIsADebugBuild() {
        assertInvalid(Reason.INSECURE_UPLOAD, validate(uploadUrl = "http://10.0.2.2:8080/r/1"))
        assertTrue(validate(uploadUrl = "http://10.0.2.2:8080/r/1", allowHttp = true) is LinkParseResult.Valid)
    }

    @Test
    fun theAddressFollowsTheLinkRules() {
        for (url in listOf("ftp://a.example/r", "https://user:pw@a.example/r", "https://a.example/r#x", "not a url", "")) {
            assertInvalid(Reason.BAD_PARAMETERS, validate(uploadUrl = url))
        }
    }

    @Test
    fun theTokenFollowsTheLinkRules() {
        for (token in listOf("", "with space", "tab\tin", "ümlaut", "x".repeat(4097))) {
            assertInvalid(Reason.BAD_PARAMETERS, validate(token = token))
        }
        assertTrue(validate(token = "x".repeat(4096)) is LinkParseResult.Valid)
    }

    @Test
    fun anExpiredPermissionIsRefused() {
        assertInvalid(Reason.EXPIRED, validate(expires = now))
        assertInvalid(Reason.EXPIRED, validate(expires = now - 1))
    }

    @Test
    fun theLabelIsCleanedAndOptional() {
        assertEquals(null, (validate(label = null) as LinkParseResult.Valid).link.label)
        val long = (validate(label = "a".repeat(200)) as LinkParseResult.Valid).link.label!!
        assertEquals(RecordingLinkParser.MAX_LABEL_LENGTH, long.length)
        assertEquals("ab", (validate(label = "a\u0007b") as LinkParseResult.Valid).link.label)
    }
}
