package com.neststudio.pocketshell.desktop

import com.jediterm.core.util.TermSize
import com.jediterm.terminal.TerminalColor
import com.jediterm.terminal.TextStyle
import com.jediterm.terminal.TtyConnector
import com.jediterm.terminal.ui.JediTermWidget
import com.jediterm.terminal.ui.settings.DefaultSettingsProvider
import com.neststudio.pocketshell.core.ServerMessage
import com.neststudio.pocketshell.core.SessionInfo
import com.neststudio.pocketshell.data.Pairing
import com.neststudio.pocketshell.net.TerminalConnection
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import javax.swing.BorderFactory
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.SwingUtilities
import javax.swing.Timer

/** D2Coding (bundled; Hangul is double width) at the size from the settings. */
object TerminalFonts {
    private val base: Font by lazy {
        TerminalFonts::class.java.getResourceAsStream("/d2coding.ttf")?.use { Font.createFont(Font.TRUETYPE_FONT, it) }
            ?: Font(Font.MONOSPACED, Font.PLAIN, 14)
    }

    fun of(size: Int): Font = base.deriveFont(size.toFloat())
}

private val BG = Color(0x0E, 0x14, 0x26)
private val FG = Color(0xE6, 0xE9, 0xF0)

private class Settings(private val size: Int) : DefaultSettingsProvider() {
    override fun getTerminalFont(): Font = TerminalFonts.of(size)
    override fun getTerminalFontSize(): Float = size.toFloat()
    override fun getDefaultStyle(): TextStyle = TextStyle(TerminalColor.rgb(FG.red, FG.green, FG.blue), TerminalColor.rgb(BG.red, BG.green, BG.blue))
    override fun getBufferMaxLinesCount(): Int = 10_000
    override fun audibleBell(): Boolean = false
    override fun copyOnSelect(): Boolean = false
}

/**
 * JediTerm's input side for a PocketShell session: output bytes from the WebSocket are decoded (UTF-8, sequences split
 * across frames kept) and handed to the emulator's reader thread; keystrokes and size changes go back via callbacks.
 */
class RemoteTty(private val title: String) : TtyConnector {
    var onInput: (ByteArray) -> Unit = {}
    var onResize: (Int, Int) -> Unit = { _, _ -> }

    private val queue = LinkedBlockingQueue<CharArray>()
    private var current: CharArray? = null
    private var pos = 0
    private val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE)
    private var carry = ByteArray(0)
    private val ended = CountDownLatch(1)
    @Volatile private var closed = false

    @Synchronized
    fun feed(bytes: ByteArray) {
        val all = if (carry.isEmpty()) bytes else carry + bytes
        val bb = ByteBuffer.wrap(all)
        val cb = CharBuffer.allocate(all.size + 2)
        decoder.decode(bb, cb, false)
        carry = all.copyOfRange(bb.position(), all.size)
        cb.flip()
        if (cb.hasRemaining()) queue.put(CharArray(cb.remaining()).also { cb.get(it) })
    }

    fun feed(text: String) = queue.put(text.toCharArray())

    /** A new socket replays the whole output: clear the screen and the scrollback first. */
    @Synchronized
    fun resetForReplay() {
        decoder.reset()
        carry = ByteArray(0)
        queue.put("\u001bc\u001b[3J\u001b[H\u001b[2J".toCharArray())
    }

    override fun read(buf: CharArray, offset: Int, length: Int): Int {
        var c = current
        if (c == null || pos >= c.size) {
            c = queue.take()
            if (c === EOF) { queue.put(EOF); return -1 }
            current = c
            pos = 0
        }
        val n = minOf(length, c.size - pos)
        System.arraycopy(c, pos, buf, offset, n)
        pos += n
        return n
    }

    override fun write(bytes: ByteArray) = onInput(bytes)
    override fun write(string: String) = onInput(string.toByteArray(Charsets.UTF_8))
    override fun isConnected(): Boolean = !closed
    override fun resize(termSize: TermSize) = onResize(termSize.columns, termSize.rows)
    override fun waitFor(): Int { ended.await(); return 0 }
    override fun ready(): Boolean = queue.isNotEmpty() || (current?.let { pos < it.size } == true)
    override fun getName(): String = title

    override fun close() {
        if (closed) return
        closed = true
        queue.put(EOF)
        ended.countDown()
    }

    private companion object {
        val EOF = CharArray(0)
    }
}

/** One window per session: JediTerm + the shared TerminalConnection (reconnects with backoff, replay on connect). */
class TerminalWindow(private val pc: Pairing, private val session: SessionInfo, fontSize: Int, private val onClosed: () -> Unit = {}) {
    private val frame = JFrame("${session.displayTitle} — ${pc.displayName}")
    private val tty = RemoteTty(session.displayTitle)
    private val widget = JediTermWidget(120, 32, Settings(fontSize))
    private val status = JLabel(" 연결 중…")
    private val external = session.isExternal
    private var cols = 120
    private var rows = 32
    private val font = TerminalFonts.of(fontSize)

    private val connection: TerminalConnection

    init {
        val api = Apis.of(pc)
        connection = TerminalConnection(api.http, api.hosts, pc.port, pc.token, SwingPoster, Listener(), sizeProvider = { cols to rows })
        connection.sessionId = session.id
        connection.openPcTab = System.getenv("PKSHELL_NO_PC_TAB") != "1"
        tty.onInput = { bytes -> SwingUtilities.invokeLater { connection.sendInput(bytes) } }
        tty.onResize = { c, r ->
            SwingUtilities.invokeLater {
                if (c > 0 && r > 0 && (c != cols || r != rows)) {
                    cols = c
                    rows = r
                    if (!external) connection.sendResize(c, r)
                }
            }
        }
        status.foreground = Color(150, 160, 185)
        status.background = BG
        status.isOpaque = true
        status.border = BorderFactory.createEmptyBorder(3, 8, 3, 8)
        frame.contentPane.background = BG
        frame.contentPane.layout = BorderLayout()
        frame.contentPane.add(widget, BorderLayout.CENTER)
        frame.contentPane.add(status, BorderLayout.SOUTH)
        frame.defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
        TerminalWindow::class.java.getResourceAsStream("/icon.png")?.use { javax.imageio.ImageIO.read(it) }?.let { frame.iconImage = it }
        frame.addWindowListener(object : WindowAdapter() {
            override fun windowClosed(e: WindowEvent) {
                connection.disconnect()
                tty.close()
                widget.stop()
                onClosed()
            }
            override fun windowActivated(e: WindowEvent) { connection.connectNow() }
        })
    }

    /** False when the window was closed (open a new one). */
    fun bringToFront(): Boolean {
        if (!frame.isDisplayable) return false
        frame.isVisible = true
        frame.toFront()
        frame.requestFocus()
        return true
    }

    fun show() {
        frame.size = sizeFor(120, 32)
        frame.setLocationByPlatform(true)
        widget.setTtyConnector(tty)
        widget.start()
        frame.isVisible = true
        widget.requestFocusInWindow()
        // Let the panel lay out (JediTerm reports the grid through resize) before the first connect.
        Timer(150) { connection.connectNow() }.apply { isRepeats = false }.start()
    }

    /** Window size that holds [c]×[r] cells in the terminal font (plus the scroll bar and status line). */
    private fun sizeFor(c: Int, r: Int): Dimension {
        val fm = frame.getFontMetrics(font)
        val cw = fm.charWidth('W')
        val ch = fm.height
        val insets = frame.insets
        return Dimension(c * cw + 24 + insets.left + insets.right, r * ch + 30 + insets.top + insets.bottom)
    }

    private fun setStatus(text: String) { status.text = " $text" }

    private inner class Listener : TerminalConnection.Listener {
        override fun onConnected(host: String) {
            tty.resetForReplay()
            setStatus("연결됨 · $host")
            if (!external) connection.sendResize(cols, rows)
        }

        override fun onOutput(bytes: ByteArray) = tty.feed(bytes)

        override fun onControl(message: ServerMessage) {
            when (message) {
                is ServerMessage.Hello -> message.session?.let { frame.title = "${it.displayTitle} — ${pc.displayName}" }
                is ServerMessage.Size -> {
                    // A PC console keeps its own size: fit the window to it so its lines do not wrap here.
                    if (external && (message.cols != cols || message.rows != rows)) frame.size = sizeFor(message.cols, message.rows)
                    setStatus("연결됨 · PC ${message.cols}×${message.rows}")
                }
                is ServerMessage.Exit -> {
                    connection.autoReconnect = false
                    tty.feed("\r\n\u001b[0m[세션 종료 (코드 ${message.code})]\r\n")
                    setStatus("세션 종료 (코드 ${message.code})")
                }
                is ServerMessage.ReplayEnd, is ServerMessage.History, is ServerMessage.Unknown -> {}
            }
        }

        override fun onReconnectScheduled(delayMillis: Long, reason: String) =
            setStatus("연결 끊김 ($reason) · ${(delayMillis + 999) / 1000}초 후 다시 연결")

        override fun onFatal(reason: String, authFailure: Boolean) = setStatus(reason)
    }
}
