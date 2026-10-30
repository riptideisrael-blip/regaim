package com.regaim.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * The family file on this phone: the list (encrypted) and each item's file (encrypted with a key that never leaves
 * the phone's secure hardware). Nothing here goes to a server; sharing with the other parent is the next stage.
 */
object FamilyFile {
    /** The privacy text the parent agrees to before the first item (a new version asks again). */
    /** 2: the file can be shared with the other parent (optional), savings reports are read by the AI, and backed up to Drive (optional). */
    const val CONSENT_VERSION = 2

    private fun dir(ctx: Context) = File(ctx.filesDir, "family_file").apply { mkdirs() }
    private fun index(ctx: Context) = File(dir(ctx), "items.json")
    private val lock = Any()

    fun consented(ctx: Context) = Settings.prefs(ctx).getInt("family_file_consent", 0) >= CONSENT_VERSION
    fun setConsented(ctx: Context) = Settings.prefs(ctx).edit()
        .putInt("family_file_consent", CONSENT_VERSION).putLong("family_file_consent_at", System.currentTimeMillis()).apply()

    fun consentText(): String = L.t(
        "מה נשמר בתיק המשפחה:\n" +
            "• קבלות, מסמכים, אחריות, קופונים, שוברים וחיסכון הילדים שאתם מוסיפים, והפרטים שלהם (סכום, תאריך, קוד, של איזה ילד).\n\n" +
            "איפה:\n" +
            "• בטלפון הזה, מוצפן.\n" +
            "• רק אם תפעילו \"שיתוף התיק\" עם ההורה השני (ושניכם תאשרו): הפריטים והקבצים נשמרים גם בשרת של Moments, גלויים רק לשניכם. ההגנה שם היא בהרשאות החשבון, לא בהצפנה מקצה לקצה. קוד CVV לא יוצא מהטלפון אף פעם. כיבוי השיתוף מוחק מהשרת את מה שנשלח מהטלפון הזה.\n" +
            "• רק אם תפעילו גיבוי לגוגל דרייב: עותק מוצפן בדרייב שלכם.\n\n" +
            "בינה מלאכותית (Claude של Anthropic, דרך השרת שלנו, לא נשמר אצלנו):\n" +
            "• \"מילוי אוטומטי\": התמונה (או העמוד הראשון של ה-PDF) נשלחת כדי לקרוא סכום, תאריך וקוד.\n" +
            "• העלאת דוח חיסכון: הדוח נשלח כדי לקרוא את הקופה, המסלול, היתרה וההפקדות.\n" +
            "• חיפוש חכם: נשלחת רשימה של כותרות, סכומים ותאריכים (בלי קודים, הערות או קבצים).\n\n" +
            "חיסכון: כדי להעריך כמה יש היום, האפליקציה מורידה נתונים ציבוריים (תשואות מגמל נט של משרד האוצר, מחירי מטבעות מ-CoinGecko) לפי מספר הקופה או סמל המטבע בלבד.\n\n" +
            "אפשר למחוק כל פריט, או את כל התיק, בכל רגע.",
        "What the family file keeps:\n" +
            "• Receipts, documents, warranties, coupons, vouchers and the children's savings you add, and their details (amount, date, code, which child).\n\n" +
            "Where:\n" +
            "• On this phone, encrypted.\n" +
            "• Only if you turn on \"Share the file\" with the other parent (and both of you agree): the items and files are also kept on the Moments server, visible only to the two of you. They're protected there by account permissions, not end-to-end encryption. A CVV never leaves the phone. Turning sharing off deletes from the server what this phone sent.\n" +
            "• Only if you turn on the Google Drive backup: an encrypted copy in your Drive.\n\n" +
            "AI (Anthropic's Claude, via our server, not kept by us):\n" +
            "• \"Auto-fill\": the picture (or the PDF's first page) is sent to read the amount, date and code.\n" +
            "• Uploading a savings report: the report is sent to read the fund, track, balance and deposits.\n" +
            "• Smart search: a list of titles, amounts and dates (no codes, notes or files) is sent.\n\n" +
            "Savings: to estimate today's amount, the app downloads public data (Gemel-Net returns from the Ministry of Finance, coin prices from CoinGecko) by the fund number or coin symbol only.\n\n" +
            "You can delete any item, or the whole file, at any time.",
    )

    fun all(ctx: Context): List<FamilyItems.Item> = synchronized(lock) {
        FamilyItems.listFromJson(try { SecureFiles.readText(index(ctx)) } catch (_: Exception) { null })
            .sortedByDescending { it.date.ifEmpty { it.expires }.ifEmpty { "" + it.created } }
    }

    fun get(ctx: Context, id: String) = all(ctx).firstOrNull { it.id == id }

    private fun write(ctx: Context, items: List<FamilyItems.Item>) = SecureFiles.writeText(index(ctx), FamilyItems.listToJson(items))

    /**
     * Saves a new or changed item. [bytes] (the document or photo) is written when the item has no file yet, or with
     * [replace] for a new photo of an existing item (the old file is deleted once the new one is written). Returns the saved item.
     */
    fun save(ctx: Context, item: FamilyItems.Item, bytes: ByteArray? = null, ext: String = "", replace: Boolean = false): FamilyItems.Item = synchronized(lock) {
        var i = item.copy(id = item.id.ifBlank { UUID.randomUUID().toString() }, created = if (item.created > 0) item.created else System.currentTimeMillis())
        val list = FamilyItems.listFromJson(try { SecureFiles.readText(index(ctx)) } catch (_: Exception) { null })
        val old = list.firstOrNull { it.id == i.id }?.file.orEmpty()
        if (bytes != null && (i.file.isEmpty() || replace)) {
            val name = i.id + (if (replace) "-" + System.currentTimeMillis().toString(36) else "") + (if (ext.isNotEmpty()) ".$ext" else "") + ".enc"
            SecureFiles.writeBytes(File(dir(ctx), name), bytes)
            i = i.copy(file = name)
        }
        write(ctx, list.filterNot { it.id == i.id } + i)
        if (old.isNotEmpty() && old != i.file) File(dir(ctx), old).delete()
        i
    }

    /** Adds many items at once (an imported list), in one write. Returns how many were added. */
    fun addAll(ctx: Context, items: List<FamilyItems.Item>): Int = synchronized(lock) {
        val list = FamilyItems.listFromJson(try { SecureFiles.readText(index(ctx)) } catch (_: Exception) { null })
        val now = System.currentTimeMillis()
        val added = items.mapIndexed { n, it -> it.copy(id = it.id.ifBlank { UUID.randomUUID().toString() }, created = now + n) }
        write(ctx, list + added)
        added.size
    }

    fun delete(ctx: Context, id: String) = synchronized(lock) {
        val list = FamilyItems.listFromJson(try { SecureFiles.readText(index(ctx)) } catch (_: Exception) { null })
        list.firstOrNull { it.id == id }?.file?.takeIf { it.isNotEmpty() }?.let { File(dir(ctx), it).delete() }
        write(ctx, list.filterNot { it.id == id })
    }

    // The children's savings (funds and their quarterly balances), encrypted next to the file.
    private fun savingsFile(ctx: Context) = File(dir(ctx), "savings.json")

    fun savings(ctx: Context): List<Savings.Fund> = synchronized(lock) {
        Savings.listFromJson(try { SecureFiles.readText(savingsFile(ctx)) } catch (_: Exception) { null })
    }

    fun saveFund(ctx: Context, f: Savings.Fund): Savings.Fund = synchronized(lock) {
        val fund = f.copy(id = f.id.ifBlank { UUID.randomUUID().toString() })
        val list = Savings.listFromJson(try { SecureFiles.readText(savingsFile(ctx)) } catch (_: Exception) { null })
        SecureFiles.writeText(savingsFile(ctx), Savings.listToJson(list.filterNot { it.id == fund.id } + fund))
        fund
    }

    /** Funds removed on this phone (so the other parent's copy is removed too, and doesn't come back). */
    fun deletedFunds(ctx: Context): Set<String> = Settings.prefs(ctx).getStringSet("savings_deleted", emptySet()).orEmpty()
    fun forgetDeleted(ctx: Context, ids: Set<String>) = Settings.prefs(ctx).edit().putStringSet("savings_deleted", deletedFunds(ctx) - ids).apply()
    fun markDeleted(ctx: Context, id: String) = Settings.prefs(ctx).edit().putStringSet("savings_deleted", deletedFunds(ctx) + id).apply()

    fun deleteFund(ctx: Context, id: String) = synchronized(lock) {
        markDeleted(ctx, id)
        val list = Savings.listFromJson(try { SecureFiles.readText(savingsFile(ctx)) } catch (_: Exception) { null })
        SecureFiles.writeText(savingsFile(ctx), Savings.listToJson(list.filterNot { it.id == id }))
    }

    /** Deletes everything in the family file (the list and all the documents). */
    fun deleteAll(ctx: Context) = synchronized(lock) { dir(ctx).deleteRecursively() }

    fun bytes(ctx: Context, item: FamilyItems.Item): ByteArray? =
        if (item.file.isEmpty()) null else try { SecureFiles.readBytes(File(dir(ctx), item.file)) } catch (_: Exception) { null }

    /** A decrypted copy for viewing in another app (in the cache's share folder; replaced on the next view). */
    fun viewCopy(ctx: Context, item: FamilyItems.Item): File? {
        val b = bytes(ctx, item) ?: return null
        val out = File(File(ctx.cacheDir, "share").apply { mkdirs() }, "family_" + (if (item.mime == "application/pdf") "doc.pdf" else "doc.jpg"))
        out.writeBytes(b)
        return out
    }

    fun hash(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }.take(32)

    /** A picture of the document (an image, or a PDF's first page) for the screen and for auto-fill; null for text. */
    fun picture(ctx: Context, bytes: ByteArray, mime: String, maxSide: Int = 1400): Bitmap? = try {
        if (mime == "application/pdf") pdfPage(ctx, bytes, maxSide)
        else {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            var sample = 1
            while (maxOf(opts.outWidth, opts.outHeight) / sample > maxSide * 2) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    } catch (_: Throwable) { null }

    private fun pdfPage(ctx: Context, bytes: ByteArray, maxSide: Int): Bitmap? {
        val tmp = File.createTempFile("ff", ".pdf", ctx.cacheDir)
        try {
            tmp.writeBytes(bytes)
            ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { r ->
                    if (r.pageCount == 0) return null
                    r.openPage(0).use { p ->
                        val scale = maxSide.toFloat() / maxOf(p.width, p.height)
                        val bmp = Bitmap.createBitmap((p.width * scale).toInt().coerceAtLeast(1), (p.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        return bmp
                    }
                }
            }
        } finally { tmp.delete() }
    }

    fun jpegBase64(bmp: Bitmap, maxSide: Int = 1280): String {
        val scale = maxSide.toFloat() / maxOf(bmp.width, bmp.height)
        val b = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
        val out = ByteArrayOutputStream()
        b.compress(Bitmap.CompressFormat.JPEG, 82, out)
        return android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
    }
}
