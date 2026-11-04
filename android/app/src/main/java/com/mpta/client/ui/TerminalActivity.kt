package com.mpta.client.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.mpta.client.MptaApp
import com.mpta.client.R
import com.mpta.client.core.KeyMapper
import com.mpta.client.core.ServerMessage
import com.mpta.client.core.SessionInfo
import com.mpta.client.core.SpecialKey
import com.mpta.client.net.ApiClient
import com.mpta.client.net.TerminalConnection
import com.termux.terminal.RemoteTerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Terminal screen (AC-15..18): Termux TerminalView backed by [RemoteTerminalSession] over a WebSocket.
 * - history session → POST resume first, then attach to the new session.
 * - external session → "호스트로 인수" (POST /api/external/{pid}/adopt) switches to the new host session.
 */
class TerminalActivity : AppCompatActivity() {

    private lateinit var terminalView: TerminalView
    private lateinit var statusView: TextView
    private lateinit var titleView: TextView
    private lateinit var adoptButton: Button
    private lateinit var session: RemoteTerminalSession
    private var connection: TerminalConnection? = null
    private var api: ApiClient? = null

    private var sessionId = ""
    private var sessionKind = SessionInfo.KIND_HOST
    private var externalPid: Int? = null

    /** Size in cells reported by the view; null until the first layout. */
    private var viewCols = 0
    private var viewRows = 0
    private var resumed = false

    // Special key bar toggles.
    private var ctrlOn = false
    private var altOn = false
    private lateinit var ctrlButton: TextView
    private lateinit var altButton: TextView

    private var fontSizeSp = 12f
    private val main = Handler(Looper.getMainLooper())
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var currentNetwork: Network? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_terminal)
        findViewById<View>(R.id.root).applySystemInsets(includeIme = true)

        val app = application as MptaApp
        val pairing = app.pairing()
        val apiClient = app.api()
        if (pairing == null || apiClient == null) {
            startActivity(Intent(this, PairingActivity::class.java))
            finish()
            return
        }
        api = apiClient

        sessionId = intent.getStringExtra(EXTRA_ID).orEmpty()
        sessionKind = intent.getStringExtra(EXTRA_KIND) ?: SessionInfo.KIND_HOST
        externalPid = intent.getIntExtra(EXTRA_PID, -1).takeIf { it > 0 }

        titleView = findViewById(R.id.title)
        statusView = findViewById(R.id.status)
        adoptButton = findViewById(R.id.adoptButton)
        terminalView = findViewById(R.id.terminal)
        titleView.text = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { sessionId }

        session = RemoteTerminalSession(TRANSCRIPT_ROWS, sessionClient, object : RemoteTerminalSession.Transport {
            override fun sendInput(data: ByteArray) {
                connection?.sendInput(data)
            }

            override fun onResize(columns: Int, rows: Int) {
                onViewResized(columns, rows)
            }
        })

        connection = TerminalConnection(apiClient.http, pairing.hosts, pairing.port, pairing.token, connectionListener) {
            if (viewCols > 0) viewCols to viewRows else 80 to 24
        }

        terminalView.setTerminalViewClient(viewClient)
        terminalView.setTextSize(spToPx(fontSizeSp))
        terminalView.attachSession(session)
        terminalView.keepScreenOn = true

        buildKeyBar(findViewById(R.id.keyBar))
        findViewById<Button>(R.id.keyboardButton).setOnClickListener { toggleKeyboard() }
        adoptButton.setOnClickListener { adopt() }
        adoptButton.visibility = if (sessionKind == SessionInfo.KIND_EXTERNAL) View.VISIBLE else View.GONE

        if (sessionKind == SessionInfo.KIND_HISTORY) resumeHistory() else connection?.sessionId = sessionId
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        registerNetworkCallback()
        // AC-18: reconnect immediately when coming back (screen on / app switch).
        maybeConnect()
        terminalView.requestFocus()
    }

    override fun onPause() {
        super.onPause()
        resumed = false
    }

    override fun onStop() {
        super.onStop()
        unregisterNetworkCallback()
        connection?.disconnect()
        setStatus("연결 끊김 (백그라운드)")
    }

    override fun onDestroy() {
        super.onDestroy()
        main.removeCallbacksAndMessages(null)
        connection?.disconnect()
    }

    // ---------------------------------------------------------------- connection

    private fun maybeConnect() {
        val c = connection ?: return
        if (!resumed || viewCols == 0 || c.sessionId.isEmpty()) return
        c.connectNow()
    }

    private fun onViewResized(cols: Int, rows: Int) {
        val first = viewCols == 0
        viewCols = cols
        viewRows = rows
        if (first) maybeConnect() else connection?.sendResize(cols, rows)
    }

    private val connectionListener = object : TerminalConnection.Listener {
        override fun onConnected(host: String) {
            // The server replays the whole output.vt after hello: start from a blank emulator.
            session.resetForReplay()
            terminalView.onScreenUpdated()
            setStatus("연결됨 · $host · 재생 중…")
            // The size in the URL may be stale if the view changed while connecting.
            connection?.sendResize(viewCols, viewRows)
        }

        override fun onOutput(bytes: ByteArray) {
            session.appendRemoteOutput(bytes, 0, bytes.size)
        }

        override fun onControl(message: ServerMessage) {
            when (message) {
                is ServerMessage.Hello -> message.session?.let { s ->
                    titleView.text = s.title.ifBlank { s.id }
                }
                is ServerMessage.Size -> setStatus("연결됨 · PTY ${message.cols}×${message.rows} · 화면 ${viewCols}×${viewRows}")
                is ServerMessage.ReplayEnd -> setStatus("연결됨 · 화면 ${viewCols}×${viewRows}")
                is ServerMessage.Exit -> {
                    connection?.autoReconnect = false
                    session.markExited(message.code, "\r\n\u001b[0m[세션 종료 (코드 ${message.code})]\r\n")
                    setStatus("세션 종료 (코드 ${message.code})")
                }
                is ServerMessage.Unknown -> Log.d(TAG, "unknown control message: ${message.type}")
            }
        }

        override fun onReconnectScheduled(delayMillis: Long, reason: String) {
            setStatus("연결 끊김: $reason · ${delayMillis / 1000}초 후 재연결")
        }

        override fun onFatal(reason: String, authFailure: Boolean) {
            setStatus(reason)
            Toast.makeText(this@TerminalActivity, reason, Toast.LENGTH_LONG).show()
        }
    }

    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                main.post {
                    val previous = currentNetwork
                    currentNetwork = network
                    // AC-18: the default network changed (Wi-Fi <-> mobile, Tailscale up): the old socket is
                    // bound to the previous network, so reconnect right away instead of waiting for a timeout.
                    if (previous != null && previous != network) connection?.reconnectNow() else maybeConnect()
                }
            }

            override fun onLost(network: Network) {
                main.post { if (currentNetwork == network) currentNetwork = null }
            }
        }
        cm.registerDefaultNetworkCallback(cb)
        networkCallback = cb
    }

    private fun unregisterNetworkCallback() {
        val cb = networkCallback ?: return
        getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb)
        networkCallback = null
        currentNetwork = null
    }

    private fun resumeHistory() {
        val a = api ?: return
        setStatus("이전 세션 재개 중…")
        lifecycleScope.launch {
            try {
                val s = a.resume(sessionId)
                switchTo(s)
            } catch (e: IOException) {
                setStatus("재개 실패: ${e.message}")
            } catch (e: org.json.JSONException) {
                setStatus("재개 실패: 서버 응답 형식 오류")
            }
        }
    }

    private fun adopt() {
        val a = api ?: return
        val pid = externalPid ?: sessionId.removePrefix("ext-").toIntOrNull()
        if (pid == null) {
            setStatus("외부 세션 PID를 알 수 없습니다")
            return
        }
        adoptButton.isEnabled = false
        setStatus("호스트 세션으로 인수 중…")
        lifecycleScope.launch {
            try {
                val s = a.adopt(pid)
                adoptButton.visibility = View.GONE
                switchTo(s)
            } catch (e: IOException) {
                adoptButton.isEnabled = true
                setStatus("인수 실패: ${e.message}")
            } catch (e: org.json.JSONException) {
                adoptButton.isEnabled = true
                setStatus("인수 실패: 서버 응답 형식 오류")
            }
        }
    }

    private fun switchTo(s: SessionInfo) {
        sessionId = s.id
        sessionKind = s.kind
        titleView.text = s.title.ifBlank { s.id }
        val c = connection ?: return
        if (resumed && viewCols > 0) c.switchSession(s.id) else c.sessionId = s.id
    }

    private fun setStatus(text: String) {
        statusView.text = text
    }

    // ---------------------------------------------------------------- key bar

    private fun buildKeyBar(bar: LinearLayout) {
        fun keyButton(label: String, onClick: () -> Unit): TextView {
            val tv = TextView(this)
            tv.text = label
            tv.gravity = Gravity.CENTER
            tv.setTextColor(ContextCompat.getColor(this, R.color.keybar_text))
            tv.textSize = 14f
            val pad = (resources.displayMetrics.density * 12).toInt()
            tv.setPadding(pad, 0, pad, 0)
            tv.minWidth = (resources.displayMetrics.density * 44).toInt()
            tv.isClickable = true
            tv.setBackgroundResource(android.R.drawable.list_selector_background)
            tv.setOnClickListener { onClick() }
            bar.addView(tv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT))
            return tv
        }
        keyButton(SpecialKey.ESC.label) { sendSpecial(SpecialKey.ESC) }
        ctrlButton = keyButton("CTRL") { ctrlOn = !ctrlOn; refreshToggles() }
        altButton = keyButton("ALT") { altOn = !altOn; refreshToggles() }
        for (k in listOf(
            SpecialKey.TAB, SpecialKey.LEFT, SpecialKey.UP, SpecialKey.DOWN, SpecialKey.RIGHT,
            SpecialKey.HOME, SpecialKey.END, SpecialKey.PGUP, SpecialKey.PGDN,
            SpecialKey.PIPE, SpecialKey.TILDE, SpecialKey.SLASH, SpecialKey.MINUS,
        )) {
            keyButton(k.label) { sendSpecial(k) }
        }
        refreshToggles()
    }

    private fun refreshToggles() {
        val on = ContextCompat.getColor(this, R.color.keybar_active)
        ctrlButton.setBackgroundColor(if (ctrlOn) on else 0)
        altButton.setBackgroundColor(if (altOn) on else 0)
    }

    private fun sendSpecial(key: SpecialKey) {
        val appCursor = session.emulator?.isCursorKeysApplicationMode ?: false
        val bytes = KeyMapper.bytesFor(key, ctrlOn, altOn, appCursor)
        clearToggles()
        session.write(bytes, 0, bytes.size)
    }

    private fun clearToggles() {
        if (ctrlOn || altOn) {
            ctrlOn = false
            altOn = false
            refreshToggles()
        }
    }

    private fun toggleKeyboard() {
        terminalView.requestFocus()
        val imm = getSystemService(InputMethodManager::class.java) ?: return
        @Suppress("DEPRECATION")
        imm.toggleSoftInput(InputMethodManager.SHOW_IMPLICIT, 0)
    }

    private fun spToPx(sp: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, resources.displayMetrics).toInt()

    // ---------------------------------------------------------------- Termux callbacks

    private val sessionClient = object : TerminalSessionClient {
        override fun onTextChanged(changedSession: RemoteTerminalSession) = terminalView.onScreenUpdated()
        override fun onTitleChanged(changedSession: RemoteTerminalSession) {}
        override fun onSessionFinished(finishedSession: RemoteTerminalSession) {}
        override fun onCopyTextToClipboard(session: RemoteTerminalSession, text: String?) {
            if (text == null) return
            getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("terminal", text))
        }

        override fun onPasteTextFromClipboard(session: RemoteTerminalSession?) {
            val clip = getSystemService(ClipboardManager::class.java)?.primaryClip ?: return
            val text = clip.getItemAt(0)?.coerceToText(this@TerminalActivity)?.toString() ?: return
            terminalView.mEmulator?.paste(text)
        }

        override fun onBell(session: RemoteTerminalSession) {}
        override fun onColorsChanged(session: RemoteTerminalSession) {}
        override fun onTerminalCursorStateChange(state: Boolean) {}
        override fun getTerminalCursorStyle(): Int? = null
        override fun logError(tag: String?, message: String?) { Log.e(tag ?: TAG, message.orEmpty()) }
        override fun logWarn(tag: String?, message: String?) { Log.w(tag ?: TAG, message.orEmpty()) }
        override fun logInfo(tag: String?, message: String?) { Log.i(tag ?: TAG, message.orEmpty()) }
        override fun logDebug(tag: String?, message: String?) { Log.d(tag ?: TAG, message.orEmpty()) }
        override fun logVerbose(tag: String?, message: String?) { Log.v(tag ?: TAG, message.orEmpty()) }
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) { Log.e(tag ?: TAG, message.orEmpty(), e) }
        override fun logStackTrace(tag: String?, e: Exception?) { Log.e(tag ?: TAG, "error", e) }
    }

    private val viewClient = object : TerminalViewClient {
        override fun onScale(scale: Float): Float {
            if (scale < 0.9f || scale > 1.1f) {
                fontSizeSp = (fontSizeSp * (if (scale > 1f) 1.1f else 0.9f)).coerceIn(6f, 32f)
                terminalView.setTextSize(spToPx(fontSizeSp))
                return 1.0f
            }
            return scale
        }

        override fun onSingleTapUp(e: MotionEvent?) {
            terminalView.requestFocus()
            getSystemService(InputMethodManager::class.java)?.showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT)
        }

        override fun shouldBackButtonBeMappedToEscape() = false
        override fun shouldEnforceCharBasedInput() = false
        override fun shouldUseCtrlSpaceWorkaround() = false
        override fun isTerminalViewSelected() = true
        override fun copyModeChanged(copyMode: Boolean) {}

        override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: RemoteTerminalSession?): Boolean {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                finish()
                return true
            }
            return false
        }

        override fun onKeyUp(keyCode: Int, e: KeyEvent?) = false
        override fun onLongPress(event: MotionEvent?) = false

        // CTRL/ALT toggles are applied in onCodePoint through KeyMapper, so Termux's own handling stays off.
        override fun readControlKey() = false
        override fun readAltKey() = false
        override fun readShiftKey() = false
        override fun readFnKey() = false

        override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: RemoteTerminalSession?): Boolean {
            if (!ctrlOn && !altOn) return false
            val bytes = KeyMapper.encodeCodePoint(codePoint, ctrlOn || ctrlDown, altOn)
            clearToggles()
            this@TerminalActivity.session.write(bytes, 0, bytes.size)
            return true
        }

        override fun onEmulatorSet() {}
        override fun logError(tag: String?, message: String?) { Log.e(tag ?: TAG, message.orEmpty()) }
        override fun logWarn(tag: String?, message: String?) { Log.w(tag ?: TAG, message.orEmpty()) }
        override fun logInfo(tag: String?, message: String?) { Log.i(tag ?: TAG, message.orEmpty()) }
        override fun logDebug(tag: String?, message: String?) { Log.d(tag ?: TAG, message.orEmpty()) }
        override fun logVerbose(tag: String?, message: String?) { Log.v(tag ?: TAG, message.orEmpty()) }
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) { Log.e(tag ?: TAG, message.orEmpty(), e) }
        override fun logStackTrace(tag: String?, e: Exception?) { Log.e(tag ?: TAG, "error", e) }
    }

    companion object {
        private const val TAG = "mpta"
        private const val TRANSCRIPT_ROWS = 5000
        private const val EXTRA_ID = "id"
        private const val EXTRA_KIND = "kind"
        private const val EXTRA_PID = "pid"
        private const val EXTRA_TITLE = "title"

        fun intent(context: Context, s: SessionInfo): Intent =
            Intent(context, TerminalActivity::class.java)
                .putExtra(EXTRA_ID, s.id)
                .putExtra(EXTRA_KIND, if (s.isHistory) SessionInfo.KIND_HISTORY else s.kind)
                .putExtra(EXTRA_PID, s.pid ?: -1)
                .putExtra(EXTRA_TITLE, s.title)
    }
}
