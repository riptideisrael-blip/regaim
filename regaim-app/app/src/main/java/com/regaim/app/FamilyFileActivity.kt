package com.regaim.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/**
 * "תיק המשפחה": receipts, documents, coupons, vouchers and gifts, on this phone (encrypted). What's about to expire
 * first, then the year's receipts with their total, then the rest. Filter by child.
 */
class FamilyFileActivity : AppCompatActivity() {
    companion object {
        private const val EXTRA_CHILD = "child"
        fun open(ctx: Context, child: String = "") = ctx.startActivity(Intent(ctx, FamilyFileActivity::class.java).putExtra(EXTRA_CHILD, child))
    }

    private lateinit var ui: Ui
    private lateinit var content: LinearLayout
    private var child = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_screen)
        findViewById<View>(R.id.btnClose).setOnClickListener { finish() }
        findViewById<TextView>(R.id.screenTitle).text = L.t("תיק המשפחה", "Family file")
        content = findViewById(R.id.screenContent)
        ui = Ui(this)
        child = intent.getStringExtra(EXTRA_CHILD).orEmpty()
    }

    override fun onResume() { super.onResume(); render() }

    private fun render() {
        content.removeAllViews()
        val kids = Profile.members(this).filter { it.isChild }
        val names = kids.associate { it.id to it.name }
        val today = java.time.LocalDate.now().toString()
        val year = java.time.LocalDate.now().year
        val all = FamilyFile.all(this)
        val shown = if (child.isEmpty()) all else all.filter { it.child == child }

        content.addView(ui.button(L.t("+ הוספה לתיק", "+ Add to the file"), primary = true, top = 12) { FamilyItemActivity.pick(this, child) })
        content.addView(ui.hint(L.t("אפשר גם לשתף לכאן מכל אפליקציה (וואטסאפ, מייל, גלריה): שתף ← Moments · תיק המשפחה.", "You can also share here from any app (WhatsApp, email, gallery): Share → Moments · family file."), top = 6))

        if (kids.isNotEmpty()) {
            val labels = listOf(L.t("כולם", "Everyone")) + kids.map { it.name }
            val sel = kids.indexOfFirst { it.id == child }.let { if (it < 0) 0 else it + 1 }
            content.addView(ui.chips(labels, sel, top = 12) { i -> child = if (i == 0) "" else kids[i - 1].id; render() })
        }

        if (shown.isEmpty()) {
            content.addView(ui.card(top = 16).apply {
                addView(ui.text(L.t("התיק עוד ריק", "The file is empty"), 17f, bold = true))
                addView(ui.hint(L.t("שתפו לכאן קבלה מהגן, אישור, קופון או שובר. הכול נשמר רק בטלפון הזה, מוצפן.", "Share a kindergarten receipt, a form, a coupon or a voucher here. Everything stays on this phone, encrypted."), top = 4))
            })
            return
        }

        val soon = FamilyItems.expiringSoon(shown, today)
        if (soon.isNotEmpty()) {
            content.addView(ui.section(L.t("דורש תשומת לב", "Needs attention")))
            val card = ui.card(top = 8)
            soon.forEach { card.addView(row(it, names, today)) }
            content.addView(card)
        }

        val receipts = shown.filter { it.kind == FamilyItems.Kind.RECEIPT }
        if (receipts.isNotEmpty()) {
            content.addView(ui.section(L.t("קבלות", "Receipts")))
            val total = FamilyItems.receiptTotal(shown, year, child.ifEmpty { null })
            if (total > 0) content.addView(ui.text(L.t("סך הכול $year: ${FamilyItems.money(total)}", "Total $year: ${FamilyItems.money(total)}"), 16f, bold = true, top = 4))
            val card = ui.card(top = 8)
            receipts.forEach { card.addView(row(it, names, today)) }
            content.addView(card)
        }

        val wallet = shown.filter { it.kind == FamilyItems.Kind.COUPON || it.kind == FamilyItems.Kind.VOUCHER || it.kind == FamilyItems.Kind.GIFT }
        if (wallet.isNotEmpty()) {
            content.addView(ui.section(L.t("קופונים, שוברים ומתנות", "Coupons, vouchers and gifts")))
            val card = ui.card(top = 8)
            wallet.sortedBy { it.expires.ifEmpty { "9999" } }.forEach { card.addView(row(it, names, today)) }
            content.addView(card)
        }

        val docs = shown.filter { it.kind == FamilyItems.Kind.DOCUMENT }
        if (docs.isNotEmpty()) {
            content.addView(ui.section(L.t("מסמכים", "Documents")))
            val card = ui.card(top = 8)
            docs.forEach { card.addView(row(it, names, today)) }
            content.addView(card)
        }

        content.addView(ui.action(L.t("מחיקת כל התיק", "Delete the whole file"), R.color.ds_error) {
            AlertDialog.Builder(this)
                .setTitle(L.t("למחוק את כל התיק?", "Delete the whole file?"))
                .setMessage(L.t("כל הפריטים והקבצים יימחקו מהטלפון. אי אפשר לשחזר.", "All items and files will be deleted from the phone. This can't be undone."))
                .setPositiveButton(L.t("מחיקה", "Delete")) { _, _ -> FamilyFile.deleteAll(this); render() }
                .setNegativeButton(L.t("ביטול", "Cancel"), null).show()
        })
        content.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, ui.dp(24)) })
    }

    private fun row(i: FamilyItems.Item, names: Map<String, String>, today: String): View {
        val title = i.title.ifBlank { i.vendor }.ifBlank { i.kind.label }
        val parts = listOfNotNull(
            i.kind.label,
            names[i.child],
            i.vendor.takeIf { it.isNotBlank() && it != title },
            FamilyItems.expiryLine(i, today).takeIf { it.isNotEmpty() } ?: i.date.takeIf { it.isNotEmpty() }?.let { FamilyItems.short(it) },
        )
        val value = (i.balance ?: i.amount)?.let { FamilyItems.money(it) }.orEmpty()
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, ui.dp(10), 0, ui.dp(10))
            minimumHeight = ui.dp(48)
            isClickable = true
            background = android.util.TypedValue().let { tv -> theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true); getDrawable(tv.resourceId) }
            addView(ui.horizontal(top = 0).apply {
                addView(ui.text(title, 15f, bold = true).apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
                if (value.isNotEmpty()) addView(ui.text(value, 15f, bold = true).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT) })
            })
            addView(ui.hint(parts.joinToString(" · "), top = 2))
            contentDescription = (listOf(title) + parts + value).filter { it.isNotBlank() }.joinToString(", ")
            setOnClickListener { FamilyItemActivity.open(this@FamilyFileActivity, i.id) }
        }
    }
}
