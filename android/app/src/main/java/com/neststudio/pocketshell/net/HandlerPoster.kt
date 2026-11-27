package com.neststudio.pocketshell.net

import android.os.Handler
import android.os.Looper

/** [Poster] on the Android main thread. */
object HandlerPoster : Poster {
    private val main = Handler(Looper.getMainLooper())
    override fun post(r: Runnable) { main.post(r) }
    override fun postDelayed(r: Runnable, delayMillis: Long) { main.postDelayed(r, delayMillis) }
    override fun removeCallbacks(r: Runnable) { main.removeCallbacks(r) }
}
