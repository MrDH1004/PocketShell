package com.neststudio.pocketshell

import com.neststudio.pocketshell.core.ImageSizing
import org.junit.Assert.assertEquals
import org.junit.Test

class ImageSizingTest {
    @Test
    fun screenshotKeepsItsSize() {
        assertEquals(1080 to 2400, ImageSizing.fit(1080, 2400))
        assertEquals(1, ImageSizing.sampleSize(1080, 2400))
    }

    @Test
    fun largePhotoIsScaledToMaxSide() {
        assertEquals(2560 to 1920, ImageSizing.fit(4000, 3000))
        assertEquals(1440 to 2560, ImageSizing.fit(3000, 5333))
    }

    @Test
    fun sampleSizeNeverDropsBelowMaxSide() {
        assertEquals(1, ImageSizing.sampleSize(4000, 3000))  // 4000/2 = 2000 < 2560
        assertEquals(2, ImageSizing.sampleSize(6000, 4000))  // 3000 ≥ 2560, 1500 < 2560
        assertEquals(4, ImageSizing.sampleSize(12000, 9000)) // 3000 ≥ 2560
    }
}
