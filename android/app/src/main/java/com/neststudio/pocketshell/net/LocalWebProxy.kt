package com.neststudio.pocketshell.net

import android.util.Log
import com.neststudio.pocketshell.core.ProxyHead
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.Executors

/**
 * HTTP proxy on 127.0.0.1 for the web view: every WebView connection (plain HTTP, or HTTPS / WebSocket through
 * CONNECT) is opened by the PC through a [WebTunnel], so sites only the PC can reach load unchanged. Runs only while
 * the web view is open. Other apps on the phone could reach a loopback port, so each request must carry Basic proxy
 * credentials ([user]/[secret], random per start) that only our WebView is given; without them the answer is 407.
 */
class LocalWebProxy(private val tunnel: (String, Int) -> WebTunnel) {
    val user = "pocketshell"
    val secret: String = ByteArray(18).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private val expected = ProxyHead.basic(user, secret)
    private val pool = Executors.newCachedThreadPool()
    private var server: ServerSocket? = null

    /** Port it listens on (after [start]). */
    var port = 0
        private set

    fun start() {
        val s = ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"))
        server = s
        port = s.localPort
        pool.execute {
            while (!s.isClosed) {
                val c = try {
                    s.accept()
                } catch (e: IOException) {
                    break
                }
                pool.execute { serve(c) }
            }
        }
    }

    fun stop() {
        try {
            server?.close()
        } catch (e: IOException) {
            Log.w(TAG, "close", e)
        }
        pool.shutdownNow()
    }

    private fun serve(client: Socket) {
        client.use { c ->
            try {
                c.tcpNoDelay = true
                val input = c.getInputStream()
                val out = c.getOutputStream()
                val (headBytes, extra) = readHead(input) ?: return
                val head = ProxyHead.parse(String(headBytes, Charsets.ISO_8859_1))
                if (head == null) {
                    reply(out, "400 Bad Request", "잘못된 요청")
                    return
                }
                if (head.authorization != expected) {
                    out.write("HTTP/1.1 407 Proxy Authentication Required\r\nProxy-Authenticate: Basic realm=\"PocketShell\"\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    out.flush()
                    return
                }
                val t = try {
                    tunnel(head.host, head.port)
                } catch (e: IOException) {
                    reply(out, "502 Bad Gateway", "${head.host}:${head.port} — ${e.message}")
                    return
                }
                try {
                    if (head.connect) {
                        out.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
                        out.flush()
                    } else {
                        val f = head.forward.toByteArray(Charsets.ISO_8859_1)
                        t.write(f, 0, f.size)
                    }
                    if (extra.isNotEmpty()) t.write(extra, 0, extra.size)
                    relay(input, out, t)
                } finally {
                    t.close()
                }
            } catch (e: IOException) {
                Log.d(TAG, "connection ended: ${e.message}")
            }
        }
    }

    /** WebView → PC on a pool thread, PC → WebView here, until either side ends. */
    private fun relay(input: InputStream, out: OutputStream, t: WebTunnel) {
        val up = pool.submit {
            val buf = ByteArray(32 * 1024)
            try {
                while (true) {
                    val n = input.read(buf)
                    if (n < 0 || !t.write(buf, 0, n)) break
                }
            } catch (e: IOException) {
                // WebView closed the connection
            }
            t.close()
        }
        try {
            while (true) {
                val chunk = t.read() ?: break
                out.write(chunk)
                out.flush()
            }
        } catch (e: IOException) {
            // WebView went away
        } finally {
            up.cancel(true)
        }
    }

    /** Reads up to the end of the request head; returns (head, bytes read past it). */
    private fun readHead(input: InputStream): Pair<ByteArray, ByteArray>? {
        val buf = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        while (buf.size() < MAX_HEAD) {
            val n = input.read(chunk)
            if (n < 0) return null
            buf.write(chunk, 0, n)
            val all = buf.toByteArray()
            val end = indexOfBlankLine(all)
            if (end >= 0) return all.copyOfRange(0, end) to all.copyOfRange(end, all.size)
        }
        return null
    }

    private fun indexOfBlankLine(b: ByteArray): Int {
        for (i in 0..b.size - 4) {
            if (b[i] == '\r'.code.toByte() && b[i + 1] == '\n'.code.toByte() && b[i + 2] == '\r'.code.toByte() && b[i + 3] == '\n'.code.toByte()) return i + 4
        }
        return -1
    }

    private fun reply(out: OutputStream, status: String, message: String) {
        val body = "<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width'>" +
            "<body style='font-family:sans-serif;padding:16px'><h3>열 수 없습니다</h3><p>${escape(message)}</p>" +
            "<p>PC가 이 주소에 접속할 수 있는지, PC의 PocketShell 호스트가 켜져 있는지 확인하세요.</p>"
        val bytes = body.toByteArray(Charsets.UTF_8)
        out.write("HTTP/1.1 $status\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(bytes)
        out.flush()
    }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private companion object {
        const val TAG = "LocalWebProxy"
        const val MAX_HEAD = 64 * 1024
    }
}
