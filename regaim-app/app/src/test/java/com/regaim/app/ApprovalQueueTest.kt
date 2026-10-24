package com.regaim.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.regaim.app.CaptureVotes.Rules

class ApprovalQueueTest {

    private fun memory(id: String, review: String = "kept", analyzed: Boolean = false, time: Long = 0L) = Memory(
        id = id, uri = "", name = "", time = time, label = "Laughter", lat = null, lon = null, place = "", thumb = "",
        durationMs = 10_000, analyzed = analyzed, review = review,
    )

    // ---------- detector votes ----------

    @Test fun underEightVotesNothingChanges() {
        assertEquals(1f, Rules.multiplier(0, 7), 0.0001f)
        assertEquals(0.3f, Rules.effective(0.3f, 0, 7), 0.0001f)
        assertEquals(0.3f, Rules.effective(0.3f, 7, 0), 0.0001f)
    }

    @Test fun mostlyThumbsDownRaisesTheEffectiveBar() {
        assertTrue(Rules.effective(0.3f, 2, 6) > 0.3f) // 75% down at 8 votes
        assertEquals(0.3f * 1.15f, Rules.effective(0.3f, 2, 6), 0.0001f)
        assertTrue(Rules.effective(0.3f, 1, 11) > Rules.effective(0.3f, 2, 6)) // more votes, another step
    }

    @Test fun mostlyThumbsUpLowersTheEffectiveBar() {
        assertTrue(Rules.effective(0.3f, 6, 2) < 0.3f) // 75% up
        assertEquals(0.3f * 0.9f, Rules.effective(0.3f, 6, 2), 0.0001f)
    }

    @Test fun mixedVotesChangeNothing() {
        assertEquals(0.3f, Rules.effective(0.3f, 4, 4), 0.0001f)
        assertEquals(0.3f, Rules.effective(0.3f, 3, 5), 0.0001f) // 62% down: below 70%
    }

    @Test fun ceilingAndFloorHold() {
        assertEquals(Rules.CEILING, Rules.effective(0.3f, 0, 400), 0.0001f)
        assertEquals(Rules.FLOOR, Rules.effective(0.3f, 400, 0), 0.0001f)
        assertEquals(0.75f, Rules.CEILING, 0f)
        assertEquals(0.2f, Rules.FLOOR, 0f)
    }

    @Test fun theUsersOwnSliderIsNeverOverridden() {
        // Votes never push the bar past the slider in the "wrong" direction, and the slider value itself isn't touched.
        assertEquals(0.9f, Rules.effective(0.9f, 0, 400), 0.0001f) // a slider above the ceiling stays the slider
        assertEquals(0.1f, Rules.effective(0.1f, 400, 0), 0.0001f) // a slider below the floor stays the slider
        val slider = 0.3f
        Rules.effective(slider, 0, 50)
        assertEquals(0.3f, slider, 0f)
    }

    @Test fun visualTriggersGetALongerWindowNotAModelChange() {
        assertEquals(60_000L, Rules.visualWindowMs(0, 7))
        assertEquals(120_000L, Rules.visualWindowMs(1, 9))
        assertEquals(Rules.VISUAL_MAX_MS, Rules.visualWindowMs(0, 500))
        assertEquals(Rules.VISUAL_MIN_MS, Rules.visualWindowMs(9, 1)) // mostly 👍: a little more often, never under 30 s
    }

    // ---------- global + personal ----------

    @Test fun withoutLocalVotesTheGlobalBarApplies() {
        assertEquals(0.3f * 1.15f, Rules.effective(0.3f, 1.15f, 0, 0), 0.0001f)
        assertEquals(0.3f * 1.15f, Rules.effective(0.3f, 1.15f, 3, 4), 0.0001f) // under 8 local votes
    }

    @Test fun atTwentyLocalVotesTheGlobalNoLongerMatters() {
        assertEquals(Rules.effective(0.3f, 1f, 10, 10), Rules.effective(0.3f, 1.6f, 10, 10), 0.0001f)
        assertEquals(Rules.effective(0.3f, 1f, 2, 18), Rules.effective(0.3f, 0.7f, 2, 18), 0.0001f)
    }

    @Test fun betweenEightAndTwentyTheyBlend() {
        val g = 1.3f
        val mid = Rules.combined(g, 3, 7) // 10 votes, 70% down: personal 1.15, weight 0.5
        assertEquals(0.5f * g + 0.5f * g * 1.15f, mid, 0.0001f)
    }

    @Test fun underTwoHundredGlobalVotesTheGlobalMultiplierIsOne() {
        assertEquals(1f, Rules.globalMultiplier(0, 199), 0f)
        assertEquals(1.15f, Rules.globalMultiplier(50, 150), 0.0001f) // 75% down at 200
        assertEquals(0.9f, Rules.globalMultiplier(160, 40), 0.0001f) // 80% up
    }

    @Test fun globalLimitsHold() {
        assertEquals(Rules.CEILING, Rules.effective(0.3f, 4f, 0, 0), 0.0001f)
        assertEquals(Rules.FLOOR, Rules.effective(0.3f, 0.1f, 0, 0), 0.0001f)
    }

    @Test fun turningTheSwitchOffStopsSendingButNotLocalLearning() {
        assertFalse(Rules.sendsGlobally(helping = false, signedIn = true))
        assertFalse(Rules.sendsGlobally(helping = true, signedIn = false))
        assertTrue(Rules.sendsGlobally(helping = true, signedIn = true))
        val after = Rules.tally(emptyMap(), "Laughter", up = false)
        assertEquals(0 to 1, after["Laughter"])
        assertEquals(1 to 1, Rules.tally(after, "Laughter", up = true)["Laughter"])
    }

    @Test fun aVoteSendsOnlyTheLabelTheBucketAndTheVote() {
        val p = GlobalVotes.payload("Laughter", 0.47f, 1)
        assertEquals(setOf("p_label", "p_bucket", "p_vote"), p.keys().asSequence().toSet())
        assertEquals("0.4-0.6", p.getString("p_bucket"))
        assertEquals(-1, GlobalVotes.payload("Smile", -1f, -1).getInt("p_vote"))
        assertEquals("0.8+", GlobalVotes.bucket(0.93f))
        assertEquals("0.2-0.4", GlobalVotes.bucket(0.25f))
    }

    // ---------- the queue ----------

    @Test fun whatGoesToTheQueue() {
        assertTrue(QueueRules.goesToQueue(false, "Laughter"))
        assertTrue(QueueRules.goesToQueue(false, "Smile"))
        assertTrue(QueueRules.goesToQueue(false, "Approach"))
        assertFalse(QueueRules.goesToQueue(true, "Laughter"))
        assertFalse(QueueRules.goesToQueue(false, "manual"))
        assertFalse(QueueRules.goesToQueue(false, "Hug"))
        assertFalse(QueueRules.goesToQueue(false, "״אוהבת אותך״"))
        assertFalse(QueueRules.goesToQueue(false, "״שמור רגע״"))
    }

    @Test fun aMemoryWithoutTheFieldIsKeptAndNotApproved() {
        val old = Memory.fromJson(JSONObject().put("id", "old").put("time", 5L).put("label", "Laughter"))
        assertEquals("kept", old.review)
        assertFalse(old.approved)
        assertEquals(listOf("old"), MemoryStore.keptOf(listOf(old)).map { it.id })
        val back = Memory.fromJson(memory("p", review = "pending").copy(approved = false).toJson())
        assertTrue(back.isPending)
    }

    @Test fun pendingIsNotKeptAndNotAnalyzed() {
        val list = listOf(memory("a"), memory("p", review = "pending"), memory("b", analyzed = true))
        assertEquals(listOf("a", "b"), MemoryStore.keptOf(list).map { it.id })
        assertEquals(listOf("a"), MemoryStore.toAnalyze(list).map { it.id })
    }

    @Test fun unansweredQueueItemsExpireAfter36Hours() {
        val now = 100L * 3_600_000L
        assertFalse(QueueRules.expired(memory("p", review = "pending", time = now - 35L * 3_600_000L), now))
        assertTrue(QueueRules.expired(memory("p", review = "pending", time = now - 37L * 3_600_000L), now))
        assertFalse(QueueRules.expired(memory("k", time = 0L), now))
    }
}
