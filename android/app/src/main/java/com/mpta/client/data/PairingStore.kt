package com.mpta.client.data

import android.content.Context

/** Result of a successful pairing: everything needed to reach and authenticate to the host. */
data class Pairing(
    val pcName: String,
    val hosts: List<String>,
    val port: Int,
    val fingerprint: String,
    val deviceId: String,
    val token: String,
)

/**
 * Stores the pairing in app-private SharedPreferences (MODE_PRIVATE, android:allowBackup="false" so it is
 * not copied off the device). EncryptedSharedPreferences (androidx.security-crypto) was not used because
 * that library is deprecated; the token only grants access to this one PC and can be revoked there
 * (`mpta revoke <id>`).
 */
class PairingStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): Pairing? {
        val token = prefs.getString(K_TOKEN, null) ?: return null
        val fp = prefs.getString(K_FP, null) ?: return null
        val hosts = prefs.getString(K_HOSTS, null)?.split('\n')?.filter { it.isNotBlank() } ?: return null
        if (hosts.isEmpty()) return null
        return Pairing(
            pcName = prefs.getString(K_NAME, "PC") ?: "PC",
            hosts = hosts,
            port = prefs.getInt(K_PORT, 7380),
            fingerprint = fp,
            deviceId = prefs.getString(K_DEVICE_ID, "") ?: "",
            token = token,
        )
    }

    fun save(p: Pairing) {
        prefs.edit()
            .putString(K_NAME, p.pcName)
            .putString(K_HOSTS, p.hosts.joinToString("\n"))
            .putInt(K_PORT, p.port)
            .putString(K_FP, p.fingerprint)
            .putString(K_DEVICE_ID, p.deviceId)
            .putString(K_TOKEN, p.token)
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val PREFS = "mpta_pairing"
        const val K_NAME = "pcName"
        const val K_HOSTS = "hosts"
        const val K_PORT = "port"
        const val K_FP = "fp"
        const val K_DEVICE_ID = "deviceId"
        const val K_TOKEN = "token"
    }
}
