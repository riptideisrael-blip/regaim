package com.regaim.app

/**
 * Levels: each world has a few, the same scenes with harder games. A walk where every question was answered right
 * the first time (a score of 100) moves the child up one level in that world; anything less stays (never down, never
 * "you failed"). No Android here (unit-tested).
 */
object PitzkiLevels {
    /** How many levels a world has (1: no levels yet). */
    fun max(world: String): Int = when (world) {
        NumbersWorld.ID -> NumbersWorld.LEVELS
        FarmWorld.ID -> FarmWorld.LEVELS
        PlayWorld.ID -> PlayWorld.LEVELS
        else -> 1
    }

    /** The walk's score, 0..100 (no questions: 100). */
    fun score(asked: Int, right: Int): Int = if (asked <= 0) 100 else (right.coerceIn(0, asked) * 100) / asked

    /** The level for the next walk: up one after a perfect walk (with questions in it), up to the world's last. */
    fun next(level: Int, max: Int, asked: Int, right: Int): Int =
        if (asked > 0 && right >= asked) (level + 1).coerceAtMost(max) else level.coerceIn(1, max)
}
