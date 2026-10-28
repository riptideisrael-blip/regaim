package com.regaim.app

import com.regaim.app.FamilyItems.Item
import com.regaim.app.FamilyItems.Kind
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FamilyItemsTest {
    private val today = "2026-10-28"

    @Test fun datesFromReceipts() {
        assertEquals("2026-04-01", FamilyItems.cleanDate("2026-04-01"))
        assertEquals("2026-04-01", FamilyItems.cleanDate("1/4/2026"))
        assertEquals("2026-04-01", FamilyItems.cleanDate("01.04.26"))
        assertEquals("", FamilyItems.cleanDate("31.02.2026"))
        assertEquals("", FamilyItems.cleanDate("אפריל"))
    }

    @Test fun amounts() {
        assertEquals(1150.5, FamilyItems.cleanAmount("₪1,150.50")!!, 0.001)
        assertEquals(2480.0, FamilyItems.cleanAmount("2480 ש\"ח")!!, 0.001)
        assertEquals(50.0, FamilyItems.cleanAmount(50)!!, 0.001)
        assertNull(FamilyItems.cleanAmount("בלי סכום"))
        assertEquals("1,150 ₪", FamilyItems.money(1150.0))
        assertEquals("1,150.50 ₪", FamilyItems.money(1150.5))
    }

    @Test fun expiry() {
        val c = Item("1", Kind.COUPON, expires = "2026-10-31")
        assertEquals("פג בעוד 3 ימים", FamilyItems.expiryLine(c, today))
        assertEquals("פג היום", FamilyItems.expiryLine(c.copy(expires = today), today))
        assertEquals("פג תוקף", FamilyItems.expiryLine(c.copy(expires = "2026-10-01"), today))
        assertEquals("בתוקף עד 30.11.2026", FamilyItems.expiryLine(c.copy(expires = "2026-11-30"), today))
        val list = listOf(c, c.copy(id = "2", expires = "2026-10-29"), c.copy(id = "3", expires = "2026-12-30"), c.copy(id = "4", expires = "2026-10-01"))
        assertEquals(listOf("2", "1"), FamilyItems.expiringSoon(list, today).map { it.id })
    }

    @Test fun totalsByYearAndChild() {
        val list = listOf(
            Item("1", Kind.RECEIPT, child = "uri", amount = 1150.0, date = "2026-10-01"),
            Item("2", Kind.RECEIPT, child = "noam", amount = 2480.0, date = "2026-10-01"),
            Item("3", Kind.RECEIPT, child = "uri", amount = 100.0, date = "2025-12-31"),
            Item("4", Kind.COUPON, child = "uri", amount = 50.0, date = "2026-10-02"),
        )
        assertEquals(3630.0, FamilyItems.receiptTotal(list, 2026), 0.001)
        assertEquals(1150.0, FamilyItems.receiptTotal(list, 2026, "uri"), 0.001)
    }

    @Test fun duplicates() {
        val r = Item("1", Kind.RECEIPT, vendor = "מעון פרפר", amount = 2480.0, date = "2026-10-01", hash = "abc")
        assertEquals("1", FamilyItems.duplicateOf(listOf(r), r.copy(id = "2", hash = "zzz", vendor = "מעון פרפר "))?.id)
        assertEquals("1", FamilyItems.duplicateOf(listOf(r), Item("2", Kind.DOCUMENT, hash = "abc"))?.id)
        assertNull(FamilyItems.duplicateOf(listOf(r), r.copy(id = "2", hash = "zzz", amount = 2500.0)))
        val c = Item("5", Kind.COUPON, code = "SHF50")
        assertEquals("5", FamilyItems.duplicateOf(listOf(c), Item("6", Kind.COUPON, code = "SHF50"))?.id)
    }

    @Test fun suggestionFillsOnlyWhatWasFound() {
        val base = Item("1", Kind.DOCUMENT, note = "שלי")
        val s = JSONObject("""{"kind":"receipt","vendor":"גן השקד","amount":"1,150","date":"1/10/2026","child":"אורי","code":null}""")
        val got = FamilyItems.fromSuggestion(base, s, mapOf("uri" to "אורי", "noam" to "נעם"))
        assertEquals(Kind.RECEIPT, got.kind)
        assertEquals("גן השקד", got.vendor)
        assertEquals(1150.0, got.amount!!, 0.001)
        assertEquals("2026-10-01", got.date)
        assertEquals("uri", got.child)
        assertEquals("", got.code)
        assertEquals("שלי", got.note)
        // A name that isn't one of the children doesn't pick anyone.
        assertEquals("", FamilyItems.fromSuggestion(base, JSONObject("""{"child":"אוריה"}"""), mapOf("uri" to "אורי")).child)
    }

    @Test fun categories() {
        val list = listOf(
            Item("1", Kind.RECEIPT, child = "uri", amount = 1150.0, date = "2026-10-01", category = "daycare"),
            Item("2", Kind.RECEIPT, child = "uri", amount = 300.0, date = "2026-09-01", category = "health"),
            Item("3", Kind.RECEIPT, child = "noam", amount = 2480.0, date = "2026-10-01", category = "daycare"),
            Item("4", Kind.RECEIPT, amount = 99.0, date = "2026-10-05"),
        )
        val all = FamilyItems.totalsByCategory(list, 2026)
        assertEquals(FamilyItems.Category.DAYCARE to 3630.0, all[0])
        assertEquals(null to 99.0, all.last())
        assertEquals(listOf(FamilyItems.Category.DAYCARE to 1150.0, FamilyItems.Category.HEALTH to 300.0), FamilyItems.totalsByCategory(list, 2026, "uri"))
        assertEquals("health", FamilyItems.fromSuggestion(Item("9", Kind.RECEIPT), JSONObject("""{"category":"health"}"""), emptyMap()).category)
        assertEquals("", FamilyItems.fromSuggestion(Item("9", Kind.RECEIPT), JSONObject("""{"category":"pets"}"""), emptyMap()).category)
        assertNull(FamilyItems.Category.of(""))
    }

    @Test fun months() {
        val list = listOf(
            Item("1", Kind.RECEIPT, amount = 100.0, date = "2026-09-03"),
            Item("2", Kind.RECEIPT, amount = 200.0, date = "2026-10-01"),
            Item("3", Kind.RECEIPT, amount = 300.0, date = "2026-10-20"),
            Item("4", Kind.RECEIPT, amount = 5.0),
        )
        val g = FamilyItems.byMonth(list)
        assertEquals(listOf("2026-10", "2026-09", ""), g.map { it.first })
        assertEquals(listOf("3", "2"), g[0].second.map { it.id })
        assertEquals("אוקטובר 2026", FamilyItems.monthLabel("2026-10"))
        assertEquals("בלי תאריך", FamilyItems.monthLabel(""))
        assertEquals(500.0, FamilyItems.monthTotal(list, "2026-10"), 0.001)
    }

    @Test fun jsonRoundTrip() {
        val i = Item("1", Kind.VOUCHER, child = "uri", title = "BuyMe", amount = null, expires = "2027-01-01", code = "X1", balance = 120.0, balanceAt = "2026-10-28", created = 5L, category = "gifts")
        assertEquals(listOf(i), FamilyItems.listFromJson(FamilyItems.listToJson(listOf(i))))
        assertEquals(emptyList<Item>(), FamilyItems.listFromJson("not json"))
    }

    @Test fun walletByChain() {
        val list = listOf(
            Item("1", Kind.VOUCHER, vendor = "שופרסל", amount = 100.0, expires = "2028-12-31"),
            Item("2", Kind.VOUCHER, vendor = "שופרסל", amount = 200.0, expires = "2027-01-31"),
            Item("3", Kind.VOUCHER, vendor = "מקדונלדס", amount = 50.0, expires = "2024-12-30"),
            Item("4", Kind.COUPON, title = "BuyMe · 500 ₪", amount = 500.0),
            Item("5", Kind.RECEIPT, vendor = "שופרסל", amount = 999.0),
        )
        val w = FamilyItems.wallet(list, today)
        assertEquals(listOf("BuyMe", "שופרסל"), w.map { it.name })
        assertEquals(300.0, w[1].total, 0.001)
        assertEquals("2027-01-31", w[1].nearest)
        assertEquals(listOf("2", "1"), w[1].items.map { it.id })
        assertEquals("•••• 9507", FamilyItems.masked("9376-7600-7182-9507"))
        assertEquals("DBE6039437", FamilyItems.masked("DBE6039437"))
        assertEquals("https://buyme.co.il/x1", FamilyItems.link("שובר\nhttps://buyme.co.il/x1\nCVV 848"))
        assertEquals("848", FamilyItems.cvv("שובר\nCVV 848"))
    }

    @Test fun warrantiesAndReminders() {
        assertEquals("2027-10-28", FamilyItems.plusMonths("2026-10-28", 12))
        assertEquals("2026-11-11", FamilyItems.plusDays("2026-10-28", 14))
        assertEquals(Kind.WARRANTY, Kind.of("warranty"))
        val w = Item("w", Kind.WARRANTY, title = "מקרר", expires = "2026-11-20")      // 23 days: the 30-day window
        val x = Item("x", Kind.EXCHANGE, title = "חולצה", expires = "2026-10-29")    // tomorrow
        val r = Item("r", Kind.RECEIPT, expires = "2026-10-29")                      // receipts aren't reminded
        val v = Item("v", Kind.VOUCHER, vendor = "אצה", amount = 100.0, expires = "2028-12-31") // far away
        val due = FamilyItems.due(listOf(w, x, r, v), today, emptySet())
        assertEquals(listOf("w" to 23L, "x" to 1L), due.map { it.item.id to it.days })
        // Tomorrow is within both the 3-day and 1-day windows: one reminder, marking both.
        assertEquals(listOf("x:2026-10-29:3", "x:2026-10-29:1"), due[1].keys)
        val sent = due.flatMap { it.keys }.toSet()
        assertEquals(emptyList<String>(), FamilyItems.due(listOf(w, x), today, sent).map { it.item.id })
        // A week later the warranty reaches its 7-day window: a new reminder.
        assertEquals(listOf("w"), FamilyItems.due(listOf(w), "2026-11-14", sent).map { it.item.id })
        assertEquals("האחריות נגמרת בעוד 23 ימים" to "מקרר", FamilyItems.reminderText(w, 23))
        assertEquals(true, FamilyItems.isWallet(Item("c", Kind.CREDIT)))
    }

    @Test fun search() {
        val kids = mapOf("uri" to "אורי")
        val gan = Item("1234567890", Kind.RECEIPT, child = "uri", title = "שכר לימוד אוקטובר", vendor = "גן השקד", amount = 1150.0, date = "2026-10-01", category = "daycare")
        val buy = Item("abcdefghij", Kind.VOUCHER, vendor = "Buyme", amount = 500.0, code = "9376-7600-7182-9507", note = "CVV 848")
        assertEquals(true, FamilyItems.matches(gan, "גן אורי", kids))
        assertEquals(true, FamilyItems.matches(gan, "1.10.2026", kids))
        assertEquals(true, FamilyItems.matches(gan, "1150", kids))
        assertEquals(true, FamilyItems.matches(gan, "מעון", kids))
        assertEquals(true, FamilyItems.matches(buy, "buyme 9507", kids))
        assertEquals(false, FamilyItems.matches(buy, "גן", kids))
        val index = FamilyItems.searchIndex(listOf(gan, buy), kids)
        assertEquals("12345678|receipt|שכר לימוד אוקטובר|גן השקד|daycare|אורי|1150.00|2026-10-01|", index.lines()[0])
        // Codes, card numbers and notes never go out.
        assertEquals(false, index.contains("9507") || index.contains("848"))
        assertEquals(listOf("abcdefghij"), FamilyItems.pick(listOf(gan, buy), listOf("abcdefgh", "zzz")).map { it.id })
    }

    @Test fun codesInGroups() {
        assertEquals("9376 7600 7182 9507", FamilyItems.grouped("9376-7600-7182-9507"))
        assertEquals("106720505 8999", FamilyItems.grouped("106720505-8999"))
        assertEquals("7777 7098 5155 5475", FamilyItems.grouped("7777709851555475"))
        assertEquals("nuf150EJTS00V3I4N9", FamilyItems.grouped("nuf150EJTS00V3I4N9"))
        assertEquals("123", FamilyItems.grouped("123"))
    }
}
