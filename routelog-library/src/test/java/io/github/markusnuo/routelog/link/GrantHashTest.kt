package io.github.markusnuo.routelog.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GrantHashTest {
    @Test
    fun isTheLowerCaseHexSha256OfTheUtf8Token() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", GrantHash.of("abc"))
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", GrantHash.of(""))
    }

    @Test
    fun hasAlwaysSixtyFourLowerCaseHexDigits() {
        for (token in listOf("a", "abc.DEF-123_+/=", "x".repeat(4096))) {
            val hash = GrantHash.of(token)
            assertEquals(64, hash.length)
            assertTrue(hash, hash.all { it in '0'..'9' || it in 'a'..'f' })
        }
    }

    @Test
    fun differentTokensHaveDifferentHashes() {
        assertNotEquals(GrantHash.of("token-1"), GrantHash.of("token-2"))
        assertNotEquals(GrantHash.of("abc"), GrantHash.of("ABC"))
        assertEquals(GrantHash.of("same"), GrantHash.of("same"))
    }
}
