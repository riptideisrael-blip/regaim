package com.regaim.app

/**
 * "The magic forest with Pitzki": the colors lesson, as data (pure Kotlin, tested on the computer).
 * Pitzki the forest elf and the child walk through the forest and meet ten colors, in Hebrew and in English.
 * The screen plays the beats one after another; it never decides the words itself.
 */
enum class PColor(
    val en: String,
    val he: String,
    val emoji: String,
    /** The thing in the forest that has this color (Hebrew, as in "איזה צבע יש ל…"). */
    val thing: String,
    /** What Pitzki says when they find it. */
    val found: String,
    /** The same thing for the drawing and the animation (English). */
    val enThing: String,
    /** How it comes to life in its close-up (English, for the animation). */
    val enMotion: String,
    private val adj: String = "",
) {
    GREEN("Green", "ירוק", "🍃", "עלה", "עלה בצורת לב!", "a big green heart-shaped leaf", "the leaf flutters gently in the breeze and Pitzki lifts it up to show it"),
    BROWN("Brown", "חום", "🪵", "גזע", "עץ גדול! תראו את הגזע שלו.", "the brown bark of a huge old tree trunk", "Pitzki pats the bark and a few leaves float down around the trunk"),
    RED("Red", "אדום", "🍎", "תפוח", "תפוח על הענף!", "a shiny red apple on a low branch", "the apple swings on its branch, then Pitzki picks it and holds it up proudly"),
    YELLOW("Yellow", "צהוב", "🌻", "פרח", "פרח שמש קטן!", "a bright yellow sunflower", "the sunflower sways in the breeze and turns toward the sun"),
    GRAY("Gray", "אפור", "🪨", "אבן", "אבן ליד הרגליים שלנו!", "a smooth gray stone on the path", "Pitzki picks up the stone and turns it in his hand, smiling", adj = "אפורה"),
    WHITE("White", "לבן", "🐇", "ארנב", "ארנב קטן ורך קופץ בין העצים!", "a small fluffy white bunny hopping out from behind a tree", "the bunny hops out, wiggles its nose and twitches its ears"),
    BLACK("Black", "שחור", "🪲", "חיפושית", "חיפושית קטנה ומבריקה על עלה!", "a small shiny black beetle walking on a leaf", "the beetle walks along the leaf, its shiny shell catching the light", adj = "שחורה"),
    PURPLE("Purple", "סגול", "🍇", "ענבים", "ענבים על הגפן!", "a bunch of purple grapes hanging from a vine", "the grapes sway on the vine and Pitzki picks one and smiles", adj = "סגולים"),
    PINK("Pink", "ורוד", "🦩", "פלמינגו", "פלמינגו עומד ליד האגם!", "a pink flamingo standing by a little forest pond", "the flamingo lifts one leg and gracefully bends its long neck"),
    BLUE("Blue", "כחול", "🐦", "ציפור", "ציפור קטנה על הענף!", "a little blue bird singing on a branch", "the bird hops along the branch, sings and flaps its little wings", adj = "כחולה"),
    ;

    /** The color describing its thing ("the gray stone" is feminine in Hebrew: אבן אפורה). */
    val heAdj: String get() = adj.ifEmpty { he }
}

/** The child the lesson is for. An empty name: Pitzki talks to "friends" (plural). */
data class PitzkiKid(val name: String, val girl: Boolean) {
    val named: Boolean get() = name.isNotBlank()
    /** Picks the Hebrew form: to a boy, to a girl, or to everyone. */
    fun g(boy: String, girl: String, all: String) = if (!named) all else if (this.girl) girl else boy
}

sealed class Beat {
    companion object {
        /** In [Hear.words]: any answer counts (the child makes an animal's sound: a recognizer can't spell "moo"). */
        const val ANY = "*"
    }

    /**
     * Pitzki says [text] (Hebrew). [id] names a recorded line (assets/pitzki/voice/[id].mp3) that may replace the
     * phone's voice; [personal] lines carry the child's name or form, so a recording can't stand in for them.
     */
    data class Say(val id: String, val text: String, val personal: Boolean = false) : Beat()
    /** The English word, said clearly, with the big colored word bubble. */
    data class Word(val color: PColor) : Beat()
    /** A pause for the child to answer out loud. */
    data class Wait(val ms: Long) : Beat()
    /**
     * The child answers out loud (when the parent allows the microphone): Pitzki hears the color and answers
     * "right!" or gives the right answer. Without the microphone it's a short pause, as before.
     */
    data class Listen(val color: PColor) : Beat()
    /** Waits for the child to tap the right thing in the row below. */
    data class Ask(val color: PColor) : Beat()
    /** Stars and a cheer. */
    object Celebrate : Beat()
    /**
     * Pitzki's song, sung (the recording in assets/pitzki/song.m4a), from [fromMs] to [toMs] (0 = to its end),
     * each line shown as it's sung ([lines]: when it starts, the words). One recording plays on across the song's
     * scenes. Without the recording, the lines are said instead.
     */
    data class Song(val fromMs: Long, val toMs: Long, val lines: List<Pair<Long, String>>) : Beat()

    // ---------- games for the other worlds (opposites, patience, accuracy) ----------

    /** Tap the right one of a few big pictures ("where is the big one?"). [right]: its index. */
    data class Pick(val id: String, val choices: List<Choice>, val right: Int) : Beat()
    /**
     * Pitzki's talking video for scene [key] (shared, lips matched, with its own sound) plays to its end; without
     * that video Pitzki says [text] in his voice over the scene instead.
     */
    data class Talk(val key: String, val text: String) : Beat()
    /** An animal's sound (the farm): its recording when it was made, else Pitzki says it ([text]). */
    data class Sound(val animal: String, val text: String) : Beat()
    /** The child says a word out loud ("thank you", "slow!"): any of [words] heard -> [ok], else [hint]. */
    data class Hear(val id: String, val words: List<String>, val ok: String, val hint: String) : Beat()
    /**
     * An open question, answered out loud ("which animal do you like best?"): the first of [answers] whose word was
     * heard gets its reply; something else heard -> [any]; nothing heard (or no microphone) -> [quiet]. No wrong answer.
     */
    data class Open(val id: String, val answers: List<Pair<List<String>, String>>, val any: String, val quiet: String) : Beat()
    /**
     * Dragging with a finger: each of [items] to its home (its dark shape, in another order on the other side).
     * [only] >= 0: only that one is asked for (the others go back: listening). The animal's sound when it's home.
     */
    data class Drag(val id: String, val items: List<Choice>, val only: Int = -1) : Beat()
    /** Counting things by touching each one: [n] of [emoji] on the screen; each touched one is said and stays lit. */
    data class TapCount(val id: String, val n: Int, val emoji: String) : Beat()
    /** A sum to see: [a] of [emoji] and [b] more; the child chooses how many together (tap or out loud). */
    data class Sum(val id: String, val a: Int, val b: Int, val emoji: String) : Beat()
    /**
     * Pitzki shows a sum before asking it: [a] things appear ([look] said), [b] more join them ([add]), all are counted
     * together one by one, and the answer is said ([total]). Nothing to answer yet: it's the lesson.
     */
    data class ShowSum(val id: String, val a: Int, val b: Int, val emoji: String, val look: String, val add: String, val total: String) : Beat()
    /** Counting together, slowly, 1 to [n] (waiting patiently). */
    data class Count(val n: Int) : Beat()
    /** Breathing together: in… out… [times] times (calming down while waiting). */
    data class Breathe(val times: Int) : Beat()
    /** A light: red… then green; tap only on green (patience and accuracy). */
    object Light : Beat()
}

/** One picture to choose: [emoji] shown at [scale] (big / small), with its word. */
data class Choice(val emoji: String, val label: String, val scale: Float = 1f)

/**
 * One scene. [n] is its place in the lesson (1, 2, …); [key] names its drawing and animation on the phone
 * ("intro", "walk", "green", …, "end"), so adding colors never redraws the scenes a family already has.
 */
data class PitzkiScene(val n: Int, val key: String, val title: String, val color: PColor?, val beats: List<Beat>)

object PitzkiScript {
    /** The order the colors are met in the forest. */
    val ORDER = listOf(
        PColor.GREEN, PColor.BROWN, PColor.RED, PColor.YELLOW, PColor.GRAY,
        PColor.WHITE, PColor.BLACK, PColor.PURPLE, PColor.PINK, PColor.BLUE,
    )
    /** The questions in the review (mixed, so it's not just repeating). */
    val REVIEW = listOf(PColor.RED, PColor.BLUE, PColor.WHITE, PColor.GREEN, PColor.PURPLE, PColor.BLACK, PColor.YELLOW)
    /**
     * The walks with the child between the colors: after every two colors the child is back on screen (so the film
     * keeps changing: the child, two of Pitzki's close-ups, the child again…). Key -> it comes after this many colors.
     */
    private val WALKS = linkedMapOf("walk" to 2, "walk2" to 4, "walk3" to 6, "walk4" to 8)
    /** Every scene's key, in order: the song, the child, two colors, a walk with the child, two colors, … the end. */
    /** The world being played ("forest": colors; "train": opposites, patience, accuracy). Set by the screen. */
    @Volatile var worldId = "forest"
    /** What Pitzki remembers about the child the lesson is built for ([PitzkiMemory]); empty the first time. */
    @Volatile var memory: Map<String, Int> = emptyMap()
    /** How many walks this child has done (a "welcome back" after the first). */
    @Volatile var walksDone = 0
    /**
     * The child's scenes whose animation is made 10 seconds long (the opening, where the child walks in and Pitzki
     * says a lot; stepping into the train, seen to its end). The others are 5 seconds: Pitzki says a line or two there,
     * and a longer walk only went on in silence (and cost twice as much).
     */
    val LONG_CLIPS: Set<String> get() = setOf(INTRO_KEY, "train_board")
    /** Child scenes whose animation is seen to its end before the next scene (the rest go on once Pitzki is done). */
    val SEEN_TO_END: Set<String> = setOf("train_board")

    /** The child's level in the world being played (1 = the first; [PitzkiLevels]). */
    @Volatile var level = 1
    val isTrain: Boolean get() = worldId == TrainWorld.ID
    val isNight: Boolean get() = worldId == NightWorld.ID
    val isPlay: Boolean get() = worldId == PlayWorld.ID
    val isNumbers: Boolean get() = worldId == NumbersWorld.ID
    val isFarm: Boolean get() = worldId == FarmWorld.ID

    val KEYS: List<String> get() = if (isNumbers) NumbersWorld.KEYS else if (isPlay) PlayWorld.KEYS else if (isNight) NightWorld.KEYS else if (isFarm) FarmWorld.KEYS else if (isTrain) TrainWorld.KEYS else FOREST_KEYS
    val CHILD_KEYS: List<String> get() = if (isNumbers) NumbersWorld.CHILD_KEYS else if (isPlay) PlayWorld.CHILD_KEYS else if (isNight) NightWorld.CHILD_KEYS else if (isFarm) FarmWorld.CHILD_KEYS else if (isTrain) TrainWorld.CHILD_KEYS else FOREST_CHILD_KEYS
    /** The scene where the child arrives (Pitzki leads them in). */
    val INTRO_KEY: String get() = if (isNumbers) NumbersWorld.INTRO else if (isPlay) PlayWorld.INTRO else if (isNight) NightWorld.INTRO else if (isFarm) FarmWorld.KID_INTRO else if (isTrain) TrainWorld.INTRO else "intro"
    /**
     * The child drawn once as the film's character (from the real photos), for the parent to approve: the look of a
     * high-end 3D animated film, but the child's own face and eyes (only slightly stylized, never huge), mouth closed.
     */
    fun heroPrompt(girl: Boolean, photos: Int): String {
        val who = if (girl) "a little girl" else "a little boy"
        return "Turn this child into a fully animated 3D CARTOON character, like the hero of a high-end 3D animated family movie. " +
            "It must clearly be a cartoon, NOT a photo and NOT realistic: stylized rounded shapes, a slightly bigger head and softer " +
            "proportions, smooth stylized skin, hair sculpted into soft stylized curls, warm cinematic lighting, the same 3D cartoon " +
            "style as an animated feature film. " +
            (if (photos > 1) "The $photos images are real photos of the same child, $who, from different moments. " else "The image is a real photo of a child, $who. ") +
            "Keep the child instantly recognizable: the same face shape, eyebrows, nose, cheeks, smile, hair color, curls and hairline, " +
            "skin tone and age. The eyes are a little bigger than in real life, as in animated films, but not huge, and keep the child's " +
            "own eye shape and color. Mouth closed in a gentle smile. " +
            "Full body, standing, facing the camera, arms relaxed, simple comfortable everyday clothes in soft friendly colors, " +
            "on a plain soft cream background. Exactly one child. No text, no letters, no logos."
    }

    /** The approved character from every side, for the parent to turn around with a finger (four views in a row). */
    fun heroSheetPrompt(girl: Boolean): String {
        val who = if (girl) "little girl" else "little boy"
        return "A character turnaround sheet for a high-end 3D animated family film: the exact same $who as in the image " +
            "(the same face, eyes, hair, skin, clothes, age and 3D cartoon style), shown four times side by side in one row, " +
            "evenly spaced, each in its own equal quarter of the width, all the same height and size, full body, standing on " +
            "the same line: from left to right, 1) facing the camera, 2) a three-quarter view turned to the right, 3) in profile " +
            "facing right, 4) from the back. Plain soft cream background, no lines between them, no labels, no text, no letters. " +
            "Mouth closed in a gentle smile."
    }

    fun lesson(kid: PitzkiKid): List<PitzkiScene> = if (isNumbers) NumbersWorld.lesson(kid) else if (isPlay) PlayWorld.lesson(kid) else if (isNight) NightWorld.lesson(kid) else if (isFarm) FarmWorld.lesson(kid) else if (isTrain) TrainWorld.lesson(kid) else forestLesson(kid)

    private val FOREST_KEYS: List<String> = buildList {
        add("song"); add("intro")
        ORDER.forEachIndexed { i, c ->
            WALKS.entries.firstOrNull { it.value == i }?.let { add(it.key) }
            add(c.name.lowercase())
        }
        add("end")
    }
    /**
     * The scenes the child is in (drawn and animated for each child). Pitzki's song and the colors' close-ups have him alone: made
     * once, the same for every family, so a child's film costs only these.
     */
    private val FOREST_CHILD_KEYS = listOf("intro", "walk", "walk2", "walk3", "walk4", "end")

    /** The worlds a family plays (the numbers are part of the games). */
    val WORLDS: List<String> get() = listOf("forest", TrainWorld.ID, FarmWorld.ID, NightWorld.ID, PlayWorld.ID)
    /** The child's own scenes in [world]. */
    fun childKeysOf(world: String): List<String> = when (world) {
        TrainWorld.ID -> TrainWorld.CHILD_KEYS; FarmWorld.ID -> FarmWorld.CHILD_KEYS; NightWorld.ID -> NightWorld.CHILD_KEYS
        PlayWorld.ID -> PlayWorld.CHILD_KEYS; NumbersWorld.ID -> NumbersWorld.CHILD_KEYS; else -> FOREST_CHILD_KEYS
    }
    val SCENES: Int get() = KEYS.size
    /** After a question or "say with me": time for a 3-year-old to answer (at least 2 seconds, more is kinder). */
    const val ANSWER_MS = 3000L

    /** Pitzki's song (written by the family, with niqqud so the voice says it right): the opening of every film. */
    val SONG = listOf(
        "אֲנִי פִּיצְקִי, שְׁדוֹן הַיַּעַר,",
        "עִם כּוֹבַע יָרֹק וְחַי בַּיַּעַר!",
        "קוֹפֵץ בֵּין הַפְּרָחִים, מְדַלֵּג עַל הַשְּׁבִיל,",
        "וּבְכָל פִּנָּה מְחַכֶּה לִי תַּרְגִּיל!",
    )
    /** When each line is sung in the recording (from the song's own lyric timing). */
    val SONG_AT = listOf(2_074L, 5_585L, 9_574L, 12_766L)

    /** Only the first name: "אורי נתן" → "אורי" (that's how a child is called). */
    fun firstName(full: String): String = full.trim().split(Regex("\\s+")).firstOrNull().orEmpty()

    private fun forestLesson(kid: PitzkiKid): List<PitzkiScene> {
        val name = kid.name.trim()
        val hi = if (kid.named) "שלום $name!" else "שלום חברים!"
        val scenes = ArrayList<PitzkiScene>()
        // Every film opens with Pitzki's song (the same in every film): he sings and introduces himself.
        // The song (about 20 seconds, one recording): one scene, Pitzki singing.
        scenes.add(PitzkiScene(1, "song", "השיר של פיצקי", null, listOf(Beat.Song(0, 0, SONG.zip(SONG_AT).map { it.second to it.first }), Beat.Wait(500))))
        scenes.add(PitzkiScene(2, "intro", "פתיח", null, listOf(
            Beat.Say("intro_come", (if (kid.named) "$name, " else "") + kid.g("בוא! בוא איתי ליער!", "בואי! בואי איתי ליער!", "בואו! בואו איתי ליער!"), personal = kid.named),
            Beat.Say("intro_hello", "$hi " + kid.g("איזה כיף שֶׁבָּאתָ לטיול איתי!", "איזה כיף שֶׁבָּאת לטיול איתי!", "איזה כיף שבאתם לטיול איתי!"), personal = kid.named),
            Beat.Say("intro_trip", "היום נצא יחד לטיול ביער, ונלמד צבעים באנגלית."),
            Beat.Say("intro_ready", kid.g("מוכן?", "מוכנה?", "מוכנים?"), personal = kid.named),
            Beat.Wait(ANSWER_MS), // time to answer
            Beat.Say("intro_go", kid.g("בוא נמצא צבעים!", "בואי נמצא צבעים!", "בואו נמצא צבעים!"), personal = kid.named),
        )))
        // The walks with the child, between every two colors.
        val walks = mapOf(
            2 to PitzkiScene(0, "walk", "הליכה ביער", null, listOf(
                Beat.Say("walk_pretty", "איזה יער יפה! כבר מצאנו שני צבעים."),
                Beat.Say("walk_point", kid.g("נמשיך בשביל ונחפש עוד. תצביע יחד איתי!", "נמשיך בשביל ונחפש עוד. תצביעי יחד איתי!", "נמשיך בשביל ונחפש עוד. תצביעו יחד איתי!"), personal = kid.named),
                Beat.Wait(800),
            )),
            4 to PitzkiScene(0, "walk2", "ממשיכים", null, listOf(
                Beat.Say("walk2_more", "איזה כיף! מצאנו כבר ארבעה צבעים."),
                Beat.Say("walk2_deeper", kid.g("בוא נמשיך פנימה ליער, יש עוד!", "בואי נמשיך פנימה ליער, יש עוד!", "בואו נמשיך פנימה ליער, יש עוד!"), personal = kid.named),
                Beat.Wait(800),
            )),
            6 to PitzkiScene(0, "walk3", "קופצים על האבנים", null, listOf(
                Beat.Say("walk3_six", "וואו, כבר שישה צבעים!"),
                Beat.Say("walk3_jump", (if (kid.named) "$name, " else "") + kid.g("בוא נקפוץ יחד על האבנים בנחל!", "בואי נקפוץ יחד על האבנים בנחל!", "בואו נקפוץ יחד על האבנים בנחל!"), personal = kid.named),
                Beat.Say("walk3_hop", "הופ! הופ! הופ!"),
                Beat.Wait(600),
            )),
            8 to PitzkiScene(0, "walk4", "כמעט בסוף", null, listOf(
                Beat.Say("walk4_two", "עוד רק שני צבעים!"),
                Beat.Say("walk4_ready", kid.g("אתה מוכן לגלות אותם?", "את מוכנה לגלות אותם?", "אתם מוכנים לגלות אותם?"), personal = kid.named),
                Beat.Wait(ANSWER_MS), // a question: time to answer
            )),
        )
        ORDER.forEachIndexed { i, c ->
            val key = c.name.lowercase()
            walks[i]?.let { w -> scenes.add(w.copy(n = scenes.size + 1)) }
            scenes.add(PitzkiScene(scenes.size + 1, key, c.he, c, listOf(
                // Pitzki talks to the child ("look!" to him or her), not to an audience.
                Beat.Say("${key}_found", "וואו, " + kid.g("תראה!", "תראי!", "תראו!") + " ${c.found}", personal = kid.named),
                Beat.Say("${key}_which", "איזה צבע יש ל${c.thing}?"),
                Beat.Listen(c),
                Beat.Say("${key}_he", "${c.he}!"),
                Beat.Say("${key}_en", "באנגלית אומרים"),
                Beat.Word(c),
                Beat.Say("${key}_together", (if (kid.named) "$name, " else "") + kid.g("תגיד איתי:", "תגידי איתי:", "תגידו איתי:"), personal = kid.named),
                Beat.Word(c),
                Beat.Wait(ANSWER_MS), // time to say it
                Beat.Say("${key}_tap", kid.g("תלחץ על ה${c.thing}", "תלחצי על ה${c.thing}", "תלחצו על ה${c.thing}") + " ${the(c.heAdj)}.", personal = kid.named),
                Beat.Ask(c),
            )))
        }
        val review = ArrayList<Beat>()
        review.add(Beat.Say("end_check", kid.g("עכשיו בוא נבדוק מה זכרנו!", "עכשיו בואי נבדוק מה זכרנו!", "עכשיו בואו נבדוק מה זכרנו!"), personal = kid.named))
        for (c in REVIEW) {
            review.add(Beat.Say("end_where_${c.name.lowercase()}", "איפה ${the(c.he)}?"))
            review.add(Beat.Ask(c))
        }
        review.add(Beat.Celebrate)
        review.add(Beat.Say("end_wonderful", "איזה טיול נפלא! היום למדנו עשרה צבעים:"))
        ORDER.forEach { review.add(Beat.Word(it)) }
        review.add(Beat.Say("end_fav_q", kid.g("ואיזה צבע אתה הכי אוהב?", "ואיזה צבע את הכי אוהבת?", "ואיזה צבע אתם הכי אוהבים?"), personal = kid.named))
        review.add(Beat.Open("end_fav", ORDER.map { c -> listOf(c.he) to "גם אני אוהב ${c.he}!" },
            any = "איזה צבע יפה!", quiet = "אני הכי אוהב ירוק, כמו הכובע שלי!"))
        review.add(Beat.Say("end_bye", if (kid.named) "נתראה בהרפתקה הבאה, $name!" else "נתראה בהרפתקה הבאה!", personal = kid.named))
        scenes.add(PitzkiScene(scenes.size + 1, "end", "חזרה וסיום", null, review))
        return scenes
    }

    /** A cheer after a right answer (a different one each time, by [i]). */
    fun praise(kid: PitzkiKid, i: Int): String {
        val all = listOf(
            kid.g("כל הכבוד!", "כל הכבוד!", "כל הכבוד!"),
            kid.g("נכון מאוד! אתה אלוף!", "נכון מאוד! את אלופה!", "נכון מאוד! אתם אלופים!"),
            kid.g("יופי! מָצָאתָ!", "יופי! מָצָאת!", "יופי! מצאתם!"),
            "וואו, איזה יופי!",
        )
        return all[Math.floorMod(i, all.size)]
    }

    /** After a wrong tap: gentle, says what it was and asks again. */
    fun tryAgain(kid: PitzkiKid, tapped: PColor, wanted: PColor) = tryAgainParts(kid, tapped, wanted).joinToString(" ")

    /**
     * The same, in short pieces that are each said in Pitzki's own voice (a few fixed lines per color, instead of
     * a sentence for every pair of colors that would be left to the phone's voice).
     */
    fun tryAgainParts(kid: PitzkiKid, tapped: PColor, wanted: PColor) =
        listOf("כמעט! זה ${tapped.he}.", kid.g("נסה שוב:", "נסי שוב:", "נסו שוב:") + " איפה ${the(wanted.he)}?")

    /** After two misses or a long wait: shows the answer, no score lost. */
    fun showAnswer(wanted: PColor) = "הנה ${the(wanted.he)}! ${wanted.en}!"

    /** The words a child may say for each color: Hebrew, English, and English as a Hebrew recognizer writes it. */
    private val SAID: Map<PColor, List<String>> = mapOf(
        PColor.GREEN to listOf("ירוק", "ירוקה", "green", "גרין", "גריין"),
        PColor.BROWN to listOf("חום", "חומה", "brown", "בראון", "בראונ", "בראו"),
        PColor.RED to listOf("אדום", "אדומה", "red", "רד", "רעד"),
        PColor.YELLOW to listOf("צהוב", "צהובה", "yellow", "ילו", "יילו", "יאלו", "יעלו"),
        PColor.GRAY to listOf("אפור", "אפורה", "gray", "grey", "גריי", "גרי", "גראי"),
        PColor.WHITE to listOf("לבן", "לבנה", "white", "וויט", "ווייט", "ויט", "ווט"),
        PColor.BLACK to listOf("שחור", "שחורה", "black", "בלאק", "בלק"),
        PColor.PURPLE to listOf("סגול", "סגולה", "סגולים", "purple", "פרפל", "פורפל", "פרפול"),
        PColor.PINK to listOf("ורוד", "ורודה", "pink", "פינק"),
        PColor.BLUE to listOf("כחול", "כחולה", "blue", "בלו"),
    )

    /**
     * Which color the child said, from what the recognizer heard ([heard]: its guesses, best first). Whole words
     * only ("ורד" isn't "רד"), with a leading ה or ו allowed ("האדום"). Null: no color was said.
     */
    fun heard(heard: List<String>): PColor? {
        for (h in heard) {
            for (raw in h.lowercase().split(Regex("[^\\p{L}]+"))) {
                if (raw.isEmpty()) continue
                fun strip(w: String) = w.drop(1).takeIf { w.length > 3 && (w[0] == 'ה' || w[0] == 'ו') }
                // "האדום", and "הוורוד" (ה, then the doubled ו).
                val tries = listOfNotNull(raw, strip(raw), strip(raw)?.let { strip(it) })
                for (t in tries) SAID.entries.firstOrNull { (_, words) -> t in words }?.let { return it.key }
            }
        }
        return null
    }

    /** The child said the right color. */
    fun rightSaid(kid: PitzkiKid) = "נכון מאוד! " + kid.g("אתה אלוף!", "את אלופה!", "אתם אלופים!")

    /** Something was said, but no color was heard. */
    fun notHeard(kid: PitzkiKid) = kid.g("לא שמעתי טוב. בוא נגיד יחד!", "לא שמעתי טוב. בואי נגיד יחד!", "לא שמעתי טוב. בואו נגיד יחד!")

    /**
     * Every line Pitzki may say in [kid]'s lesson (Hebrew lines and the English words), so his voice can be made
     * ahead of time. The same text is the same recording for every family; only lines with the name are the child's own.
     */
    fun voiceLines(kid: PitzkiKid): List<String> {
        val out = LinkedHashSet<String>()
        // Every world's lines (switching worlds needs nothing new).
        // The world chosen now first (its lines are needed first), then the other one.
        val all = listOf(forestLesson(kid), TrainWorld.lesson(kid), FarmWorld.lesson(kid, FarmWorld.LEVELS), NightWorld.lesson(kid), (1..PlayWorld.LEVELS).flatMap { PlayWorld.lesson(kid, it) }, (1..NumbersWorld.LEVELS).flatMap { NumbersWorld.lesson(kid, it) })
        val now = if (isNumbers) 5 else if (isPlay) 4 else if (isNight) 3 else if (isFarm) 2 else if (isTrain) 1 else 0
        val worlds = all[now] + all.filterIndexed { i, _ -> i != now }.flatten()
        for (b in worlds.flatMap { it.beats }) when (b) {
            is Beat.Say -> out.add(b.text)
            is Beat.Talk -> out.add(b.text)
            is Beat.Sound -> out.add(b.text)
            is Beat.Hear -> { out.add(b.ok); out.add(b.hint) }
            is Beat.Open -> { b.answers.forEach { out.add(it.second) }; out.add(b.any); out.add(b.quiet) }
            is Beat.Sum -> out.add(NumbersWorld.sumHelp(b.a, b.b))
            is Beat.ShowSum -> { out.add(b.look); out.add(b.add); out.add(b.total) }
            else -> {}
        }
        out.addAll(GAME_LINES)
        out.add(LEVEL_UP); out.add(TOP_LEVEL)
        out.addAll(ArrowGame.LINES)
        // The teaching film's lines: ready in his voice before the first time it's watched.
        out.addAll(MathEpisode.SHOTS.map { it.line })
        // The talk with Pitzki: his questions and answers, ready before the first talk.
        out.addAll(PitzkiChat.allLines(kid))
        out.add(tryAgainPick(kid))
        (0 until 4).forEach { out.add(praise(kid, it)) }
        out.add(rightSaid(kid))
        out.add(notHeard(kid))
        PColor.values().forEach { out.add(showAnswer(it)); out.add(it.en) }
        // The pieces of "almost!" (tapped or said another color): a handful per color, all in his voice.
        for (c in PColor.values()) {
            out.addAll(tryAgainParts(kid, c, c))
            out.addAll(wrongSaidParts(kid, c, c))
            out.add("${c.he}!")
        }
        return out.toList()
    }

    /** The child said another color: kind, and the right answer. */
    fun wrongSaid(kid: PitzkiKid, said: PColor, wanted: PColor) = wrongSaidParts(kid, said, wanted).joinToString(" ")

    fun wrongSaidParts(kid: PitzkiKid, said: PColor, wanted: PColor) =
        listOf("כמעט! זה לא ${said.he}.", "התשובה הנכונה היא ${wanted.he}!", kid.g("נסה להגיד איתי:", "נסי להגיד איתי:", "נסו להגיד איתי:"))

    /** Words the games say (counting, breathing, the light, showing the answer). */
    val NUMBERS: List<String> get() = NumbersWorld.WORDS
    const val BREATHE_IN = "שואפים…"
    const val BREATHE_OUT = "נושפים…"
    const val LIGHT_WAIT = "עוד לא! מחכים לירוק."
    const val LIGHT_NOW = "עכשיו ירוק! לחץ!"
    const val HERE_IT_IS = "הנה, זה כאן!"
    val GAME_LINES: List<String> get() = NUMBERS.map { "$it!" } + listOf(BREATHE_IN, BREATHE_OUT, LIGHT_WAIT, LIGHT_NOW, HERE_IT_IS)

    const val LEVEL_UP = "כל הכבוד! מאה! עלית לרמה הבאה!"
    const val TOP_LEVEL = "מאה! הגעת לרמה הכי גבוהה!"

    fun tryAgainPick(kid: PitzkiKid) = kid.g("כמעט! נסה שוב.", "כמעט! נסי שוב.", "כמעט! נסו שוב.")

    /**
     * Whether the child said one of [words] (Hebrew), from what the recognizer heard: whole words, with a leading
     * ה / ו / ל allowed ("הלאט"), and a word that starts with one of them ("עוצרים" for "עוצר").
     */
    /**
     * Which picture the child said out loud ("פרה!", "the cow"), by its name; -1 if none, or if two different ones
     * were heard (not a guess). The names are compared without niqqud or punctuation.
     */
    fun heardChoice(heard: List<String>, labels: List<String>, digits: List<String> = emptyList()): Int {
        if (digits.isNotEmpty()) {
            val said = heard.flatMap { h -> Regex("\\d+").findAll(h).map { it.value }.toList() }.toSet()
            val d = digits.indices.filter { digits[it] in said }
            if (d.size == 1) return d[0]
        }
        val plain = labels.map { l -> l.filter { it !in '\u0591'..'\u05C7' }.lowercase().replace(Regex("[^\\p{L} ]"), "").trim() }
        val hits = plain.indices.filter { i -> plain[i].isNotEmpty() && heardAny(heard, plain[i].replace("־", " ").split(" ").filter { it.length >= 2 }) }
        return if (hits.size == 1) hits[0] else -1
    }

    fun heardAny(heard: List<String>, words: List<String>): Boolean {
        for (h in heard) for (raw in h.lowercase().split(Regex("[^\\p{L}]+"))) {
            if (raw.isEmpty()) continue
            val tries = listOfNotNull(raw, raw.drop(1).takeIf { raw.length > 2 && raw[0] in "הול" })
            if (tries.any { t -> words.any { w -> t == w || (w.length >= 3 && t.startsWith(w)) } }) return true
        }
        return false
    }

    /** "the" in Hebrew: ה before the word; a word starting with ו doubles it (ורוד → הוורוד). */
    fun the(word: String) = "ה" + if (word.startsWith("ו") && !word.startsWith("וו")) "ו$word" else word
}
