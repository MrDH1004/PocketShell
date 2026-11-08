package com.neststudio.pocketshell.core

import com.termux.terminal.WcWidth

/**
 * Lays one PC console row out for the phone's width. An external console keeps the PC window's size (a maximized
 * window is 200+ columns), so instead of shrinking the font until it fits, the phone shows it at the user's font and
 * re-flows each row:
 *
 * 1. A row wider than the phone first loses padding: the longest run of one repeated filler cell (spaces, box-drawing
 *    horizontals) is shortened, so window-wide rules and boxes ("╭────╮", "│ > text        │") fit the phone width.
 * 2. Whatever is still too wide wraps onto further lines, colors carried over.
 *
 * Row bodies are host VtFrame text: full SGR runs (`ESC[0;fg;bg…m`) followed by one character per cell (a wide char
 * once, 2 cells wide). The PC cursor column is mapped through both steps.
 */
object Reflow {
    /** [cursorLine] is the index in [lines] holding the cursor (-1 = none), [cursorCol] its 1-based column. */
    class Result(val lines: List<String>, val cursorLine: Int, val cursorCol: Int)

    private class Cell(var text: String, val width: Int, val sgr: String, val pcCol: Int) {
        var cursor = false
    }

    private const val FILLERS = "  ─━═-"

    /** [cursorCol] = 1-based PC column of the cursor on this row, 0 when the cursor is not on it. */
    fun layout(body: String, cols: Int, cursorCol: Int = 0): Result {
        if (cols <= 0) return Result(listOf(body), if (cursorCol > 0) 0 else -1, cursorCol)
        val cells = parse(body)
        if (cursorCol > 0) markCursor(cells, cursorCol - 1)
        squeeze(cells, cols)
        return wrap(cells, cols)
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

    private fun wrap(cells: List<Cell>, cols: Int): Result {
        val lines = ArrayList<String>()
        var line = StringBuilder()
        var width = 0
        var sgr = ""
        var cursorLine = -1
        var cursorCol = 0
        for (c in cells) {
            if (width + c.width > cols && width > 0) {
                lines.add(line.toString())
                line = StringBuilder(sgr) // the color continues on the next line
                width = 0
            }
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
        return Result(lines, cursorLine, cursorCol)
    }
}
