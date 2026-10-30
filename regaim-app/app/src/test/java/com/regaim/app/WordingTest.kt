package com.regaim.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WordingTest {
    @Test fun shutterSaysWhatATapDoes() {
        assertEquals("צילום תמונה", Wording.shutter(videoMode = false, recording = false))
        assertEquals("התחלת הקלטה", Wording.shutter(videoMode = true, recording = false))
        assertEquals("עצירת ההקלטה", Wording.shutter(videoMode = true, recording = true))
    }

    @Test fun stateIsReadWithTheName() {
        assertEquals("מיקרופון: כבוי", Wording.state("מיקרופון", false))
        assertEquals("מיקרופון: פועל", Wording.state("מיקרופון", true))
    }

    @Test fun developerWordsAreHiddenFromParents() {
        val fb = "נסו שוב"
        assertEquals(fb, Wording.forParent("צריך להריץ את community-v4.sql ב-Supabase", admin = false, fallback = fb))
        assertEquals(fb, Wording.forParent("שגיאה מהשרת (500)", admin = false, fallback = fb))
        assertEquals(fb, Wording.forParent("BILLING_UNAVAILABLE debugMessage", admin = false, fallback = fb))
        assertEquals(fb, Wording.forParent(null, admin = false, fallback = fb))
        assertEquals(fb, Wording.forParent("  ", admin = true, fallback = fb))
    }

    @Test fun plainWordsAndTheAdminSeeTheRealMessage() {
        assertEquals("האימייל או הסיסמה לא נכונים", Wording.forParent("האימייל או הסיסמה לא נכונים", admin = false, fallback = "x"))
        assertEquals("צריך להריץ את print-books.sql", Wording.forParent("צריך להריץ את print-books.sql", admin = true, fallback = "x"))
    }

    @Test fun technicalDetection() {
        assertTrue(Wording.technical("Supabase"))
        assertTrue(Wording.technical("HTTP 403"))
        assertFalse(Wording.technical("הספר בדרך אליכם"))
        assertFalse(Wording.technical("ספרת 3 תפוחים"))
    }

    @Test fun sureInWordsNotPercent() {
        assertEquals("כמעט בטוח", Wording.sure(0.9f))
        assertEquals("די בטוח", Wording.sure(0.73f))
        assertEquals("אולי", Wording.sure(0.4f))
        assertFalse(Wording.sure(0.73f).contains("%"))
    }

    @Test fun backupTipOnlyOnceAndOnlyWhenNeeded() {
        assertTrue(Wording.showBackupTip(hasMoments = true, copiesToGallery = false, answered = false))
        assertFalse(Wording.showBackupTip(hasMoments = false, copiesToGallery = false, answered = false))
        assertFalse(Wording.showBackupTip(hasMoments = true, copiesToGallery = true, answered = false))
        assertFalse(Wording.showBackupTip(hasMoments = true, copiesToGallery = false, answered = true))
    }

    @Test fun digestLineSaysWhoWhenKnown() {
        assertEquals("רוקדים ביחד בסלון · נעם ואבא", Wording.digestLine("רוקדים ביחד בסלון", listOf("נעם", "אבא")))
        assertEquals("רוקדים בסלון", Wording.digestLine("רוקדים בסלון", emptyList()))
        // A name the title already says isn't repeated.
        assertEquals("נעם רוקד בסלון · אורי", Wording.digestLine("נעם רוקד בסלון", listOf("נעם", "אורי")))
        assertEquals("כולם · א, ב וג", Wording.digestLine("כולם", listOf("א", "ב", "ג")))
    }

    @Test fun familyNameInTheAppLanguage() {
        assertEquals("משפחת רביבו", Wording.familyName("The רביבו family"))
        assertEquals("משפחת רביבו", Wording.familyName("משפחת רביבו"))
        assertEquals("המעגל שלנו", Wording.familyName("המעגל שלנו"))
    }

    @Test fun memberRelationPlaceholderSaysWhatToDo() {
        assertEquals("נגיעה לקישור", Wording.memberRelation("בן/בת", "בן/בת"))
        assertEquals("נגיעה לקישור", Wording.memberRelation("", ""))
        assertEquals("אמא", Wording.memberRelation("אמא", "אמא"))
    }

    @Test fun familyNewsNotification() {
        assertEquals("רגע חדש ממיכל" to "נעם בגן", Wording.familyNews(listOf("מיכל" to "נעם בגן")))
        assertEquals("רגע חדש ממיכל" to "לחצו כדי לראות", Wording.familyNews(listOf("מיכל" to "")))
        assertEquals("3 רגעים חדשים במשפחה" to "האחרון ממיכל", Wording.familyNews(listOf("מיכל" to "a", "סבא" to "b", "מיכל" to "c")))
    }
}
