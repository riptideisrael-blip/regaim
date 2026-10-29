package com.regaim.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NamedMomentsTest {
    private fun memory(people: String = "", extra: String = "", analyzed: Boolean = true, source: String = "sampled_images", byUser: Boolean = false) = Memory(
        id = "m", uri = "content://m", name = "m.mp4", time = 1L, label = "Laughter", lat = null, lon = null, place = "", thumb = "", durationMs = 1L,
        description = "רואים ילד יושב ליד שולחן", title = "ילד ליד שולחן", reason = "הילד צוחק", analyzed = analyzed,
        people = people, extraPeople = extra, descriptionSource = source, descriptionByUser = byUser,
    )

    @Test fun onlyKnownPeopleGetWords() {
        assertFalse(NamedMoments.named(memory()))
        assertTrue(NamedMoments.named(memory(people = "נעם")))
        assertTrue(NamedMoments.named(memory(extra = "אורי"))) // marked by the parent
    }

    @Test fun oldUnnamedWordsAreClearedButNotTheParents() {
        val m = memory()
        assertTrue(NamedMoments.shouldClear(m))
        val c = NamedMoments.clear(m)
        assertEquals("", c.description); assertEquals("", c.title); assertEquals("", c.reason)
        assertEquals(NamedMoments.UNNAMED, c.descriptionSource)
        assertFalse(NamedMoments.shouldClear(memory(byUser = true)))
        assertFalse(NamedMoments.shouldClear(memory(people = "נעם")))
    }

    @Test fun describedAgainOnceSomeoneIsKnown() {
        val waiting = NamedMoments.clear(memory())
        assertFalse(NamedMoments.waitingForNames(waiting))
        val tagged = waiting.copy(extraPeople = "נעם")
        assertTrue(NamedMoments.waitingForNames(tagged))
        assertEquals(listOf("m"), MemoryStore.toAnalyze(listOf(tagged)).map { it.id })
        assertEquals(emptyList<String>(), MemoryStore.toAnalyze(listOf(waiting)).map { it.id })
    }

    @Test fun personTagsAreDropped() {
        assertTrue(NamedMoments.aboutSomeone("ילד"))
        assertTrue(NamedMoments.aboutSomeone("Baby"))
        assertFalse(NamedMoments.aboutSomeone("ים"))
        assertFalse(NamedMoments.aboutSomeone("ארוחת ערב"))
    }
}
