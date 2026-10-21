package io.github.markusnuo.routelog.link

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalNetworkTest {
    @Test
    fun privateIpv4Ranges() {
        assertTrue(LocalNetwork.isLocalHost("10.0.2.2"))
        assertTrue(LocalNetwork.isLocalHost("172.16.0.1"))
        assertTrue(LocalNetwork.isLocalHost("172.31.255.255"))
        assertTrue(LocalNetwork.isLocalHost("192.168.1.20"))
        assertTrue(LocalNetwork.isLocalHost("169.254.10.1"))
    }

    @Test
    fun carrierGradeNatIsLocal() {
        // 100.64.0.0/10, e.g. Tailscale: Android counts it as local network.
        assertTrue(LocalNetwork.isLocalHost("100.64.0.1"))
        assertTrue(LocalNetwork.isLocalHost("100.100.100.100"))
        assertTrue(LocalNetwork.isLocalHost("100.127.255.255"))
        assertFalse(LocalNetwork.isLocalHost("100.63.255.255"))
        assertFalse(LocalNetwork.isLocalHost("100.128.0.1"))
    }

    @Test
    fun publicIpv4IsNotLocal() {
        assertFalse(LocalNetwork.isLocalHost("172.15.0.1"))
        assertFalse(LocalNetwork.isLocalHost("172.32.0.1"))
        assertFalse(LocalNetwork.isLocalHost("192.169.1.1"))
        assertFalse(LocalNetwork.isLocalHost("8.8.8.8"))
    }

    @Test
    fun loopbackIsNotAffected() {
        assertFalse(LocalNetwork.isLocalHost("127.0.0.1"))
        assertFalse(LocalNetwork.isLocalHost("localhost"))
        assertFalse(LocalNetwork.isLocalHost("[::1]"))
    }

    @Test
    fun localIpv6Ranges() {
        assertTrue(LocalNetwork.isLocalHost("[fd12:3456::1]"))
        assertTrue(LocalNetwork.isLocalHost("[fc00::1]"))
        assertTrue(LocalNetwork.isLocalHost("[fe80::1]"))
        assertTrue(LocalNetwork.isLocalHost("[febf::1]"))
        assertTrue(LocalNetwork.isLocalHost("[FD12:3456:789a:1::1]"))
        assertFalse(LocalNetwork.isLocalHost("[fec0::1]"))
        assertFalse(LocalNetwork.isLocalHost("[2001:db8::1]"))
    }

    @Test
    fun ipv4MappedIpv6IsJudgedByItsIpv4Address() {
        assertTrue(LocalNetwork.isLocalHost("[::ffff:192.168.1.10]"))
        assertTrue(LocalNetwork.isLocalHost("[::ffff:c0a8:010a]"))
        assertTrue(LocalNetwork.isLocalHost("[::ffff:c0a8:10a]"))
        assertTrue(LocalNetwork.isLocalHost("[0:0:0:0:0:ffff:10.0.2.2]"))
        assertTrue(LocalNetwork.isLocalHost("[::ffff:100.64.0.1]"))
        assertFalse(LocalNetwork.isLocalHost("[::ffff:8.8.8.8]"))
        assertFalse(LocalNetwork.isLocalHost("[::ffff:808:808]"))
        assertFalse(LocalNetwork.isLocalHost("[::ffff:127.0.0.1]"))
        assertFalse(LocalNetwork.isLocalHost("[::ffff:7f00:1]"))
    }

    @Test
    fun anIpv6LiteralThatIsNotOneIsNotLocal() {
        assertFalse(LocalNetwork.isLocalHost("[fd00::1::2]"))
        assertFalse(LocalNetwork.isLocalHost("[fd00:1]"))
        assertFalse(LocalNetwork.isLocalHost("[fd00:1:2:3:4:5:6:7:8]"))
        assertFalse(LocalNetwork.isLocalHost("[fd00:12345::1]"))
        assertFalse(LocalNetwork.isLocalHost("[fd00::1.2.3.4:5]"))
        assertFalse(LocalNetwork.isLocalHost("[::ffff:192.168.1]"))
    }

    @Test
    fun namesOnlyALocalNetworkResolves() {
        assertTrue(LocalNetwork.isLocalHost("devpc"))
        assertTrue(LocalNetwork.isLocalHost("devpc.local"))
        assertTrue(LocalNetwork.isLocalHost("nas.lan"))
        assertTrue(LocalNetwork.isLocalHost("printer.home.arpa"))
        assertTrue(LocalNetwork.isLocalHost("build.internal"))
        assertTrue(LocalNetwork.isLocalHost("DevPC.Local."))
    }

    @Test
    fun publicNamesAreNotLocal() {
        assertFalse(LocalNetwork.isLocalHost("example.com"))
        assertFalse(LocalNetwork.isLocalHost("app.example.com"))
        assertFalse(LocalNetwork.isLocalHost("local.example.com"))
        assertFalse(LocalNetwork.isLocalHost("example.lane"))
    }

    @Test
    fun almostAnIpv4AddressIsAName() {
        // Not an address literal: decided by the name rules, and these are public-looking names.
        assertFalse(LocalNetwork.isLocalHost("10.0.2.256"))
        assertFalse(LocalNetwork.isLocalHost("192.168.1"))
    }

    @Test
    fun loopbackForms() {
        assertTrue(LocalNetwork.isLoopback("localhost"))
        assertTrue(LocalNetwork.isLoopback("LocalHost."))
        assertTrue(LocalNetwork.isLoopback("127.0.0.1"))
        assertTrue(LocalNetwork.isLoopback("127.1.2.3"))
        assertTrue(LocalNetwork.isLoopback("127.255.255.255"))
        assertTrue(LocalNetwork.isLoopback("[::1]"))
        assertTrue(LocalNetwork.isLoopback("[0:0:0:0:0:0:0:1]"))
        assertTrue(LocalNetwork.isLoopback("[::ffff:127.0.0.1]"))
        assertTrue(LocalNetwork.isLoopback("[::ffff:7f00:1]"))
    }

    @Test
    fun everythingElseIsNoLoopback() {
        assertFalse(LocalNetwork.isLoopback("128.0.0.1"))
        assertFalse(LocalNetwork.isLoopback("126.255.255.255"))
        assertFalse(LocalNetwork.isLoopback("10.0.2.2"))
        assertFalse(LocalNetwork.isLoopback("[::2]"))
        assertFalse(LocalNetwork.isLoopback("[::]"))
        assertFalse(LocalNetwork.isLoopback("[::ffff:192.168.1.10]"))
        assertFalse(LocalNetwork.isLoopback("[fe80::1]"))
        assertFalse(LocalNetwork.isLoopback("localhost.example.com"))
        assertFalse(LocalNetwork.isLoopback("devpc"))
        assertFalse(LocalNetwork.isLoopback("127.0.0.256"))
    }

    @Test
    fun aLocalHostNeedsThePermissionWhateverTheScheme() {
        assertTrue(LocalNetwork.needsPermission("192.168.1.10", secure = true))
        assertTrue(LocalNetwork.needsPermission("192.168.1.10", secure = false))
        assertTrue(LocalNetwork.needsPermission("devpc.local", secure = true))
        assertTrue(LocalNetwork.needsPermission("[::ffff:c0a8:010a]", secure = true))
        assertTrue(LocalNetwork.needsPermission("100.64.0.1", secure = false))
    }

    @Test
    fun plainHttpToAnythingButLoopbackNeedsThePermission() {
        // Plain http exists only in debug builds and goes to a development PC, named or not.
        assertTrue(LocalNetwork.needsPermission("devpc.fritz.box", secure = false))
        assertTrue(LocalNetwork.needsPermission("nas.localdomain", secure = false))
        assertTrue(LocalNetwork.needsPermission("example.com", secure = false))
        assertTrue(LocalNetwork.needsPermission("8.8.8.8", secure = false))
        assertTrue(LocalNetwork.needsPermission("[2001:db8::1]", secure = false))
    }

    @Test
    fun loopbackNeverNeedsThePermission() {
        for (host in listOf("localhost", "127.0.0.1", "127.9.9.9", "[::1]", "[::ffff:127.0.0.1]")) {
            assertFalse(host, LocalNetwork.needsPermission(host, secure = false))
            assertFalse(host, LocalNetwork.needsPermission(host, secure = true))
        }
    }

    @Test
    fun aPublicNameOverHttpsNeedsNoPermission() {
        assertFalse(LocalNetwork.needsPermission("example.com", secure = true))
        assertFalse(LocalNetwork.needsPermission("8.8.8.8", secure = true))
        assertFalse(LocalNetwork.needsPermission("[2001:db8::1]", secure = true))
        // Resolves to a private address, but the name can't tell: not recognised with https.
        assertFalse(LocalNetwork.needsPermission("devpc.fritz.box", secure = true))
    }
}
