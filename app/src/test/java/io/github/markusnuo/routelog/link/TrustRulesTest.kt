package io.github.markusnuo.routelog.link

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrustRulesTest {
    private fun link(host: String, secure: Boolean = true) = RecordingLink(
        uploadUrl = "${if (secure) "https" else "http"}://$host/x",
        host = host,
        secure = secure,
        token = "t",
        label = null,
        expiresAtMillis = Long.MAX_VALUE,
    )

    @Test
    fun theTrustedHostItself() = assertTrue(TrustRules.isTrusted(link("example.com"), listOf("example.com")))

    @Test
    fun subdomainsOfATrustedHost() {
        assertTrue(TrustRules.isTrusted(link("customer.example.com"), listOf("example.com")))
        assertTrue(TrustRules.isTrusted(link("a.b.example.com"), listOf("example.com")))
    }

    @Test
    fun notASiblingOrParent() {
        assertFalse(TrustRules.isTrusted(link("customer.example.com"), listOf("app.example.com")))
        assertFalse(TrustRules.isTrusted(link("example.com"), listOf("app.example.com")))
    }

    @Test
    fun notAHostThatOnlyEndsWithTheSameLetters() {
        assertFalse(TrustRules.isTrusted(link("evilexample.com"), listOf("example.com")))
        assertFalse(TrustRules.isTrusted(link("example.com.evil.example"), listOf("example.com")))
    }

    @Test
    fun neverOverHttp() {
        assertFalse(TrustRules.canBeTrusted(link("192.168.1.20", secure = false)))
        assertFalse(TrustRules.isTrusted(link("192.168.1.20", secure = false), listOf("192.168.1.20")))
    }

    @Test
    fun onlyDomainNamesWithADotCanBeTrusted() {
        assertTrue(TrustRules.canBeTrusted(link("example.com")))
        assertTrue(TrustRules.canBeTrusted(link("customer.example.com")))
        assertFalse(TrustRules.canBeTrusted(link("localhost")))
        assertFalse(TrustRules.canBeTrusted(link("intranet")))
        assertFalse(TrustRules.canBeTrusted(link("ch")))
    }

    @Test
    fun ipAddressesCanNotBeTrusted() {
        for (host in listOf("192.168.1.20", "10.0.2.2", "127.1", "1.2.3", "[::1]", "[2001:db8::1]")) {
            assertFalse(host, TrustRules.canBeTrusted(link(host)))
            assertFalse(host, TrustRules.isTrusted(link(host), listOf(host)))
        }
    }

    @Test
    fun aHostWithoutADotIsNotTrustedEvenIfItIsOnTheList() {
        assertFalse(TrustRules.isTrusted(link("localhost"), listOf("localhost")))
    }

    @Test
    fun caseAndTrailingDotDoNotMatter() {
        assertTrue(TrustRules.isTrusted(link("customer.example.com"), listOf("Example.COM.")))
    }

    @Test
    fun emptyEntriesTrustNothing() {
        assertFalse(TrustRules.isTrusted(link("example.com"), listOf("", ".")))
        assertFalse(TrustRules.isTrusted(link("example.com"), emptyList()))
    }
}
