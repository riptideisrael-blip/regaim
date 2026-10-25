package com.regaim.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
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
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.common.collect.ImmutableList
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

/**
 * Sharing out of the app, the way big apps do it: a sheet with the apps you actually have
 * (WhatsApp, Instagram, TikTok, Facebook, more…), and a small "Moments" watermark burned into the photo or video
 * so people who see it know where it came from. The watermark can be switched off per share.
 */
@OptIn(markerClass = [UnstableApi::class])
object SocialShare {
    private const val MAX_SHARE_MS = 60_000L
    private val worker = Executors.newSingleThreadExecutor()

    private class Target(val label: String, val pkg: String?)

    private val KNOWN = listOf(
        Target("WhatsApp", "com.whatsapp"),
        Target("Instagram", "com.instagram.android"),
        Target("TikTok", "com.zhiliaoapp.musically"),
        Target("TikTok", "com.ss.android.ugc.trill"),
        Target("Facebook", "com.facebook.katana"),
    )

    private fun installed(ctx: Context, pkg: String) = try {
        @Suppress("DEPRECATION")
        ctx.packageManager.getPackageInfo(pkg, 0); true
    } catch (_: Exception) { false }

    private fun prefs(ctx: Context) = Settings.prefs(ctx)

    fun show(a: AppCompatActivity, m: Memory) {
        val ui = Ui(a)
        val dialog = BottomSheetDialog(a)
        val root = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(20), ui.dp(18), ui.dp(20), ui.dp(24))
        }
        root.addView(ui.text(L.t("שיתוף", "Share"), 20f, bold = true))
        root.addView(ui.hint(L.t("לאן לשלוח את \"${Fmt.title(m)}\"?", "Where should \"${Fmt.title(m)}\" go?"), top = 2))

        val mark = SwitchCompat(a).apply {
            text = L.t("סימן מים של Moments", "Moments watermark")
            textSize = 15f
            isChecked = prefs(a).getBoolean("share_watermark", true)
            setTextColor(ContextCompat.getColor(a, R.color.ink))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.dp(14) }
            setOnCheckedChangeListener { _, on -> prefs(a).edit().putBoolean("share_watermark", on).apply() }
        }
        root.addView(mark)
        root.addView(ui.hint(L.t("לוגו קטן בפינה, כדי שמי שרואה יידע מאיפה זה הגיע.", "A small logo in the corner, so people know where it came from."), top = 0))

        var music: Music.Mood? = Music.choice(a)
        if (m.isVideo) {
            val musicRow = ui.action("") { }
            fun label() { musicRow.text = L.t("🎵 מוזיקה: ${music?.title ?: "ללא"} ›", "🎵 Music: ${music?.title ?: "none"} ›") }
            label()
            musicRow.setOnClickListener {
                Music.chooser(a, music) { picked -> music = picked; Music.setChoice(a, picked); label() }
            }
            root.addView(musicRow)
            root.addView(ui.hint(L.t("המוזיקה נשמעת בשקט מתחת לקול המקורי.", "The music plays softly under the original sound."), top = 0))
        }
        val status = ui.hint("", top = 10)
        val row = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.dp(18) }
        }
        var busy = false
        fun go(pkg: String?) {
            if (busy) return
            busy = true
            status.text = if (m.isVideo) L.t("מכינים את הסרטון...", "Preparing video...") else L.t("מכינים את התמונה...", "Preparing photo...")
            prepare(a, m, mark.isChecked, music) { file, err ->
                busy = false
                if (a.isFinishing || a.isDestroyed) return@prepare
                if (file == null) { status.text = err ?: L.t("משהו השתבש", "Something went wrong"); return@prepare }
                dialog.dismiss()
                send(a, m, file, pkg)
            }
        }
        val targets = KNOWN.filter { it.pkg != null && installed(a, it.pkg) }.distinctBy { it.label }
        targets.forEach { t ->
            row.addView(target(a, ui, t.label, try { a.packageManager.getApplicationIcon(t.pkg!!) } catch (_: Exception) { null }) { go(t.pkg) })
        }
        row.addView(target(a, ui, L.t("עוד", "More"), ContextCompat.getDrawable(a, R.drawable.ic_share_outline)) { go(null) })
        root.addView(android.widget.HorizontalScrollView(a).apply { isHorizontalScrollBarEnabled = false; addView(row) })
        root.addView(status)
        dialog.setContentView(root)
        dialog.show()
    }

    private fun target(a: AppCompatActivity, ui: Ui, label: String, icon: android.graphics.drawable.Drawable?, onClick: () -> Unit) =
        LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(ui.dp(8), 0, ui.dp(8), 0)
            addView(ImageView(a).apply {
                setImageDrawable(icon)
                if (label == L.t("עוד", "More")) { setColorFilter(ContextCompat.getColor(a, R.color.ink)); setPadding(ui.dp(14), ui.dp(14), ui.dp(14), ui.dp(14)); setBackgroundResource(R.drawable.chat_chip_bg) }
                layoutParams = LinearLayout.LayoutParams(ui.dp(56), ui.dp(56))
            })
            addView(TextView(a).apply {
                text = label; textSize = 12f; gravity = Gravity.CENTER
                setTextColor(ContextCompat.getColor(a, R.color.ink))
                setPadding(0, ui.dp(6), 0, 0)
            })
            setOnClickListener { onClick() }
        }

    private fun shareDir(ctx: Context) = File(ctx.cacheDir, "share").apply { mkdirs(); listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 3_600_000L }?.forEach { it.delete() } }

    private fun source(m: Memory): Uri = if (m.uri.startsWith("content://")) Uri.parse(m.uri) else Uri.fromFile(File(m.uri))

    private fun send(a: AppCompatActivity, m: Memory, file: File, pkg: String?) {
        try {
            val uri = FileProvider.getUriForFile(a, "${a.packageName}.files", file)
            val send = Intent(Intent.ACTION_SEND)
                .setType(if (m.isVideo) "video/mp4" else "image/jpeg")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_TEXT, Fmt.title(m) + L.t("\n\nנשמר עם Moments 💜", "\n\nSaved with Moments 💜"))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (pkg != null) a.startActivity(send.setPackage(pkg))
            else a.startActivity(Intent.createChooser(send, L.t("לשתף עם...", "Share with...")).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (_: Exception) {
            Toast.makeText(a, L.t("לא הצלחנו לפתוח את האפליקציה לשיתוף", "Couldn't open the app to share"), Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- preparing the file ----------

    /** Calls [done] on the main thread with the file to share, or an error message. */
    private fun prepare(a: AppCompatActivity, m: Memory, watermark: Boolean, music: Music.Mood?, done: (File?, String?) -> Unit) {
        val ctx = a.applicationContext
        if (!m.isVideo) {
            worker.execute {
                val r = try { photo(ctx, m, watermark) } catch (_: Exception) { null }
                a.runOnUiThread { done(r, if (r == null) L.t("לא הצלחנו להכין את התמונה", "Couldn't prepare the photo") else null) }
            }
            return
        }
        val out = File(shareDir(ctx), "moments_${System.currentTimeMillis()}.mp4")
        val (startMs, endMs) = range(m)
        if (!watermark && music == null) {
            worker.execute {
                val r = try { Circle.cutRange(ctx, m, startMs, endMs).let { f -> f.copyTo(out, true); f.delete(); out } } catch (_: Exception) { null }
                a.runOnUiThread { done(r, if (r == null) L.t("לא הצלחנו להכין את הסרטון", "Couldn't prepare the video") else null) }
            }
            return
        }
        worker.execute {
            // Always fix the output size (long side ≤ 1920): a 4K clip re-encoded at full size is slow or fails.
            val size = outputSize(ctx, source(m))
            val len = if (endMs > startMs) endMs - startMs else m.durationMs - startMs
            val wav = if (music != null) try {
                File(ctx.cacheDir, "music_${System.currentTimeMillis()}.wav").also { Music.renderWav(music, len, 0.35f, it) }
            } catch (_: Exception) { null } else null
            // The closing card (like TikTok's): only with the mark on, so a family can turn both off together.
            val card = if (watermark) try { endCard(ctx, size.first, size.second) } catch (_: Exception) { null } else null
            a.runOnUiThread { exportVideo(ctx, m, startMs, endMs, size, watermark, wav, card, out, done) }
        }
    }

    /** Whole moment if it is short; otherwise one minute around its heart. */
    private fun range(m: Memory): Pair<Long, Long> {
        if (m.durationMs <= MAX_SHARE_MS || m.durationMs <= 0) return 0L to m.durationMs.coerceAtLeast(0)
        val w = m.previewWindow
        val mid = if (w != null) (w.startMs + w.endMs) / 2 else m.durationMs / 2
        val start = (mid - MAX_SHARE_MS / 2).coerceIn(0, m.durationMs - MAX_SHARE_MS)
        return start to start + MAX_SHARE_MS
    }

    /**
     * Burns the Moments mark into an already-cut clip (used for the public community).
     * Call from a background thread; returns the marked file, or null if it couldn't be done.
     */
    fun stampClip(ctx: Context, clip: File): File? {
        val out = File(ctx.cacheDir, "stamped_${System.currentTimeMillis()}.mp4")
        val latch = java.util.concurrent.CountDownLatch(1)
        var ok = false
        val (w, h) = outputSize(ctx, Uri.fromFile(clip))
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try {
                val edited = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(clip)))
                    .setEffects(Effects(emptyList<AudioProcessor>(), markEffects(ctx, w, h)))
                    .build()
                Transformer.Builder(ctx)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) { ok = true; latch.countDown() }
                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) { latch.countDown() }
                    })
                    .build()
                    .start(edited, out.absolutePath)
            } catch (_: Throwable) { latch.countDown() }
        }
        if (!latch.await(180, java.util.concurrent.TimeUnit.SECONDS)) ok = false
        if (!ok) out.delete()
        return if (ok && out.length() > 0) out else null
    }


    // ---------- the Moments mark on videos ----------

    /**
     * A transparent picture the exact size of the video frame, with the Moments mark in its bottom corner.
     * Laid over the frame 1:1, so it can't drift off the edge (corner anchors did, and cut the mark in half).
     */
    fun markLayer(ctx: Context, w: Int, h: Int): Bitmap {
        val layer = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val side = minOf(w, h)
        val b = badge(ctx, (side * 0.26f).toInt().coerceAtLeast(130))
        val margin = side * 0.04f
        // A tall video: higher up, out of the player's buttons at the bottom (and near the picture, like TikTok's mark).
        val bottom = if (h > w) h * 0.80f else h - margin * 1.4f
        Canvas(layer).drawBitmap(b, w - b.width - margin, bottom - b.height, Paint(Paint.FILTER_BITMAP_FLAG))
        return layer
    }

    /** Fixes the frame to [w]×[h] (even numbers), then lays the mark over it. */
    fun markEffects(ctx: Context, w: Int, h: Int): List<Effect> {
        val ew = (w / 2) * 2
        val eh = (h / 2) * 2
        return listOf(
            Presentation.createForWidthAndHeight(ew, eh, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP),
            OverlayEffect(ImmutableList.of(BitmapOverlay.createStaticBitmapOverlay(markLayer(ctx, ew, eh), OverlaySettings.Builder().build()))),
        )
    }

    /** The video's shown size, with its long side at most 1920 (plenty for WhatsApp, and light on memory). */
    internal fun outputSize(ctx: Context, uri: Uri): Pair<Int, Int> {
        val (w, h) = displaySize(ctx, uri)
        val scale = minOf(1f, 1920f / maxOf(w, h).coerceAtLeast(1))
        return ((w * scale).toInt().coerceAtLeast(2)) to ((h * scale).toInt().coerceAtLeast(2))
    }

    // ---------- the closing card ----------

    const val END_CARD_MS = 1500L

    /** A 1.5-second closing card, TikTok-style: dark, the Moments mark, the name and one line. Background thread. */
    fun endCard(ctx: Context, w: Int, h: Int): File {
        val ew = (w / 2) * 2; val eh = (h / 2) * 2
        val bmp = Bitmap.createBitmap(ew, eh, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.rgb(18, 18, 18))
        val side = minOf(ew, eh)
        val logo = (side * 0.20f).toInt()
        val cy = eh / 2f
        ContextCompat.getDrawable(ctx, R.drawable.ic_logo_mark)?.mutate()?.let { d ->
            d.colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
            val left = (ew - logo) / 2
            val top = (cy - logo * 1.1f).toInt()
            d.setBounds(left, top, left + logo, top + logo)
            d.draw(c)
        }
        val name = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textAlign = Paint.Align.CENTER; textSize = side * 0.085f; typeface = Fonts.display
        }
        c.drawText("Moments", ew / 2f, cy + side * 0.10f, name)
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(190, 255, 255, 255); textAlign = Paint.Align.CENTER; textSize = side * 0.042f
        }
        c.drawText(L.t("הרגעים שלא רוצים לפספס", "The moments you don't want to miss"), ew / 2f, cy + side * 0.17f, line)
        val f = File(shareDir(ctx), "endcard_${System.currentTimeMillis()}.jpg")
        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        return f
    }

    /** The card as the last piece of a video sequence, [size] = the video's output size. */
    fun endCardItem(card: File, size: Pair<Int, Int>): EditedMediaItem =
        EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(card)))
            .setDurationUs(END_CARD_MS * 1000L).setFrameRate(30)
            .setEffects(Effects(emptyList<AudioProcessor>(), listOf<Effect>(Presentation.createForWidthAndHeight((size.first / 2) * 2, (size.second / 2) * 2, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP))))
            .build()

    /** The cover picture with the Moments mark in its corner. */
    fun stampBitmap(ctx: Context, src: Bitmap): Bitmap {
        val bmp = src.copy(Bitmap.Config.ARGB_8888, true)
        val b = badge(ctx, (minOf(bmp.width, bmp.height) * 0.30f).toInt().coerceAtLeast(120))
        val margin = minOf(bmp.width, bmp.height) * 0.04f
        Canvas(bmp).drawBitmap(b, bmp.width - b.width - margin, bmp.height - b.height - margin, Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = 235 })
        return bmp
    }

    internal fun displaySize(ctx: Context, m: Memory): Pair<Int, Int> = displaySize(ctx, source(m))

    private fun displaySize(ctx: Context, uri: Uri): Pair<Int, Int> {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(ctx, uri)
            val w = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1080
            val h = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 1920
            val rot = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rot == 90 || rot == 270) h to w else w to h
        } catch (_: Exception) { 1080 to 1920 } finally { try { mmr.release() } catch (_: Exception) { } }
    }

    private fun exportVideo(ctx: Context, m: Memory, startMs: Long, endMs: Long, size: Pair<Int, Int>, mark: Boolean, music: File?, card: File?, out: File, done: (File?, String?) -> Unit) {
        try {
            val video = ArrayList<Effect>()
            if (mark) video.addAll(markEffects(ctx, size.first, size.second))
            else video.add(Presentation.createForWidthAndHeight((size.first / 2) * 2, (size.second / 2) * 2, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP))
            val clip = MediaItem.ClippingConfiguration.Builder().setStartPositionMs(startMs)
            if (endMs > startMs) clip.setEndPositionMs(endMs)
            val item = MediaItem.Builder().setUri(source(m)).setClippingConfiguration(clip.build()).build()
            val edited = EditedMediaItem.Builder(item)
                .setEffects(Effects(emptyList<AudioProcessor>(), video))
                .build()
            val sequences = ArrayList<EditedMediaItemSequence>()
            val main = arrayListOf(edited)
            card?.let { main.add(endCardItem(it, size)) }
            sequences.add(EditedMediaItemSequence(main))
            if (music != null) {
                val track = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(music))).setRemoveVideo(true).build()
                sequences.add(EditedMediaItemSequence(track))
            }
            val transformer = Transformer.Builder(ctx)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) { music?.delete(); card?.delete(); done(out, null) }
                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        out.delete()
                        // The closing card is the newest part: if it's what broke the export, share without it.
                        if (card != null) { card.delete(); exportVideo(ctx, m, startMs, endMs, size, mark, music, null, out, done); return }
                        music?.delete()
                        done(null, L.t("לא הצלחנו להכין את הסרטון. אפשר לכבות את סימן המים או המוזיקה ולנסות שוב.", "Couldn't prepare the video. Turn off the watermark or music and try again."))
                    }
                })
                .build()
            // The card has no sound of its own: silence is added under it, so the clip's sound plays to the end.
            transformer.start(Composition.Builder(sequences).experimentalSetForceAudioTrack(card != null).build(), out.absolutePath)
        } catch (_: Throwable) {
            done(null, L.t("לא הצלחנו להכין את הסרטון. אפשר לכבות את סימן המים או המוזיקה ולנסות שוב.", "Couldn't prepare the video. Turn off the watermark or music and try again."))
        }
    }

    private fun photo(ctx: Context, m: Memory, watermark: Boolean): File {
        val src = MediaImages.decode(ctx, m.uri, 2048) ?: throw IllegalStateException("no image")
        val bmp = src.copy(Bitmap.Config.ARGB_8888, true)
        if (watermark) {
            val b = badge(ctx, (minOf(bmp.width, bmp.height) * 0.30f).toInt())
            val margin = minOf(bmp.width, bmp.height) * 0.04f
            Canvas(bmp).drawBitmap(b, bmp.width - b.width - margin, bmp.height - b.height - margin, Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = 235 })
        }
        val out = File(shareDir(ctx), "moments_${System.currentTimeMillis()}.jpg")
        FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        return out
    }

    /** A soft dark pill with the Moments mark and name, [width] pixels wide. */
    fun badge(ctx: Context, width: Int): Bitmap {
        val h = (width * 0.30f).toInt().coerceAtLeast(36)
        val bmp = Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 20, 18, 34) }
        c.drawRoundRect(RectF(0f, 0f, width.toFloat(), h.toFloat()), h / 2f, h / 2f, bg)
        val pad = h * 0.22f
        val logoSize = (h - pad * 2).toInt()
        ContextCompat.getDrawable(ctx, R.drawable.ic_logo_mark)?.mutate()?.let { d ->
            d.colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
            d.setBounds(pad.toInt(), pad.toInt(), pad.toInt() + logoSize, pad.toInt() + logoSize)
            d.draw(c)
        }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            textSize = h * 0.46f
            setShadowLayer(h * 0.05f, 0f, h * 0.02f, Color.argb(90, 0, 0, 0))
        }
        val x = pad * 1.6f + logoSize
        val avail = width - x - pad * 1.4f
        while (text.measureText("Moments app") > avail && text.textSize > 8f) text.textSize = text.textSize * 0.92f
        val y = h / 2f - (text.descent() + text.ascent()) / 2f
        c.drawText("Moments app", x, y, text)
        return bmp
    }
}
