package com.regaim.app

/**
 * The third world: Pitzki's farm, "who makes that sound?" (ages ~3). Seven animals: first only the animal's sound is
 * heard, Pitzki asks who it is (his talking video, the animal still hidden), the child answers (out loud or by tapping
 * a picture), and only then the animal comes out (its animation, from hidden to seen) and Pitzki says who it was.
 * Pitzki and the animals are the same for every family (made once); the child is drawn into four scenes of their
 * own (arriving, petting the dog, feeding the sheep, goodbye), and a short game of three sounds ends the walk.
 */
object FarmWorld {
    const val ID = "farm"
    const val INTRO = "farm_intro"

    /** An animal: its key, how it's called, how its sound is written, the picture, and the ask / reveal lines. */
    class Animal(
        val id: String, val he: String, val emoji: String, val sound: String,
        /** Pitzki's question in his video (the same for every child: no name, said to everyone). */
        val ask: String,
        /** What he says when it comes out. */
        val reveal: String,
        /** The animal hidden in the picture before it comes out (said to the drawing model). */
        val hidden: String,
        /** How it comes out (the animation from hidden to seen). */
        val comesOut: String,
        /** The sound effect asked from the voice service. */
        val sfx: String,
    ) {
        val key get() = "farm_$id"
        val open get() = "farm_${id}_open"
    }

    /** In the order of the walk: the coop, the barn, the dog house, the hay, the fence, the stable, the pasture. */
    val ANIMALS = listOf(
        Animal("rooster", "תרנגול", "🐓", "קוּקוּרִיקוּ!",
            "שמעתם? מי קורא ככה, קוּקוּרִיקוּ? מי עושה את הקול?",
            "זה התרנגול! הוא מעיר את כל החווה בבוקר. קוּקוּרִיקוּ!",
            "the coop door is closed and the perch in front of it is empty",
            "the colorful rooster hops out onto the wooden perch, spreads its wings a little and crows proudly; Pitzki claps",
            "a rooster crowing cock-a-doodle-doo once, clear, no background"),
        Animal("cow", "פרה", "🐮", "מוּוּוּ!",
            "איזה קול עמוק! מי עושה מוּוּוּ?",
            "זאת הפרה! היא נותנת לנו חלב. מוּוּוּ!",
            "the barn doorway is dark and empty",
            "the friendly black and white cow steps slowly out of the barn, its bell ringing, and moos with a big smile; Pitzki pats it gently",
            "a friendly cow mooing once, long and warm, no background"),
        Animal("dog", "כלב", "🐶", "הַב הַב!",
            "וואו! מי נובח ככה? מי עושה הַב הַב?",
            "זה הכלב! הוא מכשכש בזנב מרוב שמחה. הַב הַב!",
            "the round doorway of the wooden dog house is dark and empty, the bowl is outside",
            "the happy puppy runs out of the dog house, wags its tail and barks joyfully; Pitzki laughs",
            "a happy small dog barking twice, clear, friendly, no background"),
        Animal("cat", "חתול", "🐱", "מְיָאוּ!",
            "שֶׁקֶט שֶׁקֶט... מי עושה מְיָאוּ?",
            "זה החתול! הוא התחבא בחבית. מְיָאוּ!",
            "the opening of the barrel is dark and empty, only straw inside",
            "a little orange and white kitten (a cat: pointed ears, whiskers, a small pink nose) peeks out of the barrel, blinks curiously and meows; Pitzki giggles",
            "a cute small kitten meowing twice, soft, no background"),
        Animal("donkey", "חמור", "🫏", "אִי־אָה!",
            "איזה קול מצחיק! מי עושה אִי־אָה?",
            "זה החמור! יש לו אוזניים ארוכות ארוכות. אִי־אָה!",
            "behind the fence and the barn wall nothing is seen at all (no ears, no head)",
            "the grey donkey comes up to the fence, wiggles its long ears one at a time and brays with its mouth open; Pitzki laughs gently",
            "a funny donkey braying hee-haw twice, no background"),
        Animal("horse", "סוס", "🐴", "הִי־הִי־הִי!",
            "מי צוהל שם, הִי־הִי־הִי? מי עושה את הקול?",
            "זה הסוס! הוא רץ מהר מהר. הִי־הִי־הִי!",
            "the stable is dark and empty behind the fence",
            "the brown horse with a white stripe on its face trots out of the stable to the fence, shakes its mane and neighs gently; Pitzki pats it",
            "a horse neighing once, cheerful, no background"),
        Animal("sheep", "כבשה", "🐑", "מֶהֶה!",
            "מי עושה מֶהֶה? מי מסתתר במרעה?",
            "זאת הכבשה! יש לה צמר רך ונעים. מֶהֶה!",
            "behind the big rocks and tall flowers of the pasture nothing is seen",
            "the fluffy white sheep walks slowly out from behind the rocks, blinks and bleats softly; Pitzki smiles",
            "a gentle sheep bleating twice, soft, no background"),
    )

    fun animal(key: String) = ANIMALS.firstOrNull { key == it.key || key == it.open }

    /** Pitzki's talking videos in the farm: the hello at the gate, and a question for each animal (still hidden). */
    val TALKS: List<TrainWorld.Talk> = listOf(
        TrainWorld.Talk(INTRO,
            "שלום! איזה כיף שבאתם לחוות החברים! היום נגלה יחד מי משמיע כל קול. מקשיבים טוב טוב!",
            "", "Pitzki stands at the farm gate, waves hello happily, bounces a little and points toward the farm, talking excitedly to the camera."),
    ) + ANIMALS.map { a ->
        TrainWorld.Talk(a.key, a.ask, "",
            "Pitzki holds his hand by his ear, listening, then talks to the camera with a curious, excited face and a playful shrug. " +
                "No animal appears at all: nothing comes out or peeks out, the doorways and the barrel stay dark and empty. " +
                "Pitzki keeps his green felt hat on the whole time.")
    }

    /** The scenes with the child (drawn and animated for each child): arriving, petting the dog, feeding the sheep, goodbye. */
    const val KID_INTRO = "farm_intro_kid"
    val CHILD_KEYS = listOf(KID_INTRO, "farm_dog_kid", "farm_sheep_kid", "farm_end_kid")

    val KEYS: List<String> get() = listOf("song", INTRO, KID_INTRO) +
        ANIMALS.flatMap { a -> listOf(a.key, a.open) + (if (a.id == "dog") listOf("farm_dog_kid") else if (a.id == "sheep") listOf("farm_sheep_kid") else emptyList()) } +
        listOf("farm_review", "farm_end_kid")

    /** Three pictures for an animal: it and two others (always the same, so the voice for them is made once). */
    /** Levels: 1) two pictures to choose from, 2) three, 3) four and the bonus questions. */
    const val LEVELS = 3

    /** The animal [i] among [n] pictures (2, 3 or 4: by the level), always in the same places. */
    fun choices(i: Int, n: Int = (PitzkiScript.level + 1).coerceIn(2, 4)): Pair<List<Choice>, Int> {
        val a = ANIMALS[i]
        val others = listOf(2, 4, 1).map { ANIMALS[(i + it) % ANIMALS.size] }.take(n - 1)
        val list = others.map { Choice(it.emoji, it.he, if (n >= 4) 1f else 1.2f) }.toMutableList()
        val right = i % n
        list.add(right, Choice(a.emoji, a.he, if (n >= 4) 1f else 1.2f))
        return list to right
    }

    /** The animals whose sound the child makes too (the easiest for a 3-year-old). */
    val COPY = setOf("cow", "dog", "cat", "sheep")

    fun lesson(kid: PitzkiKid, level: Int = PitzkiScript.level): List<PitzkiScene> {
        val n = (level + 1).coerceIn(2, 4)
        fun choices(i: Int) = FarmWorld.choices(i, n)
        val name = kid.name.trim()
        val nm = kid.named
        fun g(b: String, gi: String, all: String) = kid.g(b, gi, all)
        val p = nm
        val s = ArrayList<PitzkiScene>()
        fun add(key: String, title: String, vararg beats: Beat) { s.add(PitzkiScene(s.size + 1, key, title, null, beats.toList())) }
        fun talk(key: String) = Beat.Talk(key, TALKS.first { it.key == key }.text)

        add("song", "השיר של פיצקי", Beat.Song(0, 0, PitzkiScript.SONG.zip(PitzkiScript.SONG_AT).map { it.second to it.first }), Beat.Wait(500))
        add(INTRO, "חוות החברים", talk(INTRO), Beat.Wait(400))
        add(KID_INTRO, "מגיעים לחווה",
            Beat.Say("f_come", (if (nm) "$name, " else "") + g("בוא! בוא איתי לחווה!", "בואי! בואי איתי לחווה!", "בואו! בואו איתי לחווה!"), personal = p),
            *welcomeBack(kid).toTypedArray(),
            Beat.Say("f_ready_q", (if (nm) "$name, " else "") + g("מוכן?", "מוכנה?", "מוכנים?"), personal = p),
            Beat.Hear("f_ready", listOf("כן", "מוכן", "מוכנה", "בטח", "יאללה", "כן כן"), ok = "יאללה, מתחילים!", hint = "יאללה, מתחילים!"),
        )
        for ((i, a) in ANIMALS.withIndex()) {
            val (list, right) = choices(i)
            add(a.key, "מי עושה ${a.sound.trimEnd('!')}?",
                Beat.Sound(a.id, a.sound),
                talk(a.key),
                Beat.Pick("f_${a.id}_pick", list, right = right),
                Beat.Say("f_reveal_${a.id}", g("בוא נגלה מי זה!", "בואי נגלה מי זה!", "בואו נגלה מי זה!"), personal = p),
            )
            // The easy sounds: the child makes it too (out loud: any sound counts, it's for fun).
            if (a.id in COPY) add(a.open, a.he,
                Beat.Say("f_${a.id}_is", a.reveal),
                Beat.Sound(a.id, a.sound),
                Beat.Say("f_${a.id}_copy", g("עכשיו אתה! איך עושה ה${a.he}?", "עכשיו את! איך עושה ה${a.he}?", "עכשיו אתם! איך עושה ה${a.he}?"), personal = p),
                Beat.Hear("f_${a.id}_copy_h", listOf(Beat.ANY), ok = "וואו! בדיוק כמו ה${a.he}!", hint = g("בוא נעשה יחד: ", "בואי נעשה יחד: ", "בואו נעשה יחד: ") + a.sound),
            ) else add(a.open, a.he,
                Beat.Say("f_${a.id}_is", a.reveal),
                Beat.Sound(a.id, a.sound),
                Beat.Wait(400),
            )
            if (a.id == "dog") add("farm_dog_kid", "ליטוף לכלב",
                Beat.Say("f_dog_pet", "הכלב רוצה ליטוף! " + g("תלטף אותו בעדינות.", "תלטפי אותו בעדינות.", "תלטפו אותו בעדינות."), personal = p),
                Beat.Say("f_dog_love", "איזה כלב חמוד! הוא אוהב " + g("אותך", "אותך", "אתכם") + "!", personal = p),
                Beat.Wait(800),
            )
            if (a.id == "sheep") add("farm_sheep_kid", "חציר לכבשה",
                Beat.Say("f_sheep_feed", "הכבשה רעבה. " + g("בוא ניתן לה קצת חציר.", "בואי ניתן לה קצת חציר.", "בואו ניתן לה קצת חציר."), personal = p),
                Beat.Say("f_sheep_eat", "ממממ! היא אוכלת בנחת. תודה!"),
                Beat.Wait(800),
            )
        }
        // A short game: three sounds again (no pictures yet), guess, then the animal is said. Pitzki remembers: the
        // animals the child missed last time come first; the rest as before (the cow, the cat, the rooster).
        val review = reviewOrder(PitzkiScript.memory)
        val beats = ArrayList<Beat>()
        beats.add(Beat.Say("f_game", (if (nm) "$name, " else "") + "איזה יופי! עכשיו משחק קצר!", personal = p))
        for (i in review) {
            val a = ANIMALS[i]
            val (list, right) = choices(i)
            beats.add(Beat.Sound(a.id, a.sound))
            beats.add(Beat.Say("f_game_q_${a.id}", "מי עושה את הקול?"))
            beats.add(Beat.Pick("f_game_${a.id}", list, right = right))
            beats.add(Beat.Say("f_game_is_${a.id}", if (a.he == "פרה" || a.he == "כבשה") "נכון, זאת ה${a.he}!" else "נכון, זה ה${a.he}!"))
        }
        // Everything known already: a new kind of question (what the animal gives, where it lives).
        if (level >= 3 || allKnown(PitzkiScript.memory)) for ((i, q) in BONUS.withIndex()) {
            val a = ANIMALS.first { it.id == q.first }
            val (list, right) = choices(ANIMALS.indexOf(a))
            beats.add(Beat.Say("f_bonus_q$i", q.second))
            beats.add(Beat.Pick("f_bonus_$i", list, right = right))
            beats.add(Beat.Say("f_bonus_is$i", q.third))
        }
        add("farm_review", "משחק קולות", *beats.toTypedArray())
        add("farm_end_kid", "נתראה בהרפתקה הבאה",
            Beat.Celebrate,
            Beat.Say("f_bravo", (if (nm) "כל הכבוד, $name! " else "כל הכבוד! ") + "הכרנו את כל חברי החווה.", personal = p),
            Beat.Say("f_fav_q", g("איזו חיה אתה הכי אוהב?", "איזו חיה את הכי אוהבת?", "איזו חיה אתם הכי אוהבים?"), personal = p),
            Beat.Open("f_fav", ANIMALS.map { a -> listOf(a.he, a.he + "ה", "ה" + a.he) to "גם אני אוהב את ה${a.he}! ${a.sound}" },
                any = "איזו חיה נהדרת!", quiet = "אני אוהב את כולן!"),
            Beat.Say("f_bye", "נתראה בהרפתקה הבאה!"),
        )
        return s
    }

    /** The questions about one animal (in the walk and in the review game): what Pitzki remembers by. */
    fun askIds(a: Animal) = listOf("f_${a.id}_pick", "f_game_${a.id}")

    /** Three animals for the review game: missed ones first, then the cow, the cat, the rooster, then the rest. */
    fun reviewOrder(mem: Map<String, Int>): List<Int> {
        val missed = ANIMALS.indices.filter { PitzkiMemory.last(mem, askIds(ANIMALS[it])) < 0 }
        return (missed + listOf(1, 3, 0) + ANIMALS.indices).distinct().take(3)
    }

    /** Every animal answered right the last time it was asked. */
    fun allKnown(mem: Map<String, Int>) = ANIMALS.all { PitzkiMemory.last(mem, askIds(it)) > 0 }

    /** The new questions once all the animals are known: (animal, question, answer). */
    val BONUS = listOf(
        Triple("cow", "שאלה חדשה! איזו חיה נותנת לנו חלב?", "נכון! הפרה נותנת לנו חלב."),
        Triple("dog", "ומי שומר על הבית ונובח כשמישהו מגיע?", "נכון! הכלב שומר על הבית."),
        Triple("sheep", "ולמי יש צמר רך שעושים ממנו סוודר חם?", "נכון! לכבשה יש צמר."),
    )

    /** "Welcome back" after the first walk, and what was hard last time. */
    private fun welcomeBack(kid: PitzkiKid): List<Beat> {
        if (PitzkiScript.walksDone <= 0) return emptyList()
        val hard = ANIMALS.firstOrNull { PitzkiMemory.last(PitzkiScript.memory, askIds(it)) < 0 }
        val back = kid.g("איזה כיף שחזרת לחווה!", "איזה כיף שחזרת לחווה!", "איזה כיף שחזרתם לחווה!")
        val more = when {
            hard != null -> " היום נתאמן שוב על ה${hard.he}."
            allKnown(PitzkiScript.memory) -> kid.g(" אתה כבר מכיר את כל החיות! היום יש שאלות חדשות.", " את כבר מכירה את כל החיות! היום יש שאלות חדשות.", " אתם כבר מכירים את כל החיות! היום יש שאלות חדשות.")
            else -> ""
        }
        return listOf(Beat.Say("f_back", back + more, personal = kid.named))
    }

    /** Where the child's scenes take place (said in every drawing). */
    const val SETTING = "a cozy, sunny storybook farm with a red barn, wooden fences, hay bales and a windmill, warm golden light"

    /** What happens in each of the child's scenes (the drawing). */
    fun childAction(key: String): String = when (key) {
        KID_INTRO -> "At the wooden gate of a sunny farm, the child stands next to Pitzki, both smiling at the camera, Pitzki waving hello; the farm path, the red barn and the windmill behind them."
        "farm_dog_kid" -> "Next to a wooden dog house, the child crouches beside a happy brown-and-white puppy and gently pets its head; the puppy wags its tail; Pitzki smiles beside them."
        "farm_sheep_kid" -> "In a green pasture by a wooden feeding trough, the child holds out a small handful of hay to a fluffy white sheep, which eats from it; Pitzki stands beside them smiling."
        else -> "Pitzki and the child stand together in the middle of a sunny farmyard, smiling at the camera; behind them, in a half circle and all clearly seen: a rooster, a black-and-white cow, a brown-and-white puppy, an orange-and-white kitten, a grey donkey, a brown horse with a white stripe, and a white sheep. No other animals."
    }

    /** How each of the child's scenes moves. */
    fun childMotion(key: String): String = when (key) {
        KID_INTRO -> "Pitzki waves to the camera; the child stands next to him and smiles; then both turn and walk a few steps together along the farm path toward the coop."
        "farm_dog_kid" -> "The child gently pets the puppy, which wags its tail happily, nuzzles the child's hand and stays by the child's side; Pitzki giggles."
        "farm_sheep_kid" -> "The child holds out the hay, the sheep steps closer, blinks and eats calmly from the child's hand; the child smiles happily; Pitzki claps softly."
        else -> "Pitzki and the child wave goodbye to the camera together; the animals behind them move gently and happily; the camera slowly pulls back to show the whole farm in warm sunlight."
    }

    // ---------- the pictures (from the app's farm pictures) ----------

    /** The picture each scene starts from, in the app (assets/pitzki/farm): the gate, each animal seen, all of them. */
    fun asset(key: String): String? = when {
        key == INTRO -> "farm_gate"
        key == "farm_review" -> "farm_end"
        key.endsWith("_open") -> key
        else -> null
    }

    /**
     * Until the farm's own pictures are made (admin tools), a picture that comes with the app: the animal's picture,
     * the gate, or the end. The questions show the gate (the animal stays a surprise). Not for the child's own scenes.
     */
    fun bundled(key: String): String? = when {
        key in CHILD_KEYS -> null
        asset(key) != null -> asset(key)
        animal(key) != null -> "farm_gate"
        else -> null
    }

    /**
     * The animals' coming-out clips made so far start with a child (and a big hand) that isn't there: only their clean
     * end is played (the part of the clip, from 0 to 1, where it starts). Remove an animal here when its clip is made again.
     */
    // (Seen again on 22.10: the rooster, donkey and horse still showed the child for a moment, and the dog doubled.)
    private val CLEAN_FROM = mapOf("rooster" to 0.8f, "cow" to 0.75f, "dog" to 0.7f, "cat" to 0.6f, "donkey" to 0.8f, "horse" to 0.72f, "sheep" to 0.62f)
    fun clipFrom(key: String): Float = ANIMALS.firstOrNull { it.open == key }?.let { CLEAN_FROM[it.id] } ?: 0f

    /** Questions whose hidden picture gives the animal away (the donkey's ears): the farm gate is shown instead. */
    // (farm_cat: its "hidden" picture already shows an animal in the barrel, and it isn't a cat.)
    private val GATE_INSTEAD = setOf("farm_donkey", INTRO, "farm_cat")
// (A file's date can't tell an old one from a new one: families get them by download. When one is made again and
    // it's good, it's taken out of these lists.)
    fun gateInstead(key: String) = key in GATE_INSTEAD

    /** Talking videos whose picture is spoiled (the hello's sign with made-up letters): only Pitzki's voice is heard. */
    // (farm_cat: an animal that isn't a cat came out of the barrel, and Pitzki lost his hat.)
    // (The hello is played again: its sign is covered by the app's clean one, [SIGN_RECT].)
    private val VOICE_ONLY = setOf("farm_cat")

    /** Where the wooden sign is in the gate picture and its hello video (left, top, right, bottom, 0..1 of the width/height). */
    // (Wider than the board: the video also wrote made-up letters over the barn, left of it. The patch,
    // assets/pitzki/farm/farm_sign_patch.png, is this part of the clean gate picture with soft edges.)
    val SIGN_RECT = floatArrayOf(944f / 1536, 108f / 864, 1512f / 1536, 375f / 864)
    fun voiceOnly(key: String) = key in VOICE_ONLY

    /** The question's picture: the same place as the animal's picture, the animal hidden (drawn from it). */
    fun hiddenPrompt(a: Animal): String =
        "Edit this picture: keep everything exactly the same (Pitzki the elf with his green felt hat, his pose with his hand by his ear, " +
            "the place, the light, the style, the framing), but take the ${a.id} out completely: ${a.hidden}. " +
            "No animal at all anywhere in the picture: not a shadow, not ears, not eyes in the dark, not a tail. Remove any white sound lines. " +
            "Exactly one Pitzki, no text, no letters."

    /** The gate's picture made clean: its sign without the (wrongly written) words. */
    const val GATE_PROMPT =
        "Edit this picture: keep everything exactly the same (Pitzki the elf, his pose, the farm gate, the barn, the animals, the light, " +
            "the style, the framing), but the wooden sign on the right is blank: no letters or words on it at all, only wood grain " +
            "and a small carved flower. Exactly one Pitzki, no text anywhere."

    /** The animal coming out: from the hidden picture to the seen one (5 seconds). */
    fun revealMotion(a: Animal) = "From hidden to seen: ${a.comesOut}. Exactly one animal comes out and it is clearly a ${a.id}, " +
        "the same ${a.id} as in the last picture; no other animal, no child, no person, no hand. Pitzki stays the same elf with his " +
        "green felt hat, delighted. Smooth, gentle, slow camera. No text."
}

/** Pitzki's talking videos of every world (the train's and the farm's): shared, with their own sound. */
object PitzkiTalks {
    val ALL: List<TrainWorld.Talk> get() = TrainWorld.TALKS + FarmWorld.TALKS
    val KEYS: Set<String> get() = ALL.map { it.key }.toSet()
    fun get(key: String) = ALL.firstOrNull { it.key == key }
}
