package com.regaim.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class ChildPageTest {
    private val today = LocalDate.of(2026, 10, 26)

    @Test fun birthdayCountdown() {
        assertEquals(0, ChildPage.birthdayInDays("2021-10-26", today))
        assertEquals(1, ChildPage.birthdayInDays("2021-10-27", today))
        assertEquals(47, ChildPage.birthdayInDays("2021-12-12", today))
        // Already passed this year: next year's.
        assertEquals(364, ChildPage.birthdayInDays("2021-10-25", today))
    }

    @Test fun leapDayAndBadInput() {
        assertEquals(LocalDate.of(2027, 2, 28).toEpochDay() - today.toEpochDay(), ChildPage.birthdayInDays("2024-02-29", today)!!.toLong())
        assertNull(ChildPage.birthdayInDays("", today))
        assertNull(ChildPage.birthdayInDays("2021-13-01", today))
        assertNull(ChildPage.birthdayInDays("abc", today))
    }

    @Test fun subtitleShowsTheBirthdayOnlyWhenClose() {
        assertEquals("גיל 5 · יום הולדת בעוד 47 ימים", ChildPage.subtitle("גיל 5", 47))
        assertEquals("גיל 5 · יום הולדת היום! 🎂", ChildPage.subtitle("גיל 5", 0))
        assertEquals("גיל 5", ChildPage.subtitle("גיל 5", 200))
        assertEquals("יום הולדת מחר", ChildPage.subtitle("", 1))
        assertEquals("", ChildPage.subtitle("", null))
    }

    @Test fun mineMatchesWholeNamesOnly() {
        val all = listOf("אורי, אמא", "אוריה", "נעם", " אורי ")
        val got = ChildPage.mine(all, "אורי") { it.split(",") }
        assertEquals(listOf("אורי, אמא", " אורי "), got)
        assertEquals(emptyList<String>(), ChildPage.mine(all, " ") { it.split(",") })
    }

    @Test fun counts() {
        assertEquals("עוד אין רגעים", ChildPage.counts(0, 0))
        assertEquals("רגע אחד", ChildPage.counts(1, 0))
        assertEquals("128 רגעים · 9 אהובים", ChildPage.counts(128, 9))
        assertEquals("3 רגעים · אהוב אחד", ChildPage.counts(3, 1))
    }
}
