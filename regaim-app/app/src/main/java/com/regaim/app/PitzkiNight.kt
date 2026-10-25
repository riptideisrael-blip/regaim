package com.regaim.app

/**
 * The fourth world: good night with Pitzki (ages ~3–5). Calm and slow, for the evening: the sun goes to sleep,
 * the moon and the stars, the owl that's awake at night, the fireflies that glow, the dark that keeps everything
 * as it was (a gentle answer to fear of the dark), and the animals going to sleep. Pitzki's scenes alone are shared
 * by every family (drawn once by the admin); the child is in three scenes.
 */
object NightWorld {
    const val ID = "night"
    const val INTRO = "night_intro"

    val KEYS = listOf(
        "song", "night_hello", "night_intro", "night_moon", "night_stars", "night_lantern", "night_owl",
        "night_fireflies", "night_dark", "night_sleep", "night_end",
    )
    /** The scenes the child is in. */
    val CHILD_KEYS = listOf("night_intro", "night_lantern", "night_end")

    fun lesson(kid: PitzkiKid): List<PitzkiScene> {
        val name = kid.name.trim()
        val nm = kid.named
        val p = nm
        fun g(b: String, gi: String, all: String) = kid.g(b, gi, all)
        val s = ArrayList<PitzkiScene>()
        fun add(key: String, title: String, vararg beats: Beat) { s.add(PitzkiScene(s.size + 1, key, title, null, beats.toList())) }
        val moon = Choice("🌙", "ירח", 1.3f); val sun = Choice("☀️", "שמש", 1.3f)
        val firefly = Choice("✨", "גחלילית", 1.3f); val stone = Choice("🪨", "אבן", 1.3f)
        val sleepy = Choice("😴", "עייף", 1.3f); val awake = Choice("😃", "ער", 1.3f)

        add("song", "השיר של פיצקי", Beat.Song(0, 0, PitzkiScript.SONG.zip(PitzkiScript.SONG_AT).map { it.second to it.first }), Beat.Wait(500))
        add("night_hello", "השמש הולכת לישון",
            Beat.Say("n_hello", "ערב טוב! השמש הולכת לישון, והשמיים נהיים כתומים וסגולים."),
            Beat.Say("n_hello2", "היום נגיד יחד לילה טוב לכל החברים שלנו."),
            Beat.Wait(400))
        add(INTRO, "טיול ערב",
            Beat.Say("n_come", (if (nm) "$name, " else "") + g("בוא! בוא איתי לטיול ערב!", "בואי! בואי איתי לטיול ערב!", "בואו! בואו איתי לטיול ערב!"), personal = p),
            Beat.Say("n_ready_q", (if (nm) "$name, " else "") + g("מוכן?", "מוכנה?", "מוכנים?"), personal = p),
            Beat.Hear("n_ready", listOf("כן", "מוכן", "מוכנה", "בטח", "יאללה"), ok = "יופי! הולכים לאט ובשקט.", hint = "יופי! הולכים לאט ובשקט."))
        add("night_moon", "הירח",
            Beat.Say("n_moon", "תראו! הירח עולה בשמיים. הוא מאיר לנו בלילה."),
            Beat.Say("n_moon_q", "מה מאיר לנו בלילה?"),
            Beat.Pick("n_moon_pick", listOf(sun, moon), right = 1),
            Beat.Say("n_moon_is", "נכון! בלילה יש ירח, וביום יש שמש."))
        add("night_stars", "סופרים כוכבים",
            Beat.Say("n_stars", g("איזה הרבה כוכבים! בוא נספור יחד חמישה כוכבים.", "איזה הרבה כוכבים! בואי נספור יחד חמישה כוכבים.", "איזה הרבה כוכבים! בואו נספור יחד חמישה כוכבים."), personal = p),
            Beat.Count(5),
            Beat.Say("n_stars_ok", "כל הכבוד! כוכבים קטנים מנצנצים: טווינקל, טווינקל."))
        add("night_lantern", "הפנס הקטן",
            Beat.Say("n_lantern", (if (nm) "$name, " else "") + g("הנה פנס קטן בשבילך. ", "הנה פנס קטן בשבילך. ", "הנה פנס קטן בשבילכם. ") + "איתו רואים טוב גם בערב.", personal = p),
            Beat.Say("n_lantern_q", g("מה עוזר לך להירגע בלילה?", "מה עוזר לך להירגע בלילה?", "מה עוזר לכם להירגע בלילה?"), personal = p),
            Beat.Open("n_calm", listOf(
                listOf("דובי", "בובה", "דובון", "בובי") to "דובי זה חבר נהדר! הוא שומר עליך כל הלילה.",
                listOf("אמא", "אבא", "חיבוק", "סבתא", "סבא") to "חיבוק מאמא ואבא זה הכי טוב!",
                listOf("אור", "מנורה", "פנס", "לילה") to "מנורה קטנה זה רעיון מצוין!",
                listOf("שיר", "סיפור", "ספר") to "שיר או סיפור לפני השינה, איזה כיף!",
            ), any = "איזה רעיון יפה!", quiet = "לי עוזר לחבק את הכובע שלי ולנשום לאט."))
        add("night_owl", "הינשוף",
            Beat.Say("n_owl", "שמעתם? זה הינשוף! הוא ער בלילה, ואומר: הוּ-הוּ!"),
            Beat.Say("n_owl_copy", g("עכשיו אתה! איך עושה הינשוף?", "עכשיו את! איך עושה הינשוף?", "עכשיו אתם! איך עושה הינשוף?"), personal = p),
            Beat.Hear("n_owl_h", listOf(Beat.ANY), ok = "וואו! בדיוק כמו ינשוף!", hint = g("בוא נעשה יחד: הוּ-הוּ!", "בואי נעשה יחד: הוּ-הוּ!", "בואו נעשה יחד: הוּ-הוּ!")))
        add("night_fireflies", "גחליליות",
            Beat.Say("n_fire", "תראו את האורות הקטנים! אלה גחליליות. הן מאירות כמו פנסים קטנים."),
            Beat.Say("n_fire_q", "מי מאיר בחושך?"),
            Beat.Pick("n_fire_pick", listOf(stone, firefly), right = 1),
            Beat.Say("n_fire_is", "נכון! הגחלילית מאירה. גם בחושך יש הרבה אורות קטנים ויפים."))
        add("night_dark", "החושך",
            Beat.Say("n_dark", "כשנהיה חושך, הכל נשאר בדיוק אותו דבר: העצים, הבית, והצעצועים שלך."),
            Beat.Say("n_dark2", "רק האור הולך לישון. ובבוקר הוא חוזר!"),
            Beat.Say("n_dark_q", "ומה אומרים לחושך?"),
            Beat.Hear("n_dark_h", listOf("לילה", "טוב", "שלום"), ok = "נכון! לילה טוב, חושך!", hint = "אומרים לו: לילה טוב, חושך!"))
        add("night_sleep", "כולם הולכים לישון",
            Beat.Say("n_sleep", "גם הארנב הולך לישון, וגם הציפורים בקן."),
            Beat.Say("n_sleep_q", "בסוף היום, איך מרגישים?"),
            Beat.Pick("n_sleep_pick", listOf(awake, sleepy), right = 1),
            Beat.Say("n_sleep_is", "נכון, עייפים. מפהקים… אהההה…"),
            Beat.Say("n_sleep_bye", g("בוא נגיד לילה טוב לחברים!", "בואי נגיד לילה טוב לחברים!", "בואו נגיד לילה טוב לחברים!"), personal = p),
            Beat.Hear("n_gn", listOf("לילה", "טוב"), ok = "לילה טוב, חברים!", hint = "לילה טוב, חברים!"))
        add("night_end", "לילה טוב",
            Beat.Celebrate,
            Beat.Say("n_bravo", (if (nm) "כל הכבוד, $name! " else "כל הכבוד! ") + "היום ראינו ירח, כוכבים, ינשוף וגחליליות.", personal = p),
            Beat.Say("n_bye", if (nm) "לילה טוב, $name. חלומות מתוקים!" else "לילה טוב. חלומות מתוקים!", personal = p))
        return s
    }

    /** Where the child's scenes take place (said in every drawing). */
    const val SETTING = "a calm magical forest at dusk, a soft purple and blue evening sky, the moon and the first stars, warm lantern light, cozy and not scary at all"

    fun childAction(key: String): String = when (key) {
        INTRO -> "At the edge of a calm forest at sunset, Pitzki and the child stand side by side on a soft path, smiling; the sky is orange and purple, the first stars appear."
        "night_lantern" -> "Pitzki and the child sit together on a soft mossy log in the evening forest; the child holds a small warm glowing lantern with both hands; stars and the moon above; cozy, calm, not scary."
        else -> "The child is tucked into a cozy bed with a soft blanket under a window full of stars and a smiling moon; Pitzki sits at the edge of the bed with a warm little lantern, waving good night softly."
    }

    fun childMotion(key: String): String = when (key) {
        INTRO -> "Pitzki and the child walk slowly together along the evening path, looking up at the sky as the stars appear one by one; calm, slow camera."
        "night_lantern" -> "The lantern glows softly, the child looks at it with wonder and smiles; Pitzki points up at the stars; fireflies drift by slowly."
        else -> "The child snuggles under the blanket and closes the eyes slowly; Pitzki waves good night and the lantern dims gently; stars twinkle outside the window."
    }

    /** The opening animated from Pitzki alone to both together. */
    const val INTRO_FROM_START =
        "Pitzki stands on the evening forest path, smiles and beckons with his hand in a 'come with me' gesture toward the right edge of the frame; " +
            "the child walks in calmly from the right and stops next to him, and they look up at the sky together. Nobody talks. " +
            "The child's face, hair and clothes are exactly as in the last frame."

    /** Pitzki alone, once for every family. */
    fun sharedPrompt(key: String): String = "A single frame from a high-end soft 3D animated children's film for ages 2 to 5. Wide landscape composition. " +
        "The first image is Pitzki, a small forest elf: keep his face, curly chestnut hair, green pointed hat with a leaf, " +
        "leaf-green clothes, brown shoes and proportions exactly the same. Scene: " + when (key) {
            "night_hello" -> "Pitzki stands on a hill at the edge of a calm forest at sunset, waving at the setting sun; the sky is orange, pink and purple."
            "night_moon" -> "A big round friendly glowing full moon rises over a calm forest in a deep blue evening sky; Pitzki stands on a hill pointing up at it, delighted."
            "night_stars" -> "Pitzki lies on his back on soft grass in a clearing, pointing up at a sky full of twinkling stars; exactly five big bright stars stand out clearly."
            "night_owl" -> "A kind, fluffy brown owl with big round eyes sits on a branch in the moonlight; Pitzki stands below, his hand by his ear, listening, smiling."
            "night_fireflies" -> "In a calm dark-blue forest clearing, many tiny warm glowing fireflies float around Pitzki, who holds out his hand as one lands on it, amazed."
            "night_dark" -> "A cozy little wooden elf house in the forest at night, a warm light in its window, the trees calm and friendly in the moonlight; Pitzki sits on the doorstep, relaxed and smiling."
            "night_sleep" -> "In the moonlit forest, a little rabbit sleeps curled up in the grass and two small birds sleep in a nest on a branch; Pitzki tiptoes past, a finger on his lips, yawning."
            else -> "Pitzki in a calm forest in the evening."
        } + " Exactly one Pitzki in the picture (never two elves): no child, no other people. Soft, cozy, calm and never scary: warm light, gentle colors. " +
        "No text, no letters, no numbers, no speech bubbles, no watermark, no borders."

    fun sharedMotion(key: String): String = when (key) {
        "night_hello" -> "The sun slowly sets behind the hills and the sky turns purple; Pitzki waves goodbye to the sun and smiles."
        "night_moon" -> "The moon rises slowly and glows brighter; Pitzki points at it and smiles; a few stars appear."
        "night_stars" -> "The stars twinkle one by one; Pitzki counts them with his finger, smiling."
        "night_owl" -> "The owl blinks slowly, turns its head and opens its beak softly; Pitzki listens with his hand by his ear and laughs quietly."
        "night_fireflies" -> "The fireflies drift slowly around Pitzki, glowing softly; one lands on his open hand and he smiles in wonder."
        "night_dark" -> "The window light glows warmly; Pitzki yawns, stretches and smiles calmly; leaves sway gently in the night breeze."
        "night_sleep" -> "The rabbit breathes slowly in its sleep, the birds snuggle in the nest; Pitzki tiptoes past and yawns."
        else -> "Pitzki smiles calmly."
    } + " Only Pitzki and the animals already in the picture, no other people, no hand coming in. Pitzki's mouth stays in a closed smile. Slow, calm camera."
}
