import com.neststudio.pocketshell.core.HostAddress
import com.neststudio.pocketshell.core.ServerMessage
import com.neststudio.pocketshell.desktop.Apis
import com.neststudio.pocketshell.desktop.PinPair
import com.neststudio.pocketshell.desktop.RemoteTty
import com.neststudio.pocketshell.desktop.Store
import com.neststudio.pocketshell.desktop.Updater
import com.neststudio.pocketshell.net.ApiClient
import com.neststudio.pocketshell.net.PinnedHttp
import com.neststudio.pocketshell.net.Poster
import com.neststudio.pocketshell.net.TerminalConnection
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopTest {
    @Test
    fun updaterPicksNewestReleaseWithThisPlatformsInstaller() {
        val name = { v: String -> Updater.assetName(v) }
        val json = """[
          {"tag_name":"v0.2.150","draft":false,"prerelease":false,"html_url":"p150","assets":[{"name":"other.zip","browser_download_url":"x"}]},
          {"tag_name":"v0.2.149","draft":false,"prerelease":false,"html_url":"p149","assets":[{"name":"${name("0.2.149")}","browser_download_url":"u149"}]},
          {"tag_name":"v0.2.148","draft":false,"prerelease":false,"html_url":"p148","assets":[{"name":"${name("0.2.148")}","browser_download_url":"u148"}]},
          {"tag_name":"v0.2.151","draft":false,"prerelease":true,"html_url":"p151","assets":[{"name":"${name("0.2.151")}","browser_download_url":"u151"}]}
        ]"""
        val r = Updater.pick(json, "0.2.146")
        assertEquals("0.2.149", r?.version)
        assertEquals("u149", r?.url)
        assertNull(Updater.pick(json, "0.2.149"))
        assertNull(Updater.pick("not json", "0.2.146"))
    }

    @Test
    fun remoteTtyKeepsUtf8SplitAcrossFrames() {
        val tty = RemoteTty("t")
        val bytes = "가나 ok".toByteArray(Charsets.UTF_8)
        tty.feed(bytes.copyOfRange(0, 2)) // half of 가
        tty.feed(bytes.copyOfRange(2, bytes.size))
        val buf = CharArray(32)
        val sb = StringBuilder()
        while (tty.ready()) { val n = tty.read(buf, 0, buf.size); sb.appendRange(buf, 0, n) }
        assertEquals("가나 ok", sb.toString())
        tty.close()
        assertEquals(-1, tty.read(buf, 0, 1))
    }

    @Test
    fun storeRoundTrip() {
        val dir = Files.createTempDirectory("pshc").toFile()
        val s = Store(dir)
        val p = com.neststudio.pocketshell.data.Pairing("PC", listOf("10.0.0.2"), 7380, "ab".repeat(32), "dev", "tok")
        s.save(p)
        s.save(p.copy(alias = "집"))
        assertEquals(listOf("집"), s.pairings().map { it.alias })
        s.savePrefs(s.prefs().copy(fontSize = 99, activeId = p.id))
        assertEquals(28, s.prefs().fontSize) // clamped
        assertEquals(p.id, s.prefs().activeId)
        s.remove(p.id)
        assertTrue(s.pairings().isEmpty())
        dir.deleteRecursively()
    }

    /**
     * Against a running test host: PKSHELL_IT_PORT=47999 PKSHELL_IT_HOME=<its --home>. PIN pairing (TOFU + confirm +
     * pinned claim), a new session, its WebSocket (echo a marker), then cleanup.
     */
    @Test
    fun pairOpenAndTypeAgainstTestHost() = runBlocking {
        val port = System.getenv("PKSHELL_IT_PORT")?.toIntOrNull() ?: return@runBlocking
        val home = File(System.getenv("PKSHELL_IT_HOME") ?: return@runBlocking)
        val fp = File(home, "cert.sha256").readText().trim()
        val local = ApiClient(listOf("127.0.0.1"), port, File(home, "local.token").readText().trim(), PinnedHttp.client(fp))
        val pin = JSONObject(local.request("POST", "/api/pair/start", JSONObject().put("mode", "pin"))).getString("pin")

        var asked: String? = null
        val p = PinPair.pair(HostAddress("127.0.0.1", port), null, pin, "desktop-it") { short -> asked = short; true }
        assertEquals(fp, p.fingerprint)
        assertNotNull(asked)
        assertTrue(p.hosts.contains("127.0.0.1"))
        System.getenv("PKSHELL_IT_SAVE_TO")?.let { Store(File(it)).save(p) } // seed a GUI run

        val api = Apis.of(p)
        val s = api.createSession(null, "desktop-it", 100, 30)
        try {
            val exec = Executors.newSingleThreadScheduledExecutor()
            val tasks = ConcurrentHashMap<Runnable, java.util.concurrent.ScheduledFuture<*>>()
            val poster = object : Poster {
                override fun post(r: Runnable) { exec.execute(r) }
                override fun postDelayed(r: Runnable, delayMillis: Long) { tasks[r] = exec.schedule(r, delayMillis, TimeUnit.MILLISECONDS) }
                override fun removeCallbacks(r: Runnable) { tasks.remove(r)?.cancel(false) }
            }
            val out = StringBuffer()
            val seen = CountDownLatch(1)
            lateinit var conn: TerminalConnection
            conn = TerminalConnection(api.http, api.hosts, p.port, p.token, poster, object : TerminalConnection.Listener {
                override fun onConnected(host: String) { conn.sendInput("echo PSH_DESKTOP_\$((6*7))\r".toByteArray()) ; conn.sendInput("echo PSH_DESKTOP_%NUMBER_OF_PROCESSORS%x\r\n".toByteArray()) }
                override fun onOutput(bytes: ByteArray) {
                    out.append(String(bytes, Charsets.UTF_8))
                    if (Regex("PSH_DESKTOP_(42|\\d+x)").containsMatchIn(out.toString().replace("echo PSH_DESKTOP_", ""))) seen.countDown()
                }
                override fun onControl(message: ServerMessage) {}
                override fun onReconnectScheduled(delayMillis: Long, reason: String) {}
                override fun onFatal(reason: String, authFailure: Boolean) { out.append("FATAL $reason") }
            }) { 100 to 30 }
            conn.sessionId = s.id
            conn.openPcTab = false
            exec.execute { conn.connectNow() }
            val ok = seen.await(20, TimeUnit.SECONDS)
            exec.execute { conn.disconnect() }
            exec.shutdown()
            assertTrue(ok, "marker not echoed; output tail: ${out.takeLast(400)}")
        } finally {
            api.delete(s.id)
        }
    }
}
