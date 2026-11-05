package com.mpta.client.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
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
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.mpta.client.MptaApp
import com.mpta.client.R
import com.mpta.client.core.ConsoleHistory
import com.mpta.client.core.ImageSizing
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Terminal screen (AC-15..18): Termux TerminalView backed by [RemoteTerminalSession] over a WebSocket.
 * - history session → POST resume first, then attach to the new session.
 * - external session → mirrors a console opened on the PC (fit to the PC console size).
 */
class TerminalActivity : AppCompatActivity() {

    private lateinit var terminalView: TerminalView
    private lateinit var statusView: TextView
    private lateinit var titleView: TextView
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

    private var fontSizeSp = DEFAULT_FONT_SP
    private var textPx = 0

    /**
     * External consoles keep the PC window's size (the phone cannot resize them), so the font is shrunk until
     * the whole console (fitCols×fitRows) fits the view. 0 = no fitting (host session, or the user zoomed manually).
     */
    private var fitCols = 0
    private var fitRows = 0
    /** External console: lines scrolled off the PC window + the current window (scroll-back on the phone). */
    private val history = ConsoleHistory()
    /** Lines above the live bottom the user scrolled to (0 = live). */
    private var scroll = 0
    /** External console's row count from the last size notice (0 = unknown). */
    private var consoleRows = 0
    private var consoleCols = 0
    /** Phone grid (cols * 10000 + rows) the current screen content was drawn for; -1 forces a redraw. */
    private var drawnRows = 0
    private var fitWidth = 0
    private var fitHeight = 0
    private val main = Handler(Looper.getMainLooper())
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var pastingImage = false
    private val pickImage = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) pasteImage(uri)
    }
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
        terminalView = findViewById(R.id.terminal)
        titleView.text = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { sessionId }

        session = RemoteTerminalSession(TRANSCRIPT_ROWS, sessionClient, object : RemoteTerminalSession.Transport {
            override fun sendInput(data: ByteArray) {
                if (scroll > 0) { // typing returns to the live view
                    scroll = 0
                    drawExternal()
                }
                connection?.sendInput(data)
            }

            override fun onResize(columns: Int, rows: Int) {
                onViewResized(columns, rows)
            }
        })

        connection = TerminalConnection(apiClient.http, apiClient.hosts, pairing.port, pairing.token, connectionListener) {
            if (viewCols > 0) viewCols to viewRows else 80 to 24
        }

        terminalView.setTerminalViewClient(viewClient)
        applyTextSize(spToPx(fontSizeSp))
        terminalView.attachSession(session)
        terminalView.keepScreenOn = true

        buildKeyBar(findViewById(R.id.keyBar))
        findViewById<View>(R.id.keyboardButton).setOnClickListener { toggleKeyboard() }
        findViewById<View>(R.id.fitButton).setOnClickListener { resetSize() }
        findViewById<View>(R.id.pasteImageButton).setOnClickListener {
            if (!pastingImage) pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }

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

    private fun applyTextSize(px: Int) {
        textPx = px
        terminalView.setTextSize(px) // re-measures synchronously → onViewResized if the cell grid changed
    }

    /**
     * Largest font (up to the user's size) at which the whole [cols]×[rows] console fits the view, measured the
     * same way as TerminalView/TerminalRenderer (columns = floor(width / "X" width), rows = floor((height -
     * (lineSpacing + ascent)) / lineSpacing)). Applied in one step so the frame that follows the size notice is
     * drawn at the final grid.
     */
    private fun fitToConsole(cols: Int, rows: Int) {
        val width = terminalView.width
        val height = terminalView.height
        if (cols <= 0 || rows <= 0 || width <= 0 || height <= 0) return
        fitCols = cols
        fitRows = rows
        fitWidth = width
        fitHeight = height
        // Keyboard up: fit the width only and show the bottom part (ConsoleHistory) instead of a tiny whole console.
        // Judged by the IME padding actually applied to the root (applySystemInsets), which is what resized this view:
        // the IME inset flag could still say "visible" while the keyboard was closing, leaving the width-only font.
        val root = findViewById<View>(R.id.root)
        val widthOnly = root.height > 0 && root.paddingBottom > root.height / 5
        Log.i(TAG, "fit ${cols}x$rows view=${width}x$height rootPadBottom=${root.paddingBottom}/${root.height} widthOnly=$widthOnly")
        val paint = Paint().apply { typeface = Typeface.MONOSPACE; isAntiAlias = true }
        var px = spToPx(fontSizeSp)
        while (px > MIN_FIT_TEXT_PX) {
            paint.textSize = px.toFloat()
            val lineSpacing = ceil(paint.fontSpacing).toInt()
            val viewCols = (width / paint.measureText("X")).toInt()
            val viewRows = (height - (lineSpacing + ceil(paint.ascent()).toInt())) / lineSpacing
            if (viewCols >= cols && (widthOnly || viewRows >= rows)) break
            px--
        }
        if (px != textPx) {
            applyTextSize(px)
            drawnRows = -1 // the grid changed (columns too): always redraw
        }
        redrawIfRowsChanged()
    }

    /** Top-bar button: undo pinch zoom — default font, and for an external console fit it to the PC size again. */
    private fun resetSize() {
        fontSizeSp = DEFAULT_FONT_SP
        if (sessionKind == SessionInfo.KIND_EXTERNAL && consoleCols > 0 && consoleRows > 0) {
            fitWidth = 0 // force the measurement even if the view size is unchanged
            fitToConsole(consoleCols, consoleRows)
        } else {
            applyTextSize(spToPx(fontSizeSp))
        }
        scroll = 0
        drawnRows = -1
        redrawIfRowsChanged()
    }

    /** Draws (history + window) for the current phone rows and scroll position (external consoles). */
    private fun drawExternal() {
        if (!history.hasFrame) return
        val out = history.render(viewRows, scroll)
        session.appendRemoteOutput(out, 0, out.size)
        updateExternalStatus()
    }

    private fun updateExternalStatus() {
        if (consoleRows <= 0) return
        val where = if (scroll > 0) "기록 ${scroll}줄 위 (아래로 밀면 실시간)" else "화면 ${viewCols}×${viewRows}"
        setStatus("연결됨 · PC ${consoleCols}×${consoleRows} · $where")
    }

    /** The PC sends a frame only when its console changes: redraw after the phone's grid changed. */
    private fun redrawIfRowsChanged() {
        val grid = viewCols * 10_000 + viewRows
        if (grid == drawnRows) return
        drawnRows = grid
        if (sessionKind == SessionInfo.KIND_EXTERNAL) drawExternal()
    }

    private fun onViewResized(cols: Int, rows: Int) {
        // View size changed (keyboard shown/hidden, rotation): refit the console to the new area.
        if (fitCols > 0 && (terminalView.width != fitWidth || terminalView.height != fitHeight)) {
            main.post { if (fitCols > 0) fitToConsole(fitCols, fitRows) }
        } else if (sessionKind == SessionInfo.KIND_EXTERNAL) {
            main.post { redrawIfRowsChanged() }
        }
        val first = viewCols == 0
        viewCols = cols
        viewRows = rows
        if (first) maybeConnect() else connection?.sendResize(cols, rows)
    }

    private val connectionListener = object : TerminalConnection.Listener {
        override fun onConnected(host: String) {
            // The server replays the whole output.vt after hello: start from a blank emulator.
            session.resetForReplay() // external history survives reconnects (same console)
            terminalView.onScreenUpdated()
            setStatus("연결됨 · $host · 재생 중…")
            // The size in the URL may be stale if the view changed while connecting.
            connection?.sendResize(viewCols, viewRows)
        }

        override fun onOutput(bytes: ByteArray) {
            if (sessionKind == SessionInfo.KIND_EXTERNAL) {
                val shifted = history.accept(bytes)
                if (shifted >= 0) {
                    if (scroll > 0) scroll += shifted // keep the lines the user is reading in place
                    drawnRows = viewCols * 10_000 + viewRows
                    drawExternal()
                    return
                }
            }
            session.appendRemoteOutput(bytes, 0, bytes.size)
        }

        override fun onControl(message: ServerMessage) {
            when (message) {
                is ServerMessage.Hello -> message.session?.let { s ->
                    titleView.text = s.title.ifBlank { s.id }
                }
                is ServerMessage.Size -> {
                    if (sessionKind == SessionInfo.KIND_EXTERNAL) {
                        consoleRows = message.rows
                        consoleCols = message.cols
                        fitToConsole(message.cols, message.rows)
                    }
                    setStatus("연결됨 · PC ${message.cols}×${message.rows} · 화면 ${viewCols}×${viewRows}")
                }
                is ServerMessage.History -> {
                    history.replaceHistory(message.lines)
                    drawnRows = -1
                    redrawIfRowsChanged()
                }
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

    private fun switchTo(s: SessionInfo) {
        sessionId = s.id
        sessionKind = s.kind
        titleView.text = s.title.ifBlank { s.id }
        val c = connection ?: return
        if (resumed && viewCols > 0) c.switchSession(s.id) else c.sessionId = s.id
    }

    /**
     * Sends the picked image to the PC clipboard, then Alt+V (ESC v) on this session: Claude Code attaches the
     * clipboard image as [Image #N], the same as pressing Alt+V at the PC.
     */
    private fun pasteImage(uri: Uri) {
        val a = api ?: return
        pastingImage = true
        Toast.makeText(this, "이미지를 PC로 보내는 중…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            try {
                val png = withContext(Dispatchers.IO) { encodePng(uri) }
                a.postClipboardImage(png)
                val c = connection
                if (c == null) {
                    Toast.makeText(this@TerminalActivity, "PC 클립보드에 넣었습니다 (세션 연결 없음: PC에서 Alt+V)", Toast.LENGTH_LONG).show()
                } else {
                    c.sendInput(KeyMapper.encodeCodePoint('v'.code, ctrl = false, alt = true))
                    Toast.makeText(this@TerminalActivity, "이미지를 붙였습니다 (${png.size / 1024} KB)", Toast.LENGTH_SHORT).show()
                }
            } catch (e: IOException) {
                Log.w(TAG, "image paste failed", e)
                Toast.makeText(this@TerminalActivity, "이미지 붙여넣기 실패: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                pastingImage = false
            }
        }
    }

    /** Decodes any image the picker returns (incl. HEIC/WebP) and re-encodes it as PNG, longest side ≤ MAX_SIDE. */
    private fun encodePng(uri: Uri): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            ?: throw IOException("이미지를 열 수 없습니다")
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("지원하지 않는 이미지 형식입니다")
        val opts = BitmapFactory.Options().apply { inSampleSize = ImageSizing.sampleSize(bounds.outWidth, bounds.outHeight) }
        val decoded = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: throw IOException("이미지를 읽을 수 없습니다")
        val (w, h) = ImageSizing.fit(decoded.width, decoded.height)
        val bitmap = if (w != decoded.width || h != decoded.height) {
            Bitmap.createScaledBitmap(decoded, w, h, true).also { decoded.recycle() }
        } else decoded
        return ByteArrayOutputStream().use { out ->
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) throw IOException("PNG 변환 실패")
            bitmap.recycle()
            out.toByteArray()
        }
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
        for (k in listOf(SpecialKey.ESC, SpecialKey.TAB, SpecialKey.ENTER)) keyButton(k.label) { sendSpecial(k) }
        ctrlButton = keyButton("CTRL") { ctrlOn = !ctrlOn; refreshToggles() }
        altButton = keyButton("ALT") { altOn = !altOn; refreshToggles() }
        altButton.visibility = View.GONE // hidden on the bar; ALT handling is kept
        for (k in listOf(
            SpecialKey.LEFT, SpecialKey.UP, SpecialKey.DOWN, SpecialKey.RIGHT,
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
                // Manual zoom wins over fit-to-columns (an external console may then be cut off on the right).
                fitCols = 0
                fontSizeSp = (fontSizeSp * (if (scale > 1f) 1.1f else 0.9f)).coerceIn(6f, MAX_FONT_SP)
                applyTextSize(spToPx(fontSizeSp))
                return 1.0f
            }
            return scale
        }

        override fun onRemoteScroll(rowsDown: Int): Boolean {
            if (sessionKind != SessionInfo.KIND_EXTERNAL || !history.hasFrame) return false
            val next = (scroll - rowsDown).coerceIn(0, history.maxScroll(viewRows))
            if (next != scroll) {
                scroll = next
                drawExternal()
            }
            return true
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
        private const val MAX_FONT_SP = 32f
        private const val DEFAULT_FONT_SP = 12f
        /** Below this the text is unreadable; a wider console is then cut off instead. */
        private const val MIN_FIT_TEXT_PX = 8
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
