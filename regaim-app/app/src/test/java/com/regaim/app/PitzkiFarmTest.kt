package com.regaim.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PitzkiFarmTest {
    private val boy = PitzkiKid("אורי", false)
    private val girl = PitzkiKid("נועה", true)

    @Test fun farmIsWellFormed() {
        PitzkiScript.worldId = FarmWorld.ID
        try {
            val s = PitzkiScript.lesson(boy)
            assertEquals(FarmWorld.KEYS, s.map { it.key })
            assertEquals(FarmWorld.KID_INTRO, PitzkiScript.INTRO_KEY)
            assertTrue(PitzkiScript.KEYS.containsAll(PitzkiScript.CHILD_KEYS))
            assertEquals(7, FarmWorld.ANIMALS.size)
            // Each animal: its sound first, then Pitzki asks (his video), then the pictures: the right one is that animal.
            for (a in FarmWorld.ANIMALS) {
                val ask = s.first { it.key == a.key }.beats
                assertTrue(a.id, ask[0] is Beat.Sound && ask[1] is Beat.Talk)
                val pick = ask.filterIsInstance<Beat.Pick>().single()
                assertEquals(a.he, pick.choices[pick.right].label)
                assertEquals(2, pick.choices.map { it.label }.toSet().size) // level 1: two pictures (more at the next levels)
                assertTrue(s.indexOfFirst { it.key == a.open } == s.indexOfFirst { it.key == a.key } + 1)
            }
            // The game at the end: three sounds, each with its pictures.
            val game = s.first { it.key == "farm_review" }.beats
            assertEquals(3, game.filterIsInstance<Beat.Sound>().size)
            for (pk in game.filterIsInstance<Beat.Pick>()) assertTrue(pk.right in pk.choices.indices)
            // Pitzki's videos are the same for every child: no name, no boy/girl words.
            for (t in FarmWorld.TALKS) {
                assertTrue(t.key in FarmWorld.KEYS && t.key !in FarmWorld.CHILD_KEYS)
                assertFalse(t.key, t.text.contains("אורי") || t.text.contains("אתה ") || t.text.contains("תנסה"))
            }
            val says = s.flatMap { it.beats }.filterIsInstance<Beat.Say>()
            assertEquals(says.size, says.map { it.id }.toSet().size)
            for (x in says) if (x.text.contains("אורי")) assertTrue(x.id, x.personal)
            assertTrue(PitzkiScript.lesson(girl).flatMap { it.beats }.filterIsInstance<Beat.Say>().any { it.text.contains("מוכנה?") })
            assertTrue(PitzkiTalks.KEYS.containsAll(FarmWorld.TALKS.map { it.key }))
        } finally { PitzkiScript.worldId = "forest" }
    }

    /** Before the farm's pictures are made, every scene that isn't the child's has a picture that comes with the app. */
    @Test fun bundledPicturesCoverTheFarm() {
        val files = java.io.File("src/main/assets/pitzki/farm").takeIf { it.isDirectory } ?: java.io.File("app/src/main/assets/pitzki/farm")
        for (key in FarmWorld.KEYS) {
            val name = FarmWorld.bundled(key)
            if (key in FarmWorld.CHILD_KEYS || key == "song") { assertEquals(key, null, name); continue }
            assertTrue(key, name != null && java.io.File(files, "$name.jpg").exists())
        }
        // The questions never show the animal (only the gate).
        for (a in FarmWorld.ANIMALS) assertEquals("farm_gate", FarmWorld.bundled(a.key))
    }

    /** The spoiled starts are cut only in the animals' coming-out clips, and the donkey's question shows the gate. */
    @Test fun spoiledClipsAreCut() {
        for (a in FarmWorld.ANIMALS) {
            val f = FarmWorld.clipFrom(a.open)
            assertTrue(a.id, f in 0f..0.8f)
            assertEquals(0f, FarmWorld.clipFrom(a.key), 0f) // never the question scene
        }
        assertTrue(FarmWorld.clipFrom("farm_cow_open") > 0f)
        assertEquals(0f, FarmWorld.clipFrom("farm_dog_kid"), 0f)
        assertTrue(FarmWorld.gateInstead("farm_donkey"))
        assertFalse(FarmWorld.gateInstead("farm_donkey_open"))
        assertTrue(FarmWorld.gateInstead(FarmWorld.INTRO) && !FarmWorld.voiceOnly(FarmWorld.INTRO))
        assertFalse(FarmWorld.voiceOnly("farm_cow"))
        assertTrue(FarmWorld.voiceOnly("farm_cat") && FarmWorld.gateInstead("farm_cat"))
    }

    /** After the easy animals come out, the child makes their sound too (any sound counts); the owl's thanks is the child's. */
    @Test fun childMakesTheSound() {
        PitzkiScript.worldId = FarmWorld.ID
        try {
            val s = PitzkiScript.lesson(boy)
            for (a in FarmWorld.ANIMALS) {
                val hears = s.first { it.key == a.open }.beats.filterIsInstance<Beat.Hear>()
                if (a.id in FarmWorld.COPY) {
                    assertEquals(a.id, 1, hears.size)
                    assertTrue(Beat.ANY in hears[0].words && hears[0].hint.endsWith(a.sound))
                } else assertTrue(a.id, hears.isEmpty())
            }
        } finally { PitzkiScript.worldId = "forest" }
        assertFalse(TrainWorld.cutText("train_owl", TrainWorld.talk("train_owl")!!.text).contains("תּוֹדָה"))
        assertTrue(TrainWorld.TALK_END_MS.getValue("train_owl") in 4300L..5500L) // inside the pause before his "thank you"
    }

    /** Pitzki remembers: the missed animal comes back in the game; all known -> new questions and a welcome back. */
    @Test fun pitzkiRemembers() {
        var m = PitzkiMemory.parse("")
        m = PitzkiMemory.record(m, "f_horse_pick", false)
        m = PitzkiMemory.record(m, "f_cow_pick", true)
        assertEquals(m, PitzkiMemory.parse(PitzkiMemory.write(m)))
        val horse = FarmWorld.ANIMALS.indexOfFirst { it.id == "horse" }
        assertEquals(horse, FarmWorld.reviewOrder(m).first())
        assertEquals(3, FarmWorld.reviewOrder(m).distinct().size)
        assertEquals(listOf(1, 3, 0), FarmWorld.reviewOrder(emptyMap()))
        assertFalse(FarmWorld.allKnown(m))
        val all = FarmWorld.ANIMALS.associate { "f_${it.id}_pick" to 1 }
        assertTrue(FarmWorld.allKnown(all))
        PitzkiScript.worldId = FarmWorld.ID
        PitzkiScript.memory = all; PitzkiScript.walksDone = 2
        try {
            val s = PitzkiScript.lesson(boy)
            assertTrue(s.first { it.key == "farm_review" }.beats.any { it is Beat.Pick && it.id == "f_bonus_0" })
            assertTrue(s.first { it.key == FarmWorld.KID_INTRO }.beats.any { it is Beat.Say && it.id == "f_back" })
            assertTrue(s.first { it.key == "farm_end_kid" }.beats.any { it is Beat.Open })
        } finally { PitzkiScript.worldId = "forest"; PitzkiScript.memory = emptyMap(); PitzkiScript.walksDone = 0 }
        PitzkiScript.worldId = FarmWorld.ID
        try { assertTrue(PitzkiScript.lesson(boy).first { it.key == FarmWorld.KID_INTRO }.beats.none { it is Beat.Say && it.id == "f_back" }) }
        finally { PitzkiScript.worldId = "forest" }
    }

    /** The night world: its scenes in order, the child in three, Pitzki's own drawn once (a prompt for each). */
    @Test fun nightIsWellFormed() {
        PitzkiScript.worldId = NightWorld.ID
        try {
            for (k in listOf(boy, girl, PitzkiKid("", false))) {
                val s = PitzkiScript.lesson(k)
                assertEquals(NightWorld.KEYS, s.map { it.key })
                assertEquals(NightWorld.INTRO, PitzkiScript.INTRO_KEY)
                assertTrue(PitzkiScript.KEYS.containsAll(PitzkiScript.CHILD_KEYS))
                for (sc in s) for (b in sc.beats) if (b is Beat.Pick) assertTrue(b.id, b.right in b.choices.indices)
            }
            for (key in NightWorld.KEYS - NightWorld.CHILD_KEYS.toSet() - "song")
                assertTrue(key, NightWorld.sharedPrompt(key).contains("Exactly one Pitzki") && !NightWorld.sharedPrompt(key).contains("Pitzki in a calm forest in the evening"))
            assertTrue(PitzkiScript.voiceLines(boy).any { it.contains("דובי זה חבר נהדר") })
        } finally { PitzkiScript.worldId = "forest" }
    }

    /** Right after Pitzki gets his ticket the child is asked what we say to the owl; the next scene doesn't ask again. */
    @Test fun thanksToTheOwl() {
        PitzkiScript.worldId = TrainWorld.ID
        try {
            val s = PitzkiScript.lesson(boy)
            val owl = s.first { it.key == "train_owl" }.beats
            assertTrue(owl[0] is Beat.Talk)
            assertTrue(owl.any { it is Beat.Say && it.text.contains("מה אומרים לאדון ינשוף") })
            assertTrue(owl.any { it is Beat.Hear && "תודה" in it.words })
            assertTrue(s.first { it.key == "train_ticket" }.beats.none { it is Beat.Hear || (it is Beat.Say && it.text.contains("ינשוף")) })
        } finally { PitzkiScript.worldId = "forest" }
    }

    /** The games world: every drag has its homes; the listening round asks for one that's there; animals have sounds. */
    @Test fun playIsWellFormed() {
        PitzkiScript.worldId = PlayWorld.ID
        try {
            val s = PitzkiScript.lesson(boy)
            assertEquals(PlayWorld.KEYS, s.map { it.key })
            assertEquals(PlayWorld.INTRO, PitzkiScript.INTRO_KEY)
            val drags = s.flatMap { it.beats }.filterIsInstance<Beat.Drag>()
            assertEquals(3, drags.size) // level 1 (big / small from level 2)
            for (d in drags) {
                assertTrue(d.id, d.items.isNotEmpty() && (d.only < 0 || d.only in d.items.indices))
                for (c in d.items) assertTrue(c.label, FarmWorld.ANIMALS.any { it.he == c.label } || c.label in listOf("גדול", "קטן"))
            }
        } finally { PitzkiScript.worldId = "forest" }
    }

    @Test fun numbersCountTo20AndAdd() {
        assertEquals(20, PitzkiScript.NUMBERS.size)
        assertEquals("עשרים", PitzkiScript.NUMBERS[19])
        for (n in 1..20) {
            val (c, r) = NumbersWorld.choices(n)
            assertEquals(n.toString(), c[r].emoji)
            assertTrue(c.all { it.emoji.toInt() in 1..20 } && c.map { it.emoji }.toSet().size == c.size)
        }
        PitzkiScript.worldId = NumbersWorld.ID
        try {
            val counts = ArrayList<Int>()
            for (lv in 1..NumbersWorld.LEVELS) {
                val s = NumbersWorld.lesson(boy, lv)
                assertEquals(NumbersWorld.KEYS, s.map { it.key })
                val beats = s.flatMap { it.beats }
                for (p in beats.filterIsInstance<Beat.Pick>()) assertTrue(p.id, p.right in p.choices.indices)
                for (m in beats.filterIsInstance<Beat.Sum>()) assertTrue(m.id, m.a + m.b <= 10)
                for (t in beats.filterIsInstance<Beat.TapCount>()) assertTrue(t.id, t.n in 1..20)
                counts.add(beats.filterIsInstance<Beat.Count>().single().n)
            }
            assertEquals(listOf(5, 10, 20, 20), counts)
            // The level picks the lesson; every level's lines are ready in his voice.
            PitzkiScript.level = 3
            assertEquals(20, PitzkiScript.lesson(boy).flatMap { it.beats }.filterIsInstance<Beat.Count>().single().n)
            PitzkiScript.level = 1
            assertEquals(5, PitzkiScript.lesson(boy).flatMap { it.beats }.filterIsInstance<Beat.Count>().single().n)
            val lines = PitzkiScript.voiceLines(boy)
            for (lv in 1..NumbersWorld.LEVELS) for (b in NumbersWorld.lesson(boy, lv).flatMap { it.beats }.filterIsInstance<Beat.Say>()) assertTrue(b.text, b.text in lines)
            assertTrue(lines.contains(NumbersWorld.sumHelp(2, 1)))
            // The first sum of every level is shown (taught) before it's asked, the same sum.
            for (lv in 1..NumbersWorld.LEVELS) {
                val beats = NumbersWorld.lesson(boy, lv).flatMap { it.beats }
                val show = beats.filterIsInstance<Beat.ShowSum>().single()
                val ask = beats.filterIsInstance<Beat.Sum>().first()
                assertTrue(beats.indexOf(show) < beats.indexOf(ask))
                assertEquals(show.a to show.b, ask.a to ask.b)
                assertTrue(show.total, show.total in lines && show.look in lines && show.add in lines)
            }
            assertTrue(lines.contains(PitzkiScript.LEVEL_UP))
        } finally { PitzkiScript.worldId = "forest"; PitzkiScript.level = 1 }
        // Answers out loud: the word, or the digit the recognizer writes.
        val (c, _) = NumbersWorld.choices(3)
        val labels = c.map { it.label }; val digits = c.map { it.emoji }
        assertEquals(digits.indexOf("3"), PitzkiScript.heardChoice(listOf("שלוש"), labels, digits))
        assertEquals(digits.indexOf("3"), PitzkiScript.heardChoice(listOf("3"), labels, digits))
        assertEquals(digits.indexOf("4"), PitzkiScript.heardChoice(listOf("זה 4"), labels, digits))
        val (c2, _) = NumbersWorld.choices(11)
        assertEquals(c2.indexOfFirst { it.emoji == "10" }, PitzkiScript.heardChoice(listOf("עשר"), c2.map { it.label }, c2.map { it.emoji }))
        assertEquals(c2.indexOfFirst { it.emoji == "12" }, PitzkiScript.heardChoice(listOf("12"), c2.map { it.label }, c2.map { it.emoji }))
    }

    @Test fun levelsGoUpOnlyWith100() {
        assertEquals(100, PitzkiLevels.score(5, 5))
        assertEquals(80, PitzkiLevels.score(5, 4))
        assertEquals(2, PitzkiLevels.next(1, 4, 5, 5))
        assertEquals(1, PitzkiLevels.next(1, 4, 5, 4))   // 80: stays (never down)
        assertEquals(2, PitzkiLevels.next(2, 4, 5, 0))
        assertEquals(1, PitzkiLevels.next(1, 4, 0, 0))   // no questions: no level up
        assertEquals(4, PitzkiLevels.next(4, 4, 3, 3))   // the top stays the top
        assertEquals(1, PitzkiLevels.max("train"))
        // The farm: 2, 3, then 4 pictures (the right one always among them) and the bonus questions at the top.
        for (lv in 1..FarmWorld.LEVELS) {
            val picks = FarmWorld.lesson(boy, lv).flatMap { it.beats }.filterIsInstance<Beat.Pick>()
            for (p in picks) { assertEquals(p.id, lv + 1, p.choices.size); assertTrue(p.id, p.right in p.choices.indices && p.choices.map { it.label }.toSet().size == p.choices.size) }
            assertEquals(lv >= 3, picks.any { it.id.startsWith("f_bonus") })
        }
        // The games open with the counting (one walk with everything), then the animals.
        PitzkiScript.worldId = PlayWorld.ID
        try {
            val keys = PitzkiScript.lesson(boy).map { it.key }
            assertEquals(PlayWorld.KEYS, keys)
            assertTrue(keys.indexOf("num_count") < keys.indexOf(PlayWorld.INTRO))
            assertEquals(PlayWorld.CHILD_KEYS, keys.filter { it in PitzkiScript.CHILD_KEYS })
        } finally { PitzkiScript.worldId = "forest" }
        // The games: 2, 3, 4 animals home; big / small from level 2.
        for (lv in 1..PlayWorld.LEVELS) {
            val drags = PlayWorld.lesson(boy, lv).flatMap { it.beats }.filterIsInstance<Beat.Drag>()
            assertEquals(minOf(lv + 1, 4), drags.first().items.size)
            assertEquals(lv >= 2, drags.any { it.id == "p_drag3" })
            for (d in drags) assertTrue(d.id, d.only < 0 || d.only in d.items.indices)
        }
        val lines = PitzkiScript.voiceLines(boy)
        for (lv in 1..PlayWorld.LEVELS) for (b in PlayWorld.lesson(boy, lv).flatMap { it.beats }.filterIsInstance<Beat.Say>()) assertTrue(b.text, b.text in lines)
    }
}
