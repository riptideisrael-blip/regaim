package com.regaim.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import com.google.common.collect.ImmutableList
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * "The animated book": every drawn page of a finished book comes to life for about 5 seconds (the drawings are the
 * ones the family already approved; nothing is drawn again), with the page's words on screen, soft music and the
 * Moments mark, joined into one film saved to the gallery. The animation runs on fal (Kling), through our server.
 */
@OptIn(markerClass = [UnstableApi::class])
object BookFilm {
    private const val W = 720
    private const val H = 1280

    /** Animates every page that has no clip yet. [progress]: (done, total). Background thread only. */
    fun animate(ctx: Context, link: BookMaker.Link, bookId: String, cancelled: () -> Boolean, progress: (Int, Int) -> Unit) {
        val b = BookStore.load(ctx, bookId) ?: return
        val dir = BookStore.dir(ctx, b.id)
        val all = b.allPages
        var done = all.count { it.clip.isNotEmpty() && File(dir, it.clip).exists() }
        progress(done, all.size)
        all.forEachIndexed { i, p ->
            if (cancelled()) return
            if (p.image.isEmpty() || (p.clip.isNotEmpty() && File(dir, p.clip).exists())) return@forEachIndexed
            val (url, req) = try {
                link.job(mapOf("kind" to "book_anim", "image" to pageImage(File(dir, p.image)), "scene" to p.scene.take(500)))
            } catch (e: BookMaker.NeedCredit) { throw e } catch (e: Net.Offline) { throw e } catch (e: CartoonFilm.FatalEdgeError) { throw e
            } catch (_: Exception) { null to null }
            val name = "clip_${i}_${System.currentTimeMillis()}.mp4"
            val ok = url != null && try { link.downloadTo(url, File(dir, name)) } catch (e: Net.Offline) { throw e } catch (_: Exception) { false }
            req?.let { link.forget(bookId, it) }
            if (ok) {
                BookStore.update(ctx, bookId) { x ->
                    if (i == 0) x.copy(cover = x.cover.copy(clip = name))
                    else x.copy(pages = x.pages.mapIndexed { k, q -> if (k == i - 1) q.copy(clip = name) else q })
                }
                done++
            }
            progress(done, all.size)
        }
    }

    /** The page as sent to the animation (a big page is made smaller so it stays within the server's limit). */
    private fun pageImage(f: File): String =
        if (f.length() < 2_500_000) CartoonFilm.dataUriOfFile(f)
        else CartoonFilm.dataUri(android.graphics.BitmapFactory.decodeFile(f.absolutePath))

    /** The page's words over the bottom of the frame (and the mark), as one transparent layer. */
    private fun captionLayer(ctx: Context, text: String, rtl: Boolean, title: Boolean): Bitmap {
        val layer = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(layer)
        if (text.isNotBlank()) {
            val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (title) Color.WHITE else Color.parseColor("#2A2420")
                textSize = if (title) 54f else 30f
                typeface = Fonts.display
                if (title) setShadowLayer(8f, 0f, 3f, 0xAA000000.toInt())
            }
            val t = BookJson.display(text)
            val l = StaticLayout.Builder.obtain(t, 0, t.length, tp, W - 120)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setTextDirection(if (rtl) TextDirectionHeuristics.RTL else TextDirectionHeuristics.LTR)
                .setLineSpacing(8f, 1f).build()
            if (title) {
                c.save(); c.translate(60f, 110f); l.draw(c); c.restore()
            } else {
                val top = H - 60f - l.height - 56f
                c.drawRoundRect(RectF(36f, top, W - 36f, H - 60f), 30f, 30f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xEBF8F2E7.toInt() })
                c.save(); c.translate(60f, top + 28f); l.draw(c); c.restore()
            }
        }
        val mark = SocialShare.markLayer(ctx, W, H)
        // The mark goes to the top corner (the words are at the bottom).
        c.drawBitmap(mark, 0f, -(H * 0.80f), Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = 200 })
        return layer
    }

    /**
     * Joins the page clips (cover first) into one film with the words on screen and soft music, saves it to the
     * gallery and returns its address, or null. Background thread only (waits for the export).
     */
    fun compose(ctx: Context, bookId: String): String? {
        val b = BookStore.load(ctx, bookId) ?: return null
        val dir = BookStore.dir(ctx, b.id)
        val rtl = b.lang == "he"
        val items = ArrayList<EditedMediaItem>()
        var totalMs = 0L
        b.allPages.forEachIndexed { i, p ->
            if (p.clip.isEmpty()) return@forEachIndexed
            val f = File(dir, p.clip)
            if (!f.exists()) return@forEachIndexed
            val overlay = captionLayer(ctx, if (i == 0) b.title else p.text, rtl, title = i == 0)
            val effects = Effects(emptyList<AudioProcessor>(), listOf<Effect>(
                Presentation.createForWidthAndHeight(W, H, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP),
                OverlayEffect(ImmutableList.of(BitmapOverlay.createStaticBitmapOverlay(overlay, OverlaySettings.Builder().build()))),
            ))
            items.add(EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(f))).setRemoveAudio(true).setEffects(effects).build())
            totalMs += 5_000L
        }
        if (items.isEmpty()) return null
        val temp = ArrayList<File>()
        try {
            items.add(SocialShare.endCardItem(SocialShare.endCard(ctx, W, H).also { temp.add(it) }, W to H))
            totalMs += SocialShare.END_CARD_MS
        } catch (_: Exception) { }
        val track = try {
            File(ctx.cacheDir, "book_music_${System.currentTimeMillis()}.wav").also { temp.add(it); AudioMix.write(Music.Mood.CALM, null, totalMs, 0.8f, it) }
        } catch (_: Exception) { null }
        val out = File(File(ctx.cacheDir, "share").apply { mkdirs() }, "book_film_${System.currentTimeMillis()}.mp4")

        fun export(withSound: Boolean): Boolean {
            val latch = CountDownLatch(1)
            var ok = false
            Handler(Looper.getMainLooper()).post {
                try {
                    val seqs = arrayListOf(EditedMediaItemSequence(items))
                    if (withSound && track != null) seqs.add(EditedMediaItemSequence(EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(track))).setRemoveVideo(true).build()))
                    Transformer.Builder(ctx).setVideoMimeType(MimeTypes.VIDEO_H264)
                        .addListener(object : Transformer.Listener {
                            override fun onCompleted(composition: Composition, exportResult: ExportResult) { ok = true; latch.countDown() }
                            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) { latch.countDown() }
                        }).build()
                        .start(Composition.Builder(seqs).build(), out.absolutePath)
                } catch (_: Throwable) { latch.countDown() }
            }
            latch.await(15, TimeUnit.MINUTES)
            return ok && out.exists() && out.length() > 10_000
        }
        val made = export(track != null) || export(false)
        temp.forEach { it.delete() }
        if (!made) return null
        return try {
            ReelMaker.publish(ctx, out, "Moments_book_${System.currentTimeMillis()}.mp4").also { out.delete() }
        } catch (_: Exception) { null }
    }
}
