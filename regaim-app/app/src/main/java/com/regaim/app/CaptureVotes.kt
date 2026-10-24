package com.regaim.app

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * The detector layer of learning, two layers. YAMNet itself is never retrained and no media leaves the phone.
 *  - Personal: the family's 👍 / 👎 in the approval queue, per trigger label, on the phone (capture_votes.json).
 *    Works without an account.
 *  - Global: a per-label summary of everyone's votes (see [GlobalVotes]), fetched once a day and kept locally.
 * Effective bar for a label = slider × global multiplier × personal multiplier, blended: below 8 local votes only
 * the global counts; from 8 to 20 the personal weighs votes/20; from 20 the personal replaces the global.
 * The user's own slider (Status.threshold) is never changed; the result is used only where a sound becomes a "hit"
 * in CaptureService. The double confirmation (2 of 3 windows, or above max(0.6, slider × 2)) is untouched.
 */
object CaptureVotes {
    /** Pure rules and every number in one place. The numbers are a starting point, not calibrated. */
    object Rules {
        /** Below this many votes on a label, nothing changes. */
        const val MIN_VOTES = 8
        /** At least this share of 👎 makes the label stricter. */
        const val DOWN_SHARE = 0.70f
        /** At least this share of 👍 makes the label more sensitive. */
        const val UP_SHARE = 0.75f
        /** Each step: the multiplier rises by 0.15 / falls by 0.1. A step at 8 votes, then one more every 4 votes. */
        const val STEP_UP = 0.15f
        const val STEP_DOWN = 0.10f
        const val VOTES_PER_STEP = 4
        /** Final effective threshold is never above 0.75 (2.5 × the default slider 0.3) — it doesn't multiply forever. */
        const val CEILING = 0.75f
        /** …and never below 0.2. */
        const val FLOOR = 0.20f
        /** Smile / Approach have no YAMNet threshold: when turned down a lot, their one-per-minute window grows instead. */
        const val VISUAL_WINDOW_MS = 60_000L
        const val VISUAL_MAX_MS = 300_000L

        /** Local votes from which the personal multiplier fully replaces the global one. */
        const val FULL_PERSONAL_VOTES = 20
        /** Below this many votes on a label across everyone, the global multiplier is 1 (mirrored in capture-votes.sql). */
        const val GLOBAL_MIN_VOTES = 200
        const val GLOBAL_VOTES_PER_STEP = 200
        /** One vote added to the local tally — always, whatever the global switch says. */
        fun tally(counts: Map<String, Pair<Int, Int>>, label: String, up: Boolean): Map<String, Pair<Int, Int>> {
            val (u, d) = counts[label] ?: (0 to 0)
            return counts + (label to if (up) (u + 1 to d) else (u to d + 1))
        }

        /** Whether a vote also goes to the global summary: signed in and the switch on. */
        fun sendsGlobally(helping: Boolean, signedIn: Boolean): Boolean = helping && signedIn

        /** A visual window never gets shorter than this. */
        const val VISUAL_MIN_MS = 30_000L

        fun steps(votes: Int): Int = if (votes < MIN_VOTES) 0 else 1 + (votes - MIN_VOTES) / VOTES_PER_STEP

        /** Same rule as the personal one, on everyone's votes, from [GLOBAL_MIN_VOTES]. */
        fun globalMultiplier(up: Int, down: Int): Float {
            val n = up + down
            if (n < GLOBAL_MIN_VOTES) return 1f
            val s = 1 + (n - GLOBAL_MIN_VOTES) / GLOBAL_VOTES_PER_STEP
            return when {
                down.toFloat() / n >= DOWN_SHARE -> 1f + STEP_UP * s
                up.toFloat() / n >= UP_SHARE -> (1f - STEP_DOWN * s).coerceAtLeast(0f)
                else -> 1f
            }
        }

        /** The multiplier on the slider: global and personal, blended by how many local votes there are. */
        fun combined(global: Float, localUp: Int, localDown: Int): Float {
            val n = localUp + localDown
            if (n < MIN_VOTES) return global
            val personal = multiplier(localUp, localDown)
            if (n >= FULL_PERSONAL_VOTES) return personal
            val w = n.toFloat() / FULL_PERSONAL_VOTES
            return (1f - w) * global + w * global * personal
        }

        /** The bar for a sound label: slider × [combined], held between [FLOOR] and [CEILING], never past the slider. */
        fun effective(slider: Float, global: Float, localUp: Int, localDown: Int): Float = bound(slider, combined(global, localUp, localDown))

        fun bound(slider: Float, m: Float): Float {
            val raw = slider * m
            return when {
                m > 1f -> maxOf(slider, minOf(raw, CEILING))
                m < 1f -> minOf(slider, maxOf(raw, FLOOR))
                else -> slider
            }
        }

        /** Smile / Approach: the same multiplier moves only their one-per-minute window (each 0.15 = one more minute). */
        fun visualWindow(m: Float): Long =
            Math.round(VISUAL_WINDOW_MS * (1.0 + (m - 1.0) / STEP_UP)).coerceIn(VISUAL_MIN_MS, VISUAL_MAX_MS)

        fun multiplier(up: Int, down: Int): Float {
            val n = up + down
            if (n < MIN_VOTES) return 1f
            val s = steps(n)
            return when {
                down.toFloat() / n >= DOWN_SHARE -> 1f + STEP_UP * s
                up.toFloat() / n >= UP_SHARE -> (1f - STEP_DOWN * s).coerceAtLeast(0f)
                else -> 1f
            }
        }

        /**
         * The bar a sound with this label must reach: slider × multiplier, held between [FLOOR] and [CEILING].
         * The limits only ever pull toward the user's own slider, never past it.
         */
        fun effective(slider: Float, up: Int, down: Int): Float = bound(slider, multiplier(up, down))

        fun visualWindowMs(up: Int, down: Int): Long = visualWindow(multiplier(up, down))
    }

    private class Count(var up: Int = 0, var down: Int = 0)

    @Volatile private var cache: Map<String, Count>? = null
    private val lock = Any()
    private fun file(ctx: Context) = File(ctx.filesDir, "capture_votes.json")

    private fun counts(ctx: Context): Map<String, Count> = cache ?: synchronized(lock) {
        cache ?: run {
            val o = try { JSONObject(file(ctx).readText()) } catch (_: Exception) { JSONObject() }
            val m = HashMap<String, Count>()
            for (k in o.keys()) o.optJSONObject(k)?.let { m[k] = Count(it.optInt("up"), it.optInt("down")) }
            cache = m
            m
        }
    }

    private fun vote(ctx: Context, label: String, up: Boolean) {
        if (label.isEmpty()) return
        synchronized(lock) {
            val next = Rules.tally(counts(ctx).mapValues { it.value.up to it.value.down }, label, up)
            val all = HashMap(next.mapValues { Count(it.value.first, it.value.second) })
            val o = JSONObject()
            all.forEach { (k, v) -> o.put(k, JSONObject().put("up", v.up).put("down", v.down)) }
            try { file(ctx).writeText(o.toString()) } catch (_: Exception) { }
            cache = all
        }
    }

    /**
     * A 👍 / 👎 from the queue: always counted on the phone; and, when signed in with "help detection accuracy" on,
     * the label, a score bucket and the vote go to the global summary (nothing else).
     */
    fun up(ctx: Context, label: String, score: Float = -1f) = cast(ctx, label, score, true)
    fun down(ctx: Context, label: String, score: Float = -1f) = cast(ctx, label, score, false)

    private fun cast(ctx: Context, label: String, score: Float, up: Boolean) {
        vote(ctx, label, up) // the phone's own learning: always
        if (Rules.sendsGlobally(GlobalVotes.helping(ctx), Account.signedIn(ctx))) GlobalVotes.send(ctx, label, score, if (up) 1 else -1)
    }

    private fun multiplier(ctx: Context, label: String): Float {
        val c = counts(ctx)[label]
        return Rules.combined(GlobalVotes.multiplier(ctx, label), c?.up ?: 0, c?.down ?: 0)
    }

    fun effective(ctx: Context, label: String, slider: Float): Float = Rules.bound(slider, multiplier(ctx, label))

    fun visualWindowMs(ctx: Context, label: String): Long = Rules.visualWindow(multiplier(ctx, label))

    /** For "what Moments learned": one line per label whose behavior changed. */
    fun summary(ctx: Context): List<String> = counts(ctx).mapNotNull { (label, c) ->
        val what = if (label in Labels.VISUAL) {
            val w = visualWindowMs(ctx, label)
            if (w == Rules.VISUAL_WINDOW_MS) return@mapNotNull null
            L.t("לכל היותר פעם ב-${w / 1000} שניות", "at most once every ${w / 1000} seconds")
        } else {
            val m = multiplier(ctx, label)
            when {
                m > 1f -> L.t("צריך זיהוי חזק יותר", "needs a stronger detection")
                m < 1f -> L.t("רגיש יותר", "more sensitive")
                else -> return@mapNotNull null
            }
        }
        L.t("${Labels.he(label)}: $what · 👍 ${c.up} · 👎 ${c.down}", "${Labels.he(label)}: $what · 👍 ${c.up} · 👎 ${c.down}")
    }
}

/** Which captures wait for 👍/👎, and when an unanswered one expires. Pure. */
object QueueRules {
    /** Unanswered queue items go to the trash after this long (silence isn't a 👎). */
    const val EXPIRE_MS = 36L * 60 * 60 * 1000

    /**
     * Automatic captures (laugh, sound, smile, approach) go to the queue. Straight to the archive:
     * a button / code word ([forced] or [Labels.isChosen]), a hug, and "I love you".
     */
    fun goesToQueue(forced: Boolean, label: String): Boolean {
        if (forced || Labels.isChosen(label)) return false
        if (label == "Hug") return false
        if (label.startsWith("״") && Phrases.isLove(label.trim('״'))) return false
        return true
    }

    fun expired(m: Memory, now: Long): Boolean = m.isPending && now - m.time > EXPIRE_MS
}
