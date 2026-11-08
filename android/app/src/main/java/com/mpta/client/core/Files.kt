package com.mpta.client.core

import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Locale

/** One entry of `GET /api/files`. */
data class FileEntry(val name: String, val dir: Boolean, val size: Long, val modified: String)

/** `GET /api/files` → `{path, entries:[{name, dir, size, modified}]}`. */
data class FileListing(val path: String, val entries: List<FileEntry>) {
    companion object {
        fun parse(text: String): FileListing {
            val o = JSONObject(text)
            val arr = o.optJSONArray("entries")
            val entries = if (arr == null) emptyList() else (0 until arr.length()).map {
                val e = arr.getJSONObject(it)
                FileEntry(e.getString("name"), e.optBoolean("dir"), e.optLong("size"), e.optString("modified"))
            }
            return FileListing(o.optString("path"), entries)
        }
    }
}

/** Windows path helpers for the file browser (the host is a Windows PC). */
object PcPath {
    fun child(dir: String, name: String): String = if (dir.endsWith("\\")) dir + name else "$dir\\$name"

    /** Parent of [path], or null at [root] (the browser does not leave the project folder) or a drive root. */
    fun parentWithin(path: String, root: String): String? {
        if (same(path, root)) return null
        val trimmed = path.trimEnd('\\')
        val i = trimmed.lastIndexOf('\\')
        if (i <= 0) return null
        val parent = if (i == 2 && trimmed[1] == ':') trimmed.substring(0, 3) else trimmed.substring(0, i)
        return if (inside(parent, root)) parent else null
    }

    /** "folder\sub" relative to [root] for the header ("" at the root). */
    fun relative(path: String, root: String): String {
        if (same(path, root)) return ""
        val r = root.trimEnd('\\') + "\\"
        return if (path.startsWith(r, ignoreCase = true)) path.substring(r.length) else path
    }

    fun inside(path: String, root: String): Boolean =
        same(path, root) || path.startsWith(root.trimEnd('\\') + "\\", ignoreCase = true)

    private fun same(a: String, b: String) = a.trimEnd('\\').equals(b.trimEnd('\\'), ignoreCase = true)
}

/** How a file is previewed. */
enum class PreviewKind { TEXT, IMAGE, NONE }

object FilePreview {
    /** Text previews are capped (bigger files: download instead). */
    const val MAX_TEXT_BYTES = 2L * 1024 * 1024
    const val MAX_IMAGE_BYTES = 30L * 1024 * 1024

    private val IMAGE = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "heif")
    private val BINARY = setOf(
        "exe", "dll", "pdb", "obj", "o", "a", "lib", "so", "class", "jar", "zip", "7z", "rar", "gz", "tar",
        "apk", "aab", "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "hwp", "mp3", "mp4", "mov", "avi",
        "wav", "ttf", "otf", "woff", "woff2", "ico", "db", "sqlite", "bin", "iso", "msi",
    )

    fun extension(name: String): String = name.substringAfterLast('.', "").lowercase(Locale.ROOT)

    fun kind(name: String, size: Long): PreviewKind {
        val ext = extension(name)
        return when {
            ext in IMAGE -> if (size <= MAX_IMAGE_BYTES) PreviewKind.IMAGE else PreviewKind.NONE
            ext in BINARY -> PreviewKind.NONE
            size <= MAX_TEXT_BYTES -> PreviewKind.TEXT
            else -> PreviewKind.NONE
        }
    }

    /** True when the start of [bytes] has a NUL (binary data). */
    fun looksBinary(bytes: ByteArray): Boolean {
        val n = minOf(bytes.size, 8192)
        for (i in 0 until n) if (bytes[i] == 0.toByte()) return true
        return false
    }

    /** UTF-8 (BOM stripped) when valid, else CP949 (Korean Windows files), else lossy UTF-8. */
    fun decodeText(bytes: ByteArray): String {
        val start = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) 3 else 0
        strict(bytes, start, Charsets.UTF_8)?.let { return it }
        val cp949 = runCatching { Charset.forName("x-windows-949") }.getOrNull()
        if (cp949 != null) strict(bytes, start, cp949)?.let { return it }
        return String(bytes, start, bytes.size - start, Charsets.UTF_8)
    }

    private fun strict(bytes: ByteArray, start: Int, cs: Charset): String? = try {
        cs.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, start, bytes.size - start)).toString()
    } catch (e: CharacterCodingException) {
        null
    }

    fun humanSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024))
        else -> String.format(Locale.ROOT, "%.2f GB", bytes / (1024.0 * 1024 * 1024))
    }
}
