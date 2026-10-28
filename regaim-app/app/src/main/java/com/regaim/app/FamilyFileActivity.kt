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
    /** Chains opened in the wallet (and "" for the expired ones). */
    private val open = mutableSetOf<String>()

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
        // Coupons from a spreadsheet with nothing to redeem (no code, no link) aren't shown.
        val all = FamilyFile.all(this).filterNot { CouponImport.noCode(it) }
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

        // The family wallet: what's still valid, chain by chain; the expired ones folded away at the end.
        val walletAll = shown.filter { FamilyItems.isWallet(it) }
        if (walletAll.isNotEmpty()) walletSection(walletAll, names, today)

        val docs = shown.filter { it.kind == FamilyItems.Kind.DOCUMENT }
        if (docs.isNotEmpty()) {
            content.addView(header(L.t("מסמכים", "Documents"), ""))
            content.addView(list(docs, names, today))
        }

        addButtons()
    }

    private fun addButtons() {
        content.addView(ui.button(L.t("+ הוספה לתיק", "+ Add to the file"), primary = true, top = 20) { FamilyItemActivity.pick(this, child) })
        content.addView(ui.button(L.t("ייבוא קופונים מקובץ Excel", "Import coupons from an Excel file"), top = 8) { FamilyImportActivity.open(this) })
        content.addView(ui.hint(L.t("אפשר גם לשתף לכאן מכל אפליקציה (וואטסאפ, מייל, גלריה): שתף ← Moments · תיק המשפחה.", "You can also share here from any app (WhatsApp, email, gallery): Share → Moments · family file."), top = 6))
        val hidden = FamilyFile.all(this).filter { CouponImport.noCode(it) }
        if (hidden.isNotEmpty()) content.addView(ui.action(L.t("ניקוי ${hidden.size} שורות בלי קוד שיובאו מ-Excel", "Clear ${hidden.size} imported rows without a code"), R.color.ink_soft) {
            AlertDialog.Builder(this)
                .setTitle(L.t("לנקות ${hidden.size} שורות בלי קוד?", "Clear ${hidden.size} rows without a code?"))
                .setMessage(L.t("אלה שורות מהקובץ שאין בהן קוד או קישור למימוש. הן לא מוצגות בתיק, וכך יימחקו גם מהטלפון.", "These are rows from the file with no code or link to redeem. They aren't shown, and this deletes them from the phone too."))
                .setPositiveButton(L.t("ניקוי", "Clear")) { _, _ -> hidden.forEach { FamilyFile.delete(this, it.id) }; render() }
                .setNegativeButton(L.t("ביטול", "Cancel"), null).show()
        })
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
                    textDirection = View.TEXT_DIRECTION_FIRST_STRONG
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

    /** A soft colour per chain name (stable), from the app's warm palette. */
    private fun chainTint(name: String): Pair<Int, Int> {
        val all = TINTS.values.toList()
        return all[Math.floorMod(name.hashCode(), all.size)]
    }

    private fun walletSection(items: List<FamilyItems.Item>, names: Map<String, String>, today: String) {
        val chains = FamilyItems.wallet(items, today)
        val expired = items.filter { FamilyItems.expired(it, today) }
        val total = chains.sumOf { it.total }
        val count = chains.sumOf { it.items.size }
        val soon = chains.flatMap { it.items }.count { FamilyItems.daysUntil(it.expires, today)?.let { d -> d in 0..60 } == true }

        content.addView(header(L.t("הארנק המשפחתי", "The family wallet"), ""))
        // The headline: what the family can still use.
        content.addView(ui.card(top = 8).apply {
            setPadding(ui.dp(20), ui.dp(18), ui.dp(20), ui.dp(18))
            background = DS.rounded(0xFF6F4E37.toInt(), ui.dp(22).toFloat())
            addView(TextView(this@FamilyFileActivity).apply {
                text = L.t("שווי שאפשר לממש", "Value you can still use"); textSize = 13f; setTextColor(0xCCFFFFFF.toInt())
            })
            addView(amount(FamilyItems.money(total), 30f, 0xFFFFFFFF.toInt()).apply { setPadding(0, ui.dp(2), 0, 0) })
            addView(TextView(this@FamilyFileActivity).apply {
                text = listOfNotNull(
                    L.t("$count שוברים וקופונים · ${chains.size} רשתות", "$count vouchers and coupons · ${chains.size} chains"),
                    if (soon > 0) L.t("⏳ $soon פגים בחודשיים הקרובים", "⏳ $soon expire in the next two months") else null,
                ).joinToString("\n")
                textSize = 13f; setTextColor(0xE6FFFFFF.toInt()); setPadding(0, ui.dp(6), 0, 0)
            })
        })

        for (c in chains) {
            val isOpen = c.name in open
            val (bg, ink) = chainTint(c.name)
            val card = ui.card(top = 10)
            card.setPadding(ui.dp(14), ui.dp(4), ui.dp(14), ui.dp(4))
            card.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(0, ui.dp(12), 0, ui.dp(12)); minimumHeight = ui.dp(64)
                background = android.util.TypedValue().let { tv -> theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true); getDrawable(tv.resourceId) }
                addView(TextView(this@FamilyFileActivity).apply {
                    text = c.name.trim().take(1).uppercase(); textSize = 18f; gravity = Gravity.CENTER; setTextColor(ink); typeface = DS.emphasis
                    background = DS.rounded(bg, ui.dp(14).toFloat())
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    layoutParams = LinearLayout.LayoutParams(ui.dp(46), ui.dp(46)).apply { marginEnd = ui.dp(12) }
                })
                val sub = listOfNotNull(
                    if (c.items.size == 1) L.t("שובר אחד", "1 voucher") else L.t("${c.items.size} שוברים", "${c.items.size} vouchers"),
                    c.nearest.takeIf { it.isNotEmpty() }?.let { L.t("הקרוב פג ", "next expires ") + FamilyItems.short(it) },
                )
                addView(LinearLayout(this@FamilyFileActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = ui.dp(10) }
                    addView(TextView(this@FamilyFileActivity).apply {
                        text = c.name; textSize = 16f; setTextColor(DS.ink(this@FamilyFileActivity)); typeface = DS.emphasis
                        maxLines = 1; ellipsize = TextUtils.TruncateAt.END; textDirection = View.TEXT_DIRECTION_FIRST_STRONG
                    })
                    addView(TextView(this@FamilyFileActivity).apply {
                        text = sub.joinToString(" · "); textSize = 13f; setTextColor(DS.inkSoft(this@FamilyFileActivity)); setPadding(0, ui.dp(2), 0, 0)
                    })
                })
                if (c.total > 0) addView(amount(FamilyItems.money(c.total), 16f, DS.ink(this@FamilyFileActivity)))
                addView(TextView(this@FamilyFileActivity).apply {
                    text = if (isOpen) "⌃" else "⌄"; textSize = 16f; setTextColor(DS.inkSoft(this@FamilyFileActivity))
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = ui.dp(8) }
                })
                contentDescription = (listOf(c.name) + sub + listOfNotNull(c.total.takeIf { it > 0 }?.let { FamilyItems.money(it) }) +
                    (if (isOpen) L.t("פתוח", "Open") else L.t("סגור", "Closed"))).joinToString(", ")
                setOnClickListener { toggle(c.name) }
            })
            if (isOpen) c.items.forEach { i -> card.addView(divider(52)); card.addView(voucherRow(i, today)) }
            content.addView(card)
        }

        if (expired.isNotEmpty()) {
            val isOpen = "" in open
            content.addView(ui.action((if (isOpen) "⌃ " else "⌄ ") + L.t("פג תוקף · ${expired.size}", "Expired · ${expired.size}"), R.color.ink_soft) { toggle("") })
            if (isOpen) content.addView(list(expired.sortedByDescending { it.expires }, names, today))
        }
    }

    private fun toggle(key: String) {
        if (key in open) open -= key else open += key
        val sv = content.parent as? View
        val y = sv?.scrollY ?: 0
        render()
        sv?.post { sv.scrollTo(0, y) }
    }

    private fun divider(start: Int) = View(this).apply {
        setBackgroundColor(0xFFE8DED3.toInt())
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply { marginStart = ui.dp(start) }
    }

    /** One voucher inside its chain: its code (last digits only), when it expires, what it's worth. */
    private fun voucherRow(i: FamilyItems.Item, today: String): View {
        val title = i.code.takeIf { it.isNotEmpty() }?.let { FamilyItems.masked(it) }
            ?: if (FamilyItems.link(i.note).isNotEmpty()) L.t("שובר דיגיטלי (קישור)", "Digital voucher (link)") else i.kind.label
        val days = FamilyItems.daysUntil(i.expires, today)
        val meta = listOfNotNull(
            FamilyItems.expiryLine(i, today).takeIf { it.isNotEmpty() } ?: L.t("בלי תאריך תפוגה", "No expiry date"),
            if (i.file.isNotEmpty()) L.t("📷 יש תמונה", "📷 Has a photo") else null,
        )
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(58), ui.dp(10), 0, ui.dp(10)); minimumHeight = ui.dp(52)
            background = android.util.TypedValue().let { tv -> theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true); getDrawable(tv.resourceId) }
            addView(LinearLayout(this@FamilyFileActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = ui.dp(10) }
                addView(TextView(this@FamilyFileActivity).apply {
                    text = title; textSize = 15f; setTextColor(DS.ink(this@FamilyFileActivity))
                    textDirection = View.TEXT_DIRECTION_FIRST_STRONG; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                })
                addView(TextView(this@FamilyFileActivity).apply {
                    text = meta.joinToString(" · "); textSize = 12.5f
                    setTextColor(if (days != null && days in 0..60) 0xFFA4442F.toInt() else DS.inkSoft(this@FamilyFileActivity))
                    setPadding(0, ui.dp(2), 0, 0)
                })
            })
            val w = FamilyItems.worth(i)
            if (w > 0) addView(amount(FamilyItems.money(w), 15f, DS.ink(this@FamilyFileActivity)))
            contentDescription = (listOf(title) + meta + listOfNotNull(w.takeIf { it > 0 }?.let { FamilyItems.money(it) })).joinToString(", ")
            setOnClickListener { FamilyItemActivity.open(this@FamilyFileActivity, i.id) }
        }
    }
}
