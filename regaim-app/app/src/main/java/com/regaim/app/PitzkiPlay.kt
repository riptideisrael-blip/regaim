package com.regaim.app

/**
 * The games world (ages ~3–5): one walk with everything. It opens with counting ("let's count together"), picking
 * apples into the basket, balloons and the first sums ([NumbersWorld]'s games, on Pitzki's own scenes, made once for
 * every family); then the animals run off and the child drags each one home (its shape), listening ("only the
 * cat!") and big / small; then everyone is home. Only the last two scenes are the child's own: the animals running
 * off (where the dragging happens) and everyone home. Levels: [LEVELS], the counting and the animals together.
 */
object PlayWorld {
    const val ID = "play"
    const val INTRO = "play_intro"

    val KEYS = listOf("song", "num_count", "num_tap", "num_sum", "play_intro", "play_end")
    /** The scenes the child is in: the animals run off (and are taken home there), and everyone home. */
    val CHILD_KEYS = listOf("play_intro", "play_end")

    private fun a(id: String) = FarmWorld.ANIMALS.first { it.id == id }.let { Choice(it.emoji, it.he, 1f) }

    /**
     * Levels (the numbers' four): the animals 1) two home and one to listen for, 2) three, and big / small, 3–4)
     * four, and listening among three.
     */
    val LEVELS: Int get() = NumbersWorld.LEVELS

    fun lesson(kid: PitzkiKid, level: Int = PitzkiScript.level): List<PitzkiScene> {
        val lv = level.coerceIn(1, LEVELS)
        val name = kid.name.trim()
        val nm = kid.named
        val p = nm
        fun g(b: String, gi: String, all: String) = kid.g(b, gi, all)
        val s = ArrayList<PitzkiScene>()
        fun add(key: String, title: String, vararg beats: Beat) { s.add(PitzkiScene(s.size + 1, key, title, null, beats.toList())) }
        add("song", "השיר של פיצקי", Beat.Song(0, 0, PitzkiScript.SONG.zip(PitzkiScript.SONG_AT).map { it.second to it.first }), Beat.Wait(500))
        // First the numbers (it opens with "let's count together").
        for (sc in NumbersWorld.lesson(kid, lv, alone = false)) add(sc.key, sc.title, *sc.beats.toTypedArray())
        // Then the animals run off, and are taken home right there.
        add(INTRO, "החיות ברחו!",
            Beat.Say("p_oops", g("אוי! תראה! כל החיות ברחו מהבתים שלהן!", "אוי! תראי! כל החיות ברחו מהבתים שלהן!", "אוי! תראו! כל החיות ברחו מהבתים שלהן!"), personal = p),
            Beat.Say("p_help", g("תעזור לי להחזיר אותן הביתה?", "תעזרי לי להחזיר אותן הביתה?", "תעזרו לי להחזיר אותן הביתה?"), personal = p),
            Beat.Hear("p_ready", listOf("כן", "בטח", "יאללה", "מוכן", "מוכנה"), ok = "יאללה, מתחילים!", hint = "יאללה, מתחילים!"),
            // 1) Every animal to its own shape.
            Beat.Say("p_r1", g("גרור כל חיה באצבע אל הבית שלה.", "גררי כל חיה באצבע אל הבית שלה.", "גררו כל חיה באצבע אל הבית שלה."), personal = p),
            when (lv) {
                1 -> Beat.Drag("p_drag1a", listOf(a("cow"), a("dog")))
                2 -> Beat.Drag("p_drag1", listOf(a("cow"), a("dog"), a("rooster")))
                else -> Beat.Drag("p_drag1c", listOf(a("cow"), a("dog"), a("rooster"), a("horse")))
            },
            Beat.Say("p_r1_ok${minOf(lv, 3)}", when (lv) { 1 -> "כל הכבוד! הפרה והכלב בבית!"; 2 -> "כל הכבוד! הפרה, הכלב והתרנגול בבית!"; else -> "כל הכבוד! הפרה, הכלב, התרנגול והסוס בבית!" }),
            // 2) Listening: only the one Pitzki says.
            Beat.Say("p_r2", g("עכשיו תקשיב טוב: תחזיר הביתה רק את החתול!", "עכשיו תקשיבי טוב: תחזירי הביתה רק את החתול!", "עכשיו תקשיבו טוב: תחזירו הביתה רק את החתול!"), personal = p),
            if (lv >= 3) Beat.Drag("p_drag2c", listOf(a("sheep"), a("donkey"), a("cat")), only = 2) else Beat.Drag("p_drag2", listOf(a("sheep"), a("cat")), only = 1),
            Beat.Say("p_r2b", "ועכשיו את הכבשה!"),
            Beat.Drag("p_drag2b", listOf(a("sheep")), only = 0),
            // 3) Big and small.
            *(if (lv >= 2) arrayOf<Beat>(
                Beat.Say("p_r3", "הסוס הגדול לבית הגדול, והסוס הקטן לבית הקטן!"),
                Beat.Drag("p_drag3", listOf(Choice("🐴", "גדול", 1.5f), Choice("🐴", "קטן", 0.75f)))) else emptyArray()))
        add("play_end", "כולם בבית!",
            Beat.Celebrate,
            Beat.Say("p_bravo", (if (nm) "כל הכבוד, $name! " else "כל הכבוד! ") + "ספרנו, עשינו חשבון, והחזרנו את כל החיות הביתה!", personal = p),
            Beat.Say("p_bye", if (nm) "נשחק שוב בפעם הבאה, $name!" else "נשחק שוב בפעם הבאה!", personal = p))
        return s
    }

    const val SETTING = "a cozy, sunny storybook farmyard with a red barn, a wooden dog house, a little chicken coop and green grass, warm golden light"

    // The animals are taken home in the opening scene: the two stand on the left, the empty homes on the right
    // (where the app puts the homes' shapes), the middle is open grass (where the animals wait).
    fun childAction(key: String): String = when (key) {
        INTRO -> "In the left third of a sunny farmyard, the child and Pitzki stand side by side, surprised and laughing, hands on their cheeks, " +
            "looking to the right, where the red barn, the dog house and the little chicken coop stand open and empty. The middle of the frame is open grass. No animals anywhere."
        else -> "In a sunny farmyard, the child and Pitzki jump with joy and high-five; behind them a happy cow looks out of the barn, a puppy out of the dog house and a rooster on the coop."
    }

    fun childMotion(key: String): String = when (key) {
        INTRO -> "The child and Pitzki look at the empty homes in surprise, then nod to each other, ready to help; they stay where they are, " +
            "the child points to the barn. No animals appear; the camera stays still."
        else -> "The child and Pitzki jump with joy and high-five; the animals behind them look out happily."
    }

    const val INTRO_FROM_START =
        "Pitzki stands in the farmyard, smiles and beckons with his hand in a 'come with me' gesture toward the right edge of the frame; " +
            "the child walks in from the right and stops next to him. Nobody talks. The child's face, hair and clothes are exactly as in the last frame."
}
