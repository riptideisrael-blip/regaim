package com.regaim.app

import com.regaim.app.Savings.Fund
import com.regaim.app.Savings.Report
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SavingsTest {
    private val today = "2026-10-29"

    @Test fun estimateIsLastBalancePlusDepositsSince() {
        val f = Fund("1", "uri", Savings.Kind.CHILD_SAVINGS, "מור", track = "מניות", monthly = 57.0, stateMonthly = 57.0,
            reports = listOf(Report("2026-06-30", 9000.0), Report("2026-09-30", 9850.0)))
        val e = Savings.estimate(f, today)!!
        assertEquals(1, e.months)
        assertEquals(9850.0, e.reported, 0.001)
        assertEquals(114.0, e.depositedSince, 0.001)
        assertEquals(9964.0, e.total, 0.001)
        assertEquals(3, Savings.monthsSince("2026-06-30", "2026-09-15"))
        assertEquals(0, Savings.monthsSince("2026-10-01", today))
        assertNull(Savings.estimate(f.copy(reports = emptyList()), today))
    }

    @Test fun totalsPerChildAndFamily() {
        val a = Fund("1", "uri", monthly = 100.0, reports = listOf(Report("2026-09-30", 1000.0)))
        val b = Fund("2", "uri", monthly = 0.0, reports = listOf(Report("2026-10-15", 500.0)))
        val c = Fund("3", "noam", monthly = 50.0, reports = listOf(Report("2026-09-30", 200.0)))
        val d = Fund("4", "noam") // no report yet: not counted
        val all = listOf(a, b, c, d)
        assertEquals(1600.0, Savings.childTotal(all, "uri", today), 0.001)
        assertEquals(250.0, Savings.childTotal(all, "noam", today), 0.001)
        assertEquals(1850.0, Savings.familyTotal(all, today), 0.001)
    }

    @Test fun aReportFindsItsFund() {
        val stocks = Fund("1", "uri", provider = "מור בית השקעות", track = "מניות")
        val general = Fund("2", "uri", provider = "מור בית השקעות", track = "כללי")
        val noam = Fund("3", "noam", provider = "הראל", track = "מניות")
        assertEquals("1", Savings.match(listOf(stocks, general, noam), "uri", "מור", "מניות")?.id)
        assertNull(Savings.match(listOf(stocks, general), "uri", "מור", ""))   // two tracks, unclear: ask
        assertEquals("3", Savings.match(listOf(stocks, noam), "noam", "הראל", "")?.id)
        assertNull(Savings.match(listOf(stocks), "noam", "מור", "מניות"))
        val f = Savings.withReport(stocks, Report("2026-09-30", 100.0))
        assertEquals(1, Savings.withReport(f, Report("2026-09-30", 120.0)).reports.size)
    }

    @Test fun readsTheReportReader() {
        val r = Savings.reading(JSONObject("""{"provider":"מור","fund_name":"מור חיסכון לכל ילד","track":"מניות","report_date":"30/09/2026",
            "balance":"9,850.12","deposits_in_period":342,"parent_monthly_deposit":57,"state_monthly_deposit":57,"return_pct":4.2,"fees_pct":null,
            "kind":"child_savings","child_name":"אורי"}"""))
        assertEquals("2026-09-30", r.date)
        assertEquals(9850.12, r.balance!!, 0.001)
        assertEquals(Savings.Kind.CHILD_SAVINGS, r.kind)
        assertNull(r.feesPct)
        val f = Fund("1", "uri", reports = listOf(Report("2026-09-30", 9850.12, 342.0, 4.2)), monthly = 57.0)
        assertEquals(listOf(f), Savings.listFromJson(Savings.listToJson(listOf(f))))
    }

    @Test fun noGuessedReturnWithoutOfficialData() {
        // A bad quarter (-5.66%) isn't carried forward: without official returns, only the deposits are added.
        val f = Fund("1", "uri", monthly = 100.0, reports = listOf(Report("2026-09-30", 10000.0, returnPct = -5.66, months = 3)))
        val e = Savings.estimate(f, today)!!
        assertEquals(10100.0, e.total, 0.001)
        assertNull(e.ratePct)
        assertEquals(-5.66, e.periodReturnPct!!, 0.0)
    }

    @Test fun bothParentsReportsJoin() {
        val mine = Fund("f1", "uri", provider = "מור", track = "מניות", monthly = 57.0, updated = 100, reports = listOf(Report("2026-06-30", 9000.0)))
        val theirs = Fund("f1", "kid9", provider = "מור", track = "מניות", monthly = 100.0, updated = 200, reports = listOf(Report("2026-09-30", 9850.0), Report("2026-06-30", 8999.0)))
        val m = Savings.merge(mine, theirs)
        assertEquals(listOf("2026-06-30", "2026-09-30"), m.reports.map { it.date })
        assertEquals(8999.0, m.reports[0].balance, 0.0) // the newer side's when both have the date
        assertEquals(100.0, m.monthly, 0.0)
        assertEquals(200L, m.updated)
        assertEquals(m, Savings.merge(m, theirs))
        val shared = Savings.shareData(mine, "אורי נתן")
        assertEquals("kid7", Savings.fromShare("f1", shared) { if (it == "אורי נתן") "kid7" else "" }.child)
        // Monthly from the period's deposits when the report doesn't say it.
        val r = Savings.reading(JSONObject("""{"deposits_in_period":342,"period_months":3}"""))
        assertEquals(114.0, r.perMonth!!, 0.001)
    }

    /** A real case: the Q1 report (31.3.2026, 4,344 ₪, 200 ₪ a month, fee 0.70%) and the Q3 report said 6,217 ₪. */
    @Test fun officialReturnsMatchTheNextReport() {
        val q1 = Fund("1", "uri", Savings.Kind.PRIVATE, "הראל", track = "עוקב מדד S&P 500", monthly = 200.0,
            reports = listOf(Report("2026-03-31", 4344.0, 600.0, -5.66, 0.70, months = 3)))
        val gemel = Savings.Official(mapOf(202601 to -1.62, 202602 to 0.11, 202603 to -4.22, 202604 to 3.23, 202605 to 0.44,
            202606 to 4.72, 202607 to 2.18, 202608 to 0.9, 202609 to 2.07))
        val e = Savings.estimateOfficial(q1, "2026-09-30", gemel)!!
        assertEquals(6217.0, e.total, 10.0) // the September report said 6,217 ₪
        // October isn't published yet: only its deposit is added.
        assertEquals(e.total + 200, Savings.estimateOfficial(q1, "2026-10-29", gemel)!!.total, 0.01)
        // The quarter's months compound to the report's own -5.66%.
        assertEquals(-5.66, ((1 - 0.0162) * (1 + 0.0011) * (1 - 0.0422) - 1) * 100, 0.02)
        // Not found there: the last balance and the deposits, no guessed return.
        assertEquals(4344.0 + 7 * 200, Savings.best(q1, "2026-10-29", null)!!.total, 0.01)
    }

    @Test fun findsTheFundAtGemelNet() {
        val sp = listOf(7958 to "מור גמל להשקעה - עוקב מדד S&P500", 13414 to "הראל גמל להשקעה עוקב מדד s&p 500",
            13563 to "מגדל גמל להשקעה עוקב מדד s&p500", 14865 to "אלטשולר שחם חיסכון פלוס עוקב מדד S&P 500")
        assertEquals(13414, Savings.pickGemel(sp, "הראל גמל להשקעה", "עוקב מדד S&P 500")?.first)
        assertEquals(14865, Savings.pickGemel(sp, "אלטשולר שחם", "S&P500")?.first)
        val kids = listOf(11375 to "הראל חיסכון לילד - חוסכים המעדיפים סיכון מועט", 11376 to "הראל חיסכון לילד - חוסכים המעדיפים סיכון בינוני",
            11377 to "הראל חיסכון לילד - חוסכים המעדיפים סיכון מוגבר", 9555 to "הראל חיסכון לילד - הלכה", 9420 to "מור חיסכון לילד - חוסכים המעדיפים סיכון מוגבר")
        assertEquals(11377, Savings.pickGemel(kids, "הראל", "סיכון מוגבר")?.first)
        assertEquals(9555, Savings.pickGemel(kids, "הראל", "הלכה")?.first)
        assertEquals(9420, Savings.pickGemel(kids, "מור בית השקעות", "חוסכים המעדיפים סיכון מוגבר")?.first)
        assertNull(Savings.pickGemel(kids, "הראל", ""))          // several tracks, none said: don't guess
        assertNull(Savings.pickGemel(kids, "מיטב", "סיכון מוגבר")) // not in the list
    }

    @Test fun yearReturnsForThePortfolio() {
        val o = Savings.Official(mapOf(202601 to -1.62, 202602 to 0.11, 202603 to -4.22, 202604 to 3.23, 202605 to 0.44,
            202606 to 4.72, 202607 to 2.18, 202608 to 0.9, 202609 to 2.07, 202512 to 1.0, 202511 to 1.0, 202510 to 1.0))
        assertEquals(7.79, Savings.yearToDate(o, 2026)!!, 0.03) // the September report said 7.79%
        assertNull(Savings.yearToDate(o, 2024))
        assertEquals(true, Savings.lastYear(o)!! > 7.79)
        assertNull(Savings.lastYear(Savings.Official(mapOf(202609 to 1.0))))
        assertEquals(6.0, Savings.weighted(listOf(3000.0 to 8.0, 1500.0 to 2.0, 500.0 to null))!!, 0.0001)
        assertNull(Savings.weighted(listOf(100.0 to null)))
    }

    /** The September Horizon statement: 0.00679205 BTC and 0.22533479 ETH, 150 ₪ of each bought every month. */
    @Test fun cryptoAtTodaysPrices() {
        val f = Fund("c", "uri", Savings.Kind.CRYPTO, "הוריזון",
            coins = listOf(Savings.Coin("BTC", 0.00679205, 150.0), Savings.Coin("ETH", 0.22533479, 150.0)),
            reports = listOf(Report("2026-09-30", 3650.0, coins = mapOf("BTC" to 0.00679205, "ETH" to 0.22533479))))
        val p = Savings.Prices(now = mapOf("BTC" to 250646.0, "ETH" to 7262.14), daily = mapOf(
            "BTC" to mapOf("2026-09-30" to 256517.5, "2026-10-15" to 240000.0, "2026-01-01" to 300000.0),
            "ETH" to mapOf("2026-09-30" to 8400.0, "2026-10-14" to 7000.0, "2026-01-01" to 9000.0)))
        val e = Savings.estimate(f, today, p = p)!!
        val held = 0.00679205 * 250646 + 0.22533479 * 7262.14
        val bought = 150.0 / 240000 * 250646 + 150.0 / 7000 * 7262.14 // October's buys (ETH: the day before the 15th)
        assertEquals(held + bought, e.total, 0.01)
        assertEquals(300.0, e.depositedSince, 0.0)
        assertEquals(0.00679205 * 256517.5 + 0.22533479 * 8400, e.reported, 0.01)
        assertEquals(true, e.growth < 0) // both fell since September
        assertEquals(300.0, f.perMonth, 0.0)
        // Without today's prices: the statement's value and the buys since, said plainly.
        assertEquals(3950.0, Savings.estimate(f, today)!!.total, 0.01)
        assertEquals(true, Savings.yearToDateCrypto(f, p, 2026)!! < 0)
        assertEquals(f, Savings.listFromJson(Savings.listToJson(listOf(f))).single())
        val r = Savings.reading(JSONObject("""{"kind":"crypto","provider":"הוריזון","value_usd":1171.82,"report_date":"30/09/2026",
            "coins":[{"symbol":"btc","units":"0.00679205","monthly_ils":150},{"symbol":"ETH","units":0.22533479,"monthly_ils":150}]}"""))
        assertEquals(Savings.Kind.CRYPTO, r.kind)
        assertEquals(listOf("BTC", "ETH"), r.coins.map { it.symbol })
        assertEquals(300.0, r.perMonth!!, 0.0)
        assertEquals(1171.82, r.valueUsd!!, 0.0)
    }

    @Test fun projectionCalculator() {
        // 10,000 ₪ today and 500 ₪ a month at 8% a year for 18 years.
        val p = Savings.project(10000.0, 500.0, 8.0, 18.0)
        val r = Math.pow(1.08, 1.0 / 12) - 1
        val fv = 10000 * Math.pow(1.08, 18.0) + 500 * (Math.pow(1 + r, 216.0) - 1) / r
        assertEquals(fv, p.last().total, 0.5)
        assertEquals(10000.0 + 500 * 216, p.last().deposited, 0.001)
        assertEquals(19, p.size) // today and each of the 18 years
        assertEquals(10000.0 + 6000, Savings.project(10000.0, 500.0, 0.0, 1.0).last().total, 0.001)
        assertEquals(173 / 12.0, Savings.yearsTo18("2023-04-20", "2026-10-29")!!, 0.001) // 18 on 20.4.2041: 14 years and 5 months
        assertEquals(0.0, Savings.yearsTo18("1990-01-01", today)!!, 0.0)
        assertNull(Savings.yearsTo18("", today))
    }
}
