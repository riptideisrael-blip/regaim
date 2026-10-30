package com.regaim.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * The best photos of a child for their book, chosen automatically on the phone (nothing is uploaded here):
 * the moments where face recognition found the child, opened again at full quality; every face measured for
 * size, sharpness, light, angle, open eyes, how sure we are it's the child, and how recent it is (kids change fast).
 * The parent is asked only when there aren't enough good photos or recognition isn't sure.
 */
object BookPhotos {
    /** [print]/[anchorDist]: the face print and its distance to the child's reference photos (null for a parent's own photo). */
    class Chosen(val crop: Bitmap, val memoryId: String, val score: Float, val print: FloatArray? = null, val anchorDist: Float = 0f)
    class Result(val photos: List<Chosen>, val problem: String?)

    private const val MAX_MOMENTS = 40
    private const val MAX_FRAMES = 100
    /** A face this close to a face recognition already named in the same moment is that same face. */
    private const val LABEL_SAME = 0.7f
    /** How many photos are kept ready (3 are shown and used; the others replace any the parent removes). */
    const val OFFER = 8
    // How sure a face must be (strict first, then a little looser) is in [BookPick].

    /** Background thread only. */
    fun pick(ctx: Context, child: FamilyMember, progress: (Int) -> Unit = {}): Result {
        if (!FaceId.ensureModel(ctx)) return Result(emptyList(), L.t("לא הצלחנו להכין את זיהוי הפנים. בדקו את החיבור ונסו שוב.", "Couldn't set up face recognition. Check the connection and try again."))
        try { PeopleStore.enrollFamily(ctx) } catch (_: Exception) { }
        val people = PeopleStore.people(ctx)
        val person = people.firstOrNull { it.name.trim() == child.name.trim() }
        if (person == null || person.prints.isEmpty()) {
            return Result(emptyList(), L.t("עוד לא זיהינו את ${child.name} ברגעים. בחרו תמונה ברורה של ${child.name}, או הוסיפו תמונת פרופיל.", "We haven't recognized ${child.name} in your moments yet. Pick a clear photo, or add a profile photo."))
        }
        // The moments where this child was found, newest first.
        val ids = PeopleStore.faces(ctx).filter { it.personId == person.id }.map { it.memoryId }.toSet()
        // All the family's moments on the phone (kept or still waiting for approval): nothing leaves the phone before the parent confirms.
        val shotsByMemory = PeopleStore.faces(ctx).filter { !it.ignored }.groupBy { it.memoryId }
        val moments = MemoryStore.all(ctx).filter { it.id in ids || child.name in Fmt.names(it) }
            .sortedByDescending { it.time }.take(MAX_MOMENTS)
        val rejected = Rejects.load(ctx, child.name)

        val detector = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .setMinFaceSize(0.06f)
                .build()
        )
        val seen = ArrayList<BookPick.Seen>()
        // Pictures of the faces that might be offered, kept small (JPEG in memory) until the choice is made.
        val crops = HashMap<String, Pair<ByteArray, String>>()
        val now = System.currentTimeMillis()
        var frames = 0
        try {
            // The faces we trust as this child: led by the profile photo (if it still looks like the child today);
            // a sibling's face learned by mistake is left out.
            val profile = if (child.photo.isBlank()) null else MediaImages.decode(ctx, child.photo, 1600)?.let { bmp ->
                val f = try { Tasks.await(detector.process(InputImage.fromBitmap(bmp, 0))) } catch (_: Exception) { emptyList<Face>() }
                    .maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                f?.let { PeopleStore.cropFace(bmp, it.boundingBox) }?.let { FaceId.embed(ctx, it) }
            }
            val trust = FaceGroups.trusted(person.prints, profile, rejected).ifEmpty { person.prints }
            val others = people.filter { it.id != person.id && it.prints.isNotEmpty() }
            fun look(bmp: Bitmap, key: String, group: String, ageDays: Long, shots: List<FaceShot>, memoryId: String, tagged: Boolean = false) =
                measure(ctx, detector, bmp, key, group, ageDays, person.id, trust, others, rejected, shots, memoryId, seen, crops, tagged)
            // The profile photo too: often the clearest face there is.
            if (child.photo.isNotBlank()) MediaImages.decode(ctx, child.photo, 1600)?.let { bmp ->
                val age = (now - File(child.photo).lastModified().coerceAtLeast(0L)) / 86_400_000L
                look(bmp, "profile", "profile", age, emptyList(), "profile")
            }
            moments.forEachIndexed { k, m ->
                if (frames >= MAX_FRAMES) return@forEachIndexed
                progress(100 * k / max(1, moments.size))
                for ((n, bmp) in framesOf(ctx, m).withIndex()) {
                    frames++
                    look(bmp, "${m.id}#$n", m.dayKey, (now - m.time) / 86_400_000L, shotsByMemory[m.id].orEmpty(), m.id, child.name.trim() in Fmt.names(m))
                    bmp.recycle()
                }
            }
        } finally {
            try { detector.close() } catch (_: Exception) { }
        }
        progress(100)
        // Same child only (not a sibling, not a face the parent rejected); best first: sharpness, size, light, angle
        // and how recent, together. Strict first, a little looser only if fewer than 3 photos are left.
        val picked = BookPick.choose(seen.filter { it.st.key in crops }, rejected, OFFER)
        val chosen = picked.mapNotNull { s ->
            val (jpeg, mem) = crops[s.st.key] ?: return@mapNotNull null
            val bmp = android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return@mapNotNull null
            Chosen(bmp, mem, FaceQuality.score(s.st), s.print, s.dist)
        }
        if (chosen.isEmpty()) return Result(emptyList(), L.t(
            "לא מצאנו תמונה מספיק ברורה של ${child.name}. כדאי להוסיף תמונת פרופיל עדכנית וברורה בפרופיל המשפחה.",
            "We couldn't find a clear enough photo of ${child.name}. Add a clear, recent profile photo in the family profile.",
        ))
        return Result(chosen, null)
    }

    /** The biggest clear face in a saved photo (the parent's profile photo). Null if there's none. Background thread only. */
    fun faceFromPath(ctx: Context, path: String): Bitmap? {
        if (path.isBlank()) return null
        val bmp = MediaImages.decode(ctx, path, 1600) ?: return null
        val detector = FaceDetection.getClient(FaceDetectorOptions.Builder().setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE).build())
        return try {
            val f = Tasks.await(detector.process(InputImage.fromBitmap(bmp, 0))).maxByOrNull { it.boundingBox.width() * it.boundingBox.height() } ?: return null
            if (min(f.boundingBox.width(), f.boundingBox.height()) < 90) null else shrink(crop(bmp, f.boundingBox), 1024)
        } catch (_: Exception) { null } finally { try { detector.close() } catch (_: Exception) { } }
    }

    /** "father", "mother"… for the illustrator, from the parent's role in the profile. */
    fun roleEn(role: String): String = when (role) { "אבא" -> "father"; "אמא" -> "mother"; "סבא" -> "grandfather"; "סבתא" -> "grandmother"; else -> "parent" }

    /** A photo the parent picked: its biggest face. Null when there's no usable face in it. Background thread only. */
    fun fromParentPhoto(ctx: Context, uri: Uri): Chosen? {
        val bmp = MediaImages.decode(ctx, uri.toString(), 1600) ?: return null
        val detector = FaceDetection.getClient(FaceDetectorOptions.Builder().setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE).build())
        return try {
            val f = Tasks.await(detector.process(InputImage.fromBitmap(bmp, 0))).maxByOrNull { it.boundingBox.width() * it.boundingBox.height() } ?: return null
            if (min(f.boundingBox.width(), f.boundingBox.height()) < 90) return null
            Chosen(crop(bmp, f.boundingBox), "parent", 1f)
        } catch (_: Exception) { null } finally { try { detector.close() } catch (_: Exception) { } }
    }

    private fun framesOf(ctx: Context, m: Memory): List<Bitmap> {
        if (!m.isVideo) return listOfNotNull(MediaImages.decode(ctx, m.uri, 1600))
        val mmr = MediaMetadataRetriever()
        return try {
            if (m.uri.startsWith("content://")) mmr.setDataSource(ctx, Uri.parse(m.uri)) else mmr.setDataSource(m.uri)
            val dur = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: max(m.durationMs, 1L)
            val spots = (listOf(m.highlight) + listOf(0.25, 0.5, 0.75).map { (dur * it).toLong() }).distinct()
            spots.mapNotNull { t ->
                mmr.getFrameAtTime(t * 1000L, MediaMetadataRetriever.OPTION_CLOSEST)?.let { shrink(it, 1600) }
            }
        } catch (_: Exception) { emptyList() } finally { try { mmr.release() } catch (_: Exception) { } }
    }

    /** Measures every face in [bmp] that might be the child and keeps what the choice needs. */
    private fun measure(
        ctx: Context, detector: com.google.mlkit.vision.face.FaceDetector, bmp: Bitmap, key: String, group: String, ageDays: Long,
        personId: String, trust: List<FloatArray>, others: List<Person>, rejected: List<FloatArray>, shots: List<FaceShot>, memoryId: String,
        out: MutableList<BookPick.Seen>, crops: MutableMap<String, Pair<ByteArray, String>>, taggedMoment: Boolean = false,
    ) {
        val faces = try { Tasks.await(detector.process(InputImage.fromBitmap(bmp, 0))) } catch (_: Exception) { emptyList<Face>() }
        class F(val i: Int, val f: Face, val print: FloatArray, val dist: Float)
        // The print from the same crop recognition uses (else distances aren't comparable and good faces fail).
        val measured = faces.withIndex().mapNotNull { (i, f) ->
            if (min(f.boundingBox.width(), f.boundingBox.height()) < 80) return@mapNotNull null
            val print = PeopleStore.cropFace(bmp, f.boundingBox)?.let { FaceId.embed(ctx, it) } ?: return@mapNotNull null
            F(i, f, print, trust.minOf { FaceId.dist(print, it) })
        }
        // In a moment the family tagged with the child: the face most like the child is the child.
        val likeliest = measured.minByOrNull { it.dist }
        for (m in measured) {
            val f = m.f
            val box = f.boundingBox
            val tight = tightCrop(bmp, box) ?: continue
            val print = m.print
            val dist = m.dist
            // What recognition already decided for this moment: the named face nearest to this one.
            val named = shots.minByOrNull { FaceId.dist(print, it.print) }?.takeIf { FaceId.dist(print, it.print) < LABEL_SAME }
            if (named?.personId != null && named.personId != personId) continue // named as someone else (a sibling)
            val other = others.minOfOrNull { o -> o.prints.minOf { FaceId.dist(print, it) } } ?: Float.MAX_VALUE
            val reject = rejected.minOfOrNull { FaceId.dist(print, it) } ?: Float.MAX_VALUE
            val gray = luma(tight)
            val crowded = faces.any { o -> o !== f && Rect.intersects(grow(o.boundingBox, 0.3f), grow(box, 0.3f)) }
            val eyes = listOfNotNull(f.leftEyeOpenProbability, f.rightEyeOpenProbability).minOrNull() ?: -1f
            val k = "$key#${m.i}"
            val side = min(box.width(), box.height())
            val st = FaceQuality.Stats(k, group, side, ReelSignals.sharpness(tight), gray.first, gray.second, f.headEulerAngleY, f.headEulerAngleZ, eyes, min(dist, 0.8f), ageDays, crowded)
            val s = BookPick.Seen(st, print, dist, other, reject, named?.personId == personId, tagged = taggedMoment && m === likeliest)
            if (!BookPick.maybe(s)) continue
            out.add(s)
            val jpeg = java.io.ByteArrayOutputStream().also { o -> shrink(crop(bmp, box), 1024).let { c -> c.compress(Bitmap.CompressFormat.JPEG, 92, o); c.recycle() } }.toByteArray()
            crops[k] = jpeg to memoryId
            // At most 60 kept (memory): the weakest goes.
            if (crops.size > 60) out.filter { it.st.key in crops }.minByOrNull { FaceQuality.score(it.st) }?.let { crops.remove(it.st.key) }
        }
    }

    /** The child's tagged moments (by face recognition or by hand), newest first: for the parent to pick from. */
    fun taggedMoments(ctx: Context, name: String, limit: Int = 60): List<Memory> {
        val person = PeopleStore.people(ctx).firstOrNull { it.name.trim() == name.trim() }
        val ids = if (person == null) emptySet() else PeopleStore.faces(ctx).filter { it.personId == person.id }.map { it.memoryId }.toSet()
        return MemoryStore.all(ctx).filter { it.id in ids || name.trim() in Fmt.names(it) }.sortedByDescending { it.time }.take(limit)
    }

    /**
     * A moment the parent picked: the child's face in it (the face most like the child, if there are several; else the
     * biggest), from the clearest frame. Null if no usable face. Background thread only.
     */
    fun fromMoment(ctx: Context, m: Memory, name: String): Chosen? {
        if (!FaceId.ensureModel(ctx)) return null
        val person = PeopleStore.people(ctx).firstOrNull { it.name.trim() == name.trim() }
        val detector = FaceDetection.getClient(FaceDetectorOptions.Builder().setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE).build())
        var best: Chosen? = null
        var bestScore = -1f
        try {
            for (bmp in framesOf(ctx, m)) {
                val faces = try { Tasks.await(detector.process(InputImage.fromBitmap(bmp, 0))) } catch (_: Exception) { emptyList<Face>() }
                val usable = faces.filter { min(it.boundingBox.width(), it.boundingBox.height()) >= 80 }
                val withPrint = usable.map { f -> f to PeopleStore.cropFace(bmp, f.boundingBox)?.let { FaceId.embed(ctx, it) } }
                val pick = if (person != null && person.prints.isNotEmpty() && withPrint.size > 1)
                    withPrint.minByOrNull { (_, p) -> p?.let { q -> person.prints.minOf { FaceId.dist(q, it) } } ?: 9f }
                else withPrint.maxByOrNull { (f, _) -> f.boundingBox.width() * f.boundingBox.height() }
                if (pick != null) {
                    val (f, p) = pick
                    val tight = tightCrop(bmp, f.boundingBox)
                    val score = min(f.boundingBox.width(), f.boundingBox.height()) * (0.3f + (tight?.let { ReelSignals.sharpness(it) } ?: 0f))
                    if (score > bestScore) {
                        bestScore = score
                        best = Chosen(shrink(crop(bmp, f.boundingBox), 1024), m.id, 1f, p, 0f)
                    }
                }
                bmp.recycle()
            }
        } finally {
            try { detector.close() } catch (_: Exception) { }
        }
        return best
    }

    /** Mean brightness and the share of burnt / black pixels of a face. */
    private fun luma(b: Bitmap): Pair<Float, Float> {
        val s = Bitmap.createScaledBitmap(b, 64, 64, true)
        val px = IntArray(64 * 64)
        s.getPixels(px, 0, 64, 0, 0, 64, 64)
        var sum = 0f
        var clip = 0
        for (c in px) {
            val y = 0.299f * ((c shr 16) and 0xff) + 0.587f * ((c shr 8) and 0xff) + 0.114f * (c and 0xff)
            sum += y
            if (y < 12f || y > 245f) clip++
        }
        return sum / px.size to clip.toFloat() / px.size
    }

    private fun grow(r: Rect, f: Float): Rect {
        val dx = (r.width() * f).toInt(); val dy = (r.height() * f).toInt()
        return Rect(r.left - dx, r.top - dy, r.right + dx, r.bottom + dy)
    }

    private fun tightCrop(src: Bitmap, box: Rect): Bitmap? {
        val l = max(0, box.left); val t = max(0, box.top)
        val r = min(src.width, box.right); val b = min(src.height, box.bottom)
        if (r - l < 40 || b - t < 40) return null
        return Bitmap.createBitmap(src, l, t, r - l, b - t)
    }

    /** The face with room for hair and chin (what the illustrator needs), from the full-quality frame. */
    private fun crop(src: Bitmap, box: Rect): Bitmap {
        val pad = (max(box.width(), box.height()) * 0.5f).toInt()
        val l = (box.left - pad).coerceAtLeast(0)
        val t = (box.top - (pad * 1.2f).toInt()).coerceAtLeast(0)
        val r = (box.right + pad).coerceAtMost(src.width)
        val b = (box.bottom + pad).coerceAtMost(src.height)
        val c = Bitmap.createBitmap(src, l, t, max(1, r - l), max(1, b - t))
        // The whole frame comes back as the same object: copy it, the frame is recycled after measuring.
        return if (c === src) src.copy(Bitmap.Config.ARGB_8888, false) else c
    }

    private fun shrink(b: Bitmap, limit: Int): Bitmap {
        val big = max(b.width, b.height)
        if (big <= limit) return b
        val s = limit.toFloat() / big
        return Bitmap.createScaledBitmap(b, (b.width * s).toInt(), (b.height * s).toInt(), true).also { if (it !== b) b.recycle() }
    }
}

/**
 * Faces the parent said aren't this child (removed on the confirm screen), kept per child so the next book
 * doesn't offer that face, or faces like it, again. Prints only (numbers), on the phone.
 */
object Rejects {
    private fun p(ctx: Context) = ctx.getSharedPreferences("book_rejects", Context.MODE_PRIVATE)
    private fun key(child: String) = "c_" + child.trim()

    fun load(ctx: Context, child: String): List<FloatArray> = try {
        val a = org.json.JSONArray(p(ctx).getString(key(child), "[]"))
        (0 until a.length()).map { i -> a.getJSONArray(i).let { r -> FloatArray(r.length()) { r.getDouble(it).toFloat() } } }
    } catch (_: Exception) { emptyList() }

    @Synchronized fun add(ctx: Context, child: String, print: FloatArray) {
        val all = (load(ctx, child) + print).takeLast(30)
        val a = org.json.JSONArray()
        for (f in all) a.put(org.json.JSONArray().also { r -> f.forEach { r.put(it.toDouble()) } })
        p(ctx).edit().putString(key(child), a.toString()).apply()
    }
}
