package com.regaim.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * Pitzki's side of the app: which child, how the child looks (from what the family already has), the family's own
 * photos for each color, and the forest scenes drawn with the child in them (kept on the phone, per child).
 */
object PitzkiStore {
    /** A child from the family profile, with what Pitzki can show of them. */
    class Child(val memberId: String, val kid: PitzkiKid, val look: Look?, val birth: String = "", val photos: List<File> = emptyList())

    /**
     * How the child looks: [file] is a picture on the phone. [drawn] = an approved character from a book
     * (already a drawing); otherwise a real photo (profile picture or the face the app learned).
     */
    class Look(val file: File, val drawn: Boolean, val hero: Boolean = false) {
        /** Changes when the picture changes, so the scenes are drawn again for the new look. */
        val key: String get() = "${file.absolutePath}:${file.length()}:${file.lastModified()}"
    }

    fun children(ctx: Context): List<Child> = kidsInOrder(Profile.members(ctx).filter { it.isChild && it.name.isNotBlank() }.map { m ->
        Child(m.id, PitzkiKid(PitzkiScript.firstName(m.name), girl = m.relation == "ילדה"), look(ctx, m), m.birth, photos(ctx, m))
    }, prefs(ctx).getString("kids_order", "").orEmpty().split(",").filter { it.isNotBlank() })

    /**
     * The children in the parent's order (dragged into place); before that, and for a child added since: oldest
     * first (by birth date, "yyyy-mm-dd"; no date: last), as they were.
     */
    fun kidsInOrder(kids: List<Child>, saved: List<String>): List<Child> {
        val byAge = kids.withIndex().sortedWith(compareBy({ it.value.birth.isBlank() }, { it.value.birth }, { it.index })).map { it.value }
        if (saved.isEmpty()) return byAge
        return byAge.sortedBy { k -> saved.indexOf(k.memberId).let { if (it < 0) Int.MAX_VALUE else it } }
    }

    /** The parent dragged the children into this order. */
    fun setKidsOrder(ctx: Context, ids: List<String>) = prefs(ctx).edit().putString("kids_order", ids.joinToString(",")).apply()

    /** Clear face photos the parent chose for Pitzki's film (up to 3): the child is drawn from these first. */
    fun facePhotos(ctx: Context, member: String): List<File> = (1..3).map { File(dir(ctx, member), "face_$it.jpg") }.filter { it.exists() && it.length() > 0 }

    /** Background thread. The guided pictures ([FaceCaptureActivity]) replace the earlier face photos. */
    fun saveFaceBitmaps(ctx: Context, member: String, pics: List<Bitmap>): Int {
        if (pics.isEmpty()) return 0
        (1..3).forEach { File(dir(ctx, member), "face_$it.jpg").delete() }
        var n = 0
        for (b in pics.take(3)) {
            val out = java.io.ByteArrayOutputStream()
            b.compress(Bitmap.CompressFormat.JPEG, 92, out)
            if (SafeFiles.writeBytes(File(dir(ctx, member), "face_${n + 1}.jpg"), out.toByteArray())) n++
        }
        return n
    }

    /** Background thread. The parent's chosen photos replace the earlier ones; returns how many were saved. */
    fun saveFacePhotos(ctx: Context, member: String, uris: List<android.net.Uri>): Int {
        val pics = uris.take(3).mapNotNull { MediaImages.decode(ctx, it.toString(), 1280) }
        if (pics.isEmpty()) return 0
        (1..3).forEach { File(dir(ctx, member), "face_$it.jpg").delete() }
        var n = 0
        for (b in pics) {
            val out = java.io.ByteArrayOutputStream()
            b.compress(Bitmap.CompressFormat.JPEG, 90, out)
            if (SafeFiles.writeBytes(File(dir(ctx, member), "face_${n + 1}.jpg"), out.toByteArray())) n++
        }
        return n
    }

    /** Real photos of the child: the ones the parent chose for the film first, then the profile picture and the face the app learned (up to 4). */
    private fun photos(ctx: Context, m: FamilyMember): List<File> {
        val out = ArrayList<File>(facePhotos(ctx, m.id))
        m.photo.takeIf { it.isNotBlank() }?.let { File(it) }?.takeIf { it.exists() }?.let { out.add(it) }
        PeopleStore.people(ctx).filter { it.name.trim() == m.name.trim() }.mapNotNull { p -> p.photo.takeIf { it.isNotBlank() }?.let { File(it) } }
            .filter { it.exists() && out.none { o -> o.absolutePath == it.absolutePath } }.forEach { out.add(it) }
        return out.take(4)
    }

    private fun look(ctx: Context, m: FamilyMember): Look? {
        // The film character the parent approved (Pixar-like, from the real photos): every scene is drawn from it.
        heroFile(ctx, m.id).takeIf { it.exists() && it.length() > 0 }?.let { return Look(it, drawn = true, hero = true) }
        CharacterStore.latest(ctx, m.id)?.let { c -> CharacterStore.file(ctx, c).takeIf { it.exists() }?.let { return Look(it, drawn = true) } }
        m.photo.takeIf { it.isNotBlank() }?.let { File(it) }?.takeIf { it.exists() }?.let { return Look(it, drawn = false) }
        PeopleStore.people(ctx).firstOrNull { it.name.trim() == m.name.trim() }?.photo?.takeIf { it.isNotBlank() }
            ?.let { File(it) }?.takeIf { it.exists() }?.let { return Look(it, drawn = false) }
        return null
    }

    // ---------- bundled art ----------

    /** Pitzki's own art, in the app (assets/pitzki): "pitzki" (whole body), "face", "forest" (the wide scene). */
    fun art(ctx: Context, name: String): Bitmap? = try {
        ctx.assets.open("pitzki/$name.jpg").use { BitmapFactory.decodeStream(it) }
    } catch (_: Exception) { null }

    /** A recorded line, if one was added to the app (assets/pitzki/voice/<id>.mp3). */
    fun hasRecording(ctx: Context, id: String): Boolean = try {
        ctx.assets.openFd("pitzki/voice/$id.mp3").close(); true
    } catch (_: Exception) { false }

    // ---------- progress ----------

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("pitzki", Context.MODE_PRIVATE)
    /** What Pitzki remembers about this child ([PitzkiMemory]). */
    fun memory(ctx: Context, member: String): Map<String, Int> = PitzkiMemory.parse(prefs(ctx).getString("mem_$member", "").orEmpty())
    fun remember(ctx: Context, member: String, id: String, rightFirstTime: Boolean) =
        prefs(ctx).edit().putString("mem_$member", PitzkiMemory.write(PitzkiMemory.record(memory(ctx, member), id, rightFirstTime))).apply()

    /** The child's level in a world ([PitzkiLevels]): 1 until a perfect walk moves it up. */
    fun level(ctx: Context, member: String, world: String) = prefs(ctx).getInt("lvl_${member}_$world", 1).coerceIn(1, PitzkiLevels.max(world))
    fun setLevel(ctx: Context, member: String, world: String, level: Int) = prefs(ctx).edit().putInt("lvl_${member}_$world", level).apply()

    fun stars(ctx: Context, member: String) = prefs(ctx).getInt("stars_$member", 0)
    fun addStars(ctx: Context, member: String, n: Int) = prefs(ctx).edit().putInt("stars_$member", stars(ctx, member) + n).apply()
    /** The parent's choice: Pitzki listens to the child's answers (microphone). On unless the parent turned it off. */
    fun listenOn(ctx: Context) = prefs(ctx).getBoolean("listen_on", true)
    fun setListen(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("listen_on", on).apply()
    /** The little cloud that explains the microphone button: shown once. */
    fun micTipShown(ctx: Context) = prefs(ctx).getBoolean("mic_tip", false)
    fun setMicTipShown(ctx: Context) = prefs(ctx).edit().putBoolean("mic_tip", true).apply()
    fun lessonsDone(ctx: Context, member: String) = prefs(ctx).getInt("done_$member", 0)
    fun markDone(ctx: Context, member: String) = prefs(ctx).edit().putInt("done_$member", lessonsDone(ctx, member) + 1).apply()

    // ---------- forest scenes drawn with the child ----------

    private fun dir(ctx: Context, member: String) = File(File(ctx.filesDir, "pitzki"), member.filter { it.isLetterOrDigit() || it == '-' || it == '_' }).apply { mkdirs() }
    fun sceneFile(ctx: Context, member: String, key: String) = File(dir(ctx, member).also { migrate(it) }, "scene_$key.jpg")
    private fun meta(ctx: Context, member: String) = File(dir(ctx, member), "scenes.json")
    /** A scene come to life (a few seconds of animation of the drawn scene: Pitzki and the child move). */
    fun clipFile(ctx: Context, member: String, key: String) = File(dir(ctx, member).also { migrate(it) }, "clip_$key.mp4")

    /** The first version named scenes by number (1–8); they keep their drawing and animation under their name. */
    private val OLD = listOf("intro", "walk", "green", "brown", "red", "yellow", "gray", "end")
    private fun migrate(d: File) {
        if (!File(d, "scene_1.jpg").exists() && !File(d, "clip_1.mp4").exists()) return
        OLD.forEachIndexed { i, key ->
            for ((from, to) in listOf("scene_${i + 1}.jpg" to "scene_$key.jpg", "clip_${i + 1}.mp4" to "clip_$key.mp4")) {
                val f = File(d, from)
                if (f.exists()) { val t = File(d, to); if (!t.exists()) f.renameTo(t) else f.delete() }
            }
        }
    }

    /** How many scenes of the child's film move (their own, or Pitzki's shared close-ups). */
    fun clips(ctx: Context, c: Child): Int =
        if (!scenesReady(ctx, c)) 0 else PitzkiScript.KEYS.count { clipFor(ctx, c.memberId, it) != null }

    /**
     * The picture of a scene for this child: their own drawing when there is one (the child is in it), otherwise
     * Pitzki's shared close-up (the same for every family). Null: neither yet (the lesson shows the forest).
     */
    fun sceneFor(ctx: Context, member: String?, key: String): File? {
        if (member != null) sceneFile(ctx, member, key).takeIf { it.exists() }?.let { return it }
        return sharedScene(ctx, key).takeIf { it.exists() && it.length() > 0 }
    }

    /** The animation that goes with [sceneFor] (never a shared clip over the family's own drawing). */
    fun clipFor(ctx: Context, member: String?, key: String): File? {
        if (member != null && sceneFile(ctx, member, key).exists()) return clipFile(ctx, member, key).takeIf { it.exists() && it.length() > 0 }
        if (!sharedScene(ctx, key).exists()) return null
        return sharedClip(ctx, key).takeIf { it.exists() && it.length() > 0 }
    }

    /** The look the saved scenes were drawn from, or "". */
    private fun drawnFrom(ctx: Context, member: String): String = when (val r = SafeFiles.readObject(meta(ctx, member))) {
        is SafeFiles.Read.Ok -> r.value.optString("look")
        else -> ""
    }

    /** The parent said this look (the book's character, or a new one) is their child, for the film. */
    fun lookConfirmed(ctx: Context, c: Child): Boolean = c.look != null && prefs(ctx).getString("look_ok_${c.memberId}", "") == c.look.key
    fun confirmLook(ctx: Context, c: Child) { c.look?.let { prefs(ctx).edit().putString("look_ok_${c.memberId}", it.key).apply() } }

    /** Once: the opening scene's animation made before the warmer high-five opening is made again. */
    fun upgrade(ctx: Context) {
        if (prefs(ctx).getInt("intro_motion_v", 1) >= 2) return
        try { File(ctx.filesDir, "pitzki").listFiles()?.filter { it.isDirectory && it.name != "shared" }?.forEach { File(it, "clip_intro.mp4").delete() } } catch (_: Exception) {}
        prefs(ctx).edit().putInt("intro_motion_v", 2).apply()
    }

    /** Scenes of the child's own that are drawn but don't move yet. */
    fun needsAnimation(ctx: Context, c: Child): Boolean =
        scenesReady(ctx, c) && PitzkiScript.KEYS.any { sceneFile(ctx, c.memberId, it).exists() && !clipFile(ctx, c.memberId, it).exists() }

    /** The scenes the child is in are drawn, from the child's current look (the close-ups are Pitzki's, shared). */
    fun scenesReady(ctx: Context, c: Child): Boolean {
        val look = c.look ?: return false
        return drawnFrom(ctx, c.memberId) == look.key && PitzkiScript.CHILD_KEYS.all { sceneFile(ctx, c.memberId, it).exists() }
    }

    /** How many scenes are already drawn from the child's current look (when the lesson grew, some are missing). */
    fun partial(ctx: Context, c: Child): Int {
        val look = c.look ?: return 0
        if (drawnFrom(ctx, c.memberId) != look.key) return 0
        return PitzkiScript.CHILD_KEYS.count { sceneFile(ctx, c.memberId, it).exists() }
    }

    /** Scenes exist but from an older look (a new character was approved since). */
    fun scenesOutdated(ctx: Context, c: Child): Boolean =
        c.look != null && drawnFrom(ctx, c.memberId).isNotEmpty() && drawnFrom(ctx, c.memberId) != c.look.key

    /**
     * Removes the child's drawn scenes of the world being played (the lesson goes back to the circle look). Only this
     * world's: the other worlds' scenes, and what says which look they were drawn from, stay as they are. They're
     * not thrown away but kept aside (the last set per world), so a parent who drew again by mistake can take them back.
     */
    fun deleteScenes(ctx: Context, member: String) {
        val files = PitzkiScript.KEYS.flatMap { listOf(sceneFile(ctx, member, it), clipFile(ctx, member, it), moreFile(ctx, member, it)) } +
            listOf(startFile(ctx, member), handFile(ctx, member))
        val have = files.filter { it.exists() && it.length() > 0 }
        if (have.isNotEmpty()) {
            val keep = trash(ctx, member)
            keep.listFiles()?.forEach { it.delete() }
            keep.mkdirs()
            for (f in have) { val to = File(keep, f.name); if (!f.renameTo(to)) try { f.copyTo(to, overwrite = true); f.delete() } catch (_: Exception) { f.delete() } }
        }
        files.forEach { it.delete() }
        Settings.prefs(ctx).edit().apply { PitzkiScript.KEYS.forEach { remove(drawerKey(member, it)) } }.apply()
        pendingFile(ctx, member).delete()
        newFilm(ctx, member)
    }

    /** Where this world's last removed scenes are kept. */
    private fun trash(ctx: Context, member: String) = File(dir(ctx, member), "before_${PitzkiScript.worldId}")

    /** How many of this world's earlier scenes can be taken back. */
    fun earlierScenes(ctx: Context, member: String): Int =
        trash(ctx, member).listFiles()?.count { it.name.startsWith("scene_") && !it.name.contains("_start") } ?: 0

    /**
     * The earlier scenes come back (and the ones there now are kept aside instead, so this can be undone too). They
     * count as drawn from the child's current look, and the opening as approved (it was, when they were made).
     */
    fun restoreScenes(ctx: Context, c: Child): Boolean {
        if (drawing?.finished == false) return false
        val keep = trash(ctx, c.memberId)
        val old = keep.listFiles()?.filter { it.isFile }.orEmpty()
        if (old.isEmpty()) return false
        val d = dir(ctx, c.memberId)
        val swap = File(d, "swap_${System.currentTimeMillis()}").apply { mkdirs() }
        // What's there now -> aside; the earlier ones -> back.
        val now = PitzkiScript.KEYS.flatMap { listOf(sceneFile(ctx, c.memberId, it), clipFile(ctx, c.memberId, it), moreFile(ctx, c.memberId, it)) } +
            listOf(startFile(ctx, c.memberId), handFile(ctx, c.memberId))
        for (f in now) if (f.exists()) f.renameTo(File(swap, f.name))
        for (f in old) f.renameTo(File(d, f.name))
        keep.listFiles()?.forEach { it.delete() }
        swap.listFiles()?.forEach { it.renameTo(File(keep, it.name)) }
        swap.delete()
        SafeFiles.write(meta(ctx, c.memberId), JSONObject().put("look", c.look?.key.orEmpty()).put("started", System.currentTimeMillis()).toString())
        Settings.prefs(ctx).edit().putString("pitzki_intro_ok_${c.memberId}_${PitzkiScript.INTRO_KEY}", introMark(ctx, c)).apply()
        newFilm(ctx, c.memberId)
        return true
    }

    // ---------- the parents' shared gallery: a child's Pitzki film from the other parent's phone ----------

    /** The files of [member]'s film (its drawings and animations), to share with the other parent. */
    fun filmFiles(ctx: Context, member: String): List<File> =
        dir(ctx, member).listFiles()?.filter { f -> f.isFile && f.length() > 0 &&
            ((f.name.startsWith("scene_") && f.name.endsWith(".jpg")) || (f.name.startsWith("clip_") && f.name.endsWith(".mp4"))) }.orEmpty()

    /**
     * Background thread. The other parent's film of this child becomes this phone's too: each file is fetched by
     * [fetch], and the film counts as drawn from this phone's look of the child (nothing is drawn again, and the
     * first scene doesn't wait for approval: the other parent approved it).
     */
    fun adoptFilm(ctx: Context, c: Child, names: List<String>, fetch: (String, File) -> Boolean): Int {
        val d = dir(ctx, c.memberId)
        var got = 0
        for (n in names) {
            if (!(n.startsWith("scene_") || n.startsWith("clip_")) || n.contains('/') || n.contains("..")) continue
            if (fetch(n, File(d, n))) got++
        }
        if (got > 0) {
            SafeFiles.write(meta(ctx, c.memberId), JSONObject().put("look", c.look?.key.orEmpty()).put("started", System.currentTimeMillis()).toString())
            Settings.prefs(ctx).edit().putString("pitzki_intro_ok_${c.memberId}_${PitzkiScript.INTRO_KEY}", introMark(ctx, c))
                .putBoolean("pitzki_intro2_${c.memberId}", true).apply()
        }
        return got
    }

    /** What's being drawn right now (one child at a time), for the screen to show. */
    class Drawing(val member: String, @Volatile var done: Int = 0, @Volatile var failed: String = "", @Volatile var finished: Boolean = false) {
        /** "draw" (the pictures), then "anim" (bringing them to life); "character" for a new character. */
        @Volatile var phase = "draw"
            set(v) { field = v; phaseAt = System.currentTimeMillis() }
        /** When the current phase started. */
        @Volatile var phaseAt = System.currentTimeMillis()
        /** Pictures actually drawn in this run (not the ones that were already there): for the time per picture. */
        @Volatile var made = 0
        /** When the last picture was finished (or the phase started). */
        @Volatile var lastAt = System.currentTimeMillis()
        /** Whether the animation follows the drawings in this run (for the overall percent). */
        @Volatile var animateAfter = false
        @Volatile var animated = 0
        /** How many scenes this run draws / animates (for the progress bar). */
        @Volatile var total = 0
        /** What's happening right now, when it's worth saying (a retry, an error from the server). */
        @Volatile var note = ""
        /** What is being made, for the notification ("Ori's film"). */
        @Volatile var title = ""
        /** Stopped after the first scene, for the parent to say "yes, that's my child" before the rest is paid. */
        @Volatile var waitingApproval = false
        /** When it started (for "about N more minutes"). */
        val started = System.currentTimeMillis()

        /**
         * How far along the whole job is (0–99 %) and about how many seconds are left. Measured where it can be
         * (the time each picture really took), from typical times otherwise: a drawing ~1 minute (Pitzki's
         * shared close-ups ~35 s), all the animations together ~5 minutes (they're made at the same time).
         */
        fun eta(): Pair<Int, Int> {
            val now = System.currentTimeMillis()
            val inPhase = ((now - phaseAt) / 1000).toInt()
            val animPlan = if (member == "talk") 240 else 300
            when (phase) {
                "character" -> return (inPhase * 100 / 90).coerceIn(1, 95) to (90 - inPhase).coerceAtLeast(10)
                // (The farm too: its talking videos made the bar sit at 97% "a minute left" for minutes.)
                "anim" -> if ((member == "talks" || member == "farm") && total > 0) {
                    // Pitzki's talking videos, one after another: ~7 minutes each (measured once some are made).
                    val per = if (animated > 0) (inPhase / animated).coerceIn(120, 900) else 420
                    val sinceLast = ((now - maxOf(lastAt, phaseAt)) / 1000).toInt()
                    val left = ((total - animated) * per - sinceLast.coerceIn(0, per - 30)).coerceAtLeast(30)
                    val p = (100 - left * 100 / (total * per)).coerceIn(1, 97)
                    return p to left
                } else {
                    val byTime = inPhase * 100 / animPlan
                    val byCount = if (total > 0) animated * 100 / total else 0
                    val p = maxOf(byTime, byCount).coerceIn(1, 97)
                    val left = (animPlan - inPhase).coerceAtLeast(if (p >= 90) 20 else 45)
                    return p to left
                }
                else -> {
                    val per = if (made > 0) (inPhase / made).coerceIn(15, 180) else if (member == "shared") 35 else 60
                    val drawLeftCount = (total - done).coerceAtLeast(0)
                    // The picture being drawn now is partly done already.
                    val sinceLast = ((now - maxOf(lastAt, phaseAt)) / 1000).toInt()
                    val drawLeft = (drawLeftCount * per - sinceLast.coerceIn(0, per - 5)).coerceAtLeast(if (drawLeftCount > 0) 10 else 0)
                    val anim = if (animateAfter) animPlan else 0
                    val whole = total * per + anim
                    val left = drawLeft + anim
                    val p = if (whole <= 0) 1 else (100 - left * 100 / whole).coerceIn(1, 95)
                    return p to left
                }
            }
        }
    }

    @Volatile var drawing: Drawing? = null
        private set
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /**
     * Draws the 8 forest scenes with the child (8 pictures through Moments' server). Keeps going if the screen is
     * left; scenes already drawn from the same look are kept, so a stop in the middle continues where it was.
     * False if a drawing is already running.
     */
    fun drawScenes(ctx: Context, c: Child, animate: Boolean = false): Boolean {
        val look = c.look ?: return false
        if (PitzkiScript.CHILD_KEYS.isEmpty()) return false // a world without the child's scenes (the numbers)
        if (drawing?.finished == false) return false
        val app = ctx.applicationContext
        val d = Drawing(c.memberId).apply { title = L.t("הסרט של ${c.kid.name}", "${c.kid.name}'s film") }
        drawing = d
        PitzkiService.start(app) // keeps going (and connected) when the parent leaves the app
        worker.execute {
            try {
                if (drawnFrom(app, c.memberId) != look.key) {
                    deleteScenes(app, c.memberId)
                    SafeFiles.write(meta(app, c.memberId), JSONObject().put("look", look.key).put("started", System.currentTimeMillis()).toString())
                }
                val pitzki = art(app, "pitzki") ?: throw IllegalStateException(L.t("חסרה התמונה של פיצקי באפליקציה", "Pitzki's picture is missing from the app"))
                val child = BitmapFactory.decodeFile(look.file.absolutePath) ?: throw IllegalStateException(L.t("לא הצלחנו לפתוח את התמונה של ${c.kid.name}", "Couldn't open ${c.kid.name}'s picture"))
                // A drawn character keeps the style; a real photo next to it keeps the face (the likeness).
                // Real photos of the child (up to 3, different moments): the face from several angles keeps the likeness.
                val realPhotos: List<Bitmap> = c.photos.filter { look.drawn || it.absolutePath != look.file.absolutePath }
                    .take(if (look.drawn || facePhotos(app, c.memberId).isNotEmpty()) 4 else 2).mapNotNull { try { BitmapFactory.decodeFile(it.absolutePath) } catch (_: Throwable) { null } }
                // The child is drawn from the real photos only (the book's drawn character made the eyes too big):
                // the first photo is the child, the others show the face from more angles.
                // The approved film character: the only picture of the child (the same character in every scene).
                val fromPhotos = !look.hero && realPhotos.isNotEmpty() && (look.drawn || facePhotos(app, c.memberId).isNotEmpty())
                val childPic = if (fromPhotos) realPhotos.first() else child
                val morePhotos = if (look.hero) emptyList() else if (fromPhotos) realPhotos.drop(1) else realPhotos.take(if (look.drawn) 3 else 2)
                var first: Bitmap? = if (sceneFile(app, c.memberId, PitzkiScript.INTRO_KEY).exists()) BitmapFactory.decodeFile(sceneFile(app, c.memberId, PitzkiScript.INTRO_KEY).absolutePath) else null
                d.total = PitzkiScript.CHILD_KEYS.size
                // The scenes are drawn by the book's model (it keeps the child's likeness), through the server: they use
                // the film's credit (the same one the animation uses, so one credit for the whole film).
                val ref = creditRef(app, c)
                Settings.prefs(app).edit().putString("pitzki_ref_${c.memberId}", ref).apply()
                val ed = edge(app)
                var gpt = PitzkiScript.CHILD_KEYS.any { !sceneFile(app, c.memberId, it).exists() } || !startFile(app, c.memberId).exists()
                if (gpt) reserve(app, ed, ref)
                if (animate) Settings.prefs(app).edit().putBoolean("pitzki_want_anim_${c.memberId}", true).apply()
                d.animateAfter = animate
                for ((i, n) in PitzkiScript.CHILD_KEYS.withIndex()) {
                    val f = sceneFile(app, c.memberId, n)
                    if (f.exists()) { d.done = i + 1; continue }
                    // The first scene first: the parent sees how the child looks in the film and approves it,
                    // before the other drawings and the animations are paid for.
                    if (n != PitzkiScript.INTRO_KEY && !introApproved(app, c)) { d.waitingApproval = true; return@execute }
                    val useFirst = first != null && n != PitzkiScript.INTRO_KEY
                    val refs = listOfNotNull(pitzki, childPic, first.takeIf { useFirst }) + morePhotos
                    val text = prompt(n, c, useFirst, morePhotos.size, fromPhotos)
                    // A server not updated yet doesn't know this drawing: Gemini draws it, as before.
                    val drawn = if (gpt) drawGpt(app, ed, ref, text, refs, d, hero = look.hero) else null
                    if (drawn == null) gpt = false
                    val img = drawn ?: GeminiClient.scene(app, text, refs)
                        ?: throw IllegalStateException(L.t("הציור לא הצליח (סצנה $n). אפשר לנסות שוב: מה שכבר צויר נשמר.", "The drawing didn't work (scene $n). Try again: what's drawn is kept."))
                    val out = java.io.ByteArrayOutputStream()
                    img.compress(Bitmap.CompressFormat.JPEG, 88, out)
                    if (!SafeFiles.writeBytes(f, out.toByteArray())) throw IllegalStateException(L.t("אין מספיק מקום בטלפון", "Not enough space on the phone"))
                    // Which model drew it: scenes from the older, less faithful drawing can be found and made again.
                    Settings.prefs(app).edit().putString(drawerKey(c.memberId, n), if (drawn != null) "gpt" else "gemini").apply()
                    if (n == PitzkiScript.INTRO_KEY) first = img
                    d.made++; d.lastAt = System.currentTimeMillis()
                    d.done = i + 1
                }
                // The opening's first moment: Pitzki alone, reaching out his hand to someone just outside the picture
                // (the child isn't seen yet). The animation goes from it to the opening scene, so Pitzki leads the
                // child in. A failed drawing here doesn't stop the film (the opening then starts with both).
                val introF = sceneFile(app, c.memberId, PitzkiScript.INTRO_KEY)
                val startF = startFile(app, c.memberId)
                if (introF.exists() && !startF.exists() && introApproved(app, c)) try {
                    val introBmp = BitmapFactory.decodeFile(introF.absolutePath)
                    if (introBmp != null) {
                        val t = startPrompt()
                        val img = (if (gpt) drawGpt(app, ed, ref, t, listOf(pitzki, introBmp), d, solo = true) else null)
                            ?: GeminiClient.scene(app, t, listOf(pitzki, introBmp))
                        if (img != null) {
                            val out = java.io.ByteArrayOutputStream()
                            img.compress(Bitmap.CompressFormat.JPEG, 88, out)
                            SafeFiles.writeBytes(startF, out.toByteArray())
                            clipFile(app, c.memberId, "intro").delete() // the opening is animated again, from the new first moment
                        }
                    }
                } catch (_: Exception) { }
                if (animate) {
                    d.phase = "anim"
                    animateScenes(app, c, d)
                }
            } catch (e: Exception) {
                d.failed = friendly(e, L.t("הציור לא הצליח", "The drawing didn't work"))
            } finally {
                d.finished = true
            }
        }
        return true
    }

    private class Edge(val url: String, val token: String)

    /**
     * One scene drawn by the book's model (GPT Image, through the server and fal): sent, then collected when it's
     * ready (about a minute). Null when the server doesn't know this kind of drawing yet (then Gemini draws it).
     */
    private fun drawGpt(app: Context, ed: Edge, ref: String, prompt: String, refs: List<Bitmap>, d: Drawing, solo: Boolean = false, hero: Boolean = false): Bitmap? {
        val uris = refs.map { CartoonFilm.dataUri(it) }
        val res = CartoonFilm.callEdge(ed.url, Account.token(app) ?: ed.token, mapOf(
            "action" to "submit", "kind" to "pitzki_scene", "image" to uris.first(), "refs" to org.json.JSONArray(uris.drop(1)),
            "prompt" to prompt, "ref" to ref, "solo" to solo, "hero" to hero))
        if (res.optInt("_code", 200) == 400 && res.optString("error").contains("לא מוכרת")) return null
        CartoonFilm.checkError(res)
        val status = res.optString("status_url")
        val response = res.optString("response_url")
        val until = System.currentTimeMillis() + 6 * 60_000L
        while (System.currentTimeMillis() < until) {
            Thread.sleep(4000)
            val token = Account.token(app) ?: continue
            val p = try {
                CartoonFilm.callEdge(ed.url, token, mapOf("action" to "poll", "status_url" to status, "response_url" to response))
            } catch (_: Exception) { d.note = L.t("אין חיבור לרשת, מנסים שוב…", "No connection, trying again…"); continue }
            when (p.optString("status")) {
                "DONE" -> {
                    d.note = ""
                    val tmp = File(app.cacheDir, "pitzki_draw.jpg")
                    var ok = false
                    for (t in 1..3) { if (download(p.optString("url"), tmp)) { ok = true; break }; Thread.sleep(2000) }
                    val bmp = if (ok) BitmapFactory.decodeFile(tmp.absolutePath) else null
                    tmp.delete()
                    forget(app, ed.url, token, status)
                    return bmp ?: throw IllegalStateException(L.t("הורדת הציור נכשלה. אפשר לנסות שוב: מה שכבר צויר נשמר.", "Downloading the drawing failed. Try again: what's drawn is kept."))
                }
                "FAILED" -> throw IllegalStateException(L.t("הציור לא הצליח", "The drawing didn't work") + ": " + p.optString("detail").take(120))
            }
        }
        throw IllegalStateException(L.t("הציור לקח יותר מדי זמן. אפשר לנסות שוב: מה שכבר צויר נשמר.", "The drawing took too long. Try again: what's drawn is kept."))
    }

    // ---------- the parent approves the first scene before the rest is made ----------

    private fun introMark(ctx: Context, c: Child): String {
        val f = sceneFile(ctx, c.memberId, PitzkiScript.INTRO_KEY)
        return "${c.look?.key}:${f.length()}:${f.lastModified()}"
    }

    /**
     * The parent said this first scene looks like the child (or, for films made before this step, more scenes were
     * already drawn from it).
     */
    fun introApproved(ctx: Context, c: Child): Boolean {
        if (!sceneFile(ctx, c.memberId, PitzkiScript.INTRO_KEY).exists()) return false
        if (Settings.prefs(ctx).getString("pitzki_intro_ok_${c.memberId}_${PitzkiScript.INTRO_KEY}", "") == introMark(ctx, c)) return true
        return PitzkiScript.CHILD_KEYS.any { it != PitzkiScript.INTRO_KEY && sceneFile(ctx, c.memberId, it).exists() }
    }

    /** The first scene is drawn and waits for the parent's "yes" (the rest isn't made yet). */
    fun awaitingIntro(ctx: Context, c: Child): Boolean =
        sceneFile(ctx, c.memberId, PitzkiScript.INTRO_KEY).exists() && !introApproved(ctx, c) && drawnFrom(ctx, c.memberId) == c.look?.key

    /** "Yes, that's my child": the rest is drawn (and animated, if that's what was asked). */
    fun approveIntro(ctx: Context, c: Child): Boolean {
        val p = Settings.prefs(ctx)
        p.edit().putString("pitzki_intro_ok_${c.memberId}_${PitzkiScript.INTRO_KEY}", introMark(ctx, c)).apply()
        return drawScenes(ctx, c, animate = p.getBoolean("pitzki_want_anim_${c.memberId}", true))
    }

    /** "Not like my child": the first scene is drawn again (and waits for approval again). */
    fun redrawIntro(ctx: Context, c: Child): Boolean {
        sceneFile(ctx, c.memberId, PitzkiScript.INTRO_KEY).delete()
        clipFile(ctx, c.memberId, PitzkiScript.INTRO_KEY).delete()
        return drawScenes(ctx, c, animate = Settings.prefs(ctx).getBoolean("pitzki_want_anim_${c.memberId}", true))
    }

    // ---------- one scene at a time: which are old, draw one again, delete one ----------

    private fun drawerKey(member: String, key: String) = "pitzki_drawer_${member}_$key"

    /**
     * The child's scenes drawn the older way (Gemini, before the book's model): less like the child, and they don't
     * match the new ones. Scenes from before this mark existed count as old too.
     */
    fun oldScenes(ctx: Context, c: Child): List<String> = PitzkiScript.CHILD_KEYS.filter {
        sceneFile(ctx, c.memberId, it).exists() && Settings.prefs(ctx).getString(drawerKey(c.memberId, it), "") != "gpt"
    }

    /** These scenes are drawn (and animated) again; the others stay as they are. */
    fun redrawScenes(ctx: Context, c: Child, keys: Collection<String>): Boolean {
        if (drawing?.finished == false) return false
        for (k in keys) { sceneFile(ctx, c.memberId, k).delete(); clipFile(ctx, c.memberId, k).delete(); moreFile(ctx, c.memberId, k).delete() }
        if (PitzkiScript.INTRO_KEY in keys) { startFile(ctx, c.memberId).delete(); handFile(ctx, c.memberId).delete() }
        return drawScenes(ctx, c, animate = Settings.prefs(ctx).getBoolean("pitzki_want_anim_${c.memberId}", true))
    }

    /** One scene removed (the lesson shows the forest and the child's picture there until it's made again). */
    fun deleteScene(ctx: Context, c: Child, key: String) {
        if (key == PitzkiScript.INTRO_KEY) { startFile(ctx, c.memberId).delete(); handFile(ctx, c.memberId).delete() }
        moreFile(ctx, c.memberId, key).delete()
        sceneFile(ctx, c.memberId, key).delete()
        clipFile(ctx, c.memberId, key).delete()
        Settings.prefs(ctx).edit().remove(drawerKey(c.memberId, key)).apply()
    }

    /** The film's estimated cost so far (admin): the ref its drawings and animations were sent with. */
    fun lastRef(ctx: Context, member: String): String = Settings.prefs(ctx).getString("pitzki_ref_$member", "").orEmpty()

    /**
     * Brings the drawn scenes to life (5 seconds each, through the same service as the 3D films: one film credit
     * for the whole lesson). Scenes already animated are kept. If not a single scene came to life, the credit
     * is given back.
     */
    private fun animateScenes(app: Context, c: Child, d: Drawing) {
        if (c.look == null) return
        // Every scene with the child's own drawing gets its own animation (the close-ups are shared).
        // The opening in two parts (the hand, then both together). An opening made the old way (animated from Pitzki
        // alone to both: the child's face was made up on the way) is made again, once.
        val start = startFile(app, c.memberId)
        val two = Settings.prefs(app)
        if (start.exists() && !two.getBoolean("pitzki_intro2_${c.memberId}", false)) {
            if (!handFile(app, c.memberId).exists()) clipFile(app, c.memberId, PitzkiScript.INTRO_KEY).delete()
            two.edit().putBoolean("pitzki_intro2_${c.memberId}", true).apply()
        }
        val todo = PitzkiScript.KEYS.filter { sceneFile(app, c.memberId, it).exists() && !clipFile(app, c.memberId, it).exists() } +
            (if (start.exists() && !handFile(app, c.memberId).exists()) listOf(HAND) else emptyList())
        animateAll(app, d, todo, creditRef(app, c), c.memberId,
            { if (it == HAND) start else sceneFile(app, c.memberId, it) },
            { if (it == HAND) handFile(app, c.memberId) else clipFile(app, c.memberId, it) },
            { if (it == HAND) HAND_MOTION else motion(it) },
            durationOf = { if (it == HAND || it !in PitzkiScript.LONG_CLIPS) "5" else "10" })
    }

    /**
     * Makes again only the opening's first seconds (Pitzki alone reaching out: a giant hand came in), from the same
     * first picture, with the new motion. Nothing else is drawn or animated again. About $0.35 (5 seconds).
     */
    fun remakeHand(ctx: Context, c: Child): Boolean {
        if (drawing?.finished == false) return false
        val app = ctx.applicationContext
        if (!startFile(app, c.memberId).exists()) return false
        // Other scenes still being animated are collected first (their jobs aren't lost).
        if (pending(app, c.memberId).keys.any { it != HAND }) return false
        val d = Drawing(c.memberId).apply { title = L.t("הפתיחה של ${c.kid.name}", "${c.kid.name}'s opening"); phase = "anim"; total = 1 }
        drawing = d
        PitzkiService.start(app)
        worker.execute {
            try {
                handFile(app, c.memberId).delete()
                // An old opening job still remembered as "sent" isn't collected again: this one is new.
                pending(app, c.memberId).filterKeys { it != HAND }.let { left -> savePending(app, c.memberId, left.map { (k, j) -> Triple(k, j.first, j.second) }) }
                animateAll(app, d, listOf(HAND), creditRef(app, c), c.memberId,
                    { startFile(app, c.memberId) }, { handFile(app, c.memberId) }, { HAND_MOTION }, duration = "5")
            } catch (e: Exception) {
                d.failed = friendly(e, L.t("הפתיחה לא נוצרה", "The opening wasn't made"))
            } finally { d.finished = true }
        }
        return true
    }

    /**
     * Brings [todo] scenes to life (one film credit, [ref]): all are sent at once, then collected. [store] names
     * where the ones already sent are remembered (so closing the app doesn't send — and pay — them again).
     */
    private fun animateAll(
        app: Context, d: Drawing, todo: List<String>, ref: String, store: String,
        picture: (String) -> File, clip: (String) -> File, motionOf: (String) -> String,
        // A scene that starts from another picture and ends on its own (the opening: Pitzki alone → both together).
        startOf: (String) -> File? = { null },
        // What happens from the first picture to the last (instead of the opening's "Pitzki leads the child in").
        startScene: ((String) -> String)? = null,
        duration: String = "10",
        durationOf: (String) -> String = { duration },
    ) {
        val ed = edge(app)
        d.animated = 0
        d.total = todo.size
        if (todo.isEmpty()) { pendingFile(app, store).delete(); return }
        // Scenes already sent (the app was closed while they were being animated): collected, not sent (and paid) again.
        val sent = pending(app, store).filterKeys { it in todo }
        if (todo.any { it !in sent }) reserve(app, ed, ref)
        var made = 0
        var lastError = ""
        // "pitzki_anim": lively, 10-second motion (walking, talking, expressions). A server not updated yet doesn't
        // know it: then the gentler 5-second "book_anim" is used, so the film still gets made.
        var kind = "pitzki_anim"
        // All scenes are sent first, so the service animates them at the same time (a few minutes in all, not
        // a few minutes each); then each one is collected as it's ready.
        val jobs = ArrayList<Triple<String, String, String>>()
        sent.forEach { (n, j) -> jobs.add(Triple(n, j.first, j.second)) }
        for (n in todo.filter { it !in sent }) {
            try {
                val bmp = BitmapFactory.decodeFile(picture(n).absolutePath) ?: continue
                val start = startOf(n)?.let { BitmapFactory.decodeFile(it.absolutePath) }
                val image = CartoonFilm.dataUri(start ?: bmp)
                // With a first moment: the video goes from it to the scene itself (its last frame).
                val tail = if (start != null) CartoonFilm.dataUri(bmp) else ""
                fun submit(k: String) = CartoonFilm.callEdge(ed.url, ed.token, mapOf(
                    "action" to "submit", "kind" to k, "duration" to durationOf(n), "key" to n, "image" to image, "scene" to (if (start != null && startScene != null) startScene(n) else if (start != null) (if (PitzkiScript.isNumbers) NumbersWorld.INTRO_FROM_START else if (PitzkiScript.isPlay) PlayWorld.INTRO_FROM_START else if (PitzkiScript.isNight) NightWorld.INTRO_FROM_START else if (PitzkiScript.isTrain) TrainWorld.INTRO_FROM_START else INTRO_FROM_START) else motionOf(n)), "ref" to ref,
                    "tail" to tail))
                var job = submit(kind)
                if (kind == "pitzki_anim" && job.optInt("_code", 200) == 400 && job.optString("error").contains("לא מוכרת")) {
                    kind = "book_anim"
                    job = submit(kind)
                }
                // The animation service itself refused (its account ran out of balance, or blocked us): sending the
                // other scenes would only fail the same way, and use up the credit's calls.
                val refusal = (job.optString("error") + " " + job.optString("detail")).lowercase()
                if (job.optInt("_code", 200) == 502 && ("balance" in refusal || "locked" in refusal || ", 403" in refusal || ", 401" in refusal)) {
                    lastError = L.t("שירות ההנפשה (fal) חסום כרגע: נגמרה היתרה בחשבון. צריך להטעין אותו ב-fal.ai ואז ללחוץ שוב.",
                        "The animation service (fal) is blocked right now: its account balance ran out. Top it up at fal.ai, then tap again.")
                    break
                }
                CartoonFilm.checkError(job)
                jobs.add(Triple(n, job.optString("status_url"), job.optString("response_url")))
                savePending(app, store, jobs)
            } catch (e: CartoonFilm.FatalEdgeError) {
                lastError = friendly(e, "")
                break
            } catch (e: Exception) {
                lastError = friendly(e, "")
            }
        }
        // Collected together, every few seconds, with a fresh sign-in each time (a long wait can outlast the old one).
        // One request asks about all the scenes ("poll_many"); a server not updated yet is asked scene by scene.
        val left = jobs.toMutableList()
        val downloadTries = HashMap<String, Int>()
        var many = true
        var errors = 0
        val until = System.currentTimeMillis() + 20 * 60_000L
        while (left.isNotEmpty() && System.currentTimeMillis() < until && errors <= 30) {
            Thread.sleep(6000)
            val token = Account.token(app)
            if (token == null) { errors++; d.note = L.t("החיבור לחשבון נותק, מנסים שוב…", "Signed out, trying again…"); continue }
            val answers = HashMap<String, JSONObject>()
            if (many) {
                val arr = org.json.JSONArray()
                left.forEach { (n, st, rs) -> arr.put(JSONObject().put("k", n).put("status_url", st).put("response_url", rs)) }
                val res = try {
                    CartoonFilm.callEdge(ed.url, token, mapOf("action" to "poll_many", "jobs" to arr))
                } catch (e: Exception) { errors++; d.note = L.t("אין חיבור לרשת, מנסים שוב…", "No connection, trying again…"); continue }
                val code = res.optInt("_code", 200)
                when {
                    code == 400 && res.optString("error").contains("לא מוכרת") -> many = false
                    code !in 200..299 -> {
                        errors++
                        d.note = L.t("השרת השיב שגיאה ($code)", "The server answered an error ($code)") + res.optString("error").let { if (it.isBlank()) "" else ": ${it.take(80)}" }
                        continue
                    }
                    else -> res.optJSONObject("results")?.let { r -> left.forEach { (n, _, _) -> r.optJSONObject(n)?.let { answers[n] = it } } }
                }
            }
            if (!many) for ((n, status, response) in left) {
                val res = try {
                    CartoonFilm.callEdge(ed.url, token, mapOf("action" to "poll", "status_url" to status, "response_url" to response))
                } catch (e: Exception) { errors++; d.note = L.t("אין חיבור לרשת, מנסים שוב…", "No connection, trying again…"); continue }
                val code = res.optInt("_code", 200)
                if (code !in 200..299) {
                    errors++
                    d.note = L.t("השרת השיב שגיאה ($code)", "The server answered an error ($code)") + res.optString("error").let { if (it.isBlank()) "" else ": ${it.take(80)}" }
                    continue
                }
                answers[n] = res
            }
            val iter = left.iterator()
            while (iter.hasNext()) {
                val (n, status, _) = iter.next()
                val res = answers[n] ?: continue
                when (res.optString("status", "PENDING")) {
                    "DONE" -> {
                        if (download(res.optString("url"), clip(n))) {
                            iter.remove(); made++; d.animated = d.animated + 1
                            forget(app, ed.url, token, status)
                        } else {
                            // Already paid for: tried again on the next rounds (the link works for 16 hours).
                            val t = (downloadTries[n] ?: 0) + 1
                            downloadTries[n] = t
                            if (t >= 5) { iter.remove(); lastError = L.t("ההורדה של סצנה $n נכשלה", "Downloading scene $n failed") }
                            else d.note = L.t("ההורדה של סצנה נכשלה, מנסים שוב…", "A scene's download failed, trying again…")
                        }
                    }
                    "FAILED" -> { iter.remove(); lastError = res.optString("detail").take(160) }
                    else -> d.note = ""
                }
            }
            // What is still being made stays remembered: closing the app (or a stop) never loses a paid scene.
            savePending(app, store, left)
        }
        if (left.isNotEmpty()) {
            // Still being made at the service (it can take long when it's busy), or no connection: kept, and collected
            // on the next try without paying again (the service keeps them 16 hours). No refund: they're coming.
            savePending(app, store, left)
            val later = L.t("שאר הסצנות עוד בהכנה. לחצו שוב בעוד כמה דקות כדי לאסוף אותן, בלי לשלם שוב.",
                "The other scenes are still being made. Tap again in a few minutes to collect them, without paying again.")
            if (made == 0) throw IllegalStateException(if (errors > 30) d.note + ". " + later else later)
            d.failed = L.t("$made מתוך ${todo.size} סצנות מוכנות. ", "$made of ${todo.size} scenes are ready. ") + later
            return
        }
        pendingFile(app, store).delete()
        if (made == 0) {
            try { CartoonFilm.callEdge(ed.url, ed.token, mapOf("action" to "refund", "ref" to ref)) } catch (_: Exception) {}
            throw IllegalStateException(if (lastError.startsWith(L.t("אין חיבור", "No internet")) || lastError.startsWith(L.t("שירות ההנפשה", "The animation service"))) lastError
                else L.t("ההנפשה לא הצליחה", "The animation didn't work") + if (lastError.isBlank()) "" else ": ${lastError.take(120)}")
        }
        if (made < todo.size) d.failed = L.t("$made מתוך ${todo.size} סצנות קמו לחיים. אפשר לנסות שוב את השאר.", "$made of ${todo.size} scenes came to life. You can try the rest again.")
    }

    /**
     * The film credit of one making session: the child's character and the film's animation within the same
     * hours share one credit (the server keeps a reserved credit for 3 hours).
     */
    /**
     * The film's credit: one per film, kept for 7 days (the server keeps it open as long), so drawing a scene again
     * tomorrow uses the same credit's remaining steps instead of a new credit.
     */
    private fun creditRef(app: Context, c: Child): String {
        val p = Settings.prefs(app)
        val saved = p.getString("pitzki_film_ref_${c.memberId}", "").orEmpty()
        val at = p.getLong("pitzki_film_ref_at_${c.memberId}", 0L)
        if (saved.isNotEmpty() && System.currentTimeMillis() - at < 7 * 24 * 3600_000L - 3600_000L) return saved
        val ref = newRef(c)
        p.edit().putString("pitzki_film_ref_${c.memberId}", ref).putLong("pitzki_film_ref_at_${c.memberId}", System.currentTimeMillis()).apply()
        return ref
    }

    private fun newRef(c: Child) = "pitzki-" + c.memberId.filter { it.isLetterOrDigit() }.take(24) + "-" +
        java.text.SimpleDateFormat("yyyyMMddHHmm", java.util.Locale.US).format(java.util.Date())

    /** A new film for this child (all its scenes deleted): the next drawing starts a new credit. */
    fun newFilm(ctx: Context, member: String) {
        Settings.prefs(ctx).edit().remove("pitzki_film_ref_$member").remove("pitzki_film_ref_at_$member").apply()
    }

    private fun edge(app: Context) = Edge(Circle.url(app) + "/functions/v1/cartoon-film",
        Account.token(app) ?: throw IllegalStateException(L.t("צריך להתחבר לחשבון", "Please sign in")))

    private fun reserve(app: Context, e: Edge, ref: String) {
        val res = CartoonFilm.callEdge(e.url, e.token, mapOf("action" to "reserve", "ref" to ref, "again" to true))
        if (res.optInt("_code", 200) !in 200..299 || !res.optBoolean("ok", false)) {
            throw IllegalStateException(res.optString("error", "").ifBlank { L.t("אין קרדיט לסרט כרגע", "No film credit right now") }
                .let { if (it == "expired") L.t("נסו שוב", "Try again") else it })
        }
    }

    // ---------- the child's character (for the best likeness) ----------

    /** A character drawn from the child's photos, waiting for the parent's OK. */
    fun candidate(ctx: Context, member: String) = File(dir(ctx, member), "character_candidate.jpg")

    /**
     * Draws the child as an animated character from their real photos, with the same model the children's books use
     * (it keeps likeness best). The parent approves it; then it's the child's character everywhere (books too).
     */
    fun makeCharacter(ctx: Context, c: Child): Boolean {
        if (c.photos.isEmpty()) return false
        if (drawing?.finished == false) return false
        val app = ctx.applicationContext
        val d = Drawing(c.memberId).apply { phase = "character"; title = L.t("הדמות של ${c.kid.name}", "${c.kid.name}'s character") }
        drawing = d
        PitzkiService.start(app)
        worker.execute {
            try {
                val ed = edge(app)
                reserve(app, ed, creditRef(app, c))
                val uris = c.photos.mapNotNull { f -> BitmapFactory.decodeFile(f.absolutePath)?.let { CartoonFilm.dataUri(it) } }
                if (uris.isEmpty()) throw IllegalStateException(L.t("לא הצלחנו לפתוח את התמונות של ${c.kid.name}", "Couldn't open ${c.kid.name}'s photos"))
                val job = CartoonFilm.callEdge(ed.url, ed.token, mapOf(
                    "action" to "submit", "kind" to "book_character", "image" to uris.first(), "refs" to org.json.JSONArray(uris.drop(1)),
                    "age" to BookJson.ageDesc(Profile.ageMonths(c.birth), c.kid.girl), "aspect" to "3:4",
                    "outfit" to "comfortable everyday clothes in soft, friendly colors"))
                CartoonFilm.checkError(job)
                val url = CartoonFilm.pollEdge(ed.url, ed.token, job.optString("status_url"), job.optString("response_url"), java.util.concurrent.atomic.AtomicBoolean(false))
                val bmp = (if (url.isEmpty()) null else CartoonFilm.downloadBitmap(url))
                    ?: throw IllegalStateException(L.t("הדמות לא צוירה", "The character wasn't drawn") + CartoonFilm.lastJobDetail.let { if (it.isBlank()) "" else ": $it" })
                val out = java.io.ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, 92, out)
                if (!SafeFiles.writeBytes(candidate(app, c.memberId), out.toByteArray())) throw IllegalStateException(L.t("אין מספיק מקום בטלפון", "Not enough space on the phone"))
            } catch (e: Exception) {
                d.failed = friendly(e, L.t("הדמות לא צוירה", "The character wasn't drawn"))
            } finally {
                d.finished = true
            }
        }
        return true
    }

    /** The parent said "that's my child": it becomes the child's character (Pitzki's film and the books use it). */
    fun approveCharacter(ctx: Context, c: Child): Boolean {
        val f = candidate(ctx, c.memberId)
        if (!f.exists()) return false
        val ok = CharacterStore.save(ctx, c.memberId, c.kid.name, f, "pitzki")
        if (ok) f.delete()
        return ok
    }

    // ---------- the child as the film's character (Pixar-like), approved by the parent ----------

    /** The approved film character: when it exists, all the scenes are drawn from it. */
    fun heroFile(ctx: Context, member: String) = File(dir(ctx, member), "hero.jpg")
    /** A film character waiting for the parent's OK. */
    fun heroCandidate(ctx: Context, member: String) = File(dir(ctx, member), "hero_candidate.jpg")
    fun heroDrawing(member: String) = "hero-$member"

    /** Background. Draws the child as the film's character from the real photos (about 25 cents); the parent approves it. */
    fun makeHero(ctx: Context, c: Child): Boolean {
        if (drawing?.finished == false) return false
        val app = ctx.applicationContext
        val d = Drawing(heroDrawing(c.memberId)).apply { title = L.t("הדמות של ${c.kid.name}", "${c.kid.name}'s character"); total = 1 }
        drawing = d
        PitzkiService.start(app)
        worker.execute {
            try {
                // The clear face photos the parent chose first, then the others (up to 3).
                val real = c.photos.take(3).mapNotNull { try { BitmapFactory.decodeFile(it.absolutePath) } catch (_: Throwable) { null } }
                if (real.isEmpty()) throw IllegalStateException(L.t("צריך קודם תמונות של ${c.kid.name} (3 תמונות פנים ברורות)", "First add photos of ${c.kid.name} (3 clear face photos)"))
                val ed = edge(app)
                val ref = creditRef(app, c)
                reserve(app, ed, ref)
                val img = drawGpt(app, ed, ref, PitzkiScript.heroPrompt(c.kid.girl, real.size), real, d, hero = true)
                    ?: throw IllegalStateException(L.t("הדמות לא צוירה. אפשר לנסות שוב.", "The character wasn't drawn. Try again."))
                val out = java.io.ByteArrayOutputStream()
                img.compress(Bitmap.CompressFormat.JPEG, 92, out)
                if (!SafeFiles.writeBytes(heroCandidate(app, c.memberId), out.toByteArray())) throw IllegalStateException(L.t("אין מספיק מקום בטלפון", "Not enough space on the phone"))
                d.done = 1; d.made++; d.lastAt = System.currentTimeMillis()
            } catch (e: Exception) {
                d.failed = friendly(e, L.t("הדמות לא צוירה", "The character wasn't drawn"))
            } finally {
                d.finished = true
            }
        }
        return true
    }

    /** The character from every side (four views in a row): only to look at, the front picture stays the reference. */
    fun heroSheet(ctx: Context, member: String) = File(dir(ctx, member), "hero_sheet.jpg")

    /** Background. Draws the approved character from four sides (about 25 cents). */
    fun makeHeroSheet(ctx: Context, c: Child): Boolean {
        if (drawing?.finished == false) return false
        val app = ctx.applicationContext
        val hero = BitmapFactory.decodeFile(heroFile(app, c.memberId).absolutePath) ?: return false
        val d = Drawing(heroDrawing(c.memberId)).apply { title = L.t("${c.kid.name} מכל הצדדים", "${c.kid.name} from every side"); total = 1 }
        drawing = d
        PitzkiService.start(app)
        worker.execute {
            try {
                val ed = edge(app)
                val ref = creditRef(app, c)
                reserve(app, ed, ref)
                val img = drawGpt(app, ed, ref, PitzkiScript.heroSheetPrompt(c.kid.girl), listOf(hero), d, hero = true)
                    ?: throw IllegalStateException(L.t("הציור לא הצליח. אפשר לנסות שוב.", "The drawing didn't work. Try again."))
                val out = java.io.ByteArrayOutputStream()
                img.compress(Bitmap.CompressFormat.JPEG, 92, out)
                if (!SafeFiles.writeBytes(heroSheet(app, c.memberId), out.toByteArray())) throw IllegalStateException(L.t("אין מספיק מקום בטלפון", "Not enough space on the phone"))
                d.done = 1; d.made++
            } catch (e: Exception) {
                d.failed = friendly(e, L.t("הציור לא הצליח", "The drawing didn't work"))
            } finally { d.finished = true }
        }
        return true
    }

    /** "That's my child": the candidate becomes the film's character (the scenes are drawn again from it). */
    fun approveHero(ctx: Context, member: String): Boolean {
        val f = heroCandidate(ctx, member)
        if (!f.exists()) return false
        val to = heroFile(ctx, member)
        to.delete()
        heroSheet(ctx, member).delete() // a new character: its sides are drawn again when asked
        val ok = f.renameTo(to) || try { f.copyTo(to, overwrite = true); f.delete(); true } catch (_: Exception) { false }
        return ok
    }

    /** Back to drawing the child from the real photos. */
    fun removeHero(ctx: Context, member: String) { heroFile(ctx, member).delete(); heroCandidate(ctx, member).delete(); heroSheet(ctx, member).delete() }

    // ---------- Pitzki's close-ups: made once, the same for every family ----------

    /** Bump when the close-ups are made again, so phones fetch the new ones. */
    private const val SHARED_V = "v1"
    private fun sharedDir(ctx: Context) = File(File(ctx.filesDir, "pitzki"), "shared").apply { mkdirs() }
    fun sharedScene(ctx: Context, key: String) = File(sharedDir(ctx), "$key.jpg")
    fun sharedClip(ctx: Context, key: String) = File(sharedDir(ctx), "$key.mp4")
    /** Pitzki's talking video for a scene (lips matched, with its sound), and its continuation. */
    fun sharedTalk(ctx: Context, key: String) = File(sharedDir(ctx), "${key}_talk.mp4")
    fun sharedTalk2(ctx: Context, key: String) = File(sharedDir(ctx), "${key}_talk2.mp4")
    /** The shared scenes drawn and animated by Kling (the talking scenes are made apart: [buildTalks]). */
    private val BUILD_KEYS: List<String> get() = (SHARED_KEYS - PitzkiTalks.KEYS).filter { !it.startsWith("farm_") }
    val SHARED_KEYS: List<String> get() = PitzkiScript.KEYS - PitzkiScript.CHILD_KEYS.toSet()
    private fun sharedUrl(ctx: Context, name: String) = Circle.url(ctx) + "/storage/v1/object/public/pitzki-shared/$SHARED_V/$name"

    /** How many close-ups this phone has, and how many of them move. */
    fun sharedReady(ctx: Context): Pair<Int, Int> =
        SHARED_KEYS.count { sharedScene(ctx, it).exists() } to SHARED_KEYS.count { sharedClip(ctx, it).exists() || sharedTalk(ctx, it).exists() }

    @Volatile private var fetching = false

    /** Background thread only. Downloads the close-ups this phone doesn't have yet (quietly: missing ones stay missing). */
    fun fetchShared(ctx: Context) {
        if (fetching) return
        fetching = true
        try {
            bundledTalks(ctx)
            bundledSfx(ctx)
            val files = SHARED_KEYS.flatMap { key -> listOf("$key.jpg" to sharedScene(ctx, key), "$key.mp4" to sharedClip(ctx, key)) } +
                listOf("song_talk.mp4" to talkTest(ctx), "chat.jpg" to sharedScene(ctx, CHAT), "chat.mp4" to sharedClip(ctx, CHAT), "chat_listen.mp4" to sharedClip(ctx, CHAT_LISTEN)) +
                PitzkiTalks.KEYS.flatMap { key -> listOf("${key}_talk.mp4" to sharedTalk(ctx, key), "${key}_talk2.mp4" to sharedTalk2(ctx, key)) } +
                FarmWorld.ANIMALS.flatMap { a -> listOf("${a.open}.jpg" to sharedScene(ctx, a.open), "${a.open}.mp4" to sharedClip(ctx, a.open),
                    "${a.key}.jpg" to sharedScene(ctx, a.key), "sfx_${a.id}.mp3" to sfxFile(ctx, a.id)) } +
                listOf("${FarmWorld.INTRO}.jpg" to sharedScene(ctx, FarmWorld.INTRO), "farm_review.jpg" to sharedScene(ctx, "farm_review")) +
                ArrowArt.KEYS.map { "$it.jpg" to sharedScene(ctx, it) } +
                MathEpisode.SHOT_KEYS.map { "$it.mp4" to sharedClip(ctx, it) }
            for ((name, f) in files) {
                if (!f.exists() && download(sharedUrl(ctx, name), f)) {
                    // It came from the server: no need to send it back.
                    val p = Settings.prefs(ctx)
                    val sent = p.getStringSet("pitzki_uploaded", emptySet()).orEmpty()
                    p.edit().putStringSet("pitzki_uploaded", sent.filterNot { it.startsWith("$name:") }.toSet() + "$name:${f.length()}:${f.lastModified()}").apply()
                }
            }
            // The admin's lip-synced song (made before it was shared automatically) goes to every family once.
            if (Account.isAdmin(ctx) && talkTest(ctx).exists()) try { uploadOnceOnly(ctx, "song_talk.mp4", talkTest(ctx), "video/mp4") } catch (_: Exception) { }
        } finally { fetching = false }
    }

    /**
     * The admin makes Pitzki's close-ups once (drawn, animated, and put on the server for every family): only the
     * missing ones, so a stop in the middle continues. Uses one film credit for the animations.
     */
    fun buildShared(ctx: Context, redo: Set<String> = emptySet()): Boolean {
        if (drawing?.finished == false) return false
        val app = ctx.applicationContext
        val d = Drawing("shared").apply { title = L.t("הסצנות של פיצקי", "Pitzki's scenes"); animateAfter = true }
        drawing = d
        PitzkiService.start(app)
        worker.execute {
            try {
                fetchShared(app) // what's on the server already isn't made again
                // Scenes the admin asked to make again (a bad drawing): drawn, animated and uploaded anew, replacing them.
                for (key in redo) { sharedScene(app, key).delete(); sharedClip(app, key).delete() }
                val pitzki = art(app, "pitzki") ?: throw IllegalStateException(L.t("חסרה התמונה של פיצקי באפליקציה", "Pitzki's picture is missing from the app"))
                var style: Bitmap? = BUILD_KEYS.map { sharedScene(app, it) }.firstOrNull { it.exists() }?.let { BitmapFactory.decodeFile(it.absolutePath) }
                val missing = BUILD_KEYS.filter { !sharedScene(app, it).exists() }
                d.total = missing.size
                // Drawn by the book's model (it follows "exactly one Pitzki" far better), from Pitzki's picture only: an earlier
                // scene as a second picture made the model draw him twice. Gemini only if the server is older.
                val sharedRef = "pitzki-shared-" + java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())
                val ed = if (missing.isNotEmpty()) edge(app) else null
                if (ed != null) reserve(app, ed, sharedRef)
                var gpt = ed != null
                for (key in missing) {
                    val drawn = if (gpt && ed != null) drawGpt(app, ed, sharedRef, sharedPrompt(key, false), listOf(pitzki), d, solo = true) else null
                    if (drawn == null) gpt = false
                    val img = drawn ?: GeminiClient.scene(app, sharedPrompt(key, false), listOf(pitzki))
                        ?: throw IllegalStateException(L.t("הציור לא הצליח ($key). אפשר לנסות שוב: מה שכבר צויר נשמר.", "The drawing didn't work ($key). Try again: what's drawn is kept."))
                    val out = java.io.ByteArrayOutputStream()
                    img.compress(Bitmap.CompressFormat.JPEG, 88, out)
                    // Kept on the phone first: a paid drawing is never lost to a failed upload (it's uploaded below).
                    if (!SafeFiles.writeBytes(sharedScene(app, key), out.toByteArray())) throw IllegalStateException(L.t("אין מספיק מקום בטלפון", "Not enough space on the phone"))
                    if (style == null) style = img
                    d.done = d.done + 1
                    d.made++; d.lastAt = System.currentTimeMillis()
                }
                // Every drawing on the phone goes to the server (again, if an earlier upload failed: it replaces itself).
                for (key in BUILD_KEYS) {
                    val f = sharedScene(app, key)
                    if (f.exists() && f.length() > 0) uploadOnceOnly(app, "$key.jpg", f, "image/jpeg")
                }
                d.phase = "anim"
                val todo = BUILD_KEYS.filter { sharedScene(app, it).exists() && !sharedClip(app, it).exists() }
                val ref = "pitzki-shared-" + java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())
                animateAll(app, d, todo, ref, "shared", { sharedScene(app, it) }, { sharedClip(app, it) }, ::sharedMotion)
                for (key in BUILD_KEYS) {
                    val f = sharedClip(app, key)
                    if (f.exists() && f.length() > 0) uploadOnceOnly(app, "$key.mp4", f, "video/mp4")
                }
            } catch (e: Exception) {
                d.failed = friendly(e, L.t("הסצנות המשותפות לא נוצרו", "The shared scenes weren't made"))
            } finally {
                d.finished = true
            }
        }
        return true
    }

    /**
     * The admin draws the arrows game's pictures once ([ArrowArt]: Pitzki's poses, the things, the clearing; no
     * animation), for every family. Only the missing ones ([redo]: these again). About 16 drawings.
     */
    fun buildArrowArt(ctx: Context, redo: Set<String> = emptySet()): Boolean {
        if (drawing?.finished == false) return false
        val app = ctx.applicationContext
        val d = Drawing("arrow").apply { title = L.t("ציורי משחק החצים", "The arrows game's pictures") }
        drawing = d
        PitzkiService.start(app)
        worker.execute {
            try {
                fetchShared(app)
                for (key in redo) sharedScene(app, key).delete()
                val pitzki = art(app, "pitzki") ?: throw IllegalStateException(L.t("חסרה התמונה של פיצקי באפליקציה", "Pitzki's picture is missing from the app"))
                val missing = ArrowArt.KEYS.filter { !sharedScene(app, it).exists() }
                d.total = missing.size
                val ref = "pitzki-arrow-" + java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())
                val ed = if (missing.isNotEmpty()) edge(app) else null
                if (ed != null) reserve(app, ed, ref)
                for (key in missing) {
                    val pose = key in ArrowArt.POSES
                    val img = (if (ed != null) drawGpt(app, ed, ref, ArrowArt.prompt(key), listOf(pitzki), d, solo = pose, hero = !pose) else null)
                        ?: throw IllegalStateException(L.t("הציור לא הצליח ($key). אפשר לנסות שוב: מה שכבר צויר נשמר.", "The drawing didn't work ($key). Try again: what's drawn is kept."))
                    val out = java.io.ByteArrayOutputStream()
                    img.compress(Bitmap.CompressFormat.JPEG, 92, out)
                    if (!SafeFiles.writeBytes(sharedScene(app, key), out.toByteArray())) throw IllegalStateException(L.t("אין מספיק מקום בטלפון", "Not enough space on the phone"))
                    d.done = d.done + 1
                    d.made++; d.lastAt = System.currentTimeMillis()
                }
                for (key in ArrowArt.KEYS) {
                    val f = sharedScene(app, key)
                    if (f.exists() && f.length() > 0) uploadOnceOnly(app, "$key.jpg", f, "image/jpeg")
                }
            } catch (e: Exception) {
                d.failed = friendly(e, L.t("ציורי משחק החצים לא נוצרו", "The arrows game's pictures weren't made"))
            } finally {
                d.finished = true
            }
        }
        return true
    }

    /**
     * Admin test ([MathLesson]): the first and last pictures of one teaching shot (one apple on the table and one in
     * Pitzki's hand → both on the table), then the animation between them (5 seconds), then the apples are counted
     * along the shot. Kept on this phone only. About $1–2. [again]: everything is made again.
     */
    fun buildMathLesson(ctx: Context, again: Boolean = false): Boolean {
        if (drawing?.finished == false) return false
        val app = ctx.applicationContext
        val d = Drawing("math").apply { title = L.t("ניסיון: פיצקי מלמד 1+1", "Test: Pitzki teaches 1+1") }
        drawing = d
        PitzkiService.start(app)
        worker.execute {
            try {
                val start = sharedScene(app, MathLesson.START); val end = sharedScene(app, MathLesson.END); val clip = sharedClip(app, MathLesson.SHOT)
                if (again) { start.delete(); end.delete(); clip.delete(); pendingFile(app, "math").delete() }
                val pitzki = art(app, "pitzki") ?: throw IllegalStateException(L.t("חסרה התמונה של פיצקי באפליקציה", "Pitzki's picture is missing from the app"))
                val ref = "pitzki-math-" + java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())
                val ed = edge(app)
                fun save(img: Bitmap, f: File) {
                    val out = java.io.ByteArrayOutputStream()
                    img.compress(Bitmap.CompressFormat.JPEG, 92, out)
                    if (!SafeFiles.writeBytes(f, out.toByteArray())) throw IllegalStateException(L.t("אין מספיק מקום בטלפון", "Not enough space on the phone"))
                }
                if (!start.exists() || !end.exists()) {
                    d.total = 2
                    reserve(app, ed, ref)
                    if (!start.exists()) {
                        end.delete()
                        save(drawGpt(app, ed, ref, MathLesson.START_PROMPT, listOf(pitzki), d, solo = true)
                            ?: throw IllegalStateException(L.t("הציור הראשון לא הצליח. אפשר לנסות שוב.", "The first picture didn't work. Try again.")), start)
                    }
                    d.done = 1
                    // The last picture is drawn from the first one, so the place, the table and the light stay the same.
                    val first = BitmapFactory.decodeFile(start.absolutePath) ?: throw IllegalStateException("start")
                    save(drawGpt(app, ed, ref, MathLesson.END_PROMPT, listOf(pitzki, first), d, solo = true)
                        ?: throw IllegalStateException(L.t("הציור השני לא הצליח. אפשר לנסות שוב: הראשון נשמר.", "The second picture didn't work. Try again: the first is kept.")), end)
                    d.done = 2
                    clip.delete()
                }
                if (!clip.exists()) {
                    d.phase = "anim"
                    animateAll(app, d, listOf(MathLesson.SHOT), ref, "math", { end }, { clip }, { MathLesson.MOTION },
                        startOf = { start }, startScene = { MathLesson.MOTION }, duration = "5")
                }
                if (!clip.exists()) throw IllegalStateException(L.t("ההנפשה לא נוצרה", "The animation wasn't made"))
                d.note = L.t("סופרים את התפוחים לאורך הקטע…", "Counting the apples along the shot…")
                MathLesson.check(app)
            } catch (e: Exception) {
                d.failed = friendly(e, L.t("הניסיון לא נוצר", "The test wasn't made"))
            } finally {
                d.finished = true
            }
        }
        return true
    }

    /**
     * Admin: Pitzki's teaching film ([MathEpisode]) for every family. The key pictures are drawn one after the other,
     * each from the one before (so the place and the camera stay the same), then every shot is animated from its first
     * picture to its last; all go to the shared folder. Only what's missing is made ([redo]: these keys again, and the
     * shots that touch them). About $7. Then the apples are counted along every shot.
     */
    fun buildEpisode(ctx: Context, redo: Set<String> = emptySet()): Boolean {
        if (drawing?.finished == false) return false
        val app = ctx.applicationContext
        val d = Drawing(MathEpisode.ID).apply { title = L.t("פיצקי מלמד: אחד ועוד אחד", "Pitzki teaches: one and one") }
        drawing = d
        PitzkiService.start(app)
        worker.execute {
            try {
                // A picture drawn again: every picture after it is drawn again too (each is drawn from the one before).
                val first = MathEpisode.FRAMES.indexOfFirst { it.key in redo }
                if (first >= 0) for (f in MathEpisode.FRAMES.drop(first)) sharedScene(app, f.key).delete()
                for (s in MathEpisode.SHOTS) if (s.key in redo || (first >= 0 && maxOf(s.from, s.to) >= first)) sharedClip(app, s.key).delete()
                val pitzki = art(app, "pitzki") ?: throw IllegalStateException(L.t("חסרה התמונה של פיצקי באפליקציה", "Pitzki's picture is missing from the app"))
                val ref = "pitzki-ep1-" + java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())
                val ed = edge(app)
                val missing = MathEpisode.FRAMES.indices.filter { !sharedScene(app, MathEpisode.FRAMES[it].key).exists() }
                d.total = missing.size
                if (missing.isNotEmpty()) reserve(app, ed, ref)
                for (i in missing) {
                    val prev = if (i == 0) null else BitmapFactory.decodeFile(sharedScene(app, MathEpisode.FRAMES[i - 1].key).absolutePath)
                    val img = drawGpt(app, ed, ref, if (prev == null) MathEpisode.firstPrompt() else MathEpisode.nextPrompt(i),
                        if (prev == null) listOf(pitzki) else listOf(pitzki, prev), d, solo = true)
                        ?: throw IllegalStateException(L.t("ציור ${i + 1} לא הצליח. אפשר לנסות שוב: מה שכבר צויר נשמר.", "Picture ${i + 1} didn't work. Try again: what's drawn is kept."))
                    val out = java.io.ByteArrayOutputStream()
                    img.compress(Bitmap.CompressFormat.JPEG, 92, out)
                    val f = sharedScene(app, MathEpisode.FRAMES[i].key)
                    if (!SafeFiles.writeBytes(f, out.toByteArray())) throw IllegalStateException(L.t("אין מספיק מקום בטלפון", "Not enough space on the phone"))
                    d.done = d.done + 1; d.made++; d.lastAt = System.currentTimeMillis()
                    try { uploadOnceOnly(app, "${MathEpisode.FRAMES[i].key}.jpg", f, "image/jpeg") } catch (_: Exception) { }
                }
                d.phase = "anim"
                val shots = MathEpisode.SHOTS.associateBy { it.key }
                val todo = MathEpisode.SHOT_KEYS.filter { !sharedClip(app, it).exists() }
                fun frame(i: Int) = sharedScene(app, MathEpisode.FRAMES[i].key)
                animateAll(app, d, todo, ref, MathEpisode.ID, { frame(shots.getValue(it).to) }, { sharedClip(app, it) }, { shots.getValue(it).motion },
                    startOf = { frame(shots.getValue(it).from) }, startScene = { shots.getValue(it).motion }, duration = "5")
                for (k in MathEpisode.SHOT_KEYS) sharedClip(app, k).takeIf { it.exists() && it.length() > 0 }?.let { try { uploadOnceOnly(app, it.name, it, "video/mp4") } catch (_: Exception) { } }
                val left = MathEpisode.SHOT_KEYS.count { !sharedClip(app, it).exists() }
                if (left > 0) throw IllegalStateException(L.t("$left קטעים לא הונפשו. אפשר ללחוץ שוב: מה שמוכן נשמר.", "$left shots weren't animated. Tap again: what's ready is kept."))
                d.note = L.t("סופרים את התפוחים בכל קטע…", "Counting the apples in every shot…")
                checkEpisode(app)
            } catch (e: Exception) {
                d.failed = friendly(e, L.t("הסרט לא נוצר", "The film wasn't made"))
            } finally {
                d.finished = true
            }
        }
        return true
    }

    /** The episode is on this phone, every shot of it. */
    fun episodeReady(ctx: Context) = MathEpisode.SHOT_KEYS.all { sharedClip(ctx, it).exists() }

    /** Background thread. Counts the apples every half second of every shot; keeps "shot → problem times". */
    fun checkEpisode(ctx: Context): Map<String, List<LongRange>> {
        val out = LinkedHashMap<String, List<LongRange>>()
        for (s in MathEpisode.SHOTS) {
            val clip = sharedClip(ctx, s.key)
            if (!clip.exists()) continue
            val patches = ArrayList<Pair<Long, List<Int>>>()
            val r = android.media.MediaMetadataRetriever()
            try {
                r.setDataSource(clip.absolutePath)
                val dur = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                var t = 0L
                while (t < dur) {
                    r.getFrameAtTime(t * 1000, android.media.MediaMetadataRetriever.OPTION_CLOSEST)?.let { f ->
                        val sw = 240; val sh = f.height * sw / f.width
                        val sc = Bitmap.createScaledBitmap(f, sw, sh, true)
                        val px = IntArray(sw * sh); sc.getPixels(px, 0, sw, 0, 0, sw, sh)
                        patches.add(t to AppleSpots.redPatches(px, sw, sh))
                        sc.recycle(); f.recycle()
                    }
                    t += 500
                }
            } catch (_: Exception) {} finally { try { r.release() } catch (_: Exception) {} }
            // One apple's size from the shot where both apples lie apart on the table (else the first with apples).
            val single = episodeAppleSize(ctx) ?: AppleSpots.singleSize(patches.firstOrNull()?.second.orEmpty(), MathEpisode.FRAMES[s.from].apples.coerceAtLeast(1))
            val counts = patches.map { (t, p) -> t to AppleSpots.countBySize(p, single) }
            out[s.key] = SceneFlags.outside(counts, MathEpisode.applesDuring(s))
        }
        val a = org.json.JSONObject()
        out.forEach { (k, v) -> a.put(k, org.json.JSONArray().also { arr -> v.forEach { arr.put(org.json.JSONArray().put(it.first).put(it.last + 500)) } }) }
        prefs(ctx).edit().putString("ep1_check", a.toString()).apply()
        return out
    }

    /** One apple's size in the film's pictures: from the key picture with two apples lying apart (the 6th). */
    private fun episodeAppleSize(ctx: Context): Int? {
        val f = sharedScene(ctx, MathEpisode.FRAMES[5].key)
        val b = BitmapFactory.decodeFile(f.absolutePath) ?: return null
        val sw = 240; val sh = b.height * sw / b.width
        val sc = Bitmap.createScaledBitmap(b, sw, sh, true)
        val px = IntArray(sw * sh); sc.getPixels(px, 0, sw, 0, 0, sw, sh)
        return AppleSpots.singleSize(AppleSpots.redPatches(px, sw, sh), 2).takeIf { it > 0 }
    }

    /** The last apple count: shot → times where the apples were wrong (null: not counted). */
    fun episodeProblems(ctx: Context): Map<String, List<LongRange>>? = try {
        prefs(ctx).getString("ep1_check", null)?.let { raw ->
            val o = org.json.JSONObject(raw)
            o.keys().asSequence().associateWith { k -> o.getJSONArray(k).let { a -> (0 until a.length()).map { a.getJSONArray(it).let { r -> r.getLong(0)..r.getLong(1) } } } }
        }
    } catch (_: Exception) { null }

    // ---------- Pitzki's talking scenes (the train world): Hedra videos, or made here through fal ----------

    /**
     * Background thread. Admin: a video made elsewhere (Hedra) becomes Pitzki's talking scene [key] ([second]: its
     * continuation), for every family: kept here, its first frame is the scene's picture, and both go to the server.
     * Returns a problem to show, or null.
     */
    fun importTalk(ctx: Context, key: String, uri: android.net.Uri, second: Boolean): String? {
        val app = ctx.applicationContext
        val to = if (second) sharedTalk2(app, key) else sharedTalk(app, key)
        val tmp = File(to.parentFile, to.name + ".part")
        try {
            app.contentResolver.openInputStream(uri)?.use { inp -> tmp.outputStream().use { inp.copyTo(it) } }
                ?: return L.t("לא הצלחנו לקרוא את הקובץ", "Couldn't read the file")
            val r = android.media.MediaMetadataRetriever()
            val first = try {
                r.setDataSource(tmp.absolutePath)
                if (r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) != "yes") null
                else r.getFrameAtTime(0)
            } catch (_: Exception) { null } finally { try { r.release() } catch (_: Exception) {} }
            if (first == null) { tmp.delete(); return L.t("זה לא נראה כמו סרטון", "That doesn't look like a video") }
            if (tmp.length() > 50L * 1024 * 1024) { tmp.delete(); return L.t("הסרטון גדול מ-50MB", "The video is over 50MB") }
            to.delete()
            if (!tmp.renameTo(to)) { tmp.delete(); return L.t("אין מספיק מקום בטלפון", "Not enough space on the phone") }
            if (!second) {
                // A new talking video: its continuation was made from the old one's last frame.
                sharedTalk2(app, key).delete()
                val out = java.io.ByteArrayOutputStream()
                first.compress(Bitmap.CompressFormat.JPEG, 90, out)
                SafeFiles.writeBytes(sharedScene(app, key), out.toByteArray())
                uploadOnceOnly(app, "$key.jpg", sharedScene(app, key), "image/jpeg")
            }
            uploadOnceOnly(app, to.name, to, "video/mp4")
            return null
        } catch (e: Exception) {
            tmp.delete()
            return friendly(e, L.t("הייבוא לא הצליח", "The import didn't work"))
        }
    }

    /** Pitzki's voice saying [text] (the steady settings), as an mp3 file on this phone. Background thread. */
    /** Background thread. Pitzki's voice for any line (a reply in the chat), made now if it isn't on the phone. */
    fun voiceFor(ctx: Context, text: String): File =
        synchronized(voiceLocks.getOrPut(text.trim()) { Any() }) { voiceMp3(ctx.applicationContext, text) }
    /** Different lines are made at the same time; the same line is never made twice (the second waits for the first). */
    private val voiceLocks = java.util.concurrent.ConcurrentHashMap<String, Any>()

    /** Is Pitzki's voice for [text] already on this phone (said at once)? */
    fun voiceReady(ctx: Context, text: String): Boolean = voiceFile(ctx, PitzkiVoice.spokenText(text)) != null

    // ---------- the chat with Pitzki: his place (in the forest, on a stump, with his animal friends) ----------

    const val CHAT = "chat"
    /** Pitzki listening: looking at the child, smiling and nodding (while the child talks, and on the home screen). */
    const val CHAT_LISTEN = "chat_listen"
    private const val CHAT_LISTEN_MOTION =
        "Pitzki sits quietly on the stump and listens to a child in front of him: he looks straight into the camera the whole time, " +
            "into the viewer's eyes, with a warm, kind smile, small slow nods, natural blinking, mouth closed or softly smiling, never talking. " +
            "His eyes never leave the camera. The animals around him stay calm and still in the background. Slow, steady camera, no cuts."
    private const val CHAT_PICTURE =
        "A single frame from a high-end soft 3D animated children's film for ages 2 to 4, 16:9 landscape. The image is Pitzki, " +
            "a small forest elf: keep his face, curly chestnut hair, green felt hat with a curled tip and a leaf, normal-sized brown eyes, " +
            "leaf-green clothes, brown belt and felt shoes exactly the same. Scene: Pitzki sits comfortably on a big mossy tree stump in a " +
            "magical sunny forest clearing, facing the camera, smiling warmly as if chatting with a friend, his face large and clear. " +
            "Around him his animal friends: two little songbirds on a branch above him, a kind round owl on a low branch, and a fluffy " +
            "bunny sitting in the grass beside the stump. Soft golden light, flowers and ferns. Exactly one Pitzki, no people. " +
            "No text, no letters, no logos."
    private const val CHAT_MOTION =
        "Pitzki looks straight into the camera the whole time, eye contact with the viewer as if talking to a child in front of him: " +
            "he talks warmly and lively, with natural mouth movement, small hand gestures and gentle head nods, and never turns his head " +
            "or eyes toward the animals. Around him, quietly in the background, the birds hop a little on the branch, the owl blinks " +
            "slowly, the bunny stays sitting in place and only twitches its ears. Leaves sway softly. Slow, steady camera, no cuts."

    /** The chat's picture and moving loop, made once for every family (admin). One film credit. */
    fun buildChatScene(ctx: Context, redo: Boolean = false, motionOnly: Boolean = false): Boolean {
        if (drawing?.finished == false) return false
        val app = ctx.applicationContext
        val d = Drawing("chat").apply { title = L.t("המקום של פיצקי לשיחה", "Pitzki's chat place"); total = 1; animateAfter = true }
        drawing = d
        PitzkiService.start(app)
        worker.execute {
            try {
                fetchShared(app)
                if (redo) { if (!motionOnly) sharedScene(app, CHAT).delete(); sharedClip(app, CHAT).delete(); sharedClip(app, CHAT_LISTEN).delete() }
                val ed = edge(app)
                val ref = "pitzki-chat-" + java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())
                if (!sharedScene(app, CHAT).exists()) {
                    reserve(app, ed, ref)
                    val pitzki = art(app, "pitzki") ?: throw IllegalStateException(L.t("חסרה התמונה של פיצקי באפליקציה", "Pitzki's picture is missing from the app"))
                    val img = drawGpt(app, ed, ref, CHAT_PICTURE, listOf(pitzki), d, solo = true)
                        ?: throw IllegalStateException(L.t("הציור לא הצליח. אפשר לנסות שוב.", "The drawing didn't work. Try again."))
                    val out = java.io.ByteArrayOutputStream()
                    img.compress(Bitmap.CompressFormat.JPEG, 90, out)
                    SafeFiles.writeBytes(sharedScene(app, CHAT), out.toByteArray())
                    d.done = 1; d.made++; d.lastAt = System.currentTimeMillis()
                }
                try { uploadOnceOnly(app, "chat.jpg", sharedScene(app, CHAT), "image/jpeg") } catch (_: Exception) { }
                d.phase = "anim"
                // Two loops from the same picture: talking, and listening (looking at the child).
                val todo = listOf(CHAT, CHAT_LISTEN).filter { !sharedClip(app, it).exists() }
                if (todo.isNotEmpty())
                    animateAll(app, d, todo, ref, "chat", { sharedScene(app, CHAT) }, { sharedClip(app, it) }, { if (it == CHAT_LISTEN) CHAT_LISTEN_MOTION else CHAT_MOTION })
                if (sharedClip(app, CHAT_LISTEN).exists()) try { uploadOnceOnly(app, "chat_listen.mp4", sharedClip(app, CHAT_LISTEN), "video/mp4") } catch (_: Exception) { }
                if (sharedClip(app, CHAT).exists()) try { uploadOnceOnly(app, "chat.mp4", sharedClip(app, CHAT), "video/mp4") } catch (_: Exception) { }
                else throw IllegalStateException(L.t("ההנפשה לא הצליחה. אפשר לנסות שוב: הציור נשמר.", "The animation didn't work. Try again: the drawing is kept."))
            } catch (e: Exception) {
                d.failed = friendly(e, L.t("המקום לשיחה לא נוצר", "The chat place wasn't made"))
            } finally {
                d.finished = true
            }
        }
        return true
    }

    private fun voiceMp3(app: Context, text: String): File {
        val f = File(voiceDir(app), voiceKey(PitzkiVoice.spokenText(text)) + ".mp3")
        if (f.exists() && f.length() > 0) return f
        if (download(sharedUrl(app, "voice/" + f.name), f)) return f
        val token = Account.token(app) ?: throw IllegalStateException(L.t("צריך להתחבר לחשבון", "Please sign in"))
        val res = CartoonFilm.callEdge(Circle.url(app) + "/functions/v1/cartoon-film", token, mapOf("action" to "tts", "text" to PitzkiVoice.spokenText(text)))
        if (res.optInt("_code", 200) !in 200..299) throw IllegalStateException(res.optString("error").ifBlank { L.t("שירות הקול לא זמין כרגע", "The voice service isn't available right now") })
        val bytes = android.util.Base64.decode(res.optString("audio"), android.util.Base64.DEFAULT)
        if (bytes.isEmpty() || !SafeFiles.writeBytes(f, bytes)) throw IllegalStateException(L.t("הקול לא נשמר", "The voice wasn't saved"))
        return f
    }

    /**
     * Pitzki's talking videos that came with the app (assets/pitzki/talk: the ones made in Hedra), put in place on
     * this phone (with their first frame as the scene's picture) unless a newer one is already here.
     */
    /**
     * Animal sounds that come with the app (assets/pitzki/farm/sfx_<animal>.mp3, made by the family elsewhere): copied
     * to where the lesson plays them, once. They win over sounds made later (they were chosen by ear).
     */
    fun bundledSfx(ctx: Context) {
        for (a in FarmWorld.ANIMALS) {
            val name = "pitzki/farm/sfx_${a.id}.mp3"
            val f = sfxFile(ctx, a.id)
            val mark = Settings.prefs(ctx)
            try {
                ctx.assets.openFd(name).use { fd -> if (mark.getLong("sfx_bundled_${a.id}", -1L) == fd.length && f.exists()) return@use
                    ctx.assets.open(name).use { input -> f.outputStream().use { input.copyTo(it) } }
                    mark.edit().putLong("sfx_bundled_${a.id}", fd.length).apply() }
            } catch (_: Exception) { // not in the app (or compressed): try a plain open
                try {
                    if (!f.exists()) ctx.assets.open(name).use { input -> f.outputStream().use { input.copyTo(it) } }
                } catch (_: Exception) { }
            }
        }
    }

    private fun bundledTalks(ctx: Context) {
        for (key in PitzkiTalks.KEYS) {
            val f = sharedTalk(ctx, key)
            if (f.exists() && f.length() > 0) continue
            val ok = try {
                ctx.assets.open("pitzki/talk/${key}_talk.mp4").use { i -> f.outputStream().use { i.copyTo(it) } }
                true
            } catch (_: Exception) { f.delete(); false }
            if (!ok || sharedScene(ctx, key).exists()) continue
            val r = android.media.MediaMetadataRetriever()
            val first = try { r.setDataSource(f.absolutePath); r.getFrameAtTime(0) } catch (_: Exception) { null } finally { try { r.release() } catch (_: Exception) {} }
            if (first != null) {
                val out = java.io.ByteArrayOutputStream()
                first.compress(Bitmap.CompressFormat.JPEG, 90, out)
                SafeFiles.writeBytes(sharedScene(ctx, key), out.toByteArray())
            }
        }
    }

    /** Background thread. Admin: Pitzki's lines for the talking scenes as mp3 files, to make the videos in Hedra. */
    fun talkVoices(ctx: Context): List<File> {
        val app = ctx.applicationContext
        val dir = File(app.cacheDir, "share").apply { mkdirs() }
        val out = ArrayList<File>()
        for (t in TrainWorld.TALKS) {
            for ((i, text) in listOf(t.text, t.more).filter { it.isNotBlank() }.withIndex()) {
                val f = File(dir, "pitzki_${t.key}" + (if (i > 0) "_2" else "") + ".mp3")
                voiceMp3(app, text).copyTo(f, overwrite = true)
                out.add(f)
            }
        }
        return out
    }

    private fun mediaMs(f: File): Long {
        val r = android.media.MediaMetadataRetriever()
        return try { r.setDataSource(f.absolutePath); r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L }
        catch (_: Exception) { 0L } finally { try { r.release() } catch (_: Exception) {} }
    }

    private fun lastFrame(f: File): Bitmap? {
        val r = android.media.MediaMetadataRetriever()
        return try {
            r.setDataSource(f.absolutePath)
            val ms = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            r.getFrameAtTime(maxOf(0L, ms - 80) * 1000, android.media.MediaMetadataRetriever.OPTION_CLOSEST)
                ?: r.getFrameAtTime(maxOf(0L, ms - 80) * 1000)
        } catch (_: Exception) { null } finally { try { r.release() } catch (_: Exception) {} }
    }

    private fun sceneTitle(key: String) = (TrainWorld.lesson(PitzkiKid("", false)) + FarmWorld.lesson(PitzkiKid("", false)) + NightWorld.lesson(PitzkiKid("", false)) + NumbersWorld.lesson(PitzkiKid("", false), 1)).firstOrNull { it.key == key }?.title ?: key

    /** What's still missing in the talking scenes: (key, true = the continuation). */
    fun talksMissing(ctx: Context, talks: List<TrainWorld.Talk> = TrainWorld.TALKS): List<Pair<String, Boolean>> = talks.flatMap { t ->
        val main = sharedTalk(ctx, t.key).exists()
        listOfNotNull(if (!main) t.key to false else null, if (t.more.isNotBlank() && !sharedTalk2(ctx, t.key).exists()) t.key to true else null)
    }

    /**
     * Admin: makes the missing talking scenes through fal (the ones not imported from Hedra): the picture (drawn by
     * the book's model from Pitzki's picture), his line in his voice, and his lips matched to it (Kling AI Avatar).
     * A continuation starts from the first video's last frame. Each one goes to the server for every family.
     * [redo]: made again (their old videos deleted first). One film credit.
     */
    fun buildTalks(ctx: Context, redo: Set<String> = emptySet()): Boolean {
        if (drawing?.finished == false) return false
        val app = ctx.applicationContext
        val d = Drawing("talks").apply { title = L.t("פיצקי מדבר", "Pitzki talks"); phase = "anim" }
        drawing = d
        PitzkiService.start(app)
        worker.execute {
            try { makeTalks(app, d, redo, TrainWorld.TALKS) }
            catch (e: Exception) { d.failed = friendly(e, L.t("הסצנות המדברות לא נוצרו", "The talking scenes weren't made")) }
            finally { d.finished = true }
        }
        return true
    }

    /** Background thread. Makes the missing talking videos of [talks] (see [buildTalks]); what failed goes in [d]. */
    private fun makeTalks(app: Context, d: Drawing, redo: Set<String>, talks: List<TrainWorld.Talk>) {
                fetchShared(app)
                for (key in redo) { sharedTalk(app, key).delete(); sharedTalk2(app, key).delete(); if (FarmWorld.asset(key) == null && !key.startsWith("farm_")) sharedScene(app, key).delete() }
                val todo = talksMissing(app, talks)
                d.total = todo.size
                if (todo.isEmpty()) return
                val pitzki = art(app, "pitzki") ?: throw IllegalStateException(L.t("חסרה התמונה של פיצקי באפליקציה", "Pitzki's picture is missing from the app"))
                val ed = edge(app)
                val ref = "pitzki-talks-" + java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())
                reserve(app, ed, ref)
                // One that fails doesn't stop the others: each is tried, and what failed is said at the end.
                val problems = ArrayList<String>()
                for ((key, second) in todo) {
                    val t = PitzkiTalks.get(key) ?: continue
                    d.note = (if (second) L.t("ממשיכים: ", "Continuing: ") + t.more else t.text).take(40) + "…"
                    try {
                    val picture: Bitmap = if (second) {
                        lastFrame(sharedTalk(app, key)) ?: throw IllegalStateException(L.t("לא הצלחנו לקחת את הפריים האחרון", "Couldn't take the last frame"))
                    } else {
                        val have = sharedScene(app, key).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.absolutePath) }
                        have ?: run {
                            if (t.image.isBlank()) throw IllegalStateException(L.t("חסרה התמונה של הסצנה", "The scene's picture is missing"))
                            val prompt = "A single frame from a high-end soft 3D animated children's film for ages 2 to 4, 16:9 landscape. " +
                                "The image is Pitzki, a small forest elf: keep his face, curly chestnut hair, green felt hat with a curled tip and a leaf, " +
                                "normal-sized brown eyes, leaf-green clothes, brown belt and felt shoes exactly the same. Scene, at a cozy little train station " +
                                "at the edge of an enchanted forest, warm morning light: " + t.image +
                                " Pitzki faces the camera, his face large and clear, mouth gently closed. No text, no letters, no logos, no signs with writing."
                            val img = drawGpt(app, ed, ref, prompt, listOf(pitzki), d, solo = true)
                                ?: throw IllegalStateException(L.t("הציור לא הצליח ($key). אפשר לנסות שוב: מה שכבר נוצר נשמר.", "The drawing didn't work ($key). Try again: what's made is kept."))
                            val out = java.io.ByteArrayOutputStream()
                            img.compress(Bitmap.CompressFormat.JPEG, 90, out)
                            SafeFiles.writeBytes(sharedScene(app, key), out.toByteArray())
                            try { uploadOnceOnly(app, "$key.jpg", sharedScene(app, key), "image/jpeg") } catch (_: Exception) { }
                            img
                        }
                    }
                    val sound = voiceMp3(app, if (second) t.more else t.text)
                    val seconds = ((mediaMs(sound) + 999) / 1000).toInt().coerceIn(3, 30)
                    val job = CartoonFilm.callEdge(ed.url, ed.token, mapOf(
                        "action" to "submit", "kind" to "pitzki_talk", "image" to CartoonFilm.dataUri(picture), "ref" to ref, "seconds" to seconds,
                        "audio" to "data:audio/mpeg;base64," + android.util.Base64.encodeToString(sound.readBytes(), android.util.Base64.NO_WRAP),
                        "scene" to t.motion.take(600)))
                    CartoonFilm.checkError(job)
                    val url = CartoonFilm.pollEdge(ed.url, Account.token(app) ?: ed.token, job.optString("status_url"), job.optString("response_url"), java.util.concurrent.atomic.AtomicBoolean(false))
                    if (url.isEmpty()) throw IllegalStateException(L.t("הסנכרון לא הצליח", "The lip-sync didn't work") + CartoonFilm.lastJobDetail.let { if (it.isBlank()) "" else ": ${it.take(160)}" })
                    val to = if (second) sharedTalk2(app, key) else sharedTalk(app, key)
                    if (!download(url, to)) throw IllegalStateException(L.t("ההורדה לא הצליחה", "The download didn't work"))
                    try { uploadOnceOnly(app, to.name, to, "video/mp4") } catch (_: Exception) { /* kept here; sent next time */ }
                    } catch (e: Exception) {
                        problems.add(sceneTitle(key) + (if (second) L.t(" (ההמשך)", " (continuation)") else "") + ": " +
                            friendly(e, L.t("לא נוצר", "not made")))
                    }
                    d.animated++
                    d.made++; d.lastAt = System.currentTimeMillis()
                }
                d.note = ""
                if (problems.isNotEmpty()) d.failed = problems.joinToString("\n")
    }

    // ---------- a photo of the child with Pitzki ----------

    /** Where the photo takes place (id, name, emoji, the place for the drawing model). */
    val PHOTO_PLACES = listOf(
        listOf("forest", "ביער", "🌲", "sitting together on a mossy fallen log in a sunlit magical forest, warm golden light through the trees"),
        listOf("train", "ברכבת", "🚂", "sitting together by the big window of a red and silver double-decker train, green hills outside, warm light"),
        listOf("farm", "בחווה", "🐄", "sitting together on a hay bale at a cozy farm with a red barn, a friendly cow and chickens around, sunny afternoon"),
        listOf("sea", "בים", "🌊", "sitting together on the sand at the seashore building a sandcastle, gentle waves and a soft sunset"),
        listOf("stars", "בלילה עם כוכבים", "🌙", "sitting together on a hill at night looking up at a sky full of stars and a big friendly moon, soft blue light"),
    )

    /** The photos made of [member] with Pitzki, newest first. */
    fun photos(ctx: Context, member: String): List<File> =
        dir(ctx, member).listFiles()?.filter { it.name.startsWith("with_pitzki_") && it.name.endsWith(".jpg") }?.sortedByDescending { it.lastModified() }.orEmpty()

    // ---------- cutting a bad moment out of an animation (free: it's skipped when played) ----------

    private fun cutKey(f: File) = "pitzki_cut_" + f.absolutePath
    /** The parts of [f] that are skipped when it plays, in ms (from, to). */
    fun cuts(ctx: Context, f: File): List<Pair<Long, Long>> =
        Settings.prefs(ctx).getString(cutKey(f), "").orEmpty().split(";").mapNotNull { p ->
            val a = p.substringBefore("-").toLongOrNull(); val b = p.substringAfter("-", "").toLongOrNull()
            if (a != null && b != null && b > a) a to b else null
        }
    fun addCut(ctx: Context, f: File, fromMs: Long, toMs: Long) {
        if (toMs <= fromMs) return
        val all = (cuts(ctx, f) + (fromMs to toMs)).sortedBy { it.first }
        Settings.prefs(ctx).edit().putString(cutKey(f), all.joinToString(";") { "${it.first}-${it.second}" }).apply()
    }
    fun clearCuts(ctx: Context, f: File) { Settings.prefs(ctx).edit().remove(cutKey(f)).apply() }

    // ---------- the parent goes over the child's scenes before the first walk ----------

    /** Changes whenever one of the child's scenes or animations is made again (so it's gone over again). */
    private fun reviewMark(ctx: Context, member: String): String = PitzkiScript.CHILD_KEYS.joinToString("|") { k ->
        "${sceneFile(ctx, member, k).lastModified()}:${clipFile(ctx, member, k).lastModified()}:${moreFile(ctx, member, k).lastModified()}"
    }
    fun reviewed(ctx: Context, member: String) = prefs(ctx).getString("reviewed_${PitzkiScript.worldId}_$member", "") == reviewMark(ctx, member)
    fun setReviewed(ctx: Context, member: String) = prefs(ctx).edit().putString("reviewed_${PitzkiScript.worldId}_$member", reviewMark(ctx, member)).apply()

    /** Only this scene's animation made again (the drawing stays): about $0.7 for 10 seconds. */
    fun reanimateOne(ctx: Context, c: Child, key: String): Boolean {
        if (drawing?.finished == false) return false
        clipFile(ctx, c.memberId, key).delete(); moreFile(ctx, c.memberId, key).delete()
        if (key == PitzkiScript.INTRO_KEY) handFile(ctx, c.memberId).delete()
        clearCuts(ctx, clipFile(ctx, c.memberId, key))
        return drawScenes(ctx, c, animate = true)
    }

    /** Background thread. A photo of the child with Pitzki the family made elsewhere: kept with the others. */
    fun addOwnPhoto(ctx: Context, member: String, uri: android.net.Uri): Boolean {
        val b = MediaImages.decode(ctx, uri.toString(), 2048) ?: return false
        val out = java.io.ByteArrayOutputStream()
        b.compress(Bitmap.CompressFormat.JPEG, 92, out)
        return SafeFiles.writeBytes(File(dir(ctx, member), "with_pitzki_${System.currentTimeMillis()}.jpg"), out.toByteArray())
    }

    /** The newest photo with Pitzki of any child (for the home screen), or null. */
    fun latestPhoto(ctx: Context): File? = children(ctx).flatMap { photos(ctx, it.memberId) }.maxByOrNull { it.lastModified() }

    /**
     * A photo of the child with Pitzki in [place] (about 25 cents, one step of the film credit): the child looks like
     * the real photos (real proportions, not a cartoon), Pitzki stays the animated elf. Kept with the child's photos
     * and added to the family's moments (so it's in the gallery, and shared like any moment).
     */
    fun makePhoto(ctx: Context, c: Child, place: String): Boolean {
        if (drawing?.finished == false) return false
        val app = ctx.applicationContext
        val where = PHOTO_PLACES.firstOrNull { it[0] == place } ?: PHOTO_PLACES[0]
        val d = Drawing("photo-${c.memberId}").apply { title = L.t("תמונה עם פיצקי", "A photo with Pitzki"); total = 1 }
        drawing = d
        PitzkiService.start(app)
        worker.execute {
            try {
                val pitzki = art(app, "pitzki") ?: throw IllegalStateException(L.t("חסרה התמונה של פיצקי באפליקציה", "Pitzki's picture is missing from the app"))
                val real = c.photos.take(4).mapNotNull { try { BitmapFactory.decodeFile(it.absolutePath) } catch (_: Throwable) { null } }
                if (real.isEmpty()) throw IllegalStateException(L.t("צריך קודם תמונה של ${c.kid.name} (בפרופיל, או 3 תמונות פנים במסך של פיצקי)", "First add a photo of ${c.kid.name}"))
                val who = if (c.kid.girl) "a little girl" else "a little boy"
                val prompt = "A heartwarming, high-end cinematic image. The first image is Pitzki, a small animated forest elf: keep his face, curly " +
                    "chestnut hair, green felt hat with a curled tip and a leaf, normal-sized brown eyes, leaf-green clothes, brown belt and felt " +
                    "shoes exactly the same, in his soft 3D animated style. The other images are real photos of the same child, $who. " +
                    "Show this exact child looking just like the real photos: a real, photographic child with real proportions, normal-sized eyes, " +
                    "the same face, hair, curls and skin tone (NOT a cartoon). The child and Pitzki are best friends, ${where[3]}; the child's arm " +
                    "around Pitzki, both smiling warmly at the camera, cozy and happy. Exactly one Pitzki and one child. No text, no letters, no logos."
                val ed = edge(app)
                reserve(app, ed, creditRef(app, c))
                val img = drawGpt(app, ed, creditRef(app, c), prompt, listOf(pitzki) + real, d)
                    ?: throw IllegalStateException(L.t("התמונה לא נוצרה. אפשר לנסות שוב.", "The photo wasn't made. Try again."))
                val f = File(dir(app, c.memberId), "with_pitzki_${System.currentTimeMillis()}.jpg")
                val out = java.io.ByteArrayOutputStream()
                img.compress(Bitmap.CompressFormat.JPEG, 92, out)
                if (!SafeFiles.writeBytes(f, out.toByteArray())) throw IllegalStateException(L.t("אין מספיק מקום בטלפון", "Not enough space on the phone"))
                // Into the family's moments too (the gallery, the shared gallery of the parents).
                try {
                    val id = java.util.UUID.randomUUID().toString()
                    val thumb = MemoryStore.saveBubble(app, id, img)
                    MemoryStore.add(app, Memory(
                        id = id, uri = f.absolutePath, name = f.name, time = System.currentTimeMillis(), label = "manual",
                        lat = null, lon = null, place = "", thumb = thumb, durationMs = 0L,
                        title = L.t("📸 ${c.kid.name} ופיצקי ${where[1]}", "📸 ${c.kid.name} and Pitzki"), titleByUser = true,
                        chosen = true, highlightMs = -1, mediaType = "image", timeSource = "captured", origin = "pitzki_photo", noLearn = true,
                    ))
                } catch (_: Exception) { /* the photo is kept with Pitzki anyway */ }
                d.done = 1; d.made++; d.lastAt = System.currentTimeMillis()
            } catch (e: Exception) {
                d.failed = friendly(e, L.t("התמונה לא נוצרה", "The photo wasn't made"))
            } finally {
                d.finished = true
            }
        }
        return true
    }

    // ---------- the farm: pictures from the app, the animals hidden and coming out, sounds, Pitzki talking ----------

    fun sfxFile(ctx: Context, animal: String) = File(sharedDir(ctx), "sfx_$animal.mp3")

    /** What's still missing in the farm (for the admin's card): pictures, Pitzki's videos, the animals coming out, sounds. */
    fun farmMissing(ctx: Context): Int =
        FarmWorld.ANIMALS.count { !sharedScene(ctx, it.key).exists() } + (if (!sharedScene(ctx, FarmWorld.INTRO).exists()) 1 else 0) +
            talksMissing(ctx, FarmWorld.TALKS).size + FarmWorld.ANIMALS.count { !sharedClip(ctx, it.open).exists() } +
            FarmWorld.ANIMALS.count { !sfxFile(ctx, it.id).exists() }

    /**
     * Admin: only the animals' sounds (the voice service's sound effects, a few cents each), for every family: so the
     * lesson plays the real sound instead of Pitzki saying it.
     */
    fun buildFarmSounds(ctx: Context): Boolean {
        if (drawing?.finished == false) return false
        val app = ctx.applicationContext
        val todo = FarmWorld.ANIMALS.filter { !sfxFile(app, it.id).exists() }
        val d = Drawing("farm").apply { title = L.t("קולות החיות", "The animals' sounds"); total = todo.size }
        drawing = d
        PitzkiService.start(app)
        worker.execute {
            try {
                val ed = edge(app)
                d.note = L.t("מכינים את קולות החיות…", "Making the animals' sounds…")
                for (a in todo) {
                    val res = CartoonFilm.callEdge(ed.url, Account.token(app) ?: ed.token, mapOf("action" to "sfx", "text" to a.sfx, "seconds" to 3))
                    if (res.optInt("_code", 200) !in 200..299) throw IllegalStateException(res.optString("error").ifBlank { L.t("שירות הקולות לא זמין", "The sound service isn't available") })
                    val bytes = android.util.Base64.decode(res.optString("audio"), android.util.Base64.DEFAULT)
                    if (bytes.isNotEmpty() && SafeFiles.writeBytes(sfxFile(app, a.id), bytes))
                        try { upload(app, "sfx_${a.id}.mp3", bytes, "audio/mpeg") } catch (_: Exception) { }
                    d.done++; d.made++; d.lastAt = System.currentTimeMillis()
                }
                d.note = ""
            } catch (e: Exception) {
                d.failed = friendly(e, L.t("הקולות לא נוצרו", "The sounds weren't made"))
            } finally { d.finished = true }
        }
        return true
    }

    /** What's still missing in the farm, by kind: pictures, Pitzki's talking videos, animals coming out, sounds. */
    fun farmParts(ctx: Context): IntArray = intArrayOf(
        FarmWorld.ANIMALS.count { !sharedScene(ctx, it.key).exists() } + (if (!sharedScene(ctx, FarmWorld.INTRO).exists()) 1 else 0),
        talksMissing(ctx, FarmWorld.TALKS).size,
        FarmWorld.ANIMALS.count { !sharedClip(ctx, it.open).exists() },
        FarmWorld.ANIMALS.count { !sfxFile(ctx, it.id).exists() })

    /** About what making only the missing parts costs, in dollars (a picture ~$0.25, a talking video ~$0.45, an animal coming out ~$0.35, a sound ~$0.02). */
    fun farmCost(parts: IntArray): Double = parts[0] * 0.25 + parts[1] * 0.45 + parts[2] * 0.35 + parts[3] * 0.02

    /**
     * Admin: makes the farm for every family through fal (about $8, once): the gate's picture without its wrong
     * words, each animal's picture with the animal hidden (drawn from the app's picture), Pitzki asking in each
     * (lips matched to his voice), each animal coming out (from hidden to seen), each animal's sound (the voice
     * service's sound effects), and Pitzki's hello and goodbye. All go to the server. [redo]: scene keys made again.
     */
    fun buildFarm(ctx: Context, redo: Set<String> = emptySet()): Boolean {
        if (drawing?.finished == false) return false
        val app = ctx.applicationContext
        val d = Drawing("farm").apply { title = L.t("החווה של פיצקי", "Pitzki's farm") }
        drawing = d
        PitzkiService.start(app)
        worker.execute {
            val problems = ArrayList<String>()
            try {
                fetchShared(app)
                for (key in redo) {
                    sharedTalk(app, key).delete(); sharedClip(app, key).delete()
                    if (FarmWorld.asset(key) == null || key == FarmWorld.INTRO) sharedScene(app, key).delete()
                }
                val ed = edge(app)
                val ref = "pitzki-farm-" + java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())
                reserve(app, ed, ref)
                fun asset(name: String): Bitmap? = try { app.assets.open("pitzki/farm/$name.jpg").use { BitmapFactory.decodeStream(it) } } catch (_: Exception) { null }
                fun save(key: String, img: Bitmap) {
                    val out = java.io.ByteArrayOutputStream()
                    img.compress(Bitmap.CompressFormat.JPEG, 90, out)
                    SafeFiles.writeBytes(sharedScene(app, key), out.toByteArray())
                    try { uploadOnceOnly(app, "$key.jpg", sharedScene(app, key), "image/jpeg") } catch (_: Exception) { }
                }
                // 1. The pictures: the animals seen and all of them together as they are; the gate cleaned; each animal hidden.
                for (a in FarmWorld.ANIMALS) if (!sharedScene(app, a.open).exists()) asset(a.open)?.let { save(a.open, it) }
                if (!sharedScene(app, "farm_review").exists()) asset("farm_end")?.let { save("farm_review", it) }
                val drawTodo = (listOf(FarmWorld.INTRO) + FarmWorld.ANIMALS.map { it.key }).filter { !sharedScene(app, it).exists() }
                d.total = drawTodo.size
                for (key in drawTodo) {
                    val a = FarmWorld.animal(key)
                    d.note = if (a != null) L.t("מסתירים את ה${a.he}…", "Hiding the ${a.id}…") else L.t("מנקים את השלט…", "Cleaning the sign…")
                    try {
                        val from = (if (a != null) asset(a.open) else asset("farm_gate")) ?: continue
                        // The gate's sign is already written right in the app's picture ("חוות החברים"); drawing it again
                        // made up letters. It's used as it is.
                        if (a == null) { save(key, from); d.done++; continue }
                        val img = drawGpt(app, ed, ref, if (a != null) FarmWorld.hiddenPrompt(a) else FarmWorld.GATE_PROMPT, listOf(from), d, solo = true)
                            ?: throw IllegalStateException(L.t("הציור לא הצליח", "The drawing didn't work"))
                        save(key, img)
                    } catch (e: Exception) { problems.add(sceneTitle(key) + ": " + friendly(e, L.t("לא צויר", "not drawn"))) }
                    d.done++; d.made++; d.lastAt = System.currentTimeMillis()
                }
                // 2. The sounds (the voice service's sound effects), once.
                d.note = L.t("מכינים את קולות החיות…", "Making the animals' sounds…")
                for (a in FarmWorld.ANIMALS) {
                    if (sfxFile(app, a.id).exists()) continue
                    try {
                        val res = CartoonFilm.callEdge(ed.url, Account.token(app) ?: ed.token, mapOf("action" to "sfx", "text" to a.sfx, "seconds" to 3))
                        if (res.optInt("_code", 200) !in 200..299) throw IllegalStateException(res.optString("error").ifBlank { L.t("שירות הקולות לא זמין", "The sound service isn't available") })
                        val bytes = android.util.Base64.decode(res.optString("audio"), android.util.Base64.DEFAULT)
                        if (bytes.isNotEmpty() && SafeFiles.writeBytes(sfxFile(app, a.id), bytes))
                            try { upload(app, "sfx_${a.id}.mp3", bytes, "audio/mpeg") } catch (_: Exception) { }
                    } catch (e: Exception) { problems.add("${a.he} (${L.t("קול", "sound")}): " + friendly(e, L.t("לא נוצר", "not made"))); break }
                }
                // 3. Each animal coming out: from its hidden picture to the seen one (5 seconds).
                d.phase = "anim"
                val outTodo = FarmWorld.ANIMALS.filter { sharedScene(app, it.key).exists() && sharedScene(app, it.open).exists() && !sharedClip(app, it.open).exists() }.map { it.open }
                if (outTodo.isNotEmpty())
                    animateAll(app, d, outTodo, ref, "farm", { sharedScene(app, it) }, { sharedClip(app, it) },
                        { FarmWorld.revealMotion(FarmWorld.animal(it)!!) }, startOf = { sharedScene(app, FarmWorld.animal(it)!!.key) }, duration = "5")
                for (a in FarmWorld.ANIMALS) sharedClip(app, a.open).takeIf { it.exists() }?.let { try { uploadOnceOnly(app, it.name, it, "video/mp4") } catch (_: Exception) { } }
                // 4. Pitzki talking: hello, asking for each animal (still hidden), goodbye.
                d.animated = 0
                makeTalks(app, d, emptySet(), FarmWorld.TALKS)
                if (d.failed.isNotEmpty()) { problems.add(d.failed); d.failed = "" }
            } catch (e: Exception) {
                problems.add(friendly(e, L.t("החווה לא נוצרה", "The farm wasn't made")))
            } finally {
                d.note = ""
                if (problems.isNotEmpty()) d.failed = problems.joinToString("\n")
                d.finished = true
            }
        }
        return true
    }

    // ---------- Pitzki's voice (ElevenLabs, through the server) ----------

    /** How the child's name is said (with niqqud), as the parent wrote it; "" = as written. */
    fun sayName(ctx: Context, member: String): String = Settings.prefs(ctx).getString("pitzki_say_$member", "").orEmpty()
    fun setSayName(ctx: Context, member: String, said: String) {
        Settings.prefs(ctx).edit().putString("pitzki_say_$member", said.trim()).apply()
        voiceAt = 0L // the lines with the name are made again, said the new way
    }

    private fun voiceDir(ctx: Context) = File(File(ctx.filesDir, "pitzki"), "voice").apply { mkdirs() }

    /** A line's name: the same words are the same recording, for every family. */
    fun voiceKey(text: String): String {
        // "v2": made with the steadier voice settings (the same accent in every line); older recordings are made again.
        val d = java.security.MessageDigest.getInstance("SHA-1").digest(("v2|" + text.trim()).toByteArray(Charsets.UTF_8))
        return d.joinToString("") { "%02x".format(it) }.take(20)
    }

    /** Pitzki saying [text] in his own voice, if it's ready on this phone. */
    fun voiceFile(ctx: Context, text: String): File? = File(voiceDir(ctx), voiceKey(text) + ".mp3").takeIf { it.exists() && it.length() > 0 }

    /** How the voice is getting ready (for the screen): lines ready, lines in all, and a problem if one stopped it. */
    class VoicePrep(@Volatile var ready: Int = 0, @Volatile var total: Int = 0, @Volatile var problem: String = "", @Volatile var finished: Boolean = false)
    @Volatile var voicePrep: VoicePrep? = null
        private set
    private val voiceWorker = Executors.newSingleThreadExecutor()

    /**
     * Gets every line of [kid]'s lesson in Pitzki's voice, in the background: from the shared folder when another
     * family already has it, otherwise made by the server (and, by the admin, shared for everyone). Lines with the
     * child's name are only for this phone. Quietly stops if the voice isn't set up yet (the phone's voice is used).
     */
    private var voiceFor = ""
    private var voiceAt = 0L

    fun prepareVoice(ctx: Context, kid: PitzkiKid) {
        val prev = voicePrep
        if (prev != null && !prev.finished) return
        // Done for this child a moment ago (or the voice isn't open yet): not asked again on every screen refresh.
        // After a problem (a key just fixed, no network) it's tried again sooner: in a minute.
        val gap = if (prev?.problem?.isNotEmpty() == true) 60_000L else 10 * 60_000L
        if (prev != null && voiceFor == kid.name && System.currentTimeMillis() - voiceAt < gap) return
        voiceFor = kid.name
        voiceAt = System.currentTimeMillis()
        val app = ctx.applicationContext
        val lines = PitzkiScript.voiceLines(kid)
        val p = VoicePrep(total = lines.size)
        voicePrep = p
        voiceWorker.execute {
            try {
                val admin = Account.isAdmin(app)
                val token = Account.token(app) ?: throw IllegalStateException(L.t("צריך להתחבר לחשבון", "Please sign in"))
                class Closed(msg: String) : Exception(msg)
                fun one(text: String) {
                    // Named by what is said: the child's name the way the family says it is its own recording.
                    val f = File(voiceDir(app), voiceKey(PitzkiVoice.spokenText(text)) + ".mp3")
                    if (f.exists() && f.length() > 0) { synchronized(p) { p.ready++ }; return }
                    val shareable = !(kid.named && text.contains(kid.name))
                    if (shareable && download(sharedUrl(app, "voice/" + f.name), f)) { synchronized(p) { p.ready++ }; return }
                    // Through the same lock as the talk's lines: one line is never made twice at once.
                    synchronized(voiceLocks.getOrPut(text.trim()) { Any() }) {
                        if (f.exists() && f.length() > 0) { synchronized(p) { p.ready++ }; return }
                        val res = CartoonFilm.callEdge(Circle.url(app) + "/functions/v1/cartoon-film", token,
                            mapOf("action" to "tts", "text" to PitzkiVoice.spokenText(text)))
                        val code = res.optInt("_code", 200)
                        if (code == 403 || code == 503) throw Closed(res.optString("error")) // not open yet: the phone's voice stays
                        if (code !in 200..299) throw IllegalStateException(res.optString("error").ifBlank { L.t("שירות הקול לא זמין כרגע", "The voice service isn't available right now") })
                        val bytes = android.util.Base64.decode(res.optString("audio"), android.util.Base64.DEFAULT)
                        if (bytes.isEmpty() || !SafeFiles.writeBytes(f, bytes)) return
                        if (admin && shareable) try { upload(app, "voice/" + f.name, bytes, "audio/mpeg") } catch (_: Exception) { /* kept on this phone */ }
                    }
                    synchronized(p) { p.ready++ }
                }
                // Three lines made at the same time (each takes a few seconds at the voice service).
                val pool = java.util.concurrent.Executors.newFixedThreadPool(3)
                val stop = java.util.concurrent.atomic.AtomicBoolean(false)
                val jobs = lines.map { text ->
                    pool.submit {
                        if (!stop.get()) try { one(text) } catch (e: Exception) {
                            stop.set(true)
                            if (p.problem.isEmpty()) p.problem = if (e is Closed) e.message.orEmpty() else friendly(e, L.t("הקול של פיצקי לא הוכן", "Pitzki's voice wasn't made"))
                        }
                    }
                }
                jobs.forEach { try { it.get() } catch (_: Exception) {} }
                pool.shutdown()
            } catch (e: Exception) {
                p.problem = friendly(e, L.t("הקול של פיצקי לא הוכן", "Pitzki's voice wasn't made"))
            } finally {
                p.finished = true
            }
        }
    }

    // ---------- a test: Pitzki sings with his lips matched to the song ----------

    /** The test's result: the song, sung by Pitzki with matching lips. */
    fun talkTest(ctx: Context) = File(sharedDir(ctx), "song_talk.mp4")

    /**
     * Admin only, a test before building it for real: Pitzki's song scene + his whole song (Suno, ~19 s) go to
     * a lip-sync model; the result shows how well his mouth follows the singing. Uses one film credit.
     */
    fun makeTalkTest(ctx: Context): Boolean {
        if (drawing?.finished == false) return false
        val app = ctx.applicationContext
        val d = Drawing("talk").apply { phase = "anim"; total = 1; title = L.t("פיצקי שר", "Pitzki sings") }
        drawing = d
        PitzkiService.start(app)
        worker.execute {
            try {
                fetchShared(app)
                val scene = sharedScene(app, "song")
                if (!scene.exists()) {
                    // The song scene isn't made yet: draw it now (the same picture the shared scenes use).
                    val pitzki = art(app, "pitzki") ?: throw IllegalStateException(L.t("חסרה התמונה של פיצקי באפליקציה", "Pitzki's picture is missing from the app"))
                    val img = GeminiClient.scene(app, sharedPrompt("song", false), listOf(pitzki))
                        ?: throw IllegalStateException(L.t("הציור של סצנת השיר לא הצליח", "Drawing the song scene didn't work"))
                    val out = java.io.ByteArrayOutputStream()
                    img.compress(Bitmap.CompressFormat.JPEG, 88, out)
                    SafeFiles.writeBytes(scene, out.toByteArray())
                }
                val bmp = BitmapFactory.decodeFile(scene.absolutePath) ?: throw IllegalStateException(L.t("לא הצלחנו לפתוח את סצנת השיר", "Couldn't open the song scene"))
                val sound = app.assets.open("pitzki/song.m4a").use { it.readBytes() }
                val ed = edge(app)
                val talkRef = "pitzki-talk-" + java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())
                reserve(app, ed, talkRef)
                val job = CartoonFilm.callEdge(ed.url, ed.token, mapOf(
                    "action" to "submit", "kind" to "pitzki_talk", "image" to CartoonFilm.dataUri(bmp), "ref" to talkRef, "seconds" to 20,
                    "audio" to "data:audio/mp4;base64," + android.util.Base64.encodeToString(sound, android.util.Base64.NO_WRAP),
                    "scene" to "Pitzki stands on a mossy tree stump in a sunny forest clearing and sings his song joyfully, swaying and spreading his arms."))
                if (job.optInt("_code", 200) == 400 && job.optString("error").contains("לא מוכרת"))
                    throw IllegalStateException(L.t("צריך להעלות לשרת את הגרסה החדשה של cartoon-film (עם pitzki_talk).", "Upload the new cartoon-film version (with pitzki_talk) to the server."))
                CartoonFilm.checkError(job)
                val url = CartoonFilm.pollEdge(ed.url, Account.token(app) ?: ed.token, job.optString("status_url"), job.optString("response_url"), java.util.concurrent.atomic.AtomicBoolean(false))
                if (url.isEmpty()) throw IllegalStateException(L.t("הסנכרון לא הצליח", "The lip-sync didn't work") + CartoonFilm.lastJobDetail.let { if (it.isBlank()) "" else ": ${it.take(160)}" })
                if (!download(url, talkTest(app))) throw IllegalStateException(L.t("ההורדה לא הצליחה", "The download didn't work"))
                // Every family's film opens with it (the song scene, sung with matching lips).
                if (Account.isAdmin(app)) try { uploadOnceOnly(app, "song_talk.mp4", talkTest(app), "video/mp4") } catch (_: Exception) { /* kept here; sent next time */ }
                d.animated = 1
            } catch (e: Exception) {
                d.failed = friendly(e, L.t("הניסיון לא הצליח", "The test didn't work"))
            } finally {
                d.finished = true
            }
        }
        return true
    }

    /** Puts one close-up on the server (only the admin may: see server/pitzki-shared-scenes.sql). */
    /**
     * Uploads a shared file unless this very version was already uploaded (a fetched or already-sent file isn't
     * sent again; a file drawn again is).
     */
    private fun uploadOnceOnly(ctx: Context, name: String, f: File, type: String) {
        val p = Settings.prefs(ctx)
        val mark = "$name:${f.length()}:${f.lastModified()}"
        val sent = p.getStringSet("pitzki_uploaded", emptySet()).orEmpty()
        if (mark in sent) return
        upload(ctx, name, f.readBytes(), type)
        p.edit().putStringSet("pitzki_uploaded", sent.filterNot { it.startsWith("$name:") }.toSet() + mark).apply()
    }

    /** Uploads to the shared folder; a passing server or network hiccup (5xx, a cut line) is tried again 3 times. */
    private fun upload(ctx: Context, name: String, bytes: ByteArray, type: String) {
        var wait = 3_000L
        for (attempt in 1..4) {
            try { uploadOnce(ctx, name, bytes, type); return }
            catch (e: Busy) { if (attempt == 4) throw IllegalStateException(e.message) }
            catch (e: java.io.IOException) { if (attempt == 4) throw e }
            Thread.sleep(wait)
            wait *= 2
        }
    }

    private class Busy(msg: String) : Exception(msg)

    private fun uploadOnce(ctx: Context, name: String, bytes: ByteArray, type: String) {
        val token = Account.token(ctx) ?: throw IllegalStateException(L.t("צריך להתחבר לחשבון", "Please sign in"))
        val conn = java.net.URL(Circle.url(ctx) + "/storage/v1/object/pitzki-shared/$SHARED_V/$name").openConnection() as java.net.HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 30_000
            conn.readTimeout = 120_000
            conn.doOutput = true
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("apikey", Circle.key(ctx))
            conn.setRequestProperty("Content-Type", type)
            conn.setRequestProperty("x-upsert", "true")
            conn.outputStream.use { it.write(bytes) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val why = try { conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty().take(120) } catch (_: Exception) { "" }
                // Too big for the shared folder's size limit (the singing video can be large).
                if (code == 413 || "too large" in why.lowercase() || "maximum allowed size" in why)
                    throw IllegalStateException(L.t("הקובץ $name גדול מדי לתיקייה המשותפת (${bytes.size / 1_048_576} MB). צריך להריץ את pitzki-shared-size.sql ב-Supabase.",
                        "$name is too big for the shared folder (${bytes.size / 1_048_576} MB). Run pitzki-shared-size.sql in Supabase."))
                // 5xx: the server was busy for a moment (tried again). Otherwise the folder or its rules are missing.
                if (code >= 500) throw Busy(L.t("השרת של Supabase לא ענה כרגע ($code). מה שצויר נשמר בטלפון: לחצו שוב בעוד דקה.", "Supabase's server didn't answer right now ($code). What's drawn is kept on the phone: tap again in a minute."))
                throw IllegalStateException(L.t("ההעלאה לשרת לא הצליחה ($code). צריך להריץ פעם אחת את pitzki-shared-scenes.sql ב-Supabase.", "Uploading to the server failed ($code). Run pitzki-shared-scenes.sql once in Supabase.") + if (why.isBlank()) "" else " $why")
            }
        } finally { conn.disconnect() }
    }

    /** A close-up: Pitzki alone with the thing, big and in focus. */
    private fun sharedPrompt(key: String, withStyle: Boolean): String {
        if (key.startsWith("train_")) return TrainWorld.sharedPrompt(key)
        if (key.startsWith("night_")) return NightWorld.sharedPrompt(key)
        if (key.startsWith("num_")) return NumbersWorld.sharedPrompt(key)
        if (key == "song") return "A single frame from a high-end soft 3D animated children's film for ages 3 to 5. Wide landscape composition. " +
            "The first image is Pitzki, a small forest elf: keep his face, curly chestnut hair, green pointed hat with a leaf, " +
            "leaf-green clothes, brown shoes and proportions exactly the same. " +
            (if (withStyle) "The second image is an earlier scene of the same film: same style, same forest, same light. " else "") +
            "Scene: Pitzki stands on a mossy tree stump in a sunny forest clearing, singing joyfully to the viewer with open arms, " +
            "butterflies and tiny birds around him. Exactly one Pitzki (never two elves): no child, no other people. " +
            "No text, no letters, no music notes, no speech bubbles, no watermark, no borders."
        val c = PColor.values().first { it.name.lowercase() == key }
        return "A single frame from a high-end soft 3D animated children's film for ages 3 to 5. Wide landscape composition, close-up. " +
            "The first image is Pitzki, a small forest elf: keep his face, curly chestnut hair, green pointed hat with a leaf, " +
            "leaf-green clothes, brown shoes and proportions exactly the same. " +
            (if (withStyle) "The second image is an earlier scene of the same film: same style, same forest, same light. " else "") +
            "Scene: a close-up of ${c.enThing}, big, sharp and in the center, its color vivid and unmistakable; Pitzki is beside it, " +
            "pointing at it with a delighted face. Exactly one Pitzki in the picture (never two elves, no twin, no reflection): no child, no other people. Soft blurred forest background, warm sunlight. " +
            "No text, no letters, no speech bubbles, no watermark, no borders."
    }

    private fun sharedMotion(key: String): String {
        if (key.startsWith("train_")) return TrainWorld.sharedMotion(key)
        if (key.startsWith("night_")) return NightWorld.sharedMotion(key)
        if (key.startsWith("num_")) return NumbersWorld.sharedMotion(key)
        if (key == "song") return "Pitzki sings happily to the camera, mouth moving with the song, dancing on the tree stump: he sways, " +
            "taps his foot, spreads his arms, tips his hat and bows; butterflies fly around him. Lively, joyful, smooth camera. Only Pitzki, no other people."
        val c = PColor.values().first { it.name.lowercase() == key }
        return "Close-up: ${c.enMotion}. Pitzki points at it, looks at the camera with an excited, delighted face and talks, " +
            "as if telling the child about it. Slow cinematic camera push-in. Only Pitzki, no other people."
    }

    // ---------- scenes being animated (kept on the phone, so closing the app doesn't lose them) ----------

    private fun pendingFile(ctx: Context, member: String) = File(dir(ctx, member), "animating.json")

    /** The service keeps Pitzki's results 16 hours (the server asks it to); collected a little before that. */
    private const val KEEP_MS = 15 * 3600_000L + 30 * 60_000L

    /** Scenes sent for animation in the last 16 hours (kept that long by the service): scene → (status, result). */
    private fun pending(ctx: Context, member: String): Map<String, Pair<String, String>> {
        val o = ((SafeFiles.readObject(pendingFile(ctx, member)) as? SafeFiles.Read.Ok<*>)?.value as? JSONObject) ?: return emptyMap()
        if (System.currentTimeMillis() - o.optLong("at") > KEEP_MS) return emptyMap()
        val out = HashMap<String, Pair<String, String>>()
        val arr = o.optJSONArray("jobs") ?: return emptyMap()
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { j ->
            // "k": the scene's name; "n": its number in the first version.
            val key = j.optString("k").ifEmpty { OLD.getOrNull(j.optInt("n") - 1).orEmpty() }
            if (key.isNotEmpty() && j.optString("status").isNotEmpty()) out[key] = j.optString("status") to j.optString("response")
        }
        return out
    }

    private fun savePending(ctx: Context, member: String, jobs: List<Triple<String, String, String>>) {
        val old = ((SafeFiles.readObject(pendingFile(ctx, member)) as? SafeFiles.Read.Ok<*>)?.value as? JSONObject)?.optLong("at") ?: 0L
        val at = if (old > 0 && System.currentTimeMillis() - old < KEEP_MS) old else System.currentTimeMillis()
        val arr = org.json.JSONArray()
        jobs.forEach { (n, st, rs) -> arr.put(JSONObject().put("k", n).put("status", st).put("response", rs)) }
        SafeFiles.write(pendingFile(ctx, member), JSONObject().put("at", at).put("jobs", arr).toString())
    }

    /** Animation was cut off (the app was closed): true if it can be picked up again without paying again. */
    fun interrupted(ctx: Context, c: Child): Boolean =
        drawing?.finished != false && scenesReady(ctx, c) && pending(ctx, c.memberId).isNotEmpty()

    /** Picks up an animation cut off by closing the app (collects what was already sent). */
    fun resume(ctx: Context, c: Child): Boolean = if (interrupted(ctx, c)) drawScenes(ctx, c, animate = true) else false

    /** After a clip was saved: asks the service to delete the request's pictures and video (the child's face). */
    private fun forget(app: Context, edgeUrl: String, token: String, statusUrl: String) {
        val id = Regex("/requests/([0-9a-fA-F-]{36})").find(statusUrl)?.groupValues?.get(1) ?: return
        val ok = try { CartoonFilm.callEdge(edgeUrl, token, mapOf("action" to "forget", "request_id" to id)).optBoolean("ok", false) } catch (_: Exception) { false }
        recordForget(app, ok)
    }

    /** How many of the requests' deletions at fal were confirmed (the admin sees it: the real photos must not stay there). */
    fun recordForget(ctx: Context, ok: Boolean) {
        val p = Settings.prefs(ctx)
        p.edit().putInt("fal_forget_all", p.getInt("fal_forget_all", 0) + 1).putInt("fal_forget_ok", p.getInt("fal_forget_ok", 0) + if (ok) 1 else 0).apply()
    }
    fun forgetStats(ctx: Context): Pair<Int, Int> = Settings.prefs(ctx).let { it.getInt("fal_forget_ok", 0) to it.getInt("fal_forget_all", 0) }

    private fun download(url: String, to: File): Boolean = try {
        val tmp = File(to.parentFile, to.name + ".part")
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 30_000
        conn.readTimeout = 90_000
        try { conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } } } finally { conn.disconnect() }
        tmp.length() > 0 && tmp.renameTo(to)
    } catch (_: Exception) { false }

    /** What moves in each scene: Pitzki and the child walk and act together, like a film. */
    /**
     * What moves in each scene. Only Pitzki talks: the child never moves their lips as if speaking (it looked like
     * the child was dubbed), and moves in their own way, never copying Pitzki's movements.
     */
    /** The opening animated from Pitzki alone to both together: Pitzki reaches out and leads the child in. */
    private const val INTRO_FROM_START =
            "Pitzki stands on the forest path reaching his open hand toward the right edge of the frame and calls warmly to someone outside the picture; " +
                "a small child's hand, a 3-year-old's hand no bigger than Pitzki's own, reaches in and takes his hand, and Pitzki gently leads the child into the frame to the middle of the path, " +
                "where they stop together hand in hand and smile. The child walks in calmly and happily. The child does not talk: the child's mouth " +
                "stays closed in a natural smile. Nobody talks in this shot. The child's face, hair and clothes are exactly as in the last frame."

    private fun motion(key: String): String {
        val c = PColor.values().firstOrNull { it.name.lowercase() == key }
        // Nobody talks in the picture (Pitzki's voice is heard over it): a moving mouth looked like the child was talking.
        val child = " Nobody talks in this shot: the child's mouth stays closed in a gentle smile the whole time (a short silent laugh at most), " +
            "and the child reacts only with the eyes, looks, nods and their own gestures, never copying or mirroring Pitzki's movements. " +
            "Pitzki's mouth stays in a closed smile too. The child's face, eye size and proportions stay exactly as in the picture, " +
            "in the same 3D cartoon style as Pitzki (never turning realistic)."
        if (key.startsWith("train_")) return TrainWorld.childMotion(key) + child
        if (key.startsWith("night_")) return NightWorld.childMotion(key) + child
        if (key.startsWith("play_")) return PlayWorld.childMotion(key) + child
        if (key.startsWith("num_")) return NumbersWorld.childMotion(key) + child
        if (key.startsWith("farm_")) return FarmWorld.childMotion(key) + child
        return when {
            key == "intro" -> "Pitzki walks up to the child and stops right in front of them, looking at the child the whole time with a warm smile. " +
                "Pitzki waves hello warmly; the child smiles shyly, then they give each other a happy high-five. " +
                "Pitzki never turns away or walks past the child." + child
            key == "walk2" -> "Pitzki and the child walk deeper into the magical forest, hand in hand; Pitzki points around, the child looks up at the fireflies in wonder. The camera follows them." + child
            key == "walk3" -> "Pitzki and the child hop from stone to stone across the sparkling stream, one hop at a time, laughing; little splashes. The camera follows them." + child
            key == "walk4" -> "Pitzki points ahead across the little bridge; the child looks where he points, eyes wide with excitement, then they start walking together." + child
            key == "walk" -> "Pitzki and the child walk side by side along the forest path; Pitzki points at the trees and the child points too, amazed. The camera tracks with them as they walk." + child
            c != null -> "Pitzki points at ${c.enThing}; the child's face lights up with surprise and the child looks at it, smiling." + child
            // The end has the color quiz (the child taps the colors below), then goodbye: they stay, facing the child.
            else -> "Pitzki and the child sit side by side on the mossy log facing the viewer, looking down toward the bottom of the frame encouragingly, as if waiting for an answer; " +
                "Pitzki gives a thumbs-up, then both smile and wave warmly. They stay sitting; nobody walks away." + child
        }
    }

    /** A message a parent understands: no internet is said plainly (and that trying again costs nothing more). */
    private fun friendly(e: Exception, fallback: String): String {
        var t: Throwable? = e
        while (t != null) {
            if (t is java.net.UnknownHostException || t is java.net.ConnectException || t is java.net.SocketTimeoutException || t is javax.net.ssl.SSLException)
                return L.t("אין חיבור לאינטרנט כרגע. כשיש רשת לחצו שוב: מה שכבר נעשה נשמר, ובשעות הקרובות זה לא יחויב שוב.",
                    "No internet connection right now. When you're online, tap again: what's done is kept, and it won't be charged again in the next few hours.")
            // "Software caused connection abort" and the like: the line was cut in the middle (the screen went off,
            // the phone switched between Wi-Fi and mobile data).
            if (t is java.net.SocketException || t is java.io.InterruptedIOException)
                return L.t("החיבור לאינטרנט נקטע באמצע (אולי המסך כבה או שהרשת התחלפה). לחצו שוב: מה שכבר נעשה נשמר ולא יחויב שוב.",
                    "The internet connection was cut in the middle (maybe the screen went off or the network switched). Tap again: what's done is kept and won't be charged again.")
            t = t.cause
        }
        return e.message?.takeIf { it.isNotBlank() } ?: fallback
    }

    // ---------- a short clip made longer: 5 more seconds that start exactly where it ended ----------

    /** The continuation of a scene's clip (played right after it, from its last frame). */
    fun moreFile(ctx: Context, member: String, key: String) = File(dir(ctx, member), "clip_${key}_more.mp4")

    /**
     * The opening's first part: Pitzki alone reaches out, and only the child's hand comes in and takes his (the face
     * isn't seen, so it can't look wrong). Then the opening itself plays, from the drawing with both of them.
     */
    fun handFile(ctx: Context, member: String) = File(dir(ctx, member), "clip_${PitzkiScript.INTRO_KEY}_hand.mp4")
    private const val HAND = "hand"
    private const val HAND_MOTION =
        "Pitzki stands alone, looks toward the right edge of the frame with a big warm smile and waves in a friendly 'come here' gesture, " +
            "then beckons twice with his hand in a 'come with me' gesture and turns a little toward the path, with small excited bounces. " +
            "Nobody else appears: no hand, no arm and no other person ever enters the frame. Exactly one Pitzki, the same size all the time. " +
            "Pitzki's mouth stays in a closed smile. Slow smooth camera."
    private fun endFrameFile(ctx: Context, member: String, key: String) = File(dir(ctx, member), "end_$key.jpg")

    private fun durationMs(f: File): Long = try {
        val r = android.media.MediaMetadataRetriever()
        try { r.setDataSource(f.absolutePath); r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L }
        finally { r.release() }
    } catch (_: Exception) { 0L }

    /** The child's scenes whose animation is short (made when the walks were 5 seconds) and not continued yet. */
    fun shortClips(ctx: Context, c: Child): List<String> = PitzkiScript.CHILD_KEYS.filter { it in PitzkiScript.LONG_CLIPS }.filter {
        val f = clipFile(ctx, c.memberId, it)
        f.exists() && !moreFile(ctx, c.memberId, it).exists() && durationMs(f) in 1..7_000
    }

    /**
     * Continues the short clips: their last frame is animated 5 more seconds (the same scene going on), so the
     * existing animation isn't thrown away and the join is seamless. About $0.35 each.
     */
    fun extendClips(ctx: Context, c: Child): Boolean {
        if (drawing?.finished == false) return false
        val keys = shortClips(ctx, c)
        if (keys.isEmpty()) return false
        val app = ctx.applicationContext
        val d = Drawing(c.memberId).apply { title = L.t("הסרט של ${c.kid.name}", "${c.kid.name}'s film"); phase = "anim" }
        drawing = d
        PitzkiService.start(app)
        worker.execute {
            try {
                for (k in keys) {
                    val f = endFrameFile(app, c.memberId, k)
                    if (f.exists()) continue
                    val r = android.media.MediaMetadataRetriever()
                    try {
                        r.setDataSource(clipFile(app, c.memberId, k).absolutePath)
                        val dur = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                        val last = r.getFrameAtTime((dur - 80).coerceAtLeast(0) * 1000, android.media.MediaMetadataRetriever.OPTION_CLOSEST)
                        if (last != null) {
                            val out = java.io.ByteArrayOutputStream()
                            last.compress(Bitmap.CompressFormat.JPEG, 92, out)
                            SafeFiles.writeBytes(f, out.toByteArray())
                        }
                    } finally { r.release() }
                }
                val todo = keys.filter { endFrameFile(app, c.memberId, it).exists() }
                animateAll(app, d, todo, creditRef(app, c), c.memberId + "-more", { endFrameFile(app, c.memberId, it) },
                    { moreFile(app, c.memberId, it) }, ::moreMotion, duration = "5")
            } catch (e: Exception) {
                d.failed = friendly(e, L.t("ההארכה לא הצליחה", "Making it longer didn't work"))
            } finally {
                d.finished = true
            }
        }
        return true
    }

    private fun moreMotion(key: String): String =
        "The same shot continues seamlessly from this exact frame, same camera, same light. " + when (key) {
            "train_intro", "train_end", "train_ticket" -> "Pitzki and the child stand together on the station platform; Pitzki keeps talking warmly and gestures, the child smiles and looks at the little train."
            "train_board", "train_window" -> "The train moves gently; Pitzki and the child look out of the window together, smiling."
            "intro", "end" -> "Pitzki and the child stand together hand in hand; Pitzki keeps talking warmly and gestures, the child smiles and looks around happily."
            "walk3" -> "Pitzki and the child keep hopping from stone to stone across the stream, laughing."
            "walk4" -> "Pitzki and the child walk on across the little bridge toward the flowers."
            else -> "Pitzki and the child keep walking together along the forest path, looking around with curiosity; Pitzki talks and points."
        } + " The child does not talk: the child's mouth stays closed in a natural smile. Nobody talks in this shot. Faces, clothes and art style stay exactly the same."

    /** The opening's first moment (Pitzki alone, on the same path as the opening scene, reaching out his hand). */
    fun startFile(ctx: Context, member: String, key: String = PitzkiScript.INTRO_KEY) = File(dir(ctx, member).also { migrate(it) }, "scene_${key}_start.jpg")

    private fun startPrompt(): String =
        "A single frame from a high-end soft 3D animated children's film for ages 3 to 5. Wide landscape composition. " +
            "The first image is Pitzki, a small forest elf: keep his face, curly chestnut hair, green pointed hat with a leaf, " +
            "leaf-green clothes, brown shoes and proportions exactly the same. The second image is the opening scene of the film: " +
            "keep exactly the same place, path, trees, light, camera angle and framing, but the child is NOT in the picture. " +
            "Pitzki stands a little left of the middle, turned toward the right edge of the frame, smiling warmly and reaching his open hand " +
            "out to someone just outside the picture on the right, inviting them in. Only Pitzki is visible. " +
            "No text, no letters, no speech bubbles, no watermark, no borders."

    /** One scene's drawing instructions. Pitzki is the first picture, the child the second, scene 1 the third. */
    private fun prompt(n: String, c: Child, withFirst: Boolean, photos: Int = 0, fromPhotos: Boolean = false): String {
        val withPhoto = photos > 0
        val who = if (c.kid.girl) "a little girl" else "a little boy"
        val childRef = if (c.look?.hero == true)
            "The second image is the child, $who, as the approved character of this film. Draw exactly this character in the scene: " +
                "the same face, the same eyes (the same size and shape, not bigger), hair, curls, skin tone, age, body proportions and clothes, " +
                "in the same soft 3D animated style as Pitzki. The child's mouth is closed in a gentle smile."
        else if (fromPhotos)
            "The second image is a real photo of the child, $who. Draw this exact child in a soft 3D animated look but with REALISTIC proportions: " +
                "normal-sized eyes exactly like in the photo (never big cartoon eyes), the real face shape, nose, mouth, hair color, curls and hairline, skin tone and age. " +
                "Pitzki's cartoon style applies to Pitzki only; the child looks like a gently rendered version of the real photo."
        else if (c.look?.drawn == true)
            "The second image is the child, $who: an existing animated character. Keep exactly the same face, hair, skin tone, age and clothes."
        else
            "The second image is a real photo of the child, $who. Turn the child into the same soft 3D animated style as Pitzki, keeping them recognizable: same face shape, hair color and style, skin tone and age."
        val color = PColor.values().firstOrNull { it.name.lowercase() == n }
        val action = when {
            PitzkiScript.isFarm -> FarmWorld.childAction(n)
            PitzkiScript.isTrain -> TrainWorld.childAction(n)
            PitzkiScript.isNight -> NightWorld.childAction(n)
            PitzkiScript.isPlay -> PlayWorld.childAction(n)
            PitzkiScript.isNumbers -> NumbersWorld.childAction(n)
            n == "intro" -> "Pitzki and the child stand together in the middle of a sunny magical forest path, holding hands, the child a little to the right of Pitzki, both smiling happily."
            n == "walk" -> "Pitzki and the child walk together along the forest path, looking around with curiosity."
            n == "walk2" -> "Pitzki and the child walk hand in hand deeper into the magical forest, with glowing fireflies and a little pond ahead."
            n == "walk3" -> "Pitzki and the child hop together across big flat stepping stones in a shallow sparkling forest stream, arms out for balance, both delighted."
            n == "walk4" -> "Pitzki and the child stand on a small wooden bridge over a creek, Pitzki pointing ahead to a colorful clearing full of flowers, the child looking where he points, excited."
            color != null -> "Pitzki and the child find ${color.enThing}; Pitzki points at it and the child looks at it, delighted. It is clearly visible and its color is vivid."
            else -> "Pitzki and the child sit side by side on a big mossy log on the forest path, facing the viewer, smiling, in warm golden evening light."
        }
        return "A single frame from a high-end soft 3D animated children's film for ages 3 to 5. Wide landscape composition. " +
            "The first image is Pitzki, a small forest elf: keep his face, curly chestnut hair, green pointed hat with a leaf, " +
            "leaf-green clothes, brown shoes and proportions exactly the same. $childRef " +
            (if (withFirst) "The third image is an earlier scene of the same film: same characters, same style, same place" +
                (if (fromPhotos) " (the child's face and eye size always come from the real photos, not from it). " else ". ") else "") +
            (if (withPhoto) (if (photos > 1) "The last $photos images are real photos of the same child, from different moments and angles: " else "The last image is a real photo of the same child: ") +
                "use them only for the face, so the drawn child clearly looks like this real child (face shape, eye shape and size, eyebrows, " +
                "nose, mouth and smile, cheeks, hair color, curls and hairline), still in the animated style. " else "") +
            "Scene: $action Exactly two characters: one Pitzki and one child (never two elves). " +
            (if (c.look?.hero == true) "The child is drawn exactly as the approved cartoon character of the second image: a 3D animated " +
                "cartoon like Pitzki, NOT realistic and NOT photographic, with the same face, eyes, hair and clothes. "
            else "Keep the child's real eye size and face proportions, do not enlarge the eyes. ") +
            "The child's mouth is closed in a gentle smile. Both characters fully visible, the same height scale, gentle happy expressions, slow calm mood, " +
            (if (PitzkiScript.isNumbers) NumbersWorld.SETTING else if (PitzkiScript.isPlay) PlayWorld.SETTING else if (PitzkiScript.isNight) NightWorld.SETTING else if (PitzkiScript.isFarm) FarmWorld.SETTING else if (PitzkiScript.isTrain) TrainWorld.SETTING else "soft warm sunlight, lush friendly forest") +
            ". No text, no letters, no speech bubbles, no watermark, no borders."
    }

    /** Runs [f] on the main thread. */
    fun onMain(f: () -> Unit) = main.post(f)
}
