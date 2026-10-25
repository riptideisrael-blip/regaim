package com.regaim.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Keeps Pitzki's film being made while the parent leaves the app or the screen locks: a foreground service with a
 * quiet notification ("Making Ori's film… 3 of 4"), so Android doesn't cut the network or stop the app (that's what
 * "Software caused connection abort" was). The work itself stays in PitzkiStore; this only keeps the app alive while
 * it runs, and tells the parent when it's ready.
 */
class PitzkiService : Service() {
    companion object {
        private const val CHANNEL = "pitzki"
        private const val ONGOING = 4501
        private const val NOTICE = 4502
        @Volatile private var running = false
        /** Pitzki's screen is open: no "ready" notification needed (the screen shows it). */
        @Volatile var screenOpen = false

        /** Called when Pitzki's work starts. Never throws. */
        fun start(ctx: Context) {
            try {
                if (!running) ContextCompat.startForegroundService(ctx, Intent(ctx, PitzkiService::class.java))
            } catch (_: Exception) { } // not allowed right now: the work still goes on while the app is open
        }

        fun clearNotice(ctx: Context) { try { nm(ctx)?.cancel(NOTICE) } catch (_: Exception) { } }

        private fun nm(ctx: Context): NotificationManager? = ctx.getSystemService(NotificationManager::class.java)?.also {
            it.createNotificationChannel(NotificationChannel(CHANNEL, L.t("היער של פיצקי", "Pitzki's forest"), NotificationManager.IMPORTANCE_LOW))
        }

        private fun open(ctx: Context): PendingIntent = PendingIntent.getActivity(
            ctx, 45, Intent(ctx, PitzkiActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        private fun ongoing(ctx: Context, d: PitzkiStore.Drawing?): android.app.Notification {
            val anim = d?.phase == "anim"
            val text = when {
                d == null -> ""
                d.note.isNotEmpty() -> d.note
                d.phase == "character" -> L.t("מציירים דמות מהתמונות…", "Drawing a character from the photos…")
                anim -> L.t("קמים לחיים: ${d.animated} מתוך ${d.total}", "Coming to life: ${d.animated} of ${d.total}")
                else -> L.t("מציירים: ${d.done} מתוך ${d.total}", "Drawing: ${d.done} of ${d.total}")
            }
            val eta = d?.eta()
            val pct = eta?.first ?: 0
            val mins = eta?.let { (it.second + 30) / 60 } ?: 0
            val head = if (eta == null) "" else "$pct% · " + (if (mins <= 1) L.t("עוד בערך דקה", "about a minute left") else L.t("עוד בערך $mins דקות", "about $mins min left")) + " · "
            return NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle(L.t("מכינים את ${d?.title.orEmpty()} ✨", "Making ${d?.title.orEmpty()} ✨"))
                .setContentText(head + text)
                .setProgress(100, pct, d == null)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(open(ctx))
                .build()
        }

        private fun notice(ctx: Context, d: PitzkiStore.Drawing) {
            val failed = d.failed.isNotEmpty()
            val title = when {
                failed -> L.t("${d.title}: עצרנו באמצע", "${d.title}: stopped in the middle")
                d.waitingApproval -> L.t("הסצנה הראשונה מוכנה 👀", "The first scene is ready 👀")
                else -> L.t("${d.title} מוכן! 🎬", "${d.title} is ready! 🎬")
            }
            val text = when {
                failed -> d.failed
                d.waitingApproval -> L.t("פתחו ואשרו שהילד דומה, ואז נמשיך לשאר הסרט", "Open and confirm the likeness, then we'll make the rest of the film")
                else -> L.t("פתחו כדי לצאת לטיול עם פיצקי", "Open to go for a walk with Pitzki")
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
    private val main = Handler(Looper.getMainLooper())
    private var lastShown = ""

    /** Every 2 seconds: the notification follows the work; when it's done, the service ends (and says so). */
    private val tick = object : Runnable {
        override fun run() {
            val d = PitzkiStore.drawing
            if (d == null || d.finished) {
                if (d != null && !screenOpen) notice(this@PitzkiService, d)
                stopSelf()
                return
            }
            val now = "${d.phase}/${d.done}/${d.animated}/${d.total}/${d.note}/${d.eta().first}"
            if (now != lastShown) { lastShown = now; nm(this@PitzkiService)?.notify(ONGOING, ongoing(this@PitzkiService, d)) }
            main.postDelayed(this, 2000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        running = true
        nm(this)
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        try {
            ServiceCompat.startForeground(this, ONGOING, ongoing(this, PitzkiStore.drawing), type)
        } catch (_: Exception) {
            running = false
            stopSelf()
            return START_NOT_STICKY
        }
        if (wake == null) wake = try {
            (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "moments:pitzki").apply {
                setReferenceCounted(false)
                acquire(45 * 60_000L) // drawing + animating, with time to spare for a busy service
            }
        } catch (_: Exception) { null }
        main.removeCallbacks(tick)
        main.post(tick)
        return START_NOT_STICKY
    }

    /** Android 15+: "data sync" in the background has a daily limit; past it the service must stop at once. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) { }
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        main.removeCallbacks(tick)
        try { wake?.takeIf { it.isHeld }?.release() } catch (_: Exception) { }
        wake = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
