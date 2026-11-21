package com.neststudio.pocketshell.core

/**
 * The repository's PATCHNOTES.md (fetched from GitHub): one `## v<version> (<date>)` section per release, newest
 * first, with `- ` lines under it. Shown once after the app updates and from the ⋮ menu (패치내역).
 */
object PatchNotes {
    data class Entry(val version: SemVer, val date: String, val items: List<String>)

    private val heading = Regex("""^##\s+v?(\S+)\s*(?:\((.*)\))?\s*$""")
    private val bullet = Regex("""^\s*[-*+]\s+""")
    private val emphasis = Regex("""\*\*|__|`""")

    fun parse(text: String): List<Entry> {
        val out = mutableListOf<Entry>()
        var version: SemVer? = null
        var date = ""
        var items = mutableListOf<String>()
        fun flush() {
            version?.let { if (items.isNotEmpty()) out += Entry(it, date, items.toList()) }
        }
        for (raw in text.lineSequence()) {
            val line = raw.trimEnd()
            val h = heading.matchEntire(line)
            if (h != null) {
                flush()
                version = SemVer.parse(h.groupValues[1])
                date = h.groupValues[2].trim()
                items = mutableListOf()
                continue
            }
            if (line.startsWith("#")) { // any other heading ends the section
                flush()
                version = null
                items = mutableListOf()
                continue
            }
            if (version == null || line.isBlank()) continue
            val clean = emphasis.replace(line, "")
            if (bullet.containsMatchIn(clean)) items += bullet.replace(clean, "").trim()
            else if (items.isNotEmpty()) items[items.size - 1] = items.last() + " " + clean.trim() // wrapped line
        }
        flush()
        return out.sortedByDescending { it.version }
    }

    /**
     * Sections to show once after an update to [current]: the ones newer than [seen] up to [current]. With no [seen]
     * (first run with this feature, or a fresh install) only [current]'s own section.
     */
    fun since(entries: List<Entry>, seen: SemVer?, current: SemVer): List<Entry> =
        entries.filter { it.version <= current && (if (seen == null) it.version == current else it.version > seen) }

    fun format(entries: List<Entry>): String = entries.joinToString("\n\n") { e ->
        val head = "v${e.version}" + if (e.date.isNotEmpty()) " (${e.date})" else ""
        head + "\n" + e.items.joinToString("\n") { "• $it" }
    }
}
