package com.neststudio.pocketshell.core

import org.json.JSONException
import org.json.JSONObject

/** Last known AI state of one PC for the home-screen widget and the quick settings tile; null summary = unreachable. */
data class PcStatus(val summary: AgentSummary?, val at: Long) {
    val online: Boolean get() = summary != null

    fun toJson(): String = JSONObject().apply {
        put("at", at)
        summary?.let { put("working", it.working).put("waiting", it.waiting).put("done", it.done) }
    }.toString()

    companion object {
        fun fromJson(text: String?): PcStatus? {
            if (text.isNullOrEmpty()) return null
            return try {
                val o = JSONObject(text)
                val summary = if (o.has("working")) AgentSummary(o.optInt("working"), o.optInt("waiting"), o.optInt("done")) else null
                PcStatus(summary, o.optLong("at", 0))
            } catch (e: JSONException) {
                null
            }
        }
    }
}

/**
 * All paired PCs folded into one line of counts plus who is unreachable. A PC never checked counts as neither
 * online nor offline ([unknown]).
 */
data class BoardTotals(
    val working: Int,
    val waiting: Int,
    val done: Int,
    val online: List<String>,
    val offline: List<String>,
    val unknown: List<String>,
    /** Oldest check among the PCs that have one (0 = none). */
    val oldest: Long,
) {
    val agents: Int get() = working + waiting + done

    companion object {
        /** [pcs] are (id, display name) in list order. */
        fun of(pcs: List<Pair<String, String>>, statuses: Map<String, PcStatus>): BoardTotals {
            var working = 0
            var waiting = 0
            var done = 0
            val online = ArrayList<String>()
            val offline = ArrayList<String>()
            val unknown = ArrayList<String>()
            var oldest = 0L
            for ((id, name) in pcs) {
                val s = statuses[id]
                if (s == null) {
                    unknown.add(name)
                    continue
                }
                if (oldest == 0L || s.at < oldest) oldest = s.at
                val a = s.summary
                if (a == null) {
                    offline.add(name)
                } else {
                    online.add(name)
                    working += a.working
                    waiting += a.waiting
                    done += a.done
                }
            }
            return BoardTotals(working, waiting, done, online, offline, unknown, oldest)
        }
    }
}
