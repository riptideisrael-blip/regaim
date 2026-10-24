package com.regaim.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.max

/**
 * Ordering a printed book through Lulu (print on demand, shipped to the family): the book is laid out as a
 * hardcover picture book (US Letter, words facing each picture), turned into Lulu's two print files (the inside pages
 * and the cover), uploaded to our private "print" storage, and sent to Lulu by our server. All the drawing happens
 * here, page by page, at print resolution (300 per inch). Background thread only, except [orders].
 */
object PrintBook {
    private val paper = Color.parseColor("#F8F2E7")
    private val ink = Color.parseColor("#2A2420")
    private val soft = Color.parseColor("#8A7F74")
    private val brand = Color.parseColor("#87664F")

    private fun edge(ctx: Context) = Circle.url(ctx) + "/functions/v1/cartoon-film"

    private fun call(ctx: Context, body: Map<String, Any>): JSONObject {
        val token = Account.token(ctx) ?: throw IllegalStateException(L.t("צריך להתחבר לחשבון", "Please sign in"))
        val r = CartoonFilm.callEdge(edge(ctx), token, body)
        if (!r.optBoolean("ok", false)) throw IllegalStateException(r.optString("error").ifBlank { L.t("השרת לא ענה. נסו שוב.", "The server didn't answer. Try again.") })
        return r
    }

    fun plan(b: Book) = PrintSpec.plan(b.pages.size, b.dedication.isNotBlank())
    fun pageCount(b: Book) = plan(b).size

    /** Lulu's price for this book shipped to [address]: total, book, shipping, currency. */
    fun quote(ctx: Context, b: Book, address: JSONObject, shipping: String): JSONObject =
        call(ctx, mapOf("action" to "print_quote", "pages" to pageCount(b), "address" to address, "shipping" to shipping))

    // ---------- orders kept on the phone ----------

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("print_orders", Context.MODE_PRIVATE)
    fun order(ctx: Context, bookId: String): JSONObject? = prefs(ctx).getString(bookId, null)?.let { try { JSONObject(it) } catch (_: Exception) { null } }
    private fun saveOrder(ctx: Context, bookId: String, o: JSONObject) = prefs(ctx).edit().putString(bookId, o.toString()).apply()
    fun lastAddress(ctx: Context): JSONObject = prefs(ctx).getString("_address", null)?.let { try { JSONObject(it) } catch (_: Exception) { null } } ?: JSONObject()
    fun rememberAddress(ctx: Context, a: JSONObject) = prefs(ctx).edit().putString("_address", a.toString()).apply()

    /** The order's state at Lulu (created, in production, shipped...) and tracking links. */
    fun status(ctx: Context, bookId: String): JSONObject? {
        val o = order(ctx, bookId) ?: return null
        val r = call(ctx, mapOf("action" to "print_status", "id" to o.optString("id")))
        o.put("status", r.optString("status")).put("message", r.optString("message")).put("tracking", r.opt("tracking") ?: "")
        saveOrder(ctx, bookId, o)
        return o
    }

    /**
     * Makes the print files, uploads them and orders one copy. [progress]: a line for the screen.
     * Returns the saved order (Lulu's id and status).
     */
    fun place(ctx: Context, bookId: String, address: JSONObject, email: String, shipping: String, progress: (String) -> Unit): JSONObject {
        val uid = Account.id(ctx).ifEmpty { throw IllegalStateException(L.t("צריך להתחבר לחשבון", "Please sign in")) }
        // Sharp pictures for paper: every page enlarged twice first (cheap; pages already enlarged are skipped).
        progress(L.t("מכינים את הציורים להדפסה...", "Preparing the pictures for print..."))
        try { BookMaker.Print.prepare(ctx, bookId) { i, n -> progress(L.t("מחדדים ציור ${i + 1} מתוך $n...", "Sharpening picture ${i + 1} of $n...")) } } catch (_: Exception) { }
        val b = BookStore.load(ctx, bookId) ?: throw IllegalStateException(L.t("הספר לא נמצא", "The book wasn't found"))
        val pages = pageCount(b)
        val work = File(ctx.cacheDir, "print_${b.id}").apply { deleteRecursively(); mkdirs() }
        try {
            val interior = interiorPdf(ctx, b, work) { i, n -> progress(L.t("מעמדים עמוד ${i + 1} מתוך $n...", "Laying out page ${i + 1} of $n...")) }
            progress(L.t("מכינים את הכריכה...", "Making the cover..."))
            val size = call(ctx, mapOf("action" to "print_cover", "pages" to pages))
            val cover = coverPdf(ctx, b, work, size.optDouble("width"), size.optDouble("height"))
            val stamp = System.currentTimeMillis()
            val ip = "$uid/${safe(b.id)}/interior_$stamp.pdf"
            val cp = "$uid/${safe(b.id)}/cover_$stamp.pdf"
            progress(L.t("מעלים את קובץ העמודים (${interior.length() / 1_000_000} MB)...", "Uploading the pages (${interior.length() / 1_000_000} MB)..."))
            upload(ctx, ip, interior)
            progress(L.t("מעלים את הכריכה...", "Uploading the cover..."))
            upload(ctx, cp, cover)
            progress(L.t("שולחים את ההזמנה ל-Lulu...", "Sending the order to Lulu..."))
            val r = call(ctx, mapOf(
                "action" to "print_order", "pages" to pages, "title" to b.title, "book" to b.id, "email" to email,
                "address" to address, "shipping" to shipping, "interior" to ip, "cover" to cp,
            ))
            val o = JSONObject().put("id", r.optString("id")).put("status", r.optString("status"))
                .put("created", stamp).put("shipping", shipping).put("pages", pages)
            saveOrder(ctx, bookId, o)
            return o
        } finally {
            work.deleteRecursively()
        }
    }

    /** What [exportFiles] made: the files, and why the cover is missing (null when it's there). */
    class Export(val files: List<File>, val noCover: String?)

    /**
     * The same two print files as an order, kept on the phone (nothing uploaded, nothing ordered) so they can be
     * looked at or uploaded to Lulu's site by hand. The cover's size comes from Lulu through our server; without it
     * only the pages are made.
     */
    fun exportFiles(ctx: Context, bookId: String, progress: (String) -> Unit): Export {
        progress(L.t("מכינים את הציורים להדפסה...", "Preparing the pictures for print..."))
        try { BookMaker.Print.prepare(ctx, bookId) { i, n -> progress(L.t("מחדדים ציור ${i + 1} מתוך $n...", "Sharpening picture ${i + 1} of $n...")) } } catch (_: Exception) { }
        val b = BookStore.load(ctx, bookId) ?: throw IllegalStateException(L.t("הספר לא נמצא", "The book wasn't found"))
        val out = File(ctx.cacheDir, "share/print").apply { deleteRecursively(); mkdirs() }
        val work = File(ctx.cacheDir, "print_x_${b.id}").apply { deleteRecursively(); mkdirs() }
        try {
            val interior = interiorPdf(ctx, b, work) { i, n -> progress(L.t("מעמדים עמוד ${i + 1} מתוך $n...", "Laying out page ${i + 1} of $n...")) }
            val files = ArrayList<File>()
            files.add(File(out, "interior.pdf").also { interior.copyTo(it, overwrite = true) })
            progress(L.t("מכינים את הכריכה...", "Making the cover..."))
            val noCover = try {
                val size = call(ctx, mapOf("action" to "print_cover", "pages" to pageCount(b)))
                val cover = coverPdf(ctx, b, work, size.optDouble("width"), size.optDouble("height"))
                files.add(File(out, "cover.pdf").also { cover.copyTo(it, overwrite = true) })
                null
            } catch (e: Exception) { e.message ?: "" }
            return Export(files, noCover)
        } finally {
            work.deleteRecursively()
        }
    }

    /** Opens the share sheet with the print files (save to Drive/Files, or send to yourself). */
    fun shareFiles(ctx: Context, files: List<File>, title: String) {
        val uris = ArrayList<android.net.Uri>()
        for (f in files) uris.add(androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f))
        val send = android.content.Intent(android.content.Intent.ACTION_SEND_MULTIPLE).apply {
            type = "application/pdf"
            putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, uris)
            putExtra(android.content.Intent.EXTRA_SUBJECT, title)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(android.content.Intent.createChooser(send, L.t("שמירת קבצי ההדפסה", "Save the print files")).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun safe(s: String) = s.replace(Regex("[^A-Za-z0-9_-]"), "")

    private fun upload(ctx: Context, path: String, f: File) {
        val conn = URL(Circle.url(ctx) + "/storage/v1/object/print/$path").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 30_000
            conn.readTimeout = 300_000
            conn.setRequestProperty("apikey", Circle.key(ctx))
            conn.setRequestProperty("Authorization", "Bearer " + (Account.token(ctx) ?: ""))
            conn.setRequestProperty("Content-Type", "application/pdf")
            conn.setRequestProperty("x-upsert", "true")
            conn.doOutput = true
            conn.setFixedLengthStreamingMode(f.length())
            conn.outputStream.use { o -> f.inputStream().use { it.copyTo(o, 64 * 1024) } }
            val code = conn.responseCode
            if (code !in 200..299) {
                val why = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty().take(200)
                throw IllegalStateException(
                    if (code == 404 || why.contains("Bucket not found", true)) L.t("השרת עוד לא מוכן להדפסה (צריך להריץ את print-books.sql)", "The server isn't ready for printing yet (run print-books.sql)")
                    else L.t("ההעלאה נכשלה ($code)", "Upload failed ($code)") + " " + why,
                )
            }
        } finally { conn.disconnect() }
    }

    // ---------- the inside pages ----------

    /** Every inside page drawn at print size (in points, scaled to 300 per inch) and saved as JPEG, then one PDF. */
    private fun interiorPdf(ctx: Context, b: Book, work: File, progress: (Int, Int) -> Unit): File {
        val dir = BookStore.dir(ctx, b.id)
        val rtl = b.lang == "he"
        val w = PrintSpec.pageWpt.toFloat(); val h = PrintSpec.pageHpt.toFloat()
        val safe = ((PrintSpec.BLEED_IN + 0.5) * 72).toFloat()
        val list = plan(b)
        val imgs = ArrayList<StreamPdf.Page>()
        list.forEachIndexed { i, (kind, story) ->
            progress(i, list.size)
            val bmp = Bitmap.createBitmap(PrintSpec.pageWpx, PrintSpec.pageHpx, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.scale(PrintSpec.pageWpx / w, PrintSpec.pageHpx / h)
            when (kind) {
                PrintSpec.Kind.TITLE -> {
                    picture(c, pictureOf(dir, b.cover), w, h)
                    c.drawRect(0f, 0f, w, h * 0.4f, Paint().apply { shader = LinearGradient(0f, 0f, 0f, h * 0.4f, 0x99000000.toInt(), 0, Shader.TileMode.CLAMP) })
                    val t = block(c, b.title, 40f, Color.WHITE, safe + 30f, w, safe, bold = true, rtl = rtl, shadow = true)
                    block(c, L.t("ספר של ${b.child}", "A book about ${b.child}"), 18f, Color.WHITE, safe + 30f + t + 14f, w, safe, rtl = rtl, shadow = true)
                }
                PrintSpec.Kind.OWNER -> {
                    c.drawColor(paper)
                    block(c, L.t("הספר הזה שייך ל:", "This book belongs to:"), 26f, ink, h * 0.38f, w, safe, rtl = rtl)
                    c.drawLine(w * 0.22f, h * 0.52f, w * 0.78f, h * 0.52f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = soft; strokeWidth = 1.2f })
                    logo(ctx, c, w / 2f, h - safe - 40f, 22f)
                }
                PrintSpec.Kind.DEDICATION -> {
                    c.drawColor(paper)
                    block(c, "♡", 34f, Color.parseColor("#C07A5A"), h * 0.34f, w, safe, rtl = rtl)
                    block(c, b.dedication, 24f, ink, h * 0.34f + 60f, w, safe, rtl = rtl)
                }
                PrintSpec.Kind.TEXT -> {
                    c.drawColor(paper)
                    val p = b.pages[story]
                    val textH = measure(BookJson.display(p.text), 25f, w, safe, rtl)
                    block(c, BookJson.display(p.text), 25f, ink, (h - textH) / 2f, w, safe, rtl = rtl)
                    block(c, "${story + 1}", 11f, soft, h - safe - 14f, w, safe, rtl = rtl)
                }
                PrintSpec.Kind.PICTURE -> picture(c, pictureOf(dir, b.pages[story]), w, h)
                PrintSpec.Kind.NOTES -> {
                    c.drawColor(paper)
                    block(c, L.t("רגעים לזכור", "Moments to remember"), 22f, ink, safe + 20f, w, safe, bold = true, rtl = rtl)
                    val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33000000; strokeWidth = 0.8f }
                    var y = safe + 90f
                    while (y < h - safe - 10f) { c.drawLine(safe, y, w - safe, y, line); y += 34f }
                }
                PrintSpec.Kind.END -> {
                    if (picture(c, pictureOf(dir, b.cover), w, h)) c.drawColor(0xB3000000.toInt())
                    block(c, L.t("הסוף", "The End"), 48f, Color.WHITE, h * 0.4f, w, safe, bold = true, rtl = rtl)
                    block(c, L.t("נוצר באהבה ב-Moments", "Made with love in Moments"), 16f, Color.WHITE, h * 0.4f + 72f, w, safe, rtl = rtl)
                }
            }
            val f = File(work, "p$i.jpg")
            FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            bmp.recycle()
            imgs.add(StreamPdf.Page(w.toDouble(), h.toDouble(), listOf(StreamPdf.Img(f, PrintSpec.pageWpx, PrintSpec.pageHpx, 0.0, 0.0, w.toDouble(), h.toDouble()))))
        }
        val out = File(work, "interior.pdf")
        FileOutputStream(out).use { StreamPdf.write(it, imgs) }
        imgs.forEach { p -> p.images.forEach { it.file.delete() } }
        return out
    }

    // ---------- the cover ----------

    /**
     * One spread: the back on the left (the Moments mark), the front on the right (the book's cover picture and its
     * title). Drawn in 4 strips so a cover of ~6000 x 4000 pixels fits in a phone's memory.
     */
    private fun coverPdf(ctx: Context, b: Book, work: File, wPt: Double, hPt: Double): File {
        if (!(wPt > 100 && hPt > 100)) throw IllegalStateException(L.t("Lulu לא החזירה את מידות הכריכה", "Lulu didn't return the cover size"))
        val dir = BookStore.dir(ctx, b.id)
        val rtl = b.lang == "he"
        val (pxW, pxH) = PrintSpec.coverPx(wPt, hPt)
        val w = wPt.toFloat(); val h = hPt.toFloat()
        // The front starts a little after the middle (past the spine), and the words stay well inside the edges
        // (a hardcover's cover wraps around the boards).
        val frontLeft = w / 2f + 0.35f * 72f
        val margin = 1.1f * 72f
        val picFile = pictureOf(dir, b.cover)
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        picFile?.let { BitmapFactory.decodeFile(it.absolutePath, o) }
        val pic = picFile?.let { BitmapFactory.decodeFile(it.absolutePath) }
        val strips = 4
        val imgs = ArrayList<StreamPdf.Img>()
        try {
            for (k in 0 until strips) {
                val x0 = pxW * k / strips; val x1 = pxW * (k + 1) / strips
                val bmp = Bitmap.createBitmap(x1 - x0, pxH, Bitmap.Config.ARGB_8888)
                val c = Canvas(bmp)
                c.translate(-x0.toFloat(), 0f)
                c.scale(pxW / w, pxH / h)
                c.drawColor(paper)
                // Back: the mark and a line, centered on the back half.
                logo(ctx, c, w / 4f, h * 0.45f, 34f)
                blockAt(c, L.t("נוצר באהבה ב-Moments", "Made with love in Moments"), 16f, soft, w / 4f, h * 0.45f + 70f, w / 2f - 2 * margin, rtl)
                // Front: the cover picture, filling the front.
                if (pic != null) {
                    val fw = w - frontLeft; val scale = max(fw / pic.width, h / pic.height)
                    val sw = (fw / scale).toInt(); val sh = (h / scale).toInt()
                    val sx = (pic.width - sw) / 2; val sy = (pic.height - sh) / 2
                    c.drawBitmap(pic, Rect(sx, sy, sx + sw, sy + sh), RectF(frontLeft, 0f, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
                } else c.drawRect(frontLeft, 0f, w, h, Paint().apply { color = brand })
                c.drawRect(frontLeft, 0f, w, h * 0.42f, Paint().apply { shader = LinearGradient(0f, 0f, 0f, h * 0.42f, 0x99000000.toInt(), 0, Shader.TileMode.CLAMP) })
                val cx = (frontLeft + w) / 2f
                val tw = w - frontLeft - 2 * margin
                val th = blockAt(c, b.title, 40f, Color.WHITE, cx, margin + 10f, tw, rtl, bold = true, shadow = true)
                blockAt(c, L.t("ספר של ${b.child}", "A book about ${b.child}"), 18f, Color.WHITE, cx, margin + 10f + th + 12f, tw, rtl, shadow = true)
                val f = File(work, "cover_$k.jpg")
                FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                bmp.recycle()
                val ptX = wPt * k / strips; val ptW = wPt * (k + 1) / strips - ptX
                imgs.add(StreamPdf.Img(f, x1 - x0, pxH, ptX, 0.0, ptW, hPt))
            }
        } finally { pic?.recycle() }
        val out = File(work, "cover.pdf")
        FileOutputStream(out).use { StreamPdf.write(it, listOf(StreamPdf.Page(wPt, hPt, imgs))) }
        imgs.forEach { it.file.delete() }
        return out
    }

    // ---------- drawing ----------

    /** The print-quality picture of a page (enlarged) when it exists, else the screen one. */
    private fun pictureOf(dir: File, p: BookPage): File? =
        p.print.takeIf { it.isNotEmpty() }?.let { File(dir, it) }?.takeIf { it.exists() }
            ?: p.image.takeIf { it.isNotEmpty() }?.let { File(dir, it) }?.takeIf { it.exists() }

    /** The picture filling the page (cropped from the middle). False when there's none. */
    private fun picture(c: Canvas, f: File?, w: Float, h: Float): Boolean {
        val bmp = f?.let { BitmapFactory.decodeFile(it.absolutePath) } ?: run { c.drawColor(paper); return false }
        val scale = max(w / bmp.width, h / bmp.height)
        val sw = (w / scale).toInt(); val sh = (h / scale).toInt()
        val sx = (bmp.width - sw) / 2; val sy = (bmp.height - sh) / 2
        c.drawBitmap(bmp, Rect(sx, sy, sx + sw, sy + sh), RectF(0f, 0f, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
        bmp.recycle()
        return true
    }

    private fun paint(size: Float, color: Int, bold: Boolean, shadow: Boolean) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size; this.color = color; isFakeBoldText = bold
        try { typeface = Fonts.display } catch (_: Throwable) { }
        if (shadow) setShadowLayer(5f, 0f, 1.5f, 0xAA000000.toInt())
    }

    private fun layout(t: String, p: TextPaint, width: Int, rtl: Boolean): StaticLayout =
        StaticLayout.Builder.obtain(t, 0, t.length, p, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setTextDirection(if (rtl) TextDirectionHeuristics.RTL else TextDirectionHeuristics.LTR)
            .setLineSpacing(7f, 1f).build()

    private fun measure(t: String, size: Float, w: Float, safe: Float, rtl: Boolean): Float =
        layout(t, paint(size, 0, false, false), (w - 2 * safe).toInt(), rtl).height.toFloat()

    /** Centered text across the page inside the safe margins, from [top]; returns its height. */
    private fun block(c: Canvas, t: String, size: Float, color: Int, top: Float, w: Float, safe: Float, bold: Boolean = false, rtl: Boolean = true, shadow: Boolean = false): Float {
        val l = layout(t, paint(size, color, bold, shadow), (w - 2 * safe).toInt(), rtl)
        c.save(); c.translate(safe, top); l.draw(c); c.restore()
        return l.height.toFloat()
    }

    /** Centered text of [width] around [cx], from [top]; returns its height. */
    private fun blockAt(c: Canvas, t: String, size: Float, color: Int, cx: Float, top: Float, width: Float, rtl: Boolean, bold: Boolean = false, shadow: Boolean = false): Float {
        val l = layout(t, paint(size, color, bold, shadow), width.toInt(), rtl)
        c.save(); c.translate(cx - width / 2f, top); l.draw(c); c.restore()
        return l.height.toFloat()
    }

    private fun logo(ctx: Context, c: Canvas, cx: Float, cy: Float, size: Float) {
        val d = ContextCompat.getDrawable(ctx, R.drawable.ic_logo_mark)?.mutate() ?: return
        d.setTint(brand)
        d.setBounds((cx - size / 2).toInt(), (cy - size / 2).toInt(), (cx + size / 2).toInt(), (cy + size / 2).toInt())
        d.draw(c)
    }
}
