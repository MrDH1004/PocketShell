package com.neststudio.pocketshell.desktop

import com.neststudio.pocketshell.core.DiscoveredHost
import com.neststudio.pocketshell.core.HostAddress
import com.neststudio.pocketshell.core.PinPairing
import com.neststudio.pocketshell.data.Pairing
import com.neststudio.pocketshell.net.ApiClient
import com.neststudio.pocketshell.net.PinnedHttp
import com.neststudio.pocketshell.net.TofuProbe
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

/** Pairing stopped for a reason the user should read (wrong certificate, cancelled). */
class PairStopped(message: String) : Exception(message)

/**
 * PIN pairing (DESIGN.md §12.1), the Android PairingActivity flow:
 * 1) read the certificate the PC presents (typed address first, then the other advertised ones),
 * 2) a discovered PC's fpShort must match it,
 * 3) the user compares the fpShort with the PC's 휴대폰 연결 window ([confirm]),
 * 4) claim over a client pinned to exactly that certificate, and check the fp the PC returns,
 * 5) name + every address (LAN + Tailscale) from /api/info.
 */
object PinPair {
    suspend fun pair(
        addr: HostAddress, disc: DiscoveredHost?, pin: String, deviceName: String, confirm: suspend (String) -> Boolean,
    ): Pairing {
        val candidates = (listOf(addr.host) + disc?.hosts.orEmpty()).distinct()
        var observed: String? = null
        var reached: String? = null
        var lastError: IOException? = null
        for (h in candidates) {
            try {
                observed = TofuProbe.fingerprint(h, addr.port)
                reached = h
                break
            } catch (e: IOException) {
                lastError = e
            }
        }
        if (observed == null || reached == null) throw lastError ?: IOException("접속할 주소가 없음")
        val short = PinPairing.fpShort(observed)
        if (disc != null && !PinPairing.fpShortEquals(disc.fpShort, short))
            throw PairStopped("검색된 PC의 인증서 지문(${disc.fpShort})과 접속한 곳의 지문($short)이 다름. 다른 기기가 응답했을 수 있음.")
        if (!confirm(short)) throw PairStopped("연결 취소")

        val http = PinnedHttp.client(observed)
        val claim = ApiClient(listOf(reached), addr.port, null, http).claimPin(pin, deviceName)
        val returned = claim.fingerprint ?: throw PairStopped("PC가 인증서 지문을 보내지 않음. PC를 업데이트하세요.")
        if (returned != observed) throw PairStopped("PC가 알려 준 인증서 지문이 접속한 인증서와 다름. 연결 중단.")

        var name = disc?.name ?: reached
        val hosts = ArrayList<String>().apply { add(reached); addAll(disc?.hosts.orEmpty()) }
        try {
            val info = JSONObject(ApiClient(listOf(reached), addr.port, claim.token, http).info())
            name = info.optString("name", "").ifBlank { name }
            info.optJSONArray("hosts")?.let { a -> for (i in 0 until a.length()) a.optString(i, "").trim().takeIf { it.isNotEmpty() }?.let(hosts::add) }
        } catch (e: IOException) {
            Log.warn("info after pairing failed: ${e.message}")
        } catch (e: JSONException) {
            Log.warn("info after pairing unreadable: ${e.message}")
        }
        return Pairing(name, hosts.distinct(), addr.port, observed, claim.deviceId, claim.token, share = claim.share)
    }
}
