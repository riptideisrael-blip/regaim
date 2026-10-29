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
        findViewById<TextView>(R.id.screenTitle).text = L.t("חיסכון לילדים", "Children's savings")
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

    override fun onResume() { super.onResume(); if (FamilyFile.consented(this)) render() }
    override fun onDestroy() { worker.shutdownNow(); super.onDestroy() }

    private fun amount(text: String, size: Float, color: Int) = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(color); typeface = Typeface.create(DS.emphasis, Typeface.BOLD)
        textDirection = View.TEXT_DIRECTION_LTR; maxLines = 1
    }

    private fun render() {
        content.removeAllViews()
        val today = java.time.LocalDate.now().toString()
        val kids = Profile.members(this).filter { it.isChild }
        val all = FamilyFile.savings(this)
        val shownKids = if (child.isEmpty()) kids else kids.filter { it.id == child }
        val funds = if (child.isEmpty()) all else all.filter { it.child == child }

        if (kids.size > 1) {
            val labels = listOf(L.t("כולם", "Everyone")) + kids.map { it.name }
            val sel = kids.indexOfFirst { it.id == child }.let { if (it < 0) 0 else it + 1 }
            content.addView(ui.chips(labels, sel, top = 12) { i -> child = if (i == 0) "" else kids[i - 1].id; render() })
        }

        // The headline: how much there is now (an estimate, said so).
        val total = funds.sumOf { Savings.estimate(it, today)?.total ?: 0.0 }
        content.addView(ui.card(top = 12).apply {
            setPadding(ui.dp(20), ui.dp(18), ui.dp(20), ui.dp(18))
            background = DS.rounded(DS.primary(this@SavingsActivity), ui.dp(22).toFloat())
            addView(TextView(this@SavingsActivity).apply { text = L.t("יש היום בחיסכון (הערכה)", "In savings today (estimate)"); textSize = 13f; setTextColor(0xCCFFFFFF.toInt()) })
            addView(amount(FamilyItems.money(total), 30f, 0xFFFFFFFF.toInt()).apply { setPadding(0, ui.dp(2), 0, 0) })
            val monthly = funds.sumOf { it.perMonth }
            addView(TextView(this@SavingsActivity).apply {
                text = listOfNotNull(
                    if (monthly > 0) L.t("נכנסים ${FamilyItems.money(monthly)} בחודש", "${FamilyItems.money(monthly)} goes in each month") else null,
                    L.t("לפי הדוח האחרון של כל קופה, ועוד ההפקדות מאז. התשואה מתעדכנת בדוח הבא.", "From each fund's last report, plus the deposits since. Returns update with the next report."),
                ).joinToString("\n")
                textSize = 12.5f; setTextColor(0xE6FFFFFF.toInt()); setPadding(0, ui.dp(6), 0, 0)
            })
        })

        content.addView(ui.button(if (busy) L.t("קוראים את הדוח…", "Reading the report…") else L.t("📄 העלאת דוח רבעוני", "📄 Upload a quarterly report"), primary = true, top = 14) {
            if (!busy) picker.launch(arrayOf("application/pdf", "image/*"))
        }.apply { isEnabled = !busy })
        content.addView(ui.hint(L.t("הקופה, המסלול והיתרה נקראים מהדוח, ואתם בודקים לפני שמירה.", "The fund, track and balance are read from the report; you check them before saving."), top = 4))

        for (k in shownKids) {
            val mine = funds.filter { it.child == k.id }
            val sum = mine.sumOf { Savings.estimate(it, today)?.total ?: 0.0 }
            content.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.dp(24) }
                addView(ui.text(k.name, 18f, bold = true).apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f); androidx.core.view.ViewCompat.setAccessibilityHeading(this, true) })
                if (sum > 0) addView(amount(FamilyItems.money(sum), 17f, DS.ink(this@SavingsActivity)))
            })
            val card = ui.card(top = 8)
            card.setPadding(ui.dp(14), ui.dp(4), ui.dp(14), ui.dp(4))
            if (mine.isEmpty()) card.addView(ui.hint(L.t("עוד אין קופה. מעלים דוח, או מוסיפים ידנית.", "No fund yet. Upload a report, or add one by hand."), top = 12).apply { setPadding(0, 0, 0, ui.dp(12)) })
            mine.forEachIndexed { n, f ->
                if (n > 0) card.addView(View(this).apply { setBackgroundColor(0xFFE8DED3.toInt()); layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1) })
                card.addView(fundRow(f, today))
            }
            card.addView(ui.action(L.t("+ קופה ל${k.name}", "+ A fund for ${k.name}")) { SavingsFundActivity.newFor(this, k.id) })
            content.addView(card)
        }
        if (kids.isEmpty()) content.addView(ui.hint(L.t("כדי לעקוב אחרי חיסכון מוסיפים קודם ילדים בפרופיל המשפחה.", "To follow savings, first add children in the family profile."), top = 16))
        content.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, ui.dp(24)) })
    }

    private fun fundRow(f: Savings.Fund, today: String): View {
        val e = Savings.estimate(f, today)
        val meta = listOfNotNull(
            f.kind.label,
            f.lastReport?.let { L.t("דוח ${FamilyItems.short(it.date)}: ${FamilyItems.money(it.balance)}", "Report ${FamilyItems.short(it.date)}: ${FamilyItems.money(it.balance)}") }
                ?: L.t("עוד אין דוח", "No report yet"),
            if (f.perMonth > 0) L.t("+${FamilyItems.money(f.perMonth)} בחודש", "+${FamilyItems.money(f.perMonth)} a month") else null,
        )
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
            })
            e?.let { addView(amount(FamilyItems.money(it.total), 15f, DS.ink(this@SavingsActivity))) }
            contentDescription = (listOf(f.title) + meta + listOfNotNull(e?.let { FamilyItems.money(it.total) })).joinToString(", ")
            setOnClickListener { SavingsFundActivity.open(this@SavingsActivity, f.id) }
        }
    }

    /** A report: read on request (signed in), then the fund screen opens with what was read, for the parent to check. */
    private fun readReport(uri: android.net.Uri) {
        busy = true; render()
        worker.execute {
            val type = try { contentResolver.getType(uri) } catch (_: Exception) { null }.orEmpty()
            val b = try { contentResolver.openInputStream(uri)?.use { it.readBytes() } } catch (_: Exception) { null }
            if (b == null || b.size > 20 * 1024 * 1024) { runOnUiThread { busy = false; render(); Toast.makeText(this, L.t("לא הצלחנו לקרוא את הקובץ (עד 20MB)", "Couldn't read the file (up to 20 MB)"), Toast.LENGTH_LONG).show() }; return@execute }
            val pdf = type == "application/pdf" || (b.size > 4 && String(b, 0, 4) == "%PDF")
            val mime = if (pdf) "application/pdf" else "image/jpeg"
            val kids = Profile.members(applicationContext).filter { it.isChild }
            // The report is kept in the family file (a document of that child, once known).
            val doc = FamilyFile.save(applicationContext, FamilyItems.Item("", FamilyItems.Kind.DOCUMENT, child = child, title = L.t("דוח חיסכון", "Savings report"),
                date = java.time.LocalDate.now().toString(), mime = mime, hash = FamilyFile.hash(b), source = "savings"), b, if (pdf) "pdf" else "jpg")
            val reading = if (!Account.signedIn(applicationContext)) null else try {
                val pic = if (pdf) null else FamilyFile.picture(applicationContext, b, mime)?.let { FamilyFile.jpegBase64(it) }
                val pdfB64 = if (pdf && b.size < 8 * 1024 * 1024) android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP) else null
                Savings.reading(ClaudeClient.readSavingsReport(applicationContext, pic, pdfB64, kids.map { it.name }, java.time.LocalDate.now().toString()))
            } catch (_: Exception) { null }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                busy = false; render()
                if (reading == null) Toast.makeText(this, if (!Account.signedIn(this)) L.t("קריאה אוטומטית דורשת התחברות. ממלאים ידנית.", "Reading needs you to sign in. Fill it in by hand.")
                    else L.t("לא הצלחנו לקרוא את הדוח. ממלאים ידנית.", "Couldn't read the report. Fill it in by hand."), Toast.LENGTH_LONG).show()
                SavingsFundActivity.fromReport(this, reading, doc.id, child)
            }
        }
    }
}
