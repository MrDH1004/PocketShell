package com.neststudio.pocketshell.core

/**
 * How much past output the PC sends when a session opens (sessions ⋮ menu, 0.2.109). A long session can have
 * megabytes of output; replaying all of it makes opening slow. [replayKb] limits a PC session's replay (the PC keeps
 * up to 8 MB), [historyLines] a PC console's scrolled-off lines (the PC keeps 3000). 0 = everything.
 */
enum class TerminalHistory(val id: String, val replayKb: Int, val historyLines: Int) {
    SMALL("small", 256, 500),
    NORMAL("normal", 1024, 1500),
    LARGE("large", 4096, 3000),
    ALL("all", 0, 0);

    companion object {
        val DEFAULT = NORMAL

        fun of(id: String?): TerminalHistory = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}
