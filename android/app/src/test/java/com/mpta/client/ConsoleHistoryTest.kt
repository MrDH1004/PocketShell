package com.mpta.client

import com.mpta.client.core.ConsoleHistory
import org.junit.Assert.assertEquals
import org.junit.Test

class ConsoleHistoryTest {
    private val esc = "\u001b"

    /** Same layout as host VtFrame.Build: rows 1..n, empty string = blank row. */
    private fun frame(rows: List<String>, cursor: Pair<Int, Int>?): ByteArray {
        val sb = StringBuilder("$esc[?25l$esc[H")
        rows.forEachIndexed { i, r ->
            sb.append("$esc[${i + 1};1H").append(r).append("$esc[0m$esc[K")
        }
        if (cursor != null) sb.append("$esc[${cursor.first};${cursor.second}H$esc[?25h")
        return sb.toString().toByteArray()
    }

    /** Phone rows the render shows, in order (row bodies). */
    private fun shown(out: ByteArray): List<String> =
        Regex("$esc\\[(\\d+);1H(.*?)$esc\\[0m$esc\\[K").findAll(String(out, Charsets.UTF_8)).map { it.groupValues[2] }.toList()

    private fun cursorOf(out: ByteArray): String? =
        Regex("$esc\\[(\\d+);(\\d+)H$esc\\[\\?25h").find(String(out, Charsets.UTF_8))?.let { "${it.groupValues[1]};${it.groupValues[2]}" }

    private val screen1 = listOf("l1", "l2", "l3", "l4", "l5", "l6", "l7", "l8", "> in", "status")
    /** Output scrolled up by 2: l1, l2 left the window. */
    private val screen2 = listOf("l3", "l4", "l5", "l6", "l7", "l8", "l9", "l10", "> in", "status")

    @Test
    fun detectsScrollAndKeepsScrolledOffLines() {
        val h = ConsoleHistory()
        assertEquals(0, h.accept(frame(screen1, 9 to 3)))
        assertEquals(2, h.accept(frame(screen2, 9 to 3)))
        assertEquals(2, h.historySize)
        // A repaint of the bottom only (input typed) is not a scroll.
        assertEquals(0, h.accept(frame(screen2.dropLast(2) + listOf("> inx", "status"), 9 to 4)))
        assertEquals(2, h.historySize)
    }

    @Test
    fun extraPhoneRowsAreFilledWithHistory() {
        val h = ConsoleHistory()
        h.accept(frame(screen1, 9 to 3))
        h.accept(frame(screen2, 9 to 3))
        val out = h.render(12, 0)
        assertEquals(listOf("l1", "l2") + screen2, shown(out))
        assertEquals("11;3", cursorOf(out))
    }

    @Test
    fun fewerPhoneRowsShowTheBottomAndScrollBack() {
        val h = ConsoleHistory()
        h.accept(frame(screen1, 9 to 3))
        h.accept(frame(screen2, 9 to 3))
        assertEquals(listOf("l10", "> in", "status"), shown(h.render(3, 0)))
        assertEquals(9, h.maxScroll(3))
        val up = h.render(3, 4)
        assertEquals(listOf("l6", "l7", "l8"), shown(up))
        assertEquals(null, cursorOf(up)) // no cursor while reading history
        assertEquals(listOf("l1", "l2", "l3"), shown(h.render(3, 99))) // clamped to the oldest line
    }

    @Test
    fun shortContentIsBottomAnchoredAndRowsAreAlwaysCleared() {
        val h = ConsoleHistory()
        h.accept(frame(listOf("a", "b", "", "", ""), 2 to 2))
        val out = h.render(4, 0)
        assertEquals(listOf("", "", "a", "b"), shown(out))
        assertEquals("4;2", cursorOf(out))
        assertEquals(listOf("", "", "", "", "", "a", "b"), shown(h.render(7, 0)))
    }

    @Test
    fun redrawWithNothingInCommonIsNotAScroll() {
        assertEquals(0, ConsoleHistory.scrollShift(screen1, listOf("x1", "x2", "x3", "x4", "x5", "x6", "x7", "x8", "x9", "x10")))
        assertEquals(0, ConsoleHistory.scrollShift(List(10) { "" }, List(10) { "" }))
    }
}
