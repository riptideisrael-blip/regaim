package com.regaim.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PitzkiChatTest {
    private val boy = PitzkiKid("אורי", false)
    private val girl = PitzkiKid("נועה", true)
    private val nobody = PitzkiKid("", false)

    @Test fun bankHasEightSubjectsOfEight() {
        for (t in PitzkiChat.TOPICS) assertTrue(t, PitzkiChat.BANK.count { it.topic == t } >= 8)
        assertEquals(PitzkiChat.BANK.size, PitzkiChat.BANK.map { it.id }.toSet().size)
        for (q in PitzkiChat.BANK) {
            assertTrue(q.id, q.right < q.choices.size)
            assertTrue(q.id, q.level in 1..2)
            if (q.right >= 0) assertTrue(q.id, q.choices.size >= 2)
            // Every level-1 subject can start a talk.
        }
        for (t in PitzkiChat.TOPICS) assertTrue(t, PitzkiChat.BANK.any { it.topic == t && it.level == 1 })
    }

    @Test fun textsAreFormedForEveryChild() {
        for (q in PitzkiChat.BANK) for (k in listOf(boy, girl, nobody)) {
            for (t in listOf(q.ask, q.fact, q.follow)) {
                val f = PitzkiChat.form(k, t, "blue")
                assertFalse("${q.id}: $f", f.contains('[') || f.contains(']') || f.contains('{'))
            }
        }
        assertTrue(PitzkiChat.form(girl, "[אתה/את/אתם] [יודע/יודעת/יודעים]").startsWith("את יודעת"))
        assertTrue(PitzkiChat.form(boy, "{n}איזה צבע").startsWith("אורי, "))
        // No name is written into the bank (the child's name comes from the profile).
        assertFalse(PitzkiChat.BANK.any { it.ask.contains("אורי") || it.fact.contains("אורי") })
    }

    @Test fun answersAreUnderstoodNotGuessed() {
        val banana = PitzkiChat.BANK.first { it.id == "c_banana" }
        assertEquals(0, PitzkiChat.match(banana, listOf("צהוב")))
        assertEquals(0, PitzkiChat.match(banana, listOf("היא צהובה")))
        assertEquals(1, PitzkiChat.match(banana, listOf("אדום")))
        assertEquals(-1, PitzkiChat.match(banana, listOf("אני לא יודע")))
        val alone = PitzkiChat.BANK.first { it.id == "r_alone" }
        assertEquals(0, PitzkiChat.match(alone, listOf("לא")))
        assertTrue(PitzkiChat.replyTo(boy, banana, 0).contains("צהוב"))
        assertTrue(PitzkiChat.replyTo(boy, banana, 1).startsWith("כמעט"))
        val fav = PitzkiChat.BANK.first { it.id == "c_fav" }
        assertTrue(PitzkiChat.replyTo(boy, fav, PitzkiChat.match(fav, listOf("כחול"))).contains("blue"))
    }

    @Test fun aiRepliesAreKeptClean() {
        assertEquals("איזה יופי! גם אני אוהב פיצה.", PitzkiChat.clean("\"איזה יופי! גם אני אוהב פיצה.\""))
        assertEquals("וואו, גם אני!", PitzkiChat.clean("וואו, גם אני! מה עוד אתה אוהב?"))
        assertNull(PitzkiChat.clean("מה הכתובת שלך?"))
        assertNull(PitzkiChat.clean("אתה טיפש"))
        assertNull(PitzkiChat.clean("Great job!"))
        assertNull(PitzkiChat.clean(""))
    }

    @Test fun aTalksLinesAreKnownAhead() {
        val plan = PitzkiChat.plan(emptySet(), java.util.Random(3))
        assertEquals(6, plan.size)
        assertEquals(6, plan.map { it.first.topic }.toSet().size)
        val all = PitzkiChat.allLines(boy).toSet()
        val banana = PitzkiChat.BANK.first { it.id == "c_banana" }
        assertTrue(PitzkiChat.replyTo(boy, banana, 0) in all)
        assertTrue(PitzkiChat.replyTo(boy, banana, 1) in all)
        assertTrue(PitzkiChat.form(boy, banana.ask) in all)
    }

    @Test fun aTalkGoesAroundSubjects() {
        val r = java.util.Random(7)
        val used = HashSet<String>()
        repeat(6) {
            val q = PitzkiChat.pick(used, emptySet(), 1, r)!!
            assertFalse(q.topic in used)
            assertEquals(1, q.level)
            used.add(q.topic)
        }
    }
}
