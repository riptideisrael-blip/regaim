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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_screen)
        findViewById<View>(R.id.btnClose).setOnClickListener { finish() }
        content = findViewById(R.id.screenContent)
        ui = Ui(this)
        val kids = Profile.members(this).filter { it.isChild }
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
            if (match != null) { fund = match; isNew = false; matchedExisting = true }
            else fund = fund.copy(child = child, kind = r.kind ?: fund.kind, provider = r.provider, name = r.name, track = r.track,
                monthly = r.monthly ?: 0.0, stateMonthly = r.stateMonthly ?: 0.0)
            pending = Savings.Report(r.date, r.balance ?: Double.NaN, r.deposits, r.returnPct, r.feesPct, intent.getStringExtra(EXTRA_DOC).orEmpty())
        }
        render()
    }

    private fun num(e: EditText?) = FamilyItems.cleanAmount(e?.text?.toString())

    private fun fromFields() {
        fProvider ?: return
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
        if (fProvider != null) fromFields()
        content.removeAllViews()
        findViewById<TextView>(R.id.screenTitle).text = if (isNew) L.t("קופת חיסכון", "Savings fund") else fund.title
        val kids = Profile.members(this).filter { it.isChild }

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
        fTrack = field(L.t("מסלול (למשל: מניות, כללי, הלכה)", "Track (e.g. stocks, general)"), fund.track)
        fName = field(L.t("שם הקופה או התוכנית (אם יש)", "Fund or plan name (if any)"), fund.name)
        fMonthly = field(L.t("כמה אתם מפקידים בחודש ב-₪", "What you deposit each month in ₪"), money(fund.monthly), number = true)
        fState = if (fund.kind == Savings.Kind.CHILD_SAVINGS) field(L.t("כמה המדינה מפקידה בחודש ב-₪ (כתוב בדוח)", "What the state deposits each month in ₪ (in the report)"), money(fund.stateMonthly), number = true) else null

        // The report being added (read, or typed by hand).
        content.addView(ui.section(L.t("יתרה לפי דוח", "Balance from a report")))
        val p = pending
        if (p != null) {
            fDate = field(L.t("תאריך הדוח (יום.חודש.שנה)", "Report date (day.month.year)"), FamilyItems.short(p.date))
            fBalance = field(L.t("היתרה בדוח ב-₪", "Balance in the report in ₪"), money(p.balance), number = true)
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
                    if (r.doc.isNotEmpty()) L.t("הדוח ›", "Report ›") else "", top = 0) { if (r.doc.isNotEmpty()) FamilyItemActivity.open(this, r.doc) })
            }
            content.addView(card)
        }
        content.addView(ui.hint(L.t("כמה יש היום = היתרה בדוח האחרון ועוד ההפקדות מאז. התשואה האמיתית מתעדכנת בדוח הבא.", "Today's amount = the last report's balance plus the deposits since. The real return updates with the next report."), top = 10))

        content.addView(ui.button(L.t("שמירה", "Save"), primary = true, top = 18) { save() })
        if (!isNew) content.addView(ui.action(L.t("מחיקת הקופה", "Delete the fund"), R.color.ds_error) {
            AlertDialog.Builder(this).setTitle(L.t("למחוק את הקופה?", "Delete the fund?"))
                .setMessage(L.t("הקופה והיתרות שלה יימחקו מהאפליקציה. הדוחות נשארים בתיק המשפחה.", "The fund and its balances are removed from the app. The reports stay in the family file."))
                .setPositiveButton(L.t("מחיקה", "Delete")) { _, _ -> FamilyFile.deleteFund(this, fund.id); finish() }
                .setNegativeButton(L.t("ביטול", "Cancel"), null).show()
        })
        content.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, ui.dp(24)) })
    }

    private fun save() {
        fromFields()
        if (fund.child.isEmpty()) { Toast.makeText(this, L.t("בחרו של איזה ילד הקופה", "Choose which child the fund is for"), Toast.LENGTH_SHORT).show(); return }
        if (fund.provider.isBlank() && fund.name.isBlank()) { Toast.makeText(this, L.t("כתבו את שם החברה או הקופה", "Write the company or fund name"), Toast.LENGTH_SHORT).show(); return }
        val p = pending
        if (p != null && (p.date.isEmpty() || p.balance.isNaN())) {
            Toast.makeText(this, L.t("חסרים תאריך או יתרה בדוח (תאריך: יום.חודש.שנה)", "The report's date or balance is missing (date: day.month.year)"), Toast.LENGTH_LONG).show(); return
        }
        val saved = FamilyFile.saveFund(this, if (p != null) Savings.withReport(fund, p) else fund)
        // The report document: now filed under the child, with a clear title.
        if (p != null && p.doc.isNotEmpty()) FamilyFile.get(this, p.doc)?.let { d ->
            FamilyFile.save(this, d.copy(child = saved.child, title = L.t("דוח חיסכון · ${saved.title} · ${FamilyItems.short(p.date)}", "Savings report · ${saved.title} · ${FamilyItems.short(p.date)}"), date = p.date))
        }
        Toast.makeText(this, L.t("נשמר ✓", "Saved ✓"), Toast.LENGTH_SHORT).show()
        finish()
    }
}
