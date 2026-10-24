package com.regaim.app

import android.content.Context
import android.content.Intent
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
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

/**
 * The finished book as one PDF to send (WhatsApp, email...): the cover, the dedication, every page with its text,
 * and "The End". Every page carries the small Moments mark (the book's watermark). Made on the phone from the
 * screen-size pictures, so the file stays small enough to send.
 */
object BookPdf {
    private const val W = 576 // points: a tall page, the shape of the pictures (9:16)
    private const val H = 1024
    private val paper = Color.parseColor("#F8F2E7")
    private val ink = Color.parseColor("#2A2420")

    /** Pixels of each page picture (the page is drawn whole, then saved as JPEG: a few MB for the whole book). */
    private const val PX_W = 1080
    private const val PX_H = 1920

    /** Background thread only. */
    fun make(ctx: Context, b: Book): File {
        val dir = BookStore.dir(ctx, b.id)
        // Each page is drawn into a picture and kept as JPEG. (Android's own PDF maker stored pictures uncompressed:
        // a 36 MB book, too big to send.)
        val jpegs = ArrayList<ByteArray>()
        fun page(light: Boolean = false, draw: (Canvas) -> Unit) {
            val bmp = Bitmap.createBitmap(PX_W, PX_H, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.scale(PX_W.toFloat() / W, PX_H.toFloat() / H)
            draw(c)
            mark(ctx, c, light)
            jpegs.add(java.io.ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 84, it) }.toByteArray())
            bmp.recycle()
        }
        val rtl = b.lang == "he"
        page { c ->
            picture(c, File(dir, b.cover.image))
            c.drawRect(0f, 0f, W.toFloat(), H * 0.35f, Paint().apply {
                shader = LinearGradient(0f, 0f, 0f, H * 0.35f, 0x99000000.toInt(), 0, Shader.TileMode.CLAMP)
            })
            text(c, b.title, 40f, Color.WHITE, 60f, bold = true, rtl = rtl, shadow = true)
            text(c, L.t("ספר של ${b.child}", "A book about ${b.child}"), 18f, Color.WHITE, 60f + lines(b.title, 40f) + 14f, rtl = rtl, shadow = true)
        }
        if (b.dedication.isNotBlank()) page(light = true) { c ->
            c.drawColor(paper)
            text(c, "♡", 34f, Color.parseColor("#C07A5A"), H * 0.36f, rtl = rtl)
            text(c, b.dedication, 26f, ink, H * 0.36f + 60f, rtl = rtl)
        }
        b.pages.forEachIndexed { i, p ->
            page { c ->
                picture(c, File(dir, p.image))
                // The words on a soft paper panel over the calm lower part of the picture, as in the app.
                val tp = textPaint(20f, ink, false)
                val layout = layout(BookJson.display(p.text), tp, W - 112, rtl)
                val panelH = layout.height + 70f
                val top = H - 40f - panelH
                c.drawRoundRect(RectF(28f, top, W - 28f, H - 40f), 26f, 26f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = (paper and 0x00FFFFFF) or (0xEB shl 24) })
                c.save(); c.translate(56f, top + 24f); layout.draw(c); c.restore()
                text(c, "${i + 1}", 11f, Color.parseColor("#8A7F74"), H - 40f - 26f, rtl = rtl)
            }
        }
        page { c ->
            if (!picture(c, File(dir, b.cover.image))) c.drawColor(paper) else c.drawColor(0xB3000000.toInt())
            text(c, L.t("הסוף", "The End"), 48f, Color.WHITE, H * 0.42f, bold = true, rtl = rtl)
            text(c, L.t("נוצר באהבה ב-Moments", "Made with love in Moments"), 16f, Color.WHITE, H * 0.42f + 72f, rtl = rtl)
        }
        val out = File(File(ctx.cacheDir, "share").apply { mkdirs() }, safeName(b.title) + ".pdf")
        FileOutputStream(out).use { PdfOfJpegs.write(it, jpegs, PX_W, PX_H, W, H) }
        return out
    }

    /** Opens the share sheet with the book's PDF. */
    fun share(ctx: Context, f: File, title: String) {
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(send, L.t("שליחת הספר", "Send the book")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun safeName(t: String) = t.replace(Regex("[\\\\/:*?\"<>|\\n\\r]"), " ").trim().take(40).ifEmpty { "book" }

    /** The picture filling the page (cropped from the middle). False if there's no picture. */
    private fun picture(c: Canvas, f: File): Boolean {
        if (!f.exists()) { c.drawColor(paper); return false }
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, o)
        var sample = 1
        while (o.outWidth / (sample * 2) >= W * 2) sample *= 2
        val bmp: Bitmap = BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample }) ?: run { c.drawColor(paper); return false }
        val scale = max(W.toFloat() / bmp.width, H.toFloat() / bmp.height)
        val sw = (W / scale).toInt(); val sh = (H / scale).toInt()
        val sx = (bmp.width - sw) / 2; val sy = (bmp.height - sh) / 2
        c.drawBitmap(bmp, Rect(sx, sy, sx + sw, sy + sh), Rect(0, 0, W, H), Paint(Paint.FILTER_BITMAP_FLAG))
        bmp.recycle()
        return true
    }

    /** The watermark: the Moments mark and name, small, in the top corner. */
    private fun mark(ctx: Context, c: Canvas, light: Boolean) {
        val d = ContextCompat.getDrawable(ctx, R.drawable.ic_logo_mark)?.mutate() ?: return
        val col = if (light) Color.parseColor("#87664F") else Color.WHITE
        d.setTint(col)
        d.alpha = 170
        d.setBounds(22, 22, 44, 44)
        d.draw(c)
        c.drawText("Moments", 50f, 39f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = col; alpha = 170; textSize = 14f; isFakeBoldText = true
            if (!light) setShadowLayer(3f, 0f, 1f, 0x88000000.toInt())
        })
    }

    private fun textPaint(size: Float, color: Int, bold: Boolean) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size; this.color = color; isFakeBoldText = bold
        try { typeface = Fonts.display } catch (_: Throwable) { }
    }

    private fun layout(t: String, p: TextPaint, width: Int, rtl: Boolean): StaticLayout =
        StaticLayout.Builder.obtain(t, 0, t.length, p, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setTextDirection(if (rtl) TextDirectionHeuristics.RTL else TextDirectionHeuristics.LTR)
            .setLineSpacing(6f, 1f)
            .build()

    private fun lines(t: String, size: Float): Float = layout(t, textPaint(size, 0, true), W - 80, true).height.toFloat()

    /** Centered text block starting at [top]. */
    private fun text(c: Canvas, t: String, size: Float, color: Int, top: Float, bold: Boolean = false, rtl: Boolean = true, shadow: Boolean = false) {
        val p = textPaint(size, color, bold)
        if (shadow) p.setShadowLayer(6f, 0f, 2f, 0xAA000000.toInt())
        val l = layout(t, p, W - 80, rtl)
        c.save(); c.translate(40f, top); l.draw(c); c.restore()
    }
}

/** A minimal PDF: one full-page JPEG per page (pure, unit tested). */
object PdfOfJpegs {
    fun write(out: java.io.OutputStream, pages: List<ByteArray>, pxW: Int, pxH: Int, ptW: Int, ptH: Int) {
        val buf = java.io.ByteArrayOutputStream()
        val offsets = ArrayList<Int>()
        fun ascii(s: String) = buf.write(s.toByteArray(Charsets.US_ASCII))
        fun obj(n: Int, body: () -> Unit) { while (offsets.size < n) offsets.add(0); offsets[n - 1] = buf.size(); ascii("$n 0 obj\n"); body(); ascii("\nendobj\n") }
        ascii("%PDF-1.4\n")
        buf.write(byteArrayOf(0x25, 0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), 0x0A)) // "binary file" marker
        // 1 = catalog, 2 = pages, then per page: page, picture, drawing.
        val kids = pages.indices.joinToString(" ") { "${3 + it * 3} 0 R" }
        obj(1) { ascii("<< /Type /Catalog /Pages 2 0 R >>") }
        obj(2) { ascii("<< /Type /Pages /Count ${pages.size} /Kids [$kids] >>") }
        pages.forEachIndexed { i, jpg ->
            val p = 3 + i * 3
            obj(p) { ascii("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 $ptW $ptH] /Resources << /XObject << /Im0 ${p + 1} 0 R >> >> /Contents ${p + 2} 0 R >>") }
            obj(p + 1) {
                ascii("<< /Type /XObject /Subtype /Image /Width $pxW /Height $pxH /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length ${jpg.size} >>\nstream\n")
                buf.write(jpg)
                ascii("\nendstream")
            }
            val draw = "q $ptW 0 0 $ptH 0 0 cm /Im0 Do Q"
            obj(p + 2) { ascii("<< /Length ${draw.length} >>\nstream\n$draw\nendstream") }
        }
        val xref = buf.size()
        ascii("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        for (o in offsets) ascii("%010d 00000 n \n".format(o))
        ascii("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        buf.writeTo(out)
    }
}
