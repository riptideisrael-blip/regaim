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
        fun open(ctx: Context, child: String = "") = ctx.startActivity(Intent(ctx, FamilyFileActivity::class.java).putExtra(EXTRA_CHILD, child).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))

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
            FamilyItems.Kind.CREDIT to (0xFFE1ECE9.toInt() to 0xFF35645A.toInt()),
            FamilyItems.Kind.WARRANTY to (0xFFDDEBF0.toInt() to 0xFF2F5F73.toInt()),
            FamilyItems.Kind.EXCHANGE to (0xFFE8E4F1.toInt() to 0xFF5A4B80.toInt()),
            FamilyItems.Kind.DOCUMENT to (0xFFE9E5DF.toInt() to 0xFF5C534B.toInt()),
            FamilyItems.Kind.RECEIPT to (0xFFEDE5DC.toInt() to 0xFF675C54.toInt()),
        )
    }

    private lateinit var ui: Ui
    private lateinit var content: LinearLayout
    private var child = ""
    /** A category filter ("" = all). */
    private var category = ""
    /** The overview tile chosen (wallet, receipts…; null = everything). */
    private var tile: FamilyItems.Tile? = null
    /** The search box's words ("" = not searching), and the smart search's answer for them. */
    private var query = ""
    private var smart: Pair<String, List<String>>? = null
    private var smartFor = ""
    private var smartBusy = false
    private lateinit var scroller: View
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
    /** Chains opened in the wallet (and "" for the expired ones). */
    private val open = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_screen)
        findViewById<View>(R.id.btnClose).setOnClickListener { finish() }
        findViewById<TextView>(R.id.screenTitle).text = L.t("תיק המשפחה", "Family file")
        // A tab of its own: the tab bar instead of "Close".
        findViewById<View>(R.id.btnClose).visibility = View.GONE
        findViewById<View>(R.id.bottomNav).visibility = View.VISIBLE
        Nav.setup(this, Nav.Tab.FILE)
        Nav.addProfileButton(this, findViewById<View>(R.id.screenTitle).parent as ViewGroup)
        val screen = findViewById<LinearLayout>(R.id.screenContent)
        scroller = screen.parent as View
        ui = Ui(this)
        child = intent.getStringExtra(EXTRA_CHILD).orEmpty()
        // The search box stays put; everything under it is redrawn.
        screen.addView(ui.field(L.t("🔍 חיפוש: רשת, קוד, סכום, ילד, תאריך…", "🔍 Search: chain, code, amount, child, date…"), "", top = 12).apply {
            isSingleLine = true
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(e: android.text.Editable?) { query = e?.toString().orEmpty().trim(); render() }
            })
            setOnEditorActionListener { _, _, _ -> if (query.isNotEmpty()) smartSearch(); true }
        })
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        screen.addView(content)
    }

    override fun onDestroy() { worker.shutdownNow(); super.onDestroy() }

    /** Opened again (the tab, or a child's page): show what was asked for (one child, or everyone). */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        child = intent.getStringExtra(EXTRA_CHILD).orEmpty()
    }

    override fun onResume() {
        super.onResume()
        render()
        // The other parent's items (when both share the file): shown as soon as they arrive.
        FamilyFileSync.kick(this) { runOnUiThread { if (!isDestroyed) render() } }
    }

    /** The other parent's name (from what they shared), for "from Michal". */
    private fun partnerName(): String =
        Circle.cachedPartnerMoments(this).firstOrNull()?.author?.takeIf { it.isNotBlank() } ?: L.t("ההורה השני", "the other parent")

    private fun tint(i: FamilyItems.Item): Pair<Int, Int> =
        FamilyItems.Category.of(i.category)?.let { TINTS[it.id] } ?: KIND_TINTS[i.kind] ?: (0xFFEDE5DC.toInt() to 0xFF675C54.toInt())

    private fun render() {
        content.removeAllViews()
        val kids = Profile.members(this).filter { !it.isPet } /* parents too, not only the children */
        val names = kids.associate { it.id to it.name }
        val now = java.time.LocalDate.now()
        val today = now.toString()
        val year = now.year
        val thisMonth = today.substring(0, 7)
        // Coupons from a spreadsheet with nothing to redeem (no code, no link) aren't shown.
        val all = FamilyFile.all(this).filterNot { CouponImport.noCode(it) }
        val byChild = if (child.isEmpty()) all else all.filter { it.child == child }
        val byGroup = tile?.let { t -> byChild.filter { it.kind in t.kinds } } ?: byChild
        val shown = if (category.isEmpty()) byGroup else byGroup.filter { it.category == category }

        // Filters: whose, and (only categories that have something) what for.
        if (kids.isNotEmpty()) {
            val labels = listOf(L.t("כולם", "Everyone")) + kids.map { it.name }
            val sel = kids.indexOfFirst { it.id == child }.let { if (it < 0) 0 else it + 1 }
            content.addView(ui.chips(labels, sel, top = 12) { i -> child = if (i == 0) "" else kids[i - 1].id; render() })
        }
        if (query.isNotEmpty()) { searchResults(byChild, names, today); return }

        // The overview: one tile per kind of thing in the file, each a filter.
        tiles(byChild, today, year)

        // The children's savings, in one line (its own screen).
        val funds = FamilyFile.savings(this).let { f -> if (child.isEmpty()) f else f.filter { it.child == child } }
        if (funds.isNotEmpty() && tile == null) content.addView(ui.card(top = 2).apply {
            addView(ui.row(L.t("💰 חיסכון והשקעות", "💰 Savings and investments"), FamilyItems.money(Savings.familyTotal(funds, today) { Savings.estimate(it, today, GemelNet.cached(this@FamilyFileActivity, it.gemelId), CryptoPrices.cached(this@FamilyFileActivity)) }) + " ›", top = 0) { SavingsActivity.open(this@FamilyFileActivity, child) })
        })

        val cats = FamilyItems.Category.values().filter { c -> byGroup.any { it.category == c.id } }
        if (cats.size > 1 && (tile == FamilyItems.Tile.RECEIPTS || tile == FamilyItems.Tile.DOCUMENTS)) {
            val labels = listOf(L.t("כל הקטגוריות", "All categories")) + cats.map { it.label }
            val sel = cats.indexOfFirst { it.id == category }.let { if (it < 0) 0 else it + 1 }
            content.addView(ui.chips(labels, sel, top = 6) { i -> category = if (i == 0) "" else cats[i - 1].id; render() })
        }

        if (shown.isEmpty()) {
            content.addView(ui.card(top = 16).apply {
                addView(ui.text(L.t("התיק עוד ריק", "The file is empty"), 17f, bold = true))
                addView(ui.hint(L.t("שתפו לכאן קבלה, תעודת אחריות, זיכוי, פתק החלפה, קופון או שובר. נזכיר לכם לפני שהתוקף נגמר. הכול נשמר רק בטלפון הזה, מוצפן.", "Share a receipt, a warranty, a store credit, an exchange slip, a coupon or a voucher here. We'll remind you before it runs out. Everything stays on this phone, encrypted."), top = 4))
            })
            addButtons()
            return
        }

        // Summary: this year, this month, and where it went.
        val yearTotal = FamilyItems.receiptTotal(shown, year, null)
        if (yearTotal > 0 && tile == FamilyItems.Tile.RECEIPTS) content.addView(summary(shown, year, thisMonth, yearTotal))

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

        // Warranties and exchange slips: the ones still running, soonest first; the ended ones after.
        val prot = shown.filter { FamilyItems.isProtection(it) }
        if (prot.isNotEmpty()) {
            val (running, ended) = prot.partition { !FamilyItems.expired(it, today) }
            content.addView(header(L.t("אחריות והחלפות", "Warranties and exchanges"), ""))
            if (running.isNotEmpty()) content.addView(list(running.sortedBy { it.expires.ifEmpty { "9999" } }, names, today))
            if (ended.isNotEmpty()) {
                val isOpen = "#ended" in open
                content.addView(ui.action((if (isOpen) "⌃ " else "⌄ ") + L.t("הסתיימו · ${ended.size}", "Ended · ${ended.size}"), R.color.ink_soft) { toggle("#ended") })
                if (isOpen) content.addView(list(ended.sortedByDescending { it.expires }, names, today))
            }
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
        content.addView(ui.button(L.t("ייבוא קופונים מקובץ Excel", "Import coupons from an Excel file"), top = 8) { FamilyImportActivity.open(this) })
        content.addView(ui.button(L.t("💰 חיסכון והשקעות", "💰 Savings and investments"), top = 8) { SavingsActivity.open(this, child) })
        content.addView(ui.hint(L.t("אפשר גם לשתף לכאן מכל אפליקציה (וואטסאפ, מייל, גלריה): שתף ← Moments · תיק המשפחה.", "You can also share here from any app (WhatsApp, email, gallery): Share → Moments · family file."), top = 6))
        if (FamilyFile.all(this).isNotEmpty()) content.addView(ui.switch(L.t("תזכורת לפני שאחריות, זיכוי, שובר או פתק החלפה נגמרים", "Remind me before a warranty, credit, voucher or exchange slip runs out"), FamilyFileReminders.on(this), top = 14) { on -> FamilyFileReminders.setOn(this, on) })
        // Sharing with the other parent: each of them turns it on, after reading what it means.
        if (PartnerShare.partner(this).isNotEmpty() && FamilyFile.consented(this)) {
            val who = partnerName()
            content.addView(ui.switch(L.t("שיתוף התיק עם $who", "Share the file with $who"), FamilyFileSync.on(this), top = 8) { want ->
                if (want == FamilyFileSync.on(this)) return@switch
                if (want) AlertDialog.Builder(this)
                    .setTitle(L.t("לשתף את התיק עם $who?", "Share the file with $who?"))
                    .setMessage(FamilyFileSync.shareConsentText(who))
                    .setPositiveButton(L.t("מאשר/ת", "I agree")) { _, _ -> FamilyFileSync.turnOn(this); FamilyFileSync.kick(this, force = true) { runOnUiThread { if (!isDestroyed) render() } }; render() }
                    .setNegativeButton(L.t("ביטול", "Cancel")) { _, _ -> render() }
                    .setOnCancelListener { render() }
                    .show()
                else AlertDialog.Builder(this)
                    .setTitle(L.t("להפסיק לשתף?", "Stop sharing?"))
                    .setMessage(L.t("מה שנשלח מהטלפון הזה יימחק מהשרת, והפריטים של $who יוסרו מכאן.", "What this phone sent is deleted from the server, and $who's items are removed from here."))
                    .setPositiveButton(L.t("להפסיק", "Stop")) { _, _ -> FamilyFileSync.turnOff(this) { runOnUiThread { if (!isDestroyed) render() } } }
                    .setNegativeButton(L.t("ביטול", "Cancel")) { _, _ -> render() }
                    .setOnCancelListener { render() }
                    .show()
            })
            content.addView(ui.hint(if (FamilyFileSync.on(this)) L.t("הפריטים שלך נשלחים ל$who. הפריטים של $who יופיעו כאן כשגם $who יפעיל/תפעיל שיתוף.", "Your items go to $who. $who's items appear here once $who turns sharing on too.")
                else L.t("כרגע התיק נשמר רק בטלפון הזה.", "For now the file stays on this phone only."), top = 2))
        }
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
            names[i.child]?.takeIf { it.isNotBlank() },
            if (i.source == FamilyShare.FROM_PARTNER) L.t("מ", "from ") + partnerName() else null,
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
                    // Each part isolated, so an English name next to Hebrew doesn't move the dots around.
                    text = meta.joinToString(" · ") { "\u2068$it\u2069" }; textSize = 13f; setTextColor(DS.inkSoft(this@FamilyFileActivity))
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
                setOnLongClickListener { deleteChain(c); true }
            })
            if (isOpen) {
                c.items.forEach { i -> card.addView(divider(52)); card.addView(voucherRow(i, today)) }
                card.addView(divider(0))
                card.addView(ui.action(L.t("🗑 מחיקת כל ${c.name}", "🗑 Delete all of ${c.name}"), R.color.ds_error) { deleteChain(c) })
            }
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
        val y = scroller.scrollY
        render()
        scroller.post { scroller.scrollTo(0, y) }
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
            addView(View(this@FamilyFileActivity).apply { layoutParams = LinearLayout.LayoutParams(ui.dp(22), 1) })
            contentDescription = (listOf(title) + meta + listOfNotNull(w.takeIf { it > 0 }?.let { FamilyItems.money(it) })).joinToString(", ")
            setOnClickListener { FamilyItemActivity.open(this@FamilyFileActivity, i.id) }
        }
    }

    /** Deletes every voucher of one chain (after asking), e.g. all the BuyMe ones once they're used. */
    private fun deleteChain(c: FamilyItems.Chain) {
        val n = c.items.size
        AlertDialog.Builder(this)
            .setTitle(L.t("למחוק את כל ${c.name}?", "Delete all of ${c.name}?"))
            .setMessage(L.t(
                (if (n == 1) "שובר אחד" else "$n שוברים") + (if (c.total > 0) " בשווי ${FamilyItems.money(c.total)}" else "") + " יימחקו מהתיק ומהטלפון. אי אפשר לשחזר.",
                (if (n == 1) "1 voucher" else "$n vouchers") + (if (c.total > 0) " worth ${FamilyItems.money(c.total)}" else "") + " will be deleted from the file and the phone. This can't be undone."))
            .setPositiveButton(L.t("מחיקה", "Delete")) { _, _ ->
                c.items.forEach { FamilyFile.delete(this, it.id) }
                open -= c.name
                render()
            }
            .setNegativeButton(L.t("ביטול", "Cancel"), null).show()
    }

    /** Two tiles a row: what's in the wallet, this year's receipts, warranties, documents. Tapping one shows only it. */
    private fun tiles(items: List<FamilyItems.Item>, today: String, year: Int) {
        val present = FamilyItems.Tile.values().filter { t -> items.any { it.kind in t.kinds && !CouponImport.noCode(it) } }
        if (present.size < 2) { tile = null; return }
        if (tile !in present) tile = null
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.dp(12) } }
        present.chunked(2).forEach { pair ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = ui.dp(10) } }
            pair.forEachIndexed { n, t ->
                val mine = items.filter { it.kind in t.kinds }
                val (big, small) = when (t) {
                    FamilyItems.Tile.WALLET -> FamilyItems.wallet(mine, today).let { w -> FamilyItems.money(w.sumOf { it.total }) to L.t("${w.sumOf { it.items.size }} בתוקף", "${w.sumOf { it.items.size }} valid") }
                    FamilyItems.Tile.RECEIPTS -> FamilyItems.money(FamilyItems.receiptTotal(mine, year, null)) to L.t("השנה · ${mine.size} קבלות", "this year · ${mine.size} receipts")
                    FamilyItems.Tile.PROTECTION -> mine.count { !FamilyItems.expired(it, today) }.toString() to L.t("בתוקף", "running")
                    FamilyItems.Tile.DOCUMENTS -> mine.size.toString() to L.t("מסמכים", "documents")
                }
                val on = tile == t
                row.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12)); minimumHeight = ui.dp(88)
                    background = DS.rounded(if (on) 0xFF6F4E37.toInt() else DS.card(this@FamilyFileActivity), ui.dp(18).toFloat())
                    val ink = if (on) 0xFFFFFFFF.toInt() else DS.ink(this@FamilyFileActivity)
                    val soft = if (on) 0xE6FFFFFF.toInt() else DS.inkSoft(this@FamilyFileActivity)
                    addView(TextView(this@FamilyFileActivity).apply { text = t.label; textSize = 14f; setTextColor(soft); typeface = DS.emphasis })
                    addView(amount(big, 22f, ink).apply { setPadding(0, ui.dp(4), 0, 0) })
                    addView(TextView(this@FamilyFileActivity).apply { text = small; textSize = 12.5f; setTextColor(soft) })
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (n == 0) marginEnd = ui.dp(10) }
                    isClickable = true
                    contentDescription = listOf(t.label, big, small, if (on) L.t("נבחר. נגיעה מציגה הכול", "Selected. Tap to show everything") else L.t("נגיעה מציגה רק את זה", "Tap to show only this")).joinToString(", ")
                    A11y.button(this)
                    setOnClickListener { tile = if (on) null else t; category = ""; render() }
                })
            }
            if (pair.size == 1) row.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = ui.dp(10) } })
            grid.addView(row)
        }
        content.addView(grid)
        if (tile != null) content.addView(ui.action(L.t("← הצגת הכול", "← Show everything"), R.color.ink_soft) { tile = null; category = ""; render() })
    }

    /** While typing: what matches on the phone right away, and a smart search for questions in your own words. */
    private fun searchResults(items: List<FamilyItems.Item>, names: Map<String, String>, today: String) {
        val found = items.filter { FamilyItems.matches(it, query, names) }
        val ai = smart?.takeIf { smartFor == query }
        if (ai != null) {
            val picked = FamilyItems.pick(items, ai.second)
            content.addView(ui.card(top = 12).apply {
                addView(ui.text(L.t("✨ חיפוש חכם", "✨ Smart search"), 14f, R.color.ink_soft, bold = true))
                addView(ui.text(ai.first.ifBlank { if (picked.isEmpty()) L.t("לא מצאתי משהו שמתאים", "Nothing matched") else L.t("מצאתי ${picked.size}", "Found ${picked.size}") }, 16f, top = 4).apply { textDirection = View.TEXT_DIRECTION_FIRST_STRONG })
            })
            if (picked.isNotEmpty()) content.addView(list(picked, names, today))
        }
        content.addView(header(if (found.isEmpty()) L.t("אין התאמה מדויקת", "No exact match") else L.t("נמצאו ${found.size}", "${found.size} found"), ""))
        if (found.isNotEmpty()) content.addView(list(found.sortedWith(compareBy<FamilyItems.Item> { FamilyItems.expired(it, today) }.thenByDescending { it.date.ifEmpty { it.expires } }).take(60), names, today))
        if (ai == null) {
            content.addView(ui.button(if (smartBusy) L.t("מחפשים…", "Searching…") else L.t("✨ חיפוש חכם: \"$query\"", "✨ Smart search: \"$query\""), top = 14) { smartSearch() }.apply { isEnabled = !smartBusy })
            content.addView(ui.hint(L.t("אפשר לשאול במילים שלכם: \"כמה שילמנו לגן השנה?\", \"שובר לסופר שעוד בתוקף\". נשלחים רק כותרות, סכומים ותאריכים, בלי קודים.", "Ask in your own words: \"how much did we pay the kindergarten this year?\", \"a supermarket voucher that's still valid\". Only titles, amounts and dates are sent, no codes."), top = 4))
        }
    }

    private fun smartSearch() {
        if (smartBusy || query.isEmpty()) return
        if (!Account.signedIn(this)) {
            android.widget.Toast.makeText(this, L.t("חיפוש חכם דורש התחברות לחשבון. החיפוש הרגיל עובד גם בלי.", "Smart search needs you to sign in. The regular search works without."), android.widget.Toast.LENGTH_LONG).show()
            return
        }
        val p = Settings.prefs(this)
        if (!p.getBoolean("family_file_smart_ok", false)) {
            AlertDialog.Builder(this)
                .setTitle(L.t("חיפוש חכם", "Smart search"))
                .setMessage(L.t("כדי להבין את השאלה, נשלחת לבינה המלאכותית רשימה של הפריטים בתיק: מה זה, ממי, של איזה ילד, סכום ותאריכים. בלי קודים, מספרי כרטיס, הערות או קבצים, ושום דבר לא נשמר שם.", "To understand the question, a list of the file's items is sent to the AI: what it is, from whom, which child, amount and dates. No codes, card numbers, notes or files, and nothing is kept there."))
                .setPositiveButton(L.t("אישור", "OK")) { _, _ -> p.edit().putBoolean("family_file_smart_ok", true).apply(); smartSearch() }
                .setNegativeButton(L.t("ביטול", "Cancel"), null).show()
            return
        }
        val q = query
        val kids = Profile.members(this).filter { !it.isPet } /* parents too, not only the children */.associate { it.id to it.name }
        val items = FamilyFile.all(this).filterNot { CouponImport.noCode(it) }.let { a -> if (child.isEmpty()) a else a.filter { it.child == child } }
        smartBusy = true; render()
        worker.execute {
            val r = try {
                val o = ClaudeClient.searchFamilyFile(applicationContext, q, FamilyItems.searchIndex(items, kids), java.time.LocalDate.now().toString())
                val ids = o.optJSONArray("ids")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
                Result.success(o.optString("answer").takeIf { it != "null" }.orEmpty() to ids)
            } catch (e: Exception) { Result.failure(e) }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                smartBusy = false
                r.onSuccess { smart = it; smartFor = q }.onFailure {
                    android.widget.Toast.makeText(this, Wording.forParent(it.message, admin = false, fallback = L.t("החיפוש החכם לא הצליח הפעם. החיפוש הרגיל עובד.", "Smart search didn't work this time. The regular search works.")), android.widget.Toast.LENGTH_LONG).show()
                }
                render()
            }
        }
    }
}
