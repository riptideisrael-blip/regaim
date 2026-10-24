package com.regaim.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decision layer on synthetic timelines that mirror the real test set (see the detection lab).
 * Sound samples every 0.25 s, frames every 0.5 s, like the scanner after its refine pass.
 */
class MomentFusionTest {
    private val dur = 60_000L

    /** Sound for the whole video: [base] everywhere, [at] inside the given seconds. */
    private fun sound(base: Heard = Heard(), vararg at: Pair<ClosedFloatingPointRange<Double>, Heard>): List<Pair<Long, Heard>> =
        (0..(dur / 250).toInt()).map { i ->
            val ms = i * 250L
            ms to (at.firstOrNull { ms / 1000.0 in it.first }?.second ?: base)
        }

    private fun frames(base: Seen = Seen(), vararg at: Pair<ClosedFloatingPointRange<Double>, Seen>): List<Pair<Long, Seen>> =
        (0..(dur / 500).toInt()).map { i ->
            val ms = i * 500L
            ms to (at.firstOrNull { ms / 1000.0 in it.first }?.second ?: base)
        }

    private val calm = Seen(faces = 1, smile = 0.1f, motion = 0.05f)
    private val talk = Heard(childVoice = 0.1f, burst = 0.1f)

    private fun run(heard: List<Pair<Long, Heard>>?, seen: List<Pair<Long, Seen>>) = MomentFusion.analyze(dur, heard, seen)

    private fun assertFoundAt(c: List<Candidate>, sec: Double, atLeast: Tier = Tier.GOOD): Candidate {
        val hit = c.firstOrNull { it.startMs - 1500 <= sec * 1000 && sec * 1000 <= it.endMs + 1500 }
        assertNotNull("no moment around ${sec}s in $c", hit)
        assertTrue("tier ${hit!!.tier} below $atLeast (rank ${hit.rank})", hit.tier.ordinal <= atLeast.ordinal)
        return hit
    }

    @Test fun case01_childLaughsWithoutTalking() {
        val c = run(sound(talk, 30.0..33.0 to Heard(laugh = 0.7f, burst = 0.8f, rhythm = 0.7f)), frames(calm))
        val m = assertFoundAt(c, 31.5)
        assertEquals(MomentType.LAUGHTER, m.primary)
        assertTrue("heard_laugh" in m.evidence)
    }

    @Test fun case02_laughDuringPlay() {
        val c = run(
            sound(talk, 20.0..24.0 to Heard(laugh = 0.55f, burst = 0.6f)),
            frames(calm, 18.0..25.0 to Seen(faces = 1, smile = 0.8f, mouthOpen = 0.6f, motion = 0.7f)),
        )
        val m = assertFoundAt(c, 22.0, Tier.STRONG)
        assertTrue(m.types.any { it.first == MomentType.PLAY })
    }

    @Test fun case03_motherTicklesChild_isOneEventWithTickling() {
        // 12s calm, 14s tickling starts (close + moving), 16s smile, 18s mouth opens, 19s laugh heard, until 24s.
        val c = run(
            sound(talk, 19.0..24.5 to Heard(laugh = 0.6f, burst = 0.7f, rhythm = 0.6f)),
            frames(
                calm,
                14.0..15.9 to Seen(faces = 2, smile = 0.2f, closeness = 0.8f, motion = 0.7f),
                16.0..17.9 to Seen(faces = 2, smile = 0.75f, mouthOpen = 0.2f, closeness = 0.8f, motion = 0.7f),
                18.0..24.5 to Seen(faces = 2, smile = 0.9f, mouthOpen = 0.7f, closeness = 0.8f, motion = 0.8f),
            ),
        )
        val inside = c.filter { it.endMs > 13_000 && it.startMs < 25_000 }
        assertEquals("one event, not several: $inside", 1, inside.size)
        val m = inside[0]
        assertEquals(Tier.STRONG, m.tier)
        assertTrue("starts with the tickling: ${m.startMs}", m.startMs <= 14_500)
        assertTrue(m.types.any { it.first == MomentType.TICKLE })
        assertTrue("tickle_pattern" in m.evidence)
    }

    @Test fun case04_fatherPlaysWithChild() {
        val c = run(
            sound(talk, 40.0..44.0 to Heard(childVoice = 0.6f, burst = 0.6f, laugh = 0.3f)),
            frames(calm, 38.0..46.0 to Seen(faces = 2, smile = 0.7f, closeness = 0.5f, facing = 0.6f, motion = 0.6f)),
        )
        val m = assertFoundAt(c, 42.0)
        assertTrue(m.types.any { it.first == MomentType.PLAY || it.first == MomentType.INTERACTION })
    }

    @Test fun case05_hugWithoutSound() {
        val c = run(sound(talk), frames(calm, 10.0..14.0 to Seen(faces = 2, smile = 0.3f, closeness = 0.95f, motion = 0.1f)))
        val m = assertFoundAt(c, 12.0)
        assertEquals(MomentType.AFFECTION, m.primary)
    }

    @Test fun case06_smileOnly() {
        val c = run(sound(talk), frames(calm, 25.0..28.0 to Seen(faces = 1, smile = 0.92f, mouthOpen = 0.1f)))
        val m = assertFoundAt(c, 26.5)
        assertEquals(MomentType.JOY, m.primary)
    }

    @Test fun case07_laughHeardButHardToSee() {
        val c = run(sound(talk, 30.0..32.5 to Heard(laugh = 0.6f, burst = 0.6f)), frames(Seen()))
        val m = assertFoundAt(c, 31.0)
        assertTrue("no_faces" in m.evidence)
        assertEquals(null, m.visual)
    }

    @Test fun case08_laughingFaceBarelyHeard() {
        val c = run(sound(talk), frames(calm, 30.0..33.0 to Seen(faces = 1, smile = 0.9f, mouthOpen = 0.8f)))
        val m = assertFoundAt(c, 31.5)
        assertTrue(m.types.any { it.first == MomentType.LAUGHTER })
    }

    @Test fun case09_musicIsNotLaughter() {
        val c = run(sound(Heard(music = 0.9f, rhythm = 0.8f, burst = 0.5f)), frames(calm))
        assertTrue("music alone found $c", c.isEmpty())
    }

    @Test fun case10_backgroundNoiseIsNotAMoment() {
        val c = run(sound(Heard(childVoice = 0.35f, burst = 0.05f)), frames(calm))
        assertTrue("noise found $c", c.none { it.tier != Tier.MAYBE })
    }

    @Test fun case11_noSoundTrack() {
        val c = run(null, frames(calm, 5.0..8.0 to Seen(faces = 1, smile = 0.9f, mouthOpen = 0.6f)))
        val m = assertFoundAt(c, 6.5)
        assertTrue("no_audio" in m.evidence)
        assertEquals(null, m.audio)
    }

    @Test fun case12_manyPeople() {
        val c = run(
            sound(talk, 50.0..53.0 to Heard(laugh = 0.5f, cheer = 0.4f, burst = 0.7f)),
            frames(Seen(faces = 4, smile = 0.2f), 49.0..54.0 to Seen(faces = 4, smile = 0.85f, mouthOpen = 0.5f, closeness = 0.4f)),
        )
        assertFoundAt(c, 51.5, Tier.STRONG)
    }

    @Test fun case13_runningAndLaughing() {
        val c = run(
            sound(talk, 8.0..12.0 to Heard(laugh = 0.45f, childVoice = 0.5f, burst = 0.6f)),
            frames(calm, 7.0..12.0 to Seen(faces = 1, smile = 0.6f, motion = 0.9f)),
        )
        assertFoundAt(c, 10.0)
    }

    @Test fun case14_noRealEmotion() {
        val c = run(sound(Heard(childVoice = 0.15f, burst = 0.15f)), frames(Seen(faces = 2, smile = 0.15f, closeness = 0.2f, motion = 0.15f)))
        assertTrue("nothing should be found: $c", c.isEmpty())
    }

    @Test fun cryingCancelsTheLaugh() {
        val c = run(sound(talk, 20.0..23.0 to Heard(laugh = 0.35f, cry = 0.8f, burst = 0.8f)), frames(calm))
        assertTrue(c.none { it.tier == Tier.STRONG })
    }

    @Test fun walkingCameraAloneIsNotAMoment() {
        val c = run(sound(talk), frames(Seen(faces = 0, motion = 1f)))
        assertTrue(c.isEmpty())
    }

    @Test fun clipHasLeadUpAndAfter() {
        val (s, e) = MomentFusion.clip(42_000, 48_000, 45_000, 72_000)
        assertTrue("lead-up $s", 48_000 - s >= 6_000 && 42_000 - s in 8_000..12_000)
        assertTrue("after $e", e - 48_000 in 5_000..8_000)
        val (s2, e2) = MomentFusion.clip(1_000, 3_000, 2_000, 5_000)
        assertEquals(0L, s2); assertEquals(5_000L, e2)
        val (s3, e3) = MomentFusion.clip(10_000, 90_000, 60_000, 120_000)
        assertTrue(e3 - s3 <= MomentFusion.MAX_CLIP_MS && 60_000 in s3..e3)
    }

    @Test fun twoMomentsFarApartStaySeparate() {
        val c = run(
            sound(talk, 10.0..12.0 to Heard(laugh = 0.7f, burst = 0.8f), 45.0..47.0 to Heard(laugh = 0.7f, burst = 0.8f)),
            frames(calm),
        )
        assertEquals(2, c.size)
    }

    @Test fun longerSharedMomentRanksAboveAOneSecondSmile() {
        val c = run(
            sound(talk, 30.0..40.0 to Heard(laugh = 0.6f, burst = 0.7f)),
            frames(
                calm,
                5.0..5.9 to Seen(faces = 1, smile = 0.95f),
                30.0..40.0 to Seen(faces = 2, smile = 0.8f, mouthOpen = 0.5f, closeness = 0.7f, motion = 0.4f),
            ),
        )
        assertTrue(c.size >= 2)
        assertTrue("long shared laugh first: $c", c.first().startMs in 28_000..32_000)
    }

    @Test fun familyFeedbackMovesTheRank() {
        val heard = sound(talk, 30.0..32.0 to Heard(laugh = 0.45f, burst = 0.5f))
        val base = MomentFusion.analyze(dur, heard, frames(calm)).first()
        val learned = MomentFusion.learn(List(6) { base.pattern to 1 })
        val boosted = MomentFusion.analyze(dur, heard, frames(calm), MomentFusion.Config(learned = learned)).first()
        assertTrue(boosted.rank > base.rank)
        val lowered = MomentFusion.analyze(dur, heard, frames(calm), MomentFusion.Config(learned = MomentFusion.learn(List(6) { base.pattern to -1 }))).firstOrNull()
        assertTrue(lowered == null || lowered.rank < base.rank)
        assertTrue(MomentFusion.learn(List(3) { "x" to 1 }).isEmpty())
    }
}

class LabScoreTest {
    private fun cand(start: Long, end: Long, tier: Tier) = Candidate(start, end, start, 0, end, 0.5f, 0.5f, tier, 0.5f, null, 0f, 0.5f, listOf(MomentType.LAUGHTER to 0.5f), emptyList())

    @Test fun countsHitsMissesAndFalseAlarms() {
        val cases = listOf(
            LabScore.Case(true, 10_000, listOf(cand(9_000, 12_000, Tier.STRONG))),   // hit
            LabScore.Case(true, 40_000, listOf(cand(9_000, 12_000, Tier.STRONG))),   // found the wrong place: miss
            LabScore.Case(true, -1, listOf(cand(1_000, 2_000, Tier.MAYBE))),        // only "maybe"
            LabScore.Case(false, -1, listOf(cand(1_000, 2_000, Tier.MAYBE))),       // a maybe on a no-moment video
            LabScore.Case(false, -1, emptyList()),
        )
        val good = LabScore.metrics(cases, Tier.GOOD)
        assertEquals(1, good.tp); assertEquals(2, good.fn); assertEquals(0, good.fp); assertEquals(2, good.tn)
        val any = LabScore.metrics(cases, Tier.MAYBE)
        assertEquals(2, any.tp); assertEquals(1, any.fn); assertEquals(1, any.fp); assertEquals(1, any.tn)
        assertEquals(2f / 3f, any.recall, 0.001f)
        assertEquals(0.5f, any.falsePositiveRate, 0.001f)
    }

    @Test fun aStricterCutoffTradesRecallForFewerFalseAlarms() {
        fun c(start: Long, conf: Float) = Candidate(start, start + 2_000, start, 0, start + 2_000, conf, conf, Tier.MAYBE, conf, null, 0f, conf, listOf(MomentType.LAUGHTER to conf), emptyList())
        val cases = listOf(
            LabScore.Case(true, 5_000, listOf(c(4_000, 0.7f))),  // a clear moment
            LabScore.Case(true, 5_000, listOf(c(4_000, 0.4f))),  // a weak real moment
            LabScore.Case(false, -1, listOf(c(1_000, 0.35f))),   // a weak false alarm
        )
        val loose = LabScore.metricsAt(cases, 0.30f)
        assertEquals(2, loose.tp); assertEquals(1, loose.fp)
        val strict = LabScore.metricsAt(cases, 0.50f)
        assertEquals(1, strict.tp); assertEquals(1, strict.fn); assertEquals(0, strict.fp); assertEquals(1, strict.tn)
    }
}
