package com.regaim.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.TimeZone

class MemoryFactsTest {
    private lateinit var priorTimeZone: TimeZone

    @Before fun fixedTimeZone() {
        priorTimeZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After fun restoreTimeZone() = TimeZone.setDefault(priorTimeZone)

    private fun memory() = Memory(
        id = "fixture", uri = "", name = "", time = 1_791_273_600_000L,
        label = "manual", lat = null, lon = null, place = "", thumb = "", durationMs = 60_000,
    )

    @Test fun storedDateAndTimeAreAnsweredWithoutInference() {
        val answer = MemoryFacts.answer(memory(), "באיזה יום צילמנו את זה?")!!
        assertTrue(answer.contains("6.10.2026"))
        assertTrue(answer.contains("08:00"))
        assertTrue(answer.contains("לפי הזמן שנשמר"))
        assertTrue(MemoryFacts.answer(memory(), "באיזו שעה?")!!.contains("08:00"))
    }

    @Test fun missingDateDoesNotBecomeUnixEpoch() {
        val answer = MemoryFacts.answer(memory().copy(time = 0), "מתי צולם הזיכרון?")!!
        assertTrue(answer.contains("לא נשמר תאריך"))
        assertFalse(answer.contains("1970"))
        assertTrue(MemoryFacts.answer(memory().copy(time = -1), "באיזו שעה?")!!.contains("לא נשמרה שעה"))
    }

    @Test fun importedDateIsNotClaimedToBeCaptureDate() {
        val imported = memory().copy(timeSource = "imported")
        val date = MemoryFacts.answer(imported, "מתי צולם הזיכרון האחרון?")!!
        assertTrue(date.contains("הזיכרון נוסף"))
        assertTrue(date.contains("תאריך הצילום המקורי לא ידוע"))
        assertFalse(date.contains("הזיכרון תועד"))
        val time = MemoryFacts.answer(imported, "באיזו שעה?")!!
        assertTrue(time.contains("שעת הצילום המקורית לא ידועה"))
    }

    @Test fun missingPlaceDoesNotInferPlaceFromCoordinates() {
        val answer = MemoryFacts.answer(memory().copy(lat = 31.8, lon = 34.6), "איפה זה היה?")!!
        assertTrue(answer.contains("לא נשמר מקום"))
        assertFalse(answer.contains("31.8"))
    }

    @Test fun placeUsesStoredName() {
        assertEquals("המקום שנשמר לזיכרון הזה: פארק המשפחה.", MemoryFacts.answer(memory().copy(place = "פארק המשפחה"), "איפה זה היה?"))
    }

    @Test fun missingPeopleAreUnknown() {
        val answer = MemoryFacts.answer(memory(), "מי היה שם?")!!
        assertTrue(answer.contains("לא נשמרו שמות"))
        assertTrue(answer.contains("איני יכולה לדעת"))
    }

    @Test fun hiddenPeopleAreRemovedAndManualNamesHaveTheirOwnProvenance() {
        val answer = MemoryFacts.answer(memory().copy(
            people = "אדם א, אדם ב, אדם א", extraPeople = "אדם א, אדם ג, אדם ב", hiddenPeople = "אדם ב",
        ), "מי היה שם?")!!
        assertTrue(answer.contains("שמות מזיהוי הפנים: אדם א"))
        assertTrue(answer.contains("הזיהוי עשוי לטעות"))
        assertTrue(answer.contains("שמות שהוספת לזיכרון: אדם ג"))
        assertFalse(answer.contains("אדם ב"))
    }

    @Test fun descriptionsAreQuotedAsStoredDescriptions() {
        val description = "המשפחה שיחקה יחד בחצר."
        assertEquals("לפי התיאור שנשמר לזיכרון: $description", MemoryFacts.answer(memory().copy(description = description), "מה קרה ברגע הזה?"))
    }

    @Test fun noDescriptionIsExplicitlyUnknown() {
        assertTrue(MemoryFacts.answer(memory(), "מה קרה ברגע הזה?")!!.contains("עדיין אין תיאור"))
    }

    @Test fun unsupportedNarrativeQuestionsNeedMoreEvidence() {
        assertNull(MemoryFacts.answer(memory(), "מה עשה הילד בסוף הסרטון?"))
        assertNull(MemoryFacts.answer(memory(), "למה הוא צחק?"))
        assertNull(MemoryFacts.answer(memory(), ""))
    }
}
