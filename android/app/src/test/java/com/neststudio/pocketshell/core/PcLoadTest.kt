package com.neststudio.pocketshell.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PcLoadTest {
    @Test
    fun parsesAndClamps() {
        val l = PcLoad.parse("""{"cpu":12.6,"gpu":null,"memUsed":17179869184,"memTotal":34359738368}""")
        assertEquals(12.6, l.cpu!!, 0.001)
        assertNull(l.gpu)
        assertEquals(50.0, l.memPercent!!, 0.001)
        assertEquals(100.0, PcLoad.parse("""{"cpu":130}""").cpu!!, 0.001)
        assertNull(PcLoad.parse("""{}""").memPercent)
    }

    @Test
    fun texts() {
        assertEquals("13%", PcLoad.percentText(12.6))
        assertEquals("–", PcLoad.percentText(null))
        assertEquals("16.0/32.0GB", PcLoad.gbText(17179869184, 34359738368))
    }
}
