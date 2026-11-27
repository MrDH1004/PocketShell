package com.neststudio.pocketshell.net

/** Runs work on the UI thread (Android: a main-looper Handler, desktop: the Swing event thread). */
interface Poster {
    fun post(r: Runnable)
    fun postDelayed(r: Runnable, delayMillis: Long)
    fun removeCallbacks(r: Runnable)
}
