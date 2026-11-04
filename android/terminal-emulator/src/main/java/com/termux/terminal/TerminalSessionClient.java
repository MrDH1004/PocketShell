package com.termux.terminal;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * The interface for communication between {@link RemoteTerminalSession} and its client. It is used to
 * send callbacks to the client when {@link RemoteTerminalSession} changes or for sending other
 * back data to the client like logs.
 */
public interface TerminalSessionClient {

    void onTextChanged(@NonNull RemoteTerminalSession changedSession);

    void onTitleChanged(@NonNull RemoteTerminalSession changedSession);

    void onSessionFinished(@NonNull RemoteTerminalSession finishedSession);

    void onCopyTextToClipboard(@NonNull RemoteTerminalSession session, String text);

    void onPasteTextFromClipboard(@Nullable RemoteTerminalSession session);

    void onBell(@NonNull RemoteTerminalSession session);

    void onColorsChanged(@NonNull RemoteTerminalSession session);

    void onTerminalCursorStateChange(boolean state);




    Integer getTerminalCursorStyle();



    void logError(String tag, String message);

    void logWarn(String tag, String message);

    void logInfo(String tag, String message);

    void logDebug(String tag, String message);

    void logVerbose(String tag, String message);

    void logStackTraceWithMessage(String tag, String message, Exception e);

    void logStackTrace(String tag, Exception e);

}
