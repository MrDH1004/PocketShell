package com.neststudio.pocketshell.data

import android.content.Context
import com.neststudio.pocketshell.core.SessionSections

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

    companion object {
        private const val PREFS = "ui"
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
