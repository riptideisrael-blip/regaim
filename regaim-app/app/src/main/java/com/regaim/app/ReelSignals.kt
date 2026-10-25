package com.regaim.app

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import org.json.JSONObject
import java.io.File
import kotlin.math.max

/**
 * What the editor knows about each moment, measured on the phone (nothing is uploaded):
 * faces and smiles (ML Kit, on the moment's own frame), sharpness, a picture fingerprint for duplicates,
 * screenshots, what the detectors heard and saw, and what the family marked. Cached per moment.
 */
object ReelSignals {
    private const val VERSION = 1
    private fun file(ctx: Context) = File(ctx.filesDir, "reel_signals.json")
    private val lock = Any()

    private val LAUGH = setOf("Laughter", "Baby laughter", "Giggle", "Belly laugh", "Chuckle, chortle", "Snicker")

    /** Background thread only. [progress] gets 0..100. */
    fun of(ctx: Context, list: List<Memory>, progress: (Int) -> Unit = {}): List<ReelInput> {
        // An unreadable cache is moved aside (not overwritten) and everything is simply measured again.
        val cache = synchronized(lock) { ReelCache.load(file(ctx)) }
        val fresh = JSONObject()
        val detector = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .setMinFaceSize(0.08f)
                .build()
        )
        val confident = Facts.confident(ctx)
        val out = ArrayList<ReelInput>()
        try {
            list.forEachIndexed { k, m ->
                progress(100 * k / max(1, list.size))
                val key = "${m.id}_${m.uri.hashCode()}_$VERSION"
                // Only real measurements are kept; a failed look is tried again next time.
                val seen = cache.optJSONObject(key) ?: look(ctx, m, detector).also { if (ReelCache.measured(it)) fresh.put(key, it) }
                out.add(input(ctx, m, seen, m.id in confident))
            }
        } finally {
            try { detector.close() } catch (_: Exception) { }
            if (fresh.length() > 0) synchronized(lock) {
                // Merged with what's on disk now (another run may have added to it), without moments that are gone.
                val now = ReelCache.load(file(ctx))
                val merged = ReelCache.merge(now, fresh, MemoryStore.all(ctx).map { it.id }.toSet())
                if (!SafeFiles.write(file(ctx), merged.toString())) android.util.Log.w("Moments", "reel_signals.json not saved (will measure again)")
            }
        }
        progress(100)
        return out
    }

    /** One look at the moment's own frame: faces, smile, sharpness, fingerprint, shape. */
    private fun look(ctx: Context, m: Memory, detector: com.google.mlkit.vision.face.FaceDetector): JSONObject {
        // "ok": false = couldn't be measured (not "no faces"): the editor treats it as unknown, and it isn't cached.
        val o = JSONObject().put("ok", false).put("faces", 0).put("smile", 0.0).put("sharp", 0.6).put("look", 0L).put("w", 0).put("h", 0)
        val bmp = frame(ctx, m) ?: return o.put("why", "frame")
        try {
            val faces = try { Tasks.await(detector.process(InputImage.fromBitmap(bmp, 0))) } catch (_: Exception) { return o.put("why", "faces") }
            val area = (bmp.width * bmp.height).toFloat()
            val big = faces.filter { it.boundingBox.width() * it.boundingBox.height() / area >= 0.012f }
            o.put("faces", big.size)
            o.put("smile", (big.maxOfOrNull { it.smilingProbability ?: 0f } ?: 0f).toDouble())
            o.put("sharp", sharpness(bmp).toDouble())
            o.put("look", CaptureFilter.dHash(CaptureFilter.lumaGrid(bmp)))
            o.put("w", bmp.width).put("h", bmp.height)
            o.put("ok", true)
        } finally { bmp.recycle() }
        return o
    }

    private fun frame(ctx: Context, m: Memory): Bitmap? {
        if (!m.isVideo) return MediaImages.decode(ctx, m.uri, 480)
        val mmr = MediaMetadataRetriever()
        return try {
            if (m.uri.startsWith("content://")) mmr.setDataSource(ctx, Uri.parse(m.uri)) else mmr.setDataSource(m.uri)
            val raw = mmr.getFrameAtTime(m.highlight * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
            val s = 480f / max(raw.width, raw.height)
            if (s < 1f) Bitmap.createScaledBitmap(raw, (raw.width * s).toInt().coerceAtLeast(1), (raw.height * s).toInt().coerceAtLeast(1), true).also { if (it !== raw) raw.recycle() } else raw
        } catch (_: Exception) { null } finally { try { mmr.release() } catch (_: Exception) { } }
    }

    /** Edge energy (Laplacian variance) of a small grey copy: 0 = a blur, 1 = sharp. */
    internal fun sharpness(src: Bitmap): Float {
        val w = 160
        val h = max(1, (160f * src.height / src.width).toInt())
        val small = Bitmap.createScaledBitmap(src, w, h, true)
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        if (small !== src) small.recycle()
        val g = FloatArray(px.size) { val c = px[it]; 0.299f * ((c shr 16) and 0xff) + 0.587f * ((c shr 8) and 0xff) + 0.114f * (c and 0xff) }
        var sum = 0.0
        var sq = 0.0
        var n = 0
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            val l = 4 * g[i] - g[i - 1] - g[i + 1] - g[i - w] - g[i + w]
            sum += l; sq += l * l; n++
        }
        if (n == 0) return 0.5f
        val v = sq / n - (sum / n) * (sum / n)
        return MomentFusion.smooth(20f, 160f, v.toFloat())
    }

    private fun input(ctx: Context, m: Memory, o: JSONObject, confident: Boolean): ReelInput {
        val det = Detections.get(ctx, m.id)
        val types = det?.optJSONArray("types")
        fun type(k: String): Float {
            if (types == null) return 0f
            for (i in 0 until types.length()) { val t = types.optJSONObject(i) ?: continue; if (t.optString("k") == k) return t.optDouble("s").toFloat() }
            return 0f
        }
        // Detector times are in the original video; a cut clip starts at clip_start, a video kept whole at 0.
        val off = if (det != null && kotlin.math.abs(m.durationMs - (det.optLong("clip_end") - det.optLong("clip_start"))) < 1_500L) det.optLong("clip_start") else 0L
        val trigger = (Facts.get(ctx, m.id)?.optDouble("trigger_score", -1.0) ?: -1.0).toFloat()
        val strength = if (trigger >= 0f) (0.5f + trigger).coerceAtMost(1f) else 0.7f
        val text = (m.tags + " " + m.category + " " + m.title + " " + m.description).lowercase()
        val laughLabel = m.label in LAUGH
        val laugh = maxOf(type("laughter"), if (laughLabel) 0.75f * strength else 0f, if (m.category == "צחוק") 0.5f else 0f)
        val hug = maxOf(type("affection"), if (m.label == "Hug") 0.8f else 0f, if ("חיבוק" in text || "hug" in text) 0.6f else 0f)
        val motion = maxOf(type("play"), if (m.label == "Approach") 0.7f else 0f, if (m.category == "משחק") 0.5f else 0f)
        val voice = maxOf(laugh, type("celebration"), if (m.label in Labels.SINGING || m.label.startsWith("״")) 0.6f else 0f)
        val name = (m.name + " " + m.uri).lowercase()
        val w = o.optInt("w"); val h = o.optInt("h")
        val screenAspect = w > 0 && h > 0 && (h.toFloat() / w in 1.95f..2.4f)
        val screenshot = !m.isVideo && ("screenshot" in name || "צילום מסך" in name || "screen_shot" in name ||
            (screenAspect && o.optInt("faces") == 0 && m.origin == "import" && "dcim" !in name && "camera" !in name))
        return ReelInput(
            id = m.id, isVideo = m.isVideo, durationMs = m.durationMs, time = m.time,
            peakMs = if (det != null) det.optLong("peak", -1L) - off else if (m.highlightMs >= 0) m.highlightMs else -1L,
            eventStartMs = det?.let { it.optLong("start", -1L) - off } ?: -1L,
            eventEndMs = det?.let { it.optLong("end", -1L) - off } ?: -1L,
            faces = o.optInt("faces"), named = Fmt.names(m).size, smile = maxOf(o.optDouble("smile").toFloat(), if (m.label == "Smile") 0.8f else 0f, type("joy")),
            laugh = laugh, hug = hug, motion = motion, voice = voice,
            favorite = m.favorite, milestone = m.milestone.isNotEmpty(), chosen = m.chosen, approved = m.approved,
            aiScore = if (confident) m.score else 0,
            special = listOf("יום הולדת", "מסיבה", "עוגה", "חג", "birthday", "party", "cake").any { it in text } || type("celebration") >= 0.45f,
            outdoor = m.category == "בחוץ" || listOf("בחוץ", "פארק", "גינה", "ים", "טיול", "outdoor", "park", "beach").any { it in text },
            sharpness = o.optDouble("sharp", 0.6).toFloat(), screenshot = screenshot, measured = ReelCache.measured(o),
            look = if (m.look != 0L) m.look else o.optLong("look"),
        )
    }
}
