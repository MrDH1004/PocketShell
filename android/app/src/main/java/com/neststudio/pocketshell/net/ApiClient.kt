package com.neststudio.pocketshell.net

import android.util.Log
import com.neststudio.pocketshell.core.AgentEvents
import com.neststudio.pocketshell.core.ClaimResult
import com.neststudio.pocketshell.core.ClientProtocol
import com.neststudio.pocketshell.core.FileListing
import com.neststudio.pocketshell.core.FingerprintHostnameVerifier
import com.neststudio.pocketshell.core.FingerprintTrustManager
import com.neststudio.pocketshell.core.ProjectOptions
import com.neststudio.pocketshell.core.SessionInfo
import com.neststudio.pocketshell.core.serverErrorText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.io.OutputStream
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
        client: OkHttpClient = http,
    ): String {
        val reqBody = when {
            rawBody != null -> rawBody
            body != null -> body.toString().toRequestBody(JSON)
            method == "POST" -> "{}".toRequestBody(JSON)
            else -> null
        }
        return call(method, path, reqBody, auth, client) { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw ApiException(resp.code, httpErrorMessage(resp.code, text), text)
            text
        }
    }

    /**
     * Sends the request to the hosts in order and hands the first response to [handle]. Only a failure to get a
     * response moves to the next host; an error inside [handle] (e.g. a download cut off) is not retried, since it
     * may already have written data.
     */
    private suspend fun <T> call(
        method: String,
        path: String,
        reqBody: RequestBody?,
        auth: Boolean,
        client: OkHttpClient,
        handle: (Response) -> T,
    ): T = withContext(Dispatchers.IO) {
        val failures = ArrayList<String>()
        var lastError: IOException? = null
        for (host in hosts.ordered()) {
            val builder = Request.Builder().url(ClientProtocol.baseUrl(host, port) + path)
            if (auth && token != null) builder.header("Authorization", "Bearer $token")
            builder.method(method, reqBody)
            val resp = try {
                client.newCall(builder.build()).execute()
            } catch (e: IOException) {
                lastError = e
                failures.add("$host: ${e.message ?: e.javaClass.simpleName}")
                Log.w(TAG, "$method $path via $host failed", e)
                continue
            }
            hosts.reportSuccess(host)
            return@withContext resp.use(handle)
        }
        if (failures.size <= 1) throw lastError ?: IOException("접속할 호스트가 없습니다")
        throw IOException(failures.joinToString("\n"), lastError)
    }

    private fun q(v: String): String = java.net.URLEncoder.encode(v, "UTF-8")

    suspend fun session(id: String): SessionInfo = SessionInfo.fromJson(JSONObject(request("GET", "/api/sessions/$id")))

    /** `GET /api/files?path=` — folder listing (hosts older than 0.2.20 answer 404). */
    suspend fun listFiles(path: String): FileListing = FileListing.parse(request("GET", "/api/files?path=${q(path)}"))

    /** `GET /api/files/content?path=` streamed into [out]; [progress] gets (bytes so far, total or -1). */
    suspend fun download(path: String, out: OutputStream, progress: (Long, Long) -> Unit = { _, _ -> }) {
        val slow = http.newBuilder().readTimeout(60, TimeUnit.SECONDS).build()
        call("GET", "/api/files/content?path=${q(path)}", null, true, slow) { resp ->
            if (!resp.isSuccessful) {
                val text = resp.body?.string().orEmpty()
                throw ApiException(resp.code, httpErrorMessage(resp.code, text), text)
            }
            val body = resp.body ?: throw IOException("빈 응답")
            val total = body.contentLength()
            body.byteStream().use { input ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    progress(done, total)
                }
            }
        }
    }

    /** `GET /api/app/android` — the phone app the PC host ships (version, name, size, sha256); 404 when it has none. */
    suspend fun androidApp(): String = request("GET", "/api/app/android")

    /** `GET /api/app/android/apk` handed to [handle] as (stream, length or -1). */
    suspend fun <T> androidApk(handle: (java.io.InputStream, Long) -> T): T {
        val slow = http.newBuilder().readTimeout(60, TimeUnit.SECONDS).build()
        return call("GET", "/api/app/android/apk", null, true, slow) { resp ->
            if (!resp.isSuccessful) {
                val text = resp.body?.string().orEmpty()
                throw ApiException(resp.code, httpErrorMessage(resp.code, text), text)
            }
            val body = resp.body ?: throw IOException("빈 응답")
            body.byteStream().use { handle(it, body.contentLength()) }
        }
    }

    /** `POST /api/files/upload?dir=&name=[&overwrite=1]`; 409 when the file exists and !overwrite. */
    suspend fun upload(dir: String, name: String, body: RequestBody, overwrite: Boolean) {
        val slow = http.newBuilder().writeTimeout(120, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).build()
        val ow = if (overwrite) "&overwrite=1" else ""
        request("POST", "/api/files/upload?dir=${q(dir)}&name=${q(name)}$ow", rawBody = body, client = slow)
    }

    /**
     * Long poll for "an agent stopped" events after [after] (-1 = only ask where the log is). The host answers as soon
     * as there is one, or after [waitSeconds]; [client] must allow a read that long.
     */
    suspend fun events(after: Long, waitSeconds: Int, client: OkHttpClient): AgentEvents =
        AgentEvents.parse(request("GET", "/api/events?after=$after&wait=$waitSeconds", client = client))

    /** Types [text] into a session without opening it (quick reply); [enter] adds the Enter key. */
    suspend fun sendInput(id: String, text: String, enter: Boolean) {
        request("POST", "/api/sessions/$id/input", JSONObject().put("text", text).put("enter", enter))
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

    /** Deletes a session (a running one is ended first); [withFolder] also moves its folder to the PC's Recycle Bin. */
    suspend fun delete(id: String, withFolder: Boolean = false) {
        request("DELETE", "/api/sessions/$id" + if (withFolder) "?folder=1" else "")
    }

    /** Ends a live session; a host session stays under 최근, a PC console's programs are terminated. */
    suspend fun end(id: String) {
        request("POST", "/api/sessions/$id/end")
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

    /**
     * `POST /api/update/apply {force, killWorking}`; throws [ApiException] 409 while host sessions are running and
     * ![force], and (hosts from 0.2.61) while an AI CLI is mid-turn in one of them and ![killWorking]. The 409 body
     * lists the sessions (UpdateRefusal).
     */
    suspend fun applyHostUpdate(force: Boolean, killWorking: Boolean = false) {
        request("POST", "/api/update/apply", JSONObject().put("force", force).put("killWorking", killWorking))
    }

    /**
     * `POST /api/clipboard/image` (PNG/JPEG body): the PC puts it on its clipboard for an Alt+V paste. Images are
     * MBs, so the upload gets longer timeouts than the 15 s of the small REST calls (slow Tailscale relay / mobile).
     */
    suspend fun postClipboardImage(image: ByteArray, jpeg: Boolean) {
        val slow = http.newBuilder().writeTimeout(120, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
        request("POST", "/api/clipboard/image", rawBody = image.toRequestBody(if (jpeg) JPEG else PNG), client = slow)
    }

    private fun httpErrorMessage(code: Int, body: String): String = when (code) {
        401 -> "인증 실패 (401) — 기기 등록이 취소되었거나 코드가 만료되었습니다"
        404 -> "찾을 수 없음 (404)"
        409 -> serverErrorText(body) ?: "요청 충돌 (409) ${body.take(200)}"
        429 -> "시도 횟수 초과 (429) — 잠시 후 다시 시도하세요"
        400 -> serverErrorText(body) ?: "잘못된 요청 (400) ${body.take(200)}"
        else -> serverErrorText(body) ?: "서버 오류 ($code) ${body.take(200)}"
    }

    private companion object {
        const val TAG = "ApiClient"
        val JSON = "application/json; charset=utf-8".toMediaType()
        val PNG = "image/png".toMediaType()
        val JPEG = "image/jpeg".toMediaType()
    }
}
