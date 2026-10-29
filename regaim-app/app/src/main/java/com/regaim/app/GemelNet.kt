package com.regaim.app

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * The official monthly returns of savings funds: Gemel-Net, the Ministry of Finance's open data (data.gov.il,
 * dataset "gemelnet", updated every month). Public numbers only: nothing about the family is sent, just which fund to
 * look up. Each fund's months are kept on the phone and refreshed twice a day at most. Background thread only.
 */
object GemelNet {
    private const val RESOURCE = "a30dcbea-a1d2-482c-ae29-8f781f5025fb" // "נתוני הגמל נט לשנים 2024-היום"
    private const val API = "https://data.gov.il/api/3/action/datastore_search"

    private fun get(params: Map<String, String>): JSONObject {
        val q = (params + ("resource_id" to RESOURCE)).entries.joinToString("&") { it.key + "=" + URLEncoder.encode(it.value, "UTF-8") }
        val conn = URL("$API?$q").openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000; conn.readTimeout = 45_000
        conn.setRequestProperty("User-Agent", "Moments-family-app")
        try {
            if (conn.responseCode !in 200..299) throw IllegalStateException("gemelnet ${conn.responseCode}")
            val o = JSONObject(conn.inputStream.bufferedReader().readText())
            if (!o.optBoolean("success")) throw IllegalStateException("gemelnet not success")
            return o.getJSONObject("result")
        } finally { conn.disconnect() }
    }

    private fun dir(ctx: Context) = File(ctx.filesDir, "gemelnet").apply { mkdirs() }

    /** The fund's months kept on the phone (null if never fetched). */
    fun cached(ctx: Context, fundId: Int): Savings.Official? {
        if (fundId <= 0) return null
        return try {
            val o = JSONObject(File(dir(ctx), "$fundId.json").readText()).getJSONObject("yields")
            Savings.Official(o.keys().asSequence().associate { it.toInt() to o.getDouble(it) })
        } catch (_: Exception) { null }
    }

    private fun fetchedAt(ctx: Context, fundId: Int): Long = try { JSONObject(File(dir(ctx), "$fundId.json").readText()).optLong("at") } catch (_: Exception) { 0L }

    private fun fetchYields(ctx: Context, fundId: Int) {
        val r = get(mapOf("filters" to JSONObject().put("FUND_ID", fundId).toString(), "fields" to "REPORT_PERIOD,MONTHLY_YIELD",
            "sort" to "REPORT_PERIOD desc", "limit" to "400"))
        val rows = r.getJSONArray("records")
        val y = JSONObject()
        for (i in 0 until rows.length()) {
            val o = rows.getJSONObject(i)
            if (!o.isNull("MONTHLY_YIELD")) y.put(o.optInt("REPORT_PERIOD").toString(), o.optDouble("MONTHLY_YIELD"))
        }
        SafeFiles.write(File(dir(ctx), "$fundId.json"), JSONObject().put("at", System.currentTimeMillis()).put("yields", y).toString())
    }

    /** The funds in a list at Gemel-Net in its latest month (number and name). */
    private fun candidates(classification: String): List<Pair<Int, String>> {
        val latest = get(mapOf("fields" to "REPORT_PERIOD", "sort" to "REPORT_PERIOD desc", "limit" to "1"))
            .getJSONArray("records").getJSONObject(0).optInt("REPORT_PERIOD")
        val rows = get(mapOf("filters" to JSONObject().put("FUND_CLASSIFICATION", classification).put("REPORT_PERIOD", latest).toString(),
            "fields" to "FUND_ID,FUND_NAME", "limit" to "2000")).getJSONArray("records")
        return (0 until rows.length()).map { rows.getJSONObject(it) }.map { it.optInt("FUND_ID") to it.optString("FUND_NAME").trim() }
    }

    /**
     * Finds each fund at Gemel-Net (once, by company and track) and refreshes its months (twice a day at most).
     * True when something new arrived (the screen shows it).
     */
    fun refresh(ctx: Context): Boolean {
        var changed = false
        val lists = HashMap<String, List<Pair<Int, String>>>()
        for (f in FamilyFile.savings(ctx)) {
            var fund = f
            if (fund.gemelId == 0 && fund.provider.isNotBlank()) {
                val cls = Savings.classification(fund.kind)
                val found = try { Savings.pickGemel(lists.getOrPut(cls) { candidates(cls) }, fund.provider, fund.track) } catch (_: Exception) { null }
                if (found != null) {
                    fund = FamilyFile.saveFund(ctx, fund.copy(gemelId = found.first, gemelName = found.second, updated = System.currentTimeMillis()))
                    changed = true
                }
            }
            if (fund.gemelId > 0 && System.currentTimeMillis() - fetchedAt(ctx, fund.gemelId) > 12 * 3600_000L) {
                try { fetchYields(ctx, fund.gemelId); changed = true } catch (_: Exception) { }
            }
        }
        return changed
    }

    /** "09/2026": the last month published for a fund. */
    fun latestLabel(o: Savings.Official?): String = o?.latest?.takeIf { it > 0 }?.let { "%02d/%d".format(it % 100, it / 100) }.orEmpty()
}
