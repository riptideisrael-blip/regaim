package com.regaim.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.ColorDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.util.Size
import android.widget.ImageView
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

/**
 * Rectangular cover images for the big memory cards (the round bubbles are too small for them).
 * Taken from the middle of the clip (the moment itself), cached on disk and in memory.
 */
object Covers {
    private val mem = object : LruCache<String, Bitmap>(minOf(40L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 8).toInt().coerceAtLeast(20 * 1024 * 1024)) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val pool = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    // "v2": covers are now up to 1080 pixels on the long side (photos were 720), so older, softer ones are made again once.
    private fun key(m: Memory) = "v2_${m.id}_${m.uri.hashCode()}_${m.mediaType}_${m.durationMs}_${m.previewWindow?.startMs}_${m.previewWindow?.endMs}"
    @Volatile private var oldRemoved = false
    private fun file(ctx: Context, m: Memory): File {
        val dir = File(ctx.filesDir, "covers").apply { mkdirs() }
        if (!oldRemoved) { // the softer covers of before "v2" are only copies: free their space once
            oldRemoved = true
            try { dir.listFiles { f -> !f.name.startsWith("v2_") }?.forEach { it.delete() } } catch (_: Exception) {}
        }
        return File(dir, key(m) + ".jpg")
    }

    /** Background thread only. */
    fun get(ctx: Context, m: Memory): Bitmap? {
        mem.get(key(m))?.let { return it }
        val f = file(ctx, m)
        val cached: Bitmap? = if (f.exists()) BitmapFactory.decodeFile(f.absolutePath) else null
        val real: Bitmap? = cached ?: extract(ctx, m)?.also { fresh ->
            try { FileOutputStream(f).use { out -> fresh.compress(Bitmap.CompressFormat.JPEG, 90, out) } } catch (_: Exception) {}
        }
        if (real != null) {
            mem.put(key(m), real)
            return real
        }
        // The small round preview is only a last resort, and it isn't kept: next time the original is tried again.
        return if (m.thumb.isNotEmpty()) BitmapFactory.decodeFile(m.thumb)?.let { innerSquare(it) } else null
    }

    /**
     * Background thread only. A picture for the whole screen: the photo, or the video's frame, at [maxSide] pixels
     * (not the card cover, which is smaller). Falls back to the card cover when the original can't be read.
     */
    fun sharp(ctx: Context, m: Memory, maxSide: Int): Bitmap? {
        val b = try { if (m.isVideo) frame(ctx, m, maxSide) else MediaImages.decode(ctx, m.uri, maxSide) } catch (_: Throwable) { null }
        return b ?: get(ctx, m)
    }

    /**
     * The saved preview is a round bubble (with a ring, on a see-through square). When nothing better can be read
     * (the clip can't be opened), the square inside the circle is used, so a card shows a picture, not a ringed
     * circle on an empty square.
     */
    private fun innerSquare(b: Bitmap): Bitmap {
        val side = (minOf(b.width, b.height) * 0.68f).toInt().coerceAtLeast(1)
        return Bitmap.createBitmap(b, (b.width - side) / 2, (b.height - side) / 2, side, side)
    }

    private fun extract(ctx: Context, m: Memory): Bitmap? =
        if (!m.isVideo) MediaImages.decode(ctx, m.uri, 1080) else frame(ctx, m, 1080, exact = false)

    /**
     * One frame of the clip, at most [maxSide] pixels on its long side. [exact] false (card covers, made for many
     * moments at once): the nearest key frame, much lighter for the phone than decoding to the exact moment. Asks the decoder for the smaller size directly
     * (a 4K frame at full size can run out of memory, which used to leave only the tiny round preview), then the
     * nearest key frame, then the gallery's own preview of the clip.
     */
    private fun frame(ctx: Context, m: Memory, maxSide: Int, exact: Boolean = true): Bitmap? {
        val mmr = MediaMetadataRetriever()
        try {
            if (m.uri.startsWith("content://")) mmr.setDataSource(ctx, Uri.parse(m.uri)) else mmr.setDataSource(m.uri)
            val dur = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: m.durationMs
            val window = m.previewWindow
            val atUs = (if (window != null) window.startMs + (window.endMs - window.startMs) / 2 else dur / 2) * 1000
            var f: Bitmap? = null
            if (Build.VERSION.SDK_INT >= 27) f = try { mmr.getScaledFrameAtTime(atUs, if (exact) MediaMetadataRetriever.OPTION_CLOSEST else MediaMetadataRetriever.OPTION_CLOSEST_SYNC, maxSide, maxSide) } catch (_: Throwable) { null }
            if (f == null) f = try { mmr.getFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) } catch (_: Throwable) { null }
            // A card cover that shows almost nothing (a wall, the floor, a blur): try two other moments of the clip
            // and keep the one with the most to see.
            if (f != null && !exact && Build.VERSION.SDK_INT >= 27 && score(f) < FrameScore.DULL) {
                val from = window?.startMs ?: 0L
                val to = window?.endMs ?: dur
                var best = f; var bestScore = score(f)
                for (part in listOf(0.2, 0.8)) {
                    val t = ((from + (to - from) * part).toLong()).coerceIn(0L, maxOf(dur, 0L)) * 1000
                    val other = try { mmr.getScaledFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, maxSide, maxSide) } catch (_: Throwable) { null } ?: continue
                    val sc = score(other)
                    if (sc > bestScore) { best = other; bestScore = sc }
                }
                f = best
            }
            if (f != null) return fit(f, maxSide)
        } catch (_: Throwable) {
        } finally {
            try { mmr.release() } catch (_: Exception) {}
        }
        if (Build.VERSION.SDK_INT >= 29 && m.uri.startsWith("content://")) {
            try { return fit(ctx.contentResolver.loadThumbnail(Uri.parse(m.uri), Size(maxSide, maxSide), null), maxSide) } catch (_: Throwable) {}
        }
        return null
    }

    /** [FrameScore.detail] of a small copy of [b]. */
    private fun score(b: Bitmap): Double = try {
        val small = Bitmap.createScaledBitmap(b, 48, maxOf(1, 48 * b.height / maxOf(1, b.width)), true)
        val px = IntArray(small.width * small.height)
        small.getPixels(px, 0, small.width, 0, 0, small.width, small.height)
        FrameScore.detail(px, small.width, small.height)
    } catch (_: Throwable) { 100.0 }

    private fun fit(b: Bitmap, maxSide: Int): Bitmap {
        val s = maxSide.toFloat() / maxOf(b.width, b.height)
        return if (s >= 1f) b
        else Bitmap.createScaledBitmap(b, (b.width * s).toInt().coerceAtLeast(1), (b.height * s).toInt().coerceAtLeast(1), true)
    }

    /**
     * A photo shown big (the moment screen): the card cover first (fast), then the photo itself at screen sharpness
     * ([maxSide] pixels). Not kept in the memory cache (too big). Videos show the cover only.
     */
    fun loadSharp(iv: ImageView, m: Memory, maxSide: Int, onReady: ((Bitmap) -> Unit)? = null) {
        load(iv, m, onReady)
        if (m.isVideo) return
        val k = key(m)
        val ctx = iv.context.applicationContext
        pool.execute {
            val b = try { MediaImages.decode(ctx, m.uri, maxSide) } catch (_: Throwable) { null } ?: return@execute
            main.post {
                if (iv.tag == k) {
                    iv.tag = k + "#sharp" // the smaller cover, if it arrives later, won't replace it
                    iv.setImageBitmap(b)
                    onReady?.invoke(b)
                }
            }
        }
    }

    /** Shows the cover in [iv] (placeholder first, soft fade in when ready). */
    fun load(iv: ImageView, m: Memory, onReady: ((Bitmap) -> Unit)? = null) {
        val k = key(m)
        iv.tag = k
        mem.get(k)?.let {
            iv.setImageBitmap(it)
            onReady?.invoke(it)
            return
        }
        iv.setImageDrawable(ColorDrawable(DS.buttonSecondary(iv.context)))
        val ctx = iv.context.applicationContext
        pool.execute {
            val b = try { get(ctx, m) } catch (_: Throwable) { null }
            main.post {
                if (b != null && iv.tag == k && !Motion.isPlaying(iv)) {
                    iv.alpha = 0f
                    iv.setImageBitmap(b)
                    iv.animate().alpha(1f).setDuration(220).start()
                    onReady?.invoke(b)
                }
            }
        }
    }
}
