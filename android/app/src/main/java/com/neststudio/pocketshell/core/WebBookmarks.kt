package com.neststudio.pocketshell.core

/** One saved address of the web view. */
data class WebBookmark(val title: String, val url: String)

/** Web view addresses: what the user typed → a URL, and the saved list (one "title\turl" per line). */
object WebBookmarks {
    /**
     * Typed text → URL. No scheme means http:// (intranet sites are mostly plain HTTP; an https one redirects).
     * Blank → null.
     */
    fun normalize(input: String): String? {
        val s = input.trim()
        if (s.isEmpty()) return null
        if (s.contains("://")) return s
        return "http://$s"
    }

    fun parse(text: String): List<WebBookmark> = text.lines().mapNotNull { line ->
        val tab = line.indexOf('\t')
        if (tab < 0) return@mapNotNull null
        val url = line.substring(tab + 1).trim()
        if (url.isEmpty()) null else WebBookmark(line.substring(0, tab).trim().ifEmpty { url }, url)
    }

    fun toText(list: List<WebBookmark>): String =
        list.joinToString("\n") { "${it.title.replace('\t', ' ').replace('\n', ' ')}\t${it.url}" }

    /** Adds [b] at the top; an existing entry with the same URL is replaced (moved up). */
    fun add(list: List<WebBookmark>, b: WebBookmark): List<WebBookmark> = listOf(b) + list.filter { it.url != b.url }

    fun remove(list: List<WebBookmark>, url: String): List<WebBookmark> = list.filter { it.url != url }
}
