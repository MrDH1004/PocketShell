package com.neststudio.pocketshell.core

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
    /** External only: AI CLI running in the console ("claude" | "gpt"). */
    val agent: String? = null,
    /** Live AI sessions: [AgentState] value, null when the host does not know (or is older than 0.2.59). */
    val agentState: String? = null,
    /** The question shown while [agentState] is waiting. */
    val prompt: AgentPrompt? = null,
    /** The host may delete this session's folder with it (a folder right under a project root; host ≥ 0.2.62). */
    val folderDeletable: Boolean = false,
    /** Web servers running in the session's folder (listening ports, host ≥ 0.2.88). */
    val webPorts: List<Int> = emptyList(),
    /** People the session is shared with, open PINs included (owner's list, host ≥ 0.2.95). */
    val shares: Int = 0,
) {
    val isExternal: Boolean get() = kind == KIND_EXTERNAL

    /**
     * Name shown for the session (list card, terminal top bar): the project folder's name when the folder is known.
     * Claude Code / Codex set the window title to the conversation's topic ("✳ 조작 가능한 모든 것 MCP"), which says
     * little about where it runs. Without a folder: the title, then the shell, then the id.
     */
    val displayTitle: String
        get() = projectName(cwd) ?: title.ifBlank { shell.ifBlank { id } }

    /** Shown under 최근; opening it POSTs resume (host history, or a closed PC console reopened in its folder). */
    val isHistory: Boolean get() = kind == KIND_HISTORY || kind == KIND_RECENT || (kind == KIND_HOST && state == STATE_EXITED)
    val isRunningHost: Boolean get() = kind == KIND_HOST && state == STATE_RUNNING

    companion object {
        const val KIND_HOST = "host"
        const val KIND_EXTERNAL = "external"
        const val KIND_HISTORY = "history"
        /** A PC console that was closed (host ≥ 0.2.13). */
        const val KIND_RECENT = "recent"
        const val STATE_RUNNING = "running"
        const val STATE_EXITED = "exited"

        /** Last folder name of a PC path ("E:\AIWork\flowdesk\" → "flowdesk", "C:\" → "C:"), null when blank. */
        fun projectName(cwd: String): String? =
            cwd.trim().trimEnd('\\', '/').substringAfterLast('\\').substringAfterLast('/').ifBlank { null }

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
            agent = if (o.has("agent") && !o.isNull("agent")) o.optString("agent") else null,
            agentState = if (o.has("agentState") && !o.isNull("agentState")) o.optString("agentState") else null,
            prompt = AgentPrompt.fromJson(o.optJSONObject("prompt")),
            folderDeletable = o.optBoolean("folderDeletable", false),
            webPorts = o.optJSONArray("webPorts")?.let { a -> (0 until a.length()).map { a.optInt(it) }.filter { it in 1..65535 } }.orEmpty(),
            shares = o.optInt("shares", 0).coerceAtLeast(0),
        )

        /** Parses `GET /api/sessions` → `{sessions:[...]}`. */
        fun listFromJson(text: String): List<SessionInfo> {
            val arr: JSONArray = JSONObject(text).optJSONArray("sessions") ?: return emptyList()
            return (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
        }
    }
}

/** What a long press on a session row offers. */
enum class SessionAction { SHARE, UNSHARE, END, DELETE, DELETE_WITH_FOLDER }

object SessionActions {
    /**
     * A running session can be ended (its record stays under 최근) or deleted; a record can be deleted. A console
     * opened on the PC can only be ended. Deleting the folder too is offered only when the host allows it. A live
     * session can be shared with someone, and sharing stopped while anyone has it.
     */
    fun of(s: SessionInfo): List<SessionAction> = buildList {
        if (s.isExternal || s.isRunningHost) {
            add(SessionAction.SHARE)
            if (s.shares > 0) add(SessionAction.UNSHARE)
        }
        addAll(manage(s))
    }

    private fun manage(s: SessionInfo): List<SessionAction> = when {
        s.isExternal -> listOf(SessionAction.END)
        else -> buildList {
            if (s.isRunningHost) add(SessionAction.END)
            add(SessionAction.DELETE)
            if (s.folderDeletable && s.cwd.isNotBlank()) add(SessionAction.DELETE_WITH_FOLDER)
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
        const val DEFAULT_RECENT_LIMIT = 8

        /**
         * [recentLimit]: newest N entries under 최근 (0 hides the section). A folder shows once on the whole screen:
         * 최근 leaves out folders open under 실행 중 / 외부 and keeps only the newest entry per folder, so reopening
         * a 최근 entry moves it up instead of adding another.
         */
        fun of(all: List<SessionInfo>, recentLimit: Int = DEFAULT_RECENT_LIMIT): SessionSections {
            val sessions = all.filterNot { isPlainCmd(it) || isSystem32Console(it) }
            val running = sessions.filter { it.isRunningHost }.sortedByDescending { it.lastActivity }
            val external = sessions.filter { it.isExternal }.sortedBy { it.pid ?: Int.MAX_VALUE }
            val open = (running + external).mapTo(HashSet(), ::folderKey)
            val recent = sessions.filter { it.isHistory && folderKey(it) !in open }.sortedByDescending { it.lastActivity }
                .distinctBy(::folderKey).take(recentLimit.coerceAtLeast(0))
            return SessionSections(running, external, recent)
        }

        /**
         * A PC console still titled with cmd's own path ("C:\WINDOWS\System32\cmd.exe", "관리자: …"): nothing named
         * runs in it, so it is not listed under 외부 or 최근.
         */
        private fun isPlainCmd(s: SessionInfo): Boolean =
            (s.isExternal || s.kind == SessionInfo.KIND_RECENT) &&
                s.title.trim().endsWith("\\system32\\cmd.exe", ignoreCase = true)

        /**
         * A PC console sitting in C:\Windows\System32 (where an administrator terminal opens): no project there, so
         * it is not listed under 외부 or 최근. A live console with Claude/GPT running in it still shows.
         */
        private fun isSystem32Console(s: SessionInfo): Boolean =
            !s.isRunningHost && !(s.isExternal && s.agent != null) && SYSTEM32.matches(s.cwd.trim().trimEnd('\\', '/'))

        private val SYSTEM32 = Regex("""^[A-Za-z]:\\windows\\system32$""", RegexOption.IGNORE_CASE)

        /** The folder, case and trailing separator ignored (as on Windows); no folder → the session's own id. */
        private fun folderKey(s: SessionInfo): String {
            val dir = s.cwd.trim().trimEnd('\\', '/').lowercase()
            return if (dir.isEmpty()) "id:" + s.id else dir
        }
    }
}

/** Server → client text control messages on the WebSocket (DESIGN.md §5). */
sealed class ServerMessage {
    data class Hello(val session: SessionInfo?) : ServerMessage()
    data class Size(val cols: Int, val rows: Int) : ServerMessage()
    data class Exit(val code: Int) : ServerMessage()
    data object ReplayEnd : ServerMessage()
    /** External consoles: lines that scrolled off the PC window before this connection (oldest first). */
    data class History(val lines: List<String>) : ServerMessage()
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
                "history" -> History(o.optJSONArray("lines")?.let { a -> (0 until a.length()).map { a.optString(it, "") } } ?: emptyList())
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

    /** `wss://host:port/ws/tunnel?host=T&port=P` (host ≥ 0.2.81): a TCP connection from the PC to T:P. */
    fun tunnelUrl(host: String, port: Int, target: String, targetPort: Int): String {
        val h = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
        return "wss://$h:$port/ws/tunnel?host=${urlEncode(target)}&port=$targetPort"
    }

    fun baseUrl(host: String, port: Int): String {
        val h = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
        return "https://$h:$port"
    }

    private fun urlEncode(s: String): String = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
