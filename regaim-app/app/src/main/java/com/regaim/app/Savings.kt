package com.regaim.app

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/**
 * The children's savings: each child's funds ("חיסכון לכל ילד" or a private fund/policy), what's deposited each month,
 * and the balances read from the quarterly reports. How much a child has now is an estimate the parent can follow:
 * the last report's balance plus the deposits made since (no guessed returns; the next report brings the real number).
 * No Android here (unit-tested).
 */
object Savings {
    enum class Kind(val id: String, val he: String, val en: String) {
        CHILD_SAVINGS("child_savings", "חיסכון לכל ילד", "Savings for every child"),
        PRIVATE("private", "חיסכון פרטי", "Private savings");
        val label: String get() = L.t(he, en)
        companion object { fun of(id: String?) = values().firstOrNull { it.id == id } ?: PRIVATE }
    }

    /** One quarterly report: the balance on that date (and what it says about the period, when it says). */
    data class Report(val date: String, val balance: Double, val deposits: Double? = null, val returnPct: Double? = null, val feesPct: Double? = null, val doc: String = "",
                      /** How many months the report covers (a quarter = 3), when known. */
                      val months: Int? = null)

    data class Fund(
        val id: String,
        val child: String,
        val kind: Kind = Kind.PRIVATE,
        /** The company managing it (e.g. "מור", "הראל", "אלטשולר שחם"). */
        val provider: String = "",
        val name: String = "",
        /** The investment track (e.g. "מניות", "כללי", "הלכה", "סיכון מוגבר"). */
        val track: String = "",
        /** What the parents deposit each month (₪). */
        val monthly: Double = 0.0,
        /** What the state deposits each month (₪), for "savings for every child". */
        val stateMonthly: Double = 0.0,
        val reports: List<Report> = emptyList(),
        val note: String = "",
        /** When it was last changed (either parent): the newer details win when both phones changed it. */
        val updated: Long = 0L,
        /** The fund's number in Gemel-Net (the Ministry of Finance's monthly returns), and its name there; 0 = not found. */
        val gemelId: Int = 0,
        val gemelName: String = "",
    ) {
        val lastReport: Report? get() = reports.maxByOrNull { it.date }
        val perMonth: Double get() = monthly + stateMonthly
        val title: String get() = listOf(provider, track).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { name.ifBlank { kind.label } }
    }

    /** Months whose deposit came after the report: a report at the end of September, today 29 October → 1. */
    fun monthsSince(reportDate: String, today: String): Int = try {
        val r = LocalDate.parse(reportDate); val t = LocalDate.parse(today)
        ((t.year * 12 + t.monthValue) - (r.year * 12 + r.monthValue)).coerceAtLeast(0)
    } catch (_: Exception) { 0 }

    /**
     * [growth]: the estimated gain or loss since the report (₪); [ratePct]: the return assumed since the report (%), or
     * null when the report didn't say its return (then only the deposits are added).
     */
    data class Estimate(val total: Double, val reported: Double, val depositedSince: Double, val months: Int, val asOf: String,
                        val growth: Double = 0.0, val ratePct: Double? = null, val periodReturnPct: Double? = null)

    /**
     * The fund now: from the official monthly returns when the fund was found at Gemel-Net ([estimateOfficial]);
     * otherwise the last report's balance plus the deposits since (no guessed return). Null before the first report.
     */
    fun estimate(f: Fund, today: String, o: Official? = null): Estimate? = best(f, today, o)

    /** A child's total now (estimated), over the funds that have a report. */
    fun childTotal(funds: List<Fund>, child: String, today: String, official: (Fund) -> Official? = { null }): Double =
        funds.filter { it.child == child }.sumOf { estimate(it, today, official(it))?.total ?: 0.0 }

    fun familyTotal(funds: List<Fund>, today: String, official: (Fund) -> Official? = { null }): Double =
        funds.sumOf { estimate(it, today, official(it))?.total ?: 0.0 }

    /** The fund a new report belongs to: same child, same company and (when known) same track; else null (a new fund). */
    fun match(funds: List<Fund>, child: String, provider: String, track: String): Fund? {
        fun n(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
        val same = funds.filter { it.child == child && n(it.provider).isNotEmpty() && (n(it.provider).contains(n(provider)) || n(provider).contains(n(it.provider))) && n(provider).isNotEmpty() }
        return same.firstOrNull { n(track).isNotEmpty() && n(it.track) == n(track) } ?: same.singleOrNull()
    }

    /**
     * The same fund from the other parent's phone, joined with this phone's: every report from both (one per date,
     * the newer side's when both have it), and the newer side's details. Either parent can add the next report.
     */
    fun merge(local: Fund?, remote: Fund): Fund {
        if (local == null) return remote
        val newer = if (remote.updated > local.updated) remote else local
        val older = if (newer === remote) local else remote
        val reports = (newer.reports + older.reports.filter { o -> newer.reports.none { it.date == o.date } }).sortedBy { it.date }
        return newer.copy(child = newer.child.ifEmpty { older.child }, reports = reports, updated = maxOf(local.updated, remote.updated))
    }

    /** What the other parent's phone gets: the fund with the child's name (each phone has its own ids). */
    fun shareData(f: Fund, childName: String): JSONObject = toJson(f).put("child", "").put("childName", childName).put("type", "fund")

    fun fromShare(id: String, o: JSONObject, childId: (String) -> String): Fund = fromJson(o).copy(id = id, child = childId(o.optString("childName")))

    /** Adds (or replaces, for the same date) a report. */
    fun withReport(f: Fund, r: Report): Fund = f.copy(reports = (f.reports.filterNot { it.date == r.date } + r).sortedBy { it.date })

    // ---- JSON ----
    private fun num(o: JSONObject, k: String): Double? = if (o.isNull(k) || !o.has(k)) null else o.optDouble(k).takeIf { !it.isNaN() }

    fun toJson(f: Fund): JSONObject = JSONObject().put("id", f.id).put("child", f.child).put("kind", f.kind.id).put("provider", f.provider)
        .put("name", f.name).put("track", f.track).put("monthly", f.monthly).put("stateMonthly", f.stateMonthly).put("note", f.note).put("updated", f.updated)
        .put("gemelId", f.gemelId).put("gemelName", f.gemelName)
        .put("reports", JSONArray().apply {
            f.reports.forEach { r ->
                put(JSONObject().put("date", r.date).put("balance", r.balance).put("deposits", r.deposits ?: JSONObject.NULL)
                    .put("returnPct", r.returnPct ?: JSONObject.NULL).put("feesPct", r.feesPct ?: JSONObject.NULL).put("doc", r.doc)
                    .put("months", r.months ?: JSONObject.NULL))
            }
        })

    fun fromJson(o: JSONObject): Fund = Fund(
        id = o.optString("id"), child = o.optString("child"), kind = Kind.of(o.optString("kind")), provider = o.optString("provider"),
        name = o.optString("name"), track = o.optString("track"), monthly = num(o, "monthly") ?: 0.0, stateMonthly = num(o, "stateMonthly") ?: 0.0,
        note = o.optString("note"), updated = o.optLong("updated"), gemelId = o.optInt("gemelId"), gemelName = o.optString("gemelName"),
        reports = (o.optJSONArray("reports") ?: JSONArray()).let { a ->
            (0 until a.length()).mapNotNull { a.optJSONObject(it) }.mapNotNull { r ->
                val d = FamilyItems.cleanDate(r.optString("date")); val b = num(r, "balance")
                if (d.isEmpty() || b == null) null else Report(d, b, num(r, "deposits"), num(r, "returnPct"), num(r, "feesPct"), r.optString("doc"),
                    if (r.isNull("months") || !r.has("months")) null else r.optInt("months").takeIf { it in 1..12 })
            }.sortedBy { it.date }
        },
    )

    fun listToJson(funds: List<Fund>): String = JSONArray().apply { funds.forEach { put(toJson(it)) } }.toString()
    fun listFromJson(text: String?): List<Fund> = try {
        val a = JSONArray(text ?: "[]"); (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(::fromJson) }.filter { it.id.isNotBlank() }
    } catch (_: Exception) { emptyList() }

    /** What the report reader found (fields it couldn't read stay empty). */
    data class Reading(val provider: String, val name: String, val track: String, val date: String, val balance: Double?, val deposits: Double?,
                       val monthly: Double?, val stateMonthly: Double?, val returnPct: Double?, val feesPct: Double?, val kind: Kind?, val childName: String,
                       val months: Int? = null) {
        /** What goes in each month: as written, or the period's deposits spread over its months. */
        val perMonth: Double? get() = if (monthly != null || stateMonthly != null) (monthly ?: 0.0) + (stateMonthly ?: 0.0)
            else deposits?.let { it / (months ?: 3).coerceIn(1, 12) }
    }

    fun reading(o: JSONObject): Reading {
        fun s(k: String) = o.optString(k).takeIf { !o.isNull(k) && it != "null" }.orEmpty().trim()
        fun d(k: String) = if (o.isNull(k)) null else FamilyItems.cleanAmount(o.opt(k))
        return Reading(s("provider"), s("fund_name"), s("track"), FamilyItems.cleanDate(s("report_date")), d("balance"), d("deposits_in_period"),
            d("parent_monthly_deposit"), d("state_monthly_deposit"), if (o.isNull("return_pct")) null else o.optDouble("return_pct").takeIf { !it.isNaN() },
            if (o.isNull("fees_pct")) null else o.optDouble("fees_pct").takeIf { !it.isNaN() },
            when (s("kind")) { "child_savings" -> Kind.CHILD_SAVINGS; "private" -> Kind.PRIVATE; else -> null }, s("child_name"),
            if (o.isNull("period_months")) null else o.optInt("period_months").takeIf { it in 1..12 })
    }

    // ---- Official monthly returns (Gemel-Net, data.gov.il) ----

    /** A month as yyyymm (e.g. 202609). */
    fun ym(date: String): Int = try { LocalDate.parse(date).let { it.year * 100 + it.monthValue } } catch (_: Exception) { 0 }
    private fun nextYm(m: Int) = if (m % 100 == 12) (m / 100 + 1) * 100 + 1 else m + 1

    /** Which list a kind of savings is in, at Gemel-Net. */
    fun classification(k: Kind) = if (k == Kind.CHILD_SAVINGS) "קופת גמל להשקעה - חסכון לילד" else "קופת גמל להשקעה"

    private val COMPANIES = listOf("הראל", "מור", "מגדל", "הפניקס", "מנורה", "אלטשולר", "מיטב", "ילין", "אנליסט", "אינפיניטי", "כלל", "איילון", "פסגות", "אקסלנס")

    private fun norm(s: String) = s.lowercase().replace("&", "").replace(Regex("""[^\p{L}\p{N}]+"""), " ").replace(Regex("""\bs\s*p\s*500\b"""), "sp500").replace(Regex("""\bsp\s+500\b"""), "sp500").trim()

    /**
     * The Gemel-Net fund that is this fund: the same company, and the track's words in its name ("עוקב מדד S&P 500",
     * "סיכון מוגבר", "הלכה"). Null when it isn't clear (then the parent sees no official returns, never a wrong one).
     */
    fun pickGemel(candidates: List<Pair<Int, String>>, provider: String, track: String): Pair<Int, String>? {
        val company = COMPANIES.firstOrNull { norm(provider).contains(norm(it)) } ?: norm(provider).split(' ').firstOrNull { it.length >= 2 } ?: return null
        val mine = candidates.filter { norm(it.second).contains(norm(company)) }
        if (mine.isEmpty()) return null
        val words = norm(track).split(' ').filter { it.length >= 2 && it !in setOf("מסלול", "עוקב", "מדד", "חוסכים", "המעדיפים", "לחוסכים") }
        if (words.isEmpty()) return mine.singleOrNull()
        val scored = mine.map { c -> c to words.count { w -> norm(c.second).split(' ').any { it == w || it.startsWith(w) } } }
        val best = scored.maxOf { it.second }
        if (best < words.size) return null // every word of the track must be there
        return scored.filter { it.second == best }.singleOrNull()?.first
    }

    /** The months published at Gemel-Net for a fund: yyyymm → that month's return in percent. */
    data class Official(val yields: Map<Int, Double>) {
        val latest: Int get() = yields.keys.maxOrNull() ?: 0
    }

    /**
     * The fund now from the official returns: from the month after the report, each published month grows the
     * balance by that month's return (less the management fee, a twelfth of the yearly rate) and adds that month's
     * deposit (with about half the month's return); months not published yet add only the deposit. Checked on a real
     * case: from the March report to September it came to 6,220 ₪, and the September report said 6,217 ₪.
     */
    fun estimateOfficial(f: Fund, today: String, o: Official?): Estimate? {
        val r = f.lastReport ?: return null
        if (o == null || o.yields.isEmpty()) return null
        val end = ym(today)
        var m = nextYm(ym(r.date))
        var total = r.balance
        var months = 0; var grownMonths = 0; var growthFactor = 1.0
        val fee = (r.feesPct ?: 0.0) / 100.0 / 12.0
        while (m <= end) {
            val y = o.yields[m]
            if (y != null) {
                val g = (1 + y / 100.0) * (1 - fee)
                // The deposit comes in during the month (around the 10th): it gets about half of the month's return.
                total = total * g + f.perMonth * (1 + (g - 1) / 2)
                growthFactor *= g; grownMonths++
            } else total += f.perMonth
            months++
            m = nextYm(m)
        }
        val added = months * f.perMonth
        return Estimate(total, r.balance, added, months, r.date, total - r.balance - added,
            if (grownMonths > 0) (growthFactor - 1) * 100 else null, r.returnPct)
    }

    /** The best estimate: official returns when the fund was found there; else the last balance plus the deposits since. */
    fun best(f: Fund, today: String, o: Official?): Estimate? =
        estimateOfficial(f, today, o) ?: f.lastReport?.let { r ->
            val m = monthsSince(r.date, today); Estimate(r.balance + m * f.perMonth, r.balance, m * f.perMonth, m, r.date, 0.0, null, r.returnPct)
        }
}
