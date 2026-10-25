package com.regaim.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The big round "memory bubble" on the main screen:
 * live camera view inside a circle, a ring that fills with the laughter level,
 * and a soft pulse while recording.
 */
class LiveBubbleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var bitmap: Bitmap? = null
        set(value) {
            if (field !== value) {
                field = value
                invalidate()
            }
        }

    /** 0..1 laughter score. */
    var level = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    /** 0..1 position of the save threshold on the ring. */
    var threshold = 0.3f

    var recording = false
        set(value) {
            field = value
            invalidate()
        }

    /** Flash when a moment was just caught. */
    var caught = false

    private val density = resources.displayMetrics.density
    private val ringWidth = 10f * density
    private val accent = Color.parseColor("#87664F")
    private val accentSoft = Color.parseColor("#F0E6DC")
    private val ink = Color.parseColor("#1F2A26")

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accentSoft }
    private val photo = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val dim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(90, 31, 29, 43) }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ringWidth
        color = Color.parseColor("#F0E6DC")
    }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ringWidth
        strokeCap = Paint.Cap.ROUND
        color = accent
    }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        strokeCap = Paint.Cap.ROUND
        color = ink
    }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ink
        textAlign = Paint.Align.CENTER
        textSize = 18f * density
        isFakeBoldText = true
    }
    private val subLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6E6A80")
        textAlign = Paint.Align.CENTER
        textSize = 14f * density
    }
    private val arc = RectF()
    private val shaderMatrix = Matrix()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val side = min(w, (280 * density).toInt())
        setMeasuredDimension(w, side)
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val outer = min(width, height) / 2f - 4f * density
        val ringR = outer - 16f * density
        val r = ringR - ringWidth / 2f - 6f * density

        // Pulse halo while recording.
        if (recording) {
            val t = (System.currentTimeMillis() % 1800L) / 1800f
            val pulse = (sin(t * 2 * Math.PI) * 0.5 + 0.5).toFloat()
            halo.alpha = if (caught) 110 else (25 + 35 * pulse).toInt()
            canvas.drawCircle(cx, cy, ringR + 6f * density + 8f * density * pulse, halo)
            postInvalidateOnAnimation()
        }

        // Inner circle: live camera, or a soft placeholder.
        val bmp = bitmap
        if (bmp != null && recording) {
            val scale = max(2 * r / bmp.width, 2 * r / bmp.height)
            shaderMatrix.reset()
            shaderMatrix.setScale(scale, scale)
            shaderMatrix.postTranslate(cx - bmp.width * scale / 2f, cy - bmp.height * scale / 2f)
            photo.shader = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                setLocalMatrix(shaderMatrix)
            }
            canvas.drawCircle(cx, cy, r, photo)
        } else {
            canvas.drawCircle(cx, cy, r, fill)
            if (recording) {
                canvas.drawCircle(cx, cy, r, dim)
                label.color = Color.WHITE
                canvas.drawText(L.t("המצלמה מתחממת...", "Camera warming up..."), cx, cy + label.textSize / 3, label)
                label.color = ink
            } else {
                canvas.drawText(L.t("לחץ על הכפתור", "Tap the button"), cx, cy - 4 * density, label)
                canvas.drawText(L.t("והבועה תתחיל לצלם", "and the bubble starts recording"), cx, cy + 20 * density, subLabel)
            }
        }

        // Laughter ring.
        arc.set(cx - ringR, cy - ringR, cx + ringR, cy + ringR)
        canvas.drawCircle(cx, cy, ringR, track)
        if (recording && level > 0.01f) canvas.drawArc(arc, -90f, 360f * level, false, ring)

        // Threshold tick: past this point a moment is saved.
        if (recording) {
            val a = Math.toRadians((-90 + 360 * threshold).toDouble())
            val inR = ringR - ringWidth
            val outR = ringR + ringWidth
            canvas.drawLine(
                (cx + inR * Math.cos(a)).toFloat(), (cy + inR * Math.sin(a)).toFloat(),
                (cx + outR * Math.cos(a)).toFloat(), (cy + outR * Math.sin(a)).toFloat(),
                tick,
            )
        }
    }
}
