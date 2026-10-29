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
 * A notification when the other parent shares something: new moments in the shared gallery (a tap plays them like a
 * story) and new items in the shared family file (a tap opens the item). Android checks in the background about every
 * 15 minutes while there's internet (no push server: the phone asks the family server itself). The first check only
 * notes what's already there, so turning it on doesn't notify about the past. On by default; off in the family circle.
 */
object PartnerNotify {
    private const val WORK = "partner_notify"
    private const val CHANNEL = "partner_shared"
    private const val ON = "partner_notify_on"
    private const val SEEN_POSTS = "partner_notify_posts"
    private const val SEEN_ITEMS = "partner_notify_items"
    private const val READY = "partner_notify_ready"

    fun on(ctx: Context) = Settings.prefs(ctx).getBoolean(ON, true)
    fun setOn(ctx: Context, v: Boolean) { Settings.prefs(ctx).edit().putBoolean(ON, v).apply(); schedule(ctx) }

    /** Keeps the background check going while a partner is chosen and it's on; never throws. */
    fun schedule(ctx: Context) {
        try {
            val wm = WorkManager.getInstance(ctx.applicationContext)
            if (!on(ctx) || PartnerShare.partner(ctx).isEmpty()) { wm.cancelUniqueWork(WORK); return }
            val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<PartnerNotifyWorker>(15, TimeUnit.MINUTES).setConstraints(net).build())
        } catch (_: Throwable) { }
    }

    /** One check. Background thread only. */
    fun check(ctx: Context) {
        val app = ctx.applicationContext
        if (!on(app) || PartnerShare.partner(app).isEmpty() || !Account.signedIn(app) || !Circle.joined(app)) return
        val p = Settings.prefs(app)
        val first = !p.getBoolean(READY, false)

        // The shared gallery.
        val posts = try { Circle.partnerMoments(app).filter { !it.pitzki } } catch (_: Exception) { null }
        if (posts != null) {
            val seen = ids(app, SEEN_POSTS)
            val fresh = posts.filter { it.id !in seen }
            if (!first && fresh.isNotEmpty()) momentsNote(app, fresh)
            keep(app, SEEN_POSTS, posts.map { it.id })
        }

        // The shared family file (its own switch; nothing unless both parents turned it on).
        if (FamilyFileSync.on(app)) {
            try { FamilyFileSync.syncNow(app) } catch (_: Exception) { }
            val items = FamilyFile.all(app).filter { it.source == FamilyShare.FROM_PARTNER }
            val seen = ids(app, SEEN_ITEMS)
            val fresh = items.filter { it.id !in seen }
            if (!first && fresh.isNotEmpty()) itemsNote(app, fresh)
            keep(app, SEEN_ITEMS, items.map { it.id })
        }
        if (first && posts != null) p.edit().putBoolean(READY, true).apply()
    }

    private fun ids(ctx: Context, key: String): Set<String> =
        Settings.prefs(ctx).getString(key, "").orEmpty().split(',').filter { it.isNotBlank() }.toSet()

    /** What's there now (the list doesn't grow past what the server still has). */
    private fun keep(ctx: Context, key: String, now: List<String>) {
        Settings.prefs(ctx).edit().putString(key, now.takeLast(1000).joinToString(",")).apply()
    }

    private fun channel(nm: NotificationManager) {
        try { nm.createNotificationChannel(NotificationChannel(CHANNEL, L.t("מה ששיתפו איתך", "Shared with you"), NotificationManager.IMPORTANCE_DEFAULT)) } catch (_: Exception) { }
    }

    private fun momentsNote(ctx: Context, fresh: List<Circle.Post>) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        channel(nm)
        val who = fresh.first().author.trim().substringBefore(' ').ifBlank { L.t("ההורה השני", "The other parent") }
        val photos = fresh.count { it.kind == "photo" }
        val videos = fresh.size - photos
        val what = when {
            fresh.size == 1 && photos == 1 -> L.t("תמונה חדשה", "a new photo")
            fresh.size == 1 -> L.t("סרטון חדש", "a new video")
            videos == 0 -> L.t("${fresh.size} תמונות חדשות", "${fresh.size} new photos")
            photos == 0 -> L.t("${fresh.size} סרטונים חדשים", "${fresh.size} new videos")
            else -> L.t("${fresh.size} רגעים חדשים", "${fresh.size} new moments")
        }
        val titles = fresh.mapNotNull { it.title.trim().ifBlank { null } }.take(3)
        // A tap plays them like a story (oldest first); back goes home.
        val open = androidx.core.app.TaskStackBuilder.create(ctx)
            .addNextIntent(Intent(ctx, HomeActivity::class.java))
            .addNextIntent(StoryViewerActivity.partnerIntent(ctx, fresh.sortedBy { it.createdAt }.map { it.id }))
            .getPendingIntent(7600, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val b = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(L.t("$what מ$who", "$who shared $what"))
            .setContentText(titles.joinToString(" · ").ifEmpty { L.t("לחצו לצפייה", "Tap to watch") })
            .setContentIntent(open)
            .setAutoCancel(true)
        // The newest one's picture, when it's on the phone already or comes quickly.
        try {
            val cover = fresh.maxByOrNull { it.createdAt }?.let { p -> p.cover.ifEmpty { if (p.kind == "photo") p.video else "" }.ifEmpty {
                Circle.mediaLink(ctx, p.coverPath.ifEmpty { if (p.kind == "photo") p.videoPath else "" }) } }.orEmpty()
            Circle.image(ctx, cover)?.let { bmp -> b.setLargeIcon(bmp).setStyle(NotificationCompat.BigPictureStyle().bigPicture(bmp)) }
        } catch (_: Exception) { }
        try { nm.notify(7600, b.build()) } catch (_: Exception) { } // notifications not allowed
    }

    private fun itemsNote(ctx: Context, fresh: List<FamilyItems.Item>) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        channel(nm)
        val names = fresh.map { it.title.ifBlank { it.kind.label } }.take(3).joinToString(" · ")
        val stack = androidx.core.app.TaskStackBuilder.create(ctx).addNextIntent(Intent(ctx, FamilyFileActivity::class.java))
        if (fresh.size == 1) stack.addNextIntent(FamilyItemActivity.intent(ctx, fresh[0].id))
        val open = stack.getPendingIntent(7601, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        try {
            nm.notify(7601, NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle(if (fresh.size == 1) L.t("נוסף לתיק המשפחה", "Added to the family file") else L.t("${fresh.size} פריטים נוספו לתיק המשפחה", "${fresh.size} items added to the family file"))
                .setContentText(names)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build())
        } catch (_: Exception) { }
    }
}

class PartnerNotifyWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        try { PartnerNotify.check(applicationContext) } catch (_: Throwable) { }
        return Result.success()
    }
}
