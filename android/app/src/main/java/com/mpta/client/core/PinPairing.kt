package com.mpta.client.core

import org.json.JSONException
import org.json.JSONObject

/** PIN pairing helpers (DESIGN.md §12.1). */
object PinPairing {
    const val DEFAULT_PORT = 7380
    const val DISCOVERY_PORT = 7381
    const val DISCOVERY_REQUEST = "MPTA?1"
    const val DISCOVERY_WINDOW_MS = 1500L

    /** Strips spaces and hyphens the user may type ("123 456", "123-456"). */
    fun normalizePin(input: String): String = input.filterNot { it.isWhitespace() || it == '-' }

    /** Exactly six ASCII digits. */
    fun isValidPin(pin: String): Boolean = pin.length == 6 && pin.all { it in '0'..'9' }

    /**
     * Short fingerprint shown on both screens: first 8 bytes of the certificate SHA-256 as
     * `XXXX-XXXX-XXXX-XXXX` (uppercase hex). Accepts the full fingerprint in any [FingerprintPinning.normalize] form.
     */
    fun fpShort(fingerprint: String): String {
        val fp = FingerprintPinning.normalize(fingerprint)
        require(FingerprintPinning.isValidFingerprint(fp)) { "인증서 지문 형식이 올바르지 않습니다" }
        return fp.substring(0, 16).uppercase().chunked(4).joinToString("-")
    }

    fun fpShortOfDer(der: ByteArray): String = fpShort(FingerprintPinning.sha256Hex(der))

    private fun compactFpShort(s: String) = s.filterNot { it == '-' || it == ':' || it.isWhitespace() }.uppercase()

    /** 16 hex digits once separators are removed. */
    fun isValidFpShort(s: String): Boolean =
        compactFpShort(s).let { c -> c.length == 16 && c.all { it in '0'..'9' || it in 'A'..'F' } }

    /** Compares two fpShort strings ignoring case and separators; false if either is malformed. */
    fun fpShortEquals(a: String, b: String): Boolean =
        isValidFpShort(a) && isValidFpShort(b) && compactFpShort(a) == compactFpShort(b)
}

/** Host + port typed by the user (Tailscale or a LAN host the broadcast did not reach). */
data class HostAddress(val host: String, val port: Int) {
    companion object {
        /**
         * Accepts `192.168.0.5`, `192.168.0.5:7380`, `my-pc.tail1234.ts.net`, `[fd7a::1]:7380`, `fd7a::1`.
         * Missing port → [PinPairing.DEFAULT_PORT]. Returns null if invalid.
         */
        fun parse(input: String): HostAddress? {
            val s = input.trim()
            if (s.isEmpty() || s.any { it.isWhitespace() || it == '/' }) return null
            val host: String
            val portText: String?
            if (s.startsWith("[")) {
                val end = s.indexOf(']')
                if (end <= 1) return null
                host = s.substring(1, end)
                val rest = s.substring(end + 1)
                portText = when {
                    rest.isEmpty() -> null
                    rest.startsWith(":") -> rest.substring(1)
                    else -> return null
                }
            } else if (s.count { it == ':' } > 1) {
                host = s // bare IPv6 without port
                portText = null
            } else {
                val colon = s.indexOf(':')
                host = if (colon >= 0) s.substring(0, colon) else s
                portText = if (colon >= 0) s.substring(colon + 1) else null
            }
            if (host.isEmpty()) return null
            if (!host.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == ':' || it == '%' }) return null
            val port = if (portText == null) {
                PinPairing.DEFAULT_PORT
            } else {
                portText.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            }
            return HostAddress(host, port)
        }
    }
}

/** A host that answered the LAN discovery broadcast (DESIGN.md §12.2). Carries no secrets. */
data class DiscoveredHost(
    val name: String,
    val hosts: List<String>,
    val port: Int,
    val fpShort: String,
) {
    companion object {
        /**
         * Parses the UDP reply `{"v":1,"name","hosts":[...],"port":7380,"fpShort"}`. [sourceIp] (the datagram's
         * source address, which is known to be reachable) is put first in [hosts]. Returns null for anything that
         * is not a valid v1 reply, since arbitrary datagrams may arrive on the socket.
         */
        fun parse(text: String, sourceIp: String? = null): DiscoveredHost? {
            val obj = try {
                JSONObject(text.trim())
            } catch (e: JSONException) {
                return null
            }
            if (obj.optInt("v", -1) != 1) return null
            val port = obj.optInt("port", -1)
            if (port !in 1..65535) return null
            val fpShort = obj.optString("fpShort", "").trim().uppercase()
            if (!PinPairing.isValidFpShort(fpShort)) return null
            val listed = obj.optJSONArray("hosts")?.let { arr ->
                (0 until arr.length()).map { arr.optString(it, "").trim() }.filter { it.isNotEmpty() }
            } ?: emptyList()
            val hosts = (listOfNotNull(sourceIp?.trim()?.takeIf { it.isNotEmpty() }) + listed).distinct()
            if (hosts.isEmpty()) return null
            val name = obj.optString("name", "").ifBlank { hosts.first() }
            return DiscoveredHost(name, hosts, port, fpShort)
        }

        /** Merges replies of the same PC (same fpShort and port) that arrived via several interfaces. */
        fun merge(replies: List<DiscoveredHost>): List<DiscoveredHost> =
            replies.groupBy { it.fpShort to it.port }.values.map { group ->
                group.first().copy(hosts = group.flatMap { it.hosts }.distinct())
            }
    }
}

/** `POST /api/pair/claim` response for either code or pin: `{deviceId, token, fp}`. */
data class ClaimResult(val deviceId: String, val token: String, val fingerprint: String?) {
    companion object {
        fun parse(text: String): ClaimResult {
            val o = try {
                JSONObject(text)
            } catch (e: JSONException) {
                throw IllegalArgumentException("서버 응답 형식 오류", e)
            }
            val id = o.optString("deviceId", "")
            val token = o.optString("token", "")
            require(id.isNotEmpty() && token.isNotEmpty()) { "서버 응답에 deviceId/token이 없습니다" }
            val fp = if (o.isNull("fp")) null else o.optString("fp", "").ifBlank { null }
            return ClaimResult(id, token, fp?.let { FingerprintPinning.normalize(it) })
        }
    }
}
