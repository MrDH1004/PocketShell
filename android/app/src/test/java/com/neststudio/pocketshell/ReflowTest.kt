package com.neststudio.pocketshell

import com.neststudio.pocketshell.core.ConsoleHistory
import com.neststudio.pocketshell.core.Reflow
import org.junit.Assert.assertEquals
import org.junit.Test

class ReflowTest {
    private val esc = "\u001b"
    private val red = "$esc[0;91;40m"
    private val plain = "$esc[0;37;40m"

    private fun strip(s: String) = s.replace(Regex("$esc\\[[0-9;]*m"), "")

    @Test
    fun shortRowIsUnchanged() {
        val r = Reflow.layout("${plain}hello", 10)
        assertEquals(listOf("${plain}hello"), r.lines)
    }

    @Test
    fun longTextWrapsAndCarriesColor() {
        val r = Reflow.layout("${red}abcdefgh", 3)
        assertEquals(listOf("abc", "def", "gh"), r.lines.map(::strip))
        assertEquals(red + "def", r.lines[1]) // color continues on the wrapped line
    }

    @Test
    fun windowWideRuleAndBoxShrinkToThePhone() {
        assertEquals(listOf("──────────"), Reflow.layout("─".repeat(120), 10).lines.map(::strip))
        assertEquals(listOf("╭────────╮"), Reflow.layout("╭" + "─".repeat(118) + "╮", 10).lines.map(::strip))
        assertEquals(listOf("│ > hi   │"), Reflow.layout("│ > hi" + " ".repeat(113) + "│", 10).lines.map(::strip))
    }

    @Test
    fun wideCharsTakeTwoColumnsAndNeverSplit() {
        val r = Reflow.layout("가나다", 5)
        assertEquals(listOf("가나", "다"), r.lines)
    }

    @Test
    fun cursorFollowsSqueezeAndWrap() {
        // "│ > hi_   …│": cursor right after "hi" (PC column 7) stays there after the padding is squeezed.
        val box = Reflow.layout("│ > hi" + " ".repeat(113) + "│", 10, cursorCol = 7)
        assertEquals(0, box.cursorLine)
        assertEquals(7, box.cursorCol)
        // Past the row's text, on a wrapped line.
        val wrapped = Reflow.layout("abcdef", 4, cursorCol = 7)
        assertEquals(1, wrapped.cursorLine)
        assertEquals(3, wrapped.cursorCol)
    }

    @Test
    fun historyRendersReflowedBottomAnchored() {
        val h = ConsoleHistory()
        val sb = StringBuilder("$esc[?25l$esc[H")
        listOf("x".repeat(25), "> ab").forEachIndexed { i, r -> sb.append("$esc[${i + 1};1H").append(r).append("$esc[0m$esc[K") }
        sb.append("$esc[2;5H$esc[?25h")
        h.accept(sb.toString().toByteArray())
        val out = String(h.render(5, 0, 10), Charsets.UTF_8)
        val rows = Regex("$esc\\[(\\d+);1H(.*?)$esc\\[0m$esc\\[K").findAll(out).map { strip(it.groupValues[2]) }.toList()
        assertEquals(listOf("", "xxxxxxxxxx", "xxxxxxxxxx", "xxxxx", "> ab "), rows) // the cursor cell past the text is a space
        assertEquals(true, out.endsWith("$esc[5;5H$esc[?25h"))
        assertEquals(0, h.maxScroll(5, 10))
        assertEquals(1, h.maxScroll(3, 10))
    }
}
