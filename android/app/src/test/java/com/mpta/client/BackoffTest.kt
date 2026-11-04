package com.mpta.client

import com.mpta.client.core.Backoff
import org.junit.Assert.assertEquals
import org.junit.Test

class BackoffTest {

    @Test
    fun scheduleIsOneTwoFourEightThenCappedAtTen() {
        val b = Backoff()
        val delays = (1..8).map { b.nextDelayMillis() }
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 10_000L, 10_000L, 10_000L, 10_000L), delays)
    }

    @Test
    fun resetStartsOverAtOneSecond() {
        val b = Backoff()
        repeat(5) { b.nextDelayMillis() }
        b.reset()
        assertEquals(0, b.attempt)
        assertEquals(1_000L, b.nextDelayMillis())
        assertEquals(2_000L, b.nextDelayMillis())
    }

    @Test
    fun largeAttemptCountsDoNotOverflow() {
        val b = Backoff()
        assertEquals(10_000L, b.delayForAttempt(62))
        assertEquals(10_000L, b.delayForAttempt(Int.MAX_VALUE))
    }
}
