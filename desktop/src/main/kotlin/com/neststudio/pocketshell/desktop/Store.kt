package com.neststudio.pocketshell.desktop

import com.neststudio.pocketshell.data.Pairing
import com.neststudio.pocketshell.data.PairingCodec
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

/** Where the desktop client keeps its data (never the PC server's %APPDATA%\PocketShell). */
object AppDirs {
    private val os = System.getProperty("os.name").orEmpty().lowercase()
    val isMac = os.contains("mac")
    val isWindows = os.contains("win")

    val dir: File by lazy {
        val home = System.getProperty("user.home")
        val d = when {
            isMac -> File(home, "Library/Application Support/PocketShell")
            isWindows -> File(System.getenv("APPDATA") ?: home, "PocketShellClient")
            else -> File(System.getenv("XDG_CONFIG_HOME") ?: "$home/.config", "pocketshell")
        }
        System.getenv("PKSHELL_CLIENT_HOME")?.takeIf { it.isNotBlank() }?.let { File(it) } ?: d
    }
}

data class Prefs(
    val fontSize: Int = 14,
    val deviceName: String = defaultDeviceName(),
    val activeId: String? = null,
) {
    companion object {
        const val MIN_FONT = 10
        const val MAX_FONT = 28

        fun defaultDeviceName(): String {
            val host = try { java.net.InetAddress.getLocalHost().hostName } catch (e: IOException) { "" }
            val kind = if (AppDirs.isMac) "Mac" else if (AppDirs.isWindows) "Windows" else "Desktop"
            return host.removeSuffix(".local").ifBlank { kind }.let { if (it.contains(kind, ignoreCase = true)) it else "$it ($kind)" }
        }
    }
}

/**
 * Paired PCs (pairings.json, the Android app's format) and preferences (prefs.json). Files are owner-only on macOS /
 * Linux: each token gives access to its PC.
 */
class Store(private val dir: File = AppDirs.dir) {
    private val pairingsFile = File(dir, "pairings.json")
    private val prefsFile = File(dir, "prefs.json")

    @Synchronized
    fun pairings(): List<Pairing> = PairingCodec.decode(read(pairingsFile))

    /** Adds [p], or replaces the entry with the same id (re-pairing). */
    @Synchronized
    fun save(p: Pairing) {
        val list = pairings().filter { it.id != p.id } + p
        write(pairingsFile, PairingCodec.encode(list))
    }

    @Synchronized
    fun remove(id: String) = write(pairingsFile, PairingCodec.encode(pairings().filter { it.id != id }))

    @Synchronized
    fun prefs(): Prefs {
        val text = read(prefsFile) ?: return Prefs()
        return try {
            val o = JSONObject(text)
            Prefs(
                fontSize = o.optInt("fontSize", 14).coerceIn(Prefs.MIN_FONT, Prefs.MAX_FONT),
                deviceName = o.optString("deviceName", "").ifBlank { Prefs.defaultDeviceName() },
                activeId = o.optString("activeId", "").ifBlank { null },
            )
        } catch (e: JSONException) {
            Prefs()
        }
    }

    @Synchronized
    fun savePrefs(p: Prefs) = write(
        prefsFile,
        JSONObject().put("fontSize", p.fontSize).put("deviceName", p.deviceName).put("activeId", p.activeId ?: "").toString(2),
    )

    private fun read(f: File): String? = try { if (f.isFile) f.readText() else null } catch (e: IOException) { Log.warn("read ${f.name}: ${e.message}"); null }

    private fun write(f: File, text: String) {
        dir.mkdirs()
        val tmp = File(dir, f.name + ".tmp")
        tmp.writeText(text)
        ownerOnly(tmp)
        Files.move(tmp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    }

    private fun ownerOnly(f: File) {
        if (AppDirs.isWindows) return // %APPDATA% is per user already
        try { Files.setPosixFilePermissions(f.toPath(), PosixFilePermissions.fromString("rw-------")) } catch (e: UnsupportedOperationException) { } catch (e: IOException) { }
    }
}

/** client.log in the data folder (last ~1 MB kept). */
object Log {
    private val file by lazy { File(AppDirs.dir, "client.log") }

    @Synchronized
    fun info(msg: String) = write("INFO", msg)

    @Synchronized
    fun warn(msg: String) = write("WARN", msg)

    private fun write(level: String, msg: String) {
        try {
            AppDirs.dir.mkdirs()
            if (file.length() > 1_000_000) file.renameTo(File(AppDirs.dir, "client.log.1"))
            file.appendText("${java.time.LocalDateTime.now().toString().replace('T', ' ').take(23)} [$level] $msg\n")
        } catch (e: IOException) {
            System.err.println("log: ${e.message}")
        }
    }
}
