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
    data class Report(val date: String, val balance: Double, val deposits: Double? = null, val returnPct: Double? = null, val feesPct: Double? = null, val doc: String = "")

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

    data class Estimate(val total: Double, val reported: Double, val depositedSince: Double, val months: Int, val asOf: String)

    /** The fund now: the last report's balance plus the deposits since. Null before the first report. */
    fun estimate(f: Fund, today: String): Estimate? {
        val r = f.lastReport ?: return null
        val m = monthsSince(r.date, today)
        val added = m * f.perMonth
        return Estimate(r.balance + added, r.balance, added, m, r.date)
    }

    /** A child's total now (estimated), over the funds that have a report. */
    fun childTotal(funds: List<Fund>, child: String, today: String): Double =
        funds.filter { it.child == child }.sumOf { estimate(it, today)?.total ?: 0.0 }

    fun familyTotal(funds: List<Fund>, today: String): Double = funds.sumOf { estimate(it, today)?.total ?: 0.0 }

    /** The fund a new report belongs to: same child, same company and (when known) same track; else null (a new fund). */
    fun match(funds: List<Fund>, child: String, provider: String, track: String): Fund? {
        fun n(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
        val same = funds.filter { it.child == child && n(it.provider).isNotEmpty() && (n(it.provider).contains(n(provider)) || n(provider).contains(n(it.provider))) && n(provider).isNotEmpty() }
        return same.firstOrNull { n(track).isNotEmpty() && n(it.track) == n(track) } ?: same.singleOrNull()
    }

    /** Adds (or replaces, for the same date) a report. */
    fun withReport(f: Fund, r: Report): Fund = f.copy(reports = (f.reports.filterNot { it.date == r.date } + r).sortedBy { it.date })

    // ---- JSON ----
    private fun num(o: JSONObject, k: String): Double? = if (o.isNull(k) || !o.has(k)) null else o.optDouble(k).takeIf { !it.isNaN() }

    fun toJson(f: Fund): JSONObject = JSONObject().put("id", f.id).put("child", f.child).put("kind", f.kind.id).put("provider", f.provider)
        .put("name", f.name).put("track", f.track).put("monthly", f.monthly).put("stateMonthly", f.stateMonthly).put("note", f.note)
        .put("reports", JSONArray().apply {
            f.reports.forEach { r ->
                put(JSONObject().put("date", r.date).put("balance", r.balance).put("deposits", r.deposits ?: JSONObject.NULL)
                    .put("returnPct", r.returnPct ?: JSONObject.NULL).put("feesPct", r.feesPct ?: JSONObject.NULL).put("doc", r.doc))
            }
        })

    fun fromJson(o: JSONObject): Fund = Fund(
        id = o.optString("id"), child = o.optString("child"), kind = Kind.of(o.optString("kind")), provider = o.optString("provider"),
        name = o.optString("name"), track = o.optString("track"), monthly = num(o, "monthly") ?: 0.0, stateMonthly = num(o, "stateMonthly") ?: 0.0,
        note = o.optString("note"),
        reports = (o.optJSONArray("reports") ?: JSONArray()).let { a ->
            (0 until a.length()).mapNotNull { a.optJSONObject(it) }.mapNotNull { r ->
                val d = FamilyItems.cleanDate(r.optString("date")); val b = num(r, "balance")
                if (d.isEmpty() || b == null) null else Report(d, b, num(r, "deposits"), num(r, "returnPct"), num(r, "feesPct"), r.optString("doc"))
            }.sortedBy { it.date }
        },
    )

    fun listToJson(funds: List<Fund>): String = JSONArray().apply { funds.forEach { put(toJson(it)) } }.toString()
    fun listFromJson(text: String?): List<Fund> = try {
        val a = JSONArray(text ?: "[]"); (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(::fromJson) }.filter { it.id.isNotBlank() }
    } catch (_: Exception) { emptyList() }

    /** What the report reader found (fields it couldn't read stay empty). */
    data class Reading(val provider: String, val name: String, val track: String, val date: String, val balance: Double?, val deposits: Double?,
                       val monthly: Double?, val stateMonthly: Double?, val returnPct: Double?, val feesPct: Double?, val kind: Kind?, val childName: String)

    fun reading(o: JSONObject): Reading {
        fun s(k: String) = o.optString(k).takeIf { !o.isNull(k) && it != "null" }.orEmpty().trim()
        fun d(k: String) = if (o.isNull(k)) null else FamilyItems.cleanAmount(o.opt(k))
        return Reading(s("provider"), s("fund_name"), s("track"), FamilyItems.cleanDate(s("report_date")), d("balance"), d("deposits_in_period"),
            d("parent_monthly_deposit"), d("state_monthly_deposit"), if (o.isNull("return_pct")) null else o.optDouble("return_pct").takeIf { !it.isNaN() },
            if (o.isNull("fees_pct")) null else o.optDouble("fees_pct").takeIf { !it.isNaN() },
            when (s("kind")) { "child_savings" -> Kind.CHILD_SAVINGS; "private" -> Kind.PRIVATE; else -> null }, s("child_name"))
    }
}
