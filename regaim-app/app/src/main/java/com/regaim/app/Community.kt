package com.regaim.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The community feed: moments that people chose, one by one, to publish to every Moments user.
 * Private family circles are separate (see Circle). Needs server/community-v4.sql.
 * Only the first name is shown; place and full names are never published here.
 */
object Community {
    private const val BUCKET = "community"
    const val PAGE = 12
    val REPORT_REASONS = listOf("תוכן לא הולם", "פרטיות או ילדים", "ספאם או פרסומת", "אחר")
    /** Display labels for [REPORT_REASONS] (same order); the Hebrew value is what is sent to the server. */
    val REPORT_REASON_LABELS: List<String> get() = listOf(
        L.t("תוכן לא הולם", "Inappropriate content"), L.t("פרטיות או ילדים", "Privacy or children"),
        L.t("ספאם או פרסומת", "Spam or advertising"), L.t("אחר", "Other"),
    )

    class Post(
        val id: String, val authorId: String, val author: String, val title: String, val caption: String,
        val kind: String, val mediaUrl: String, val coverUrl: String, val createdAt: Long,
        val hidden: Boolean, val likes: Int, val liked: Boolean, val comments: Int, val reports: Int = 0,
        /** Posted by the Moments team (the server sets this; nobody else can). */
        val official: Boolean = false,
        /** A book: its pages (picture link, words), cover first. */
        val pages: List<Pair<String, String>> = emptyList(),
        /** The parent's own rating (1-5, 0 = none) and review of the book they made. */
        val rating: Int = 0,
        val review: String = "",
        /** The server checked this account really made this book in the app (set by the server only). */
        val verified: Boolean = false,
    ) {
        val isVideo get() = kind == "video"
        val isBook get() = kind == "book"
        val image get() = coverUrl.ifEmpty { mediaUrl }
    }

    class Comment(val id: String, val authorId: String, val author: String, val body: String, val createdAt: Long)

    class Page(val posts: List<Post>, val more: Boolean)

    // ---------- settings ----------

    private fun p(ctx: Context) = Settings.prefs(ctx)
    /** The rules were accepted, including "I'm 18 or older" (asked again of anyone who accepted before it was added). */
    fun termsAccepted(ctx: Context) = p(ctx).getBoolean("community_terms_18", false)
    fun acceptTerms(ctx: Context) = p(ctx).edit().putBoolean("community_terms", true).putBoolean("community_terms_18", true).apply()
    fun lastAudience(ctx: Context) = p(ctx).getString("publish_audience", "family") ?: "family"
    fun setLastAudience(ctx: Context, a: String) = p(ctx).edit().putString("publish_audience", a).apply()

    /** Signed in with a real account (the community is for signed-in members only). */
    fun available(ctx: Context) = Account.signedIn(ctx)

    /** The name others see: first name only. */
    fun displayName(ctx: Context): String =
        Profile.parentName(ctx).ifEmpty { Account.name(ctx) }.ifEmpty { Circle.me(ctx) }.trim().split(" ").first().take(40).ifEmpty { L.t("חבר במעגל", "Friend") }

    // ---------- http ----------

    private fun open(ctx: Context, method: String, path: String): HttpURLConnection {
        val conn = URL(Circle.url(ctx) + path).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 20_000
        conn.readTimeout = 120_000
        conn.setRequestProperty("apikey", Circle.key(ctx))
        val token = Account.token(ctx) ?: throw IllegalStateException(L.t("כדי להשתמש בקהילה צריך להתחבר לחשבון.", "Sign in to your account to use the community."))
        conn.setRequestProperty("Authorization", "Bearer $token")
        return conn
    }

    /** The 409 "already done" error, in either language. */
    private fun isAlreadyDone(e: Exception) = e.message?.let { "כבר" in it || "already" in it } == true

    private fun read(conn: HttpURLConnection): String {
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        if (code !in 200..299) {
            val missing = code == 404 || "PGRST205" in text || "PGRST202" in text || "does not exist" in text
            throw IllegalStateException(
                when {
                    missing -> L.t("הקהילה עוד לא זמינה. נסו שוב מאוחר יותר.", "The community isn't available yet. Try again later.")
                    code == 401 || code == 403 -> L.t("אין הרשאה. התחברו שוב לחשבון.", "Not authorized. Please sign in again.")
                    code == 409 -> L.t("כבר עשיתם את זה", "You've already done that")
                    else -> L.t("שגיאת שרת ($code)", "Server error ($code)")
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

    private fun upload(ctx: Context, path: String, file: File, type: String) {
        val conn = open(ctx, "POST", "/storage/v1/object/$BUCKET/$path")
        try {
            conn.setRequestProperty("Content-Type", type)
            conn.setRequestProperty("x-upsert", "false")
            conn.doOutput = true
            conn.setFixedLengthStreamingMode(file.length())
            conn.outputStream.use { o -> file.inputStream().use { it.copyTo(o) } }
            read(conn)
        } finally {
            conn.disconnect()
        }
    }

    private fun removeObject(ctx: Context, path: String) {
        if (path.isEmpty()) return
        try {
            val conn = open(ctx, "DELETE", "/storage/v1/object/$BUCKET/$path")
            try { conn.responseCode } finally { conn.disconnect() }
        } catch (_: Exception) {}
    }

    private fun enc(v: String) = URLEncoder.encode(v, "UTF-8")
    private fun publicUrl(ctx: Context, path: String) = if (path.isEmpty()) "" else Circle.url(ctx) + "/storage/v1/object/public/$BUCKET/" + path

    /**
     * Short-lived signed links for a page of posts, in one request. The community media is private
     * (children's photos must not open for anyone who has a link); the server signs only what the
     * signed-in member may see. Falls back to the old public links if the server isn't updated yet.
     */
    private fun links(ctx: Context, paths: List<String>): Map<String, String> {
        val wanted = paths.filter { it.isNotEmpty() }.distinct()
        if (wanted.isEmpty()) return emptyMap()
        return try {
            val conn = open(ctx, "POST", "/storage/v1/object/sign/$BUCKET")
            val text = try {
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                val body = JSONObject().put("expiresIn", 3600).put("paths", JSONArray(wanted))
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                read(conn)
            } finally { conn.disconnect() }
            val arr = JSONArray(text)
            val out = HashMap<String, String>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val signed = o.optString("signedURL").takeIf { it.isNotBlank() && it != "null" } ?: continue
                out[o.optString("path")] = if (signed.startsWith("https://")) signed else Circle.url(ctx) + "/storage/v1" + signed
            }
            wanted.associateWith { out[it] ?: publicUrl(ctx, it) }
        } catch (_: Exception) {
            wanted.associateWith { publicUrl(ctx, it) }
        }
    }

    private val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    private fun isoOf(t: Long) = synchronized(iso) { iso.format(Date(t)) }
    private fun timeOf(s: String): Long {
        if (s.isEmpty() || s == "null") return 0L
        val core = s.replace(Regex("(\\.\\d{3})\\d*"), "$1").replace("+00:00", "Z")
        for (f in listOf("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "yyyy-MM-dd'T'HH:mm:ssXXX")) {
            try {
                return SimpleDateFormat(f, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(core)?.time ?: continue
            } catch (_: Exception) {}
        }
        return 0L
    }

    // ---------- feed ----------

    /** Newest first. [before] = createdAt of the last post you already have. Background thread only. */
    fun feed(ctx: Context, before: Long = 0L): Page {
        val me = Account.id(ctx)
        var q = "community_posts?order=created_at.desc&limit=${PAGE + 1}&select=*"
        if (before > 0) q += "&created_at=lt.${enc(isoOf(before))}"
        val rows = rest(ctx, "GET", q)
        val rawPosts = (0 until minOf(rows.length(), PAGE)).map { rows.getJSONObject(it) }
        if (rawPosts.isEmpty()) return Page(emptyList(), false)
        val ids = rawPosts.joinToString(",") { it.getString("id") }
        val likes = rest(ctx, "GET", "community_likes?post_id=in.($ids)&select=post_id,user_id")
        val comments = rest(ctx, "GET", "community_comments?post_id=in.($ids)&select=post_id")
        val reports = if (Account.isAdmin(ctx)) rest(ctx, "GET", "community_reports?post_id=in.($ids)&select=post_id") else JSONArray()
        fun count(a: JSONArray, id: String) = (0 until a.length()).count { a.getJSONObject(it).optString("post_id") == id }
        fun pagesOf(o: JSONObject): List<Pair<String, String>> {
            val a = o.optJSONArray("pages") ?: return emptyList()
            return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { it.optString("p") to it.optString("t") }.filter { it.first.isNotEmpty() }
        }
        val urls = links(ctx, rawPosts.flatMap { listOf(it.optString("media_path"), it.optString("cover_path")) + pagesOf(it).map { p -> p.first } }.map { if (it == "null") "" else it })
        fun url(path: String) = if (path.isEmpty() || path == "null") "" else urls[path] ?: publicUrl(ctx, path)
        val posts = rawPosts.map { o ->
            val id = o.getString("id")
            Post(
                id = id, authorId = o.optString("author_id"), author = o.optString("author_name"),
                title = o.optString("title"), caption = o.optString("caption"), kind = o.optString("kind", "photo"),
                mediaUrl = url(o.optString("media_path")), coverUrl = url(o.optString("cover_path")),
                createdAt = timeOf(o.optString("created_at")), hidden = o.optBoolean("hidden"),
                likes = count(likes, id),
                liked = (0 until likes.length()).any { likes.getJSONObject(it).let { l -> l.optString("post_id") == id && l.optString("user_id") == me } },
                comments = count(comments, id), reports = count(reports, id),
                official = o.optBoolean("official", false),
                pages = pagesOf(o).map { (path, text) -> url(path) to text },
                rating = if (o.isNull("rating")) 0 else o.optInt("rating", 0),
                review = o.optString("review").takeIf { it != "null" }.orEmpty(),
                verified = o.optBoolean("verified", false),
            )
        }
        return Page(posts, rows.length() > PAGE)
    }

    fun setLike(ctx: Context, postId: String, like: Boolean) {
        if (like) {
            try {
                rest(ctx, "POST", "community_likes", JSONObject().put("post_id", postId).toString(), "return=minimal")
            } catch (e: IllegalStateException) {
                if (!isAlreadyDone(e)) throw e
            }
        } else {
            rest(ctx, "DELETE", "community_likes?post_id=eq.${enc(postId)}&user_id=eq.${enc(Account.id(ctx))}", null, "return=minimal")
        }
    }

    fun comments(ctx: Context, postId: String): List<Comment> {
        val rows = rest(ctx, "GET", "community_comments?post_id=eq.${enc(postId)}&order=created_at.asc&limit=200&select=*")
        return (0 until rows.length()).map { rows.getJSONObject(it).let { o ->
            Comment(o.optString("id"), o.optString("author_id"), o.optString("author_name"), o.optString("body"), timeOf(o.optString("created_at")))
        } }
    }

    fun addComment(ctx: Context, postId: String, body: String) {
        rest(ctx, "POST", "community_comments", JSONObject().put("post_id", postId).put("author_name", displayName(ctx)).put("body", body.trim().take(500)).toString(), "return=minimal")
    }

    fun deleteComment(ctx: Context, id: String) {
        rest(ctx, "DELETE", "community_comments?id=eq.${enc(id)}", null, "return=minimal")
    }

    fun report(ctx: Context, postId: String, reason: String) {
        try {
            rest(ctx, "POST", "community_reports", JSONObject().put("post_id", postId).put("reason", reason.take(60)).toString(), "return=minimal")
        } catch (e: IllegalStateException) {
            if (!isAlreadyDone(e)) throw e
        }
    }

    fun block(ctx: Context, authorId: String) {
        try {
            rest(ctx, "POST", "community_blocks", JSONObject().put("blocked_id", authorId).toString(), "return=minimal")
        } catch (e: IllegalStateException) {
            if (!isAlreadyDone(e)) throw e
        }
    }

    /** Deletes a post (its author, or the admin) together with its files. */
    fun delete(ctx: Context, post: Post) {
        // Works for public and signed links alike: the file's path is what follows "/community/", before any "?token=".
        fun pathOf(url: String) = url.substringBefore("?").substringAfter("/$BUCKET/", "")
        rest(ctx, "DELETE", "community_posts?id=eq.${enc(post.id)}", null, "return=minimal")
        removeObject(ctx, pathOf(post.mediaUrl))
        if (post.coverUrl.isNotEmpty() && post.coverUrl != post.mediaUrl) removeObject(ctx, pathOf(post.coverUrl))
        post.pages.map { pathOf(it.first) }.filter { it.isNotEmpty() && it != pathOf(post.mediaUrl) }.forEach { removeObject(ctx, it) }
    }

    /** Admin only: hide or bring back a post. */
    fun setHidden(ctx: Context, postId: String, hidden: Boolean) {
        rest(ctx, "PATCH", "community_posts?id=eq.${enc(postId)}", JSONObject().put("hidden", hidden).toString(), "return=minimal")
    }

    // ---------- publishing ----------

    /**
     * Publishes one memory to the community. A photo goes up as a JPEG (max 1600 px), a video as ~10 seconds
     * around its highlight. Place and people are NOT sent. Background thread only.
     */
    fun publish(ctx: Context, m: Memory, title: String, caption: String, onProgress: (String) -> Unit) {
        val uid = Account.id(ctx)
        require(uid.isNotEmpty()) { L.t("צריך להתחבר לחשבון", "Please sign in to your account") }
        val stamp = "${m.id.replace(Regex("[^A-Za-z0-9_-]"), "")}_${System.currentTimeMillis()}"
        var mediaPath = ""
        var coverPath = ""
        val rawClip = if (m.isVideo) Circle.cutRange(ctx, m, -1, -1) else null
        // Everything that goes public carries the Moments mark, so people know where it came from.
        val clip = rawClip?.let { SocialShare.stampClip(ctx, it) ?: it }
        try {
            onProgress(L.t("מכינים את הרגע...", "Preparing your moment…"))
            if (m.isVideo) {
                mediaPath = "$uid/$stamp.mp4"
                onProgress(L.t("שולחים לקהילה...", "Sharing with the community…"))
                upload(ctx, mediaPath, clip!!, "video/mp4")
                val cover = Circle.coverAt(ctx, m, Circle.Draft(title, caption, false, "plain"))
                if (cover != null) {
                    coverPath = "$uid/${stamp}_cover.jpg"
                    uploadBitmap(ctx, coverPath, SocialShare.stampBitmap(ctx, cover))
                }
            } else {
                val bmp = MediaImages.decode(ctx, m.uri, 1600) ?: throw IllegalStateException(L.t("התמונה אינה זמינה", "This photo isn't available"))
                mediaPath = "$uid/$stamp.jpg"
                onProgress(L.t("שולחים לקהילה...", "Sharing with the community…"))
                uploadBitmap(ctx, mediaPath, SocialShare.stampBitmap(ctx, bmp))
            }
            val row = JSONObject()
                .put("author_name", displayName(ctx)).put("title", title.trim().take(120)).put("caption", caption.trim().take(1000))
                .put("kind", if (m.isVideo) "video" else "photo").put("media_path", mediaPath).put("cover_path", coverPath)
                .put("taken_at", JSONObject.NULL) // the exact time a child was somewhere isn't for strangers
            rest(ctx, "POST", "community_posts", row.toString(), "return=minimal")
            p(ctx).edit().putBoolean("community_published_${m.id}", true).apply()
        } catch (e: Exception) {
            removeObject(ctx, mediaPath); removeObject(ctx, coverPath)
            throw e
        } finally {
            clip?.delete()
            if (rawClip != clip) rawClip?.delete()
        }
    }

    fun wasPublished(ctx: Context, m: Memory) = p(ctx).getBoolean("community_published_${m.id}", false)

    /**
     * Shares a finished book with the community: its pictures (with the Moments mark), and its words if the parent
     * chose so, with the parent's own rating and review. The server marks it "a real book" only if this account
     * really made it in the app. Background thread only.
     */
    fun publishBook(ctx: Context, b: Book, rating: Int, review: String, withText: Boolean, onProgress: (String) -> Unit) {
        val uid = Account.id(ctx)
        require(uid.isNotEmpty()) { L.t("צריך להתחבר לחשבון", "Please sign in to your account") }
        val dir = BookStore.dir(ctx, b.id)
        val ready = b.allPages.withIndex().filter { (_, p) -> p.status == "done" && p.image.isNotEmpty() && File(dir, p.image).exists() }
        require(ready.isNotEmpty()) { L.t("אין עמודים מוכנים בספר", "The book has no finished pages") }
        val stamp = "book_${b.id.replace(Regex("[^A-Za-z0-9_-]"), "")}_${System.currentTimeMillis()}"
        val done = ArrayList<String>()
        val pages = JSONArray()
        try {
            ready.forEachIndexed { k, (i, p) ->
                onProgress(L.t("מעלים עמוד ${k + 1} מתוך ${ready.size}...", "Uploading page ${k + 1} of ${ready.size}…"))
                val bmp = BitmapFactory.decodeFile(File(dir, p.image).absolutePath) ?: return@forEachIndexed
                val path = "$uid/$stamp/p$i.jpg"
                uploadBitmap(ctx, path, SocialShare.stampBitmap(ctx, bmp))
                done.add(path)
                val words = if (!withText) "" else if (i == 0) b.title else BookJson.display(p.text)
                pages.put(JSONObject().put("p", path).put("t", words.take(800)))
            }
            require(done.isNotEmpty()) { L.t("לא הצלחנו להעלות את העמודים", "Couldn't upload the pages") }
            val row = JSONObject()
                .put("author_name", displayName(ctx))
                .put("title", (if (withText) b.title else L.t("ספר אישי שיצרנו ב-Moments", "A personal book we made in Moments")).trim().take(120))
                .put("caption", "").put("kind", "book").put("media_path", done.first()).put("cover_path", "")
                .put("pages", pages).put("rating", if (rating in 1..5) rating else JSONObject.NULL)
                .put("review", review.trim().take(600)).put("book_ref", b.id.take(80))
                .put("taken_at", JSONObject.NULL)
            onProgress(L.t("משתפים בקהילה...", "Sharing with the community…"))
            try {
                rest(ctx, "POST", "community_posts", row.toString(), "return=minimal")
            } catch (e: Exception) {
                // The server isn't updated for books yet (payments-and-community-books.sql).
                if (e.message?.contains("kind_check") == true || e.message?.contains("pages") == true)
                    throw IllegalStateException(L.t("עוד אי אפשר לשתף ספרים בקהילה. נסו שוב מאוחר יותר.", "Books can't be shared in the community yet. Try again later."))
                throw e
            }
            p(ctx).edit().putBoolean("community_book_${b.id}", true).apply()
        } catch (e: Exception) {
            done.forEach { removeObject(ctx, it) }
            throw e
        }
    }

    fun bookShared(ctx: Context, b: Book) = p(ctx).getBoolean("community_book_${b.id}", false)

    /**
     * A post that is a picture made in the app (a tip or a challenge card from the Moments team).
     * Only the admin account uses it; the server marks such posts as official. Background thread only.
     */
    fun publishCard(ctx: Context, bmp: Bitmap, title: String, caption: String) {
        val uid = Account.id(ctx)
        require(uid.isNotEmpty()) { L.t("צריך להתחבר לחשבון", "Please sign in to your account") }
        val path = "$uid/card_${System.currentTimeMillis()}.jpg"
        uploadBitmap(ctx, path, bmp)
        try {
            val row = JSONObject()
                .put("author_name", "Moments").put("title", title.trim().take(120)).put("caption", caption.trim().take(1000))
                .put("kind", "photo").put("media_path", path).put("cover_path", "").put("taken_at", JSONObject.NULL)
            rest(ctx, "POST", "community_posts", row.toString(), "return=minimal")
        } catch (e: Exception) { removeObject(ctx, path); throw e }
    }

    /** A clean square card with big text, for the team's tips and challenges. */
    fun tipCard(ctx: Context, headline: String, body: String): Bitmap {
        val size = 1080
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        c.drawColor(android.graphics.Color.rgb(43, 58, 53))
        val rtl = !L.isEn
        fun layout(text: String, sizePx: Float, color: Int, bold: Boolean, width: Int): android.text.StaticLayout {
            val paint = android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color; textSize = sizePx; typeface = if (bold) Fonts.display else android.graphics.Typeface.DEFAULT
            }
            return android.text.StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
                .setAlignment(android.text.Layout.Alignment.ALIGN_CENTER)
                .setTextDirection(if (rtl) android.text.TextDirectionHeuristics.RTL else android.text.TextDirectionHeuristics.LTR)
                .setLineSpacing(0f, 1.15f).build()
        }
        val w = size - 160
        val h1 = layout(headline, 84f, android.graphics.Color.WHITE, true, w)
        val h2 = layout(body, 48f, android.graphics.Color.argb(215, 255, 255, 255), false, w)
        val total = h1.height + 50 + h2.height
        var y = (size - total) / 2f
        c.save(); c.translate(80f, y); h1.draw(c); c.restore()
        y += h1.height + 50
        c.save(); c.translate(80f, y); h2.draw(c); c.restore()
        val mark = SocialShare.badge(ctx, 300)
        c.drawBitmap(mark, (size - mark.width) / 2f, size - mark.height - 60f, null)
        return bmp
    }

    private fun uploadBitmap(ctx: Context, path: String, bmp: Bitmap) {
        val side = 1600f / maxOf(bmp.width, bmp.height)
        val scaled = if (side < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * side).toInt(), (bmp.height * side).toInt(), true) else bmp
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 86, out)
        val tmp = File(ctx.cacheDir, "community_${System.currentTimeMillis()}.jpg")
        try {
            tmp.writeBytes(out.toByteArray())
            upload(ctx, path, tmp, "image/jpeg")
        } finally {
            tmp.delete()
        }
    }

    // ---------- pictures ----------

    private val imgCache = object : android.util.LruCache<String, Bitmap>(BitmapLru.share(16, 24 * 1024 * 1024)) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /** A feed picture (public address, no sign-in needed). Background thread only. */
    fun image(ctx: Context, url: String): Bitmap? {
        if (url.isEmpty()) return null
        // Signed links change every time the feed loads: remember pictures by the file, not the link.
        val key = url.substringBefore("?")
        imgCache.get(key)?.let { return it }
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            val bytes = try {
                if (conn.responseCode != 200) return null
                conn.inputStream.use { it.readBytes() }
            } finally {
                conn.disconnect()
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 1080) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?.also { imgCache.put(key, it) }
        } catch (_: Exception) {
            null
        }
    }

}
