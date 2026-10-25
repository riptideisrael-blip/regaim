package com.regaim.app

/**
 * Pitzki's first teaching film: "one and one" (about a minute). One corner of the forest all along: a round wooden
 * table, Pitzki, real apples that are part of the scenes (not pictures pasted on top). Made from key pictures
 * ([FRAMES]): each one drawn from the one before (same place, camera and light; only what changes is asked), and each
 * shot ([SHOTS]) animates from one picture to the next, so the film is one continuous moment. Pitzki's voice is said
 * over the pictures (his mouth stays closed, as in the walks), and the chalkboard is drawn by the app (exact numbers).
 * Made once by the admin for every family. No Android here (unit-tested).
 */
object MathEpisode {
    const val ID = "ep1"

    /** A key picture: what it shows, and how many apples are in it. */
    class Frame(val key: String, val change: String, val apples: Int)

    /** A shot: from one key picture to another, what moves, what Pitzki says, what the board shows after it. */
    class Shot(val key: String, val from: Int, val to: Int, val motion: String, val line: String, val board: String, val waitMs: Long = 0)

    private const val PITZKI = "Pitzki, a small forest elf: keep his face, curly chestnut hair, green pointed hat with a leaf, " +
        "leaf-green clothes, brown belt, brown shoes and proportions exactly the same, in the same high-end soft 3D animated film style. " +
        "His eyes are wide open and bright, a happy friendly face."
    const val PLACE = "A cozy sunny forest clearing, warm soft morning light, seen from the front at a child's eye height. " +
        "A small round wooden table stands in the middle of the picture, close to the viewer, its top clearly visible. " +
        "Nothing else red anywhere (no red flowers, mushrooms or clothes). Exactly one Pitzki, no other characters, no text, no letters, no numbers."

    val FRAMES = listOf(
        Frame("ep1_f00", "Pitzki is just walking in from the left side of the picture toward the table, smiling at the viewer. The table is empty.", 0),
        Frame("ep1_f01", "Pitzki now stands right behind the middle of the table, facing the viewer, waving hello with his right hand. The table is empty.", 0),
        Frame("ep1_f02", "Pitzki holds up ONE big shiny red apple in his right hand, showing it to the viewer. The table is still empty.", 1),
        Frame("ep1_f03", "That ONE apple now lies on the left side of the table; Pitzki's hands rest gently on the table edge, he smiles at the viewer.", 1),
        Frame("ep1_f04", "The apple still lies on the left side of the table, and Pitzki holds up ONE more big shiny red apple in his right hand, showing it to the viewer.", 2),
        Frame("ep1_f05", "Now TWO big shiny red apples lie side by side on the table, a little apart; Pitzki's hands rest gently on the table edge.", 2),
        Frame("ep1_f06", "Pitzki gently touches the right apple with one finger, as if counting, and looks at the viewer with a big smile. The two apples stay on the table.", 2),
        Frame("ep1_f07", "Pitzki's hands rest on the table edge; he leans a little toward the viewer with a curious, waiting smile, eyebrows up, as if asking a question.", 2),
        Frame("ep1_f08", "Pitzki claps his hands together happily in front of his chest, a huge smile. The two apples stay on the table.", 2),
        Frame("ep1_f09", "Pitzki jumps for joy behind the table, both feet off the ground, both arms up high. The two apples stay on the table.", 2),
        Frame("ep1_f10", "Pitzki stands behind the table and waves goodbye to the viewer with his right hand, smiling warmly. The two apples stay on the table.", 2),
    )

    private const val STILL = " The camera does not move, pan or zoom at all. The table stays exactly in place. His hands stay normal hands with five fingers. " +
        "No apple appears from nowhere, disappears, splits or merges. Pitzki's mouth stays in a closed smile; nobody talks in the picture."

    val SHOTS = listOf(
        Shot("ep1_s01", 0, 1, "Pitzki walks happily from the left to behind the table, stops and waves hello to the viewer." + STILL,
            "שלום חברים! בואו נלמד משהו חדש ביחד!", ""),
        Shot("ep1_s02", 1, 2, "Pitzki bends down behind the table, takes a red apple from below and lifts it up to show the viewer." + STILL,
            "תראו, יש לי תפוח אחד.", ""),
        Shot("ep1_s03", 2, 3, "Pitzki slowly lowers the apple and gently puts it down on the left side of the table, then rests his hands on the table edge." + STILL,
            "תפוח אחד על השולחן.", ""),
        Shot("ep1_s04", 3, 3, "Pitzki looks at the apple and back at the viewer, nodding gently, his hands resting on the table edge." + STILL,
            "הנה המספר אחד.", "1"),
        Shot("ep1_s05", 3, 4, "Pitzki bends down behind the table, takes one more red apple from below and lifts it up to show the viewer; the first apple stays on the table." + STILL,
            "ועכשיו… עוד תפוח אחד!", "1"),
        Shot("ep1_s06", 4, 5, "Pitzki slowly lowers the apple and gently puts it down on the table next to the first apple, then rests his hands on the table edge." + STILL,
            "שמים אותו ליד החבר שלו.", "1"),
        Shot("ep1_s07", 5, 5, "Pitzki looks from one apple to the other, thinking, then smiles at the viewer; his hands rest on the table edge." + STILL,
            "אחד… ועוד אחד…", "1 + 1"),
        Shot("ep1_s08", 5, 6, "Pitzki slowly touches the left apple with one finger, then the right apple, as if counting them." + STILL,
            "בואו נספור ביחד: אחת… שתיים!", "1 + 1"),
        Shot("ep1_s09", 6, 7, "Pitzki rests his hands on the table edge and leans a little toward the viewer, curious, waiting for an answer." + STILL,
            "כמה תפוחים יש על השולחן?", "1 + 1", waitMs = 3000),
        Shot("ep1_s10", 7, 8, "Pitzki claps his hands happily, smiling wide." + STILL,
            "נכון! שתיים! אחד ועוד אחד זה שתיים!", "1 + 1 = 2"),
        Shot("ep1_s11", 8, 9, "Pitzki jumps for joy behind the table with both arms up, and lands softly." + STILL,
            "כל הכבוד! אתם חכמים!", "1 + 1 = 2"),
        Shot("ep1_s12", 9, 10, "Pitzki waves goodbye to the viewer with a warm smile." + STILL,
            "נתראה בפעם הבאה. ביי!", "1 + 1 = 2"),
    )

    val FRAME_KEYS: List<String> get() = FRAMES.map { it.key }
    val SHOT_KEYS: List<String> get() = SHOTS.map { it.key }

    /** The first picture: from Pitzki's own picture only. */
    fun firstPrompt(): String = "The image is $PITZKI $PLACE ${FRAMES[0].change} Exactly ${FRAMES[0].apples} apples in the whole picture."

    /** Every next picture: drawn from the one before, changing only what this moment changes. */
    fun nextPrompt(i: Int): String {
        val f = FRAMES[i]
        val n = if (f.apples == 0) "NO apples anywhere in the picture." else "Exactly ${f.apples} big red apple${if (f.apples > 1) "s" else ""} in the whole picture, no others."
        return "The first image is $PITZKI The second image is the previous moment of the same scene: keep the clearing, the trees, the table " +
            "(same place and same size in the picture), the light, the camera, the framing and the zoom EXACTLY the same as in the second image. " +
            "Only Pitzki's pose and the apples change. Now: ${f.change} $n $PLACE"
    }

    /** The apple counts allowed during a shot (from its first picture's to its last's). */
    fun applesDuring(s: Shot): Set<Int> = setOf(FRAMES[s.from].apples, FRAMES[s.to].apples)
}
