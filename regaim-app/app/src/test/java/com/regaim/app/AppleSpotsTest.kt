package com.regaim.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleSpotsTest {
    private val green = (0xFF shl 24) or (60 shl 16) or (140 shl 8) or 50
    private val red = (0xFF shl 24) or (210 shl 16) or (40 shl 8) or 35

    private fun picture(w: Int, h: Int, apples: List<Triple<Int, Int, Int>>, extra: (IntArray) -> Unit = {}): IntArray {
        val px = IntArray(w * h) { green }
        for ((cx, cy, r) in apples) for (y in cy - r..cy + r) for (x in cx - r..cx + r)
            if (x in 0 until w && y in 0 until h && (x - cx) * (x - cx) + (y - cy) * (y - cy) <= r * r) px[y * w + x] = red
        extra(px)
        return px
    }

    @Test fun findsTheApplesOnTheTree() {
        val apples = listOf(Triple(150, 30, 7), Triple(180, 50, 8), Triple(200, 25, 6), Triple(130, 60, 7), Triple(210, 70, 6))
        val px = picture(240, 135, apples) { p ->
            // A red stripe (a ribbon, not an apple) and a fallen apple at the very bottom: not counted.
            for (x in 10..90) for (y in 100..102) p[y * 240 + x] = red
            for (y in 124..132) for (x in 40..48) p[y * 240 + x] = red
        }
        val found = AppleSpots.find(px, 240, 135, 5)
        assertEquals(5, found.size)
        for ((cx, cy, _) in apples) assertTrue("$cx,$cy", found.any { kotlin.math.abs(it.x - cx) < 2 && kotlin.math.abs(it.y - cy) < 2 })
        // Asked for more than there are: only the real ones.
        assertEquals(5, AppleSpots.find(px, 240, 135, 8).size)
        // The biggest first.
        assertEquals(180f, AppleSpots.find(px, 240, 135, 1)[0].x, 2f)
    }

    @Test fun applesThatTouchAreStillTwo() {
        val w = 120; val h = 60
        fun picture(vararg disks: Triple<Int, Int, Int>) = IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            if (disks.any { (cx, cy, r) -> (x - cx) * (x - cx) + (y - cy) * (y - cy) <= r * r }) 0xFFD01818.toInt() else 0xFF406030.toInt()
        }
        val apart = AppleSpots.redPatches(picture(Triple(30, 30, 10), Triple(80, 30, 10)), w, h)
        val single = AppleSpots.singleSize(apart, 2)
        assertEquals(2, AppleSpots.countBySize(apart, single))
        // Side by side, touching: one red patch, two apples.
        val touching = AppleSpots.redPatches(picture(Triple(50, 30, 10), Triple(70, 30, 10)), w, h)
        assertEquals(1, touching.size)
        assertEquals(2, AppleSpots.countBySize(touching, single))
        // One apple half behind a hand is still one; a small red speck is none.
        val hidden = picture(Triple(30, 30, 10)).also { px -> for (y in 0 until h) for (x in 30 until 41) px[y * w + x] = 0xFFE0B090.toInt() }
        assertEquals(1, AppleSpots.countBySize(AppleSpots.redPatches(hidden, w, h), single))
        assertEquals(0, AppleSpots.countBySize(AppleSpots.redPatches(picture(Triple(60, 30, 3)), w, h), single))
    }
}
