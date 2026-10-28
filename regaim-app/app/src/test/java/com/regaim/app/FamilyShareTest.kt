package com.regaim.app

import com.regaim.app.FamilyItems.Item
import com.regaim.app.FamilyItems.Kind
import org.junit.Assert.assertEquals
import org.junit.Test

class FamilyShareTest {
    private val names = mapOf("uri" to "אורי נתן", "noam" to "נעם")
    private fun name(id: String) = names[id].orEmpty()

    @Test fun sendsOnlyOwnChangedItemsAndMarksRemoved() {
        val a = Item("a", Kind.RECEIPT, child = "uri", title = "גן", amount = 1150.0)
        val b = Item("b", Kind.VOUCHER, vendor = "אצה", code = "82718228-7653")
        val fromPartner = Item("p_x", Kind.WARRANTY, source = FamilyShare.FROM_PARTNER)
        val first = FamilyShare.plan(listOf(a, b, fromPartner), emptyMap(), ::name)
        assertEquals(listOf("a", "b"), first.send.map { it.id })
        val sent = first.send.associate { it.id to FamilyShare.stamp(it, name(it.child)) } + ("gone" to "123")
        val again = FamilyShare.plan(listOf(a.copy(amount = 1200.0), b, fromPartner), sent, ::name)
        assertEquals(listOf("a"), again.send.map { it.id })
        assertEquals(listOf("gone"), again.remove)
    }

    @Test fun theOtherPhoneMatchesTheChildByName() {
        val a = Item("a", Kind.RECEIPT, child = "uri", title = "גן", file = "a.jpg.enc", mime = "image/jpeg")
        val row = FamilyShare.data(a, name("uri"))
        assertEquals("", row.optString("file"))
        assertEquals("", row.optString("child"))
        val there = FamilyShare.fromRow("a", row, { n -> if (n == "אורי נתן") "kid7" else "" }, "p_a.jpg.enc")
        assertEquals("p_a", there.id)
        assertEquals("kid7", there.child)
        assertEquals(FamilyShare.FROM_PARTNER, there.source)
        assertEquals("גן", there.title)
        assertEquals("p_a.jpg.enc", there.file)
        // A received item is never sent back.
        assertEquals(emptyList<String>(), FamilyShare.plan(listOf(there), emptyMap(), ::name).send.map { it.id })
    }
}
