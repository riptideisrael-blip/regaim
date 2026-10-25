package com.regaim.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt
import kotlin.random.Random

class FacePrintsTest {
    private fun unit(v: FloatArray): FloatArray {
        val n = sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
        return FloatArray(v.size) { v[it] / n }
    }

    private fun near(base: FloatArray, r: Random, noise: Float) =
        unit(FloatArray(base.size) { base[it] + (r.nextFloat() - 0.5f) * noise })

    @Test
    fun damagedPrintsNeverMatch() {
        val a = unit(FloatArray(192) { 1f })
        assertEquals(FacePrints.FAR, FacePrints.dist(FloatArray(0), FloatArray(0)))
        assertEquals(FacePrints.FAR, FacePrints.dist(a, FloatArray(0)))
        assertEquals(FacePrints.FAR, FacePrints.dist(a, a.copyOf(128)))
        val nan = a.copyOf().also { it[3] = Float.NaN }
        assertEquals(FacePrints.FAR, FacePrints.dist(a, nan))
        assertEquals(0f, FacePrints.dist(a, a), 1e-6f)
        assertTrue(FacePrints.dist(a, FloatArray(192) { if (it == 0) 1f else 0f }) < 2.0001f)
    }

    @Test
    fun normalizeRejectsGarbage() {
        assertNull(FacePrints.normalize(FloatArray(0)))
        assertNull(FacePrints.normalize(FloatArray(192)))
        assertNull(FacePrints.normalize(FloatArray(192) { if (it == 5) Float.POSITIVE_INFINITY else 1f }))
        val n = FacePrints.normalize(FloatArray(4) { 2f })!!
        assertEquals(1f, sqrt(n.sumOf { (it * it).toDouble() }).toFloat(), 1e-5f)
    }

    @Test
    fun typicalLeavesOutTheOddFace() {
        val r = Random(7)
        val child = unit(FloatArray(192) { r.nextFloat() - 0.5f })
        val sibling = unit(FloatArray(192) { r.nextFloat() - 0.5f })
        val group = List(8) { near(child, r, 0.08f) } + near(sibling, r, 0.08f)
        val odd = group.last()
        val picked = FacePrints.typical(group, 6)
        assertEquals(6, picked.size)
        assertTrue(picked.none { it === odd })
        // Even with room for all, the odd one stays out.
        assertTrue(FacePrints.typical(group, 20).none { it === odd })
    }

    @Test
    fun typicalKeepsSmallGroups() {
        val r = Random(3)
        val a = unit(FloatArray(192) { r.nextFloat() - 0.5f })
        assertEquals(2, FacePrints.typical(listOf(a, near(a, r, 0.1f)), 6).size)
        assertEquals(1, FacePrints.typical(listOf(a, FloatArray(0)), 6).size)
    }

    @Test
    fun scanTriesRetryThenGiveUp() {
        val ids = listOf("a" to 100L, "b" to 300L, "c" to 200L, "d" to 50L)
        val tries = mapOf("a" to 1, "d" to ScanTries.MAX)
        // Fewest failures first, newest first among equals; given-up and already-failed-this-run are skipped.
        assertEquals(listOf("b", "a"), ScanTries.order(ids, tries, setOf("c")))
        assertEquals(listOf("b", "c", "a"), ScanTries.order(ids, tries, emptySet()))
        assertTrue(!ScanTries.giveUp(ScanTries.MAX - 1))
        assertTrue(ScanTries.giveUp(ScanTries.MAX))
    }

    @Test
    fun hexIsLowercase() {
        assertEquals("00ff10", FacePrints.hex(byteArrayOf(0, -1, 16)))
    }

    @Test fun samplesFromEveryAgeAreKept() {
        fun v(x: Float) = floatArrayOf(x, 1f)
        val recent = (1..15).map { v(it.toFloat()) }
        val (r, p) = FacePrints.roll(recent, emptyList(), listOf(v(16f)))
        org.junit.Assert.assertEquals(15, r.size)
        org.junit.Assert.assertEquals(16f, r.last()[0])
        org.junit.Assert.assertEquals(1f, p.single()[0]) // the oldest moved to "past", not lost
        // Many years: the past stays small and spread from the oldest to the newer ones.
        var cur = recent; var past = emptyList<FloatArray>()
        for (k in 16..60) { val x = FacePrints.roll(cur, past, listOf(v(k.toFloat()))); cur = x.first; past = x.second }
        org.junit.Assert.assertTrue(past.size <= 8)
        org.junit.Assert.assertEquals(1f, past.first()[0])
        org.junit.Assert.assertTrue(past.last()[0] > 30f)
    }

    // Two people as random directions in 192-d space (far apart, like real different faces); noise = same person, other photo.
    private fun person(seed: Int) = unit(FloatArray(192) { Random(seed).let { r -> repeat(it) { r.nextFloat() }; r.nextFloat() - 0.5f } })

    @Test fun threeFacesWithOneSiblingLeaveTheSiblingOut() {
        val r = Random(7)
        val uri = person(1); val sib = person(2)
        val a = near(uri, r, 0.05f); val b = near(uri, r, 0.05f); val odd = near(sib, r, 0.05f)
        assertTrue(FacePrints.dist(a, b) < FacePrints.ODD_PAIR && FacePrints.dist(a, odd) > FacePrints.ODD_AWAY)
        val kept = FacePrints.typical(listOf(a, odd, b), 6)
        assertEquals(2, kept.size)
        assertTrue(kept.none { it === odd })
    }

    @Test fun threeFacesOfOnePersonAreAllKept() {
        val r = Random(9)
        val uri = person(1)
        val faces = listOf(near(uri, r, 0.08f), near(uri, r, 0.08f), near(uri, r, 0.08f))
        assertEquals(3, FacePrints.typical(faces, 6).size)
        // Three different people (nothing agrees): no evidence, all three stay.
        assertEquals(3, FacePrints.typical(listOf(person(1), person(2), person(3)), 6).size)
        // Two: can't tell which one is odd.
        assertEquals(2, FacePrints.typical(listOf(person(1), person(2)), 6).size)
    }

    @Test fun scanFailuresAreTriedByTheirKind() {
        assertEquals(ScanTries.Why.MISSING, ScanTries.classify("FileNotFoundException: open failed: ENOENT", null))
        assertEquals(ScanTries.Why.MISSING, ScanTries.classify("anything", false))
        assertEquals(ScanTries.Why.TEMPORARY, ScanTries.classify("OutOfMemoryError: Failed to allocate", true))
        assertEquals(ScanTries.Why.TEMPORARY, ScanTries.classify("IOException: pending download", null))
        assertEquals(ScanTries.Why.DECODE, ScanTries.classify("IllegalStateException: no frames", true))
        assertTrue(ScanTries.giveUp(1, ScanTries.Why.MISSING))
        assertTrue(!ScanTries.giveUp(5, ScanTries.Why.TEMPORARY))
        assertTrue(ScanTries.giveUp(10, ScanTries.Why.TEMPORARY))
        // A moment waiting for its download is still scanned after 3 failed runs; a decode failure is not.
        val ids = listOf("t" to 1L, "d" to 2L)
        assertEquals(listOf("t"), ScanTries.order(ids, mapOf("t" to 3, "d" to 3), emptySet(), mapOf("t" to ScanTries.Why.TEMPORARY)))
    }

    @Test fun benchCountsRightUnsureAndWrong() {
        val r = Random(3)
        val uri = person(1); val noam = person(2)
        val uriFaces = List(4) { near(uri, r, 0.05f) }
        val noamFaces = List(3) { near(noam, r, 0.05f) }
        val people = listOf(Triple("uri", uriFaces, emptyList<FloatArray>()), Triple("noam", noamFaces, emptyList()))
        val res = FaceBench.evaluate(uriFaces.map { FaceBench.Sample("uri", it) } + noamFaces.map { FaceBench.Sample("noam", it) }, people, 0.82f, 0.06f)
        assertEquals(7, res.total)
        assertEquals(7, res.right) // each face found by the person's other faces, never by itself
        assertEquals(0f, res.falseMatchRate, 1e-6f)
        // A face labelled "uri" that is really Noam's: counted as a wrong match (Uri -> Noam).
        val mixed = FaceBench.evaluate(listOf(FaceBench.Sample("uri", near(noam, r, 0.05f))), people, 0.82f, 0.06f)
        assertEquals(1, mixed.wrong)
        assertEquals(1, mixed.confusion["uri" to "noam"])
        // Someone the app was told "not them" is never matched to them.
        val rejected = listOf(Triple("uri", uriFaces, listOf(noamFaces[0])), Triple("other", emptyList<FloatArray>(), emptyList()))
        assertNull(FacePrints.bestMatch(near(noam, r, 0.02f), rejected, 0.82f, 0.06f))
    }

    @Test
    fun whyAFaceHasNoName() {
        // Points on a circle: distance between unit vectors at angle t is 2*sin(t/2).
        fun at(deg: Double) = floatArrayOf(Math.cos(Math.toRadians(deg)).toFloat(), Math.sin(Math.toRadians(deg)).toFloat())
        val uri = Triple("uri", listOf(at(0.0)), emptyList<FloatArray>())
        val noam = Triple("noam", listOf(at(50.0)), emptyList<FloatArray>())
        val m = 0.82f; val margin = 0.06f
        assertEquals(FacePrints.Unnamed.NO_PEOPLE, FacePrints.whyUnnamed(at(0.0), 100, 72, emptyList(), m, margin))
        assertEquals(FacePrints.Unnamed.SMALL, FacePrints.whyUnnamed(at(0.0), 40, 72, listOf(uri), m, margin))
        assertEquals(FacePrints.Unnamed.WOULD_MATCH, FacePrints.whyUnnamed(at(10.0), 100, 72, listOf(uri), m, margin))
        assertEquals(FacePrints.Unnamed.TIE, FacePrints.whyUnnamed(at(25.0), 100, 72, listOf(uri, noam), m, margin))
        // 0.82 is about 48.5 degrees; 52 degrees is just beyond (near), 120 is far.
        assertEquals(FacePrints.Unnamed.FAR_NEAR, FacePrints.whyUnnamed(at(-52.0), 100, 72, listOf(uri), m, margin))
        assertEquals(FacePrints.Unnamed.FAR, FacePrints.whyUnnamed(at(-120.0), 100, 72, listOf(uri), m, margin))
        val uriNot = Triple("uri", listOf(at(0.0)), listOf(at(12.0)))
        assertEquals(FacePrints.Unnamed.NOT_THEM, FacePrints.whyUnnamed(at(10.0), 100, 72, listOf(uriNot), m, margin))
    }

    @Test
    fun groupNamedOnlyWhenAllAgree() {
        fun at(deg: Double) = floatArrayOf(Math.cos(Math.toRadians(deg)).toFloat(), Math.sin(Math.toRadians(deg)).toFloat())
        val michal = Triple("michal", listOf(at(0.0)), emptyList<FloatArray>())
        val yosef = Triple("yosef", listOf(at(90.0)), emptyList<FloatArray>())
        val strong = 0.80f; val margin = 0.06f
        assertEquals("michal", FacePrints.groupVote(listOf(at(20.0), at(-25.0)), listOf(michal, yosef), strong, margin))
        assertNull("one face alone is asked about", FacePrints.groupVote(listOf(at(5.0)), listOf(michal, yosef), strong, margin))
        assertNull("one face too far: ask", FacePrints.groupVote(listOf(at(10.0), at(-60.0)), listOf(michal, yosef), strong, margin))
        assertNull("faces disagree: ask", FacePrints.groupVote(listOf(at(5.0), at(85.0)), listOf(michal, yosef), strong, margin))
        assertNull("in between two people: ask", FacePrints.groupVote(listOf(at(44.0), at(46.0)), listOf(michal, yosef), strong, margin))
    }
}
