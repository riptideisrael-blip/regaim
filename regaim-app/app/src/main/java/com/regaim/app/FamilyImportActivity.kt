package com.regaim.app

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.time.LocalDate
import java.util.concurrent.Executors

/**
 * Imports a coupon spreadsheet (.xlsx) into the family file: reads it on the phone, shows every row grouped by chain
 * for the parent to check (expired ones and ones already in the file start unticked; unclear amounts are marked),
 * and adds only what's ticked. Nothing is sent anywhere. Card security numbers are kept in the item's (encrypted)
 * note and never shown in the list.
 */
class FamilyImportActivity : AppCompatActivity() {
    companion object {
        private const val XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        fun open(ctx: Context) = ctx.startActivity(Intent(ctx, FamilyImportActivity::class.java))
    }

    private lateinit var ui: Ui
    private lateinit var content: LinearLayout
    private val worker = Executors.newSingleThreadExecutor()
    private var drafts: List<CouponImport.Draft> = emptyList()
    private var already: Set<String> = emptySet()
    private val chosen = mutableSetOf<Int>()
    private val today = LocalDate.now()

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) { finish(); return@registerForActivityResult }
        content.removeAllViews()
        content.addView(ui.hint(L.t("קוראים את הקובץ…", "Reading the file…"), top = 16))
        worker.execute {
            val r = try {
                val sheets = contentResolver.openInputStream(uri)?.use { XlsxReader.read(it) }
                Result.success(sheets?.let { CouponImport.parse(it) } ?: emptyList())
            } catch (e: Exception) { Result.failure(e) }
            val have = FamilyFile.all(applicationContext).map { it.hash }.filter { it.isNotEmpty() }.toSet()
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                r.onSuccess { list ->
                    if (list.isEmpty()) { Toast.makeText(this, L.t("לא מצאנו קופונים בקובץ", "No coupons found in the file"), Toast.LENGTH_LONG).show(); finish(); return@onSuccess }
                    drafts = list; already = have
                    chosen.clear()
                    list.forEachIndexed { n, d -> if (!d.expired(today) && d.hash !in have) chosen += n }
                    render()
                }.onFailure {
                    Toast.makeText(this, L.t("זה לא נראה כמו קובץ Excel ‏(xlsx)", "That doesn't look like an Excel (xlsx) file"), Toast.LENGTH_LONG).show(); finish()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_screen)
        findViewById<View>(R.id.btnClose).setOnClickListener { finish() }
        findViewById<TextView>(R.id.screenTitle).text = L.t("ייבוא קופונים מ-Excel", "Import coupons from Excel")
        content = findViewById(R.id.screenContent)
        ui = Ui(this)
        if (!FamilyFile.consented(this)) {
            AlertDialog.Builder(this)
                .setTitle(L.t("תיק המשפחה: מה נשמר ואיפה", "The family file: what's kept, and where"))
                .setMessage(FamilyFile.consentText())
                .setPositiveButton(L.t("מאשר/ת", "I agree")) { _, _ -> FamilyFile.setConsented(this); pick() }
                .setNegativeButton(L.t("לא עכשיו", "Not now")) { _, _ -> finish() }
                .setOnCancelListener { finish() }
                .show()
        } else pick()
    }

    override fun onDestroy() { worker.shutdownNow(); super.onDestroy() }

    private fun pick() = picker.launch(arrayOf(XLSX, "application/octet-stream"))

    /** A long code or card number shows only its last 4 digits. */
    private fun masked(code: String) = if (code.length >= 8) "•••• " + code.takeLast(4) else code

    private fun render() {
        content.removeAllViews()
        val groups = drafts.indices.groupBy { drafts[it].chain }
        val validValue = chosen.sumOf { drafts[it].value ?: 0.0 }
        val expired = drafts.count { it.expired(today) }
        val dup = drafts.count { it.hash in already }
        val unclear = drafts.count { it.unclear }

        content.addView(ui.card(top = 12).apply {
            addView(ui.text(L.t("נמצאו ${drafts.size} שורות ב-${groups.size} רשתות", "Found ${drafts.size} rows in ${groups.size} chains"), 17f, bold = true))
            addView(ui.hint(listOfNotNull(
                L.t("מסומנים ${chosen.size} · שווי ${FamilyItems.money(validValue)}", "${chosen.size} ticked · worth ${FamilyItems.money(validValue)}"),
                if (expired > 0) L.t("$expired שפג תוקפם לא מסומנים", "$expired expired ones aren't ticked") else null,
                if (dup > 0) L.t("$dup כבר בתיק", "$dup already in the file") else null,
                if (unclear > 0) L.t("$unclear לבדיקה: לא ברור אם המספר הוא סכום או CVV", "$unclear to check: the number might be the CVV, not the amount") else null,
            ).joinToString("\n"), top = 4))
            addView(ui.hint(L.t("הקובץ נקרא בטלפון בלבד. מספרי CVV נשמרים מוצפנים בהערה ולא מוצגים ברשימה.", "The file is read on the phone only. CVV numbers are kept encrypted in the note and not shown in the list."), top = 8))
        })
        content.addView(importButton(top = 12))

        for ((chain, rows) in groups) {
            val sum = rows.filter { it in chosen }.sumOf { drafts[it].value ?: 0.0 }
            content.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.dp(22) }
                addView(ui.text("$chain · ${rows.size}", 17f, bold = true).apply {
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    textDirection = View.TEXT_DIRECTION_FIRST_STRONG
                    androidx.core.view.ViewCompat.setAccessibilityHeading(this, true)
                })
                if (sum > 0) addView(amount(FamilyItems.money(sum), 15f, DS.inkSoft(this@FamilyImportActivity)))
            })
            val card = ui.card(top = 8)
            card.setPadding(ui.dp(10), ui.dp(2), ui.dp(14), ui.dp(2))
            rows.forEachIndexed { n, idx ->
                if (n > 0) card.addView(View(this).apply {
                    setBackgroundColor(0xFFE8DED3.toInt())
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply { marginStart = ui.dp(44) }
                })
                card.addView(row(idx))
            }
            content.addView(card)
        }
        content.addView(importButton(top = 20))
        content.addView(ui.hint(L.t("אחרי הייבוא אפשר לפתוח כל פריט ולהוסיף לו תמונה של השובר.", "After importing you can open any item and add a photo of the voucher."), top = 6))
        content.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, ui.dp(24)) })
    }

    private fun amount(text: String, size: Float, color: Int) = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(color); typeface = Typeface.create(DS.emphasis, Typeface.BOLD)
        textDirection = View.TEXT_DIRECTION_LTR; maxLines = 1
    }

    private fun row(idx: Int): View {
        val d = drafts[idx]
        val isIn = d.hash in already
        val title = listOfNotNull(
            d.code.takeIf { it.isNotEmpty() }?.let { masked(it) },
            d.link.takeIf { it.isNotEmpty() }?.let { L.t("קישור לשובר", "Voucher link") },
            d.note.takeIf { it.isNotEmpty() && d.code.isEmpty() && d.link.isEmpty() },
        ).firstOrNull() ?: L.t("שובר בלי קוד", "Voucher without a code")
        val meta = listOfNotNull(
            when {
                d.expires.isEmpty() -> L.t("בלי תאריך תפוגה", "No expiry date")
                d.expired(today) -> L.t("פג תוקף ", "Expired ") + FamilyItems.short(d.expires)
                else -> L.t("בתוקף עד ", "Valid until ") + FamilyItems.short(d.expires)
            },
            d.note.takeIf { it.isNotEmpty() && it != title },
            if (d.cvv.isNotEmpty()) L.t("כולל CVV", "Has a CVV") else null,
            if (d.unclear) L.t("⚠ לבדוק: אולי זה CVV ולא סכום", "⚠ Check: this might be the CVV, not the amount") else null,
            if (isIn) L.t("כבר בתיק", "Already in the file") else null,
        )
        val box = CheckBox(this).apply {
            isChecked = idx in chosen
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            isClickable = false
            layoutParams = LinearLayout.LayoutParams(ui.dp(34), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, ui.dp(10), 0, ui.dp(10)); minimumHeight = ui.dp(56)
            alpha = if (d.expired(today) || isIn) 0.6f else 1f
            background = android.util.TypedValue().let { tv -> theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true); getDrawable(tv.resourceId) }
            addView(box)
            addView(LinearLayout(this@FamilyImportActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = ui.dp(6); marginEnd = ui.dp(10) }
                addView(TextView(this@FamilyImportActivity).apply {
                    text = title; textSize = 15f; setTextColor(DS.ink(this@FamilyImportActivity)); typeface = DS.emphasis
                    maxLines = 2; ellipsize = TextUtils.TruncateAt.END; textDirection = View.TEXT_DIRECTION_FIRST_STRONG
                })
                addView(TextView(this@FamilyImportActivity).apply {
                    text = meta.joinToString(" · "); textSize = 13f
                    setTextColor(if (d.unclear) 0xFFA4442F.toInt() else DS.inkSoft(this@FamilyImportActivity))
                    maxLines = 3; ellipsize = TextUtils.TruncateAt.END; setPadding(0, ui.dp(2), 0, 0)
                })
            })
            d.value?.let { addView(amount(FamilyItems.money(it), 15f, DS.ink(this@FamilyImportActivity))) }
            contentDescription = (listOf(if (box.isChecked) L.t("מסומן", "Ticked") else L.t("לא מסומן", "Not ticked"), title) + meta + listOfNotNull(d.value?.let { FamilyItems.money(it) })).joinToString(", ")
            setOnClickListener {
                if (idx in chosen) chosen -= idx else chosen += idx
                val y = (content.parent as? View)?.scrollY ?: 0
                render()
                (content.parent as? View)?.post { (content.parent as? View)?.scrollTo(0, y) }
            }
        }
    }

    private fun importButton(top: Int) = ui.button(
        if (chosen.isEmpty()) L.t("לא סומן כלום", "Nothing ticked") else L.t("ייבוא ${chosen.size} לתיק המשפחה", "Import ${chosen.size} into the family file"),
        primary = true, top = top,
    ) { doImport() }.apply { isEnabled = chosen.isNotEmpty() }

    private fun doImport() {
        val items = chosen.sorted().map { CouponImport.toItem(drafts[it]) }
        worker.execute {
            val r = try { Result.success(FamilyFile.addAll(applicationContext, items)) } catch (e: Exception) { Result.failure(e) }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                r.onSuccess { n ->
                    Toast.makeText(this, L.t("נוספו $n פריטים לתיק המשפחה ✓", "Added $n items to the family file ✓"), Toast.LENGTH_SHORT).show()
                    finish()
                }.onFailure { Toast.makeText(this, L.t("לא הצלחנו לשמור (אולי אין מקום בטלפון)", "Couldn't save (maybe the phone is full)"), Toast.LENGTH_LONG).show() }
            }
        }
    }
}
