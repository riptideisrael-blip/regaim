package com.regaim.app

import org.junit.Assert.assertEquals
import org.junit.Test

class SharedDaysTest {
    private data class M(val id: String, val t: Long)
    private data class P(val memoryId: String, val t: Long)
    private val day = 24L * 3600 * 1000
    private val base = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).parse("2026-10-23 12:00")!!.time

    @Test fun mergesByDayNewestFirst() {
        val local = listOf(M("a", base), M("b", base + 60_000))
        val shared = listOf(P("x", base + 120_000), P("y", base - day), P("a", base + 5))
        val days = SharedDays.merge(local, shared, { it.t }, { it.t }, { it.id }, { it.memoryId })
        assertEquals(listOf("2026-10-23", "2026-10-22"), days.map { it.key })
        assertEquals(listOf("b", "a"), days[0].local.map { it.id })
        // The other parent's copy of this phone's own moment "a" isn't shown twice.
        assertEquals(listOf("x"), days[0].shared.map { it.memoryId })
        assertEquals(3, days[0].count)
        assertEquals(1, days[1].count)
        assertEquals(base + 120_000, days[0].time({ it.t }, { it.t }))
    }

    @Test fun onlySharedOrOnlyLocal() {
        assertEquals(1, SharedDays.merge(emptyList<M>(), listOf(P("", base)), { it.t }, { it.t }, { it.id }, { it.memoryId }).size)
        assertEquals(0, SharedDays.merge(emptyList<M>(), emptyList<P>(), { it.t }, { it.t }, { it.id }, { it.memoryId }).size)
    }
}
