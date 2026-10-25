package com.regaim.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.google.common.collect.ImmutableList
import java.io.File
import kotlin.math.abs
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Premium: one moment as a short 3D animated (Pixar-style) film, about 15 seconds.
 *
 * The flow, in a [Session]:
 *  1. Look: we pick the best 3 scenes of the moment, draw ONE of them as a cartoon (the "anchor"), and show it.
 *     The family approves it (or asks for another drawing) before anything expensive happens.
 *  2. Film: every other scene is drawn like the approved anchor (same face, age, hair, clothes), each drawn scene
 *     is animated into a ~5 s clip, and a failed scene is tried once more.
 *  3. The clips are joined with the moment's own sound (voices, laughs) and soft music, plus the watermark,
 *     then saved to the gallery and to the archive. This keeps going if the screen is closed.
 *
 * All AI runs on our server (Supabase Edge Function "cartoon-film"); the key never lives in the app.
 * One credit opens a window of 12 AI calls; a film uses 5–10 of them.
 */
@OptIn(markerClass = [UnstableApi::class])
object CartoonFilm {
    /**
     * A video moment becomes a 15-second film: the best frame (the "anchor", whose drawing the family approved)
     * animated for 10 seconds, and one more scene for 5. Fewer drawings = the characters stay the same.
     * A photo makes a 5-second film. Longer films: "extend" (another credit) adds 15 seconds more.
     */
    private const val SCENE_MS = 5000L
    private const val VIDEO_SCENES = 2
    private const val CALL_BUDGET = 20
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** The film being made right now (one at a time), so the screen can come back to it. */
    @Volatile var current: Session? = null
        private set

    fun scenesFor(m: Memory): Int = if (!m.isVideo) 1 else VIDEO_SCENES
    fun seconds(m: Memory): Int = if (!m.isVideo) 5 else 15
    /** Seconds added by one extension (one more credit). */
    const val EXTEND_SECONDS = 15

    /** Starts a new film for [m]: reserves a credit, picks the scenes and draws the first look. */
    fun start(ctx: Context, m: Memory): Session {
        current?.takeIf { it.isActive }?.let { return it }
        current?.discard()
        val s = Session(ctx.applicationContext, m)
        current = s
        worker.execute { s.prepare() }
        return s
    }

    /** How many films this account can still make, or null if we can't tell right now. Call off the main thread. */
    fun credits(ctx: Context): Int? {
        return try {
            if (!Account.signedIn(ctx)) return null
            val token = Account.token(ctx) ?: return null
            val r = callEdge(Circle.url(ctx) + "/functions/v1/cartoon-film", token, mapOf("action" to "credits"))
            // The server also says whether payments are on (Google Play), so the app knows to offer buying a book.
            if (r.has("pay")) Billing.setOn(ctx, r.optBoolean("pay", false))
            if (r.has("credits")) r.optInt("credits") else null
        } catch (_: Exception) { null }
    }

    /**
     * The account's film credits and, for the film being made now, how many paid steps (a drawing or an animation)
     * are left in it and until when (an ISO date). Null if we can't tell. Background thread.
     */
    fun filmStatus(ctx: Context): Triple<Int, Int?, String>? = try {
        val token = Account.token(ctx)
        if (!Account.signedIn(ctx) || token == null) null else {
            val r = callEdge(Circle.url(ctx) + "/functions/v1/cartoon-film", token, mapOf("action" to "credits"))
            if (!r.has("credits")) null
            else Triple(r.optInt("credits"), if (r.has("steps_left")) r.optInt("steps_left") else null, r.optString("until"))
        }
    } catch (_: Exception) { null }

    /** Admin: adds [n] film credits to the account with [email]. Returns its new balance, or the problem. Background thread. */
    fun grant(ctx: Context, email: String, n: Int): Result<Int> = try {
        val token = Account.token(ctx) ?: throw IllegalStateException(L.t("צריך להתחבר לחשבון", "Please sign in"))
        val r = callEdge(Circle.url(ctx) + "/functions/v1/cartoon-film", token, mapOf("action" to "grant", "email" to email, "n" to n))
        if (r.optInt("_code", 200) in 200..299 && r.optBoolean("ok")) Result.success(r.optInt("balance"))
        else Result.failure(IllegalStateException(r.optString("error").ifBlank { L.t("השרת לא מכיר עדיין את הפעולה (צריך Deploy).", "The server doesn't know this yet (Deploy needed).") }))
    } catch (e: Exception) { Result.failure(e) }

    /** A server answer that means "stop the whole film" (not signed in, no credits, server not set up). */
    internal class FatalEdgeError(message: String) : Exception(message)

    class Scene(val bmp: Bitmap, val timeMs: Long, val score: Float)

    class Session internal constructor(private val ctx: Context, val memory: Memory) {
        enum class State { PREPARING, LOOK, FILMING, DONE, FAILED, CANCELLED }

        @Volatile var state = State.PREPARING
            private set
        @Volatile var look: Bitmap? = null
            private set
        @Volatile var status = ""
            private set
        @Volatile var progress = -1
            private set
        @Volatile var result: File? = null
            private set
        @Volatile var savedId: String? = null
            private set
        @Volatile var error: String? = null
            private set
        /** Honest note about the result, e.g. "2 of 3 scenes worked". Empty when everything worked. */
        @Volatile var note = ""
            private set
        @Volatile var redrawsLeft = 2
            private set
        /** A message about the last "extend" that didn't happen (no credits...). Shown on the result screen. */
        @Volatile var extendError: String? = null
            private set
        /** Every animated piece so far (its time in the clip, its file, how long), kept so the film can grow. */
        private val pieces = ArrayList<Triple<Long, File, Long>>()
        private var lastMood: Music.Mood? = null
        private var lastVoices = true
        /** Total length of the finished film, in seconds. */
        val filmSeconds: Int get() = synchronized(pieces) { (pieces.sumOf { it.third } / 1000L).toInt() }
        /** Called on the main thread whenever something changes. The screen sets it. */
        var listener: (() -> Unit)? = null

        val isActive get() = state == State.PREPARING || state == State.LOOK || state == State.FILMING

        /** The original frame chosen for the look (shown while the drawing is being made). */
        @Volatile var preview: Bitmap? = null
            private set
        @Volatile private var phaseStart = System.currentTimeMillis()
        @Volatile private var sceneStart = 0L
        @Volatile private var scenesDone = 0
        private val lookHistory = ArrayList<Pair<Bitmap, String>>()
        /** Every drawing made so far, oldest first, so the family can go back to one they liked more. */
        val looks: List<Bitmap> get() = synchronized(lookHistory) { lookHistory.map { it.first } }
        /** Index in [looks] of the drawing the film will use. */
        @Volatile var chosenLook = 0
            private set

        fun chooseLook(i: Int) {
            if (state != State.LOOK) return
            synchronized(lookHistory) {
                val pick = lookHistory.getOrNull(i) ?: return
                look = pick.first; anchorUrl = pick.second; chosenLook = i
            }
            changed()
        }

        /**
         * 0–99 for the "filling" circle. The AI services don't report progress, so it is estimated from
         * typical times (a drawing ~40 s, a scene ~90 s), moving on for real as each scene finishes.
         */
        fun estimate(): Int {
            val now = System.currentTimeMillis()
            return when (state) {
                State.PREPARING -> ((now - phaseStart) * 95 / 70_000L).toInt().coerceIn(2, 95)
                State.FILMING -> {
                    if (progress in 0..100) return 90 + progress / 10
                    val total = scenes.size.coerceAtLeast(1)
                    val inScene = if (sceneStart > 0) ((now - sceneStart) / 90_000f).coerceIn(0f, 0.95f) else 0f
                    (((scenesDone.coerceAtMost(total) + inScene) / total) * 88f).toInt().coerceIn(2, 89)
                }
                State.DONE -> 100
                else -> 0
            }
        }

        private val cancelled = AtomicBoolean(false)
        private var edgeUrl = ""
        private var token = ""
        private var reserved = false
        private var calls = 0
        private var scenes: List<Scene> = emptyList()
        private var anchorIdx = 0
        private var anchorUrl = ""
        private var aspect = "9:16"
        /** Profile photos of the people in the moment, sent with the first drawing so the faces look like them. */
        private var refs: List<String> = emptyList()
        /**
         * The family's approved characters (from their books) of the people and pets in this moment, with labels:
         * when there are any, the film draws them exactly as in the books instead of from profile photos.
         */
        private var chars: List<String> = emptyList()
        private var charLabels: List<String> = emptyList()
        /** Whose profile photos were used (shown on the approval screen). Empty = none. */
        @Volatile var refNames: List<String> = emptyList()
            private set
        private var transformer: Transformer? = null

        private fun changed() = main.post { listener?.invoke() }
        private fun update(st: State, text: String = status) {
            if (st != state) phaseStart = System.currentTimeMillis()
            state = st; status = text; changed()
        }

        private fun fail(message: String) {
            refundQuietly()
            error = message
            update(State.FAILED)
            if (current === this) current = null
        }

        // ---------- 1. the look ----------

        internal fun prepare() {
            try {
                update(State.PREPARING, L.t("בוחרים את הרגעים הכי טובים מהסרטון...", "Picking the best moments of the clip..."))
                if (!Account.signedIn(ctx)) throw IllegalStateException(L.t("צריך להתחבר כדי ליצור סרטים", "Sign in to create films"))
                token = Account.token(ctx) ?: throw IllegalStateException(L.t("בעיה בהתחברות. התחברו מחדש ונסו שוב.", "Sign-in problem. Sign in again and try again."))
                edgeUrl = Circle.url(ctx) + "/functions/v1/cartoon-film"

                scenes = pickScenes(ctx, memory, scenesFor(memory))
                if (scenes.isEmpty()) throw IllegalStateException(L.t("לא הצלחנו לקרוא את הרגע", "Couldn't read the moment"))
                anchorIdx = scenes.indices.maxByOrNull { scenes[it].score } ?: 0
                aspect = aspectOf(scenes[anchorIdx].bmp)
                preview = scenes[anchorIdx].bmp
                loadFaceRefs()

                val res = callEdge(edgeUrl, token, mapOf("action" to "reserve"))
                if (!res.optBoolean("ok", false))
                    throw IllegalStateException(res.optString("error", "").ifBlank { L.t("לא הצלחנו להתחיל את הסרט. נסו שוב.", "Couldn't start the film. Try again.") })
                reserved = true
                drawLook()
            } catch (e: Exception) {
                if (!cancelled.get()) fail(friendly(e, L.t("לא הצלחנו להתחיל את הסרט", "Couldn't start the film")))
            }
        }

        private fun drawLook() {
            update(State.PREPARING, L.t("מציירים את הדמויות... (כחצי דקה)", "Drawing the characters... (about 30 seconds)"))
            // With the family's approved characters: drawn exactly as in their books (already animated: no second pass).
            val withChars = chars.isNotEmpty()
            val body = mutableMapOf<String, Any>("action" to "submit", "kind" to if (withChars) "style_chars" else "style", "image" to dataUri(scenes[anchorIdx].bmp), "aspect" to aspect)
            if (withChars) { body["refs"] = org.json.JSONArray(chars); body["labels"] = org.json.JSONArray(charLabels) }
            else if (refs.isNotEmpty()) body["refs"] = org.json.JSONArray(refs)
            val drawn = submitAndWait(body)
            if (cancelled.get()) return
            if (drawn.isEmpty()) throw IllegalStateException(L.t("הציור לא הצליח הפעם. נסו שוב.", "The drawing didn't work this time. Try again."))
            // Drawn with the face photos: the face model keeps too much "photo", so a second pass makes it fully 3D.
            val url = if (!withChars && refs.isNotEmpty()) polish(drawn) else drawn
            if (cancelled.get()) return
            val bmp = downloadBitmap(url) ?: throw IllegalStateException(L.t("לא הצלחנו להוריד את הציור. בדקו את החיבור.", "Couldn't download the drawing. Check your connection."))
            anchorUrl = url
            look = bmp
            synchronized(lookHistory) { lookHistory.add(bmp to url); chosenLook = lookHistory.size - 1 }
            update(State.LOOK, "")
        }

        /**
         * The family's profile photos of the people in this moment (up to two, children first).
         * If nobody is tagged and the family has exactly one child with a photo, that child.
         */
        private fun loadFaceRefs() {
            loadCharacters()
            try {
                val names = Fmt.names(memory).map { it.trim() }.toSet()
                val people = Profile.members(ctx).filter { !it.isPet && it.photo.isNotBlank() }
                val inMoment = people.filter { it.name.trim() in names }
                val chosen = inMoment.ifEmpty { people.filter { it.isChild }.takeIf { it.size == 1 } ?: emptyList() }
                    .sortedByDescending { it.isChild }.take(2)
                // Only the face, cropped: a whole photo (with its own pose and background) confuses the drawing model.
                val loaded = chosen.mapNotNull { p -> MediaImages.decode(ctx, p.photo, 960)?.let { p.name to dataUri(faceCrop(it)) } }
                refs = loaded.map { it.second }
                refNames = loaded.map { it.first }
            } catch (_: Exception) {
                refs = emptyList(); refNames = emptyList()
            }
        }

        /** Saved characters of up to 3 people/pets tagged in the moment: each sheet, plus a close-up of the face when found. */
        private fun loadCharacters() {
            try {
                val names = Fmt.names(memory).map { it.trim() }.toSet()
                val pics = ArrayList<String>()
                val labels = ArrayList<String>()
                val who = ArrayList<String>()
                for (m in Profile.members(ctx).filter { it.name.trim() in names }.sortedByDescending { it.isChild }) {
                    if (who.size >= 3) break
                    val c = CharacterStore.latest(ctx, m.id) ?: continue
                    val sheet = android.graphics.BitmapFactory.decodeFile(CharacterStore.file(ctx, c).absolutePath) ?: continue
                    val kind = m.petKind?.let { "the family's $it" } ?: "a person"
                    pics.add(dataUri(sheet)); labels.add("${m.name} ($kind): character sheet")
                    if (!m.isPet) {
                        val face = faceCrop(sheet)
                        if (face !== sheet) { pics.add(dataUri(face)); labels.add("${m.name}: close-up of the face - copy it exactly") }
                    }
                    who.add(m.name)
                }
                chars = pics; charLabels = labels
                if (who.isNotEmpty()) refNames = who
            } catch (_: Exception) {
                chars = emptyList(); charLabels = emptyList()
            }
        }

        /** "Draw them again": a new look, up to two times. */
        fun redraw() {
            if (state != State.LOOK || redrawsLeft <= 0) return
            redrawsLeft--
            update(State.PREPARING)
            worker.execute {
                try { drawLook() } catch (e: Exception) { if (!cancelled.get()) fail(friendly(e, L.t("הציור נכשל", "Drawing failed"))) }
            }
        }

        fun cancel() {
            if (!isActive) return
            cancelled.set(true)
            try { transformer?.cancel() } catch (_: Throwable) {}
            update(State.CANCELLED)
            if (current === this) current = null
            worker.execute { refundQuietly() }
        }

        // ---------- 2. the film ----------

        fun makeFilm(mood: Music.Mood?, withVoices: Boolean) {
            if (state != State.LOOK) return
            update(State.FILMING, L.t("מתחילים...", "Starting..."))
            worker.execute {
                try { film(mood, withVoices) } catch (e: Exception) {
                    if (!cancelled.get()) fail(friendly(e, L.t("לא הצלחנו ליצור את הסרט", "Couldn't create the film")))
                }
            }
        }

        private fun film(mood: Music.Mood?, withVoices: Boolean) {
            lastMood = mood; lastVoices = withVoices
            val total = scenes.size
            val clips = arrayOfNulls<File>(total)
            var lastError = ""
            fun attempt(i: Int) {
                try {
                    sceneStart = System.currentTimeMillis()
                    clips[i] = animateScene(i)
                    if (clips[i] != null) scenesDone++
                } catch (e: FatalEdgeError) {
                    throw IllegalStateException(e.message)
                } catch (e: Exception) {
                    lastError = friendly(e, "")
                }
            }
            for (i in 0 until total) {
                if (cancelled.get()) return
                update(State.FILMING, L.t("מציירים ומנפישים סצנה ${i + 1} מתוך $total...", "Drawing and animating scene ${i + 1} of $total..."))
                attempt(i)
            }
            // One more try for a scene that failed, while the call budget allows it.
            for (i in 0 until total) {
                if (cancelled.get()) return
                if (clips[i] == null && calls + 2 <= CALL_BUDGET) {
                    update(State.FILMING, L.t("מנסים שוב את סצנה ${i + 1}...", "Trying scene ${i + 1} again..."))
                    attempt(i)
                }
            }
            if (cancelled.get()) return
            val okIdx = (0 until total).filter { clips[it] != null }
            if (okIdx.isEmpty())
                throw IllegalStateException(lastError.ifBlank { L.t("ההנפשה לא הצליחה הפעם. הקרדיט הוחזר, אפשר לנסות שוב.", "The animation didn't work this time. Your credit was returned; you can try again.") })
            note = if (okIdx.size < total) L.t("${okIdx.size} מתוך $total סצנות הצליחו, לכן הסרט קצר יותר.", "${okIdx.size} of $total scenes worked, so the film is shorter.") +
                (if (lastError.isNotBlank()) " ($lastError)" else "") else ""
            synchronized(pieces) { okIdx.forEach { pieces.add(Triple(scenes[it].timeMs, clips[it]!!, durationMs(clips[it]!!))) } }
            update(State.FILMING, L.t("מרכיבים את הסרט עם הקול והמוזיקה...", "Putting the film together with sound and music..."))
            compose()
        }

        /**
         * "Make it longer": one more credit, two more scenes (10 + 5 seconds) from other spots in the clip,
         * drawn like the approved characters, and the whole film is put together again.
         */
        fun extend() {
            if (state != State.DONE || !memory.isVideo) return
            extendError = null
            cancelled.set(false)
            // The first film's credit is spent for good: nothing below may refund it.
            reserved = false
            progress = -1
            scenesDone = 0
            update(State.FILMING, L.t("מוסיפים עוד 15 שניות...", "Adding 15 more seconds..."))
            worker.execute {
                try {
                    val res = callEdge(edgeUrl, token, mapOf("action" to "reserve"))
                    if (!res.optBoolean("ok", false)) {
                        extendError = res.optString("error", "").ifBlank { L.t("לא הצלחנו להאריך את הסרט כרגע.", "Couldn't extend the film right now.") }
                        update(State.DONE, ""); return@execute
                    }
                    reserved = true
                    calls = 0
                    val used = synchronized(pieces) { pieces.map { it.first } }
                    val fresh = pickScenes(ctx, memory, 4)
                        .sortedByDescending { sc -> used.minOfOrNull { abs(it - sc.timeMs) } ?: Long.MAX_VALUE }
                        .take(2)
                    if (fresh.isEmpty()) throw IllegalStateException(L.t("לא מצאנו עוד רגעים בסרטון להוסיף", "No more spots in the clip to add"))
                    val added = ArrayList<Triple<Long, File, Long>>()
                    fresh.forEachIndexed { k, sc ->
                        if (cancelled.get()) return@execute
                        update(State.FILMING, L.t("מציירים ומנפישים סצנה נוספת ${k + 1} מתוך ${fresh.size}...", "Drawing and animating extra scene ${k + 1} of ${fresh.size}..."))
                        sceneStart = System.currentTimeMillis()
                        try {
                            val f = animateFrame(sc.bmp, if (k == 0) 10 else 5)
                            if (f != null) added.add(Triple(sc.timeMs, f, durationMs(f)))
                        } catch (e: FatalEdgeError) { throw IllegalStateException(e.message) } catch (_: Exception) { }
                    }
                    if (added.isEmpty()) {
                        refundQuietly()
                        extendError = L.t("ההארכה לא הצליחה הפעם. הקרדיט הוחזר.", "The extension didn't work this time. Your credit was returned.")
                        update(State.DONE, ""); return@execute
                    }
                    synchronized(pieces) { pieces.addAll(added) }
                    val oldId = savedId
                    update(State.FILMING, L.t("מרכיבים את הסרט הארוך...", "Putting the longer film together..."))
                    compose(replaceId = oldId)
                } catch (e: Exception) {
                    if (cancelled.get()) return@execute
                    refundQuietly()
                    extendError = friendly(e, L.t("ההארכה לא הצליחה", "The extension didn't work"))
                    update(State.DONE, "")
                }
            }
        }

        /** Deletes this film's working files (a new film replaces it). */
        internal fun discard() {
            synchronized(pieces) { pieces.forEach { it.second.delete() }; pieces.clear() }
        }

        private fun animateScene(i: Int): File? {
            // The approved drawing gets the long 10-second animation; other scenes 5 seconds (photos: 5).
            val seconds = if (i == anchorIdx && memory.isVideo) 10 else 5
            if (i == anchorIdx) return animateDrawn(anchorUrl, seconds)
            return animateFrame(scenes[i].bmp, seconds)
        }

        /** Draws [frame] like the approved characters, then animates it. */
        private fun animateFrame(frame: Bitmap, seconds: Int): File? {
            val raw = submitAndWait(mapOf("action" to "submit", "kind" to "style_ref", "image" to dataUri(frame), "ref" to anchorUrl, "aspect" to aspect))
            val drawn = if (raw.isEmpty() || cancelled.get()) raw else polish(raw)
            if (cancelled.get()) return null
            if (drawn.isEmpty()) throw IllegalStateException(L.t("ציור הסצנה נכשל", "Drawing the scene failed") + lastJobDetail.let { if (it.isBlank()) "" else ": $it" })
            return animateDrawn(drawn, seconds)
        }

        private fun animateDrawn(drawn: String, seconds: Int): File? {
            if (cancelled.get()) return null
            val video = submitAndWait(mapOf("action" to "submit", "kind" to "anim", "duration" to seconds.toString(), "image" to drawn, "motion" to actionHint(memory)))
            if (cancelled.get()) return null
            if (video.isEmpty()) throw IllegalStateException(L.t("הנפשת הסצנה נכשלה", "Animating the scene failed") + lastJobDetail.let { if (it.isBlank()) "" else ": $it" })
            return downloadVideo(ctx, video)
        }

        // ---------- 3. putting it together ----------

        /**
         * A failed export: a first film fails as usual; a failed extension keeps the film the family already
         * has on screen, takes the extra scenes back out, and returns the extra credit.
         */
        private fun exportFailed(extending: Boolean, message: String) {
            if (!extending) { fail(message); return }
            refundQuietly()
            extendError = L.t("לא הצלחנו להרכיב את הסרט הארוך. הקרדיט הנוסף הוחזר.", "Couldn't put the longer film together. The extra credit was returned.")
            update(State.DONE, "")
        }

        private fun compose(replaceId: String? = null) {
            val extending = replaceId != null || state == State.FILMING && result != null
            val parts = synchronized(pieces) { pieces.sortedBy { it.first }.toList() }
            val mood = lastMood
            val withVoices = lastVoices
            val (outW, outH) = outSize(aspect)
            val withMark = Settings.prefs(ctx).getBoolean("share_watermark", true)
            val markBitmap = if (withMark) SocialShare.markLayer(ctx, outW, outH) else null
            fun effects(): Effects {
                val list = ArrayList<Effect>()
                list.add(Presentation.createForWidthAndHeight(outW, outH, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP))
                if (markBitmap != null) list.add(OverlayEffect(ImmutableList.of(BitmapOverlay.createStaticBitmapOverlay(markBitmap, OverlaySettings.Builder().build()))))
                return Effects(emptyList<AudioProcessor>(), list)
            }
            // Only the sound file is temporary: the animated pieces stay, so the film can be extended.
            val temp = ArrayList<File>()
            val videoItems = ArrayList<EditedMediaItem>()
            var totalMs = 0L
            for ((_, f, len) in parts) {
                totalMs += len
                videoItems.add(EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(f))).setRemoveAudio(true).setEffects(effects()).build())
            }

            // The closing card, like the shared clips (only when the Moments mark is on).
            if (withMark) try {
                videoItems.add(SocialShare.endCardItem(SocialShare.endCard(ctx, outW, outH).also { temp.add(it) }, outW to outH))
                totalMs += SocialShare.END_CARD_MS
            } catch (_: Exception) { }
            // One ready sound track: each scene with the moment's own sound from its spot in the clip, music softly under it.
            val voices: FloatArray? = if (withVoices && memory.isVideo) try {
                AudioMix.voices(ctx, memory, parts.map { AudioMix.Part(it.first, it.third) })
            } catch (_: Exception) { null } else null
            val track: File? = if (voices != null || mood != null) try {
                File(ctx.cacheDir, "toon_sound_${System.currentTimeMillis()}.wav").also {
                    temp.add(it); AudioMix.write(mood, voices, totalMs, if (voices != null) 0.18f else 0.8f, it)
                }
            } catch (_: Exception) { null } else null

            val dir = File(ctx.cacheDir, "share").apply { mkdirs() }
            val out = File(dir, "moments_film_${System.currentTimeMillis()}.mp4")

            fun export(withSound: Boolean) {
                val sequences = ArrayList<EditedMediaItemSequence>()
                sequences.add(EditedMediaItemSequence(videoItems))
                if (withSound && track != null) sequences.add(EditedMediaItemSequence(EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(track))).setRemoveVideo(true).build()))
                val finished = AtomicBoolean(false)
                val t = Transformer.Builder(ctx)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            finished.set(true)
                            worker.execute {
                                temp.forEach { it.delete() }
                                if (!withSound && track != null) note = (note + " " + L.t("(הסרט יצא בלי קול: לא הצלחנו לחבר אותו.)", "(The film came out without sound: we couldn't add it.)")).trim()
                                // Keep it for good: the gallery (Movies/Moments) and the archive, next to the moment.
                                val newId = try { keep(ctx, memory, out) } catch (_: Exception) { null }
                                // An extended film replaces the shorter one (it goes to the trash, restorable).
                                if (newId != null && replaceId != null) try { MemoryStore.moveToTrash(ctx, setOf(replaceId), learn = false) } catch (_: Exception) { }
                                savedId = newId ?: savedId
                                reserved = false // the credit for this film (or its extension) is used
                                result?.takeIf { it != out }?.delete()
                                result = out
                                progress = 100
                                update(State.DONE, "")
                                // Stays as `current` so the screen can show the result again; a new film replaces it.
                            }
                        }
                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                            finished.set(true)
                            out.delete()
                            if (cancelled.get()) return
                            // Last resort: the film without sound, rather than no film.
                            if (withSound && track != null) { export(false); return }
                            temp.forEach { it.delete() }
                            worker.execute { exportFailed(extending, L.t("לא הצלחנו להרכיב את הסרט. נסו שוב.", "Couldn't put the film together. Try again.")) }
                        }
                    })
                    .build()
                transformer = t
                t.start(Composition.Builder(sequences).build(), out.absolutePath)
                val holder = ProgressHolder()
                val poll = object : Runnable {
                    override fun run() {
                        if (cancelled.get() || finished.get()) return
                        val st = try { t.getProgress(holder) } catch (_: Throwable) { Transformer.PROGRESS_STATE_NOT_STARTED }
                        if (st == Transformer.PROGRESS_STATE_AVAILABLE) { progress = holder.progress; listener?.invoke() }
                        main.postDelayed(this, 500)
                    }
                }
                main.postDelayed(poll, 500)
            }
            main.post {
                if (cancelled.get()) { temp.forEach { it.delete() }; return@post }
                try { export(track != null) } catch (_: Throwable) {
                    try { export(false) } catch (_: Throwable) {
                        temp.forEach { it.delete() }
                        worker.execute { exportFailed(extending, L.t("לא הצלחנו להרכיב את הסרט", "Couldn't put the film together")) }
                    }
                }
            }
        }

        // ---------- server ----------

        /**
         * The 3D finishing pass over an already drawn picture. Never fails the film: if it can't run,
         * the drawing is used as it is.
         */
        private fun polish(url: String): String {
            if (calls >= CALL_BUDGET) return url
            return try {
                calls++
                val res = callEdge(edgeUrl, token, mapOf("action" to "submit", "kind" to "polish", "image" to url, "aspect" to aspect))
                if (res.optBoolean("skip", false) || res.optString("status_url", "").isEmpty()) url
                else pollEdge(edgeUrl, token, res.optString("status_url", ""), res.optString("response_url", ""), cancelled).ifEmpty { url }
            } catch (_: Exception) { url }
        }

        /** Sends one AI job and waits for it. Returns the result URL, or "" if this job failed. */
        private fun submitAndWait(body: Map<String, Any>): String {
            lastJobDetail = ""
            if (calls >= CALL_BUDGET) return ""
            calls++
            val res = callEdge(edgeUrl, token, body)
            checkError(res)
            return pollEdge(edgeUrl, token, res.optString("status_url", ""), res.optString("response_url", ""), cancelled)
        }

        private fun refundQuietly() {
            if (!reserved) return
            reserved = false
            try { callEdge(edgeUrl, token, mapOf("action" to "refund")) } catch (_: Exception) {}
        }
    }

    /** A message a parent understands, instead of a technical one. */
    internal fun friendly(e: Exception, fallback: String): String = when (e) {
        is Net.Offline -> e.message ?: fallback
        // SocketException covers "connection abort" / "connection reset" (a phone switching networks or a weak signal).
        is java.net.UnknownHostException, is java.net.SocketException, is java.io.InterruptedIOException, is java.io.EOFException, is javax.net.ssl.SSLException ->
            L.t("החיבור לשרת נפל גם אחרי כמה ניסיונות. בדקו את החיבור (ב-Wi-Fi של מקום עבודה נסו נתונים סלולריים) ונסו שוב.",
                "The connection to our server dropped, even after a few tries. Check your connection (on a work Wi-Fi, try mobile data) and try again.") +
                " (${e.javaClass.simpleName})"
        else -> e.message?.takeIf { it.isNotBlank() } ?: fallback
    }

    /** English hint for the animation, from what we know about the moment (its category / the sound that saved it). */
    private fun actionHint(m: Memory): String {
        val byCategory = when (m.category) {
            "צחוק" -> "The child is laughing happily."
            "משחק" -> "The child is playing."
            "אוכל" -> "The child is eating."
            "בחוץ" -> "The child is playing outside."
            "אמבטיה" -> "The child is in the bath, splashing gently."
            "שינה" -> "The child is sleeping peacefully, breathing slowly."
            "משפחה" -> "A tender family moment together."
            else -> ""
        }
        if (byCategory.isNotEmpty()) return byCategory
        val l = m.label.lowercase()
        return if ("laugh" in l || "giggle" in l || "chuckle" in l) "The child is laughing happily." else ""
    }

    private fun aspectOf(b: Bitmap): String {
        val r = b.height.toFloat() / b.width
        return when {
            r > 1.5f -> "9:16"
            r > 1.1f -> "3:4"
            r < 0.67f -> "16:9"
            r < 0.9f -> "4:3"
            else -> "1:1"
        }
    }

    private fun outSize(aspect: String): Pair<Int, Int> = when (aspect) {
        "9:16" -> 720 to 1280
        "3:4" -> 720 to 960
        "16:9" -> 1280 to 720
        "4:3" -> 960 to 720
        else -> 720 to 720
    }

    /**
     * Saves a finished film to the gallery and adds it to the archive as its own moment, right next to
     * the moment it was made from (same day, same people). Returns the new memory's id.
     */
    fun keep(ctx: Context, from: Memory, film: File): String {
        val uri = ReelMaker.publish(ctx, film, "Moments_film_${System.currentTimeMillis()}.mp4")
        val id = "film_${System.currentTimeMillis()}"
        val r = MediaMetadataRetriever()
        var dur = SCENE_MS
        var thumb = ""
        try {
            r.setDataSource(film.absolutePath)
            dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: dur
            r.getFrameAtTime(1_500_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { thumb = MemoryStore.saveBubble(ctx, id, it) }
        } catch (_: Exception) {
        } finally {
            try { r.release() } catch (_: Exception) {}
        }
        val base = from.title.ifBlank { Fmt.title(from) }
        MemoryStore.add(ctx, Memory(
            id = id, uri = uri, name = film.name, time = from.time + 1, label = "manual",
            lat = from.lat, lon = from.lon, place = from.place, thumb = thumb, durationMs = dur,
            title = L.t("🎬 סרט מצויר: $base", "🎬 Animated film: $base").take(60), titleByUser = true,
            description = L.t("סרט אנימציה בתלת-ממד שנוצר מהרגע \"$base\".", "A 3D animated film made from the moment \"$base\"."),
            descriptionByUser = true, descriptionSource = "user", analyzed = true,
            category = from.category, chosen = true, noLearn = true,
            extraPeople = from.allPeople, facesScanned = true, petsScanned = true,
            origin = "film", timeSource = from.timeSource,
        ))
        return id
    }

    /**
     * Chooses the frames worth drawing (each one costs money): frames where a person is clearly in the
     * picture, at a normal distance. Frames with nobody in them (grass, the parent's legs) are skipped,
     * and extreme close-ups (a wide-angle lens right at the face) score low, since they draw a stretched,
     * creepy face. The film keeps the moment's order: one scene per part of the clip, the best frame in each.
     */
    private fun pickScenes(ctx: Context, m: Memory, count: Int): List<Scene> {
        if (!m.isVideo) return ComicMaker.framesFromClip(ctx, m, 1).map { Scene(it, 0L, 3f) }
        val candidates = ArrayList<Pair<Long, Bitmap>>()
        val mmr = MediaMetadataRetriever()
        try {
            if (m.uri.startsWith("content://")) mmr.setDataSource(ctx, Uri.parse(m.uri)) else mmr.setDataSource(m.uri)
            val dur = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: maxOf(m.durationMs, 1L)
            val n = (count * 4).coerceIn(6, 16)
            for (k in 0 until n) {
                val t = (dur * (0.06 + 0.88 * k / (n - 1))).toLong()
                val f = try { mmr.getFrameAtTime(t * 1000L, MediaMetadataRetriever.OPTION_CLOSEST) } catch (_: Exception) { null } ?: continue
                candidates.add(t to shrink(f, 960))
            }
        } catch (_: Exception) {
        } finally {
            try { mmr.release() } catch (_: Exception) {}
        }
        if (candidates.isEmpty()) return ComicMaker.framesFromClip(ctx, m, count).map { Scene(it, 0L, 1f) }

        val detector = com.google.mlkit.vision.face.FaceDetection.getClient(
            com.google.mlkit.vision.face.FaceDetectorOptions.Builder()
                .setPerformanceMode(com.google.mlkit.vision.face.FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setMinFaceSize(0.05f)
                .build()
        )
        // Score: 0 = nobody there; 3 = a face at a good distance; lower for tiny faces and extreme close-ups.
        val scored = try {
            candidates.map { (t, bmp) ->
                val faces = try {
                    com.google.android.gms.tasks.Tasks.await(detector.process(com.google.mlkit.vision.common.InputImage.fromBitmap(bmp, 0)))
                } catch (_: Exception) { emptyList<com.google.mlkit.vision.face.Face>() }
                val biggest = faces.maxOfOrNull { it.boundingBox.width().toFloat() * it.boundingBox.height() } ?: 0f
                val share = biggest / (bmp.width.toFloat() * bmp.height)
                val score = when {
                    biggest <= 0f -> 0f
                    share < 0.015f -> 1f
                    share <= 0.18f -> 3f
                    share <= 0.30f -> 2f
                    else -> 0.5f
                }
                Scene(bmp, t, score)
            }
        } finally {
            try { detector.close() } catch (_: Exception) {}
        }
        val withPeople = scored.filter { it.score > 0f }
        if (withPeople.isEmpty()) {
            // No faces found at all (a pet, a back to the camera...): evenly spaced frames.
            return scored.chunked(maxOf(1, scored.size / count)).take(count).map { it[it.size / 2] }
        }
        val buckets = withPeople.chunked(maxOf(1, (withPeople.size + count - 1) / count))
        val best = buckets.mapNotNull { b -> b.maxByOrNull { it.score } }
        val good = best.filter { it.score >= 1f }
        return good.ifEmpty { best }.sortedBy { it.timeMs }.take(count)
    }

    /** The biggest face in a photo with some room around it (hair, chin); the whole photo if no face is found. */
    internal fun faceCrop(b: Bitmap): Bitmap {
        val detector = com.google.mlkit.vision.face.FaceDetection.getClient(
            com.google.mlkit.vision.face.FaceDetectorOptions.Builder()
                .setPerformanceMode(com.google.mlkit.vision.face.FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE).build()
        )
        return try {
            val faces = com.google.android.gms.tasks.Tasks.await(detector.process(com.google.mlkit.vision.common.InputImage.fromBitmap(b, 0)))
            val f = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() } ?: return b
            val box = f.boundingBox
            val pad = (maxOf(box.width(), box.height()) * 0.45f).toInt()
            val l = (box.left - pad).coerceAtLeast(0)
            val t = (box.top - (pad * 1.3f).toInt()).coerceAtLeast(0)
            val r = (box.right + pad).coerceAtMost(b.width)
            val btm = (box.bottom + pad).coerceAtMost(b.height)
            if (r - l < 40 || btm - t < 40) b else Bitmap.createBitmap(b, l, t, r - l, btm - t)
        } catch (_: Exception) { b } finally { try { detector.close() } catch (_: Exception) {} }
    }

    private fun shrink(b: Bitmap, limit: Int): Bitmap {
        val big = maxOf(b.width, b.height)
        if (big <= limit) return b
        val s = limit.toFloat() / big
        return Bitmap.createScaledBitmap(b, (b.width * s).toInt(), (b.height * s).toInt(), true)
    }

    /** Server errors: 401/402/500 stop the film; anything else fails just this job. */
    internal fun checkError(res: org.json.JSONObject) {
        val code = res.optInt("_code", 200)
        val err = res.optString("error", "")
        if (code in 200..299 && res.optString("status_url", "").isNotEmpty()) return
        val msg = err.ifBlank { L.t("שירות הציור לא זמין כרגע.", "The drawing service isn't available right now.") }
        if (code == 401 || code == 402 || code == 500) throw FatalEdgeError(msg)
        throw IllegalStateException(msg)
    }

    /** A saved JPEG as it is (full quality, not made smaller): for print preparation. */
    internal fun dataUriOfFile(f: java.io.File): String =
        "data:image/jpeg;base64," + android.util.Base64.encodeToString(f.readBytes(), android.util.Base64.NO_WRAP)

    internal fun dataUri(bmp: Bitmap): String {
        // Keep uploads small: the server accepts up to ~4 MB of base64.
        val scaled = shrink(bmp, 1280)
        val stream = java.io.ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, stream)
        val data = android.util.Base64.encodeToString(stream.toByteArray(), android.util.Base64.NO_WRAP)
        return "data:image/jpeg;base64,$data"
    }

    /**
     * Mobile networks drop for a second all the time (a tower handover, a DNS hiccup), so every server call
     * is tried up to 3 times, a few seconds apart, before we tell the family something is wrong.
     */
    private fun <T> withRetry(attempts: Int = 3, block: () -> T): T {
        var last: Exception? = null
        for (i in 0 until attempts) {
            try { return block() } catch (e: java.io.IOException) {
                last = e
                if (i < attempts - 1) Thread.sleep(if (i == 0) 2000L else 5000L)
            }
        }
        throw last ?: java.io.IOException("network")
    }

    /** POSTs to the edge function (with retries). The HTTP status is returned in the JSON as "_code". */
    internal fun callEdge(edgeUrl: String, token: String, body: Map<String, Any>): org.json.JSONObject = withRetry {
        val res = callEdgeOnce(edgeUrl, token, body)
        // The gateway itself was busy (not our function's answer): worth another try.
        val code = res.optInt("_code", 200)
        if ((code == 503 || code == 504) && !res.has("error")) throw java.io.IOException("gateway $code")
        res
    }

    private fun callEdgeOnce(edgeUrl: String, token: String, body: Map<String, Any>): org.json.JSONObject {
        TestMode.guard()
        val conn = java.net.URL(edgeUrl).openConnection() as java.net.HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 30_000
            conn.readTimeout = 90_000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.doOutput = true

            val json = org.json.JSONObject()
            body.forEach { (k, v) -> json.put(k, v) }
            conn.outputStream.use { it.write(json.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: "{}"
            val out = try { if (text.isBlank()) org.json.JSONObject() else org.json.JSONObject(text) } catch (_: Exception) { org.json.JSONObject() }
            out.put("_code", code)
            return out
        } finally {
            conn.disconnect()
        }
    }

    /** Why the last AI job failed, as the service said it (for the "2 of 3 scenes" note while testing). */
    @Volatile internal var lastJobDetail = ""

    internal fun pollEdge(edgeUrl: String, token: String, statusUrl: String, responseUrl: String, cancelled: AtomicBoolean): String {
        if (statusUrl.isEmpty() || responseUrl.isEmpty()) return ""
        repeat(120) { // up to 10 minutes, every 5 seconds
            if (cancelled.get()) return ""
            Thread.sleep(5000)
            if (cancelled.get()) return ""
            val res = try {
                callEdge(edgeUrl, token, mapOf("action" to "poll", "status_url" to statusUrl, "response_url" to responseUrl))
            } catch (_: Exception) { return@repeat } // a network blip: try again next round
            when (res.optString("status", "PENDING")) {
                "DONE" -> return res.optString("url", "")
                "FAILED" -> { lastJobDetail = res.optString("detail", "").take(160); return "" }
            }
        }
        return ""
    }

    private fun durationMs(f: File): Long {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(f.absolutePath)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: SCENE_MS
        } catch (_: Exception) {
            SCENE_MS
        } finally {
            try { r.release() } catch (_: Exception) {}
        }
    }

    internal fun downloadBitmap(url: String): Bitmap? = try {
        withRetry {
            val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 30_000
            conn.readTimeout = 60_000
            conn.inputStream.use { BitmapFactory.decodeStream(it) }
        }
    } catch (_: Exception) { null }

    private fun downloadVideo(ctx: Context, url: String): File? {
        return try {
            val file = File(ctx.cacheDir, "film_${System.nanoTime()}.mp4")
            withRetry {
                val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 30_000
                conn.readTimeout = 90_000
                conn.inputStream.use { input -> file.outputStream().use { output -> input.copyTo(output) } }
            }
            file
        } catch (_: Exception) {
            null
        }
    }
}
