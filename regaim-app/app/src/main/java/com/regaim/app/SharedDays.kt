package com.regaim.app

/**
 * The shared gallery: this phone's moments and the other parent's, day by day (newest day first, newest moment
 * first in a day). A moment both phones have (the other parent shared one of this phone's own) shows once. No Android
 * here (unit-tested).
 */
object SharedDays {
    fun dayKey(t: Long): String = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(t))

    class Day<A, B>(val key: String, val local: List<A>, val shared: List<B>) {
        val count: Int get() = local.size + shared.size
        /** The day's first moment's time (for its title). */
        fun time(timeA: (A) -> Long, timeB: (B) -> Long): Long = (local.map(timeA) + shared.map(timeB)).maxOrNull() ?: 0L
    }

    fun <A, B> merge(
        local: List<A>, shared: List<B>,
        timeA: (A) -> Long, timeB: (B) -> Long,
        idA: (A) -> String, memoryIdB: (B) -> String,
    ): List<Day<A, B>> {
        val mine = local.map(idA).toSet()
        val others = shared.filter { memoryIdB(it).let { id -> id.isEmpty() || id !in mine } }
        val a = local.groupBy { dayKey(timeA(it)) }
        val b = others.groupBy { dayKey(timeB(it)) }
        return (a.keys + b.keys).sortedDescending().map { k ->
            Day(k, a[k].orEmpty().sortedByDescending(timeA), b[k].orEmpty().sortedByDescending(timeB))
        }
    }
}
