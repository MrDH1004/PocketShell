package com.neststudio.pocketshell.core

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

/** One entry of `GET /api/drives` (host ≥ 0.2.73): a well-known folder or a drive the PC browser starts from. */
data class PcPlace(val name: String, val path: String, val drive: Boolean) {
    companion object {
        /** `{places:[{name, path, kind}]}`; entries without a path are skipped. */
        fun parseList(text: String): List<PcPlace> {
            val arr = JSONObject(text).optJSONArray("places") ?: return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val path = o.optString("path")
                if (path.isBlank()) null else PcPlace(o.optString("name").ifBlank { path }, path, o.optString("kind") == "drive")
            }
        }
    }
}

/** Windows path helpers for the file browser (the host is a Windows PC). */
object PcPath {
    fun child(dir: String, name: String): String = if (dir.endsWith("\\")) dir + name else "$dir\\$name"

    /** PC browser: the parent folder; a drive root (or a path without one) goes up to "" (= 내 PC). */
    fun parentOrTop(path: String): String {
        val trimmed = path.trimEnd('\\')
        val i = trimmed.lastIndexOf('\\')
        if (i < 0 || (i == 2 && trimmed.length == 2)) return ""
        return if (i == 2 && trimmed[1] == ':') trimmed.substring(0, 3) else if (i == 0) "" else trimmed.substring(0, i)
    }

    /** Folder name for a session title: last segment, or the drive ("C:") for a drive root. */
    fun name(path: String): String = path.trimEnd('\\').substringAfterLast('\\').ifEmpty { path }

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

/** What a browser row shows for an entry (icon). */
enum class FileCategory { FOLDER, CODE, IMAGE, TEXT, ARCHIVE, MEDIA, OTHER }

object FileKinds {
    private val CODE = setOf(
        "kt", "kts", "java", "cs", "csproj", "sln", "c", "h", "cpp", "hpp", "cc", "py", "js", "mjs", "cjs", "ts", "tsx",
        "jsx", "go", "rs", "rb", "php", "swift", "m", "sh", "bash", "ps1", "psm1", "bat", "cmd", "sql", "gradle", "xml",
        "html", "htm", "css", "scss", "vue", "svelte", "json", "yaml", "yml", "toml", "ini", "dart", "lua", "r",
    )
    private val TEXT = setOf("txt", "md", "markdown", "log", "csv", "tsv", "rst", "cfg", "conf", "env", "properties", "gitignore")
    private val ARCHIVE = setOf("zip", "7z", "rar", "gz", "tar", "tgz", "bz2", "xz", "apk", "aab", "jar", "msi", "iso")
    private val MEDIA = setOf("mp3", "mp4", "mov", "avi", "mkv", "wav", "flac", "ogg", "webm", "m4a")
    private val IMAGE = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "heif", "svg", "ico")

    fun category(e: FileEntry): FileCategory {
        if (e.dir) return FileCategory.FOLDER
        val ext = FilePreview.extension(e.name).ifEmpty { e.name.removePrefix(".").lowercase(Locale.ROOT) }
        return when (ext) {
            in IMAGE -> FileCategory.IMAGE
            in CODE -> FileCategory.CODE
            in TEXT -> FileCategory.TEXT
            in ARCHIVE -> FileCategory.ARCHIVE
            in MEDIA -> FileCategory.MEDIA
            else -> FileCategory.OTHER
        }
    }
}

/** Browser sort orders; folders always come first. */
enum class FileSort(val id: String) {
    NAME("name"), MODIFIED("modified"), SIZE("size");

    fun apply(entries: List<FileEntry>): List<FileEntry> {
        val byName = compareBy<FileEntry, String>(String.CASE_INSENSITIVE_ORDER) { it.name }
        val files = when (this) {
            NAME -> byName
            MODIFIED -> compareByDescending<FileEntry> { it.modified }.then(byName)
            SIZE -> compareByDescending<FileEntry> { it.size }.then(byName)
        }
        val (dirs, rest) = entries.partition { it.dir }
        // Folders have no size: by name for SIZE, by time for MODIFIED.
        val dirOrder = if (this == MODIFIED) files else byName
        return dirs.sortedWith(dirOrder) + rest.sortedWith(files)
    }

    companion object {
        fun of(id: String): FileSort = entries.firstOrNull { it.id == id } ?: NAME
    }
}

/** Breadcrumb segments from the project root to [path]: (label, full path). */
object Crumbs {
    fun of(path: String, root: String): List<Pair<String, String>> {
        val rootLabel = root.trimEnd('\\').substringAfterLast('\\').ifEmpty { root }
        val out = mutableListOf(rootLabel to root)
        val rel = PcPath.relative(path, root)
        if (rel.isEmpty()) return out
        var cur = root
        for (part in rel.split('\\').filter { it.isNotEmpty() }) {
            cur = PcPath.child(cur, part)
            out += part to cur
        }
        return out
    }

    /** PC browser: [top] ("내 PC", path "") > drive > folders down to [path]. */
    fun ofPc(path: String, top: String): List<Pair<String, String>> {
        val out = mutableListOf(top to "")
        val parts = path.trimEnd('\\').split('\\').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return out
        var cur = parts[0] + "\\"
        out += parts[0] to cur
        for (part in parts.drop(1)) {
            cur = PcPath.child(cur, part)
            out += part to cur
        }
        return out
    }
}

/** "방금 / N분 전 / N시간 전 / N일 전" for the last week, then the date. */
object RelativeTime {
    fun format(modified: java.time.Instant, now: java.time.Instant, zone: java.time.ZoneId): String {
        val s = java.time.Duration.between(modified, now).seconds
        return when {
            s < 0 -> date(modified, zone)
            s < 60 -> "방금"
            s < 3600 -> "${s / 60}분 전"
            s < 86400 -> "${s / 3600}시간 전"
            s < 7 * 86400 -> "${s / 86400}일 전"
            else -> date(modified, zone)
        }
    }

    private fun date(t: java.time.Instant, zone: java.time.ZoneId): String =
        java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(zone).format(t)
}
