package com.neststudio.pocketshell.core

/** What another app shared to PocketShell ("share → PocketShell"), and where it may go. */
object ShareTargets {
    /** The host's `POST /api/sessions/{id}/input` takes at most this many characters. */
    const val MAX_TEXT = 4000

    /**
     * The text to type: the shared text, with the subject first (a space between) when the app sent one that the text does not
     * already start with (browsers share a page as subject = title, text = URL). CRLF becomes LF; no trailing
     * newline, so nothing is submitted. Null when there is nothing to type.
     */
    fun text(text: String?, subject: String?): String? {
        val body = text?.replace("\r\n", "\n")?.trim().orEmpty()
        val title = subject?.replace("\r\n", "\n")?.trim().orEmpty()
        val joined = when {
            title.isEmpty() || body.startsWith(title) -> body
            body.isEmpty() -> title
            else -> "$title $body" // a space: a line break is lost in a shell and splits a Claude prompt
        }
        return joined.ifEmpty { null }
    }

    /** [text] cut to [MAX_TEXT] characters without splitting a surrogate pair. */
    fun fit(text: String): String {
        if (text.length <= MAX_TEXT) return text
        var end = MAX_TEXT
        if (Character.isHighSurrogate(text[end - 1])) end--
        return text.substring(0, end)
    }

    /**
     * Live sessions that can take input, best first: an AI waiting on a question, then any AI session, then the
     * rest; most recent activity first within each.
     */
    fun order(sessions: List<SessionInfo>): List<SessionInfo> = orderBy(sessions) { it }

    /** [order] for items that carry a session (e.g. paired with the PC it runs on). */
    fun <T> orderBy(items: List<T>, session: (T) -> SessionInfo): List<T> =
        items.filter { session(it).let { s -> !s.isHistory && (s.isRunningHost || s.isExternal) } }
            .sortedWith(
                compareBy<T> {
                    val s = session(it)
                    when {
                        s.agentState == AgentState.WAITING -> 0
                        s.agentState != null || s.agent != null -> 1
                        else -> 2
                    }
                }.thenByDescending { session(it).lastActivity },
            )
}
