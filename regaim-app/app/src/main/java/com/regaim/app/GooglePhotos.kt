package com.regaim.app

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Google Photos Picker API. The user chooses photos/videos (or whole albums) inside the Google Photos app;
 * we only ever receive what was picked. Needs the scope [SCOPE] and an Android OAuth client in Google Cloud.
 */
object GooglePhotos {
    const val SCOPE = "https://www.googleapis.com/auth/photospicker.mediaitems.readonly"
    private const val API = "https://photospicker.googleapis.com/v1"
    const val MAX_ITEMS = 2000

    class Session(val id: String, val pickerUri: String)
    class Item(val id: String, val video: Boolean, val baseUrl: String, val mime: String, val name: String, val createdAt: Long)

    private fun call(method: String, url: String, token: String, body: String? = null): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 15000; c.readTimeout = 30000
            c.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) { c.doOutput = true; c.setRequestProperty("Content-Type", "application/json"); c.outputStream.use { it.write(body.toByteArray()) } }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IllegalStateException(friendly(code, text))
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        } finally { c.disconnect() }
    }

    private fun friendly(code: Int, text: String): String = when {
        code == 401 -> L.t("ההרשאה של Google פגה. נסו שוב.", "Google access expired. Please try again.")
        code == 403 && "has not been used" in text -> L.t("ה-Google Photos Picker API עוד לא הופעל בפרויקט ב-Google Cloud.", "The Google Photos Picker API isn't enabled in the Google Cloud project yet.")
        code == 403 -> L.t("ל-Google אין הרשאה לפעולה הזאת. בדקו את ההגדרות ב-Google Cloud.", "Google doesn't allow this action. Check your Google Cloud settings.")
        else -> L.t("Google החזיר שגיאה ($code).", "Google returned an error ($code).")
    }

    fun createSession(token: String): Session {
        val o = call("POST", "$API/sessions", token, "{\"pickingConfig\":{\"maxItemCount\":\"$MAX_ITEMS\"}}")
        return Session(o.getString("id"), o.getString("pickerUri"))
    }

    fun picked(token: String, id: String): Boolean = call("GET", "$API/sessions/$id", token).optBoolean("mediaItemsSet", false)

    fun items(token: String, id: String): List<Item> {
        val out = ArrayList<Item>()
        var page: String? = null
        do {
            val u = "$API/mediaItems?sessionId=$id&pageSize=100" + (page?.let { "&pageToken=" + Uri.encode(it) } ?: "")
            val o = call("GET", u, token)
            val arr = o.optJSONArray("mediaItems")
            if (arr != null) for (i in 0 until arr.length()) {
                val it = arr.getJSONObject(i)
                val f = it.optJSONObject("mediaFile") ?: continue
                out.add(Item(it.optString("id"), it.optString("type") == "VIDEO", f.optString("baseUrl"), f.optString("mimeType"), f.optString("filename"), parseTime(it.optString("createTime"))))
            }
            page = o.optString("nextPageToken").ifBlank { null }
        } while (page != null && out.size < MAX_ITEMS)
        return out.take(MAX_ITEMS)
    }

    fun deleteSession(token: String, id: String) { try { call("DELETE", "$API/sessions/$id", token) } catch (_: Exception) { } }

    private fun parseTime(s: String): Long = try {
        val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        f.parse(s.take(19))?.time ?: 0L
    } catch (_: Exception) { 0L }

    /** Downloads one picked item into the phone's gallery (Pictures/Moments or Movies/Moments) and returns its address. */
    fun download(ctx: Context, token: String, item: Item): Uri {
        val ext = if (item.video) "mp4" else if (item.mime.contains("png")) "png" else "jpg"
        val mime = if (item.video) "video/mp4" else item.mime.ifBlank { "image/jpeg" }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "gphotos_${item.id.take(12).filter { it.isLetterOrDigit() }}.$ext")
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            if (item.createdAt > 0) put("datetaken", item.createdAt)
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, (if (item.video) "Movies" else "Pictures") + "/Moments")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val table = if (item.video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val uri = ctx.contentResolver.insert(table, values) ?: throw IllegalStateException(L.t("לא הצלחנו לשמור בגלריה", "Couldn't save to your gallery"))
        try {
            val c = URL(item.baseUrl + if (item.video) "=dv" else "=d").openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 15000; c.readTimeout = 120000
                c.setRequestProperty("Authorization", "Bearer $token")
                if (c.responseCode !in 200..299) throw IllegalStateException(friendly(c.responseCode, ""))
                (ctx.contentResolver.openOutputStream(uri) ?: throw IllegalStateException(L.t("לא הצלחנו לשמור בגלריה", "Couldn't save to your gallery"))).use { o -> c.inputStream.use { it.copyTo(o) } }
            } finally { c.disconnect() }
            if (Build.VERSION.SDK_INT >= 29) ctx.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            return uri
        } catch (e: Exception) {
            try { ctx.contentResolver.delete(uri, null, null) } catch (_: Exception) { }
            throw e
        }
    }
}
