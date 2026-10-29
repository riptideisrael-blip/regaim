package com.regaim.app

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Turns moments into a comic story (1080x1920, story format):
 * frames are drawn in a comic style on the phone, and Claude writes the captions and speech bubbles.
 */
object ComicMaker {

    class Panel(val frame: Bitmap, val caption: String, val bubble: String, val illustrated: Boolean = false)

    /**
     * Fits a picture into the panel, cropping around the faces in it (not just the middle), so heads aren't cut.
     * [warm]: a photo (the drawing couldn't be made) gets a soft warm, slightly faded look instead of a harsh filter.
     */
    private fun fit(src: Bitmap, w: Int, h: Int, warm: Boolean = false): Bitmap {
        val scale = max(w.toFloat() / src.width, h.toFloat() / src.height)
        val (fx, fy) = faceCenter(src) ?: (src.width / 2f to src.height * 0.42f) // no face found: a little above the middle
        // The visible window, centred on the faces as far as the picture allows.
        val dx = (w / 2f - fx * scale).coerceIn(w - src.width * scale, 0f)
        val dy = (h / 2f - fy * scale).coerceIn(h - src.height * scale, 0f)
        val m = Matrix().apply { postScale(scale, scale); postTranslate(dx, dy) }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        if (warm) paint.colorFilter = android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix().apply {
            setSaturation(0.85f)
            postConcat(android.graphics.ColorMatrix(floatArrayOf(
                1.04f, 0f, 0f, 0f, 10f,
                0f, 1.0f, 0f, 0f, 6f,
                0f, 0f, 0.92f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            )))
        })
        Canvas(out).drawBitmap(src, m, paint)
        return out
    }

    /** The middle of the faces in [b] (all of them together), or null. Background thread only. */
    private fun faceCenter(b: Bitmap): Pair<Float, Float>? {
        val detector = com.google.mlkit.vision.face.FaceDetection.getClient(
            com.google.mlkit.vision.face.FaceDetectorOptions.Builder()
                .setPerformanceMode(com.google.mlkit.vision.face.FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setMinFaceSize(0.08f).build()
        )
        return try {
            val faces = com.google.android.gms.tasks.Tasks.await(detector.process(com.google.mlkit.vision.common.InputImage.fromBitmap(b, 0)))
            if (faces.isEmpty()) null else {
                val l = faces.minOf { it.boundingBox.left }; val r = faces.maxOf { it.boundingBox.right }
                val t = faces.minOf { it.boundingBox.top }; val bt = faces.maxOf { it.boundingBox.bottom }
                // A little below the faces' middle, so some of the body shows too.
                (l + r) / 2f to (t + bt) / 2f + (bt - t) * 0.35f
            }
        } catch (_: Exception) { null } finally { detector.close() }
    }

    private const val W = 1080
    private const val H = 1920
    private val INK = Color.parseColor("#1F1D2B")
    private val PAPER = Color.parseColor("#FBF8F1")
    private val CAPTION_BG = Color.parseColor("#FFE9A8")
    private val ACCENT = Color.parseColor("#1A6FCC")

    // ---------- frames ----------

    /** Grabs [count] frames spread over one clip. */
    fun framesFromClip(ctx: Context, m: Memory, count: Int): List<Bitmap> {
        if (!m.isVideo) return MediaImages.decode(ctx, m.uri, 960)?.let { listOf(it) } ?: emptyList()
        val mmr = MediaMetadataRetriever()
        return try {
            if (m.uri.startsWith("content://")) mmr.setDataSource(ctx, Uri.parse(m.uri)) else mmr.setDataSource(m.uri)
            val dur = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: max(m.durationMs, 1L)
            val spots = when (count) {
                1 -> listOf(0.5)
                2 -> listOf(0.35, 0.65)
                3 -> listOf(0.3, 0.5, 0.7)
                else -> (0 until count).map { 0.15 + 0.7 * it / (count - 1) }
            }
            spots.mapNotNull { p -> mmr.getFrameAtTime((dur * p * 1000).toLong(), MediaMetadataRetriever.OPTION_CLOSEST_SYNC) }
        } catch (_: Exception) {
            emptyList()
        } finally {
            try { mmr.release() } catch (_: Exception) {}
        }
    }

    // ---------- comic style ----------

    /** Center-crops to the panel shape, then posterizes colors and draws ink outlines on the edges. */
    fun comicify(src: Bitmap, w: Int, h: Int): Bitmap {
        val scale = max(w.toFloat() / src.width, h.toFloat() / src.height)
        val m = Matrix().apply {
            postScale(scale, scale)
            postTranslate((w - src.width * scale) / 2f, (h - src.height * scale) / 2f)
        }
        val base = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(base).drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG))

        val px = IntArray(w * h)
        base.getPixels(px, 0, w, 0, 0, w, h)
        val gray = FloatArray(w * h)
        for (i in px.indices) {
            val c = px[i]
            gray[i] = 0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)
        }
        val out = IntArray(w * h)
        val levels = 5
        val step = 255f / (levels - 1)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val c = px[i]
                // Posterize + a bit more saturation, for the flat "printed" look.
                var r = Color.red(c).toFloat()
                var g = Color.green(c).toFloat()
                var b = Color.blue(c).toFloat()
                val l = gray[i]
                r = l + (r - l) * 1.35f
                g = l + (g - l) * 1.35f
                b = l + (b - l) * 1.35f
                var rr = (Math.round(r.coerceIn(0f, 255f) / step) * step).toInt()
                var gg = (Math.round(g.coerceIn(0f, 255f) / step) * step).toInt()
                var bb = (Math.round(b.coerceIn(0f, 255f) / step) * step).toInt()
                // Sobel edge strength -> ink line.
                if (x in 1 until w - 1 && y in 1 until h - 1) {
                    val gx = -gray[i - w - 1] - 2 * gray[i - 1] - gray[i + w - 1] + gray[i - w + 1] + 2 * gray[i + 1] + gray[i + w + 1]
                    val gy = -gray[i - w - 1] - 2 * gray[i - w] - gray[i - w + 1] + gray[i + w - 1] + 2 * gray[i + w] + gray[i + w + 1]
                    val mag = sqrt(gx * gx + gy * gy)
                    if (mag > 150f) {
                        val k = min(1f, (mag - 150f) / 160f)
                        rr = (rr * (1 - k) + 31 * k).toInt()
                        gg = (gg * (1 - k) + 29 * k).toInt()
                        bb = (bb * (1 - k) + 43 * k).toInt()
                    }
                }
                out[i] = Color.rgb(rr.coerceIn(0, 255), gg.coerceIn(0, 255), bb.coerceIn(0, 255))
            }
        }
        base.setPixels(out, 0, w, 0, 0, w, h)
        // Soft halftone dots over the darker areas, like old print.
        val c = Canvas(base)
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(28, 31, 29, 43) }
        var yy = 6
        while (yy < h) {
            var xx = if ((yy / 12) % 2 == 0) 6 else 12
            while (xx < w) {
                if (gray[yy * w + xx] < 110f) c.drawCircle(xx.toFloat(), yy.toFloat(), 2.2f, dot)
                xx += 12
            }
            yy += 12
        }
        return base
    }

    // ---------- page ----------

    fun render(title: String, dateLine: String, panels: List<Panel>): Bitmap {
        val page = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(page)
        c.drawColor(PAPER)

        // Header
        val margin = 48f
        val titlePaint = textPaint(64f, INK, bold = true)
        val datePaint = textPaint(32f, ACCENT, bold = true)
        drawRtl(c, "Moments · $dateLine", datePaint, margin, 60f, W - 2 * margin)
        val titleLayout = layout(title, titlePaint, (W - 2 * margin).toInt(), 2)
        c.save(); c.translate(margin, 110f); titleLayout.draw(c); c.restore()
        val top = 110f + titleLayout.height + 30f

        // Panels: the clips are tall (phone video), so two side by side in a row keeps people whole;
        // 3 panels = two on top and one wide below, 4 = a 2x2 grid, 1-2 = one per row.
        val n = panels.size.coerceIn(1, 4)
        val gutter = 26f
        val rows = if (n <= 2) n else 2
        val rowH = (H - top - margin - gutter * (rows - 1)) / rows
        val fullW = W - 2 * margin
        val halfW = (fullW - gutter) / 2f
        fun rectOf(i: Int): RectF {
            val row: Int; val col: Int; val span: Boolean
            when (n) {
                1, 2 -> { row = i; col = 0; span = true }
                3 -> { row = if (i < 2) 0 else 1; col = if (i < 2) i else 0; span = i == 2 }
                else -> { row = i / 2; col = i % 2; span = false }
            }
            val y = top + row * (rowH + gutter)
            // Right to left: the first panel of a row is on the right.
            val x = if (span) margin else margin + (1 - col) * (halfW + gutter)
            return RectF(x, y, x + if (span) fullW else halfW, y + rowH)
        }
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 9f
            color = INK
        }
        for (i in 0 until n) {
            val p = panels[i]
            val rect = rectOf(i)
            // A drawing as it is; a photo (no drawing could be made) softly warmed, never the harsh poster filter.
            val art = fit(p.frame, rect.width().toInt(), rect.height().toInt(), warm = !p.illustrated)
            c.drawBitmap(art, rect.left, rect.top, null)
            c.drawRect(rect, border)
            if (p.caption.isNotBlank()) drawCaption(c, p.caption, rect)
            if (p.bubble.isNotBlank()) drawBubble(c, p.bubble, rect, i % 2 == 0)
        }
        return page
    }

    private fun drawCaption(c: Canvas, text: String, panel: RectF) {
        val narrow = panel.width() < 600f
        val paint = textPaint(if (narrow) 30f else 34f, INK, bold = true)
        val maxW = (panel.width() * if (narrow) 0.86f else 0.62f).toInt()
        val l = layout(text, paint, maxW, 3)
        val pad = 18f
        val boxW = l.width.toFloat().coerceAtMost(maxW.toFloat()) + pad * 2
        val box = RectF(panel.right - boxW - 4.5f, panel.top + 4.5f, panel.right - 4.5f, panel.top + 4.5f + l.height + pad * 2)
        c.drawRect(box, Paint().apply { color = CAPTION_BG })
        c.drawRect(box, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 5f; color = INK })
        c.save(); c.translate(box.left + pad, box.top + pad); l.draw(c); c.restore()
    }

    private fun drawBubble(c: Canvas, text: String, panel: RectF, left: Boolean) {
        val narrow = panel.width() < 600f
        val paint = textPaint(if (narrow) 32f else 38f, INK, bold = true)
        val maxW = (panel.width() * if (narrow) 0.72f else 0.48f).toInt()
        val l = layout(text, paint, maxW, 3)
        val padX = 34f
        val padY = 26f
        val bw = l.width + padX * 2
        val bh = l.height + padY * 2
        val bx = if (left) panel.left + 30f else panel.right - 30f - bw
        val by = panel.bottom - bh - 60f
        val oval = RectF(bx, by, bx + bw, by + bh)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 5f; color = INK }
        // Tail pointing down into the scene.
        val tailX = if (left) oval.left + bw * 0.3f else oval.right - bw * 0.3f
        val tail = Path().apply {
            moveTo(tailX - 22f, oval.bottom - 12f)
            lineTo(tailX + (if (left) 10f else -10f), oval.bottom + 46f)
            lineTo(tailX + 22f, oval.bottom - 12f)
            close()
        }
        c.drawPath(tail, fill)
        c.drawPath(tail, stroke)
        c.drawOval(oval, fill)
        c.drawOval(oval, stroke)
        // Cover the tail's join line.
        c.drawRect(tailX - 18f, oval.bottom - 14f, tailX + 18f, oval.bottom - 2f, fill)
        c.save(); c.translate(oval.left + padX, oval.top + padY); l.draw(c); c.restore()
    }

    private fun textPaint(size: Float, color: Int, bold: Boolean) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        this.color = color
        typeface = Typeface.create(Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun layout(text: String, paint: TextPaint, width: Int, maxLines: Int): StaticLayout {
        val w = max(1, min(width, paint.measureText(text).toInt() + 2))
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, w)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setTextDirection(if (L.isEn) TextDirectionHeuristics.FIRSTSTRONG_LTR else TextDirectionHeuristics.FIRSTSTRONG_RTL)
            .setMaxLines(maxLines)
            .setEllipsize(android.text.TextUtils.TruncateAt.END)
            .build()
    }

    private fun drawRtl(c: Canvas, text: String, paint: TextPaint, x: Float, y: Float, width: Float) {
        val l = StaticLayout.Builder.obtain(text, 0, text.length, paint, width.toInt())
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setTextDirection(if (L.isEn) TextDirectionHeuristics.LTR else TextDirectionHeuristics.RTL)
            .setMaxLines(1)
            .build()
        c.save(); c.translate(x, y - paint.textSize); l.draw(c); c.restore()
    }

    // ---------- whole flow ----------

    /** style / tone = indexes in ComicStyles; page 0 is the first page, 1+ continue the story. */
    class Options(val style: Int = 0, val tone: Int = 0, val page: Int = 0)

    class Made(val file: File, val title: String, val page: Int)

    private class Shot(val m: Memory, val bmp: Bitmap, val atMs: Long)

    /** Frames at given points (0..1) of one clip, with their time in ms. */
    private fun framesAt(ctx: Context, m: Memory, spots: List<Double>): List<Pair<Long, Bitmap>> {
        if (!m.isVideo) return MediaImages.decode(ctx, m.uri, 960)?.let { listOf(0L to it) } ?: emptyList()
        val mmr = MediaMetadataRetriever()
        return try {
            if (m.uri.startsWith("content://")) mmr.setDataSource(ctx, Uri.parse(m.uri)) else mmr.setDataSource(m.uri)
            val dur = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: max(m.durationMs, 1L)
            spots.mapNotNull { p ->
                val at = (dur * p).toLong()
                mmr.getFrameAtTime(at * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { at to it }
            }
        } catch (_: Exception) {
            emptyList()
        } finally {
            try { mmr.release() } catch (_: Exception) {}
        }
    }

    /** Which frames go on this page: a new page uses other moments of the day, or other moments of the clip. */
    private fun pickShots(ctx: Context, moments: List<Memory>, page: Int): List<Shot> {
        val chosen = moments.sortedBy { it.time }
        if (chosen.size == 1) {
            val m = chosen[0]
            val spots = when (page) {
                0 -> listOf(0.3, 0.5, 0.7)
                1 -> listOf(0.12, 0.42, 0.88)
                2 -> listOf(0.2, 0.6, 0.94)
                else -> (0..2).map { 0.08 + ((0.27 * it + 0.13 * page) % 0.86) }.sorted()
            }
            return framesAt(ctx, m, spots).map { Shot(m, it.second, it.first) }
        }
        // Favorites and milestones first; 4 moments per page.
        val ranked = chosen.sortedByDescending { (if (it.milestone.isNotEmpty()) 2 else 0) + (if (it.favorite || it.chosen) 1 else 0) }
        val pick = if (ranked.size > 4) {
            val start = (page * 4) % ranked.size
            (ranked.drop(start) + ranked).take(4)
        } else ranked
        val spot = if (page == 0) 0.5 else listOf(0.3, 0.7, 0.2, 0.8, 0.4, 0.6)[(page - 1) % 6]
        return pick.sortedBy { it.time }.mapNotNull { m ->
            framesAt(ctx, m, listOf(spot)).firstOrNull()?.let { Shot(m, it.second, it.first) }
        }
    }

    /** AI illustrations are slow and cost money: keep each one, so a new text version reuses them. */
    private fun cachedArt(ctx: Context, shot: Shot, style: Int): Bitmap? {
        val f = File(File(ctx.cacheDir, "comic_art").apply { mkdirs() }, "${shot.m.id}_${shot.atMs / 500}_$style.png")
        if (f.exists()) android.graphics.BitmapFactory.decodeFile(f.absolutePath)?.let { return it }
        val scene = shot.m.description.ifEmpty { shot.m.displayTitle }
        val art = try {
            GeminiClient.illustrate(ctx, shot.bmp, scene, ComicStyles.PROMPTS.getOrElse(style) { ComicStyles.PROMPTS[0] })
        } catch (_: Exception) {
            null
        } ?: return null
        try { FileOutputStream(f).use { art.compress(Bitmap.CompressFormat.PNG, 100, it) } } catch (_: Exception) {}
        return art
    }

    /** Builds one or more versions of a comic page. Background thread only. Not saved to the gallery yet. */
    fun make(
        ctx: Context,
        moments: List<Memory>,
        opt: Options = Options(),
        versions: Int = 1,
        previous: List<String> = emptyList(),
        onProgress: ((String) -> Unit)? = null,
    ): List<Made> {
        val shots = pickShots(ctx, moments, opt.page)
        if (shots.isEmpty()) throw IllegalStateException(L.t("לא הצלחתי לקרוא תמונות מהקליפים", "Couldn't read images from the clips"))

        onProgress?.invoke(if (versions > 1) L.t("כותב $versions סיפורים שונים...", "Writing $versions different stories...") else L.t("כותב את הסיפור...", "Writing the story..."))
        val scripts = try {
            if (ClaudeClient.hasKey(ctx)) ClaudeClient.comicScripts(ctx, shots.map { it.m }, shots.size, opt.tone, opt.page, previous, versions)
            else emptyList()
        } catch (_: Exception) {
            emptyList()
        }

        val real = opt.style == ComicStyles.REAL
        val useAi = !real && GeminiClient.hasKey(ctx)
        val arts = shots.mapIndexed { i, shot ->
            if (useAi) {
                onProgress?.invoke(L.t("מצייר פאנל ${i + 1} מתוך ${shots.size}...\n(${ComicStyles.NAMES.getOrElse(opt.style) { "" }})", "Drawing panel ${i + 1} of ${shots.size}...\n(${ComicStyles.NAMES.getOrElse(opt.style) { "" }})"))
                (cachedArt(ctx, shot, opt.style) ?: cachedArt(ctx, shot, opt.style))?.let { it to true } ?: (shot.bmp to false)
            } else (shot.bmp to real)
        }

        val chosen = moments.sortedBy { it.time }
        val date = SimpleDateFormat("d.M.yyyy", Locale.US).format(Date(chosen.first().time)) +
            if (opt.page > 0) L.t(" · עמוד ${opt.page + 1}", " · Page ${opt.page + 1}") else ""
        val fallbackTitle = if (chosen.size == 1) chosen[0].displayTitle else L.t("הסיפור של היום", "Today's story")
        val dir = File(ctx.filesDir, "comics").apply { mkdirs() }
        val count = if (scripts.isEmpty()) 1 else min(versions.coerceAtLeast(1), scripts.size)
        return (0 until count).map { v ->
            val script = scripts.getOrNull(v)
            val title = script?.title ?: fallbackTitle
            val panels = shots.mapIndexed { i, shot ->
                val text = script?.panels?.getOrNull(i)
                Panel(arts[i].first, text?.first?.ifBlank { null } ?: shot.m.displayTitle, text?.second ?: "", illustrated = arts[i].second)
            }
            val page = render(title, date, panels)
            val file = File(dir, "comic_${System.currentTimeMillis()}_$v.png")
            FileOutputStream(file).use { page.compress(Bitmap.CompressFormat.PNG, 100, it) }
            Made(file, title, opt.page)
        }
    }

    fun saveToGalleryPublic(ctx: Context, file: File) = saveToGallery(ctx, file)

    private fun saveToGallery(ctx: Context, file: File) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Moments")
                }
                val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return
                ctx.contentResolver.openOutputStream(uri)?.use { o -> file.inputStream().use { it.copyTo(o) } }
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Moments")
                dir.mkdirs()
                val dest = File(dir, file.name)
                file.copyTo(dest, overwrite = true)
                MediaScannerConnection.scanFile(ctx, arrayOf(dest.absolutePath), arrayOf("image/png"), null)
            }
        } catch (_: Exception) {
        }
    }

    @Suppress("unused")
    private fun near(a: Float, b: Float) = abs(a - b) < 0.001f
}

/** Art styles and story tones for comics. */
object ComicStyles {
    val NAMES: List<String> get() = if (L.isEn) listOf("Color comic", "Picture book", "Manga", "Animated movie", "Pencil sketch", "Real photos")
        else listOf("קומיקס צבעוני", "ספר ילדים", "מנגה", "סרט אנימציה", "סקיצה בעיפרון", "תמונות אמיתיות")
    val PROMPTS = listOf(
        "a warm, colorful hand-drawn comic book panel: clean ink outlines, flat soft colors, gentle shading, friendly cartoon faces",
        "a gentle children's picture-book illustration in soft watercolor and colored pencil, warm and cozy",
        "a black-and-white manga panel with expressive eyes, screentone shading and dynamic lines",
        "a bright still from a 3D animated family movie, soft lighting, expressive friendly characters",
        "a loose pencil sketch with light watercolor washes, like a page from an artist's sketchbook",
        "",
    )
    const val REAL = 5

    val TONES: List<String> get() = if (L.isEn) listOf("Funny", "Heartfelt", "Adventure", "Rhyming", "Through the child's eyes")
        else listOf("מצחיק", "מרגש", "הרפתקה", "שיר מחורז", "בעיני הילד")
    val TONE_PROMPTS = listOf(
        "חם ומצחיק",
        "מרגש ונוגע ללב, בלי פאתוס ובלי קיטש",
        "כמו סיפור הרפתקאות, הילדים הם הגיבורים",
        "כל caption הוא שורה מחורזת כמו בספר ילדים, והחרוזים ממשיכים מפאנל לפאנל",
        "מסופר בגוף ראשון מנקודת המבט של הילד, בשפה של ילד",
    )
}
