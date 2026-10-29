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
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.Executors

/**
 * The children's savings: how much each child has (estimated: the last quarterly report's balance plus the deposits
 * since), fund by fund. Upload a quarterly report (the details are read from it and checked by the parent), or add a
 * fund by hand. Kept on this phone, encrypted, with the family file. Facts only: no advice on which fund to choose.
 */
class SavingsActivity : AppCompatActivity() {
    companion object {
        private const val EXTRA_CHILD = "child"
        fun open(ctx: Context, child: String = "") = ctx.startActivity(Intent(ctx, SavingsActivity::class.java).putExtra(EXTRA_CHILD, child))
    }

    private lateinit var ui: Ui
    private lateinit var content: LinearLayout
    private val worker = Executors.newSingleThreadExecutor()
    private var child = ""
    private var busy = false

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) readReport(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_screen)
        findViewById<View>(R.id.btnClose).setOnClickListener { finish() }
        findViewById<TextView>(R.id.screenTitle).text = L.t("חיסכון והשקעות", "Savings and investments")
        content = findViewById(R.id.screenContent)
        ui = Ui(this)
        child = intent.getStringExtra(EXTRA_CHILD).orEmpty()
        if (!FamilyFile.consented(this)) AlertDialog.Builder(this)
            .setTitle(L.t("תיק המשפחה: מה נשמר ואיפה", "The family file: what's kept, and where"))
            .setMessage(FamilyFile.consentText())
            .setPositiveButton(L.t("מאשר/ת", "I agree")) { _, _ -> FamilyFile.setConsented(this); render() }
            .setNegativeButton(L.t("לא עכשיו", "Not now")) { _, _ -> finish() }
            .setOnCancelListener { finish() }.show()
    }

    override fun onResume() {
        super.onResume()
        if (!FamilyFile.consented(this)) return
        render()
        FamilyFileSync.kick(this) { runOnUiThread { if (!isDestroyed) render() } }
        // The official monthly returns (public data, nothing about the family is sent).
        worker.execute {
            val got = try { GemelNet.refresh(applicationContext) } catch (_: Exception) { false }
            // Coin prices (only the coins' names are sent).
            val coins = FamilyFile.savings(applicationContext).filter { it.kind == Savings.Kind.CRYPTO }.flatMap { f -> f.coins.map { it.symbol } + (f.lastReport?.coins?.keys ?: emptySet()) }.toSet()
            val priced = try { coins.isNotEmpty() && CryptoPrices.refresh(applicationContext, coins + "USDT") } catch (_: Exception) { false }
            if (got || priced) runOnUiThread { if (!isDestroyed) render() }
        }
    }
    override fun onDestroy() { worker.shutdownNow(); super.onDestroy() }

    private fun amount(text: String, size: Float, color: Int) = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(color); typeface = Typeface.create(DS.emphasis, Typeface.BOLD)
        textDirection = View.TEXT_DIRECTION_LTR; maxLines = 1
    }

    private fun render() {
        content.removeAllViews()
        val today = java.time.LocalDate.now().toString()
        val kids = Profile.members(this).filter { !it.isPet } /* parents too, not only the children */
        val all = FamilyFile.savings(this)
        val shownKids = if (child.isEmpty()) kids else kids.filter { it.id == child }
        val funds = if (child.isEmpty()) all else all.filter { it.child == child }

        // Who to show: the children, and a parent once they have a fund.
        val choosable = kids.filter { p -> p.isChild || all.any { it.child == p.id } || p.id == child }
        if (choosable.size > 1) {
            val labels = listOf(L.t("כולם", "Everyone")) + choosable.map { it.name }
            val sel = choosable.indexOfFirst { it.id == child }.let { if (it < 0) 0 else it + 1 }
            content.addView(ui.chips(labels, sel, top = 12) { i -> child = if (i == 0) "" else choosable[i - 1].id; render() })
        }

        // The portfolio at a glance: a ring of what it's made of, and the three numbers that matter.
        content.addView(portfolio(funds, shownKids, today))
        // How it got here: the balances in the reports over time, and today's estimate.
        val hist = Savings.history(funds, today, funds.sumOf { est(it, today)?.total ?: 0.0 })
        if (hist.isNotEmpty()) content.addView(historyCard(hist))
        content.addView(calculator(funds, shownKids, today))

        content.addView(ui.button(if (busy) L.t("קוראים את הדוח…", "Reading the report…") else L.t("📄 העלאת דוח (קופה או קריפטו)", "📄 Upload a report (fund or crypto)"), primary = true, top = 14) {
            if (!busy) picker.launch(SavingsReports.TYPES)
        }.apply { isEnabled = !busy })
        content.addView(ui.hint(L.t("דוח רבעוני או דף חשבון של קריפטו: הקופה, המטבעות והיתרה נקראים מהדוח, ואתם בודקים לפני שמירה.", "A quarterly report or a crypto statement: the fund, coins and balance are read from it; you check before saving."), top = 4))

        // Children always; a parent once they have a fund (adding one: from the fund screen, "whose").
        for (k in shownKids.filter { p -> p.isChild || funds.any { it.child == p.id } || child == p.id }) {
            val mine = funds.filter { it.child == k.id }
            val sum = mine.sumOf { est(it, today)?.total ?: 0.0 }
            content.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.dp(24) }
                addView(ui.text(k.name, 18f, bold = true).apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f); androidx.core.view.ViewCompat.setAccessibilityHeading(this, true) })
                if (sum > 0) addView(amount(ils(sum), 17f, DS.ink(this@SavingsActivity)))
            })
            val card = ui.card(top = 8)
            card.setPadding(ui.dp(14), ui.dp(4), ui.dp(14), ui.dp(4))
            if (mine.isEmpty()) card.addView(ui.hint(L.t("עוד אין קופה. מעלים דוח, או מוסיפים ידנית.", "No fund yet. Upload a report, or add one by hand."), top = 12).apply { setPadding(0, 0, 0, ui.dp(12)) })
            mine.forEachIndexed { n, f ->
                if (n > 0) card.addView(View(this).apply { setBackgroundColor(0xFFE4E9F0.toInt()); layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1) })
                card.addView(fundRow(f, today))
            }
            card.addView(ui.action(L.t("+ קופה ל${k.name}", "+ A fund for ${k.name}")) { SavingsFundActivity.newFor(this, k.id) })
            content.addView(card)
            content.addView(goalRow(k, sum))
        }
        if (funds.any { it.lastReport != null }) content.addView(ui.button(L.t("שיתוף סיכום (PDF)", "Share a summary (PDF)"), top = 18) { sharePdf(funds, shownKids, today) })
        // Both parents see the same savings when the family file is shared.
        if (PartnerShare.partner(this).isNotEmpty() && !FamilyFileSync.on(this)) content.addView(ui.hint(
            L.t("כדי שגם ההורה השני יראה את החיסכון ויוכל להעלות את הדוח הבא: מפעילים \"שיתוף התיק\" בתחתית תיק המשפחה (אצל שניכם).",
                "So the other parent sees the savings and can upload the next report: turn on \"Share the file\" at the bottom of the family file (on both phones)."), top = 16))
        if (kids.isEmpty()) content.addView(ui.hint(L.t("כדי לעקוב אחרי חיסכון מוסיפים קודם את בני המשפחה בפרופיל.", "To follow savings, first add children in the family profile."), top = 16))
        content.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, ui.dp(24)) })
    }

    /** Today's amount: official monthly returns (Gemel-Net) or, for crypto, today's coin prices. */
    private fun est(f: Savings.Fund, today: String) = Savings.estimate(f, today, off(f), if (f.kind == Savings.Kind.CRYPTO) CryptoPrices.cached(this) else null)

    /** The fund's official months (Gemel-Net), kept on the phone. */
    private fun off(f: Savings.Fund) = GemelNet.cached(this, f.gemelId)

    private fun sign(v: Double) = if (v >= 0) "+" else "−"
    private fun pct(v: Double) = sign(v) + String.format(java.util.Locale.US, "%.1f", kotlin.math.abs(v)) + "%"

    private fun fundRow(f: Savings.Fund, today: String): View {
        val e = est(f, today)
        val meta = listOfNotNull(
            f.kind.label,
            e?.periodReturnPct?.let { L.t("תשואה בדוח: ${pct(it)}", "Report's return: ${pct(it)}") },
            if (f.kind == Savings.Kind.CRYPTO) f.coins.joinToString(" · ") { "${it.symbol} ${it.units.toBigDecimal().stripTrailingZeros().toPlainString()}" }.ifEmpty { null } else null,
            if (f.kind == Savings.Kind.CRYPTO) e?.ratePct?.takeIf { CryptoPrices.cached(this) != null }?.let { L.t("מאז הדוח: ${pct(it)} (מחיר עכשיו)", "Since the report: ${pct(it)} (price now)") }
            else e?.ratePct?.let { L.t("מאז הדוח: ${pct(it)} (גמל נט עד ${GemelNet.latestLabel(off(f))})", "Since the report: ${pct(it)} (Gemel-Net to ${GemelNet.latestLabel(off(f))})") }
                ?: if (f.lastReport != null && f.gemelId == 0) L.t("לא נמצאה בגמל נט: יתרה + הפקדות", "Not found at Gemel-Net: balance + deposits") else null,
            f.lastReport?.let { L.t("דוח ${FamilyItems.short(it.date)}: ${ils(it.balance)}", "Report ${FamilyItems.short(it.date)}: ${ils(it.balance)}") }
                ?: L.t("עוד אין דוח", "No report yet"),
            if (f.perMonth > 0) L.t("+${ils(f.perMonth)} בחודש", "+${ils(f.perMonth)} a month") else null,
            if (f.kind == Savings.Kind.CRYPTO) Savings.dailyChangeCrypto(f, CryptoPrices.cached(this), today)?.let { L.t("מאתמול: ${pct(it)}", "Since yesterday: ${pct(it)}") } else null,
        )
        val peers = GemelNet.compare(this, f)?.let { peerText(it) }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, ui.dp(12), 0, ui.dp(12)); minimumHeight = ui.dp(56)
            background = android.util.TypedValue().let { tv -> theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true); getDrawable(tv.resourceId) }
            addView(LinearLayout(this@SavingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = ui.dp(10) }
                addView(TextView(this@SavingsActivity).apply {
                    text = f.title; textSize = 15f; setTextColor(DS.ink(this@SavingsActivity)); typeface = DS.emphasis
                    maxLines = 2; ellipsize = TextUtils.TruncateAt.END; textDirection = View.TEXT_DIRECTION_FIRST_STRONG
                })
                addView(TextView(this@SavingsActivity).apply {
                    text = meta.joinToString(" · ") { "⁨$it⁩" }; textSize = 12.5f; setTextColor(DS.inkSoft(this@SavingsActivity)); setPadding(0, ui.dp(2), 0, 0)
                })
                // Next to the other funds of the same track (Gemel-Net): numbers only.
                if (peers != null) addView(TextView(this@SavingsActivity).apply {
                    text = peers; textSize = 12f; setTextColor(DS.inkSoft(this@SavingsActivity))
                    setPadding(ui.dp(8), ui.dp(5), ui.dp(8), ui.dp(5))
                    background = DS.rounded(DS.cardAlt(this@SavingsActivity), ui.dp(8).toFloat())
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.dp(6) }
                })
            })
            e?.let { addView(amount(ils(it.total), 15f, DS.ink(this@SavingsActivity))) }
            contentDescription = (listOf(f.title) + meta + listOfNotNull(peers, e?.let { ils(it.total) })).joinToString(", ")
            setOnClickListener { SavingsFundActivity.open(this@SavingsActivity, f.id) }
        }
    }

    /** A report: read on request (signed in), then the fund screen opens with what was read, for the parent to check. */
    private fun readReport(uri: android.net.Uri) {
        busy = true; render()
        SavingsReports.read(this, uri, child) { reading, docId ->
            busy = false; render()
            SavingsFundActivity.fromReport(this, reading, docId, child)
        }
    }

    // The calculator's choices (kept while the screen is open).
    private var calcRate = 8.0
    private var calcYears: Double? = null // null = until 18 (one child), else 18 years
    private var calcMonthly: Double? = null // null = what goes in today

    /**
     * "How much could there be": today's amount and the monthly deposits growing at a chosen yearly return, until the
     * child is 18 or for a chosen number of years. A calculator, said so: a fixed return, no fees, tax or inflation.
     */
    private fun calculator(funds: List<Savings.Fund>, people: List<FamilyMember>, today: String): View {
        val start = funds.sumOf { est(it, today)?.total ?: 0.0 }
        val person = if (child.isNotEmpty()) people.firstOrNull { it.id == child } else null
        val to18 = person?.let { Savings.yearsTo18(it.birth, today) }?.takeIf { it > 0 }
        val years = calcYears ?: to18 ?: 18.0
        val card = ui.card(top = 12)
        card.setPadding(ui.dp(18), ui.dp(16), ui.dp(18), ui.dp(16))
        card.addView(ui.text(L.t("מחשבון: כמה יהיה בקופה", "Calculator: how much there could be"), 16f, bold = true))

        card.addView(ui.text(L.t("תשואה שנתית", "Yearly return"), 13f, R.color.ink_soft, bold = true, top = 10))
        val rates = listOf(4.0, 6.0, 8.0, 10.0)
        card.addView(ui.chips(rates.map { "${it.toInt()}%" }, rates.indexOf(calcRate), top = 4) { i -> calcRate = rates[i]; render() })
        card.addView(ui.text(L.t("לכמה זמן", "For how long"), 13f, R.color.ink_soft, bold = true, top = 10))
        val spans: List<Pair<String, Double?>> = listOfNotNull(
            to18?.let { L.t("עד גיל 18", "Until 18") to null },
            L.t("5 שנים", "5 years") to 5.0, L.t("10 שנים", "10 years") to 10.0, L.t("18 שנים", "18 years") to 18.0, L.t("25 שנים", "25 years") to 25.0,
        )
        val selected = spans.indexOfFirst { it.second == calcYears }.let { if (it < 0) 0 else it }
        card.addView(ui.chips(spans.map { it.first }, selected, top = 4) { i -> calcYears = spans[i].second; render() })

        val monthlyNow = funds.sumOf { it.perMonth }
        card.addView(ui.text(L.t("הפקדה חודשית ב-₪", "Each month in ₪"), 13f, R.color.ink_soft, bold = true, top = 10))
        val monthlyField = ui.field(L.t("הפקדה חודשית", "Each month"), ils(calcMonthly ?: monthlyNow).removeSuffix(" ₪"), top = 4).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        card.addView(monthlyField)

        val result = amount("", 28f, DS.ink(this)).apply { setPadding(0, ui.dp(14), 0, 0) }
        val split = ui.hint("", top = 2)
        val chart = GrowthChartView(this).apply {
            deposits = 0xFFD6E4F5.toInt(); growth = DS.primary(this@SavingsActivity); soft = DS.inkSoft(this@SavingsActivity)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.dp(10) }
        }
        val legend = ui.hint(L.t("בהיר: מה שהופקד · כהה: התשואה", "Light: deposited · dark: growth"), top = 4)
        card.addView(result); card.addView(split); card.addView(chart); card.addView(legend)

        fun update() {
            val monthly = FamilyItems.cleanAmount(monthlyField.text.toString()) ?: 0.0
            val pts = Savings.project(start, monthly, calcRate, years)
            val end = pts.last()
            val whenText = if (calcYears == null && to18 != null) L.t("בגיל 18", "at 18") else L.t("בעוד ${trim(years)} שנים", "in ${trim(years)} years")
            result.text = ils(end.total)
            split.text = L.t("$whenText · הופקדו ${ils(end.deposited)} · תשואה ${ils(end.total - end.deposited)}",
                "$whenText · deposited ${ils(end.deposited)} · growth ${ils(end.total - end.deposited)}")
            chart.startLabel = L.t("היום", "today"); chart.endLabel = whenText; chart.points = pts
            result.contentDescription = L.t("יכול להיות $whenText: ${ils(end.total)}", "Could be $whenText: ${ils(end.total)}")
        }
        monthlyField.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(e: android.text.Editable?) { calcMonthly = FamilyItems.cleanAmount(e?.toString()); update() }
        })
        update()
        card.addView(ui.hint(L.t("חישוב בלבד, לא תחזית ולא המלצה: מניח תשואה קבועה של ${calcRate.toInt()}% בשנה, בלי דמי ניהול, מס ואינפלציה. התשואה בפועל משתנה ואינה מובטחת.",
            "A calculation only, not a forecast or advice: a fixed ${calcRate.toInt()}% a year, no fees, tax or inflation. Real returns vary and aren't guaranteed."), top = 10))
        return card
    }

    private fun trim(y: Double) = if (y % 1.0 == 0.0) y.toInt().toString() else String.format(java.util.Locale.US, "%.1f", y)

    private val PALETTE = listOf(0xFF1A6FCC.toInt(), 0xFF5AC8FA.toInt(), 0xFF7B8CDE.toInt(), 0xFF2BA8A0.toInt(), 0xFFF2A971.toInt(), 0xFF9B7FD1.toInt())
    private val UP = 0xFF1C7F46.toInt()
    private val DOWN = 0xFFB3261E.toInt()
    private val DEPOSITED = 0xFF5AC8FA.toInt()

    /**
     * The portfolio card: a ring (by child for the whole family, by fund for one child) with the total in the middle
     * and a legend beside it; under it what's there, the year's return so far (official months, weighted by size)
     * and what goes in each month.
     */
    private fun portfolio(funds: List<Savings.Fund>, kids: List<FamilyMember>, today: String): View {
        val year = today.take(4).toIntOrNull() ?: 0
        val est = funds.associateWith { est(it, today) }
        val total = est.values.sumOf { it?.total ?: 0.0 }
        val monthly = funds.sumOf { it.perMonth }
        val ytdParts = funds.map { it to (if (it.kind == Savings.Kind.CRYPTO) Savings.yearToDateCrypto(it, CryptoPrices.cached(this), year) else Savings.yearToDate(off(it), year)) }
        val ytd = Savings.weighted(ytdParts.map { (est[it.first]?.total ?: 0.0) to it.second })
        // Which funds the year's return is about (a fund without official months isn't in it).
        val counted = ytdParts.filter { it.second != null && (est[it.first]?.total ?: 0.0) > 0 }.map { it.first }
        val withValue = funds.count { (est[it]?.total ?: 0.0) > 0 }
        val ytdSub = when {
            counted.isEmpty() -> L.t("אין עדיין נתונים", "no data yet")
            counted.size < withValue -> L.t("${counted.size} מתוך $withValue קופות", "${counted.size} of $withValue funds")
            counted.all { it.kind == Savings.Kind.CRYPTO } -> L.t("קריפטו", "crypto")
            counted.any { it.kind == Savings.Kind.CRYPTO } -> L.t("גמל נט + קריפטו", "Gemel-Net + crypto")
            else -> L.t("גמל נט", "Gemel-Net")
        }
        val byChild = child.isEmpty() && kids.size > 1
        val parts: List<Pair<String, Double>> = if (byChild) kids.map { k -> k.name to funds.filter { it.child == k.id }.sumOf { est[it]?.total ?: 0.0 } }.filter { it.second > 0 }
            else funds.flatMap { f ->
                val v = est[f]?.total ?: 0.0
                val shares = if (f.kind == Savings.Kind.CRYPTO) Savings.coinShares(f, CryptoPrices.cached(this)) else emptyMap()
                if (shares.size > 1) shares.entries.sortedByDescending { it.value }.map { (sym, s) -> coinLabel(sym) to v * s }
                else listOf(legendName(f) to v)
            }.filter { it.second > 0 }

        val card = ui.card(top = 12)
        card.setPadding(ui.dp(18), ui.dp(16), ui.dp(18), ui.dp(16))
        card.addView(ui.text(if (child.isEmpty()) L.t("תיק החיסכון של המשפחה", "The family's savings") else L.t("התיק של ${kids.firstOrNull()?.name.orEmpty()}", "${kids.firstOrNull()?.name.orEmpty()}'s savings"), 16f, bold = true))

        // One part only: the amount, big (a ring of one colour says nothing).
        if (parts.size <= 1) {
            card.addView(amount(if (total > 0) ils(total) else "—", 34f, DS.ink(this)).apply { setPadding(0, ui.dp(8), 0, 0) })
            card.addView(ui.hint(listOfNotNull(L.t("יש היום (הערכה)", "today (estimate)"), parts.firstOrNull()?.first?.replace("\n", " · ")).joinToString(" · "), top = 2))
        }
        // The ring and its legend.
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.dp(10) } }
        row.addView(DonutView(this).apply {
            slices = parts.mapIndexed { i, p -> p.second to colorOf(p.first, i) }
            center = if (total > 0) ils(total) else "—"
            caption = L.t("יש היום (הערכה)", "today (estimate)")
            track = DS.cardAlt(this@SavingsActivity); ink = DS.ink(this@SavingsActivity); soft = DS.inkSoft(this@SavingsActivity)
            contentDescription = L.t("יש היום ${ils(total)}", "Today ${ils(total)}") + ", " + parts.joinToString(", ") { "${it.first} ${ils(it.second)}" }
            layoutParams = LinearLayout.LayoutParams(ui.dp(150), ui.dp(150)).apply { marginEnd = ui.dp(14) }
        })
        val legend = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
        if (parts.isEmpty()) legend.addView(ui.hint(L.t("מעלים דוח רבעוני, והתיק יופיע כאן.", "Upload a quarterly report and the portfolio shows here."), top = 0))
        parts.forEachIndexed { i, (name, v) ->
            legend.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(0, ui.dp(4), 0, ui.dp(4))
                // A tap chooses the part's colour (kept on this phone).
                contentDescription = L.t("$name, ${ils(v)}. לשינוי הצבע", "$name, ${ils(v)}. Change the colour")
                setOnClickListener { pickColor(name) }
                addView(View(this@SavingsActivity).apply { background = DS.rounded(colorOf(name, i), ui.dp(5).toFloat()); layoutParams = LinearLayout.LayoutParams(ui.dp(12), ui.dp(12)).apply { marginEnd = ui.dp(8) } })
                addView(LinearLayout(this@SavingsActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    addView(TextView(this@SavingsActivity).apply { text = name; textSize = 13f; setTextColor(DS.ink(this@SavingsActivity)); maxLines = 2; ellipsize = TextUtils.TruncateAt.END; textDirection = View.TEXT_DIRECTION_FIRST_STRONG })
                    addView(TextView(this@SavingsActivity).apply {
                        text = "\u2068${ils(v)}\u2069 · ${Math.round(v / total * 100)}%"; textSize = 12f; setTextColor(DS.inkSoft(this@SavingsActivity))
                    })
                })
            })
        }
        row.addView(legend)
        if (parts.size > 1) card.addView(row)

        // The three numbers.
        val stats = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.dp(14) } }
        fun stat(label: String, value: String, color: Int, sub: String, n: Int) = stats.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(10))
            background = DS.rounded(DS.cardAlt(this@SavingsActivity), ui.dp(14).toFloat())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (n < 2) marginEnd = ui.dp(8) }
            addView(TextView(this@SavingsActivity).apply { text = label; textSize = 12f; setTextColor(DS.inkSoft(this@SavingsActivity)) })
            addView(amount(value, 17f, color).apply { setPadding(0, ui.dp(4), 0, 0) })
            if (sub.isNotEmpty()) addView(TextView(this@SavingsActivity).apply { text = sub; textSize = 11f; setTextColor(DS.inkSoft(this@SavingsActivity)); setPadding(0, ui.dp(2), 0, 0) })
            contentDescription = listOf(label, value, sub).filter { it.isNotBlank() }.joinToString(", ")
        })
        stat(L.t("יש בתיק", "In the portfolio"), ils(total), DS.ink(this), L.t("הערכה להיום", "estimate for today"), 0)
        stat(L.t("תשואה השנה", "Return this year"), ytd?.let { pct(it) } ?: "—", ytd?.let { if (it >= 0) UP else DOWN } ?: DS.inkSoft(this),
            ytdSub, 1)
        val who = listOfNotNull(
            if (funds.any { it.monthly > 0 }) L.t("הורים", "parents") else null,
            if (funds.any { it.stateMonthly > 0 }) L.t("מדינה", "state") else null,
            if (funds.any { f -> f.coins.any { it.monthly > 0 } }) L.t("קניית קריפטו", "crypto buys") else null,
        ).joinToString(" + ")
        stat(L.t("הפקדה חודשית", "Each month"), ils(monthly), DS.ink(this), who, 2)
        card.addView(stats)

        // What the total is made of: the balances in the reports, what was deposited since, and the gain or loss since.
        val b = Savings.breakdown(funds.map { est[it] })
        if (b.total > 0) card.addView(breakdownBar(b))
        sources(funds)?.let { card.addView(ui.text(it, 12f, R.color.ink_soft, top = 10)) }

        val hasGemel = funds.any { it.kind != Savings.Kind.CRYPTO && it.gemelId > 0 }
        val hasPlain = funds.any { it.kind != Savings.Kind.CRYPTO && it.gemelId == 0 && it.lastReport != null }
        val hasCrypto = funds.any { it.kind == Savings.Kind.CRYPTO }
        val how = listOfNotNull(
            if (hasGemel) L.t("היתרה בדוח האחרון לפי התשואות החודשיות הרשמיות (גמל נט, משרד האוצר) ועוד ההפקדות", "the last report's balance at the official monthly returns (Gemel-Net, Ministry of Finance) plus the deposits") else null,
            if (hasPlain) L.t("קופה שלא נמצאה בגמל נט: היתרה בדוח ועוד ההפקדות, בלי תשואה", "a fund not at Gemel-Net: the report's balance plus the deposits, no return") else null,
            if (hasCrypto) L.t("מטבעות דיגיטליים: היחידות לפי המחיר של היום", "crypto: the units at today's price") else null,
        )
        if (how.isNotEmpty()) card.addView(ui.hint(L.t("הערכה: ", "Estimate: ") + how.joinToString("; ") + L.t(". תשואות עבר אינן מבטיחות תשואות בעתיד. מידע בלבד, לא המלצה.", ". Past returns don't promise future ones. Information only, not advice."), top = 12))
        return card
    }

    private fun pct2(v: Double) = String.format(java.util.Locale.US, "%.2f", v) + "%"

    /** "In the S&P 500 track (15 funds, Gemel-Net 09/2026): this year +7.8% · average +8.1% · 6th · fees 0.56% · average 0.52%". */
    private fun peerText(c: Savings.PeerCompare): String {
        val month = if (c.period > 0) "%02d/%d".format(c.period % 100, c.period / 100) else ""
        val parts = listOfNotNull(
            c.ytd?.let { y -> L.t("השנה ${pct(y)}", "this year ${pct(y)}") + (c.avgYtd?.let { L.t(" · ממוצע ${pct(it)}", " · average ${pct(it)}") } ?: "") },
            c.rankYtd?.let { L.t("מקום $it מתוך ${c.count}", "#$it of ${c.count}") },
            c.avg3y?.let { y -> L.t("3 שנים (שנתי) ${pct(y)}", "3 years (yearly) ${pct(y)}") + (c.avgAvg3y?.let { L.t(" · ממוצע ${pct(it)}", " · average ${pct(it)}") } ?: "") },
            c.fee?.let { fee -> L.t("דמי ניהול ${pct2(fee)}", "fees ${pct2(fee)}") + (c.avgFee?.let { L.t(" · ממוצע ${pct2(it)}", " · average ${pct2(it)}") } ?: "") },
        )
        return L.t("לעומת ${c.count} הקופות במסלול ⁨${Savings.isolateLatin(c.sub)}⁩ (גמל נט $month):\n", "Next to the ${c.count} funds of the ⁨${Savings.isolateLatin(c.sub)}⁩ track (Gemel-Net $month):\n") +
            parts.joinToString("\n") { "⁨$it⁩" }
    }

    /** One bar of three parts (reports' balances, deposited since, gain since) and the three amounts under it. */
    private fun breakdownBar(b: Savings.Breakdown): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.dp(14) }
        val gain = b.growth.coerceAtLeast(0.0)
        val parts = listOf(b.reported to DS.primary(this@SavingsActivity), b.depositedSince to DEPOSITED, gain to UP).filter { it.first > 0 }
        addView(LinearLayout(this@SavingsActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            background = DS.rounded(DS.cardAlt(this@SavingsActivity), ui.dp(6).toFloat())
            clipToOutline = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(12))
            parts.forEach { (v, color) -> addView(View(this@SavingsActivity).apply { setBackgroundColor(color); layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, v.toFloat()) }) }
        })
        fun line(color: Int, label: String, value: String, valueColor: Int = DS.ink(this@SavingsActivity)) = addView(LinearLayout(this@SavingsActivity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, ui.dp(6), 0, 0)
            addView(View(this@SavingsActivity).apply { background = DS.rounded(color, ui.dp(4).toFloat()); layoutParams = LinearLayout.LayoutParams(ui.dp(8), ui.dp(8)).apply { marginEnd = ui.dp(8) } })
            addView(TextView(this@SavingsActivity).apply { text = label; textSize = 13f; setTextColor(DS.inkSoft(this@SavingsActivity)); layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) })
            addView(amount(value, 13.5f, valueColor))
            contentDescription = "$label, $value"
        })
        line(DS.primary(this@SavingsActivity), L.t("היתרות בדוחות האחרונים", "Balances in the last reports"), ils(b.reported))
        if (b.depositedSince > 0) line(DEPOSITED, L.t("הופקד מאז", "Deposited since"), ils(b.depositedSince))
        if (Math.rint(b.growth) != 0.0) line(if (b.growth >= 0) UP else DOWN, if (b.growth >= 0) L.t("רווח מאז (הערכה)", "Gain since (estimate)") else L.t("הפסד מאז (הערכה)", "Loss since (estimate)"),
            sign(b.growth) + ils(kotlin.math.abs(b.growth)) + ((b.reported + b.depositedSince).takeIf { it > 0 }?.let { " (" + pct(b.growth / it * 100) + ")" } ?: ""), if (b.growth >= 0) UP else DOWN)
    }

    /** Where the numbers come from: the last report, Gemel-Net's last month, and when the coin prices were taken. */
    private fun sources(funds: List<Savings.Fund>): String? {
        val parts = mutableListOf<String>()
        funds.mapNotNull { it.lastReport?.date }.maxOrNull()?.let { parts += L.t("דוח אחרון ${FamilyItems.short(it)}", "last report ${FamilyItems.short(it)}") }
        funds.filter { it.kind != Savings.Kind.CRYPTO }.mapNotNull { off(it)?.latest?.takeIf { m -> m > 0 } }.maxOrNull()
            ?.let { parts += L.t("גמל נט עד ${GemelNet.latestLabel(Savings.Official(mapOf(it to 0.0)))}", "Gemel-Net to ${GemelNet.latestLabel(Savings.Official(mapOf(it to 0.0)))}") }
        if (funds.any { it.kind == Savings.Kind.CRYPTO }) CryptoPrices.cached(this)?.at?.takeIf { it > 0 }?.let { at ->
            val t = java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.systemDefault())
            val text = if (t.toLocalDate() == java.time.LocalDate.now()) "%02d:%02d".format(t.hour, t.minute) else FamilyItems.short(t.toLocalDate().toString())
            parts += L.t("מחירי קריפטו מ-$text", "crypto prices from $text")
        }
        val plain = funds.count { it.kind != Savings.Kind.CRYPTO && it.lastReport != null && it.gemelId == 0 }
        if (plain > 0) parts += if (plain == 1) L.t("קופה אחת לא נמצאה בגמל נט (יתרה + הפקדות)", "one fund not at Gemel-Net (balance + deposits)")
            else L.t("$plain קופות לא נמצאו בגמל נט (יתרה + הפקדות)", "$plain funds not at Gemel-Net (balance + deposits)")
        return if (parts.isEmpty()) null else L.t("מקורות: ", "Sources: ") + parts.joinToString(" · ") { "⁨$it⁩" }
    }

    private fun historyCard(points: List<Pair<String, Double>>): View {
        val card = ui.card(top = 12)
        card.setPadding(ui.dp(18), ui.dp(16), ui.dp(18), ui.dp(14))
        card.addView(ui.text(L.t("איך התיק גדל", "How it grew"), 16f, bold = true))
        val first = points.first(); val last = points.last()
        val change = last.second - first.second
        card.addView(ui.hint(L.t("מ-${FamilyItems.short(first.first)}: ${sign(change)}${ils(kotlin.math.abs(change))} (כולל הפקדות)", "Since ${FamilyItems.short(first.first)}: ${sign(change)}${ils(kotlin.math.abs(change))} (deposits included)"), top = 2))
        card.addView(HistoryChartView(this).apply {
            this.points = points; line = DS.primary(this@SavingsActivity); soft = DS.inkSoft(this@SavingsActivity); dotFill = DS.card(this@SavingsActivity)
            startLabel = FamilyItems.short(first.first); endLabel = L.t("היום", "today")
            contentDescription = points.joinToString(", ") { "${FamilyItems.short(it.first)}: ${ils(it.second)}" }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.dp(10) }
        })
        card.addView(ui.hint(L.t("נקודה מלאה: סכום היתרות בדוחות באותו תאריך · נקודה חלולה: הערכה להיום", "Full dot: the reports' balances on that date · hollow dot: today's estimate"), top = 6))
        return card
    }

    // ---- A goal for each person (kept on this phone) ----
    private fun goal(id: String) = Settings.prefs(this).getFloat("savings_goal_$id", 0f).toDouble()

    private fun goalRow(k: FamilyMember, sum: Double): View {
        val g = goal(k.id)
        val p = Savings.progress(sum, g)
        if (p == null) return ui.action(L.t("+ יעד חיסכון ל${k.name}", "+ A savings goal for ${k.name}")) { editGoal(k) }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12))
            background = DS.pressable(this@SavingsActivity, DS.rounded(DS.card(this@SavingsActivity), ui.dp(16).toFloat()))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.dp(8) }
            addView(LinearLayout(this@SavingsActivity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(TextView(this@SavingsActivity).apply { text = L.t("היעד: ${ils(g)}", "Goal: ${ils(g)}"); textSize = 13.5f; setTextColor(DS.ink(this@SavingsActivity)); typeface = DS.emphasis; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) })
                addView(TextView(this@SavingsActivity).apply { text = "${Math.round(p * 100)}%"; textSize = 13.5f; setTextColor(DS.primary(this@SavingsActivity)); typeface = DS.emphasis })
            })
            addView(LinearLayout(this@SavingsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                background = DS.rounded(DS.cardAlt(this@SavingsActivity), ui.dp(5).toFloat()); clipToOutline = true
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(10)).apply { topMargin = ui.dp(8) }
                addView(View(this@SavingsActivity).apply { background = DS.rounded(DS.primary(this@SavingsActivity), ui.dp(5).toFloat()); layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, p.toFloat().coerceAtLeast(0.01f)) })
                if (p < 1.0) addView(View(this@SavingsActivity).apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, (1 - p).toFloat()) })
            })
            addView(TextView(this@SavingsActivity).apply {
                text = if (p >= 1.0) L.t("הגעתם ליעד", "Goal reached") else L.t("חסרים ${ils(g - sum)}", "${ils(g - sum)} to go")
                textSize = 12f; setTextColor(DS.inkSoft(this@SavingsActivity)); setPadding(0, ui.dp(6), 0, 0)
            })
            contentDescription = L.t("יעד החיסכון של ${k.name}: ${ils(g)}, ${Math.round(p * 100)} אחוז. לשינוי", "${k.name}'s savings goal: ${ils(g)}, ${Math.round(p * 100)} percent. Change")
            A11y.button(this)
            setOnClickListener { editGoal(k) }
        }
    }

    private fun editGoal(k: FamilyMember) {
        val field = ui.field(L.t("סכום היעד ב-₪", "Goal in ₪"), goal(k.id).takeIf { it > 0 }?.let { Math.round(it).toString() }.orEmpty()).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(20), ui.dp(4), ui.dp(20), 0); addView(field) }
        AlertDialog.Builder(this)
            .setTitle(L.t("יעד חיסכון ל${k.name}", "A savings goal for ${k.name}"))
            .setMessage(L.t("למשל הסכום שתרצו שיהיה בגיל 18. נשמר רק בטלפון הזה.", "For example what you'd like there to be at 18. Kept on this phone only."))
            .setView(box)
            .setPositiveButton(L.t("שמירה", "Save")) { _, _ ->
                val v = FamilyItems.cleanAmount(field.text.toString()) ?: 0.0
                Settings.prefs(this).edit().putFloat("savings_goal_${k.id}", v.toFloat()).apply(); render()
            }
            .setNeutralButton(L.t("בלי יעד", "No goal")) { _, _ -> Settings.prefs(this).edit().remove("savings_goal_${k.id}").apply(); render() }
            .setNegativeButton(L.t("ביטול", "Cancel"), null).show()
    }

    // ---- The ring's colours: chosen per part (kept on this phone) ----
    private val COLOR_NAMES get() = listOf(L.t("כחול", "Blue"), L.t("תכלת", "Sky"), L.t("לבנדר", "Lavender"), L.t("טורקיז", "Teal"), L.t("משמש", "Apricot"), L.t("סגול", "Purple"))

    private fun colorOf(part: String, i: Int): Int =
        PALETTE[Settings.prefs(this).getInt("savings_color_$part", i % PALETTE.size).coerceIn(0, PALETTE.size - 1)]

    private fun pickColor(part: String) {
        AlertDialog.Builder(this).setTitle(L.t("צבע ל${part}", "A colour for $part"))
            .setItems(COLOR_NAMES.mapIndexed { i, n ->
                android.text.SpannableString("●  $n").apply { setSpan(android.text.style.ForegroundColorSpan(PALETTE[i]), 0, 1, 0) }
            }.toTypedArray<CharSequence>()) { _, i -> Settings.prefs(this).edit().putInt("savings_color_$part", i).apply(); render() }
            .show()
    }

    // ---- A one-page summary to share ----
    private fun sharePdf(funds: List<Savings.Fund>, people: List<FamilyMember>, today: String) {
        try {
            val year = today.take(4).toIntOrNull() ?: 0
            // The same numbers and colours as on the screen.
            val file = SavingsPdf.write(this, funds, people, today, est = { est(it, today) },
                ytd = { if (it.kind == Savings.Kind.CRYPTO) Savings.yearToDateCrypto(it, CryptoPrices.cached(this), year) else Savings.yearToDate(off(it), year) },
                color = { f, i -> colorOf(legendName(f), i) })
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", file)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), L.t("שיתוף הסיכום", "Share the summary")))
        } catch (_: Exception) {
            Toast.makeText(this, L.t("לא הצלחנו להכין את הסיכום", "Couldn't make the summary"), Toast.LENGTH_LONG).show()
        }
    }

    /** A fund in the ring's legend: the company, and the track on its own line (so "S&P 500" isn't split). */
    private fun legendName(f: Savings.Fund): String {
        val second = f.track.ifBlank { f.name }.ifBlank { f.kind.label }
        return if (f.provider.isBlank()) Savings.isolateLatin(second) else f.provider + "\n" + Savings.isolateLatin(second)
    }

    private fun coinLabel(sym: String) = when (sym) {
        "BTC" -> L.t("ביטקוין", "Bitcoin"); "ETH" -> L.t("אתריום", "Ethereum"); "SOL" -> L.t("סולנה", "Solana"); else -> sym
    }

    /** Savings in whole shekels (an estimate isn't precise to the agora). */
    private fun ils(v: Double) = FamilyItems.money(Math.rint(v))
}
