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

    private fun at(id: String, minute: Long, people: String = "", title: String = "ילד ליד שולחן", descr: String = "רואים ילד") =
        memory(people = people).copy(id = id, time = minute * 60_000L, title = title, description = descr)

    @Test fun guessesComeFromTheSameMinutes() {
        val me = at("x", 100)
        val all = listOf(me, at("a", 95, "אורי, אנבל"), at("b", 104, "אורי"), at("c", 300, "נעם"))
        assertEquals(listOf("אורי", "אנבל"), NamedMoments.suggest(me, all))
        assertEquals(emptyList<String>(), NamedMoments.suggest(at("y", 1000), all))
    }

    @Test fun aSequenceIsChainedByMinutes() {
        val waiting = listOf(at("w1", 100), at("w2", 108), at("w3", 117), at("w4", 140))
        assertEquals(listOf("w1", "w3"), NamedMoments.sameSequence(waiting[1], waiting).map { it.id })
        assertEquals(emptyList<String>(), NamedMoments.sameSequence(waiting[3], waiting).map { it.id })
    }

    @Test fun wordsInTheOtherLanguageAreWrittenAgain() {
        val en = at("e", 1, people = "אורי", title = "Anabel and Uri Natan at Home", descr = "Uri walks")
        assertTrue(NamedMoments.wrongLanguage(en, english = false))
        assertFalse(NamedMoments.wrongLanguage(en, english = true))
        assertFalse(NamedMoments.wrongLanguage(at("h", 1, people = "אורי"), english = false))
        assertFalse(NamedMoments.wrongLanguage(en.copy(titleByUser = true), english = false))
        assertFalse(NamedMoments.wrongLanguage(en.copy(people = ""), english = false)) // nobody known: no words anyway
    }
}
