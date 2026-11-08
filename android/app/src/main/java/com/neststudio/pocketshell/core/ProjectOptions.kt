package com.neststudio.pocketshell.core

import org.json.JSONException
import org.json.JSONObject

/** An AI CLI found on the PC (`claude` | `codex`). */
data class ProjectAgent(val id: String, val name: String)

/** `GET /api/projects`: folders set in the PC tray app and AI CLIs installed on the PC. */
data class ProjectOptions(val roots: List<String>, val agents: List<ProjectAgent>) {
    companion object {
        fun parse(json: String): ProjectOptions {
            val o = JSONObject(json)
            val roots = o.optJSONArray("roots")
            val agents = o.optJSONArray("agents")
            return ProjectOptions(
                roots = (0 until (roots?.length() ?: 0)).mapNotNull { i -> roots?.optString(i)?.takeIf { it.isNotBlank() } },
                agents = (0 until (agents?.length() ?: 0)).mapNotNull { i ->
                    val a = agents?.optJSONObject(i) ?: return@mapNotNull null
                    val id = a.optString("id")
                    if (id.isBlank()) null else ProjectAgent(id, a.optString("name").ifBlank { id })
                },
            )
        }

        /** Last path segment, for showing a root compactly ("E:\AIWork\RNDAIProjects" → "RNDAIProjects"). */
        fun folderName(path: String): String = path.trimEnd('\\', '/').substringAfterLast('\\').substringAfterLast('/').ifBlank { path }
    }
}

/** The host's `{"error": "..."}` body text, or null. */
fun serverErrorText(body: String): String? = try {
    JSONObject(body).optString("error").takeIf { it.isNotBlank() }
} catch (e: JSONException) {
    null
}
