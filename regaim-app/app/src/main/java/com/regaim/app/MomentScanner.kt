package com.regaim.app

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Finds the moments in a video, fully on the phone (nothing is uploaded):
 *
 *   sound   → YAMNet every 0.25 s (laughter, cheering, singing, excited child voice, crying, music)
 *             + the signal itself (a burst louder than the rest of the video, a "ha-ha-ha" rhythm)
 *   picture → ~1 frame a second (Google ML Kit: faces, smiles, open mouths, faces close together / facing,
 *             movement, party scenes)
 *   fusion  → [MomentFusion] finds candidate events
 *   refine  → only around those events, 3 frames a second, then fuse again
 *
 * The heavy part (frames) is spent only where something seems to be happening.
 */
object MomentScanner {
    private const val RATE = 16_000
    private const val HOP = RATE / 4
    private const val MAX_FRAMES = 600
    private const val REFINE_EVENTS = 8
    private const val REFINE_STEP_MS = 333L

    class Result(
        val durationMs: Long,
        val candidates: List<Candidate>,
        val hasAudio: Boolean,
        val frames: Int,
        val framesWithFaces: Int,
        val tookMs: Long,
        /** Peak frame of each candidate, by peakMs (a cached JPEG path). */
        val thumbs: Map<Long, String>,
        val error: String? = null,
    )

    /** Background thread only. [progress] 0..100. */
    fun scan(ctx: Context, uri: Uri, cancelled: AtomicBoolean, sensitivity: Float = sensitivity(), progress: (Int) -> Unit = {}): Result {
        val t0 = System.currentTimeMillis()
        val duration = duration(ctx, uri)
        if (duration <= 0L) return Result(0, emptyList(), false, 0, 0, 0, emptyMap(), L.t("לא הצלחנו לקרוא את הסרטון", "Couldn't read this video"))

        // 1) Sound.
        val heard: List<Pair<Long, Heard>>? = try {
            listen(ctx, uri, duration, cancelled) { p -> progress((p * 40).toInt()) }
        } catch (_: Exception) { null }
        progress(40)

        // 2) Picture, coarse.
        val mmr = MediaMetadataRetriever()
        val faces = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .setMinFaceSize(0.05f)
                .build()
        )
        val labeler = ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS)
        val seen = ArrayList<Pair<Long, Seen>>()
        val looks = HashMap<Long, Look>()
        try {
            mmr.setDataSource(ctx, uri)
            val step = max(1_000L, duration / MAX_FRAMES)
            // Short videos: the exact frame. Long ones: the nearest keyframe (much faster).
            val exact = duration <= 120_000L
            var prev: Look? = null
            var t = 0L
            var n = 0
            while (t <= duration && !cancelled.get()) {
                val bmp = frame(mmr, t, exact)
                if (bmp != null) {
                    val look = look(faces, if (n % 2 == 0) labeler else null, bmp)
                    look.ms = t
                    looks[t] = look
                    seen.add(t to look.toSeen(prev, t - (prev?.ms ?: t)))
                    prev = look
                    bmp.recycle()
                }
                n++
                t += step
                progress(40 + (40 * t / duration).toInt().coerceAtMost(40))
            }

            // 3) Fuse loosely, then look closer around every suspect.
            val learned = Detections.learned(ctx)
            val suspects = MomentFusion.analyze(duration, heard, seen, MomentFusion.Config(sensitivity * 0.85f, learned, REFINE_EVENTS))
            val extra = ArrayList<Long>()
            for (c in suspects) {
                var x = (c.startMs - 1_500L).coerceAtLeast(0L)
                val end = (c.endMs + 1_500L).coerceAtMost(duration)
                var count = 0
                while (x <= end && count < 36) {
                    if (seen.none { abs(it.first - x) < 150L } && extra.none { abs(it - x) < 150L }) extra.add(x)
                    x += REFINE_STEP_MS; count++
                }
            }
            if (extra.isNotEmpty()) {
                extra.sort()
                extra.forEachIndexed { i, x ->
                    if (cancelled.get()) return@forEachIndexed
                    val bmp = frame(mmr, x, true) ?: return@forEachIndexed
                    looks[x] = look(faces, null, bmp).also { it.ms = x }
                    bmp.recycle()
                    progress(80 + 15 * (i + 1) / extra.size)
                }
                // Movement is measured against the sample just before, wherever it came from.
                seen.clear()
                var p: Look? = null
                for (x in looks.keys.sorted()) {
                    val l = looks[x]!!
                    seen.add(x to l.toSeen(p, x - (p?.ms ?: x)))
                    p = l
                }
            }

            // 4) The real decision.
            val found = MomentFusion.analyze(duration, heard, seen, MomentFusion.Config(sensitivity, learned))
            val thumbs = HashMap<Long, String>()
            val dir = File(ctx.cacheDir, "scan").apply { mkdirs() }
            for (c in found) {
                val b = frame(mmr, c.peakMs, true) ?: continue
                val f = File(dir, "${uri.toString().hashCode()}_${c.peakMs}.jpg")
                try { FileOutputStream(f).use { b.compress(Bitmap.CompressFormat.JPEG, 85, it) }; thumbs[c.peakMs] = f.absolutePath } catch (_: Exception) { }
                b.recycle()
            }
            progress(100)
            return Result(duration, found, heard != null, looks.size, looks.values.count { it.faces.isNotEmpty() }, System.currentTimeMillis() - t0, thumbs)
        } catch (e: Exception) {
            return Result(duration, emptyList(), heard != null, looks.size, 0, System.currentTimeMillis() - t0, emptyMap(), e.message ?: "error")
        } finally {
            try { mmr.release() } catch (_: Exception) { }
            try { faces.close() } catch (_: Exception) { }
            try { labeler.close() } catch (_: Exception) { }
        }
    }

    /** The user's laugh sensitivity (Settings) as the fusion's strictness: 1 = normal. */
    fun sensitivity(): Float = (Status.threshold / 0.3f).coerceIn(0.7f, 1.4f)

    // ---------- sound ----------

    private fun listen(ctx: Context, uri: Uri, duration: Long, cancelled: AtomicBoolean, progress: (Float) -> Unit): List<Pair<Long, Heard>> {
        val detector = LaughDetector(ctx)
        val need = detector.requiredSamples
        val times = ArrayList<Long>()
        val groups = ArrayList<LaughDetector.Groups>()
        val db = ArrayList<Float>()
        val rhythm = ArrayList<Float>()
        try {
            var buf = FloatArray(0)
            var consumed = 0L
            var next = 0L
            CameraImport.decodeMono16k(ctx, uri, cancelled, { ms -> progress((ms.toFloat() / duration).coerceIn(0f, 1f)) }) { chunk ->
                buf = buf + chunk
                while (next + need <= consumed + buf.size) {
                    val from = (next - consumed).toInt()
                    val w = buf.copyOfRange(from, from + need)
                    times.add((next + need / 2) * 1000L / RATE)
                    groups.add(detector.groups(w))
                    db.add(loudness(w))
                    rhythm.add(rhythm(w))
                    next += HOP
                }
                val drop = (next - consumed).toInt().coerceIn(0, buf.size)
                if (drop > 0) { buf = buf.copyOfRange(drop, buf.size); consumed += drop }
            }
        } finally {
            detector.close()
        }
        // "Louder than usual" is relative to this video: a quiet room and a noisy playground both have bursts.
        val median = db.sorted().getOrNull(db.size / 2) ?: -60f
        return times.indices.map { i ->
            val g = groups[i]
            times[i] to Heard(
                laugh = if (Status.laughOn) MomentFusion.smooth(0.04f, 0.35f, g.laugh) else 0f,
                cheer = MomentFusion.smooth(0.08f, 0.5f, g.cheer),
                singing = if (Status.singOn) MomentFusion.smooth(0.1f, 0.5f, g.singing) else 0f,
                childVoice = MomentFusion.smooth(0.1f, 0.6f, g.child),
                cry = MomentFusion.smooth(0.1f, 0.5f, g.cry),
                music = g.music.coerceIn(0f, 1f),
                burst = MomentFusion.smooth(3f, 15f, db[i] - median),
                rhythm = rhythm[i],
            )
        }
    }

    private fun loudness(w: FloatArray): Float {
        var s = 0.0
        for (x in w) s += x * x
        return (10 * log10(s / w.size + 1e-10)).toFloat()
    }

    /** How much the loudness pulses 3–7 times a second (a laugh's "ha-ha-ha"), 0..1. */
    private fun rhythm(w: FloatArray): Float {
        val frame = RATE / 100
        val n = w.size / frame
        if (n < 40) return 0f
        val env = FloatArray(n) { i ->
            var s = 0f
            for (k in i * frame until (i + 1) * frame) s += w[k] * w[k]
            sqrt(s / frame)
        }
        val mean = env.average().toFloat()
        if (mean < 1e-4f) return 0f
        for (i in env.indices) env[i] -= mean
        fun power(f: Double): Double {
            var re = 0.0; var im = 0.0
            for (i in env.indices) { val a = 2 * PI * f * i / 100.0; re += env[i] * cos(a); im -= env[i] * sin(a) }
            return re * re + im * im
        }
        var band = 0.0; var total = 0.0
        var f = 1.0
        while (f <= 15.0) { val p = power(f); total += p; if (f in 3.0..7.0) band += p; f += 0.5 }
        if (total <= 0.0) return 0f
        return MomentFusion.smooth(0.4f, 0.75f, (band / total).toFloat())
    }

    // ---------- picture ----------

    /** What one frame shows, kept so movement can be measured against the next sample. */
    private class Look(val faces: List<Face>, val w: Int, val h: Int, val luma: IntArray, val party: Float) {
        var ms: Long = 0L

        fun toSeen(prev: Look?, gapMs: Long): Seen {
            val area = (w * h).toFloat()
            val big = faces.filter { it.boundingBox.width() * it.boundingBox.height() / area >= 0.004f }
                .sortedByDescending { it.boundingBox.width() * it.boundingBox.height() }
            var smile = 0f
            var mouth = 0f
            for (f in big) {
                val a = f.boundingBox.width() * f.boundingBox.height() / area
                val s = (f.smilingProbability ?: 0f) * min(1f, sqrt(a / 0.01f))
                if (s > smile) { smile = s; mouth = mouthOpen(f) }
            }
            var close = 0f
            var facing = 0f
            if (big.size >= 2) {
                val a = big[0].boundingBox
                val b = big[1].boundingBox
                val d = hypot((a.exactCenterX() - b.exactCenterX()).toDouble(), (a.exactCenterY() - b.exactCenterY()).toDouble()).toFloat() / ((a.width() + b.width()) / 2f)
                close = 1f - MomentFusion.smooth(0.9f, 2.6f, d)
                // One face near the camera and one far away only look close on the screen.
                if ((b.width() * b.height()).toFloat() / (a.width() * a.height()) < 0.3f) close *= 0.3f
                val ya = big[0].headEulerAngleY
                val yb = big[1].headEulerAngleY
                if (abs(ya) >= 12f && abs(yb) >= 12f && (ya > 0) != (yb > 0)) facing = 0.6f
            }
            var motion = 0f
            if (prev != null && gapMs > 0) {
                val scale = sqrt(1000f / gapMs.coerceIn(150L, 3_000L))
                var diff = 0
                for (i in luma.indices) diff += abs(luma[i] - prev.luma.getOrElse(i) { luma[i] })
                val grid = MomentFusion.smooth(6f, 30f, diff.toFloat() / luma.size * scale)
                var faceMove = 0f
                val pf = prev.faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                val cf = big.firstOrNull()
                if (pf != null && cf != null) {
                    val shift = hypot((cf.boundingBox.exactCenterX() / w - pf.boundingBox.exactCenterX() / prev.w).toDouble(), (cf.boundingBox.exactCenterY() / h - pf.boundingBox.exactCenterY() / prev.h).toDouble()).toFloat()
                    val size = cf.boundingBox.width().toFloat() / w
                    faceMove = MomentFusion.smooth(0.1f, 0.6f, shift / max(size, 0.05f) * scale)
                }
                motion = max(0.7f * grid, faceMove)
            }
            return Seen(big.size, smile, mouth, close, facing, motion, party)
        }
    }

    /** Mouth openness from landmarks: the lower lip's distance below the line between the mouth corners. */
    private fun mouthOpen(f: Face): Float {
        val l = f.getLandmark(FaceLandmark.MOUTH_LEFT)?.position ?: return 0f
        val r = f.getLandmark(FaceLandmark.MOUTH_RIGHT)?.position ?: return 0f
        val b = f.getLandmark(FaceLandmark.MOUTH_BOTTOM)?.position ?: return 0f
        val len = hypot((r.x - l.x).toDouble(), (r.y - l.y).toDouble()).toFloat()
        if (len < 1f) return 0f
        val dist = abs((r.x - l.x) * (l.y - b.y) - (l.x - b.x) * (r.y - l.y)) / len
        return MomentFusion.smooth(0.07f, 0.17f, dist / f.boundingBox.height().coerceAtLeast(1))
    }

    private val PARTY = setOf("Balloon", "Cake", "Party", "Christmas", "Fireworks", "Candle", "Birthday", "Confetti")

    private fun look(faces: com.google.mlkit.vision.face.FaceDetector, labeler: com.google.mlkit.vision.label.ImageLabeler?, bmp: Bitmap): Look {
        val img = InputImage.fromBitmap(bmp, 0)
        val found = try { Tasks.await(faces.process(img)) } catch (_: Exception) { emptyList<Face>() }
        val party = if (labeler == null) 0f else try {
            Tasks.await(labeler.process(img)).filter { it.text in PARTY }.maxOfOrNull { it.confidence }?.let { MomentFusion.smooth(0.5f, 0.85f, it) } ?: 0f
        } catch (_: Exception) { 0f }
        return Look(found, bmp.width, bmp.height, CaptureFilter.lumaGrid(bmp), party)
    }

    /** One frame, at most 640 px on its long side, upright. */
    private fun frame(mmr: MediaMetadataRetriever, ms: Long, exact: Boolean): Bitmap? = try {
        val opt = if (exact) MediaMetadataRetriever.OPTION_CLOSEST else MediaMetadataRetriever.OPTION_CLOSEST_SYNC
        val raw = if (Build.VERSION.SDK_INT >= 27) mmr.getScaledFrameAtTime(ms * 1000L, opt, 640, 640) else mmr.getFrameAtTime(ms * 1000L, opt)
        if (raw == null) null else {
            val s = 640f / max(raw.width, raw.height)
            if (s < 1f) Bitmap.createScaledBitmap(raw, (raw.width * s).toInt().coerceAtLeast(1), (raw.height * s).toInt().coerceAtLeast(1), true).also { if (it !== raw) raw.recycle() } else raw
        }
    } catch (_: Exception) { null }

    private fun duration(ctx: Context, uri: Uri): Long {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(ctx, uri)
            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (_: Exception) { 0L } finally { try { mmr.release() } catch (_: Exception) { } }
    }
}

/** Honest words for a found moment — what it seems like, never "94% happy". */
object MomentWords {
    fun kind(t: MomentType): String = when (t) {
        MomentType.LAUGHTER -> L.t("צחוק", "laughter")
        MomentType.JOY -> L.t("שמחה", "joy")
        MomentType.AFFECTION -> L.t("קרבה", "closeness")
        MomentType.PLAY -> L.t("משחק", "play")
        MomentType.TICKLE -> L.t("דגדוגים", "tickles")
        MomentType.INTERACTION -> L.t("ביחד", "together")
        MomentType.CELEBRATION -> L.t("חגיגה", "celebration")
    }

    fun headline(c: Candidate): String {
        val strong = c.types.filter { it.second >= 0.45f }.map { it.first }
        val maybe = c.tier == Tier.MAYBE
        if (strong.size >= 2 && !maybe) return L.t("${kind(strong[0])} ו${kind(strong[1])}", "${kind(strong[0]).replaceFirstChar { it.uppercase() }} and ${kind(strong[1])}")
        return when (c.primary) {
            MomentType.LAUGHTER -> if (maybe) L.t("אולי רגע של צחוק", "Maybe a laugh") else L.t("נמצא רגע של צחוק", "A moment of laughter")
            MomentType.JOY -> if (maybe) L.t("אולי רגע שמח", "Maybe a happy moment") else L.t("נראה כמו רגע שמח", "Looks like a happy moment")
            MomentType.AFFECTION -> if (maybe) L.t("אולי חיבוק", "Maybe a hug") else L.t("רגע של קרבה", "A close moment")
            MomentType.PLAY -> if (maybe) L.t("אולי משחק", "Maybe playtime") else L.t("רגע של משחק", "Playtime")
            MomentType.TICKLE -> L.t("נראה כמו משחק דגדוגים", "Looks like tickles")
            MomentType.INTERACTION -> if (maybe) L.t("אולי רגע ביחד", "Maybe a moment together") else L.t("רגע ביחד", "A moment together")
            MomentType.CELEBRATION -> if (maybe) L.t("אולי חגיגה", "Maybe a celebration") else L.t("נראה כמו חגיגה", "Looks like a celebration")
        }
    }

    /** "Heard a laugh · big smile · close together" */
    fun why(c: Candidate): String = c.evidence.mapNotNull { e ->
        when (e) {
            "heard_laugh" -> L.t("נשמע צחוק", "laughter heard")
            "heard_cheer" -> L.t("נשמעות תשואות", "cheering heard")
            "heard_singing" -> L.t("נשמעת שירה", "singing heard")
            "heard_child" -> L.t("קול ילד נרגש", "an excited child's voice")
            "seen_laugh_face" -> L.t("פנים צוחקות", "a laughing face")
            "seen_smile" -> L.t("רואים חיוך", "a smile")
            "close" -> L.t("קרובים זה לזה", "close together")
            "together" -> L.t("כמה אנשים ביחד", "people together")
            "moving" -> L.t("הרבה תנועה", "lots of movement")
            "party" -> L.t("נראה כמו מסיבה", "looks like a party")
            "tickle_pattern" -> L.t("מגע, תנועה וצחוק ברצף", "touch, movement and laughter in a row")
            "music" -> L.t("יש מוזיקה ברקע", "music in the background")
            else -> null
        }
    }.joinToString(" · ")

    fun tier(t: Tier): String = when (t) {
        Tier.STRONG -> L.t("חזק", "Strong")
        Tier.GOOD -> L.t("טוב", "Good")
        Tier.MAYBE -> L.t("אולי", "Maybe")
    }
}
