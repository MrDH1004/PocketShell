package com.neststudio.pocketshell.data

import android.content.Context
import com.neststudio.pocketshell.core.QuietHours
import com.neststudio.pocketshell.core.SessionSections
import com.neststudio.pocketshell.core.Snippets

/** Small UI options (app-private SharedPreferences). */
class UiPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Entries shown under 최근 in the session list; 0 hides the section. */
    var recentLimit: Int
        get() = prefs.getInt(K_RECENT_LIMIT, SessionSections.DEFAULT_RECENT_LIMIT).coerceAtLeast(0)
        set(value) = prefs.edit().putInt(K_RECENT_LIMIT, value.coerceAtLeast(0)).apply()

    /** Terminal font size in sp (settings dialog and pinch zoom both store it). */
    var fontSizeSp: Float
        get() = prefs.getFloat(K_FONT_SIZE, DEFAULT_FONT_SP).coerceIn(MIN_FONT_SP, MAX_FONT_SP)
        set(value) = prefs.edit().putFloat(K_FONT_SIZE, value.coerceIn(MIN_FONT_SP, MAX_FONT_SP)).apply()

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
        private const val K_RECENT_LIMIT = "recent_limit"
        private const val K_FONT_SIZE = "font_size_sp"
        private const val K_FONT = "font"

        /** Choices offered in the menu. */
        val RECENT_LIMIT_CHOICES = intArrayOf(0, 3, 5, 8, 10, 15, 20, 30)

        const val DEFAULT_FONT_SP = 12f
        const val MIN_FONT_SP = 6f
        const val MAX_FONT_SP = 32f
    }
}
