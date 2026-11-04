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
