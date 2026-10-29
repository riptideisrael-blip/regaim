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
}
