package com.mpta.client.net

import android.util.Log
import com.mpta.client.core.ClaimResult
import com.mpta.client.core.ClientProtocol
import com.mpta.client.core.FingerprintHostnameVerifier
import com.mpta.client.core.FingerprintTrustManager
import com.mpta.client.core.ProjectOptions
import com.mpta.client.core.SessionInfo
import com.mpta.client.core.serverErrorText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext

/** Non-2xx HTTP response. [body] is the raw response body (e.g. the 409 `{error, runningSessions}`). */
class ApiException(val code: Int, message: String, val body: String = "") : IOException(message)

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
 * REST client for the host (DESIGN.md §4). Each request tries the hosts in [HostSelector] order (Tailscale first
 * when it is on, then same LAN) and moves to the next host only on a connection-level failure.
 */
class ApiClient(
    val hosts: HostSelector,
    private val port: Int,
    private val token: String?,
    val http: OkHttpClient,
) {
    constructor(hosts: List<String>, port: Int, token: String?, http: OkHttpClient) :
        this(HostSelector.fixed(hosts), port, token, http)

    suspend fun request(
        method: String,
        path: String,
        body: JSONObject? = null,
        auth: Boolean = true,
        rawBody: RequestBody? = null,
    ): String =
        withContext(Dispatchers.IO) {
            val failures = ArrayList<String>()
            var lastError: IOException? = null
            for (host in hosts.ordered()) {
                val builder = Request.Builder().url(ClientProtocol.baseUrl(host, port) + path)
                if (auth && token != null) builder.header("Authorization", "Bearer $token")
                val reqBody = when {
                    rawBody != null -> rawBody
                    body != null -> body.toString().toRequestBody(JSON)
                    method == "POST" -> "{}".toRequestBody(JSON)
                    else -> null
                }
                builder.method(method, reqBody)
                try {
                    http.newCall(builder.build()).execute().use { resp ->
                        hosts.reportSuccess(host)
                        val text = resp.body?.string().orEmpty()
                        if (!resp.isSuccessful) throw ApiException(resp.code, httpErrorMessage(resp.code, text), text)
                        return@withContext text
                    }
                } catch (e: ApiException) {
                    throw e
                } catch (e: IOException) {
                    lastError = e
                    failures.add("$host: ${e.message ?: e.javaClass.simpleName}")
                    Log.w(TAG, "$method $path via $host failed", e)
                }
            }
            if (failures.size <= 1) throw lastError ?: IOException("접속할 호스트가 없습니다")
            throw IOException(failures.joinToString("\n"), lastError)
        }

    suspend fun sessions(): List<SessionInfo> = SessionInfo.listFromJson(request("GET", "/api/sessions"))

    suspend fun createSession(shell: String, title: String?, cols: Int?, rows: Int?): SessionInfo {
        val body = JSONObject().put("shell", shell)
        if (!title.isNullOrBlank()) body.put("title", title)
        if (cols != null && rows != null) body.put("cols", cols).put("rows", rows)
        return SessionInfo.fromJson(JSONObject(request("POST", "/api/sessions", body)))
    }

    /** `GET /api/projects`; hosts older than 0.2.9 answer 404. */
    suspend fun projectOptions(): ProjectOptions = ProjectOptions.parse(request("GET", "/api/projects"))

    /** `POST /api/projects`: `<root>\<name>` (created if missing) + a session there running the agent CLI. */
    suspend fun createProject(root: String, name: String, agent: String): SessionInfo {
        val body = JSONObject().put("root", root).put("name", name).put("agent", agent)
        return SessionInfo.fromJson(JSONObject(request("POST", "/api/projects", body)))
    }

    suspend fun resume(historyId: String): SessionInfo =
        SessionInfo.fromJson(JSONObject(request("POST", "/api/sessions/${historyId}/resume")))


    /** New host session with an external console's shell, folder and size (`POST /api/external/{pid}/adopt`). */
    suspend fun newTerminalHere(pid: Int): SessionInfo =
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

    /**
     * PIN claim (DESIGN.md §12.1): `{pin, deviceName}` → `{deviceId, token, fp}`. This client must already be
     * pinned to the fingerprint the user confirmed; the caller also checks the returned fp against it.
     */
    suspend fun claimPin(pin: String, deviceName: String): ClaimResult {
        val text = request("POST", "/api/pair/claim", JSONObject().put("pin", pin).put("deviceName", deviceName), auth = false)
        return try {
            ClaimResult.parse(text)
        } catch (e: IllegalArgumentException) {
            throw IOException(e.message, e)
        }
    }

    /** Raw `GET /api/info` JSON (`{name, version, fp, port, hosts, update?}`). */
    suspend fun info(): String = request("GET", "/api/info")

    /** `POST /api/update/apply {force}`; throws [ApiException] 409 while host sessions are running and !force. */
    suspend fun applyHostUpdate(force: Boolean) {
        request("POST", "/api/update/apply", JSONObject().put("force", force))
    }

    /** `POST /api/clipboard/image` (PNG body): the PC puts it on its clipboard for an Alt+V paste. */
    suspend fun postClipboardImage(png: ByteArray) {
        request("POST", "/api/clipboard/image", rawBody = png.toRequestBody(PNG))
    }

    private fun httpErrorMessage(code: Int, body: String): String = when (code) {
        401 -> "인증 실패 (401) — 기기 등록이 취소되었거나 코드가 만료되었습니다"
        404 -> "찾을 수 없음 (404)"
        409 -> "요청 충돌 (409) ${body.take(200)}"
        429 -> "시도 횟수 초과 (429) — 잠시 후 다시 시도하세요"
        400 -> serverErrorText(body) ?: "잘못된 요청 (400) ${body.take(200)}"
        else -> "서버 오류 ($code) ${body.take(200)}"
    }

    private companion object {
        const val TAG = "ApiClient"
        val JSON = "application/json; charset=utf-8".toMediaType()
        val PNG = "image/png".toMediaType()
    }
}
