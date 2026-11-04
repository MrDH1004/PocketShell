package com.mpta.client.core

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Session model (DESIGN.md §3). Only fields the app uses are kept. */
data class SessionInfo(
    val id: String,
    val kind: String,
    val title: String,
    val shell: String,
    val cwd: String,
    val state: String,
    val createdAt: String,
    val lastActivity: String,
    val cols: Int,
    val rows: Int,
    val pid: Int?,
    val resumedFrom: String?,
) {
    val isExternal: Boolean get() = kind == KIND_EXTERNAL
    val isHistory: Boolean get() = kind == KIND_HISTORY || (kind == KIND_HOST && state == STATE_EXITED)
    val isRunningHost: Boolean get() = kind == KIND_HOST && state == STATE_RUNNING

    companion object {
        const val KIND_HOST = "host"
        const val KIND_EXTERNAL = "external"
        const val KIND_HISTORY = "history"
        const val STATE_RUNNING = "running"
        const val STATE_EXITED = "exited"

        fun fromJson(o: JSONObject): SessionInfo = SessionInfo(
            id = o.getString("id"),
            kind = o.optString("kind", KIND_HOST),
            title = o.optString("title", ""),
            shell = o.optString("shell", ""),
            cwd = o.optString("cwd", ""),
            state = o.optString("state", STATE_RUNNING),
            createdAt = o.optString("createdAt", ""),
            lastActivity = o.optString("lastActivity", ""),
            cols = o.optInt("cols", 0),
            rows = o.optInt("rows", 0),
            pid = if (o.has("pid") && !o.isNull("pid")) o.optInt("pid") else null,
            resumedFrom = if (o.has("resumedFrom") && !o.isNull("resumedFrom")) o.optString("resumedFrom") else null,
        )

        /** Parses `GET /api/sessions` → `{sessions:[...]}`. */
        fun listFromJson(text: String): List<SessionInfo> {
            val arr: JSONArray = JSONObject(text).optJSONArray("sessions") ?: return emptyList()
            return (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
        }
    }
}

/** Session list split into the three UI sections (실행 중 / 외부 / 최근). */
data class SessionSections(
    val running: List<SessionInfo>,
    val external: List<SessionInfo>,
    val recent: List<SessionInfo>,
) {
    companion object {
        fun of(sessions: List<SessionInfo>): SessionSections = SessionSections(
            running = sessions.filter { it.isRunningHost }.sortedByDescending { it.lastActivity },
            external = sessions.filter { it.isExternal }.sortedBy { it.pid ?: Int.MAX_VALUE },
            recent = sessions.filter { it.isHistory }.sortedByDescending { it.lastActivity },
        )
    }
}

/** Server → client text control messages on the WebSocket (DESIGN.md §5). */
sealed class ServerMessage {
    data class Hello(val session: SessionInfo?) : ServerMessage()
    data class Size(val cols: Int, val rows: Int) : ServerMessage()
    data class Exit(val code: Int) : ServerMessage()
    data object ReplayEnd : ServerMessage()
    data class Unknown(val type: String) : ServerMessage()

    companion object {
        /** Returns null for text that is not a JSON object with a "type" string. */
        fun parse(text: String): ServerMessage? {
            val o = try {
                JSONObject(text)
            } catch (e: JSONException) {
                return null
            }
            val type = o.optString("type", "")
            if (type.isEmpty()) return null
            return when (type) {
                "hello" -> Hello(o.optJSONObject("session")?.let { runCatching { SessionInfo.fromJson(it) }.getOrNull() })
                "size" -> Size(o.optInt("cols", 0), o.optInt("rows", 0))
                "exit" -> Exit(o.optInt("code", 0))
                "replay-end" -> ReplayEnd
                else -> Unknown(type)
            }
        }
    }
}

/** Client → server messages and the WebSocket URL. */
object ClientProtocol {
    fun resizeMessage(cols: Int, rows: Int): String =
        JSONObject().put("type", "resize").put("cols", cols).put("rows", rows).toString()

    /**
     * `wss://host:port/ws/sessions/{id}?client=android&cols=N&rows=N&openPcTab=1`.
     * The token is sent only in the `Authorization: Bearer` header, never in the URL (keeps it out of logs).
     */
    fun webSocketUrl(host: String, port: Int, sessionId: String, cols: Int, rows: Int, openPcTab: Boolean): String {
        val h = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
        return "wss://$h:$port/ws/sessions/${urlEncode(sessionId)}" +
            "?client=android&cols=$cols&rows=$rows&openPcTab=${if (openPcTab) 1 else 0}"
    }

    fun baseUrl(host: String, port: Int): String {
        val h = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
        return "https://$h:$port"
    }

    private fun urlEncode(s: String): String = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
