package com.mpta.client

import com.mpta.client.core.KeyMapper
import com.mpta.client.core.SpecialKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeyMapperTest {

    private fun bytes(s: String) = s.toByteArray(Charsets.UTF_8)
    private fun key(k: SpecialKey, ctrl: Boolean = false, alt: Boolean = false, app: Boolean = false) =
        KeyMapper.bytesFor(k, ctrl, alt, app)

    @Test
    fun escTabAndPlainCharacters() {
        assertArrayEquals(byteArrayOf(0x1b), key(SpecialKey.ESC))
        assertArrayEquals(byteArrayOf(0x09), key(SpecialKey.TAB))
        assertArrayEquals(bytes("|"), key(SpecialKey.PIPE))
        assertArrayEquals(bytes("~"), key(SpecialKey.TILDE))
        assertArrayEquals(bytes("/"), key(SpecialKey.SLASH))
        assertArrayEquals(bytes("-"), key(SpecialKey.MINUS))
    }

    @Test
    fun arrowsNormalCursorMode() {
        assertArrayEquals(bytes("\u001b[A"), key(SpecialKey.UP))
        assertArrayEquals(bytes("\u001b[B"), key(SpecialKey.DOWN))
        assertArrayEquals(bytes("\u001b[C"), key(SpecialKey.RIGHT))
        assertArrayEquals(bytes("\u001b[D"), key(SpecialKey.LEFT))
        assertArrayEquals(bytes("\u001b[H"), key(SpecialKey.HOME))
        assertArrayEquals(bytes("\u001b[F"), key(SpecialKey.END))
    }

    @Test
    fun arrowsApplicationCursorMode() {
        assertArrayEquals(bytes("\u001bOA"), key(SpecialKey.UP, app = true))
        assertArrayEquals(bytes("\u001bOB"), key(SpecialKey.DOWN, app = true))
        assertArrayEquals(bytes("\u001bOC"), key(SpecialKey.RIGHT, app = true))
        assertArrayEquals(bytes("\u001bOD"), key(SpecialKey.LEFT, app = true))
        assertArrayEquals(bytes("\u001bOH"), key(SpecialKey.HOME, app = true))
        assertArrayEquals(bytes("\u001bOF"), key(SpecialKey.END, app = true))
    }

    @Test
    fun pageKeys() {
        assertArrayEquals(bytes("\u001b[5~"), key(SpecialKey.PGUP))
        assertArrayEquals(bytes("\u001b[6~"), key(SpecialKey.PGDN))
        assertArrayEquals(bytes("\u001b[5;5~"), key(SpecialKey.PGUP, ctrl = true))
    }

    @Test
    fun modifiedArrowsUseXtermParameters() {
        assertArrayEquals(bytes("\u001b[1;5C"), key(SpecialKey.RIGHT, ctrl = true))
        assertArrayEquals(bytes("\u001b[1;3D"), key(SpecialKey.LEFT, alt = true))
        assertArrayEquals(bytes("\u001b[1;7A"), key(SpecialKey.UP, ctrl = true, alt = true, app = true))
    }

    @Test
    fun ctrlLetters() {
        assertArrayEquals(byteArrayOf(0x03), KeyMapper.encodeCodePoint('c'.code, ctrl = true, alt = false))
        assertArrayEquals(byteArrayOf(0x03), KeyMapper.encodeCodePoint('C'.code, ctrl = true, alt = false))
        assertArrayEquals(byteArrayOf(0x01), KeyMapper.encodeCodePoint('a'.code, ctrl = true, alt = false))
        assertArrayEquals(byteArrayOf(0x1a), KeyMapper.encodeCodePoint('z'.code, ctrl = true, alt = false))
        assertArrayEquals(byteArrayOf(0x04), KeyMapper.encodeCodePoint('d'.code, ctrl = true, alt = false))
        assertArrayEquals(byteArrayOf(0x00), KeyMapper.encodeCodePoint(' '.code, ctrl = true, alt = false))
        assertArrayEquals(byteArrayOf(0x1f), key(SpecialKey.SLASH, ctrl = true))
        assertArrayEquals(byteArrayOf(0x1c), key(SpecialKey.PIPE, ctrl = true))
        assertEquals(27, KeyMapper.controlCodeFor('['.code))
        assertNull(KeyMapper.controlCodeFor('한'.code))
    }

    @Test
    fun altPrefixesEscape() {
        assertArrayEquals(bytes("\u001bb"), KeyMapper.encodeCodePoint('b'.code, ctrl = false, alt = true))
        assertArrayEquals(byteArrayOf(0x1b, 0x03), KeyMapper.encodeCodePoint('c'.code, ctrl = true, alt = true))
        assertArrayEquals(bytes("\u001b\u001b"), key(SpecialKey.ESC, alt = true))
        assertArrayEquals(bytes("\u001b\t"), key(SpecialKey.TAB, alt = true))
        assertArrayEquals(bytes("\u001b-"), key(SpecialKey.MINUS, alt = true))
    }

    @Test
    fun nonAsciiIsUtf8() {
        assertArrayEquals(
            byteArrayOf(0xED.toByte(), 0x95.toByte(), 0x9C.toByte()),
            KeyMapper.encodeCodePoint('한'.code, ctrl = true, alt = false),
        )
    }
}
