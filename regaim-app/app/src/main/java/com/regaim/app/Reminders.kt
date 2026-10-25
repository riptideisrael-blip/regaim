package com.regaim.app

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.app.NotificationCompat
import java.util.Calendar

/**
 * Gentle reminders, scheduled on the phone (no server):
 *  - "היום לפני שנה" every morning, only when there really is a moment from this day in an earlier year (or month).
 *  - The Friday digest for the grandparents, every Friday morning, when the week has moments to send.
 * Alarms are inexact (no special permission) and are set again on every app start and after a restart of the phone.
 */
object Reminders {
    const val CHANNEL = "reminders"
    const val ACTION_ON_THIS_DAY = "com.regaim.app.ON_THIS_DAY"
    const val ACTION_DIGEST = "com.regaim.app.FRIDAY_DIGEST"
    private const val NOTIF_ON_THIS_DAY = 7101
    private const val NOTIF_DIGEST = 7102

    const val KEY_ON_THIS_DAY = "on_this_day_on"
    const val KEY_DIGEST = "friday_digest_on"
    const val KEY_DIGEST_PHONE = "digest_phone"

    private fun p(ctx: Context) = Settings.prefs(ctx)
    fun onThisDayOn(ctx: Context) = p(ctx).getBoolean(KEY_ON_THIS_DAY, true)
    fun digestOn(ctx: Context) = p(ctx).getBoolean(KEY_DIGEST, true)

    fun setOnThisDay(ctx: Context, on: Boolean) { p(ctx).edit().putBoolean(KEY_ON_THIS_DAY, on).apply(); schedule(ctx) }
    fun setDigest(ctx: Context, on: Boolean) { p(ctx).edit().putBoolean(KEY_DIGEST, on).apply(); schedule(ctx) }

    // ---------- scheduling ----------

    /**
     * Sets both alarms. A time already planned and still ahead is kept (opening the app must not push today's
     * reminder to tomorrow); [fired] = the alarm for that action just went off, so plan the next one.
     */
    fun schedule(ctx: Context, fired: String? = null) {
        try {
            val am = ctx.getSystemService(AlarmManager::class.java) ?: return
            fun plan(action: String, code: Int, on: Boolean, fresh: () -> Long) {
                val pi = pending(ctx, action, code)
                val key = "alarm_at_$code"
                if (!on) { am.cancel(pi); p(ctx).edit().remove(key).apply(); return }
                val now = System.currentTimeMillis()
                val planned = p(ctx).getLong(key, 0L)
                val at = if (fired != action && planned > now) planned else fresh()
                p(ctx).edit().putLong(key, at).apply()
                am.setWindow(AlarmManager.RTC_WAKEUP, at, 30 * 60_000L, pi)
            }
            plan(ACTION_ON_THIS_DAY, 1, onThisDayOn(ctx)) { next(9, 30, null) }
            plan(ACTION_DIGEST, 2, digestOn(ctx)) { next(10, 0, Calendar.FRIDAY) }
        } catch (_: Exception) { }
    }

    private fun pending(ctx: Context, action: String, code: Int): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, code, Intent(ctx, ReminderReceiver::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /** The next time it is [hour]:[minute] (on [weekday], if given), after now. */
    internal fun next(hour: Int, minute: Int, weekday: Int?, now: Long = System.currentTimeMillis()): Long {
        val c = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        if (weekday != null) while (c.get(Calendar.DAY_OF_WEEK) != weekday || c.timeInMillis <= now) c.add(Calendar.DAY_OF_MONTH, 1)
        else if (c.timeInMillis <= now) c.add(Calendar.DAY_OF_MONTH, 1)
        return c.timeInMillis
    }

    // ---------- "on this day" ----------

    /** A moment from this calendar day in an earlier year, else from exactly a month ago. Null when there is none. */
    fun onThisDay(all: List<Memory>, now: Long = System.currentTimeMillis()): Pair<String, Memory>? {
        val today = Calendar.getInstance().apply { timeInMillis = now }
        fun cal(t: Long) = Calendar.getInstance().apply { timeInMillis = t }
        val real = all.filter { it.timeSource != "imported" && it.origin != "film" }
        val years = real.filter {
            val c = cal(it.time)
            c.get(Calendar.YEAR) < today.get(Calendar.YEAR) &&
                c.get(Calendar.MONTH) == today.get(Calendar.MONTH) && c.get(Calendar.DAY_OF_MONTH) == today.get(Calendar.DAY_OF_MONTH)
        }
        ReelMaker.best(years).firstOrNull()?.let { m ->
            val n = today.get(Calendar.YEAR) - cal(m.time).get(Calendar.YEAR)
            return (if (n == 1) L.t("היום לפני שנה", "One year ago today") else L.t("היום לפני $n שנים", "$n years ago today")) to m
        }
        val monthAgo = Calendar.getInstance().apply { timeInMillis = now; add(Calendar.MONTH, -1) }
        val month = real.filter {
            val c = cal(it.time)
            c.get(Calendar.YEAR) == monthAgo.get(Calendar.YEAR) && c.get(Calendar.MONTH) == monthAgo.get(Calendar.MONTH) &&
                c.get(Calendar.DAY_OF_MONTH) == today.get(Calendar.DAY_OF_MONTH)
        }
        ReelMaker.best(month).firstOrNull()?.let { return L.t("היום לפני חודש", "One month ago today") to it }
        return null
    }

    internal fun notifyOnThisDay(ctx: Context) {
        if (!onThisDayOn(ctx)) return
        val day = Fmt.monthKey(System.currentTimeMillis()) + "-" + Calendar.getInstance().get(Calendar.DAY_OF_MONTH)
        if (p(ctx).getString("on_this_day_last", "") == day) return
        val (label, m) = onThisDay(MemoryStore.kept(ctx)) ?: return
        p(ctx).edit().putString("on_this_day_last", day).apply()
        val open = PendingIntent.getActivity(
            ctx, 11, Intent(ctx, MomentActivity::class.java).putExtra("id", m.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val people = Fmt.names(m).take(3).joinToString(", ")
        val text = listOf(Fmt.title(m), people).filter { it.isNotEmpty() }.joinToString(" · ")
        post(ctx, NOTIF_ON_THIS_DAY, "❤️ $label", text, cover(ctx, m), open)
    }

    // ---------- Friday digest ----------

    internal fun notifyDigest(ctx: Context) {
        if (!digestOn(ctx)) return
        if (Calendar.getInstance().get(Calendar.DAY_OF_WEEK) != Calendar.FRIDAY) return
        val week = Digest.moments(ctx)
        if (week.size < 2) return
        val open = PendingIntent.getActivity(
            ctx, 12, Intent(ctx, DigestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val best = PeriodVideo.pick(week).firstOrNull()
        post(
            ctx, NOTIF_DIGEST,
            L.t("הסיכום השבועי לסבא וסבתא מוכן 💛", "This week's digest for the grandparents is ready 💛"),
            L.t("${week.size} רגעים מהשבוע. לחצו כדי לשלוח בוואטסאפ.", "${week.size} moments this week. Tap to send on WhatsApp."),
            best?.let { cover(ctx, it) }, open,
        )
    }

    // ---------- helpers ----------

    private fun cover(ctx: Context, m: Memory): Bitmap? = try { Covers.get(ctx, m) } catch (_: Throwable) { null }

    private fun post(ctx: Context, id: Int, title: String, text: String, picture: Bitmap?, open: PendingIntent) {
        try {
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(NotificationChannel(CHANNEL, L.t("זיכרונות ותזכורות", "Memories & reminders"), NotificationManager.IMPORTANCE_DEFAULT))
            val b = NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(open)
                .setAutoCancel(true)
            if (picture != null) b.setLargeIcon(picture).setStyle(NotificationCompat.BigPictureStyle().bigPicture(picture).bigLargeIcon(null as Bitmap?))
            else b.setStyle(NotificationCompat.BigTextStyle().bigText(text))
            nm.notify(id, b.build())
        } catch (_: Exception) { } // no notification permission: nothing to do
    }
}

/** Wakes up for the reminders (and after the phone restarts, to set them again). */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ctx = context.applicationContext
        val action = intent.action ?: return
        val done = goAsync()
        Thread {
            try {
                when (action) {
                    Reminders.ACTION_ON_THIS_DAY -> Reminders.notifyOnThisDay(ctx)
                    Reminders.ACTION_DIGEST -> Reminders.notifyDigest(ctx)
                }
            } catch (_: Throwable) {
            } finally {
                Reminders.schedule(ctx, fired = action)
                done.finish()
            }
        }.start()
    }
}
