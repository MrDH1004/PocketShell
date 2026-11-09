package com.neststudio.pocketshell.ui

import android.content.Context
import android.view.View

/** Tablet / landscape layout helpers (DESIGN §8): card columns and a readable content width on wide screens. */
object WideScreen {
    /** Narrowest a session card gets before another column is not worth it. */
    private const val CARD_MIN_DP = 360
    private const val MAX_COLUMNS = 3

    /** Card columns for the current window width: 1 on phones in portrait, 2–3 on tablets and in landscape. */
    fun columns(context: Context): Int =
        (context.resources.configuration.screenWidthDp / CARD_MIN_DP).coerceIn(1, MAX_COLUMNS)

    /**
     * Keeps [view]'s content at most [maxDp] wide and centered by growing its side padding beyond what the layout
     * gave it. Follows width changes (rotation, split screen, freeform windows).
     */
    fun limitWidth(view: View, maxDp: Int) {
        val baseStart = view.paddingStart
        val baseEnd = view.paddingEnd
        val maxPx = maxDp * view.resources.displayMetrics.density
        view.addOnLayoutChangeListener { v, left, _, right, _, _, _, _, _ ->
            val extra = maxOf(0f, (right - left - maxPx) / 2f).toInt()
            if (v.paddingStart != baseStart + extra || v.paddingEnd != baseEnd + extra) {
                v.post { v.setPaddingRelative(baseStart + extra, v.paddingTop, baseEnd + extra, v.paddingBottom) }
            }
        }
    }
}
