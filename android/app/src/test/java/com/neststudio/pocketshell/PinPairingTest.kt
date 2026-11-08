package com.neststudio.pocketshell

import com.neststudio.pocketshell.core.ClaimResult
import com.neststudio.pocketshell.core.DiscoveredHost
import com.neststudio.pocketshell.core.HostAddress
import com.neststudio.pocketshell.core.PinPairing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PinPairingTest {

    private fun der(name: String): ByteArray =
        requireNotNull(javaClass.classLoader!!.getResourceAsStream("certs/$name")) { "missing $name" }.use { it.readBytes() }

    @Test
    fun fpShortOfKnownDerCert() {
        // a.der SHA-256 (sha256sum) = 6ba4c49772f9fc3b16ed... → first 8 bytes 6ba4c49772f9fc3b
        assertEquals("6BA4-C497-72F9-FC3B", PinPairing.fpShortOfDer(der("a.der")))
        // b.der = 936b2288a5a46a2e15ee...
        assertEquals("936B-2288-A5A4-6A2E", PinPairing.fpShortOfDer(der("b.der")))
    }

    @Test
    fun fpShortFromFingerprintStrings() {
        val fp = "6ba4c49772f9fc3b16edf5b076b1528e746ab629ee9ed44b7f21731a4654d869"
        assertEquals("6BA4-C497-72F9-FC3B", PinPairing.fpShort(fp))
        assertEquals("6BA4-C497-72F9-FC3B", PinPairing.fpShort(fp.uppercase().chunked(2).joinToString(":")))
        assertThrows(IllegalArgumentException::class.java) { PinPairing.fpShort("abcd") }
        assertTrue(PinPairing.fpShortEquals("6ba4-c497-72f9-fc3b", "6BA4C49772F9FC3B"))
        assertFalse(PinPairing.fpShortEquals("6BA4-C497-72F9-FC3C", "6BA4-C497-72F9-FC3B"))
        assertFalse(PinPairing.fpShortEquals("", ""))
        assertFalse(PinPairing.isValidFpShort("ZZZZ-C497-72F9-FC3B"))
    }

    @Test
    fun pinValidation() {
        assertTrue(PinPairing.isValidPin("123456"))
        assertTrue(PinPairing.isValidPin("000000"))
        assertEquals("123456", PinPairing.normalizePin(" 123 456 "))
        assertEquals("123456", PinPairing.normalizePin("123-456"))
        for (bad in listOf("", "12345", "1234567", "12345a", "١٢٣٤٥٦", "12 456")) {
            assertFalse(bad, PinPairing.isValidPin(bad))
        }
    }

    @Test
    fun hostAddressParsing() {
        assertEquals(HostAddress("192.168.0.5", 7380), HostAddress.parse("192.168.0.5"))
        assertEquals(HostAddress("100.64.1.2", 7391), HostAddress.parse(" 100.64.1.2:7391 "))
        assertEquals(HostAddress("my-pc.tail1234.ts.net", 7380), HostAddress.parse("my-pc.tail1234.ts.net"))
        assertEquals(HostAddress("fd7a:115c::1", 7380), HostAddress.parse("fd7a:115c::1"))
        assertEquals(HostAddress("fd7a:115c::1", 8443), HostAddress.parse("[fd7a:115c::1]:8443"))
        for (bad in listOf("", ":7380", "1.2.3.4:", "1.2.3.4:0", "1.2.3.4:70000", "1.2.3.4:x", "https://1.2.3.4", "a b", "[::1]x")) {
            assertNull(bad, HostAddress.parse(bad))
        }
    }

    @Test
    fun discoveryReplyParsing() {
        val reply = """{"v":1,"name":"DESKTOP-1","hosts":["192.168.0.10","100.101.102.103"],"port":7380,"fpShort":"6ba4-c497-72f9-fc3b"}"""
        val h = DiscoveredHost.parse(reply, "192.168.0.10")!!
        assertEquals("DESKTOP-1", h.name)
        assertEquals(listOf("192.168.0.10", "100.101.102.103"), h.hosts)
        assertEquals(7380, h.port)
        assertEquals("6BA4-C497-72F9-FC3B", h.fpShort)

        // Source address goes first even if not listed.
        val h2 = DiscoveredHost.parse(reply, "10.0.0.7")!!
        assertEquals(listOf("10.0.0.7", "192.168.0.10", "100.101.102.103"), h2.hosts)
    }

    @Test
    fun discoveryReplyRejectsJunkAndWrongVersion() {
        assertNull(DiscoveredHost.parse("PKSHELL?1", "1.2.3.4"))
        assertNull(DiscoveredHost.parse("""{"v":2,"name":"x","hosts":["1.2.3.4"],"port":7380,"fpShort":"6BA4-C497-72F9-FC3B"}"""))
        assertNull(DiscoveredHost.parse("""{"v":1,"name":"x","hosts":["1.2.3.4"],"port":0,"fpShort":"6BA4-C497-72F9-FC3B"}"""))
        assertNull(DiscoveredHost.parse("""{"v":1,"name":"x","hosts":["1.2.3.4"],"port":7380,"fpShort":"nope"}"""))
        assertNull(DiscoveredHost.parse("""{"v":1,"name":"x","hosts":[],"port":7380,"fpShort":"6BA4-C497-72F9-FC3B"}"""))
        // Name falls back to the first host.
        assertEquals("1.2.3.4", DiscoveredHost.parse("""{"v":1,"hosts":["1.2.3.4"],"port":7380,"fpShort":"6BA4C49772F9FC3B"}""")!!.name)
    }

    @Test
    fun repliesFromSeveralInterfacesAreMerged() {
        val a = DiscoveredHost("PC", listOf("192.168.0.10"), 7380, "6BA4-C497-72F9-FC3B")
        val b = DiscoveredHost("PC", listOf("10.0.0.5", "192.168.0.10"), 7380, "6BA4-C497-72F9-FC3B")
        val other = DiscoveredHost("PC2", listOf("192.168.0.11"), 7380, "936B-2288-A5A4-6A2E")
        val merged = DiscoveredHost.merge(listOf(a, b, other))
        assertEquals(2, merged.size)
        assertEquals(listOf("192.168.0.10", "10.0.0.5"), merged[0].hosts)
    }

    @Test
    fun claimResultParsing() {
        val r = ClaimResult.parse("""{"deviceId":"d1","token":"t1","fp":"6BA4C49772F9FC3B16EDF5B076B1528E746AB629EE9ED44B7F21731A4654D869"}""")
        assertEquals("d1", r.deviceId)
        assertEquals("6ba4c49772f9fc3b16edf5b076b1528e746ab629ee9ed44b7f21731a4654d869", r.fingerprint)
        assertNull(ClaimResult.parse("""{"deviceId":"d1","token":"t1"}""").fingerprint)
        assertThrows(IllegalArgumentException::class.java) { ClaimResult.parse("""{"deviceId":"d1"}""") }
        assertThrows(IllegalArgumentException::class.java) { ClaimResult.parse("not json") }
    }
}
