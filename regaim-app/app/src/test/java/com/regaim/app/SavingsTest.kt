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
}
