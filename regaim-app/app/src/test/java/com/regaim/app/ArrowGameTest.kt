package com.regaim.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArrowGameTest {
    @Test fun everyLevelCanBeWon() {
        for (l in ArrowGame.LEVELS) {
            assertTrue(l.say, ArrowGame.shortest(l) > 0)
            assertTrue(l.say, l.start !in l.things && l.goal !in l.things)
        }
        // The last level: only the jump gets over the row of rock, log and mushrooms.
        val last = ArrowGame.LEVELS.last()
        val noJump = last.copy(things = last.things)
        var c = noJump.start
        c = ArrowGame.step(noJump, c, ArrowGame.Dir.FORWARD)!!
        assertNull(ArrowGame.step(noJump, c, ArrowGame.Dir.FORWARD))
        assertEquals(noJump.goal, ArrowGame.jump(noJump, c, ArrowGame.Dir.FORWARD))
    }

    @Test fun edgesAndRocksStopHim() {
        val l = ArrowGame.LEVELS[1]
        assertNull(ArrowGame.step(l, l.start, ArrowGame.Dir.LEFT))   // the edge
        assertNull(ArrowGame.step(l, l.start, ArrowGame.Dir.BACK))   // the near edge
        assertNotNull(ArrowGame.step(l, l.start, ArrowGame.Dir.RIGHT))
        assertNull(ArrowGame.step(l, ArrowGame.Cell(1, 3), ArrowGame.Dir.FORWARD)) // the mushroom
        assertTrue(ArrowGame.LINES.contains("קדימה!") && ArrowGame.LINES.contains("אופס!"))
    }

    @Test fun theGreenComesOff() {
        val green = (0xFF shl 24) or (20 shl 16) or (240 shl 8) or 30
        val skin = (0xFF shl 24) or (230 shl 16) or (170 shl 8) or (140)
        val hat = (0xFF shl 24) or (70 shl 16) or (110 shl 8) or 50 // Pitzki's own green hat: a little green, stays
        val px = intArrayOf(green, skin, hat)
        GreenKey.key(px)
        assertEquals(0, px[0] ushr 24)
        assertEquals(255, px[1] ushr 24)
        assertTrue((px[2] ushr 24) > 150)
    }

    @Test fun thingsAreCutApart() {
        val w = 60; val h = 20
        val px = IntArray(w * h)
        // Three things: columns 2–9, 20–31, 45–55.
        for ((s, e) in listOf(2 to 9, 20 to 31, 45 to 55)) for (x in s..e) for (y in 4..15) px[y * w + x] = (0xFF shl 24)
        val boxes = GreenKey.things(px, w, h, 3)
        assertEquals(listOf(2, 20, 45), boxes.map { it[0] })
        assertEquals(listOf(9, 31, 55), boxes.map { it[2] })
        val b = GreenKey.bounds(px, w, h)!!
        assertEquals(listOf(2, 4, 55, 15), b.toList())
    }

    @Test fun theBoardInWordsForAScreenReader() {
        val lv = ArrowGame.LEVELS[0]
        val d = ArrowGame.describe(lv, lv.start)
        assertTrue(d.startsWith("לוח המשחק. פיצקי ב"))
        assertTrue(d.contains("צריך להגיע אל"))
        assertTrue(d.contains("כפתורי החצים"))
    }
}
