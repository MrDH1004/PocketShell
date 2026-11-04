package com.mpta.client

import com.mpta.client.core.PairingPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PairingPayloadTest {

    private val fp = "6ba4c49772f9fc3b16edf5b076b1528e746ab629ee9ed44b7f21731a4654d869"

    @Test
    fun parsesDesignDocPayload() {
        val text = """{"v":1,"name":"DESKTOP-1303","hosts":["192.168.0.10","100.101.102.103"],"port":7380,"fp":"$fp","code":"AbC-123_xyz"}"""
        val p = PairingPayload.parse(text)
        assertEquals("DESKTOP-1303", p.name)
        assertEquals(listOf("192.168.0.10", "100.101.102.103"), p.hosts)
        assertEquals(7380, p.port)
        assertEquals(fp, p.fingerprint)
        assertEquals("AbC-123_xyz", p.code)
    }

    @Test
    fun keepsHostOrderAndDropsBlankHosts() {
        val p = PairingPayload.parse("""{"v":1,"name":"pc","hosts":["100.64.0.1"," ","10.0.0.2"],"port":7391,"fp":"$fp","code":"c"}""")
        assertEquals(listOf("100.64.0.1", "10.0.0.2"), p.hosts)
    }

    @Test
    fun normalizesUppercaseColonFingerprint() {
        val colon = fp.uppercase().chunked(2).joinToString(":")
        val p = PairingPayload.parse("""  {"v":1,"name":"pc","hosts":["10.0.0.2"],"port":7380,"fp":"$colon","code":"c"}  """)
        assertEquals(fp, p.fingerprint)
    }

    @Test
    fun rejectsInvalidPayloads() {
        val bad = listOf(
            "not json",
            """{"v":2,"name":"pc","hosts":["10.0.0.2"],"port":7380,"fp":"$fp","code":"c"}""",
            """{"v":1,"name":"pc","hosts":[],"port":7380,"fp":"$fp","code":"c"}""",
            """{"v":1,"name":"pc","port":7380,"fp":"$fp","code":"c"}""",
            """{"v":1,"name":"pc","hosts":["10.0.0.2"],"port":0,"fp":"$fp","code":"c"}""",
            """{"v":1,"name":"pc","hosts":["10.0.0.2"],"port":7380,"fp":"abcd","code":"c"}""",
            """{"v":1,"name":"pc","hosts":["10.0.0.2"],"port":7380,"fp":"$fp","code":""}""",
        )
        for (text in bad) {
            assertThrows(text, IllegalArgumentException::class.java) { PairingPayload.parse(text) }
        }
    }
}
