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
    fun read(a: Activity, uri: Uri, child: String, done: (Savings.Reading?, String) -> Unit) = readAll(a, listOf(uri), child, done)

    /**
     * Several documents of one fund at once (a statement, a tracks report, a screenshot): each is kept and read, and
     * what they say is joined into one reading ([Savings.combine]); the id is the document the balance came from.
     * Documents of different people: only the first person's are used, and the parent is told.
     */
    fun readAll(a: Activity, uris: List<Uri>, child: String, done: (Savings.Reading?, String) -> Unit) {
        dropped = (uris.size - MAX).coerceAtLeast(0)
        Thread {
            val got = uris.take(MAX).map { uri ->
                val type = try { a.contentResolver.getType(uri) } catch (_: Exception) { null }.orEmpty()
                val b = try { a.contentResolver.openInputStream(uri)?.use { it.readBytes() } } catch (_: Exception) { null }
                one(a, b, type, child)
            }
            finish(a, got, done)
        }.start()
    }

    /** The same, for a document already in hand (shared into the family file). */
    fun readBytes(a: Activity, b: ByteArray, type: String, child: String, done: (Savings.Reading?, String) -> Unit) {
        Thread { finish(a, listOf(one(a, b, type, child)), done) }.start()
    }

    /** At most this many documents in one go (each is one reading by the AI). */
    const val MAX = 5

    /** Files past [MAX] in the last pick (said on the fund's screen). */
    @Volatile private var dropped = 0

    /**
     * What the last reading wants the parent to know (some files unread, another child's files left out, files past
     * the limit). Shown in the fund's screen itself, where it stays readable (not a toast that disappears); taken once.
     */
    @Volatile var lastNote: String? = null
    fun takeNote(): String? = lastNote.also { lastNote = null }

    /** One document: kept in the family file, then read (signed in). Null reading: too big, unreadable or not signed in. */
    private fun one(a: Activity, b: ByteArray?, type: String, child: String): Pair<Savings.Reading?, String> {
        val ctx = a.applicationContext
        if (b == null || b.size > 20 * 1024 * 1024) return null to ""
        val pdf = type == "application/pdf" || (b.size > 4 && String(b, 0, 4) == "%PDF")
        val mime = if (pdf) "application/pdf" else "image/jpeg"
        val kids = Profile.members(ctx).filter { !it.isPet }
        val doc = FamilyFile.save(ctx, FamilyItems.Item("", FamilyItems.Kind.DOCUMENT, child = child, title = L.t("דוח חיסכון", "Savings report"),
            date = java.time.LocalDate.now().toString(), mime = mime, hash = FamilyFile.hash(b), source = "savings"), b, if (pdf) "pdf" else "jpg")
        val reading = if (!Account.signedIn(ctx)) null else try {
            val pdfB64 = if (pdf && b.size < 8 * 1024 * 1024) android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP) else null
            // A picture: as is. A PDF too big to send: its first page as a picture.
            val pic = if (pdf && pdfB64 != null) null else FamilyFile.picture(ctx, b, mime, 2000)?.let { FamilyFile.jpegBase64(it, 1800) }
            Savings.reading(ClaudeClient.readSavingsReport(ctx, pic, pdfB64, kids.map { it.name }, java.time.LocalDate.now().toString()))
        } catch (_: Exception) { null }
        return reading to doc.id
    }

    private fun finish(a: Activity, got: List<Pair<Savings.Reading?, String>>, done: (Savings.Reading?, String) -> Unit) {
        val ctx = a.applicationContext
        var read = got.filter { it.first != null }
        // One person's documents only (the first one named).
        val mixed = Savings.mixedPeople(read.map { it.first!! })
        if (mixed) {
            val first = read.map { it.first!!.childName.trim().substringBefore(' ') }.first { it.isNotEmpty() }
            read = read.filter { it.first!!.childName.trim().substringBefore(' ').let { n -> n.isEmpty() || n == first } }
        }
        val readings = read.map { it.first!! }
        val reading = Savings.combine(readings)
        val docId = if (readings.isEmpty()) got.firstOrNull { it.second.isNotEmpty() }?.second.orEmpty() else read[Savings.baseIndex(readings)].second
        // A crypto statement: the coins' prices (to value it in shekels on its date).
        if (reading != null && (reading.coins.isNotEmpty() || reading.valueUsd != null)) try { CryptoPrices.refresh(ctx, reading.coins.map { it.symbol }.toSet() + "USDT") } catch (_: Exception) { }
        val tooBig = got.any { it.second.isEmpty() }
        a.runOnUiThread {
            if (a.isDestroyed) return@runOnUiThread
            val msg = when {
                reading == null && !Account.signedIn(a) -> L.t("קריאה אוטומטית דורשת התחברות. ממלאים ידנית.", "Reading needs you to sign in. Fill it in by hand.")
                reading == null && tooBig && got.size == 1 -> L.t("לא הצלחנו לקרוא את הקובץ (עד 20MB)", "Couldn't read the file (up to 20 MB)")
                reading == null -> L.t("לא הצלחנו לקרוא את הדוח. ממלאים ידנית.", "Couldn't read the report. Fill it in by hand.")
                mixed -> L.t("הקבצים של יותר מילד אחד. נלקחו רק של ${reading.childName.substringBefore(' ')}; את השאר מעלים בנפרד.",
                    "The files are of more than one child. Only ${reading.childName.substringBefore(' ')}'s were used; upload the rest separately.")
                read.size < got.size -> L.t("חלק מהקבצים לא נקראו. השאר מולאו, בדקו לפני שמירה.", "Some files couldn't be read. The rest are filled in; check before saving.")
                else -> null
            }
            val extra = if (dropped > 0) L.t("נקראו רק $MAX הקבצים הראשונים. את השאר מעלים בפעם נוספת.", "Only the first $MAX files were read. Upload the rest again.") else null
            dropped = 0
            // Nothing read: a short message here (no fund screen with a note opens). Otherwise the note on the fund's screen.
            if (reading == null) msg?.let { Toast.makeText(a, it, Toast.LENGTH_LONG).show() }
            else lastNote = listOfNotNull(msg, extra).joinToString(" ").ifBlank { null }
            done(reading, docId)
        }
    }
}
