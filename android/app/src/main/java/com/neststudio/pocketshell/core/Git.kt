package com.neststudio.pocketshell.core

import org.json.JSONObject

/** One changed file (`GET /api/git`, host ≥ 0.2.96). */
data class GitFile(
    val path: String,
    val from: String?,
    /** "M", "A", "D", "R", "?" (untracked), "U" (conflict), "T". */
    val status: String,
    val staged: Boolean,
    val added: Int?,
    val removed: Int?,
) {
    val isUntracked: Boolean get() = status == "?"
    val name: String get() = path.substringAfterLast('/')
    val folder: String get() = path.substringBeforeLast('/', "")
}

/** Uncommitted changes of the repository a folder is in. */
data class GitStatus(
    val root: String,
    val branch: String,
    val upstream: String?,
    val ahead: Int,
    val behind: Int,
    val files: List<GitFile>,
    val truncated: Boolean,
) {
    val totalAdded: Int get() = files.sumOf { it.added ?: 0 }
    val totalRemoved: Int get() = files.sumOf { it.removed ?: 0 }

    companion object {
        fun parse(text: String): GitStatus {
            val o = JSONObject(text)
            val arr = o.optJSONArray("files")
            val files = (0 until (arr?.length() ?: 0)).mapNotNull { i ->
                val f = arr!!.optJSONObject(i) ?: return@mapNotNull null
                val path = f.optString("path", "")
                if (path.isEmpty()) return@mapNotNull null
                GitFile(
                    path = path,
                    from = f.optStringOrNull("from"),
                    status = f.optString("status", "M"),
                    staged = f.optBoolean("staged", false),
                    added = if (f.isNull("added") || !f.has("added")) null else f.optInt("added"),
                    removed = if (f.isNull("removed") || !f.has("removed")) null else f.optInt("removed"),
                )
            }
            return GitStatus(
                root = o.optString("root", ""),
                branch = o.optString("branch", ""),
                upstream = o.optStringOrNull("upstream"),
                ahead = o.optInt("ahead", 0),
                behind = o.optInt("behind", 0),
                files = files,
                truncated = o.optBoolean("truncated", false),
            )
        }
    }
}

data class GitDiff(val path: String, val diff: String, val binary: Boolean, val truncated: Boolean) {
    companion object {
        fun parse(text: String): GitDiff {
            val o = JSONObject(text)
            return GitDiff(o.optString("path", ""), o.optString("diff", ""), o.optBoolean("binary", false), o.optBoolean("truncated", false))
        }
    }
}

/** How a diff line is drawn. */
enum class DiffLineKind { ADD, DEL, HUNK, META, CONTEXT }

object DiffLines {
    /** Kind of each line of a unified diff. "+++"/"---" file headers are META, not ADD/DEL. */
    fun kind(line: String): DiffLineKind = when {
        line.startsWith("+++ ") || line.startsWith("--- ") -> DiffLineKind.META
        line.startsWith("@@") -> DiffLineKind.HUNK
        line.startsWith("+") -> DiffLineKind.ADD
        line.startsWith("-") -> DiffLineKind.DEL
        line.startsWith("diff --git") || line.startsWith("index ") || line.startsWith("new file") ||
            line.startsWith("deleted file") || line.startsWith("similarity ") || line.startsWith("rename ") ||
            line.startsWith("old mode") || line.startsWith("new mode") || line.startsWith("Binary") ||
            line.startsWith("\\ No newline") -> DiffLineKind.META
        else -> DiffLineKind.CONTEXT
    }
}

private fun JSONObject.optStringOrNull(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key, "").ifBlank { null }
