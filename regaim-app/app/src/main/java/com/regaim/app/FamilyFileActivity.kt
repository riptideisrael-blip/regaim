package com.regaim.app

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/**
 * "תיק המשפחה": receipts, documents, coupons, vouchers and gifts, on this phone (encrypted).
 * A summary first (this year, this month, where the money went), then what's about to expire, receipts by month,
 * coupons and vouchers, and documents. Filter by child and by category.
 */
class FamilyFileActivity : AppCompatActivity() {
    companion object {
        private const val EXTRA_CHILD = "child"
        fun open(ctx: Context, child: String = "") = ctx.startActivity(Intent(ctx, FamilyFileActivity::class.java).putExtra(EXTRA_CHILD, child))

        /** A soft background and a darker ink per category (the app's warm palette, told apart by lightness too). */
        private val TINTS = mapOf(
            "daycare" to (0xFFF3E3D3.toInt() to 0xFF7A4E2D.toInt()),
            "classes" to (0xFFE6EEDD.toInt() to 0xFF4A643D.toInt()),
            "health" to (0xFFF6E3DC.toInt() to 0xFFA4442F.toInt()),
            "vacation" to (0xFFDDEBF0.toInt() to 0xFF2F5F73.toInt()),
            "phone" to (0xFFE8E4F1.toInt() to 0xFF5A4B80.toInt()),
            "clothes" to (0xFFF4E9D6.toInt() to 0xFF8A6420.toInt()),
            "equipment" to (0xFFE9E5DF.toInt() to 0xFF5C534B.toInt()),
            "gifts" to (0xFFF5E1E8.toInt() to 0xFF8E3B5C.toInt()),
            "home" to (0xFFE1ECE9.toInt() to 0xFF35645A.toInt()),
            "other" to (0xFFEDE5DC.toInt() to 0xFF675C54.toInt()),
        )
        private val KIND_TINTS = mapOf(
            FamilyItems.Kind.COUPON to (0xFFF4E9D6.toInt() to 0xFF8A6420.toInt()),
            FamilyItems.Kind.VOUCHER to (0xFFF5E1E8.toInt() to 0xFF8E3B5C.toInt()),
            FamilyItems.Kind.GIFT to (0xFFF5E1E8.toInt() to 0xFF8E3B5C.toInt()),
            FamilyItems.Kind.DOCUMENT to (0xFFE9E5DF.toInt() to 0xFF5C534B.toInt()),
            FamilyItems.Kind.RECEIPT to (0xFFEDE5DC.toInt() to 0xFF675C54.toInt()),
        )
    }

    private lateinit var ui: Ui
    private lateinit var content: LinearLayout
    private var child = ""
    /** A category filter ("" = all). */
    private var category = ""

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

    private fun tint(i: FamilyItems.Item): Pair<Int, Int> =
        FamilyItems.Category.of(i.category)?.let { TINTS[it.id] } ?: KIND_TINTS[i.kind] ?: (0xFFEDE5DC.toInt() to 0xFF675C54.toInt())

    private fun render() {
        content.removeAllViews()
        val kids = Profile.members(this).filter { it.isChild }
        val names = kids.associate { it.id to it.name }
        val now = java.time.LocalDate.now()
        val today = now.toString()
        val year = now.year
        val thisMonth = today.substring(0, 7)
        val all = FamilyFile.all(this)
        val byChild = if (child.isEmpty()) all else all.filter { it.child == child }
        val shown = if (category.isEmpty()) byChild else byChild.filter { it.category == category }

        // Filters: whose, and (only categories that have something) what for.
        if (kids.isNotEmpty()) {
            val labels = listOf(L.t("כולם", "Everyone")) + kids.map { it.name }
            val sel = kids.indexOfFirst { it.id == child }.let { if (it < 0) 0 else it + 1 }
            content.addView(ui.chips(labels, sel, top = 12) { i -> child = if (i == 0) "" else kids[i - 1].id; render() })
        }
        val cats = FamilyItems.Category.values().filter { c -> byChild.any { it.category == c.id } }
        if (cats.size > 1) {
            val labels = listOf(L.t("כל הקטגוריות", "All categories")) + cats.map { it.label }
            val sel = cats.indexOfFirst { it.id == category }.let { if (it < 0) 0 else it + 1 }
            content.addView(ui.chips(labels, sel, top = 6) { i -> category = if (i == 0) "" else cats[i - 1].id; render() })
        }

        if (shown.isEmpty()) {
            content.addView(ui.card(top = 16).apply {
                addView(ui.text(L.t("התיק עוד ריק", "The file is empty"), 17f, bold = true))
                addView(ui.hint(L.t("שתפו לכאן קבלה מהגן, אישור, קופון או שובר. הכול נשמר רק בטלפון הזה, מוצפן.", "Share a kindergarten receipt, a form, a coupon or a voucher here. Everything stays on this phone, encrypted."), top = 4))
            })
            addButtons()
            return
        }

        // Summary: this year, this month, and where it went.
        val yearTotal = FamilyItems.receiptTotal(shown, year, null)
        if (yearTotal > 0) content.addView(summary(shown, year, thisMonth, yearTotal))

        val soon = FamilyItems.expiringSoon(shown, today)
        if (soon.isNotEmpty()) {
            content.addView(header(L.t("דורש תשומת לב", "Needs attention"), ""))
            content.addView(list(soon, names, today))
        }

        val receipts = shown.filter { it.kind == FamilyItems.Kind.RECEIPT }
        for ((ym, items) in FamilyItems.byMonth(receipts)) {
            val sum = items.sumOf { it.amount ?: 0.0 }
            content.addView(header(FamilyItems.monthLabel(ym), if (sum > 0) FamilyItems.money(sum) else ""))
            content.addView(list(items, names, today))
        }

        val wallet = shown.filter { it.kind == FamilyItems.Kind.COUPON || it.kind == FamilyItems.Kind.VOUCHER || it.kind == FamilyItems.Kind.GIFT }
        if (wallet.isNotEmpty()) {
            content.addView(header(L.t("קופונים, שוברים ומתנות", "Coupons, vouchers and gifts"), ""))
            content.addView(list(wallet.sortedBy { it.expires.ifEmpty { "9999" } }, names, today))
        }

        val docs = shown.filter { it.kind == FamilyItems.Kind.DOCUMENT }
        if (docs.isNotEmpty()) {
            content.addView(header(L.t("מסמכים", "Documents"), ""))
            content.addView(list(docs, names, today))
        }

        addButtons()
    }

    private fun addButtons() {
        content.addView(ui.button(L.t("+ הוספה לתיק", "+ Add to the file"), primary = true, top = 20) { FamilyItemActivity.pick(this, child) })
        content.addView(ui.hint(L.t("אפשר גם לשתף לכאן מכל אפליקציה (וואטסאפ, מייל, גלריה): שתף ← Moments · תיק המשפחה.", "You can also share here from any app (WhatsApp, email, gallery): Share → Moments · family file."), top = 6))
        if (FamilyFile.all(this).isNotEmpty()) content.addView(ui.action(L.t("מחיקת כל התיק", "Delete the whole file"), R.color.ds_error) {
            AlertDialog.Builder(this)
                .setTitle(L.t("למחוק את כל התיק?", "Delete the whole file?"))
                .setMessage(L.t("כל הפריטים והקבצים יימחקו מהטלפון. אי אפשר לשחזר.", "All items and files will be deleted from the phone. This can't be undone."))
                .setPositiveButton(L.t("מחיקה", "Delete")) { _, _ -> FamilyFile.deleteAll(this); render() }
                .setNegativeButton(L.t("ביטול", "Cancel"), null).show()
        })
        content.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, ui.dp(24)) })
    }

    /** The year's total, this month's, and a bar of where the money went (each category its own colour). */
    private fun summary(items: List<FamilyItems.Item>, year: Int, ym: String, yearTotal: Double): View {
        val card = ui.card(top = 14)
        card.addView(ui.text(L.t("קבלות $year", "Receipts $year"), 13f, R.color.ink_soft))
        card.addView(amount(FamilyItems.money(yearTotal), 28f, DS.ink(this)).apply { gravity = Gravity.START })
        val month = FamilyItems.monthTotal(items, ym)
        card.addView(ui.text(L.t("החודש: ${FamilyItems.money(month)}", "This month: ${FamilyItems.money(month)}"), 14f, R.color.ink_soft, top = 2))

        val parts = FamilyItems.totalsByCategory(items, year, null).filter { it.second > 0 }
        if (parts.size > 1 || parts.firstOrNull()?.first != null) {
            val bar = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(10)).apply { topMargin = ui.dp(14) }
                background = DS.rounded(DS.cardAlt(this@FamilyFileActivity), ui.dp(5).toFloat())
                clipToOutline = true
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            parts.forEach { (c, v) ->
                bar.addView(View(this).apply {
                    setBackgroundColor((c?.let { TINTS[it.id] } ?: TINTS["other"]!!).second)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, (v / yearTotal).toFloat())
                })
            }
            card.addView(bar)
            for ((c, v) in parts.take(5)) {
                val (_, ink) = c?.let { TINTS[it.id] } ?: TINTS["other"]!!
                card.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.dp(8) }
                    addView(View(this@FamilyFileActivity).apply {
                        background = DS.rounded(ink, ui.dp(5).toFloat())
                        layoutParams = LinearLayout.LayoutParams(ui.dp(10), ui.dp(10)).apply { marginEnd = ui.dp(10) }
                    })
                    addView(ui.text(c?.label ?: L.t("בלי קטגוריה", "No category"), 14f).apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) })
                    addView(ui.text("${Math.round(v / yearTotal * 100)}%", 13f, R.color.ink_soft).apply {
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = ui.dp(12) }
                    })
                    addView(amount(FamilyItems.money(v), 14f, DS.ink(this@FamilyFileActivity)))
                })
            }
        }
        return card
    }

    /** A section title with an optional total on the other side. */
    private fun header(title: String, total: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.dp(24) }
        addView(ui.text(title, 18f, bold = true).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            androidx.core.view.ViewCompat.setAccessibilityHeading(this, true)
        })
        if (total.isNotEmpty()) addView(amount(total, 15f, DS.inkSoft(this@FamilyFileActivity)))
    }

    /** Items in one card, with a thin line between them. */
    private fun list(items: List<FamilyItems.Item>, names: Map<String, String>, today: String): View {
        val card = ui.card(top = 8)
        card.setPadding(ui.dp(14), ui.dp(4), ui.dp(14), ui.dp(4))
        items.forEachIndexed { n, i ->
            if (n > 0) card.addView(View(this).apply {
                setBackgroundColor(0xFFE8DED3.toInt())
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply { marginStart = ui.dp(52) }
            })
            card.addView(row(i, names, today))
        }
        return card
    }

    /** An amount, always left-to-right (so "3,495 ₪" never runs into a Hebrew or English title). */
    private fun amount(text: String, size: Float, color: Int) = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(color); typeface = Typeface.create(DS.emphasis, Typeface.BOLD)
        textDirection = View.TEXT_DIRECTION_LTR; maxLines = 1
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun row(i: FamilyItems.Item, names: Map<String, String>, today: String): View {
        val (bg, ink) = tint(i)
        val cat = FamilyItems.Category.of(i.category)
        val title = i.title.ifBlank { i.vendor }.ifBlank { cat?.label ?: i.kind.label }
        val meta = listOfNotNull(
            if (i.kind == FamilyItems.Kind.RECEIPT || i.kind == FamilyItems.Kind.DOCUMENT) cat?.label else i.kind.label,
            i.vendor.takeIf { it.isNotBlank() && it != title },
            names[i.child],
            FamilyItems.expiryLine(i, today).takeIf { it.isNotEmpty() } ?: i.date.takeIf { it.isNotEmpty() }?.let { FamilyItems.short(it) },
        )
        val value = (i.balance ?: i.amount)?.let { FamilyItems.money(it) }.orEmpty()
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, ui.dp(12), 0, ui.dp(12))
            minimumHeight = ui.dp(56)
            isClickable = true
            background = android.util.TypedValue().let { tv -> theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true); getDrawable(tv.resourceId) }
            // The category's initial in its colour.
            addView(TextView(this@FamilyFileActivity).apply {
                text = (cat?.label ?: i.kind.label).take(1)
                textSize = 15f; gravity = Gravity.CENTER; setTextColor(ink); typeface = DS.emphasis
                background = DS.rounded(bg, ui.dp(12).toFloat())
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                layoutParams = LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)).apply { marginEnd = ui.dp(12) }
            })
            addView(LinearLayout(this@FamilyFileActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = ui.dp(10) }
                addView(TextView(this@FamilyFileActivity).apply {
                    text = title; textSize = 15f; setTextColor(DS.ink(this@FamilyFileActivity)); typeface = DS.emphasis
                    maxLines = 2; ellipsize = TextUtils.TruncateAt.END
                    textDirection = View.TEXT_DIRECTION_FIRST_STRONG_LOCALE
                })
                addView(TextView(this@FamilyFileActivity).apply {
                    text = meta.joinToString(" · "); textSize = 13f; setTextColor(DS.inkSoft(this@FamilyFileActivity))
                    maxLines = 2; ellipsize = TextUtils.TruncateAt.END
                    setPadding(0, ui.dp(2), 0, 0)
                })
            })
            if (value.isNotEmpty()) addView(amount(value, 15f, DS.ink(this@FamilyFileActivity)))
            contentDescription = (listOf(title) + meta + value).filter { it.isNotBlank() }.joinToString(", ")
            setOnClickListener { FamilyItemActivity.open(this@FamilyFileActivity, i.id) }
        }
    }
}
