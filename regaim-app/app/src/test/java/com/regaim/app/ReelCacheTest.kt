package com.regaim.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReelCacheTest {
    @Test fun oldEntriesWithoutOkCountAsMeasured() {
        assertTrue(ReelCache.measured(JSONObject().put("faces", 2)))
        assertFalse(ReelCache.measured(JSONObject().put("ok", false)))
    }

    @Test fun idsWithUnderscoresAreKept() {
        assertEquals("film_123", ReelCache.idOf("film_123_-98765_1"))
        assertEquals("abc", ReelCache.idOf("abc_42_1"))
    }

    @Test fun mergeKeepsBothRunsDropsGoneAndFailed() {
        val now = JSONObject().put("a_1_1", JSONObject().put("faces", 1)).put("gone_1_1", JSONObject().put("faces", 1))
        val fresh = JSONObject().put("b_2_1", JSONObject().put("ok", true)).put("c_3_1", JSONObject().put("ok", false))
        val m = ReelCache.merge(now, fresh, setOf("a", "b", "c"))
        val keys = m.keys().asSequence().toSet()
        assertEquals(setOf("a_1_1", "b_2_1"), keys)
    }
}
