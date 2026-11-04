package com.mpta.client.net

import com.mpta.client.core.ClientProtocol
import com.mpta.client.core.FingerprintHostnameVerifier
import com.mpta.client.core.FingerprintTrustManager
import com.mpta.client.core.SessionInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext

/** Non-2xx HTTP response. */
class ApiException(val code: Int, message: String) : IOException(message)

object PinnedHttp {
    /** OkHttp client that trusts only the certificate with [fingerprint] (DESIGN.md §2). */
    fun client(fingerprint: String): OkHttpClient {
        val tm = FingerprintTrustManager(fingerprint)
        val ssl = SSLContext.getInstance("TLS")
        ssl.init(null, arrayOf(tm), SecureRandom())
        return OkHttpClient.Builder()
            .sslSocketFactory(ssl.socketFactory, tm)
            .hostnameVerifier(FingerprintHostnameVerifier(fingerprint))
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .pingInterval(15, TimeUnit.SECONDS)
            .build()
    }
}

/**
 * REST client for the host (DESIGN.md §4). Each request tries the hosts in order (LAN first, then
 * Tailscale) and moves to the next host only on a connection-level failure.
 */
class ApiClient(
    private val hosts: List<String>,
    private val port: Int,
    private val token: String?,
    val http: OkHttpClient,
) {
    suspend fun request(method: String, path: String, body: JSONObject? = null, auth: Boolean = true): String =
        withContext(Dispatchers.IO) {
            var lastError: IOException? = null
            for (host in hosts) {
                val builder = Request.Builder().url(ClientProtocol.baseUrl(host, port) + path)
                if (auth && token != null) builder.header("Authorization", "Bearer $token")
                val reqBody = when {
                    body != null -> body.toString().toRequestBody(JSON)
                    method == "POST" -> "{}".toRequestBody(JSON)
                    else -> null
                }
                builder.method(method, reqBody)
                try {
                    http.newCall(builder.build()).execute().use { resp ->
                        val text = resp.body?.string().orEmpty()
                        if (!resp.isSuccessful) throw ApiException(resp.code, httpErrorMessage(resp.code, text))
                        return@withContext text
                    }
                } catch (e: ApiException) {
                    throw e
                } catch (e: IOException) {
                    lastError = e
                }
            }
            throw lastError ?: IOException("접속할 호스트가 없습니다")
        }

    suspend fun sessions(): List<SessionInfo> = SessionInfo.listFromJson(request("GET", "/api/sessions"))

    suspend fun createSession(shell: String, title: String?, cols: Int?, rows: Int?): SessionInfo {
        val body = JSONObject().put("shell", shell)
        if (!title.isNullOrBlank()) body.put("title", title)
        if (cols != null && rows != null) body.put("cols", cols).put("rows", rows)
        return SessionInfo.fromJson(JSONObject(request("POST", "/api/sessions", body)))
    }

    suspend fun resume(historyId: String): SessionInfo =
        SessionInfo.fromJson(JSONObject(request("POST", "/api/sessions/${historyId}/resume")))

    suspend fun adopt(pid: Int): SessionInfo =
        SessionInfo.fromJson(JSONObject(request("POST", "/api/external/$pid/adopt")))

    suspend fun delete(id: String) {
        request("DELETE", "/api/sessions/$id")
    }

    /** `POST /api/pair/claim` → (deviceId, token). */
    suspend fun claim(code: String, deviceName: String): Pair<String, String> {
        val text = request("POST", "/api/pair/claim", JSONObject().put("code", code).put("deviceName", deviceName), auth = false)
        val o = JSONObject(text)
        return o.getString("deviceId") to o.getString("token")
    }

    private fun httpErrorMessage(code: Int, body: String): String = when (code) {
        401 -> "인증 실패 (401) — 기기 등록이 취소되었거나 코드가 만료되었습니다"
        404 -> "찾을 수 없음 (404)"
        else -> "서버 오류 ($code) ${body.take(200)}"
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
