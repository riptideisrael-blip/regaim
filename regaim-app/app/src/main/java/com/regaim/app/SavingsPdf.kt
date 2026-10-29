package com.regaim.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import java.io.File

/**
 * The savings as an A4 report to send or print, in the app's Sky Clean look (white page, one blue, hairlines):
 * a header, three numbers (today, the year's return, each month), what the total is made of, how it grew, then each
 * child with a bar of their funds and a card per fund (last report, each month, since the report, the year's return).
 * A footer with the page number on every page. Made on the phone, written to the share folder (replaced next time);
 * nothing is sent anywhere until the parent shares it.
 */
object SavingsPdf {
    private const val W = 595f
    private const val H = 842f
    private const val M = 36f
    private const val FOOT = 34f // room kept at the bottom of every page for the footer

    private val BLUE = Color.rgb(0x1A, 0x6F, 0xCC)
    private val SKY = Color.rgb(0x5A, 0xC8, 0xFA)
    private val INK = Color.rgb(0x0F, 0x1B, 0x2D)
    private val SOFT = Color.rgb(0x5B, 0x68, 0x7A)
    private val TILE = Color.rgb(0xEE, 0xF3, 0xF9)
    private val LINE = Color.rgb(0xE4, 0xE9, 0xF0)
    private val UP = Color.rgb(0x1C, 0x7F, 0x46)
    private val DOWN = Color.rgb(0xB3, 0x26, 0x1E)

    /**
     * [est]: a fund today (as on the savings screen); [ytd]: its return this year in % (official months or coin
     * prices), or null; [color]: the fund's colour as chosen on the savings screen (its index among the child's funds).
     */
    fun write(
        ctx: Context, funds: List<Savings.Fund>, people: List<FamilyMember>, today: String,
        est: (Savings.Fund) -> Savings.Estimate?, ytd: (Savings.Fund) -> Double? = { null },
        color: (Savings.Fund, Int) -> Int = { _, i -> listOf(BLUE, SKY, Color.rgb(0x7B, 0x8C, 0xDE), Color.rgb(0x2B, 0xA8, 0xA0))[i % 4] },
    ): File {
        val rtl = L.t("א", "a") == "א"
        val p = Painter(rtl)
        val e = funds.associateWith { est(it) }
        val total = e.values.sumOf { it?.total ?: 0.0 }
        val monthly = funds.sumOf { it.perMonth }
        val yearRet = Savings.weighted(funds.map { (e[it]?.total ?: 0.0) to ytd(it) })
        val kids = people.filter { k -> funds.any { it.child == k.id } }

        // ---- Header: the title, the date, the brand; a blue hairline under it.
        p.newPage()
        val title = if (kids.size == 1) L.t("החיסכון של ${kids[0].name}", "${kids[0].name}'s savings") else L.t("חיסכון והשקעות של המשפחה", "The family's savings and investments")
        p.text(title, p.readX(M, W - 2 * M, W - 2 * M - 90), p.y, W - 2 * M - 90, 21f, INK, bold = true)
        p.text("Moments", M, p.y + 6, W - 2 * M, 12f, BLUE, bold = true, opposite = true)
        p.y += 30
        p.text(L.t("נכון ל-${FamilyItems.short(today)} · הערכה לפי הדוח האחרון של כל קופה", "As of ${FamilyItems.short(today)} · estimated from each fund's last report"), M, p.y, W - 2 * M, 10.5f, SOFT)
        p.y += 20
        p.rect(M, p.y, W - M, p.y + 2.5f, BLUE, 1.2f)
        p.y += 18

        // ---- Three numbers.
        val gap = 10f
        val tw = (W - 2 * M - 2 * gap) / 3
        val who = listOfNotNull(
            if (funds.any { it.monthly > 0 }) L.t("הורים", "parents") else null,
            if (funds.any { it.stateMonthly > 0 }) L.t("מדינה", "state") else null,
            if (funds.any { f -> f.coins.any { it.monthly > 0 } }) L.t("קריפטו", "crypto") else null,
        ).joinToString(" + ")
        val tiles = listOf(
            Triple(L.t("יש היום", "Today"), ils(total) to INK, L.t("הערכה", "estimate")),
            Triple(L.t("תשואה השנה", "Return this year"), (yearRet?.let { pct(it) } ?: "—") to (yearRet?.let { if (it >= 0) UP else DOWN } ?: SOFT),
                L.t("מתחילת ${today.take(4)}", "since Jan ${today.take(4)}")),
            Triple(L.t("הפקדה חודשית", "Each month"), ils(monthly) to INK, who),
        )
        tiles.forEachIndexed { i, (label, value, sub) ->
            // First tile on the reading side (right in Hebrew).
            val x = if (rtl) W - M - (i + 1) * tw - i * gap else M + i * (tw + gap)
            p.round(x, p.y, x + tw, p.y + 66, TILE, 10f)
            p.text(label, x + 12, p.y + 10, tw - 24, 9.5f, SOFT)
            p.text(value.first, x + 12, p.y + 25, tw - 24, 18f, value.second, bold = true)
            if (sub.isNotEmpty()) p.text(sub, x + 12, p.y + 48, tw - 24, 8.5f, SOFT)
        }
        p.y += 66 + 22

        // ---- What the total is made of.
        val b = Savings.breakdown(funds.map { e[it] })
        if (b.total > 0) {
            p.ensure(96)
            p.heading(L.t("ממה מורכב הסכום", "What the total is made of"))
            val up = maxOf(b.growth, 0.0)
            val parts = listOf(b.reported to BLUE, b.depositedSince to SKY, up to UP).filter { it.first > 0 }
            p.bar(M, p.y, W - 2 * M, 10f, parts)
            p.y += 20
            p.row(L.t("היתרות בדוחות האחרונים", "Balances in the last reports"), ils(b.reported), BLUE)
            if (b.depositedSince > 0) p.row(L.t("הופקד מאז", "Deposited since"), ils(b.depositedSince), SKY)
            if (Math.abs(b.growth) >= 1) p.row(
                if (b.growth >= 0) L.t("רווח מאז (הערכה)", "Gain since (estimate)") else L.t("הפסד מאז (הערכה)", "Loss since (estimate)"),
                signed(b.growth), if (b.growth >= 0) UP else DOWN, valueColor = if (b.growth >= 0) UP else DOWN)
            p.y += 14
        }

        // ---- How it grew (one point per report date, today last).
        val hist = Savings.history(funds, today, total)
        if (hist.size >= 2) {
            p.ensure(150)
            p.heading(L.t("איך התיק גדל", "How it grew"))
            p.chart(M, p.y, W - 2 * M, 96f, hist)
            p.y += 96 + 26
        }

        // ---- Each child: a bar of their funds, then a card per fund.
        for (k in kids) {
            val mine = funds.filter { it.child == k.id }
            val kidTotal = mine.sumOf { e[it]?.total ?: 0.0 }
            p.ensure(74 + 78)
            // One child: the title already has the name and the tiles the total.
            if (kids.size > 1) {
                p.text(k.name, p.readX(M, W - 2 * M, W - 2 * M - 120), p.y, W - 2 * M - 120, 15f, INK, bold = true)
                p.text(ils(kidTotal), M, p.y, W - 2 * M, 15f, INK, bold = true, opposite = true)
                p.y += 24
            } else p.heading(L.t("הקופות", "The funds"))
            val cols = mine.mapIndexed { i, f -> color(f, i) }
            if (mine.size > 1 && kidTotal > 0) {
                p.bar(M, p.y, W - 2 * M, 8f, mine.mapIndexed { i, f -> (e[f]?.total ?: 0.0) to cols[i] }.filter { it.first > 0 })
                p.y += 14
                val legend = mine.mapIndexed { i, f -> f.provider.ifBlank { f.title } + " " + Math.round((e[f]?.total ?: 0.0) / kidTotal * 100) + "%" }
                var lx = 0f
                legend.forEachIndexed { i, s ->
                    val w = p.width(s, 9f) + 12
                    if (lx > 0 && lx + w > W - 2 * M) { lx = 0f; p.y += 15 } // the next line
                    val x = if (rtl) W - M - lx - w else M + lx
                    p.round(if (rtl) x + w - 8 else x, p.y + 3, if (rtl) x + w - 2 else x + 6, p.y + 9, cols[i], 2f)
                    p.text(s, if (rtl) x else x + 10, p.y, w - 10, 9f, SOFT)
                    lx += w + 12
                }
                p.y += 20
            } else p.y += 2

            mine.forEachIndexed { i, f -> fundCard(p, f, e[f], ytd(f), cols[i], rtl) }
            p.y += 10
        }

        // ---- Where the numbers come from.
        val hasGemel = funds.any { it.kind != Savings.Kind.CRYPTO && it.gemelId > 0 }
        val hasPlain = funds.any { it.kind != Savings.Kind.CRYPTO && it.gemelId == 0 && it.lastReport != null }
        val hasCrypto = funds.any { it.kind == Savings.Kind.CRYPTO }
        val how = listOfNotNull(
            if (hasGemel) L.t("היתרה בדוח האחרון לפי התשואות החודשיות הרשמיות (גמל נט, משרד האוצר) ועוד ההפקדות מאז", "the last report's balance at the official monthly returns (Gemel-Net, Ministry of Finance) plus the deposits since") else null,
            if (hasPlain) L.t("קופה שלא נמצאה בגמל נט: היתרה בדוח ועוד ההפקדות, בלי תשואה", "a fund not at Gemel-Net: the report's balance plus the deposits, no return") else null,
            if (hasCrypto) L.t("מטבעות דיגיטליים: היחידות לפי המחיר של היום", "crypto: the units at today's price") else null,
        )
        val note = L.t("איך חישבנו: ", "How it's worked out: ") + how.joinToString("; ") +
            L.t(". הסכום המדויק מופיע בדוח הבא של הקופה. תשואות עבר אינן מבטיחות תשואות בעתיד. מידע בלבד, לא המלצה.",
                ". The exact amount is in the fund's next report. Past returns don't promise future ones. Information only, not advice.")
        val nh = p.measure(note, W - 2 * M - 24, 8.5f)
        p.ensure(nh + 20)
        p.round(M, p.y, W - M, p.y + nh + 18, TILE, 8f)
        p.text(note, M + 12, p.y + 9, W - 2 * M - 24, 8.5f, SOFT)
        p.y += nh + 18

        val out = File(File(ctx.cacheDir, "share").apply { mkdirs() }, "savings_summary.pdf")
        p.finish(out)
        return out
    }

    /** One fund: its name and today's amount, what kind and track, then four small columns. */
    private fun fundCard(p: Painter, f: Savings.Fund, e: Savings.Estimate?, ytd: Double?, color: Int, rtl: Boolean) {
        val h = 78f
        p.ensure(h + 8)
        val top = p.y
        p.round(M, top, W - M, top + h, Color.WHITE, 10f, stroke = LINE)
        // The fund's colour as a thin bar on the reading side.
        if (rtl) p.round(W - M - 4, top + 12, W - M, top + h - 12, color, 2f) else p.round(M, top + 12, M + 4, top + h - 12, color, 2f)
        val x0 = M + 16; val w = W - 2 * M - 32
        val name = listOf(f.provider, f.name.takeIf { f.track.isBlank() }.orEmpty()).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { f.kind.label }
        p.text(Savings.isolateLatin(name), p.readX(x0, w, w - 110), top + 10, w - 110, 12.5f, INK, bold = true)
        p.text(e?.let { ils(it.total) } ?: "—", x0, top + 10, w, 12.5f, INK, bold = true, opposite = true)
        val sub = listOf(f.kind.label, f.track).filter { it.isNotBlank() }.joinToString(" · ")
        p.text(Savings.isolateLatin(sub), x0, top + 27, w, 9f, SOFT)

        val r = f.lastReport
        val since = e?.let { it.total - it.reported - it.depositedSince }
        val cols = listOf(
            Triple(L.t("דוח אחרון", "Last report"), r?.let { "${ils(it.balance)} · ${FamilyItems.short(it.date)}" } ?: "—", INK),
            Triple(L.t("הפקדה בחודש", "Each month"), if (f.perMonth > 0) "+" + ils(f.perMonth) else "—", INK),
            Triple(L.t("מאז הדוח", "Since the report"), since?.takeIf { Math.abs(it) >= 1 }?.let { signed(it) } ?: "—",
                since?.takeIf { Math.abs(it) >= 1 }?.let { if (it >= 0) UP else DOWN } ?: SOFT),
            Triple(L.t("תשואה השנה", "This year"), ytd?.let { pct(it) } ?: "—", ytd?.let { if (it >= 0) UP else DOWN } ?: SOFT),
        )
        val cw = w / 4
        p.rect(x0, top + 44, x0 + w, top + 44.6f, LINE, 0f)
        cols.forEachIndexed { i, (label, value, c) ->
            val x = if (rtl) x0 + w - (i + 1) * cw else x0 + i * cw
            p.text(label, x, top + 50, cw - 6, 8f, SOFT)
            p.text(value, x, top + 61, cw - 6, 10f, c, bold = true)
        }
        p.y += h + 8
    }

    private fun ils(v: Double) = "⁨" + FamilyItems.money(Math.rint(v)) + "⁩"
    private fun signed(v: Double) = "⁦" + (if (v >= 0) "+" else "−") + FamilyItems.money(Math.rint(Math.abs(v))) + "⁩"
    private fun pct(v: Double) = "⁦" + (if (v >= 0) "+" else "−") + String.format(java.util.Locale.US, "%.1f", Math.abs(v)) + "%⁩"

    /** Pages, the position on the page, and drawing in points (1/72"), right-to-left when the app is in Hebrew. */
    private class Painter(val rtl: Boolean) {
        val doc = PdfDocument()
        var page: PdfDocument.Page? = null
        var pageNo = 0
        var y = M
        val c: Canvas get() = page!!.canvas

        fun newPage() {
            page?.let { footer(); doc.finishPage(it) }
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(W.toInt(), H.toInt(), pageNo).create())
            y = M
        }

        /** A new page when [h] more doesn't fit above the footer. */
        fun ensure(h: Float) { if (y + h > H - M - FOOT) { newPage(); y += 4 } }

        private fun footer() {
            val fy = H - M - 12
            rect(M, fy - 8, W - M, fy - 7.4f, LINE, 0f)
            text(L.t("הוכן באפליקציית Moments · מידע בלבד, לא המלצה", "Made with Moments · information only, not advice"), readX(M, W - 2 * M, W - 2 * M - 60), fy, W - 2 * M - 60, 8f, SOFT)
            text(L.t("עמוד $pageNo", "Page $pageNo"), M, fy, W - 2 * M, 8f, SOFT, opposite = true)
        }

        fun finish(out: File) {
            page?.let { footer(); doc.finishPage(it) }
            out.outputStream().use { doc.writeTo(it) }
            doc.close()
        }

        private fun paint(size: Float, color: Int, bold: Boolean) = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            textSize = size; this.color = color; typeface = Typeface.create(Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
        }

        private fun layout(s: String, w: Float, size: Float, color: Int = INK, bold: Boolean = false, opposite: Boolean = false) =
            StaticLayout.Builder.obtain(s, 0, s.length, paint(size, color, bold), w.toInt().coerceAtLeast(1))
                .setAlignment(if (opposite) Layout.Alignment.ALIGN_OPPOSITE else Layout.Alignment.ALIGN_NORMAL)
                .setTextDirection(if (rtl) TextDirectionHeuristics.RTL else TextDirectionHeuristics.LTR).build()

        /** A box [w] wide on the reading side of the space from [x0], [full] wide: its left edge. */
        fun readX(x0: Float, full: Float, w: Float) = if (rtl) x0 + full - w else x0

        /** Text in a box [w] wide, its left edge at [x]: aligned to the reading side, or the other side when [opposite]. Its height. */
        fun text(s: String, x: Float, top: Float, w: Float, size: Float, color: Int, bold: Boolean = false, opposite: Boolean = false): Float {
            val l = layout(s, w, size, color, bold, opposite)
            c.save(); c.translate(x, top); l.draw(c); c.restore()
            return l.height.toFloat()
        }

        fun measure(s: String, w: Float, size: Float) = layout(s, w, size).height.toFloat()
        fun width(s: String, size: Float) = paint(size, INK, false).measureText(s)

        fun heading(s: String) { text(s, M, y, W - 2 * M, 12.5f, INK, bold = true); y += 22 }

        /** A label on the reading side, its amount on the other, a colour dot before the label. */
        fun row(label: String, value: String, dot: Int, valueColor: Int = INK) {
            val dx = if (rtl) W - M - 8 else M
            round(dx, y + 4, dx + 8, y + 12, dot, 4f)
            text(label, readX(M, W - 2 * M, W - 2 * M - 14), y, W - 2 * M - 14, 10.5f, INK)
            text(value, M, y, W - 2 * M, 10.5f, valueColor, bold = true, opposite = true)
            y += 18
        }

        fun rect(l: Float, t: Float, r: Float, b: Float, color: Int, radius: Float) = round(l, t, r, b, color, radius)

        fun round(l: Float, t: Float, r: Float, b: Float, color: Int, radius: Float, stroke: Int? = null) {
            c.drawRoundRect(RectF(l, t, r, b), radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
            if (stroke != null) c.drawRoundRect(RectF(l, t, r, b), radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = stroke; style = Paint.Style.STROKE; strokeWidth = 0.8f })
        }

        /** A rounded bar split by [parts], starting on the reading side. */
        fun bar(x: Float, top: Float, w: Float, h: Float, parts: List<Pair<Double, Int>>) {
            val sum = parts.sumOf { it.first }
            round(x, top, x + w, top + h, TILE, h / 2)
            if (sum <= 0) return
            c.save()
            c.clipPath(Path().apply { addRoundRect(RectF(x, top, x + w, top + h), h / 2, h / 2, Path.Direction.CW) })
            var at = 0f
            for ((v, col) in parts) {
                val pw = (v / sum * w).toFloat()
                val l = if (rtl) x + w - at - pw else x + at
                c.drawRect(l, top, l + pw + 0.5f, top + h, Paint().apply { color = col })
                at += pw
            }
            c.restore()
        }

        /** The total over time: a soft area under a blue line, the first and last amounts and dates. Time runs left to right. */
        fun chart(x: Float, top: Float, w: Float, h: Float, pts: List<Pair<String, Double>>) {
            round(x, top, x + w, top + h, Color.WHITE, 8f, stroke = LINE)
            val days = pts.map { try { java.time.LocalDate.parse(it.first).toEpochDay().toFloat() } catch (_: Exception) { 0f } }
            val d0 = days.first(); val d1 = days.last().takeIf { it > d0 } ?: (d0 + 1)
            val max = pts.maxOf { it.second }.coerceAtLeast(1.0)
            val px = x + 14; val pw = w - 28; val py = top + 26; val ph = h - 40
            fun X(i: Int) = px + (days[i] - d0) / (d1 - d0) * pw
            fun Y(v: Double) = py + ph - (v / max * ph).toFloat()
            for (g in 0..2) c.drawLine(px, py + ph * g / 2, px + pw, py + ph * g / 2, Paint().apply { color = LINE; strokeWidth = 0.5f })
            val line = Path(); val area = Path()
            pts.forEachIndexed { i, (_, v) -> if (i == 0) { line.moveTo(X(i), Y(v)); area.moveTo(X(i), py + ph); area.lineTo(X(i), Y(v)) } else { line.lineTo(X(i), Y(v)); area.lineTo(X(i), Y(v)) } }
            area.lineTo(X(pts.size - 1), py + ph); area.close()
            c.drawPath(area, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(40, 0x1A, 0x6F, 0xCC) })
            c.drawPath(line, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BLUE; style = Paint.Style.STROKE; strokeWidth = 1.8f; strokeJoin = Paint.Join.ROUND })
            pts.indices.forEach { i -> c.drawCircle(X(i), Y(pts[i].second), if (i == pts.size - 1) 3.2f else 2.2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BLUE }) }
            // The first and the last point, labelled in the chart's own left-to-right frame.
            val lab = paint(8f, SOFT, false); val big = paint(9f, INK, true)
            fun at(s: String, xx: Float, yy: Float, p: TextPaint, end: Boolean) = c.drawText(s, if (end) xx - p.measureText(s) else xx, yy, p)
            at(FamilyItems.money(Math.rint(pts.first().second)), px, top + 15, big, false)
            at(FamilyItems.money(Math.rint(pts.last().second)), px + pw, top + 15, big, true)
            at(FamilyItems.short(pts.first().first), px, top + h - 4, lab, false)
            at(L.t("היום", "today"), px + pw, top + h - 4, lab, true)
        }
    }
}
