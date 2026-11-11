package com.neststudio.pocketshell.core

import org.json.JSONException
import org.json.JSONObject

/** A daily time range in which notifications arrive silently. Times are minutes of the day (0..1439). */
object QuietHours {
    const val DEFAULT_START = 23 * 60
    const val DEFAULT_END = 7 * 60

    /** True when [minute] lies in [start, end). The range may pass midnight (23:00–07:00); start == end is never. */
    fun contains(start: Int, end: Int, minute: Int): Boolean = when {
        start == end -> false
        start < end -> minute in start until end
        else -> minute >= start || minute < end
    }

    fun format(minutes: Int): String = "%02d:%02d".format(minutes / 60, minutes % 60)
}

/**
 * The buttons a "waiting" notification gets. Android shows three: with more choices than that, the first two and
 * one that opens the session, so no choice is silently dropped.
 */
object PromptActions {
    const val MAX = 3

    /** [options] keep their position in the prompt (the label shows its number). */
    data class Plan(val options: List<IndexedValue<PromptOption>>, val openForMore: Boolean)

    fun plan(options: List<PromptOption>): Plan =
        if (options.size <= MAX) Plan(options.withIndex().toList(), false)
        else Plan(options.withIndex().take(MAX - 1), true)
}

/** Phrases the user sends often ("계속해"): one per line in the editor, sent with Enter. */
object Snippets {
    const val MAX = 20
    const val MAX_LENGTH = 200
    val DEFAULTS = listOf("계속해", "테스트 돌려줘", "커밋해")

    /** Editor text → phrases: blank lines and repeats dropped, at most [MAX], each at most [MAX_LENGTH] characters. */
    fun parse(text: String): List<String> =
        text.lines().map { it.trim().take(MAX_LENGTH) }.filter { it.isNotEmpty() }.distinct().take(MAX)

    fun toText(snippets: List<String>): String = snippets.joinToString("\n")
}

/** What the host answers when an update would end running sessions (`POST /api/update/apply` → 409). */
data class UpdateRefusal(
    /** Running host sessions, null when the host did not say. */
    val running: Int?,
    /** Sessions whose AI CLI is in the middle of a turn (hosts before 0.2.61: always 0). */
    val working: Int,
    /** One line per session the update would end, for the confirmation. */
    val sessions: List<Line>,
) {
    data class Line(val title: String, val agentState: String?)

    companion object {
        fun parse(body: String): UpdateRefusal = try {
            val o = JSONObject(body)
            val arr = o.optJSONArray("sessions")
            val lines = (0 until (arr?.length() ?: 0)).mapNotNull { i ->
                val s = arr?.optJSONObject(i) ?: return@mapNotNull null
                val title = s.optString("title", "").ifBlank { s.optString("id", "") }
                if (title.isBlank()) null else Line(title, if (s.isNull("agentState")) null else s.optString("agentState"))
            }
            UpdateRefusal(HostUpdateStatus.runningSessionsFrom409(body), o.optInt("workingAgents", 0), lines)
        } catch (e: JSONException) {
            UpdateRefusal(null, 0, emptyList())
        }
    }
}
