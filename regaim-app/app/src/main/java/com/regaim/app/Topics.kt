package com.regaim.app

import android.content.Context

/** Story topics made automatically from the moments: this week, each month, milestones, favorites, laughs, each person... */
object Topics {
    class Topic(val icon: String, val title: String, val moments: List<Memory>)

    fun all(ctx: Context): List<Topic> {
        val all = MemoryStore.kept(ctx)
        val list = mutableListOf<Topic>()
        fun add(icon: String, title: String, ms: List<Memory>) { if (ms.size >= 2) list.add(Topic(icon, title, ReelMaker.best(ms).take(12).sortedBy { it.time })) }
        add("✨", L.t("השבוע שלנו", "Our week"), ReelMaker.weekMoments(ctx))
        add("⭐", L.t("אבני דרך", "Milestones"), all.filter { it.milestone.isNotEmpty() })
        add("❤️", L.t("המועדפים שלנו", "Our favorites"), all.filter { it.favorite })
        add("😂", L.t("צחוקים", "Laughs"), all.filter { it.category == "צחוק" })
        for (name in all.flatMap { Fmt.names(it) }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(4).map { it.key }) {
            add("👤", name, all.filter { Fmt.names(it).contains(name) })
        }
        for (cat in listOf("משחק", "בחוץ", "משפחה", "אוכל")) add(Fmt.CATEGORY_EMOJI[cat] ?: "✨", L.category(cat), all.filter { it.category == cat })
        for ((_, ms) in all.groupBy { Fmt.monthKey(it.time) }.toList().sortedByDescending { it.first }.take(6)) {
            add("📅", Fmt.month(ms.first().time), ms)
        }
        return list
    }

    /** Albums: one per month, newest first. */
    fun months(ctx: Context): List<Pair<String, List<Memory>>> =
        MemoryStore.kept(ctx).groupBy { Fmt.monthKey(it.time) }.toList().sortedByDescending { it.first }
}
