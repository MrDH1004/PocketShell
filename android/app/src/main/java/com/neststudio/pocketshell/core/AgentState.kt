package com.neststudio.pocketshell.core

import org.json.JSONException
import org.json.JSONObject

/** What an AI CLI session is doing, as the PC host reports it (DESIGN.md §14). */
object AgentState {
    const val WORKING = "working"
    /** Stopped on a question with numbered choices (approval prompt). */
    const val WAITING = "waiting"
    /** Stopped with no question: the turn is finished. */
    const val DONE = "done"
}

/**
 * One choice of a prompt. [key] is what to type to pick it: its number, or arrow keys from the highlighted choice
 * (possibly none); [enter] says Enter must follow (lists picked with arrows).
 */
data class PromptOption(val key: String, val label: String, val enter: Boolean = false)

/** The question an agent is waiting on. */
data class AgentPrompt(val question: String, val options: List<PromptOption>) {
    companion object {
        fun fromJson(o: JSONObject?): AgentPrompt? {
            if (o == null) return null
            val arr = o.optJSONArray("options") ?: return null
            val options = (0 until arr.length()).mapNotNull { i ->
                val e = arr.optJSONObject(i) ?: return@mapNotNull null
                val key = e.optString("key", "")
                val enter = e.optBoolean("enter", false)
                // The highlighted choice of an arrow list needs no key at all, only Enter.
                if (key.isEmpty() && !enter) null else PromptOption(key, e.optString("label", ""), enter)
            }
            return if (options.isEmpty()) null else AgentPrompt(o.optString("question", ""), options)
        }
    }
}

/** One "the agent stopped" event from `GET /api/events`. */
data class AgentEvent(
    val seq: Long,
    val sessionId: String,
    val title: String,
    val agent: String?,
    val state: String,
    val prompt: AgentPrompt?,
    /** "done" only: the last lines the agent wrote (hosts from 0.2.61). */
    val preview: String? = null,
)

/** Reply of `GET /api/events`: where the host's log is now and the events after the `after` we sent. */
data class AgentEvents(val epoch: String, val seq: Long, val events: List<AgentEvent>) {
    companion object {
        @Throws(JSONException::class)
        fun parse(json: String): AgentEvents {
            val o = JSONObject(json)
            val arr = o.optJSONArray("events")
            val events = (0 until (arr?.length() ?: 0)).mapNotNull { i ->
                val e = arr?.optJSONObject(i) ?: return@mapNotNull null
                val state = e.optString("state", "")
                val id = e.optString("sessionId", "")
                if (state.isEmpty() || id.isEmpty()) return@mapNotNull null
                AgentEvent(
                    seq = e.optLong("seq", 0),
                    sessionId = id,
                    title = e.optString("title", ""),
                    agent = if (e.isNull("agent")) null else e.optString("agent"),
                    state = state,
                    prompt = AgentPrompt.fromJson(e.optJSONObject("prompt")),
                    preview = if (e.isNull("preview")) null else e.optString("preview").ifBlank { null },
                )
            }
            return AgentEvents(o.optString("epoch", ""), o.optLong("seq", 0), events)
        }
    }
}

/** Where a phone is in one PC's event log. The host's seq starts over when it restarts (new epoch). */
data class EventCursor(val epoch: String, val seq: Long) {
    /** The cursor to continue from and the events to announce. [seq] is what to send as `after`. */
    data class Step(val cursor: EventCursor, val announce: List<AgentEvent>)

    fun accept(reply: AgentEvents): Step = when {
        // First contact (`after=-1`): only learn where the log is; what happened before is not ours to announce.
        this == START -> Step(EventCursor(reply.epoch, reply.seq), emptyList())
        // Host restarted: our seq meant its old log. Ask again from the start of the new one.
        reply.epoch != epoch -> Step(EventCursor(reply.epoch, 0), emptyList())
        else -> {
            val fresh = reply.events.filter { it.seq > seq }
            Step(EventCursor(epoch, maxOf(seq, fresh.maxOfOrNull { it.seq } ?: seq)), fresh)
        }
    }

    companion object {
        val START = EventCursor("", -1)
    }
}

/** How many AI sessions of a PC are in each state (`/api/info.agents`). */
data class AgentSummary(val working: Int, val waiting: Int, val done: Int) {
    val isEmpty: Boolean get() = working + waiting + done == 0

    companion object {
        fun fromJson(o: JSONObject?): AgentSummary? =
            if (o == null) null else AgentSummary(o.optInt("working", 0), o.optInt("waiting", 0), o.optInt("done", 0))
    }
}
