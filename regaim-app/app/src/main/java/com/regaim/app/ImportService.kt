package com.regaim.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

/**
 * Copies photos and videos into Moments (from Google Photos, or many at once from the gallery) as a foreground
 * service with a progress notification, so the copying goes on when the parent leaves the screen or the app.
 * Before, it ran inside the screen, and Android froze it as soon as the parent left.
 */
class ImportService : Service() {
    /** One copying job. [run] adds the items one by one and reports each step. */
    abstract class Job(val total: Int) {
        /** Adds item [i]; returns the new moment's id, [SKIPPED] if it was already in Moments, or null if it couldn't be added. */
        abstract fun add(ctx: Context, i: Int): String?
        open fun finished(ctx: Context) {}
    }

    /** What the screens show while copying. */
    object State {
        @Volatile var running = false
        @Volatile var done = 0
        @Volatile var total = 0
        @Volatile var added = 0
        @Volatile var skipped = 0
        @Volatile var lastId: String? = null
        /** Counts finished jobs, so a screen knows "its" job ended. */
        @Volatile var finishedJobs = 0
    }

    companion object {
        /** [Job.add]'s answer for an item that was already copied before (not a failure). */
        const val SKIPPED = ""
        private const val CHANNEL = "imports"
        private const val ONGOING = 4501
        private const val NOTICE = 4502
        private val queue = Executors.newSingleThreadExecutor()
        @Volatile private var job: Job? = null

        /** Starts copying. False if another copy is still running (one at a time). */
        @Synchronized
        fun start(ctx: Context, j: Job): Boolean {
            if (State.running) return false
            job = j
            State.running = true
            State.done = 0
            State.total = j.total
            State.added = 0
            State.skipped = 0
            State.lastId = null
            val app = ctx.applicationContext
            try { ContextCompat.startForegroundService(app, Intent(app, ImportService::class.java)) } catch (_: Exception) { }
            queue.execute { work(app, j) }
            return true
        }

        private fun work(app: Context, j: Job) {
            val wake = try {
                (app.getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "moments:import").apply {
                    setReferenceCounted(false); acquire(3 * 60 * 60_000L)
                }
            } catch (_: Exception) { null }
            try {
                for (i in 0 until j.total) {
                    val id = try { j.add(app, i) } catch (_: Exception) { null }
                    State.done = i + 1
                    when (id) {
                        null -> {}
                        SKIPPED -> State.skipped++
                        else -> { State.added++; State.lastId = id }
                    }
                    if (i % 3 == 0 || i == j.total - 1) notifyProgress(app)
                }
                try { j.finished(app) } catch (_: Exception) { }
            } finally {
                try { wake?.takeIf { it.isHeld }?.release() } catch (_: Exception) { }
                job = null
                State.running = false
                State.finishedJobs++
                notifyDone(app)
                try { app.stopService(Intent(app, ImportService::class.java)) } catch (_: Exception) { }
                // Find who's in the new moments (on the phone).
                try { FaceScan.kick(app) } catch (_: Throwable) { }
            }
        }

        private fun nm(ctx: Context): NotificationManager? = ctx.getSystemService(NotificationManager::class.java)?.also {
            it.createNotificationChannel(NotificationChannel(CHANNEL, L.t("הוספת תמונות וסרטונים", "Adding photos and videos"), NotificationManager.IMPORTANCE_LOW))
        }

        private fun open(ctx: Context): PendingIntent = PendingIntent.getActivity(
            ctx, 45, Intent(ctx, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        private fun progress(ctx: Context) = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(L.t("מוסיפים רגעים", "Adding moments"))
            .setContentText(L.t("${State.done} מתוך ${State.total}. אפשר לצאת מהאפליקציה, נמשיך ברקע.", "${State.done} of ${State.total}. You can leave the app; we'll keep going."))
            .setProgress(State.total.coerceAtLeast(1), State.done, State.total == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open(ctx))
            .build()

        private fun notifyProgress(ctx: Context) { try { nm(ctx)?.notify(ONGOING, progress(ctx)) } catch (_: Exception) { } }

        private fun notifyDone(ctx: Context) {
            try {
                val failed = State.total - State.added - State.skipped
                val text = if (State.added == 0 && State.skipped > 0 && failed == 0) L.t("כל מה שבחרתם כבר נמצא ב-Moments", "Everything you picked is already in Moments")
                else if (State.added == 0) L.t("לא הצלחנו להוסיף את הפריטים. נסו שוב.", "Couldn't add the items. Please try again.")
                else L.t("נוספו ${State.added} רגעים ✓", "${State.added} moments added ✓") + if (failed > 0) L.t(" ($failed לא נוספו)", " ($failed couldn't be added)") else ""
                nm(ctx)?.notify(NOTICE, NotificationCompat.Builder(ctx, CHANNEL)
                    .setSmallIcon(R.drawable.ic_notif)
                    .setContentTitle(L.t("ההוספה הסתיימה", "Done adding"))
                    .setContentText(text)
                    .setAutoCancel(true)
                    .setContentIntent(open(ctx))
                    .build())
            } catch (_: Exception) { }
        }
    }

    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Always go to the foreground first (Android requires it after a start request), then stop if already done.
        if (!started) {
            started = true
            val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
            try { ServiceCompat.startForeground(this, ONGOING, progress(this), type) } catch (_: Exception) { stopSelf(); return START_NOT_STICKY }
        }
        if (!State.running) stopSelf()
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
        try { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) } catch (_: Exception) { }
        super.onDestroy()
    }
}

/** Copies the items picked in Google Photos into the gallery and into Moments. Already-copied items are skipped. */
class GooglePhotosJob(private val token: String, private val sessionId: String, private val items: List<GooglePhotos.Item>) : ImportService.Job(items.size) {
    private var seen: HashSet<String>? = null
    private var since = 0

    private fun seen(ctx: Context) = seen ?: HashSet(ctx.getSharedPreferences("gphotos", Context.MODE_PRIVATE).getStringSet("imported", emptySet()) ?: emptySet()).also { seen = it }

    override fun add(ctx: Context, i: Int): String? {
        val it0 = items[i]
        val s = seen(ctx)
        if (it0.id in s) return ImportService.SKIPPED
        val uri = GooglePhotos.download(ctx, token, it0)
        val id = MediaImporter.add(ctx, uri, knownTime = it0.createdAt).id
        s.add(it0.id)
        if (++since % 10 == 0) save(ctx)
        return id
    }

    private fun save(ctx: Context) =
        ctx.getSharedPreferences("gphotos", Context.MODE_PRIVATE).edit().putStringSet("imported", HashSet(seen(ctx))).apply()

    override fun finished(ctx: Context) {
        save(ctx)
        GooglePhotos.deleteSession(token, sessionId)
    }
}

/** Many photos and videos picked from the phone's gallery. */
class GalleryJob(private val uris: List<Uri>) : ImportService.Job(uris.size) {
    override fun add(ctx: Context, i: Int): String? = MediaImporter.add(ctx, uris[i]).id
}
