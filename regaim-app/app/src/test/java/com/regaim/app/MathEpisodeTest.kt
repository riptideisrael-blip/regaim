package com.regaim.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MathEpisodeTest {
    @Test fun theShotsGoFromPictureToPictureInOrder() {
        val shots = MathEpisode.SHOTS
        assertEquals(0, shots.first().from)
        assertEquals(MathEpisode.FRAMES.size - 1, shots.last().to)
        for ((a, b) in shots.zipWithNext()) assertEquals("one continuous moment", a.to, b.from)
        for (s in shots) assertTrue(s.to == s.from || s.to == s.from + 1)
        assertEquals(shots.size, shots.map { it.key }.toSet().size)
    }

    @Test fun theApplesAddUpAndTheBoardIsRight() {
        // Never more than one new apple in a shot, never one lost.
        for (s in MathEpisode.SHOTS) assertTrue(MathEpisode.FRAMES[s.to].apples - MathEpisode.FRAMES[s.from].apples in 0..1)
        assertEquals(2, MathEpisode.FRAMES.last().apples)
        assertEquals("1 + 1 = 2", MathEpisode.SHOTS.last().board)
        // The board only grows (written on, never wiped mid-film once written).
        val boards = MathEpisode.SHOTS.map { it.board }.filter { it.isNotEmpty() }
        for ((a, b) in boards.zipWithNext()) assertTrue("$a -> $b", b.startsWith(a))
        assertEquals(setOf(1, 2), MathEpisode.applesDuring(MathEpisode.SHOTS[4]))
        assertTrue(MathEpisode.nextPrompt(5).contains("Exactly 2 big red apples"))
        assertTrue(MathEpisode.nextPrompt(1).contains("NO apples"))
    }

    @Test fun anAppleOutsideWhatsAllowedIsFlagged() {
        val s = (0L..3000L step 500L).map { t -> t to if (t in 1000L..1500L) 3 else 2 }
        assertEquals(listOf(1000L..1500L), SceneFlags.outside(s, setOf(1, 2)))
        assertTrue(SceneFlags.outside((0L..3000L step 500L).map { it to if (it < 1500) 1 else 2 }, setOf(1, 2)).isEmpty())
    }
}
