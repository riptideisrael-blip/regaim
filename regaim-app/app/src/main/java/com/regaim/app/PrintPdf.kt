package com.regaim.app

import java.io.File
import java.io.OutputStream

/**
 * The printed book's shape (pure, unit tested): a hardcover picture book, US Letter 8.5 x 11 in, full color.
 * Lulu wants every page with 0.125 in "bleed" on each side (cut off when trimmed) and pictures at 300 per inch.
 */
object PrintSpec {
    const val DPI = 300
    const val TRIM_W_IN = 8.5
    const val TRIM_H_IN = 11.0
    const val BLEED_IN = 0.125
    /** Lulu's hardcover needs at least 24 pages inside. */
    const val MIN_PAGES = 24

    val pageWpt: Int get() = ((TRIM_W_IN + 2 * BLEED_IN) * 72).toInt()   // 630
    val pageHpt: Int get() = ((TRIM_H_IN + 2 * BLEED_IN) * 72).toInt()   // 810
    val pageWpx: Int get() = ((TRIM_W_IN + 2 * BLEED_IN) * DPI).toInt()  // 2625
    val pageHpx: Int get() = ((TRIM_H_IN + 2 * BLEED_IN) * DPI).toInt()  // 3375
    /** Text kept this far inside the trimmed edge (Lulu's safety margin, 0.5 in), in page pixels from the page edge. */
    val safePx: Int get() = ((BLEED_IN + 0.5) * DPI).toInt()

    /** What goes on each inside page, in order. */
    enum class Kind { TITLE, OWNER, DEDICATION, TEXT, PICTURE, NOTES, END }

    /**
     * The inside pages: a title page, "this book belongs to", the dedication, then each story page as a pair (its
     * words facing its picture), and "the end"; notes pages ("moments to remember") fill up to Lulu's minimum and
     * to an even count. The int is the story page index (for TEXT/PICTURE), else -1.
     */
    fun plan(storyPages: Int, dedication: Boolean): List<Pair<Kind, Int>> {
        val out = ArrayList<Pair<Kind, Int>>()
        out.add(Kind.TITLE to -1)
        out.add(Kind.OWNER to -1)
        if (dedication) out.add(Kind.DEDICATION to -1)
        for (i in 0 until storyPages) { out.add(Kind.TEXT to i); out.add(Kind.PICTURE to i) }
        val end = Kind.END to -1
        while (out.size + 1 < MIN_PAGES || (out.size + 1) % 2 != 0) out.add(Kind.NOTES to -1)
        out.add(end)
        return out
    }

    /** Pixels for a cover of [wPt] x [hPt] points (Lulu gives the size, which depends on the page count). */
    fun coverPx(wPt: Double, hPt: Double): Pair<Int, Int> = Math.round(wPt / 72.0 * DPI).toInt() to Math.round(hPt / 72.0 * DPI).toInt()
}

/**
 * A PDF made of JPEG pictures, written straight to a file (a print book is tens of MB: it isn't held in memory).
 * Each page can hold several pictures side by side (a big cover is drawn in strips, to fit in a phone's memory).
 */
object StreamPdf {
    /** One picture on a page: the JPEG file, its pixel size, and where it goes on the page (points, from bottom-left). */
    class Img(val file: File, val pxW: Int, val pxH: Int, val x: Double, val y: Double, val w: Double, val h: Double)
    class Page(val wPt: Double, val hPt: Double, val images: List<Img>)

    private class Counting(val out: OutputStream) : OutputStream() {
        var n = 0L
        override fun write(b: Int) { out.write(b); n++ }
        override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); n += len }
    }

    private fun num(d: Double) = if (d == Math.floor(d)) d.toLong().toString() else "%.3f".format(java.util.Locale.US, d)

    fun write(out: OutputStream, pages: List<Page>) {
        val o = Counting(out)
        val offsets = HashMap<Int, Long>()
        fun ascii(s: String) = o.write(s.toByteArray(Charsets.US_ASCII))
        fun obj(n: Int, body: () -> Unit) { offsets[n] = o.n; ascii("$n 0 obj\n"); body(); ascii("\nendobj\n") }
        ascii("%PDF-1.4\n")
        o.write(byteArrayOf(0x25, 0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), 0x0A))
        // Numbering: 1 catalog, 2 pages; then each page = page object, contents, and one object per picture.
        var next = 3
        val pageNums = pages.map { p -> val n = next; next += 2 + p.images.size; n }
        obj(1) { ascii("<< /Type /Catalog /Pages 2 0 R >>") }
        obj(2) { ascii("<< /Type /Pages /Count ${pages.size} /Kids [${pageNums.joinToString(" ") { "$it 0 R" }}] >>") }
        pages.forEachIndexed { pi, p ->
            val n = pageNums[pi]
            val xo = p.images.indices.joinToString(" ") { "/Im$it ${n + 2 + it} 0 R" }
            obj(n) { ascii("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${num(p.wPt)} ${num(p.hPt)}] /Resources << /XObject << $xo >> >> /Contents ${n + 1} 0 R >>") }
            val draw = p.images.mapIndexed { i, im -> "q ${num(im.w)} 0 0 ${num(im.h)} ${num(im.x)} ${num(im.y)} cm /Im$i Do Q" }.joinToString("\n")
            obj(n + 1) { ascii("<< /Length ${draw.length} >>\nstream\n$draw\nendstream") }
            p.images.forEachIndexed { i, im ->
                obj(n + 2 + i) {
                    ascii("<< /Type /XObject /Subtype /Image /Width ${im.pxW} /Height ${im.pxH} /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length ${im.file.length()} >>\nstream\n")
                    im.file.inputStream().use { it.copyTo(o) }
                    ascii("\nendstream")
                }
            }
        }
        val xref = o.n
        ascii("xref\n0 $next\n0000000000 65535 f \n")
        for (k in 1 until next) ascii("%010d 00000 n \n".format(offsets[k] ?: 0L))
        ascii("trailer\n<< /Size $next /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        o.flush()
    }
}
