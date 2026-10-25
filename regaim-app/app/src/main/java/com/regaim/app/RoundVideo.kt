package com.regaim.app

import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.TextView
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.max

/**
 * Round moments, like a short video message in WhatsApp: the moment shows in a circle, and the one most in view
 * plays its heart silently, over and over. Sound only when the moment is opened. One clip plays at a time (battery),
 * nothing plays when "living pictures" are off in the settings, and nothing plays while the screen isn't seen.
 */
object RoundVideo {
    /** Clips any view to a circle (its background and content). */
    val OVAL: ViewOutlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) = outline.setOval(0, 0, view.width, view.height)
    }

    private class Spot(var m: Memory, val inset: Int)

    private val spots = WeakHashMap<FrameLayout, Spot>()
    private val listening = WeakHashMap<FrameLayout, Boolean>()
    private val main = Handler(Looper.getMainLooper())
    private val io = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var currentHost: WeakReference<FrameLayout>? = null
    private var player: MediaPlayer? = null
    private var surface: Surface? = null
    private var layer: FrameLayout? = null
    @Volatile private var generation = 0
    private var ticking = false

    /**
     * Lets [host] play [m] silently while it's the moment most in view. [inset]: the ring's width, so the clip sits
     * inside the ring. For a photo (or with living pictures off) the host just shows its picture.
     */
    fun attach(host: FrameLayout, m: Memory, inset: Int) {
        if (!m.isVideo || !Motion.enabled(host.context)) { detach(host); return }
        val spot = spots[host]
        if (spot == null) spots[host] = Spot(m, inset)
        else if (spot.m.id != m.id) {
            if (currentHost?.get() === host) stop()
            spot.m = m
        }
        if (listening[host] != true) {
            listening[host] = true
            host.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = tick()
                override fun onViewDetachedFromWindow(v: View) { if (currentHost?.get() === v) stop() }
            })
            host.viewTreeObserver.addOnWindowFocusChangeListener { focused -> if (focused) tick() }
        }
        tick()
    }

    /** [host] no longer shows a clip (it was reused for a photo). */
    fun detach(host: FrameLayout) {
        if (currentHost?.get() === host) stop()
        spots.remove(host)
    }

    /** "0:09 🔇" at the bottom of the circle, like WhatsApp. Null for a photo. */
    fun badge(host: FrameLayout, m: Memory, bottomPx: Int): TextView? {
        if (!m.isVideo) return null
        val c = host.context
        return TextView(c).apply {
            text = Fmt.shortDuration(m.durationMs) + "  🔇"
            textSize = 12f
            typeface = DS.emphasis
            setTextColor(DS.onPhoto(c))
            setShadowLayer(6f, 0f, 1f, DS.scrim(c))
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = bottomPx }
        }
    }

    // ---------- choosing which one plays ----------

    private val loop = object : Runnable {
        override fun run() {
            check()
            // Only while a round moment is on a screen that's seen: back in the app, window focus starts it again.
            if (spots.keys.any { it.isAttachedToWindow && it.windowVisibility == View.VISIBLE }) main.postDelayed(this, 400) else ticking = false
        }
    }

    private fun tick() {
        if (ticking) return
        ticking = true
        main.post(loop)
    }

    private val rect = android.graphics.Rect()

    /** How much of [h] is on screen (0..1), 0 when its screen isn't seen. */
    private fun seen(h: FrameLayout): Float {
        if (!h.isAttachedToWindow || !h.isShown || h.windowVisibility != View.VISIBLE || h.width == 0 || h.height == 0) return 0f
        if (!h.getGlobalVisibleRect(rect)) return 0f
        return rect.width().toFloat() * rect.height() / (h.width.toFloat() * h.height)
    }

    private fun check() {
        val cur = currentHost?.get()
        if (cur != null) {
            if (seen(cur) >= 0.6f) { keepInWindow(); return } // keep playing: no jumping while scrolling
            stop()
        }
        var best: FrameLayout? = null
        var bestScore = 0f
        for ((h, _) in spots.entries.toList()) {
            val f = seen(h)
            if (f < 0.8f) continue
            val dm = h.resources.displayMetrics
            val off = abs(rect.exactCenterX() - dm.widthPixels / 2f) + abs(rect.exactCenterY() - dm.heightPixels / 2f)
            val score = f * 100000f - off
            if (best == null || score > bestScore) { best = h; bestScore = score }
        }
        val b = best ?: return
        val spot = spots[b] ?: return
        play(b, spot)
    }

    private fun window(m: Memory): Pair<Long, Long> {
        val w = m.previewWindow
        return if (w != null) w.startMs to w.endMs else 0L to m.durationMs.coerceAtLeast(1L)
    }

    /** Loops only the heart of the moment (a few seconds), not the whole clip. */
    private fun keepInWindow() {
        val p = player ?: return
        val spot = currentHost?.get()?.let { spots[it] } ?: return
        val (start, end) = window(spot.m)
        try { if (p.isPlaying && p.currentPosition >= end) seek(p, start) } catch (_: Exception) {}
    }

    private fun seek(p: MediaPlayer, ms: Long) {
        if (Build.VERSION.SDK_INT >= 26) p.seekTo(ms, MediaPlayer.SEEK_CLOSEST) else p.seekTo(ms.toInt())
    }

    // ---------- playing ----------

    private fun play(host: FrameLayout, spot: Spot) {
        stop()
        val ctx = host.context
        val m = spot.m
        val token = ++generation
        val clip = FrameLayout(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
                setMargins(spot.inset, spot.inset, spot.inset, spot.inset)
            }
            outlineProvider = OVAL
            clipToOutline = true
            alpha = 0f // the picture stays until the first frame is drawn
            isClickable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val tv = TextureView(ctx)
        clip.addView(tv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        host.addView(clip, minOf(1, host.childCount)) // above the picture, under the badge and the selection check
        layer = clip
        currentHost = WeakReference(host)
        tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                if (token != generation) return
                val (start, _) = window(m)
                val mp = MediaPlayer()
                player = mp
                val s = Surface(st)
                surface = s
                mp.setOnInfoListener { _, what, _ ->
                    if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START && token == generation) clip.animate().alpha(1f).setDuration(250).start()
                    false
                }
                mp.setOnCompletionListener { p -> if (token == generation) try { seek(p, start); p.start() } catch (_: Exception) {} }
                mp.setOnErrorListener { _, _, _ -> if (token == generation) stop(); true } // the picture stays
                val uri = if (m.uri.startsWith("content://")) Uri.parse(m.uri) else Uri.parse("file://" + m.uri)
                // Opening a clip can take long (a big file, or one still in the cloud): never on the main thread,
                // or the whole app freezes. One clip at a time, in order, so a stop never races an open.
                io.execute {
                    val ok = try {
                        if (token != generation) false else {
                            mp.setDataSource(ctx.applicationContext, uri)
                            mp.setSurface(s)
                            mp.setVolume(0f, 0f) // silent: sound only inside the moment
                            mp.prepare()
                            true
                        }
                    } catch (_: Throwable) { false }
                    main.post {
                        if (token != generation || player !== mp) return@post
                        if (!ok) { stop(); return@post }
                        try {
                            crop(tv, mp.videoWidth, mp.videoHeight)
                            seek(mp, start)
                            mp.start()
                        } catch (_: Exception) { stop() }
                    }
                }
            }

            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
                if (token == generation) player?.let { try { crop(tv, it.videoWidth, it.videoHeight) } catch (_: Exception) {} }
            }

            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                if (token == generation) stop()
                return true
            }

            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
    }

    /** Fills the circle with the clip (cropped, not stretched). */
    private fun crop(tv: TextureView, vw: Int, vh: Int) {
        if (vw <= 0 || vh <= 0 || tv.width == 0 || tv.height == 0) return
        val sx = tv.width.toFloat() / vw
        val sy = tv.height.toFloat() / vh
        val s = max(sx, sy)
        tv.setTransform(Matrix().apply { setScale(s / sx, s / sy, tv.width / 2f, tv.height / 2f) })
    }

    private fun stop() {
        generation++
        val p = player
        val sf = surface
        player = null
        surface = null
        // Released off the main thread too (it can wait for the decoder), after any open still running.
        if (p != null || sf != null) io.execute {
            try { p?.release() } catch (_: Exception) {}
            try { sf?.release() } catch (_: Exception) {}
        }
        val l = layer
        layer = null
        currentHost = null
        // Removed on the next frame: this may run while the screen is taking views away.
        if (l != null) main.post { (l.parent as? ViewGroup)?.removeView(l) }
    }
}
