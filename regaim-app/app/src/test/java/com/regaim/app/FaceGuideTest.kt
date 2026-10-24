package com.regaim.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceGuideTest {
    private fun face(yaw: Float = 0f, width: Float = 0.4f, cx: Float = 0.5f, cy: Float = 0.45f, eyes: Float? = 0.9f, light: Int = 140, moved: Float = 0.01f) =
        FaceGuide.Face(yaw, width, cx, cy, eyes, light, moved)

    @Test fun takesThePictureOnlyWhenTheFaceIsRight() {
        val front = FaceGuide.Step.FRONT
        assertEquals(FaceGuide.READY, FaceGuide.check(front, 1, face()))
        // Each problem gets its hint (and no picture).
        for (bad in listOf(null to 0, face() to 2, face(width = 0.15f) to 1, face(width = 0.9f) to 1, face(cx = 0.9f) to 1,
            face(light = 30) to 1, face(light = 250) to 1, face(yaw = 25f) to 1, face(eyes = 0.1f) to 1, face(moved = 0.2f) to 1))
            assertNotEquals(bad.toString(), FaceGuide.READY, FaceGuide.check(front, bad.second, bad.first))
        // Unknown eyes (the detector didn't say): not a reason to wait.
        assertEquals(FaceGuide.READY, FaceGuide.check(front, 1, face(eyes = null)))
    }

    @Test fun theSidesTurnBothWays() {
        assertNotEquals(FaceGuide.READY, FaceGuide.check(FaceGuide.Step.SIDE_A, 1, face(yaw = 3f)))
        assertEquals(FaceGuide.READY, FaceGuide.check(FaceGuide.Step.SIDE_A, 1, face(yaw = 22f)))
        assertNotEquals(FaceGuide.READY, FaceGuide.check(FaceGuide.Step.SIDE_A, 1, face(yaw = 60f)))
        // The second side: the other way from the first.
        assertNotEquals(FaceGuide.READY, FaceGuide.check(FaceGuide.Step.SIDE_B, 1, face(yaw = 20f), firstSide = 22f))
        assertEquals(FaceGuide.READY, FaceGuide.check(FaceGuide.Step.SIDE_B, 1, face(yaw = -20f), firstSide = 22f))
    }

    @Test fun theCropKeepsTheHairAndStaysInTheFrame() {
        val (l, t, side) = FaceGuide.crop(400f, 500f, 300f, 360f, 1080, 1440)
        assertEquals(792, side)
        assertTrue(l >= 0 && t >= 0 && l + side <= 1080 && t + side <= 1440)
        // The face inside the square.
        assertTrue(l <= 400 && l + side >= 700 && t <= 500 && t + side >= 860)
        // At the edge of the frame: moved in, not cut.
        val (l2, t2, s2) = FaceGuide.crop(950f, 10f, 120f, 140f, 1080, 1440)
        assertTrue(l2 + s2 <= 1080 && t2 >= 0)
    }

    @Test fun sharperIsMore() {
        val w = 40; val h = 40
        val sharp = IntArray(w * h) { if ((it % w) / 4 % 2 == 0) 0 else 255 }
        val soft = IntArray(w * h) { 128 + ((it % w) - 20) }
        assertTrue(FaceGuide.sharpness(sharp, w, h) > FaceGuide.sharpness(soft, w, h))
    }
}
