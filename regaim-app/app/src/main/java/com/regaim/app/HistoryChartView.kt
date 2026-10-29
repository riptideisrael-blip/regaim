package com.regaim.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.view.View

/**
 * The total over time: a line through the reports' balances (spaced by date), a soft fill under it, a dot on each
 * report and a hollow one on today's estimate (it isn't a report). The first and last dates under it. Drawn by hand.
 */
class HistoryChartView(ctx: Context) : View(ctx) {
    var points: List<Pair<String, Double>> = emptyList()
        set(v) { field = v; invalidate() }
    var line: Int = 0xFF1A6FCC.toInt()
    var soft: Int = 0xFF5B687A.toInt()
    var dotFill: Int = 0xFFFFFFFF.toInt()
    var startLabel = ""
    var endLabel = ""
    /** The last point is an estimate (drawn hollow, the line to it dashed). */
    var lastIsEstimate = true

    private val d = resources.displayMetrics.density
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f * d; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11 * resources.displayMetrics.scaledDensity }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), (140 * d).toInt())

    private fun day(s: String) = try { java.time.LocalDate.parse(s).toEpochDay().toFloat() } catch (_: Exception) { 0f }

    override fun onDraw(c: Canvas) {
        if (points.size < 2) return
        val labelH = text.textSize * 1.8f
        val pad = 6 * d
        val top = pad; val bottom = height - labelH
        val x0 = day(points.first().first); val x1 = day(points.last().first).coerceAtLeast(x0 + 1)
        val max = points.maxOf { it.second }.coerceAtLeast(1.0)
        val min = (points.minOf { it.second } * 0.85).coerceAtMost(max * 0.9)
        // Right to left in Hebrew would read backwards for time: time goes left to right, as in every chart.
        fun x(s: String) = pad + (day(s) - x0) / (x1 - x0) * (width - 2 * pad)
        fun y(v: Double) = (bottom - ((v - min) / (max - min)).toFloat() * (bottom - top))
        val xs = points.map { x(it.first) }; val ys = points.map { y(it.second) }

        // The fill under the reports' line.
        val area = Path().apply {
            moveTo(xs[0], bottom)
            xs.indices.forEach { lineTo(xs[it], ys[it]) }
            lineTo(xs.last(), bottom); close()
        }
        fill.shader = LinearGradient(0f, top, 0f, bottom, (line and 0x00FFFFFF) or 0x40000000, line and 0x00FFFFFF, Shader.TileMode.CLAMP)
        c.drawPath(area, fill)
        fill.shader = null

        val n = points.size
        val solidEnd = if (lastIsEstimate) n - 2 else n - 1
        stroke.color = line; stroke.pathEffect = null
        c.drawPath(Path().apply { moveTo(xs[0], ys[0]); (1..solidEnd).forEach { lineTo(xs[it], ys[it]) } }, stroke)
        if (lastIsEstimate) {
            stroke.pathEffect = android.graphics.DashPathEffect(floatArrayOf(5 * d, 4 * d), 0f)
            c.drawLine(xs[n - 2], ys[n - 2], xs[n - 1], ys[n - 1], stroke)
            stroke.pathEffect = null
        }
        for (i in 0 until n) {
            val hollow = lastIsEstimate && i == n - 1
            fill.color = if (hollow) dotFill else line
            c.drawCircle(xs[i], ys[i], 4 * d, fill)
            if (hollow) c.drawCircle(xs[i], ys[i], 4 * d, stroke)
        }
        text.color = soft
        text.textAlign = Paint.Align.LEFT; c.drawText(startLabel, 0f, height - 4 * d, text)
        text.textAlign = Paint.Align.RIGHT; c.drawText(endLabel, width.toFloat(), height - 4 * d, text)
    }
}
