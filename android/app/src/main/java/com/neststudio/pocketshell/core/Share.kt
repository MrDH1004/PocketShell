package com.neststudio.pocketshell.core

import org.json.JSONException
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

/** `POST /api/sessions/{id}/share` reply: the PIN the other person pairs with, and how to reach this PC. */
data class ShareInvite(
    val pin: String,
    val hosts: List<String>,
    val port: Int,
    val fpShort: String,
    val readOnly: Boolean,
    val hours: Int,
    val title: String,
) {
    /** "123 456" (easier to read out). */
    val pinSpaced: String get() = if (pin.length == 6) pin.substring(0, 3) + " " + pin.substring(3) else pin

    /** "192.168.0.10, 100.64.0.2" (port only when it is not the default). */
    val addresses: String
        get() = hosts.joinToString(", ") { h ->
            val host = if (h.contains(':')) "[$h]" else h
            if (port == PinPairing.DEFAULT_PORT) host else "$host:$port"
        }

    companion object {
        fun parse(text: String): ShareInvite {
            val o = try {
                JSONObject(text)
            } catch (e: JSONException) {
                throw IllegalArgumentException("서버 응답 형식 오류", e)
            }
            val pin = o.optString("pin", "")
            require(PinPairing.isValidPin(pin)) { "서버 응답에 PIN이 없습니다" }
            val hosts = o.optJSONArray("hosts")?.let { a -> (0 until a.length()).map { a.optString(it, "").trim() } }
                .orEmpty().filter { it.isNotEmpty() }
            return ShareInvite(
                pin = pin,
                hosts = hosts,
                port = o.optInt("port", PinPairing.DEFAULT_PORT),
                fpShort = o.optString("fpShort", ""),
                readOnly = o.optBoolean("readOnly", false),
                hours = o.optInt("hours", 0),
                title = if (o.isNull("title")) "" else o.optString("title", ""),
            )
        }
    }
}
