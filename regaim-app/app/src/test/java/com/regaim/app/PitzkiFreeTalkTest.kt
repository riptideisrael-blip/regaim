package com.regaim.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PitzkiFreeTalkTest {
    private val uri = PitzkiKid("אורי", false)
    private val noa = PitzkiKid("נועה", true)

    @Test fun answersByTopic() {
        assertEquals("pet_dog", PitzkiFreeTalk.reply(listOf("יש לי כלב"), uri, emptyMap()).topic)
        assertEquals("kindergarten", PitzkiFreeTalk.reply(listOf("היום בגן שיחקתי"), uri, emptyMap()).topic)
        assertEquals("me_eat", PitzkiFreeTalk.reply(listOf("פיצקי מה אתה אוהב לאכול"), uri, emptyMap()).topic)
        assertEquals("me_live", PitzkiFreeTalk.reply(listOf("איפה אתה גר"), uri, emptyMap()).topic)
        assertEquals("feel_afraid", PitzkiFreeTalk.reply(listOf("אני מפחד מהחושך"), uri, emptyMap()).topic)
        assertEquals("fallback", PitzkiFreeTalk.reply(listOf("בלה בלה"), uri, emptyMap()).topic)
        // Not repeating himself: the next reply of the same topic.
        val a = PitzkiFreeTalk.reply(listOf("כלב"), uri, emptyMap()).text
        val b = PitzkiFreeTalk.reply(listOf("כלב"), uri, mapOf("pet_dog" to 1)).text
        assertFalse(a == b)
        assertTrue(PitzkiFreeTalk.reply(listOf("שלום"), noa, emptyMap()).text.isNotBlank())
    }

    @Test fun rudeWordsGetAGentleNo() {
        for (w in listOf("אתה טיפש", "סתום", "זה חרא", "בן זונה", "מטומטם"))
            assertEquals(w, "rude", PitzkiFreeTalk.reply(listOf(w), uri, emptyMap()).topic)
        // A cup is not a rude word; and "חם" isn't "חרא".
        assertFalse(PitzkiFreeTalk.rude(listOf("אני רוצה כוס מים")))
        assertFalse(PitzkiFreeTalk.rude(listOf("חם לי היום")))
        // He never says a rude word himself.
        for (line in PitzkiFreeTalk.allLines(uri) + PitzkiFreeTalk.allLines(noa)) assertFalse(line, PitzkiFreeTalk.rude(listOf(line)))
    }

    @Test fun hardTopicsGoToTheParents() {
        assertEquals("ask_parent", PitzkiFreeTalk.reply(listOf("למה יש אזעקה"), uri, emptyMap()).topic)
        assertEquals("ask_parent", PitzkiFreeTalk.reply(listOf("מה הכתובת שלך"), uri, emptyMap()).topic)
        // "where did you put it" isn't about death; needing the toilet isn't rude.
        assertFalse(PitzkiFreeTalk.reply(listOf("איפה שמת את הכדור"), uri, emptyMap()).topic == "ask_parent")
        assertEquals("potty", PitzkiFreeTalk.reply(listOf("אני צריך פיפי"), uri, emptyMap()).topic)
    }
}
