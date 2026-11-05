package com.mpta.client

import com.mpta.client.core.FrameCrop
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class FrameCropTest {
    private val esc = "\u001b"

    /** Same layout as host VtFrame.Build: rows 1..n, empty string = blank row. */
    private fun frame(rows: List<String>, cursor: Pair<Int, Int>?): String {
        val sb = StringBuilder("$esc[?25l$esc[H")
        rows.forEachIndexed { i, r ->
            sb.append("$esc[${i + 1};1H")
            if (r.isNotEmpty()) sb.append("$esc[0;37;40m").append(r)
            sb.append("$esc[0m$esc[K")
        }
        if (cursor != null) sb.append("$esc[${cursor.first};${cursor.second}H$esc[?25h")
        return sb.toString()
    }

    private fun crop(f: String, rows: Int) = String(FrameCrop.crop(f.toByteArray(), rows), Charsets.UTF_8)

    @Test
    fun keepsBottomWindowAroundCursorAndStatusLines() {
        // 10-row console: content on 1..7 (prompt on 5, status 6..7), blank 8..10.
        val rows = listOf("a", "b", "c", "d", "> 입력", "status1", "status2", "", "", "")
        val out = crop(frame(rows, 5 to 7), 4)
        assertEquals(frame(listOf("d", "> 입력", "status1", "status2"), 2 to 7), out)
    }

    @Test
    fun cursorBelowContentWins() {
        val rows = listOf("a", "b", "", "", "", "")
        assertEquals(frame(listOf("", "", ""), 3 to 1), crop(frame(rows, 6 to 1), 3))
    }

    @Test
    fun clampsToTopAndHidesCursorOutsideWindow() {
        val rows = listOf("a", "b", "c", "", "", "")
        assertEquals(frame(listOf("a", "b", "c"), null), crop(frame(rows, null), 3))
    }

    @Test
    fun clearBelowErasesRowsUnderTheConsoleAndKeepsCursor() {
        val f = frame(listOf("a", "b"), 2 to 3)
        val out = String(FrameCrop.clearBelow(f.toByteArray(), 2), Charsets.UTF_8)
        assertEquals(f + "${esc}7$esc[3;1H$esc[J${esc}8", out)
        assertArrayEquals("x".toByteArray(), FrameCrop.clearBelow("x".toByteArray(), 2))
    }

    @Test
    fun passesThroughWhenItFitsOrIsNotAFrame() {
        val f = frame(listOf("a", "b"), 1 to 1)
        assertEquals(f, crop(f, 5))
        val other = "hello".toByteArray()
        assertArrayEquals(other, FrameCrop.crop(other, 1))
    }
}
