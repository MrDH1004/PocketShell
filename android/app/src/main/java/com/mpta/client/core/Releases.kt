package com.mpta.client.core

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * An Android update found in GitHub Releases (DESIGN.md §11.2).
 * [apkSha256] comes from the asset's `digest` (`sha256:<hex>`); when absent, [sumsUrl] points to the
 * release's `SHA256SUMS.txt`. A candidate with neither must not be installed.
 */
data class ReleaseCandidate(
    val version: SemVer,
    val tag: String,
    val title: String,
    val notes: String,
    val apkName: String,
    val apkUrl: String,
    val apkSha256: String?,
    val sumsUrl: String?,
)

object Releases {
    const val SUMS_ASSET = "SHA256SUMS.txt"

    fun androidAssetName(version: SemVer): String = "mpta-android-v$version.apk"

    /**
     * Parses the `GET /repos/{owner}/{repo}/releases` array and returns the highest-SemVer non-draft release
     * that carries the Android asset `mpta-android-v<ver>.apk`. Releases with a non-SemVer tag are skipped.
     * Throws [IllegalArgumentException] if the body is not a JSON array.
     */
    fun pickLatestAndroid(json: String): ReleaseCandidate? {
        val arr = try {
            JSONArray(json.trim())
        } catch (e: JSONException) {
            throw IllegalArgumentException("릴리스 목록이 올바른 JSON 배열이 아닙니다", e)
        }
        var best: ReleaseCandidate? = null
        for (i in 0 until arr.length()) {
            val rel = arr.optJSONObject(i) ?: continue
            val c = candidateOf(rel) ?: continue
            if (best == null || c.version > best.version) best = c
        }
        return best
    }

    /** The candidate if it is strictly newer than [currentVersion]; null if not newer or current is unparsable. */
    fun newerThan(currentVersion: String, candidate: ReleaseCandidate?): ReleaseCandidate? {
        val current = SemVer.parse(currentVersion) ?: return null
        return candidate?.takeIf { it.version > current }
    }

    /** `sha256:<hex>` → lowercase hex; null for other algorithms or malformed values. */
    fun parseDigest(digest: String?): String? {
        if (digest == null) return null
        val t = digest.trim()
        if (!t.startsWith("sha256:", ignoreCase = true)) return null
        return normalizeHex(t.substring("sha256:".length))
    }

    /**
     * Finds [fileName] in a `sha256sum`-style file (`<hex>  <name>` or `<hex> *<name>` per line).
     * Returns lowercase hex or null.
     */
    fun parseSha256Sums(text: String, fileName: String): String? {
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val sep = line.indexOfFirst { it.isWhitespace() }
            if (sep <= 0) continue
            val hex = normalizeHex(line.substring(0, sep)) ?: continue
            val name = line.substring(sep).trim().removePrefix("*").trim()
            if (name == fileName) return hex
        }
        return null
    }

    private fun normalizeHex(s: String): String? {
        val h = s.trim().lowercase()
        return h.takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
    }

    private fun candidateOf(rel: JSONObject): ReleaseCandidate? {
        if (rel.optBoolean("draft", false)) return null
        val tag = rel.optString("tag_name", "")
        val version = SemVer.parse(tag) ?: return null
        val assets = rel.optJSONArray("assets") ?: return null
        val apkName = androidAssetName(version)
        var apk: JSONObject? = null
        var sumsUrl: String? = null
        for (j in 0 until assets.length()) {
            val a = assets.optJSONObject(j) ?: continue
            when (a.optString("name", "")) {
                apkName -> apk = a
                SUMS_ASSET -> sumsUrl = a.optString("browser_download_url", "").ifBlank { null }
            }
        }
        val asset = apk ?: return null
        val url = asset.optString("browser_download_url", "").ifBlank { return null }
        val digest = if (asset.isNull("digest")) null else asset.optString("digest", "")
        return ReleaseCandidate(
            version = version,
            tag = tag,
            title = rel.optString("name", "").let { if (rel.isNull("name") || it.isBlank()) tag else it },
            notes = if (rel.isNull("body")) "" else rel.optString("body", ""),
            apkName = apkName,
            apkUrl = url,
            apkSha256 = parseDigest(digest),
            sumsUrl = sumsUrl,
        )
    }
}
