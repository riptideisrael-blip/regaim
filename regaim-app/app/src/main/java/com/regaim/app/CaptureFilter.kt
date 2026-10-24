package com.regaim.app

import android.graphics.Bitmap
import android.graphics.Color

/**
 * Keeps the automatic recorder from saving junk: clips from inside a pocket or bag (dark, flat picture, or the phone's
 * proximity sensor covered) and near-identical clips of the same scene seen a few minutes apart.
 * Moments saved on purpose (button, code word, "I love you") are never filtered.
 */
object CaptureFilter {
    const val GRID_W = 9
    const val GRID_H = 8
    const val SIMILAR_BITS = 8          // of 64: at most this many differing bits = "the same picture"
    const val WINDOW_MS = 20 * 60_000L  // only compare with clips from the last 20 minutes
    private const val KEEP = 6

    // ---- pure logic (unit tested) ----

    fun mean(luma: IntArray): Int = if (luma.isEmpty()) 0 else luma.sum() / luma.size

    /** Dark and almost flat = lens covered (pocket, bag, table face-down). */
    fun looksCovered(luma: IntArray): Boolean {
        if (luma.isEmpty()) return false
        val lo = luma.min(); val hi = luma.max()
        return mean(luma) < 22 && (hi - lo) < 45
    }

    /** Difference hash over a 9x8 grid: one bit per "is the left pixel brighter than its right neighbour". */
    fun dHash(luma: IntArray): Long {
        require(luma.size == GRID_W * GRID_H)
        var h = 0L
        for (y in 0 until GRID_H) for (x in 0 until GRID_W - 1) {
            h = (h shl 1) or (if (luma[y * GRID_W + x] > luma[y * GRID_W + x + 1]) 1L else 0L)
        }
        return h
    }

    fun distance(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)

    class Recent {
        private val hashes = ArrayDeque<Pair<Long, Long>>()
        @Synchronized fun isSimilar(hash: Long, now: Long): Boolean {
            while (hashes.isNotEmpty() && now - hashes.first().second > WINDOW_MS) hashes.removeFirst()
            return hashes.any { distance(it.first, hash) <= SIMILAR_BITS }
        }
        @Synchronized fun add(hash: Long, now: Long) {
            hashes.addLast(hash to now)
            while (hashes.size > KEEP) hashes.removeFirst()
        }
    }

    // ---- learning from deleted clips ----

    private const val DELETED_KEEP = 60
    private const val DELETED_DAYS = 90L
    const val DELETED_BITS = 7

    private fun deletedFile(ctx: android.content.Context) = java.io.File(ctx.filesDir, "rejected_looks.json")

    private fun deletedLooks(ctx: android.content.Context): List<Pair<Long, Long>> = try {
        val a = org.json.JSONArray(deletedFile(ctx).readText())
        (0 until a.length()).map { val o = a.getJSONObject(it); o.getString("h").toLong() to o.getLong("t") }
    } catch (_: Exception) { emptyList() }

    /** You deleted an automatic clip: remember how it looked so similar ones are not recorded again. */
    @Synchronized
    fun rememberDeleted(ctx: android.content.Context, hash: Long) {
        if (hash == 0L) return
        val now = System.currentTimeMillis()
        val keep = (deletedLooks(ctx).filter { now - it.second < DELETED_DAYS * 86_400_000L } + (hash to now)).takeLast(DELETED_KEEP)
        val a = org.json.JSONArray()
        keep.forEach { a.put(org.json.JSONObject().put("h", it.first.toString()).put("t", it.second)) }
        try { deletedFile(ctx).writeText(a.toString()) } catch (_: Exception) { }
    }

    @Synchronized
    fun looksLikeDeleted(ctx: android.content.Context, hash: Long): Boolean {
        val now = System.currentTimeMillis()
        return deletedLooks(ctx).any { now - it.second < DELETED_DAYS * 86_400_000L && distance(it.first, hash) <= DELETED_BITS }
    }

    /** A deleted clip was restored: stop blocking pictures like it. */
    @Synchronized
    fun forgetLook(ctx: android.content.Context, hash: Long) {
        if (hash == 0L) return
        val keep = deletedLooks(ctx).filterNot { it.first == hash }
        val a = org.json.JSONArray()
        keep.forEach { a.put(org.json.JSONObject().put("h", it.first.toString()).put("t", it.second)) }
        try { deletedFile(ctx).writeText(a.toString()) } catch (_: Exception) { }
    }

    @Synchronized
    fun forgetDeleted(ctx: android.content.Context) { deletedFile(ctx).delete() }

    // ---- Android glue ----

    fun lumaGrid(frame: Bitmap): IntArray {
        val small = Bitmap.createScaledBitmap(frame, GRID_W, GRID_H, true)
        val out = IntArray(GRID_W * GRID_H)
        for (i in out.indices) {
            val c = small.getPixel(i % GRID_W, i / GRID_W)
            out[i] = (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)).toInt()
        }
        if (small !== frame) small.recycle()
        return out
    }
}
