package com.regaim.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HonestyRulesTest {
    private val ctx = HonestyRules.Context(recognized = setOf("נועה"), profileNames = setOf("נועה", "אורי", "Dana"))
    private fun kinds(t: String, c: HonestyRules.Context = ctx) = HonestyRules.check(t, c).map { it.kind }.toSet()

    @Test fun speechThatWasNeverHeard() {
        assertTrue(HonestyRules.Kind.SPEECH in kinds("נועה אמרה \"אני אוהבת אותך\" ורצה."))
        assertTrue(HonestyRules.Kind.SPEECH in kinds("She said: 'look at me'."))
        assertTrue(HonestyRules.Kind.SPEECH in kinds("ואז נשמע ״בוא נשחק עוד״ מהחדר."))
        assertFalse(HonestyRules.Kind.SPEECH in kinds("נועה צוחקת על השטיח."))
        assertTrue(kinds("נועה אמרה \"אני אוהבת אותך\".", HonestyRules.Context(setOf("נועה"), setOf("נועה"), hasTranscript = true)).isEmpty())
    }

    @Test fun profilePeopleWhoWereNotRecognized() {
        assertTrue(HonestyRules.Kind.UNRECOGNIZED_PERSON in kinds("אורי משחק עם הכדור."))
        assertTrue(HonestyRules.Kind.UNRECOGNIZED_PERSON in kinds("נועה מחבקת ואורי מסתכל."))
        assertTrue(HonestyRules.Kind.UNRECOGNIZED_PERSON in kinds("Dana is laughing."))
        assertFalse(HonestyRules.Kind.UNRECOGNIZED_PERSON in kinds("ולנועה יש בלון אדום."))
        assertFalse(HonestyRules.Kind.UNRECOGNIZED_PERSON in kinds("ילד משחק עם אוריגמי."))
    }

    @Test fun firstTimeOnlyWhenMarked() {
        assertTrue(HonestyRules.Kind.FIRST_TIME in kinds("הצעדים הראשונים של נועה!"))
        assertTrue(HonestyRules.Kind.FIRST_TIME in kinds("For the first time she claps."))
        assertFalse(HonestyRules.Kind.FIRST_TIME in kinds("זה הרגע הראשון שתועד עם בלון."))
        assertFalse(HonestyRules.Kind.FIRST_TIME in kinds("לראשונה!", HonestyRules.Context(emptySet(), emptySet(), milestoneMarked = true)))
    }

    @Test fun theEndOfTheVideoWasNotSeen() {
        assertTrue(HonestyRules.Kind.VIDEO_END in kinds("בסוף הסרטון כולם מתחבקים."))
        assertTrue(HonestyRules.Kind.VIDEO_END in kinds("At the end of the video they hug."))
    }

    @Test fun cleanDropsOnlyTheBadSentences() {
        val out = HonestyRules.clean("נועה צוחקת על השטיח. בסוף הסרטון אורי מצטרף. יש הרבה כדורים צבעוניים.", ctx)
        assertEquals("נועה צוחקת על השטיח. יש הרבה כדורים צבעוניים.", out)
        assertEquals("", HonestyRules.clean("אורי אמר \"עוד פעם\".", ctx))
    }
}
