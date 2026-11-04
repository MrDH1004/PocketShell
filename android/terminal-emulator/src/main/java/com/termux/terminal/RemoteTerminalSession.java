package com.termux.terminal;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * A terminal session whose "process" lives on a remote host (mpta).
 * <p>
 * Replaces Termux's JNI/pty based {@code TerminalSession}:
 * <ul>
 *     <li>Keyboard/IME input written by {@code TerminalView} goes to {@link Transport#sendInput(byte[])}
 *     (the app sends it as a WebSocket binary frame).</li>
 *     <li>Bytes received from the host are fed with {@link #appendRemoteOutput(byte[], int, int)} into
 *     {@link TerminalEmulator#append(byte[], int)} and the client is notified to redraw.</li>
 *     <li>View size changes are reported through {@link Transport#onResize(int, int)}.</li>
 * </ul>
 * All methods must be called on the main (UI) thread, like the original TerminalSession.
 */
public final class RemoteTerminalSession extends TerminalOutput {

    /** Callbacks to the network layer. */
    public interface Transport {
        /** Input bytes (UTF-8 text or VT key sequences) to write to the remote PTY. */
        void sendInput(byte[] data);

        /** The local view now has this many columns/rows. */
        void onResize(int columns, int rows);
    }

    TerminalEmulator mEmulator;
    TerminalSessionClient mClient;
    private final Transport mTransport;
    private final Integer mTranscriptRows;

    /** Output received before the view reported a size (emulator not yet created). */
    private final ByteArrayOutputStream mPendingOutput = new ByteArrayOutputStream();

    /** Buffer to translate code points into UTF-8. */
    private final byte[] mUtf8InputBuffer = new byte[5];

    /** Set by the application for user identification of session. */
    public String mSessionName;

    private boolean mFinished;
    private int mExitStatus;

    public RemoteTerminalSession(Integer transcriptRows, TerminalSessionClient client, Transport transport) {
        mTranscriptRows = transcriptRows;
        mClient = client;
        mTransport = transport;
    }

    public void updateTerminalSessionClient(TerminalSessionClient client) {
        mClient = client;
        if (mEmulator != null) mEmulator.updateTerminalSessionClient(client);
    }

    /** Called by TerminalView when its size in cells changes. Creates the emulator on first call. */
    public void updateSize(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        if (mEmulator == null) {
            mEmulator = new TerminalEmulator(this, columns, rows, cellWidthPixels, cellHeightPixels, mTranscriptRows, mClient);
            if (mPendingOutput.size() > 0) {
                byte[] pending = mPendingOutput.toByteArray();
                mPendingOutput.reset();
                mEmulator.append(pending, pending.length);
            }
        } else {
            mEmulator.resize(columns, rows, cellWidthPixels, cellHeightPixels);
        }
        mTransport.onResize(columns, rows);
        notifyScreenUpdate();
    }

    /** Feed bytes received from the remote PTY into the emulator. */
    public void appendRemoteOutput(byte[] data, int offset, int length) {
        if (length <= 0) return;
        if (mEmulator == null) {
            mPendingOutput.write(data, offset, length);
            return;
        }
        if (offset == 0) {
            mEmulator.append(data, length);
        } else {
            byte[] copy = new byte[length];
            System.arraycopy(data, offset, copy, 0, length);
            mEmulator.append(copy, length);
        }
        notifyScreenUpdate();
    }

    /**
     * Bring the emulator back to a blank initial state so that a full server-side replay can be
     * applied without duplicating content (used on every (re)connect).
     * Resets parser state first (so an escape sequence cut off by a disconnect cannot swallow the
     * following bytes), leaves the alternate screen, then applies RIS which clears screen and scrollback.
     */
    public void resetForReplay() {
        mPendingOutput.reset();
        mFinished = false;
        if (mEmulator == null) return;
        mEmulator.reset();
        byte[] seq = "\033[?1049l\033c".getBytes(StandardCharsets.US_ASCII);
        mEmulator.append(seq, seq.length);
        mEmulator.setTopRow(0);
        notifyScreenUpdate();
    }

    /** Mark the remote shell as exited (from a {"type":"exit"} message) and print a note. */
    public void markExited(int exitCode, String message) {
        mFinished = true;
        mExitStatus = exitCode;
        if (message != null) {
            byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
            appendRemoteOutput(bytes, 0, bytes.length);
        }
        if (mClient != null) mClient.onSessionFinished(this);
    }

    /** Write data to the remote shell. */
    @Override
    public void write(byte[] data, int offset, int count) {
        if (count <= 0) return;
        byte[] copy = new byte[count];
        System.arraycopy(data, offset, copy, 0, count);
        mTransport.sendInput(copy);
    }

    /** Write the Unicode code point to the terminal encoded in UTF-8. */
    public void writeCodePoint(boolean prependEscape, int codePoint) {
        if (codePoint > 1114111 || (codePoint >= 0xD800 && codePoint <= 0xDFFF)) {
            // 1114111 (= 2**16 + 1024**2 - 1) is the highest code point, [0xD800,0xDFFF] is the surrogate range.
            throw new IllegalArgumentException("Invalid code point: " + codePoint);
        }

        int bufferPosition = 0;
        if (prependEscape) mUtf8InputBuffer[bufferPosition++] = 27;

        if (codePoint <= /* 7 bits */0b1111111) {
            mUtf8InputBuffer[bufferPosition++] = (byte) codePoint;
        } else if (codePoint <= /* 11 bits */0b11111111111) {
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11000000 | (codePoint >> 6));
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        } else if (codePoint <= /* 16 bits */0b1111111111111111) {
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11100000 | (codePoint >> 12));
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 6) & 0b111111));
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        } else {
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11110000 | (codePoint >> 18));
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 12) & 0b111111));
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 6) & 0b111111));
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        }
        write(mUtf8InputBuffer, 0, bufferPosition);
    }

    public TerminalEmulator getEmulator() {
        return mEmulator;
    }

    /** The terminal title as set through escape sequences or null if none set. */
    public String getTitle() {
        return (mEmulator == null) ? null : mEmulator.getTitle();
    }

    protected void notifyScreenUpdate() {
        if (mClient != null) mClient.onTextChanged(this);
    }

    public boolean isRunning() {
        return !mFinished;
    }

    public int getExitStatus() {
        return mExitStatus;
    }

    @Override
    public void titleChanged(String oldTitle, String newTitle) {
        if (mClient != null) mClient.onTitleChanged(this);
    }

    @Override
    public void onCopyTextToClipboard(String text) {
        if (mClient != null) mClient.onCopyTextToClipboard(this, text);
    }

    @Override
    public void onPasteTextFromClipboard() {
        if (mClient != null) mClient.onPasteTextFromClipboard(this);
    }

    @Override
    public void onBell() {
        if (mClient != null) mClient.onBell(this);
    }

    @Override
    public void onColorsChanged() {
        if (mClient != null) mClient.onColorsChanged(this);
    }
}
