package com.regaim.app

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The live-camera rules for "Smile", "Approach" and "Hug", without Android, so they can be tested on a computer.
 * [FaceWatcher] feeds it what ML Kit saw in each analyzed frame (about one every 0.8 s).
 *
 * Stricter than before, to cut false recordings:
 *  - Smile: one frame isn't enough any more; a clear smile must be seen in two frames in a row.
 *  - Hug (camera covered): the camera must go from a normal picture to covered right after a face was very close.
 *    A dark room, or a phone put in a pocket, no longer counts.
 *  - Hug (two faces): two faces of similar size, both clearly in the picture, pressed together for 3 s.
 */
class FaceSignals {
    /** One face in a frame: center and size in pixels, and [area] = the share of the frame it covers (0..1). */
    data class Box(val cx: Float, val cy: Float, val w: Float, val h: Float, val area: Float)

    companion object {
        const val SMILE_STRONG = 0.88f
        const val SMILE_HOLD = 0.75f
        /** The two smiling frames must be this close in time (frames come every ~0.8 s; allows one skipped frame). */
        const val SMILE_GAP_MS = 1_900L
        const val CLOSE_FACE = 0.12f
        const val CLOSE_TO_COVER_MS = 3_000L
        const val COVERED_MS = 2_500L
        const val TOGETHER_MS = 3_000L
        const val TOGETHER_MIN_AREA = 0.025f
        private const val NONE = Long.MIN_VALUE
    }

    private var lastSmileMs = NONE
    private val history = ArrayDeque<Pair<Long, Float>>() // time, biggest face area share
    private var lastCloseFaceMs = NONE
    private var lastClearMs = NONE      // last frame that wasn't covered
    private var coveredSinceMs = NONE
    private var coverFollowsFace = false
    private var togetherSinceMs = NONE

    /**
     * Returns "Hug", "Smile", "Approach" or null for one frame.
     * [smile] is the best smile probability among faces big enough to matter.
     */
    fun frame(now: Long, faces: List<Box>, smile: Float, covered: Boolean, hugOn: Boolean, facesOn: Boolean): String? {
        val biggest = faces.maxOfOrNull { it.area } ?: 0f
        if (hugOn && hug(now, faces, biggest, covered)) return "Hug"
        if (!facesOn) return null
        if (smile(now, smile)) return "Smile"
        if (approach(now, biggest)) return "Approach"
        return null
    }

    private fun smile(now: Long, smile: Float): Boolean {
        val held = lastSmileMs != NONE && now - lastSmileMs <= SMILE_GAP_MS
        if (smile >= SMILE_STRONG && held) {
            lastSmileMs = NONE
            return true
        }
        lastSmileMs = if (smile >= SMILE_HOLD) now else NONE
        return false
    }

    private fun approach(now: Long, biggest: Float): Boolean {
        history.addLast(now to biggest)
        while (history.isNotEmpty() && now - history.first().first > 2500) history.removeFirst()
        val oldest = history.firstOrNull() ?: return false
        // A face that tripled in size within ~2 seconds and is now close = someone running toward you.
        if (oldest.second in 0.004f..0.05f && biggest > 0.07f && biggest > oldest.second * 3f) {
            history.clear()
            return true
        }
        return false
    }

    private fun hug(now: Long, faces: List<Box>, biggest: Float, covered: Boolean): Boolean {
        // 1) Someone hugs you: a face very close, then right away the chest camera goes from a normal picture to covered.
        if (biggest > CLOSE_FACE) lastCloseFaceMs = now
        if (covered && faces.isEmpty()) {
            if (coveredSinceMs == NONE) {
                coveredSinceMs = now
                coverFollowsFace = lastCloseFaceMs != NONE && now - lastCloseFaceMs <= CLOSE_TO_COVER_MS &&
                    lastClearMs != NONE && now - lastClearMs <= CLOSE_TO_COVER_MS
            }
            if (coverFollowsFace && now - coveredSinceMs >= COVERED_MS) {
                reset()
                return true
            }
        } else {
            coveredSinceMs = NONE
            coverFollowsFace = false
            if (!covered) lastClearMs = now
        }

        // 2) Two people hugging in front of you: two similar faces pressed together for 3+ seconds.
        val big = faces.filter { it.area > TOGETHER_MIN_AREA }.sortedByDescending { it.area }
        var together = false
        if (big.size >= 2) {
            val a = big[0]
            val b = big[1]
            val dx = a.cx - b.cx
            val dy = a.cy - b.cy
            val d = sqrt(dx * dx + dy * dy)
            val similar = max(a.area, b.area) / min(a.area, b.area).coerceAtLeast(1e-6f) <= 2.5f
            together = similar && d < (a.w + b.w) / 2f && kotlin.math.abs(dy) < max(a.h, b.h)
        }
        if (together) {
            if (togetherSinceMs == NONE) togetherSinceMs = now
            if (now - togetherSinceMs >= TOGETHER_MS) {
                reset()
                return true
            }
        } else togetherSinceMs = NONE
        return false
    }

    private fun reset() {
        coveredSinceMs = NONE
        coverFollowsFace = false
        togetherSinceMs = NONE
        lastCloseFaceMs = NONE
        lastSmileMs = NONE
        history.clear()
    }
}
