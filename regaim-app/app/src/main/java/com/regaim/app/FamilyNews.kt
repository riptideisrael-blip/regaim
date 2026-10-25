package com.regaim.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * "A new moment from the family": every phone in the circle looks, about every 15 minutes (Android's shortest
 * regular interval), for posts shared since it last looked, and shows one notification for them. No push service:
 * only this phone asking the family's own server. Own posts don't notify. Off in the settings ([on]).
 */
object FamilyNews {
    private const val WORK = "family_news"
    const val CHANNEL = "family_news"
    private const val SEEN = "family_news_seen"
    private const val ON = "family_news_on"

    fun on(ctx: Context) = Settings.prefs(ctx).getBoolean(ON, true)
    fun setOn(ctx: Context, v: Boolean) { Settings.prefs(ctx).edit().putBoolean(ON, v).apply(); schedule(ctx) }

    fun schedule(ctx: Context) {
        try {
            val wm = WorkManager.getInstance(ctx.applicationContext)
            if (!on(ctx)) { wm.cancelUniqueWork(WORK); return }
            val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<FamilyNewsWorker>(15, TimeUnit.MINUTES).setConstraints(net).build())
        } catch (_: Throwable) { }
    }

    /** Looks once. Background thread only. */
    fun check(ctx: Context) {
        if (!on(ctx) || !Circle.joined(ctx) || !Account.signedIn(ctx)) return
        val p = Settings.prefs(ctx)
        val seen = p.getString(SEEN, null)
        if (seen == null) { // first time: start from now (no flood of old moments)
            p.edit().putString(SEEN, java.time.Instant.now().toString()).apply(); return
        }
        val fresh = Circle.newsSince(ctx, seen)
        if (fresh.isEmpty()) return
        p.edit().putString(SEEN, fresh.maxOf { it.createdIso }).apply()
        val others = fresh.filter { it.authorId != Account.id(ctx) }
        if (others.isEmpty()) return
        notify(ctx, others)
    }

    private fun notify(ctx: Context, news: List<Circle.News>) {
        try {
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(NotificationChannel(CHANNEL, L.t("רגעים חדשים מהמשפחה", "New family moments"), NotificationManager.IMPORTANCE_DEFAULT))
            val (title, body) = Wording.familyNews(news.map { it.author to it.title })
            val open = PendingIntent.getActivity(ctx, 7301,
                Intent(ctx, CircleActivity::class.java).putExtra("post_id", news.first().id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            nm.notify(7301, NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle(title)
                .setContentText(body)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build())
        } catch (_: Exception) { } // notifications not allowed: nothing to do
    }
}

class FamilyNewsWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        try { FamilyNews.check(applicationContext) } catch (_: Throwable) { }
        return Result.success()
    }
}
