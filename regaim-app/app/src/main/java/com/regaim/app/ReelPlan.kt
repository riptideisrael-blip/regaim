package com.regaim.app

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** The three edits a family can choose between. Each has its own pace, motion and score. */
enum class ReelStyle(val key: String, val bpm: Int, val maxMoments: Int) {
    EMOTIONAL("emotional", 72, 10),
    HAPPY("happy", 104, 15),
    CINEMATIC("cinematic", 84, 12),
}

/**
 * Everything the editor knows about one photo or video, measured on the phone (no cloud needed).
 * 0..1 values; 0 when unknown. [look] is a picture fingerprint (dHash), 0 when unknown.
 */
data class ReelInput(
    val id: String,
    val isVideo: Boolean,
    val durationMs: Long,
    val time: Long,
    /** Where the moment itself is in a video (the laugh, the hug), and its span if the detector found one. */
    val peakMs: Long = -1L,
    val eventStartMs: Long = -1L,
    val eventEndMs: Long = -1L,
    val faces: Int = 0,
    val named: Int = 0,
    val smile: Float = 0f,
    val laugh: Float = 0f,
    val hug: Float = 0f,
    val motion: Float = 0f,
    /** The clip's own sound is worth hearing (a laugh, voices). */
    val voice: Float = 0f,
    val favorite: Boolean = false,
    val milestone: Boolean = false,
    val chosen: Boolean = false,
    val approved: Boolean = false,
    /** The AI's 1–10 score, only when it said it was confident; else 0. */
    val aiScore: Int = 0,
    /** Birthday, party, celebration. */
    val special: Boolean = false,
    val outdoor: Boolean = false,
    /** 1 = sharp, 0 = a blur. */
    val sharpness: Float = 1f,
    /** False when the picture couldn't be looked at (faces and sharpness are unknown, not zero). */
    val measured: Boolean = true,
    val screenshot: Boolean = false,
    val look: Long = 0L,
)

enum class ReelRole { OPENER, EVERYDAY, ACTIVITY, PEAK, CLOSER }

enum class KenBurns { NONE, ZOOM_IN, ZOOM_OUT, PAN_LEFT, PAN_RIGHT }

/** One shot of the edit: which moment, which part of it, for how long, and how it moves. */
data class ReelShot(
    val id: String,
    val isVideo: Boolean,
    val role: ReelRole,
    /** In the source video (0 for photos). */
    val sourceStartMs: Long,
    val lengthMs: Long,
    /** Where it starts in the finished film. */
    val atMs: Long,
    val motion: KenBurns,
    /** How far the Ken Burns move goes (0.05 = 5%). */
    val amount: Float,
    val fadeInMs: Long,
    val fadeOutMs: Long,
    /** Hear the clip's own sound (music ducks under it). */
    val keepSound: Boolean,
    val score: Float,
)

data class ReelPlan(
    val style: ReelStyle,
    val shots: List<ReelShot>,
    val bpm: Int,
    /** Index in [shots] of the strong family moment the music builds to. */
    val peakIndex: Int,
    /** The small caption shown over the second shot. */
    val introCaption: String,
    val outroTitle: String,
    val outroSub: String,
    val outroMs: Long,
    val totalMs: Long,
    /** Moments that were good enough but left out (for "another version"). */
    val alternates: List<String>,
    val rejected: Map<String, String>,
    val seed: Int,
) {
    val beatMs: Long get() = 60_000L / bpm
    val storyMs: Long get() = totalMs - outroMs
}

/**
 * The family memory editor. Pure logic (unit tested), no Android:
 *  1. score every moment (people, faces, smiles, laughs, hugs, movement, what the family marked, quality),
 *  2. drop junk (screenshots, blurs, nothing-in-it) and near-duplicates (a burst of 20 → the best 1–2),
 *  3. keep 8–15 strong moments, never filling time with weak ones,
 *  4. order them as a story: the strongest first, everyday, activities, the big family moment, a calm ending,
 *  5. give each its own length (weak 1.5–2 s, photo 2–3 s, emotional 3–4 s, video 2–5 s), snapped to the beat,
 *     with the peak landing on a bar line so the music can rise into it,
 *  6. pick the 2–5 seconds of each video that matter (from just before the laugh starts), and a gentle move for photos.
 */
object ReelEditor {
    const val MIN_SCORE = 0.30f
    const val MIN_MOMENTS = 4
    const val MAX_STORY_MS = 60_000L
    const val DUP_WINDOW_MS = 90_000L
    const val DUP_HAMMING = 8
    const val BLUR_LIMIT = 0.25f

    fun score(i: ReelInput): Float {
        if (i.screenshot) return 0f
        val people = min(1f, i.faces * 0.5f + i.named * 0.3f)
        val expression = max(i.smile, i.laugh)
        val together = max(i.hug, if (i.faces >= 2) 0.5f else 0f)
        var s = 0.25f * people + 0.30f * expression + 0.15f * together + 0.08f * i.motion +
            (if (i.special) 0.10f else 0f) + (if (i.isVideo) 0.08f * i.voice else 0f)
        s += (if (i.milestone) 0.30f else 0f) + (if (i.favorite) 0.25f else 0f) + (if (i.chosen) 0.12f else 0f) +
            (if (i.approved) 0.06f else 0f) + (if (i.aiScore > 0) (i.aiScore - 5) / 5f * 0.10f else 0f)
        // A video earns its place when it carries what a photo can't: laughter, sound, movement, a hug.
        if (i.isVideo && maxOf(i.laugh, i.hug, i.motion, i.voice) >= 0.4f) s += 0.05f
        // Things only, nobody in it.
        if (i.measured && i.faces == 0 && i.named == 0 && !i.milestone && !i.favorite) s *= 0.5f
        s *= 0.4f + 0.6f * i.sharpness.coerceIn(0f, 1f)
        return s.coerceIn(0f, 1f)
    }

    private fun hamming(a: Long, b: Long) = java.lang.Long.bitCount(a xor b)

    /** Same moment again: a near-identical picture, or the same few minutes with a similar look. */
    fun similar(a: ReelInput, b: ReelInput): Boolean {
        if (a.look != 0L && b.look != 0L && hamming(a.look, b.look) <= DUP_HAMMING) return true
        return abs(a.time - b.time) <= DUP_WINDOW_MS && (a.look == 0L || b.look == 0L || hamming(a.look, b.look) <= DUP_HAMMING * 2)
    }

    /**
     * The moments worth keeping. A burst of the same moment gives at most 2 (the second only if nearly as good).
     * [seed] > 0 asks for another version: a few of the weaker picks are swapped for the next-best alternates.
     */
    fun select(all: List<ReelInput>, style: ReelStyle, seed: Int = 0, rejected: MutableMap<String, String> = HashMap()): Pair<List<ReelInput>, List<ReelInput>> {
        val scored = ArrayList<Pair<ReelInput, Float>>()
        for (i in all) {
            when {
                i.screenshot -> rejected[i.id] = "screenshot"
                i.measured && i.sharpness < BLUR_LIMIT && !i.favorite && !i.milestone -> rejected[i.id] = "blurry"
                i.isVideo && i.durationMs < 800 -> rejected[i.id] = "too short"
                else -> scored.add(i to score(i))
            }
        }
        scored.sortByDescending { it.second }
        val kept = ArrayList<Pair<ReelInput, Float>>()
        for ((i, s) in scored) {
            val same = kept.filter { similar(it.first, i) }
            when {
                same.size >= 2 -> rejected[i.id] = "duplicate"
                same.size == 1 && s < same[0].second * 0.9f -> rejected[i.id] = "duplicate"
                else -> kept.add(i to s)
            }
        }
        val good = kept.filter { it.second >= MIN_SCORE }
        // Quality over quantity: only good moments, but at least a few so there's a story.
        val pool = if (good.size >= MIN_MOMENTS) good else kept.take(MIN_MOMENTS)
        kept.filter { it !in pool }.forEach { rejected[it.first.id] = "weak" }
        var chosen = pool.take(style.maxMoments).map { it.first }
        var alternates = pool.drop(style.maxMoments).map { it.first }
        if (seed > 0 && alternates.isNotEmpty() && chosen.size > 3) {
            // Keep the 3 strongest; swap up to a quarter of the rest, rotating by the seed.
            val swap = min(alternates.size, max(1, (chosen.size - 3) / 4 + 1))
            val weak = chosen.drop(3)
            val out = (0 until swap).map { weak[(weak.size - 1 - (it + seed) % weak.size).coerceIn(0, weak.size - 1)] }.toSet()
            val inn = (0 until swap).map { alternates[(it + seed) % alternates.size] }.toSet()
            chosen = chosen.filter { it !in out } + inn
            alternates = alternates.filter { it !in inn } + out
        }
        return chosen to alternates
    }

    /** Story order: opener, everyday, activities, the big family moment, a calm ending. */
    fun story(sel: List<ReelInput>): List<Pair<ReelInput, ReelRole>> {
        if (sel.isEmpty()) return emptyList()
        val left = sel.toMutableList()
        // Open with the strongest moment, preferring one that moves and sounds.
        val opener = left.maxByOrNull { score(it) + if (it.isVideo && (it.laugh > 0.4f || it.voice > 0.4f)) 0.08f else 0f }!!
        left.remove(opener)
        if (left.isEmpty()) return listOf(opener to ReelRole.OPENER)
        // End calm and warm: a hug, a smile, little movement.
        val closer = left.maxByOrNull { it.hug * 0.6f + it.smile * 0.3f + (1f - it.motion) * 0.3f + score(it) * 0.5f }!!
        left.remove(closer)
        // The big family moment: people together, a celebration.
        val peak = left.maxByOrNull { (it.hug + min(1f, it.faces / 3f) + (if (it.special) 0.6f else 0f) + (if (it.milestone) 0.6f else 0f)) * (0.5f + score(it)) }
        if (peak != null) left.remove(peak)
        val everyday = left.filter { it.motion < 0.45f && !it.outdoor }.sortedBy { it.time }
        val activity = left.filter { it !in everyday }.sortedBy { it.time }
        return listOf(opener to ReelRole.OPENER) + everyday.map { it to ReelRole.EVERYDAY } +
            activity.map { it to ReelRole.ACTIVITY } + listOfNotNull(peak?.let { it to ReelRole.PEAK }) + listOf(closer to ReelRole.CLOSER)
    }

    /** How long a moment should stay before snapping to the beat. */
    fun targetMs(i: ReelInput, role: ReelRole, style: ReelStyle): Long {
        val s = score(i)
        val emotional = i.hug >= 0.4f || i.milestone || role == ReelRole.PEAK || role == ReelRole.CLOSER
        var ms = if (i.isVideo) {
            if (i.eventStartMs >= 0 && i.eventEndMs > i.eventStartMs) (i.eventEndMs - i.eventStartMs + 1_000L).coerceIn(2_000L, 5_000L)
            else if (i.laugh >= 0.4f || i.hug >= 0.4f) 3_500L else 2_800L
        } else when {
            emotional -> 3_500L
            s >= 0.55f -> 2_600L
            else -> 1_800L
        }
        ms = (ms * when (style) { ReelStyle.EMOTIONAL -> 1.15; ReelStyle.HAPPY -> 0.85; ReelStyle.CINEMATIC -> 1.1 }).toLong()
        if (role == ReelRole.OPENER) ms = max(ms, 2_500L)
        if (role == ReelRole.CLOSER) ms = max((ms * 1.25).toLong(), 3_000L)
        if (i.isVideo) ms = min(ms, max(i.durationMs, 1_000L))
        return ms
    }

    /** The 2–5 seconds of a video to use: from a little before the moment starts (a laugh's first breath). */
    fun sourceStart(i: ReelInput, lengthMs: Long): Long {
        if (!i.isVideo || i.durationMs <= lengthMs) return 0L
        val start = when {
            i.eventStartMs >= 0 -> i.eventStartMs - 500L
            i.peakMs >= 0 -> i.peakMs - max(700L, (lengthMs * 0.35).toLong())
            else -> (i.durationMs - lengthMs) / 2
        }
        return start.coerceIn(0L, i.durationMs - lengthMs)
    }

    fun plan(all: List<ReelInput>, style: ReelStyle, seed: Int = 0, monthLabel: String = "", hebrew: Boolean = true): ReelPlan {
        val rejected = HashMap<String, String>()
        val (sel, alternates) = select(all, style, seed, rejected)
        var order = story(sel)
        val beat = 60_000L / style.bpm
        val bar = beat * 4

        fun build(order: List<Pair<ReelInput, ReelRole>>): List<ReelShot> {
            val shots = ArrayList<ReelShot>()
            var at = 0L
            var photoN = seed
            val peakIdx = order.indexOfFirst { it.second == ReelRole.PEAK }.let { if (it < 0) order.size - 1 else it }
            order.forEachIndexed { idx, (i, role) ->
                // Cuts land on beats: every length is a whole number of beats (at least 2).
                var beats = max(2, (targetMs(i, role, style).toDouble() / beat).roundToInt())
                if (i.isVideo) while (beats > 2 && beats * beat > i.durationMs) beats--
                // The shot before the peak stretches so the peak starts on a bar line (where the music arrives).
                if (idx == peakIdx - 1) {
                    val end = at + beats * beat
                    val toBar = ((bar - end % bar) % bar) / beat
                    if (toBar in 1..3 && (!i.isVideo || (beats + toBar) * beat <= i.durationMs)) beats += toBar.toInt()
                }
                val len = beats * beat
                val motion = if (i.isVideo) KenBurns.NONE else {
                    val cycle = when (style) {
                        ReelStyle.EMOTIONAL -> listOf(KenBurns.ZOOM_IN, KenBurns.ZOOM_IN, KenBurns.ZOOM_OUT)
                        ReelStyle.HAPPY -> listOf(KenBurns.ZOOM_IN, KenBurns.ZOOM_OUT, KenBurns.PAN_LEFT, KenBurns.PAN_RIGHT)
                        ReelStyle.CINEMATIC -> listOf(KenBurns.PAN_LEFT, KenBurns.ZOOM_IN, KenBurns.PAN_RIGHT, KenBurns.ZOOM_OUT)
                    }
                    cycle[(photoN++) % cycle.size]
                }
                val amount = when (style) { ReelStyle.EMOTIONAL -> 0.05f; ReelStyle.HAPPY -> 0.07f; ReelStyle.CINEMATIC -> 0.06f }
                val sectionChange = role == ReelRole.PEAK || role == ReelRole.CLOSER
                val fadeIn = when {
                    idx == 0 -> 0L // start strong, no fade from black
                    style == ReelStyle.HAPPY -> 0L
                    sectionChange -> if (style == ReelStyle.CINEMATIC) 350L else 250L
                    else -> 0L
                }
                val fadeOut = when {
                    idx == order.size - 1 -> 900L
                    idx == peakIdx - 1 && style == ReelStyle.CINEMATIC -> 350L
                    else -> 0L
                }
                shots.add(ReelShot(
                    i.id, i.isVideo, role, sourceStart(i, len), len, at, motion, amount, fadeIn, fadeOut,
                    keepSound = i.isVideo && (i.laugh >= 0.35f || i.voice >= 0.4f), score = score(i),
                ))
                at += len
            }
            return shots
        }

        var shots = build(order)
        // Never stretch to fill time: over a minute, the weakest middle moments go.
        while (shots.sumOf { it.lengthMs } > MAX_STORY_MS && order.size > MIN_MOMENTS) {
            val weakest = order.withIndex().filter { it.value.second == ReelRole.EVERYDAY || it.value.second == ReelRole.ACTIVITY }
                .minByOrNull { score(it.value.first) } ?: break
            rejected[weakest.value.first.id] = "time"
            order = order.filterIndexed { k, _ -> k != weakest.index }
            shots = build(order)
        }
        val peakIndex = shots.indexOfFirst { it.role == ReelRole.PEAK }.let { if (it < 0) shots.size - 1 else it }
        // The closing card lasts about 3 seconds, a whole number of bars so the last chord lands with it.
        val outroMs = bar * max(1, (3_200.0 / bar).roundToInt())
        val intro = when (style) {
            ReelStyle.EMOTIONAL -> if (hebrew) "עוד שבוע ביחד ❤" else "Another week together ❤"
            ReelStyle.HAPPY -> if (hebrew) "השבוע שלנו" else "Our week"
            ReelStyle.CINEMATIC -> if (hebrew) "השבוע שלנו" else "Our week"
        }
        val outro = if (hebrew) "עוד שבוע שנשמור בלב ❤" else "Another week to keep in our hearts ❤"
        val sub = if (monthLabel.isEmpty()) "" else if (hebrew) "השבוע שלנו · $monthLabel" else "Our week · $monthLabel"
        return ReelPlan(
            style, shots, style.bpm, peakIndex, intro, outro, sub, outroMs, shots.sumOf { it.lengthMs } + outroMs,
            alternates.map { it.id }, rejected, seed,
        )
    }

    /** The style that fits the week, when the family didn't pick one. */
    fun autoStyle(all: List<ReelInput>): ReelStyle {
        if (all.isEmpty()) return ReelStyle.EMOTIONAL
        val special = all.count { it.special }
        val hugs = all.count { it.hug >= 0.4f || it.milestone }
        val play = all.count { it.laugh >= 0.4f || it.motion >= 0.5f }
        val outdoor = all.count { it.outdoor }
        return when {
            special >= 2 || play > all.size / 2 -> ReelStyle.HAPPY
            outdoor > all.size / 2 -> ReelStyle.CINEMATIC
            hugs >= 2 -> ReelStyle.EMOTIONAL
            else -> ReelStyle.EMOTIONAL
        }
    }
}
