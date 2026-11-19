package com.neststudio.pocketshell.data

import android.content.Context
import com.neststudio.pocketshell.core.QuietHours
import com.neststudio.pocketshell.core.SessionSections
import com.neststudio.pocketshell.core.Snippets

/** Small UI options (app-private SharedPreferences). */
class UiPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Entries shown under 최근 in the session list; 0 hides the section, [SessionSections.RECENT_ALL] shows all.
     * A new key since 0.2.97: 최근 then started showing everything, whatever count was chosen before.
     */
    var recentLimit: Int
        get() = prefs.getInt(K_RECENT_LIMIT, SessionSections.DEFAULT_RECENT_LIMIT).coerceAtLeast(0)
        set(value) = prefs.edit().putInt(K_RECENT_LIMIT, value.coerceAtLeast(0)).apply()

    /** Terminal font size in sp (settings dialog and pinch zoom both store it). */
    var fontSizeSp: Float
        get() = prefs.getFloat(K_FONT_SIZE, DEFAULT_FONT_SP).coerceIn(MIN_FONT_SP, MAX_FONT_SP)
        set(value) = prefs.edit().putFloat(K_FONT_SIZE, value.coerceIn(MIN_FONT_SP, MAX_FONT_SP)).apply()

    /** Terminal scroll sensitivity in percent (100 = the finger's distance); drag and fling alike. */
    var scrollPercent: Int
        get() = prefs.getInt(K_SCROLL, DEFAULT_SCROLL_PERCENT).coerceIn(MIN_SCROLL_PERCENT, MAX_SCROLL_PERCENT)
        set(value) = prefs.edit().putInt(K_SCROLL, value.coerceIn(MIN_SCROLL_PERCENT, MAX_SCROLL_PERCENT)).apply()

    /**
     * The terminal grid the phone last had (cols to rows), sent when creating a session so the PC starts at the
     * phone's width instead of 120 columns; null until a terminal was opened once.
     */
    var lastGrid: Pair<Int, Int>?
        get() {
            val c = prefs.getInt(K_LAST_COLS, 0)
            val r = prefs.getInt(K_LAST_ROWS, 0)
            return if (c > 0 && r > 0) c to r else null
        }
        set(value) = prefs.edit().putInt(K_LAST_COLS, value?.first ?: 0).putInt(K_LAST_ROWS, value?.second ?: 0).apply()

    /** Terminal key bar: shown keys in order (KeyBarLayout ids). */
    var keyBar: List<String>
        get() = com.neststudio.pocketshell.core.KeyBarLayout.parse(prefs.getString(K_KEY_BAR, null))
        set(value) = prefs.edit().putString(K_KEY_BAR, com.neststudio.pocketshell.core.KeyBarLayout.toText(value)).apply()

    fun resetKeyBar() = prefs.edit().remove(K_KEY_BAR).apply()

    /** Web view: saved addresses (newest first) and the last page opened. */
    var webBookmarks: List<com.neststudio.pocketshell.core.WebBookmark>
        get() = com.neststudio.pocketshell.core.WebBookmarks.parse(prefs.getString(K_WEB_BOOKMARKS, null).orEmpty())
        set(value) = prefs.edit().putString(K_WEB_BOOKMARKS, com.neststudio.pocketshell.core.WebBookmarks.toText(value)).apply()

    var webLastUrl: String
        get() = prefs.getString(K_WEB_LAST, null).orEmpty()
        set(value) = prefs.edit().putString(K_WEB_LAST, value).apply()

    /** Terminal font id, see TerminalFonts. */
    var fontId: String
        get() = prefs.getString(K_FONT, null).orEmpty()
        set(value) = prefs.edit().putString(K_FONT, value).apply()

    /** File browser sort order id, see FileSort. */
    var fileSort: String
        get() = prefs.getString(K_FILE_SORT, null).orEmpty()
        set(value) = prefs.edit().putString(K_FILE_SORT, value).apply()

    /** Notify when an AI CLI on a paired PC stops (AgentWatchService). On until the user turns it off. */
    var agentNotify: Boolean
        get() = prefs.getBoolean(K_AGENT_NOTIFY, true)
        set(value) = prefs.edit().putBoolean(K_AGENT_NOTIFY, value).apply()

    /** The notification permission was asked once already (Android 13+); do not ask on every launch. */
    var notifyPermissionAsked: Boolean
        get() = prefs.getBoolean(K_NOTIFY_ASKED, false)
        set(value) = prefs.edit().putBoolean(K_NOTIFY_ASKED, value).apply()

    /** The battery optimization hint was shown once. */
    var batteryHintShown: Boolean
        get() = prefs.getBoolean(K_BATTERY_HINT, false)
        set(value) = prefs.edit().putBoolean(K_BATTERY_HINT, value).apply()

    /** A session whose "stopped" notifications are off (a long job the user does not want to hear about). */
    fun isMuted(pcId: String, sessionId: String): Boolean =
        prefs.getStringSet(K_MUTED, emptySet()).orEmpty().contains("$pcId/$sessionId")

    fun setMuted(pcId: String, sessionId: String, muted: Boolean) {
        val set = prefs.getStringSet(K_MUTED, emptySet()).orEmpty().toMutableSet()
        if (muted) set.add("$pcId/$sessionId") else set.remove("$pcId/$sessionId")
        // Console ids go away with their process: the list must not grow forever.
        val kept = if (set.size > MUTED_MAX) set.toList().takeLast(MUTED_MAX).toSet() else set
        prefs.edit().putStringSet(K_MUTED, kept).apply()
    }

    /** Quiet hours: notifications in this daily range arrive without sound or a pop-up. */
    var quietEnabled: Boolean
        get() = prefs.getBoolean(K_QUIET_ON, false)
        set(value) = prefs.edit().putBoolean(K_QUIET_ON, value).apply()

    /** Minutes of the day. */
    var quietStart: Int
        get() = prefs.getInt(K_QUIET_START, QuietHours.DEFAULT_START).coerceIn(0, 24 * 60 - 1)
        set(value) = prefs.edit().putInt(K_QUIET_START, value.coerceIn(0, 24 * 60 - 1)).apply()

    var quietEnd: Int
        get() = prefs.getInt(K_QUIET_END, QuietHours.DEFAULT_END).coerceIn(0, 24 * 60 - 1)
        set(value) = prefs.edit().putInt(K_QUIET_END, value.coerceIn(0, 24 * 60 - 1)).apply()

    fun quietNow(): Boolean {
        if (!quietEnabled) return false
        val now = java.time.LocalTime.now()
        return QuietHours.contains(quietStart, quietEnd, now.hour * 60 + now.minute)
    }

    /** Phrases sent with one tap (terminal key bar, notification reply). Never edited: the defaults. */
    var snippets: List<String>
        get() = prefs.getString(K_SNIPPETS, null)?.let(Snippets::parse) ?: Snippets.DEFAULTS
        set(value) = prefs.edit().putString(K_SNIPPETS, Snippets.toText(value)).apply()

    /** Terminal: the AI CLI's choices as buttons above the key bar while it waits (⋮ menu toggle, on by default). */
    var quickReplies: Boolean
        get() = prefs.getBoolean(K_QUICK_REPLIES, true)
        set(value) = prefs.edit().putBoolean(K_QUICK_REPLIES, value).apply()

    companion object {
        private const val PREFS = "ui"
        private const val K_QUICK_REPLIES = "quick_replies"
        private const val K_MUTED = "muted_sessions"
        private const val MUTED_MAX = 200
        private const val K_QUIET_ON = "quiet_enabled"
        private const val K_QUIET_START = "quiet_start"
        private const val K_QUIET_END = "quiet_end"
        private const val K_SNIPPETS = "snippets"
        private const val K_BATTERY_HINT = "battery_hint_shown"
        private const val K_AGENT_NOTIFY = "agent_notify"
        private const val K_NOTIFY_ASKED = "notify_permission_asked"
        private const val K_FILE_SORT = "file_sort"
        private const val K_RECENT_LIMIT = "recent_limit_v2"
        private const val K_FONT_SIZE = "font_size_sp"
        private const val K_FONT = "font"
        private const val K_SCROLL = "scroll_percent"
        private const val K_KEY_BAR = "key_bar"
        private const val K_WEB_BOOKMARKS = "web_bookmarks"
        private const val K_WEB_LAST = "web_last_url"
        private const val K_LAST_COLS = "last_cols"
        private const val K_LAST_ROWS = "last_rows"

        /** Choices offered in the menu. */
        val RECENT_LIMIT_CHOICES = intArrayOf(SessionSections.RECENT_ALL, 0, 5, 10, 20, 30, 50)

        const val DEFAULT_SCROLL_PERCENT = 100
        const val MIN_SCROLL_PERCENT = 50
        const val MAX_SCROLL_PERCENT = 300
        const val SCROLL_STEP = 10

        const val DEFAULT_FONT_SP = 12f
        const val MIN_FONT_SP = 6f
        const val MAX_FONT_SP = 32f
    }
}
