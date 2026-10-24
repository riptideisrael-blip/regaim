package com.regaim.app

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Rect
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import java.io.File
import java.io.FileOutputStream
import java.util.WeakHashMap
import java.util.concurrent.Executors

/**
 * "Living" thumbnails: ~3 seconds around the heart of each moment (the laugh, the word, the hug),
 * shown as a soft looping flipbook. Frames are taken once, cached on disk, and only animate while on screen.
 */
object Motion {
    private const val FRAMES = 12
    private const val STEP_MS = 320L
    private val pool = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val running = WeakHashMap<ImageView, Runnable>()
    private val animators = WeakHashMap<ImageView, android.animation.ValueAnimator>()
    private val mem = object : android.util.LruCache<String, List<Bitmap>>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: List<Bitmap>) = value.sumOf { it.byteCount }
    }

    fun enabled(ctx: Context) = Settings.prefs(ctx).getBoolean("motion_on", true)

    private fun key(m: Memory) = "v2_${m.id}_${m.uri.hashCode()}_${m.durationMs}_${m.previewWindow?.startMs}_${m.previewWindow?.endMs}"
    private fun dir(ctx: Context) = File(ctx.filesDir, "motion").apply { mkdirs() }

    /** Background thread only. */
    private fun frames(ctx: Context, m: Memory, width: Int): List<Bitmap> {
        val window = m.previewWindow ?: return emptyList()
        if (window.isFallback) return emptyList()
        val k = key(m)
        mem.get(k)?.let { return it }
        val files = (0 until FRAMES).map { File(dir(ctx), "${k}_$it.jpg") }
        val list = if (files.all { it.exists() }) {
            files.mapNotNull { BitmapFactory.decodeFile(it.absolutePath) }
        } else {
            val mmr = MediaMetadataRetriever()
            try {
                if (m.uri.startsWith("content://")) mmr.setDataSource(ctx, Uri.parse(m.uri)) else mmr.setDataSource(m.uri)
                (0 until FRAMES).mapNotNull { i ->
                    val span = window.endMs - window.startMs
                    val t = (window.startMs + (span - 1).coerceAtLeast(0) * i / (FRAMES - 1)) * 1000
                    val raw = if (Build.VERSION.SDK_INT >= 27) {
                        mmr.getScaledFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST, width, width * 2)
                    } else mmr.getFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST)
                    raw?.let { b ->
                        val s = if (b.width > width) Bitmap.createScaledBitmap(b, width, (b.height * width.toFloat() / b.width).toInt(), true) else b
                        try { FileOutputStream(files[i]).use { o -> s.compress(Bitmap.CompressFormat.JPEG, 80, o) } } catch (_: Exception) {}
                        s
                    }
                }
            } catch (_: Throwable) {
                emptyList()
            } finally {
                try { mmr.release() } catch (_: Exception) {}
            }
        }
        if (list.size >= 3) mem.put(k, list)
        return list
    }

    /**
     * Starts the living preview in [iv] once its frames are ready (the static cover stays until then).
     * [round] clips it to a circle (for the round bubbles).
     */
    fun attach(iv: ImageView, m: Memory, round: Boolean = false, width: Int = 360) {
        stop(iv)
        val window = m.previewWindow
        if (!enabled(iv.context) || !m.isVideo || window == null || window.isFallback) return
        val k = key(m)
        iv.setTag(R.id.motionTag, k)
        val ctx = iv.context.applicationContext
        val begin = object : Runnable {
            override fun run() {
                if (iv.getTag(R.id.motionTag) != k || !iv.isAttachedToWindow) return
                running[iv] = this
                if (!canAnimate(iv)) {
                    main.postDelayed(this, STEP_MS)
                    return
                }
                pool.execute {
                    if (iv.getTag(R.id.motionTag) != k || !iv.isAttachedToWindow) return@execute
                    val list = try { frames(ctx, m, width) } catch (_: Throwable) { emptyList() }
                    main.post {
                        if (iv.getTag(R.id.motionTag) == k && iv.isAttachedToWindow) {
                            if (list.size >= 3) play(iv, list, round) else stop(iv)
                        }
                    }
                }
            }
        }
        if (iv.isAttachedToWindow) begin.run()
        iv.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                if (iv.getTag(R.id.motionTag) == k && running[iv] == null) begin.run()
            }
            override fun onViewDetachedFromWindow(v: View) {
                stop(iv)
                if (iv.getTag(R.id.motionTag) != k) iv.removeOnAttachStateChangeListener(this)
            }
        })
    }

    /**
     * One continuous, gently blended loop (forward, then back) over the frames: no hard cuts, no flashing.
     * The still cover fades into the motion instead of popping.
     */
    private fun play(iv: ImageView, list: List<Bitmap>, round: Boolean) {
        stop(iv)
        iv.scaleType = ImageView.ScaleType.FIT_XY
        if (round) {
            iv.outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
            iv.clipToOutline = true
        }
        val cover = (iv.drawable as? BitmapDrawable)?.bitmap
        val d = FlipDrawable(list, cover)
        iv.setImageDrawable(d)
        val n = list.size
        val cycle = 2 * n - 2
        val anim = android.animation.ValueAnimator.ofFloat(0f, cycle.toFloat()).apply {
            duration = cycle * STEP_MS
            interpolator = android.view.animation.LinearInterpolator()
            repeatCount = android.animation.ValueAnimator.INFINITE
            addUpdateListener { d.setTime(it.animatedValue as Float) }
        }
        animators[iv] = anim
        anim.start()
        anim.currentPlayTime = (Math.random() * anim.duration).toLong()   // tiles don't move in lockstep
        val watch = object : Runnable {
            override fun run() {
                if (!iv.isAttachedToWindow) { stop(iv); return }
                if (canAnimate(iv)) { if (anim.isPaused) anim.resume() } else if (anim.isRunning && !anim.isPaused) anim.pause()
                main.postDelayed(this, 400)
            }
        }
        running[iv] = watch
        main.postDelayed(watch, 400)
    }

    /** Draws frame [t] blended with the next one, centre-cropped to the view. */
    private class FlipDrawable(private val frames: List<Bitmap>, private val cover: Bitmap?) : android.graphics.drawable.Drawable() {
        private val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG or android.graphics.Paint.ANTI_ALIAS_FLAG)
        private var t = 0f
        private val born = android.os.SystemClock.uptimeMillis()

        fun setTime(v: Float) { t = v; invalidateSelf() }

        private fun frameAt(pos: Float): Pair<Bitmap, Bitmap> {
            val n = frames.size
            val cycle = 2 * n - 2
            val a = pos.toInt() % cycle
            val b = (a + 1) % cycle
            fun idx(x: Int) = if (x < n) x else cycle - x
            return frames[idx(a)] to frames[idx(b)]
        }

        private fun crop(c: android.graphics.Canvas, bmp: Bitmap, alpha: Int) {
            val w = bounds.width().toFloat(); val h = bounds.height().toFloat()
            if (w <= 0 || h <= 0) return
            val sc = maxOf(w / bmp.width, h / bmp.height)
            val save = c.save()
            c.translate(bounds.left + (w - bmp.width * sc) / 2f, bounds.top + (h - bmp.height * sc) / 2f)
            c.scale(sc, sc)
            paint.alpha = alpha
            c.drawBitmap(bmp, 0f, 0f, paint)
            c.restoreToCount(save)
        }

        override fun draw(canvas: android.graphics.Canvas) {
            val (a, b) = frameAt(t)
            val frac = t - t.toInt()
            // fade the motion in over the still cover (first 450 ms)
            val appear = ((android.os.SystemClock.uptimeMillis() - born) / 450f).coerceIn(0f, 1f)
            if (cover != null && appear < 1f) crop(canvas, cover, 255)
            val layer = (255 * appear).toInt()
            crop(canvas, a, layer)
            if (frac > 0.01f) crop(canvas, b, (layer * frac).toInt())
            if (appear < 1f) invalidateSelf()
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
        @Suppress("OVERRIDE_DEPRECATION")
        override fun getOpacity() = android.graphics.PixelFormat.OPAQUE
    }

    fun isPlaying(iv: ImageView) = animators.containsKey(iv)

    fun stop(iv: ImageView) {
        running.remove(iv)?.let { main.removeCallbacks(it) }
        animators.remove(iv)?.cancel()
    }

    private fun canAnimate(iv: ImageView): Boolean {
        if (!iv.isShown || iv.windowVisibility != View.VISIBLE || !iv.getGlobalVisibleRect(Rect())) return false
        var context = iv.context
        while (context is ContextWrapper) {
            if (context is LifecycleOwner) return context.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if (context.baseContext === context) break
            context = context.baseContext
        }
        return true
    }
}
