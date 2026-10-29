package com.regaim.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Shader
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import kotlin.math.min

/** One saved moment ("memory bubble"). */
data class Memory(
    val id: String,
    val uri: String,
    val name: String,
    val time: Long,
    val label: String,
    val lat: Double?,
    val lon: Double?,
    val place: String,
    val thumb: String,
    val durationMs: Long,
    val description: String = "",
    val tags: String = "",
    val analyzed: Boolean = false,
    val title: String = "",
    val category: String = "",
    val milestone: String = "",
    val favorite: Boolean = false,
    /** Saved on purpose by the user (button / code word). */
    val chosen: Boolean = false,
    /** AI's guess why this moment matters to the user. */
    val reason: String = "",
    /** AI's 1-10 guess how much this fits what the user likes to keep. */
    val score: Int = 0,
    /** Names of recognized people, e.g. "אורי, אמא" (face recognition on the phone). */
    val people: String = "",
    val facesScanned: Boolean = false,
    /** You asked Moments not to learn from this moment. */
    val noLearn: Boolean = false,
    /** Names you added by hand (", "), and names you removed from face recognition. */
    val extraPeople: String = "",
    val hiddenPeople: String = "",
    /** Where in the clip the moment itself happened (the laugh, the word), in ms; -1 = unknown (use the middle). */
    val highlightMs: Long = -1,
    /** Defaults preserve all memories created by the existing recorder. */
    val mediaType: String = "video",
    /** A range explicitly selected by the user; the capture marker is kept separately. */
    val highlightStartMs: Long = -1,
    val highlightEndMs: Long = -1,
    val highlightSource: String = "",
    /** "imported" means the original capture date could not be read. */
    val timeSource: String = "captured",
    val titleByUser: Boolean = false,
    val descriptionByUser: Boolean = false,
    /** Empty on legacy summaries; new cloud descriptions disclose their sampled-image evidence. */
    val descriptionSource: String = "",
    val origin: String = "capture",
    /** Who may see this memory. See [Privacy]; old memories and new ones start private. */
    val privacy: String = "private",
    /** Picture fingerprint taken when the automatic recorder saved it (0 = unknown); see CaptureFilter. */
    val look: Long = 0L,
    /** Pet names (from the profile) recognized in the moment. */
    val pets: String = "",
    val petsScanned: Boolean = false,
    /**
     * "pending": an automatic capture waiting for the family's 👍 / 👎 (the approval queue); "kept": in the archive.
     * Memories saved before the queue existed have no value and read as "kept".
     */
    val review: String = "kept",
    /** The family approved it from the queue (👍). Not a favorite and not "chosen". */
    val approved: Boolean = false,
    /**
     * The AI's suggestion that this may be a milestone ("צעדים ראשונים?"). Only a question to the parent: it becomes
     * [milestone] only when they say yes, and is never used in descriptions. Empty once answered.
     */
    val milestoneGuess: String = "",
    /** When it was added to the app (0: before this was kept). A gallery photo added today can be years old by [time]. */
    val added: Long = 0L,
) {
    val isPending: Boolean get() = review == "pending"
    val privacyLevel: Privacy get() = Privacy.of(privacy)
    val isVideo: Boolean get() = mediaType != "image"

    val previewWindow: PreviewWindow? get() = HighlightPolicy.window(
        isVideo, durationMs, highlightMs, highlightStartMs, highlightEndMs, highlightSource,
    )
    /** Everyone in the moment: recognized faces + names you added − names you removed. */
    val allPeople: String get() {
        fun split(s: String) = s.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val hidden = split(hiddenPeople).toSet()
        return (split(people) + split(pets) + split(extraPeople)).filter { it !in hidden }.distinct().joinToString(", ")
    }

    /** The heart of the clip: the moment of the laugh / word, or the middle. */
    val highlight: Long get() = if (highlightMs in 0..durationMs) highlightMs else durationMs / 2

    /** Day key like 2026-10-05, used to group memories by day. */
    val dayKey: String get() = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(time))

    /** Short title for cards: AI title, else the sound that triggered it. */
    val displayTitle: String get() = title.ifBlank { Labels.he(label) }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("uri", uri); put("name", name); put("time", time); put("label", label)
        if (lat != null) put("lat", lat)
        if (lon != null) put("lon", lon)
        put("place", place); put("thumb", thumb); put("durationMs", durationMs)
        put("description", description); put("tags", tags); put("analyzed", analyzed)
        put("title", title); put("category", category); put("milestone", milestone); put("favorite", favorite)
        put("chosen", chosen); put("reason", reason); put("score", score)
        put("pets", pets); put("petsScanned", petsScanned)
        put("people", people); put("facesScanned", facesScanned); put("noLearn", noLearn)
        put("extraPeople", extraPeople); put("hiddenPeople", hiddenPeople); put("highlightMs", highlightMs)
        put("mediaType", mediaType); put("highlightStartMs", highlightStartMs); put("highlightEndMs", highlightEndMs)
        put("highlightSource", highlightSource); put("timeSource", timeSource)
        put("titleByUser", titleByUser); put("descriptionByUser", descriptionByUser); put("descriptionSource", descriptionSource)
        put("origin", origin); put("privacy", privacy); put("look", look.toString())
        put("review", review); put("approved", approved); put("milestoneGuess", milestoneGuess); put("added", added)
    }

    companion object {
        fun fromJson(o: JSONObject) = Memory(
            id = o.optString("id"),
            uri = o.optString("uri"),
            name = o.optString("name"),
            time = o.optLong("time"),
            label = o.optString("label"),
            lat = if (o.has("lat")) o.optDouble("lat") else null,
            lon = if (o.has("lon")) o.optDouble("lon") else null,
            place = o.optString("place"),
            thumb = o.optString("thumb"),
            durationMs = o.optLong("durationMs"),
            description = o.optString("description"),
            tags = o.optString("tags"),
            analyzed = o.optBoolean("analyzed"),
            title = o.optString("title"),
            category = o.optString("category"),
            milestone = o.optString("milestone"),
            favorite = o.optBoolean("favorite"),
            chosen = o.optBoolean("chosen"),
            reason = o.optString("reason"),
            score = o.optInt("score"),
            pets = o.optString("pets"),
            petsScanned = o.optBoolean("petsScanned"),
            people = o.optString("people"),
            facesScanned = o.optBoolean("facesScanned"),
            noLearn = o.optBoolean("noLearn"),
            extraPeople = o.optString("extraPeople"),
            hiddenPeople = o.optString("hiddenPeople"),
            highlightMs = o.optLong("highlightMs", -1),
            mediaType = if (o.optString("mediaType", "video") == "image") "image" else "video",
            highlightStartMs = o.optLong("highlightStartMs", -1),
            highlightEndMs = o.optLong("highlightEndMs", -1),
            highlightSource = o.optString("highlightSource"),
            timeSource = o.optString("timeSource", "captured"),
            titleByUser = o.optBoolean("titleByUser"),
            descriptionByUser = o.optBoolean("descriptionByUser"),
            descriptionSource = o.optString("descriptionSource"),
            origin = o.optString("origin", "capture"),
            privacy = Privacy.of(o.optString("privacy")).key,
            look = o.optString("look", "0").toLongOrNull() ?: 0L,
            // No field (saved before the queue): kept and not approved. The existing library never enters the queue.
            review = if (o.optString("review") == "pending") "pending" else "kept",
            approved = o.optBoolean("approved", false),
            milestoneGuess = o.optString("milestoneGuess"),
            added = o.optLong("added"),
        )
    }
}

/** Local index of all moments, stored as JSON inside the app. Nothing leaves the phone. */
object MemoryStore {
    private const val INDEX_ERROR_HE = "לא הצלחנו לקרוא את הזיכרונות. הנתונים נשמרו ולא נדרסו."
    private const val INDEX_ERROR_EN = "We couldn't read your memories. Your data is safe and wasn't overwritten."
    val INDEX_ERROR: String get() = L.t(INDEX_ERROR_HE, INDEX_ERROR_EN)
    /** True for the index error in either language (the language may change while the error is set). */
    fun isIndexError(s: String?) = s == INDEX_ERROR_HE || s == INDEX_ERROR_EN
    private fun file(ctx: Context) = File(ctx.filesDir, "memories.json")

    @Synchronized
    fun all(ctx: Context): List<Memory> {
        return try {
            read(ctx).also { if (isIndexError(Status.error)) Status.error = null }
        } catch (_: Exception) {
            Status.error = INDEX_ERROR
            emptyList()
        }
    }

    /** Everything the family sees or builds from (lists, stories, reels, comics, films, sharing, questions): not the queue. */
    fun kept(ctx: Context): List<Memory> = keptOf(all(ctx))

    /** The approval queue, newest first. */
    fun pending(ctx: Context): List<Memory> = all(ctx).filter { it.isPending }

    fun keptOf(list: List<Memory>): List<Memory> = list.filter { !it.isPending }

    /** What the AI may describe: not yet described, and not waiting in the queue (no pending frame leaves the phone). */
    fun toAnalyze(list: List<Memory>): List<Memory> = list.filter { !it.isPending && (!it.analyzed || NamedMoments.waitingForNames(it)) }

    /** 👍 from the queue: into the archive, approved (not a favorite, not "chosen"), then described as usual. */
    fun approve(ctx: Context, id: String) = modify(ctx, id) { it.copy(review = "kept", approved = true) }

    /** Queue items nobody touched for [QueueRules.EXPIRE_MS] go to the trash. Silence isn't a 👎: nothing is learned. */
    fun expirePending(ctx: Context, now: Long = System.currentTimeMillis()) {
        val old = try { read(ctx) } catch (_: Exception) { return }.filter { QueueRules.expired(it, now) }.map { it.id }.toSet()
        if (old.isNotEmpty()) moveToTrash(ctx, old, learn = false)
    }

    /** Mutations use a strict read: a damaged index must never be replaced by an empty one. */
    /**
     * The last list read or written, with the file's time and size then. Reading thousands of moments from the file
     * on every screen refresh was slow; now the file is parsed again only when it really changed.
     */
    @Volatile private var cache: Triple<Long, Long, List<Memory>>? = null

    private fun read(ctx: Context): List<Memory> {
        val f = file(ctx)
        val bak = File(ctx.filesDir, "memories.json.bak")
        if (!f.exists()) return if (bak.exists()) parse(bak) else emptyList()
        val stamp = f.lastModified() to f.length()
        cache?.let { c -> if (c.first == stamp.first && c.second == stamp.second) return c.third }
        return try { parse(f).also { cache = Triple(stamp.first, stamp.second, it) } } catch (e: Exception) {
            // Damaged (e.g. the battery died mid-write): the previous good copy, if there is one.
            if (bak.exists()) parse(bak) else throw e
        }
    }

    private fun parse(f: File): List<Memory> {
        val arr = JSONArray(f.readText())
        return (0 until arr.length()).map { Memory.fromJson(arr.getJSONObject(it)) }.sortedByDescending { it.time }
    }

    /** Read, change and write one moment in one step, so a change made meanwhile elsewhere isn't overwritten. */
    @Synchronized
    fun modify(ctx: Context, id: String, change: (Memory) -> Memory) {
        val list = read(ctx)
        if (list.none { it.id == id }) return
        write(ctx, list.map { if (it.id == id) change(it) else it })
    }

    @Synchronized
    fun add(ctx: Context, m: Memory) {
        write(ctx, read(ctx) + (if (m.added > 0) m else m.copy(added = System.currentTimeMillis())))
        // Find who's in it (on the phone), so new moments are tagged without anyone asking.
        try { FaceScan.kick(ctx) } catch (_: Throwable) { }
    }

    @Synchronized
    fun update(ctx: Context, m: Memory) = write(ctx, read(ctx).map { if (it.id == m.id) m else it })

    /**
     * A change the parent made on screen (♥, a title, names…). A full phone can't save it: say so instead of
     * crashing the app. Main thread only. Returns whether it was saved.
     */
    fun saveFromUi(ctx: Context, work: () -> Unit): Boolean = try {
        work(); true
    } catch (e: Exception) {
        android.widget.Toast.makeText(ctx.applicationContext, L.t("לא הצלחנו לשמור את השינוי. ייתכן שהטלפון מלא: פנו קצת מקום ונסו שוב.",
            "Couldn't save the change. The phone may be full: free some space and try again."), android.widget.Toast.LENGTH_LONG).show()
        false
    }

    /** Describes every moment that wasn't analyzed yet. Background thread only. Returns how many succeeded. */
    private val analyzing = java.util.concurrent.atomic.AtomicBoolean(false)

    fun analyzePending(ctx: Context): Int {
        // Recognize faces first, so the AI can use the people's names.
        try { if (PeopleStore.peopleEnabled(ctx)) PeopleStore.scanPending(ctx) } catch (_: Exception) {}
        try { Pets.scanPending(ctx) } catch (_: Throwable) {}
        // Once: moments already described with nobody recognized lose those words (they come back with names).
        try { clearUnnamedOnce(ctx) } catch (_: Exception) {}
        if (!ClaudeClient.hasKey(ctx)) return 0
        if (!analyzing.compareAndSet(false, true)) return 0
        try {
            return analyzeAll(ctx)
        } finally {
            analyzing.set(false)
        }
    }

    /** Moments described before the "names only" rule with nobody recognized: their AI words are removed, once. */
    private fun clearUnnamedOnce(ctx: Context) {
        val key = "unnamed_words_cleared_v1"
        if (Settings.prefs(ctx).getBoolean(key, false)) return
        synchronized(this) {
            val list = read(ctx)
            if (list.any { NamedMoments.shouldClear(it) }) write(ctx, list.map { if (NamedMoments.shouldClear(it)) NamedMoments.clear(it) else it })
        }
        Settings.prefs(ctx).edit().putBoolean(key, true).apply()
    }

    private fun analyzeAll(ctx: Context): Int {
        var done = 0
        // Moments that keep failing wait (and stop after a few tries) instead of being sent again every time.
        for (m in toAnalyze(all(ctx)).filter { AnalysisRetry.mayTry(ctx, it.id) }) {
            // Nobody known in it: nothing is sent; it waits for a name (then it's described).
            if (!NamedMoments.named(m) && !m.descriptionByUser) {
                synchronized(this) { read(ctx).firstOrNull { it.id == m.id }?.let { update(ctx, NamedMoments.clear(it).copy(analyzed = true)) } }
                continue
            }
            try {
                val result = ClaudeClient.analyze(ctx, m)
                synchronized(this) {
                    val fresh = read(ctx).firstOrNull { it.id == m.id }
                    if (fresh != null) update(ctx, mergeAnalysis(m, fresh, result))
                }
                AnalysisRetry.succeeded(ctx, m.id)
                done++
            } catch (e: ClaudeClient.ApiException) {
                // The AI service itself refused (no budget, a key problem): stop for now, the same for every moment.
                Status.error = L.t("ניתוח AI: ${e.message}", "AI analysis: ${e.message}")
                break
            } catch (e: Exception) {
                AnalysisRetry.failed(ctx, m.id, e)
                if (AnalysisRetry.classify(e) == AnalysisRetry.Kind.NETWORK) break // no connection: the rest would fail too
            }
        }
        if (done > 0) refreshTaste(ctx)
        return done
    }

    /** Merge only analysis-owned fields; user changes during the request always survive. */
    internal fun mergeAnalysis(snapshot: Memory, fresh: Memory, result: Memory): Memory {
        // A response about the previous media/identity context is not evidence about the new one.
        if (snapshot.uri != fresh.uri || snapshot.mediaType != fresh.mediaType || snapshot.allPeople != fresh.allPeople ||
            snapshot.place != fresh.place || snapshot.time != fresh.time || snapshot.timeSource != fresh.timeSource) {
            return fresh.copy(analyzed = fresh.descriptionByUser && fresh.description.isNotBlank())
        }
        return fresh.copy(
        title = if (!fresh.titleByUser && fresh.title == snapshot.title) result.title else fresh.title,
        description = if (!fresh.descriptionByUser && fresh.description == snapshot.description) result.description else fresh.description,
        descriptionSource = if (!fresh.descriptionByUser && fresh.description == snapshot.description) result.descriptionSource else fresh.descriptionSource,
        tags = if (fresh.tags == snapshot.tags) result.tags else fresh.tags,
        category = if (fresh.category == snapshot.category) result.category else fresh.category,
        milestone = if (fresh.milestone == snapshot.milestone) result.milestone else fresh.milestone,
        // A suggestion only for a moment the parent hasn't marked, and not one they already answered.
        milestoneGuess = if (fresh.milestone.isEmpty() && fresh.milestoneGuess == snapshot.milestoneGuess && fresh.milestoneGuess != "-") result.milestoneGuess else fresh.milestoneGuess,
        reason = if (!fresh.descriptionByUser && fresh.reason == snapshot.reason) result.reason else fresh.reason,
        score = result.score,
        analyzed = true,
        )
    }

    fun get(ctx: Context, id: String): Memory? = all(ctx).firstOrNull { it.id == id }

    // ---------- day stories ("סיפור היום") ----------

    private fun storiesFile(ctx: Context) = File(ctx.filesDir, "stories.json")

    /**
     * After the app language changes: the day stories are written again, and the 40 newest AI descriptions
     * (not ones the family wrote) are described again, in the new language.
     */
    @Synchronized
    fun relanguage(ctx: Context) {
        storiesFile(ctx).delete()
        val recent = read(ctx).filter { it.analyzed && !it.descriptionByUser && !it.titleByUser }.take(40).map { it.id }.toSet()
        if (recent.isNotEmpty()) write(ctx, read(ctx).map { if (it.id in recent) it.copy(analyzed = false) else it })
    }

    @Synchronized
    fun story(ctx: Context, dayKey: String): String {
        val f = storiesFile(ctx)
        if (!f.exists()) return ""
        return try { JSONObject(f.readText()).optJSONObject(dayKey)?.optString("text") ?: "" } catch (_: Exception) { "" }
    }

    @Synchronized
    private fun storyCount(ctx: Context, dayKey: String): Int {
        val f = storiesFile(ctx)
        if (!f.exists()) return -1
        return try { JSONObject(f.readText()).optJSONObject(dayKey)?.optInt("count", -1) ?: -1 } catch (_: Exception) { -1 }
    }

    @Synchronized
    private fun saveStory(ctx: Context, dayKey: String, text: String, count: Int) {
        val f = storiesFile(ctx)
        val root = try { if (f.exists()) JSONObject(f.readText()) else JSONObject() } catch (_: Exception) { JSONObject() }
        root.put(dayKey, JSONObject().put("text", text).put("count", count))
        if (!SafeFiles.write(f, root.toString())) throw java.io.IOException(L.t("לא הצלחנו לשמור", "Couldn't save"))
    }

    /** Writes or refreshes the story of each day whose moments changed. Background thread only. */
    fun refreshStories(ctx: Context, maxDays: Int = 7): Boolean {
        if (!ClaudeClient.hasKey(ctx)) return false
        var changed = false
        val byDay = kept(ctx).filter { it.analyzed }.groupBy { it.dayKey }.toList().sortedByDescending { it.first }.take(maxDays)
        for ((day, items) in byDay) {
            if (items.size < 2 || storyCount(ctx, day) == items.size) continue
            try {
                saveStory(ctx, day, ClaudeClient.dayStory(ctx, items.sortedBy { it.time }), items.size)
                changed = true
            } catch (_: Exception) {
                break
            }
        }
        return changed
    }

    // ---------- learning what the user likes (see Learning.kt) ----------

    fun taste(ctx: Context): String = Learning.tasteText(ctx)

    fun refreshTaste(ctx: Context, force: Boolean = false): Boolean = Learning.refresh(ctx, force)

    @Synchronized
    fun remove(ctx: Context, id: String) {
        val list = read(ctx)
        list.firstOrNull { it.id == id }?.let { Learning.rememberRejected(ctx, it) }
        list.firstOrNull { it.id == id }?.let { File(it.thumb).delete() }
        write(ctx, list.filterNot { it.id == id })
    }

    // ---------- trash ("סל המחזור", like Google Photos: 30 days, then gone) ----------

    const val TRASH_DAYS = 30L
    private fun trashFile(ctx: Context) = File(ctx.filesDir, "trash.json")

    class Trashed(val memory: Memory, val at: Long) {
        val daysLeft: Int get() = (TRASH_DAYS - (System.currentTimeMillis() - at) / 86_400_000L).toInt().coerceAtLeast(0)
    }

    private fun readTrash(ctx: Context): List<Trashed> = try {
        val f = trashFile(ctx)
        if (!f.exists()) emptyList() else {
            val a = JSONArray(f.readText())
            (0 until a.length()).map { val o = a.getJSONObject(it); Trashed(Memory.fromJson(o.getJSONObject("m")), o.getLong("at")) }
        }
    } catch (_: Exception) { emptyList() }

    private fun writeTrash(ctx: Context, list: List<Trashed>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("m", it.memory.toJson()).put("at", it.at)) }
        val tmp = File(ctx.filesDir, "trash.json.tmp")
        tmp.writeText(a.toString())
        if (!tmp.renameTo(trashFile(ctx))) throw java.io.IOException(L.t("לא הצלחנו לשמור את סל המחזור", "Couldn't save the trash"))
    }

    /** Newest first; anything older than 30 days is removed for good on the way. */
    @Synchronized
    fun trash(ctx: Context): List<Trashed> {
        val now = System.currentTimeMillis()
        val (keep, old) = readTrash(ctx).partition { now - it.at < TRASH_DAYS * 86_400_000L }
        if (old.isNotEmpty()) {
            old.forEach { File(it.memory.thumb).delete(); dropPrivateMedia(ctx, it.memory) }
            try { writeTrash(ctx, keep) } catch (_: Exception) { }
        }
        return keep.sortedByDescending { it.at }
    }

    /** Moves moments to the trash (undo-able). Deleting teaches the recorder what not to keep. */
    @Synchronized
    fun moveToTrash(ctx: Context, ids: Set<String>, learn: Boolean = true) {
        if (ids.isEmpty()) return
        val list = read(ctx)
        val gone = list.filter { it.id in ids }
        if (gone.isEmpty()) return
        val now = System.currentTimeMillis()
        writeTrash(ctx, readTrash(ctx) + gone.map { Trashed(it, now) })
        write(ctx, list.filterNot { it.id in ids })
        // A moment the recorder itself replaced (a bigger laugh came) is not a "don't keep this" signal.
        if (learn) gone.forEach { try { Learning.rememberRejected(ctx, it) } catch (_: Exception) { } }
        // A found moment the family deleted teaches the moment finder too.
        if (learn) gone.forEach { try { Detections.feedbackFor(ctx, it.id, -1) } catch (_: Exception) { } }
    }

    /** Brings moments back from the trash (and un-learns them). */
    @Synchronized
    fun restore(ctx: Context, ids: Set<String>) {
        if (ids.isEmpty()) return
        val t = readTrash(ctx)
        val back = t.filter { it.memory.id in ids }.map { it.memory }
        if (back.isEmpty()) return
        val current = read(ctx)
        write(ctx, current + back.filter { b -> current.none { it.id == b.id } })
        writeTrash(ctx, t.filterNot { it.memory.id in ids })
        back.forEach { CaptureFilter.forgetLook(ctx, it.look) }
    }

    /** A moment kept only inside the app: its video or photo goes with it for good (a gallery file stays the family's). */
    private fun dropPrivateMedia(ctx: Context, m: Memory) {
        try { if (ClipSaver.isPrivate(ctx, m.uri)) File(m.uri.removePrefix("file://")).delete() } catch (_: Exception) { }
    }

    /** Deletes from the trash for good. */
    @Synchronized
    fun deleteForever(ctx: Context, ids: Set<String>) {
        val t = readTrash(ctx)
        t.filter { it.memory.id in ids }.forEach { File(it.memory.thumb).delete(); dropPrivateMedia(ctx, it.memory) }
        writeTrash(ctx, t.filterNot { it.memory.id in ids })
    }

    /** Deletes several moments at once (the original gallery files stay). */
    @Synchronized
    fun removeMany(ctx: Context, ids: Set<String>) {
        if (ids.isEmpty()) return
        val list = read(ctx)
        list.filter { it.id in ids }.forEach { Learning.rememberRejected(ctx, it); File(it.thumb).delete() }
        write(ctx, list.filterNot { it.id in ids })
    }

    /** Changes several moments in one write. */
    @Synchronized
    fun updateMany(ctx: Context, ids: Set<String>, change: (Memory) -> Memory) {
        if (ids.isEmpty()) return
        write(ctx, read(ctx).map { if (it.id in ids) change(it) else it })
    }

    private fun write(ctx: Context, list: List<Memory>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        val tmp = File(ctx.filesDir, "memories.json.tmp")
        // Written to disk for real (fsync) before it replaces the index, and the last good index is kept as .bak.
        java.io.FileOutputStream(tmp).use { out -> out.write(arr.toString().toByteArray()); out.fd.sync() }
        val f = file(ctx)
        if (!tmp.renameTo(f)) { cache = null; throw java.io.IOException(L.t("לא הצלחנו לשמור את הזיכרונות", "Couldn't save your memories")) }
        // The backup is taken from what was just written whole (never from a file that may be the damaged one).
        try { SafeFiles.writeBytes(File(ctx.filesDir, "memories.json.bak"), f.readBytes()) } catch (_: Exception) { }
        cache = Triple(f.lastModified(), f.length(), list.sortedByDescending { it.time })
    }

    /** Crops a frame into a round "bubble" and saves it as PNG. Returns the path. */
    fun saveBubble(ctx: Context, id: String, frame: Bitmap, sizePx: Int = 320): String {
        val side = min(frame.width, frame.height)
        val square = Bitmap.createBitmap(frame, (frame.width - side) / 2, (frame.height - side) / 2, side, side)
        val scaled = Bitmap.createScaledBitmap(square, sizePx, sizePx, true)
        val out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val r = sizePx / 2f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = BitmapShader(scaled, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }
        canvas.drawCircle(r, r, r - 6f, paint)
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 8f
            color = Color.parseColor("#1A6FCC")
        }
        canvas.drawCircle(r, r, r - 6f, ring)

        val dir = File(ctx.filesDir, "bubbles").apply { mkdirs() }
        val f = File(dir, "$id.png")
        FileOutputStream(f).use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return f.absolutePath
    }
}
