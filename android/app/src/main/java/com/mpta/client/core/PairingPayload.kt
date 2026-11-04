package com.mpta.client.core

import org.json.JSONException
import org.json.JSONObject

/**
 * QR payload from `POST /api/pair/start` (DESIGN.md §2):
 * `{"v":1,"name":"<PC>","hosts":["192.168.x.x","100.x.x.x"],"port":7380,"fp":"<sha256hex>","code":"<code>"}`
 */
data class PairingPayload(
    val name: String,
    val hosts: List<String>,
    val port: Int,
    val fingerprint: String,
    val code: String,
) {
    companion object {
        /** Parses and validates; throws [IllegalArgumentException] with a Korean message on bad input. */
        fun parse(text: String): PairingPayload {
            val obj = try {
                JSONObject(text.trim())
            } catch (e: JSONException) {
                throw IllegalArgumentException("페어링 정보가 올바른 JSON이 아닙니다", e)
            }
            val v = obj.optInt("v", -1)
            require(v == 1) { "지원하지 않는 페어링 버전입니다 (v=$v)" }

            val name = obj.optString("name", "").ifBlank { "PC" }

            val hostsJson = obj.optJSONArray("hosts")
                ?: throw IllegalArgumentException("hosts 항목이 없습니다")
            val hosts = (0 until hostsJson.length())
                .map { hostsJson.optString(it, "").trim() }
                .filter { it.isNotEmpty() }
            require(hosts.isNotEmpty()) { "접속할 호스트 주소가 없습니다" }

            val port = obj.optInt("port", -1)
            require(port in 1..65535) { "포트 값이 올바르지 않습니다 ($port)" }

            val fp = FingerprintPinning.normalize(obj.optString("fp", ""))
            require(FingerprintPinning.isValidFingerprint(fp)) { "인증서 지문(fp)이 올바르지 않습니다" }

            val code = obj.optString("code", "").trim()
            require(code.isNotEmpty()) { "페어링 코드가 없습니다" }

            return PairingPayload(name, hosts, port, fp, code)
        }
    }
}
