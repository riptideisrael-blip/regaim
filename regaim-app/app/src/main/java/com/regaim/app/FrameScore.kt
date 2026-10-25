package com.regaim.app

/**
 * How much there is to see in a picture (a wall, a ceiling or a blurred frame score low; a face or a scene high):
 * the spread of brightness plus the edges between neighboring pixels, on a small copy. Used to pick a moment's cover
 * frame. No Android here (unit-tested).
 */
object FrameScore {
    /** [px]: ARGB pixels of a small picture, [w]×[h]. Higher = more to see. */
    fun detail(px: IntArray, w: Int, h: Int): Double {
        if (w < 2 || h < 2 || px.size < w * h) return 0.0
        val lum = DoubleArray(w * h) { i -> val c = px[i]; 0.299 * ((c shr 16) and 255) + 0.587 * ((c shr 8) and 255) + 0.114 * (c and 255) }
        val mean = lum.average()
        val spread = Math.sqrt(lum.sumOf { (it - mean) * (it - mean) } / lum.size)
        var edges = 0.0
        for (y in 0 until h - 1) for (x in 0 until w - 1) {
            val i = y * w + x
            edges += Math.abs(lum[i] - lum[i + 1]) + Math.abs(lum[i] - lum[i + w])
        }
        return spread + edges / ((w - 1) * (h - 1))
    }

    /** Below this, a cover is probably a wall, the floor or a blur: worth trying another moment of the clip. */
    const val DULL = 28.0
}
