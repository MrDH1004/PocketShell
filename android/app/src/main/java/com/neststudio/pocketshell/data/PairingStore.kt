package com.neststudio.pocketshell.data

import android.content.Context
import com.neststudio.pocketshell.core.ShareGrant
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

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
