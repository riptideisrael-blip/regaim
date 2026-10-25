package com.regaim.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * "Is there a new version?": the newest APK is on our server (a private folder: only someone signed in to the app can
 * download it), put there by the GitHub build. The app downloads it, checks it's whole and the same file, and opens
 * Android's installer (Android always asks the person to approve an install). Not for a Google Play version: there,
 * updates come from Play.
 */
object AppUpdater {
    private const val BUCKET = "app-updates"

    fun installed(ctx: Context): Long = try {
        PackageInfoCompat.getLongVersionCode(ctx.packageManager.getPackageInfo(ctx.packageName, 0))
    } catch (_: Exception) { 0L }

    private fun open(ctx: Context, path: String): HttpURLConnection {
        val token = Account.token(ctx) ?: throw IllegalStateException(L.t("כדי לבדוק עדכונים צריך להתחבר לחשבון", "Sign in to check for updates"))
        val conn = URL(Circle.url(ctx) + "/storage/v1/object/authenticated/$BUCKET/$path").openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 60_000
        conn.useCaches = false
        conn.setRequestProperty("apikey", Circle.key(ctx))
        conn.setRequestProperty("Authorization", "Bearer $token")
        return conn
    }

    /** The admin's test channel: the protected (R8) build, before it goes to the families. */
    fun beta(ctx: Context) = Account.isAdmin(ctx) && Settings.prefs(ctx).getBoolean("update_beta", false)
    fun setBeta(ctx: Context, on: Boolean) = Settings.prefs(ctx).edit().putBoolean("update_beta", on).apply()

    /** Background thread. The newest version on the server (null: none there yet). */
    fun check(ctx: Context): AppUpdate.Info? {
        val conn = open(ctx, if (beta(ctx)) "latest-beta.json" else "latest.json")
        try {
            val code = conn.responseCode
            if (code == 400 || code == 404) return null
            if (code !in 200..299) throw IllegalStateException(L.t("השרת לא ענה ($code)", "The server didn't answer ($code)"))
            return AppUpdate.parse(conn.inputStream.bufferedReader().use { it.readText() })
        } finally { conn.disconnect() }
    }

    private fun dir(ctx: Context) = File(ctx.cacheDir, "update").apply { mkdirs() }

    /** Background thread. Downloads the version (its parts, one after the other) and checks it. [progress]: 0..100. */
    fun download(ctx: Context, info: AppUpdate.Info, progress: (Int) -> Unit): File {
        val out = File(dir(ctx), "moments-${info.code}.apk")
        // An earlier download of the same version that's whole is used as is.
        if (out.length() == info.size && sha(out) == info.sha256) return out
        dir(ctx).listFiles()?.forEach { it.delete() }
        if (ctx.cacheDir.usableSpace < info.size * 2) throw IllegalStateException(L.t("אין מספיק מקום בטלפון", "Not enough space on the phone"))
        val tmp = File(dir(ctx), "download.part")
        var got = 0L
        tmp.outputStream().use { sink ->
            for (part in info.parts) {
                val conn = open(ctx, part)
                try {
                    if (conn.responseCode !in 200..299) throw IllegalStateException(L.t("ההורדה נכשלה (${conn.responseCode})", "The download failed (${conn.responseCode})"))
                    conn.inputStream.use { input ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf); if (n < 0) break
                            sink.write(buf, 0, n); got += n
                            progress((got * 100 / info.size).toInt().coerceIn(0, 100))
                        }
                    }
                } finally { conn.disconnect() }
            }
        }
        if (tmp.length() != info.size || sha(tmp) != info.sha256) {
            tmp.delete()
            throw IllegalStateException(L.t("הקובץ שירד לא שלם. אפשר לנסות שוב.", "The downloaded file isn't whole. Try again."))
        }
        tmp.renameTo(out)
        return out
    }

    private fun sha(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input -> val buf = ByteArray(64 * 1024); while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Opens Android's installer. The first time Android asks to allow installs from Moments: then the settings screen
     * opens and false is returned (after allowing, tap install again).
     */
    fun install(a: Activity, apk: File): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !a.packageManager.canRequestPackageInstalls()) {
            a.startActivity(Intent(AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.packageName)))
            return false
        }
        val uri = FileProvider.getUriForFile(a, a.packageName + ".files", apk)
        a.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }
}
