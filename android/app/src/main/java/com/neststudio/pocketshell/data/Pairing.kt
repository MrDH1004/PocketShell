package com.neststudio.pocketshell.data

import com.neststudio.pocketshell.core.ShareGrant
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Result of a successful pairing: everything needed to reach and authenticate to the host. */
data class Pairing(
    val pcName: String,
    val hosts: List<String>,
    val port: Int,
    val fingerprint: String,
    val deviceId: String,
    val token: String,
    /** Name the user gave this PC on the phone; blank = show [pcName]. */
    val alias: String = "",
    /** Someone else's PC that shared one session with this phone (null = this phone's own PC). */
    val share: ShareGrant? = null,
) {
    /** What the UI calls this PC. */
    val displayName: String get() = alias.ifBlank { pcName }

    /**
     * One entry per PC: the host's certificate fingerprint (re-pairing the same PC replaces its entry). A shared
     * session is its own entry, so it never replaces the owner's pairing with the same PC.
     */
    val id: String get() = if (share == null) fingerprint else fingerprint + "#" + share.sessionId
}

/** JSON form of the paired PC list (pure, unit-tested). */
object PairingCodec {
    fun encode(list: List<Pairing>): String = JSONArray().apply {
        for (p in list) put(
            JSONObject()
                .put("name", p.pcName)
                .put("hosts", JSONArray(p.hosts))
                .put("port", p.port)
                .put("fp", p.fingerprint)
                .put("deviceId", p.deviceId)
                .put("token", p.token)
                .put("alias", p.alias)
                .apply { p.share?.let { put("share", it.toJson()) } },
        )
    }.toString()

    /** Unreadable entries are skipped, never the whole list. */
    fun decode(json: String?): List<Pairing> {
        if (json.isNullOrBlank()) return emptyList()
        val arr = try { JSONArray(json) } catch (e: JSONException) { return emptyList() }
        val out = ArrayList<Pairing>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val fp = o.optString("fp", "")
            val token = o.optString("token", "")
            val hosts = o.optJSONArray("hosts")?.let { a -> (0 until a.length()).map { a.optString(it, "").trim() } }
                .orEmpty().filter { it.isNotEmpty() }
            if (fp.isEmpty() || token.isEmpty() || hosts.isEmpty()) continue
            out.add(Pairing(o.optString("name", "PC").ifBlank { "PC" }, hosts, o.optInt("port", 7380), fp, o.optString("deviceId", ""), token, o.optString("alias", "").trim(), ShareGrant.fromJson(o.optJSONObject("share"))))
        }
        return out.distinctBy { it.id }
    }

    /** Replaces the entry for the same PC in place, or appends a new PC. */
    fun upsert(list: List<Pairing>, p: Pairing): List<Pairing> =
        if (list.any { it.id == p.id }) list.map { if (it.id == p.id) p else it } else list + p
}
