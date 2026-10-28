package com.regaim.app

import java.time.LocalDate

/**
 * Turns a parent's coupon spreadsheet into family-file drafts: each sheet is a chain (its name), each row a coupon,
 * voucher or card. Sheets differ (with or without headings, dates written as a date, a year, "סוף 2029" or "12/28"),
 * so every row is read by what its cells look like, with the headings as a hint. Nothing is guessed silently:
 * expired rows and unclear amounts are marked for the parent to check. No Android here (unit-tested).
 */
object CouponImport {
    data class Draft(
        val chain: String,
        val code: String = "",
        val link: String = "",
        val value: Double? = null,
        val expires: String = "",
        val cvv: String = "",
        val note: String = "",
        /** The amount might be a card's security number: the parent checks. */
        val unclear: Boolean = false,
        val row: Int = 0,
    ) {
        fun expired(today: LocalDate) = expires.isNotEmpty() && LocalDate.parse(expires).isBefore(today)
        /** A stable fingerprint, so importing the same file twice doesn't add everything twice. */
        val hash: String get() = listOf("xlsx", chain, code, link, value?.toString().orEmpty(), expires, cvv, note, row.toString()).joinToString("|").hashCode().toUInt().toString(16)
    }

    private fun endOfYear(y: Int) = LocalDate.of(y, 12, 31)
    private fun endOfMonth(y: Int, m: Int) = LocalDate.of(y, m, 1).plusMonths(1).minusDays(1)

    /** A cell as an expiry date, or null. */
    fun asDate(c: Any?): LocalDate? = when (c) {
        is LocalDate -> c
        is Double -> if (c % 1.0 == 0.0 && c in 2020.0..2040.0) endOfYear(c.toInt()) else null
        is String -> {
            val s = c.trim()
            Regex("""^(\d{1,2})/(\d{2})$""").find(s)?.destructured?.let { (m, y) ->
                m.toInt().takeIf { it in 1..12 }?.let { endOfMonth(2000 + y.toInt(), it) }
            } ?: Regex("""(?:סוף|שנת)\s*(20\d\d)""").find(s)?.groupValues?.get(1)?.let { endOfYear(it.toInt()) }
                ?: FamilyItems.cleanDate(s).takeIf { it.isNotEmpty() }?.let { LocalDate.parse(it) }
        }
        else -> null
    }

    private fun digits(s: String) = s.count { it.isDigit() }

    /** A coupon code, card number or link. */
    fun isCode(c: Any?): Boolean = when (c) {
        is Double -> c % 1.0 == 0.0 && c >= 1e6
        is String -> {
            val s = c.trim()
            s.startsWith("http") || Regex("""^[0-9\- ]{6,}$""").matches(s) ||
                (Regex("""^[A-Za-z0-9]{8,}$""").matches(s) && s.any { it.isDigit() }) || Regex("""^\d{6,}""").containsMatchIn(s)
        }
        else -> false
    }

    private fun codeText(c: Any?): String = when (c) {
        is Double -> c.toLong().toString()
        else -> c.toString().trim()
    }

    /** "500 שקל", "555 שח", 49.0 → the amount; a year (2020–2040) is never an amount. */
    fun asValue(c: Any?): Double? = when (c) {
        is Double -> c.takeIf { it >= 0 && !(it % 1.0 == 0.0 && it in 2020.0..2040.0) && it < 1e6 }
        is String -> Regex("""^\s*(\d+(?:\.\d+)?)\s*(?:₪|ש"ח|שח|שקל|שקלים)?(?:\s|$)""").find(c)?.groupValues?.get(1)?.toDoubleOrNull()
            ?.takeIf { !c.any { ch -> ch in 'A'..'Z' || ch in 'a'..'z' } }
        else -> null
    }

    private val CVV = Regex("""(?i)cv[rv]\s*[-:]?\s*(\d{3,4})|(\d{3,4})\s*cv[rv]""")

    private fun isHeading(cells: List<Any?>): Boolean {
        val texts = cells.filterIsInstance<String>()
        return texts.isNotEmpty() && texts.any { Regex("קופון|תאריך|שווי|שוי|סכום|תוקף").containsMatchIn(it) } &&
            cells.none { it is Double || it is LocalDate } && cells.none { isCode(it) }
    }

    fun parse(sheets: List<XlsxReader.Sheet>): List<Draft> {
        val out = mutableListOf<Draft>()
        for (sheet in sheets) {
            val chain = sheet.name.trim()
            var valueCol = -1; var dateCol = -1; var codeCol = -1
            sheet.rows.forEachIndexed { rowIndex, cells ->
                if (cells.all { it == null || (it is String && it.isBlank()) }) return@forEachIndexed
                if (isHeading(cells)) {
                    cells.forEachIndexed { i, c ->
                        val t = c?.toString().orEmpty()
                        when {
                            Regex("שווי|שוי|סכום").containsMatchIn(t) -> valueCol = i
                            Regex("תאריך|תוקף").containsMatchIn(t) -> dateCol = i
                            t.contains("קופון") -> codeCol = i
                        }
                    }
                    return@forEachIndexed
                }
                var code = ""; var link = ""; var value: Double? = null; var expires: LocalDate? = null; var cvv = ""
                val notes = mutableListOf<String>()
                cells.forEachIndexed { i, c ->
                    if (c == null) return@forEachIndexed
                    val s = c.toString().trim()
                    if (c is String) CVV.find(s)?.let { m ->
                        cvv = m.groupValues[1].ifEmpty { m.groupValues[2] }
                        CVV.replace(s, "").trim(' ', '(', ')', '-').takeIf { it.isNotEmpty() }?.let { notes += it }
                        return@forEachIndexed
                    }
                    val d = asDate(c)
                    if (d != null && (i == dateCol || dateCol < 0 || c is LocalDate) && expires == null) { expires = d; return@forEachIndexed }
                    if (isCode(c) && (i == codeCol || codeCol < 0 || i != valueCol)) {
                        val t = codeText(c)
                        if (t.startsWith("http")) link = t else if (code.isEmpty()) code = t else notes += t
                        return@forEachIndexed
                    }
                    val v = asValue(c)
                    if (v != null && (i == valueCol || valueCol < 0 || value == null)) { if (value == null) value = v else notes += s; return@forEachIndexed }
                    if (s.isNotEmpty() && s != ".") notes += s
                }
                // A card's security number looks like an amount: a 3-digit number that isn't round, in a row with a
                // card expiry (MM/YY) or next to another amount, is the CVV; alone beside a long card number it's unclear.
                var unclear = false
                val numbers = cells.filterIsInstance<Double>().filter { asValue(it) != null }
                val mmyy = cells.any { it is String && Regex("""^\s*\d{1,2}/\d{2}\s*$""").matches(it) }
                val card = digits(code) >= 12
                val odd = numbers.filter { it % 1.0 == 0.0 && it in 100.0..999.0 && it.toInt() % 10 != 0 }
                if (odd.isNotEmpty() && cvv.isEmpty() && (mmyy || numbers.size > 1)) {
                    cvv = odd.first().toInt().toString()
                    if (value != null && value in odd) value = numbers.firstOrNull { it !in odd }
                    notes.removeAll { it == odd.first().toString() || it == cvv }
                } else if (odd.isNotEmpty() && card && value != null && value in odd && cvv.isEmpty()) {
                    unclear = true
                }
                if (code.isEmpty() && link.isEmpty() && value == null && expires == null && notes.isEmpty()) return@forEachIndexed
                val draft = Draft(chain, code, link, value, expires?.toString().orEmpty(), cvv, notes.joinToString(" · "), unclear, rowIndex)
                // BuyMe-style sheets: a link, then its amount on the next line.
                val last = out.lastOrNull()
                if (last != null && last.chain == chain && last.link.isNotEmpty() && last.value == null && draft.code.isEmpty() && draft.link.isEmpty() && draft.value != null) {
                    out[out.size - 1] = last.copy(value = draft.value, note = listOf(last.note, draft.note).filter { it.isNotEmpty() }.joinToString(" · "))
                } else out += draft
            }
        }
        return out
    }

    /** The draft as a family-file item (a voucher when it has a value or a card, a coupon otherwise). */
    fun toItem(d: Draft): FamilyItems.Item {
        val kind = if (d.value != null || d.cvv.isNotEmpty() || d.link.isNotEmpty()) FamilyItems.Kind.VOUCHER else FamilyItems.Kind.COUPON
        val note = listOfNotNull(
            d.note.takeIf { it.isNotEmpty() },
            d.link.takeIf { it.isNotEmpty() },
            d.cvv.takeIf { it.isNotEmpty() }?.let { "CVV $it" },
            if (d.unclear) L.t("לבדוק: לא ברור אם הסכום הוא CVV", "Check: the amount might be the CVV") else null,
        ).joinToString("\n")
        return FamilyItems.Item(
            id = "", kind = kind, title = d.chain + (d.value?.let { " · " + FamilyItems.money(it) } ?: d.note.takeIf { it.isNotEmpty() }?.let { " · $it" }.orEmpty()),
            vendor = d.chain, amount = d.value, expires = d.expires, code = d.code, note = note, source = "excel", hash = d.hash,
            category = "",
        )
    }
}
