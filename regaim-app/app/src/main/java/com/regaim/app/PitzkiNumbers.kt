package com.regaim.app

/**
 * The numbers world (ages ~3–5): counting to 10 and on to 20, counting things by touching each one, knowing how
 * many there are, and the first sums with things to see ("two apples and one more apple: how many together?").
 * The child answers by tapping or out loud ("שלוש!", or the recognizer's "3"). Pitzki remembers: after a walk
 * where the counting to 10 went well, the next one counts on to 20.
 */
object NumbersWorld {
    const val ID = "numbers"
    const val INTRO = "num_intro"

    /**
     * Pitzki's own scenes only (made once for every family): an opening and a goodbye with the child only stood
     * there in silence, and cost each family two animations. The hello is said over the counting, the goodbye over
     * the last sum.
     */
    val KEYS = listOf("song", "num_count", "num_tap", "num_sum", "num_more")
    val CHILD_KEYS = emptyList<String>()

    /** One to twenty (feminine, as counting is said), and the words for a number picture. */
    val WORDS = listOf("אחת", "שתיים", "שלוש", "ארבע", "חמש", "שש", "שבע", "שמונה", "תשע", "עשר",
        "אחת־עשרה", "שתים־עשרה", "שלוש־עשרה", "ארבע־עשרה", "חמש־עשרה", "שש־עשרה", "שבע־עשרה", "שמונה־עשרה", "תשע־עשרה", "עשרים")

    /** A number to choose: its digit big, its word under it (heard as the word or as the digit). */
    fun choice(n: Int) = Choice(n.toString(), WORDS[n - 1], 1.2f)

    /** Three numbers to choose from, the right one among them (always the same, so his voice for them is made once). */
    fun choices(right: Int): Pair<List<Choice>, Int> {
        val others = listOf(right - 1, right + 1, right + 2).filter { it in 1..20 && it != right }.take(2)
        val all = (others + right).sorted()
        return all.map { choice(it) } to all.indexOf(right)
    }

    /** What Pitzki says when a sum's answer isn't right yet: counting them all together. */
    fun sumHelp(a: Int, b: Int) = "בוא נספור את כולם ביחד: " + WORDS.take(a + b).joinToString(", ") + "!"

    /**
     * One level: counting out loud to [count], then "what comes after [after]?"; things to count by touching
     * ([taps]: how many, what); the sums ([sums]: a + b, what) and one more of them at the end ([more]).
     */
    data class Level(val count: Int, val after: Int, val taps: List<Pair<Int, String>>, val sums: List<Triple<Int, Int, String>>, val more: List<Triple<Int, Int, String>>)

    /** 1: to 5, 1+1. 2: to 10, sums to 5. 3: to 20, sums to 8. 4: to 20, sums to 10. */
    val LEVEL = listOf(
        Level(5, 2, listOf(3 to "🍎", 5 to "🎈"), listOf(Triple(1, 1, "🍎")), listOf(Triple(2, 1, "🎈"))),
        Level(10, 5, listOf(5 to "🍎", 8 to "🎈"), listOf(Triple(2, 1, "🍎"), Triple(3, 2, "🎈")), listOf(Triple(4, 1, "🚗"))),
        Level(20, 12, listOf(6 to "🍎", 12 to "🎈"), listOf(Triple(3, 2, "🍎"), Triple(4, 3, "🎈")), listOf(Triple(5, 3, "🌼"))),
        Level(20, 15, listOf(7 to "🍎", 15 to "🎈"), listOf(Triple(4, 3, "🍎"), Triple(5, 4, "🎈")), listOf(Triple(6, 3, "🌼"), Triple(7, 3, "🚗"))),
    )
    val LEVELS: Int get() = LEVEL.size

    private val THINGS = mapOf("🍎" to "תפוחים", "⭐" to "כוכבים", "🎈" to "בלונים", "🚗" to "מכוניות", "🌼" to "פרחים")

    /**
     * [alone] = false: the counting part of the games walk ([PlayWorld]): no song, no goodbye, and all the sums in one
     * scene (the walk goes on to the animals).
     */
    fun lesson(kid: PitzkiKid, level: Int = PitzkiScript.level, alone: Boolean = true): List<PitzkiScene> {
        val lv = LEVEL[(level - 1).coerceIn(0, LEVEL.size - 1)]
        val name = kid.name.trim()
        val nm = kid.named
        val p = nm
        fun g(b: String, gi: String, all: String) = kid.g(b, gi, all)
        fun w(n: Int) = WORDS[n - 1]
        val s = ArrayList<PitzkiScene>()
        fun add(key: String, title: String, vararg beats: Beat) { s.add(PitzkiScene(s.size + 1, key, title, null, beats.toList())) }
        fun sum(t: Triple<Int, Int, String>, first: Boolean, teach: Boolean = false): List<Beat> {
            val (a, b, e) = t
            val id = "nu_sum_${a}_$b"
            // The first sum is shown first (the things come together and are counted), and only then asked.
            val show = if (!teach) emptyList() else listOf(
                Beat.Say("nu_teach", g("בוא אלמד אותך משהו חדש: חשבון!", "בואי אלמד אותך משהו חדש: חשבון!", "בואו אלמד אתכם משהו חדש: חשבון!"), personal = p),
                Beat.ShowSum(id + "_show", a, b, e,
                    look = g("תראה, כאן יש ${w(a)}.", "תראי, כאן יש ${w(a)}.", "תראו, כאן יש ${w(a)}."),
                    add = "ועכשיו מוסיפים עוד ${w(b)}. " + g("בוא נספור את כולם ביחד:", "בואי נספור את כולם ביחד:", "בואו נספור את כולם ביחד:"),
                    total = "אז ${w(a)} ועוד ${w(b)} זה ${w(a + b)}!"),
                Beat.Say("nu_teach_now", g("עכשיו תורך!", "עכשיו תורך!", "עכשיו תורכם!"), personal = p),
            )
            return show + listOf(
                Beat.Say(id + "_q", (if (first) "" else "ועכשיו: ") + "${w(a)}, ועוד ${w(b)}. כמה זה ביחד?"),
                Beat.Sum(id, a, b, e),
                Beat.Say(id + "_ok", "נכון! ${w(a)} ועוד ${w(b)} זה ${w(a + b)}."),
            )
        }
        if (alone) add("song", "השיר של פיצקי", Beat.Song(0, 0, PitzkiScript.SONG.zip(PitzkiScript.SONG_AT).map { it.second to it.first }), Beat.Wait(500))
        // Counting out loud together, then what comes next (the hello said right here: no scene of standing around).
        add("num_count", "סופרים עד ${w(lv.count)}",
            Beat.Say("nu_come", (if (nm) "$name, " else "") + g("היום נספור יחד! בוא נספור בקול!", "היום נספור יחד! בואי נספור בקול!", "היום נספור יחד! בואו נספור בקול!"), personal = p),
            Beat.Count(lv.count),
            Beat.Say("nu_count_q${lv.after}", "כל הכבוד! ומה בא אחרי ${w(lv.after)}?"),
            choices(lv.after + 1).let { (c, r) -> Beat.Pick("nu_after${lv.after}", c, r) },
            Beat.Say("nu_count_ok${lv.after}", "נכון! אחרי ${w(lv.after)} בא ${w(lv.after + 1)}."))
        // Counting things: touching each one, then how many.
        val tapBeats = ArrayList<Beat>()
        for ((i, t) in lv.taps.withIndex()) {
            val (n, e) = t
            // Apples are picked from the tree into the basket (the tree has more: we pick up to n); the rest are counted.
            val apples = e == "🍎"
            tapBeats.add(Beat.Say("nu_tap_${e}_$n",
                if (apples) g("בוא נקטוף תפוחים לסל, עד ${w(n)}! תיגע בתפוח על העץ.", "בואי נקטוף תפוחים לסל, עד ${w(n)}! תיגעי בתפוח על העץ.",
                    "בואו נקטוף תפוחים לסל, עד ${w(n)}! תיגעו בתפוח על העץ.")
                else (if (i == 0) "כמה ${THINGS[e]} יש? " else "ועכשיו, כמה ${THINGS[e]}? ") +
                    g("תיגע בכל אחד ונספור.", "תיגעי בכל אחד ונספור.", "תיגעו בכל אחד ונספור."), personal = p))
            tapBeats.add(Beat.TapCount("nu_tap$n", n, e))
            tapBeats.add(Beat.Say(if (apples) "nu_tap_basket" else "nu_tap_q", if (apples) "כמה תפוחים יש בסל?" else "אז כמה יש?"))
            tapBeats.add(choices(n).let { (c, r) -> Beat.Pick("nu_tap${n}_q", c, r) })
            tapBeats.add(Beat.Say("nu_tap_ok$n", "נכון! ${w(n)}."))
        }
        add("num_tap", "סופרים בנגיעה", *tapBeats.toTypedArray())
        // The sums, with things to see.
        val sums = lv.sums.flatMapIndexed { i, t -> sum(t, i == 0, teach = i == 0) }
        if (!alone) {
            add("num_sum", "זה ועוד זה", *(sums + lv.more.flatMap { sum(it, false) }).toTypedArray())
            return s
        }
        add("num_sum", "זה ועוד זה", *sums.toTypedArray())
        add("num_more", "עוד חשבון", *lv.more.flatMap { sum(it, false) }.toTypedArray(),
            Beat.Celebrate,
            Beat.Say("nu_bravo${lv.count}", (if (nm) "כל הכבוד, $name! " else "כל הכבוד! ") + "ספרנו עד ${w(lv.count)} ועשינו חשבון!", personal = p),
            Beat.Say("nu_bye", if (nm) "נספור שוב בפעם הבאה, $name!" else "נספור שוב בפעם הבאה!", personal = p))
        return s
    }

    const val SETTING = "a sunny, colorful storybook orchard with apple trees, flowers and a little wooden table, warm golden light"

    fun childAction(key: String): String = when (key) {
        INTRO -> "In a sunny orchard, Pitzki and the child stand next to an apple tree full of red apples, both smiling, Pitzki holding up three fingers."
        else -> "In a sunny orchard, Pitzki and the child jump with joy and high-five next to a basket full of red apples."
    }

    fun childMotion(key: String): String = when (key) {
        INTRO -> "Pitzki points at the apples one by one as if counting, and the child follows with a finger, smiling."
        else -> "Pitzki and the child jump with joy and high-five; a few apples bounce in the basket."
    }

    const val INTRO_FROM_START =
        "Pitzki stands by the apple tree, smiles and beckons with his hand in a 'come with me' gesture toward the right edge of the frame; " +
            "the child walks in from the right and stops next to him. Nobody talks. The child's face, hair and clothes are exactly as in the last frame."

    /** Pitzki alone, once for every family. */
    fun sharedPrompt(key: String): String = "A single frame from a high-end soft 3D animated children's film for ages 2 to 5. Wide landscape composition. " +
        "The first image is Pitzki, a small forest elf: keep his face, curly chestnut hair, green pointed hat with a leaf, " +
        "leaf-green clothes, brown shoes and proportions exactly the same. Scene: " + when (key) {
            "num_count" -> "Pitzki stands in a sunny orchard holding up both hands with all ten fingers spread, smiling, as if counting out loud."
            // The child touches these apples in the picture (the app finds them): many, big, red, apart from each other.
            "num_tap" -> "Pitzki stands in the left third of the frame in a sunny orchard, turned to the right, pointing at a big leafy apple tree that fills the right half of the frame. " +
                "The tree carries about twelve big, shiny, bright red apples, each one clearly visible against green leaves and well apart from the others (no apples touching, none hidden). " +
                "No apples on the ground, nothing else red in the picture."
            "num_sum" -> "Pitzki sits at a little wooden table in the orchard with a few red apples on it, holding one more apple up, smiling."
            "num_more" -> "Pitzki stands in a flower meadow next to a little toy car, holding a flower in each hand, smiling."
            else -> "Pitzki in a sunny orchard."
        } + " Exactly one Pitzki in the picture (never two elves): no child, no other people, no animals or pets at all. Warm sunlight. " +
        "No text, no letters, no numbers, no digits anywhere, no speech bubbles, no watermark, no borders."

    fun sharedMotion(key: String): String = when (key) {
        "num_count" -> "Pitzki counts on his fingers one by one, nodding with each, smiling."
        "num_tap" -> "Pitzki points at the apples on the tree one by one, nodding as if counting them; the leaves sway gently; the apples stay in their places."
        "num_sum" -> "Pitzki puts the apple he holds down next to the others and smiles proudly."
        "num_more" -> "Pitzki lifts the flowers one after the other and smiles; the toy car rolls a little."
        else -> "Pitzki smiles."
    } + " Only Pitzki, no other people, no animals, no hand coming in. Pitzki's mouth stays in a closed smile. Slow, calm camera."
}
