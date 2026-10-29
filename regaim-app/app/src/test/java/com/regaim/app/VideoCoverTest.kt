package com.regaim.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoCoverTest {
    @Test fun aTallVideoCoversATallerScreen() {
        // 1080×1920 video on a 1080×2340 screen: scaled up to the screen's height, the sides cut.
        assertEquals(1316 to 2340, VideoCover.size(1080, 1920, 1080, 2340))
    }

    @Test fun aWideVideoCoversATallScreen() {
        // 1920×1080 on 1080×2340: as tall as the screen, much wider (the sides cut).
        assertEquals(4160 to 2340, VideoCover.size(1920, 1080, 1080, 2340))
    }

    @Test fun unknownSize() {
        assertNull(VideoCover.size(0, 0, 1080, 2340))
        assertNull(VideoCover.size(1080, 1920, 0, 0))
    }
}
