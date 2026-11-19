package com.neststudio.pocketshell.core

import org.json.JSONObject
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * This phone was invited to one session of someone else's PC (host ≥ 0.2.95): it reaches that session only, and
 * [readOnly] means it may watch but not type. Comes with the pairing claim and `/api/info.share`.
 */
data class ShareGrant(
    val sessionId: String,
    val readOnly: Boolean,
    /** ISO UTC; blank when the PC did not say. */
    val expiresAt: String,
    /** The session's name when it was shared (its folder). */
    val title: String,
) {
    val expiresAtMillis: Long? get() = parseIso(expiresAt)

    fun isExpired(nowMillis: Long): Boolean = expiresAtMillis?.let { nowMillis >= it } ?: false

    fun toJson(): JSONObject = JSONObject()
        .put("sessionId", sessionId)
        .put("readOnly", readOnly)
        .put("expiresAt", expiresAt)
        .put("title", title)

    companion object {
        fun fromJson(o: JSONObject?): ShareGrant? {
            if (o == null) return null
            val id = o.optString("sessionId", "").trim()
            if (id.isEmpty()) return null
            return ShareGrant(
                sessionId = id,
                readOnly = o.optBoolean("readOnly", false),
                expiresAt = if (o.isNull("expiresAt")) "" else o.optString("expiresAt", ""),
                title = if (o.isNull("title")) "" else o.optString("title", "").trim(),
            )
        }

        fun parseIso(s: String): Long? = try {
            if (s.isBlank()) null else Instant.parse(s).toEpochMilli()
        } catch (e: DateTimeParseException) {
            null
        }
    }
}
