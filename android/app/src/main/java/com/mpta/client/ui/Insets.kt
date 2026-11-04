package com.mpta.client.ui

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * targetSdk 35 enforces edge-to-edge on Android 15, and adjustResize no longer shrinks the window for
 * the keyboard. Pad [this] root view by the system bars and (optionally) the IME so content stays visible.
 */
fun View.applySystemInsets(includeIme: Boolean) {
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        val ime = if (includeIme) insets.getInsets(WindowInsetsCompat.Type.ime()).bottom else 0
        v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime))
        WindowInsetsCompat.CONSUMED
    }
    ViewCompat.requestApplyInsets(this)
}
