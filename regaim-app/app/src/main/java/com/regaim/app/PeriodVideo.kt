package com.regaim.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.MatrixTransformation
import androidx.media3.effect.RgbMatrix
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.google.common.collect.ImmutableList
import java.io.File
import java.io.FileOutputStream
import java.util.Calendar
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A video of a week or a month: a title card, then the best moments (a few seconds each), with music and the
 * Moments mark. Built with the same media3 pipeline as sharing.
 */
@OptIn(markerClass = [UnstableApi::class])
object PeriodVideo {
    enum class Period(private val he: String, private val en: String) {
        THIS_WEEK("השבוע", "This week"), LAST_WEEK("שבוע שעבר", "Last week"),
        THIS_MONTH("החודש", "This month"), LAST_MONTH("חודש שעבר", "Last month");
        val title: String get() = L.t(he, en)
        val videoTitle: String get() = when (this) {
            THIS_WEEK -> L.t("השבוע שלנו", "Our week")
            LAST_WEEK -> L.t("השבוע שעבר", "Last week")
            THIS_MONTH, LAST_MONTH -> L.t("החודש שלנו", "Our month")
        }
    }

    private const val MAX_ITEMS = 10
    private const val VIDEO_MS = 4500L
    private const val PHOTO_MS = 3500L
    private const val INTRO_MS = 2500L
    private val worker = Executors.newSingleThreadExecutor()

    /** [from, to) in epoch millis. */
    fun range(p: Period, now: Long = System.currentTimeMillis()): Pair<Long, Long> {
        val day = 24L * 3600_000L
        fun monthStart(offset: Int) = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            add(Calendar.MONTH, offset)
        }.timeInMillis
        return when (p) {
            Period.THIS_WEEK -> (now - 7 * day) to now + 1
            Period.LAST_WEEK -> (now - 14 * day) to (now - 7 * day)
            Period.THIS_MONTH -> monthStart(0) to now + 1
            Period.LAST_MONTH -> monthStart(-1) to monthStart(0)
        }
    }

    fun moments(ctx: Context, p: Period): List<Memory> {
        val (from, to) = range(p)
        return MemoryStore.kept(ctx).filter { it.time in from until to }
    }

    /** The best moments of the period, in the order they happened. */
    fun pick(list: List<Memory>): List<Memory> = ReelMaker.best(list).take(MAX_ITEMS).sortedBy { it.time }

    fun seconds(picks: List<Memory>): Int = ((INTRO_MS + picks.sumOf { segmentLength(it) }) / 1000L).toInt()

    private fun segmentLength(m: Memory): Long =
        if (!m.isVideo) PHOTO_MS else if (m.durationMs in 1 until VIDEO_MS) m.durationMs else VIDEO_MS

    private fun segmentStart(m: Memory): Long {
        if (!m.isVideo || m.durationMs <= VIDEO_MS) return 0L
        val w = m.previewWindow
        val mid = if (w != null) (w.startMs + w.endMs) / 2 else m.highlight
        return (mid - VIDEO_MS / 2).coerceIn(0L, m.durationMs - VIDEO_MS)
    }

    private fun source(m: Memory): Uri = if (m.uri.startsWith("content://")) Uri.parse(m.uri) else Uri.fromFile(File(m.uri))

    class Handle { @Volatile var cancel: (() -> Unit)? = null }

    /** The editor's decision for a set of moments: the plan, and the moments it uses. */
    class Prepared(val plan: ReelPlan, val byId: Map<String, Memory>, val considered: Int)

    /** Music name shown on the preview card: the recorded piece the film will use, or the composed music. */
    fun musicName(plan: ReelPlan): String = LicensedMusic.forStyle(plan.style, plan.seed)?.title ?: musicName(plan.style)

    fun musicName(style: ReelStyle): String = when (style) {
        ReelStyle.EMOTIONAL -> L.t("פסנתר חם", "Warm Piano")
        ReelStyle.HAPPY -> L.t("משפחה שמחה", "Warm Family")
        ReelStyle.CINEMATIC -> L.t("קולנועי עדין", "Light Cinematic")
    }

    fun styleName(style: ReelStyle): String = when (style) {
        ReelStyle.EMOTIONAL -> L.t("רגשי", "Emotional")
        ReelStyle.HAPPY -> L.t("שמח ומשפחתי", "Happy family")
        ReelStyle.CINEMATIC -> L.t("קולנועי", "Cinematic")
    }

    /**
     * Looks at every moment (on the phone), then lets the editor choose and order them. [style] null = the one that
     * fits the week. [seed] > 0 = another version. Background thread only.
     */
    fun prepare(ctx: Context, period: Period, moments: List<Memory>, style: ReelStyle?, seed: Int = 0, progress: (Int) -> Unit = {}): Prepared {
        val inputs = ReelSignals.of(ctx, moments.filter { it.origin != "film" }, progress)
        val chosenStyle = style ?: ReelEditor.autoStyle(inputs)
        val (from, _) = range(period)
        val month = java.text.SimpleDateFormat("LLLL yyyy", L.locale).format(java.util.Date(maxOf(from, moments.maxOfOrNull { it.time } ?: from)))
        val plan = ReelEditor.plan(inputs, chosenStyle, seed, month, hebrew = !L.isEn)
        return Prepared(plan, moments.associateBy { it.id }, inputs.size)
    }

    /** For callers that just want "the week's video": decide and render in one go. Callbacks on the main thread. */
    fun makeAuto(ctx: Context, period: Period, moments: List<Memory>, onProgress: (Int) -> Unit, onDone: (File?, String?) -> Unit): Handle {
        val handle = Handle()
        val main = Handler(Looper.getMainLooper())
        worker.execute {
            val p = try { prepare(ctx, period, moments, null) } catch (e: Exception) { null }
            main.post {
                if (p == null || p.plan.shots.size < 2) onDone(null, L.t("אין מספיק רגעים טובים לסרטון", "Not enough good moments for a video"))
                else { val h = make(ctx, p, onProgress, onDone); handle.cancel = h.cancel }
            }
        }
        return handle
    }

    /**
     * Renders the plan: each shot for its own length, cut on the beat; photos with a gentle Ken Burns move; a soft
     * warm grade; a small caption over the second shot; a closing card; the composed score with the moments' own
     * laughs ducked in. All callbacks run on the main thread. [onDone] gets the file, or null and a message.
     */
    fun make(ctx: Context, prepared: Prepared, onProgress: (Int) -> Unit, onDone: (File?, String?) -> Unit): Handle {
        val handle = Handle()
        val main = Handler(Looper.getMainLooper())
        val cancelled = AtomicBoolean(false)
        val finished = AtomicBoolean(false)
        handle.cancel = { cancelled.set(true) }
        val plan = prepared.plan
        worker.execute {
            val temp = ArrayList<File>()
            try {
                if (plan.shots.size < 2) throw IllegalStateException(L.t("אין מספיק רגעים טובים לסרטון", "Not enough good moments for a video"))
                // Output shape: portrait unless most clips are landscape.
                var portrait = 0
                var landscape = 0
                for (s in plan.shots.filter { it.isVideo }) {
                    val m = prepared.byId[s.id] ?: continue
                    val (w, h) = SocialShare.displaySize(ctx, m)
                    if (h >= w) portrait++ else landscape++
                }
                val outW = if (landscape > portrait) 1920 else 1080
                val outH = if (landscape > portrait) 1080 else 1920
                val withMark = Settings.prefs(ctx).getBoolean("share_watermark", true)
                val markBitmap = if (withMark) SocialShare.markLayer(ctx, outW, outH) else null
                val captionBitmap = caption(plan.introCaption, outW, outH)

                fun effects(s: ReelShot?, index: Int, lenMs: Long, fadeIn: Long, fadeOut: Long, mark: Boolean, otherShape: Boolean = false): Effects {
                    val list = ArrayList<Effect>()
                    if (otherShape) {
                        // A sideways clip in a tall video (or the other way round): cropping it to fill cut people
                        // off, and fitting it whole left it tiny. In between: bigger, the sides trimmed a little.
                        list.add(Presentation.createForWidthAndHeight(outW, outH, Presentation.LAYOUT_SCALE_TO_FIT))
                        list.add(MatrixTransformation { android.graphics.Matrix().apply { setScale(OTHER_SHAPE_ZOOM, OTHER_SHAPE_ZOOM) } })
                    } else list.add(Presentation.createForWidthAndHeight(outW, outH, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP))
                    if (s != null && s.motion != KenBurns.NONE) list.add(kenBurns(s.motion, s.amount, lenMs * 1000L))
                    list.add(grade(plan.style, fadeIn * 1000L, fadeOut * 1000L, lenMs * 1000L))
                    val overlays = ArrayList<androidx.media3.effect.TextureOverlay>()
                    if (mark && markBitmap != null) overlays.add(BitmapOverlay.createStaticBitmapOverlay(markBitmap, OverlaySettings.Builder().build()))
                    if (index == 1) overlays.add(FadingOverlay(captionBitmap, lenMs * 1000L))
                    if (overlays.isNotEmpty()) list.add(OverlayEffect(ImmutableList.copyOf(overlays)))
                    return Effects(emptyList<AudioProcessor>(), list)
                }

                val items = ArrayList<EditedMediaItem>()
                // The shots that really made it in, re-timed (a clip deleted from the gallery is skipped, and the music follows).
                val kept = ArrayList<ReelShot>()
                var used = 0L
                for (s in plan.shots) {
                    val index = items.size
                    if (cancelled.get()) break
                    val m = prepared.byId[s.id] ?: continue
                    if (s.isVideo) {
                        val realMs = readableDuration(ctx, m) ?: continue
                        val start = s.sourceStartMs.coerceIn(0L, maxOf(0L, realMs - s.lengthMs))
                        val len = minOf(s.lengthMs, realMs - start)
                        val clip = MediaItem.ClippingConfiguration.Builder().setStartPositionMs(start).setEndPositionMs(start + len).build()
                        val mi = MediaItem.Builder().setUri(source(m)).setClippingConfiguration(clip).build()
                        val (vw, vh) = SocialShare.displaySize(ctx, m)
                        val otherShape = (vh >= vw) != (outH >= outW)
                        items.add(EditedMediaItem.Builder(mi).setRemoveAudio(true).setEffects(effects(s, index, len, s.fadeInMs, s.fadeOutMs, true, otherShape)).build())
                        kept.add(s.copy(sourceStartMs = start, lengthMs = len, atMs = used))
                        used += len
                    } else {
                        val bmp = MediaImages.decode(ctx, m.uri, 1920) ?: continue
                        val f = File(ctx.cacheDir, "photo_${System.nanoTime()}.jpg").also { temp.add(it) }
                        FileOutputStream(f).use { framed(bmp, outW, outH).compress(Bitmap.CompressFormat.JPEG, 92, it) }
                        items.add(EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(f))).setDurationUs(s.lengthMs * 1000L).setFrameRate(30)
                            .setEffects(effects(s, index, s.lengthMs, s.fadeInMs, s.fadeOutMs, true)).build())
                        kept.add(s.copy(atMs = used))
                        used += s.lengthMs
                    }
                }
                if (items.size < 2) throw IllegalStateException(L.t("לא הצלחנו לקרוא את הרגעים", "Couldn't read the moments"))
                // The closing card: a quiet line, the month, a small mark.
                val card = File(ctx.cacheDir, "outro_${System.currentTimeMillis()}.jpg").also { temp.add(it) }
                FileOutputStream(card).use { outro(ctx, plan, outW, outH, withMark).compress(Bitmap.CompressFormat.JPEG, 92, it) }
                items.add(EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(card))).setDurationUs(plan.outroMs * 1000L).setFrameRate(30)
                    .setEffects(effects(null, -1, plan.outroMs, 500L, 600L, false)).build())
                // Then the Moments card, like the short-video apps end (with the watermark setting on).
                if (withMark) {
                    val end = SocialShare.endCard(ctx, outW, outH).also { temp.add(it) }
                    items.add(SocialShare.endCardItem(end, outW to outH))
                }

                // The score, written for the edit as it was really built, with the moments' own laughs.
                val peakId = plan.shots.getOrNull(plan.peakIndex)?.id
                val real = plan.copy(
                    shots = kept, totalMs = used + plan.outroMs,
                    peakIndex = kept.indexOfFirst { it.id == peakId }.let { if (it < 0) kept.size - 1 else it },
                )
                // Recorded music when one fits the style; the music composed for the film otherwise (same length either way).
                val frames = (real.totalMs / 1000.0 * AudioMix.RATE).toInt()
                val track = LicensedMusic.forStyle(real.style, real.seed)?.let { LicensedMusic.track(ctx, it, frames) } ?: ReelScore.compose(real)
                val wav = File(ctx.cacheDir, "reel_${System.currentTimeMillis()}.wav").also { temp.add(it) }
                AudioMix.reel(ctx, real, prepared.byId, track, wav)

                val dir = File(ctx.cacheDir, "share").apply { mkdirs() }
                val out = File(dir, "moments_${plan.style.key}_${System.currentTimeMillis()}.mp4")
                main.post {
                    if (cancelled.get()) { temp.forEach { it.delete() }; return@post }
                    try {
                        val sequences = ArrayList<EditedMediaItemSequence>()
                        sequences.add(EditedMediaItemSequence(items))
                        sequences.add(EditedMediaItemSequence(EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(wav))).setRemoveVideo(true).build()))
                        val transformer = Transformer.Builder(ctx)
                            .setVideoMimeType(MimeTypes.VIDEO_H264)
                            .addListener(object : Transformer.Listener {
                                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                                    finished.set(true); temp.forEach { it.delete() }
                                    onDone(out, null)
                                }
                                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                                    finished.set(true); temp.forEach { it.delete() }; out.delete()
                                    onDone(null, L.t("לא הצלחנו ליצור את הסרטון. נסו גרסה אחרת.", "Couldn't create the video. Try another version."))
                                }
                            })
                            .build()
                        transformer.start(Composition.Builder(sequences).build(), out.absolutePath)
                        handle.cancel = { cancelled.set(true); finished.set(true); try { transformer.cancel() } catch (_: Throwable) {}; temp.forEach { it.delete() }; out.delete() }
                        val holder = ProgressHolder()
                        val poll = object : Runnable {
                            override fun run() {
                                if (cancelled.get() || finished.get()) return
                                val state = try { transformer.getProgress(holder) } catch (_: Throwable) { Transformer.PROGRESS_STATE_NOT_STARTED }
                                if (state == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress)
                                main.postDelayed(this, 500)
                            }
                        }
                        main.postDelayed(poll, 500)
                    } catch (t: Throwable) {
                        temp.forEach { it.delete() }
                        onDone(null, L.t("לא הצלחנו ליצור את הסרטון", "Couldn't create the video"))
                    }
                }
            } catch (e: Throwable) {
                temp.forEach { it.delete() }
                main.post { if (!cancelled.get()) onDone(null, e.message ?: L.t("לא הצלחנו ליצור את הסרטון", "Couldn't create the video")) }
            }
        }
        return handle
    }

    // ---------- the look ----------

    /** A gentle Ken Burns move over the shot (zoom in / out or a slow pan), eased, never past [amount]. */
    private fun kenBurns(kind: KenBurns, amount: Float, lenUs: Long): MatrixTransformation {
        var t0 = -1L
        return MatrixTransformation { pt ->
            if (t0 < 0) t0 = pt
            val p = ((pt - t0).toFloat() / lenUs).coerceIn(0f, 1f)
            val e = p * p * (3 - 2 * p) // ease in and out
            val m = android.graphics.Matrix()
            when (kind) {
                KenBurns.ZOOM_IN -> { val sc = 1f + amount * e; m.setScale(sc, sc) }
                KenBurns.ZOOM_OUT -> { val sc = 1f + amount * (1 - e); m.setScale(sc, sc) }
                KenBurns.PAN_LEFT -> { val sc = 1f + amount; m.setScale(sc, sc); m.postTranslate(amount * (0.5f - e) * 1.6f, 0f) }
                KenBurns.PAN_RIGHT -> { val sc = 1f + amount; m.setScale(sc, sc); m.postTranslate(-amount * (0.5f - e) * 1.6f, 0f) }
                KenBurns.NONE -> {}
            }
            m
        }
    }

    /** A soft, warm grade per style, with fades from / to black when the plan asks for them. */
    private fun grade(style: ReelStyle, fadeInUs: Long, fadeOutUs: Long, lenUs: Long): RgbMatrix {
        val (r, g, b) = when (style) {
            ReelStyle.EMOTIONAL -> Triple(1.03f, 1.0f, 0.95f)
            ReelStyle.HAPPY -> Triple(1.02f, 1.01f, 0.98f)
            ReelStyle.CINEMATIC -> Triple(1.04f, 0.99f, 0.92f)
        }
        var t0 = -1L
        return RgbMatrix { pt, _ ->
            if (t0 < 0) t0 = pt
            val t = pt - t0
            var f = 1f
            if (fadeInUs > 0) f = minOf(f, t.toFloat() / fadeInUs)
            if (fadeOutUs > 0) f = minOf(f, (lenUs - t).toFloat() / fadeOutUs)
            f = f.coerceIn(0f, 1f)
            floatArrayOf(r * f, 0f, 0f, 0f, 0f, g * f, 0f, 0f, 0f, 0f, b * f, 0f, 0f, 0f, 0f, 1f)
        }
    }

    /** A caption that fades in and out over its shot. */
    private class FadingOverlay(private val bmp: Bitmap, private val lenUs: Long) : BitmapOverlay() {
        private var t0 = -1L
        override fun getBitmap(presentationTimeUs: Long): Bitmap = bmp
        override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
            if (t0 < 0) t0 = presentationTimeUs
            val t = (presentationTimeUs - t0).toFloat()
            val a = minOf(1f, t / 450_000f, (lenUs - t) / 450_000f).coerceIn(0f, 1f)
            return OverlaySettings.Builder().setAlphaScale(a).build()
        }
    }

    /** Small text in the lower third, with a soft shadow (no box, no banner). */
    private fun caption(text: String, w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val side = minOf(w, h)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL); textSize = side * 0.052f
            setShadowLayer(side * 0.012f, 0f, side * 0.003f, Color.argb(150, 0, 0, 0))
        }
        c.drawText(text, w / 2f, h * 0.80f, p)
        return bmp
    }

    /** The closing card: a warm dark frame, one quiet line, the month, and (when the mark is on) a small logo. */
    private fun outro(ctx: Context, plan: ReelPlan, w: Int, h: Int, mark: Boolean): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, h.toFloat(), Color.rgb(28, 25, 23), Color.rgb(36, 46, 42), Shader.TileMode.CLAMP)
        })
        val side = minOf(w, h)
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL); textSize = side * 0.06f
        }
        val sub = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(170, 255, 255, 255); textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif", Typeface.NORMAL); textSize = side * 0.034f; letterSpacing = 0.04f
        }
        c.drawText(plan.outroTitle, w / 2f, h / 2f, title)
        if (plan.outroSub.isNotEmpty()) c.drawText(plan.outroSub, w / 2f, h / 2f + side * 0.075f, sub)
        if (mark) {
            val b = SocialShare.badge(ctx, (side * 0.16f).toInt())
            val p = Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = 170 }
            c.drawBitmap(b, (w - b.width) / 2f, h - b.height - side * 0.09f, p)
        }
        return bmp
    }

    /** A photo for the frame: filled edge to edge when its shape is close; otherwise whole, on a soft blurred copy. */
    private fun framed(src: Bitmap, w: Int, h: Int): Bitmap {
        val ra = src.width.toFloat() / src.height
        val rf = w.toFloat() / h
        if (ra / rf in 0.8f..1.25f) {
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val cover = maxOf(w.toFloat() / src.width, h.toFloat() / src.height)
            val bw = src.width * cover; val bh = src.height * cover
            // Keep the upper part (faces) when cropping a tall photo.
            val top = if (bh > h) -(bh - h) * 0.35f else (h - bh) / 2f
            Canvas(out).drawBitmap(src, null, android.graphics.RectF((w - bw) / 2f, top, (w + bw) / 2f, top + bh), Paint(Paint.FILTER_BITMAP_FLAG))
            return out
        }
        return fitted(src, w, h, OTHER_SHAPE_ZOOM)
    }

    /** How much bigger a picture of the other shape is shown than "whole" (its sides trimmed a little). */
    private const val OTHER_SHAPE_ZOOM = 1.55f

    /** The clip's real length if it can be opened with a picture in it, else null. */
    private fun readableDuration(ctx: Context, m: Memory): Long? {
        val r = android.media.MediaMetadataRetriever()
        return try {
            if (m.uri.startsWith("content://")) r.setDataSource(ctx, Uri.parse(m.uri)) else r.setDataSource(m.uri)
            if (r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) != "yes") null
            else r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.takeIf { it > 300 }
        } catch (_: Exception) { null } finally { try { r.release() } catch (_: Exception) { } }
    }

    /** [src] whole and centered on a [w]×[h] frame, over a blurred, darkened copy that fills it. */
    private fun fitted(src: Bitmap, w: Int, h: Int, zoom: Float = 1f): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        // Background: a tiny copy stretched up looks blurred.
        val tiny = Bitmap.createScaledBitmap(src, 24, (24f * src.height / src.width).toInt().coerceAtLeast(1), true)
        val cover = maxOf(w.toFloat() / tiny.width, h.toFloat() / tiny.height)
        val bw = tiny.width * cover; val bh = tiny.height * cover
        c.drawBitmap(tiny, null, android.graphics.RectF((w - bw) / 2f, (h - bh) / 2f, (w + bw) / 2f, (h + bh) / 2f), Paint(Paint.FILTER_BITMAP_FLAG))
        c.drawColor(Color.argb(90, 0, 0, 0))
        // Whole, or [zoom] times bigger (never more than filling the frame).
        val fit = minOf(minOf(w.toFloat() / src.width, h.toFloat() / src.height) * zoom, maxOf(w.toFloat() / src.width, h.toFloat() / src.height))
        val fw = src.width * fit; val fh = src.height * fit
        c.drawBitmap(src, null, android.graphics.RectF((w - fw) / 2f, (h - fh) / 2f, (w + fw) / 2f, (h + fh) / 2f), Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }
}
