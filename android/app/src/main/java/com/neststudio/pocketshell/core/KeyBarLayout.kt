package com.neststudio.pocketshell.core

/**
 * Which keys the terminal key bar shows, in which order (saved as comma-separated ids). Keys can be hidden
 * (key bar settings) and moved left or right (long-press a key).
 */
object KeyBarLayout {
    /** Every key the bar can show. */
    val ALL = listOf(
        "esc", "tab", "enter", "snippets", "ctrl", "alt",
        "left", "up", "down", "right", "slash", "pipe", "tilde", "minus", "home", "end", "pgup", "pgdn",
    )

    /** The bar as it was before it could be customised (CTRL and ALT hidden). */
    val DEFAULT = ALL - listOf("ctrl", "alt")

    /** Saved text → shown keys in order; unknown ids and repeats dropped; nothing saved → [DEFAULT]. */
    fun parse(text: String?): List<String> {
        if (text == null) return DEFAULT
        return text.split(',').map { it.trim() }.filter { it in ALL }.distinct()
    }

    fun toText(keys: List<String>): String = keys.joinToString(",")

    /** [id] moved one place left ([delta] = -1) or right (+1); unchanged at the ends. */
    fun move(keys: List<String>, id: String, delta: Int): List<String> {
        val i = keys.indexOf(id)
        val j = i + delta
        if (i < 0 || j !in keys.indices) return keys
        return keys.toMutableList().also { it[i] = it[j]; it[j] = id }
    }

    /**
     * The settings list: shown keys in their order, then the hidden ones in [ALL] order, with whether each is on.
     */
    fun settingsRows(shown: List<String>): List<Pair<String, Boolean>> =
        shown.map { it to true } + ALL.filter { it !in shown }.map { it to false }

    /** Back from the settings list: the checked keys, keeping the list's order. */
    fun fromSettings(rows: List<Pair<String, Boolean>>): List<String> = rows.filter { it.second }.map { it.first }
}
