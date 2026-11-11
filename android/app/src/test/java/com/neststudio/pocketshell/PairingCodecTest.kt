package com.neststudio.pocketshell

import com.neststudio.pocketshell.data.Pairing
import com.neststudio.pocketshell.data.PairingCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingCodecTest {
    private val a = Pairing("Desk", listOf("192.168.0.5", "100.80.1.2"), 7380, "fpA", "d1", "tA")
    private val b = Pairing("Laptop", listOf("192.168.0.9"), 7390, "fpB", "d2", "tB")

    @Test
    fun roundTripsSeveralPcs() {
        assertEquals(listOf(a, b), PairingCodec.decode(PairingCodec.encode(listOf(a, b))))
    }

    @Test
    fun upsertReplacesTheSamePcInPlaceAndAppendsANewOne() {
        val renamed = a.copy(pcName = "Office", token = "tA2")
        assertEquals(listOf(renamed, b), PairingCodec.upsert(listOf(a, b), renamed))
        assertEquals(listOf(a, b), PairingCodec.upsert(listOf(a), b))
    }

    @Test
    fun aliasRoundTripsAndFallsBackToThePcName() {
        val named = a.copy(alias = "거실 PC")
        assertEquals("거실 PC", PairingCodec.decode(PairingCodec.encode(listOf(named))).single().displayName)
        assertEquals("Desk", a.displayName)
        // Lists saved before aliases existed have no "alias" key.
        val old = PairingCodec.encode(listOf(b)).replace(",\"alias\":\"\"", "")
        assertEquals(b, PairingCodec.decode(old).single())
    }

    @Test
    fun skipsBrokenEntriesAndBadJson() {
        val json = """[{"name":"x","hosts":[],"fp":"f","token":"t"},{"fp":"","token":"t","hosts":["1.2.3.4"]},""" +
            PairingCodec.encode(listOf(b)).removePrefix("[")
        assertEquals(listOf(b), PairingCodec.decode(json))
        assertTrue(PairingCodec.decode("not json").isEmpty())
        assertTrue(PairingCodec.decode(null).isEmpty())
    }
}
