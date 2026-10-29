package com.regaim.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/**
 * Year-by-year bars of a projection: what was deposited (light) and what it grew by (dark) on top, with the first
 * and last year under them. Drawn by hand, no library.
 */
class GrowthChartView(ctx: Context) : View(ctx) {
    var points: List<Savings.YearPoint> = emptyList()
        set(v) { field = v; invalidate() }
    var deposits: Int = 0xFFD6E4F5.toInt()
    var growth: Int = 0xFF1A6FCC.toInt()
    var soft: Int = 0xFF5B687A.toInt()
    var startLabel = ""
    var endLabel = ""

    private val d = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11 * resources.displayMetrics.scaledDensity }
    private val r = RectF()

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), (150 * d).toInt())

    override fun onDraw(c: Canvas) {
        val bars = points.filter { it.year > 0 }
        if (bars.isEmpty()) return
        val labelH = text.textSize * 1.6f
        val top = 4 * d; val bottom = height - labelH
        val max = bars.maxOf { it.total }.coerceAtLeast(1.0)
        val slot = width.toFloat() / bars.size
        val gap = (slot * 0.25f).coerceAtMost(6 * d)
        val radius = ((slot - gap) / 2).coerceAtMost(3 * d)
        bars.forEachIndexed { i, p ->
            val x0 = i * slot + gap / 2; val x1 = (i + 1) * slot - gap / 2
            val hTotal = ((p.total / max) * (bottom - top)).toFloat()
            val hDep = ((p.deposited.coerceAtMost(p.total) / max) * (bottom - top)).toFloat()
            fill.color = growth; r.set(x0, bottom - hTotal, x1, bottom); c.drawRoundRect(r, radius, radius, fill)
            fill.color = deposits; r.set(x0, bottom - hDep, x1, bottom); c.drawRoundRect(r, radius, radius, fill)
        }
        text.color = soft
        text.textAlign = Paint.Align.LEFT; c.drawText(startLabel, 0f, height - 4 * d, text)
        text.textAlign = Paint.Align.RIGHT; c.drawText(endLabel, width.toFloat(), height - 4 * d, text)
    }
}
