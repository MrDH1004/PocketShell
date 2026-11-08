package com.neststudio.pocketshell.core

/**
 * Phone-side history for an external console. The PC console buffer is often only the window (Windows Terminal /
 * ConPTY keep no scrollback in the console), so lines that scroll off the top are recovered by comparing successive
 * frames and kept here. The phone then shows any slice of (history + window): extra phone rows are filled with older
 * lines and the user can scroll back.
 *
 * Frames come from host VtFrame.Build: `ESC[?25l ESC[H`, per row `ESC[y;1H` + text/SGR + `ESC[0m ESC[K`, then an
 * optional cursor `ESC[r;cH ESC[?25h`. Row bodies are kept verbatim (text + SGR) and re-emitted at other positions.
 */
class ConsoleHistory(private val maxLines: Int = 3000) {
    private val history = ArrayDeque<String>()
    private var window: List<String> = emptyList()
    /** 1-based (row, col) of the cursor in [window], null when hidden. */
    private var cursor: Pair<Int, Int>? = null

    val historySize: Int get() = history.size
    val hasFrame: Boolean get() = window.isNotEmpty()

    /**
     * Takes one frame. Returns the number of lines that scrolled into history (0 if none), or -1 when [frame] is not
     * a console frame (the caller then shows it unchanged).
     */
    fun accept(frame: ByteArray): Int {
        val text = String(frame, Charsets.UTF_8)
        if (!text.startsWith(START)) return -1
        val rows = ROW.findAll(text).map { it.groupValues[1].toInt() to it.groupValues[2] }.toList()
        if (rows.isEmpty()) return -1
        val height = rows.maxOf { it.first }
        val next = Array(height) { "" }
        for ((y, body) in rows) next[y - 1] = body
        val shifted = if (window.size == height) scrollShift(window, next.asList()) else 0
        for (i in 0 until shifted) history.addLast(window[i])
        // Redraws can bring lines that already scrolled off back onto the screen: drop that overlap from the tail.
        repeat(overlapWithTop(history.takeLast(40), next.asList())) { history.removeLast() }
        while (history.size > maxLines) history.removeFirst()
        window = next.asList()
        cursor = CURSOR.find(text)?.let { it.groupValues[1].toInt() to it.groupValues[2].toInt() }
        return shifted
    }

    /** The PC's scrollback for this console (sent on connect) replaces what the phone collected itself. */
    fun replaceHistory(lines: List<String>) {
        history.clear()
        lines.takeLast(maxLines).forEach { history.addLast(it) }
    }

    fun clear() {
        history.clear()
        window = emptyList()
        cursor = null
    }

    /** Index (0-based, in history + window) of the last line worth showing at the bottom: content or cursor. */
    private fun liveBottom(): Int {
        val lastContent = window.indexOfLast { it.isNotEmpty() }
        val cursorRow = (cursor?.first ?: 0) - 1
        val bottomInWindow = maxOf(lastContent, cursorRow, 0)
        return history.size + bottomInWindow
    }

    /**
     * PC window width. When known, rows the PC program word-wrapped at that width are joined back into one logical
     * line (Reflow.joins) before being wrapped at the phone width.
     */
    var pcCols: Int = 0
        set(value) {
            if (field != value) countCache.clear()
            field = value
        }

    /** Largest useful [scroll] for [viewRows] (0 = live view). [cols] > 0: re-flowed to that width (phone lines). */
    fun maxScroll(viewRows: Int, cols: Int = 0): Int {
        if (cols <= 0) return maxOf(0, liveBottom() - (viewRows - 1))
        if (window.isEmpty()) return 0
        return maxOf(0, countRange(0, liveBottom(), cols) - viewRows)
    }

    /** Phone lines the last [n] history lines take at [cols] (to keep a scrolled-back view in place). */
    fun wrappedCount(n: Int, cols: Int): Int {
        if (cols <= 0) return n
        return countRange(maxOf(0, history.size - n), history.size - 1, cols)
    }

    /** Phone lines rows [from]..[to] take, joined rows counted once per group (groups cut at [from]). */
    private fun countRange(from: Int, to: Int, cols: Int): Int {
        var total = 0
        var idx = to
        while (idx >= from) {
            val s = maxOf(groupStart(idx), from)
            total += count(s, idx, cols)
            idx = s - 1
        }
        return total
    }

    private val countCache = HashMap<String, Int>()
    private var countCols = 0

    private fun count(from: Int, to: Int, cols: Int): Int {
        if (cols != countCols || countCache.size > 8000) {
            countCache.clear()
            countCols = cols
        }
        val bodies = (from..to).map(::line)
        return countCache.getOrPut(bodies.joinToString("\n")) { Reflow.layoutGroup(bodies, cols).lines.size }
    }

    private val infoCache = HashMap<String, Reflow.RowInfo>()

    private fun info(body: String): Reflow.RowInfo {
        if (infoCache.size > 8000) infoCache.clear()
        return infoCache.getOrPut(body) { Reflow.info(body) }
    }

    /** First row of the logical line (joined PC rows) that row [idx] belongs to. */
    private fun groupStart(idx: Int): Int {
        if (pcCols <= 0) return idx
        var s = idx
        while (s > 0 && idx - s < MAX_GROUP && Reflow.joins(info(line(s - 1)), info(line(s)), pcCols)) s--
        return s
    }

    /**
     * Phone layout (DESIGN §8): the PC rows re-flowed to [cols] (Reflow), bottom-anchored like [render]. [scroll]
     * counts phone lines; only the lines needed for the view are laid out.
     */
    private fun renderReflowed(viewRows: Int, scroll: Int, cols: Int): ByteArray {
        val c = cursor
        val need = scroll.coerceAtLeast(0) + viewRows
        val lines = ArrayList<String>() // bottom first
        var cursorFromBottom = -1
        var cursorCol = 0
        var idx = if (window.isEmpty()) -1 else liveBottom()
        val cursorIdx = if (c != null) history.size + c.first - 1 else -1
        while (idx >= 0 && lines.size < need) {
            val s = groupStart(idx)
            val cr = if (cursorIdx in s..idx) cursorIdx - s else -1
            val r = Reflow.layoutGroup((s..idx).map(::line), cols, cr, if (cr >= 0) c!!.second else 0)
            idx = s
            for (k in r.lines.indices.reversed()) {
                if (k == r.cursorLine) {
                    cursorFromBottom = lines.size
                    cursorCol = r.cursorCol
                }
                lines.add(r.lines[k])
            }
            idx--
        }
        val s = scroll.coerceIn(0, maxOf(0, lines.size - viewRows))
        val sb = StringBuilder(START)
        for (r in 0 until viewRows) {
            val i = s + viewRows - 1 - r
            sb.append("[").append(r + 1).append(";1H").append(lines.getOrElse(i) { "" }).append("[0m[K")
        }
        val fromTop = viewRows - 1 - (cursorFromBottom - s)
        if (s == 0 && cursorFromBottom >= 0 && fromTop in 0 until viewRows) {
            sb.append("[").append(fromTop + 1).append(';').append(cursorCol).append("H[?25h")
        }
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    /**
     * Full redraw of [viewRows] phone rows showing the slice that ends [scroll] lines above the live bottom. The
     * slice is bottom-anchored (input box right above the key bar); when there is less content than rows, the
     * blank rows go on top. Every row is rewritten, so nothing from an earlier layout survives.
     */
    fun render(viewRows: Int, scroll: Int, cols: Int = 0): ByteArray {
        if (cols > 0) return renderReflowed(viewRows, scroll, cols)
        val s = scroll.coerceIn(0, maxScroll(viewRows))
        val end = if (window.isEmpty()) -1 else liveBottom() - s
        val start = end - viewRows + 1
        val sb = StringBuilder(START)
        for (r in 0 until viewRows) {
            val idx = start + r
            val body = if (idx in 0..end) line(idx) else ""
            sb.append("\u001b[").append(r + 1).append(";1H").append(body).append("\u001b[0m\u001b[K")
        }
        val c = cursor
        if (s == 0 && c != null) {
            val idx = history.size + c.first - 1
            if (idx in start..end) sb.append("\u001b[").append(idx - start + 1).append(';').append(c.second).append("H\u001b[?25h")
        }
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    private fun line(idx: Int): String = if (idx < history.size) history[idx] else window[idx - history.size]

    companion object {
        private const val START = "\u001b[?25l\u001b[H"
        private const val MAX_GROUP = 60
        private val ROW = Regex("\u001b\\[(\\d+);1H(.*?)\u001b\\[0m\u001b\\[K")
        private val CURSOR = Regex("\u001b\\[(\\d+);(\\d+)H\u001b\\[\\?25h$")

        /** Largest m (≥ 2, ≥ 2 non-blank) where the last m lines of [tail] equal the first m rows of [window]; else 0. */
        fun overlapWithTop(tail: List<String>, window: List<String>): Int {
            for (m in minOf(tail.size, window.size) downTo 2) {
                val part = tail.subList(tail.size - m, tail.size)
                if (part == window.subList(0, m) && part.count { it.isNotEmpty() } >= 2) return m
            }
            return 0
        }

        /**
         * How many lines [next] scrolled up relative to [prev]: the smallest k whose top run `next[i] == prev[i + k]`
         * covers at least min(8, half the remaining rows) rows including 3 non-blank ones. 0 when the top did not move
         * (or the screen was redrawn so nothing lines up). The bottom of the screen (input box, status) is free to
         * differ, which is why only a top run is required.
         */
        fun scrollShift(prev: List<String>, next: List<String>): Int {
            val h = prev.size
            if (h != next.size || h < 4) return 0
            fun run(k: Int): Pair<Int, Int> {
                var n = 0
                var nonBlank = 0
                while (n + k < h && next[n] == prev[n + k]) {
                    if (next[n].isNotEmpty()) nonBlank++
                    n++
                }
                return n to nonBlank
            }
            fun ok(k: Int): Boolean {
                val (n, nonBlank) = run(k)
                return n >= minOf(8, (h - k) / 2) && nonBlank >= 3
            }
            if (ok(0)) return 0
            for (k in 1 until h) if (ok(k)) return k
            return 0
        }
    }
}
