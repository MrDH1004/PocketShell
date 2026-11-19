package com.neststudio.pocketshell.core

/**
 * Web addresses in terminal text, for long-press: `https://…`, and bare dev-server addresses such as
 * `localhost:5173`, `127.0.0.1:8000/docs` or `0.0.0.0:3000` (opened as http, 0.0.0.0 as localhost).
 */
object TerminalLinks {
    private val URL = Regex("""https?://[^\s'"<>`]+""", RegexOption.IGNORE_CASE)
    private val BARE = Regex(
        """(?<![\w./:-])(localhost|127\.0\.0\.1|0\.0\.0\.0|\[::1]|\d{1,3}(?:\.\d{1,3}){3}):\d{2,5}(?:/[^\s'"<>`]*)?""",
        RegexOption.IGNORE_CASE,
    )
    private const val TRAILING = ".,;:!?)]}'\"…>"

    data class Link(val start: Int, val end: Int, val url: String)

    /** Every address in [text] with its character range (end exclusive). */
    fun all(text: String): List<Link> {
        val found = mutableListOf<Link>()
        for (m in URL.findAll(text)) found += trimmed(m.range.first, m.value)
        for (m in BARE.findAll(text)) {
            if (found.any { m.range.first in it.start until it.end }) continue
            found += trimmed(m.range.first, m.value)
        }
        return found.sortedBy { it.start }.map { it.copy(url = normalize(it.url)) }
    }

    /**
     * The address under display column [column] of [text] (wide characters take [width] columns), or the only one on
     * the line when the touch is beside it; null if there is none.
     */
    fun at(text: String, column: Int, width: (Int) -> Int = { 1 }): String? {
        val links = all(text)
        if (links.isEmpty()) return null
        var col = 0
        var index = text.length
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val w = width(cp).coerceAtLeast(1)
            if (column < col + w) { index = i; break }
            col += w
            i += Character.charCount(cp)
        }
        links.firstOrNull { index in it.start until it.end }?.let { return it.url }
        return links.singleOrNull()?.url
    }

    private fun trimmed(start: Int, raw: String): Link {
        var s = raw
        while (s.isNotEmpty() && s.last() in TRAILING) {
            // keep a closing bracket that has its opening one inside the address (wiki-style URLs)
            if (s.last() == ')' && s.count { it == '(' } >= s.count { it == ')' }) break
            s = s.dropLast(1)
        }
        return Link(start, start + s.length, s)
    }

    private fun normalize(url: String): String {
        val withScheme = if (url.startsWith("http://", true) || url.startsWith("https://", true)) url else "http://$url"
        return withScheme.replaceFirst(Regex("""^(https?://)0\.0\.0\.0(?=[:/]|$)""", RegexOption.IGNORE_CASE), "$1localhost")
    }
}
