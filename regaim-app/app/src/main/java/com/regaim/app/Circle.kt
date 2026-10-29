package com.regaim.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Family sharing over authenticated membership RLS and private Storage.
 * Requires the explicit v3 server migration; never falls back to prototype access.
 */
object Circle {
    private const val BUCKET = "moments"
    // The family's web page (invites, grandparents' view, privacy, account deletion), on Netlify: the code's GitHub
    // repository can then be private. Links made with the old address are moved to the new one.
    const val DEFAULT_WEB = "https://moments-family-circle.netlify.app/"
    private const val OLD_WEB = "https://riptideisrael-blip.github.io/regaim/"
    /** The family's prototype server (public address and public key only; the secret key never goes in the app). */
    private const val DEFAULT_URL = "https://acqgmcmvxijyijhgivnt.supabase.co"
    private const val DEFAULT_KEY = "sb_publishable_MLvfYGP3BD_liOe_8g-TEg_MN3ZU1bm"
    val EMOJIS = listOf("❤️", "😂", "😍", "🥹")
    private const val SHARE_SECONDS = 10

    class Post(
        val id: String, val author: String, val title: String, val story: String, val people: String,
        val place: String, val kind: String, val takenAt: Long, val video: String, val cover: String, val createdAt: Long,
        val caption: String = "", val style: String = "plain", val memoryId: String = "",
        val authorId: String = "", val videoPath: String = "", val coverPath: String = "",
        /** Shared with one person only (the other parent): only the two of them see it. */
        val onlyFor: String = "",
        /** Pitzki's film with a child: its scenes are downloaded into Pitzki's screen, not played from here. */
        val pitzki: Boolean = false,
    )

    /** What you chose on the share screen. startMs/endMs < 0 = the default ~10 seconds around the highlight. */
    class Draft(
        val title: String, val caption: String, val withStory: Boolean, val style: String,
        val startMs: Long = -1, val endMs: Long = -1,
    )

    /** Styles for a shared moment: id to Hebrew name. The same ids are used by the web page. */
    val STYLES: List<Pair<String, String>> get() = listOf("plain" to L.t("רגיל", "Plain"), "warm" to L.t("חם", "Warm"), "bw" to L.t("שחור-לבן", "B&W"), "vintage" to L.t("נוסטלגי", "Vintage"), "party" to L.t("חגיגי", "Festive"))

    /** A color filter for the cover in this style (null = as is). */
    fun styleFilter(style: String): android.graphics.ColorMatrixColorFilter? {
        val cm = android.graphics.ColorMatrix()
        when (style) {
            "bw" -> cm.setSaturation(0f)
            "vintage" -> {
                cm.setSaturation(0.35f)
                cm.postConcat(android.graphics.ColorMatrix(floatArrayOf(
                    1.08f, 0f, 0f, 0f, 14f,
                    0f, 1.0f, 0f, 0f, 6f,
                    0f, 0f, 0.82f, 0f, -6f,
                    0f, 0f, 0f, 1f, 0f,
                )))
            }
            "warm" -> cm.set(floatArrayOf(
                1.08f, 0f, 0f, 0f, 10f,
                0f, 1.02f, 0f, 0f, 4f,
                0f, 0f, 0.92f, 0f, -4f,
                0f, 0f, 0f, 1f, 0f,
            ))
            else -> return null
        }
        return android.graphics.ColorMatrixColorFilter(cm)
    }
    class Reaction(val postId: String, val member: String, val emoji: String, val authorId: String = "")
    class Comment(val postId: String, val member: String, val body: String, val createdAt: Long, val authorId: String = "", val id: String = "")
    class Member(val userId: String, val name: String, val relationship: String, val avatar: String)
    class Message(val id: String, val authorId: String, val author: String, val body: String, val createdAt: Long)
    class Feed(val posts: List<Post>, val reactions: List<Reaction>, val comments: List<Comment>)

    // ---------- settings ----------

    private fun p(ctx: Context) = Settings.prefs(ctx)
    fun url(ctx: Context) = clean((p(ctx).getString("circle_url", "") ?: "").ifBlank { DEFAULT_URL })
    fun key(ctx: Context) = (p(ctx).getString("circle_key", "") ?: "").trim().ifEmpty { DEFAULT_KEY }

    /** Accepts the address however it was copied: "https://x.supabase.co/rest/v1/" → "https://x.supabase.co". */
    private fun clean(raw: String): String {
        var u = raw.trim()
        if (u.isNotEmpty() && !u.startsWith("http")) u = "https://$u"
        val i = u.indexOf(".supabase.co")
        if (i > 0) u = u.substring(0, i + ".supabase.co".length)
        return u.trimEnd('/')
    }
    fun web(ctx: Context) = (p(ctx).getString("circle_web", "") ?: "").trim().let { if (it.isEmpty() || it.trimEnd('/') == OLD_WEB.trimEnd('/')) DEFAULT_WEB else it }
    fun circleId(ctx: Context) = p(ctx).getString("circle_id", "") ?: ""

    // ---------- who's who: circle members ↔ the family profile on this phone ----------

    /**
     * The family-profile person ([FamilyMember.id]) this circle member is, as the parent said, or null. Kept on this
     * phone only and never guessed from a name: the account itself is the only automatic link ("self").
     */
    fun linkedMember(ctx: Context, userId: String): String? {
        if (userId.isNotEmpty() && userId == Account.id(ctx)) return "self"
        return p(ctx).getString("circle_link_${circleId(ctx)}_$userId", null)?.takeIf { it.isNotEmpty() }
    }

    fun link(ctx: Context, userId: String, memberId: String?) {
        val k = "circle_link_${circleId(ctx)}_$userId"
        p(ctx).edit().apply { if (memberId.isNullOrEmpty()) remove(k) else putString(k, memberId) }.apply()
    }
    fun code(ctx: Context) = p(ctx).getString("circle_code", "") ?: ""
    /** The circle's name, in the app's language ("The רביבו family" saved in English reads "משפחת רביבו" in Hebrew). */
    fun name(ctx: Context) = Wording.familyName(p(ctx).getString("circle_name", "") ?: "")
    fun me(ctx: Context) = (p(ctx).getString("circle_me", "") ?: "").ifEmpty {
        Profile.parentName(ctx).ifEmpty { Profile.relationLabel(Profile.role(ctx)) }.ifEmpty { L.t("אני", "Me") }
    }

    fun configured(ctx: Context): Boolean {
        val k = key(ctx)
        val isPublic = k.startsWith("sb_publishable_") || try {
            k.startsWith("eyJ") && JSONObject(String(Base64.decode(k.split('.')[1], Base64.URL_SAFE))).optString("role") == "anon"
        } catch (_: Exception) { false }
        return url(ctx).startsWith("https://") && isPublic
    }
    fun joined(ctx: Context) = configured(ctx) && circleId(ctx).isNotEmpty()

    fun setServer(ctx: Context, url: String, key: String, web: String) =
        p(ctx).edit().putString("circle_url", url.trim()).putString("circle_key", key.trim()).putString("circle_web", web.trim()).apply()

    /**
     * The family's server from an invite link's "s" (base64 of "url|key"), when this phone has none set yet.
     * Only an https address with a publishable key is accepted.
     */
    fun serverFromInvite(ctx: Context, s: String) {
        if (s.isBlank()) return
        if ((p(ctx).getString("circle_url", "") ?: "").isNotBlank()) return
        try {
            val raw = String(Base64.decode(s, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
            val url = raw.substringBefore("|").trim(); val key = raw.substringAfter("|", "").trim()
            // Only Moments' own server: a link that names another server could send the family's sign-in to it.
            if (key.isNotEmpty() && LinkSafety.sameServer(url, DEFAULT_URL)) setServer(ctx, DEFAULT_URL, key, web(ctx))
        } catch (_: Exception) { }
    }

    fun setMe(ctx: Context, name: String) = p(ctx).edit().putString("circle_me", name.trim()).apply()

    fun leave(ctx: Context) {
        p(ctx).edit().remove("circle_id").remove("circle_code").remove("circle_name").apply()
        clearMediaCache(ctx)
    }

    /**
     * Really leaves: the server removes your membership (not only this phone forgetting the circle).
     * Background thread only. Throws if the server didn't confirm; then this phone stays in the circle too, so it
     * never looks like you left while the server still lets you in.
     */
    fun leaveForGood(ctx: Context) {
        val id = circleId(ctx)
        if (id.isNotEmpty()) rpcRaw(ctx, "leave_family", JSONObject().put("p_circle_id", id))
        leave(ctx)
    }

    /** Is this account the family's owner (the one who can remove members)? Background thread only. */
    fun isOwner(ctx: Context): Boolean = try {
        circleId(ctx).isNotEmpty() && rpcRaw(ctx, "family_is_owner", JSONObject().put("p_circle_id", circleId(ctx))).trim() == "true"
    } catch (_: Exception) { false }

    /** The owner removes someone: they lose access at once, and the invite link changes. Background thread only. */
    fun removeMember(ctx: Context, userId: String) {
        rpcRaw(ctx, "remove_family_member", JSONObject().put("p_circle_id", circleId(ctx)).put("p_user_id", userId))
        try { renewInvite(ctx) } catch (_: Exception) { }
    }

    /**
     * The key the backup is encrypted with: 32 random bytes kept in this parent's account on the server (made the
     * first time, server/backup-keys.sql). The backup's files are in their Google Drive, so neither place alone can
     * read it. Background thread only.
     */
    fun backupKey(ctx: Context): ByteArray {
        val raw = rpcRaw(ctx, "backup_key", JSONObject()).trim().trim('"')
        val b = android.util.Base64.decode(raw, android.util.Base64.DEFAULT)
        if (b.size != 32) throw IllegalStateException(L.t("מפתח הגיבוי לא תקין בשרת.", "The backup key on the server isn't valid."))
        return b
    }

    /** An RPC whose answer may be a plain value (true/false) or nothing. */
    private fun rpcRaw(ctx: Context, name: String, body: JSONObject): String {
        val conn = open(ctx, "POST", "/rest/v1/rpc/$name")
        try {
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            return read(conn)
        } finally { conn.disconnect() }
    }

    fun clearSession(ctx: Context) {
        leave(ctx)
        val editor = p(ctx).edit().remove("circle_me")
        p(ctx).all.keys.filter { it.startsWith("circle_shared_") }.forEach { editor.remove(it) }
        editor.apply()
    }

    private fun clearMediaCache(ctx: Context) {
        readyKey = ""; readyAt = 0L
        try { ctx.filesDir.listFiles()?.filter { it.name.startsWith("circle_feed_") }?.forEach { it.delete() } } catch (_: Exception) { }
        imgCache.evictAll()
        File(ctx.cacheDir, "circle_img").deleteRecursively()
    }

    /** The link to send on WhatsApp: opens the family feed in any browser. */
    fun inviteLink(ctx: Context): String {
        val server = Base64.encodeToString("${url(ctx)}|${key(ctx)}".toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val base = web(ctx).let { if (it.endsWith("/") || it.endsWith(".html")) it else "$it/" }
        check(code(ctx).matches(Regex("[A-Fa-f0-9]{32}"))) { L.t("צריך לחדש את קישור ההזמנה באמצעות מנהל המשפחה", "The invite link needs to be renewed by the family admin") }
        return "$base?c=${code(ctx)}&s=$server"
    }

    // ---------- http ----------

    private fun open(ctx: Context, method: String, path: String): HttpURLConnection {
        val conn = URL(url(ctx) + path).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 20_000
        conn.readTimeout = 120_000
        val k = key(ctx)
        conn.setRequestProperty("apikey", k)
        val token = Account.token(ctx) ?: throw IllegalStateException(L.t("כדי לשתף עם המשפחה צריך להתחבר לחשבון. הזיכרונות בטלפון זמינים גם בלי התחברות.", "Sign in to share with your family. Memories on your phone are available without an account."))
        conn.setRequestProperty("Authorization", "Bearer $token")
        return conn
    }

    private fun read(conn: HttpURLConnection): String {
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        if (code !in 200..299) {
            throw IllegalStateException(
                when (code) {
                    401, 403 -> L.t("אין הרשאה למעגל הזה. התחברו לחשבון ובקשו הזמנה חדשה.", "You don't have access to this circle. Sign in and ask for a new invite.")
                    404 -> L.t("שיתוף משפחתי מאובטח עדיין לא הופעל בשרת. נדרש עדכון השרת לגרסה 3.", "Secure family sharing isn't enabled on the server yet. A server update to version 3 is needed.")
                    else -> L.t("שגיאת שרת ($code): ${text.take(140)}", "Server error ($code): ${text.take(140)}")
                }
            )
        }
        return text
    }

    private fun rest(ctx: Context, method: String, path: String, body: String? = null, prefer: String = "return=representation"): JSONArray {
        val conn = open(ctx, method, "/rest/v1/$path")
        try {
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Prefer", prefer)
            if (body != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val text = read(conn).trim()
            if (text.isEmpty()) return JSONArray()
            return if (text.startsWith("[")) JSONArray(text) else JSONArray().put(JSONObject(text))
        } finally {
            conn.disconnect()
        }
    }

    private fun upload(ctx: Context, path: String, file: File, type: String): String {
        val conn = open(ctx, "POST", "/storage/v1/object/$BUCKET/$path")
        try {
            conn.setRequestProperty("Content-Type", type)
            conn.setRequestProperty("x-upsert", "false")
            conn.doOutput = true
            conn.setFixedLengthStreamingMode(file.length())
            conn.outputStream.use { o -> file.inputStream().use { it.copyTo(o) } }
            read(conn)
            return path
        } finally {
            conn.disconnect()
        }
    }

    private fun enc(v: String) = URLEncoder.encode(v, "UTF-8")

    private val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    private fun isoOf(t: Long) = synchronized(iso) { iso.format(Date(t)) }
    private fun timeOf(s: String): Long {
        if (s.isEmpty()) return 0L
        // Postgres sends e.g. 2026-10-06T05:57:12.345678+00:00
        val core = s.replace(Regex("(\\.\\d{3})\\d*"), "$1").replace("+00:00", "Z")
        val fmts = listOf("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "yyyy-MM-dd'T'HH:mm:ssXXX")
        for (f in fmts) {
            try {
                return SimpleDateFormat(f, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(core)?.time ?: continue
            } catch (_: Exception) {
            }
        }
        return 0L
    }

    // ---------- circles ----------

    private fun rpc(ctx: Context, name: String, body: JSONObject = JSONObject()) =
        rest(ctx, "POST", "rpc/$name", body.toString())

    /** Numbers for the owner's admin screen (the server allows only the admin account). Background thread only. */
    fun adminStats(ctx: Context): JSONObject = rpc(ctx, "admin_stats").getJSONObject(0)
    /** Admin: the server's files by folder (server/voice-and-storage.sql). */
    fun adminStorage(ctx: Context): JSONObject = rpc(ctx, "admin_storage").getJSONObject(0)

    /** Does not trust local configuration: a real authenticated server check is required. */
    // Checked once in a while, not before every request (each check was two calls to the server before anything showed).
    // The server's rules decide what each account may see either way; this only gives a clear message.
    @Volatile private var readyKey = ""
    @Volatile private var readyAt = 0L

    fun secureReady(ctx: Context): Boolean {
        val key = Account.id(ctx) + "|" + circleId(ctx)
        if (key == readyKey && System.currentTimeMillis() - readyAt < 20 * 60_000L) return true
        val version = rpc(ctx, "family_circle_version").optJSONObject(0)?.optInt("version", 0)
        if (version != 3) throw IllegalStateException(L.t("שיתוף משפחתי מאובטח עדיין לא הופעל בשרת. נדרש עדכון השרת לגרסה 3.", "Secure family sharing isn't enabled on the server yet. A server update to version 3 is needed."))
        if (circleId(ctx).isNotEmpty()) {
            val rows = rpc(ctx, "family_circle_status", JSONObject().put("p_circle_id", circleId(ctx)))
            if (rows.length() == 0) throw IllegalStateException(L.t("אין חברות מאושרת במעגל הזה. בקשו הזמנה חדשה ממנהל המשפחה.", "You're not an approved member of this circle. Ask the family admin for a new invite."))
            save(ctx, rows.getJSONObject(0))
        }
        readyKey = Account.id(ctx) + "|" + circleId(ctx); readyAt = System.currentTimeMillis()
        return true
    }

    fun create(ctx: Context, circleName: String): String {
        secureReady(ctx)
        val row = rpc(ctx, "create_family_circle", JSONObject().put("p_name", circleName.trim()).put("p_display_name", me(ctx))).getJSONObject(0)
        save(ctx, row)
        return row.getString("code")
    }

    fun join(ctx: Context, code: String) {
        // Old six-character codes are deliberately not treated as access credentials.
        if (!code.trim().matches(Regex("[A-Fa-f0-9]{32}"))) throw IllegalStateException(L.t("צריך קישור הזמנה חדש ומאובטח מהמשפחה. קודים ישנים אינם מעניקים גישה.", "You need a new secure invite link from your family. Old codes no longer give access."))
        val row = rpc(ctx, "redeem_family_invite", JSONObject().put("p_token", code.trim()).put("p_display_name", me(ctx))).getJSONObject(0)
        save(ctx, row)
    }

    fun renewInvite(ctx: Context) {
        val row = rpc(ctx, "create_family_invite", JSONObject().put("p_circle_id", circleId(ctx))).getJSONObject(0)
        save(ctx, row)
    }

    /**
     * The other parent for the shared gallery: who I chose, and whether they chose me too (only then it works).
     * Null when the server doesn't have it yet (partner-gallery.sql not run). Background thread only.
     */
    fun partnerStatus(ctx: Context): Pair<String?, Boolean>? = try {
        secureReady(ctx)
        val rows = rpc(ctx, "family_partner_status", JSONObject().put("p_circle", circleId(ctx)))
        if (rows.length() == 0) null to false
        else rows.getJSONObject(0).let { it.optString("partner").nullless().ifEmpty { null } to it.optBoolean("mutual") }
    } catch (_: Exception) { null }

    /** Chooses (or clears, with null) the other parent. Returns whether they chose me too. Background thread only. */
    fun setPartner(ctx: Context, userId: String?): Boolean {
        secureReady(ctx)
        val rows = rpc(ctx, "set_family_partner", JSONObject().put("p_circle", circleId(ctx)).put("p_partner", userId ?: JSONObject.NULL))
        return rows.length() > 0 && rows.getJSONObject(0).optBoolean("mutual")
    }

    fun members(ctx: Context): List<Member> {
        secureReady(ctx)
        val rows = rpc(ctx, "get_family_members", JSONObject().put("p_circle_id", circleId(ctx)))
        return (0 until rows.length()).map { i -> rows.getJSONObject(i).let {
            Member(it.optString("user_id"), it.optString("display_name"), it.optString("relationship"), it.optString("avatar_url"))
        } }
    }

    // ---------- shared children (stage 1 of the family file) ----------

    /**
     * The family's children on the server, or null when the server doesn't have them yet (shared-children.sql not run):
     * then nothing is shared and the phone keeps its own list, as before. Background thread only.
     */
    fun children(ctx: Context): List<ChildSyncPlan.Server>? {
        val id = circleId(ctx).ifEmpty { return emptyList() }
        val conn = open(ctx, "GET", "/rest/v1/family_children?circle_id=eq.${enc(id)}&select=id,name,birth,deleted_at&order=created_at")
        try {
            if (conn.responseCode == 404) return null
            val rows = JSONArray(read(conn).ifBlank { "[]" })
            return (0 until rows.length()).map { i -> rows.getJSONObject(i).let {
                ChildSyncPlan.Server(it.optString("id"), it.optString("name"), it.optString("birth").nullless(), !it.isNull("deleted_at"))
            } }
        } finally { conn.disconnect() }
    }

    /** Whether I'm a parent in this family (its owner, or one of the two partners): only parents change the children. */
    fun isParent(ctx: Context): Boolean {
        val conn = open(ctx, "POST", "/rest/v1/rpc/family_is_parent")
        try {
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.outputStream.use { it.write(JSONObject().put("p_circle", circleId(ctx)).toString().toByteArray(Charsets.UTF_8)) }
            return read(conn).trim() == "true"
        } finally { conn.disconnect() }
    }

    private fun childBody(name: String, birth: String) = JSONObject().put("name", name.trim().take(60))
        .put("birth", ChildSyncPlan.cleanBirth(birth).ifEmpty { null } ?: JSONObject.NULL)

    /** Adds a child to the family; returns its server id. Background thread only. */
    fun childAdd(ctx: Context, name: String, birth: String): String =
        rest(ctx, "POST", "family_children", childBody(name, birth).put("circle_id", circleId(ctx)).toString()).getJSONObject(0).getString("id")

    fun childUpdate(ctx: Context, id: String, name: String, birth: String) {
        rest(ctx, "PATCH", "family_children?id=eq.${enc(id)}", childBody(name, birth).put("updated_at", isoOf(System.currentTimeMillis())).toString())
    }

    /** Removes a child for the whole family (a mark, so the other parent's phone removes them too). */
    fun childDelete(ctx: Context, id: String) {
        rest(ctx, "PATCH", "family_children?id=eq.${enc(id)}", JSONObject().put("deleted_at", isoOf(System.currentTimeMillis())).toString())
    }

    private fun save(ctx: Context, o: JSONObject) {
        p(ctx).edit().putString("circle_id", o.optString("id")).putString("circle_code", o.optString("code"))
            .putString("circle_name", o.optString("name")).apply()
    }

    // ---------- feed ----------

    private fun post(ctx: Context, o: JSONObject, links: Map<String, String>? = null): Post {
        val videoPath = mediaPath(ctx, o.optString("video").nullless())
        val coverPath = mediaPath(ctx, o.optString("cover").nullless())
        fun link(path: String) = if (links != null) links[path].orEmpty() else signedUrl(ctx, path)
        return Post(o.optString("id"), o.optString("author"), o.optString("title").nullless(), o.optString("story").nullless(),
            o.optString("people").nullless(), o.optString("place").nullless(), o.optString("kind", "video").let { if (it == "pitzki") "photo" else it },
            timeOf(o.optString("taken_at").nullless()), link(videoPath), link(coverPath), timeOf(o.optString("created_at").nullless()),
            captionOf(o), o.optString("style").nullless().ifEmpty { "plain" }, o.optString("memory_id").nullless(),
            o.optString("author_id").nullless(), videoPath, coverPath, o.optString("only_for").nullless(), o.optString("kind") == "pitzki")
    }

    /**
     * What the post says under its picture. A post of Pitzki's film keeps the list of its files there (for the other
     * parent's app to download): that's not for people to read, so they see a line about the film instead.
     */
    private fun captionOf(o: JSONObject): String {
        val raw = o.optString("caption").nullless()
        if (o.optString("kind") != "pitzki") return raw
        val child = try { JSONObject(raw).optString("child") } catch (_: Exception) { "" }
        return if (child.isBlank()) L.t("🧝 הסרט נמצא במסך של פיצקי", "🧝 The film is in Pitzki's screen")
        else L.t("🧝 כדי לצפות בסרט: נכנסים לפיצקי ובוחרים את $child", "🧝 To watch the film: open Pitzki and choose $child")
    }

    /** A family post that arrived since the last look (for the "new moment from the family" notification). */
    class News(val id: String, val author: String, val authorId: String, val title: String, val createdIso: String, val kind: String)

    /**
     * Posts shared in the circle after [sinceIso] (newest first, a few at most), not counting the family's own
     * profile sync. Small: only the words, no pictures. Background thread only.
     */
    fun newsSince(ctx: Context, sinceIso: String): List<News> {
        secureReady(ctx)
        val since = java.net.URLEncoder.encode(sinceIso, "UTF-8")
        val rows = rest(ctx, "GET", "posts?circle_id=eq.${circleId(ctx)}&created_at=gt.$since&or=(kind.is.null,kind.neq.family)&order=created_at.desc&limit=10&select=id,author,author_id,title,kind,created_at,only_for")
        val me = Account.id(ctx)
        return (0 until rows.length()).map { rows.getJSONObject(it) }
            .filter { o -> o.optString("only_for").nullless().let { it.isEmpty() || it == me } }
            .map { o -> News(o.optString("id"), o.optString("author"), o.optString("author_id").nullless(), o.optString("title").nullless(), o.optString("created_at"), o.optString("kind")) }
    }

    fun feed(ctx: Context): Feed {
        secureReady(ctx)
        // (The children's details sent to the other parent aren't something to show in the gallery.)
        val rows = rest(ctx, "GET", "posts?circle_id=eq.${circleId(ctx)}&or=(kind.is.null,kind.neq.family)&order=created_at.desc&limit=60&select=*")
        // Faster, like a social feed: every picture's and video's link in ONE request (not one each), and the
        // reactions and comments asked for at the same time. The result is kept to show at once next time.
        val list = (0 until rows.length()).map { rows.getJSONObject(it) }
        val paths = list.flatMap { o -> listOf(mediaPath(ctx, o.optString("video").nullless()), mediaPath(ctx, o.optString("cover").nullless())) }
        val links = signMany(ctx, paths)
        val ids = list.joinToString(",") { it.optString("id") }
        var reactRows = JSONArray(); var commentRows = JSONArray()
        if (list.isNotEmpty()) {
            var failure: Exception? = null
            val t = Thread { try { reactRows = rest(ctx, "GET", "reactions?post_id=in.($ids)&select=*") } catch (e: Exception) { failure = e } }
            t.start()
            commentRows = rest(ctx, "GET", "comments?post_id=in.($ids)&order=created_at.asc&select=*")
            t.join(60_000)
            failure?.let { throw it }
        }
        saveFeed(ctx, rows, reactRows, commentRows, links)
        return feedFrom(ctx, rows, reactRows, commentRows, links)
    }

    private fun feedFrom(ctx: Context, rows: JSONArray, reactRows: JSONArray, commentRows: JSONArray, links: Map<String, String>): Feed =
        Feed((0 until rows.length()).map { post(ctx, rows.getJSONObject(it), links) }, reactionsOf(reactRows), commentsOf(commentRows))

    private fun feedFile(ctx: Context) = File(ctx.filesDir, "circle_feed_" + (Account.id(ctx) + "_" + circleId(ctx)).filter { it.isLetterOrDigit() || it == '_' || it == '-' } + ".json")

    private fun saveFeed(ctx: Context, rows: JSONArray, reactRows: JSONArray, commentRows: JSONArray, links: Map<String, String>) {
        try {
            val o = JSONObject().put("at", System.currentTimeMillis()).put("posts", rows).put("reactions", reactRows).put("comments", commentRows)
                .put("links", JSONObject(links as Map<*, *>))
            SafeFiles.write(feedFile(ctx), o.toString())
        } catch (_: Exception) { }
    }

    /**
     * The family feed as it was last time (for this account and circle), to show the moment the screen opens while
     * the new one loads; null when there's none. Its pictures come from the phone's picture cache.
     */
    fun cachedFeed(ctx: Context): Feed? = try {
        val f = feedFile(ctx)
        if (!f.exists() || circleId(ctx).isEmpty()) null else {
            val o = JSONObject(f.readText())
            val l = o.optJSONObject("links") ?: JSONObject()
            val links = l.keys().asSequence().associateWith { l.optString(it) }
            feedFrom(ctx, o.optJSONArray("posts") ?: JSONArray(), o.optJSONArray("reactions") ?: JSONArray(), o.optJSONArray("comments") ?: JSONArray(), links)
        }
    } catch (_: Exception) { null }

    // ---------- the other parent's moments, for the shared gallery in Memories ----------

    private fun partnerFile(ctx: Context) = File(ctx.filesDir, "partner_moments_" + (Account.id(ctx) + "_" + circleId(ctx)).filter { it.isLetterOrDigit() || it == '_' || it == '-' } + ".json")

    /** The other parent's moments kept from last time (shown at once; refreshed in the background). */
    fun cachedPartnerMoments(ctx: Context): List<Post> = try {
        val f = partnerFile(ctx)
        if (!f.exists() || circleId(ctx).isEmpty()) emptyList() else {
            val o = JSONObject(f.readText())
            val l = o.optJSONObject("links") ?: JSONObject()
            val links = l.keys().asSequence().associateWith { l.optString(it) }
            val rows = o.optJSONArray("posts") ?: JSONArray()
            (0 until rows.length()).map { post(ctx, rows.getJSONObject(it), links) }
        }
    } catch (_: Exception) { emptyList() }

    /** When the kept list was fetched (0 = never). */
    fun partnerMomentsAt(ctx: Context): Long = try { JSONObject(partnerFile(ctx).readText()).optLong("at") } catch (_: Exception) { 0L }

    /**
     * The moments the other parent shared with this parent only (the shared gallery), newest first, with their
     * pictures' links (covers only: a moment is played from its post). Background thread only.
     */
    fun partnerMoments(ctx: Context): List<Post> {
        secureReady(ctx)
        val me = Account.id(ctx)
        val partner = PartnerShare.partner(ctx)
        if (me.isEmpty() || partner.isEmpty() || circleId(ctx).isEmpty()) return emptyList()
        val rows = rest(ctx, "GET", "posts?circle_id=eq.${circleId(ctx)}&author_id=eq.${enc(partner)}&only_for=eq.${enc(me)}" +
            "&or=(kind.is.null,kind.in.(photo,video))&order=created_at.desc&limit=1000" +
            "&select=id,author,author_id,title,story,people,place,kind,taken_at,video,cover,created_at,style,memory_id,only_for")
        val list = (0 until rows.length()).map { rows.getJSONObject(it) }
        val covers = list.map { o -> mediaPath(ctx, o.optString("cover").nullless().ifEmpty { o.optString("video").nullless() }) }
        val links = HashMap<String, String>()
        covers.filter { it.isNotEmpty() }.distinct().chunked(100).forEach { links.putAll(signMany(ctx, it)) }
        try {
            SafeFiles.write(partnerFile(ctx), JSONObject().put("at", System.currentTimeMillis()).put("posts", rows).put("links", JSONObject(links as Map<*, *>)).toString())
        } catch (_: Exception) { }
        return list.map { post(ctx, it, links) }
    }

    /** Signed links for many files in one request (falls back to one by one if the server won't). */
    private fun signMany(ctx: Context, paths: List<String>): Map<String, String> {
        val wanted = paths.filter { it.isNotEmpty() }.distinct()
        if (wanted.isEmpty()) return emptyMap()
        try {
            val conn = open(ctx, "POST", "/storage/v1/object/sign/$BUCKET")
            val text = try {
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.outputStream.use { it.write(JSONObject().put("expiresIn", 3600).put("paths", JSONArray(wanted)).toString().toByteArray(Charsets.UTF_8)) }
                read(conn)
            } finally { conn.disconnect() }
            val arr = JSONArray(text)
            val out = HashMap<String, String>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val signed = o.optString("signedURL").takeIf { it.isNotBlank() && it != "null" } ?: continue
                out[o.optString("path")] = if (signed.startsWith("https://")) signed else "${url(ctx)}/storage/v1$signed"
            }
            if (out.isNotEmpty()) return out
        } catch (_: Exception) { }
        return wanted.associateWith { try { signedUrl(ctx, it) } catch (_: Exception) { "" } }
    }

    fun postById(ctx: Context, postId: String): Post? {
        secureReady(ctx)
        val rows = rest(ctx, "GET", "posts?circle_id=eq.${circleId(ctx)}&id=eq.${enc(postId)}&limit=1&select=*")
        return if (rows.length() == 0) null else post(ctx, rows.getJSONObject(0))
    }

    fun postForMemory(ctx: Context, memoryId: String): Post? {
        secureReady(ctx)
        val rows = rest(ctx, "GET", "posts?circle_id=eq.${circleId(ctx)}&memory_id=eq.${enc(memoryId)}&order=created_at.desc&limit=1&select=*")
        return if (rows.length() == 0) null else post(ctx, rows.getJSONObject(0))
    }

    private fun reactions(ctx: Context, filter: String): List<Reaction> = reactionsOf(rest(ctx, "GET", "reactions?$filter&select=*"))
    private fun reactionsOf(rows: JSONArray): List<Reaction> = (0 until rows.length()).map { i -> rows.getJSONObject(i).let {
        Reaction(it.optString("post_id"), it.optString("member"), it.optString("emoji"), it.optString("author_id").nullless())
    } }
    private fun comments(ctx: Context, filter: String): List<Comment> = commentsOf(rest(ctx, "GET", "comments?$filter&order=created_at.asc&select=*"))
    private fun commentsOf(rows: JSONArray): List<Comment> = (0 until rows.length()).map { i -> rows.getJSONObject(i).let {
        Comment(it.optString("post_id"), it.optString("member"), it.optString("body"), timeOf(it.optString("created_at").nullless()), it.optString("author_id").nullless(), it.optString("id"))
    } }
    fun reactionsForPost(ctx: Context, postId: String) = reactions(ctx, "post_id=eq.${enc(postId)}")
    fun commentsForPost(ctx: Context, postId: String) = comments(ctx, "post_id=eq.${enc(postId)}")

    fun messages(ctx: Context, postId: String? = null): List<Message> {
        secureReady(ctx)
        val filter = if (postId == null) "post_id=is.null" else "post_id=eq.${enc(postId)}"
        val rows = rest(ctx, "GET", "family_messages?circle_id=eq.${circleId(ctx)}&$filter&order=created_at.desc&limit=100&select=*")
        return (0 until rows.length()).map { i -> rows.getJSONObject(i).let {
            Message(it.optString("id"), it.optString("author_id"), it.optString("author"), it.optString("body"), timeOf(it.optString("created_at")))
        } }.reversed()
    }
    fun sendMessage(ctx: Context, body: String, postId: String? = null) {
        secureReady(ctx)
        val text = body.trim()
        require(text.length in 1..4000) { L.t("הודעה צריכה לכלול בין 1 ל־4,000 תווים", "Messages must be 1 to 4,000 characters") }
        val row = JSONObject().put("circle_id", circleId(ctx)).put("author_id", Account.id(ctx)).put("author", me(ctx)).put("body", text)
        if (postId != null) row.put("post_id", postId)
        rest(ctx, "POST", "family_messages", row.toString())
    }

    /**
     * Sends one message from [FamilyOutbox], into the circle it was written in, with its phone-made id. If the server
     * already has that id (an earlier try got through but its answer was lost) it counts as sent. A server without
     * the id column (family-chat-outbox.sql not run yet) gets the message as before, without the guard.
     * Throws IOException when there's no connection, IllegalStateException when the server refused it.
     */
    fun sendQueued(ctx: Context, item: FamilyOutbox.Item) {
        if (item.circleId.isEmpty()) throw IllegalStateException(L.t("אין מעגל משפחה", "No family circle"))
        secureReady(ctx)
        val text = item.body.trim()
        require(text.length in 1..4000) { L.t("הודעה צריכה לכלול בין 1 ל־4,000 תווים", "Messages must be 1 to 4,000 characters") }
        val row = JSONObject().put("circle_id", item.circleId).put("author_id", Account.id(ctx)).put("author", item.author.ifBlank { me(ctx) }).put("body", text)
        if (item.postId != null) row.put("post_id", item.postId)
        // Checked again once a day, so running the SQL later turns the guard on by itself.
        val guarded = System.currentTimeMillis() > p(ctx).getLong("outbox_ids_off_until", 0L)
        if (guarded) row.put("client_id", item.clientId)
        try {
            rest(ctx, "POST", "family_messages", row.toString())
        } catch (e: IllegalStateException) {
            val m = e.message.orEmpty()
            when {
                // Already stored by an earlier try.
                m.contains("23505") || m.contains("duplicate key", true) -> return
                // The server doesn't know the id column yet: send as before and stop adding it.
                guarded && m.contains("client_id") -> {
                    p(ctx).edit().putLong("outbox_ids_off_until", System.currentTimeMillis() + 24 * 3600_000L).apply()
                    row.remove("client_id")
                    rest(ctx, "POST", "family_messages", row.toString())
                }
                else -> throw e
            }
        }
    }

    private fun mediaPath(ctx: Context, value: String): String {
        if (value.isEmpty()) return ""
        if (!value.contains("://")) return value
        if (!value.startsWith(url(ctx) + "/storage/v1/object/")) throw IllegalStateException(L.t("קישור המדיה אינו שייך לשרת המשפחה", "This media link doesn't belong to the family server"))
        val marker = "/$BUCKET/"
        val index = value.indexOf(marker, value.indexOf("/storage/v1/object/"))
        return if (index >= 0) value.substring(index + marker.length).substringBefore('?') else ""
    }

    /** A link to play a post's file (its video, or its picture), good for 15 minutes. Background thread only. */
    fun mediaLink(ctx: Context, path: String): String = signedUrl(ctx, path)

    private fun signedUrl(ctx: Context, path: String): String {
        if (path.isEmpty()) return ""
        val conn = open(ctx, "POST", "/storage/v1/object/sign/$BUCKET/$path")
        try {
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.outputStream.use { it.write(JSONObject().put("expiresIn", 900).toString().toByteArray(Charsets.UTF_8)) }
            val signed = JSONObject(read(conn)).optString("signedURL")
            if (signed.isBlank()) throw IllegalStateException(L.t("לא הצלחנו לפתוח את המדיה המשפחתית", "Couldn't open the family media"))
            return if (signed.startsWith("https://")) signed else "${url(ctx)}/storage/v1$signed"
        } finally { conn.disconnect() }
    }

    private fun String.nullless() = if (this == "null") "" else this

    fun react(ctx: Context, postId: String, emoji: String, remove: Boolean) {
        val me = me(ctx)
        if (remove) rest(ctx, "DELETE", "reactions?post_id=eq.$postId&author_id=eq.${Account.id(ctx)}&emoji=eq.${enc(emoji)}")
        else rest(ctx, "POST", "reactions", JSONObject().put("post_id", postId).put("author_id", Account.id(ctx)).put("member", me).put("emoji", emoji).toString())
    }

    fun comment(ctx: Context, postId: String, body: String) {
        rest(ctx, "POST", "comments", JSONObject().put("post_id", postId).put("author_id", Account.id(ctx)).put("member", me(ctx)).put("body", body.trim()).toString())
    }

    /** Deletes the post and its files. Background thread only. */
    fun deletePost(ctx: Context, post: Post) {
        rest(ctx, "DELETE", "posts?id=eq.${post.id}")
        removeFile(ctx, post.video)
        if (post.cover != post.video) removeFile(ctx, post.cover)
        if (post.memoryId.isNotEmpty()) Settings.prefs(ctx).edit().remove(sharedKey(ctx, post.memoryId)).apply()
    }

    private fun removeFile(ctx: Context, publicUrl: String) {
        if (publicUrl.isEmpty()) return
        try {
            val conn = open(ctx, "DELETE", "/storage/v1/object/$BUCKET")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.outputStream.use { it.write(JSONObject().put("prefixes", JSONArray().put(mediaPath(ctx, publicUrl))).toString().toByteArray(Charsets.UTF_8)) }
            try { read(conn) } finally { conn.disconnect() }
        } catch (_: Exception) {
        }
    }

    // ---------- the family file, shared with the other parent (server/family-file-share.sql) ----------

    /** Sends (or updates) one item of this parent's family file for the other parent. Background thread only. */
    fun fileItemPut(ctx: Context, partner: String, itemId: String, data: JSONObject, filePath: String?) {
        val row = JSONObject().put("circle_id", circleId(ctx)).put("owner_id", Account.id(ctx)).put("item_id", itemId)
            .put("partner_id", partner).put("data", data).put("file_path", filePath ?: JSONObject.NULL)
            .put("updated_at", isoOf(System.currentTimeMillis())).put("deleted_at", JSONObject.NULL)
        rest(ctx, "POST", "family_file_items?on_conflict=circle_id,owner_id,item_id", row.toString(), "resolution=merge-duplicates,return=minimal")
    }

    /** Marks one of this parent's items removed (the other phone removes it too), and removes its file. */
    fun fileItemRemove(ctx: Context, itemId: String) {
        val rows = rest(ctx, "PATCH", "family_file_items?circle_id=eq.${circleId(ctx)}&owner_id=eq.${enc(Account.id(ctx))}&item_id=eq.${enc(itemId)}",
            JSONObject().put("deleted_at", isoOf(System.currentTimeMillis())).put("data", JSONObject()).put("file_path", JSONObject.NULL).toString())
        (0 until rows.length()).mapNotNull { rows.optJSONObject(it)?.optString("file_path")?.nullless() }.forEach { removeFile(ctx, it) }
    }

    /** The other parent's shared items (with removed ones marked). Background thread only. */
    fun fileItemsFrom(ctx: Context, partner: String): List<JSONObject> {
        val rows = rest(ctx, "GET", "family_file_items?circle_id=eq.${circleId(ctx)}&owner_id=eq.${enc(partner)}&partner_id=eq.${enc(Account.id(ctx))}&select=item_id,data,file_path,updated_at,deleted_at&limit=5000")
        return (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }
    }

    /** Stops sharing: everything this parent sent (rows and files) is deleted from the server. */
    fun fileItemsClear(ctx: Context) {
        val mine = "family_file_items?circle_id=eq.${circleId(ctx)}&owner_id=eq.${enc(Account.id(ctx))}"
        val rows = rest(ctx, "GET", "$mine&select=file_path&limit=5000")
        (0 until rows.length()).mapNotNull { rows.optJSONObject(it)?.optString("file_path")?.nullless() }.forEach { removeFile(ctx, it) }
        rest(ctx, "DELETE", mine, prefer = "return=minimal")
    }

    /** True when the server still needs the small update (caption/style columns). */
    fun needsServerUpdate(ctx: Context) = Settings.prefs(ctx).getBoolean("circle_old_schema", false)

    /** Insert or update a post; if the server doesn't know the new columns yet, save without them. */
    private fun writePost(ctx: Context, method: String, path: String, post: JSONObject) {
        try {
            rest(ctx, method, path, post.toString())
            Settings.prefs(ctx).edit().putBoolean("circle_old_schema", false).apply()
        } catch (e: Exception) {
            throw e // No insecure or data-loss fallback to the prototype schema.
        }
    }

    // ---------- sharing ----------

    /** Shares a moment: the chosen part of the clip, a cover, a title, your words and a style. Background thread only. */
    fun shareMoment(ctx: Context, m: Memory, d: Draft = Draft(Fmt.title(m), "", true, "plain"), onlyFor: String? = null, whole: Boolean = false, onProgress: (String) -> Unit) {
        secureReady(ctx)
        if (!m.isVideo) { shareImage(ctx, m, d, onProgress, onlyFor); return }
        onProgress(L.t("רגע, אנחנו מכינים את הרגע למשפחה...", "Getting this moment ready for your family..."))
        // [whole]: the whole video as it is (a film made in the app), not a short part of it.
        val original = File(m.uri).takeIf { whole && !m.uri.startsWith("content://") && it.exists() }
        val clip = original ?: cutRange(ctx, m, d.startMs, d.endMs)
        val base = "${circleId(ctx)}/${Account.id(ctx)}/" + (if (onlyFor != null) "p/$onlyFor/" else "") + "${m.id}_${System.currentTimeMillis()}"
        var video = ""
        var coverUrl = ""
        try {
            onProgress(L.t("שולחים למעגל המשפחה...", "Sending to your family circle..."))
            video = upload(ctx, "$base.mp4", clip, "video/mp4")
            coverUrl = coverAt(ctx, m, d)?.let { bmp -> uploadJpeg(ctx, "${base}_cover.jpg", bmp) } ?: ""
            val post = JSONObject()
                .put("circle_id", circleId(ctx)).put("author_id", Account.id(ctx)).put("author", me(ctx)).put("title", d.title.trim())
                .put("story", if (d.withStory) m.description else "").put("people", Fmt.names(m).joinToString(" · "))
                .put("place", Fmt.place(m)).put("kind", "video").put("taken_at", if (m.timeSource == "imported") JSONObject.NULL else isoOf(m.time))
                .put("video", video).put("cover", coverUrl)
                .put("caption", d.caption.trim()).put("style", d.style).put("memory_id", m.id)
            if (onlyFor != null) post.put("only_for", onlyFor)
            writePost(ctx, "POST", "posts", post)
            // Shared only with the other parent: it still counts as not shared with the family.
            if (onlyFor == null) Settings.prefs(ctx).edit().putBoolean(sharedKey(ctx, m.id), true).apply()
        } catch (e: Exception) {
            removeFile(ctx, video); removeFile(ctx, coverUrl); throw e
        } finally {
            if (clip !== original) clip.delete()
        }
    }

    // ---------- the parents' shared gallery: Pitzki's films too ----------

    /** Background thread. Puts one of Pitzki's film files for the other parent only; returns its path. */
    fun uploadForPartner(ctx: Context, partner: String, rel: String, f: File, type: String): String {
        val path = "${circleId(ctx)}/${Account.id(ctx)}/p/$partner/$rel"
        return try { upload(ctx, path, f, type) } catch (e: Exception) {
            // Already there (an earlier try stopped in the middle): the same file, same path.
            val m = e.message.orEmpty()
            if ("Duplicate" in m || "already exists" in m || "409" in m) path else throw e
        }
    }

    /** Background thread. A post for the other parent only, with Pitzki's film of a child (its files are in [manifest]). */
    fun postPitzkiFilm(ctx: Context, partner: String, child: String, cover: String, manifest: JSONObject) {
        writePost(ctx, "POST", "posts", JSONObject().put("circle_id", circleId(ctx)).put("author_id", Account.id(ctx)).put("author", me(ctx))
            .put("title", L.t("🧝 הסרט של פיצקי עם $child", "🧝 Pitzki's film with $child")).put("kind", "pitzki")
            .put("video", cover).put("cover", cover).put("caption", manifest.toString()).put("style", "plain").put("only_for", partner))
    }

    /** Background thread. The children (names, birthdays, pictures, film characters) for the other parent only. */
    fun postFamily(ctx: Context, partner: String, manifest: JSONObject) {
        writePost(ctx, "POST", "posts", JSONObject().put("circle_id", circleId(ctx)).put("author_id", Account.id(ctx)).put("author", me(ctx))
            .put("title", L.t("הילדים", "The children")).put("kind", "family")
            .put("caption", manifest.toString()).put("style", "plain").put("only_for", partner))
    }

    /** Background thread. The newest children's details the other parent sent me: (post id, manifest), or null. */
    fun familyFrom(ctx: Context, partner: String): Pair<String, JSONObject>? {
        secureReady(ctx)
        val rows = rest(ctx, "GET", "posts?circle_id=eq.${circleId(ctx)}&kind=eq.family&author_id=eq.${enc(partner)}&order=created_at.desc&limit=1&select=id,caption")
        if (rows.length() == 0) return null
        val o = rows.getJSONObject(0)
        return try { o.optString("id") to JSONObject(o.optString("caption")) } catch (_: Exception) { null }
    }

    /** Background thread. Pitzki's films the other parent shared with me (newest first): (post id, manifest). */
    fun pitzkiFilmsFrom(ctx: Context, partner: String): List<Pair<String, JSONObject>> {
        secureReady(ctx)
        val rows = rest(ctx, "GET", "posts?circle_id=eq.${circleId(ctx)}&kind=eq.pitzki&author_id=eq.${enc(partner)}&order=created_at.desc&limit=20&select=id,caption")
        return (0 until rows.length()).mapNotNull { i ->
            val o = rows.getJSONObject(i)
            try { o.optString("id") to JSONObject(o.optString("caption")) } catch (_: Exception) { null }
        }
    }

    /** Background thread. Downloads a shared file (by its path) to [to]. */
    fun downloadShared(ctx: Context, path: String, to: File): Boolean {
        val url = signedUrl(ctx, mediaPath(ctx, path))
        if (url.isEmpty()) return false
        val tmp = File(to.parentFile, to.name + ".part")
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 20_000; conn.readTimeout = 120_000
            try {
                if (conn.responseCode !in 200..299) return false
                conn.inputStream.use { i -> tmp.outputStream().use { i.copyTo(it) } }
            } finally { conn.disconnect() }
            tmp.length() > 0 && (to.delete() || true) && tmp.renameTo(to)
        } catch (_: Exception) { tmp.delete(); false }
    }

    private fun shareImage(ctx: Context, m: Memory, d: Draft, onProgress: (String) -> Unit, onlyFor: String? = null) {
        val source = if (m.uri.startsWith("content://")) Uri.parse(m.uri) else Uri.fromFile(File(m.uri))
        val type = ctx.contentResolver.getType(source) ?: when (File(m.uri).extension.lowercase()) {
            "png" -> "image/png"; "webp" -> "image/webp"; "heic", "heif" -> "image/heic"; else -> "image/jpeg"
        }
        val tmp = File(ctx.cacheDir, "circle_photo_${System.currentTimeMillis()}")
        var uploaded = ""
        try {
            onProgress(L.t("רגע, אנחנו שומרים את הזיכרון הזה...", "Saving this memory..."))
            ctx.contentResolver.openInputStream(source)?.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                ?: throw IllegalStateException(L.t("התמונה אינה זמינה", "This photo isn't available"))
            require(tmp.length() <= 45L * 1024 * 1024) { L.t("התמונה גדולה מדי לשיתוף (עד 45MB)", "This photo is too large to share (max 45MB)") }
            val ext = when (type) { "image/png" -> "png"; "image/webp" -> "webp"; "image/heic" -> "heic"; else -> "jpg" }
            val base = "${circleId(ctx)}/${Account.id(ctx)}/" + (if (onlyFor != null) "p/$onlyFor/" else "") + "${m.id}_${System.currentTimeMillis()}"
            uploaded = upload(ctx, "$base.$ext", tmp, type)
            // A small copy for the grid (a whole phone photo is megabytes; the grid needs a few dozen KB).
            val cover = thumbOf(tmp)?.let { t -> try { upload(ctx, "${base}_thumb.jpg", t, "image/jpeg") } catch (_: Exception) { null } finally { t.delete() } } ?: uploaded
            writePost(ctx, "POST", "posts", JSONObject().put("circle_id", circleId(ctx)).put("author_id", Account.id(ctx)).put("author", me(ctx))
                .apply { if (onlyFor != null) put("only_for", onlyFor) }
                .put("title", d.title.trim()).put("story", if (d.withStory) m.description else "").put("people", Fmt.names(m).joinToString(" · "))
                .put("place", Fmt.place(m)).put("kind", "photo").put("taken_at", if (m.timeSource == "imported") JSONObject.NULL else isoOf(m.time)).put("video", uploaded).put("cover", cover)
                .put("caption", d.caption.trim()).put("style", d.style).put("memory_id", m.id))
            if (onlyFor == null) Settings.prefs(ctx).edit().putBoolean(sharedKey(ctx, m.id), true).apply()
        } catch (e: Exception) { if (uploaded.isNotEmpty()) removeFile(ctx, uploaded); throw e }
        finally { tmp.delete() }
    }

    /**
     * Edits a shared post: words, title, style, and (when [recut] and the moment is on this phone) a new part of the clip.
     * Background thread only.
     */
    fun updatePost(ctx: Context, post: Post, d: Draft, m: Memory?, recut: Boolean, onProgress: (String) -> Unit) {
        val change = JSONObject().put("title", d.title.trim()).put("caption", d.caption.trim()).put("style", d.style)
        if (m != null) change.put("story", if (d.withStory) m.description else "")
        var oldVideo = ""
        var oldCover = ""
        if (recut && m != null && m.isVideo) {
            onProgress(L.t("גוזרים את הקטע החדש...", "Trimming the new clip..."))
            val clip = cutRange(ctx, m, d.startMs, d.endMs)
            try {
                val base = "${circleId(ctx)}/${Account.id(ctx)}/${m.id}_${System.currentTimeMillis()}"
                onProgress(L.t("שולחים למעגל המשפחה...", "Sending to your family circle..."))
                change.put("video", upload(ctx, "$base.mp4", clip, "video/mp4"))
                coverAt(ctx, m, d)?.let { change.put("cover", uploadJpeg(ctx, "${base}_cover.jpg", it)) }
                oldVideo = post.video
                if (change.has("cover")) oldCover = post.cover
            } finally {
                clip.delete()
            }
        }
        onProgress(L.t("שומרים...", "Saving..."))
        writePost(ctx, "PATCH", "posts?id=eq.${post.id}", change)
        if (oldVideo.isNotEmpty()) removeFile(ctx, oldVideo)
        if (oldCover.isNotEmpty() && oldCover != oldVideo) removeFile(ctx, oldCover)
    }

    /** The cover: the frame in the middle of the chosen part (or the usual cover). */
    internal fun coverAt(ctx: Context, m: Memory, d: Draft): Bitmap? {
        if (d.startMs >= 0 && d.endMs > d.startMs) {
            val mmr = MediaMetadataRetriever()
            try {
                if (m.uri.startsWith("content://")) mmr.setDataSource(ctx, Uri.parse(m.uri)) else mmr.setDataSource(m.uri)
                mmr.getFrameAtTime((d.startMs + d.endMs) / 2 * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { return it }
            } catch (_: Exception) {
            } finally {
                try { mmr.release() } catch (_: Exception) {}
            }
        }
        // The whole picture at a good size first: the card cover can fall back to the small round preview, which is
        // a close crop of a face (a post then showed only a mouth and a shirt).
        return Covers.sharp(ctx, m, 1280) ?: Covers.get(ctx, m)
    }

    private fun sharedKey(ctx: Context, memoryId: String) = "circle_shared_${Account.id(ctx)}_${circleId(ctx)}_$memoryId"
    fun wasShared(ctx: Context, m: Memory) = Settings.prefs(ctx).getBoolean(sharedKey(ctx, m.id), false)

    /** Shares a photo or video from the phone's gallery. Background thread only. */
    fun shareFromGallery(ctx: Context, uri: Uri, title: String, onProgress: (String) -> Unit) {
        secureReady(ctx)
        val type = ctx.contentResolver.getType(uri) ?: throw IllegalStateException(L.t("לא ניתן לזהות את סוג המדיה", "Couldn't recognize this media type"))
        require(type.startsWith("video/") || type.startsWith("image/")) { L.t("אפשר לשתף רק תמונות וסרטונים", "Only photos and videos can be shared") }
        val isVideo = type.startsWith("video/")
        val tmp = File(ctx.cacheDir, "circle_upload_${System.currentTimeMillis()}")
        try {
            onProgress(L.t("רגע, אנחנו שומרים את הזיכרון הזה...", "Saving this memory..."))
            ctx.contentResolver.openInputStream(uri)?.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                ?: throw IllegalStateException(L.t("לא הצלחתי לקרוא את הקובץ", "Couldn't read the file"))
            if (tmp.length() > 45L * 1024 * 1024) throw IllegalStateException(L.t("הקובץ גדול מדי (עד 45MB). אפשר לקצר אותו קודם.", "This file is too large (max 45MB). Try trimming it first."))
            val ext = when (type) { "video/mp4" -> "mp4"; "video/quicktime" -> "mov"; "video/webm" -> "webm"; "image/png" -> "png"; "image/webp" -> "webp"; "image/heic", "image/heif" -> "heic"; else -> if (isVideo) "video" else "jpg" }
            val base = "${circleId(ctx)}/${Account.id(ctx)}/app_${System.currentTimeMillis()}"
            onProgress(L.t("שולחים למעגל המשפחה...", "Sending to your family circle..."))
            val fileUrl = upload(ctx, "$base.$ext", tmp, type)
            val coverUrl = if (isVideo) {
                val frame = try {
                    val mmr = MediaMetadataRetriever()
                    mmr.setDataSource(tmp.absolutePath)
                    val f = mmr.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    mmr.release()
                    f
                } catch (_: Exception) {
                    null
                }
                frame?.let { uploadJpeg(ctx, "${base}_cover.jpg", it) } ?: ""
            } else fileUrl
            val post = JSONObject()
                .put("circle_id", circleId(ctx)).put("author_id", Account.id(ctx)).put("author", me(ctx)).put("title", title.trim())
                .put("kind", if (isVideo) "video" else "photo").put("taken_at", JSONObject.NULL)
                .put("video", fileUrl).put("cover", coverUrl)
            rest(ctx, "POST", "posts", post.toString())
        } finally {
            tmp.delete()
        }
    }

    private fun uploadJpeg(ctx: Context, path: String, bmp: Bitmap): String {
        val f = File(ctx.cacheDir, "cover_${System.currentTimeMillis()}.jpg")
        try {
            val w = 720
            val scaled = if (bmp.width > w) Bitmap.createScaledBitmap(bmp, w, (bmp.height * w.toFloat() / bmp.width).toInt(), true) else bmp
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 82, out)
            f.writeBytes(out.toByteArray())
            return upload(ctx, path, f, "image/jpeg")
        } finally {
            f.delete()
        }
    }

    /**
     * Copies part of a clip into a small MP4, without re-encoding.
     * [startMs]/[endMs] < 0 = ~10 seconds around the moment itself (the highlight).
     */
    internal fun cutRange(ctx: Context, m: Memory, startMs: Long, endMs: Long): File {
        val seconds = SHARE_SECONDS
        val src = if (m.uri.startsWith("content://")) Uri.parse(m.uri) else Uri.fromFile(File(m.uri))
        val ex = MediaExtractor()
        ex.setDataSource(ctx, src, null)
        val rotation = try {
            val mmr = MediaMetadataRetriever()
            mmr.setDataSource(ctx, src)
            val r = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            mmr.release()
            r
        } catch (_: Exception) {
            0
        }
        val out = File(ctx.cacheDir, "circle_${System.currentTimeMillis()}.mp4")
        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        muxer.setOrientationHint(rotation)
        val map = HashMap<Int, Int>()
        var durUs = m.durationMs * 1000
        var videoTrack = -1
        for (i in 0 until ex.trackCount) {
            val f = ex.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                ex.selectTrack(i)
                map[i] = muxer.addTrack(f)
                if (mime.startsWith("video/")) {
                    videoTrack = i
                    if (f.containsKey(MediaFormat.KEY_DURATION)) durUs = f.getLong(MediaFormat.KEY_DURATION)
                }
            }
        }
        if (videoTrack < 0) {
            ex.release()
            muxer.release()
            out.delete()
            throw IllegalStateException(L.t("לא מצאתי וידאו ברגע הזה", "No video found in this moment"))
        }
        muxer.start()
        val half = seconds * 500_000L
        val mid = (m.highlight * 1000).coerceIn(0, durUs) // the moment itself
        val chosen = startMs >= 0 && endMs > startMs
        val startUs = if (chosen) (startMs * 1000).coerceIn(0, durUs) else (mid - half).coerceAtLeast(0)
        val endUs = if (chosen) (endMs * 1000).coerceIn(startUs, durUs) else (mid + half).coerceAtMost(durUs)
        ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        val buf = ByteBuffer.allocate(4 * 1024 * 1024)
        val info = MediaCodec.BufferInfo()
        var base = -1L
        try {
            while (true) {
                val track = ex.sampleTrackIndex
                if (track < 0) break
                val t = ex.sampleTime
                if (t > endUs) break
                val size = ex.readSampleData(buf, 0)
                if (size < 0) break
                if (base < 0) {
                    if (track != videoTrack || (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) == 0) { ex.advance(); continue }
                    base = t
                }
                val o = map[track]
                if (o != null && t >= base) {
                    val sync = (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                    info.set(0, size, t - base, if (sync) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                    muxer.writeSampleData(o, buf, info)
                }
                ex.advance()
            }
        } finally {
            try { muxer.stop() } catch (_: Exception) {}
            muxer.release()
            ex.release()
        }
        return out
    }

    // ---------- images from the web ----------

    private val imgCache = object : android.util.LruCache<String, Bitmap>(BitmapLru.share(16, 16 * 1024 * 1024)) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /** Downloads a cover (cached on disk and in memory). Background thread only. */
    /** A link without its query (the signed token): the same picture always has the same key. */
    fun cacheKey(url: String): String = url.substringBefore('?')

    fun image(ctx: Context, url: String): Bitmap? {
        if (url.isEmpty()) return null
        // Kept by the file's own address, without the link's changing token: a picture seen once opens from the
        // phone next time (keyed by the whole link, every new link downloaded it all over again).
        val key = cacheKey(url)
        imgCache.get(key)?.let { return it }
        val f = File(File(ctx.cacheDir, "circle_img").apply { mkdirs() }, key.hashCode().toString() + ".img")
        if (!f.exists()) {
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                try {
                    if (conn.responseCode != 200) return null
                    conn.inputStream.use { input -> f.outputStream().use { input.copyTo(it) } }
                } finally {
                    conn.disconnect()
                }
            } catch (_: Exception) {
                f.delete()
                return null
            }
        }
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, opts)
        var sample = 1
        while (opts.outWidth / (sample * 2) >= 720) sample *= 2
        val raw = BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val bmp = upright(f, raw)
        imgCache.put(key, bmp)
        return bmp
    }

    /** A small upright JPEG (longest side 640) of a photo file, for covers; null when it can't be read. */
    fun thumbOf(src: File): File? = try {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(src.absolutePath, o)
        var sample = 1
        while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= 640) sample *= 2
        val raw = BitmapFactory.decodeFile(src.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
        if (raw == null) null else {
            val up = upright(src, raw)
            val scale = 640f / maxOf(up.width, up.height)
            val small = if (scale < 1f) Bitmap.createScaledBitmap(up, (up.width * scale).toInt().coerceAtLeast(1), (up.height * scale).toInt().coerceAtLeast(1), true) else up
            val out = File(src.parentFile, src.name + "_thumb.jpg")
            out.outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 80, it) }
            out
        }
    } catch (_: Throwable) { null }

    /**
     * Photos this parent shared with the other parent before covers were small: a small cover is made and set, a
     * few at a time ([max]), so the other phone's grid fills quickly. From the photo on this phone when it's here,
     * else from the shared original. Background thread only. Returns how many were fixed.
     */
    fun fixPartnerCovers(ctx: Context, partner: String, max: Int = 20): Int {
        val me = Account.id(ctx)
        if (me.isEmpty() || partner.isEmpty() || circleId(ctx).isEmpty()) return 0
        val rows = rest(ctx, "GET", "posts?circle_id=eq.${circleId(ctx)}&author_id=eq.${enc(me)}&only_for=eq.${enc(partner)}&kind=eq.photo" +
            "&select=id,video,cover,memory_id&order=created_at.desc&limit=1000")
        var fixed = 0
        for (i in 0 until rows.length()) {
            if (fixed >= max) break
            val o = rows.getJSONObject(i)
            val video = mediaPath(ctx, o.optString("video").nullless())
            val cover = mediaPath(ctx, o.optString("cover").nullless())
            if (video.isEmpty() || (cover.isNotEmpty() && cover != video)) continue
            val tmp = File(ctx.cacheDir, "cover_fix_${System.nanoTime()}")
            try {
                val local = MemoryStore.get(ctx, o.optString("memory_id"))?.uri?.takeIf { !it.startsWith("content://") }?.let { File(it) }?.takeIf { it.exists() }
                if (local != null) local.copyTo(tmp, overwrite = true)
                else (URL(signedUrl(ctx, video)).openConnection() as HttpURLConnection).run {
                    try { if (responseCode == 200) inputStream.use { i2 -> tmp.outputStream().use { i2.copyTo(it) } } } finally { disconnect() }
                }
                val t = if (tmp.length() > 0) thumbOf(tmp) else null
                if (t != null) try {
                    val path = upload(ctx, video.substringBeforeLast('.') + "_thumb.jpg", t, "image/jpeg")
                    writePost(ctx, "PATCH", "posts?id=eq.${o.optString("id")}", JSONObject().put("cover", path))
                    fixed++
                } finally { t.delete() }
            } catch (_: Exception) {
            } finally { tmp.delete() }
        }
        return fixed
    }

    /** A phone photo is often stored sideways with a note (EXIF) saying how to turn it: turn it, so it isn't shown on its side. */
    private fun upright(f: File, bmp: Bitmap): Bitmap {
        val deg = try {
            when (android.media.ExifInterface(f.absolutePath).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL)) {
                android.media.ExifInterface.ORIENTATION_ROTATE_90, android.media.ExifInterface.ORIENTATION_TRANSPOSE -> 90f
                android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                android.media.ExifInterface.ORIENTATION_ROTATE_270, android.media.ExifInterface.ORIENTATION_TRANSVERSE -> 270f
                else -> 0f
            }
        } catch (_: Exception) { 0f } // not a JPEG/HEIC with a note (a cover we made, a video frame): as it is
        if (deg == 0f) return bmp
        return try {
            Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, android.graphics.Matrix().apply { postRotate(deg) }, true)
        } catch (_: OutOfMemoryError) { bmp }
    }
}
