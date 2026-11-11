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
        // a.der SHA-256 (sha256sum) = fb06f48341274fcce169... → first 8 bytes fb06f48341274fcc
        assertEquals("FB06-F483-4127-4FCC", PinPairing.fpShortOfDer(der("a.der")))
        // b.der = dbebe1da5eb5658d907f...
        assertEquals("DBEB-E1DA-5EB5-658D", PinPairing.fpShortOfDer(der("b.der")))
    }

    @Test
    fun fpShortFromFingerprintStrings() {
        val fp = "fb06f48341274fcce1699527855abb3a89fd35487bfb4f07debcfea4f41e5682"
        assertEquals("FB06-F483-4127-4FCC", PinPairing.fpShort(fp))
        assertEquals("FB06-F483-4127-4FCC", PinPairing.fpShort(fp.uppercase().chunked(2).joinToString(":")))
        assertThrows(IllegalArgumentException::class.java) { PinPairing.fpShort("abcd") }
        assertTrue(PinPairing.fpShortEquals("fb06-f483-4127-4fcc", "FB06F48341274FCC"))
        assertFalse(PinPairing.fpShortEquals("FB06-F483-4127-4FCD", "FB06-F483-4127-4FCC"))
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
        val reply = """{"v":1,"name":"DESKTOP-1","hosts":["192.168.0.10","100.101.102.103"],"port":7380,"fpShort":"fb06-f483-4127-4fcc"}"""
        val h = DiscoveredHost.parse(reply, "192.168.0.10")!!
        assertEquals("DESKTOP-1", h.name)
        assertEquals(listOf("192.168.0.10", "100.101.102.103"), h.hosts)
        assertEquals(7380, h.port)
        assertEquals("FB06-F483-4127-4FCC", h.fpShort)

        // Source address goes first even if not listed.
        val h2 = DiscoveredHost.parse(reply, "10.0.0.7")!!
        assertEquals(listOf("10.0.0.7", "192.168.0.10", "100.101.102.103"), h2.hosts)
    }

    @Test
    fun discoveryReplyRejectsJunkAndWrongVersion() {
        assertNull(DiscoveredHost.parse("PKSHELL?1", "1.2.3.4"))
        assertNull(DiscoveredHost.parse("""{"v":2,"name":"x","hosts":["1.2.3.4"],"port":7380,"fpShort":"FB06-F483-4127-4FCC"}"""))
        assertNull(DiscoveredHost.parse("""{"v":1,"name":"x","hosts":["1.2.3.4"],"port":0,"fpShort":"FB06-F483-4127-4FCC"}"""))
        assertNull(DiscoveredHost.parse("""{"v":1,"name":"x","hosts":["1.2.3.4"],"port":7380,"fpShort":"nope"}"""))
        assertNull(DiscoveredHost.parse("""{"v":1,"name":"x","hosts":[],"port":7380,"fpShort":"FB06-F483-4127-4FCC"}"""))
        // Name falls back to the first host.
        assertEquals("1.2.3.4", DiscoveredHost.parse("""{"v":1,"hosts":["1.2.3.4"],"port":7380,"fpShort":"FB06F48341274FCC"}""")!!.name)
    }

    @Test
    fun repliesFromSeveralInterfacesAreMerged() {
        val a = DiscoveredHost("PC", listOf("192.168.0.10"), 7380, "FB06-F483-4127-4FCC")
        val b = DiscoveredHost("PC", listOf("10.0.0.5", "192.168.0.10"), 7380, "FB06-F483-4127-4FCC")
        val other = DiscoveredHost("PC2", listOf("192.168.0.11"), 7380, "DBEB-E1DA-5EB5-658D")
        val merged = DiscoveredHost.merge(listOf(a, b, other))
        assertEquals(2, merged.size)
        assertEquals(listOf("192.168.0.10", "10.0.0.5"), merged[0].hosts)
    }

    @Test
    fun claimResultParsing() {
        val r = ClaimResult.parse("""{"deviceId":"d1","token":"t1","fp":"FB06F48341274FCCE1699527855ABB3A89FD35487BFB4F07DEBCFEA4F41E5682"}""")
        assertEquals("d1", r.deviceId)
        assertEquals("fb06f48341274fcce1699527855abb3a89fd35487bfb4f07debcfea4f41e5682", r.fingerprint)
        assertNull(ClaimResult.parse("""{"deviceId":"d1","token":"t1"}""").fingerprint)
        assertThrows(IllegalArgumentException::class.java) { ClaimResult.parse("""{"deviceId":"d1"}""") }
        assertThrows(IllegalArgumentException::class.java) { ClaimResult.parse("not json") }
    }
}
