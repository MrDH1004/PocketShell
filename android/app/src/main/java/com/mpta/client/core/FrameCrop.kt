package com.mpta.client.core

/**
 * Shows only [viewRows] rows of an external-console frame (VtFrame.Build on the host:
 * `ESC[?25l ESC[H`, per row `ESC[y;1H` + text/SGR + `ESC[0m ESC[K`, then optional cursor `ESC[r;cH ESC[?25h`).
 * Without this, row positions past the phone's last row clamp onto it and overwrite each other.
 *
 * The window ends at the lower of the cursor row and the last non-blank row, so the input box and the status
 * lines under it stay visible (the keyboard-up case).
 */
object FrameCrop {
    private const val START = "\u001b[?25l\u001b[H"
    private val ROW = Regex("\u001b\\[(\\d+);1H(.*?)\u001b\\[0m\u001b\\[K")
    private val CURSOR = Regex("\u001b\\[(\\d+);(\\d+)H\u001b\\[\\?25h$")

    /**
     * Phone has more rows than the console: after the frame, erase everything below row [consoleRows] (left over from
     * before a zoom/resize), keeping the cursor where the frame put it (DECSC/DECRC).
     */
    fun clearBelow(frame: ByteArray, consoleRows: Int): ByteArray {
        if (consoleRows <= 0 || !String(frame, 0, minOf(frame.size, START.length), Charsets.UTF_8).startsWith(START)) return frame
        val tail = "\u001b7\u001b[${consoleRows + 1};1H\u001b[J\u001b8".toByteArray(Charsets.US_ASCII)
        return frame + tail
    }

    fun crop(frame: ByteArray, viewRows: Int): ByteArray {
        if (viewRows <= 0) return frame
        val text = String(frame, Charsets.UTF_8)
        if (!text.startsWith(START)) return frame
        val rows = ROW.findAll(text).map { it.groupValues[1].toInt() to it.groupValues[2] }.toList()
        if (rows.size <= viewRows) return frame
        val cursor = CURSOR.find(text)?.let { it.groupValues[1].toInt() to it.groupValues[2].toInt() }

        val lastContent = rows.lastOrNull { it.second.isNotEmpty() }?.first ?: 1
        val bottom = maxOf(lastContent, cursor?.first ?: 1)
        val maxRow = rows.maxOf { it.first }
        val top = (bottom - viewRows + 1).coerceIn(1, maxRow - viewRows + 1)

        val sb = StringBuilder(text.length).append(START)
        for ((y, body) in rows) {
            if (y < top || y >= top + viewRows) continue
            sb.append("\u001b[").append(y - top + 1).append(";1H").append(body).append("\u001b[0m\u001b[K")
        }
        if (cursor != null && cursor.first in top until top + viewRows) {
            sb.append("\u001b[").append(cursor.first - top + 1).append(';').append(cursor.second).append("H\u001b[?25h")
        }
        return sb.toString().toByteArray(Charsets.UTF_8)
    }
}
