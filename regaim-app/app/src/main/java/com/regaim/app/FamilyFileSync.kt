package com.regaim.app

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * The family file shared between the two parents. Each parent turns it on in their own app (after reading what it
 * means); then this phone sends its own items (details and files) for the other parent only, and takes the other
 * parent's items, shown read-only. Items removed here are removed there. Turning it off deletes what this parent sent
 * from the server. Runs in the background, a minute apart at most; nothing happens unless both chose each other.
 */
object FamilyFileSync {
    private const val FUND = "fund_"
    private const val ON = "family_file_share_on"
    private const val SHARE_CONSENT = "family_file_share_consent_at"

    fun on(ctx: Context) = Settings.prefs(ctx).getBoolean(ON, false)

    fun shareConsentText(partner: String): String = L.t(
        "שיתוף תיק המשפחה עם $partner:\n\n" +
            "• הפריטים שלך (קבלות, מסמכים, אחריות ושוברים, כולל קודים, מספרי כרטיס ו-CVV) והקבצים שלהם נשלחים לשרת של המשפחה, ונשמרים שם.\n" +
            "• רק את/ה ו$partner יכולים לראות אותם. הגישה מוגנת בהרשאות החשבון. ההגנה היא בהרשאות ולא בהצפנה מקצה לקצה, כך שמי שמנהל את השרת יכול טכנית לגשת אליהם.\n" +
            "• הפריטים של $partner יופיעו אצלך רק אם גם $partner יאשר בטלפון שלו/ה.\n" +
            "• כיבוי השיתוף מוחק מהשרת את כל מה שנשלח מהטלפון הזה.",
        "Sharing the family file with $partner:\n\n" +
            "• Your items (receipts, documents, warranties and vouchers, including codes, card numbers and CVVs) and their files are sent to the family's server and kept there.\n" +
            "• Only you and $partner can see them. Access is protected by account permissions, not end-to-end encryption, so whoever runs the server could technically reach them.\n" +
            "• $partner's items appear here only if $partner agrees on their phone too.\n" +
            "• Turning sharing off deletes from the server everything this phone sent.",
    )

    fun turnOn(ctx: Context) {
        Settings.prefs(ctx).edit().putBoolean(ON, true).putLong(SHARE_CONSENT, System.currentTimeMillis()).apply()
    }

    /** Off: what this parent sent is deleted from the server; the other parent's items leave this phone. */
    fun turnOff(ctx: Context, done: () -> Unit) {
        val app = ctx.applicationContext
        Settings.prefs(app).edit().putBoolean(ON, false).apply()
        Thread {
            try { if (Account.signedIn(app) && Circle.joined(app)) Circle.fileItemsClear(app) } catch (_: Exception) { }
            FamilyFile.all(app).filter { it.source == FamilyShare.FROM_PARTNER }.forEach { FamilyFile.delete(app, it.id) }
            Settings.prefs(app).edit().remove(sentKey(app)).remove(gotKey(app)).apply()
            done()
        }.start()
    }

    private fun sentKey(ctx: Context) = "ff_sent_" + Circle.circleId(ctx)
    private fun gotKey(ctx: Context) = "ff_got_" + Circle.circleId(ctx)
    private fun map(ctx: Context, key: String): MutableMap<String, String> = try {
        val o = JSONObject(Settings.prefs(ctx).getString(key, "{}") ?: "{}")
        o.keys().asSequence().associateWith { o.optString(it) }.toMutableMap()
    } catch (_: Exception) { mutableMapOf() }
    private fun save(ctx: Context, key: String, m: Map<String, String>) =
        Settings.prefs(ctx).edit().putString(key, JSONObject(m as Map<*, *>).toString()).apply()

    @Volatile private var lastRun = 0L
    private val running = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Syncs in the background (a minute apart at most); [changed] runs (on that thread) when items arrived or left. */
    fun kick(ctx: Context, force: Boolean = false, changed: (() -> Unit)? = null) {
        val app = ctx.applicationContext
        if (!on(app) || !FamilyFile.consented(app) || !Account.signedIn(app) || !Circle.joined(app)) return
        if (!force && System.currentTimeMillis() - lastRun < 60_000) return
        if (!running.compareAndSet(false, true)) return
        lastRun = System.currentTimeMillis()
        Thread {
            try { if (sync(app)) changed?.invoke() } catch (_: Exception) { } // no connection, or the server isn't ready: next time
            finally { running.set(false) }
        }.start()
    }

    /** One round: send what changed here, take what changed there. True when this phone's list changed. */
    private fun sync(app: Context): Boolean {
        val partner = PartnerShare.partner(app)
        if (partner.isEmpty()) return false
        val status = Circle.partnerStatus(app) ?: return false
        if (status.first != partner || !status.second) return false
        val kids = Profile.members(app).filter { it.isChild }
        val nameOf = { id: String -> kids.firstOrNull { it.id == id }?.name.orEmpty() }
        val idOf = { name: String -> if (name.isBlank()) "" else kids.firstOrNull { Profile.sameName(it.name, name) }?.id.orEmpty() }

        // Send: this phone's own items that changed (not lines from a spreadsheet with nothing to redeem).
        val sent = map(app, sentKey(app))
        val items = FamilyFile.all(app).filterNot { CouponImport.noCode(it) }
        // (The children's savings travel in the same table as "fund_…" rows: handled below, not as items.)
        val plan = FamilyShare.plan(items, sent.filterKeys { !it.startsWith(FUND) }, nameOf)
        for (i in plan.send) {
            val stamp = FamilyShare.stamp(i, nameOf(i.child))
            var path: String? = null
            if (i.file.isNotEmpty()) FamilyFile.bytes(app, i)?.let { b ->
                val ext = if (i.mime == "application/pdf") "pdf" else "jpg"
                val tmp = File(app.cacheDir, "ff_send_${System.nanoTime()}.$ext")
                try {
                    tmp.writeBytes(b)
                    path = Circle.uploadForPartner(app, partner, "ff/${i.id.filter { it.isLetterOrDigit() || it == '-' }}_$stamp.$ext", tmp, i.mime.ifEmpty { "image/jpeg" })
                } finally { tmp.delete() }
            }
            Circle.fileItemPut(app, partner, i.id, FamilyShare.data(i, nameOf(i.child)), path)
            sent[i.id] = stamp
            save(app, sentKey(app), sent)
        }
        for (id in plan.remove) {
            Circle.fileItemRemove(app, id)
            sent.remove(id)
            save(app, sentKey(app), sent)
        }

        // The children's savings: every fund on this phone (either parent can add the next report), and removals.
        for (f in FamilyFile.savings(app)) {
            val key = FUND + f.id
            val data = Savings.shareData(f, nameOf(f.child))
            val stamp = data.toString().hashCode().toUInt().toString(16)
            if (sent[key] == stamp) continue
            Circle.fileItemPut(app, partner, key, data, null)
            sent[key] = stamp
            save(app, sentKey(app), sent)
        }
        val gone = FamilyFile.deletedFunds(app)
        for (id in gone) {
            if (sent.containsKey(FUND + id)) { Circle.fileItemRemove(app, FUND + id); sent.remove(FUND + id); save(app, sentKey(app), sent) }
        }

        // Take: the other parent's items (only there if they turned sharing on too).
        var changed = false
        val got = map(app, gotKey(app))
        val rows = Circle.fileItemsFrom(app, partner)
        val present = mutableSetOf<String>()
        for (r in rows) {
            val itemId = r.optString("item_id")
            if (itemId.startsWith(FUND)) {
                val id = itemId.removePrefix(FUND)
                val local = FamilyFile.savings(app).firstOrNull { it.id == id }
                if (!r.isNull("deleted_at")) {
                    // Removed on the other phone: removed here too (and this phone's copy on the server with it).
                    if (local != null) { FamilyFile.deleteFund(app, id); changed = true }
                    continue
                }
                if (id in gone) continue
                val remote = Savings.fromShare(id, r.optJSONObject("data") ?: continue, idOf)
                val merged = Savings.merge(local, remote)
                if (merged != local) { FamilyFile.saveFund(app, merged); changed = true }
                continue
            }
            val local = FamilyShare.localId(itemId)
            if (!r.isNull("deleted_at")) {
                if (FamilyFile.get(app, local) != null) { FamilyFile.delete(app, local); changed = true }
                got.remove(itemId); continue
            }
            present += itemId
            val path = r.optString("file_path").takeIf { !r.isNull("file_path") && it.isNotBlank() }.orEmpty()
            val version = r.optString("updated_at") + "|" + path
            val existing = FamilyFile.get(app, local)
            if (got[itemId] == version && existing != null) continue
            val data = r.optJSONObject("data") ?: continue
            // The file only when it's a new one (the details can change without it).
            var bytes: ByteArray? = null
            val samePath = got[itemId]?.substringAfter('|') == path && existing?.file?.isNotEmpty() == true
            if (path.isNotEmpty() && !samePath) {
                val tmp = File(app.cacheDir, "ff_got_${System.nanoTime()}")
                try { if (Circle.downloadShared(app, path, tmp)) bytes = tmp.readBytes() } finally { tmp.delete() }
            }
            val item = FamilyShare.fromRow(itemId, data, idOf, if (bytes == null) existing?.file.orEmpty() else existing?.file.orEmpty())
                .copy(created = existing?.created ?: 0L)
            val ext = if (item.mime == "application/pdf") "pdf" else "jpg"
            FamilyFile.save(app, item, bytes, ext, replace = bytes != null && existing?.file?.isNotEmpty() == true)
            got[itemId] = version
            changed = true
        }
        // The other parent stopped sharing (their rows are gone): their items leave this phone.
        FamilyFile.all(app).filter { it.source == FamilyShare.FROM_PARTNER && it.id.removePrefix("p_") !in present }.forEach {
            FamilyFile.delete(app, it.id); got.remove(it.id.removePrefix("p_")); changed = true
        }
        save(app, gotKey(app), got)
        return changed
    }
}
