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
        PRIVATE("private", "חיסכון פרטי", "Private savings"),
        CRYPTO("crypto", "מטבעות דיגיטליים", "Crypto");
        val label: String get() = L.t(he, en)
        companion object { fun of(id: String?) = values().firstOrNull { it.id == id } ?: PRIVATE }
    }

    /** One quarterly report: the balance on that date (and what it says about the period, when it says). */
    data class Report(val date: String, val balance: Double, val deposits: Double? = null, val returnPct: Double? = null, val feesPct: Double? = null, val doc: String = "",
                      /** How many months the report covers (a quarter = 3), when known. */
                      val months: Int? = null,
                      /** Crypto: how many units of each coin (by symbol, e.g. "BTC") on the report's date. */
                      val coins: Map<String, Double> = emptyMap(),
                      /** A statement in dollars: its value in $ as written (the ₪ balance is that at the day's dollar rate). */
                      val usd: Double? = null)

    /** A coin in a crypto fund: its symbol, the units held (as of the last report) and what's bought each month (₪). */
    data class Coin(val symbol: String, val units: Double = 0.0, val monthly: Double = 0.0)

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
        /** Crypto: the coins, their units and monthly buys. */
        val coins: List<Coin> = emptyList(),
    ) {
        val lastReport: Report? get() = reports.maxByOrNull { it.date }
        val perMonth: Double get() = monthly + stateMonthly + coins.sumOf { it.monthly }
        val title: String get() = isolateLatin(listOf(provider, track).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { name.ifBlank { kind.label } })
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
    fun estimate(f: Fund, today: String, o: Official? = null, p: Prices? = null): Estimate? =
        if (f.kind == Kind.CRYPTO) estimateCrypto(f, today, p) ?: best(f, today, null) else best(f, today, o)

    /** A child's total now (estimated), over the funds that have a report. */
    fun childTotal(funds: List<Fund>, child: String, today: String, est: (Fund) -> Estimate? = { estimate(it, today) }): Double =
        funds.filter { it.child == child }.sumOf { est(it)?.total ?: 0.0 }

    fun familyTotal(funds: List<Fund>, today: String, est: (Fund) -> Estimate? = { estimate(it, today) }): Double =
        funds.sumOf { est(it)?.total ?: 0.0 }

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
        .put("coins", JSONArray().apply { f.coins.forEach { put(JSONObject().put("symbol", it.symbol).put("units", it.units).put("monthly", it.monthly)) } })
        .put("reports", JSONArray().apply {
            f.reports.forEach { r ->
                put(JSONObject().put("date", r.date).put("balance", r.balance).put("deposits", r.deposits ?: JSONObject.NULL)
                    .put("returnPct", r.returnPct ?: JSONObject.NULL).put("feesPct", r.feesPct ?: JSONObject.NULL).put("doc", r.doc)
                    .put("months", r.months ?: JSONObject.NULL).put("coins", JSONObject(r.coins as Map<*, *>)).put("usd", r.usd ?: JSONObject.NULL))
            }
        })

    fun fromJson(o: JSONObject): Fund = Fund(
        id = o.optString("id"), child = o.optString("child"), kind = Kind.of(o.optString("kind")), provider = o.optString("provider"),
        name = fixTicker(o.optString("name")), track = fixTicker(o.optString("track")), monthly = num(o, "monthly") ?: 0.0, stateMonthly = num(o, "stateMonthly") ?: 0.0,
        note = o.optString("note"), updated = o.optLong("updated"), gemelId = o.optInt("gemelId"), gemelName = o.optString("gemelName"),
        coins = (o.optJSONArray("coins") ?: JSONArray()).let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { c ->
            Coin(c.optString("symbol").uppercase(), num(c, "units") ?: 0.0, num(c, "monthly") ?: 0.0) }.filter { it.symbol.isNotBlank() } },
        reports = (o.optJSONArray("reports") ?: JSONArray()).let { a ->
            (0 until a.length()).mapNotNull { a.optJSONObject(it) }.mapNotNull { r ->
                val d = FamilyItems.cleanDate(r.optString("date")); val b = num(r, "balance")
                if (d.isEmpty() || b == null) null else Report(d, b, num(r, "deposits"), num(r, "returnPct"), num(r, "feesPct"), r.optString("doc"),
                    if (r.isNull("months") || !r.has("months")) null else r.optInt("months").takeIf { it in 1..12 },
                    r.optJSONObject("coins")?.let { c -> c.keys().asSequence().associateWith { c.optDouble(it) }.filterValues { !it.isNaN() } } ?: emptyMap(),
                    num(r, "usd"))
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
                       val months: Int? = null,
                       /** Crypto: units per coin, what's bought each month per coin (₪), and the value in dollars. */
                       val coins: List<Coin> = emptyList(), val valueUsd: Double? = null,
                       /** The fund's number at the Ministry of Finance, as printed (the same number as at Gemel-Net). */
                       val fundNumber: Int? = null) {
        /** What goes in each month: as written, or the period's deposits spread over its months. */
        val perMonth: Double? get() = if (coins.any { it.monthly > 0 }) coins.sumOf { it.monthly }
            else if (monthly != null || stateMonthly != null) (monthly ?: 0.0) + (stateMonthly ?: 0.0)
            else deposits?.let { it / (months ?: 3).coerceIn(1, 12) }
    }

    fun reading(o: JSONObject): Reading {
        fun s(k: String) = o.optString(k).takeIf { !o.isNull(k) && it != "null" }.orEmpty().trim()
        fun d(k: String) = if (o.isNull(k)) null else FamilyItems.cleanAmount(o.opt(k))
        return Reading(fixTicker(s("provider")), fixTicker(s("fund_name")), fixTicker(s("track")), FamilyItems.cleanDate(s("report_date")), d("balance"), d("deposits_in_period"),
            d("parent_monthly_deposit"), d("state_monthly_deposit"), if (o.isNull("return_pct")) null else o.optDouble("return_pct").takeIf { !it.isNaN() },
            if (o.isNull("fees_pct")) null else o.optDouble("fees_pct").takeIf { !it.isNaN() },
            when (s("kind")) { "child_savings" -> Kind.CHILD_SAVINGS; "private" -> Kind.PRIVATE; "crypto" -> Kind.CRYPTO; else -> null }, s("child_name"),
            if (o.isNull("period_months")) null else o.optInt("period_months").takeIf { it in 1..12 },
            (o.optJSONArray("coins") ?: JSONArray()).let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) }.mapNotNull { c ->
                val sym = c.optString("symbol").uppercase().trim()
                if (sym.isEmpty()) null else Coin(sym, FamilyItems.cleanAmount(c.opt("units")) ?: 0.0, FamilyItems.cleanAmount(c.opt("monthly_ils")) ?: 0.0) } },
            d("value_usd"),
            if (o.isNull("fund_number")) null else o.optString("fund_number").filter { it.isDigit() }.toIntOrNull()?.takeIf { it in 100..999999 })
    }

    // ---- Official monthly returns (Gemel-Net, data.gov.il) ----

    /** A month as yyyymm (e.g. 202609). */
    fun ym(date: String): Int = try { LocalDate.parse(date).let { it.year * 100 + it.monthValue } } catch (_: Exception) { 0 }
    private fun nextYm(m: Int) = if (m % 100 == 12) (m / 100 + 1) * 100 + 1 else m + 1

    /** Which list a kind of savings is in, at Gemel-Net. */
    fun classification(k: Kind) = if (k == Kind.CHILD_SAVINGS) "קופת גמל להשקעה - חסכון לילד" else "קופת גמל להשקעה"

    private val COMPANIES = listOf("הראל", "מור", "מגדל", "הפניקס", "מנורה", "אלטשולר", "מיטב", "ילין", "אנליסט", "אינפיניטי", "כלל", "איילון", "פסגות", "אקסלנס")

    private fun norm(s: String) = fixTicker(s).lowercase().replace("&", "").replace(Regex("""[^\p{L}\p{N}]+"""), " ").replace(Regex("""\bs\s*p\s*500\b"""), "sp500").replace(Regex("""\bsp\s+500\b"""), "sp500").trim()

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

    /** A fund at Gemel-Net in its latest month: its track (sub-specialization) and the published numbers. */
    data class Peer(val id: Int, val sub: String, val ytd: Double?, val avg3y: Double?, val fee: Double?, val period: Int = 0)

    /** This fund next to the other funds of the same track (same list at Gemel-Net): facts, not a judgement. */
    data class PeerCompare(val sub: String, val count: Int, val period: Int,
                           val ytd: Double?, val avgYtd: Double?, val rankYtd: Int?,
                           val avg3y: Double?, val avgAvg3y: Double?,
                           val fee: Double?, val avgFee: Double?)

    /** Null when the fund isn't in [rows] or is alone in its track. Averages are simple (each fund counts once). */
    fun compare(rows: List<Peer>, fundId: Int): PeerCompare? {
        val me = rows.firstOrNull { it.id == fundId } ?: return null
        val same = rows.filter { it.sub.trim() == me.sub.trim() }.distinctBy { it.id }
        if (same.size < 2) return null
        fun avg(v: List<Double>) = if (v.isEmpty()) null else v.average()
        val byYtd = same.mapNotNull { it.ytd?.let { y -> it.id to y } }.sortedByDescending { it.second }
        return PeerCompare(me.sub.trim(), same.size, me.period,
            me.ytd, avg(same.mapNotNull { it.ytd }), byYtd.indexOfFirst { it.first == fundId }.takeIf { it >= 0 && me.ytd != null }?.plus(1),
            me.avg3y, avg(same.mapNotNull { it.avg3y }),
            me.fee, avg(same.mapNotNull { it.fee }))
    }

    /** The best estimate: official returns when the fund was found there; else the last balance plus the deposits since. */
    fun best(f: Fund, today: String, o: Official?): Estimate? =
        estimateOfficial(f, today, o) ?: f.lastReport?.let { r ->
            val m = monthsSince(r.date, today); Estimate(r.balance + m * f.perMonth, r.balance, m * f.perMonth, m, r.date, 0.0, null, r.returnPct)
        }

    /** The year's return so far from the official months (compounded), or null when none of the year is published. */
    fun yearToDate(o: Official?, year: Int): Double? {
        val months = o?.yields?.filterKeys { it / 100 == year }?.values ?: return null
        if (months.isEmpty()) return null
        return (months.fold(1.0) { acc, y -> acc * (1 + y / 100.0) } - 1) * 100
    }

    /** The last 12 published months' return (compounded), or null with fewer than 12. */
    fun lastYear(o: Official?): Double? {
        val ys = o?.yields?.entries?.sortedByDescending { it.key }?.take(12) ?: return null
        if (ys.size < 12) return null
        return (ys.fold(1.0) { acc, e -> acc * (1 + e.value / 100.0) } - 1) * 100
    }

    /** A return over several funds, each weighted by how much it holds; funds without the number are left out. */
    fun weighted(parts: List<Pair<Double, Double?>>): Double? {
        val known = parts.filter { it.second != null && it.first > 0 }
        val w = known.sumOf { it.first }
        return if (w <= 0) null else known.sumOf { it.first * it.second!! } / w
    }

    // ---- Crypto: today's prices (₪) ----

    /** CoinGecko's names for the usual symbols. */
    val COIN_IDS = mapOf("BTC" to "bitcoin", "ETH" to "ethereum", "SOL" to "solana", "XRP" to "ripple", "ADA" to "cardano", "DOGE" to "dogecoin",
        "LTC" to "litecoin", "BNB" to "binancecoin", "USDT" to "tether", "USDC" to "usd-coin", "DOT" to "polkadot", "AVAX" to "avalanche-2", "LINK" to "chainlink")

    /** Prices in ₪: now per symbol, and each day's price per symbol (yyyy-MM-dd). */
    data class Prices(val now: Map<String, Double>, val daily: Map<String, Map<String, Double>> = emptyMap(),
                      /** When today's prices were fetched (ms), 0 = unknown. */
                      val at: Long = 0L) {
        /** The price on a day, or the closest day before it (up to a week). */
        fun on(symbol: String, date: String): Double? {
            val days = daily[symbol] ?: return null
            var d = try { LocalDate.parse(date) } catch (_: Exception) { return null }
            repeat(8) { days[d.toString()]?.let { return it }; d = d.minusDays(1) }
            return null
        }
    }

    /**
     * A crypto fund now: the units on the report's date at today's price, plus each month's buys since (on the
     * 15th, at that day's price) at today's price. Null without today's prices for its coins.
     */
    fun estimateCrypto(f: Fund, today: String, p: Prices?): Estimate? {
        val r = f.lastReport ?: return null
        if (p == null) return null
        val units = r.coins.ifEmpty { f.coins.associate { it.symbol to it.units } }
        if (units.isEmpty() || units.keys.any { p.now[it] == null }) return null
        var value = units.entries.sumOf { it.value * p.now.getValue(it.key) }
        val reportValue = units.entries.sumOf { e -> p.on(e.key, r.date)?.let { e.value * it } ?: Double.NaN }.takeIf { !it.isNaN() } ?: r.balance
        var deposits = 0.0; var months = 0
        var m = try { LocalDate.parse(r.date).withDayOfMonth(15).plusMonths(1) } catch (_: Exception) { return null }
        val end = try { LocalDate.parse(today) } catch (_: Exception) { return null }
        while (!m.isAfter(end)) {
            for (c in f.coins.filter { it.monthly > 0 }) {
                val now = p.now[c.symbol] ?: continue
                val then = p.on(c.symbol, m.toString()) ?: now
                value += c.monthly / then * now
                deposits += c.monthly
            }
            months++; m = m.plusMonths(1)
        }
        val growth = value - reportValue - deposits
        val base = reportValue + deposits
        return Estimate(value, reportValue, deposits, months, r.date, growth, if (base > 0) growth / base * 100 else null, r.returnPct)
    }

    /** A crypto fund's change since 1 January (its coins by today's weight), or null without the prices. */
    fun yearToDateCrypto(f: Fund, p: Prices?, year: Int): Double? {
        if (p == null) return null
        val units = (f.lastReport?.coins ?: emptyMap()).ifEmpty { f.coins.associate { it.symbol to it.units } }
        val parts = units.mapNotNull { (sym, u) ->
            val now = p.now[sym] ?: return@mapNotNull null
            val jan = p.on(sym, "$year-01-01") ?: p.daily[sym]?.filterKeys { it.startsWith("$year-01") }?.minByOrNull { it.key }?.value ?: return@mapNotNull null
            (u * now) to (now / jan - 1) * 100
        }
        return weighted(parts.map { it.first to it.second })
    }

    /** The dollar in ₪ on a day: USDT's price that day (a dollar-pegged coin, within a fraction of a percent of the dollar), else today's. */
    fun usdRate(date: String, p: Prices?): Double? = p?.on("USDT", date) ?: p?.now?.get("USDT")

    /** Dollars to ₪ at the day's rate; null without a rate. */
    fun usdToIls(usd: Double, date: String, p: Prices?): Double? = usdRate(date, p)?.let { usd * it }

    /** A crypto fund split by coin: each coin's share of its worth at today's prices (empty without prices). */
    fun coinShares(f: Fund, p: Prices?): Map<String, Double> {
        if (p == null) return emptyMap()
        val units = (f.lastReport?.coins ?: emptyMap()).ifEmpty { f.coins.associate { it.symbol to it.units } }
        val worth = units.mapNotNull { (s, u) -> p.now[s]?.let { s to u * it } }.toMap().filterValues { it > 0 }
        val sum = worth.values.sum()
        return if (sum <= 0) emptyMap() else worth.mapValues { it.value / sum }
    }

    /** Coins' worth in ₪ on a day (each at that day's price, or the closest before it); null without a price for every coin. */
    fun valueOn(units: Map<String, Double>, date: String, p: Prices?): Double? {
        if (p == null || units.isEmpty() || date.isEmpty()) return null
        var v = 0.0
        for ((sym, u) in units) v += u * (p.on(sym, date) ?: return null)
        return v
    }

    /** A crypto fund's change since yesterday's price (its coins by today's weight), or null without the prices. */
    fun dailyChangeCrypto(f: Fund, p: Prices?, today: String): Double? {
        if (p == null) return null
        val yesterday = try { LocalDate.parse(today).minusDays(1).toString() } catch (_: Exception) { return null }
        val units = (f.lastReport?.coins ?: emptyMap()).ifEmpty { f.coins.associate { it.symbol to it.units } }
        val parts = units.mapNotNull { (sym, u) ->
            val now = p.now[sym] ?: return@mapNotNull null
            val then = p.daily[sym]?.get(yesterday) ?: return@mapNotNull null
            (u * now) to (now / then - 1) * 100
        }
        return weighted(parts)
    }

    // ---- What the portfolio is made of, and how it got here ----

    /** Over the funds' estimates: the balances in the last reports, what was deposited since, and the gain or loss since. */
    data class Breakdown(val reported: Double, val depositedSince: Double, val growth: Double) {
        val total: Double get() = reported + depositedSince + growth
    }

    fun breakdown(est: List<Estimate?>): Breakdown = est.filterNotNull().let { e ->
        Breakdown(e.sumOf { it.reported }, e.sumOf { it.depositedSince }, e.sumOf { it.total - it.reported - it.depositedSince })
    }

    /**
     * The total over time: one point per report date (each fund at its latest report on or before that date; a fund
     * with no report yet counts 0), and today's estimate last. Empty with fewer than two points.
     */
    fun history(funds: List<Fund>, today: String, todayTotal: Double): List<Pair<String, Double>> {
        val dates = funds.flatMap { f -> f.reports.map { it.date } }.filter { it <= today }.distinct().sorted()
        val pts = dates.map { d -> d to funds.sumOf { f -> f.reports.filter { it.date <= d }.maxByOrNull { it.date }?.balance ?: 0.0 } }.toMutableList()
        // Today's estimate last (it replaces a report dated today: the estimate already includes it).
        if (todayTotal > 0) { if (pts.isNotEmpty() && pts.last().first == today) pts[pts.size - 1] = today to todayTotal else pts += today to todayTotal }
        return if (pts.size < 2) emptyList() else pts
    }

    /**
     * A newer quarterly report should be out: the quarter after the last report's ended more than [graceDays] ago.
     * Returns that quarter's end date (the reminder's key), or null. Not for crypto (a statement any day) or a fund with no report.
     */
    fun reportDue(f: Fund, today: String, graceDays: Long = 30): String? {
        if (f.kind == Kind.CRYPTO) return null
        val r = f.lastReport ?: return null
        return try {
            val d = LocalDate.parse(r.date); val t = LocalDate.parse(today)
            // The end of the quarter after the report's quarter.
            val qEndMonth = ((d.monthValue - 1) / 3 + 1) * 3
            val thisQEnd = LocalDate.of(d.year, qEndMonth, 1).plusMonths(1).minusDays(1)
            val next = if (d >= thisQEnd.minusDays(3)) thisQEnd.plusDays(1).plusMonths(3).minusDays(1) else thisQEnd
            if (!t.isBefore(next.plusDays(graceDays))) next.toString() else null
        } catch (_: Exception) { null }
    }

    /** How far toward a goal (0..1), or null without a goal. */
    fun progress(total: Double, goal: Double): Double? = if (goal <= 0) null else (total / goal).coerceIn(0.0, 1.0)

    // ---- What it could be: a simple projection (a calculator, not a forecast) ----

    data class YearPoint(val year: Int, val total: Double, val deposited: Double)

    /**
     * [start] today, [monthly] deposited every month, growing at [annualPct] a year (compounded monthly) for [years]:
     * the total and what was deposited at the end of each year. No fees, taxes or inflation: said on the screen.
     */
    fun project(start: Double, monthly: Double, annualPct: Double, years: Double): List<YearPoint> {
        val r = Math.pow(1 + annualPct / 100.0, 1.0 / 12) - 1
        val months = Math.round(years * 12).toInt().coerceIn(0, 12 * 60)
        val out = mutableListOf(YearPoint(0, start, start))
        var total = start; var deposited = start
        for (m in 1..months) {
            total = total * (1 + r) + monthly
            deposited += monthly
            if (m % 12 == 0 || m == months) out += YearPoint(Math.ceil(m / 12.0).toInt(), total, deposited)
        }
        return out.distinctBy { it.year }
    }

    /** Years from [today] until [birth]'s 18th birthday (0 when already 18, null without a birth date). */
    fun yearsTo18(birth: String, today: String): Double? = try {
        val b = LocalDate.parse(FamilyItems.cleanDate(birth).ifEmpty { birth }); val t = LocalDate.parse(today)
        val at18 = b.plusYears(18)
        if (!at18.isAfter(t)) 0.0 else java.time.temporal.ChronoUnit.MONTHS.between(t, at18) / 12.0
    } catch (_: Exception) { null }

    /**
     * A ticker read backwards from a Hebrew PDF (its text kept in visual order): "P&S 500" is "S&P 500".
     * Applied to what's read and to what's kept, so the fund is found at Gemel-Net and shown right.
     */
    fun fixTicker(s: String): String = s.replace(Regex("""(?i)\bP\s*&\s*S\b"""), "S&P")

    /** English inside Hebrew ("עוקב מדד S&P 500") kept in its own order, so it isn't shown as "P&S 500". */
    fun isolateLatin(s: String): String = Regex("""[A-Za-z][A-Za-z0-9&.'\- ]*[A-Za-z0-9]|[A-Za-z]""").replace(s) { "\u2066" + it.value + "\u2069" }
}
