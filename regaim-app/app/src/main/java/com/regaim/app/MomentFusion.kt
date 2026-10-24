package com.regaim.app

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * What the sound says at one point in a video. Every value is 0..1.
 * Filled by [MomentScanner] from the on-phone sound model (YAMNet) and from the raw signal.
 */
data class Heard(
    /** Laughter classes (laugh, giggle, baby laughter, chuckle...), already calibrated to 0..1. */
    val laugh: Float = 0f,
    /** Cheering, applause, clapping, whoops. */
    val cheer: Float = 0f,
    /** People singing (not a song playing). */
    val singing: Float = 0f,
    /** An excited child's voice: children shouting / playing, babbling. */
    val childVoice: Float = 0f,
    /** Crying, whimpering, screaming. */
    val cry: Float = 0f,
    /** Music playing. */
    val music: Float = 0f,
    /** Louder than this video usually is (a burst of voice). */
    val burst: Float = 0f,
    /** A pulsing "ha-ha-ha" rhythm (3–7 beats a second) in the loudness. */
    val rhythm: Float = 0f,
)

/** What one sampled frame shows. Every value is 0..1 except [faces]. */
data class Seen(
    /** Faces big enough to matter. */
    val faces: Int = 0,
    /** The strongest smile (a big face counts more than a tiny one far away). */
    val smile: Float = 0f,
    /** How open the smiling face's mouth is (a laughing face). */
    val mouthOpen: Float = 0f,
    /** Two faces near each other; 1 = cheek to cheek. */
    val closeness: Float = 0f,
    /** Two faces turned toward each other. */
    val facing: Float = 0f,
    /** How much the people / picture moved since the previous sample. */
    val motion: Float = 0f,
    /** A party scene (cake, balloons, candles...). */
    val party: Float = 0f,
)

/** Kinds of moments. One moment can be several at once ("play + laughter"); [label] is the memory label key. */
enum class MomentType(val key: String, val label: String) {
    LAUGHTER("laughter", "Laughter"),
    JOY("joy", "Joy"),
    AFFECTION("affection", "Hug"),
    PLAY("play", "Play"),
    TICKLE("tickle", "Tickle"),
    INTERACTION("interaction", "Together"),
    CELEBRATION("celebration", "Celebration"),
}

enum class Tier { STRONG, GOOD, MAYBE }

/**
 * One possible moment in a video: where it is, how sure we are, and why.
 * [audio] / [visual] are null when that sense had nothing to go on (no sound track, no face seen) — not the same as 0.
 */
data class Candidate(
    val startMs: Long,
    val endMs: Long,
    val peakMs: Long,
    val clipStartMs: Long,
    val clipEndMs: Long,
    val confidence: Float,
    /** [confidence] adjusted for how worth remembering it is (length, people together, what this family keeps). */
    val rank: Float,
    val tier: Tier,
    val audio: Float?,
    val visual: Float?,
    val interaction: Float,
    val temporal: Float,
    /** Every kind that fits, strongest first (never empty). */
    val types: List<Pair<MomentType, Float>>,
    /** Short keys for "why": heard_laugh, seen_smile, close, moving, tickle_pattern, no_audio... */
    val evidence: List<String>,
) {
    val primary: MomentType get() = types.first().first
    /** The kinds that clearly fit, e.g. "laughter+play" — what the family's feedback is learned on. */
    val pattern: String get() = types.filter { it.second >= 0.45f }.take(2).map { it.first.key }.sorted().joinToString("+").ifEmpty { primary.key }
}

/**
 * Evidence fusion over time: the decision layer that turns what was heard and seen into moments.
 * Pure logic (no Android), so it's covered by unit tests.
 *
 * 1. Everything is laid on a 0.5 s timeline. Sound comes every 0.25 s; frames come every ~1 s (denser near
 *    suspected moments), and a frame "holds" for up to a second around it.
 * 2. Each step gets a happiness score by noisy-OR — any strong sign alone can carry it, a few weaker signs add up,
 *    and nothing has to be present (no sound track, faces turned away).
 * 3. Steps above a low bar become events; events less than 3 s apart merge into one (a tickle → smile → laugh run
 *    is one moment, not six observations). The timeline is continuous, so nothing falls between windows.
 * 4. Each event is scored per sense, per kind, and over time (did it build up, last, do sound and picture agree),
 *    then ranked for how worth remembering it is, and given a clip with the lead-up and the after.
 */
object MomentFusion {
    const val VERSION = 1
    const val STEP_MS = 500L
    const val STRONG = 0.62f
    const val GOOD = 0.45f
    const val MAYBE = 0.28f
    private const val LOW = 0.2f
    private const val SEED = 0.3f
    private const val MERGE_GAP_MS = 3_000L
    private const val HOLD_FRAME_MS = 1_100L
    const val MAX_CLIP_MS = 45_000L

    /**
     * [sensitivity]: 1 = normal; lower finds more (and more misses), higher is stricter.
     * [learned]: multipliers by kind key or pattern from what this family kept or removed (see [learn]).
     */
    class Config(val sensitivity: Float = 1f, val learned: Map<String, Float> = emptyMap(), val maxCandidates: Int = 12)

    private class Step(
        val ms: Long,
        val heard: Heard?,
        val seen: Seen?,
        val laughA: Float,
        val joyA: Float,
        val audio: Float,
        val laughFace: Float,
        val smileV: Float,
        val visual: Float,
        val inter: Float,
        val play: Float,
        val score: Float,
    )

    fun analyze(durationMs: Long, heard: List<Pair<Long, Heard>>?, seen: List<Pair<Long, Seen>>, cfg: Config = Config()): List<Candidate> {
        if (durationMs <= 0L) return emptyList()
        val k = cfg.sensitivity.coerceIn(0.6f, 1.5f)
        val steps = timeline(durationMs, heard, seen)
        if (steps.isEmpty()) return emptyList()
        val musicMean = steps.mapNotNull { it.heard?.music }.let { if (it.isEmpty()) 0f else it.average().toFloat() }

        // Smooth one-step gaps (a laugh with a breath in it), keep spikes.
        val e = FloatArray(steps.size) { i ->
            val nb = ((steps.getOrNull(i - 1)?.score ?: 0f) + (steps.getOrNull(i + 1)?.score ?: 0f)) / 2f
            max(steps[i].score, nb * 0.9f)
        }
        // Runs of steps above the low bar, merged across short gaps, kept if they have a clear peak.
        val runs = ArrayList<IntRange>()
        var i = 0
        while (i < steps.size) {
            if (e[i] < LOW * k) { i++; continue }
            var j = i
            while (j + 1 < steps.size && e[j + 1] >= LOW * k) j++
            val last = runs.lastOrNull()
            if (last != null && steps[i].ms - steps[last.last].ms <= MERGE_GAP_MS) runs[runs.size - 1] = last.first..j else runs.add(i..j)
            i = j + 1
        }
        val events = runs.filter { r -> r.any { steps[it].score >= SEED * k } }

        val out = ArrayList<Candidate>()
        for (r in events) score(steps, r, durationMs, musicMean, heard != null, k, cfg)?.let { out.add(it) }

        // Best first; a moment whose clip is mostly inside a better one's clip is the same story.
        val kept = ArrayList<Candidate>()
        for (c in out.sortedByDescending { it.rank }) {
            if (kept.size >= cfg.maxCandidates) break
            val dup = kept.any { o ->
                val overlap = min(o.clipEndMs, c.clipEndMs) - max(o.clipStartMs, c.clipStartMs)
                overlap > 0 && overlap > 0.5f * min(o.clipEndMs - o.clipStartMs, c.clipEndMs - c.clipStartMs)
            }
            if (!dup) kept.add(c)
        }
        return kept
    }

    private fun timeline(durationMs: Long, heard: List<Pair<Long, Heard>>?, seen: List<Pair<Long, Seen>>): List<Step> {
        val n = (ceil(durationMs.toDouble() / STEP_MS).toInt() + 1).coerceAtMost(200_000)
        // Sound: every sample into its 0.5 s bin, strongest of each field.
        val bins = arrayOfNulls<Heard>(n)
        heard?.forEach { (ms, h) ->
            val b = ((ms + STEP_MS / 2) / STEP_MS).toInt().coerceIn(0, n - 1)
            val o = bins[b]
            bins[b] = if (o == null) h else Heard(
                max(o.laugh, h.laugh), max(o.cheer, h.cheer), max(o.singing, h.singing), max(o.childVoice, h.childVoice),
                max(o.cry, h.cry), max(o.music, h.music), max(o.burst, h.burst), max(o.rhythm, h.rhythm),
            )
        }
        // A bin with no sound sample of its own takes its neighbour's (sound is every 0.25 s, so this is rare).
        if (heard != null && heard.isNotEmpty()) for (b in 0 until n) if (bins[b] == null) bins[b] = bins.getOrNull(b - 1) ?: bins.getOrNull(b + 1)
        val frames = seen.sortedBy { it.first }
        var f = 0
        return (0 until n).map { idx ->
            val ms = (idx * STEP_MS).coerceAtMost(durationMs)
            while (f + 1 < frames.size && abs(frames[f + 1].first - ms) <= abs(frames[f].first - ms)) f++
            val s = frames.getOrNull(f)?.takeIf { abs(it.first - ms) <= HOLD_FRAME_MS }?.second
            step(ms, bins[idx], s)
        }
    }

    private fun step(ms: Long, h: Heard?, s: Seen?): Step {
        var laughA = 0f
        var joyA = 0f
        var audio = 0f
        if (h != null) {
            // The rhythm of a laugh helps only with a voice in it, and music has rhythm too.
            val rhythmLaugh = min(0.55f, h.rhythm * h.burst * (1f - h.music) * min(1f, 0.3f + 3f * (h.laugh + h.childVoice)))
            laughA = max(h.laugh, rhythmLaugh)
            joyA = maxOf(0.85f * h.cheer, 0.75f * h.singing * (1f - 0.7f * h.music), 0.6f * h.childVoice * max(h.burst, 0.3f))
            audio = max(laughA, joyA)
            // Crying louder than the laugh: not a happy moment.
            if (h.cry > max(laughA, 0.25f)) { audio *= 0.3f; laughA *= 0.3f; joyA *= 0.3f }
        }
        var laughFace = 0f
        var smileV = 0f
        var visual = 0f
        var inter = 0f
        var play = 0f
        if (s != null) {
            laughFace = smooth(0.3f, 0.85f, s.smile) * smooth(0.25f, 0.75f, s.mouthOpen)
            smileV = smooth(0.35f, 0.9f, s.smile)
            visual = max(0.9f * smileV, laughFace)
            inter = if (s.faces >= 2) max(s.closeness, 0.7f * s.facing) else 0f
            play = s.motion * if (s.faces >= 1) 1f else 0.5f
        }
        // Noisy-OR: any one strong sign can carry a moment; a few weak ones add up; none is required.
        // Picture motion alone (a walking camera) can never reach the seed bar.
        val score = noisyOr(0.85f * audio, 0.8f * visual, 0.45f * inter, 0.25f * play, 0.5f * (s?.party ?: 0f))
        return Step(ms, h, s, laughA, joyA, audio, laughFace, smileV, visual, inter, play, score)
    }

    private fun score(steps: List<Step>, r: IntRange, durationMs: Long, musicMean: Float, hasAudio: Boolean, k: Float, cfg: Config): Candidate? {
        val ev = r.map { steps[it] }
        val withSound = ev.filter { it.heard != null }
        val withFrame = ev.filter { it.seen != null }
        val sawFaces = withFrame.any { it.seen!!.faces > 0 }
        val startMs = ev.first().ms
        val endMs = ev.last().ms + STEP_MS
        val len = endMs - startMs

        val audio = if (withSound.isEmpty()) null else topMean(withSound.map { it.audio })
        val laughTop = topMean(withSound.map { it.laughA })
        val cheerTop = topMean(withSound.map { it.heard!!.cheer })
        val singTop = topMean(withSound.map { it.heard!!.singing * (1f - 0.7f * it.heard.music) })
        val childTop = topMean(withSound.map { it.heard!!.childVoice })
        val visual = if (!sawFaces) null else topMean(withFrame.map { it.visual })
        val laughFaceTop = topMean(withFrame.map { it.laughFace })
        val smileTop = topMean(withFrame.map { it.smileV })
        val interaction = topMean(withFrame.map { it.inter }, 0.5f)
        val motionTop = topMean(withFrame.map { it.play })
        val partyTop = topMean(withFrame.map { it.seen!!.party })
        val motionMean = if (withFrame.isEmpty()) 0f else withFrame.map { it.seen!!.motion }.average().toFloat()
        val closeSteps = withFrame.count { it.seen!!.faces >= 2 && it.seen.closeness >= 0.6f }
        val togetherSteps = withFrame.count { it.inter >= 0.5f }

        // Hug / cuddle: faces pressed together for a couple of seconds, not a blur of movement.
        val affection = if (withFrame.isEmpty()) 0f else
            (closeSteps.toFloat() / withFrame.size) * smooth(1_000f, 4_000f, closeSteps * STEP_MS.toFloat()) * (1f - 0.4f * motionMean) +
                0.15f * smileTop * if (closeSteps > 0) 1f else 0f

        // Over time: does it last, did it build up from calm, do sound and picture agree on when.
        val sustain = ev.count { it.score >= SEED * k }.toFloat() / ev.size
        val beforeFrom = r.first - (3_000L / STEP_MS).toInt()
        val before = (max(0, beforeFrom) until r.first).map { steps[it].score }
        val onset = if (before.isEmpty()) 0.5f else ((ev.map { it.score }.average() - before.average()).toFloat() * 2f).coerceIn(0f, 1f)
        val aPeak = withSound.maxByOrNull { it.audio }
        val vPeak = withFrame.maxByOrNull { it.visual }
        val aOk = (aPeak?.audio ?: 0f) >= 0.3f
        val vOk = (vPeak?.visual ?: 0f) >= 0.3f
        val sync = when {
            aOk && vOk && abs(aPeak!!.ms - vPeak!!.ms) <= 2_000L -> 1f
            aOk && vOk -> 0.6f
            aOk || vOk -> 0.5f
            else -> 0.3f
        }
        val durF = smooth(1_000f, 6_000f, len.toFloat())
        val temporal = (0.3f * sustain + 0.25f * onset + 0.25f * sync + 0.2f * durF).coerceIn(0f, 1f)

        // Tickling / rough play: close together, lots of movement, and a laughing reaction — ideally in that order.
        val closeTop = max(interaction, topMean(withFrame.map { it.seen!!.closeness }))
        val reaction = maxOf(laughTop, laughFaceTop, childTop * 0.8f)
        var tickle = if (closeTop >= 0.4f && motionTop >= 0.3f) sqrt(closeTop * motionTop) * reaction else 0f
        if (tickle > 0f) {
            val firstPlay = withFrame.firstOrNull { it.inter >= 0.4f || it.play >= 0.4f }?.ms
            val laughPeak = (withSound.maxByOrNull { it.laughA } ?: withFrame.maxByOrNull { it.laughFace })?.ms
            if (firstPlay != null && laughPeak != null && firstPlay <= laughPeak) tickle = min(1f, tickle * 1.15f)
        }

        val laughter = min(1f, max(laughTop, 0.85f * laughFaceTop) + if (laughTop >= 0.3f && laughFaceTop >= 0.3f) 0.1f else 0f)
        val joy = maxOf(0.9f * smileTop, 0.8f * cheerTop, 0.7f * laughter, 0.5f * childTop)
        val inter2 = if (togetherSteps * STEP_MS >= 2_000L) interaction else interaction * 0.7f
        val play = if (sawFaces) sqrt(motionTop * maxOf(joy, laughter, inter2)) else motionTop * 0.5f * max(laughter, joy)
        val celebration = maxOf(cheerTop, 0.85f * singTop, partyTop) * if (partyTop >= 0.4f && max(cheerTop, singTop) >= 0.3f) 1.15f else 1f
        val types = listOf(
            MomentType.LAUGHTER to laughter, MomentType.JOY to joy, MomentType.AFFECTION to affection.coerceIn(0f, 1f),
            MomentType.PLAY to play, MomentType.TICKLE to tickle,
            // A hug is more than "together": when it's clearly affection, that's the name.
            MomentType.INTERACTION to inter2 * (1f - 0.5f * affection.coerceIn(0f, 1f)),
            MomentType.CELEBRATION to celebration.coerceAtMost(1f),
        ).sortedByDescending { it.second }
        // Tickling is named only when the pattern is clear; otherwise it's "play".
        val shown = types.filter { it.second >= 0.3f && (it.first != MomentType.TICKLE || it.second >= 0.35f) }.ifEmpty { listOf(types.first { it.first != MomentType.TICKLE }) }

        // A laugh is the most specific sign there is, so it weighs a little more than other happy sounds.
        val fused = noisyOr(0.95f * laughTop, 0.85f * (audio ?: 0f), 0.8f * (visual ?: 0f), 0.45f * inter2, 0.25f * motionTop, 0.5f * partyTop, 0.5f * affection)
        val synergy = (if (aOk && vOk) 0.08f else 0f) + (if (tickle >= 0.3f) 0.1f * tickle else 0f)
        val confidence = (fused * (0.8f + 0.2f * temporal) + synergy).coerceIn(0f, 1f)

        val primary = shown.first().first
        val pattern = shown.filter { it.second >= 0.45f }.take(2).map { it.first.key }.sorted().joinToString("+").ifEmpty { primary.key }
        val learned = cfg.learned[pattern] ?: cfg.learned[primary.key] ?: 1f
        // Memory-worthiness: a ten-second laugh with mom beats a one-second smile at the camera.
        val rank = (confidence * (0.88f + 0.12f * durF) * (if (inter2 >= 0.4f || affection >= 0.4f) 1.06f else 1f) * learned).coerceIn(0f, 1f)
        val tier = when {
            rank >= STRONG * k -> Tier.STRONG
            rank >= GOOD * k -> Tier.GOOD
            rank >= MAYBE * k -> Tier.MAYBE
            else -> return null
        }

        val peak = ev.maxByOrNull { it.score }!!.ms
        val (clipStart, clipEnd) = clip(startMs, endMs, peak, durationMs)
        val why = ArrayList<String>()
        if (laughTop >= 0.3f) why.add("heard_laugh")
        if (cheerTop >= 0.35f) why.add("heard_cheer")
        if (singTop >= 0.35f) why.add("heard_singing")
        if (childTop >= 0.4f && laughTop < 0.3f) why.add("heard_child")
        if (laughFaceTop >= 0.35f) why.add("seen_laugh_face") else if (smileTop >= 0.4f) why.add("seen_smile")
        if (affection >= 0.4f) why.add("close") else if (inter2 >= 0.4f) why.add("together")
        if (motionTop >= 0.45f) why.add("moving")
        if (partyTop >= 0.4f) why.add("party")
        if (tickle >= 0.35f) why.add("tickle_pattern")
        if (!hasAudio) why.add("no_audio") else if (musicMean >= 0.5f) why.add("music")
        if (!sawFaces) why.add("no_faces")
        return Candidate(
            startMs, endMs, peak, clipStart, clipEnd, confidence, rank, tier,
            audio, visual, inter2, temporal, shown, why,
        )
    }

    /** The clip to keep: the lead-up before the moment and the after, so it's a little story, at most [MAX_CLIP_MS]. */
    fun clip(startMs: Long, endMs: Long, peakMs: Long, durationMs: Long): Pair<Long, Long> {
        val len = endMs - startMs
        val pre = (8_000L + len / 4).coerceIn(8_000L, 12_000L)
        val post = (5_000L + len / 4).coerceIn(5_000L, 8_000L)
        var s = (startMs - pre).coerceAtLeast(0L)
        var e = (endMs + post).coerceAtMost(durationMs)
        if (e - s > MAX_CLIP_MS) {
            s = (peakMs - (MAX_CLIP_MS * 0.6).toLong()).coerceAtLeast(0L)
            e = (s + MAX_CLIP_MS).coerceAtMost(durationMs)
            s = (e - MAX_CLIP_MS).coerceAtLeast(0L)
        }
        return s to e
    }

    /**
     * What the family's ❤️ / ✕ taught: a multiplier per kind and per pattern ("laughter+play"), 0.8..1.25.
     * Needs a few answers before it moves; feedback = (key, +1 kept / -1 removed).
     */
    fun learn(feedback: List<Pair<String, Int>>): Map<String, Float> =
        feedback.groupBy({ it.first }, { it.second }).mapNotNull { (key, votes) ->
            if (votes.size < 4) return@mapNotNull null
            val yes = votes.count { it > 0 }
            val rate = (yes + 1f) / (votes.size + 2f)
            key to (0.8f + (rate - 0.2f) / 0.6f * 0.45f).coerceIn(0.8f, 1.25f)
        }.toMap()

    /** Mean of the strongest part of a list (robust peak). */
    private fun topMean(v: List<Float>, frac: Float = 1f / 3f): Float {
        if (v.isEmpty()) return 0f
        val n = max(1, (v.size * frac).toInt())
        return v.sortedDescending().take(n).average().toFloat()
    }

    private fun noisyOr(vararg p: Float): Float {
        var none = 1f
        for (x in p) none *= 1f - x.coerceIn(0f, 1f)
        return 1f - none
    }

    fun smooth(a: Float, b: Float, x: Float): Float {
        val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}

/**
 * Scoring the detector against a labelled test set (the detection lab). Pure, so it's unit tested.
 * A case is a video with the truth: is there a real moment (and roughly when).
 */
object LabScore {
    class Case(val hasMoment: Boolean, val atMs: Long, val found: List<Candidate>)
    class Metrics(val tp: Int, val fn: Int, val fp: Int, val tn: Int) {
        val recall: Float get() = if (tp + fn == 0) 0f else tp.toFloat() / (tp + fn)
        val precision: Float get() = if (tp + fp == 0) 0f else tp.toFloat() / (tp + fp)
        val falsePositiveRate: Float get() = if (fp + tn == 0) 0f else fp.toFloat() / (fp + tn)
        val falseNegativeRate: Float get() = if (tp + fn == 0) 0f else fn.toFloat() / (tp + fn)
    }

    /** Did the detector find this case's moment at [level] or better (and at the right time, when it's known)? */
    fun hit(c: Case, level: Tier): Boolean = c.found.any { f ->
        f.tier.ordinal <= level.ordinal && (c.atMs < 0 || c.atMs in (f.startMs - 3_000L)..(f.endMs + 3_000L))
    }

    fun alarm(c: Case, level: Tier): Boolean = c.found.any { it.tier.ordinal <= level.ordinal }

    /**
     * The same measurement at a confidence cutoff instead of a tier, to compare thresholds on results already
     * saved (no re-run). Only cutoffs at or above the saved ones mean anything: weaker candidates weren't kept.
     */
    fun metricsAt(cases: List<Case>, minConfidence: Float): Metrics {
        var tp = 0; var fn = 0; var fp = 0; var tn = 0
        for (c in cases) {
            val kept = c.found.filter { it.confidence >= minConfidence }
            val hit = kept.any { f -> c.atMs < 0 || c.atMs in (f.startMs - 3_000L)..(f.endMs + 3_000L) }
            if (c.hasMoment) { if (hit) tp++ else fn++ } else { if (kept.isNotEmpty()) fp++ else tn++ }
        }
        return Metrics(tp, fn, fp, tn)
    }

    fun metrics(cases: List<Case>, level: Tier): Metrics {
        var tp = 0; var fn = 0; var fp = 0; var tn = 0
        for (c in cases) {
            if (c.hasMoment) { if (hit(c, level)) tp++ else fn++ } else { if (alarm(c, level)) fp++ else tn++ }
        }
        return Metrics(tp, fn, fp, tn)
    }
}
