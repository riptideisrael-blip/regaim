package com.regaim.app

/**
 * Takes the green background off a drawing made on chroma-key green (the arrows game's Pitzki poses and things):
 * green pixels become see-through, the green that spilled onto the edges is taken out, and the soft edge stays soft.
 * And cuts a picture of several things into one picture per thing. Plain ARGB ints, no Android (unit-tested).
 */
object GreenKey {
    /** How green a pixel is (0: not at all), by how much green beats red and blue. */
    private fun greenness(p: Int): Int {
        val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
        return g - maxOf(r, b)
    }

    /** The picture with its green made see-through (in place, and returned). */
    fun key(px: IntArray): IntArray {
        for (i in px.indices) {
            val p = px[i]
            val gr = greenness(p)
            val r = (p shr 16) and 0xFF; var g = (p shr 8) and 0xFF; val b = p and 0xFF
            // Clearly green: gone. A little green (an edge): partly see-through. Otherwise kept.
            val a = when {
                gr >= 90 -> 0
                gr <= 25 -> 255
                else -> 255 - ((gr - 25) * 255 / 65)
            }
            // The green glow on the edges (spill): green no brighter than the brighter of red and blue.
            if (gr > 0) g = maxOf(r, b)
            px[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        return px
    }

    /** The smallest box around what's not see-through: left, top, right, bottom (inclusive), or null when empty. */
    fun bounds(px: IntArray, w: Int, h: Int, minAlpha: Int = 40): IntArray? {
        var l = w; var t = h; var r = -1; var b = -1
        for (y in 0 until h) for (x in 0 until w) if ((px[y * w + x] ushr 24) >= minAlpha) {
            if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > b) b = y
        }
        return if (r < 0) null else intArrayOf(l, t, r, b)
    }

    /**
     * The separate things in a keyed picture, left to right (boxes: left, top, right, bottom), at most [most] of
     * them, the small specks left out. Looked at in columns: a thing is a run of columns that have something in them.
     */
    fun things(px: IntArray, w: Int, h: Int, most: Int, minAlpha: Int = 60): List<IntArray> {
        val used = BooleanArray(w) { x -> (0 until h).any { y -> (px[y * w + x] ushr 24) >= minAlpha } }
        val runs = ArrayList<IntArray>()
        var x = 0
        while (x < w) {
            if (!used[x]) { x++; continue }
            val s = x
            while (x < w && used[x]) x++
            // Its own top and bottom.
            var t = h; var b = -1
            for (yy in 0 until h) for (xx in s until x) if ((px[yy * w + xx] ushr 24) >= minAlpha) { if (yy < t) t = yy; if (yy > b) b = yy }
            if (x - s >= w / 40 && b - t >= h / 20) runs.add(intArrayOf(s, t, x - 1, b))
        }
        return runs.sortedByDescending { (it[2] - it[0]) * (it[3] - it[1]) }.take(most).sortedBy { it[0] }
    }
}
