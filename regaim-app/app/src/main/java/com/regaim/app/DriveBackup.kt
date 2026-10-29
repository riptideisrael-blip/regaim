package com.regaim.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.crypto.spec.SecretKeySpec

/**
 * The backup to the parent's own Google Drive: everything the app keeps on the phone (moments, the family file,
 * Pitzki's scenes, books, faces, the family profile and settings), encrypted with a key kept in the parent's Moments
 * account (so the files in Drive alone can't be read, and our server never has the files). Once a day on Wi-Fi while
 * charging, only what changed; "Back up now" any time. Restoring on a new phone: sign in, connect Drive, restore.
 * Not backed up: things that come back by themselves (the face model, server caches, prices) and sign-in secrets.
 */
object DriveBackup {
    private const val ON = "backup_drive_on"
    private const val LAST = "backup_last"
    private const val ERROR = "backup_error"
    private const val BYTES = "backup_bytes"
    private const val FILES = "backup_files"
    private const val FOLDER_ID = "backup_folder"
    const val NEED_CONNECT = "need_connect"
    private const val WORK = "drive_backup"
    private const val NOW = "drive_backup_now"
    private const val MANIFEST = "manifest.mbk"
    private const val PREFS = "prefs.mbk"

    fun on(ctx: Context) = Settings.prefs(ctx).getBoolean(ON, false)
    fun setOn(ctx: Context, v: Boolean) { Settings.prefs(ctx).edit().putBoolean(ON, v).apply(); schedule(ctx) }
    fun last(ctx: Context) = Settings.prefs(ctx).getLong(LAST, 0L)
    fun error(ctx: Context) = Settings.prefs(ctx).getString(ERROR, "").orEmpty()
    fun totals(ctx: Context) = Settings.prefs(ctx).getInt(FILES, 0) to Settings.prefs(ctx).getLong(BYTES, 0L)

    /** What the backup is doing now (shown on its screen), or "". */
    @Volatile var progress: String = ""
    private val running = java.util.concurrent.atomic.AtomicBoolean(false)
    val busy: Boolean get() = running.get()

    /** Once a day, on Wi-Fi, while charging, battery not low. Never throws. */
    fun schedule(ctx: Context) {
        try {
            val wm = WorkManager.getInstance(ctx.applicationContext)
            if (!on(ctx)) { wm.cancelUniqueWork(WORK); return }
            val c = Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).setRequiresCharging(true).setRequiresBatteryNotLow(true).build()
            wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<DriveBackupWorker>(1, TimeUnit.DAYS).setConstraints(c).build())
        } catch (_: Throwable) { }
    }

    /** A run now (any network), or the next part of a large first backup. Never throws. */
    fun runSoon(ctx: Context, wifiOnly: Boolean = false) {
        try {
            val c = Constraints.Builder().setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build()
            WorkManager.getInstance(ctx.applicationContext).enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<DriveBackupWorker>().setConstraints(c).build())
        } catch (_: Throwable) { }
    }

    // ---------- what goes in (pure rules, unit tested) ----------

    private val SKIP_NAMES = setOf("mobilefacenet.tflite", "mobilefacenet.tmp", "crypto_prices.json", "gemelnet", "crashes",
        "backup_state.json", "backup_key.bin", "memories.json.bak")

    /** A file (path under the app's files, with "/") that isn't backed up: it comes back by itself, or it's ours. */
    fun skip(path: String): Boolean {
        val parts = path.split('/')
        val first = parts.first()
        val name = parts.last()
        return first in SKIP_NAMES || name.startsWith(".") || name.endsWith(".tmp") || name.contains(".unreadable.") ||
            first.startsWith("circle_feed_") || first.startsWith("partner_moments_") || first.startsWith("ai_key_")
    }

    private val SKIP_PREF_FILES = setOf("billing", "gphotos", "app_update", "analysis_retry", "face_scan_tries")
    private val secretKey = Regex("(?i)^acc_|token|secret|password|refresh|session|ai_key|^backup_|^partner_notify_ready")

    /** A settings file and key that are restored on a new phone (not sign-in secrets, not the backup's own state). */
    fun keepPref(file: String, key: String): Boolean =
        file !in SKIP_PREF_FILES && !file.startsWith("androidx") && !file.startsWith("com.google") && !file.startsWith("WorkManager") &&
            !file.startsWith("Firebase") && !secretKey.containsMatchIn(key)

    /** The name a file has in Drive: not its real name (nothing about the family is readable there). */
    fun driveName(path: String): String =
        "f_" + MessageDigest.getInstance("SHA-1").digest(path.toByteArray()).joinToString("") { "%02x".format(it) }.take(24) + ".mbk"

    // ---------- the key, the state ----------

    private fun keyFile(ctx: Context) = File(ctx.filesDir, "backup_key.bin")

    /** The account's backup key, kept on the phone too (sealed with the phone's own key) after the first time. */
    private fun key(ctx: Context): SecretKeySpec {
        val f = keyFile(ctx)
        val local = try { SecureFiles.readBytes(f) } catch (_: Exception) { null }
        if (local != null && local.size == 32) return BackupCrypto.keyOf(local)
        val k = Circle.backupKey(ctx)
        try { SecureFiles.writeBytes(f, k) } catch (_: Exception) { }
        return BackupCrypto.keyOf(k)
    }

    private class Entry(val size: Long, val time: Long, val id: String, val sealed: Boolean)

    private fun stateFile(ctx: Context) = File(ctx.filesDir, "backup_state.json")

    private fun loadState(ctx: Context): MutableMap<String, Entry> {
        val o = try { JSONObject(stateFile(ctx).readText()) } catch (_: Exception) { JSONObject() }
        val m = HashMap<String, Entry>()
        for (k in o.keys()) o.optJSONObject(k)?.let { m[k] = Entry(it.optLong("s"), it.optLong("m"), it.optString("id"), it.optBoolean("sealed")) }
        return m
    }

    private fun saveState(ctx: Context, m: Map<String, Entry>) {
        val o = JSONObject()
        for ((k, e) in m) o.put(k, JSONObject().put("s", e.size).put("m", e.time).put("id", e.id).put("sealed", e.sealed))
        try { SafeFiles.write(stateFile(ctx), o.toString()) } catch (_: Exception) { stateFile(ctx).writeText(o.toString()) }
    }

    private fun files(ctx: Context): Map<String, File> {
        val root = ctx.filesDir
        return root.walkTopDown().filter { it.isFile }.mapNotNull { f ->
            val p = f.relativeTo(root).invariantSeparatorsPath
            if (skip(p)) null else p to f
        }.toMap()
    }

    // ---------- backing up ----------

    /**
     * One run: what changed goes up (encrypted), what's gone is removed, then the settings and the list of files.
     * Stops after [budgetMs] (a large first backup continues in the next run). Background thread only.
     * Returns true when everything is up to date.
     */
    fun run(ctx: Context, budgetMs: Long = 8 * 60_000L): Boolean {
        val app = ctx.applicationContext
        if (!running.compareAndSet(false, true)) return false
        val started = System.currentTimeMillis()
        try {
            if (!Account.signedIn(app)) { fail(app, L.t("צריך להתחבר לחשבון Moments.", "Sign in to your Moments account.")); return false }
            progress = L.t("מתחברים לגוגל דרייב…", "Connecting to Google Drive…")
            val token = try { GDrive.token(app) } catch (_: Exception) { null }
            if (token == null) { fail(app, NEED_CONNECT); return false }
            val key = key(app)
            val folder = GDrive.folder(token).also { Settings.prefs(app).edit().putString(FOLDER_ID, it).apply() }
            val state = loadState(app)
            val now = files(app)
            val todo = now.filter { (p, f) -> state[p].let { it == null || it.size != f.length() || it.time != f.lastModified() } }
            val tmp = File(app.cacheDir, "backup_up.mbk")
            var done = 0
            var complete = true
            for ((p, f) in todo) {
                if (System.currentTimeMillis() - started > budgetMs) { complete = false; break }
                progress = L.t("מגבים ${done + 1} מתוך ${todo.size}…", "Backing up ${done + 1} of ${todo.size}…")
                val size = f.length(); val time = f.lastModified()
                val sealed = SecureFiles.sealedFile(f)
                try {
                    (if (sealed) (SecureFiles.readBytes(f) ?: continue).inputStream() else f.inputStream()).use { i ->
                        tmp.outputStream().use { o -> BackupCrypto.encrypt(key, i, o) }
                    }
                } catch (_: Exception) { continue } // a file removed or unreadable just now: next time
                val id = GDrive.upload(token, driveName(p), folder, tmp, state[p]?.id)
                state[p] = Entry(size, time, id, sealed)
                done++
                if (done % 20 == 0) saveState(app, state)
            }
            tmp.delete()
            // Gone from the phone: gone from the backup (only after a complete pass, so a half run never removes).
            if (complete) for (p in state.keys.filter { it !in now && !it.startsWith("_") }) {
                try { GDrive.delete(token, state[p]!!.id) } catch (_: Exception) { }
                state.remove(p)
            }
            // The settings (family profile, choices), then the list of what's in the backup.
            progress = L.t("שומרים את ההגדרות…", "Saving the settings…")
            state["_prefs"] = Entry(0, 0, uploadBytes(app, token, key, folder, PREFS, prefsSnapshot(app).toString().toByteArray(), state["_prefs"]?.id), false)
            val list = JSONArray()
            for ((p, e) in state) if (!p.startsWith("_")) list.put(JSONObject().put("p", p).put("id", e.id).put("sealed", e.sealed))
            val manifest = JSONObject().put("v", 1).put("at", System.currentTimeMillis()).put("files", list).put("prefs", state["_prefs"]!!.id)
                .put("account", Account.id(app)).put("complete", complete)
            state["_manifest"] = Entry(0, 0, uploadBytes(app, token, key, folder, MANIFEST, manifest.toString().toByteArray(), state["_manifest"]?.id), false)
            saveState(app, state)
            val bytes = state.filterKeys { !it.startsWith("_") }.values.sumOf { it.size }
            Settings.prefs(app).edit().putLong(LAST, System.currentTimeMillis()).putString(ERROR, if (complete) "" else L.t("הגיבוי הראשון ממשיך…", "The first backup continues…"))
                .putInt(FILES, list.length()).putLong(BYTES, bytes).apply()
            if (!complete) runSoon(app)
            return complete
        } catch (e: Exception) {
            fail(app, e.message ?: e.javaClass.simpleName)
            return false
        } finally {
            progress = ""
            running.set(false)
        }
    }

    private fun uploadBytes(ctx: Context, token: String, key: SecretKeySpec, folder: String, name: String, data: ByteArray, existing: String?): String {
        val f = File(ctx.cacheDir, "backup_$name")
        try {
            f.writeBytes(BackupCrypto.encryptBytes(key, data))
            return GDrive.upload(token, name, folder, f, existing)
        } finally { f.delete() }
    }

    private fun fail(ctx: Context, why: String) {
        Settings.prefs(ctx).edit().putString(ERROR, why).apply()
        if (why == NEED_CONNECT) notifyReconnect(ctx)
    }

    /** The backup can't run until the parent connects Drive again (a tap opens the backup screen). */
    private fun notifyReconnect(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        try {
            nm.createNotificationChannel(NotificationChannel("backup", L.t("גיבוי", "Backup"), NotificationManager.IMPORTANCE_DEFAULT))
            val open = PendingIntent.getActivity(ctx, 7700, Intent(ctx, BackupActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            nm.notify(7700, NotificationCompat.Builder(ctx, "backup").setSmallIcon(R.drawable.ic_notif)
                .setContentTitle(L.t("הגיבוי לגוגל דרייב מחכה", "The Google Drive backup is waiting"))
                .setContentText(L.t("צריך לאשר שוב את החיבור לגוגל דרייב.", "Connect Google Drive again."))
                .setContentIntent(open).setAutoCancel(true).build())
        } catch (_: Exception) { }
    }

    // ---------- the settings ----------

    private fun prefsSnapshot(ctx: Context): JSONObject {
        val dir = File(ctx.applicationInfo.dataDir, "shared_prefs")
        val out = JSONObject()
        for (x in dir.listFiles().orEmpty()) {
            val name = x.name.removeSuffix(".xml")
            if (!x.name.endsWith(".xml") || !keepPref(name, "")) continue
            val o = JSONObject()
            for ((k, v) in ctx.getSharedPreferences(name, Context.MODE_PRIVATE).all) {
                if (!keepPref(name, k) || v == null) continue
                o.put(k, when (v) {
                    is String -> JSONObject().put("t", "s").put("v", v)
                    is Int -> JSONObject().put("t", "i").put("v", v)
                    is Long -> JSONObject().put("t", "l").put("v", v)
                    is Float -> JSONObject().put("t", "f").put("v", v.toDouble())
                    is Boolean -> JSONObject().put("t", "b").put("v", v)
                    is Set<*> -> JSONObject().put("t", "set").put("v", JSONArray(v.map { it.toString() }))
                    else -> continue
                })
            }
            out.put(name, o)
        }
        return out
    }

    private fun applyPrefs(ctx: Context, all: JSONObject) {
        for (name in all.keys()) {
            if (!keepPref(name, "")) continue
            val o = all.optJSONObject(name) ?: continue
            val e = ctx.getSharedPreferences(name, Context.MODE_PRIVATE).edit()
            for (k in o.keys()) {
                if (!keepPref(name, k)) continue
                val x = o.optJSONObject(k) ?: continue
                when (x.optString("t")) {
                    "s" -> e.putString(k, x.optString("v"))
                    "i" -> e.putInt(k, x.optInt("v"))
                    "l" -> e.putLong(k, x.optLong("v"))
                    "f" -> e.putFloat(k, x.optDouble("v").toFloat())
                    "b" -> e.putBoolean(k, x.optBoolean("v"))
                    "set" -> e.putStringSet(k, x.optJSONArray("v")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() } ?: emptySet())
                }
            }
            e.commit()
        }
    }

    // ---------- restoring ----------

    /** Is there a backup in this Drive (made by Moments)? Its time, or null. Background thread only. */
    fun found(ctx: Context, token: String): Long? {
        val folder = GDrive.find(token, GDrive.FOLDER) ?: return null
        val id = GDrive.find(token, MANIFEST, folder) ?: return null
        return manifest(ctx, token, id).optLong("at")
    }

    private fun manifest(ctx: Context, token: String, id: String): JSONObject {
        val f = File(ctx.cacheDir, "backup_manifest_in.mbk")
        try {
            GDrive.download(token, id, f)
            return JSONObject(String(BackupCrypto.decryptBytes(BackupCrypto.keyOf(Circle.backupKey(ctx)), f.readBytes())))
        } finally { f.delete() }
    }

    /**
     * Brings the backup back onto this phone: every file, then the settings (this phone's sign-in stays). What the
     * phone already has under the same name is replaced. Returns null when done, else why it stopped.
     * Background thread only; the app should restart after it.
     */
    fun restore(ctx: Context, token: String, step: (String) -> Unit): String? {
        val app = ctx.applicationContext
        if (!running.compareAndSet(false, true)) return L.t("גיבוי רץ עכשיו. נסו שוב בעוד רגע.", "A backup is running. Try again in a moment.")
        try {
            val folder = GDrive.find(token, GDrive.FOLDER) ?: return L.t("לא נמצא גיבוי של Moments בגוגל דרייב הזה.", "No Moments backup in this Google Drive.")
            val mid = GDrive.find(token, MANIFEST, folder) ?: return L.t("לא נמצא גיבוי של Moments בגוגל דרייב הזה.", "No Moments backup in this Google Drive.")
            val key = BackupCrypto.keyOf(Circle.backupKey(app))
            val m = try { manifest(app, token, mid) } catch (_: Exception) { return L.t("הגיבוי שייך לחשבון Moments אחר, או שנפגם.", "The backup belongs to another Moments account, or it's damaged.") }
            val list = m.optJSONArray("files") ?: JSONArray()
            val root = app.filesDir.canonicalFile
            val state = HashMap<String, Entry>()
            val enc = File(app.cacheDir, "restore_in.mbk")
            val plain = File(app.cacheDir, "restore_out.bin")
            var failed = 0
            for (i in 0 until list.length()) {
                val e = list.optJSONObject(i) ?: continue
                val p = e.optString("p")
                val target = File(root, p).canonicalFile
                if (p.isBlank() || p.startsWith("/") || p.contains("..") || !target.path.startsWith(root.path + File.separator) || skip(p)) continue
                step(L.t("משחזרים ${i + 1} מתוך ${list.length()}…", "Restoring ${i + 1} of ${list.length()}…"))
                try {
                    GDrive.download(token, e.optString("id"), enc)
                    enc.inputStream().use { inp -> plain.outputStream().use { BackupCrypto.decrypt(key, inp, it) } }
                    target.parentFile?.mkdirs()
                    val sealed = e.optBoolean("sealed")
                    if (sealed) SecureFiles.writeBytes(target, plain.readBytes())
                    else { if (!plain.renameTo(target)) { plain.copyTo(target, overwrite = true); plain.delete() } }
                    state[p] = Entry(target.length(), target.lastModified(), e.optString("id"), sealed)
                } catch (_: Exception) { failed++ }
            }
            enc.delete(); plain.delete()
            step(L.t("משחזרים את ההגדרות…", "Restoring the settings…"))
            m.optString("prefs").takeIf { it.isNotBlank() }?.let { pid ->
                try {
                    GDrive.download(token, pid, enc)
                    applyPrefs(app, JSONObject(String(BackupCrypto.decryptBytes(key, enc.readBytes()))))
                    state["_prefs"] = Entry(0, 0, pid, false)
                } catch (_: Exception) { failed++ } finally { enc.delete() }
            }
            state["_manifest"] = Entry(0, 0, mid, false)
            saveState(app, state)
            Settings.prefs(app).edit().putBoolean(ON, true).putString(FOLDER_ID, folder).putString(ERROR, "").apply()
            schedule(app)
            return if (failed == 0) null else L.t("שוחזר, חוץ מ-$failed קבצים שלא נפתחו.", "Restored, except $failed files that couldn't be opened.")
        } catch (e: Exception) {
            return e.message ?: e.javaClass.simpleName
        } finally {
            running.set(false)
        }
    }
}

class DriveBackupWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        if (!DriveBackup.on(applicationContext)) return Result.success()
        try { DriveBackup.run(applicationContext) } catch (_: Throwable) { }
        return Result.success()
    }
}
