package com.regaim.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureFilterTest {
    private val n = CaptureFilter.GRID_W * CaptureFilter.GRID_H
    private fun gradient(shift: Int = 0) = IntArray(n) { ((it % CaptureFilter.GRID_W) * 25 + shift).coerceIn(0, 255) }

    @Test fun darkFlatPictureIsCovered() {
        assertTrue(CaptureFilter.looksCovered(IntArray(n) { 6 }))
        assertTrue(CaptureFilter.looksCovered(IntArray(n) { 10 + it % 5 }))
    }

    @Test fun normalPictureIsNotCovered() {
        assertFalse(CaptureFilter.looksCovered(gradient()))
        assertFalse(CaptureFilter.looksCovered(IntArray(n) { 120 }))      // bright and flat is a wall, not a pocket
        assertFalse(CaptureFilter.looksCovered(IntArray(n) { if (it % 2 == 0) 5 else 90 })) // dark scene with detail
    }

    @Test fun samePictureHasSameHash() {
        assertEquals(0, CaptureFilter.distance(CaptureFilter.dHash(gradient()), CaptureFilter.dHash(gradient(3))))
    }

    @Test fun differentPicturesAreFar() {
        val a = CaptureFilter.dHash(IntArray(n) { if ((it / 9 + it % 9) % 2 == 0) 200 else 20 })
        val b = CaptureFilter.dHash(gradient())
        assertTrue(CaptureFilter.distance(a, b) > CaptureFilter.SIMILAR_BITS)
    }

    @Test fun recentWindowForgetsOldClips() {
        val r = CaptureFilter.Recent()
        val h = CaptureFilter.dHash(gradient())
        r.add(h, 0)
        assertTrue(r.isSimilar(h, 60_000))
        assertFalse(r.isSimilar(h, CaptureFilter.WINDOW_MS + 1))
    }
}
