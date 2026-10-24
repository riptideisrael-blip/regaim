package com.regaim.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FarmNiqqudTest {
    @Test fun addsNiqqudKeepsTheRest() {
        assertEquals("זֶה הַכֶּלֶב! הוּא מְכַשְׁכֵּשׁ בַּזָּנָב מֵרֹב שִׂמְחָה. הַב הַב!", FarmNiqqud.show("זה הכלב! הוא מכשכש בזנב מרוב שמחה. הַב הַב!"))
        assertEquals("אורי, מוּכָן?", FarmNiqqud.show("אורי, מוכן?")) // a name stays as it is
        assertEquals("נָכוֹן מְאוֹד! אַתְּ אַלּוּפָה!", FarmNiqqud.show("נכון מאוד! את אלופה!"))
        assertTrue(FarmNiqqud.show("הוא מעיר את כל החווה").contains("אֶת"))
        assertEquals("אֵיזֶה כֶּלֶב חָמוּד! הוּא אוֹהֵב אוֹתָךְ!", FarmNiqqud.show("איזה כלב חמוד! הוא אוהב אותך!", girl = true))
    }

    /** Every word the farm says to the child has niqqud (names aside). */
    @Test fun theWholeFarmIsCovered() {
        PitzkiScript.worldId = FarmWorld.ID
        // A child who knows them all, back for another walk: the welcome back and the new questions are in too.
        PitzkiScript.memory = FarmWorld.ANIMALS.associate { "f_${it.id}_pick" to 1 }
        PitzkiScript.walksDone = 1
        try {
            val texts = listOf(PitzkiKid("אורי", false), PitzkiKid("נועה", true)).flatMap { k -> PitzkiScript.lesson(k) }.flatMap { s -> s.beats.flatMap { b ->
                when (b) { is Beat.Say -> listOf(b.text); is Beat.Talk -> listOf(b.text); is Beat.Sound -> listOf(b.text)
                    is Beat.Open -> b.answers.map { it.second } + b.any + b.quiet; is Beat.Hear -> listOf(b.ok, b.hint); else -> emptyList() } } } +
                (0..3).map { PitzkiScript.praise(PitzkiKid("אורי", false), it) }
            val bare = Regex("[א-ת]+")
            for (t in texts) {
                val shown = FarmNiqqud.show(t)
                val left = bare.findAll(shown).map { it.value }.filter { w -> w != "אורי" && w != "נועה" && w != "ה" && w != "ממממ" &&
                    Regex("(^|[^א-ת֑-ׇ])" + w + "($|[^א-ת֑-ׇ])").containsMatchIn(shown) }.toList()
                assertTrue("$t -> $left", left.isEmpty())
            }
        } finally { PitzkiScript.worldId = "forest"; PitzkiScript.memory = emptyMap(); PitzkiScript.walksDone = 0 }
    }

    @Test fun niqqudOnlyAdds() {
        val t = "שלום! איזה כיף שבאתם! היום נגלה מי משמיע כל קול."  // (a few words lose a vav, as in children's books: חַוַּת)
        assertEquals(t, FarmNiqqud.show(t).filterNot { it in '֑'..'ׇ' })
        assertFalse(FarmNiqqud.show(t) == t)
    }
}
