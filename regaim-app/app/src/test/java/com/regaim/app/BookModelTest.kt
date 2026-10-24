package com.regaim.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BookModelTest {
    private fun face(key: String, group: String = key, side: Int = 320, sharp: Float = 0.8f, yaw: Float = 5f, ageDays: Long = 20, eyes: Float = 0.9f, dist: Float = 0.5f, crowded: Boolean = false) =
        FaceQuality.Stats(key, group, side, sharp, 140f, 0.02f, yaw, 2f, eyes, dist, ageDays, crowded)

    @Test fun aClearRecentFrontalFaceScoresHigh() {
        assertTrue(FaceQuality.score(face("a")) > 0.75f)
    }

    @Test fun blurTinyTurnedOldOrUnsureFacesScoreLower() {
        val base = FaceQuality.score(face("a"))
        assertTrue(FaceQuality.score(face("b", sharp = 0.1f)) < base)
        assertTrue(FaceQuality.score(face("c", side = 90)) < base)
        assertTrue(FaceQuality.score(face("d", yaw = 45f)) < base)
        assertTrue(FaceQuality.score(face("e", ageDays = 500)) < base * 0.5f)
        assertTrue(FaceQuality.score(face("f", dist = 0.8f)) < base)
        assertTrue(FaceQuality.score(face("g", eyes = 0.05f)) < base)
        assertTrue(FaceQuality.score(face("h", crowded = true)) < base)
    }

    @Test fun picksVarietyAndAFrontalFace() {
        val all = listOf(
            face("a1", group = "day1", yaw = 25f), face("a2", group = "day1", yaw = 24f), face("a3", group = "day1", yaw = 26f),
            face("b", group = "day2", yaw = 22f, sharp = 0.75f), face("c", group = "day3", yaw = 3f, sharp = 0.55f),
            face("bad", group = "day4", sharp = 0.05f, side = 80),
        )
        val p = FaceQuality.pick(all, 3)
        assertEquals(3, p.size)
        assertEquals(3, p.map { it.group }.distinct().size)
        assertTrue(p.any { kotlin.math.abs(it.yaw) < 15f })
        assertTrue(p.none { it.key == "bad" })
    }

    @Test fun nothingGoodMeansNothingPicked() {
        assertTrue(FaceQuality.pick(listOf(face("x", sharp = 0.05f, side = 70, ageDays = 900)), 3).isEmpty())
    }

    @Test fun storyIsParsedAndChecked() {
        val ok = """Here: {"title":"אורי והלילה","outfit":"blue pajamas with stars","cover_scene":"A boy in bed with a lamp",
            "pages":[{"text":"א","scene":"s1"},{"text":"ב","scene":"s2"},{"text":"ג","scene":"s3"},{"text":"ד","scene":"s4"},{"text":"ה","scene":"s5"},{"text":"ו","scene":"s6"}]}"""
        val s = BookJson.story(ok)
        assertNotNull(s)
        assertEquals(6, s!!.pages.size)
        assertEquals("blue pajamas with stars", s.outfit)
        assertNull(BookJson.story("""{"title":"x","pages":[{"text":"a","scene":"b"}]}"""))
        assertNull(BookJson.story("no json at all"))
        assertNull(BookJson.story("""{"title":"","pages":[]}"""))
    }

    @Test fun ageLines() {
        assertEquals("3-year-old boy", BookJson.ageDesc(40, false))
        assertEquals("toddler girl of about 18 months", BookJson.ageDesc(18, true))
        assertEquals("young child", BookJson.ageDesc(null, null))
        assertTrue(BookJson.wordsPerPage(20) < BookJson.wordsPerPage(60))
    }

    @Test fun bookRoundTrip() {
        val b = Book("id1", "נועה", "3-year-old girl", "dark", "he", "נועה והלילה", "pink pajamas",
            BookPage("כריכה", "cover scene", "cover.jpg", "done"),
            listOf(BookPage("טקסט", "scene", "p1.jpg", "done", 1), BookPage("עוד", "scene 2")),
            character = "character.jpg", characterTries = 2, state = "pages", photoSources = listOf("m1", "m2"),
            forgotten = mapOf("req-1" to true, "req-2" to false), charged = true,
            cast = listOf(CastMember("father", "אבא", "the child's father", "self", "ref_father.jpg", "sheet_father_0.jpg", 1)),
            dedication = "לאורי, באהבה")
        val back = BookJson.fromJson(BookJson.toJson(b))
        assertEquals(b, back)
    }

    @Test fun castAndWhoIsOnEachPage() {
        val text = """{"title":"נעם והמוצץ","dedication":"לנעם, באהבה מאבא","outfit":"red overalls","cover_scene":"c","cover_cast":["child","father"],
            "cast":[{"id":"father","name":"אבא","who":"the child's father, a man","family_id":"self"},{"id":"Big Sister!","name":"מאיה","who":"older sister"},
                    {"id":"child","name":"x","who":"y"},{"id":"dog","name":"","who":"a dog"}],
            "pages":[{"text":"א","scene":"s1","cast":["child","father"]},{"text":"ב","scene":"s2","cast":["child","ghost"]},
                     {"text":"ג","scene":"s3"},{"text":"ד","scene":"s4","cast":["bigsister","child"]},{"text":"ה","scene":"s5","cast":["child"]}]}"""
        val s = BookJson.story(text)!!
        assertEquals(listOf("father", "bigsister"), s.cast.map { it.id })
        assertEquals("self", s.cast[0].memberId)
        assertEquals(listOf("child", "father"), s.pageCast[0])
        assertEquals(listOf("child"), s.pageCast[1]) // an unknown id is dropped
        assertEquals(emptyList<String>(), s.pageCast[2])
        assertEquals("לנעם, באהבה מאבא", s.dedication)
        assertEquals(listOf("child", "father"), s.coverCast)
    }

    @Test fun everyThemeHasGuidance() {
        assertEquals(6, BookThemes.ALL.size)
        assertTrue(BookThemes.ALL.all { it.guidance.length > 80 })
        assertEquals("dark", BookThemes.of("dark").key)
    }

    private fun book(ded: String = "ל-אורי") = Book("b1", "אורי", "3-year-old boy", "night", "he", "t", "o",
        BookPage("t", "s", image = "cover_0.jpg", status = "done"),
        (1..3).map { BookPage("p$it", "s", image = "page_${it}_0.jpg", status = "done") }, dedication = ded)

    @Test fun everyPageMustBeApprovedBeforePrint() {
        var b = book()
        assertEquals(0 to 5, BookReview.count(b)) // cover + dedication + 3 pages
        for (i in listOf(0, -1, 1, 2)) b = BookReview.approve(b, i, true)
        assertTrue(!BookReview.allApproved(b))
        b = BookReview.approve(b, 3, true)
        assertTrue(BookReview.allApproved(b))
        // No dedication page: nothing to approve there.
        assertEquals(0 to 4, BookReview.count(book(ded = "")))
    }

    @Test fun aNewDrawingKeepsTheOldOneAndNeedsApprovalAgain() {
        val p = BookPage("x", "s", image = "page_1_0.jpg", status = "done", tries = 1, ok = true, print = "print_1_0.jpg")
        val n = BookReview.drawn(p, "page_1_1.jpg")
        assertEquals("page_1_1.jpg", n.image)
        assertEquals("page_1_0.jpg", n.prev)
        assertTrue(!n.ok)
        assertEquals("", n.print)
        val back = BookReview.swapBack(n)
        assertEquals("page_1_0.jpg", back.image)
        assertEquals("page_1_1.jpg", back.prev) // and it can be undone
        assertTrue(!back.ok)
    }

    @Test fun reviewSurvivesSaving() {
        var b = BookReview.approve(book(), -1, true)
        b = b.copy(pages = b.pages.mapIndexed { k, p -> if (k == 0) BookReview.drawn(p, "page_1_1.jpg").copy(ok = true, print = "print_1_1.jpg") else p })
        val again = BookJson.fromJson(BookJson.toJson(b))
        assertTrue(again.dedicationOk)
        assertEquals("page_1_0.jpg", again.pages[0].prev)
        assertTrue(again.pages[0].ok)
        assertEquals("print_1_1.jpg", again.pages[0].print)
    }

    @Test fun aRealMomentBecomesOneShortLine() {
        val t = java.util.Calendar.getInstance().apply { set(2026, 2, 14, 12, 0) }.timeInMillis
        val line = BookJson.event(t, "בונים ארמון", "אורי ואבא   בונים\nארמון חול", "חוף אשדוד", "אורי, אבא")
        assertEquals("14.03.2026, חוף אשדוד (אורי, אבא): בונים ארמון - אורי ואבא בונים ארמון חול", line)
        assertTrue(BookJson.event(t, "x", "y".repeat(1000), "", "").length < 360)
    }

    @Test fun theParentsVoiceIsSaved() {
        val b = book().let { it.copy(pages = it.pages.mapIndexed { k, p -> if (k == 1) p.copy(voice = "voice_2_1.m4a") else p }) }
        assertEquals("voice_2_1.m4a", BookJson.fromJson(BookJson.toJson(b)).pages[1].voice)
    }

    @Test fun everyoneTheParentChoseIsInTheBook() {
        val cast = listOf(CastMember("father", "אבא", "dad", "self"), CastMember("mother", "אמא", "mom", "m1"), CastMember("baby", "נעם", "brother", "m2"))
        val pages = List(8) { listOf("child") }
        val chosen = listOf(Triple("self", "אבא", "x"), Triple("m1", "אמא", "x"), Triple("m2", "נעם", "x"), Triple("dog1", "אנבל", "the family's dog, אנבל"))
        val (c, cover, p) = BookCast.ensure(cast, listOf("child"), pages, chosen)
        assertEquals(4, c.size)
        val dog = c.last()
        assertEquals("dog1", dog.memberId); assertEquals("אנבל", dog.name)
        assertTrue(dog.id in cover)
        assertTrue(dog.id in p[7] && dog.id in p[6] && dog.id !in p[0])
        // Nothing missing: nothing changes.
        assertEquals(cast, BookCast.ensure(cast, emptyList(), pages, chosen.take(3)).first)
    }

    @Test fun theAnimatedBookIsSaved() {
        val b = book().let { it.copy(kind = "animated", film = "content://media/1", cover = it.cover.copy(clip = "clip_0_1.mp4"), pages = it.pages.mapIndexed { k, p -> if (k == 0) p.copy(clip = "clip_1_1.mp4") else p }) }
        val back = BookJson.fromJson(BookJson.toJson(b))
        assertEquals("animated", back.kind)
        assertEquals("content://media/1", back.film)
        assertEquals("clip_0_1.mp4", back.cover.clip)
        assertEquals("clip_1_1.mp4", back.pages[0].clip)
        assertEquals("digital", BookJson.fromJson(BookJson.toJson(book())).kind)
    }

    @Test fun storiesTooLongToReadAloudAreCaught() {
        val short = "אורי קם בבוקר. השמש צוחקת."
        val long = (1..60).joinToString(" ") { "מילה" }
        assertEquals(5, BookJson.words(short))
        assertTrue(!BookJson.tooWordy(listOf(short, short, long), 32)) // one long page is fine
        assertTrue(BookJson.tooWordy(listOf(short, long, long), 32))
    }

    @Test fun aClosingQuoteStaysWithItsWord() {
        val t = BookJson.display("״ישנתי בלי מוצץ!״ אמר אורי.")
        assertEquals("״ישנתי בלי מוצץ!\u2060״ אמר אורי.", t)
    }
}
