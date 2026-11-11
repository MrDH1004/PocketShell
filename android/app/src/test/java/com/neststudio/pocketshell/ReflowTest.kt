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
        val rows = Regex("$esc\\[(\\d+);1H$esc\\[0m$esc\\[K(.*?)$esc\\[0m(?=$esc\\[\\d+;\\d+H|$)").findAll(out).map { strip(it.groupValues[2]) }.toList()
        assertEquals(listOf("", "xxxxxxxxxx", "xxxxxxxxxx", "xxxxx", "> ab "), rows) // the cursor cell past the text is a space
        assertEquals(true, out.endsWith("$esc[5;5H$esc[?25h"))
        assertEquals(0, h.maxScroll(5, 10))
        assertEquals(1, h.maxScroll(3, 10))
    }

    @Test
    fun pcWordWrappedRowsJoinAndRewrapWithHangingIndent() {
        val a = "- aaaa bbbb cccc dd"
        val b = "  eeee ffff"
        assertEquals(true, Reflow.joins(Reflow.info(a), Reflow.info(b), 20))
        assertEquals(false, Reflow.joins(Reflow.info(a), Reflow.info("- new item"), 20)) // a new list item
        assertEquals(false, Reflow.joins(Reflow.info("short line"), Reflow.info(b), 20))  // not wrapped
        assertEquals(false, Reflow.joins(Reflow.info("│ aaaa bbbb cccc  │"), Reflow.info("│ eeee"), 20)) // box
        val r = Reflow.layoutGroup(listOf(a, b), 12)
        assertEquals(listOf("- aaaa bbbb", "  cccc dd", "  eeee ffff"), r.lines.map(::strip))
        // A path cut at the window edge continues without a space.
        val p = Reflow.layoutGroup(listOf("Read src/main/java/com/x", "  /Reflow.kt"), 40)
        assertEquals(listOf("Read src/main/java/com/x/Reflow.kt"), p.lines.map(::strip))
    }

    @Test
    fun codeAndDiffRowsKeepIndentationAndAreNotJoined() {
        // Indentation inside a code row is not squeezed: the row wraps, under the code column.
        val r = Reflow.layout("141      outside.nav = x", 20)
        assertEquals(listOf("141      outside.nav", "      = x"), r.lines.map(::strip))
        // A numbered code line and a diff continuation ("+parent)") stay separate rows.
        val code = "146 +   while (root != null && root.parent != blockerT."
        assertEquals(false, Reflow.joins(Reflow.info(code), Reflow.info("        +parent) root = root.parent;"), 56))
        assertEquals(false, Reflow.joins(Reflow.info("x".repeat(50)), Reflow.info("  +parent)"), 52))
    }

    @Test
    fun historyJoinsWrappedRowsWhenPcWidthKnown() {
        val h = ConsoleHistory()
        h.pcCols = 20
        val sb = StringBuilder("$esc[?25l$esc[H")
        listOf("- aaaa bbbb cccc dd", "  eeee ffff", "> x").forEachIndexed { i, r ->
            sb.append("$esc[${i + 1};1H").append(r).append("$esc[0m$esc[K")
        }
        h.accept(sb.toString().toByteArray())
        val out = String(h.render(4, 0, 12), Charsets.UTF_8)
        val rows = Regex("$esc\\[(\\d+);1H$esc\\[0m$esc\\[K(.*?)$esc\\[0m(?=$esc\\[\\d+;\\d+H|$)").findAll(out).map { strip(it.groupValues[2]) }.toList()
        assertEquals(listOf("- aaaa bbbb", "  cccc dd", "  eeee ffff", "> x"), rows)
        assertEquals(0, h.maxScroll(4, 12))
    }

    @Test
    fun partlyDrawnReplyThatScrolledOffIsReplacedNotRepeated() {
        val tail = listOf("old", "● 세 기능 모두 가능", "지금 작업은 계속")
        // Same block again: the earlier copy goes.
        assertEquals(2, ConsoleHistory.redrawnTail(tail, listOf("● 세 기능 모두 가능", "지금 작업은 계속")))
        // A fuller render of the same reply: the partial copy (and a not-yet-drawn blank) goes.
        assertEquals(2, ConsoleHistory.redrawnTail(tail, listOf("● 세 기능 모두 가능", "지금 작업은 계속 진행하고", "")))
        assertEquals(3, ConsoleHistory.redrawnTail(tail + "", listOf("● 세 기능 모두 가능", "지금 작업은 계속 진행", "", "1.")))
        // Different text or a too short first line: kept.
        assertEquals(0, ConsoleHistory.redrawnTail(tail, listOf("● 다른 내용", "x")))
        assertEquals(0, ConsoleHistory.redrawnTail(listOf("ok"), listOf("ok")))
    }

    @Test
    fun fitColsAvoidsStrayWrappedCharacters() {
        assertEquals(53, Reflow.fitCols(50, 53))  // fits: no change
        assertEquals(56, Reflow.fitCols(56, 53))  // a bit wider: shrink so the row stays on one line
        assertEquals(53, Reflow.fitCols(80, 53))  // 1.5x: shrinking that much is too small, wrap instead
        assertEquals(61, Reflow.fitCols(240, 53)) // 4 lines of up to 61 instead of 4 full + 28 stray
        // A full row of Korean (wide) text at 61 columns stays on 4 lines.
        assertEquals(4, Reflow.lineCount("가".repeat(120), 61))
    }

    @Test
    fun koreanWordsJoinedAcrossRowsKeepTheirSpace() {
        // "스크립트로만" is 12 cells wide, as long as a cut path, but it is a word: the next row starts a new one.
        val r = Reflow.layoutGroup(listOf("  1. 지금은 흉내 낸 스크립트로만", "     확인했어."), 60)
        assertEquals(listOf("  1. 지금은 흉내 낸 스크립트로만 확인했어."), r.lines.map(::strip))
    }
}
