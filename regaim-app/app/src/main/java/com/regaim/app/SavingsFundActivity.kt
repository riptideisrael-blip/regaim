package com.regaim.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/**
 * One savings fund of a child: whose, which kind, which company and track, what goes in each month, and its reports.
 * Opened after a report was read (the details filled in, for the parent to check), for a new fund, or to change one.
 */
class SavingsFundActivity : AppCompatActivity() {
    companion object {
        private const val EXTRA_ID = "fund_id"
        private const val EXTRA_CHILD = "child"
        private const val EXTRA_READING = "reading"
        private const val EXTRA_DOC = "doc"

        fun open(ctx: Context, id: String) = ctx.startActivity(Intent(ctx, SavingsFundActivity::class.java).putExtra(EXTRA_ID, id))
        fun newFor(ctx: Context, child: String) = ctx.startActivity(Intent(ctx, SavingsFundActivity::class.java).putExtra(EXTRA_CHILD, child))
        fun fromReport(ctx: Context, r: Savings.Reading?, docId: String, child: String) {
            val o = org.json.JSONObject()
            if (r != null) o.put("provider", r.provider).put("fund_name", r.name).put("track", r.track).put("report_date", r.date)
                .put("balance", r.balance ?: org.json.JSONObject.NULL).put("deposits_in_period", r.deposits ?: org.json.JSONObject.NULL)
                .put("parent_monthly_deposit", r.monthly ?: org.json.JSONObject.NULL).put("state_monthly_deposit", r.stateMonthly ?: org.json.JSONObject.NULL)
                .put("return_pct", r.returnPct ?: org.json.JSONObject.NULL).put("fees_pct", r.feesPct ?: org.json.JSONObject.NULL)
                .put("kind", r.kind?.id ?: org.json.JSONObject.NULL).put("child_name", r.childName)
                .put("value_usd", r.valueUsd ?: org.json.JSONObject.NULL)
                .put("coins", org.json.JSONArray().apply { r.coins.forEach { put(org.json.JSONObject().put("symbol", it.symbol).put("units", it.units).put("monthly_ils", it.monthly)) } }).put("period_months", r.months ?: org.json.JSONObject.NULL)
            ctx.startActivity(Intent(ctx, SavingsFundActivity::class.java).putExtra(EXTRA_READING, o.toString()).putExtra(EXTRA_DOC, docId).putExtra(EXTRA_CHILD, child))
        }
    }

    private lateinit var ui: Ui
    private lateinit var content: LinearLayout
    private var fund = Savings.Fund("", "")
    private var isNew = true
    /** A report waiting to be saved with the fund (read from a document, or typed). */
    private var pending: Savings.Report? = null
    private var matchedExisting = false

    private var fProvider: EditText? = null
    private var fName: EditText? = null
    private var fTrack: EditText? = null
    private var fMonthly: EditText? = null
    private var fState: EditText? = null
    private var fDate: EditText? = null
    private var fBalance: EditText? = null
    private var reading = false
    /** What was just read is newer than the fields on screen: the next render doesn't read them back. */
    private var fresh = false
    /** The balance was worked out from the coins' prices (said next to it). */
    private var computed = false

    private val picker = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        fromFields(); reading = true; render()
        SavingsReports.read(this, uri, fund.child) { r, docId ->
            reading = false
            if (r != null) apply(r, docId) else if (docId.isNotEmpty()) pending = (pending ?: Savings.Report("", Double.NaN)).copy(doc = docId)
            fresh = true; render()
        }
    }

    /** What a report or statement says, onto this fund: its date and balance (crypto: the coins' units, and the balance at that day's prices). */
    private fun apply(r: Savings.Reading, docId: String) {
        if (fund.kind == Savings.Kind.CRYPTO && r.coins.isNotEmpty()) fund = fund.copy(coins = r.coins.map { c ->
            c.copy(monthly = if (c.monthly > 0) c.monthly else fund.coins.firstOrNull { it.symbol == c.symbol }?.monthly ?: 0.0) } +
            fund.coins.filter { f -> r.coins.none { it.symbol == f.symbol } })
        else if (fund.kind != Savings.Kind.CRYPTO) {
            r.monthly?.let { fund = fund.copy(monthly = it) }
            r.stateMonthly?.let { fund = fund.copy(stateMonthly = it) }
        }
        val units = r.coins.associate { it.symbol to it.units }
        val date = r.date.ifEmpty { java.time.LocalDate.now().toString() }
        val worth = if (r.balance == null && fund.kind == Savings.Kind.CRYPTO) Savings.valueOn(units, date, CryptoPrices.cached(this)) else null
        computed = worth != null
        pending = Savings.Report(date, r.balance ?: worth ?: Double.NaN, r.deposits, r.returnPct, r.feesPct, docId, r.months, units)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_screen)
        findViewById<View>(R.id.btnClose).setOnClickListener { finish() }
        content = findViewById(R.id.screenContent)
        ui = Ui(this)
        val kids = Profile.members(this).filter { !it.isPet } /* parents too, not only the children */
        val funds = FamilyFile.savings(this)
        intent.getStringExtra(EXTRA_ID)?.let { id ->
            fund = funds.firstOrNull { it.id == id } ?: run { finish(); return }
            isNew = false
        }
        if (isNew) fund = fund.copy(child = intent.getStringExtra(EXTRA_CHILD).orEmpty().ifEmpty { kids.singleOrNull()?.id.orEmpty() })
        intent.getStringExtra(EXTRA_READING)?.let { text ->
            val r = Savings.reading(try { org.json.JSONObject(text) } catch (_: Exception) { org.json.JSONObject() })
            val child = kids.firstOrNull { r.childName.isNotBlank() && (Profile.sameName(it.name, r.childName) || Profile.sameName(PitzkiScript.firstName(it.name), PitzkiScript.firstName(r.childName))) }?.id ?: fund.child
            val match = Savings.match(funds, child, r.provider, r.track)
            if (match != null) {
                fund = match; isNew = false; matchedExisting = true
                // A crypto statement: the units it shows (and the monthly buys, when it shows them).
                if (fund.kind == Savings.Kind.CRYPTO && r.coins.isNotEmpty()) fund = fund.copy(coins = r.coins.map { c ->
                    c.copy(monthly = if (c.monthly > 0) c.monthly else fund.coins.firstOrNull { it.symbol == c.symbol }?.monthly ?: 0.0) })
            }
            // What goes in each month: as the report says, or its period's deposits spread over the months.
            else fund = fund.copy(child = child, kind = r.kind ?: fund.kind, provider = r.provider, name = r.name, track = r.track,
                monthly = if (r.kind == Savings.Kind.CRYPTO) 0.0 else r.monthly ?: (if (r.stateMonthly == null) r.perMonth ?: 0.0 else 0.0),
                stateMonthly = r.stateMonthly ?: 0.0, coins = if (r.kind == Savings.Kind.CRYPTO) r.coins else emptyList())
            pending = Savings.Report(r.date, r.balance ?: Double.NaN, r.deposits, r.returnPct, r.feesPct, intent.getStringExtra(EXTRA_DOC).orEmpty(), r.months,
                r.coins.associate { it.symbol to it.units })
        }
        render()
    }

    private fun num(e: EditText?) = FamilyItems.cleanAmount(e?.text?.toString())

    /** Crypto: each coin's units and monthly buys. */
    private val coinFields = linkedMapOf<String, Pair<EditText, EditText>>()

    private fun fromFields() {
        fProvider ?: return
        if (fund.kind == Savings.Kind.CRYPTO && coinFields.isNotEmpty()) {
            fund = fund.copy(coins = coinFields.map { (sym, f) -> Savings.Coin(sym, num(f.first) ?: 0.0, num(f.second) ?: 0.0) })
            pending?.let { p -> pending = p.copy(coins = fund.coins.associate { it.symbol to it.units }) }
        }
        fund = fund.copy(provider = fProvider?.text?.toString().orEmpty().trim(), name = fName?.text?.toString().orEmpty().trim(),
            track = fTrack?.text?.toString().orEmpty().trim(), monthly = num(fMonthly) ?: 0.0, stateMonthly = if (fState != null) num(fState) ?: 0.0 else fund.stateMonthly)
        pending?.let { p ->
            pending = p.copy(date = fDate?.text?.toString().orEmpty().let { FamilyItems.cleanDate(it).ifEmpty { if (it.isBlank()) "" else p.date } },
                balance = num(fBalance) ?: Double.NaN)
        }
    }

    private fun field(label: String, value: String, number: Boolean = false): EditText {
        content.addView(ui.text(label.substringBefore(" ("), 13f, R.color.ink_soft, bold = true, top = 12))
        return ui.field(label, value, top = 4).apply {
            if (number) inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            content.addView(this)
        }
    }

    private fun money(v: Double) = if (v == 0.0 || v.isNaN()) "" else FamilyItems.money(v).removeSuffix(" ₪")

    private fun render() {
        if (fProvider != null && !fresh) fromFields()
        fresh = false
        content.removeAllViews()
        findViewById<TextView>(R.id.screenTitle).text = if (isNew) L.t("קופת חיסכון", "Savings fund") else fund.title
        val kids = Profile.members(this).filter { !it.isPet } /* parents too, not only the children */

        if (matchedExisting) content.addView(ui.card(top = 12).apply {
            addView(ui.text(L.t("הדוח נוסף לקופה שכבר קיימת", "The report goes to an existing fund"), 15f, bold = true))
            addView(ui.hint(L.t("זיהינו את הקופה לפי החברה והמסלול. בדקו את התאריך והיתרה ולחצו שמירה.", "Matched by company and track. Check the date and balance, then Save."), top = 2))
        }) else if (pending != null) content.addView(ui.hint(L.t("מה שנקרא מהדוח מולא כאן. בדקו ותקנו לפני שמירה.", "What was read from the report is filled in. Check and fix before saving."), top = 12))

        content.addView(ui.text(L.t("של מי?", "Whose?"), 15f, bold = true, top = 14))
        if (kids.isNotEmpty()) content.addView(ui.chips(kids.map { it.name }, kids.indexOfFirst { it.id == fund.child }, top = 6) { i -> fromFields(); fund = fund.copy(child = kids[i].id); render() })
        content.addView(ui.text(L.t("סוג החיסכון", "Kind of savings"), 15f, bold = true, top = 14))
        val kinds = Savings.Kind.values().toList()
        content.addView(ui.chips(kinds.map { it.label }, kinds.indexOf(fund.kind), top = 6) { i -> fromFields(); fund = fund.copy(kind = kinds[i]); render() })

        fProvider = field(L.t("החברה המנהלת (למשל: מור, הראל, אלטשולר שחם)", "Managing company (e.g. Mor, Harel)"), fund.provider)
        coinFields.clear()
        if (fund.kind == Savings.Kind.CRYPTO) {
            fTrack = null; fMonthly = null; fState = null
            fName = field(L.t("שם החשבון (אם יש)", "Account name (if any)"), fund.name)
            // Each coin: how many units (as in the statement) and what's bought each month.
            content.addView(ui.section(L.t("המטבעות", "The coins")))
            for (c in fund.coins) {
                content.addView(ui.text(coinName(c.symbol), 15f, bold = true, top = 12))
                val units = field(L.t("כמה יחידות (כמו בדוח)", "Units (as in the statement)"), if (c.units > 0) c.units.toBigDecimal().stripTrailingZeros().toPlainString() else "", number = true)
                val monthly = field(L.t("קנייה חודשית ב-₪ (הוראת קבע)", "Bought each month in ₪ (standing order)"), money(c.monthly), number = true)
                coinFields[c.symbol] = units to monthly
            }
            val more = listOf("BTC", "ETH", "SOL", "XRP", "ADA", "DOGE").filter { s -> fund.coins.none { it.symbol == s } }
            if (more.isNotEmpty()) {
                content.addView(ui.text(L.t("+ הוספת מטבע", "+ Add a coin"), 13f, R.color.ink_soft, bold = true, top = 12))
                content.addView(ui.chips(more.map { coinName(it) }, -1, top = 4) { i -> fromFields(); fund = fund.copy(coins = fund.coins + Savings.Coin(more[i])); render() })
            }
        } else {
            fTrack = field(L.t("מסלול (למשל: מניות, כללי, הלכה)", "Track (e.g. stocks, general)"), fund.track)
            fName = field(L.t("שם הקופה או התוכנית (אם יש)", "Fund or plan name (if any)"), fund.name)
            fMonthly = field(L.t("כמה אתם מפקידים בחודש ב-₪", "What you deposit each month in ₪"), money(fund.monthly), number = true)
            fState = if (fund.kind == Savings.Kind.CHILD_SAVINGS) field(L.t("כמה המדינה מפקידה בחודש ב-₪ (כתוב בדוח)", "What the state deposits each month in ₪ (in the report)"), money(fund.stateMonthly), number = true) else null
        }

        // The report being added (read, or typed by hand).
        content.addView(ui.section(L.t("יתרה לפי דוח", "Balance from a report")))
        // Upload the statement or report itself: the date and balance (crypto: the units, and the balance in ₪) are filled in from it.
        content.addView(ui.button(if (reading) L.t("קוראים את הדוח…", "Reading…")
            else if (fund.kind == Savings.Kind.CRYPTO) L.t("📄 העלאת דף חשבון (PDF או צילום)", "📄 Upload a statement (PDF or photo)") else L.t("📄 העלאת דוח (PDF או צילום)", "📄 Upload a report (PDF or photo)"), top = 8) {
            if (!reading) picker.launch(SavingsReports.TYPES)
        }.apply { isEnabled = !reading })
        val p = pending
        if (p != null) {
            fDate = field(L.t("תאריך הדוח (יום.חודש.שנה)", "Report date (day.month.year)"), FamilyItems.short(p.date))
            fBalance = field(L.t("היתרה בדוח ב-₪", "Balance in the report in ₪"), money(p.balance), number = true)
            if (fund.kind == Savings.Kind.CRYPTO) content.addView(ui.hint(if (computed) L.t("חושב לפי היחידות בדוח ומחירי המטבעות ביום הדוח (CoinGecko). אפשר לתקן לסכום שכתוב בדוח.", "Worked out from the statement's units at that day's prices (CoinGecko). You can fix it to the amount in the statement.")
                else L.t("אפשר להשאיר ריק: יחושב לפי מחירי המטבעות ביום הדוח.", "Can stay empty: worked out from the coins' prices on the report's date."), top = 4))
            val extra = listOfNotNull(p.deposits?.let { L.t("הופקדו בתקופה: ${FamilyItems.money(it)}", "Deposited in the period: ${FamilyItems.money(it)}") },
                p.returnPct?.let { L.t("תשואה בתקופה: $it%", "Return in the period: $it%") }, p.feesPct?.let { L.t("דמי ניהול: $it%", "Management fee: $it%") })
            if (extra.isNotEmpty()) content.addView(ui.hint(extra.joinToString(" · "), top = 6))
        } else {
            fDate = null; fBalance = null
            content.addView(ui.action(L.t("+ הוספת יתרה מדוח (ידנית)", "+ Add a balance from a report (by hand)")) { fromFields(); pending = Savings.Report("", Double.NaN); render() })
        }
        if (fund.reports.isNotEmpty()) {
            content.addView(ui.text(L.t("דוחות קודמים", "Earlier reports"), 15f, bold = true, top = 14))
            val card = ui.card(top = 6)
            fund.reports.sortedByDescending { it.date }.forEach { r ->
                card.addView(ui.row(L.t("${FamilyItems.short(r.date)} · ${FamilyItems.money(r.balance)}", "${FamilyItems.short(r.date)} · ${FamilyItems.money(r.balance)}"),
                    if (docOf(r.doc) != null) L.t("הדוח ›", "Report ›") else "", top = 0) { docOf(r.doc)?.let { FamilyItemActivity.open(this, it.id) } })
            }
            content.addView(card)
        }
        // Where today's amount comes from.
        val o = GemelNet.cached(this, fund.gemelId)
        if (fund.kind == Savings.Kind.CRYPTO) content.addView(ui.hint(L.t("כמה יש היום = היחידות מהדוח במחיר של היום, ועוד הקניות החודשיות מאז (כל אחת לפי המחיר ביום הקנייה, בשווי של היום). מחירים: CoinGecko, בשקלים. הערכה, לא המלצה.",
            "Today = the statement's units at today's price, plus the monthly buys since (each at its day's price, valued today). Prices: CoinGecko, in shekels. An estimate, not advice."), top = 10))
        else content.addView(ui.hint(if (fund.gemelId > 0) L.t("תשואות רשמיות: ${fund.gemelName} (קופה ${fund.gemelId}, גמל נט${GemelNet.latestLabel(o).let { if (it.isEmpty()) "" else " עד $it" }}). כמה יש היום = היתרה בדוח האחרון לפי התשואות האלה, ועוד ההפקדות. הערכה, לא המלצה.",
                "Official returns: ${fund.gemelName} (fund ${fund.gemelId}, Gemel-Net${GemelNet.latestLabel(o).let { if (it.isEmpty()) "" else " to $it" }}). Today = the last report's balance at these returns, plus the deposits. An estimate, not advice.")
            else L.t("הקופה עוד לא זוהתה בגמל נט (לפי החברה והמסלול). עד אז: כמה יש היום = היתרה בדוח האחרון ועוד ההפקדות, בלי ניחוש תשואה.",
                "Not found at Gemel-Net yet (by company and track). Until then: today = the last report's balance plus the deposits, no guessed return."), top = 10))

        content.addView(ui.button(L.t("שמירה", "Save"), primary = true, top = 18) { save() })
        if (!isNew) content.addView(ui.action(L.t("מחיקת הקופה", "Delete the fund"), R.color.ds_error) {
            AlertDialog.Builder(this).setTitle(L.t("למחוק את הקופה?", "Delete the fund?"))
                .setMessage(L.t("הקופה והיתרות שלה יימחקו מהאפליקציה. הדוחות נשארים בתיק המשפחה.", "The fund and its balances are removed from the app. The reports stay in the family file."))
                .setPositiveButton(L.t("מחיקה", "Delete")) { _, _ -> FamilyFile.deleteFund(this, fund.id); FamilyFileSync.kick(this, force = true); finish() }
                .setNegativeButton(L.t("ביטול", "Cancel"), null).show()
        })
        content.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, ui.dp(24)) })
    }

    /** The report's document here: this phone's own, or the one that came from the other parent ("p_…"), either way. */
    private fun docOf(id: String): FamilyItems.Item? = if (id.isEmpty()) null
        else FamilyFile.get(this, id) ?: FamilyFile.get(this, FamilyShare.localId(id)) ?: FamilyFile.get(this, id.removePrefix("p_"))

    private fun save() {
        fromFields()
        if (fund.child.isEmpty()) { Toast.makeText(this, L.t("בחרו של מי הקופה", "Choose whose fund it is"), Toast.LENGTH_SHORT).show(); return }
        if (fund.provider.isBlank() && fund.name.isBlank()) { Toast.makeText(this, L.t("כתבו את שם החברה או הקופה", "Write the company or fund name"), Toast.LENGTH_SHORT).show(); return }
        // A crypto statement without a shekel balance: worked out from the coins' prices on its date.
        pending?.let { pr ->
            if (fund.kind == Savings.Kind.CRYPTO && pr.balance.isNaN() && pr.date.isNotEmpty()) {
                val units = pr.coins.ifEmpty { fund.coins.associate { it.symbol to it.units } }
                Savings.valueOn(units, pr.date, CryptoPrices.cached(this))?.let { pending = pr.copy(balance = it, coins = units) }
            }
        }
        val p = pending
        if (p != null && (p.date.isEmpty() || p.balance.isNaN())) {
            Toast.makeText(this, L.t("חסרים תאריך או יתרה בדוח (תאריך: יום.חודש.שנה)", "The report's date or balance is missing (date: day.month.year)"), Toast.LENGTH_LONG).show(); return
        }
        // A different company, track or kind: found again at Gemel-Net.
        val before = FamilyFile.savings(this).firstOrNull { it.id == fund.id }
        if (before != null && (before.provider != fund.provider || before.track != fund.track || before.kind != fund.kind)) fund = fund.copy(gemelId = 0, gemelName = "")
        val saved = FamilyFile.saveFund(this, (if (p != null) Savings.withReport(fund, p) else fund).copy(updated = System.currentTimeMillis()))
        FamilyFileSync.kick(this, force = true)
        // The report document: now filed under the child, with a clear title.
        if (p != null && p.doc.isNotEmpty()) FamilyFile.get(this, p.doc)?.let { d ->
            FamilyFile.save(this, d.copy(child = saved.child, title = L.t("דוח חיסכון · ${saved.title} · ${FamilyItems.short(p.date)}", "Savings report · ${saved.title} · ${FamilyItems.short(p.date)}"), date = p.date))
        }
        Toast.makeText(this, L.t("נשמר ✓", "Saved ✓"), Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun coinName(sym: String) = when (sym) {
        "BTC" -> L.t("ביטקוין (BTC)", "Bitcoin (BTC)"); "ETH" -> L.t("אתריום (ETH)", "Ethereum (ETH)"); "SOL" -> L.t("סולנה (SOL)", "Solana (SOL)")
        "XRP" -> "XRP"; "ADA" -> L.t("קרדנו (ADA)", "Cardano (ADA)"); "DOGE" -> L.t("דוג'קוין (DOGE)", "Dogecoin (DOGE)"); else -> sym
    }
}
