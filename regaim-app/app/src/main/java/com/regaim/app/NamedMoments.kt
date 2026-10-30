package com.regaim.app

/**
 * Words about a moment only when we know who's in it. A moment where nobody was recognized (by face) or marked by
 * the parent gets no AI title, description or reason ("a child sitting at a table…" says nothing to a parent and may
 * be about the wrong child). It's still sorted and scored. Once someone is recognized or marked, it's described again.
 * Pure rules, unit tested.
 */
object NamedMoments {
    /** [Memory.descriptionSource] of a moment left without words because nobody in it was known. */
    const val UNNAMED = "unnamed"

    /** Someone in the moment is known: recognized by face, or marked by the parent (a pet counts). */
    fun named(m: Memory): Boolean = m.allPeople.isNotBlank()

    /** Left without words for want of a name, and now someone is known: describe it (again). */
    fun waitingForNames(m: Memory): Boolean = m.analyzed && m.descriptionSource == UNNAMED && named(m) && !m.descriptionByUser

    /** Described by the AI while nobody was known (before this rule): its words go. The parent's own words stay. */
    fun shouldClear(m: Memory): Boolean = m.analyzed && !named(m) && !m.descriptionByUser && m.descriptionSource == "sampled_images"

    fun clear(m: Memory): Memory = m.copy(description = "", descriptionSource = UNNAMED, title = if (m.titleByUser) m.title else "", reason = "")

    private val someone = Regex("ילד|ילדה|ילדים|תינוק|תינוקת|פעוט|אישה|גבר|אבא|אמא|הורה|אחות|סבא|סבתא|\\bchild|\\bkid|\\bbaby|\\bboy|\\bgirl|\\bman\\b|\\bwoman|\\btoddler|\\bmom\\b|\\bdad\\b")

    /** A tag about a person ("ילד", "אמא", "baby"): left out when nobody is known. */
    fun aboutSomeone(tag: String): Boolean = someone.containsMatchIn(tag.lowercase())

    /** Moments this close in time (the same walk around the house) are one "sequence". */
    const val SEQUENCE_GAP_MS = 10 * 60_000L

    /**
     * Who was recognized or marked in moments from the same few minutes as [m], most often first: a good guess for a
     * moment where only a back or legs are seen. A suggestion only; the parent confirms it.
     */
    fun suggest(m: Memory, all: List<Memory>, gap: Long = SEQUENCE_GAP_MS): List<String> {
        val near = all.filter { it.id != m.id && kotlin.math.abs(it.time - m.time) <= gap && named(it) }
        return near.flatMap { it.allPeople.split(",").map { n -> n.trim() }.filter { n -> n.isNotEmpty() } }
            .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }
    }

    /**
     * The other waiting moments of the same sequence as [m] (each within [gap] of the next), in [waiting]'s order.
     * One answer can then be given for all of them.
     */
    fun sameSequence(m: Memory, waiting: List<Memory>, gap: Long = SEQUENCE_GAP_MS): List<Memory> {
        val sorted = (waiting + m).distinctBy { it.id }.sortedBy { it.time }
        val i = sorted.indexOfFirst { it.id == m.id }
        var lo = i; var hi = i
        while (lo > 0 && sorted[lo].time - sorted[lo - 1].time <= gap) lo--
        while (hi < sorted.size - 1 && sorted[hi + 1].time - sorted[hi].time <= gap) hi++
        val ids = sorted.subList(lo, hi + 1).map { it.id }.toSet() - m.id
        return waiting.filter { it.id in ids }
    }

    private val hebrew = Regex("[\u05D0-\u05EA]")

    /** The AI's words on [m] are in the other language than the app's (written before the language changed). */
    fun wrongLanguage(m: Memory, english: Boolean): Boolean {
        if (!m.analyzed || m.titleByUser || m.descriptionByUser || !named(m)) return false
        val words = (m.title + " " + m.description).trim()
        if (words.isEmpty()) return false
        return hebrew.containsMatchIn(words) == english
    }
}
