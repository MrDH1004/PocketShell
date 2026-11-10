package com.neststudio.pocketshell.core

import org.json.JSONException
import org.json.JSONObject

/** PC host update state from `/api/info.update` / `GET /api/update` (DESIGN.md §11.3). */
data class HostUpdateStatus(
    val current: String,
    val latest: String,
    val state: String,
    val error: String?,
) {
    /**
     * "PC 업데이트 적용" is offered only when there is something to apply. After a failed apply (state "error") the host
     * keeps the newer release for an explicit retry, so it is offered again when latest > current.
     */
    val canApply: Boolean
        get() = state == STATE_AVAILABLE || state == STATE_READY || (state == STATE_ERROR && latestIsNewer())

    private fun latestIsNewer(): Boolean {
        val l = SemVer.parse(latest) ?: return false
        val c = SemVer.parse(current) ?: return false
        return l > c
    }

    /** Whether the status bar should be shown at all ("none" means up to date). */
    val isVisible: Boolean get() = state != STATE_NONE

    companion object {
        const val STATE_NONE = "none"
        const val STATE_AVAILABLE = "available"
        const val STATE_DOWNLOADING = "downloading"
        const val STATE_READY = "ready"
        const val STATE_APPLYING = "applying"
        const val STATE_ERROR = "error"

        /** Parses the `update` member of `/api/info`. Null when absent (older host) or malformed. */
        fun fromInfo(infoJson: String): HostUpdateStatus? {
            val info = try {
                JSONObject(infoJson)
            } catch (e: JSONException) {
                return null
            }
            val u = info.optJSONObject("update") ?: return null
            return fromJson(u)
        }

        fun fromJson(u: JSONObject): HostUpdateStatus? {
            val state = u.optString("state", "")
            if (state.isEmpty()) return null
            return HostUpdateStatus(
                current = if (u.isNull("current")) "" else u.optString("current", ""),
                latest = if (u.isNull("latest")) "" else u.optString("latest", ""),
                state = state,
                error = if (u.isNull("error")) null else u.optString("error", "").ifBlank { null },
            )
        }

        /** `runningSessions` from the 409 body of `POST /api/update/apply`; null if not present. */
        fun runningSessionsFrom409(body: String): Int? = try {
            val o = JSONObject(body)
            when {
                o.isNull("runningSessions") -> null
                else -> o.optJSONArray("runningSessions")?.length() ?: o.optInt("runningSessions", -1).takeIf { it >= 0 }
            }
        } catch (e: JSONException) {
            null
        }
    }
}

/** App-start update check is limited to once per day (DESIGN.md §11.4). */
object UpdatePolicy {
    const val AUTO_CHECK_INTERVAL_MS = 3L * 60 * 60 * 1000

    /** True if never checked, 3 hours have passed, or the clock went backwards. */
    fun shouldAutoCheck(lastCheckMillis: Long, nowMillis: Long): Boolean =
        lastCheckMillis <= 0 || nowMillis < lastCheckMillis || nowMillis - lastCheckMillis >= AUTO_CHECK_INTERVAL_MS
}
