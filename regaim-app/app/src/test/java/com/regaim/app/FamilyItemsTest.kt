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

    @Test fun jsonRoundTrip() {
        val i = Item("1", Kind.VOUCHER, child = "uri", title = "BuyMe", amount = null, expires = "2027-01-01", code = "X1", balance = 120.0, balanceAt = "2026-10-28", created = 5L, category = "gifts")
        assertEquals(listOf(i), FamilyItems.listFromJson(FamilyItems.listToJson(listOf(i))))
        assertEquals(emptyList<Item>(), FamilyItems.listFromJson("not json"))
    }
}
