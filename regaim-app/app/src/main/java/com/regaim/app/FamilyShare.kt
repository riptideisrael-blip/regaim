package com.regaim.app

import org.json.JSONObject

/**
 * What to send to the other parent, and how their items become items on this phone. Only this phone's own items are
 * sent (not what came from the other parent); an item is sent again only when it changed, and an item removed here is
 * marked removed there. A child is matched by name (each phone has its own ids). No Android here (unit-tested).
 */
object FamilyShare {
    /** Items that came from the other parent carry this source (shown read-only, never sent back). */
    const val FROM_PARTNER = "partner"

    fun own(items: List<FamilyItems.Item>) = items.filter { it.source != FROM_PARTNER }

    /** A fingerprint of what the other parent sees (the details and which file). */
    fun stamp(i: FamilyItems.Item, childName: String): String =
        // (Without the CVV, as sent: an item sent before the CVV stayed home gets a new stamp, so it's sent again without it.)
        (FamilyItems.toJson(i.copy(created = 0, note = withoutCvv(i.note))).put("childName", childName).toString()).hashCode().toUInt().toString(16)

    data class Plan(val send: List<FamilyItems.Item>, val remove: List<String>)

    /** [sent]: item id -> the stamp last sent. */
    fun plan(items: List<FamilyItems.Item>, sent: Map<String, String>, childName: (String) -> String): Plan {
        val mine = own(items)
        val send = mine.filter { sent[it.id] != stamp(it, childName(it.child)) }
        val ids = mine.map { it.id }.toSet()
        return Plan(send, sent.keys.filter { it !in ids })
    }

    /** The row's details: the item without this phone's file name, with the child's name instead of its id. */
    fun data(i: FamilyItems.Item, childName: String): JSONObject =
        FamilyItems.toJson(i).put("file", "").put("child", "").put("childName", childName).put("note", withoutCvv(i.note))

    /** A card's security number stays on the phone that has it: the other parent sees "CVV •••". */
    fun withoutCvv(note: String): String = note.replace(Regex("""(?i)(CVV|CVC)\s*:?\s*\d{3,4}"""), "CVV •••")

    /** The other parent's row as an item here (its own id space, read-only, the child by name). */
    fun fromRow(itemId: String, data: JSONObject, childId: (String) -> String, localFile: String = ""): FamilyItems.Item =
        FamilyItems.fromJson(data).copy(id = localId(itemId), child = childId(data.optString("childName")), file = localFile, source = FROM_PARTNER)

    fun localId(itemId: String) = "p_$itemId"
}
