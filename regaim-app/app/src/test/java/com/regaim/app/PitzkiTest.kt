package com.regaim.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PitzkiTest {
    private val boy = PitzkiKid("אורי", girl = false)
    private val girl = PitzkiKid("מיכל", girl = true)
    private val nobody = PitzkiKid("", girl = false)

    private fun allText(kid: PitzkiKid) = PitzkiScript.lesson(kid).flatMap { it.beats }.joinToString("\n") {
        when (it) { is Beat.Say -> it.text; else -> "" }
    }

    @Test fun scenesInOrderWithStableNames() {
        val s = PitzkiScript.lesson(boy)
        assertEquals(PitzkiScript.SCENES, s.size)
        assertEquals((1..s.size).toList(), s.map { it.n })
        assertEquals(PitzkiScript.KEYS, s.map { it.key })
        assertEquals(10, PitzkiScript.ORDER.size)
        // The scene names stay (a family's drawings are kept); the child is back after every two colors.
        assertEquals(listOf("song", "intro", "green", "brown", "walk", "red", "yellow", "walk2", "gray", "white", "walk3",
            "black", "purple", "walk4", "pink", "blue", "end"), PitzkiScript.KEYS)
        // Pitzki's song and the colors' close-ups are shared; the child is in 6 scenes.
        assertEquals(listOf("intro", "walk", "walk2", "walk3", "walk4", "end"), PitzkiScript.CHILD_KEYS)
        assertTrue(PitzkiScript.KEYS.containsAll(PitzkiScript.CHILD_KEYS))
        assertEquals("end", PitzkiScript.KEYS.last())
    }

    @Test fun onlyTheFirstName() {
        assertEquals("אורי", PitzkiScript.firstName("אורי נתן"))
        assertEquals("נעם", PitzkiScript.firstName("  נעם "))
        assertEquals("", PitzkiScript.firstName(""))
    }

    @Test fun everyColorIsTaughtOnceAndAsked() {
        val beats = PitzkiScript.lesson(boy).flatMap { it.beats }
        for (c in PColor.values()) {
            assertEquals("scene for $c", 1, PitzkiScript.lesson(boy).count { it.color == c })
            assertEquals("asks for $c", if (c in PitzkiScript.REVIEW) 2 else 1, beats.count { it is Beat.Ask && it.color == c })
            assertTrue("word for $c", beats.count { it is Beat.Word && it.color == c } >= 3)
        }
    }

    @Test fun talksToTheChildInTheRightForm() {
        val b = allText(boy)
        val g = allText(girl)
        val n = allText(nobody)
        assertTrue(b.contains("שלום אורי!"))
        assertTrue(b.contains("מוכן?"))
        assertTrue(g.contains("מוכנה?"))
        assertTrue(g.contains("תגידי איתי"))
        assertTrue(n.contains("שלום חברים!"))
        assertTrue(n.contains("תגידו איתי"))
        assertFalse(n.contains(", תגידו")) // no dangling comma without a name
        assertTrue(b.contains("תלחץ על האבן האפורה.")) // a stone is feminine in Hebrew
        assertTrue(b.contains("תלחץ על העלה הירוק."))
        assertTrue(b.contains("תלחץ על הפלמינגו הוורוד.")) // ה before ו doubles it
        assertTrue(b.contains("תלחץ על הענבים הסגולים."))
        assertTrue(b.contains("תלחץ על הציפור הכחולה."))
        assertTrue(b.contains("תלחץ על החיפושית השחורה."))
    }

    @Test fun personalLinesAreMarkedSoRecordingsDontReplaceThem() {
        val says = PitzkiScript.lesson(boy).flatMap { it.beats }.filterIsInstance<Beat.Say>()
        for (s in says) if (s.text.contains("אורי")) assertTrue(s.id, s.personal)
        // Without a name, nothing is personal: every line can be a recording.
        assertTrue(PitzkiScript.lesson(nobody).flatMap { it.beats }.filterIsInstance<Beat.Say>().none { it.personal })
    }

    @Test fun lineIdsAreUnique() {
        val ids = PitzkiScript.lesson(boy).flatMap { it.beats }.filterIsInstance<Beat.Say>().map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test fun praiseAndHelpTexts() {
        assertEquals("כל הכבוד!", PitzkiScript.praise(boy, 0))
        assertEquals(PitzkiScript.praise(boy, 1), PitzkiScript.praise(boy, 5))
        assertTrue(PitzkiScript.praise(girl, 1).contains("אלופה"))
        assertTrue(PitzkiScript.praise(boy, -1).isNotEmpty())
        assertEquals("כמעט! זה אדום. נסי שוב: איפה הירוק?", PitzkiScript.tryAgain(girl, PColor.RED, PColor.GREEN))
    }

    @Test fun hearsTheColorTheChildSays() {
        assertEquals(PColor.GREEN, PitzkiScript.heard(listOf("ירוק")))
        assertEquals(PColor.GREEN, PitzkiScript.heard(listOf("Green")))
        assertEquals(PColor.RED, PitzkiScript.heard(listOf("זה האדום!")))
        assertEquals(PColor.YELLOW, PitzkiScript.heard(listOf("ילו")))
        assertEquals(PColor.PINK, PitzkiScript.heard(listOf("ורוד")))
        assertEquals(PColor.PINK, PitzkiScript.heard(listOf("הוורוד")))
        assertEquals(PColor.BLUE, PitzkiScript.heard(listOf("", "blue")))
        assertEquals(null, PitzkiScript.heard(listOf("ורד"))) // a rose isn't "red"
        assertEquals(null, PitzkiScript.heard(listOf("אני רוצה עוגה")))
        assertEquals(null, PitzkiScript.heard(emptyList()))
    }

    @Test fun trainWorldIsWellFormed() {
        PitzkiScript.worldId = TrainWorld.ID
        try {
            val s = PitzkiScript.lesson(boy)
            assertEquals(TrainWorld.KEYS, s.map { it.key })
            assertTrue(PitzkiScript.KEYS.containsAll(PitzkiScript.CHILD_KEYS))
            assertEquals(TrainWorld.INTRO, PitzkiScript.INTRO_KEY)
            val says = s.flatMap { it.beats }.filterIsInstance<Beat.Say>()
            assertEquals(says.size, says.map { it.id }.toSet().size)
            for (x in says) if (x.text.contains("אורי")) assertTrue(x.id, x.personal)
            // Never two of the child's scenes in a row without the child coming back soon, and the picks are valid.
            for (p in s.flatMap { it.beats }.filterIsInstance<Beat.Pick>()) assertTrue(p.right in p.choices.indices)
            assertTrue(allTextOf(PitzkiScript.lesson(girl)).contains("מוכנה?"))
            // Each talking scene: Pitzki's own (shared), once, with its words.
            val talks = s.flatMap { it.beats }.filterIsInstance<Beat.Talk>()
            assertEquals(TrainWorld.TALK_KEYS, talks.map { it.key }.toSet())
            assertEquals(talks.size, TrainWorld.TALKS.size)
            for (t in TrainWorld.TALKS) {
                assertTrue(t.key, t.key in TrainWorld.KEYS && t.key !in TrainWorld.CHILD_KEYS)
                assertTrue(t.key, t.motion.length <= 300)
            }
        } finally { PitzkiScript.worldId = "forest" }
    }

    @Test fun hearsWordsInHebrew() {
        assertTrue(PitzkiScript.heardAny(listOf("תודה רבה"), listOf("תודה")))
        assertTrue(PitzkiScript.heardAny(listOf("לאט לאט"), listOf("לאט")))
        assertTrue(PitzkiScript.heardAny(listOf("עוצרים"), listOf("עוצר")))
        assertFalse(PitzkiScript.heardAny(listOf("מהר"), listOf("לאט")))
        assertFalse(PitzkiScript.heardAny(emptyList(), listOf("תודה")))
    }

    private fun allTextOf(sc: List<PitzkiScene>) = sc.flatMap { it.beats }.joinToString("\n") { if (it is Beat.Say) it.text else "" }

    @Test fun everyColorSceneListensForTheAnswer() {
        val scenes = PitzkiScript.lesson(boy)
        for (s in scenes.filter { it.color != null }) assertTrue(s.key, s.beats.any { it is Beat.Listen && it.color == s.color })
        assertTrue(PitzkiScript.wrongSaid(girl, PColor.RED, PColor.GREEN).contains("התשובה הנכונה היא ירוק"))
        // Every piece of "almost" is one of Pitzki's prepared lines (said in his voice).
        val lines = PitzkiScript.voiceLines(boy).toSet()
        for (a in PColor.values()) for (b in PColor.values()) {
            assertTrue(lines.containsAll(PitzkiScript.tryAgainParts(boy, a, b)))
            assertTrue(lines.containsAll(PitzkiScript.wrongSaidParts(boy, a, b)))
        }
    }

    /** The film character: clearly a cartoon, eyes not huge, a closed mouth, no brand names, one child. */
    @Test fun heroPromptKeepsTheChild() {
        val p = PitzkiScript.heroPrompt(girl = false, photos = 3)
        org.junit.Assert.assertTrue(p.contains("NOT a photo") && p.contains("not huge"))
        org.junit.Assert.assertTrue(p.contains("Mouth closed"))
        org.junit.Assert.assertTrue(p.contains("a little boy") && p.contains("The 3 images"))
        org.junit.Assert.assertTrue(p.contains("Exactly one child"))
        org.junit.Assert.assertFalse(p.contains("Pixar") || p.contains("Disney"))
        org.junit.Assert.assertTrue(PitzkiScript.heroPrompt(girl = true, photos = 1).contains("The image is a real photo of a child, a little girl"))
    }

    /** Saying the picture's name chooses it; nothing clear (or two names) chooses none. */
    @Test fun choosingByVoice() {
        val labels = listOf("חֲמוֹר", "כֶּלֶב", "תַּרְנְגוֹל")
        org.junit.Assert.assertEquals(2, PitzkiScript.heardChoice(listOf("תרנגול"), labels))
        org.junit.Assert.assertEquals(1, PitzkiScript.heardChoice(listOf("זה הכלב"), labels))
        org.junit.Assert.assertEquals(0, PitzkiScript.heardChoice(listOf("חמור!"), labels))
        org.junit.Assert.assertEquals(-1, PitzkiScript.heardChoice(listOf("פרה"), labels))
        org.junit.Assert.assertEquals(-1, PitzkiScript.heardChoice(listOf("כלב או חמור"), labels))
        org.junit.Assert.assertEquals(-1, PitzkiScript.heardChoice(emptyList(), labels))
    }

    /** A real moment of the week: the child's own, a known thing, said once; other children's moments aren't used. */
    @Test fun realMomentOfTheWeek() {
        val now = 10L * 86_400_000L
        val day = 86_400_000L
        val uri = PitzkiKid("אורי", false)
        val ms = listOf(
            PitzkiMoments.Seen("a", now - day, "ביקור בגן החיות עם סבתא", "נעם"),
            PitzkiMoments.Seen("b", now - 2 * day, "אורי בחווה ליד הפרות", "אורי"),
            PitzkiMoments.Seen("c", now - 20 * day, "יום בים", "אורי"),
        )
        val r = PitzkiMoments.line(ms, uri, now, emptySet())!!
        org.junit.Assert.assertEquals("b", r.second)
        org.junit.Assert.assertTrue(r.first.contains("בחווה"))
        org.junit.Assert.assertNull(PitzkiMoments.line(ms, uri, now, setOf("b"))) // said already; the sea is too old
        org.junit.Assert.assertNull(PitzkiMoments.line(listOf(PitzkiMoments.Seen("d", now, "ימים טובים", "")), uri, now, emptySet()))
        org.junit.Assert.assertNotNull(PitzkiMoments.line(listOf(PitzkiMoments.Seen("e", now, "Playground slide", "")), uri, now, emptySet()))
    }
}
