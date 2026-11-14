package com.neststudio.pocketshell.net

import com.neststudio.pocketshell.core.ClientProtocol
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * A TCP connection the PC makes on the phone's behalf (`/ws/tunnel`, host ≥ 0.2.81), as blocking reads and writes
 * for the in-app web proxy. Bytes arrive in [read] in order; an empty array means the connection ended.
 */
class WebTunnel private constructor() {
    private val incoming = LinkedBlockingQueue<ByteArray>()
    @Volatile private var socket: WebSocket? = null
    @Volatile private var ended = false

    /** Next chunk from the site, or null when the connection ended. Blocks. */
    fun read(): ByteArray? {
        if (ended && incoming.isEmpty()) return null
        val b = incoming.take()
        if (b.isEmpty()) {
            ended = true
            return null
        }
        return b
    }

    /** Sends to the site; false when the tunnel is gone. OkHttp queues up to 16 MB, so this throttles above 4 MB. */
    fun write(data: ByteArray, off: Int, len: Int): Boolean {
        val ws = socket ?: return false
        while (ws.queueSize() > 4L * 1024 * 1024 && !ended) Thread.sleep(5)
        return ws.send(data.toByteString(off, len))
    }

    fun close() {
        socket?.let { if (!it.close(1000, null)) it.cancel() }
        finish()
    }

    private fun finish() {
        ended = true
        incoming.offer(ByteArray(0))
    }

    companion object {
        /**
         * Opens a tunnel to [target]:[targetPort] through the first PC address that answers. Blocking (call off the
         * main thread). Throws IOException with the PC's reason (e.g. "connection refused") when it cannot.
         */
        fun open(http: OkHttpClient, hosts: HostSelector, port: Int, token: String?, target: String, targetPort: Int): WebTunnel {
            var lastError = "PC에 연결하지 못했습니다"
            for (host in hosts.ordered()) {
                val t = WebTunnel()
                val opened = CountDownLatch(1)
                var error: String? = null
                val req = Request.Builder().url(ClientProtocol.tunnelUrl(host, port, target, targetPort))
                    .apply { if (token != null) header("Authorization", "Bearer $token") }
                    .build()
                val ws = http.newWebSocket(req, object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        hosts.reportSuccess(host)
                        opened.countDown()
                    }

                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                        if (bytes.size > 0) t.incoming.offer(bytes.toByteArray())
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(1000, null)
                        t.finish()
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = t.finish()

                    override fun onFailure(webSocket: WebSocket, e: Throwable, response: Response?) {
                        if (opened.count > 0L) {
                            error = response?.let { r ->
                                val body = runCatching { r.body?.string() }.getOrNull().orEmpty().take(200)
                                if (r.code == 502) "PC에서 접속하지 못했습니다: $body" else "PC 응답 ${r.code} $body"
                            } ?: "${e.javaClass.simpleName}: ${e.message}"
                            opened.countDown()
                        }
                        t.finish()
                    }
                })
                t.socket = ws
                if (!opened.await(20, TimeUnit.SECONDS)) {
                    ws.cancel()
                    lastError = "PC 응답 시간 초과"
                    continue
                }
                val err = error ?: return t
                lastError = err
                // The PC answered (it could not reach the site): another PC address would say the same.
                if (err.startsWith("PC에서")) break
            }
            throw IOException(lastError)
        }
    }
}
