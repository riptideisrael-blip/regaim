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
}
