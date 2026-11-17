package com.neststudio.pocketshell.core

import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Everything a phone needs to pair, in one link / QR code:
 * `pocketshell://pair?h=192.168.0.10,100.64.0.2&p=7380&pin=123456&fp=<sha256 hex>&n=flowdesk`.
 * The full fingerprint lets the pairing screen check the certificate itself instead of asking the user to compare.
 */
data class PairLink(
    val hosts: List<String>,
    val port: Int,
    val pin: String,
    /** Full certificate SHA-256 (normalized hex), or null when the link did not carry one. */
    val fingerprint: String?,
    /** What is being shared (session name), for display only. */
    val name: String,
) {
    fun toUri(): String = buildString {
        append(SCHEME).append("://").append(HOST).append("?h=").append(enc(hosts.joinToString(",")))
        append("&p=").append(port)
        append("&pin=").append(pin)
        fingerprint?.let { append("&fp=").append(it) }
        if (name.isNotBlank()) append("&n=").append(enc(name))
    }

    /** "192.168.0.10" or "192.168.0.10:47999" for the address field. */
    val firstAddress: String
        get() {
            val h = hosts.firstOrNull().orEmpty()
            val host = if (h.contains(':')) "[$h]" else h
            return if (port == PinPairing.DEFAULT_PORT) host else "$host:$port"
        }

    companion object {
        const val SCHEME = "pocketshell"
        const val HOST = "pair"

        /** Null for anything that is not a usable pairing link (wrong scheme, no host, bad PIN or port). */
        fun parse(text: String?): PairLink? {
            val uri = try {
                URI(text?.trim() ?: return null)
            } catch (e: URISyntaxException) {
                return null
            }
            if (!SCHEME.equals(uri.scheme, ignoreCase = true) || !HOST.equals(uri.host, ignoreCase = true)) return null
            val q = (uri.rawQuery ?: return null).split('&').mapNotNull { kv ->
                val i = kv.indexOf('=')
                if (i <= 0) null else kv.substring(0, i) to dec(kv.substring(i + 1))
            }.toMap()
            val hosts = q["h"].orEmpty().split(',').map { it.trim() }.filter { HostAddress.parse(it) != null }
            val port = q["p"]?.toIntOrNull() ?: PinPairing.DEFAULT_PORT
            val pin = PinPairing.normalizePin(q["pin"].orEmpty())
            if (hosts.isEmpty() || port !in 1..65535 || !PinPairing.isValidPin(pin)) return null
            val fp = q["fp"]?.let { FingerprintPinning.normalize(it) }?.takeIf { FingerprintPinning.isValidFingerprint(it) }
            return PairLink(hosts, port, pin, fp, q["n"].orEmpty())
        }

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
        private fun dec(s: String) = try {
            URLDecoder.decode(s, "UTF-8")
        } catch (e: IllegalArgumentException) {
            ""
        }
    }
}
