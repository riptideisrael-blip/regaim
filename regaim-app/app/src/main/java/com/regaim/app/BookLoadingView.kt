package com.regaim.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * While a book is being made: a real-looking open hardcover book lying on a table: a cloth cover with thickness,
 * stacks of page edges, pages that curve down into the spine with a soft shadow in the fold, a ribbon bookmark, and a
 * page that lifts, curls and turns with light and shadow. The Moments mark is on the left page; on the right page a
 * picture is painted in and lines of words fill as pages are drawn ([drawn] of [total]).
 *
 * With [images] (the book's real pictures, as they are drawn): the little book shows them. Each new picture lands
 * with a page turn: the page that was on the right turns over to the left, and the new picture is revealed on the
 * right, so the parent watches the book being made, page after page. With [tour], it keeps leafing through them.
 */
class BookLoadingView(ctx: Context) : View(ctx) {
    var drawn = 0
        set(v) { field = v; invalidate() }
    var total = 0
        set(v) { field = v; invalidate() }

    /** The book's real pictures drawn so far, in book order (cover first). A longer list turns to the new one. */
    var images: List<Bitmap> = emptyList()
        set(v) {
            field = v
            if (v.size < shown) shown = v.size
            invalidate()
        }

    /** Keep leafing through the pictures (while the finished book is being animated). */
    var tour = false

    /** How many pictures are laid open (right page = the last of them); a turn is running while it's below the list. */
    private var shown = 0
    private var turnT = -1f   // -1 = no turn; 0..1 = turning to picture number shown+1
    private var idleFor = 0f
    private val src = Rect()
    private val dst = RectF()
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private val brand = 0xFF87664F.toInt()
    private val coverDark = 0xFF2E2680.toInt()
    private val coverMid = 0xFF4A3FC4.toInt()
    private val coverLight = 0xFF6A5FE6.toInt()
    private val paperLight = 0xFFFFFCF6.toInt()
    private val paper = 0xFFF8F1E4.toInt()
    private val paperEdge = 0xFFE6DAC6.toInt()
    private val ink = 0xFF3A332D.toInt()
    private val gold = 0xFFE8B04A.toInt()

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    private val logo = ContextCompat.getDrawable(ctx, R.drawable.ic_logo_mark)?.mutate()
    private val path = Path()
    private val path2 = Path()
    private val rect = RectF()

    /** 0..1 of one cycle: a pause with the book open, then a page turns. */
    private var cycle = 0f
    private var time = 0f
    private var last = 0L
    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2600
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            cycle = it.animatedValue as Float
            val now = System.nanoTime()
            val dt = if (last != 0L) ((now - last) / 1e9f).coerceAtMost(0.1f) else 0f
            time += dt
            last = now
            stepTurn(dt)
            invalidate()
        }
    }

    /** Real pictures: one page turn per new picture (1.5 s), then a pause; in a tour, on to the next and around again. */
    private fun stepTurn(dt: Float) {
        if (images.isEmpty()) { turnT = -1f; return }
        if (turnT >= 0f) {
            turnT += dt / 1.5f
            if (turnT >= 1f) { turnT = -1f; shown++; idleFor = 0f }
            return
        }
        idleFor += dt
        if (shown < images.size && idleFor > 0.4f) turnT = 0f
        else if (tour && images.size > 1 && idleFor > 3.2f) {
            if (shown >= images.size) shown = 0
            turnT = 0f
        }
    }

    private fun real() = images.isNotEmpty()
    private fun pic(i: Int): Bitmap? = images.getOrNull(i)

    /** Draws [b] filling [r] (cropped to keep its proportions). */
    private fun drawCrop(c: Canvas, b: Bitmap, r: RectF) {
        val want = r.width() / r.height()
        val have = b.width.toFloat() / b.height
        if (have > want) {
            val w = (b.height * want).toInt(); val x = (b.width - w) / 2
            src.set(x, 0, x + w, b.height)
        } else {
            val h = (b.width / want).toInt(); val y = (b.height - h) / 2
            src.set(0, y, b.width, y + h)
        }
        c.drawBitmap(b, src, r, bmpPaint)
    }

    /** A picture printed on one page (clipped to the page's curve). */
    private fun pagePicture(c: Canvas, b: Bitmap, left: Boolean) {
        c.save()
        pageShape(path2, left); c.clipPath(path2)
        dst.set(if (left) cx - pageW else cx, top - arch, if (left) cx else cx + pageW, bottom + sag)
        drawCrop(c, b, dst)
        c.restore()
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); last = 0L; anim.start() }
    override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = View.MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (w * 0.75f).toInt())
    }

    // Book geometry (set every frame from the view size).
    private var cx = 0f; private var top = 0f; private var bottom = 0f
    private var pageW = 0f; private var pageH = 0f; private var sag = 0f; private var arch = 0f

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        pageW = min(w * 0.40f, h * 0.58f)
        pageH = pageW * 1.42f
        cx = w / 2f
        top = (h - pageH) / 2f + h * 0.02f
        bottom = top + pageH
        sag = pageH * 0.035f   // the pages dip into the spine
        arch = pageH * 0.025f  // and rise a little toward the middle of each page
        val bob = sin(time * 1.4f) * pageH * 0.006f // the book "breathes" a little
        c.save()
        c.translate(0f, bob)

        tableShadow(c)
        cover(c)
        stack(c, left = true)
        stack(c, left = false)
        leftPage(c)
        rightPage(c)
        gutter(c)
        turningPage(c)
        ribbon(c)
        c.restore()
        sparkles(c, h)
    }

    /** A soft oval shadow on the table under the book. */
    private fun tableShadow(c: Canvas) {
        val sy = bottom + pageH * 0.07f
        val rx = pageW * 1.25f
        fill.shader = RadialGradient(cx, sy, rx, intArrayOf(0x55000000, 0x22000000, 0x00000000), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.save(); c.scale(1f, 0.16f, cx, sy)
        c.drawCircle(cx, sy, rx, fill)
        c.restore()
        fill.shader = null
    }

    /** The hardcover: cloth-colored boards with a visible thickness, lit from above. */
    private fun cover(c: Canvas) {
        val m = pageW * 0.045f
        val thick = pageH * 0.035f
        // Thickness of the boards (seen at the bottom edge).
        rect.set(cx - pageW - m, top - m + thick, cx + pageW + m, bottom + m + thick)
        fill.color = coverDark
        c.drawRoundRect(rect, m * 0.9f, m * 0.9f, fill)
        // The boards.
        rect.set(cx - pageW - m, top - m, cx + pageW + m, bottom + m)
        fill.shader = LinearGradient(0f, rect.top, 0f, rect.bottom, coverLight, coverMid, Shader.TileMode.CLAMP)
        c.drawRoundRect(rect, m * 0.9f, m * 0.9f, fill)
        // Light falling across the cloth, darker toward the spine.
        fill.shader = LinearGradient(rect.left, 0f, rect.right, 0f,
            intArrayOf(0x22FFFFFF, 0x00FFFFFF, 0x55000000, 0x00FFFFFF, 0x18FFFFFF), floatArrayOf(0f, 0.4f, 0.5f, 0.6f, 1f), Shader.TileMode.CLAMP)
        c.drawRoundRect(rect, m * 0.9f, m * 0.9f, fill)
        fill.shader = null
        // A thin gold line pressed into the boards.
        line.color = gold; line.alpha = 90; line.strokeWidth = pageW * 0.006f
        rect.inset(m * 0.35f, m * 0.35f)
        c.drawRoundRect(rect, m * 0.6f, m * 0.6f, line)
        line.alpha = 255
    }

    /** The edges of the pages underneath: thin layers, each a bit lower and farther out. */
    private fun stack(c: Canvas, left: Boolean) {
        val layers = 6
        val step = pageW * 0.006f
        for (i in layers downTo 1) {
            val off = step * i
            pageShape(path, left, outward = off, down = off * 0.9f)
            fill.color = if (i % 2 == 0) paperEdge else 0xFFEFE5D3.toInt()
            c.drawPath(path, fill)
        }
    }

    /** One page's outline: curving down into the spine, outer edge straight. */
    private fun pageShape(p: Path, left: Boolean, outward: Float = 0f, down: Float = 0f) {
        val s = if (left) -1f else 1f
        val outer = cx + s * (pageW + outward)
        val mid = cx + s * pageW * 0.45f
        p.reset()
        p.moveTo(cx, top + sag + down)
        p.quadTo(mid, top - arch + down, outer, top + down)
        p.lineTo(outer, bottom + down)
        p.quadTo(mid, bottom - arch * 0.6f + down, cx, bottom + sag * 0.5f + down)
        p.close()
    }

    private fun paperFill(left: Boolean) {
        val s = if (left) -1f else 1f
        // Brighter in the middle of the page, warmer toward the spine and the outer edge.
        fill.shader = LinearGradient(cx, 0f, cx + s * pageW, 0f,
            intArrayOf(paper, paperLight, paperLight, paper), floatArrayOf(0f, 0.3f, 0.75f, 1f), Shader.TileMode.CLAMP)
    }

    /** Left page: the Moments mark and name. */
    private fun leftPage(c: Canvas) {
        pageShape(path, left = true)
        paperFill(left = true)
        c.drawPath(path, fill)
        fill.shader = null
        if (real()) {
            // Under a turning page, the left side still shows what was there before.
            val leftPic = pic(shown - 2)
            if (leftPic != null) { pagePicture(c, leftPic, left = true); return }
        }
        val lx = cx - pageW * 0.52f
        logo?.let { d ->
            val s = pageW * 0.38f
            val pulse = 1f + 0.03f * sin(time * 2.2f)
            val ly = top + pageH * 0.4f
            // A soft glow behind the mark.
            fill.shader = RadialGradient(lx, ly, s * 0.9f, intArrayOf(0x3387664F, 0x0087664F), null, Shader.TileMode.CLAMP)
            c.drawCircle(lx, ly, s * 0.9f, fill)
            fill.shader = null
            d.setBounds((lx - s / 2 * pulse).toInt(), (ly - s / 2 * pulse).toInt(), (lx + s / 2 * pulse).toInt(), (ly + s / 2 * pulse).toInt())
            d.draw(c)
        }
        text.color = brand
        text.textSize = pageW * 0.12f
        c.drawText("Moments", lx, top + pageH * 0.74f, text)
        line.color = gold; line.strokeWidth = pageH * 0.006f
        c.drawLine(lx - pageW * 0.16f, top + pageH * 0.79f, lx + pageW * 0.16f, top + pageH * 0.79f, line)
    }

    /** Right page: a picture painted in as pages are drawn, and lines of words below it. */
    private fun rightPage(c: Canvas) {
        pageShape(path, left = false)
        paperFill(left = false)
        c.drawPath(path, fill)
        fill.shader = null
        if (real()) {
            // During a turn the new picture is already there, revealed as the page lifts.
            val rightPic = pic(if (turnT >= 0f) shown else shown - 1)
            if (rightPic != null) { pagePicture(c, rightPic, left = false); return }
        }
        val share = if (total > 0) (drawn.toFloat() / total).coerceIn(0f, 1f) else 0f
        val l = cx + pageW * 0.16f
        val r = cx + pageW * 0.86f
        val t = top + pageH * 0.12f
        val b = top + pageH * 0.56f
        rect.set(l, t, r, b)
        val rad = pageW * 0.04f
        // The empty frame, then the painting (sky, sun, hills) wiped in from the side as pages are done.
        fill.color = 0xFFF1E8D8.toInt()
        c.drawRoundRect(rect, rad, rad, fill)
        val reveal = 0.12f + 0.88f * share
        c.save()
        c.clipRect(l, t, l + (r - l) * reveal, b)
        fill.shader = LinearGradient(0f, t, 0f, b, 0xFFBFD9F2.toInt(), 0xFFF6E3C4.toInt(), Shader.TileMode.CLAMP)
        c.drawRoundRect(rect, rad, rad, fill)
        fill.shader = null
        fill.color = 0xFFF5C46A.toInt()
        c.drawCircle(l + (r - l) * 0.72f, t + (b - t) * 0.3f, (b - t) * 0.12f, fill)
        path2.reset()
        path2.moveTo(l, b)
        path2.quadTo(l + (r - l) * 0.3f, t + (b - t) * 0.45f, l + (r - l) * 0.62f, t + (b - t) * 0.78f)
        path2.quadTo(l + (r - l) * 0.85f, t + (b - t) * 0.6f, r, t + (b - t) * 0.7f)
        path2.lineTo(r, b); path2.close()
        fill.color = 0xFF8CC08A.toInt()
        c.drawPath(path2, fill)
        path2.reset()
        path2.moveTo(l, b)
        path2.quadTo(l + (r - l) * 0.55f, t + (b - t) * 0.72f, r, b)
        path2.close()
        fill.color = 0xFF6FA670.toInt()
        c.drawPath(path2, fill)
        c.restore()
        // The brush edge, while painting.
        if (share < 1f) {
            val ex = l + (r - l) * reveal
            fill.shader = LinearGradient(ex - pageW * 0.05f, 0f, ex, 0f, 0x00FFFFFF, 0x99FFFCF6.toInt(), Shader.TileMode.CLAMP)
            c.drawRect(ex - pageW * 0.05f, t, ex, b, fill)
            fill.shader = null
        }
        // Lines of words: written ones in ink, the rest faint.
        val lines = 5
        val done = (lines * share).toInt()
        line.strokeWidth = pageH * 0.016f
        for (i in 0 until lines) {
            val y = top + pageH * (0.66f + 0.06f * i)
            val len = (r - l) * (if (i == lines - 1) 0.55f else if (i % 2 == 0) 0.95f else 0.85f)
            line.color = if (i < done) ink else paperEdge
            line.alpha = if (i < done) 150 else 200
            c.drawLine(l, y, l + len, y, line)
        }
        line.alpha = 255
    }

    /** The shadow in the fold, where the pages curve into the spine. */
    private fun gutter(c: Canvas) {
        val g = pageW * 0.2f
        fill.shader = LinearGradient(cx - g, 0f, cx + g, 0f,
            intArrayOf(0x00000000, 0x14000000, 0x46000000, 0x14000000, 0x00000000), floatArrayOf(0f, 0.38f, 0.5f, 0.62f, 1f), Shader.TileMode.CLAMP)
        c.save()
        pageShape(path, left = true); c.clipPath(path); c.drawRect(cx - g, top - arch, cx, bottom + sag, fill)
        c.restore(); c.save()
        pageShape(path, left = false); c.clipPath(path); c.drawRect(cx, top - arch, cx + g, bottom + sag, fill)
        c.restore()
        fill.shader = null
    }

    /**
     * The turning page: rests for a moment, then lifts from the right, curls over the spine and lands on the left.
     * Its front is lit, its back is in shade, and it casts a shadow on the page under it.
     */
    private fun turningPage(c: Canvas) {
        val k: Float
        if (real()) {
            if (turnT < 0f) return
            k = turnT
        } else {
            val rest = 0.28f
            if (cycle < rest) return
            k = ((cycle - rest) / (1f - rest)).coerceIn(0f, 1f)
        }
        val e = (1f - cos(PI * k).toFloat()) / 2f // ease in-out
        val angle = PI.toFloat() * e
        val x = cos(angle)               // 1 = flat on the right, -1 = flat on the left
        val lift = sin(angle)            // 0 flat, 1 standing up
        val edge = cx + pageW * x
        // Standing up, the page comes toward the eye: a little taller.
        val grow = pageH * 0.05f * lift
        val et = top - grow
        val eb = bottom + grow * 0.35f
        // The curl: the middle of the page bows away from the turning direction.
        val bow = pageW * 0.22f * lift * (if (x >= 0) 1f else -1f) * (0.4f + 0.6f * abs(x))
        val mx = (cx + edge) / 2f + bow * 0.35f

        // Its shadow on the page below (on whichever side the page is over).
        if (lift > 0.02f) {
            val side = if (x >= 0) 1f else -1f
            val sw = pageW * 0.28f * lift
            val from = edge
            val to = edge + side * sw
            c.save()
            pageShape(path, left = x < 0); c.clipPath(path)
            fill.shader = LinearGradient(from, 0f, to, 0f, (0x40 * lift).toInt() shl 24, 0, Shader.TileMode.CLAMP)
            c.drawRect(min(from, to), top - arch, maxOf(from, to), bottom + sag, fill)
            c.restore()
            fill.shader = null
        }

        path.reset()
        path.moveTo(cx, top + sag)
        path.quadTo(mx, et - arch * (1f + lift), edge, et)
        path.quadTo(edge + bow * 0.08f, (et + eb) / 2f, edge, eb)
        path.quadTo(mx, eb - arch * 0.6f, cx, bottom + sag * 0.5f)
        path.close()

        val front = x >= 0
        val light = if (front) paperLight else 0xFFEDE3D2.toInt()
        val dark = if (front) 0xFFE9DFCC.toInt() else 0xFFD8CBB4.toInt()
        // Shading along the page: darker at the spine, a highlight on the curl, then the edge.
        fill.shader = LinearGradient(cx, 0f, edge, 0f,
            intArrayOf(dark, light, light, dark), floatArrayOf(0f, 0.45f, 0.8f, 1f), Shader.TileMode.CLAMP)
        c.drawPath(path, fill)
        fill.shader = null
        // The picture on the turning page (the one that was on the right), squeezed as it turns, with the same light.
        val turning = if (real()) pic(shown - 1) else null
        if (turning != null) {
            c.save(); c.clipPath(path)
            dst.set(min(cx, edge), et - arch * (1f + lift), maxOf(cx, edge), eb)
            if (dst.width() > 2f) {
                drawCrop(c, turning, dst)
                fill.shader = LinearGradient(cx, 0f, edge, 0f,
                    intArrayOf(0x40000000, 0x00000000, 0x22FFFFFF, 0x30000000), floatArrayOf(0f, 0.45f, 0.8f, 1f), Shader.TileMode.CLAMP)
                c.drawPath(path, fill)
                fill.shader = null
            }
            c.restore()
        }
        // Faint words showing on the front, as it starts to lift.
        if (turning == null && !real() && front && lift < 0.85f) {
            line.color = ink; line.alpha = (70 * (1f - lift)).toInt(); line.strokeWidth = pageH * 0.012f
            val span = edge - cx
            for (i in 0 until 4) {
                val y = top + pageH * (0.66f + 0.06f * i)
                c.drawLine(cx + span * 0.16f, y, cx + span * (if (i == 3) 0.55f else 0.86f), y, line)
            }
            line.alpha = 255
        }
        // The outline and a thin light line on the edge.
        line.color = 0x33000000; line.strokeWidth = pageW * 0.004f
        c.drawPath(path, line)
        line.color = 0x88FFFFFF.toInt(); line.strokeWidth = pageW * 0.006f
        c.drawLine(edge, et + pageH * 0.02f, edge, eb - pageH * 0.02f, line)
    }

    /** A satin ribbon bookmark hanging from the spine, swaying gently. */
    private fun ribbon(c: Canvas) {
        val sway = sin(time * 1.6f) * pageW * 0.03f
        val w = pageW * 0.05f
        val y0 = bottom + sag * 0.5f - pageH * 0.02f
        val y1 = bottom + pageH * 0.16f
        path.reset()
        path.moveTo(cx - w / 2f, y0)
        path.quadTo(cx - w / 2f + sway * 0.5f, (y0 + y1) / 2f, cx - w / 2f + sway, y1)
        path.lineTo(cx + sway, y1 - w * 0.6f)
        path.lineTo(cx + w / 2f + sway, y1)
        path.quadTo(cx + w / 2f + sway * 0.5f, (y0 + y1) / 2f, cx + w / 2f, y0)
        path.close()
        fill.shader = LinearGradient(cx - w, 0f, cx + w, 0f, 0xFFB8862E.toInt(), gold, Shader.TileMode.MIRROR)
        c.drawPath(path, fill)
        fill.shader = null
    }

    /** A few soft golden sparkles rising from the open book. */
    private fun sparkles(c: Canvas, h: Float) {
        for (k in 0 until 6) {
            val phase = (time * 0.25f + k / 6f) % 1f
            val sx = cx + sin(k * 2.1f + time * 0.6f) * pageW * 0.8f
            val sy = top - phase * h * 0.14f + pageH * 0.08f
            val r = (1f - abs(phase - 0.5f) * 2f) * pageW * 0.03f
            fill.color = if (k % 3 == 0) 0xFFB9B1FF.toInt() else gold
            fill.alpha = (200 * (1f - phase)).toInt()
            star(c, sx, sy, r)
        }
        fill.alpha = 255
    }

    private fun star(c: Canvas, x: Float, y: Float, r: Float) {
        if (r <= 0f) return
        path2.reset()
        path2.moveTo(x, y - r); path2.quadTo(x, y, x + r, y); path2.quadTo(x, y, x, y + r); path2.quadTo(x, y, x - r, y); path2.quadTo(x, y, x, y - r); path2.close()
        c.drawPath(path2, fill)
    }
}
