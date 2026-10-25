package com.regaim.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FaceSignalsTest {
    private val face = FaceSignals.Box(500f, 400f, 200f, 200f, 0.05f)
    private val close = FaceSignals.Box(500f, 400f, 600f, 600f, 0.3f)

    private fun run(s: FaceSignals, frames: List<Triple<Long, List<FaceSignals.Box>, Pair<Float, Boolean>>>) =
        frames.mapNotNull { (t, f, sc) -> s.frame(t, f, sc.first, sc.second, hugOn = true, facesOn = true) }

    @Test
    fun oneSmilingFrameIsNotEnough() {
        val s = FaceSignals()
        assertNull(s.frame(0, listOf(face), 0.95f, false, true, true))
        assertNull(s.frame(800, listOf(face), 0.2f, false, true, true))
        assertNull(s.frame(1600, listOf(face), 0.95f, false, true, true))
    }

    @Test
    fun smileInTwoFramesInARow() {
        val s = FaceSignals()
        assertNull(s.frame(0, listOf(face), 0.8f, false, true, true))
        assertEquals("Smile", s.frame(800, listOf(face), 0.92f, false, true, true))
        // Not again right away from the same smile.
        assertNull(s.frame(1600, listOf(face), 0.92f, false, true, true))
    }

    @Test
    fun smileFramesTooFarApartDontCount() {
        val s = FaceSignals()
        assertNull(s.frame(0, listOf(face), 0.9f, false, true, true))
        assertNull(s.frame(5000, listOf(face), 0.9f, false, true, true))
    }

    @Test
    fun hugWhenCoveredRightAfterACloseFace() {
        val s = FaceSignals()
        val got = run(s, listOf(
            Triple(0L, listOf(close), 0f to false),
            Triple(800L, emptyList(), 0f to true),
            Triple(1600L, emptyList(), 0f to true),
            Triple(2400L, emptyList(), 0f to true),
            Triple(3400L, emptyList(), 0f to true),
        ))
        assertEquals(listOf("Hug"), got)
    }

    @Test
    fun darkRoomIsNotAHug() {
        // Already dark before the face came close (lights off): never "from a normal picture to covered".
        val s = FaceSignals()
        val got = run(s, (0 until 10).map { Triple(it * 800L, if (it == 0) listOf(close) else emptyList(), 0f to true) })
        assertEquals(emptyList<String>(), got)
    }

    @Test
    fun pocketWithoutAFaceIsNotAHug() {
        val s = FaceSignals()
        val got = run(s, listOf(Triple(0L, emptyList<FaceSignals.Box>(), 0f to false)) +
            (1..8).map { Triple(it * 800L, emptyList<FaceSignals.Box>(), 0f to true) })
        assertEquals(emptyList<String>(), got)
    }

    @Test
    fun twoSimilarFacesTogetherFor3Seconds() {
        val a = FaceSignals.Box(400f, 400f, 200f, 200f, 0.05f)
        val b = FaceSignals.Box(570f, 420f, 190f, 190f, 0.045f)
        val s = FaceSignals()
        val got = run(s, (0..5).map { Triple(it * 800L, listOf(a, b), 0f to false) })
        assertEquals(listOf("Hug"), got)
    }

    @Test
    fun bigFaceAndSmallBackgroundFaceAreNotAHug() {
        val a = FaceSignals.Box(400f, 400f, 300f, 300f, 0.12f)
        val b = FaceSignals.Box(560f, 400f, 120f, 120f, 0.03f)
        val s = FaceSignals()
        val got = run(s, (0..6).map { Triple(it * 800L, listOf(a, b), 0f to false) })
        assertEquals(emptyList<String>(), got)
    }

    @Test
    fun approachStillWorks() {
        val s = FaceSignals()
        assertNull(s.frame(0, listOf(FaceSignals.Box(0f, 0f, 1f, 1f, 0.01f)), 0f, false, false, true))
        assertEquals("Approach", s.frame(1600, listOf(FaceSignals.Box(0f, 0f, 1f, 1f, 0.09f)), 0f, false, false, true))
    }
}
