package com.mpta.client

import com.mpta.client.core.FrameTail
import org.junit.Assert.assertEquals
import org.junit.Test

class FrameTailTest {
    private val start = "\u001b[?25l\u001b[H"

    private fun FrameTail.text() = String(snapshot(), Charsets.UTF_8)

    @Test
    fun keepsOnlyLatestFrame() {
        val t = FrameTail()
        t.append("${start}one".toByteArray())
        t.append("${start}two".toByteArray())
        assertEquals("${start}two", t.text())
    }

    @Test
    fun frameAndMarkerSplitAcrossChunks() {
        val t = FrameTail()
        t.append("${start}old\u001b[?2".toByteArray())
        t.append("5l\u001b[Hnew-a".toByteArray())
        t.append("-b".toByteArray())
        assertEquals("${start}new-a-b", t.text())
    }

    @Test
    fun emptyBeforeFirstFrameAndAfterClear() {
        val t = FrameTail()
        t.append("plain output".toByteArray())
        assertEquals("", t.text())
        t.append("${start}x".toByteArray())
        t.clear()
        assertEquals("", t.text())
    }

    @Test
    fun dropsUnstructuredOutputPastLimit() {
        val t = FrameTail(maxBytes = 8)
        t.append("0123456789".toByteArray())
        t.append(start.toByteArray())
        assertEquals(start, t.text())
    }
}
