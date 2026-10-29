package com.regaim.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.util.concurrent.Executors

/**
 * One item of the family file: a new one shared from another app ("Share → Moments · family file"), picked from the
 * phone, or an existing one to look at and change. Nothing is saved until the parent taps Save.
 */
class FamilyItemActivity : AppCompatActivity() {
    companion object {
        private const val EXTRA_ID = "item_id"
        private const val EXTRA_PICK = "pick"
        private const val EXTRA_CHILD = "child"
        private const val MAX_BYTES = 20 * 1024 * 1024
        private const val XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

        fun intent(ctx: Context, id: String) = Intent(ctx, FamilyItemActivity::class.java).putExtra(EXTRA_ID, id)
        fun open(ctx: Context, id: String) = ctx.startActivity(intent(ctx, id))
        fun pick(ctx: Context, child: String = "") = ctx.startActivity(Intent(ctx, FamilyItemActivity::class.java).putExtra(EXTRA_PICK, true).putExtra(EXTRA_CHILD, child))
    }

    private lateinit var ui: Ui
    private lateinit var content: LinearLayout
    private val worker = Executors.newSingleThreadExecutor()

    private var item = FamilyItems.Item("", FamilyItems.Kind.DOCUMENT)
    private var isNew = true
    private var bytes: ByteArray? = null
    private var mime = ""
    private var sharedText: String? = null
    private var picture: Bitmap? = null
    private var busy = false
    /** A new photo or file was picked for an item that's already in the file (replaces its old one on Save). */
    private var attached = false
    /** A saved voucher opens to be shown at the checkout; its form only after "Edit the details". */
    private var editing = false

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) { if (bytes == null && sharedText == null) finish(); return@registerForActivityResult }
        load(uri)
    }
    private val photoPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) load(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_screen)
        findViewById<View>(R.id.btnClose).setOnClickListener { finish() }
        content = findViewById(R.id.screenContent)
        ui = Ui(this)
        if (!FamilyFile.consented(this)) askConsent { start() } else start()
    }

    override fun onDestroy() { worker.shutdownNow(); super.onDestroy() }

    private fun askConsent(then: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(L.t("תיק המשפחה: מה נשמר ואיפה", "The family file: what's kept, and where"))
            .setMessage(FamilyFile.consentText())
            .setPositiveButton(L.t("מאשר/ת", "I agree")) { _, _ -> FamilyFile.setConsented(this); then() }
            .setNegativeButton(L.t("לא עכשיו", "Not now")) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun start() {
        val id = intent.getStringExtra(EXTRA_ID)
        if (id != null) {
            val existing = FamilyFile.get(this, id) ?: run { finish(); return }
            item = existing; isNew = false
            render()
            if (existing.file.isNotEmpty()) worker.execute {
                val b = FamilyFile.bytes(applicationContext, existing)
                val pic = b?.let { FamilyFile.picture(applicationContext, it, existing.mime) }
                runOnUiThread { if (!isDestroyed && !attached) { picture = pic; render() } }
            }
            return
        }
        item = item.copy(child = intent.getStringExtra(EXTRA_CHILD).orEmpty())
        when (intent.action) {
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                if (uri == null && text != null && text.contains("docs.google.com/spreadsheets")) {
                    AlertDialog.Builder(this)
                        .setTitle(L.t("זה קישור לגיליון, לא הקובץ עצמו", "That's a link to a sheet, not the file"))
                        .setMessage(L.t("כדי לייבא את כל הקופונים: בגיליון לוחצים ⋮ ← שיתוף וייצוא ← שליחת עותק ← Microsoft Excel ‏(xlsx), ומשתפים ל-Moments · תיק המשפחה.", "To import all the coupons: in the sheet tap ⋮ → Share & export → Send a copy → Microsoft Excel (xlsx), and share it to Moments · family file."))
                        .setPositiveButton(L.t("הבנתי", "Got it")) { _, _ -> finish() }
                        .setOnCancelListener { finish() }.show()
                    return
                }
                if (uri != null) load(uri) else if (!text.isNullOrBlank()) { sharedText = text; item = item.copy(note = text.take(500), source = "share", kind = FamilyItems.Kind.COUPON); render() } else finish()
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                @Suppress("DEPRECATION")
                val uris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
                if (uris.size > 1) Toast.makeText(this, L.t("נוסף הקובץ הראשון. את השאר אפשר לשתף אחד-אחד.", "Adding the first file. Share the others one at a time."), Toast.LENGTH_LONG).show()
                uris.firstOrNull()?.let { load(it) } ?: finish()
            }
            else -> if (intent.getBooleanExtra(EXTRA_PICK, false)) picker.launch(arrayOf("image/*", "application/pdf", XLSX)) else finish()
        }
    }

    private fun load(uri: Uri) {
        render(loading = true)
        worker.execute {
            val type = try { contentResolver.getType(uri) } catch (_: Exception) { null }.orEmpty()
            val b = try {
                contentResolver.openInputStream(uri)?.use { s ->
                    val buf = java.io.ByteArrayOutputStream()
                    val chunk = ByteArray(64 * 1024)
                    var total = 0
                    while (true) {
                        val n = s.read(chunk); if (n < 0) break
                        total += n; if (total > MAX_BYTES) return@use null
                        buf.write(chunk, 0, n)
                    }
                    buf.toByteArray()
                }
            } catch (_: Exception) { null }
            // A spreadsheet (a list of coupons): goes to the import, which reads every row.
            if (b != null && isNew && b.size > 4 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte()) {
                val f = java.io.File(java.io.File(cacheDir, "import").apply { mkdirs() }, "coupons.xlsx")
                val ok = try { f.writeBytes(b); true } catch (_: Exception) { false }
                runOnUiThread { if (!isDestroyed) { if (ok) FamilyImportActivity.openFile(this, f); finish() } }
                return@execute
            }
            if (b != null && b.size > 4 && (b[0].toInt() and 0xFF) == 0xD0 && (b[1].toInt() and 0xFF) == 0xCF) {
                runOnUiThread { if (!isDestroyed) { Toast.makeText(this, L.t("זה קובץ Excel ישן (xls). שמרו אותו כ-xlsx ונסו שוב.", "That's an old Excel file (xls). Save it as xlsx and try again."), Toast.LENGTH_LONG).show(); if (isNew) finish() else render() } }
                return@execute
            }
            val m = if (type == "application/pdf" || (b != null && b.size > 4 && String(b, 0, 4) == "%PDF")) "application/pdf" else "image/jpeg"
            val pic = b?.let { FamilyFile.picture(applicationContext, it, m) }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (b == null || (m != "application/pdf" && pic == null)) {
                    Toast.makeText(this, if (b == null) L.t("לא הצלחנו לקרוא את הקובץ (עד 20MB, תמונה או PDF)", "Couldn't read the file (up to 20 MB, a picture or a PDF)") else L.t("זה לא נראה כמו תמונה או PDF", "That doesn't look like a picture or a PDF"), Toast.LENGTH_LONG).show()
                    // Adding a photo to something already here (or to shared text): keep the screen, only the photo failed.
                    if (isNew && sharedText == null) finish() else render()
                    return@runOnUiThread
                }
                bytes = b; mime = m; picture = pic
                if (!isNew) { attached = true; editing = true; item = item.copy(mime = m) }
                else item = item.copy(mime = m, hash = FamilyFile.hash(b), source = if (sharedText != null) item.source else if (intent.action == null) "pick" else "share",
                    kind = if (item.kind == FamilyItems.Kind.DOCUMENT) FamilyItems.Kind.RECEIPT else item.kind)
                render()
            }
        }
    }

    private fun children() = Profile.members(this).filter { !it.isPet } /* parents too, not only the children */

    // The fields, kept across re-renders.
    private var fTitle: EditText? = null
    private var fVendor: EditText? = null
    private var fAmount: EditText? = null
    private var fDate: EditText? = null
    private var fExpires: EditText? = null
    private var fCode: EditText? = null
    private var fBalance: EditText? = null
    private var fNote: EditText? = null

    private fun fromFields() {
        val t = fTitle ?: return
        item = item.copy(
            title = t.text.toString().trim(), vendor = fVendor?.text?.toString().orEmpty().trim(),
            amount = FamilyItems.cleanAmount(fAmount?.text?.toString()), code = fCode?.text?.toString().orEmpty().trim(),
            note = fNote?.text?.toString().orEmpty().trim(),
            date = fDate?.text?.toString().orEmpty().let { FamilyItems.cleanDate(it).ifEmpty { if (it.isBlank()) "" else item.date } },
            expires = fExpires?.text?.toString().orEmpty().let { FamilyItems.cleanDate(it).ifEmpty { if (it.isBlank()) "" else item.expires } },
            balance = fBalance?.let { FamilyItems.cleanAmount(it.text.toString()) } ?: item.balance,
        )
    }

    private fun render(loading: Boolean = false) {
        if (fTitle != null) fromFields()
        content.removeAllViews()
        findViewById<TextView>(R.id.screenTitle).text = if (isNew) L.t("הוספה לתיק המשפחה", "Add to the family file") else item.title.ifBlank { item.kind.label }
        if (loading) { content.addView(ui.hint(L.t("פותחים את הקובץ…", "Opening the file…"), top = 16)); return }
        if (!isNew && FamilyItems.isWallet(item)) content.addView(voucherCard())

        picture?.let { bmp ->
            content.addView(ImageView(this).apply {
                setImageBitmap(bmp); adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER
                maxHeight = ui.dp(320)
                background = DS.rounded(DS.card(this@FamilyItemActivity), ui.dp(16).toFloat())
                contentDescription = L.t("תצוגה של הקובץ", "Preview of the file")
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.dp(12) }
                if (!isNew && item.file.isNotEmpty()) setOnClickListener { openFile() }
            })
        }
        // A PDF that couldn't be drawn here (e.g. protected): say what it is; it can still be opened and read.
        if (picture == null && (mime == "application/pdf" || item.mime == "application/pdf")) content.addView(ui.card(top = 12).apply {
            addView(ui.text(L.t("📄 קובץ PDF", "📄 A PDF file"), 15f, bold = true))
            addView(ui.hint(if (isNew) L.t("אין תצוגה מקדימה לקובץ הזה, אבל הוא יישמר ואפשר לפתוח אותו בכל רגע.", "No preview for this file, but it's saved and can be opened any time.") else L.t("נגיעה פותחת את הקובץ", "Tap to open the file"), top = 2))
            if (!isNew && item.file.isNotEmpty()) setOnClickListener { openFile() }
        })
        if (sharedText != null && picture == null) content.addView(ui.card(top = 12).apply { addView(ui.text(sharedText!!.take(600), 14f)) })

        // A photo for something without one (a coupon from a list, shared text), or a better one.
        if (!isNew || bytes == null) {
            val has = picture != null || item.file.isNotEmpty() || bytes != null
            content.addView(ui.button(if (has) L.t("📷 החלפת תמונה", "📷 Replace the photo") else L.t("📷 הוספת תמונה", "📷 Add a photo"), top = 12) {
                fromFields(); photoPicker.launch(arrayOf("image/*", "application/pdf"))
            })
            if (attached) content.addView(ui.hint(L.t("התמונה החדשה תישמר כשתלחצו שמירה", "The new photo is kept when you tap Save"), top = 4))
        }

        // A voucher at the checkout: what to show the cashier, then "used" and "edit"; the form only on request.
        val viewing = !isNew && FamilyItems.isWallet(item) && !editing
        checkoutLight(viewing)
        if (viewing) {
            if (item.source != FamilyShare.FROM_PARTNER) {
                content.addView(ui.button(L.t("✓ מומש · הסרה מהארנק", "✓ Used · remove from the wallet"), primary = true, top = 16) { confirmUsed() })
                content.addView(ui.button(L.t("עריכת פרטים", "Edit the details"), top = 8) { editing = true; render() })
            } else content.addView(ui.hint(L.t("מהתיק של ההורה השני. סימון כמומש או עריכה נעשים בטלפון שלו/ה.", "From the other parent's file. Marking it used or editing it is done on their phone."), top = 14))
            content.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, ui.dp(24)) })
            return
        }

        // Auto-fill (on request only).
        if (isNew && (bytes != null || sharedText != null)) {
            content.addView(ui.button(if (busy) L.t("קוראים את הפרטים…", "Reading the details…") else L.t("✨ מילוי אוטומטי", "✨ Auto-fill"), primary = false, top = 12) { autoFill() }.apply { isEnabled = !busy })
            content.addView(ui.hint(L.t("שולח את התמונה לקריאה בלבד, ממלא את השדות ואתם בודקים לפני שמירה.", "Sends the picture to be read only; fills the fields, and you check them before saving."), top = 4))
        }

        content.addView(ui.section(if (!isNew && FamilyItems.isWallet(item)) L.t("עריכת פרטים", "Edit the details") else L.t("מה זה?", "What is it?")))
        val kinds = FamilyItems.Kind.values().toList()
        content.addView(ui.chips(kinds.map { it.label }, kinds.indexOf(item.kind), top = 6) { i -> fromFields(); item = item.copy(kind = kinds[i]); render() })

        val kids = children()
        if (kids.isNotEmpty()) {
            content.addView(ui.text(L.t("של מי?", "Whose?"), 15f, bold = true, top = 14))
            val names = listOf(L.t("כל המשפחה", "The whole family")) + kids.map { it.name }
            val sel = kids.indexOfFirst { it.id == item.child }.let { if (it < 0) 0 else it + 1 }
            content.addView(ui.chips(names, sel, top = 6) { i -> fromFields(); item = item.copy(child = if (i == 0) "" else kids[i - 1].id); render() })
        }

        // For what: the parent picks where it's filed (receipts, documents, warranties).
        if (item.kind == FamilyItems.Kind.RECEIPT || item.kind == FamilyItems.Kind.DOCUMENT || item.kind == FamilyItems.Kind.WARRANTY) {
            content.addView(ui.text(L.t("קטגוריה", "Category"), 15f, bold = true, top = 14))
            content.addView(categoryPicker())
        }

        val voucher = FamilyItems.isWallet(item)
        val protection = FamilyItems.isProtection(item)
        val warranty = item.kind == FamilyItems.Kind.WARRANTY
        fTitle = ui.field(if (protection) L.t("מה המוצר (למשל: מקרר, עגלה)", "The product (e.g. fridge, stroller)") else L.t("כותרת (למשל: שכר לימוד אוקטובר)", "Title (e.g. October tuition)"), item.title, top = 14)
        fVendor = ui.field(if (protection) L.t("איפה נקנה (חנות, אתר)", "Bought at (store, site)") else L.t("ממי (גן, חנות, חברה)", "From (kindergarten, store, company)"), item.vendor)
        fAmount = ui.field(if (voucher) L.t("שווי ב-₪", "Value in ₪") else if (protection) L.t("מחיר ב-₪", "Price in ₪") else L.t("סכום ב-₪", "Amount in ₪"), item.amount?.let { FamilyItems.money(it).removeSuffix(" ₪") }.orEmpty()).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        fDate = ui.field(if (protection) L.t("תאריך קנייה (יום.חודש.שנה)", "Bought on (day.month.year)") else L.t("תאריך (יום.חודש.שנה)", "Date (day.month.year)"), FamilyItems.short(item.date))
        listOf(fTitle, fVendor, fAmount, fDate).forEach { addField(it) }
        if (voucher || protection || item.kind == FamilyItems.Kind.DOCUMENT) {
            fExpires = ui.field(if (warranty) L.t("האחריות עד (יום.חודש.שנה)", "Warranty until (day.month.year)") else if (item.kind == FamilyItems.Kind.EXCHANGE) L.t("אפשר להחליף עד (יום.חודש.שנה)", "Exchange until (day.month.year)") else L.t("בתוקף עד (יום.חודש.שנה)", "Valid until (day.month.year)"), FamilyItems.short(item.expires))
            addField(fExpires)
            // The usual lengths, counted from the purchase date (or today).
            if (protection) {
                val opts: List<Pair<String, (String) -> String>> = if (warranty) listOf(
                    L.t("שנה", "1 year") to { d: String -> FamilyItems.plusMonths(d, 12) },
                    L.t("שנתיים", "2 years") to { d: String -> FamilyItems.plusMonths(d, 24) },
                    L.t("3 שנים", "3 years") to { d: String -> FamilyItems.plusMonths(d, 36) },
                    L.t("5 שנים", "5 years") to { d: String -> FamilyItems.plusMonths(d, 60) },
                ) else listOf(
                    L.t("14 יום", "14 days") to { d: String -> FamilyItems.plusDays(d, 14) },
                    L.t("30 יום", "30 days") to { d: String -> FamilyItems.plusDays(d, 30) },
                    L.t("60 יום", "60 days") to { d: String -> FamilyItems.plusDays(d, 60) },
                )
                content.addView(ui.chips(opts.map { it.first }, -1, top = 6) { n ->
                    fromFields()
                    val from = item.date.ifEmpty { java.time.LocalDate.now().toString() }
                    item = item.copy(date = from, expires = opts[n].second(from)); render()
                })
            }
        } else fExpires = null
        if (voucher) {
            fCode = ui.field(L.t("קוד / מספר כרטיס", "Code / card number"), item.code)
            fBalance = ui.field(L.t("יתרה ידועה ב-₪ (אם יש)", "Known balance in ₪ (if any)"), item.balance?.let { FamilyItems.money(it).removeSuffix(" ₪") }.orEmpty()).apply {
                inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            }
            addField(fCode); addField(fBalance)
        } else { fCode = null; fBalance = null }
        fNote = ui.field(L.t("הערה", "Note"), item.note, multiline = true)
        addField(fNote)

        if (item.source == FamilyShare.FROM_PARTNER) {
            // The other parent's item: changed only on their phone (a change here would be undone by the next sync).
            content.addView(ui.hint(L.t("הפריט הזה הגיע מהתיק של ההורה השני. אפשר לשנות או למחוק אותו רק בטלפון שלו/ה.", "This item came from the other parent's file. It can be changed or deleted only on their phone."), top = 16))
            if (item.file.isNotEmpty()) content.addView(ui.button(L.t("פתיחת הקובץ", "Open the file"), top = 8) { openFile() })
            content.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, ui.dp(24)) })
            return
        }
        content.addView(ui.button(L.t("שמירה", "Save"), primary = true, top = 18) { save() })
        if (!isNew) {
            if (item.file.isNotEmpty()) content.addView(ui.button(L.t("פתיחת הקובץ", "Open the file"), top = 8) { openFile() })
            content.addView(ui.action(L.t("מחיקה מהתיק", "Delete from the file"), R.color.ds_error) { confirmDelete() })
        }
        content.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, ui.dp(24)) })
    }

    /** All the categories at once, wrapping onto lines (not a row to scroll). */
    private fun categoryPicker(): View {
        val group = com.google.android.material.chip.ChipGroup(this).apply {
            isSingleSelection = true
            chipSpacingHorizontal = ui.dp(8); chipSpacingVertical = ui.dp(4)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.dp(6) }
        }
        val on = DS.primary(this); val off = DS.buttonSecondary(this)
        for (c in FamilyItems.Category.values()) {
            val chosen = item.category == c.id
            group.addView(com.google.android.material.chip.Chip(this).apply {
                text = c.label
                textSize = 14f
                isCheckable = true
                isChecked = chosen
                isCheckedIconVisible = false
                chipBackgroundColor = android.content.res.ColorStateList.valueOf(if (chosen) on else off)
                setTextColor(if (chosen) DS.onButton(this@FamilyItemActivity) else DS.ink(this@FamilyItemActivity))
                chipStrokeWidth = 0f
                minHeight = ui.dp(48)
                setOnClickListener { fromFields(); item = item.copy(category = if (chosen) "" else c.id); render() }
            })
        }
        return group
    }

    private fun autoFill() {
        if (busy) return
        if (!Account.signedIn(this)) {
            Toast.makeText(this, L.t("מילוי אוטומטי דורש התחברות לחשבון. אפשר למלא ידנית.", "Auto-fill needs you to sign in. You can fill it in yourself."), Toast.LENGTH_LONG).show()
            return
        }
        fromFields()
        busy = true; render()
        val kids = children()
        val today = java.time.LocalDate.now().toString()
        val pic = picture
        val text = sharedText
        worker.execute {
            val result = try {
                val pdf = bytes?.takeIf { pic == null && mime == "application/pdf" && it.size < 4 * 1024 * 1024 }
                    ?.let { android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP) }
                Result.success(ClaudeClient.readFamilyItem(applicationContext, pic?.let { FamilyFile.jpegBase64(it) }, text, kids.map { it.name }, today, pdf))
            } catch (e: Exception) { Result.failure(e) }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                busy = false
                result.onSuccess { s ->
                    item = FamilyItems.fromSuggestion(item, s, kids.associate { it.id to it.name })
                    fTitle = null // the fields are rebuilt from the item
                    render()
                    Toast.makeText(this, L.t("מולא. בדקו את הפרטים לפני שמירה.", "Filled in. Check the details before saving."), Toast.LENGTH_SHORT).show()
                }.onFailure {
                    render()
                    Toast.makeText(this, Wording.forParent(it.message, admin = false, fallback = L.t("לא הצלחנו לקרוא אוטומטית. אפשר למלא ידנית.", "Couldn't read it automatically. You can fill it in yourself.")), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun save() {
        fromFields()
        val badDate = listOf(fDate, fExpires).any { f -> f != null && f.text.isNotBlank() && FamilyItems.cleanDate(f.text.toString()).isEmpty() }
        if (badDate) { Toast.makeText(this, L.t("תאריך לא תקין. כותבים יום.חודש.שנה, למשל 1.10.2026", "That date isn't valid. Write day.month.year, e.g. 1.10.2026"), Toast.LENGTH_LONG).show(); return }
        if (item.title.isBlank() && item.vendor.isBlank()) { Toast.makeText(this, L.t("כתבו כותרת או ממי זה", "Add a title or who it's from"), Toast.LENGTH_SHORT).show(); return }
        val dup = if (isNew) FamilyItems.duplicateOf(FamilyFile.all(this), item) else null
        if (dup != null) {
            AlertDialog.Builder(this)
                .setTitle(L.t("נראה שזה כבר בתיק", "This looks like it's already in the file"))
                .setMessage(listOf(dup.title, dup.vendor, FamilyItems.short(dup.date)).filter { it.isNotBlank() }.joinToString(" · "))
                .setPositiveButton(L.t("לשמור בכל זאת", "Save anyway")) { _, _ -> doSave() }
                .setNegativeButton(L.t("ביטול", "Cancel"), null).show()
        } else doSave()
    }

    private fun doSave() {
        val b = bytes
        val ext = if (mime == "application/pdf") "pdf" else if (b != null) "jpg" else ""
        worker.execute {
            val r = try { Result.success(FamilyFile.save(applicationContext, item, if (isNew || attached) b else null, ext, replace = attached)) } catch (e: Exception) { Result.failure(e) }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                r.onSuccess {
                    Toast.makeText(this, L.t("נשמר בתיק המשפחה ✓", "Saved to the family file ✓"), Toast.LENGTH_SHORT).show()
                    if (isNew) FamilyFileActivity.open(this) // the file that's already open, not another copy
                    finish()
                }.onFailure {
                    Toast.makeText(this, L.t("לא הצלחנו לשמור (אולי אין מקום בטלפון)", "Couldn't save (maybe the phone is full)"), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle(L.t("למחוק מהתיק?", "Delete from the file?"))
            .setMessage(L.t("הפריט והקובץ שלו יימחקו מהטלפון.", "The item and its file will be deleted from the phone."))
            .setPositiveButton(L.t("מחיקה", "Delete")) { _, _ -> FamilyFile.delete(this, item.id); finish() }
            .setNegativeButton(L.t("ביטול", "Cancel"), null).show()
    }

    private fun openFile() {
        worker.execute {
            val f = FamilyFile.viewCopy(applicationContext, item)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (f == null) { Toast.makeText(this, L.t("לא הצלחנו לפתוח את הקובץ", "Couldn't open the file"), Toast.LENGTH_SHORT).show(); return@runOnUiThread }
                try {
                    val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
                    startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, item.mime.ifEmpty { "image/jpeg" }).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                } catch (_: Exception) {
                    Toast.makeText(this, L.t("אין בטלפון אפליקציה שפותחת את הקובץ", "No app on this phone opens this file"), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private var showCvv = false

    /** A saved coupon or voucher at a glance: the chain, its value, the code to copy, the link, when it expires. */
    private fun voucherCard(): View {
        val today = java.time.LocalDate.now().toString()
        val chain = item.vendor.ifBlank { item.title.substringBefore(" · ") }.ifBlank { item.kind.label }
        val link = FamilyItems.link(item.note)
        val cvv = FamilyItems.cvv(item.note)
        val white = 0xFFFFFFFF.toInt()
        return ui.card(top = 12).apply {
            setPadding(ui.dp(20), ui.dp(18), ui.dp(20), ui.dp(18))
            background = DS.rounded(0xFF6F4E37.toInt(), ui.dp(22).toFloat())
            addView(TextView(this@FamilyItemActivity).apply { text = chain; textSize = 15f; setTextColor(0xE6FFFFFF.toInt()); typeface = DS.emphasis; textDirection = View.TEXT_DIRECTION_FIRST_STRONG })
            FamilyItems.worth(item).takeIf { it > 0 }?.let { w ->
                addView(TextView(this@FamilyItemActivity).apply {
                    text = FamilyItems.money(w); textSize = 32f; setTextColor(white); typeface = DS.emphasis; textDirection = View.TEXT_DIRECTION_LTR
                    setPadding(0, ui.dp(4), 0, 0)
                })
            }
            FamilyItems.expiryLine(item, today).takeIf { it.isNotEmpty() }?.let { e ->
                addView(TextView(this@FamilyItemActivity).apply { text = e; textSize = 13f; setTextColor(0xE6FFFFFF.toInt()); setPadding(0, ui.dp(2), 0, 0) })
            }
            if (item.code.isNotEmpty()) {
                addView(TextView(this@FamilyItemActivity).apply {
                    text = FamilyItems.grouped(item.code); textSize = 28f; setTextColor(white); typeface = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
                    contentDescription = item.code.toCharArray().joinToString(" ")
                    textDirection = View.TEXT_DIRECTION_LTR; textAlignment = View.TEXT_ALIGNMENT_CENTER
                    letterSpacing = 0.06f; setTextIsSelectable(true)
                    background = DS.rounded(0x26FFFFFF, ui.dp(12).toFloat())
                    setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12))
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.dp(14) }
                })
            }
            val actions = LinearLayout(this@FamilyItemActivity).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            fun pill(label: String, onClick: () -> Unit) = TextView(this@FamilyItemActivity).apply {
                text = label; textSize = 14f; setTextColor(white); typeface = DS.emphasis; gravity = android.view.Gravity.CENTER
                background = DS.rounded(0x33FFFFFF, ui.dp(20).toFloat())
                setPadding(ui.dp(14), ui.dp(9), ui.dp(14), ui.dp(9)); minHeight = ui.dp(44)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = ui.dp(8) }
                setOnClickListener { onClick() }
            }
            if (item.code.isNotEmpty()) actions.addView(pill(L.t("העתקת הקוד", "Copy the code")) {
                (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("code", item.code))
                Toast.makeText(this@FamilyItemActivity, L.t("הקוד הועתק", "Code copied"), Toast.LENGTH_SHORT).show()
            })
            if (link.isNotEmpty()) actions.addView(pill(L.t("פתיחת השובר ↗", "Open the voucher ↗")) {
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link))) } catch (_: Exception) { }
            })
            if (cvv.isNotEmpty()) actions.addView(pill(if (showCvv) "CVV $cvv" else L.t("הצגת CVV", "Show CVV")) { fromFields(); showCvv = !showCvv; render() })
            if (actions.childCount > 0) addView(HorizontalScrollView(this@FamilyItemActivity).apply {
                isHorizontalScrollBarEnabled = false; addView(actions)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.dp(12) }
            })
        }
    }

    /** A field with its name above it, so a filled-in "150" or "1.10.2026" still says what it is. */
    private fun addField(f: EditText?) {
        f ?: return
        val name = f.hint?.toString().orEmpty().substringBefore(" (")
        content.addView(ui.text(name, 13f, R.color.ink_soft, bold = true, top = 12).apply { labelFor = View.generateViewId().also { f.id = it } })
        (f.layoutParams as? LinearLayout.LayoutParams)?.topMargin = ui.dp(4)
        content.addView(f)
    }

    /** At the checkout: the screen at full brightness and kept on, so the cashier can read or scan the code. */
    private fun checkoutLight(on: Boolean) {
        window.attributes = window.attributes.apply { screenBrightness = if (on) 1f else android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
        if (on) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun confirmUsed() {
        AlertDialog.Builder(this)
            .setTitle(L.t("השובר מומש?", "Was the voucher used?"))
            .setMessage(L.t("הוא יוסר מהארנק ומהטלפון. אם נשארה בו יתרה, עדיף לעדכן אותה ב\"עריכת פרטים\".", "It's removed from the wallet and the phone. If some balance is left, update it under \"Edit the details\" instead."))
            .setPositiveButton(L.t("מומש, להסיר", "Used, remove")) { _, _ -> FamilyFile.delete(this, item.id); finish() }
            .setNegativeButton(L.t("ביטול", "Cancel"), null).show()
    }
}
