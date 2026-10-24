package com.regaim.app

/**
 * "Pitzki walks by the arrows": a clearing of stepping stones, Pitzki on one, a friend waiting on another. Each
 * arrow moves him one stone (forward = away from the child, back = toward); the frog jump takes him over one rock
 * or log, two stones at once. Rocks, mushrooms and the edge stop him ("oops!"). In planning mode the child lines up
 * the arrows first and then he walks them all (thinking ahead, like a first program). No Android here (unit-tested).
 */
object ArrowGame {
    const val COLS = 5
    const val ROWS = 4

    enum class Dir(val dx: Int, val dy: Int, val word: String) {
        FORWARD(0, -1, "קדימה!"), BACK(0, 1, "אחורה!"), LEFT(-1, 0, "שמאלה!"), RIGHT(1, 0, "ימינה!")
    }

    /** What stands on a stone: a rock or a mushroom stops him; a log can be jumped over. */
    enum class Thing { ROCK, MUSHROOM, LOG }
    /** Who waits at the end. */
    enum class Goal { ACORN, HEDGEHOG, HOUSE }

    data class Cell(val x: Int, val y: Int)
    data class Level(val start: Cell, val goal: Cell, val goalIs: Goal, val things: Map<Cell, Thing>, val say: String)

    /**
     * The board in words, for a screen reader: where Pitzki is, where he's going, what's in the way (columns from the
     * left, rows from the far end).
     */
    fun describe(lv: Level, at: Cell): String {
        fun where(c: Cell) = "טור ${c.x + 1} משמאל, שורה ${c.y + 1} מלמעלה"
        val goal = when (lv.goalIs) { Goal.ACORN -> "הבלוט"; Goal.HEDGEHOG -> "הקיפוד"; Goal.HOUSE -> "הבית" }
        val things = lv.things.entries.sortedWith(compareBy({ it.key.y }, { it.key.x })).joinToString(", ") { (c, t) ->
            (when (t) { Thing.ROCK -> "סלע"; Thing.MUSHROOM -> "פטרייה"; Thing.LOG -> "בול עץ (קופצים מעליו)" }) + " ב" + where(c)
        }
        return "לוח המשחק. פיצקי ב${where(at)}. צריך להגיע אל $goal, ב${where(lv.goal)}." +
            (if (things.isNotEmpty()) " בדרך: $things." else "") + " משתמשים בכפתורי החצים ובכפתור הקפיצה."
    }

    val LEVELS = listOf(
        Level(Cell(0, 3), Cell(4, 3), Goal.ACORN, emptyMap(), "ישר עד הבלוט!"),
        Level(Cell(0, 3), Cell(3, 0), Goal.HEDGEHOG, mapOf(Cell(1, 2) to Thing.MUSHROOM, Cell(2, 2) to Thing.ROCK), "הקיפוד מחכה רחוק. יש פנייה!"),
        Level(Cell(0, 3), Cell(4, 0), Goal.HOUSE,
            mapOf(Cell(1, 3) to Thing.ROCK, Cell(1, 2) to Thing.MUSHROOM, Cell(3, 1) to Thing.ROCK, Cell(3, 0) to Thing.MUSHROOM, Cell(2, 3) to Thing.ROCK),
            "הביתה! שימו לב לסלעים."),
        // Only the jump gets over the log.
        Level(Cell(2, 3), Cell(2, 0), Goal.ACORN,
            mapOf(Cell(2, 1) to Thing.LOG, Cell(1, 1) to Thing.ROCK, Cell(3, 1) to Thing.ROCK, Cell(0, 1) to Thing.MUSHROOM, Cell(4, 1) to Thing.MUSHROOM),
            "בול עץ בדרך! לוחצים על הצפרדע וקופצים מעליו."),
    )

    /** Where a step lands, or null (an edge, a rock, a mushroom, a log). */
    fun step(l: Level, at: Cell, d: Dir): Cell? {
        val to = Cell(at.x + d.dx, at.y + d.dy)
        if (to.x !in 0 until COLS || to.y !in 0 until ROWS) return null
        return if (l.things.containsKey(to)) null else to
    }

    /** Where a jump lands (two stones on, over anything but a rock wall at the landing), or null. */
    fun jump(l: Level, at: Cell, d: Dir): Cell? {
        val to = Cell(at.x + 2 * d.dx, at.y + 2 * d.dy)
        if (to.x !in 0 until COLS || to.y !in 0 until ROWS) return null
        return if (l.things.containsKey(to)) null else to
    }

    /** The fewest moves from start to goal (steps and jumps), or -1 if it can't be reached. */
    fun shortest(l: Level): Int {
        val seen = HashMap<Cell, Int>().apply { put(l.start, 0) }
        val q = ArrayDeque(listOf(l.start))
        while (q.isNotEmpty()) {
            val c = q.removeFirst()
            if (c == l.goal) return seen[c]!!
            for (d in Dir.values()) for (n in listOfNotNull(step(l, c, d), jump(l, c, d))) if (n !in seen) { seen[n] = seen[c]!! + 1; q.addLast(n) }
        }
        return -1
    }

    /** Pitzki's lines in this game (in his voice). */
    val LINES: List<String> get() = Dir.values().map { it.word } + listOf("אופס!", "הופ!", "הגענו!", "פיצקי נתקע. נתקן?", "עוד לא הגענו. עוד צעדים?") + LEVELS.map { it.say }
}
