package com.regaim.app

import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * The parent's own Google Drive, for the backup: only files this app made (the "drive.file" permission: Moments
 * can't see anything else in the Drive), in one folder, "Moments backup". Plain HTTPS to Google's Drive API with the
 * token Google's sign-in gives on the phone. Background thread only, except [request].
 */
object GDrive {
    const val SCOPE = "https://www.googleapis.com/auth/drive.file"
    const val FOLDER = "Moments גיבוי"
    private const val API = "https://www.googleapis.com/drive/v3"
    private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"

    /** What Drive said, in words for the parent (no space left, the API not turned on, no permission). */
    class DriveException(val code: Int, message: String) : Exception(message)

    fun request(): AuthorizationRequest = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(SCOPE))).build()

    /** A token without asking (the parent allowed it before), or null when Google needs to ask on screen. */
    fun token(ctx: Context): String? {
        val r: AuthorizationResult = Tasks.await(Identity.getAuthorizationClient(ctx.applicationContext).authorize(request()), 30, TimeUnit.SECONDS)
        return if (r.hasResolution()) null else r.accessToken
    }

    private fun open(token: String, method: String, url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 20_000
            readTimeout = 120_000
            setRequestProperty("Authorization", "Bearer $token")
        }

    private fun read(c: HttpURLConnection): String {
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.use { String(it.readBytes(), Charsets.UTF_8) }.orEmpty()
        if (code !in 200..299) throw DriveException(code, why(code, text))
        return text
    }

    private fun why(code: Int, body: String): String {
        val reason = try { JSONObject(body).optJSONObject("error")?.optJSONArray("errors")?.optJSONObject(0)?.optString("reason").orEmpty() } catch (_: Exception) { "" }
        return when {
            reason == "storageQuotaExceeded" -> L.t("אין מספיק מקום בגוגל דרייב.", "There isn't enough space in Google Drive.")
            reason == "accessNotConfigured" || body.contains("has not been used in project") -> L.t("ה-API של גוגל דרייב לא מופעל בפרויקט של Google Cloud.", "The Google Drive API isn't turned on in the Google Cloud project.")
            code == 401 -> L.t("צריך להתחבר שוב לגוגל דרייב.", "Google Drive needs you to connect again.")
            else -> L.t("גוגל דרייב לא ענה (קוד $code).", "Google Drive didn't answer (code $code).")
        }
    }

    private fun q(s: String) = URLEncoder.encode(s, "UTF-8")

    /** The id of the file (or folder) named [name] that this app made, or null. */
    fun find(token: String, name: String, parent: String? = null): String? {
        val query = "name = '${name.replace("'", "\\'")}' and trashed = false" + (parent?.let { " and '$it' in parents" } ?: "")
        val c = open(token, "GET", "$API/files?q=${q(query)}&spaces=drive&fields=files(id)&pageSize=1")
        try {
            return JSONObject(read(c)).optJSONArray("files")?.optJSONObject(0)?.optString("id")?.ifBlank { null }
        } finally { c.disconnect() }
    }

    /** The backup folder, made the first time. */
    fun folder(token: String): String {
        find(token, FOLDER)?.let { return it }
        val c = open(token, "POST", "$API/files?fields=id")
        try {
            c.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            c.doOutput = true
            c.outputStream.use { it.write(JSONObject().put("name", FOLDER).put("mimeType", "application/vnd.google-apps.folder").toString().toByteArray()) }
            return JSONObject(read(c)).getString("id")
        } finally { c.disconnect() }
    }

    /**
     * Uploads [file] as [name] in [parent] (a new file), or as a new version of [existing]. Resumable upload, so
     * large videos go as one stream. Returns the file's id.
     */
    fun upload(token: String, name: String, parent: String, file: File, existing: String? = null): String {
        val meta = JSONObject().put("name", name)
        if (existing == null) meta.put("parents", org.json.JSONArray().put(parent))
        // (HttpURLConnection has no PATCH: Google's APIs take POST with the method in a header.)
        val start = open(token, "POST",
            if (existing == null) "$UPLOAD/files?uploadType=resumable&fields=id" else "$UPLOAD/files/$existing?uploadType=resumable&fields=id")
        if (existing != null) start.setRequestProperty("X-HTTP-Method-Override", "PATCH")
        val session = try {
            start.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            start.setRequestProperty("X-Upload-Content-Type", "application/octet-stream")
            start.setRequestProperty("X-Upload-Content-Length", file.length().toString())
            start.doOutput = true
            start.outputStream.use { it.write(meta.toString().toByteArray()) }
            read(start)
            start.getHeaderField("Location") ?: throw DriveException(0, L.t("גוגל דרייב לא פתח העלאה.", "Google Drive didn't start the upload."))
        } catch (e: java.io.FileNotFoundException) {
            // The file was removed from the Drive by hand: a new one.
            if (existing != null) return upload(token, name, parent, file, null) else throw e
        } catch (e: DriveException) {
            if (existing != null && e.code == 404) return upload(token, name, parent, file, null) else throw e
        } finally { start.disconnect() }
        val put = open(token, "PUT", session)
        try {
            put.setRequestProperty("Content-Type", "application/octet-stream")
            put.doOutput = true
            put.setFixedLengthStreamingMode(file.length())
            put.outputStream.use { o -> file.inputStream().use { it.copyTo(o, 64 * 1024) } }
            return JSONObject(read(put)).getString("id")
        } finally { put.disconnect() }
    }

    fun download(token: String, id: String, to: File) {
        val c = open(token, "GET", "$API/files/$id?alt=media")
        try {
            if (c.responseCode !in 200..299) read(c)
            c.inputStream.use { i -> to.outputStream().use { i.copyTo(it, 64 * 1024) } }
        } finally { c.disconnect() }
    }

    fun delete(token: String, id: String) {
        val c = open(token, "DELETE", "$API/files/$id")
        try { if (c.responseCode !in 200..299 && c.responseCode != 404) read(c) } finally { c.disconnect() }
    }
}
