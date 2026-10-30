package com.regaim.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View

/**
 * A ring chart: one arc per part (its share of the whole), small gaps between them, and two lines in the middle
 * (the total and what it is). Drawn by hand, no library. The parts are named in the legend next to it.
 */
class DonutView(ctx: Context) : View(ctx) {
    var slices: List<Pair<Double, Int>> = emptyList() // value, color
        set(v) { field = v; invalidate() }
    var center: String = ""
        set(v) { field = v; invalidate() }
    var caption: String = ""
        set(v) { field = v; invalidate() }
    var track: Int = 0xFFEEF3F9.toInt()
    var ink: Int = 0xFF0F1B2D.toInt()
    var soft: Int = 0xFF5B687A.toInt()

    private val d = resources.displayMetrics.density
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.BUTT }
    private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.create(DS.emphasis, Typeface.BOLD) }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val box = RectF()

    override fun onMeasure(w: Int, h: Int) {
        val size = MeasureSpec.getSize(w).coerceAtMost((180 * d).toInt())
        setMeasuredDimension(size, size)
    }

    override fun onDraw(c: Canvas) {
        val stroke = width * 0.12f
        val inset = stroke / 2 + 2 * d
        box.set(inset, inset, width - inset, height - inset)
        arc.strokeWidth = stroke
        // The empty ring under the parts.
        arc.color = track
        c.drawArc(box, 0f, 360f, false, arc)
        val total = slices.sumOf { it.first.coerceAtLeast(0.0) }
        if (total > 0) {
            val gap = if (slices.count { it.first > 0 } > 1) 2.2f else 0f
            var start = -90f
            for ((v, color) in slices) {
                if (v <= 0) continue
                val sweep = (v / total * 360).toFloat()
                arc.color = color
                c.drawArc(box, start + gap / 2, (sweep - gap).coerceAtLeast(0.5f), false, arc)
                start += sweep
            }
        }
        // The total in the middle, and what it is under it.
        // Grows with the phone's font size too (never smaller than 12sp for the caption).
        val sp = resources.displayMetrics.scaledDensity
        big.color = ink; big.textSize = maxOf(width * 0.13f, 18 * sp)
        small.color = soft; small.textSize = maxOf(width * 0.068f, 12 * sp)
        val cy = height / 2f
        c.drawText(center, width / 2f, cy + big.textSize * 0.15f, big)
        c.drawText(caption, width / 2f, cy + big.textSize * 0.15f + small.textSize * 1.5f, small)
    }
}
