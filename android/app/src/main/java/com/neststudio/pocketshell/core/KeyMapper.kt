package com.neststudio.pocketshell.core

/** Keys on the special key bar that produce terminal input (CTRL/ALT are toggles, not keys). */
enum class SpecialKey(val label: String) {
    ESC("ESC"),
    TAB("TAB"),
    ENTER("↵"), // return symbol: narrower than "ENTER" (TalkBack says "Enter", see TerminalActivity.spokenName)
    LEFT("←"),
    UP("↑"),
    DOWN("↓"),
    RIGHT("→"),
    HOME("HOME"),
    END("END"),
    PGUP("PGUP"),
    PGDN("PGDN"),
    PIPE("|"),
    TILDE("~"),
    SLASH("/"),
    MINUS("-"),
}

/**
 * Maps special keys and modified characters to the bytes a VT/xterm terminal expects.
 * Pure logic, no Android dependencies.
 */
object KeyMapper {
    private const val ESC = "\u001b"

    /**
     * @param ctrl CTRL toggle active
     * @param alt ALT toggle active (prefixes ESC, "meta sends escape")
     * @param applicationCursor DECCKM (application cursor keys) is set in the emulator
     */
    fun bytesFor(key: SpecialKey, ctrl: Boolean, alt: Boolean, applicationCursor: Boolean): ByteArray {
        val seq: String = when (key) {
            SpecialKey.ESC -> withAlt(ESC, alt)
            SpecialKey.TAB -> withAlt("\t", alt) // CTRL+TAB has no standard VT encoding
            SpecialKey.ENTER -> withAlt("\r", alt) // what a terminal's Enter key sends
            SpecialKey.UP -> cursorKey('A', ctrl, alt, applicationCursor)
            SpecialKey.DOWN -> cursorKey('B', ctrl, alt, applicationCursor)
            SpecialKey.RIGHT -> cursorKey('C', ctrl, alt, applicationCursor)
            SpecialKey.LEFT -> cursorKey('D', ctrl, alt, applicationCursor)
            SpecialKey.HOME -> cursorKey('H', ctrl, alt, applicationCursor)
            SpecialKey.END -> cursorKey('F', ctrl, alt, applicationCursor)
            SpecialKey.PGUP -> tildeKey(5, ctrl, alt)
            SpecialKey.PGDN -> tildeKey(6, ctrl, alt)
            SpecialKey.PIPE -> return encodeCodePoint('|'.code, ctrl, alt)
            SpecialKey.TILDE -> return encodeCodePoint('~'.code, ctrl, alt)
            SpecialKey.SLASH -> return encodeCodePoint('/'.code, ctrl, alt)
            SpecialKey.MINUS -> return encodeCodePoint('-'.code, ctrl, alt)
        }
        return seq.toByteArray(Charsets.UTF_8)
    }

    /**
     * Encodes a typed character with optional CTRL/ALT: CTRL maps letters to C0 controls
     * (CTRL+C = 0x03), ALT prefixes ESC. Other characters are sent as UTF-8.
     */
    fun encodeCodePoint(codePoint: Int, ctrl: Boolean, alt: Boolean): ByteArray {
        val cp = if (ctrl) controlCodeFor(codePoint) ?: codePoint else codePoint
        val body = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8)
        return if (alt) byteArrayOf(0x1b) + body else body
    }

    /** C0 control code for CTRL+[codePoint], or null if CTRL has no meaning for it. */
    fun controlCodeFor(codePoint: Int): Int? = when (codePoint) {
        in 'a'.code..'z'.code -> codePoint - 'a'.code + 1
        in 'A'.code..'Z'.code -> codePoint - 'A'.code + 1
        ' '.code, '@'.code, '2'.code -> 0
        '['.code, '3'.code -> 27
        '\\'.code, '|'.code, '4'.code -> 28
        ']'.code, '5'.code -> 29
        '^'.code, '~'.code, '6'.code -> 30
        '_'.code, '/'.code, '-'.code, '7'.code -> 31
        '?'.code, '8'.code -> 127
        else -> null
    }

    /** xterm modifier parameter: 1 + (shift 1) + (alt 2) + (ctrl 4). */
    private fun modifierParam(ctrl: Boolean, alt: Boolean): Int =
        1 + (if (alt) 2 else 0) + (if (ctrl) 4 else 0)

    private fun cursorKey(final: Char, ctrl: Boolean, alt: Boolean, applicationCursor: Boolean): String {
        val mod = modifierParam(ctrl, alt)
        return when {
            mod != 1 -> "$ESC[1;$mod$final"
            applicationCursor -> "${ESC}O$final"
            else -> "$ESC[$final"
        }
    }

    private fun tildeKey(code: Int, ctrl: Boolean, alt: Boolean): String {
        val mod = modifierParam(ctrl, alt)
        return if (mod == 1) "$ESC[$code~" else "$ESC[$code;$mod~"
    }

    private fun withAlt(seq: String, alt: Boolean) = if (alt) ESC + seq else seq
}
