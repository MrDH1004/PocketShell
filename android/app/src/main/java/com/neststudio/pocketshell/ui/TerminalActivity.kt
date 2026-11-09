package com.neststudio.pocketshell.ui

import android.app.Activity
import android.content.ActivityNotFoundException
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
import android.speech.RecognizerIntent
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.Menu
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
import com.neststudio.pocketshell.PkshellApp
import com.neststudio.pocketshell.R
import com.neststudio.pocketshell.core.ConsoleHistory
import com.neststudio.pocketshell.core.ImageSizing
import com.neststudio.pocketshell.core.KeyMapper
import com.neststudio.pocketshell.core.Reflow
import com.neststudio.pocketshell.core.ServerMessage
import com.neststudio.pocketshell.core.SessionInfo
import com.neststudio.pocketshell.core.SpecialKey
import com.neststudio.pocketshell.data.UiPrefs
import com.neststudio.pocketshell.net.ApiClient
import com.neststudio.pocketshell.net.TerminalConnection
import com.termux.terminal.RemoteTerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Locale

/**
 * Terminal screen (AC-15..18): Termux TerminalView backed by [RemoteTerminalSession] over a WebSocket.
 * - history session → POST resume first, then attach to the new session.
 * - external session → mirrors a console opened on the PC (fit to the PC console size).
 */
class TerminalActivity : AppCompatActivity() {

    private lateinit var terminalView: TerminalView
    private lateinit var zoom: ZoomPanLayout
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

    private lateinit var uiPrefs: UiPrefs
    private var fontSizeSp = UiPrefs.DEFAULT_FONT_SP
    private var typeface: Typeface = Typeface.MONOSPACE
    private var textPx = 0

    /** External console: lines scrolled off the PC window + the current window (scroll-back on the phone). */
    private val history = ConsoleHistory()
    /** Lines above the live bottom the user scrolled to (0 = live). */
    private var scroll = 0
    /** External console's row count from the last size notice (0 = unknown). */
    private var consoleRows = 0
    private var consoleCols = 0
    /** Phone grid (cols * 10000 + rows) the current screen content was drawn for; -1 forces a redraw. */
    private var drawnRows = 0
    private val main = Handler(Looper.getMainLooper())
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var pastingImage = false
    private val pickImage = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) pasteImage(uri)
    }
    private var currentNetwork: Network? = null
    /** System speech recognizer (no RECORD_AUDIO needed here): the result is typed into the terminal, no Enter. */
    private val voiceInput = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val text = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim().orEmpty()
        if (text.isNotEmpty()) insertText(text)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_terminal)
        findViewById<View>(R.id.root).applySystemInsets(includeIme = true)

        val app = application as PkshellApp
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
        zoom = findViewById(R.id.zoom)
        zoom.onOverscrollY = { terminalView.scrollByFinger(it) } // zoomed: dragging past the edge scrolls history
        titleView.text = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { sessionId }

        session = RemoteTerminalSession(TRANSCRIPT_ROWS, sessionClient, object : RemoteTerminalSession.Transport {
            override fun sendInput(data: ByteArray) {
                if (scroll > 0) { // typing returns to the live view
                    scroll = 0
                    terminalView.resetRemoteOffset()
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
        uiPrefs = UiPrefs(this)
        fontSizeSp = uiPrefs.fontSizeSp
        typeface = TerminalFont.of(uiPrefs.fontId).typeface(this)
        applyTextSize(spToPx(fontSizeSp))
        terminalView.setTypeface(typeface)
        terminalView.attachSession(session)
        terminalView.keepScreenOn = true

        buildKeyBar(findViewById(R.id.keyBar))
        findViewById<View>(R.id.keyboardButton).setOnClickListener { toggleKeyboard() }
        findViewById<View>(R.id.fitButton).setOnClickListener { resetSize() }
        findViewById<View>(R.id.moreButton).setOnClickListener { showOptions(it) }
        findViewById<View>(R.id.filesButton).setOnClickListener { openFiles() }
        findViewById<View>(R.id.voiceButton).setOnClickListener { startVoiceInput() }
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

    /** ⋮ top-bar menu: info, view settings (fit to screen is its own top-bar button). */
    private fun showOptions(anchor: View) {
        val menu = android.widget.PopupMenu(this, anchor)
        // "정보" submenu: connection + size info (was the title bar's second line), one read-only line per part.
        // Each entry is its own group so group dividers draw a line between all of them (API 28+).
        val info = menu.menu.addSubMenu(1, Menu.NONE, 99, R.string.terminal_info)
        info.setHeaderTitle(R.string.terminal_info)
        // First line: the full session title (the title bar truncates it).
        info.add(9, Menu.NONE, 0, titleView.text).isEnabled = false
        statusView.text.toString().split(" · ").filter { it.isNotBlank() }.forEachIndexed { i, part ->
            info.add(10 + i, Menu.NONE, i + 1, part).isEnabled = false
        }
        menu.menu.add(3, OPT_VIEW, 101, R.string.view_settings)
        androidx.core.view.MenuCompat.setGroupDividerEnabled(menu.menu, true)
        androidx.core.view.MenuCompat.setGroupDividerEnabled(info, true)
        menu.setOnMenuItemClickListener {
            when (it.itemId) {
                OPT_VIEW -> {
                    val note = if (sessionKind == SessionInfo.KIND_EXTERNAL) getString(R.string.view_external_note) else null
                    ViewSettingsDialog.show(this, note) { applyViewSettings() }
                }
            }
            true
        }
        menu.show()
    }

    /** Opens the file browser at the session's current folder (asked fresh: the shell may have cd'd). */
    private fun openFiles() {
        val a = api ?: return
        val id = sessionId
        lifecycleScope.launch {
            val cwd = try {
                a.session(id).cwd
            } catch (e: IOException) {
                Log.w(TAG, "session cwd", e)
                ""
            } catch (e: org.json.JSONException) {
                ""
            }
            if (cwd.isBlank()) {
                Toast.makeText(this@TerminalActivity, R.string.files_no_folder, Toast.LENGTH_LONG).show()
                return@launch
            }
            startActivity(FileBrowserActivity.intent(this@TerminalActivity, cwd, titleView.text.toString()))
        }
    }

    /** Top-bar button: undo the pinch zoom (1x, picture back in place) and go back to the saved font size. */
    private fun resetSize() {
        zoom.reset()
        fontSizeSp = uiPrefs.fontSizeSp
        refit()
    }

    /** Settings dialog changed the size or font: apply both (refitting an external console); the pinch zoom is kept. */
    private fun applyViewSettings() {
        fontSizeSp = uiPrefs.fontSizeSp
        typeface = TerminalFont.of(uiPrefs.fontId).typeface(this)
        terminalView.setTypeface(typeface)
        refit()
    }

    /**
     * The user's font size, for every session kind. An external console keeps the PC window's size, but the phone
     * does not mirror it: its rows are re-flowed to the phone's columns (ConsoleHistory/Reflow, DESIGN §8).
     */
    private fun refit() {
        if (sessionKind == SessionInfo.KIND_EXTERNAL) fitExternal() else applyTextSize(spToPx(fontSizeSp))
        scroll = 0
        terminalView.resetRemoteOffset()
        drawnRows = -1
        redrawIfRowsChanged()
    }

    /** External console: the column count rows are laid out at (Reflow.fitCols), 0 = the view's columns. */
    private var reflowCols = 0
    /** View width [fitExternal] measured for (re-measured when it changes, e.g. rotation). */
    private var fittedWidth = 0

    private fun layoutCols(): Int = if (reflowCols in 1..viewCols) reflowCols else viewCols

    /**
     * External console font: the user's size, shrunk a little (Reflow.MAX_SHRINK at most) when that makes full PC
     * rows split into whole phone lines instead of leaving a few stray characters on an extra line. Columns are
     * measured the same way as TerminalRenderer: floor(width / width of "X").
     */
    private fun fitExternal() {
        val userPx = spToPx(fontSizeSp)
        val width = terminalView.width
        if (width <= 0 || consoleCols <= 0) {
            reflowCols = 0
            applyTextSize(userPx)
            return
        }
        fittedWidth = width
        val face = typeface
        val paint = Paint().apply { typeface = face; isAntiAlias = true }
        fun colsAt(px: Int): Int {
            paint.textSize = px.toFloat()
            return (width / paint.measureText("X")).toInt()
        }
        val target = Reflow.fitCols(consoleCols, colsAt(userPx))
        var px = userPx
        while (px > MIN_TEXT_PX && colsAt(px) < target) px--
        reflowCols = target
        if (px != textPx) {
            applyTextSize(px)
            drawnRows = -1
        }
    }

    /** Draws (history + window) for the current phone rows and scroll position (external consoles). */
    private fun drawExternal() {
        if (!history.hasFrame) return
        val out = history.render(viewRows, scroll, layoutCols())
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
        // View size changed (keyboard shown/hidden, rotation, font): re-flow an external console to the new grid.
        if (sessionKind == SessionInfo.KIND_EXTERNAL) main.post {
            if (terminalView.width != fittedWidth && consoleCols > 0) fitExternal() // width changed (rotation)
            redrawIfRowsChanged()
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
                    if (scroll > 0) scroll += history.wrappedCount(shifted, layoutCols()) // keep the lines the user is reading in place
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
                        history.pcCols = message.cols
                        fitExternal()
                        drawnRows = -1 // new PC size: lay the rows out again
                        redrawIfRowsChanged()
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
                setStatus(getString(R.string.err_resume, e.message.orEmpty()))
            } catch (e: org.json.JSONException) {
                setStatus(getString(R.string.err_resume, getString(R.string.err_hint_update_pc)))
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
                val (png, jpeg) = withContext(Dispatchers.IO) { encodeImage(uri) }
                a.postClipboardImage(png, jpeg)
                val c = connection
                if (c == null) {
                    Toast.makeText(this@TerminalActivity, "PC 클립보드에 넣었습니다 (세션 연결 없음: PC에서 Alt+V)", Toast.LENGTH_LONG).show()
                } else {
                    c.sendInput(KeyMapper.encodeCodePoint('v'.code, ctrl = false, alt = true))
                    Toast.makeText(this@TerminalActivity, "이미지를 붙였습니다 (${png.size / 1024} KB)", Toast.LENGTH_SHORT).show()
                }
            } catch (e: IOException) {
                Log.w(TAG, "image paste failed", e)
                showPasteError("${e.javaClass.simpleName}: ${e.message}")
            } catch (e: OutOfMemoryError) {
                Log.w(TAG, "image paste failed", e)
                showPasteError("이미지가 너무 큽니다 (메모리 부족)")
            } catch (e: SecurityException) {
                Log.w(TAG, "image paste failed", e)
                showPasteError("이미지 파일 접근 권한이 없습니다: ${e.message}")
            } finally {
                pastingImage = false
            }
        }
    }

    /** Shown in a dialog (not a toast) so the whole reason stays readable and can be reported. */
    private fun showPasteError(reason: String) {
        if (isFinishing) return
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.err_paste_title)
            .setMessage(getString(R.string.retry_connect_hint) + ErrorText.cause(this, reason))
            .setPositiveButton(R.string.confirm, null)
            .show()
    }

    /**
     * PNG (lossless, best for screenshots); a photo whose PNG is larger than [MAX_PNG_BYTES] is sent as JPEG instead
     * (a 2560 px photo is ~10 MB as PNG, under 1 MB as JPEG). Returns (bytes, isJpeg).
     */
    private fun encodeImage(uri: Uri): Pair<ByteArray, Boolean> {
        val png = encodePng(uri)
        if (png.size <= MAX_PNG_BYTES) return png to false
        val bitmap = BitmapFactory.decodeByteArray(png, 0, png.size) ?: return png to false
        return ByteArrayOutputStream().use { out ->
            val ok = bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            bitmap.recycle()
            if (ok) out.toByteArray() to true else png to false
        }
    }

    /** Decodes any image the picker returns (incl. HEIC/WebP) and re-encodes it as PNG, longest side ≤ MAX_SIDE. */
    private fun encodePng(uri: Uri): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // With inJustDecodeBounds decodeStream always returns null (only `bounds` is filled), so only a null
        // stream means the image could not be opened.
        val stream = contentResolver.openInputStream(uri) ?: throw IOException("이미지를 열 수 없습니다")
        stream.use { BitmapFactory.decodeStream(it, null, bounds) }
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

    private fun startVoiceInput() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            .putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.voice_prompt))
        try {
            voiceInput.launch(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.voice_unavailable, Toast.LENGTH_LONG).show()
        }
    }

    /** Same path as a clipboard paste (bracketed paste when the app asked for it), so nothing is executed. */
    private fun insertText(text: String) {
        val emulator = terminalView.mEmulator
        if (emulator == null || connection?.sessionId.isNullOrEmpty()) {
            Toast.makeText(this, R.string.voice_not_connected, Toast.LENGTH_SHORT).show()
            return
        }
        emulator.paste(text)
        terminalView.resetImeLine()
    }

    /**
     * Connected: the dot alone (green), the details live in the ⋮ menu. Anything else (connecting, disconnected,
     * reconnecting) is also written under the title, so the state never rests on the dot's color. The dot carries
     * the full text for TalkBack.
     */
    private fun setStatus(text: String) {
        statusView.text = text
        val live = text.startsWith("연결됨")
        val dot = findViewById<View>(R.id.connDot)
        dot.backgroundTintList = android.content.res.ColorStateList.valueOf(
            ContextCompat.getColor(this, if (live) R.color.live else R.color.text_muted),
        )
        dot.contentDescription = getString(R.string.conn_status_desc, text)
        statusView.visibility = if (live) View.GONE else View.VISIBLE
    }

    // ---------------------------------------------------------------- key bar

    private fun buildKeyBar(bar: LinearLayout) {
        fun keyButton(label: String, spoken: Int? = null, onClick: () -> Unit): TextView {
            val tv = TextView(this)
            tv.text = label
            if (spoken != null) tv.contentDescription = getString(spoken)
            // Announced as a button by TalkBack (a plain clickable TextView has no role).
            androidx.core.view.ViewCompat.setAccessibilityDelegate(tv, object : androidx.core.view.AccessibilityDelegateCompat() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: androidx.core.view.accessibility.AccessibilityNodeInfoCompat) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = Button::class.java.name
                }
            })
            tv.gravity = Gravity.CENTER
            tv.setTextColor(ContextCompat.getColor(this, R.color.keybar_text))
            tv.textSize = 13f
            tv.typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            val d = resources.displayMetrics.density
            tv.setPadding((d * 12).toInt(), 0, (d * 12).toInt(), (d * 2).toInt()) // bottom = the keycap's lip
            tv.minWidth = (d * 44).toInt()
            tv.isClickable = true
            tv.setBackgroundResource(R.drawable.bg_keycap)
            tv.setOnClickListener { onClick() }
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT)
            lp.marginStart = (d * 4).toInt()
            lp.marginEnd = (d * 4).toInt()
            bar.addView(tv, lp)
            return tv
        }
        for (k in listOf(SpecialKey.ESC, SpecialKey.TAB, SpecialKey.ENTER)) keyButton(k.label, spokenName(k)) { sendSpecial(k) }
        ctrlButton = keyButton("CTRL") { ctrlOn = !ctrlOn; refreshToggles() }
        altButton = keyButton("ALT") { altOn = !altOn; refreshToggles() }
        // Hidden on the bar (not used); the toggle handling is kept.
        ctrlButton.visibility = View.GONE
        altButton.visibility = View.GONE
        for (k in listOf(
            SpecialKey.LEFT, SpecialKey.UP, SpecialKey.DOWN, SpecialKey.RIGHT,
            SpecialKey.SLASH, SpecialKey.PIPE, SpecialKey.TILDE, SpecialKey.MINUS,
            SpecialKey.HOME, SpecialKey.END, SpecialKey.PGUP, SpecialKey.PGDN,
        )) {
            keyButton(k.label, spokenName(k)) { sendSpecial(k) }
        }
        refreshToggles()
    }

    /** What TalkBack says for a key cap (symbols and abbreviations read badly). */
    private fun spokenName(k: SpecialKey): Int = when (k) {
        SpecialKey.ESC -> R.string.key_esc
        SpecialKey.TAB -> R.string.key_tab
        SpecialKey.ENTER -> R.string.key_enter
        SpecialKey.LEFT -> R.string.key_left
        SpecialKey.UP -> R.string.key_up
        SpecialKey.DOWN -> R.string.key_down
        SpecialKey.RIGHT -> R.string.key_right
        SpecialKey.HOME -> R.string.key_home
        SpecialKey.END -> R.string.key_end
        SpecialKey.PGUP -> R.string.key_pgup
        SpecialKey.PGDN -> R.string.key_pgdn
        SpecialKey.PIPE -> R.string.key_pipe
        SpecialKey.TILDE -> R.string.key_tilde
        SpecialKey.SLASH -> R.string.key_slash
        SpecialKey.MINUS -> R.string.key_minus
    }

    private fun refreshToggles() {
        ctrlButton.setBackgroundResource(if (ctrlOn) R.drawable.bg_keycap_on else R.drawable.bg_keycap)
        altButton.setBackgroundResource(if (altOn) R.drawable.bg_keycap_on else R.drawable.bg_keycap)
    }

    private fun sendSpecial(key: SpecialKey) {
        val appCursor = session.emulator?.isCursorKeysApplicationMode ?: false
        val bytes = KeyMapper.bytesFor(key, ctrlOn, altOn, appCursor)
        clearToggles()
        session.write(bytes, 0, bytes.size)
        terminalView.resetImeLine() // the keyboard must not re-type the sent / moved-away line on backspace
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
        // Pinch is handled by ZoomPanLayout (picture zoom + drag); the font size is set in 보기 설정.
        override fun onScale(scale: Float): Float = 1.0f

        override fun onRemoteScroll(rowsDown: Int): Boolean {
            if (sessionKind != SessionInfo.KIND_EXTERNAL || !history.hasFrame) return false
            val next = (scroll - rowsDown).coerceIn(0, history.maxScroll(viewRows, layoutCols()))
            if (next != scroll) {
                scroll = next
                drawExternal()
            }
            return true
        }

        override fun canRemoteScroll(rowsDown: Int): Boolean =
            if (rowsDown < 0) scroll < history.maxScroll(viewRows, layoutCols()) else scroll > 0

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
        private const val MIN_TEXT_PX = 8
        private const val TAG = "pkshell"
        private const val TRANSCRIPT_ROWS = 5000
        private const val MAX_PNG_BYTES = 2 * 1024 * 1024
        private const val OPT_VIEW = 2
        /** Below this the text is unreadable; a wider console is then cut off instead. */
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
