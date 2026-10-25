package com.regaim.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RetrievalTest {
    private fun m(id: String, time: Long, people: String = "", place: String = "", description: String = "", milestone: String = "") = Memory(
        id = id, uri = "", name = "", time = time, label = "Laughter", lat = null, lon = null, place = place, thumb = "", durationMs = 10_000,
        people = people, description = description, milestone = milestone,
    )

    private val day = 86_400_000L
    private val now = 1_791_273_600_000L

    @Test fun onlyTheRelevantFewGoToTheAi() {
        val all = (1..300).map { m("x$it", now - it * day, description = "ארוחת ערב רגילה") } +
            listOf(m("ball", now - 40 * day, people = "נועה", description = "משחקים בבריכת כדורים"), m("sea", now - 90 * day, place = "חוף הים"))
        val p = Retrieval.pick("מתי נועה שיחקה בכדורים?", all, now)
        assertTrue(p.matched)
        assertEquals("ball", p.memories.first().id)
        assertTrue(p.memories.size <= Retrieval.LIMIT)
        assertEquals("sea", Retrieval.pick("מה עשינו בחוף הים", all, now).memories.first().id)
    }

    @Test fun hebrewPrefixesStillMatch() {
        assertTrue("כדורים" in Retrieval.words("ובכדורים"))
    }

    @Test fun firstTimeQuestionsComeOldestFirst() {
        val all = listOf(m("a", now - 10 * day, description = "צועדת לבד"), m("b", now - 100 * day, description = "צועדת לבד"))
        assertEquals("b", Retrieval.pick("מתי היא צועדת לבד בפעם הראשונה", all, now).memories.first().id)
    }

    @Test fun noMatchFallsBackToRecentAndSaysSo() {
        val all = (1..50).map { m("x$it", now - it * day) }
        val p = Retrieval.pick("קוואנטים", all, now)
        assertFalse(p.matched)
        assertEquals("x1", p.memories.first().id)
        assertEquals(Retrieval.LIMIT, p.memories.size)
    }
}
