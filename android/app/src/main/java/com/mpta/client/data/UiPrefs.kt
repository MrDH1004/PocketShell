package com.mpta.client.data

import android.content.Context
import com.mpta.client.core.SessionSections

/** Small UI options (app-private SharedPreferences). */
class UiPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Entries shown under 최근 in the session list; 0 hides the section. */
    var recentLimit: Int
        get() = prefs.getInt(K_RECENT_LIMIT, SessionSections.DEFAULT_RECENT_LIMIT).coerceAtLeast(0)
        set(value) = prefs.edit().putInt(K_RECENT_LIMIT, value.coerceAtLeast(0)).apply()

    companion object {
        private const val PREFS = "ui"
        private const val K_RECENT_LIMIT = "recent_limit"

        /** Choices offered in the menu. */
        val RECENT_LIMIT_CHOICES = intArrayOf(0, 3, 5, 8, 10, 15, 20, 30)
    }
}
