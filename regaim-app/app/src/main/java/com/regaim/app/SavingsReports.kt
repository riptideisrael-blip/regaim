package com.regaim.app

import android.app.Activity
import android.net.Uri
import android.widget.Toast

/**
 * Reading a savings report or a crypto statement (PDF or picture): the document is kept in the family file, then
 * (signed in) it's read by the AI on request; for a crypto statement the coins' prices are fetched too, so the
 * balance in shekels can be worked out for its date. Used by the savings screen and by a fund's own screen.
 */
object SavingsReports {
    val TYPES = arrayOf("application/pdf", "image/*")

    /** Off the main thread; [done] runs on it with what was read (null: couldn't read) and the kept document's id. */
    fun read(a: Activity, uri: Uri, child: String, done: (Savings.Reading?, String) -> Unit) {
        Thread {
            val type = try { a.contentResolver.getType(uri) } catch (_: Exception) { null }.orEmpty()
            val b = try { a.contentResolver.openInputStream(uri)?.use { it.readBytes() } } catch (_: Exception) { null }
            work(a, b, type, child, done)
        }.start()
    }

    /** The same, for a document already in hand (shared into the family file). */
    fun readBytes(a: Activity, b: ByteArray, type: String, child: String, done: (Savings.Reading?, String) -> Unit) {
        Thread { work(a, b, type, child, done) }.start()
    }

    private fun work(a: Activity, b: ByteArray?, type: String, child: String, done: (Savings.Reading?, String) -> Unit) {
            val ctx = a.applicationContext
            if (b == null || b.size > 20 * 1024 * 1024) {
                a.runOnUiThread { Toast.makeText(a, L.t("לא הצלחנו לקרוא את הקובץ (עד 20MB)", "Couldn't read the file (up to 20 MB)"), Toast.LENGTH_LONG).show(); done(null, "") }
                return
            }
            val pdf = type == "application/pdf" || (b.size > 4 && String(b, 0, 4) == "%PDF")
            val mime = if (pdf) "application/pdf" else "image/jpeg"
            val kids = Profile.members(ctx).filter { !it.isPet }
            val doc = FamilyFile.save(ctx, FamilyItems.Item("", FamilyItems.Kind.DOCUMENT, child = child, title = L.t("דוח חיסכון", "Savings report"),
                date = java.time.LocalDate.now().toString(), mime = mime, hash = FamilyFile.hash(b), source = "savings"), b, if (pdf) "pdf" else "jpg")
            val reading = if (!Account.signedIn(ctx)) null else try {
                val pic = if (pdf) null else FamilyFile.picture(ctx, b, mime)?.let { FamilyFile.jpegBase64(it) }
                val pdfB64 = if (pdf && b.size < 8 * 1024 * 1024) android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP) else null
                Savings.reading(ClaudeClient.readSavingsReport(ctx, pic, pdfB64, kids.map { it.name }, java.time.LocalDate.now().toString()))
            } catch (_: Exception) { null }
            // A crypto statement: the coins' prices (to value it in shekels on its date).
            if (reading != null && (reading.coins.isNotEmpty() || reading.valueUsd != null)) try { CryptoPrices.refresh(ctx, reading.coins.map { it.symbol }.toSet() + "USDT") } catch (_: Exception) { }
            a.runOnUiThread {
                if (a.isDestroyed) return@runOnUiThread
                if (reading == null) Toast.makeText(a, if (!Account.signedIn(a)) L.t("קריאה אוטומטית דורשת התחברות. ממלאים ידנית.", "Reading needs you to sign in. Fill it in by hand.")
                    else L.t("לא הצלחנו לקרוא את הדוח. ממלאים ידנית.", "Couldn't read the report. Fill it in by hand."), Toast.LENGTH_LONG).show()
                done(reading, doc.id)
            }
    }
}
