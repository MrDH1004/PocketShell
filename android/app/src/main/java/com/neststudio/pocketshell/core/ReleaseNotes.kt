package com.neststudio.pocketshell.core

/**
 * Turns a GitHub release body into short plain text for the update dialog: drops the auto-generated
 * "Full Changelog" compare line (it repeats both version numbers and confuses the installed/new display),
 * markdown headings/emphasis, and turns list markers into bullets.
 */
object ReleaseNotes {
    private val changelog = Regex("""^\**Full Changelog\**:.*$""", RegexOption.IGNORE_CASE)
    private val heading = Regex("""^#{1,6}\s*""")
    private val bullet = Regex("""^\s*[-*+]\s+""")
    private val emphasis = Regex("""\*\*|__|`""")
    private val prSuffix = Regex("""\s+by @\S+ in https?://\S+$""")

    fun clean(body: String): String =
        body.lineSequence()
            .map { it.trim() }
            .filterNot { changelog.matches(it) }
            .filterNot { it.equals("## What's Changed", ignoreCase = true) || it.equals("What's Changed", ignoreCase = true) }
            .map { line ->
                val noHead = heading.replace(line, "")
                val bulleted = if (bullet.containsMatchIn(noHead)) "• " + bullet.replace(noHead, "") else noHead
                emphasis.replace(prSuffix.replace(bulleted, ""), "")
            }
            .joinToString("\n")
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()
}
