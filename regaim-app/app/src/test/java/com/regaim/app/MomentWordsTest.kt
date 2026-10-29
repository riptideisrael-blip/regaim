package com.regaim.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MomentWordsTest {
    private fun memory(people: String = "", extra: String = "", analyzed: Boolean = true, source: String = "sampled_images", byUser: Boolean = false) = Memory(
        id = "m", uri = "content://m", name = "m.mp4", time = 1L, label = "Laughter", lat = null, lon = null, place = "", thumb = "", durationMs = 1L,
        description = "רואים ילד יושב ליד שולחן", title = "ילד ליד שולחן", reason = "הילד צוחק", analyzed = analyzed,
        people = people, extraPeople = extra, descriptionSource = source, descriptionByUser = byUser,
    )

    @Test fun onlyKnownPeopleGetWords() {
        assertFalse(MomentWords.named(memory()))
        assertTrue(MomentWords.named(memory(people = "נעם")))
        assertTrue(MomentWords.named(memory(extra = "אורי"))) // marked by the parent
    }

    @Test fun oldUnnamedWordsAreClearedButNotTheParents() {
        val m = memory()
        assertTrue(MomentWords.shouldClear(m))
        val c = MomentWords.clear(m)
        assertEquals("", c.description); assertEquals("", c.title); assertEquals("", c.reason)
        assertEquals(MomentWords.UNNAMED, c.descriptionSource)
        assertFalse(MomentWords.shouldClear(memory(byUser = true)))
        assertFalse(MomentWords.shouldClear(memory(people = "נעם")))
    }

    @Test fun describedAgainOnceSomeoneIsKnown() {
        val waiting = MomentWords.clear(memory())
        assertFalse(MomentWords.waitingForNames(waiting))
        val tagged = waiting.copy(extraPeople = "נעם")
        assertTrue(MomentWords.waitingForNames(tagged))
        assertEquals(listOf("m"), MemoryStore.toAnalyze(listOf(tagged)).map { it.id })
        assertEquals(emptyList<String>(), MemoryStore.toAnalyze(listOf(waiting)).map { it.id })
    }

    @Test fun personTagsAreDropped() {
        assertTrue(MomentWords.aboutSomeone("ילד"))
        assertTrue(MomentWords.aboutSomeone("Baby"))
        assertFalse(MomentWords.aboutSomeone("ים"))
        assertFalse(MomentWords.aboutSomeone("ארוחת ערב"))
    }
}
