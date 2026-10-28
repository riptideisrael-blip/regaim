package com.regaim.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * The family file (stage 2): receipts, documents, coupons, vouchers and gifts, each optionally of one child.
 * The data and its rules only (no Android): reading, totals, what's about to expire, duplicates, and turning the
 * AI's suggestion into fields the parent confirms. Unit-tested.
 */
object FamilyItems {
    enum class Kind(val id: String, val he: String, val en: String) {
        RECEIPT("receipt", "קבלה", "Receipt"),
        DOCUMENT("document", "מסמך", "Document"),
        COUPON("coupon", "קופון", "Coupon"),
        VOUCHER("voucher", "שובר / כרטיס מתנה", "Voucher / gift card"),
        GIFT("gift", "מתנה", "Gift");

        val label: String get() = L.t(he, en)

        companion object {
            fun of(id: String?): Kind = values().firstOrNull { it.id == id?.trim()?.lowercase() } ?: DOCUMENT
        }
    }

    /** What a receipt or document is for: the parent picks it; totals and the list are grouped by it. */
    enum class Category(val id: String, val he: String, val en: String) {
        DAYCARE("daycare", "מעון / גן", "Daycare / kindergarten"),
        CLASSES("classes", "חוגים", "Classes"),
        HEALTH("health", "בריאות ובדיקות", "Health and checkups"),
        VACATION("vacation", "חופשה", "Vacation"),
        PHONE("phone", "סלולר ותקשורת", "Phone and internet"),
        CLOTHES("clothes", "ביגוד והנעלה", "Clothes and shoes"),
        EQUIPMENT("equipment", "ציוד", "Equipment"),
        GIFTS("gifts", "מתנות ואירועים", "Gifts and events"),
        HOME("home", "בית וחשבונות", "Home and bills"),
        OTHER("other", "אחר", "Other");

        val label: String get() = L.t(he, en)

        companion object {
            /** The category, or null when none was chosen. */
            fun of(id: String?): Category? = values().firstOrNull { it.id == id?.trim()?.lowercase() }
        }
    }

    /**
     * One item. [child] = the family member id of the child it belongs to, or "" for the whole family. Dates are
     * yyyy-MM-dd or "". [amount] in shekels (null = unknown). [file] = the encrypted file's name in the family file
     * folder, or "" for a text-only item.
     */
    data class Item(
        val id: String,
        val kind: Kind,
        val child: String = "",
        val title: String = "",
        val vendor: String = "",
        val amount: Double? = null,
        val date: String = "",
        val expires: String = "",
        val code: String = "",
        val note: String = "",
        val file: String = "",
        val mime: String = "",
        val source: String = "",
        val hash: String = "",
        val created: Long = 0L,
        /** Known balance of a voucher (null = unknown), and when it was last updated by the parent. */
        val balance: Double? = null,
        val balanceAt: String = "",
        /** [Category] id, or "" when none was chosen. */
        val category: String = "",
    )

    fun toJson(i: Item): JSONObject = JSONObject()
        .put("id", i.id).put("kind", i.kind.id).put("child", i.child).put("title", i.title).put("vendor", i.vendor)
        .put("amount", i.amount ?: JSONObject.NULL).put("date", i.date).put("expires", i.expires).put("code", i.code)
        .put("note", i.note).put("file", i.file).put("mime", i.mime).put("source", i.source).put("hash", i.hash)
        .put("created", i.created).put("balance", i.balance ?: JSONObject.NULL).put("balanceAt", i.balanceAt).put("category", i.category)

    private fun num(o: JSONObject, k: String): Double? = if (o.isNull(k) || !o.has(k)) null else o.optDouble(k).takeIf { !it.isNaN() }

    fun fromJson(o: JSONObject): Item = Item(
        id = o.optString("id"), kind = Kind.of(o.optString("kind")), child = o.optString("child"),
        title = o.optString("title"), vendor = o.optString("vendor"), amount = num(o, "amount"),
        date = cleanDate(o.optString("date")), expires = cleanDate(o.optString("expires")), code = o.optString("code"),
        note = o.optString("note"), file = o.optString("file"), mime = o.optString("mime"), source = o.optString("source"),
        hash = o.optString("hash"), created = o.optLong("created"), balance = num(o, "balance"), balanceAt = o.optString("balanceAt"),
        category = Category.of(o.optString("category"))?.id.orEmpty(),
    )

    fun listToJson(items: List<Item>): String = JSONArray().apply { items.forEach { put(toJson(it)) } }.toString()

    fun listFromJson(text: String?): List<Item> = try {
        val a = JSONArray(text ?: "[]")
        (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let(::fromJson) }.filter { it.id.isNotBlank() }
    } catch (_: Exception) { emptyList() }

    /** A real yyyy-MM-dd date, or "". Also takes dd/MM/yyyy and dd.MM.yy (as Israeli receipts write them). */
    fun cleanDate(raw: String?): String {
        val t = raw?.trim().orEmpty()
        Regex("""^(\d{4})-(\d{1,2})-(\d{1,2})""").find(t)?.destructured?.let { (y, m, d) -> return valid(y.toInt(), m.toInt(), d.toInt()) }
        Regex("""^(\d{1,2})[./-](\d{1,2})[./-](\d{2}|\d{4})$""").find(t)?.destructured?.let { (d, m, y) ->
            val year = if (y.length == 2) 2000 + y.toInt() else y.toInt()
            return valid(year, m.toInt(), d.toInt())
        }
        return ""
    }

    private fun valid(y: Int, m: Int, d: Int): String {
        if (y !in 1990..2100 || m !in 1..12) return ""
        val days = java.time.YearMonth.of(y, m).lengthOfMonth()
        if (d !in 1..days) return ""
        return "%04d-%02d-%02d".format(y, m, d)
    }

    /** "1,150.50", "₪1150", "1150 ש\"ח" → 1150.5; null when there's no number. */
    fun cleanAmount(raw: Any?): Double? {
        if (raw is Number) return raw.toDouble().takeIf { it >= 0 && !it.isNaN() }
        val t = raw?.toString()?.replace(",", "")?.replace(Regex("[^0-9.]"), " ")?.trim().orEmpty()
        val n = Regex("""\d+(\.\d+)?""").find(t)?.value?.toDoubleOrNull() ?: return null
        return n.takeIf { it < 10_000_000 }
    }

    /** Days from [today] (yyyy-MM-dd) to [date]; null without a date. Negative = already passed. */
    fun daysUntil(date: String, today: String): Long? {
        if (date.isEmpty() || today.isEmpty()) return null
        return try { java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.parse(today), java.time.LocalDate.parse(date)) } catch (_: Exception) { null }
    }

    /** Coupons, vouchers and documents that expire in the next [days] days (not already expired), soonest first. */
    fun expiringSoon(items: List<Item>, today: String, days: Int = 7): List<Item> =
        items.filter { daysUntil(it.expires, today)?.let { d -> d in 0..days.toLong() } == true }.sortedBy { it.expires }

    /** "פג היום", "פג מחר", "פג בעוד 3 ימים", "פג ב-30.11", "פג תוקף"; "" without an expiry date. */
    fun expiryLine(i: Item, today: String): String {
        val d = daysUntil(i.expires, today) ?: return ""
        return when {
            d < 0 -> L.t("פג תוקף", "Expired")
            d == 0L -> L.t("פג היום", "Expires today")
            d == 1L -> L.t("פג מחר", "Expires tomorrow")
            d <= 14 -> L.t("פג בעוד $d ימים", "Expires in $d days")
            else -> L.t("בתוקף עד ${short(i.expires)}", "Valid until ${short(i.expires)}")
        }
    }

    /** 2026-11-30 → 30.11.2026 */
    fun short(date: String): String = date.split("-").takeIf { it.size == 3 }?.let { "${it[2].trimStart('0')}.${it[1].trimStart('0')}.${it[0]}" } ?: date

    /** Total paid by year (receipts only), for one child or (child = null) everyone. */
    fun receiptTotal(items: List<Item>, year: Int, child: String? = null): Double =
        items.filter { it.kind == Kind.RECEIPT && it.date.startsWith("$year-") && (child == null || it.child == child) }.sumOf { it.amount ?: 0.0 }

    /** The year's receipts by category (most money first), for one child or (child = null) everyone. "" = no category. */
    fun totalsByCategory(items: List<Item>, year: Int, child: String? = null): List<Pair<Category?, Double>> =
        items.filter { it.kind == Kind.RECEIPT && it.date.startsWith("$year-") && (child == null || it.child == child) }
            .groupBy { Category.of(it.category) }
            .map { (c, l) -> c to l.sumOf { it.amount ?: 0.0 } }
            .sortedByDescending { it.second }

    /** Receipts by month ("2026-10"), newest month first and newest receipt first in each; undated ones last ("" key). */
    fun byMonth(items: List<Item>): List<Pair<String, List<Item>>> =
        items.groupBy { if (it.date.length >= 7) it.date.substring(0, 7) else "" }
            .map { (k, l) -> k to l.sortedWith(compareByDescending<Item> { it.date }.thenByDescending { it.created }) }
            .sortedWith(compareBy<Pair<String, List<Item>>> { it.first.isEmpty() }.thenByDescending { it.first })

    private val HE_MONTHS = listOf("ינואר", "פברואר", "מרץ", "אפריל", "מאי", "יוני", "יולי", "אוגוסט", "ספטמבר", "אוקטובר", "נובמבר", "דצמבר")
    private val EN_MONTHS = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")

    /** "2026-10" → "אוקטובר 2026"; "" → "בלי תאריך". */
    fun monthLabel(ym: String): String {
        val p = ym.split("-"); val m = p.getOrNull(1)?.toIntOrNull()
        if (p.size != 2 || m == null || m !in 1..12) return L.t("בלי תאריך", "No date")
        return L.t(HE_MONTHS[m - 1], EN_MONTHS[m - 1]) + " " + p[0]
    }

    /** Receipts paid in one month ("2026-10"). */
    fun monthTotal(items: List<Item>, ym: String): Double =
        items.filter { it.kind == Kind.RECEIPT && it.date.startsWith("$ym-") }.sumOf { it.amount ?: 0.0 }

    /** "1,150 ₪" or "1,150.50 ₪". */
    fun money(v: Double): String {
        val whole = v == Math.floor(v)
        val s = if (whole) "%,d".format(v.toLong()) else "%,.2f".format(v)
        return "$s ₪"
    }

    /** The same item saved before: the same file, or the same kind + vendor + amount + date (a receipt shared twice). */
    fun duplicateOf(items: List<Item>, candidate: Item): Item? = items.firstOrNull { o ->
        o.id != candidate.id && (
            (candidate.hash.isNotEmpty() && o.hash == candidate.hash) ||
                (candidate.kind == o.kind && candidate.amount != null && candidate.amount == o.amount &&
                    candidate.date.isNotEmpty() && candidate.date == o.date &&
                    candidate.vendor.isNotBlank() && candidate.vendor.trim().equals(o.vendor.trim(), ignoreCase = true)) ||
                (candidate.code.isNotBlank() && candidate.code.trim() == o.code.trim()))
    }

    /**
     * The AI's suggestion (a JSON object) as an item draft. Only what it found is filled; a child is chosen only when
     * the name it read matches one of [children] (id → name) exactly. Fields stay editable before saving.
     */
    fun fromSuggestion(base: Item, s: JSONObject, children: Map<String, String>): Item {
        fun str(k: String) = s.optString(k).trim().takeIf { it.isNotEmpty() && it != "null" }
        val childName = str("child")
        val childId = childName?.let { n -> children.entries.firstOrNull { it.value.trim() == n }?.key }
        return base.copy(
            kind = str("kind")?.let { Kind.of(it) } ?: base.kind,
            title = str("title")?.take(80) ?: base.title,
            vendor = str("vendor")?.take(60) ?: base.vendor,
            amount = cleanAmount(if (s.isNull("amount")) null else s.opt("amount")) ?: base.amount,
            date = str("date")?.let { cleanDate(it) }?.ifEmpty { null } ?: base.date,
            expires = str("expires")?.let { cleanDate(it) }?.ifEmpty { null } ?: base.expires,
            code = str("code")?.take(60) ?: base.code,
            category = str("category")?.let { Category.of(it)?.id } ?: base.category,
            child = childId ?: base.child,
        )
    }

    /** One chain in the family wallet: its valid coupons and vouchers, what they're worth, and the nearest expiry. */
    data class Chain(val name: String, val items: List<Item>, val total: Double, val nearest: String)

    fun isWallet(i: Item) = i.kind == Kind.COUPON || i.kind == Kind.VOUCHER || i.kind == Kind.GIFT
    fun expired(i: Item, today: String) = i.expires.isNotEmpty() && i.expires < today
    fun worth(i: Item) = i.balance ?: i.amount ?: 0.0

    /** The valid wallet items grouped by chain (vendor, or the title when there's none), the most valuable chain first. */
    fun wallet(items: List<Item>, today: String): List<Chain> =
        items.filter { isWallet(it) && !expired(it, today) }
            .groupBy { it.vendor.trim().ifEmpty { it.title.trim().substringBefore(" · ") }.ifEmpty { "—" } }
            .map { (name, list) ->
                Chain(name, list.sortedBy { it.expires.ifEmpty { "9999" } }, list.sumOf { worth(it) }, list.map { it.expires }.filter { it.isNotEmpty() }.minOrNull().orEmpty())
            }
            .sortedWith(compareByDescending<Chain> { it.total }.thenBy { it.name })

    /** A long code or card number shows only its last 4 digits ("•••• 9507"). */
    fun masked(code: String) = if (code.count { it.isDigit() } >= 12) "•••• " + code.filter { it.isLetterOrDigit() }.takeLast(4) else code

    /** The first web link in a note (a BuyMe voucher, say). */
    fun link(note: String) = Regex("""https?://\S+""").find(note)?.value.orEmpty()

    /** The card security number kept in the note ("CVV 848"), or "". */
    fun cvv(note: String) = Regex("""CVV (\d{3,4})""").find(note)?.groupValues?.get(1).orEmpty()
}
