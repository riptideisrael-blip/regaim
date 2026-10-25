package com.regaim.app

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import org.json.JSONArray
import java.io.File
import java.util.concurrent.Executors

/**
 * Looks at every new animation of the child's film before the child sees it: a frame every half second, the faces
 * in it counted. More faces than belong in the scene (a third child, a second elf, a stranger) for a moment is
 * flagged with its time, so the parent can cut it (free) or animate the scene again. A drawn face isn't always found
 * (a turned head, a far one), so this finds most of these mistakes, not all: the parent's own look stays the check.
 */
object SceneScan {
    /** How many people belong in the child's scenes: the child and Pitzki. */
    const val EXPECTED = 2
    private const val STEP_MS = 500L

    fun flagged(samples: List<Pair<Long, Int>>, expected: Int) = SceneFlags.flagged(samples, expected)
    fun time(ms: Long) = SceneFlags.time(ms)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("scene_scan", Context.MODE_PRIVATE)
    private fun key(f: File) = "scan_" + f.absolutePath + "_" + f.lastModified()

    /** The flagged times of [clip], or null when it wasn't looked at yet. */
    fun result(ctx: Context, clip: File): List<LongRange>? {
        val raw = prefs(ctx).getString(key(clip), null) ?: return null
        return try {
            val a = JSONArray(raw)
            (0 until a.length()).map { a.getJSONArray(it).let { r -> r.getLong(0)..r.getLong(1) } }
        } catch (_: Exception) { null }
    }

    /** Flagged times not cut out already. */
    fun open(ctx: Context, clip: File): List<LongRange> {
        val cuts = PitzkiStore.cuts(ctx, clip)
        return result(ctx, clip).orEmpty().filter { r -> cuts.none { c -> r.first >= c.first && r.last <= c.second } }
    }

    private val worker = Executors.newSingleThreadExecutor()
    private val queued = HashSet<String>()

    /** Looks at [clip] in the background (once per version of it); [done] on the main thread when there's a result. */
    fun scan(ctx: Context, clip: File, expected: Int = EXPECTED, done: () -> Unit) {
        val app = ctx.applicationContext
        val k = key(clip)
        synchronized(queued) { if (!queued.add(k)) return }
        worker.execute {
            val samples = ArrayList<Pair<Long, Int>>()
            val r = MediaMetadataRetriever()
            val detector = FaceDetection.getClient(FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE).setMinFaceSize(0.08f).build())
            try {
                r.setDataSource(clip.absolutePath)
                val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                var t = 0L
                while (t < dur) {
                    val frame = r.getFrameAtTime(t * 1000, MediaMetadataRetriever.OPTION_CLOSEST)
                    if (frame != null) {
                        val small = if (frame.width > 960) Bitmap.createScaledBitmap(frame, 960, frame.height * 960 / frame.width, true) else frame
                        val n = try { Tasks.await(detector.process(InputImage.fromBitmap(small, 0))).size } catch (_: Exception) { 0 }
                        samples.add(t to n)
                        if (small !== frame) small.recycle()
                        frame.recycle()
                    }
                    t += STEP_MS
                }
                val a = JSONArray()
                flagged(samples, expected).forEach { a.put(JSONArray().put(it.first).put(it.last + STEP_MS)) }
                prefs(app).edit().putString(k, a.toString()).apply()
                android.os.Handler(android.os.Looper.getMainLooper()).post(done)
            } catch (_: Exception) {
            } finally {
                try { r.release() } catch (_: Exception) {}
                try { detector.close() } catch (_: Exception) {}
            }
        }
    }
}
