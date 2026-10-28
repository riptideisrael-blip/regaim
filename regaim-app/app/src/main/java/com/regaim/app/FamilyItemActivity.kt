package com.regaim.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.EditText
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

        fun open(ctx: Context, id: String) = ctx.startActivity(Intent(ctx, FamilyItemActivity::class.java).putExtra(EXTRA_ID, id))
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

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) { if (bytes == null && sharedText == null) finish(); return@registerForActivityResult }
        load(uri)
    }

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
                runOnUiThread { if (!isDestroyed) { picture = pic; render() } }
            }
            return
        }
        item = item.copy(child = intent.getStringExtra(EXTRA_CHILD).orEmpty())
        when (intent.action) {
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                if (uri != null) load(uri) else if (!text.isNullOrBlank()) { sharedText = text; item = item.copy(note = text.take(500), source = "share", kind = FamilyItems.Kind.COUPON); render() } else finish()
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                @Suppress("DEPRECATION")
                val uris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
                if (uris.size > 1) Toast.makeText(this, L.t("נוסף הקובץ הראשון. את השאר אפשר לשתף אחד-אחד.", "Adding the first file. Share the others one at a time."), Toast.LENGTH_LONG).show()
                uris.firstOrNull()?.let { load(it) } ?: finish()
            }
            else -> if (intent.getBooleanExtra(EXTRA_PICK, false)) picker.launch(arrayOf("image/*", "application/pdf")) else finish()
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
            val m = if (type == "application/pdf" || (b != null && b.size > 4 && String(b, 0, 4) == "%PDF")) "application/pdf" else "image/jpeg"
            val pic = b?.let { FamilyFile.picture(applicationContext, it, m) }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (b == null || (m != "application/pdf" && pic == null)) {
                    Toast.makeText(this, if (b == null) L.t("לא הצלחנו לקרוא את הקובץ (עד 20MB, תמונה או PDF)", "Couldn't read the file (up to 20 MB, a picture or a PDF)") else L.t("זה לא נראה כמו תמונה או PDF", "That doesn't look like a picture or a PDF"), Toast.LENGTH_LONG).show()
                    finish(); return@runOnUiThread
                }
                bytes = b; mime = m; picture = pic
                item = item.copy(mime = m, hash = FamilyFile.hash(b), source = if (intent.action == null) "pick" else "share",
                    kind = if (item.kind == FamilyItems.Kind.DOCUMENT) FamilyItems.Kind.RECEIPT else item.kind)
                render()
            }
        }
    }

    private fun children() = Profile.members(this).filter { it.isChild }

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

        // Auto-fill (on request only).
        if (isNew && (bytes != null || sharedText != null)) {
            content.addView(ui.button(if (busy) L.t("קוראים את הפרטים…", "Reading the details…") else L.t("✨ מילוי אוטומטי", "✨ Auto-fill"), primary = false, top = 12) { autoFill() }.apply { isEnabled = !busy })
            content.addView(ui.hint(L.t("שולח את התמונה לקריאה בלבד, ממלא את השדות ואתם בודקים לפני שמירה.", "Sends the picture to be read only; fills the fields, and you check them before saving."), top = 4))
        }

        content.addView(ui.section(L.t("מה זה?", "What is it?")))
        val kinds = FamilyItems.Kind.values().toList()
        content.addView(ui.chips(kinds.map { it.label }, kinds.indexOf(item.kind), top = 6) { i -> fromFields(); item = item.copy(kind = kinds[i]); render() })

        val kids = children()
        if (kids.isNotEmpty()) {
            content.addView(ui.text(L.t("של מי?", "Whose?"), 15f, bold = true, top = 14))
            val names = listOf(L.t("כל המשפחה", "The whole family")) + kids.map { it.name }
            val sel = kids.indexOfFirst { it.id == item.child }.let { if (it < 0) 0 else it + 1 }
            content.addView(ui.chips(names, sel, top = 6) { i -> fromFields(); item = item.copy(child = if (i == 0) "" else kids[i - 1].id); render() })
        }

        // For what: the parent picks where it's filed (receipts and documents).
        if (item.kind == FamilyItems.Kind.RECEIPT || item.kind == FamilyItems.Kind.DOCUMENT) {
            content.addView(ui.text(L.t("קטגוריה", "Category"), 15f, bold = true, top = 14))
            content.addView(categoryPicker())
        }

        val voucher = item.kind == FamilyItems.Kind.COUPON || item.kind == FamilyItems.Kind.VOUCHER || item.kind == FamilyItems.Kind.GIFT
        fTitle = ui.field(L.t("כותרת (למשל: שכר לימוד אוקטובר)", "Title (e.g. October tuition)"), item.title, top = 14)
        fVendor = ui.field(L.t("ממי (גן, חנות, חברה)", "From (kindergarten, store, company)"), item.vendor)
        fAmount = ui.field(if (voucher) L.t("שווי ב-₪", "Value in ₪") else L.t("סכום ב-₪", "Amount in ₪"), item.amount?.let { FamilyItems.money(it).removeSuffix(" ₪") }.orEmpty()).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        fDate = ui.field(L.t("תאריך (יום.חודש.שנה)", "Date (day.month.year)"), FamilyItems.short(item.date))
        listOf(fTitle, fVendor, fAmount, fDate).forEach { content.addView(it) }
        if (voucher || item.kind == FamilyItems.Kind.DOCUMENT) {
            fExpires = ui.field(L.t("בתוקף עד (יום.חודש.שנה)", "Valid until (day.month.year)"), FamilyItems.short(item.expires))
            content.addView(fExpires)
        } else fExpires = null
        if (voucher) {
            fCode = ui.field(L.t("קוד / מספר כרטיס", "Code / card number"), item.code)
            fBalance = ui.field(L.t("יתרה ידועה ב-₪ (אם יש)", "Known balance in ₪ (if any)"), item.balance?.let { FamilyItems.money(it).removeSuffix(" ₪") }.orEmpty()).apply {
                inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            }
            content.addView(fCode); content.addView(fBalance)
        } else { fCode = null; fBalance = null }
        fNote = ui.field(L.t("הערה", "Note"), item.note, multiline = true)
        content.addView(fNote)

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
            val r = try { Result.success(FamilyFile.save(applicationContext, item, if (isNew) b else null, ext)) } catch (e: Exception) { Result.failure(e) }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                r.onSuccess {
                    Toast.makeText(this, L.t("נשמר בתיק המשפחה ✓", "Saved to the family file ✓"), Toast.LENGTH_SHORT).show()
                    if (isNew) startActivity(Intent(this, FamilyFileActivity::class.java))
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
}
