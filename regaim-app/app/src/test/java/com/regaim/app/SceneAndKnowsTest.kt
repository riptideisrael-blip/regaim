package com.regaim.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneAndKnowsTest {
    @Test fun anExtraFaceForAMomentIsFlagged() {
        // Two faces (the child and Pitzki) all along, three around 3–4 seconds.
        val s = (0L..8000L step 500L).map { t -> t to if (t in 3000L..4000L) 3 else 2 }
        assertEquals(listOf(3000L..4000L), SceneFlags.flagged(s, 2))
        // One odd frame alone isn't flagged (a detector's slip).
        assertTrue(SceneFlags.flagged(listOf(0L to 2, 500L to 3, 1000L to 2), 2).isEmpty())
        // Fewer faces (a turned head) isn't a problem.
        assertTrue(SceneFlags.flagged(listOf(0L to 1, 500L to 0, 1000L to 1), 2).isEmpty())
        assertEquals("0:06", SceneFlags.time(6200))
    }

    @Test fun anAppleThatAppearsOrVanishesIsFlagged() {
        // Two apples all along; one vanished at 2–3s, and a single odd frame at 4s isn't flagged.
        val s = (0L..5000L step 500L).map { t -> t to when (t) { in 2000L..3000L -> 1; 4000L -> 3; else -> 2 } }
        assertEquals(listOf(2000L..3000L), SceneFlags.wrongCount(s, 2))
        assertTrue(SceneFlags.wrongCount((0L..5000L step 500L).map { it to 2 }, 2).isEmpty())
        // A third apple that appears (a made-up one) is flagged too.
        assertEquals(listOf(1000L..1500L), SceneFlags.wrongCount(listOf(0L to 2, 1000L to 3, 1500L to 3, 2000L to 2), 2))
    }

    @Test fun whatTheChildKnows() {
        val m = mapOf(PitzkiKnows.colorId(PColor.RED) to 1, PitzkiKnows.colorId(PColor.BLUE) to -1, "f_cow_pick" to 1, "f_game_cow" to 1,
            "f_dog_pick" to -1, "nu_after5" to 1, "p_drag1a_0" to 1)
        val s = PitzkiKnows.sections(m)
        val colors = s[0].items
        assertEquals(PitzkiKnows.State.KNOWN, colors.first { it.label.contains(PColor.RED.he) }.state)
        assertEquals(PitzkiKnows.State.LEARNING, colors.first { it.label.contains(PColor.BLUE.he) }.state)
        assertEquals(PitzkiKnows.State.NOT_YET, colors.first { it.label.contains(PColor.GREEN.he) }.state)
        val animals = s[1].items
        assertEquals(PitzkiKnows.State.KNOWN, animals.first { it.label.contains("פרה") }.state)
        assertEquals(PitzkiKnows.State.LEARNING, animals.first { it.label.contains("כלב") }.state)
        assertEquals(PitzkiKnows.State.KNOWN, s[3].items[0].state)
        assertEquals(PitzkiKnows.State.KNOWN, s[4].items[0].state)
        assertEquals(1, s[0].known)
        // Nothing yet: nothing known, nothing failed.
        assertTrue(PitzkiKnows.sections(emptyMap()).all { sec -> sec.items.all { it.state == PitzkiKnows.State.NOT_YET } })
    }
}
