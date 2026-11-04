package com.mpta.client.net

import android.os.Handler
import android.os.Looper
import com.mpta.client.core.Backoff
import com.mpta.client.core.ClientProtocol
import com.mpta.client.core.ServerMessage
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString

/**
 * WebSocket stream for one session (DESIGN.md §5) with automatic reconnect (AC-18).
 *
 * - Hosts are tried in pairing order; the next host is tried immediately when a host cannot be reached.
 * - When all hosts fail or an open connection drops, it reconnects after 1, 2, 4, 8, 10, 10... seconds.
 * - [connectNow] (onResume / network change) skips the wait.
 * All [Listener] callbacks run on the main thread, in the order OkHttp delivered them.
 */
class TerminalConnection(
    private val http: OkHttpClient,
    private val hosts: List<String>,
    private val port: Int,
    private val token: String,
    private val listener: Listener,
    private val sizeProvider: () -> Pair<Int, Int>,
) {
    interface Listener {
        /** A new socket is open; the server will now send hello + full replay. Reset the emulator here. */
        fun onConnected(host: String)
        fun onOutput(bytes: ByteArray)
        fun onControl(message: ServerMessage)
        /** Lost connection; a retry is scheduled after [delayMillis]. */
        fun onReconnectScheduled(delayMillis: Long, reason: String)
        /** Unrecoverable (401 / revoked / session gone). No more retries. */
        fun onFatal(reason: String, authFailure: Boolean)
    }

    private enum class State { IDLE, CONNECTING, OPEN, FATAL }

    private val main = Handler(Looper.getMainLooper())
    private val backoff = Backoff()
    private var state = State.IDLE
    private var socket: WebSocket? = null
    /** Incremented for every new socket so callbacks from old sockets are ignored. */
    private var generation = 0
    private var started = false
    private val reconnectRunnable = Runnable { startAttempt() }

    var sessionId: String = ""

    /** False after the remote shell exited: a dropped socket is then not re-opened. */
    var autoReconnect: Boolean = true

    val isOpen: Boolean get() = state == State.OPEN

    /** Start (or keep) the connection; connects immediately if not connected. */
    fun connectNow() {
        if (state == State.FATAL || sessionId.isEmpty()) return
        started = true
        if (state == State.OPEN || state == State.CONNECTING) return
        main.removeCallbacks(reconnectRunnable)
        startAttempt()
    }

    /** Drop the current socket (possibly bound to a dead network) and connect again right away. */
    fun reconnectNow() {
        if (!started || state == State.FATAL || sessionId.isEmpty()) return
        closeSocket()
        state = State.IDLE
        main.removeCallbacks(reconnectRunnable)
        startAttempt()
    }

    /** Stop and close; [connectNow] can start again later. */
    fun disconnect() {
        started = false
        main.removeCallbacks(reconnectRunnable)
        closeSocket()
        if (state != State.FATAL) state = State.IDLE
    }

    /** Switch to another session id (after resume / adopt). */
    fun switchSession(newId: String) {
        disconnect()
        sessionId = newId
        autoReconnect = true
        backoff.reset()
        if (state == State.FATAL) state = State.IDLE
        connectNow()
    }

    fun sendInput(bytes: ByteArray) {
        if (state == State.OPEN) socket?.send(bytes.toByteString())
    }

    fun sendResize(cols: Int, rows: Int) {
        if (state == State.OPEN) socket?.send(ClientProtocol.resizeMessage(cols, rows))
    }

    private fun closeSocket() {
        generation++
        socket?.let {
            if (!it.close(1000, null)) it.cancel()
        }
        socket = null
    }

    private fun startAttempt() {
        if (!started || state == State.FATAL) return
        state = State.CONNECTING
        tryHost(0)
    }

    private fun tryHost(index: Int) {
        val gen = ++generation
        val host = hosts[index]
        val (cols, rows) = sizeProvider()
        val url = ClientProtocol.webSocketUrl(host, port, sessionId, cols, rows, openPcTab = true)
        val request = Request.Builder().url(url).header("Authorization", "Bearer $token").build()
        socket = http.newWebSocket(request, object : WebSocketListener() {
            private var opened = false

            private fun post(block: () -> Unit) = main.post { if (gen == generation) block() }

            override fun onOpen(webSocket: WebSocket, response: Response) {
                opened = true
                post {
                    state = State.OPEN
                    backoff.reset()
                    listener.onConnected(host)
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val data = bytes.toByteArray()
                post { listener.onOutput(data) }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val msg = ServerMessage.parse(text) ?: return
                post { listener.onControl(msg) }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                post {
                    socket = null
                    // Only 4401 (revoked) is final; 4408 (slow consumer), 1009 and others reconnect and replay.
                    if (code == CLOSE_REVOKED) fatal("기기 인증이 취소되었습니다 (4401). 다시 페어링하세요.", true)
                    else lost("연결 종료 ($code)")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val httpCode = response?.code
                post {
                    socket = null
                    when {
                        httpCode == 401 -> fatal("인증 실패 (401). 다시 페어링하세요.", true)
                        httpCode == 404 -> fatal("세션을 찾을 수 없습니다 (404).", false)
                        !opened && httpCode == null && index + 1 < hosts.size -> tryHost(index + 1)
                        else -> lost(t.message ?: t.javaClass.simpleName)
                    }
                }
            }
        })
    }

    private fun lost(reason: String) {
        state = State.IDLE
        if (!started || !autoReconnect) return
        val delay = backoff.nextDelayMillis()
        main.removeCallbacks(reconnectRunnable)
        main.postDelayed(reconnectRunnable, delay)
        listener.onReconnectScheduled(delay, reason)
    }

    private fun fatal(reason: String, auth: Boolean) {
        state = State.FATAL
        main.removeCallbacks(reconnectRunnable)
        listener.onFatal(reason, auth)
    }

    private companion object {
        const val CLOSE_REVOKED = 4401
    }
}
