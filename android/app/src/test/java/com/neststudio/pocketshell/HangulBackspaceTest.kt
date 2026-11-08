package com.neststudio.pocketshell

import com.termux.view.ImeTextDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HangulBackspaceTest {

    @Test
    fun keystrokesSplitSyllablesAndCompounds() {
        assertEquals("ㅎㅏㄴ", ImeTextDecoder.keystrokes("한"))
        assertEquals("ㄷㅏㄹㄱ", ImeTextDecoder.keystrokes("닭"))
        assertEquals("ㄱㅗㅐㄴ", ImeTextDecoder.keystrokes("괜"))
        assertEquals("ㄲㅏㄲ", ImeTextDecoder.keystrokes("깎"))
        assertEquals("ㅎㅣㅎ", ImeTextDecoder.keystrokes("힣"))
        assertEquals("ㄱㅏ", ImeTextDecoder.keystrokes("가"))
        assertEquals("aㅎ", ImeTextDecoder.keystrokes("aㅎ"))
    }

    @Test
    fun imeBackspaceInsideSyllableIsDetected() {
        assertTrue(ImeTextDecoder.isHangulBackspace("한", "하"))
        assertTrue(ImeTextDecoder.isHangulBackspace("하", "ㅎ"))
        assertTrue(ImeTextDecoder.isHangulBackspace("ㅎ", ""))
        assertTrue(ImeTextDecoder.isHangulBackspace("닭", "달"))
        assertTrue(ImeTextDecoder.isHangulBackspace("와", "오"))
        assertTrue(ImeTextDecoder.isHangulBackspace("안녕", "안녀"))
    }

    @Test
    fun typingIsNotBackspace() {
        assertFalse(ImeTextDecoder.isHangulBackspace("하", "한"))
        assertFalse(ImeTextDecoder.isHangulBackspace("한", "하나"))
        assertFalse(ImeTextDecoder.isHangulBackspace("ㅎ", "하"))
        assertFalse(ImeTextDecoder.isHangulBackspace("각", "갘")) // 천지인 multi-tap
        assertFalse(ImeTextDecoder.isHangulBackspace("갂", "각"))
        assertFalse(ImeTextDecoder.isHangulBackspace("", "ㅎ"))
        assertFalse(ImeTextDecoder.isHangulBackspace("ab", "a")) // not Hangul: left to the IME
    }

    @Test
    fun dropLastCodePoint() {
        assertEquals("안", ImeTextDecoder.dropLastCodePoint("안녕"))
        assertEquals("", ImeTextDecoder.dropLastCodePoint("한"))
        assertEquals("a", ImeTextDecoder.dropLastCodePoint("a😀"))
        assertEquals("", ImeTextDecoder.dropLastCodePoint(""))
    }
}
