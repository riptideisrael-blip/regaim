package com.regaim.app

import android.content.Context

/**
 * The shared gallery of two parents: every moment one of them keeps (from the day it was turned on) is shared
 * automatically with the other parent only, not with the rest of the family circle. Short clips, a few at a time, in the
 * background. Both parents choose each other in the family circle screen (server/partner-gallery.sql).
 */
object PartnerShare {
    private fun p(ctx: Context) = Settings.prefs(ctx)

    /** Turned on (on this phone) since then; 0 = off. */
    fun since(ctx: Context): Long = p(ctx).getLong("partner_since", 0L)
    fun partner(ctx: Context): String = p(ctx).getString("partner_id", "").orEmpty()

    fun turnOn(ctx: Context, partnerId: String) {
        p(ctx).edit().putString("partner_id", partnerId).putLong("partner_since", if (since(ctx) > 0 && partner(ctx) == partnerId) since(ctx) else System.currentTimeMillis()).apply()
        PartnerNotify.schedule(ctx)
    }

    /** Off on this phone; what was marked as already sent is forgotten (turning it on again sends it again). */
    fun turnOff(ctx: Context) {
        val e = p(ctx).edit().remove("partner_id").remove("partner_since")
        p(ctx).all.keys.filter { it.startsWith("partner_shared_") || it.startsWith("partner_pitzki_") || it == "partner_family_sent" }.forEach { e.remove(it) }
        e.apply()
    }

    private fun doneKey(id: String) = "partner_shared_$id"

    /** Share also everything kept before it was turned on (moments and films), a few at a time in the background. */
    fun shareOld(ctx: Context) { p(ctx).edit().putLong("partner_since", 1L).apply() }
    fun sharingOld(ctx: Context) = since(ctx) == 1L

    // Taken in one step (two screens opening at once could otherwise both start a sharing and send a moment twice).
    private val running = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Background thread. Shares what the other parent doesn't have yet: moments kept since it was turned on (or all
     * of them, after [shareOld]) — films made in the app whole, other videos as short clips — and the children's
     * Pitzki films; and takes the Pitzki films the other parent shared. Never twice; a problem waits for next time.
     */
    /** What the last sync did (or why it didn't), for the parents to see in the shared gallery card. */
    fun lastResult(ctx: Context): String = p(ctx).getString("partner_last", "").orEmpty()
    private fun note(app: Context, s: String) {
        val t = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date())
        p(app).edit().putString("partner_last", "$t · $s").apply()
    }

    /** A sharing is running now (started when the app opened): a second one waits for it. */
    val busy: Boolean get() = running.get()

    /**
     * A moment goes to the other parent when it was taken, or added to the app, since sharing was turned on: photos a
     * parent adds from the gallery today go too, however old they are. Older ones only with "share the older ones".
     */
    fun isNew(m: Memory, from: Long): Boolean = maxOf(m.time, m.added) >= from

    fun sync(ctx: Context, max: Int = 30) {
        if (running.get()) return
        val app = ctx.applicationContext
        val partner = partner(app)
        val from = since(app)
        if (partner.isEmpty() || from == 0L) { note(app, L.t("לא פעיל בטלפון הזה: לפתוח את מסך המעגל המשפחתי", "Not on in this phone: open the family circle screen")); return }
        if (!Circle.joined(app) || !Account.signedIn(app)) { note(app, L.t("לא מחובר לחשבון או למעגל", "Not signed in or not in the circle")); return }
        // Any connection (mobile data too: most plans here have no limit).
        val cm = app.getSystemService(android.net.ConnectivityManager::class.java) ?: run { note(app, L.t("אין אינטרנט", "No internet")); return }
        if (cm.activeNetwork == null) { note(app, L.t("אין אינטרנט", "No internet")); return }
        if (!running.compareAndSet(false, true)) return
        try {
            // Only when both chose each other (the server checks it too).
            val status = Circle.partnerStatus(app) ?: run { note(app, L.t("השרת לא ענה", "The server didn't answer")); return }
            if (status.first != partner || !status.second) { note(app, L.t("מחכים שהשני יבחר בך", "Waiting for the other to choose you")); return }
            // The children first (the other phone may not have them yet: then Pitzki and the films have no one to go to).
            val got = try { receiveFamily(app, partner) } catch (e: Exception) { note(app, L.t("קבלת הילדים נכשלה: ", "Getting the children failed: ") + (e.message ?: e.javaClass.simpleName).take(80)); throw e }
            val sent = try { sendFamily(app, partner) } catch (e: Exception) { note(app, L.t("שליחת הילדים נכשלה: ", "Sending the children failed: ") + (e.message ?: e.javaClass.simpleName).take(80)); throw e }
            note(app, L.t("הילדים ✓", "Children ✓") + (if (sent) L.t(" · נשלחו", " · sent") else "") + (if (got > 0) L.t(" · נוספו $got", " · $got added") else "") +
                L.t(" · עכשיו הסרטים של פיצקי (זה יכול לקחת כמה דקות)…", " · now Pitzki's films (can take a few minutes)…"))
            receivePitzki(app, partner)
            sendPitzki(app, partner)
            // Said right away (the moments below can take a while).
            val kids = (if (sent) L.t(" · הילדים נשלחו", " · children sent") else "") + (if (got > 0) L.t(" · נוספו $got ילדים", " · $got children added") else "")
            note(app, L.t("הילדים והסרטים של פיצקי ✓", "Children and Pitzki films ✓") + kids + L.t(" · עכשיו הרגעים…", " · now the moments…"))
            var left = max
            while (left > 0) {
                val todo = MemoryStore.kept(app).filter { isNew(it, from) && !p(app).getBoolean(doneKey(it.id), false) }
                    .sortedByDescending { it.time }.take(minOf(5, left))
                if (todo.isEmpty()) break
                for (m in todo) {
                    Circle.shareMoment(app, m, Circle.Draft(Fmt.title(m), "", true, "plain"), onlyFor = partner, whole = m.origin == "film") { }
                    p(app).edit().putBoolean(doneKey(m.id), true).apply()
                    left--
                }
            }
            // Photos shared before covers were small: small covers now (a few each time), so the other grid is quick.
            try { Circle.fixPartnerCovers(app, partner) } catch (_: Exception) { }
            note(app, L.t("עבד ✓", "Worked ✓") + kids)
        } catch (e: Exception) { // no connection, or the server isn't ready: next time
            if (!lastResult(app).contains(L.t("נכשלה", "failed"))) note(app, L.t("נעצר: ", "Stopped: ") + (e.message ?: e.javaClass.simpleName).take(80))
        } finally { running.set(false) }
    }

    /**
     * The children in this phone's family profile (name, birthday, boy or girl, the profile picture and the approved
     * film character), sent to the other parent when they changed. Only the children; nothing else of the profile.
     */
    private fun sendFamily(app: Context, partner: String): Boolean {
        val kids = Profile.members(app).filter { it.isChild }
        if (kids.isEmpty()) return false
        val pics = kids.associateWith { k -> k.photo.takeIf { it.isNotBlank() }?.let { java.io.File(it) }?.takeIf { it.exists() && it.length() > 0 } }
        val heroes = kids.associateWith { k -> PitzkiStore.heroFile(app, k.id).takeIf { it.exists() && it.length() > 0 } }
        val stamp = kids.joinToString("|") { k -> "${k.name}/${k.birth}/${k.relation}/${pics[k]?.lastModified() ?: 0}/${heroes[k]?.lastModified() ?: 0}" }.hashCode().toLong()
        val key = "partner_family_sent"
        if (p(app).getLong(key, 0L) == stamp) return false
        val folder = "family/$stamp"
        val arr = org.json.JSONArray()
        for (k in kids) {
            val o = org.json.JSONObject().put("name", k.name).put("birth", k.birth).put("relation", k.relation)
            pics[k]?.let { o.put("photo", Circle.uploadForPartner(app, partner, "$folder/${k.id.filter { c -> c.isLetterOrDigit() }.take(24)}_photo.jpg", it, "image/jpeg")) }
            heroes[k]?.let { o.put("hero", Circle.uploadForPartner(app, partner, "$folder/${k.id.filter { c -> c.isLetterOrDigit() }.take(24)}_hero.jpg", it, "image/jpeg")) }
            arr.put(o)
        }
        Circle.postFamily(app, partner, org.json.JSONObject().put("kids", arr))
        p(app).edit().putLong(key, stamp).apply()
        return true
    }

    /**
     * The other parent's children, added to this phone's family profile when they aren't there yet (matched by name),
     * with their picture; and their film character, when this phone has none for that child. Nothing here is
     * removed or overwritten: what this phone already has stays.
     */
    private fun receiveFamily(app: Context, partner: String): Int {
        val (postId, manifest) = Circle.familyFrom(app, partner) ?: return 0
        val key = "partner_family_got"
        if (p(app).getString(key, "") == postId) return 0
        val arr = manifest.optJSONArray("kids") ?: return 0
        var added = 0
        var members = Profile.members(app)
        var changed = false
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("name").trim()
            if (name.isEmpty()) continue
            var m = members.firstOrNull { Profile.sameName(it.name, name) || Profile.sameName(PitzkiScript.firstName(it.name), PitzkiScript.firstName(name)) && it.isChild }
            if (m == null) {
                val id = java.util.UUID.randomUUID().toString()
                var photo = ""
                o.optString("photo").takeIf { it.isNotBlank() }?.let { path ->
                    val f = java.io.File(java.io.File(app.filesDir, "profile").apply { mkdirs() }, "kid_$id.jpg")
                    if (Circle.downloadShared(app, path, f)) photo = f.absolutePath
                }
                m = FamilyMember(id, name, o.optString("relation").ifBlank { "ילד" }, o.optString("birth"), photo)
                members = members + m
                changed = true
                added++
            }
            // The film character, when this phone has none for the child.
            o.optString("hero").takeIf { it.isNotBlank() }?.let { path ->
                val hero = PitzkiStore.heroFile(app, m.id)
                if (!hero.exists()) Circle.downloadShared(app, path, hero)
            }
        }
        if (changed) Profile.saveMembers(app, members)
        p(app).edit().putString(key, postId).apply()
        return added
    }

    /** Each child's Pitzki film, when it changed since it was last sent (all its drawings and animations). */
    private fun sendPitzki(app: Context, partner: String) {
        for (c in PitzkiStore.children(app)) {
            val files = PitzkiStore.filmFiles(app, c.memberId)
            if (files.none { it.name.startsWith("clip_") }) continue
            val stamp = files.maxOf { it.lastModified() }
            val key = "partner_pitzki_${c.memberId}"
            if (p(app).getLong(key, 0L) == stamp) continue
            val folder = "pitzki/${c.memberId.filter { it.isLetterOrDigit() }.take(24)}-$stamp"
            val paths = org.json.JSONObject()
            for (f in files) paths.put(f.name, Circle.uploadForPartner(app, partner, "$folder/${f.name}", f, if (f.name.endsWith(".mp4")) "video/mp4" else "image/jpeg"))
            val cover = paths.optString("scene_${TrainWorld.INTRO}.jpg").ifEmpty { paths.optString("scene_intro.jpg") }.ifEmpty { paths.optString(paths.keys().next()) }
            Circle.postPitzkiFilm(app, partner, c.kid.name, cover, org.json.JSONObject().put("child", c.kid.name).put("files", paths))
            p(app).edit().putLong(key, stamp).apply()
        }
    }

    /** The other parent's Pitzki films: the newest for each child (matched by the child's name), once. */
    private fun receivePitzki(app: Context, partner: String) {
        val kids = PitzkiStore.children(app)
        if (kids.isEmpty()) return
        val seen = HashSet<String>()
        for ((postId, manifest) in Circle.pitzkiFilmsFrom(app, partner)) {
            val name = manifest.optString("child").trim()
            if (!seen.add(name)) continue // only the newest one of each child
            val key = "partner_pitzki_got_$postId"
            if (p(app).getBoolean(key, false)) continue
            val c = kids.firstOrNull { it.kid.name.trim() == name } ?: continue
            val files = manifest.optJSONObject("files") ?: continue
            val names = files.keys().asSequence().toList()
            val got = PitzkiStore.adoptFilm(app, c, names) { n, to -> Circle.downloadShared(app, files.optString(n), to) }
            if (got == names.size) p(app).edit().putBoolean(key, true).apply()
        }
    }
}
