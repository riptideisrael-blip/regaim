package com.regaim.app

/**
 * "What the child knows", for the parents: what Pitzki remembers ([PitzkiMemory]: the first answer to each
 * question), gathered by subject. Each thing is known (✓), still being learned (the last first answer was wrong) or
 * not met yet. No Android here (unit-tested).
 */
object PitzkiKnows {
    enum class State { KNOWN, LEARNING, NOT_YET }
    data class Item(val label: String, val state: State)
    data class Section(val title: String, val items: List<Item>) {
        val known: Int get() = items.count { it.state == State.KNOWN }
    }

    private fun state(m: Map<String, Int>, ids: List<String>) = when (PitzkiMemory.last(m, ids)) {
        1 -> State.KNOWN; -1 -> State.LEARNING; else -> State.NOT_YET
    }

    /** The color question's id (the forest's last game: "where's the red one?"). */
    fun colorId(c: PColor) = "c_" + c.name.lowercase()

    fun sections(m: Map<String, Int>): List<Section> = listOf(
        Section(L.t("🌲 צבעים", "🌲 Colors"), PColor.values().map { c -> Item(c.emoji + " " + c.he, state(m, listOf(colorId(c)))) }),
        Section(L.t("🐄 חיות וקולות", "🐄 Animals and sounds"), FarmWorld.ANIMALS.map { a -> Item(a.emoji + " " + a.he, state(m, listOf("f_${a.id}_pick", "f_game_${a.id}"))) }),
        Section(L.t("🚂 הפכים", "🚂 Opposites"), listOf(
            Item(L.t("גדול / קטן", "Big / small"), state(m, listOf("t_big_pick", "t_small_pick"))),
            Item(L.t("ארוך / קצר", "Long / short"), state(m, listOf("t_long_pick"))),
            Item(L.t("שמח / עצוב", "Happy / sad"), state(m, listOf("t_happy_pick", "t_sad_pick"))),
            Item(L.t("יום / לילה", "Day / night"), state(m, listOf("n_moon_pick"))),
            Item(L.t("ער / ישן", "Awake / asleep"), state(m, listOf("n_sleep_pick"))),
        )),
        Section(L.t("🔢 מספרים וחשבון", "🔢 Numbers and sums"), listOf(
            Item(L.t("מה בא אחרי", "What comes next"), state(m, m.keys.filter { it.startsWith("nu_after") })),
            Item(L.t("ספירה בנגיעה", "Counting by touching"), state(m, m.keys.filter { it.startsWith("nu_tap") && it.endsWith("_q") })),
            Item(L.t("זה ועוד זה", "Sums"), state(m, m.keys.filter { it.startsWith("nu_sum_") })),
        )),
        Section(L.t("🧩 מקשיב ומבצע", "🧩 Listening and doing"), listOf(
            Item(L.t("מחזיר כל חיה לבית שלה", "Takes each animal home"), state(m, m.keys.filter { it.startsWith("p_drag1") })),
            Item(L.t("מקשיב: רק החיה שביקשו", "Listens: only the one asked for"), state(m, m.keys.filter { it.startsWith("p_drag2") })),
            Item(L.t("גדול לבית גדול, קטן לקטן", "Big home, small home"), state(m, m.keys.filter { it.startsWith("p_drag3") })),
        )),
    )
}
