package com.termux.view;

import com.termux.terminal.TerminalEmulator;

/**
 * Converts text committed by an input method into terminal code points.
 * <p>
 * Extracted from Termux's {@code TerminalView.onCreateInputConnection().sendTextToTerminal()} so the
 * logic can be unit tested on the JVM. Behavior is unchanged: surrogate pairs are joined, '\n' becomes
 * '\r' (Enter), other C0 control characters are turned into Ctrl+letter.
 */
public final class ImeTextDecoder {

    /** Receives each decoded code point. */
    public interface Sink {
        void onCodePoint(int codePoint, boolean ctrlHeld);
    }

    private ImeTextDecoder() {
    }

    /**
     * pkshell: edit that turns the composing text already shown on the remote terminal ({@code sent}) into
     * {@code target}: delete {@link #backspaces} code points, then type {@link #append}. Composition is mirrored
     * live (e.g. ㅁ → 모 → 몸) instead of appearing only when the IME commits.
     */
    public static int backspaces(String sent, String target) {
        int p = commonPrefix(sent, target);
        return sent.codePointCount(p, sent.length());
    }

    public static String append(String sent, String target) {
        return target.substring(commonPrefix(sent, target));
    }

    private static final String CHO = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ";
    private static final String JUNG = "ㅏㅐㅑㅒㅓㅔㅕㅖㅗㅘㅙㅚㅛㅜㅝㅞㅟㅠㅡㅢㅣ";
    private static final String JONG = " ㄱㄲㄳㄴㄵㄶㄷㄹㄺㄻㄼㄽㄾㄿㅀㅁㅂㅄㅅㅆㅇㅈㅊㅋㅌㅍㅎ";
    /** Compound vowels / final clusters as the keys typed (double consonants like ㄲ are one key). */
    private static final String COMPOUND = "ㅘㅗㅏㅙㅗㅐㅚㅗㅣㅝㅜㅓㅞㅜㅔㅟㅜㅣㅢㅡㅣ"
        + "ㄳㄱㅅㄵㄴㅈㄶㄴㅎㄺㄹㄱㄻㄹㅁㄼㄹㅂㄽㄹㅅㄾㄹㅌㄿㄹㅍㅀㄹㅎㅄㅂㅅ";

    /** Hangul text as the jamo keystrokes that build it ("닭" → ㄷㅏㄹㄱ); other characters stay as they are. */
    public static String keystrokes(String text) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0xAC00 && c <= 0xD7A3) {
                int s = c - 0xAC00;
                sb.append(CHO.charAt(s / 588));
                appendJamo(sb, JUNG.charAt((s % 588) / 28));
                if (s % 28 != 0) appendJamo(sb, JONG.charAt(s % 28));
            } else {
                appendJamo(sb, c);
            }
        }
        return sb.toString();
    }

    private static void appendJamo(StringBuilder sb, char j) {
        for (int k = 0; k < COMPOUND.length(); k += 3) {
            if (COMPOUND.charAt(k) == j) {
                sb.append(COMPOUND.charAt(k + 1)).append(COMPOUND.charAt(k + 2));
                return;
            }
        }
        sb.append(j);
    }

    /**
     * pkshell: true when the IME changed the Hangul composing text {@code from} → {@code to} only by removing
     * jamo (its backspace inside a syllable: 한 → 하 → ㅎ). Typing only ever adds keystrokes, so it never matches.
     */
    public static boolean isHangulBackspace(String from, String to) {
        if (from.isEmpty() || to.length() > from.length()) return false;
        boolean hangul = false;
        for (int i = 0; i < from.length() && !hangul; i++) {
            char c = from.charAt(i);
            hangul = (c >= 0xAC00 && c <= 0xD7A3) || (c >= 0x3131 && c <= 0x318E);
        }
        if (!hangul) return false;
        String a = keystrokes(from);
        String b = keystrokes(to);
        return b.length() < a.length() && a.startsWith(b);
    }

    /** {@code text} without its last code point. */
    public static String dropLastCodePoint(String text) {
        if (text.isEmpty()) return text;
        return text.substring(0, text.offsetByCodePoints(text.length(), -1));
    }

    private static int commonPrefix(String a, String b) {
        int n = Math.min(a.length(), b.length());
        int i = 0;
        while (i < n && a.charAt(i) == b.charAt(i)) i++;
        // Never split a surrogate pair.
        if (i > 0 && Character.isHighSurrogate(a.charAt(i - 1))) i--;
        return i;
    }

    public static void decode(CharSequence text, boolean shiftHeld, Sink sink) {
        final int textLengthInChars = text.length();
        for (int i = 0; i < textLengthInChars; i++) {
            char firstChar = text.charAt(i);
            int codePoint;
            if (Character.isHighSurrogate(firstChar)) {
                if (++i < textLengthInChars) {
                    codePoint = Character.toCodePoint(firstChar, text.charAt(i));
                } else {
                    // At end of string, with no low surrogate following the high:
                    codePoint = TerminalEmulator.UNICODE_REPLACEMENT_CHAR;
                }
            } else {
                codePoint = firstChar;
            }

            if (shiftHeld)
                codePoint = Character.toUpperCase(codePoint);

            boolean ctrlHeld = false;
            if (codePoint <= 31 && codePoint != 27) {
                if (codePoint == '\n') {
                    // The AOSP keyboard and descendants seems to send \n as text when the enter key is pressed,
                    // instead of a key event like most other keyboard apps. A terminal expects \r for the enter key.
                    codePoint = '\r';
                }

                // E.g. penti keyboard for ctrl input.
                ctrlHeld = true;
                switch (codePoint) {
                    case 31:
                        codePoint = '_';
                        break;
                    case 30:
                        codePoint = '^';
                        break;
                    case 29:
                        codePoint = ']';
                        break;
                    case 28:
                        codePoint = '\\';
                        break;
                    default:
                        codePoint += 96;
                        break;
                }
            }

            sink.onCodePoint(codePoint, ctrlHeld);
        }
    }
}
