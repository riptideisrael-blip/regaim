package com.regaim.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Keeps a book going while the parent leaves the app or the screen locks: a foreground service with a quiet
 * notification ("Making Noa's book..."), so Android doesn't cut the app's network or stop it. The work itself
 * stays in BookMaker; this only keeps the app alive while there is work, and tells the parent when the book
 * needs them (approve the character, pick a photo, continue) or is ready.
 */
class BookService : Service() {
    companion object {
        private const val CHANNEL = "books"
        private const val ONGOING = 4401
        private const val NOTICE = 4402
        @Volatile private var running = false
        /** The book screen is open: no need for "ready" notifications. */
        @Volatile var screenOpen = false

        private val WORKING = setOf(BookMaker.State.PHOTOS, BookMaker.State.WRITING, BookMaker.State.CHARACTER, BookMaker.State.PAGES, BookMaker.State.ANIMATING)

        /** Called on every change of the book being made. Never throws. */
        fun sync(ctx: Context) {
            try {
                val s = BookMaker.current
                if (s != null && s.state in WORKING) {
                    if (!running) ContextCompat.startForegroundService(ctx, Intent(ctx, BookService::class.java))
                    else nm(ctx)?.notify(ONGOING, ongoing(ctx, s))
                } else {
                    if (running) ctx.stopService(Intent(ctx, BookService::class.java))
                    if (s != null && !screenOpen) notice(ctx, s)
                }
            } catch (_: Exception) { } // not allowed right now: the work still goes on while the app is open
        }

        fun clearNotice(ctx: Context) { try { nm(ctx)?.cancel(NOTICE) } catch (_: Exception) { } }

        private fun nm(ctx: Context): NotificationManager? = ctx.getSystemService(NotificationManager::class.java)?.also {
            it.createNotificationChannel(NotificationChannel(CHANNEL, L.t("ספרים אישיים", "Personal books"), NotificationManager.IMPORTANCE_LOW))
        }

        private fun open(ctx: Context): PendingIntent = PendingIntent.getActivity(
            ctx, 44, Intent(ctx, BookActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        private fun ongoing(ctx: Context, s: BookMaker.Session?) = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(L.t("יוצרים את הספר של ${s?.childName.orEmpty()}", "Making ${s?.childName.orEmpty()}'s book"))
            .setContentText(
                if (s?.waiting == true) L.t("אין חיבור לאינטרנט. ממשיכים כשהחיבור יחזור...", "No internet connection. We'll continue when it's back...")
                else s?.message.orEmpty()
            )
            .setProgress(100, s?.progress ?: 0, s == null || (s.state != BookMaker.State.PAGES && s.state != BookMaker.State.ANIMATING))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open(ctx))
            .build()

        private fun notice(ctx: Context, s: BookMaker.Session) {
            val name = s.childName
            val (title, text) = when (s.state) {
                BookMaker.State.APPROVE -> L.t("הדמות של $name מוכנה", "$name's character is ready") to L.t("פתחו כדי לאשר ולהמשיך את הספר", "Open to approve and continue the book")
                BookMaker.State.DONE -> if (s.book?.film?.isNotEmpty() == true) L.t("הספר המונפש של $name מוכן 🎬", "$name's animated book is ready 🎬") to L.t("פתחו כדי לצפות", "Open to watch it")
                    else L.t("הספר של $name מוכן 📖", "$name's book is ready 📖") to L.t("פתחו כדי לקרוא", "Open to read it")
                BookMaker.State.CAST -> L.t("הסיפור של $name כתוב", "$name's story is written") to L.t("פתחו כדי לבחור מאילו תמונות לצייר את הדמויות", "Open to choose the photos to draw the characters from")
                BookMaker.State.CONFIRM_PHOTOS -> L.t("מצאנו תמונות של $name", "We found photos of $name") to L.t("פתחו כדי לוודא שאלה באמת תמונות של $name", "Open to check these really are $name")
                BookMaker.State.NEED_PHOTO -> L.t("צריך תמונה ברורה של $name", "We need a clear photo of $name") to s.message
                BookMaker.State.NEED_CREDIT -> L.t("הספר של $name ממתין לאישור", "$name's book is waiting for you") to s.message
                BookMaker.State.PAUSED -> L.t("הספר של $name נשמר", "$name's book is saved") to s.message
                BookMaker.State.FAILED -> L.t("הספר של $name לא נוצר", "$name's book wasn't created") to s.message
                else -> return
            }
            val n = NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(open(ctx))
                .build()
            nm(ctx)?.notify(NOTICE, n)
        }
    }

    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        running = true
        nm(this)
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        try {
            ServiceCompat.startForeground(this, ONGOING, ongoing(this, BookMaker.current), type)
        } catch (_: Exception) {
            running = false
            stopSelf()
            return START_NOT_STICKY
        }
        if (wake == null) wake = try {
            (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "moments:book").apply {
                setReferenceCounted(false)
                acquire(40 * 60_000L) // a whole book, with time to spare for a weak connection
            }
        } catch (_: Exception) { null }
        // The work may have ended between the request and now.
        val s = BookMaker.current
        if (s == null || s.state !in WORKING) stopSelf()
        return START_NOT_STICKY
    }

    /**
     * Android 15+ allows "data sync" work in the background up to 6 hours a day; past that the service must stop at
     * once (else the app is closed). The notification goes; the work itself was saved as it went.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) { }
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        try { wake?.takeIf { it.isHeld }?.release() } catch (_: Exception) { }
        wake = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
