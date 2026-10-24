package com.regaim.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateTest {
    private val sha = "a".repeat(64)
    private fun info(code: Long = 157, parts: List<String> = listOf("v157/moments.apk.000", "v157/moments.apk.001"), h: String = sha) =
        AppUpdate.Info(code, "0.42 · build 57", parts, 52_000_000, h)

    @Test fun onlyANewerVersionIsOffered() {
        assertTrue(AppUpdate.isNewer(info(), 156))
        assertFalse(AppUpdate.isNewer(info(), 157))
        assertFalse(AppUpdate.isNewer(info(), 200))
        assertFalse(AppUpdate.isNewer(null, 1))
    }

    @Test fun aStrangeNoteIsIgnored() {
        assertTrue(AppUpdate.valid(info()))
        assertFalse(AppUpdate.valid(info(parts = emptyList())))
        // A part pointing outside the updates folder (a link, a path up) isn't followed.
        assertFalse(AppUpdate.valid(info(parts = listOf("https://evil.example/x.apk"))))
        assertFalse(AppUpdate.valid(info(parts = listOf("v157/../moments/x"))))
        assertTrue(AppUpdate.valid(info(parts = listOf("b157/moments.apk.000"))))
        assertFalse(AppUpdate.valid(info(parts = listOf("../moments/x"))))
        assertFalse(AppUpdate.valid(info(h = "1234")))
        assertFalse(AppUpdate.valid(info(code = 0)))
    }

    @Test fun size() = assertEquals("45.0 MB", AppUpdate.mb(45L * 1_048_576))
}

class AppUpdateNumberTest {
    @Test fun theBuildsNumber() {
        assertEquals(121, AppUpdate.number("0.42 · build 121"))
        assertEquals(null, AppUpdate.number("0.42 · build"))
        assertEquals(null, AppUpdate.number(null))
    }

    @Test fun notNowMeansADayLater() {
        val day = 24 * 3600_000L
        assertTrue(AppUpdate.askAgain(0, 0, 121, day * 100))           // never declined
        assertFalse(AppUpdate.askAgain(121, day * 100, 121, day * 100 + 3600_000)) // an hour after "not now"
        assertTrue(AppUpdate.askAgain(121, day * 100, 121, day * 101 + 1))         // a day later
        assertTrue(AppUpdate.askAgain(121, day * 100, 122, day * 100 + 60_000))     // a newer one: asked right away
    }
}
