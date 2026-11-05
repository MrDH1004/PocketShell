package com.mpta.client.core

/**
 * Keeps the output bytes since the start of the latest external-console frame. The host's frames are
 * self-contained full screens beginning with [START] (DESIGN §6), so replaying [snapshot] redraws the whole
 * screen after a local font/grid change. Frames may arrive split across WebSocket messages.
 */
class FrameTail(private val maxBytes: Int = 4 shl 20) {
    private var buf = ByteArray(0)

    fun append(data: ByteArray) {
        val joined = buf + data
        val start = lastIndexOf(joined, START)
        buf = when {
            start >= 0 -> joined.copyOfRange(start, joined.size)
            joined.size > maxBytes -> ByteArray(0) // not frame-structured output: drop rather than grow
            else -> joined
        }
    }

    /** Bytes of the latest frame (possibly still incomplete); empty if no frame has started yet. */
    fun snapshot(): ByteArray = if (startsWith(buf, START)) buf.copyOf() else ByteArray(0)

    fun clear() {
        buf = ByteArray(0)
    }

    companion object {
        /** ESC[?25l ESC[H — first bytes of every frame from VtFrame.Build on the host. */
        val START = "\u001b[?25l\u001b[H".toByteArray(Charsets.US_ASCII)

        private fun startsWith(a: ByteArray, p: ByteArray) =
            a.size >= p.size && (p.indices).all { a[it] == p[it] }

        private fun lastIndexOf(a: ByteArray, p: ByteArray): Int {
            var i = a.size - p.size
            while (i >= 0) {
                var j = 0
                while (j < p.size && a[i + j] == p[j]) j++
                if (j == p.size) return i
                i--
            }
            return -1
        }
    }
}
