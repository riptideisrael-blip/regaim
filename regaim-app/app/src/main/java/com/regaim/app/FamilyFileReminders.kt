package com.regaim.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Reminders before something in the family file runs out: a warranty (a month and a week before), an exchange slip
 * (3 days and a day before), a voucher, coupon or store credit (a month, a week and a day before). Looks once a day,
 * on the phone only (no server); one notification per item, never twice for the same window. Off in the family file.
 */
object FamilyFileReminders {
    private const val WORK = "family_file_reminders"
    private const val CHANNEL = "family_file"
    private const val ON = "family_file_reminders_on"
    private const val SENT = "family_file_reminders_sent"

    fun on(ctx: Context) = Settings.prefs(ctx).getBoolean(ON, true)
    fun setOn(ctx: Context, v: Boolean) { Settings.prefs(ctx).edit().putBoolean(ON, v).apply(); schedule(ctx) }

    fun schedule(ctx: Context) {
        try {
            val wm = WorkManager.getInstance(ctx.applicationContext)
            if (!on(ctx)) { wm.cancelUniqueWork(WORK); return }
            wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<FamilyFileRemindersWorker>(12, TimeUnit.HOURS).build())
        } catch (_: Throwable) { }
    }

    /** Looks once. Background thread only. */
    fun check(ctx: Context) {
        if (!on(ctx) || !FamilyFile.consented(ctx)) return
        try { checkSavings(ctx) } catch (_: Exception) { }
        val p = Settings.prefs(ctx)
        val sent = p.getStringSet(SENT, emptySet()).orEmpty()
        val items = FamilyFile.all(ctx).filterNot { CouponImport.noCode(it) }
        val due = FamilyItems.due(items, java.time.LocalDate.now().toString(), sent)
        if (due.isEmpty()) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        try {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, L.t("תיק המשפחה: לפני שתוקף נגמר", "Family file: before something expires"), NotificationManager.IMPORTANCE_DEFAULT))
        } catch (_: Exception) { }
        val done = sent.toMutableSet()
        for (d in due) {
            val (title, body) = FamilyItems.reminderText(d.item, d.days)
            try {
                val code = 7400 + (d.item.id.hashCode() and 0xFFFF)
                // Back from the item goes to the family file (not out of the app).
                val open = androidx.core.app.TaskStackBuilder.create(ctx)
                    .addNextIntent(Intent(ctx, FamilyFileActivity::class.java))
                    .addNextIntent(FamilyItemActivity.intent(ctx, d.item.id))
                    .getPendingIntent(code, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                nm.notify(code, NotificationCompat.Builder(ctx, CHANNEL)
                    .setSmallIcon(R.drawable.ic_notif)
                    .setContentTitle(title)
                    .setContentText(body)
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .build())
            } catch (_: Exception) { } // notifications not allowed
            done += d.keys
        }
        // Keep only what's still about current items (the set doesn't grow forever).
        val ids = items.map { it.id }.toSet()
        p.edit().putStringSet(SENT, done.filter { it.substringBefore(':') in ids }.toSet()).apply()
    }

    private const val SAVINGS_SENT = "savings_report_reminded"

    /**
     * A fund's next quarterly report should be out (its quarter ended over a month ago): one notification for all
     * such funds, once per fund and quarter. Opens the savings screen, where the report is uploaded.
     */
    private fun checkSavings(ctx: Context) {
        val p = Settings.prefs(ctx)
        val sent = p.getStringSet(SAVINGS_SENT, emptySet()).orEmpty()
        val today = java.time.LocalDate.now().toString()
        val kids = Profile.members(ctx).associate { it.id to it.name }
        val due = FamilyFile.savings(ctx).mapNotNull { f -> Savings.reportDue(f, today)?.let { q -> f to "${f.id}:$q" } }.filter { it.second !in sent }
        if (due.isEmpty()) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        try {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, L.t("תיק המשפחה: לפני שתוקף נגמר", "Family file: before something expires"), NotificationManager.IMPORTANCE_DEFAULT))
        } catch (_: Exception) { }
        val names = due.joinToString(", ") { (f, _) -> listOfNotNull(kids[f.child], f.provider.ifBlank { null }).joinToString(" · ") }
        try {
            val open = androidx.core.app.TaskStackBuilder.create(ctx)
                .addNextIntent(Intent(ctx, FamilyFileActivity::class.java))
                .addNextIntent(Intent(ctx, SavingsActivity::class.java))
                .getPendingIntent(7300, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            nm.notify(7300, NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle(L.t("דוח רבעוני חדש אמור להיות זמין", "A new quarterly report should be out"))
                .setContentText(L.t("$names: מעלים את הדוח כדי לעדכן את הסכום.", "$names: upload it to update the amount."))
                .setStyle(NotificationCompat.BigTextStyle().bigText(L.t("$names: הרבעון הסתיים לפני יותר מחודש, והדוח אמור להיות באתר של חברת הקופה. מעלים אותו כדי לעדכן את הסכום.",
                    "$names: the quarter ended over a month ago and the report should be on the fund's site. Upload it to update the amount.")))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build())
        } catch (_: Exception) { }
        val ids = FamilyFile.savings(ctx).map { it.id }.toSet()
        p.edit().putStringSet(SAVINGS_SENT, (sent + due.map { it.second }).filter { it.substringBefore(':') in ids }.toSet()).apply()
    }
}

class FamilyFileRemindersWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        try { FamilyFileReminders.check(applicationContext) } catch (_: Throwable) { }
        return Result.success()
    }
}
