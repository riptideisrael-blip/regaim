package com.regaim.app

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Coin prices in shekels from CoinGecko's public API (no account, no key): today's price (refreshed every half hour at
 * most) and each day's price for the last year (twice a day at most), for the coins the family holds. Only the coins'
 * names are sent. Kept on the phone so the savings show without a connection. Background thread only.
 */
object CryptoPrices {
    private const val API = "https://api.coingecko.com/api/v3"
    private fun file(ctx: Context) = File(ctx.filesDir, "crypto_prices.json")

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 30_000
        c.setRequestProperty("User-Agent", "Moments-family-app"); c.setRequestProperty("Accept", "application/json")
        try {
            if (c.responseCode !in 200..299) throw IllegalStateException("coingecko ${c.responseCode}")
            return c.inputStream.bufferedReader().readText()
        } finally { c.disconnect() }
    }

    private fun load(ctx: Context): JSONObject = try { JSONObject(file(ctx).readText()) } catch (_: Exception) { JSONObject() }

    /** The prices kept on the phone (null before the first fetch). */
    fun cached(ctx: Context): Savings.Prices? {
        val o = load(ctx)
        val now = o.optJSONObject("now") ?: return null
        val daily = o.optJSONObject("daily") ?: JSONObject()
        return Savings.Prices(
            now.keys().asSequence().associateWith { now.getDouble(it) },
            daily.keys().asSequence().associateWith { sym -> daily.getJSONObject(sym).let { d -> d.keys().asSequence().associateWith { d.getDouble(it) } } },
            o.optLong("nowAt"),
        )
    }

    /** Fetches what's missing or old for these symbols. True when something new arrived. */
    fun refresh(ctx: Context, symbols: Set<String>): Boolean {
        val ids = symbols.mapNotNull { s -> Savings.COIN_IDS[s]?.let { s to it } }.toMap()
        if (ids.isEmpty()) return false
        val o = load(ctx)
        var changed = false
        val nowAt = o.optLong("nowAt")
        if (System.currentTimeMillis() - nowAt > 30 * 60_000L || ids.keys.any { o.optJSONObject("now")?.has(it) != true }) {
            val r = JSONObject(get("$API/simple/price?ids=${ids.values.joinToString(",")}&vs_currencies=ils"))
            val now = JSONObject()
            for ((sym, id) in ids) r.optJSONObject(id)?.optDouble("ils")?.takeIf { !it.isNaN() }?.let { now.put(sym, it) }
            o.put("now", now).put("nowAt", System.currentTimeMillis()); changed = true
        }
        val daily = o.optJSONObject("daily") ?: JSONObject()
        val dailyAt = o.optJSONObject("dailyAt") ?: JSONObject()
        for ((sym, id) in ids) {
            if (System.currentTimeMillis() - dailyAt.optLong(sym) < 12 * 3600_000L && daily.has(sym)) continue
            try {
                val prices = JSONObject(get("$API/coins/$id/market_chart?vs_currency=ils&days=365&interval=daily")).getJSONArray("prices")
                val days = JSONObject()
                for (i in 0 until prices.length()) {
                    val p = prices.getJSONArray(i)
                    days.put(java.time.Instant.ofEpochMilli(p.getLong(0)).atZone(java.time.ZoneId.of("Asia/Jerusalem")).toLocalDate().toString(), p.getDouble(1))
                }
                daily.put(sym, days); dailyAt.put(sym, System.currentTimeMillis()); changed = true
            } catch (_: Exception) { }
        }
        o.put("daily", daily).put("dailyAt", dailyAt)
        if (changed) SafeFiles.write(file(ctx), o.toString())
        return changed
    }
}
