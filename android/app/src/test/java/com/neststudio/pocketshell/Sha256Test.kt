package com.neststudio.pocketshell

import com.neststudio.pocketshell.core.IntegrityException
import com.neststudio.pocketshell.core.Sha256
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class Sha256Test {
    // FIPS 180-2 test vector: SHA-256("abc")
    private val abc = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

    @Test
    fun knownVector() {
        assertEquals(abc, Sha256.hash(ByteArrayInputStream("abc".toByteArray())))
    }

    @Test
    fun streamingCopyMatchesAndReportsProgress() {
        val data = ByteArray(300_000) { (it * 31).toByte() }
        val out = ByteArrayOutputStream()
        var last = 0L
        val expected = java.security.MessageDigest.getInstance("SHA-256").digest(data).let(Sha256::toHex)
        val got = Sha256.copyVerified(ByteArrayInputStream(data), out, expected.uppercase()) { last = it }
        assertEquals(expected, got)
        assertArrayEquals(data, out.toByteArray())
        assertEquals(data.size.toLong(), last)
    }

    @Test
    fun digestMismatchIsRejected() {
        val wrong = abc.replaceRange(0, 1, "c")
        val e = assertThrows(IntegrityException::class.java) {
            Sha256.copyVerified(ByteArrayInputStream("abc".toByteArray()), ByteArrayOutputStream(), wrong)
        }
        assertTrue(e.message!!.contains(abc))
    }

    @Test
    fun matchesRequiresFullLengthHex() {
        assertTrue(Sha256.matches(abc.uppercase(), abc))
        assertFalse(Sha256.matches("", ""))
        assertFalse(Sha256.matches(abc.dropLast(2), abc.dropLast(2)))
    }
}
