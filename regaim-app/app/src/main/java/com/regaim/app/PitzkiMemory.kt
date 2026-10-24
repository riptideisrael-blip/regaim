package com.regaim.app

/**
 * What Pitzki remembers about a child, per question: how the last answer went (+1 right the first time, -1 not).
 * Kept on the phone per child ("id:1;id:-1"); the lesson is built from it: the review goes back to what was hard,
 * and when everything is known, a new question is added. No Android here (unit-tested).
 */
object PitzkiMemory {
    fun parse(raw: String): Map<String, Int> = raw.split(";").mapNotNull { p ->
        val id = p.substringBefore(":").trim(); val v = p.substringAfter(":", "").toIntOrNull()
        if (id.isEmpty() || v == null) null else id to v.coerceIn(-1, 1)
    }.toMap()

    fun write(m: Map<String, Int>): String = m.entries.joinToString(";") { "${it.key}:${it.value}" }

    fun record(m: Map<String, Int>, id: String, rightFirstTime: Boolean): Map<String, Int> =
        m + (id to if (rightFirstTime) 1 else -1)

    /** The last answer to any of [ids] (the question in the walk and in the review game): -1, 0 (never asked) or 1. */
    fun last(m: Map<String, Int>, ids: List<String>): Int = ids.mapNotNull { m[it] }.let { v -> if (v.isEmpty()) 0 else if (v.any { it < 0 }) -1 else 1 }
}
