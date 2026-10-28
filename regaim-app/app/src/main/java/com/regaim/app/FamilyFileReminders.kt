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
}

class FamilyFileRemindersWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        try { FamilyFileReminders.check(applicationContext) } catch (_: Throwable) { }
        return Result.success()
    }
}
