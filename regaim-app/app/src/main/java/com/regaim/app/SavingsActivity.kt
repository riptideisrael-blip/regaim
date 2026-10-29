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

    override fun onResume() {
        super.onResume()
        if (!FamilyFile.consented(this)) return
        render()
        FamilyFileSync.kick(this) { runOnUiThread { if (!isDestroyed) render() } }
        // The official monthly returns (public data, nothing about the family is sent).
        worker.execute { val got = try { GemelNet.refresh(applicationContext) } catch (_: Exception) { false }; if (got) runOnUiThread { if (!isDestroyed) render() } }
    }
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

        // The portfolio at a glance: a ring of what it's made of, and the three numbers that matter.
        content.addView(portfolio(funds, shownKids, today))

        content.addView(ui.button(if (busy) L.t("קוראים את הדוח…", "Reading the report…") else L.t("📄 העלאת דוח רבעוני", "📄 Upload a quarterly report"), primary = true, top = 14) {
            if (!busy) picker.launch(arrayOf("application/pdf", "image/*"))
        }.apply { isEnabled = !busy })
        content.addView(ui.hint(L.t("הקופה, המסלול והיתרה נקראים מהדוח, ואתם בודקים לפני שמירה.", "The fund, track and balance are read from the report; you check them before saving."), top = 4))

        for (k in shownKids) {
            val mine = funds.filter { it.child == k.id }
            val sum = mine.sumOf { Savings.estimate(it, today, off(it))?.total ?: 0.0 }
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
        // Both parents see the same savings when the family file is shared.
        if (PartnerShare.partner(this).isNotEmpty() && !FamilyFileSync.on(this)) content.addView(ui.hint(
            L.t("כדי שגם ההורה השני יראה את החיסכון ויוכל להעלות את הדוח הבא: מפעילים \"שיתוף התיק\" בתחתית תיק המשפחה (אצל שניכם).",
                "So the other parent sees the savings and can upload the next report: turn on \"Share the file\" at the bottom of the family file (on both phones)."), top = 16))
        if (kids.isEmpty()) content.addView(ui.hint(L.t("כדי לעקוב אחרי חיסכון מוסיפים קודם ילדים בפרופיל המשפחה.", "To follow savings, first add children in the family profile."), top = 16))
        content.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, ui.dp(24)) })
    }

    /** The fund's official months (Gemel-Net), kept on the phone. */
    private fun off(f: Savings.Fund) = GemelNet.cached(this, f.gemelId)

    private fun sign(v: Double) = if (v >= 0) "+" else "−"
    private fun pct(v: Double) = sign(v) + String.format(java.util.Locale.US, "%.1f", kotlin.math.abs(v)) + "%"

    private fun fundRow(f: Savings.Fund, today: String): View {
        val e = Savings.estimate(f, today, off(f))
        val meta = listOfNotNull(
            f.kind.label,
            e?.periodReturnPct?.let { L.t("תשואה בדוח: ${pct(it)}", "Report's return: ${pct(it)}") },
            e?.ratePct?.let { L.t("מאז הדוח: ${pct(it)} (גמל נט עד ${GemelNet.latestLabel(off(f))})", "Since the report: ${pct(it)} (Gemel-Net to ${GemelNet.latestLabel(off(f))})") }
                ?: if (f.lastReport != null && f.gemelId == 0) L.t("לא נמצאה בגמל נט: יתרה + הפקדות", "Not found at Gemel-Net: balance + deposits") else null,
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

    private val PALETTE = listOf(0xFF6F4E37.toInt(), 0xFFC0784A.toInt(), 0xFF7E9A7A.toInt(), 0xFF5B7A99.toInt(), 0xFFB58B3A.toInt(), 0xFF8E5A7A.toInt())
    private val UP = 0xFF3E7D4F.toInt()
    private val DOWN = 0xFFA4442F.toInt()

    /**
     * The portfolio card: a ring (by child for the whole family, by fund for one child) with the total in the middle
     * and a legend beside it; under it what's there, the year's return so far (official months, weighted by size)
     * and what goes in each month.
     */
    private fun portfolio(funds: List<Savings.Fund>, kids: List<FamilyMember>, today: String): View {
        val year = today.take(4).toIntOrNull() ?: 0
        val est = funds.associateWith { Savings.estimate(it, today, off(it)) }
        val total = est.values.sumOf { it?.total ?: 0.0 }
        val monthly = funds.sumOf { it.perMonth }
        val ytd = Savings.weighted(funds.map { (est[it]?.total ?: 0.0) to Savings.yearToDate(off(it), year) })
        val last12 = Savings.weighted(funds.map { (est[it]?.total ?: 0.0) to Savings.lastYear(off(it)) })
        val byChild = child.isEmpty() && kids.size > 1
        val parts: List<Pair<String, Double>> = if (byChild) kids.map { k -> k.name to funds.filter { it.child == k.id }.sumOf { est[it]?.total ?: 0.0 } }.filter { it.second > 0 }
            else funds.map { it.title to (est[it]?.total ?: 0.0) }.filter { it.second > 0 }

        val card = ui.card(top = 12)
        card.setPadding(ui.dp(18), ui.dp(16), ui.dp(18), ui.dp(16))
        card.addView(ui.text(if (child.isEmpty()) L.t("תיק החיסכון של הילדים", "The children's savings") else L.t("התיק של ${kids.firstOrNull()?.name.orEmpty()}", "${kids.firstOrNull()?.name.orEmpty()}'s savings"), 16f, bold = true))

        // The ring and its legend.
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.dp(10) } }
        row.addView(DonutView(this).apply {
            slices = parts.mapIndexed { i, p -> p.second to PALETTE[i % PALETTE.size] }
            center = if (total > 0) FamilyItems.money(total) else "—"
            caption = L.t("יש היום (הערכה)", "today (estimate)")
            track = DS.cardAlt(this@SavingsActivity); ink = DS.ink(this@SavingsActivity); soft = DS.inkSoft(this@SavingsActivity)
            contentDescription = L.t("יש היום ${FamilyItems.money(total)}", "Today ${FamilyItems.money(total)}") + ", " + parts.joinToString(", ") { "${it.first} ${FamilyItems.money(it.second)}" }
            layoutParams = LinearLayout.LayoutParams(ui.dp(150), ui.dp(150)).apply { marginEnd = ui.dp(14) }
        })
        val legend = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
        if (parts.isEmpty()) legend.addView(ui.hint(L.t("מעלים דוח רבעוני, והתיק יופיע כאן.", "Upload a quarterly report and the portfolio shows here."), top = 0))
        parts.forEachIndexed { i, (name, v) ->
            legend.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(0, ui.dp(4), 0, ui.dp(4))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                addView(View(this@SavingsActivity).apply { background = DS.rounded(PALETTE[i % PALETTE.size], ui.dp(5).toFloat()); layoutParams = LinearLayout.LayoutParams(ui.dp(10), ui.dp(10)).apply { marginEnd = ui.dp(8) } })
                addView(LinearLayout(this@SavingsActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    addView(TextView(this@SavingsActivity).apply { text = name; textSize = 13f; setTextColor(DS.ink(this@SavingsActivity)); maxLines = 2; ellipsize = TextUtils.TruncateAt.END; textDirection = View.TEXT_DIRECTION_FIRST_STRONG })
                    addView(TextView(this@SavingsActivity).apply {
                        text = "\u2068${FamilyItems.money(v)}\u2069 · ${Math.round(v / total * 100)}%"; textSize = 12f; setTextColor(DS.inkSoft(this@SavingsActivity))
                    })
                })
            })
        }
        row.addView(legend)
        card.addView(row)

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
        stat(L.t("יש בתיק", "In the portfolio"), FamilyItems.money(total), DS.ink(this), L.t("הערכה להיום", "estimate for today"), 0)
        stat(L.t("תשואה השנה", "Return this year"), ytd?.let { pct(it) } ?: "—", ytd?.let { if (it >= 0) UP else DOWN } ?: DS.inkSoft(this),
            last12?.let { L.t("12 חודשים: ${pct(it)}", "12 months: ${pct(it)}") } ?: L.t("גמל נט", "Gemel-Net"), 1)
        stat(L.t("הפקדה חודשית", "Each month"), FamilyItems.money(monthly), DS.ink(this), L.t("הורים + מדינה", "parents + state"), 2)
        card.addView(stats)

        card.addView(ui.hint(L.t("הערכה: היתרה בדוח האחרון לפי התשואות החודשיות הרשמיות (גמל נט, משרד האוצר) ועוד ההפקדות. תשואות עבר אינן מבטיחות תשואות בעתיד. מידע בלבד, לא המלצה.",
            "Estimate: the last report's balance at the official monthly returns (Gemel-Net, Ministry of Finance) plus the deposits. Past returns don't promise future ones. Information only, not advice."), top = 12))
        return card
    }
}
