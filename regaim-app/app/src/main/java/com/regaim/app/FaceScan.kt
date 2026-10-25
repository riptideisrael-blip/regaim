package com.regaim.app

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Finds and names faces in every new moment automatically (photos and videos added from the gallery or
 * Google Photos, and captured moments), in the background, a few at a time, until none is left. Faces of
 * people the family already named are tagged right away; new faces wait in "Who's this?". On the phone only.
 *
 * Two ways it runs: right away on a background thread while the app is open, and through Android's WorkManager,
 * which finishes the job later (e.g. a big import from Google Photos) even if Android closed the app meanwhile.
 * Nothing here leaves the phone: no upload, no AI call.
 */
object FaceScan {
    private val running = AtomicBoolean(false)
    private const val NOW = "face_scan_now"
    private const val DAILY = "face_scan_daily"

    fun kick(ctx: Context) {
        val app = ctx.applicationContext
        if (!PeopleStore.peopleEnabled(app)) return
        later(app)
        // While recording, the camera, the encoder and the laugh model already work the phone hard (heat): faces
        // are found after the recording stops (CaptureService kicks again then), or by the backup run.
        if (Status.running) return
        if (!running.compareAndSet(false, true)) return
        Thread {
            try {
                drain(app)
            } finally {
                running.set(false)
            }
        }.apply { priority = Thread.MIN_PRIORITY; isDaemon = true }.start()
    }

    /** Scans until nothing is left. Returns true if everything readable was scanned. */
    internal fun drain(app: Context, maxRounds: Int = 200): Boolean {
        var rounds = 0
        while (rounds++ < maxRounds) {
            val n = try { PeopleStore.scanPending(app, 6) } catch (_: Throwable) { 0 }
            if (n == 0) break
        }
        try { Pets.scanPending(app) } catch (_: Throwable) { }
        return rounds <= maxRounds
    }

    /** A backup run through WorkManager, in case Android closes the app before the scan ends. Never throws. */
    private fun later(app: Context) {
        try {
            val wm = WorkManager.getInstance(app)
            val ok = Constraints.Builder().setRequiresBatteryNotLow(true).build()
            wm.enqueueUniqueWork(
                NOW, ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<FaceScanWorker>().setConstraints(ok).setInitialDelay(2, TimeUnit.MINUTES).build(),
            )
        } catch (_: Throwable) { }
    }

    /** Once a day, while charging: catches anything missed (photos added while the app was closed). */
    fun schedule(ctx: Context) {
        try {
            val app = ctx.applicationContext
            val ok = Constraints.Builder().setRequiresCharging(true).setRequiresBatteryNotLow(true).build()
            WorkManager.getInstance(app).enqueueUniquePeriodicWork(
                DAILY, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<FaceScanWorker>(1, TimeUnit.DAYS).setConstraints(ok).build(),
            )
        } catch (_: Throwable) { }
    }
}

/** The WorkManager side of [FaceScan]: same scan, on the phone only. */
class FaceScanWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        val app = applicationContext
        if (!PeopleStore.peopleEnabled(app)) return Result.success()
        if (Status.running) return Result.retry() // not while recording (heat): later
        // Stopped by Android halfway (battery got low...): the next run carries on where this one stopped.
        return if (FaceScan.drain(app) || isStopped) Result.success() else Result.retry()
    }
}
