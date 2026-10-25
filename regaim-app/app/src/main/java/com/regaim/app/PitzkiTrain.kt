package com.regaim.app

/**
 * The second world: Pitzki's train station (ages ~3). Not English this time: opposites (big / small, fast / slow,
 * long / short, stop / go), patience (waiting in line, breathing while waiting, counting) and accuracy (tapping
 * exactly when the light turns green). Same shape as the forest: Pitzki's song, the child arriving, and the child
 * back every one or two scenes; Pitzki's scenes alone are shared by every family.
 */
object TrainWorld {
    const val ID = "train"
    const val INTRO = "train_intro"

    /** In order: the song, then the child and Pitzki's scenes in turn. */
    val KEYS = listOf(
        "song", "train_hello", "train_intro", "train_big", "train_owl", "train_ticket", "train_line", "train_board",
        "train_light", "train_fast", "train_window", "train_feel", "train_end",
    )
    /** The scenes the child is in (drawn and animated for each child). */
    val CHILD_KEYS = listOf("train_intro", "train_ticket", "train_board", "train_window", "train_end")

    /**
     * Pitzki's talking scenes: a video of Pitzki saying [text] with his lips matched (made in Hedra and imported by
     * the admin, or made here through fal from [image] + his voice + [motion]). The same for every family; played
     * with its own sound. [more]: said right after, in a second video continued from the first one's last frame.
     */
    data class Talk(val key: String, val text: String, val image: String, val motion: String, val more: String = "")

    val TALKS = listOf(
        Talk("train_hello",
            "בּוֹקֶר טוֹב! אֲנִי פִּיצְקִי, וְהַיּוֹם נֵצֵא לְהַרְפַּתְקָה בַּתַּחֲנָה. הִנֵּה הָרַכֶּבֶת שֶׁלָּנוּ!",
            "Pitzki stands on the station platform in the morning, waving hello to the camera, a red and silver double-decker train waiting behind him.",
            "Pitzki waves hello and talks happily to the camera, then turns and points proudly at the double-decker train behind him."),
        Talk("train_big",
            "תִּרְאוּ אֶת הָרַכֶּבֶת הַגְּדוֹלָה! וְהִנֵּה קָרוֹן קָטָן.",
            "Pitzki stands on the platform between a very long, very big red and silver double-decker train and a tiny toy-sized red carriage, pointing at the big train. The difference in size is obvious.",
            "Pitzki points at the big double-decker train, then at the tiny carriage, talking to the camera with a delighted face.",
            more = "גָּדוֹל… קָטָן… אֵיזֶה כֵּיף!"),
        Talk("train_owl",
            "עַכְשָׁו מְחַכִּים בַּתּוֹר בְּסַבְלָנוּת. הִנֵּה הַכַּרְטִיס שֶׁלִּי! תּוֹדָה, אֲדוֹן יַנְשׁוּף!",
            "Pitzki stands at a little wooden ticket window with flower pots; a kind old owl wearing a conductor's cap hands him a golden ticket.",
            "The owl hands Pitzki the golden ticket; Pitzki takes it carefully, smiles and thanks the owl, then shows the ticket to the camera."),
        Talk("train_line",
            "לִפְנֵי הַקַּו הַצָּהֹב עוֹצְרִים וּמְחַכִּים. בַּתַּחֲנָה תָּמִיד מַחֲזִיקִים יָד שֶׁל מְבֻגָּר. כָּכָה שׁוֹמְרִים עַל עַצְמֵנוּ!",
            "Side view of a station platform: the train tracks run along the left, a thick bright yellow safety line is painted along the platform parallel to the tracks, and Pitzki stands on the safe side of it, well away from the tracks, the yellow line clearly between him and the tracks. A red and silver double-decker train approaches slowly in the distance on the tracks.",
            "Pitzki stays standing on the safe side of the yellow line, points down at the line, and talks calmly to the camera; the train arrives slowly on the far side of the line and stops gently. Pitzki never steps over the line."),
        Talk("train_light",
            "אוֹר אָדֹם — עוֹצְרִים! הָאוֹר הַיָּרֹק מַרְשֶׁה לָרַכֶּבֶת לִנְסֹעַ. אָדֹם עוֹצְרִים, יָרֹק נוֹסְעִים!",
            "Pitzki stands beside a friendly simplified railway signal with two large round lights arranged vertically, red on top and green below. Only the red light is lit; the green one is completely dark. Pitzki points at the red light while looking at the camera.",
            "Pitzki points at the red light and makes a small stop gesture, then the red light turns off and only then the green light turns on, and he points at it happily. Never both lights on together."),
        Talk("train_fast",
            "הָרַכֶּבֶת נוֹסַעַת מַהֵר, מַהֵר! וְעַכְשָׁו הִיא מְאַטָּה… לְאַט, לְאַט. מַהֵר וּלְאַט — אֵיזֶה מַסָּע נֶהְדָּר!",
            "A red and silver double-decker train travels through rolling green hills and wildflowers; Pitzki is clearly visible at a large upper-deck window, smiling at the camera, safely inside behind the closed window.",
            "Pitzki talks happily through the train window; the countryside passes quickly, then slowly as the train slows down. His hat stays on."),
        Talk("train_feel",
            "בַּהַתְחָלָה הִתְרַגַּשְׁתִּי, וּקְצָת חָשַׁשְׁתִּי. זֶה בְּסֵדֶר לְפַחֵד קְצָת. מַחֲזִיקִים יָד, נוֹשְׁמִים עָמֹק… וּשְׂמֵחִים!",
            "Close medium shot: Pitzki sits in a soft blue seat inside a double-decker train carriage beside a large window with green hills and flowers, one hand gently over his heart, calm and happy.",
            "Pitzki talks softly, a little nervous at first, puts his hand on his heart, takes one slow calming breath, relaxes and smiles with joy. The scenery outside moves gently."),
    )
    val TALK_KEYS = TALKS.map { it.key }.toSet()
    fun talk(key: String) = TALKS.firstOrNull { it.key == key }

    fun lesson(kid: PitzkiKid): List<PitzkiScene> {
        val name = kid.name.trim()
        val nm = kid.named
        fun g(b: String, gi: String, all: String) = kid.g(b, gi, all)
        val p = nm // lines with the child's form or name are the child's own
        val big = Choice("🚂", "גדול", 1.6f)
        val small = Choice("🚂", "קטן", 0.75f)
        val long = Choice("🚃🚃🚃", "ארוכה", 1f)
        val short = Choice("🚃", "קצרה", 1f)
        val happy = Choice("😊", "שמח", 1.2f)
        val sad = Choice("😢", "עצוב", 1.2f)
        val s = ArrayList<PitzkiScene>()
        fun add(key: String, title: String, vararg beats: Beat) { s.add(PitzkiScene(s.size + 1, key, title, null, beats.toList())) }

        add("song", "השיר של פיצקי", Beat.Song(0, 0, PitzkiScript.SONG.zip(PitzkiScript.SONG_AT).map { it.second to it.first }), Beat.Wait(500))
        fun spoken(key: String) = Beat.Talk(key, TrainWorld.talk(key)!!.let { t -> if (t.more.isEmpty()) t.text else t.text + " " + t.more }.let { cutText(key, it) })
        // Pitzki says who he is first; then he calls the child in and takes their hand.
        add("train_hello", "בוקר טוב בתחנה", spoken("train_hello"), Beat.Wait(400))
        add("train_intro", "מגיעים לתחנה",
            Beat.Say("t_come", (if (nm) "$name, " else "") + g("בוא! בוא איתי לתחנה!", "בואי! בואי איתי לתחנה!", "בואו! בואו איתי לתחנה!"), personal = p),
            Beat.Say("t_hello", (if (nm) "שלום $name! " else "שלום חברים! ") + "היום נוסעים יחד ברכבת!", personal = p),
            Beat.Say("t_ready", g("מוכן?", "מוכנה?", "מוכנים?"), personal = p),
            // (Heard: on at once; a "כן" doesn't wait the whole pause.)
            Beat.Hear("t_ready_h", listOf("כן", "מוכן", "מוכנה", "בטח", "יאללה", "כן כן"), ok = "יאללה!", hint = "יאללה!"),
            Beat.Say("t_tap_train", g("תלחץ על הרכבת, ויוצאים למסע!", "תלחצי על הרכבת, ויוצאים למסע!", "תלחצו על הרכבת, ויוצאים למסע!"), personal = p),
            Beat.Pick("t_train_pick", listOf(Choice("🚆", "יוצאים!", 1.4f)), right = 0),
        )
        add("train_big", "גדול וקטן",
            spoken("train_big"),
            Beat.Say("t_big_ask", "איפה הגדול? " + g("תלחץ על הגדול!", "תלחצי על הגדול!", "תלחצו על הגדול!"), personal = p),
            Beat.Pick("t_big_pick", listOf(small, big), right = 1),
            Beat.Say("t_small_ask", "ועכשיו: איפה הקטן?"),
            Beat.Pick("t_small_pick", listOf(big, small), right = 1),
        )
        // Pitzki gets his ticket, and right then asks the child what we say to the owl (his own "thank you" is cut).
        add("train_owl", "בקופה עם הינשוף", spoken("train_owl"),
            Beat.Say("t_turn", g("מה אומרים לאדון ינשוף?", "מה אומרים לאדון ינשוף?", "מה אומרים לאדון ינשוף?")),
            Beat.Hear("t_thanks", listOf("תודה", "תודה רבה", "תנקיו"),
                ok = "נכון! תודה רבה, אדון ינשוף! " + g("איזה ילד מנומס!", "איזו ילדה מנומסת!", "איזה ילדים מנומסים!"),
                hint = "אומרים: תודה, אדון ינשוף!"))
        add("train_ticket", "הכרטיס שלנו",
            Beat.Say("t_count", g("עכשיו התור שלנו! בוא נספור יחד עד חמש.", "עכשיו התור שלנו! בואי נספור יחד עד חמש.", "עכשיו התור שלנו! בואו נספור יחד עד חמש."), personal = p),
            Beat.Count(5),
            Beat.Say("t_ticket_ok", "הנה הכרטיס שלנו! יוצאים לדרך."),
        )
        add("train_line", "הקו הצהוב",
            spoken("train_line"),
            Beat.Say("t_line_ask", "מה עושים לפני הקו הצהוב?"),
            Beat.Hear("t_line_say", listOf("עוצרים", "עוצר", "לעצור", "עצור", "מחכים", "מחכה", "לחכות"),
                ok = "נכון! לפני הקו הצהוב עוצרים ומחכים.", hint = "לפני הקו הצהוב עוצרים!"),
            Beat.Say("t_waited", g("כל הכבוד! חִיכִּיתָ יפה מאוד.", "כל הכבוד! חִיכִּית יפה מאוד.", "כל הכבוד! חיכיתם יפה מאוד."), personal = p),
        )
        add("train_board", "עולים לרכבת",
            Beat.Say("t_board", "עולים לרכבת. מחזיקים יד חזק!"),
            Beat.Say("t_seat", "איזה כיף, יש לנו מקום ליד החלון!"),
            Beat.Wait(800),
        )
        add("train_light", "רמזור",
            spoken("train_light"),
            Beat.Say("t_light_tap", g("כשיהיה ירוק, תלחץ! רק בירוק, לא לפני.", "כשיהיה ירוק, תלחצי! רק בירוק, לא לפני.", "כשיהיה ירוק, תלחצו! רק בירוק, לא לפני."), personal = p),
            Beat.Light,
        )
        add("train_fast", "מהר ולאט",
            spoken("train_fast"),
            Beat.Say("t_slow_ask", "יש עלייה גדולה. מה אומרים לרכבת? מהר, או לאט?"),
            Beat.Hear("t_slow", listOf("לאט", "לאאט", "לאט לאט"), ok = "נכון! לאט, לאט. ככה נוסעים בזהירות.", hint = "בעלייה אומרים לה: לאט!"),
            Beat.Say("t_fast_ask", "ועכשיו הדרך ישרה, אפשר שוב מהר! " + g("תגיד", "תגידי", "תגידו") + ": מהר!", personal = p),
            Beat.Hear("t_fast_say", listOf("מהר", "מהר מהר"), ok = "מהר, מהר! איזה כיף!", hint = "מהר!"),
        )
        add("train_window", "ליד החלון",
            Beat.Say("t_cows", g("תראה כמה פרות! בוא ננופף להן.", "תראי כמה פרות! בואי ננופף להן.", "תראו כמה פרות! בואו ננופף להן."), personal = p),
            Beat.Say("t_long_ask", "איפה הרכבת הארוכה?"),
            Beat.Pick("t_long_pick", listOf(short, long), right = 1),
        )
        add("train_feel", "מתרגשים ושמחים",
            spoken("train_feel"),
            Beat.Say("t_happy_ask", "איפה הפרצוף השמח? " + g("תלחץ על השמח!", "תלחצי על השמח!", "תלחצו על השמח!"), personal = p),
            Beat.Pick("t_happy_pick", listOf(sad, happy), right = 1),
            Beat.Say("t_sad_ask", "ועכשיו: איפה הפרצוף העצוב?"),
            Beat.Pick("t_sad_pick", listOf(happy, sad), right = 1),
        )
        add("train_end", "הגענו",
            Beat.Say("t_arrive", "הגענו לתחנה! איזה מסע!"),
            Beat.Say("t_check", g("בוא נבדוק מה זכרנו! מה עושים ברמזור אדום?", "בואי נבדוק מה זכרנו! מה עושים ברמזור אדום?", "בואו נבדוק מה זכרנו! מה עושים ברמזור אדום?"), personal = p),
            Beat.Hear("t_red", listOf("עוצרים", "עוצר", "לעצור", "עצור", "עוצרת"), ok = "נכון! ברמזור אדום עוצרים!", hint = "ברמזור אדום עוצרים!"),
            Beat.Say("t_wait_ask", "ומה עושים כשמחכים?"),
            Beat.Hear("t_patience", listOf("נושמים", "לנשום", "מחכים", "לחכות", "סבלנות", "סופרים"), ok = "נכון! מחכים בסבלנות.", hint = "מחכים בסבלנות, אחד אחרי השני."),
            Beat.Celebrate,
            Beat.Say("t_learned", "היום למדנו: גדול וקטן, מהר ולאט, שמח ועצוב, ואיך מחכים בסבלנות."),
            Beat.Say("t_fav_q", g("ומה הכי אהבת במסע?", "ומה הכי אהבת במסע?", "ומה הכי אהבתם במסע?"), personal = p),
            Beat.Open("t_fav", listOf(
                listOf("רכבת", "הרכבת", "קרון", "קומתיים") to "גם אני! הרכבת הגדולה עם שתי הקומות!",
                listOf("ינשוף", "הינשוף", "כרטיס", "הכרטיס") to "גם אני! הינשוף והכרטיס הזהב!",
                listOf("פרות", "פרה", "חלון", "החלון") to "גם אני! לנופף לפרות מהחלון!",
                listOf("רמזור", "אור", "אדום", "ירוק") to "גם אני! הרמזור האדום והירוק!",
                listOf("מהר", "לאט") to "גם אני! מהר, מהר... ולאט, לאט!",
            ), any = "איזה יופי! גם אני נהניתי מאוד!", quiet = "אני הכי אהבתי לנסוע איתך!"),
            Beat.Say("t_bye", if (nm) "נתראה בנסיעה הבאה, $name!" else "נתראה בנסיעה הבאה!", personal = p),
        )
        return s
    }

    // ---------- the pictures and their motion ----------

    /** Where the child's scenes take place (said in every drawing). */
    const val SETTING = "a cozy, colorful little wooden train station at the edge of a magical forest, soft warm sunlight"

    /** What happens in each of the child's scenes (the drawing). */
    fun childAction(key: String): String = when (key) {
        "train_intro" -> "Pitzki and the child stand together on the platform of a tiny cozy train station, holding hands, a red and silver double-decker train waiting behind them, both smiling."
        "train_ticket" -> "The child stands at a little wooden ticket window with flower boxes, holding up a big golden ticket with both hands, delighted; a kind old owl wearing a conductor's cap smiles from the window (only one owl in the whole picture); Pitzki stands beside the child."
        "train_board" -> "Pitzki and the child stand right at the open door of a red and silver double-decker train carriage, holding hands, facing the door and about to step in: the child's foot is already on the step, warm light inside the carriage."
        "train_window" -> "Pitzki and the child sit side by side by a big window of a double-decker train, waving at three friendly cows on a green hill outside."
        // Questions are asked here (the red light, patience, the favorite): the two face the child watching, then cheer.
        else -> "Pitzki and the child stand side by side on a sunny flower-filled platform next to the parked train, facing the viewer, smiling, as if waiting for an answer."
    }

    /** How each of the child's scenes moves. */
    /**
     * Talking videos played only up to here (ms): at the ticket window Pitzki thanked the owl himself, and then asked
     * the child what we say to the owl (the answer was given away). His "תודה" is cut, and the child says it.
     */
    val TALK_END_MS: Map<String, Long> = mapOf("train_owl" to 4700L)
    private val CUT_TEXT = mapOf("train_owl" to " תּוֹדָה, אֲדוֹן יַנְשׁוּף!")
    fun cutText(key: String, text: String): String = CUT_TEXT[key]?.let { text.replace(it, "") } ?: text

    fun childMotion(key: String): String = when (key) {
        "train_intro" -> "Pitzki and the child stand on the platform hand in hand; Pitzki smiles warmly and points at the train, the child looks at it, excited. The double-decker train waits quietly."
        "train_ticket" -> "The owl hands the golden ticket through the window, the child takes it carefully with both hands and smiles; Pitzki claps softly. Only one owl, no other animals or people appear."
        "train_board" -> "Pitzki and the child, holding hands, step up through the open door into the train carriage and go inside, disappearing into the warm-lit carriage. They move only toward and into the train: they never turn away from it or walk along the platform."
        "train_window" -> "The train moves; the landscape slides past the window; Pitzki and the child wave at the cows, which look up and wave their tails."
        else -> "Pitzki and the child stay standing on the platform facing the viewer; the child thinks for a moment with a finger on the chin, then both smile, nod and cheer with their arms up. They don't walk away; the train stays parked."
    }

    /** The opening animated from Pitzki alone to both together. */
    const val INTRO_FROM_START =
        "Pitzki stands on the station platform reaching his open hand toward the right edge of the frame and calls warmly to someone outside the picture; " +
            "a small child's hand, a 3-year-old's hand no bigger than Pitzki's own, reaches in and takes his hand, and Pitzki gently leads the child into the frame to the middle of the platform, " +
            "where they stop together hand in hand and smile. The child walks in calmly and happily. The child does not talk: the child's mouth " +
            "stays closed in a natural smile. Nobody talks in this shot. The child's face, hair and clothes are exactly as in the last frame."

    /** Pitzki's own scenes (no child), shared by every family. */
    fun sharedPrompt(key: String): String = "A single frame from a high-end soft 3D animated children's film for ages 2 to 4. Wide landscape composition. " +
        "The first image is Pitzki, a small forest elf: keep his face, curly chestnut hair, green pointed hat with a leaf, " +
        "leaf-green clothes, brown shoes and proportions exactly the same. Scene: " + when (key) {
            "train_big" -> "At a cozy little train station, Pitzki stands between a very big red train engine and a tiny toy-sized blue engine, pointing at the big one; the difference in size is obvious."
            "train_line" -> "Pitzki stands calmly on a station platform behind a thick yellow safety line painted on the ground, hands together, waiting; a small train approaches slowly in the distance."
            "train_light" -> "Pitzki stands next to a friendly railway signal light with a big round red light and a big round green light (only the red one glowing now), pointing at it."
            "train_fast" -> "A small red train rushes along a track through green hills and flowers, Pitzki leaning out of the engine window, laughing, his hat fluttering in the wind."
            else -> "Pitzki at a cozy little train station."
        } + " Exactly one Pitzki in the picture (never two elves): no child, no other people. Warm sunlight. " +
        "No text, no letters, no numbers, no signs with writing, no speech bubbles, no watermark, no borders."

    fun sharedMotion(key: String): String = when (key) {
        "train_big" -> "Pitzki looks from the big engine to the tiny one and back, points, and talks to the camera with a delighted face; gentle steam puffs from the big engine."
        "train_line" -> "Pitzki waits calmly behind the yellow line, breathes in deeply and out, smiling; the train slowly arrives and stops gently."
        "train_light" -> "The red light glows, then turns off as the green light turns on; Pitzki points at it and cheers."
        "train_fast" -> "The train speeds through the hills, then slows down climbing a gentle slope; Pitzki holds his hat and laughs."
        else -> "Pitzki talks to the camera with a warm smile."
    } + " Only Pitzki, no other people. Slow smooth camera."
}
