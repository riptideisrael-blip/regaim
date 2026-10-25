package com.regaim.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import org.json.JSONArray
import org.json.JSONObject
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

/**
 * On-device face identity (MobileFaceNet, BSD-3 licensed model, downloaded once ~5MB).
 * Turns a face crop into a 192-number "face print". Nothing leaves the phone.
 */
object FaceId {
    /**
     * Pinned to the exact commit and checked by SHA-256: if the file at this address is ever changed or swapped,
     * the app refuses it instead of running an unknown model. The file hasn't changed since 2020 (commit 010f0ee).
     */
    private const val MODEL_URL =
        "https://github.com/MCarlomagno/FaceRecognitionAuth/raw/010f0ee203ddf008665e6ea202118fe9aeb28ad5/assets/mobilefacenet.tflite"
    const val MODEL_SHA256 = "be4bc7cfc53f7bc336d0f28b1ab92535f618c913a422b683210750f6b5354854"
    private const val MODEL_BYTES = 5_233_552L
    private var interpreter: Interpreter? = null
    @Volatile private var checked = false

    /** The model failed to load (damaged file): it was deleted and will be downloaded again. */
    class ModelBroken(cause: Throwable) : Exception(cause)

    fun modelFile(ctx: Context) = File(ctx.filesDir, "mobilefacenet.tflite")
    fun ready(ctx: Context) = modelFile(ctx).let { it.exists() && it.length() > 1_000_000 }

    private fun sha256(f: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return FacePrints.hex(md.digest())
    }

    /** Downloads the model once and checks it's the expected file. Background thread only. */
    @Synchronized
    fun ensureModel(ctx: Context): Boolean {
        val f = modelFile(ctx)
        if (ready(ctx)) {
            if (checked) return true
            if (try { sha256(f) == MODEL_SHA256 } catch (_: Exception) { false }) {
                checked = true
                return true
            }
            // Not the expected model: never use it. Faces read with it must be read again with the right one.
            close()
            f.delete()
            ctx.getSharedPreferences("regaim", Context.MODE_PRIVATE).edit().putBoolean("faces_reprint", true).apply()
        }
        var url = MODEL_URL
        repeat(5) {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 20_000
                readTimeout = 60_000
            }
            val tmp = File(ctx.filesDir, "mobilefacenet.tmp")
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    url = conn.getHeaderField("Location") ?: return false
                    if (!url.startsWith("https://")) return false
                    return@repeat
                }
                if (code != 200) return false
                conn.inputStream.use { input -> FileOutputStream(tmp).use { input.copyTo(it) } }
                if (tmp.length() != MODEL_BYTES || sha256(tmp) != MODEL_SHA256) {
                    tmp.delete()
                    return false
                }
                if (!tmp.renameTo(f)) {
                    tmp.delete()
                    return false
                }
                checked = true
                return true
            } catch (_: Exception) {
                tmp.delete()
                return false
            } finally {
                conn.disconnect()
            }
        }
        return false
    }

    @Synchronized
    private fun close() {
        try { interpreter?.close() } catch (_: Exception) { }
        interpreter = null
    }

    @Synchronized
    /**
     * The face turned upright and cut the way the model was trained on (eyes level, at fixed places in a 112x112
     * picture). Null when the eyes can't be found in [crop] (then the plain crop is used). Background thread only.
     */
    fun align(crop: Bitmap): Bitmap? {
        val detector = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setMinFaceSize(0.3f)
                .build()
        )
        return try {
            val face = Tasks.await(detector.process(InputImage.fromBitmap(crop, 0))).maxByOrNull { it.boundingBox.width() * it.boundingBox.height() } ?: return null
            val a = face.getLandmark(com.google.mlkit.vision.face.FaceLandmark.LEFT_EYE)?.position ?: return null
            val b = face.getLandmark(com.google.mlkit.vision.face.FaceLandmark.RIGHT_EYE)?.position ?: return null
            val (l, r) = if (a.x <= b.x) a to b else b to a
            val dx = r.x - l.x; val dy = r.y - l.y
            val d = kotlin.math.sqrt(dx * dx + dy * dy)
            if (d < 8f) return null
            // The usual 112x112 face template: eyes 35.2 px apart, centred, 51.6 px from the top.
            val m = android.graphics.Matrix().apply {
                postTranslate(-(l.x + r.x) / 2f, -(l.y + r.y) / 2f)
                postRotate(-Math.toDegrees(kotlin.math.atan2(dy, dx).toDouble()).toFloat())
                postScale(35.2f / d, 35.2f / d)
                postTranslate(56f, 51.6f)
            }
            Bitmap.createBitmap(112, 112, Bitmap.Config.ARGB_8888).also { out ->
                android.graphics.Canvas(out).drawBitmap(crop, m, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
            }
        } catch (_: Exception) { null } finally { detector.close() }
    }

    fun embed(ctx: Context, face: Bitmap): FloatArray? {
        if (!ready(ctx)) return null
        val interp = interpreter ?: try {
            Interpreter(modelFile(ctx), Interpreter.Options().setNumThreads(2)).also { interpreter = it }
        } catch (e: Exception) {
            // A damaged model file: delete it, so the next run downloads a good one, instead of every moment failing.
            modelFile(ctx).delete()
            checked = false
            throw ModelBroken(e)
        }
        val inShape = interp.getInputTensor(0).shape() // [batch, 112, 112, 3]
        val batch = inShape[0]
        val size = inShape[1]
        val outLen = interp.getOutputTensor(0).shape().last()
        val scaled = Bitmap.createScaledBitmap(face, size, size, true)
        val px = IntArray(size * size)
        scaled.getPixels(px, 0, size, 0, 0, size, size)
        val input = ByteBuffer.allocateDirect(batch * size * size * 3 * 4).order(ByteOrder.nativeOrder())
        repeat(batch) {
            for (p in px) {
                input.putFloat((((p shr 16) and 0xff) - 128f) / 128f)
                input.putFloat((((p shr 8) and 0xff) - 128f) / 128f)
                input.putFloat(((p and 0xff) - 128f) / 128f)
            }
        }
        input.rewind()
        val out = Array(batch) { FloatArray(outLen) }
        interp.run(input, out)
        return FacePrints.normalize(out[0])
    }

    /** Damaged prints (wrong length, NaN) never count as close. */
    fun dist(a: FloatArray, b: FloatArray): Float = FacePrints.dist(a, b)
}

data class Person(
    val id: String, val name: String, val photo: String, val prints: List<FloatArray>,
    /** Faces the family said are NOT this person ("זה לא אורי"): a face closer to these than to [prints] is never named so. */
    val rejects: List<FloatArray> = emptyList(),
    /** Older samples (earlier ages), thinned across time; see [FacePrints.roll]. */
    val past: List<FloatArray> = emptyList(),
) {
    /** Every sample used to recognize them: now and earlier ages. */
    val allPrints: List<FloatArray> get() = prints + past

    /** With [add] taught: the newest stay, older ones are kept spread over time. */
    fun learn(add: List<FloatArray>): Person = FacePrints.roll(prints, past, add).let { (r, p) -> copy(prints = r, past = p) }
}

data class FaceShot(
    val id: String,
    val memoryId: String,
    val path: String,
    val print: FloatArray,
    val personId: String?,
    val ignored: Boolean,
    val time: Long,
    /** The parent named this face themselves (the ground truth for measuring recognition, see [FaceBench]). */
    val confirmed: Boolean = false,
    /**
     * No eyes could be found (a head from above, a face turned away, hair): its print can't be compared with the
     * aligned ones, so it's never named automatically, never teaches, and isn't asked about under "Who's this?".
     */
    val flat: Boolean = false,
)

/** People and faces, Google-Photos style: faces are grouped, you name a group once, the app finds them again. */
object PeopleStore {
    /**
     * Two prints closer than this are treated as the same person (prints are length-1 vectors).
     * Was 0.95: too loose for young siblings and small, blurry chest-camera faces, so almost every
     * child ended up tagged as the same kid.
     */
    const val MATCH = 0.82f
    /** "Who's this?" groups: a little looser than MATCH, but no longer lumping different kids together. */
    private const val GROUP = 0.86f
    /** The best person must win clearly over the second best, else the face stays unnamed. */
    private const val MARGIN = 0.06f
    /** Faces smaller than this (pixels) are kept but never named automatically: too little detail. */
    private const val MIN_FACE_PX = 72
    private const val SAME_IN_CLIP = 0.85f
    /** "Maybe it's ...?" suggestions: looser than [MATCH], never applied without the parent's tap. */
    private const val GUESS = 0.95f

    // ---------- aligned faces and the family's own threshold ----------

    /**
     * From this version on, a face's print is made from the face turned upright (eyes level, [FaceId.align]): the
     * same person in different angles comes out much closer (measured on a family's confirmed faces: found 38% → 66%).
     * Existing faces are switched over once, by themselves ([alignOnce]); the parent does nothing.
     */
    private const val PRINTS_ALIGNED = 3 // 3: faces without visible eyes are kept apart ("flat")
    /** Aligned prints are closer together, so the naming distance is stricter; this until the family's own is measured. */
    private const val ALIGNED_DEFAULT_CUT = 0.70f
    /** Measuring the family's own threshold needs at least this many faces they named, of 2+ people. */
    private const val CALIBRATE_MIN = 12

    /** The naming distance in use (all other distances scale with it). */
    @Volatile private var cut = MATCH
    private fun scaled(v: Float) = v * cut / MATCH
    fun currentCut() = cut

    private fun facePrefs(ctx: Context) = ctx.getSharedPreferences("regaim", Context.MODE_PRIVATE)
    fun aligned(ctx: Context) = facePrefs(ctx).getInt("face_prints_v", 1) >= PRINTS_ALIGNED
    /** How many confirmed faces the threshold was measured on (0 = not measured yet, the default is used). */
    fun calibratedOn(ctx: Context) = facePrefs(ctx).getInt("face_cut_n", 0)

    private fun loadCut(ctx: Context) {
        cut = if (aligned(ctx)) facePrefs(ctx).getFloat("face_cut", ALIGNED_DEFAULT_CUT) else MATCH
    }

    /**
     * The print of a face crop the way the app now makes them: from the aligned face, with flat = false; when no
     * eyes are found, a plain print with flat = true (kept, but never compared for naming).
     */
    fun faceprint(ctx: Context, crop: Bitmap): Pair<FloatArray, Boolean>? {
        if (!(aligned(ctx) || switching)) return FaceId.embed(ctx, crop)?.let { it to false }
        FaceId.align(crop)?.let { a -> FaceId.embed(ctx, a)?.let { return it to false } }
        return FaceId.embed(ctx, crop)?.let { it to true }
    }

    /** Only a print that can be compared (aligned). Null when the eyes can't be found. */
    fun printOf(ctx: Context, crop: Bitmap): FloatArray? = faceprint(ctx, crop)?.takeIf { !it.second }?.first
    @Volatile private var switching = false

    private fun confirmedSamples(ctx: Context): List<FaceBench.Sample> =
        faces(ctx).filter { it.confirmed && it.personId != null && !it.flat }.map { FaceBench.Sample(it.personId!!, it.print) }

    /**
     * The family's own naming distance: the loosest one that names none of their confirmed faces as someone else
     * (each face recognized from the others). Measured again as more faces are confirmed. Phone only.
     */
    private fun calibrate(ctx: Context, force: Boolean = false) {
        if (!aligned(ctx)) return
        val samples = confirmedSamples(ctx)
        val last = calibratedOn(ctx)
        if (!force && samples.size < last + 10) return
        if (samples.size < CALIBRATE_MIN || samples.map { it.personId }.distinct().size < 2) return
        val gallery = samples.groupBy { it.personId }.map { (id, l) -> Triple(id, l.map { it.print }, emptyList<FloatArray>()) }
        var chosen = 0.56f
        var c = 0.90f
        while (c >= 0.56f) {
            val r = FaceBench.evaluate(samples, gallery, c, MARGIN * c / MATCH)
            if (r.wrong == 0) { chosen = c; break }
            c -= 0.02f
        }
        facePrefs(ctx).edit().putFloat("face_cut", chosen).putInt("face_cut_n", samples.size).apply()
        cut = chosen
        // New threshold: faces waiting for a name are looked at again.
        facePrefs(ctx).edit().remove("faces_rematch_sig").apply()
    }

    /**
     * Once, by itself: every saved face gets an aligned print (from its saved crop), each person is learned again
     * from the faces the family named, the threshold is measured on them, and faces named automatically are checked
     * again. Names the family gave stay. "Not them" marks start over (they were in the old prints). Background only.
     */
    private fun alignOnce(ctx: Context) {
        if (aligned(ctx) || !FaceId.ready(ctx)) return
        switching = true
        try {
            // The slow part (a minute or so) runs without holding the lock, so screens never wait on it.
            val before = people(ctx)
            val old = faces(ctx)
            // Faces named by hand before "confirmed" existed are recognized by their old print, so mark them now.
            val byHand = old.filter { f -> f.confirmed || before.firstOrNull { it.id == f.personId }?.prints?.any { FaceId.dist(it, f.print) < 0.05f } == true }.map { it.id }.toSet()
            val newPrint = HashMap<String, Pair<FloatArray, Boolean>>()
            val unreadable = HashSet<String>()
            for (f in old) {
                val bmp = try { android.graphics.BitmapFactory.decodeFile(f.path) } catch (_: Throwable) { null }
                val p = bmp?.let { faceprint(ctx, it) }
                if (p != null) newPrint[f.id] = p else unreadable.add(f.id)
            }
            synchronized(this) {
                val people = people(ctx)
                // Faces added meanwhile were already made aligned; faces whose crop is gone can't be switched: dropped.
                val fresh = faces(ctx).mapNotNull { f ->
                    when {
                        f.id in newPrint -> newPrint.getValue(f.id).let { (pr, flat) -> f.copy(print = pr, flat = flat, confirmed = f.confirmed || f.id in byHand) }
                        f.id in unreadable -> null
                        else -> f
                    }
                }
                val named = fresh.filter { it.personId != null && !it.flat }.groupBy { it.personId }
                savePeople(ctx, people.map { p ->
                    val mine = named[p.id].orEmpty()
                    val teach = mine.filter { it.confirmed }.ifEmpty { mine }.map { it.print }
                    p.copy(prints = FacePrints.typical(teach, 15), past = emptyList(), rejects = emptyList())
                })
                saveFaces(ctx, fresh)
            }
            facePrefs(ctx).edit().putInt("face_prints_v", PRINTS_ALIGNED).remove("enrolled_family").remove("face_cut_n").apply()
            loadCut(ctx)
            calibrate(ctx, force = true)
            // Automatic names checked again under the new prints and threshold; the family's own names stay.
            synchronized(this) {
                val now = people(ctx)
                saveFaces(ctx, faces(ctx).map { f ->
                    if (f.confirmed || f.ignored) f
                    else if (f.flat || faceSide(f) < MIN_FACE_PX) f.copy(personId = null)
                    else f.copy(personId = match(now, f.print)?.id)
                })
            }
            facePrefs(ctx).edit().remove("faces_rematch_sig").apply()
            refreshMemoryNames(ctx)
        } finally {
            switching = false
        }
    }

    /**
     * "Maybe it's Michal?" groups the app is sure enough about are named by themselves: 2+ faces in the group, and
     * each one clearly Michal, within a distance halfway between naming one face and suggesting. The parent can still
     * fix any of them ("not Michal"). These aren't counted as confirmed (they don't move the threshold).
     */
    private fun acceptStrongGuesses(ctx: Context) {
        if (!aligned(ctx)) return
        val people = people(ctx)
        if (people.isEmpty()) return
        val triples = people.map { Triple(it.id, it.allPrints, it.rejects) }
        val strong = cut + (scaled(GUESS) - cut) * 0.5f
        val named = HashMap<String, String>()
        for (g in unknownGroups(ctx)) {
            val usable = g.filter { faceSide(it) >= MIN_FACE_PX }
            if (usable.size != g.size) continue // a small face in the group: ask instead
            val who = FacePrints.groupVote(g.map { it.print }, triples, strong, scaled(MARGIN)) ?: continue
            g.forEach { named[it.id] = who }
        }
        if (named.isEmpty()) return
        synchronized(this) {
            saveFaces(ctx, faces(ctx).map { f -> named[f.id]?.takeIf { f.personId == null && !f.ignored }?.let { f.copy(personId = it) } ?: f })
        }
        refreshMemoryNames(ctx)
    }

    /** The face's smaller side in the original frame, read from its saved crop (face plus 18% each side). */
    private fun faceSide(f: FaceShot): Int = try {
        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(f.path, o)
        if (o.outWidth > 0) (minOf(o.outWidth, o.outHeight) / 1.36f).toInt() else 0
    } catch (_: Exception) { 0 }

    fun peopleEnabled(ctx: Context) = ctx.getSharedPreferences("regaim", Context.MODE_PRIVATE).getBoolean("people_on", true)

    private fun peopleFile(ctx: Context) = File(ctx.filesDir, "people.json")
    private fun facesFile(ctx: Context) = File(ctx.filesDir, "faces.json")

    private fun toJson(a: FloatArray) = JSONArray().also { j -> a.forEach { j.put(it.toDouble()) } }
    private fun fromJson(j: JSONArray) = FloatArray(j.length()) { j.optDouble(it).toFloat() }

    /**
     * The last read of the people / faces file failed (it exists but couldn't be opened just now). While so, nothing
     * is written over it: a save would replace the whole family's names and faces with an almost empty list.
     */
    @Volatile private var peopleUnread = false
    @Volatile private var facesUnread = false

    /** The last people list read, with the file's time and size then: avatars ask often, the file rarely changes. */
    @Volatile private var peopleCache: Triple<Long, Long, List<Person>>? = null

    @Synchronized
    fun people(ctx: Context): List<Person> = try {
        loadCut(ctx)
        val pf = peopleFile(ctx)
        peopleCache?.takeIf { pf.exists() && it.first == pf.lastModified() && it.second == pf.length() }?.let { return it.third }
        val arr = JSONArray(SecureFiles.readText(pf) ?: "[]")
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            val pr = o.optJSONArray("prints") ?: JSONArray()
            val rj = o.optJSONArray("rejects") ?: JSONArray()
            val pa = o.optJSONArray("past") ?: JSONArray()
            Person(o.optString("id"), o.optString("name"), o.optString("photo"),
                (0 until pr.length()).map { k -> fromJson(pr.getJSONArray(k)) },
                (0 until rj.length()).map { k -> fromJson(rj.getJSONArray(k)) },
                (0 until pa.length()).map { k -> fromJson(pa.getJSONArray(k)) })
        }.also { list ->
            peopleUnread = false
            peopleCache = if (pf.exists()) Triple(pf.lastModified(), pf.length(), list) else null
        }
    } catch (e: Exception) {
        // A file that opens but isn't valid JSON can't be read later either: set aside (kept), start clean.
        // Anything else (a passing Keystore hiccup) leaves the file and blocks writing over it until it reads again.
        if (e is org.json.JSONException) { SafeFiles.quarantine(peopleFile(ctx)); peopleUnread = false }
        else peopleUnread = peopleFile(ctx).exists()
        emptyList()
    }

    @Synchronized
    private fun savePeople(ctx: Context, list: List<Person>) {
        if (peopleUnread && peopleFile(ctx).exists()) throw java.io.IOException(L.t("לא הצלחנו לפתוח כרגע את רשימת האנשים. נסו שוב בעוד רגע.", "Couldn't open the people list right now. Try again in a moment."))
        val arr = JSONArray()
        list.forEach { p ->
            arr.put(JSONObject().put("id", p.id).put("name", p.name).put("photo", p.photo)
                .put("prints", JSONArray().also { j -> p.prints.forEach { j.put(toJson(it)) } })
                .put("rejects", JSONArray().also { j -> p.rejects.forEach { j.put(toJson(it)) } })
                .put("past", JSONArray().also { j -> p.past.forEach { j.put(toJson(it)) } }))
        }
        peopleCache = null
        SecureFiles.writeText(peopleFile(ctx), arr.toString()) // encrypted on the phone
    }

    @Synchronized
    fun faces(ctx: Context): List<FaceShot> = try {
        val arr = JSONArray(SecureFiles.readText(facesFile(ctx)) ?: "[]")
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            FaceShot(o.optString("id"), o.optString("memoryId"), o.optString("path"),
                fromJson(o.optJSONArray("print") ?: JSONArray()),
                o.optString("personId").ifEmpty { null }, o.optBoolean("ignored"), o.optLong("time"), o.optBoolean("confirmed"), o.optBoolean("flat"))
        }.also { facesUnread = false }
    } catch (e: Exception) {
        if (e is org.json.JSONException) { SafeFiles.quarantine(facesFile(ctx)); facesUnread = false }
        else facesUnread = facesFile(ctx).exists()
        emptyList()
    }

    @Synchronized
    private fun saveFaces(ctx: Context, list: List<FaceShot>) {
        if (facesUnread && facesFile(ctx).exists()) throw java.io.IOException(L.t("לא הצלחנו לפתוח כרגע את רשימת הפנים. נסו שוב בעוד רגע.", "Couldn't open the faces list right now. Try again in a moment."))
        val arr = JSONArray()
        list.forEach { f ->
            arr.put(JSONObject().put("id", f.id).put("memoryId", f.memoryId).put("path", f.path)
                .put("print", toJson(f.print)).put("personId", f.personId ?: "")
                .put("ignored", f.ignored).put("time", f.time).put("confirmed", f.confirmed).put("flat", f.flat))
        }
        SecureFiles.writeText(facesFile(ctx), arr.toString()) // encrypted on the phone
    }

    /**
     * Who this face is: the closest person, only when close enough and clearly closer than the second (siblings:
     * don't guess), and not someone the family said it isn't. The rule itself is in [FacePrints.bestMatch] (tested).
     */
    fun match(people: List<Person>, print: FloatArray): Person? {
        val id = FacePrints.bestMatch(print, people.map { Triple(it.id, it.allPrints, it.rejects) }, cut, scaled(MARGIN)) ?: return null
        return people.firstOrNull { it.id == id }
    }

    /** For the lab: named / waiting faces, and why each waiting face has no name. Phone only. Background thread. */
    class Diagnosis(val modelReady: Boolean, val named: Int, val ignored: Int, val unnamed: Int, val why: Map<FacePrints.Unnamed, Int>, val notScanned: Int)

    fun diagnose(ctx: Context): Diagnosis {
        val people = people(ctx)
        val triples = people.map { Triple(it.id, it.allPrints, it.rejects) }
        val all = faces(ctx)
        val waiting = all.filter { it.personId == null && !it.ignored }
        val why = HashMap<FacePrints.Unnamed, Int>()
        for (f in waiting) {
            // The face's size isn't stored: it's read from the saved crop (the crop is the face plus 18% on each side).
            val side = try {
                val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(f.path, o)
                if (o.outWidth > 0) (minOf(o.outWidth, o.outHeight) / 1.36f).toInt() else 0
            } catch (_: Exception) { 0 }
            val w = if (f.flat) FacePrints.Unnamed.NO_EYES else FacePrints.whyUnnamed(f.print, side, MIN_FACE_PX, triples, cut, scaled(MARGIN))
            why[w] = (why[w] ?: 0) + 1
        }
        val notScanned = try { MemoryStore.kept(ctx).count { !it.facesScanned } } catch (_: Exception) { 0 }
        return Diagnosis(FaceId.ready(ctx), all.count { it.personId != null }, all.count { it.ignored }, waiting.size, why, notScanned)
    }

    /** The faces the parent named themselves (ground truth for measuring). */
    private fun confirmedFaces(ctx: Context): List<FaceShot> {
        val people = people(ctx)
        return faces(ctx).filter { f ->
            val p = people.firstOrNull { it.id == f.personId } ?: return@filter false
            f.confirmed || p.prints.any { FaceId.dist(it, f.print) < 0.05f }
        }
    }

    /** Recognition measured two ways on the same confirmed faces: as today, and with the faces aligned first. */
    class AlignCompare(val faces: Int, val eyesFound: Int, val before: FaceBench.Result, val after: FaceBench.Result)

    /**
     * Only measures: nothing saved changes. Each confirmed face is recognized from the family's other confirmed faces
     * (leave-one-out), once with today's prints and once with prints of the aligned face. Background thread only.
     */
    fun compareAlignment(ctx: Context, progress: (Int, Int) -> Unit): AlignCompare? {
        if (!FaceId.ready(ctx)) return null
        val shots = confirmedFaces(ctx).take(200)
        if (shots.size < 5) return null
        val before = ArrayList<FaceBench.Sample>(); val after = ArrayList<FaceBench.Sample>()
        var eyes = 0
        shots.forEachIndexed { i, f ->
            progress(i, shots.size)
            val crop = try { android.graphics.BitmapFactory.decodeFile(f.path) } catch (_: Throwable) { null } ?: return@forEachIndexed
            val plain = FaceId.embed(ctx, crop) ?: return@forEachIndexed
            val aligned = FaceId.align(crop)
            if (aligned != null) eyes++
            val ap = aligned?.let { FaceId.embed(ctx, it) } ?: plain
            before.add(FaceBench.Sample(f.personId!!, plain)); after.add(FaceBench.Sample(f.personId!!, ap))
        }
        fun gallery(s: List<FaceBench.Sample>) = s.groupBy { it.personId }.map { (id, l) -> Triple(id, l.map { it.print }, emptyList<FloatArray>()) }
        return AlignCompare(before.size, eyes,
            FaceBench.evaluate(before, gallery(before), MATCH, MARGIN),
            FaceBench.evaluate(after, gallery(after), if (aligned(ctx)) cut else MATCH, if (aligned(ctx)) scaled(MARGIN) else MARGIN))
    }

    /**
     * Faces still waiting for a name are checked again whenever the app learned something new about the people
     * (a name given, "not them", a new photo). Only unnamed faces get a name here; names already given never change.
     */
    private fun rematchUnnamed(ctx: Context) {
        val people = people(ctx)
        val sig = "$cut;" + people.joinToString("|") { "${it.id}:${it.prints.size}:${it.past.size}:${it.rejects.size}" }
        val prefs = ctx.getSharedPreferences("regaim", Context.MODE_PRIVATE)
        if (people.isEmpty() || prefs.getString("faces_rematch_sig", "") == sig) return
        var changed = 0
        synchronized(this) {
            val now = people(ctx)
            saveFaces(ctx, faces(ctx).map { f ->
                if (f.personId != null || f.ignored || f.flat) return@map f
                val side = try {
                    val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    android.graphics.BitmapFactory.decodeFile(f.path, o)
                    if (o.outWidth > 0) (minOf(o.outWidth, o.outHeight) / 1.36f).toInt() else 0
                } catch (_: Exception) { 0 }
                if (side < MIN_FACE_PX) return@map f
                val who = match(now, f.print)?.id ?: return@map f
                changed++
                f.copy(personId = who)
            })
        }
        if (changed > 0) refreshMemoryNames(ctx)
        prefs.edit().putString("faces_rematch_sig", sig).apply()
    }

    /** How well faces are recognized, measured on the faces the parent named (on the phone). Null = too few yet. */
    fun bench(ctx: Context): FaceBench.Result? {
        val people = people(ctx)
        // Confirmed faces, and faces named by hand before the "confirmed" mark existed (their print is one the person was taught).
        val samples = faces(ctx).filter { f ->
            val p = people.firstOrNull { it.id == f.personId } ?: return@filter false
            f.confirmed || p.prints.any { FaceId.dist(it, f.print) < 0.05f }
        }
            .map { FaceBench.Sample(it.personId!!, it.print) }
        if (samples.size < 5) return null
        return FaceBench.evaluate(samples, people.map { Triple(it.id, it.allPrints, it.rejects) }, cut, scaled(MARGIN))
    }

    /**
     * Once, after the stricter rules: look again at every face that was named automatically.
     * Faces the family named by hand (their print is one of the person's own prints) stay as they are.
     */
    private fun rematchOnce(ctx: Context) {
        val prefs = ctx.getSharedPreferences("regaim", Context.MODE_PRIVATE)
        if (prefs.getBoolean("faces_rules_v2", false)) return
        synchronized(this) {
            val people = people(ctx)
            saveFaces(ctx, faces(ctx).map { f ->
                val pid = f.personId ?: return@map f
                val own = people.firstOrNull { it.id == pid }
                val byHand = own?.prints?.any { FaceId.dist(it, f.print) < 0.05f } == true
                if (byHand) f else f.copy(personId = if (f.flat) null else match(people, f.print)?.id)
            })
        }
        refreshMemoryNames(ctx)
        prefs.edit().putBoolean("faces_rules_v2", true).apply()
    }

    // ---------- scanning moments ----------

    private val scanning = java.util.concurrent.atomic.AtomicBoolean(false)
    /** Moments that failed in this run of the app: tried again on a later run, not again and again now. */
    private val failedThisRun = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /** Whether the moment's file is still there; null when it can't be told (no permission...). */
    private fun exists(ctx: Context, m: Memory): Boolean? = try {
        if (m.uri.startsWith("content://")) ctx.contentResolver.openFileDescriptor(android.net.Uri.parse(m.uri), "r")?.use { true } ?: false
        else java.io.File(m.uri.removePrefix("file://")).exists()
    } catch (_: java.io.FileNotFoundException) { false } catch (_: Exception) { null }

    private fun triesPrefs(ctx: Context) = ctx.getSharedPreferences("face_scan_tries", Context.MODE_PRIVATE)

    /**
     * Finds faces in moments that weren't scanned yet. Background thread only. Returns how many were scanned.
     * A moment that can't be read right now (not downloaded from Google Photos yet, out of memory...) is tried
     * again on later runs; only after [ScanTries.MAX] failed runs is it marked as done without faces.
     */
    fun scanPending(ctx: Context, limit: Int = 8): Int {
        if (!scanning.compareAndSet(false, true)) return 0
        try {
            if (!FaceId.ensureModel(ctx)) return 0
            try { reprintIfNeeded(ctx) } catch (_: Exception) {}
            try { alignOnce(ctx) } catch (_: Exception) {}
            try { calibrate(ctx) } catch (_: Exception) {}
            try { sealOnce(ctx) } catch (_: Exception) {}
            try { enrollFamily(ctx) } catch (_: Exception) {}
            try { mergeDuplicates(ctx) } catch (_: Exception) {}
            try { rematchOnce(ctx) } catch (_: Exception) {}
            try { rematchUnnamed(ctx) } catch (_: Exception) {}
            try { acceptStrongGuesses(ctx) } catch (_: Exception) {}
            val tp = triesPrefs(ctx)
            val tries = tp.all.mapNotNull { (k, v) -> (v as? Int)?.let { k to it } }.toMap()
            val whys = tp.all.mapNotNull { (k, v) -> if (k.startsWith("kind_")) k.removePrefix("kind_") to ScanTries.why(v as? String) else null }.toMap()
            val todo = MemoryStore.all(ctx).filter { !it.facesScanned }
            val order = ScanTries.order(todo.map { it.id to it.time }, tries, failedThisRun.toSet(), whys)
            val byId = todo.associateBy { it.id }
            var n = 0
            for (id in order.take(limit)) {
                val m = byId[id] ?: continue
                val ok = try {
                    scanMemory(ctx, m)
                } catch (e: FaceId.ModelBroken) {
                    return n // not this moment's fault: stop, the model is downloaded again next time
                } catch (e: Throwable) {
                    tp.edit().putString("why_$id", (e.javaClass.simpleName + ": " + e.message.orEmpty()).take(200)).apply()
                    false
                }
                if (ok) {
                    if (tries.containsKey(id)) tp.edit().remove(id).remove("why_$id").remove("kind_$id").apply()
                } else {
                    failedThisRun.add(id)
                    val t = (tries[id] ?: 0) + 1
                    // Why it failed decides how long to keep trying: a deleted file never, a picture not downloaded yet for days.
                    val why = ScanTries.classify(tp.getString("why_$id", "").orEmpty(), exists(ctx, m))
                    if (ScanTries.giveUp(t, why)) {
                        MemoryStore.modify(ctx, id) { it.copy(facesScanned = true) }
                        tp.edit().remove(id).remove("kind_$id").apply()
                    } else tp.edit().putInt(id, t).putString("kind_$id", why.key).apply()
                }
                n++
            }
            return n
        } finally {
            scanning.set(false)
        }
    }

    /** Once: face data saved before encryption existed is saved again, encrypted (see [SecureFiles]). */
    private fun sealOnce(ctx: Context) {
        val prefs = ctx.getSharedPreferences("regaim", Context.MODE_PRIVATE)
        if (prefs.getBoolean("faces_sealed", false)) return
        synchronized(this) {
            // Only files that were actually read: an unreadable file is never replaced by an empty one.
            if (peopleFile(ctx).exists()) { val p = people(ctx); if (p.isNotEmpty()) savePeople(ctx, p) }
            if (facesFile(ctx).exists()) { val f = faces(ctx); if (f.isNotEmpty()) saveFaces(ctx, f) }
        }
        prefs.edit().putBoolean("faces_sealed", true).apply()
    }

    /**
     * The face model was replaced (the old file wasn't the expected one): every saved face is read again with the
     * new model, and each person is remembered again from their named faces and their profile photo.
     */
    private fun reprintIfNeeded(ctx: Context) {
        val prefs = ctx.getSharedPreferences("regaim", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("faces_reprint", false)) return
        synchronized(this) {
            val fresh = faces(ctx).mapNotNull { f ->
                val bmp = try { android.graphics.BitmapFactory.decodeFile(f.path) } catch (_: Throwable) { null } ?: return@mapNotNull null
                printOf(ctx, bmp)?.let { f.copy(print = it) }
            }
            saveFaces(ctx, fresh)
            val byPerson = fresh.filter { it.personId != null }.groupBy { it.personId }
            savePeople(ctx, people(ctx).map { p -> p.copy(prints = FacePrints.typical(byPerson[p.id].orEmpty().map { it.print }, 15)) })
        }
        prefs.edit().remove("enrolled_family").putBoolean("faces_reprint", false).apply()
    }

    /**
     * Family members with a profile photo become known people right away, so nobody has to name their
     * faces by hand first. Runs once per member photo. Background thread only.
     */
    fun enrollFamily(ctx: Context) {
        if (!FaceId.ready(ctx)) return
        val prefs = ctx.getSharedPreferences("regaim", Context.MODE_PRIVATE)
        val done = prefs.getStringSet("enrolled_family", emptySet())?.toMutableSet() ?: mutableSetOf()
        var changed = false
        for (member in Profile.members(ctx)) {
            if (member.photo.isEmpty() || member.isPet) continue
            val key = "${member.name.trim()}|${member.photo}"
            if (key in done) continue
            val bmp = try { android.graphics.BitmapFactory.decodeFile(member.photo)?.let { downscale(it, 1280) } } catch (_: Exception) { null }
            if (bmp == null) { done.add(key); continue }
            val detector = FaceDetection.getClient(
                FaceDetectorOptions.Builder().setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE).setMinFaceSize(0.1f).build()
            )
            val print = try {
                val faces = Tasks.await(detector.process(InputImage.fromBitmap(bmp, 0)))
                val biggest = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                biggest?.let { cropFace(bmp, it.boundingBox) }?.let { printOf(ctx, it) }
            } catch (_: Exception) { null } finally { detector.close() }
            done.add(key)
            if (print == null) continue
            synchronized(this) {
                val people = people(ctx)
                val name = member.name.trim()
                val existing = people.firstOrNull { it.name == name }
                val person = (existing ?: Person(UUID.randomUUID().toString(), name, member.photo, emptyList()))
                    .let { it.learn(listOf(print)) }
                val updated = people.filterNot { it.id == person.id } + person
                savePeople(ctx, updated)
                // Same rule as everywhere: a face that is almost as close to a sibling isn't guessed.
                saveFaces(ctx, faces(ctx).map { f ->
                    if (f.personId == null && !f.ignored && match(updated, f.print)?.id == person.id) f.copy(personId = person.id) else f
                })
            }
            changed = true
        }
        prefs.edit().putStringSet("enrolled_family", done).apply()
        if (changed) refreshMemoryNames(ctx)
    }

    private fun downscale(b: Bitmap, limit: Int): Bitmap {
        val m = max(b.width, b.height)
        if (m <= limit) return b
        val f = limit.toFloat() / m
        return Bitmap.createScaledBitmap(b, (b.width * f).toInt(), (b.height * f).toInt(), true)
    }

    /** Looks again, more carefully, for the people in one moment, then lets the AI describe it again. */
    fun rescan(ctx: Context, m: Memory) {
        if (!FaceId.ensureModel(ctx)) return
        try { alignOnce(ctx) } catch (_: Exception) {}
        enrollFamily(ctx)
        synchronized(this) { saveFaces(ctx, faces(ctx).filterNot { it.memoryId == m.id }) }
        scanMemory(ctx, MemoryStore.get(ctx, m.id) ?: m, frames = 8)
    }

    /** Returns false when the moment couldn't be read at all (no frame came out of it): it's tried again later. */
    fun scanMemory(ctx: Context, m: Memory, frames: Int = 5): Boolean {
        if (!FaceId.ready(ctx)) return false
        val detector = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setMinFaceSize(0.08f)
                .build()
        )
        class Found(val crop: Bitmap, val print: FloatArray, val area: Int, val side: Int, val flat: Boolean)
        val found = mutableListOf<Found>()
        try {
            val pics = ComicMaker.framesFromClip(ctx, m, frames)
            if (pics.isEmpty()) return false
            for (frame in pics) {
                val faces = try { Tasks.await(detector.process(InputImage.fromBitmap(frame, 0))) } catch (_: Exception) { emptyList() }
                for (f in faces) {
                    val crop = cropFace(frame, f.boundingBox) ?: continue
                    val (print, flat) = faceprint(ctx, crop) ?: continue
                    found.add(Found(crop, print, f.boundingBox.width() * f.boundingBox.height(), minOf(f.boundingBox.width(), f.boundingBox.height()), flat))
                }
            }
        } finally {
            detector.close()
        }
        // The same person usually shows up in all 3 frames: keep the biggest shot of each.
        val unique = mutableListOf<Found>()
        // Faces with visible eyes first: a flat shot of someone already found isn't kept as another face.
        for (f in found.sortedWith(compareBy<Found> { it.flat }.thenByDescending { it.area })) {
            if (unique.none { it.flat == f.flat && FaceId.dist(it.print, f.print) < scaled(SAME_IN_CLIP) }) unique.add(f)
        }
        val people = people(ctx)
        val dir = File(ctx.filesDir, "faces").apply { mkdirs() }
        val shots = unique.take(6).map { u ->
            val id = UUID.randomUUID().toString()
            val path = File(dir, "$id.jpg").absolutePath
            FileOutputStream(path).use { u.crop.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            FaceShot(id, m.id, path, u.print, if (!u.flat && u.side >= MIN_FACE_PX) match(people, u.print)?.id else null, false, m.time, flat = u.flat)
        }
        synchronized(this) { saveFaces(ctx, faces(ctx) + shots) }
        val names = shots.mapNotNull { s -> people.firstOrNull { it.id == s.personId }?.name }.distinct()
        val joined = names.joinToString(", ")
        MemoryStore.modify(ctx, m.id) { fresh ->
            // New names mean the old description doesn't know who is in it: describe it again.
            val again = joined.isNotEmpty() && joined != fresh.people && !fresh.descriptionByUser
            fresh.copy(people = joined, facesScanned = true, analyzed = if (again) false else fresh.analyzed)
        }
        return true
    }

    /** The crop every face print is made from (the book uses the same one, so prints are comparable). */
    internal fun cropFace(src: Bitmap, box: Rect): Bitmap? {
        if (box.width() < 40 || box.height() < 40) return null
        val pad = (max(box.width(), box.height()) * 0.18f).toInt()
        val l = max(0, box.left - pad)
        val t = max(0, box.top - pad)
        val r = min(src.width, box.right + pad)
        val b = min(src.height, box.bottom + pad)
        if (r - l < 40 || b - t < 40) return null
        val side = min(r - l, b - t)
        return Bitmap.createBitmap(src, l + (r - l - side) / 2, t + (b - t - side) / 2, side, side)
    }

    // ---------- grouping and naming ----------

    /** Unnamed faces grouped by likeness, biggest groups first. */
    fun unknownGroups(ctx: Context): List<List<FaceShot>> {
        val groups = mutableListOf<MutableList<FaceShot>>()
        for (f in faces(ctx).filter { it.personId == null && !it.ignored && !it.flat }.sortedByDescending { it.time }) {
            // Joins the group it is closest to on average, not any group with one look-alike in it: one
            // in-between face no longer chains two siblings into one group.
            val g = groups.map { grp -> grp to grp.take(12).map { FaceId.dist(it.print, f.print) }.average() }
                .filter { it.second < scaled(GROUP) }
                .minByOrNull { it.second }?.first
            if (g != null) g.add(f) else groups.add(mutableListOf(f))
        }
        return groups.sortedByDescending { it.size }
    }

    /** Names a group of faces; also finds this person in other unnamed faces right away. */
    @Synchronized
    fun assign(ctx: Context, faceIds: Collection<String>, name: String) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        var people = people(ctx)
        var faces = faces(ctx)
        val picked = faces.filter { it.id in faceIds }
        val existing = people.firstOrNull { it.name == clean }
        // All the picked faces get the name; only the most typical of them teach the app the face, and none
        // that looks more like someone else the family already named.
        val others = people.filterNot { it.id == existing?.id }
        val teach = FacePrints.typical(picked.filter { !it.flat }.map { it.print }.filter { match(others, it) == null }, 6)
        val person = (existing ?: Person(UUID.randomUUID().toString(), clean, picked.firstOrNull()?.path ?: "", emptyList()))
            .let { p -> p.learn(teach) }
        people = people.filterNot { it.id == person.id } + person
        faces = faces.map { f ->
            when {
                f.id in faceIds -> f.copy(personId = person.id, ignored = false, confirmed = true)
                f.personId == null && !f.ignored && !f.flat && match(people, f.print)?.id == person.id -> f.copy(personId = person.id)
                else -> f
            }
        }
        savePeople(ctx, people)
        saveFaces(ctx, faces)
        refreshMemoryNames(ctx)
    }

    /**
     * "זה לא אורי" for many faces at once: they stop being [fromId]'s, the app learns these faces are not that person
     * (so it won't name such faces so again, also in new moments), and other faces it named automatically are checked
     * again. With [toName], they become that person's; without, they wait under "מי זה?".
     */
    @Synchronized
    fun notThem(ctx: Context, faceIds: Collection<String>, fromId: String, toName: String?) {
        val picked = faces(ctx).filter { it.id in faceIds && it.personId == fromId }
        if (picked.isEmpty()) return
        val wrong = picked.map { it.print }
        var people = people(ctx).map { p ->
            if (p.id != fromId) p
            else p.copy(
                // A sample that is really one of these faces was taught by mistake: it goes.
                prints = p.prints.filterNot { s -> wrong.any { FaceId.dist(it, s) < scaled(0.35f) } },
                past = p.past.filterNot { s -> wrong.any { FaceId.dist(it, s) < scaled(0.35f) } },
                rejects = (p.rejects + FacePrints.typical(wrong, 8)).takeLast(30),
            )
        }
        savePeople(ctx, people)
        val ids = picked.map { it.id }.toSet()
        var faces = faces(ctx).map { if (it.id in ids) it.copy(personId = null, ignored = false, confirmed = false) else it }
        // Faces named automatically as this person are checked again with what the app just learned.
        val from = people.firstOrNull { it.id == fromId }
        faces = faces.map { f ->
            if (f.personId != fromId || from == null) f
            else if (from.prints.any { FaceId.dist(it, f.print) < 0.05f }) f // named by hand
            else f.copy(personId = if (f.flat) null else match(people, f.print)?.id)
        }
        saveFaces(ctx, faces)
        if (!toName.isNullOrBlank()) assign(ctx, ids, toName) else refreshMemoryNames(ctx)
    }

    /**
     * "Maybe it's Uri?": for a group of unnamed faces, the person most of them look like, a little looser than
     * naming them automatically (the parent confirms with one tap). Null when there's no clear majority.
     */
    fun guess(people: List<Person>, group: List<FaceShot>): Person? {
        val looked = group.filter { !it.flat }.take(12)
        if (looked.isEmpty()) return null
        val votes = looked.mapNotNull { f ->
            people.mapNotNull { p ->
                val d = p.allPrints.minOfOrNull { FaceId.dist(f.print, it) } ?: return@mapNotNull null
                val r = p.rejects.minOfOrNull { FaceId.dist(f.print, it) }
                if (r != null && r < d) null else p to d
            }.minByOrNull { it.second }?.takeIf { it.second < scaled(GUESS) }?.first
        }
        val top = votes.groupingBy { it.id }.eachCount().maxByOrNull { it.value } ?: return null
        return if (top.value * 2 > looked.size) people.firstOrNull { it.id == top.key } else null
    }

    /** "No, it's not Uri": these unnamed faces won't be suggested (or named) as that person again. */
    @Synchronized
    fun notGuess(ctx: Context, group: List<FaceShot>, personId: String) {
        val wrong = FacePrints.typical(group.map { it.print }, 8)
        savePeople(ctx, people(ctx).map { if (it.id == personId) it.copy(rejects = (it.rejects + wrong).takeLast(30)) else it })
    }

    /**
     * The parent fixed who is in one moment: a name they removed means that person's faces here are not them
     * (learned, like "זה לא אורי"); a name they added, when the moment has exactly one unnamed face, names that face.
     */
    fun learnFromMoment(ctx: Context, memoryId: String, removed: List<String>, added: List<String>) {
        val people = people(ctx)
        for (name in removed) {
            val p = people.firstOrNull { it.name.trim() == name.trim() } ?: continue
            val ids = faces(ctx).filter { it.memoryId == memoryId && it.personId == p.id }.map { it.id }
            if (ids.isNotEmpty()) notThem(ctx, ids, p.id, null)
        }
        val pets = Profile.members(ctx).filter { it.isPet }.map { it.name.trim() }.toSet()
        val people2 = added.map { it.trim() }.filter { it.isNotEmpty() && it !in pets }
        if (people2.size != 1) return
        val unnamed = faces(ctx).filter { it.memoryId == memoryId && it.personId == null && !it.ignored }
        if (unnamed.size == 1) assign(ctx, listOf(unnamed.first().id), people2.first())
    }

    @Synchronized
    fun ignore(ctx: Context, faceIds: Collection<String>) {
        saveFaces(ctx, faces(ctx).map { if (it.id in faceIds) it.copy(ignored = true, personId = null) else it })
    }

    /** Renames a person; if someone already has that name, the two become one person (no duplicates). */
    @Synchronized
    fun rename(ctx: Context, personId: String, name: String) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        val other = people(ctx).firstOrNull { it.id != personId && it.name.trim() == clean }
        if (other != null) { merge(ctx, personId, other.id); return }
        savePeople(ctx, people(ctx).map { if (it.id == personId) it.copy(name = clean) else it })
        refreshMemoryNames(ctx)
    }

    /** Two entries that are the same person (e.g. "יוסף" and "אני (יוסף)"): faces and samples move to [intoId]. */
    @Synchronized
    fun merge(ctx: Context, fromId: String, intoId: String) {
        if (fromId == intoId) return
        val people = people(ctx)
        val from = people.firstOrNull { it.id == fromId } ?: return
        val into = people.firstOrNull { it.id == intoId } ?: return
        val joined = into.copy(past = into.past + from.past, rejects = (into.rejects + from.rejects).takeLast(30), photo = into.photo.ifBlank { from.photo }).learn(from.prints)
        savePeople(ctx, people.filterNot { it.id == fromId || it.id == intoId } + joined)
        saveFaces(ctx, faces(ctx).map { if (it.personId == fromId) it.copy(personId = intoId) else it })
        refreshMemoryNames(ctx)
    }

    /**
     * Cleans up names that mean the same person: "אני" / "אני (יוסף)" become the parent's own name, and two
     * people with exactly the same name become one. Safe to run any time. Background thread only.
     */
    @Synchronized
    fun mergeDuplicates(ctx: Context) {
        val self = Profile.parentName(ctx).trim()
        val alias = Regex("^(אני|me)\\s*[(（]\\s*(.+?)\\s*[)）]$", RegexOption.IGNORE_CASE)
        var changed = true
        var guard = 0
        while (changed && guard++ < 20) {
            changed = false
            val people = people(ctx)
            for (p in people) {
                val n = p.name.trim()
                val target = alias.find(n)?.groupValues?.get(2)?.trim()
                    ?: if ((n == "אני" || n.equals("me", true)) && self.isNotEmpty()) self else null
                if (target != null && target != n) {
                    val into = people.firstOrNull { it.id != p.id && it.name.trim() == target }
                    if (into != null) merge(ctx, p.id, into.id) else rename(ctx, p.id, target)
                    changed = true; break
                }
                val twin = people.firstOrNull { it.id != p.id && it.name.trim() == n }
                if (twin != null) { merge(ctx, twin.id, p.id); changed = true; break }
            }
        }
    }

    @Synchronized
    fun forget(ctx: Context, personId: String) {
        savePeople(ctx, people(ctx).filterNot { it.id == personId })
        saveFaces(ctx, faces(ctx).map { if (it.personId == personId) it.copy(personId = null) else it })
        refreshMemoryNames(ctx)
    }

    /** Writes each moment's list of names from its recognized faces. */
    private fun refreshMemoryNames(ctx: Context) {
        val names = people(ctx).associate { it.id to it.name }
        val byMemory = faces(ctx).groupBy { it.memoryId }
        fun listFor(id: String) = byMemory[id].orEmpty().mapNotNull { f -> f.personId?.let { names[it] } }.distinct().joinToString(", ")
        val changed = MemoryStore.all(ctx).filter { listFor(it.id) != it.people }.map { it.id }.toSet()
        // One write for all moments (was one full rewrite of the index per moment), on the fresh copy of each.
        MemoryStore.updateMany(ctx, changed) { fresh ->
            val list = listFor(fresh.id)
            if (list == fresh.people) fresh
            else fresh.copy(people = list, analyzed = if (list.isNotEmpty() && !fresh.descriptionByUser) false else fresh.analyzed)
        }
    }

    fun countFor(ctx: Context, name: String) = MemoryStore.kept(ctx).count { m -> Fmt.names(m).contains(name) }
}
