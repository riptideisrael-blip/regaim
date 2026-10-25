package com.regaim.app

import org.junit.Assert.assertTrue
import org.junit.Test

class FrameScoreTest {
    private fun gray(v: Int) = (0xFF shl 24) or (v shl 16) or (v shl 8) or v

    @Test fun aPlainWallIsDull() {
        val px = IntArray(32 * 32) { gray(180 + (it % 3)) }
        assertTrue(FrameScore.detail(px, 32, 32) < FrameScore.DULL)
    }

    @Test fun aBusySceneIsNot() {
        val px = IntArray(32 * 32) { i -> val x = i % 32; val y = i / 32; gray(if ((x / 4 + y / 4) % 2 == 0) 30 else 220) }
        assertTrue(FrameScore.detail(px, 32, 32) > FrameScore.DULL)
    }

    @Test fun moreDetailScoresHigher() {
        val soft = IntArray(32 * 32) { i -> gray(100 + (i % 32)) }
        val sharp = IntArray(32 * 32) { i -> gray(if (i % 2 == 0) 20 else 230) }
        assertTrue(FrameScore.detail(sharp, 32, 32) > FrameScore.detail(soft, 32, 32))
    }

    @Test fun tinyOrBrokenInputIsZero() {
        assertTrue(FrameScore.detail(IntArray(0), 0, 0) == 0.0)
    }
}
