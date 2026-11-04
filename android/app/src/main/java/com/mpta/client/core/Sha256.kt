package com.mpta.client.core

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** Downloaded file does not match the published SHA-256 (DESIGN.md §11.2). */
class IntegrityException(message: String) : IOException(message)

/** Streaming SHA-256 so large APKs are hashed while they are written, never held in memory. */
object Sha256 {

    /** Copies [input] to [output] while hashing; returns the lowercase hex digest. */
    fun copyAndHash(input: InputStream, output: OutputStream, onProgress: (Long) -> Unit = {}): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
            output.write(buf, 0, n)
            total += n
            onProgress(total)
        }
        output.flush()
        return toHex(md.digest())
    }

    // OutputStream.nullOutputStream() needs API 33; minSdk is 26.
    fun hash(input: InputStream): String = copyAndHash(input, object : OutputStream() {
        override fun write(b: Int) = Unit
        override fun write(b: ByteArray, off: Int, len: Int) = Unit
    })

    /**
     * Copies and verifies against [expectedHex]; throws [IntegrityException] on mismatch (the caller must
     * then discard whatever was written to [output]). Returns the verified digest.
     */
    fun copyVerified(
        input: InputStream,
        output: OutputStream,
        expectedHex: String,
        onProgress: (Long) -> Unit = {},
    ): String {
        val actual = copyAndHash(input, output, onProgress)
        if (!matches(expectedHex, actual)) {
            throw IntegrityException("SHA-256 불일치: 기대 ${expectedHex.trim().lowercase()}, 실제 $actual")
        }
        return actual
    }

    /** Constant-time, case-insensitive hex comparison. */
    fun matches(expectedHex: String, actualHex: String): Boolean {
        val e = expectedHex.trim().lowercase()
        val a = actualHex.trim().lowercase()
        if (e.length != 64) return false
        return MessageDigest.isEqual(e.toByteArray(Charsets.US_ASCII), a.toByteArray(Charsets.US_ASCII))
    }

    fun toHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xff
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0f])
        }
        return sb.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
