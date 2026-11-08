package com.neststudio.pocketshell.core

import kotlin.math.max
import kotlin.math.roundToInt

/** Size rules for images sent to the PC clipboard (pure logic, unit tested). */
object ImageSizing {
    /** Longest side of a pasted image; larger photos are scaled down (screenshots usually fit as they are). */
    const val MAX_SIDE = 2560

    /** Largest power-of-two decode sample that still leaves the longest side at least [maxSide]. */
    fun sampleSize(width: Int, height: Int, maxSide: Int = MAX_SIDE): Int {
        var sample = 1
        while (max(width, height) / (sample * 2) >= maxSide) sample *= 2
        return sample
    }

    /** Target size keeping the aspect ratio so that the longest side is at most [maxSide]. */
    fun fit(width: Int, height: Int, maxSide: Int = MAX_SIDE): Pair<Int, Int> {
        val longest = max(width, height)
        if (longest <= maxSide) return width to height
        val scale = maxSide.toDouble() / longest
        return (width * scale).roundToInt().coerceAtLeast(1) to (height * scale).roundToInt().coerceAtLeast(1)
    }
}
