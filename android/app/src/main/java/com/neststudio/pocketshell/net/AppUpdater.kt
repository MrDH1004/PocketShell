package com.neststudio.pocketshell.net

import android.content.Context
import com.neststudio.pocketshell.BuildConfig
import com.neststudio.pocketshell.core.IntegrityException
import com.neststudio.pocketshell.core.ReleaseCandidate
import com.neststudio.pocketshell.core.Releases
import com.neststudio.pocketshell.core.Sha256
import com.neststudio.pocketshell.core.UpdatePolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** An update whose SHA-256 is known (from the asset digest or SHA256SUMS.txt). */
data class VerifiedUpdate(val candidate: ReleaseCandidate, val sha256: String)

/**
 * Android self-update (DESIGN.md §11.2/§11.4). Talks to GitHub with normal CA validation (certificate pinning
 * is only for the PC host). The release list URL can be overridden in debug builds for QA against a local
 * release server.
 */
class AppUpdater(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true) // GitHub asset URLs redirect to objects.githubusercontent.com
        .build()

    val currentVersion: String get() = BuildConfig.VERSION_NAME

    /** Effective release list URL; the override is honoured only in debug builds. */
    val releasesUrl: String
        get() = debugOverrideUrl?.takeIf { BuildConfig.DEBUG } ?: DEFAULT_RELEASES_URL

    var debugOverrideUrl: String?
        get() = prefs.getString(K_URL_OVERRIDE, null)?.takeIf { it.isNotBlank() }
        set(value) {
            prefs.edit().apply {
                if (value.isNullOrBlank()) remove(K_URL_OVERRIDE) else putString(K_URL_OVERRIDE, value.trim())
            }.apply()
        }

    fun shouldAutoCheck(now: Long = System.currentTimeMillis()): Boolean =
        UpdatePolicy.shouldAutoCheck(prefs.getLong(K_LAST_CHECK, 0L), now)

    /**
     * Returns a newer, verifiable release or null when up to date. Throws [IOException] on network errors and
     * [IntegrityException] when the newer release publishes no SHA-256 (the update is refused).
     */
    suspend fun check(): VerifiedUpdate? = withContext(Dispatchers.IO) {
        val body = get(releasesUrl)
        prefs.edit().putLong(K_LAST_CHECK, System.currentTimeMillis()).apply()
        val latest = try {
            Releases.pickLatestAndroid(body)
        } catch (e: IllegalArgumentException) {
            throw IOException(e.message, e)
        }
        val candidate = Releases.newerThan(currentVersion, latest) ?: return@withContext null
        val sha = candidate.apkSha256
            ?: candidate.sumsUrl?.let { Releases.parseSha256Sums(get(it), candidate.apkName) }
            ?: throw IntegrityException("릴리스 ${candidate.tag}에 SHA-256 정보(digest/SHA256SUMS.txt)가 없어 업데이트를 거부합니다")
        VerifiedUpdate(candidate, sha)
    }

    /**
     * Downloads the APK into the app cache (`cache/updates/`), verifying SHA-256 while streaming. On mismatch the
     * partial file is deleted and [IntegrityException] is thrown.
     */
    suspend fun download(update: VerifiedUpdate, onProgress: (downloaded: Long, total: Long) -> Unit): File =
        withContext(Dispatchers.IO) {
            val dir = File(app.cacheDir, UPDATE_DIR).apply { mkdirs() }
            // Keep only the file being downloaded; stale APKs from earlier updates are removed.
            dir.listFiles()?.forEach { it.delete() }
            val target = File(dir, update.candidate.apkName)
            val part = File(dir, update.candidate.apkName + ".part")
            val req = Request.Builder().url(update.candidate.apkUrl).header("User-Agent", USER_AGENT).build()
            val ctx = coroutineContext
            try {
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw IOException("다운로드 실패 (HTTP ${resp.code})")
                    val body = resp.body ?: throw IOException("다운로드 응답 본문이 없습니다")
                    val total = body.contentLength()
                    body.byteStream().use { input ->
                        part.outputStream().use { out ->
                            Sha256.copyVerified(input, out, update.sha256) { n ->
                                ctx.ensureActive() // user cancelled: abort the blocking copy
                                onProgress(n, total)
                            }
                        }
                    }
                }
                if (!part.renameTo(target)) throw IOException("다운로드 파일을 저장할 수 없습니다")
                target
            } catch (e: Throwable) { // IOException, IntegrityException or cancellation
                part.delete()
                target.delete()
                throw e
            }
        }

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/vnd.github+json")
            .build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("업데이트 서버 오류 (HTTP ${resp.code})")
            return text
        }
    }

    companion object {
        const val DEFAULT_RELEASES_URL = "https://api.github.com/repos/MrDH1004/PocketShell/releases?per_page=10"
        const val USER_AGENT = "pocketshell-updater"
        /** Must match res/xml/file_paths.xml. */
        const val UPDATE_DIR = "updates"
        private const val PREFS = "pkshell_update"
        private const val K_LAST_CHECK = "lastCheck"
        private const val K_URL_OVERRIDE = "debugReleasesUrl"
    }
}
