package com.regaim.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReelEditorTest {
    private val hour = 3_600_000L
    private var n = 0
    private fun photo(t: Long, faces: Int = 1, smile: Float = 0.5f, look: Long = 0L, mod: (ReelInput) -> ReelInput = { it }): ReelInput {
        n++
        val l = if (look != 0L) look else n * -7046029254386353131L
        return mod(ReelInput("p$n", false, 0, t, faces = faces, smile = smile, look = l))
    }
    private fun video(t: Long, dur: Long = 15_000, peak: Long = 6_000, laugh: Float = 0.7f, faces: Int = 1): ReelInput {
        n++
        return ReelInput("v$n", true, dur, t, peakMs = peak, faces = faces, laugh = laugh, voice = laugh, motion = 0.5f, look = n * 0x2545F4914F6CDD1DL)
    }

    private fun week(): List<ReelInput> = buildList {
        add(video(1 * hour, laugh = 0.9f, faces = 2))
        add(video(20 * hour, laugh = 0.6f))
        add(photo(30 * hour, faces = 3, smile = 0.8f) { it.copy(hug = 0.8f) })
        add(photo(40 * hour, faces = 1, smile = 0.9f) { it.copy(favorite = true) })
        add(photo(50 * hour, faces = 2, smile = 0.6f) { it.copy(outdoor = true, motion = 0.6f) })
        add(video(60 * hour, laugh = 0.5f, faces = 2))
        add(photo(70 * hour, faces = 1, smile = 0.7f))
        add(photo(80 * hour, faces = 0, smile = 0f)) // a thing, no people
        add(photo(90 * hour) { it.copy(screenshot = true) })
        add(photo(100 * hour) { it.copy(sharpness = 0.1f) })
        // A burst: 20 nearly identical photos of one moment.
        val burstLook = 0x0F0F_0F0F_0F0F_0F0FL
        repeat(20) { k -> add(photo(110 * hour + k * 2_000L, faces = 2, smile = 0.7f + k * 0.005f, look = burstLook xor (1L shl (k % 4)))) }
    }

    @Test fun junkIsLeftOut() {
        val plan = ReelEditor.plan(week(), ReelStyle.HAPPY)
        assertEquals("screenshot", plan.rejected.values.firstOrNull { it == "screenshot" })
        assertTrue(plan.rejected.containsValue("blurry"))
        val burstIn = plan.shots.count { it.id.startsWith("p") && it.id.removePrefix("p").toInt() > 10 }
        assertTrue("a burst gives at most 2, got $burstIn", burstIn <= 2)
    }

    @Test fun qualityOverQuantity() {
        val weak = (1..30).map { photo(it * hour, faces = 0, smile = 0f) { i -> i.copy(sharpness = 0.5f) } }
        val strong = (1..6).map { video(200 * hour + it * hour, laugh = 0.9f, faces = 2) }
        val plan = ReelEditor.plan(weak + strong, ReelStyle.HAPPY)
        assertTrue("weak filler stayed out: ${plan.shots.map { it.id }}", plan.shots.all { it.id.startsWith("v") })
        assertTrue(plan.storyMs <= ReelEditor.MAX_STORY_MS)
    }

    @Test fun itTellsAStory() {
        val plan = ReelEditor.plan(week(), ReelStyle.EMOTIONAL)
        assertEquals(ReelRole.OPENER, plan.shots.first().role)
        assertEquals(ReelRole.CLOSER, plan.shots.last().role)
        assertTrue(plan.shots.any { it.role == ReelRole.PEAK })
        // The opener is the strongest moment of the week.
        assertEquals(plan.shots.maxOf { it.score }, plan.shots.first().score, 0.06f)
        assertEquals(0L, plan.shots.first().fadeInMs) // no fade from black at the start
        assertTrue(plan.shots.last().fadeOutMs > 0)
    }

    @Test fun cutsLandOnTheBeatAndLengthsVary() {
        for (style in ReelStyle.values()) {
            val plan = ReelEditor.plan(week(), style)
            val beat = plan.beatMs
            plan.shots.forEach { s ->
                assertEquals("cut on beat ($style)", 0L, s.atMs % beat)
                assertTrue("length ${s.lengthMs} ($style)", s.lengthMs in 2 * beat..(6_500L))
            }
            assertTrue("not every shot the same length ($style)", plan.shots.map { it.lengthMs }.distinct().size > 1)
            val peak = plan.shots[plan.peakIndex]
            assertTrue("peak on a bar line or close ($style)", peak.atMs % (beat * 4) == 0L || plan.peakIndex == 0 || plan.shots[plan.peakIndex - 1].isVideo)
            assertTrue(plan.storyMs <= ReelEditor.MAX_STORY_MS)
        }
    }

    @Test fun aLongVideoGivesOnlyItsBestSeconds() {
        val v = video(0, dur = 30_000, peak = 18_000)
        val plan = ReelEditor.plan(listOf(v, video(hour), photo(2 * hour), photo(3 * hour)), ReelStyle.HAPPY)
        val s = plan.shots.first { it.id == v.id }
        assertTrue(s.lengthMs in 2_000L..5_500L)
        assertTrue("starts before the laugh", s.sourceStartMs < 18_000 && s.sourceStartMs + s.lengthMs > 18_000)
        assertTrue(s.keepSound)
    }

    @Test fun photosMoveGently() {
        val plan = ReelEditor.plan(week(), ReelStyle.CINEMATIC)
        val photos = plan.shots.filter { !it.isVideo }
        assertTrue(photos.all { it.motion != KenBurns.NONE && it.amount <= 0.08f })
        assertTrue(plan.shots.filter { it.isVideo }.all { it.motion == KenBurns.NONE })
    }

    @Test fun anotherVersionKeepsTheBestAndSwapsSomeOthers() {
        val big = (1..30).map { k -> if (k % 3 == 0) video(k * hour, laugh = 0.5f + k / 100f) else photo(k * hour, faces = 2, smile = 0.4f + k / 100f) }
        val a = ReelEditor.plan(big, ReelStyle.EMOTIONAL, seed = 0)
        val b = ReelEditor.plan(big, ReelStyle.EMOTIONAL, seed = 1)
        assertNotEquals(a.shots.map { it.id }.toSet(), b.shots.map { it.id }.toSet())
        assertEquals(a.shots.first().id, b.shots.first().id)
    }

    @Test fun nothingButScreenshotsMakesNoFilm() {
        val plan = ReelEditor.plan((1..5).map { photo(it * hour) { i -> i.copy(screenshot = true) } }, ReelStyle.HAPPY)
        assertTrue(plan.shots.isEmpty())
        assertFalse(plan.rejected.isEmpty())
    }

    @Test fun unmeasuredIsUnknownNotBlurryOrEmpty() {
        val ok = ReelInput("m1", false, 0, 0, faces = 0, sharpness = 0.1f)
        val unknown = ok.copy(id = "m2", measured = false)
        val rejected = HashMap<String, String>()
        ReelEditor.select(listOf(ok, unknown), ReelStyle.values().first(), rejected = rejected)
        assertEquals("blurry", rejected["m1"])
        assertFalse("a failed look must not reject the moment as blurry", "m2" in rejected)
        assertTrue("unknown faces are not halved like \"nobody in it\"", ReelEditor.score(unknown.copy(sharpness = 0.6f, smile = 0.6f)) > ReelEditor.score(ok.copy(sharpness = 0.6f, smile = 0.6f)))
    }
}
