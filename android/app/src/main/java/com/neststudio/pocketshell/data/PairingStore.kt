package com.neststudio.pocketshell.data

import android.content.Context
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
) {
    /** What the UI calls this PC. */
    val displayName: String get() = alias.ifBlank { pcName }

    /** One entry per PC: the host's certificate fingerprint (re-pairing the same PC replaces its entry). */
    val id: String get() = fingerprint
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
                .put("alias", p.alias),
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
            out.add(Pairing(o.optString("name", "PC").ifBlank { "PC" }, hosts, o.optInt("port", 7380), fp, o.optString("deviceId", ""), token, o.optString("alias", "").trim()))
        }
        return out.distinctBy { it.id }
    }

    /** Replaces the entry for the same PC in place, or appends a new PC. */
    fun upsert(list: List<Pairing>, p: Pairing): List<Pairing> =
        if (list.any { it.id == p.id }) list.map { if (it.id == p.id) p else it } else list + p
}

/**
 * Paired PCs in app-private SharedPreferences (MODE_PRIVATE, android:allowBackup="false" so they are not copied off
 * the device), plus which one is open. EncryptedSharedPreferences (androidx.security-crypto) was not used because
 * that library is deprecated; each token only grants access to its own PC and can be revoked there
 * (`psh revoke <id>`).
 */
class PairingStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun all(): List<Pairing> {
        migrateSingle()
        return PairingCodec.decode(prefs.getString(K_PCS, null))
    }

    fun get(id: String): Pairing? = all().firstOrNull { it.id == id }

    /** The PC currently open (selected on the PC list). */
    fun load(): Pairing? = activeId?.let(::get)

    var activeId: String?
        get() = prefs.getString(K_ACTIVE, null)
        set(v) { prefs.edit().putString(K_ACTIVE, v).apply() }

    /** Adds the PC, or replaces it when this phone paired with it before. */
    @Synchronized
    fun save(p: Pairing) {
        prefs.edit().putString(K_PCS, PairingCodec.encode(PairingCodec.upsert(all(), p))).apply()
    }

    @Synchronized
    fun remove(id: String) {
        val e = prefs.edit().putString(K_PCS, PairingCodec.encode(all().filter { it.id != id }))
        if (activeId == id) e.remove(K_ACTIVE)
        e.apply()
    }

    /** Up to 0.2.53 the app kept one PC in flat keys; move it into the list once. */
    private fun migrateSingle() {
        if (prefs.contains(K_PCS)) return
        val token = prefs.getString(K_TOKEN, null)
        val fp = prefs.getString(K_FP, null)
        val hosts = prefs.getString(K_HOSTS, null)?.split('\n')?.filter { it.isNotBlank() }.orEmpty()
        val list = if (token != null && fp != null && hosts.isNotEmpty()) {
            listOf(Pairing(prefs.getString(K_NAME, "PC") ?: "PC", hosts, prefs.getInt(K_PORT, 7380), fp, prefs.getString(K_DEVICE_ID, "") ?: "", token))
        } else emptyList()
        val e = prefs.edit().putString(K_PCS, PairingCodec.encode(list))
        list.firstOrNull()?.let { e.putString(K_ACTIVE, it.id) }
        listOf(K_NAME, K_HOSTS, K_PORT, K_FP, K_DEVICE_ID, K_TOKEN).forEach { e.remove(it) }
        e.apply()
    }

    private companion object {
        const val PREFS = "pkshell_pairing"
        const val K_PCS = "pcs"
        const val K_ACTIVE = "active"
        // Single-PC keys (migrated).
        const val K_NAME = "pcName"
        const val K_HOSTS = "hosts"
        const val K_PORT = "port"
        const val K_FP = "fp"
        const val K_DEVICE_ID = "deviceId"
        const val K_TOKEN = "token"
    }
}
