package com.regaim.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.format.DateUtils
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import java.util.concurrent.Executors

/**
 * The backup to Google Drive: turn it on (connects Drive once), see when it last ran and how much is kept, back up
 * now, or restore onto this phone. Says plainly what's backed up, where, and who can read it.
 */
class BackupActivity : AppCompatActivity() {
    companion object {
        fun open(ctx: Context) = ctx.startActivity(Intent(ctx, BackupActivity::class.java))
    }

    private lateinit var ui: Ui
    private lateinit var content: LinearLayout
    private val worker = Executors.newSingleThreadExecutor()
    private var status: TextView? = null
    /** What to do once Google says yes: turn on, or restore. */
    private var afterConnect: ((String) -> Unit)? = null

    private val consent = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        val t = try { Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(res.data).accessToken } catch (_: Exception) { null }
        if (t == null) say(L.t("לא קיבלנו הרשאה מגוגל. אפשר לנסות שוב.", "Google didn't grant access. You can try again."))
        else afterConnect?.invoke(t)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_screen)
        findViewById<View>(R.id.btnClose).setOnClickListener { finish() }
        findViewById<TextView>(R.id.screenTitle).text = L.t("גיבוי לגוגל דרייב", "Backup to Google Drive")
        content = findViewById(R.id.screenContent)
        ui = Ui(this)
        render()
    }

    override fun onResume() { super.onResume(); render(); tick() }

    override fun onDestroy() { worker.shutdownNow(); super.onDestroy() }

    private fun say(t: String) = runOnUiThread { if (!isFinishing) status?.text = t }

    /** While a backup runs, its progress line updates. */
    private fun tick() {
        if (isFinishing || isDestroyed) return
        // (Only a new step is announced, not every second's count.)
        if (DriveBackup.busy) {
            val p = DriveBackup.progress
            if (p.substringBefore(' ') != status?.text?.toString()?.substringBefore(' ') || p.isEmpty()) status?.text = p
            content.postDelayed({ tick() }, 3000)
        }
    }

    private fun render() {
        content.removeAllViews()
        val on = DriveBackup.on(this)
        content.addView(ui.heading(if (on) L.t("הגיבוי פועל", "Backup is on") else L.t("הרגעים שמורים רק בטלפון הזה", "Your moments are only on this phone"), 20f, top = 12))
        content.addView(ui.hint(L.t(
            "מה נשמר: הרגעים, תיק המשפחה, הסצנות של פיצקי, הספרים, זיהוי הפנים והפרופיל של המשפחה.\n" +
                "איפה: בגוגל דרייב שלך, בתיקייה \"${GDrive.FOLDER}\". Moments רואה רק את הקבצים שהוא עצמו שם, ולא שום דבר אחר בדרייב.\n" +
                "מי יכול לקרוא: הקבצים מוצפנים, והמפתח שמור בחשבון שלך בשרת של Moments, לא ליד הקבצים. כדי לפתוח את הגיבוי צריך את שניהם. מחיקת החשבון מוחקת את המפתח, ואז אי אפשר לפתוח את הגיבוי.\n" +
                "מתי: פעם ביום, ב-Wi-Fi כשהטלפון בטעינה, ורק מה שהשתנה.",
            "What's kept: the moments, the family file, Pitzki's scenes, the books, face recognition and the family profile.\n" +
                "Where: in your Google Drive, in the \"${GDrive.FOLDER}\" folder. Moments sees only the files it put there, nothing else in your Drive.\n" +
                "Who can read it: the files are encrypted, and the key is kept in your account on the Moments server, not next to the files. Opening the backup needs both. Deleting the account deletes the key, and then the backup can't be opened.\n" +
                "When: once a day, on Wi-Fi while charging, only what changed."), top = 8))

        if (!Account.signedIn(this)) {
            content.addView(ui.hint(L.t("כדי לגבות צריך להתחבר לחשבון Moments (המפתח שמור שם).", "To back up, sign in to your Moments account (the key is kept there)."), top = 14))
            content.addView(ui.button(L.t("התחברות", "Sign in"), primary = true, top = 12) { WelcomeActivity.open(this) })
            return
        }
        if (on) {
            val last = DriveBackup.last(this)
            val (n, bytes) = DriveBackup.totals(this)
            content.addView(ui.text(if (last == 0L) L.t("עוד לא רץ", "Hasn't run yet") else L.t("גיבוי אחרון: ", "Last backup: ") +
                DateUtils.getRelativeTimeSpanString(last, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS) +
                (if (n > 0) L.t(" · $n קבצים · ${mb(bytes)}", " · $n files · ${mb(bytes)}") else ""), 14f, top = 14))
            val err = DriveBackup.error(this)
            if (err.isNotEmpty()) content.addView(ui.text(if (err == DriveBackup.NEED_CONNECT) L.t("⚠️ צריך לחבר שוב את גוגל דרייב.", "⚠️ Connect Google Drive again.") else "⚠️ $err", 14f, R.color.ink, top = 6))
            content.addView(ui.button(L.t("לגבות עכשיו", "Back up now"), primary = true, top = 14) {
                if (err == DriveBackup.NEED_CONNECT) connect { backupNow() } else backupNow()
            })
            content.addView(ui.action(L.t("שחזור מהגיבוי לטלפון הזה ›", "Restore the backup onto this phone ›")) { askRestore() })
            content.addView(ui.action(L.t("לכבות את הגיבוי", "Turn the backup off"), R.color.ink_soft) {
                AlertDialog.Builder(this)
                    .setTitle(L.t("לכבות את הגיבוי?", "Turn the backup off?"))
                    .setMessage(L.t("לא יעלה יותר שום דבר לדרייב. מה שכבר שם נשאר, ומוחקים אותו בדרייב עצמו (התיקייה \"${GDrive.FOLDER}\").", "Nothing more goes to Drive. What's already there stays; delete it in Drive itself (the \"${GDrive.FOLDER}\" folder)."))
                    .setPositiveButton(L.t("לכבות", "Turn off")) { _, _ -> DriveBackup.setOn(this, false); render(); say(L.t("הגיבוי כבוי.", "The backup is off.")) }
                    .setNegativeButton(L.t("ביטול", "Cancel"), null)
                    .show()
            })
        } else {
            content.addView(ui.button(L.t("להפעיל גיבוי לגוגל דרייב", "Turn on the Google Drive backup"), primary = true, top = 16) {
                connect { token -> worker.execute {
                    // A backup from another phone in this Drive: offer it before backing this phone up over it.
                    val there = try { DriveBackup.found(this, token) } catch (_: Exception) { null }
                    runOnUiThread {
                        if (isFinishing) return@runOnUiThread
                        DriveBackup.setOn(this, true)
                        if (there != null && MemoryStore.kept(this).isEmpty()) askRestore(token, there) else backupNow()
                        render()
                    }
                } }
            })
            content.addView(ui.action(L.t("טלפון חדש? שחזור מגיבוי ›", "New phone? Restore a backup ›")) { askRestore() })
        }
        // Announced to screen readers when it changes (a backup's progress and result).
        status = ui.hint(if (DriveBackup.busy) DriveBackup.progress else "", top = 12).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        content.addView(status)
    }

    private fun mb(b: Long) = if (b >= 1_000_000_000) String.format(java.util.Locale.US, "%.1f GB", b / 1e9) else "${b / 1_000_000} MB"

    /** Google's permission for Moments' own files in Drive (asked on screen the first time). */
    private fun connect(then: (String) -> Unit) {
        say(L.t("מתחברים לגוגל דרייב…", "Connecting to Google Drive…"))
        afterConnect = then
        Identity.getAuthorizationClient(this).authorize(GDrive.request())
            .addOnSuccessListener { r: AuthorizationResult ->
                if (r.hasResolution()) {
                    try { consent.launch(IntentSenderRequest.Builder(r.pendingIntent!!.intentSender).build()) }
                    catch (_: Exception) { say(L.t("לא הצלחנו לפתוח את חלון ההרשאה.", "Couldn't open the permission window.")) }
                } else r.accessToken?.let(then) ?: say(L.t("לא התקבלה הרשאה מגוגל.", "Google didn't grant access."))
            }
            .addOnFailureListener { say(L.t("ההתחברות לגוגל לא הצליחה. נסו שוב מאוחר יותר.", "Couldn't connect to Google. Try again later.")) }
    }

    private fun backupNow() {
        if (DriveBackup.busy) { tick(); return }
        say(L.t("מתחילים לגבות…", "Starting the backup…"))
        worker.execute {
            val ok = DriveBackup.run(applicationContext)
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                render()
                say(if (ok) L.t("✓ הכול מגובה", "✓ Everything is backed up") else DriveBackup.error(this).let { if (it == DriveBackup.NEED_CONNECT) "" else it })
            }
        }
        content.postDelayed({ tick() }, 500)
    }

    private fun askRestore(token: String? = null, at: Long? = null) {
        if (token == null) { connect { t -> worker.execute {
            val there = try { DriveBackup.found(this, t) } catch (_: Exception) { null }
            runOnUiThread { if (!isFinishing) { if (there == null) say(L.t("לא נמצא גיבוי של Moments בגוגל דרייב הזה (או שהוא של חשבון אחר).", "No Moments backup in this Google Drive (or it's another account's).")) else askRestore(t, there) } }
        } }; return }
        AlertDialog.Builder(this)
            .setTitle(L.t("לשחזר את הגיבוי?", "Restore the backup?"))
            .setMessage(L.t("נמצא גיבוי מ-${DateUtils.getRelativeTimeSpanString(at ?: 0L, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)}. הוא יוחזר לטלפון הזה, ומה שיש כאן באותם קבצים יוחלף. בסוף האפליקציה תיפתח מחדש.",
                "A backup from ${DateUtils.getRelativeTimeSpanString(at ?: 0L, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)} was found. It comes back onto this phone, replacing the same files here. The app restarts at the end."))
            .setPositiveButton(L.t("לשחזר", "Restore")) { _, _ -> restore(token) }
            .setNegativeButton(L.t("ביטול", "Cancel"), null)
            .show()
    }

    private fun restore(token: String) {
        say(L.t("משחזרים…", "Restoring…"))
        worker.execute {
            val why = DriveBackup.restore(applicationContext, token) { say(it) }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (why != null && !why.startsWith(L.t("שוחזר", "Restored"))) { say(why); return@runOnUiThread }
                AlertDialog.Builder(this)
                    .setMessage(why ?: L.t("✓ שוחזר. האפליקציה תיפתח מחדש.", "✓ Restored. The app will restart."))
                    .setCancelable(false)
                    .setPositiveButton(L.t("אישור", "OK")) { _, _ ->
                        // Everything read from the files when the app started is reloaded: a fresh start.
                        val i = packageManager.getLaunchIntentForPackage(packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        if (i != null) startActivity(i)
                        finishAffinity()
                        Runtime.getRuntime().exit(0)
                    }
                    .show()
            }
        }
    }
}
