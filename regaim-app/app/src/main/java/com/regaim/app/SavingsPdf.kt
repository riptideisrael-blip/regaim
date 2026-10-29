package com.regaim.app

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.File

/**
 * The savings on one A4 page (more pages when it doesn't fit) to send or print: the total, each person and each
 * fund with today's estimate, its last report and its monthly deposit, and where the numbers come from. Made on the
 * phone, written to the share folder (replaced next time); nothing is sent anywhere until the parent shares it.
 */
object SavingsPdf {
    private const val W = 595; private const val H = 842; private const val M = 40

    fun write(ctx: Context, funds: List<Savings.Fund>, people: List<FamilyMember>, today: String, est: (Savings.Fund) -> Savings.Estimate?): File {
        val doc = PdfDocument()
        var pageNo = 0
        var page: PdfDocument.Page? = null
        var y = 0f
        val ink = Color.rgb(0x2E, 0x26, 0x20); val soft = Color.rgb(0x67, 0x5C, 0x54); val line = Color.rgb(0xE8, 0xDE, 0xD3)
        val rtl = L.t("א", "a") == "א"

        fun newPage() {
            page?.let { doc.finishPage(it) }
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, pageNo).create())
            y = M.toFloat()
        }

        fun text(s: String, size: Float, color: Int = ink, bold: Boolean = false, after: Float = 4f, align: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL) {
            val p = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply { textSize = size; this.color = color; typeface = Typeface.create(Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL) }
            val l = StaticLayout.Builder.obtain(s, 0, s.length, p, W - 2 * M).setAlignment(align)
                .setTextDirection(if (rtl) android.text.TextDirectionHeuristics.FIRSTSTRONG_RTL else android.text.TextDirectionHeuristics.FIRSTSTRONG_LTR).build()
            if (y + l.height > H - M) newPage()
            val c = page!!.canvas
            c.save(); c.translate(M.toFloat(), y); l.draw(c); c.restore()
            y += l.height + after
        }

        fun rule() {
            if (y + 10 > H - M) newPage()
            page!!.canvas.drawLine(M.toFloat(), y + 4, (W - M).toFloat(), y + 4, android.graphics.Paint().apply { color = line; strokeWidth = 1f })
            y += 12
        }

        fun ils(v: Double) = "⁨" + FamilyItems.money(Math.rint(v)) + "⁩"

        newPage()
        val total = funds.sumOf { est(it)?.total ?: 0.0 }
        text(L.t("חיסכון והשקעות של המשפחה", "The family's savings and investments"), 20f, bold = true, after = 2f)
        text(L.t("נכון ל-${FamilyItems.short(today)} · הערכה", "As of ${FamilyItems.short(today)} · estimate"), 11f, soft, after = 12f)
        text(L.t("יש היום: ${ils(total)}", "Today: ${ils(total)}"), 16f, bold = true, after = 2f)
        text(L.t("הפקדה חודשית: ${ils(funds.sumOf { it.perMonth })}", "Each month: ${ils(funds.sumOf { it.perMonth })}"), 12f, soft, after = 10f)
        rule()

        for (k in people) {
            val mine = funds.filter { it.child == k.id }
            if (mine.isEmpty()) continue
            text("${k.name} · ${ils(mine.sumOf { est(it)?.total ?: 0.0 })}", 15f, bold = true, after = 6f)
            for (f in mine) {
                val e = est(f)
                text("${f.title} · ${e?.let { ils(it.total) } ?: "—"}", 12.5f, after = 1f)
                val meta = listOfNotNull(
                    f.kind.label,
                    f.lastReport?.let { L.t("דוח ${FamilyItems.short(it.date)}: ${ils(it.balance)}", "report ${FamilyItems.short(it.date)}: ${ils(it.balance)}") },
                    if (f.perMonth > 0) L.t("+${ils(f.perMonth)} בחודש", "+${ils(f.perMonth)} a month") else null,
                    if (f.kind == Savings.Kind.CRYPTO) L.t("לפי מחיר המטבעות", "at the coins' price")
                    else if (f.gemelId > 0) L.t("לפי התשואות בגמל נט", "at Gemel-Net's returns") else L.t("יתרה + הפקדות", "balance + deposits"),
                )
                text(meta.joinToString(" · ") { "⁨$it⁩" }, 10.5f, soft, after = 7f)
            }
            rule()
        }
        text(L.t("הערכה בלבד: היתרה בדוח האחרון של כל קופה, לפי התשואות החודשיות הרשמיות (גמל נט, משרד האוצר) או מחיר המטבעות, ועוד ההפקדות מאז. הסכום המדויק מופיע בדוח הבא של הקופה. מידע בלבד, לא המלצה. הוכן באפליקציית Moments.",
            "An estimate only: each fund's last report, at the official monthly returns (Gemel-Net, Ministry of Finance) or the coins' price, plus the deposits since. The exact amount is in the fund's next report. Information only, not advice. Made with Moments."), 9.5f, soft)

        page?.let { doc.finishPage(it) }
        val out = File(File(ctx.cacheDir, "share").apply { mkdirs() }, "savings_summary.pdf")
        out.outputStream().use { doc.writeTo(it) }
        doc.close()
        return out
    }
}
