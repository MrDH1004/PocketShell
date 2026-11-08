package com.neststudio.pocketshell.core

/**
 * Reconnect delay schedule (DESIGN.md §8, AC-18): 1, 2, 4, 8, 10, 10, ... seconds.
 * Not thread-safe; use from one thread (the main thread in the app).
 */
class Backoff(
    private val initialMillis: Long = 1_000,
    private val maxMillis: Long = 10_000,
) {
    var attempt: Int = 0
        private set

    /** Delay before the next reconnect attempt; advances the schedule. */
    fun nextDelayMillis(): Long {
        val delay = delayForAttempt(attempt)
        attempt++
        return delay
    }

    /** Call after a successful connection. */
    fun reset() {
        attempt = 0
    }

    fun delayForAttempt(n: Int): Long {
        require(n >= 0) { "attempt must be >= 0" }
        // Cap the shift so the multiplication cannot overflow.
        val shift = n.coerceAtMost(30)
        val raw = initialMillis * (1L shl shift)
        return raw.coerceAtMost(maxMillis)
    }
}
