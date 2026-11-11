package com.neststudio.pocketshell.core

/** Plain text of terminal rows, for copying and searching (pure, unit-tested). */
object TerminalText {
    private val ESCAPE = Regex("\u001b\\[[0-9;?<>=]*[ -/]*[@-~]|\u001b\\][^\u0007\u001b]*(?:\u0007|\u001b\\\\)|\u001b[()][0-9A-Za-z]|\u001b[=>78DEHM]")

    /** [vt] without escape sequences and control characters (line breaks and tabs stay). */
    fun strip(vt: String): String {
        val s = ESCAPE.replace(vt, "")
        val sb = StringBuilder(s.length)
        for (c in s) {
            if (c == '\r') continue
            if (c == '\n' || c == '\t' || c >= ' ') sb.append(c)
        }
        return sb.toString()
    }

    /**
     * Rows as text: trailing spaces of each row and blank rows at the end removed, at most [maxLines] newest rows
     * (a selectable text view gets slow far beyond that).
     */
    fun join(rows: List<String>, maxLines: Int = 5000): String {
        val trimmed = rows.map { it.trimEnd() }.dropLastWhile { it.isEmpty() }
        return trimmed.takeLast(maxLines).joinToString("\n")
    }

    /** Start offsets of every occurrence of [query] in [text], ignoring case; none for a blank query. */
    fun find(text: String, query: String, limit: Int = 2000): List<Int> {
        if (query.isBlank()) return emptyList()
        val out = ArrayList<Int>()
        var at = text.indexOf(query, 0, ignoreCase = true)
        while (at >= 0 && out.size < limit) {
            out.add(at)
            at = text.indexOf(query, at + query.length, ignoreCase = true)
        }
        return out
    }

    /**
     * Index of the match to show first: the last one (the newest output is at the end, which is where the view opens).
     * -1 when there is none.
     */
    fun firstShown(matches: List<Int>): Int = matches.size - 1

    /** Next / previous match index, wrapping around. */
    fun step(current: Int, count: Int, forward: Boolean): Int = when {
        count <= 0 -> -1
        forward -> (current + 1) % count
        else -> (current - 1 + count) % count
    }
}
