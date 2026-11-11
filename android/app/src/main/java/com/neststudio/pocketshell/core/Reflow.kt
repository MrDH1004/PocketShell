package com.neststudio.pocketshell.core

import com.termux.terminal.WcWidth

/**
 * Lays PC console rows out for the phone's width. An external console keeps the PC window's size (a maximized
 * window is 200+ columns), so instead of shrinking the font until it fits, the phone shows it at the user's font and
 * re-flows the rows:
 *
 * 1. Text the PC program word-wrapped at the PC width (a paragraph split over several rows, continuation rows
 *    indented) is joined back into one logical line ([joins]), so it can be wrapped again at the phone width instead
 *    of leaving a short tail per PC row.
 * 2. A single row wider than the phone first loses padding: the longest run of one repeated filler cell (spaces,
 *    box-drawing horizontals) is shortened, so window-wide rules and boxes ("╭────╮", "│ > text        │") fit.
 * 3. Whatever is still too wide wraps at word boundaries onto further lines, with a hanging indent (the text column
 *    after a "- " / "● " / "⎿" marker, or the PC continuation indent), colors carried over.
 *
 * Row bodies are host VtFrame text: full SGR runs (`ESC[0;fg;bg…m`) followed by one character per cell (a wide char
 * once, 2 cells wide). The PC cursor column is mapped through every step.
 */
object Reflow {
    /** [cursorLine] is the index in [lines] holding the cursor (-1 = none), [cursorCol] its 1-based column. */
    class Result(val lines: List<String>, val cursorLine: Int, val cursorCol: Int)

    /** What [joins] needs to know about one row, cheap to cache per row body. */
    class RowInfo(
        /** Width in cells without trailing blanks. */
        val width: Int,
        /** Leading blank cells (= width when the row is blank). */
        val indent: Int,
        /** Column where the text starts after a list/tool marker ("- ", "● ", "⎿  "), else [indent]. */
        val textStart: Int,
        val firstWord: Int,
        val lastWord: Int,
        /** Starts with a marker or box-drawing char: a new item, never a continuation. */
        val startsBlock: Boolean,
        /** Ends with a box-drawing char (a box or rule row). */
        val endsBlock: Boolean,
        /** Starts with a line number (code / diff view): whitespace matters, never joined onto. */
        val numbered: Boolean = false,
        /** First word is a diff sign glued to text ("+parent)"): a diff view's own wrapped continuation. */
        val gutter: Boolean = false,
        /** The last word holds a wide (CJK) character: an ordinary word, however many cells it takes. */
        val lastWordWide: Boolean = false,
    ) {
        val blank: Boolean get() = indent >= width
    }

    private class Cell(var text: String, val width: Int, val sgr: String, val pcCol: Int) {
        var cursor = false
        val space: Boolean get() = text == " " || text == " "
    }

    private const val FILLERS = "  ─━═-"
    private val MARKERS = setOf("-", "*", "+", "•", "●", "⏺", "⎿", "└", "├", ">", "❯", "✻", "※", "✓", "✗")
    private val NUMBERED = Regex("\\d{1,3}[.)]")
    private val LINE_NUMBER = Regex("\\d{1,5}")

    /** The PC program may wrap a little short of the window edge (right padding). */
    private const val WRAP_SLACK = 2

    /** [cursorCol] = 1-based PC column of the cursor on this row, 0 when the cursor is not on it. */
    fun layout(body: String, cols: Int, cursorCol: Int = 0): Result =
        layoutGroup(listOf(body), cols, if (cursorCol > 0) 0 else -1, cursorCol)

    /**
     * Lays out [bodies], consecutive PC rows that [joins] says form one logical line (a single row is a group of
     * one). [cursorRow] is the index in [bodies] holding the cursor (-1 = none), [cursorCol] its 1-based PC column.
     */
    fun layoutGroup(bodies: List<String>, cols: Int, cursorRow: Int = -1, cursorCol: Int = 0): Result {
        if (cols <= 0) return Result(listOf(bodies.joinToString("")), if (cursorRow >= 0) 0 else -1, cursorCol)
        val first = parse(bodies[0])
        if (cursorRow == 0 && cursorCol > 0) markCursor(first, cursorCol - 1)
        if (bodies.size == 1) {
            val inf0 = info(first)
            val hang = hangOf(inf0, cols)
            squeeze(first, cols)
            return wrap(first, cols, hang, if (inf0.blank) 0 else inf0.textStart)
        }
        val cells = ArrayList(first)
        var prev = info(first)
        for (r in 1 until bodies.size) {
            val row = parse(bodies[r])
            if (cursorRow == r && cursorCol > 0) markCursor(row, cursorCol - 1)
            val inf = info(row)
            while (cells.isNotEmpty() && cells.last().space && !cells.last().cursor) cells.removeAt(cells.size - 1)
            // A long token (path, URL) cut at the window edge continues without a space; words get one back. Korean
            // words reach that many cells quickly (two per syllable) and are words all the same.
            if (prev.lastWord < LONG_TOKEN || prev.lastWordWide) cells.add(Cell(" ", 1, "", -1))
            var k = 0
            while (k < row.size && row[k].space && !row[k].cursor) k++
            for (i in k until row.size) cells.add(row[i])
            prev = inf
        }
        val hang = minOf(info(parse(bodies[1])).indent, cols / 3)
        return wrap(cells, cols, hang)
    }

    private const val LONG_TOKEN = 12

    /**
     * True when [next] continues [prev]: the PC program word-wrapped one line over both rows. That is the case when
     * [prev] is long and [next]'s first word would not have fitted after it on a [pcCols]-wide row, neither row is
     * part of a box or rule, [next] is not a new list item and is indented at least as far as [prev].
     */
    fun joins(prev: RowInfo, next: RowInfo, pcCols: Int): Boolean {
        if (pcCols <= 0 || prev.blank || next.blank) return false
        if (prev.endsBlock || next.startsBlock) return false
        if (prev.numbered || next.gutter) return false // code: a wrapped code line keeps its own rows
        if (prev.width * 4 < pcCols * 3) return false
        if (next.indent < prev.indent) return false
        return prev.width + 1 + next.firstWord > pcCols - WRAP_SLACK
    }

    fun info(body: String): RowInfo = info(parse(body))

    private fun info(cells: List<Cell>): RowInfo {
        var end = cells.size
        while (end > 0 && cells[end - 1].space) end--
        var width = 0
        for (i in 0 until end) width += cells[i].width
        var k = 0
        var indent = 0
        while (k < end && cells[k].space) {
            indent += cells[k].width
            k++
        }
        var firstWord = 0
        var m = k
        val word = StringBuilder()
        while (m < end && !cells[m].space) {
            firstWord += cells[m].width
            word.append(cells[m].text)
            m++
        }
        var lastWord = 0
        var e = end - 1
        var lastWordWide = false
        while (e >= k && !cells[e].space) {
            lastWord += cells[e].width
            if (cells[e].width == 2) lastWordWide = true
            e--
        }
        val w = word.toString()
        val numbered = LINE_NUMBER.matches(w)
        val gutter = w.length >= 2 && (w[0] == '+' || w[0] == '-') && w[1] != w[0]
        val isMarker = w in MARKERS || NUMBERED.matches(w) || numbered
        val firstBox = k < end && isBox(cells[k].text)
        val lastBox = end > 0 && isBox(cells[end - 1].text)
        var textStart = indent
        if (isMarker && m < end) {
            var col = indent + firstWord
            var n = m
            while (n < end && cells[n].space) {
                col += cells[n].width
                n++
            }
            textStart = col
        }
        return RowInfo(width, indent, textStart, firstWord, lastWord, isMarker || firstBox, lastBox, numbered, gutter, lastWordWide)
    }

    private fun isBox(text: String): Boolean = text.isNotEmpty() && text[0] in '─'..'▟'

    private fun hangOf(info: RowInfo, cols: Int): Int = if (info.blank) 0 else minOf(info.textStart, cols / 3)

    /** Most the font is shrunk (as a column ratio) to avoid stray wrapped characters, see [fitCols]. */
    const val MAX_SHRINK = 1.25

    /**
     * Columns to lay an external console out at. A full PC row of [pcCols] wraps into several phone lines and the
     * last one is often just a few stray characters ("ps" alone on a line). When a slightly smaller font (at most
     * [MAX_SHRINK] times the columns) makes the PC width an exact multiple of the phone width, full rows split into
     * whole lines: that column count is returned. Otherwise (or when the PC row already fits) [phoneCols].
     */
    fun fitCols(pcCols: Int, phoneCols: Int): Int {
        if (pcCols <= phoneCols || phoneCols <= 0) return phoneCols
        val lines = pcCols / phoneCols
        // +1 per line when it wraps more than once: a wide char that would cross the edge moves to the next line,
        // and that lost cell must not push the row onto one more line.
        val target = (pcCols + lines - 1) / lines + if (lines >= 2) 1 else 0
        return if (target <= phoneCols * MAX_SHRINK) target else phoneCols
    }

    /** Number of phone lines [body] takes at [cols]. */
    fun lineCount(body: String, cols: Int): Int = layout(body, cols).lines.size

    private fun parse(body: String): ArrayList<Cell> {
        val cells = ArrayList<Cell>()
        var sgr = ""
        var col = 0
        var i = 0
        while (i < body.length) {
            if (body[i] == '\u001b' && i + 1 < body.length && body[i + 1] == '[') {
                var j = i + 2
                while (j < body.length && body[j] !in '@'..'~') j++
                if (j < body.length && body[j] == 'm') sgr = body.substring(i, j + 1)
                i = j + 1
                continue
            }
            val cp = body.codePointAt(i)
            val n = Character.charCount(cp)
            val w = WcWidth.width(cp)
            val text = body.substring(i, i + n)
            if (w == 0 && cells.isNotEmpty()) {
                cells.last().text += text // combining mark: stays with its base character
            } else {
                val width = if (w == 2) 2 else 1
                cells.add(Cell(text, width, sgr, col))
                col += width
            }
            i += n
        }
        return cells
    }

    /** Marks the cell at PC column [target], padding with plain spaces when the cursor sits past the row's text. */
    private fun markCursor(cells: ArrayList<Cell>, target: Int) {
        val hit = cells.firstOrNull { target >= it.pcCol && target < it.pcCol + it.width }
        if (hit != null) {
            hit.cursor = true
            return
        }
        var col = cells.lastOrNull()?.let { it.pcCol + it.width } ?: 0
        while (col < target) cells.add(Cell(" ", 1, "", col++))
        cells.add(Cell(" ", 1, "", col).also { it.cursor = true })
    }

    /** Shortens the longest filler runs (keeping one cell of each) until the row fits [cols] or nothing is left. */
    private fun squeeze(cells: ArrayList<Cell>, cols: Int) {
        var excess = cells.sumOf { it.width } - cols
        while (excess > 0) {
            var bestStart = -1
            var bestLen = 1
            var i = 0
            while (i < cells.size) {
                val c = cells[i]
                if (c.width != 1 || c.text.length != 1 || c.text[0] !in FILLERS) {
                    i++
                    continue
                }
                var j = i + 1
                while (j < cells.size && cells[j].text == c.text && cells[j].sgr == c.sgr) j++
                // Blanks only as padding before a box's right edge (or the row end): indentation inside text and
                // code ("140      outside…") keeps its width, or lines next to each other stop lining up.
                if (c.space && (j until cells.size).any { !cells[it].space && !isBox(cells[it].text) }) {
                    i = j
                    continue
                }
                if (j - i > bestLen) {
                    bestStart = i
                    bestLen = j - i
                }
                i = j
            }
            if (bestStart < 0) return
            val cut = minOf(excess, bestLen - 1)
            val from = bestStart + bestLen - cut // drop the end of the run, keep where the text (and cursor) starts
            if ((from until bestStart + bestLen).any { cells[it].cursor }) cells[from - 1].cursor = true
            repeat(cut) { cells.removeAt(from) }
            excess -= cut
        }
    }

    /**
     * Wraps [cells] at [cols], breaking after a word when the line is at least a third full (else mid-word, as a
     * terminal would). Continuation lines start with [hang] blank cells.
     */
    private fun wrap(cells: List<Cell>, cols: Int, hang: Int, textCol: Int = 0): Result {
        val lines = ArrayList<String>()
        var cursorLine = -1
        var cursorCol = 0
        val n = cells.size
        var i = 0
        while (true) {
            val indent = if (lines.isEmpty()) 0 else hang
            val avail = cols - indent
            var w = 0
            var j = i
            while (j < n && w + cells[j].width <= avail) {
                w += cells[j].width
                j++
            }
            if (j == i && j < n) j++ // one cell wider than the room left: place it anyway
            var end = j
            var next = j
            if (j < n) {
                if (!cells[j].space || cells[j].cursor) {
                    var k = j - 1
                    while (k > i && !(cells[k].space && !cells[k].cursor)) k--
                    var before = 0
                    for (x in i until k) before += cells[x].width
                    // Never break inside the leading indent / marker / line number: the first line must carry text.
                    if (k > i && before * 3 >= avail && (lines.isNotEmpty() || before > textCol)) {
                        end = k
                        next = k
                    }
                }
                while (next < n && cells[next].space && !cells[next].cursor) next++ // the break eats the spaces
            }
            val line = StringBuilder()
            repeat(indent) { line.append(' ') }
            var sgr = ""
            var width = indent
            for (x in i until end) {
                val c = cells[x]
                if (c.sgr != sgr) {
                    line.append(c.sgr.ifEmpty { "\u001b[0m" })
                    sgr = c.sgr
                }
                if (c.cursor) {
                    cursorLine = lines.size
                    cursorCol = width + 1
                }
                line.append(c.text)
                width += c.width
            }
            lines.add(line.toString())
            if (next >= n) break
            i = next
        }
        return Result(lines, cursorLine, cursorCol)
    }
}
