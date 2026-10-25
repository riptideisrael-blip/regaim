package com.regaim.app

import java.util.Calendar
import java.util.Locale

/**
 * Picks the few memories a question is about, on the phone, before anything goes to the AI.
 * The AI then sees 15–20 moments instead of the whole catalog: it stays accurate at a thousand memories,
 * costs less, and sends less of the family's life anywhere.
 */
object Retrieval {
    const val LIMIT = 20

    class Picked(val memories: List<Memory>, val matched: Boolean)

    private val STOP = setOf(
        "תראי", "תראה", "לי", "את", "זיכרונות", "רגעים", "רגע", "של", "עם", "מה", "קרה", "היה", "היתה", "מי", "איפה", "מתי",
        "אני", "מצא", "למצוא", "האם", "אפשר", "יש", "כל", "על", "זה", "זאת", "כמה", "איך", "למה", "שלנו", "שלי",
        "show", "memories", "memory", "moments", "moment", "me", "my", "the", "with", "from", "what", "when", "where", "who",
        "find", "was", "were", "is", "a", "of", "in", "our", "all", "any", "how", "did",
    )

    /** Words of the question, with Hebrew one-letter prefixes (ו, ה, ב, ל, מ, ש, כ) also tried without them. */
    fun words(q: String): List<String> {
        val raw = q.lowercase(Locale.ROOT).split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 1 && it !in STOP }
        return raw.flatMap { w ->
            val out = mutableListOf(w)
            var x = w
            while (x.length > 3 && x[0] in "והבלמשכ") { x = x.substring(1); if (x !in STOP) out.add(x) }
            out
        }.distinct()
    }

    fun pick(question: String, all: List<Memory>, now: Long = System.currentTimeMillis()): Picked {
        if (all.isEmpty()) return Picked(emptyList(), false)
        val q = question.lowercase(Locale.ROOT)
        val ws = words(question)
        val range = timeRange(q, now)
        val wantsFirst = listOf("ראשון", "ראשונה", "לראשונה", "first").any { it in q }
        val scored = all.map { m ->
            var s = 0
            val names = m.allPeople.split(",").map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotEmpty() }
            if (names.any { n -> ws.any { it == n } }) s += 5
            if (m.place.isNotBlank() && ws.any { it in m.place.lowercase(Locale.ROOT) }) s += 4
            if (m.milestone.isNotBlank() && ws.any { it in m.milestone.lowercase(Locale.ROOT) }) s += 4
            if (m.category.isNotBlank() && ws.any { it == m.category.lowercase(Locale.ROOT) }) s += 3
            val text = listOf(m.title, m.description, m.tags, Labels.he(m.label)).joinToString(" ").lowercase(Locale.ROOT)
            s += 2 * ws.count { it.length > 2 && it in text }
            if (range != null && m.time in range) s += 3
            if (wantsFirst && m.milestone.isNotBlank()) s += 1
            m to s
        }
        val hits = scored.filter { it.second > 0 }
        if (hits.isEmpty()) {
            // Nothing in the question matches the records: the most recent moments (the AI is told so).
            return Picked(all.sortedByDescending { it.time }.take(LIMIT), false)
        }
        val ordered = hits.sortedWith(compareByDescending<Pair<Memory, Int>> { it.second }.thenByDescending { it.first.favorite }.thenByDescending { it.first.time })
        var chosen = ordered.take(LIMIT).map { it.first }
        if (wantsFirst) chosen = chosen.sortedBy { it.time }
        return Picked(chosen, true)
    }

    /** "היום", "אתמול", "השבוע", "החודש", "בחודש שעבר" → a time range. */
    private fun timeRange(q: String, now: Long): LongRange? {
        val c = Calendar.getInstance().apply { timeInMillis = now; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        val day = 86_400_000L
        val today = c.timeInMillis
        return when {
            "היום" in q || "today" in q -> today until today + day
            "אתמול" in q || "yesterday" in q -> (today - day) until today
            "השבוע" in q || "this week" in q -> (today - 7 * day) until today + day
            "בחודש שעבר" in q || "last month" in q -> (today - 60 * day) until (today - 28 * day)
            "החודש" in q || "this month" in q -> (today - 31 * day) until today + day
            else -> null
        }
    }
}
