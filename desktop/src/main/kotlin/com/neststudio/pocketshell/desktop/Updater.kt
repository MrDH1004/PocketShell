package com.neststudio.pocketshell.desktop

import com.neststudio.pocketshell.core.SemVer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException
import java.io.IOException
import java.util.concurrent.TimeUnit

/** A newer desktop build on GitHub Releases: its version and the installer to download in the browser. */
data class DesktopRelease(val version: String, val url: String, val page: String)

/**
 * Checks GitHub Releases for a newer desktop installer (`PocketShell-mac-<arm64|x64>-v<ver>.dmg` on macOS). Download and install
 * stay with the user (open the .dmg, drag to Applications): the app is not signed, so it cannot replace itself quietly.
 */
object Updater {
    const val RELEASES_URL = "https://api.github.com/repos/MrDH1004/PocketShell/releases?per_page=10"

    val current: String by lazy {
        Updater::class.java.getResourceAsStream("/version.txt")?.use { it.readBytes().toString(Charsets.UTF_8).trim() } ?: "0.0.0"
    }

    /** "arm64" (Apple silicon) or "x64" (Intel). */
    val arch: String = System.getProperty("os.arch").orEmpty().let { if (it.contains("aarch64") || it.contains("arm64")) "arm64" else "x64" }

    fun assetName(version: String): String =
        if (AppDirs.isWindows) "PocketShell-desktop-v$version.msi" else "PocketShell-mac-$arch-v$version.dmg"

    private val http by lazy { OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build() }

    /** The newest release newer than this build that carries this platform's installer, or null. */
    suspend fun check(): DesktopRelease? = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(RELEASES_URL).header("Accept", "application/vnd.github+json").header("User-Agent", "PocketShell-desktop").build()
        val text = http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("GitHub ${r.code}")
            r.body?.string().orEmpty()
        }
        pick(text, current)
    }

    /** Pure part of [check] (unit-tested). */
    fun pick(json: String, currentVersion: String): DesktopRelease? {
        val cur = SemVer.parse(currentVersion) ?: return null
        val arr = try { JSONArray(json) } catch (e: JSONException) { return null }
        var best: Pair<SemVer, DesktopRelease>? = null
        for (i in 0 until arr.length()) {
            val rel = arr.optJSONObject(i) ?: continue
            if (rel.optBoolean("draft") || rel.optBoolean("prerelease")) continue
            val v = SemVer.parse(rel.optString("tag_name").removePrefix("v")) ?: continue
            if (v <= cur || (best != null && v <= best.first)) continue
            val assets = rel.optJSONArray("assets") ?: continue
            val want = assetName(v.toString())
            for (j in 0 until assets.length()) {
                val a = assets.optJSONObject(j) ?: continue
                if (a.optString("name") == want) best = v to DesktopRelease(v.toString(), a.optString("browser_download_url"), rel.optString("html_url"))
            }
        }
        return best?.second
    }
}
