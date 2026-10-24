package com.regaim.app

/**
 * Finds the red apples in a scene's picture (the counting game: the child touches the real apples on the tree, not
 * pictures pasted over it). Red, round, apple-sized spots, apart from each other, the biggest first. Works on plain
 * pixels (ARGB ints), no Android here (unit-tested).
 */
object AppleSpots {
    /** An apple: its center and radius, in the picture's pixels. */
    data class Spot(val x: Float, val y: Float, val r: Float)

    private fun red(p: Int): Boolean {
        val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
        if (r < 110) return false
        val min = minOf(g, b)
        val sat = (r - min).toFloat() / r
        // Apple red, not skin (a nose, cheeks, an elbow: pink, less saturated) nor a red-brown trunk.
        return sat >= 0.55f && r > g * 1.7f && r > b * 1.6f
    }

    /** The sizes (in pixels) of the red patches in a picture, biggest first (tiny specks left out). */
    fun redPatches(px: IntArray, w: Int, h: Int): List<Int> {
        if (w <= 0 || h <= 0 || px.size < w * h) return emptyList()
        val seen = BooleanArray(w * h)
        val queue = IntArray(w * h)
        val minArea = maxOf(6, (w * h * 0.0003f).toInt())
        val out = ArrayList<Int>()
        for (start in 0 until w * h) {
            if (seen[start] || !red(px[start])) continue
            var head = 0; var tail = 0
            queue[tail++] = start; seen[start] = true
            while (head < tail) {
                val i = queue[head++]; val x = i % w
                if (x > 0) { val j = i - 1; if (!seen[j] && red(px[j])) { seen[j] = true; queue[tail++] = j } }
                if (x < w - 1) { val j = i + 1; if (!seen[j] && red(px[j])) { seen[j] = true; queue[tail++] = j } }
                if (i >= w) { val j = i - w; if (!seen[j] && red(px[j])) { seen[j] = true; queue[tail++] = j } }
                if (i + w < w * h) { val j = i + w; if (!seen[j] && red(px[j])) { seen[j] = true; queue[tail++] = j } }
            }
            if (tail >= minArea) out.add(tail)
        }
        return out.sortedDescending()
    }

    /**
     * How many apples the red patches make, when one apple is about [single] pixels: two apples touching are one
     * patch twice as big (counted as two), an apple partly behind a hand is still one, a small red speck is none.
     */
    fun countBySize(patches: List<Int>, single: Int): Int =
        if (single <= 0) 0 else patches.filter { it >= single * 0.35f }.sumOf { maxOf(1, kotlin.math.round(it.toFloat() / single).toInt()) }

    /** One apple's size, from the first picture of a shot (its apples apart): the average of its [apples] biggest patches. */
    fun singleSize(patches: List<Int>, apples: Int): Int = patches.take(apples).let { if (it.isEmpty()) 0 else it.sum() / it.size }

    /** Up to [n] apples in a [w]×[h] picture, biggest first (fewer when the picture has fewer). */
    fun find(px: IntArray, w: Int, h: Int, n: Int, lowest: Float = 0.86f, need: Int = n): List<Spot> {
        if (w <= 0 || h <= 0 || px.size < w * h || n <= 0) return emptyList()
        val seen = BooleanArray(w * h)
        val total = w * h
        val minArea = maxOf(6, (total * 0.0003f).toInt())
        val maxArea = (total * 0.03f).toInt()
        val found = ArrayList<Pair<Spot, Int>>()
        val queue = IntArray(total)
        for (start in 0 until total) {
            if (seen[start] || !red(px[start])) continue
            // One red patch (4-connected).
            var head = 0; var tail = 0
            queue[tail++] = start; seen[start] = true
            var sx = 0L; var sy = 0L; var minX = w; var maxX = 0; var minY = h; var maxY = 0
            while (head < tail) {
                val i = queue[head++]
                val x = i % w; val y = i / w
                sx += x; sy += y
                if (x < minX) minX = x; if (x > maxX) maxX = x; if (y < minY) minY = y; if (y > maxY) maxY = y
                if (x > 0) { val j = i - 1; if (!seen[j] && red(px[j])) { seen[j] = true; queue[tail++] = j } }
                if (x < w - 1) { val j = i + 1; if (!seen[j] && red(px[j])) { seen[j] = true; queue[tail++] = j } }
                if (y > 0) { val j = i - w; if (!seen[j] && red(px[j])) { seen[j] = true; queue[tail++] = j } }
                if (y < h - 1) { val j = i + w; if (!seen[j] && red(px[j])) { seen[j] = true; queue[tail++] = j } }
            }
            val area = tail
            if (area < minArea || area > maxArea) continue
            val bw = maxX - minX + 1; val bh = maxY - minY + 1
            val aspect = bw.toFloat() / bh
            // Round-ish and filled (an apple, not a red stripe or a scatter of red dots).
            if (aspect < 0.55f || aspect > 1.8f) continue
            if (area.toFloat() / (bw * bh) < 0.45f) continue
            val cy = sy.toFloat() / area
            // Not at the very edges (the ground's fallen apples at the bottom, cut-off ones at the top).
            if (cy > h * lowest || cy < h * 0.03f) continue
            found.add(Spot(sx.toFloat() / area, cy, maxOf(bw, bh) / 2f) to area)
        }
        // Apples on one tree are about the same size: small red spots (an ear, a nose, a fingertip in the light) are
        // left out, unless they're needed to reach the number.
        val biggest = found.maxOfOrNull { it.first.r } ?: 0f
        val sorted = found.sortedByDescending { it.second }
        val small = sorted.filter { it.first.r < biggest * 0.6f }
        val out = ArrayList<Spot>()
        for ((s, isSmall) in sorted.filter { it.first.r >= biggest * 0.6f }.map { it.first to false } + small.map { it.first to true }) {
            if (isSmall && out.size >= need) break
            if (out.any { o -> kotlin.math.hypot((o.x - s.x).toDouble(), (o.y - s.y).toDouble()) < (o.r + s.r) * 1.25 }) continue
            out.add(s)
            if (out.size == n) break
        }
        return out
    }
}
