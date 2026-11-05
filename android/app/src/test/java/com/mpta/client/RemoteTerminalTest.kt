package com.mpta.client

import com.termux.terminal.RemoteTerminalSession
import com.termux.terminal.TextStyle
import com.termux.terminal.WcWidth
import com.termux.view.ImeTextDecoder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** Feeds VT byte streams through RemoteTerminalSession into the vendored Termux TerminalEmulator. */
class RemoteTerminalTest {

    private class FakeTransport : RemoteTerminalSession.Transport {
        val sent = ByteArrayOutputStream()
        val resizes = ArrayList<Pair<Int, Int>>()
        override fun sendInput(data: ByteArray) = sent.write(data)
        override fun onResize(columns: Int, rows: Int) {
            resizes.add(columns to rows)
        }
    }

    private fun newSession(cols: Int = 80, rows: Int = 24): Pair<RemoteTerminalSession, FakeTransport> {
        val t = FakeTransport()
        // Client is null: the emulator logs through android.util.Log stubs (returnDefaultValues = true).
        val s = RemoteTerminalSession(null, null, t)
        s.updateSize(cols, rows, 10, 20)
        return s to t
    }

    private fun RemoteTerminalSession.feed(text: String) {
        val b = text.toByteArray(Charsets.UTF_8)
        appendRemoteOutput(b, 0, b.size)
    }

    private fun RemoteTerminalSession.row(r: Int) =
        emulator.screen.allocateFullLineIfNecessary(emulator.screen.externalToInternalRow(r))

    @Test
    fun truecolorForegroundIsExact24BitColor() {
        val (s, _) = newSession()
        s.feed("\u001b[38;2;255;100;0mX")
        val style = s.emulator.screen.getStyleAt(0, 0)
        val expected = (0xff000000L or (255L shl 16) or (100L shl 8) or 0L).toInt()
        assertEquals(expected, TextStyle.decodeForeColor(style))
        assertEquals('X', s.row(0).mText[s.row(0).findStartOfColumn(0)])
    }

    @Test
    fun truecolorBackgroundIsExact24BitColor() {
        val (s, _) = newSession()
        s.feed("\u001b[48;2;1;2;3mY")
        val style = s.emulator.screen.getStyleAt(0, 0)
        assertEquals((0xff000000L or 0x010203L).toInt(), TextStyle.decodeBackColor(style))
    }

    @Test
    fun hangulOccupiesTwoColumnsAsciiOne() {
        val (s, _) = newSession()
        s.feed("한글A")
        assertEquals(2, WcWidth.width('한'.code))
        assertEquals(2, WcWidth.width('글'.code))
        assertEquals(1, WcWidth.width('A'.code))
        // 한 → columns 0-1, 글 → columns 2-3, A → column 4; cursor after them at column 5.
        assertEquals(5, s.emulator.cursorCol)
        val row = s.row(0)
        assertEquals('한', row.mText[row.findStartOfColumn(0)])
        assertEquals('글', row.mText[row.findStartOfColumn(2)])
        assertEquals('A', row.mText[row.findStartOfColumn(4)])
        assertEquals(' ', row.mText[row.findStartOfColumn(5)])
        assertEquals("한글A", s.emulator.screen.getSelectedText(0, 0, 4, 0).trim())
    }

    @Test
    fun outputBeforeFirstSizeIsBufferedThenApplied() {
        val t = FakeTransport()
        val s = RemoteTerminalSession(null, null, t)
        val b = "early".toByteArray()
        s.appendRemoteOutput(b, 0, b.size)
        s.updateSize(20, 5, 10, 20)
        assertEquals("early", s.emulator.screen.getSelectedText(0, 0, 19, 0).trim())
        assertEquals(listOf(20 to 5), t.resizes)
    }

    @Test
    fun resizeIsReportedToTransport() {
        val (s, t) = newSession(80, 24)
        s.updateSize(100, 30, 10, 20)
        assertEquals(listOf(80 to 24, 100 to 30), t.resizes)
        assertEquals(100, s.emulator.mColumns)
    }

    @Test
    fun resetForReplayClearsScreenScrollbackAndAltBuffer() {
        val (s, _) = newSession(20, 5)
        s.feed((1..30).joinToString("\r\n") { "line$it" })
        assertTrue(s.emulator.screen.activeTranscriptRows > 0)
        // Enter the alternate screen, leave an unfinished escape sequence (as if the socket dropped mid-frame).
        s.feed("\u001b[?1049h\u001b[1mALT\u001b[38;2;1")
        assertTrue(s.emulator.isAlternateBufferActive)

        s.resetForReplay()
        assertFalse(s.emulator.isAlternateBufferActive)
        assertEquals(0, s.emulator.screen.activeTranscriptRows)
        assertEquals(0, s.emulator.cursorCol)
        assertEquals(0, s.emulator.cursorRow)
        assertEquals("", s.emulator.screen.getSelectedText(0, 0, 19, 4).trim())

        // Replay after reset renders from a clean state (no leftover parser state or attributes).
        s.feed("replay")
        assertEquals("replay", s.emulator.screen.getSelectedText(0, 0, 19, 0).trim())
        assertEquals(TextStyle.COLOR_INDEX_FOREGROUND, TextStyle.decodeForeColor(s.emulator.screen.getStyleAt(0, 0)))
    }

    @Test
    fun imeCommitOfHangulSendsUtf8Bytes() {
        val (s, t) = newSession()
        // Same path TerminalView's InputConnection.commitText uses: decode → code points → session (UTF-8).
        ImeTextDecoder.decode("한글", false) { cp, ctrl -> s.writeCodePoint(ctrl, cp) }
        assertArrayEquals(
            byteArrayOf(0xED.toByte(), 0x95.toByte(), 0x9C.toByte(), 0xEA.toByte(), 0xB8.toByte(), 0x80.toByte()),
            t.sent.toByteArray(),
        )
    }

    @Test
    fun imeDecoderMapsNewlineToEnterAndJoinsSurrogates() {
        val got = ArrayList<Pair<Int, Boolean>>()
        ImeTextDecoder.decode("a\n😀", false) { cp, ctrl -> got.add(cp to ctrl) }
        // '\n' becomes Ctrl+M (= CR after Ctrl mapping in TerminalView), emoji is one code point.
        assertEquals(listOf('a'.code to false, 'm'.code to true, 0x1F600 to false), got)
    }

    @Test
    fun composingTextIsMirroredWithBackspaces() {
        // Hangul composition ㅁ → 모 → 몸 → 모 (jamo removed) → 목
        val steps = listOf("" to "ㅁ", "ㅁ" to "모", "모" to "몸", "몸" to "모", "모" to "목")
        val edits = steps.map { (a, b) -> ImeTextDecoder.backspaces(a, b) to ImeTextDecoder.append(a, b) }
        assertEquals(listOf(0 to "ㅁ", 1 to "모", 1 to "몸", 1 to "모", 1 to "목"), edits)
        // Word-level composition (Samsung predictive): only the changed tail is retyped.
        assertEquals(0 to "lo", ImeTextDecoder.backspaces("hel", "hello") to ImeTextDecoder.append("hel", "hello"))
        assertEquals(2 to "p", ImeTextDecoder.backspaces("hello", "help") to ImeTextDecoder.append("hello", "help"))
        // A surrogate pair counts as one deletion and is never split.
        assertEquals(1 to "", ImeTextDecoder.backspaces("a😀", "a") to ImeTextDecoder.append("a😀", "a"))
    }

    @Test
    fun writeCodePointWithAltPrefixesEscape() {
        val (s, t) = newSession()
        s.writeCodePoint(true, 'b'.code)
        assertArrayEquals(byteArrayOf(0x1b, 'b'.code.toByte()), t.sent.toByteArray())
    }

    @Test
    fun exitMarksSessionFinishedAndPrintsNote() {
        val (s, _) = newSession(40, 5)
        assertTrue(s.isRunning)
        s.markExited(2, "[세션 종료]")
        assertFalse(s.isRunning)
        assertEquals(2, s.exitStatus)
        assertEquals("[세션 종료]", s.emulator.screen.getSelectedText(0, 0, 39, 0).trim())
    }
}
