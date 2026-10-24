package com.regaim.app

/**
 * Pitzki remembers a real moment of the week ("I saw you were at the zoo!"): from the words saved with the family's
 * moments (title, tags, description), only a few kind, known things, said in a child's words. Nothing is sent
 * anywhere: it's read on the phone. No Android here (unit-tested).
 */
object PitzkiMoments {
    /** A moment as Pitzki sees it: when, its words, who's in it, its id. */
    data class Seen(val id: String, val time: Long, val words: String, val people: String)

    /** (words that show it, what Pitzki says: to a boy / to a girl / to everyone). */
    private val THINGS: List<Pair<List<String>, Triple<String, String, String>>> = listOf(
        listOf("גן החיות", "גן חיות", "ספארי", "zoo", "safari") to Triple("ראיתי שהיית בגן החיות! איזה כיף!", "ראיתי שהיית בגן החיות! איזה כיף!", "ראיתי שהייתם בגן החיות! איזה כיף!"),
        listOf("חווה", "פינת ליטוף", "farm", "petting") to Triple("ראיתי שהיית בחווה! גם לי יש חווה עם חברים!", "ראיתי שהיית בחווה! גם לי יש חווה עם חברים!", "ראיתי שהייתם בחווה! גם לי יש חווה עם חברים!"),
        listOf("רכבת", "train") to Triple("ראיתי שנסעת ברכבת! גם אני אוהב רכבות!", "ראיתי שנסעת ברכבת! גם אני אוהב רכבות!", "ראיתי שנסעתם ברכבת! גם אני אוהב רכבות!"),
        listOf("ים", "חוף", "beach", "sea") to Triple("ראיתי שהיית בים! שיחקת בחול?", "ראיתי שהיית בים! שיחקת בחול?", "ראיתי שהייתם בים! שיחקתם בחול?"),
        listOf("יום הולדת", "יומולדת", "birthday") to Triple("ראיתי שחגגתם יום הולדת! איזו שמחה!", "ראיתי שחגגתם יום הולדת! איזו שמחה!", "ראיתי שחגגתם יום הולדת! איזו שמחה!"),
        listOf("גן שעשועים", "מגלשה", "נדנדה", "playground", "slide", "swing") to Triple("ראיתי שהיית בגן השעשועים! גלשת במגלשה?", "ראיתי שהיית בגן השעשועים! גלשת במגלשה?", "ראיתי שהייתם בגן השעשועים! גלשתם במגלשה?"),
        listOf("אופניים", "bike", "bicycle") to Triple("ראיתי שרכבת על אופניים! איזה אלוף!", "ראיתי שרכבת על אופניים! איזו אלופה!", "ראיתי שרכבתם על אופניים! איזה אלופים!"),
        listOf("כלב", "dog", "puppy") to Triple("ראיתי שהיית עם כלב השבוע! הוא עשה לך הַב הַב?", "ראיתי שהיית עם כלב השבוע! הוא עשה לך הַב הַב?", "ראיתי שהייתם עם כלב השבוע! הוא עשה לכם הַב הַב?"),
        listOf("חתול", "cat", "kitten") to Triple("ראיתי שהיית עם חתול השבוע! הוא עשה לך מְיָאוּ?", "ראיתי שהיית עם חתול השבוע! הוא עשה לך מְיָאוּ?", "ראיתי שהייתם עם חתול השבוע! הוא עשה לכם מְיָאוּ?"),
        listOf("עוגה", "אפינו", "cake", "baking") to Triple("ראיתי שאפיתם עוגה! ממממ!", "ראיתי שאפיתם עוגה! ממממ!", "ראיתי שאפיתם עוגה! ממממ!"),
    )

    private fun has(text: String, w: String): Boolean {
        val t = " " + text.lowercase().replace(Regex("[^\\p{L}\\s]"), " ") + " "
        val x = w.lowercase()
        // A whole word, also with ה/ב/ל/ו before it ("בחווה", "לים").
        return listOf(" $x ", " ה$x ", " ב$x ", " ל$x ", " ו$x ", " וב$x ", " בה$x ").any { it in t }
    }

    /**
     * What Pitzki says about the newest moment of the last [days] days that shows one of the known things (the
     * child's own moments first: their name in it; then moments with nobody named), and that moment's id; null when
     * there's none, or it was said already ([said]).
     */
    fun line(moments: List<Seen>, kid: PitzkiKid, now: Long, said: Set<String>, days: Int = 7): Pair<String, String>? {
        val recent = moments.filter { now - it.time in 0..days * 86_400_000L && it.id !in said }.sortedByDescending { it.time }
        val name = kid.name.trim()
        val mine = recent.filter { name.isNotEmpty() && (name in it.people || name in it.words) } + recent.filter { it.people.isBlank() }
        for (m in mine.distinct()) {
            val hit = THINGS.firstOrNull { (words, _) -> words.any { has(m.words, it) } } ?: continue
            val (boy, girl, all) = hit.second
            return kid.g(boy, girl, all) to m.id
        }
        return null
    }

    /** Everything Pitzki may say here (made ahead with his voice, so he says it at once). */
    fun allLines(kid: PitzkiKid): List<String> = THINGS.map { (_, t) -> kid.g(t.first, t.second, t.third) }
}
