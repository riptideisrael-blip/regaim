package com.regaim.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Books on the phone: one folder per book (book.json, the face crops, the character, every page). */
object BookStore {
    private fun root(ctx: Context) = File(ctx.filesDir, "books").apply { mkdirs() }
    fun dir(ctx: Context, id: String) = File(root(ctx), id).apply { mkdirs() }

    @Synchronized
    fun save(ctx: Context, b: Book) {
        val d = dir(ctx, b.id)
        val tmp = File(d, "book.json.tmp")
        tmp.writeText(BookJson.toJson(b).toString())
        tmp.renameTo(File(d, "book.json"))
    }

    @Synchronized
    fun load(ctx: Context, id: String): Book? = try {
        BookJson.fromJson(JSONObject(File(root(ctx), "$id/book.json").readText()))
    } catch (_: Exception) { null }

    /** Finished books, newest first. */
    fun all(ctx: Context): List<Book> = (root(ctx).listFiles() ?: emptyArray()).mapNotNull { load(ctx, it.name) }.sortedByDescending { it.created }

    /** Read, change and save in one step (pages are drawn two at a time: no change is lost). Null if there's no such book. */
    @Synchronized
    fun update(ctx: Context, id: String, change: (Book) -> Book): Book? {
        val b = load(ctx, id) ?: return null
        return change(b).also { save(ctx, it) }
    }

    fun image(ctx: Context, b: Book, name: String): File = File(dir(ctx, b.id), name)

    fun delete(ctx: Context, id: String) { File(root(ctx), id).deleteRecursively() }

    fun writeJpeg(f: File, bmp: Bitmap) = FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
}

/**
 * Makes a personal book, reusing the cartoon film's server path (credits, fal jobs, polling):
 *  photos (automatic) → story (Claude) → one credit → the child as a book character → the parent approves
 *  → cover and pages, each drawn from the approved character and one real face photo → done.
 * Every result is downloaded at once and fal is asked to delete the request ("forget").
 *
 * Built for phones on cellular data: every server call waits for the connection and tries again (Net); when the
 * connection is gone for minutes the book is paused, not failed, and can be continued from the step it reached
 * (also after the phone closed the app). The credit is tied to the book (its id), so asking again never takes a
 * second one, and a refund that couldn't be sent is kept and sent later (Refunds).
 * One book at a time; its state is saved to disk after every step. A foreground service (BookService) keeps the
 * work going when the parent leaves the app.
 */
object BookMaker {
    const val PAGES = 8
    const val CHARACTER_TRIES = 3
    /** Redraws allowed per other character (father, sister...). */
    const val CAST_TRIES = 2

    enum class State { PHOTOS, NEED_PHOTO, CONFIRM_PHOTOS, WRITING, CAST, CHARACTER, APPROVE, PAGES, ANIMATING, PAUSED, NEED_CREDIT, DONE, FAILED }

    /** What each kind of book costs (shown to the parent) and in credits. Payment isn't connected yet: credits. */
    const val PRICE_DIGITAL = "₪35"
    const val PRICE_ANIMATED = "₪50"
    const val ANIMATION_EXTRA = "₪15"

    /** Photos found for one other character; [picked] is the one the parent chose (-1 = none). */
    class CastChoice(val cast: CastMember, val options: MutableList<Bitmap>) {
        @Volatile var picked: Int = -1
        /** Their character from an earlier book (used by default when there is one). */
        @Volatile var saved: SavedCharacter? = null
        @Volatile var useSaved: Boolean = false
    }

    /** The credit's window (3 hours) is over (or used up): continuing needs one more credit, with the parent's OK. */
    class NeedCredit : Exception()

    @Volatile var current: Session? = null
        private set

    /**
     * [people]: the family members the parent chose for the book (ids from [Cast.family]); null = the story decides.
     * [moments]: real moments the parent chose to build the story on (their written descriptions only are sent).
     */
    fun start(ctx: Context, child: FamilyMember, theme: BookTheme, details: String, people: Set<String>? = null, moments: List<String> = emptyList(), script: String = "", animated: Boolean = false): Session {
        current?.cancel()
        Refunds.flushAsync(ctx.applicationContext)
        return Session(ctx.applicationContext, child.name, child, theme, details, null).also {
            it.people = people
            it.moments = moments
            it.script = script
            it.animated = animated
            current = it
            it.go()
        }
    }

    /** A book that stopped in the middle (no connection, or the phone closed the app) and isn't being worked on now. */
    fun unfinished(ctx: Context): Book? = BookStore.all(ctx).firstOrNull { b ->
        b.state in setOf("cast", "character", "approve", "pages", "animating") && current?.bookId != b.id
    }

    /** A finished book becomes an animated book (one more credit): every page comes to life in one film. */
    fun animate(ctx: Context, b: Book): Session {
        current?.let { if (it.bookId == b.id && it.isActive) return it; it.cancel() }
        val member = Profile.members(ctx).firstOrNull { it.name.trim() == b.child.trim() }
        return Session(ctx.applicationContext, b.child, member, BookThemes.of(b.theme), "", b).also { current = it; it.animateSaved() }
    }

    /** Continues a saved book from the step it reached. */
    fun resume(ctx: Context, b: Book): Session {
        current?.let { if (it.bookId == b.id && it.isActive) return it; it.cancel() }
        val member = Profile.members(ctx).firstOrNull { it.name.trim() == b.child.trim() }
        return Session(ctx.applicationContext, b.child, member, BookThemes.of(b.theme), "", b).also { current = it; it.retry() }
    }

    /** Gives up on a saved unfinished book: returns its credit if the server still allows it. Background thread only. */
    fun abandon(ctx: Context, b: Book) {
        if (b.charged) Refunds.add(ctx, b.id)
        BookStore.update(ctx, b.id) { it.copy(state = "failed", error = L.t("בוטל", "Cancelled")) }
        Refunds.flush(ctx)
    }

    /** Forgets a finished (or failed) session so the screen starts fresh next time. */
    fun clear() { if (current?.isActive != true) current = null }

    class Session(
        private val ctx: Context, val childName: String, private val member: FamilyMember?,
        val theme: BookTheme, private val details: String, saved: Book?,
    ) {
        @Volatile var state = State.PHOTOS; private set
        /** Who the parent chose for the book (null = the story decides), and the real moments to build it on. */
        @Volatile var people: Set<String>? = null
        @Volatile var moments: List<String> = emptyList()
        /** The hero's character from an earlier book, used by default (no new drawing) unless the parent asks for a new one. */
        @Volatile var heroSaved: SavedCharacter? = null
        @Volatile var useHeroSaved: Boolean = true
        /** An animated book: after the pages, every page comes to life in one film (one more credit). */
        @Volatile var animated: Boolean = false
        /** While animating: pages done of how many. */
        @Volatile var clipsDone = 0; private set
        @Volatile var clipsTotal = 0; private set
        /** The parent's own story text (empty = the story is written for them). */
        @Volatile var script: String = ""
        @Volatile var message = ""; private set
        @Volatile var book: Book? = saved; private set
        @Volatile var progress = 0; private set
        /** While pages are drawn: how many pictures are done of how many, and when this round started. */
        @Volatile var pagesDone = 0; private set
        @Volatile var pagesTotal = 0; private set
        @Volatile var pagesSince = 0L; private set
        @Volatile var pagesAtStart = 0; private set
        /** Waiting for the internet to come back (shown on the screen and in the notification). */
        @Volatile var waiting = false; private set
        var listener: (() -> Unit)? = null
        val bookId: String = saved?.id ?: "book_${System.currentTimeMillis()}"
        private val main = Handler(Looper.getMainLooper())
        private val worker = Executors.newSingleThreadExecutor()
        private val cancelled = AtomicBoolean(false)
        private var faces: List<Bitmap> = emptyList()
        /** The other people in the story, with the photos found for each (the parent picks one, or none). */
        val castChoices = java.util.concurrent.CopyOnWriteArrayList<CastChoice>()
        /** Which character the next gallery photo is for (null = the hero). */
        @Volatile var galleryFor: String? = null
        /** The photos found automatically, waiting for the parent to confirm they really are this child. */
        val candidates = java.util.concurrent.CopyOnWriteArrayList<BookPhotos.Chosen>()
        private var sources: List<String> = emptyList()
        private val link = Link(ctx, cancelled) { w -> if (waiting != w) { waiting = w; changed(); BookService.sync(ctx) } }

        val isActive: Boolean get() = state != State.DONE && state != State.FAILED
        val triesLeft: Int get() = CHARACTER_TRIES - (book?.characterTries ?: 0)

        private fun changed() = main.post { listener?.invoke() }
        private fun set(s: State, msg: String = message) {
            state = s; message = msg
            if (s !in setOf(State.PHOTOS, State.WRITING, State.CHARACTER, State.PAGES)) waiting = false
            changed()
            BookService.sync(ctx)
            // Finished: let the worker thread end (the current task still completes).
            if (s == State.DONE || s == State.FAILED) worker.shutdown()
        }

        private fun fail(msg: String) {
            val b = book
            if (b != null) {
                book = BookStore.update(ctx, b.id) { it.copy(state = "failed", error = msg) } ?: b
                if (b.charged) { Refunds.add(ctx, b.id); Refunds.flush(ctx) }
            }
            set(State.FAILED, msg)
        }

        /** No connection for minutes: keep everything as it is; the parent continues when back online. */
        private fun pause(msg: String) = set(State.PAUSED, msg)

        /** Runs one step on the worker; a step that throws ends in pause / need-credit / failure (never a crash). */
        private fun step(fallback: String, block: () -> Unit) = worker.execute {
            try { block() } catch (e: Exception) {
                if (cancelled.get()) return@execute
                when (e) {
                    is Net.Offline, is Net.Stuck -> pause(e.message ?: "")
                    is NeedCredit -> set(State.NEED_CREDIT, L.t(
                        "עברו יותר משלוש שעות מאז שהספר התחיל (או שנוצלו כל הציורים של הקרדיט), אז ההמשך ישתמש בקרדיט נוסף. להמשיך?",
                        "More than three hours have passed since the book started (or the credit's drawings were used up), so continuing uses one more credit. Continue?",
                    ))
                    is CartoonFilm.FatalEdgeError -> fail(e.message ?: fallback)
                    else -> fail(CartoonFilm.friendly(e, fallback))
                }
            }
        }

        fun go() = step(L.t("לא הצלחנו לבחור תמונות", "Couldn't choose photos")) { photos() }

        /** Continues from where the book stopped (after a pause, or a book saved before the app was closed). */
        fun retry() = step(L.t("לא הצלחנו להמשיך את הספר", "Couldn't continue the book")) { continueSaved(again = false) }

        /** The parent agreed to use one more credit. */
        fun continueWithNewCredit() = step(L.t("לא הצלחנו להמשיך את הספר", "Couldn't continue the book")) { continueSaved(again = true) }

        private fun continueSaved(again: Boolean) {
            val b = book
            if (b == null) {
                // Stopped before the story was saved.
                if (faces.isNotEmpty()) write() else photos()
                return
            }
            when (b.state) {
                "cast" -> findCast()
                "character" -> { ensureCredit(again); drawCharacters() }
                "approve" -> set(State.APPROVE, "")
                "animating" -> animateBook()
                else -> { ensureCredit(again); drawPages() }
            }
        }

        private fun photos() {
            val child = member ?: throw IllegalStateException(L.t("הילד/ה לא נמצא/ה בפרופיל המשפחה", "The child isn't in the family profile"))
            set(State.PHOTOS, L.t("בוחרים את התמונות הכי טובות של $childName...", "Choosing the best photos of $childName..."))
            val r = BookPhotos.pick(ctx, child) { p -> progress = p; changed() }
            if (cancelled.get()) return
            // The hero already has a saved character: no need to ask about photos (they only help the likeness on
            // the pages). The best ones are used as they are, and the story is written right away.
            if (CharacterStore.latest(ctx, child.id) != null) {
                val use = r.photos.take(3)
                faces = use.map { it.crop }
                sources = use.map { it.memoryId }.filter { it != "parent" && it != "profile" }
                write()
                return
            }
            if (r.photos.isEmpty()) { set(State.NEED_PHOTO, r.problem ?: ""); return }
            // Face recognition can mix up siblings: the parent confirms the photos before anything is sent or paid.
            candidates.clear()
            candidates.addAll(r.photos)
            set(State.CONFIRM_PHOTOS, "")
        }

        /**
         * The parent says this photo isn't the child: it's remembered (so it isn't offered again), faces like it go
         * too, and the next best photo takes its place.
         */
        fun removeCandidate(c: BookPhotos.Chosen) {
            if (state != State.CONFIRM_PHOTOS) return
            candidates.remove(c)
            val p = c.print
            if (p != null) {
                Thread { Rejects.add(ctx, childName, p) }.start()
                candidates.removeAll { x -> x.print != null && FaceGroups.dist(x.print, p) < FaceGroups.LOOKALIKE * x.anchorDist }
            }
            if (candidates.isEmpty()) set(State.NEED_PHOTO, L.t(
                "לא נשארו תמונות ברורות של $childName. כדי שנמצא תמונות: הוסיפו תמונת פרופיל עדכנית וברורה של $childName בפרופיל המשפחה, ונסו שוב.",
                "No clear photos of $childName are left. To help us find some, add a clear, recent profile photo of $childName in the family profile and try again.",
            )) else changed()
        }

        /** The parent confirmed: the book is drawn from the first three. */
        fun confirmPhotos() {
            if (state != State.CONFIRM_PHOTOS || candidates.isEmpty()) return
            val use = candidates.take(3)
            faces = use.map { it.crop }
            sources = use.map { it.memoryId }.filter { it != "parent" && it != "profile" }
            step(L.t("לא הצלחנו לכתוב את הסיפור", "Couldn't write the story")) { write() }
        }

        /**
         * The parent picked one of the child's moments: the child's face from it joins the photos to confirm (first,
         * since the parent chose it). Nothing is sent or paid yet.
         */
        fun useMoment(id: String) = step(L.t("לא הצלחנו להשתמש ברגע הזה", "Couldn't use that moment")) {
            val from = state
            val m = MemoryStore.get(ctx, id) ?: return@step
            set(State.PHOTOS, L.t("מוציאים את התמונה מהרגע...", "Taking the photo from the moment..."))
            val c = BookPhotos.fromMoment(ctx, m, childName)
            if (c == null) {
                set(if (candidates.isEmpty()) State.NEED_PHOTO else State.CONFIRM_PHOTOS, L.t(
                    "לא מצאנו פנים ברורות ברגע הזה. בחרו רגע אחר, שבו $childName קרוב/ה ובאור טוב.",
                    "No clear face in that moment. Pick another one where $childName is close and in good light.",
                ))
                return@step
            }
            if (from != State.CONFIRM_PHOTOS) candidates.clear()
            candidates.add(0, c)
            set(State.CONFIRM_PHOTOS, "")
        }

        /** The parent's own photo, when we couldn't find a good one. */
        fun usePhoto(uri: Uri) = step(L.t("לא הצלחנו להשתמש בתמונה", "Couldn't use the photo")) {
            val c = BookPhotos.fromParentPhoto(ctx, uri)
            if (c == null) {
                set(State.NEED_PHOTO, L.t("לא מצאנו פנים ברורות בתמונה הזאת. נסו תמונה אחרת, קרובה ובאור טוב.", "No clear face in that photo. Try another one, closer and in good light."))
                return@step
            }
            val forCast = galleryFor
            if (state == State.CAST && forCast != null) {
                galleryFor = null
                castChoices.firstOrNull { it.cast.id == forCast }?.let { ch -> ch.options.add(0, c.crop); ch.picked = 0; ch.useSaved = false }
                set(State.CAST, "")
                return@step
            }
            if (state == State.CONFIRM_PHOTOS) {
                // Added to the photos being confirmed (first: the parent chose it).
                candidates.add(0, c)
                set(State.CONFIRM_PHOTOS, "")
                return@step
            }
            faces = listOf(c.crop)
            sources = emptyList()
            write()
        }

        private fun write() {
            set(State.WRITING, L.t("כותבים את הסיפור...", "Writing the story..."))
            val months = member?.let { Profile.ageMonths(it.birth) }
            val girl = when (member?.relation) { "ילדה" -> true; "ילד" -> false; else -> null }
            // Safe to repeat: a lost answer only means the story is written again.
            val chosen = people
            val family = Cast.family(ctx, childName).filter { chosen == null || it.id in chosen }
            // Real moments the parent chose: only their written description (date, place, who, what), never media.
            val events = moments.mapNotNull { id -> MemoryStore.get(ctx, id)?.takeIf { !it.isPending }?.let { BookJson.event(it.time, it.displayTitle, it.description, it.place, it.allPeople) } }
            val story = link.net {
                Net.token(ctx)
                ClaudeClient.bookStory(ctx, childName, months, girl, theme, details, PAGES, family.map { Triple(it.id, it.name, it.relation) },
                    mustInclude = chosen != null && family.isNotEmpty(), events = events, script = script)
            } ?: throw IllegalStateException(L.t("לא הצלחנו לכתוב את הסיפור. נסו שוב.", "Couldn't write the story. Try again."))
            if (cancelled.get()) return
            val ids = family.map { it.id }.toSet()
            // The family member each character is: by the id the story gave, else by name or role ("נועם" = "נעם", "אמא").
            val named = story.cast.map { c -> c.copy(memberId = if (c.memberId in ids) c.memberId else Cast.match(c.name, family)?.id.orEmpty()) }
            // Everyone the parent chose is in the book (pets too), even if the story left someone out.
            val (cast, coverCast, pageCast) = BookCast.ensure(named, story.coverCast, story.pages.indices.map { story.pageCast.getOrElse(it) { emptyList() } },
                if (chosen == null) emptyList() else family.map { Triple(it.id, it.name, Cast.whoOf(it)) })
            val b = Book(
                bookId, childName, BookJson.ageDesc(months, girl), theme.key, if (L.isEn) "en" else "he", story.title, story.outfit,
                BookPage(story.title, story.coverScene, cast = coverCast),
                story.pages.mapIndexed { k, p -> BookPage(p.first, p.second, cast = pageCast.getOrElse(k) { emptyList() }) },
                state = "cast", photoSources = sources, kind = if (animated) "animated" else "digital",
                cast = cast,
                dedication = story.dedication,
            )
            // The face crops stay in the book's folder on the phone (to continue or fix a page later).
            faces.forEachIndexed { i, f -> BookStore.writeJpeg(BookStore.image(ctx, b, "face_$i.jpg"), f) }
            BookStore.save(ctx, b)
            book = b
            findCast()
        }

        /**
         * For everyone else in the story who is in the family profile: their best photos from the family's moments
         * (by face recognition), so the parent can say "draw Dad like this". Nothing is sent or paid yet.
         */
        private fun findCast() {
            val b = book ?: return
            heroSaved = member?.id?.let { CharacterStore.latest(ctx, it) }
            useHeroSaved = heroSaved != null
            // Nothing to choose: no one else in the story and no saved character of the hero.
            if (b.cast.isEmpty() && heroSaved == null) { startCharacters(); return }
            set(State.CAST, L.t("מחפשים תמונות של הדמויות בסיפור...", "Finding photos of the people in the story..."))
            val family = Cast.family(ctx, childName)
            castChoices.clear()
            b.cast.forEachIndexed { k, c ->
                progress = 100 * k / b.cast.size; changed()
                val m = family.firstOrNull { it.id == c.memberId }
                val options = if (m == null) mutableListOf<Bitmap>() else Cast.photosOf(ctx, m)
                castChoices.add(CastChoice(c, options).also {
                    it.picked = if (options.isEmpty()) -1 else 0
                    it.saved = CharacterStore.latest(ctx, c.memberId)
                    it.useSaved = it.saved != null
                })
            }
            progress = 100
            set(State.CAST, "")
        }

        /** The parent picks photo [index] for [castId] (-1 = draw without a photo): a new drawing, not the saved character. */
        fun pickCast(castId: String, index: Int) {
            castChoices.firstOrNull { it.cast.id == castId }?.let { it.picked = if (index in it.options.indices) index else -1; it.useSaved = false }
            changed()
        }

        /** Use a saved character of [castId] ([saved]: which version), instead of drawing them again. */
        fun useSavedCast(castId: String, saved: SavedCharacter) {
            castChoices.firstOrNull { it.cast.id == castId }?.let { it.saved = saved; it.useSaved = true }
            changed()
        }

        /** The hero: the saved character ([saved], a version) or a new drawing (null). */
        fun useHero(saved: SavedCharacter?) {
            if (saved != null) heroSaved = saved
            useHeroSaved = saved != null
            changed()
        }

        /** The parent confirmed who is drawn from which photo: the credit is taken and the characters are drawn. */
        fun confirmCast() {
            if (state != State.CAST) return
            val b = book ?: return
            val chosen = castChoices.associate { ch -> ch.cast.id to (ch.options.getOrNull(ch.picked) ?: if (ch.useSaved) ch.options.firstOrNull() else null) }
            val reuse = castChoices.filter { it.useSaved }.mapNotNull { ch -> ch.saved?.let { ch.cast.id to it } }.toMap()
            val hero = heroSaved.takeIf { useHeroSaved }
            set(State.CHARACTER, L.t("מציירים את הדמויות...", "Drawing the characters...")) // (a second tap does nothing)
            step(L.t("לא הצלחנו לצייר את הדמויות", "Couldn't draw the characters")) {
                val cast = b.cast.map { c ->
                    val face = chosen[c.id]
                    val withRef = if (face != null) {
                        val f = "ref_${c.id}.jpg"
                        BookStore.writeJpeg(BookStore.image(ctx, b, f), face)
                        c.copy(ref = f)
                    } else c.copy(ref = "")
                    // Their saved character: copied into the book, so it isn't drawn again.
                    val saved = reuse[c.id]
                    if (saved != null) {
                        val f = "sheet_${c.id}_saved.jpg"
                        CharacterStore.file(ctx, saved).copyTo(BookStore.image(ctx, b, f), overwrite = true)
                        withRef.copy(sheet = f)
                    } else withRef
                }
                val heroFile = hero?.let { h ->
                    "character_saved.jpg".also { CharacterStore.file(ctx, h).copyTo(BookStore.image(ctx, b, it), overwrite = true) }
                }
                book = BookStore.update(ctx, b.id) { it.copy(cast = cast, state = "character", character = heroFile ?: it.character) }
                startCharacters()
            }
        }

        private fun startCharacters() {
            val b = book ?: return
            if (b.state != "character") book = BookStore.update(ctx, b.id) { it.copy(state = "character") }
            ensureCredit(again = false)
            drawCharacters()
        }

        /** The hero first (if not drawn yet), then everyone else, each as one character sheet. */
        private fun drawCharacters() {
            val b = book ?: return
            if (b.character.isEmpty()) character(toApprove = false)
            for (c in (book ?: b).cast) {
                if (cancelled.get()) return
                if (c.sheet.isEmpty()) drawCast(c.id)
            }
            book = BookStore.update(ctx, bookId) { it.copy(state = "approve") }
            set(State.APPROVE, "")
        }

        /** One other character's sheet: from their photo when there is one, always in the hero's style. True if drawn. */
        private fun drawCast(id: String): Boolean {
            val b = BookStore.load(ctx, bookId) ?: return false
            val c = b.cast.firstOrNull { it.id == id } ?: return false
            set(State.CHARACTER, L.t("מציירים את ${c.name}...", "Drawing ${c.name}..."))
            val hero = BitmapFactory.decodeFile(BookStore.image(ctx, b, b.character).absolutePath) ?: return false
            val face = c.ref.takeIf { it.isNotEmpty() }?.let { BitmapFactory.decodeFile(BookStore.image(ctx, b, it).absolutePath) }
            val images = listOfNotNull(face, hero).map { CartoonFilm.dataUri(it) }
            val (url, req) = try {
                link.job(mapOf("kind" to "book_cast", "image" to images.first(), "refs" to JSONArray(images.drop(1)),
                    "who" to c.who, "photo" to (face != null), "hero" to b.child))
            } catch (e: NeedCredit) { throw e } catch (e: CartoonFilm.FatalEdgeError) { throw e
            } catch (e: Net.Offline) { throw e } catch (e: Net.Stuck) { throw e } catch (e: CancellationException) { throw e
            } catch (_: Exception) { null to null } // this character stays undrawn: the pages describe them in words
            val bmp = url?.let { link.download(it) }
            req?.let { link.forget(bookId, it) }
            if (bmp == null || cancelled.get()) return false
            val name = "sheet_${c.id}_${c.tries}.jpg"
            BookStore.writeJpeg(BookStore.image(ctx, b, name), bmp)
            book = BookStore.update(ctx, bookId) { fresh -> fresh.copy(cast = fresh.cast.map { if (it.id == id) it.copy(sheet = name, tries = it.tries + 1) else it }) }
            return true
        }

        /** The parent picked another moment to draw [castId] from: their face from it becomes the reference, and they're drawn again. */
        fun redrawCastFromMoment(castId: String, memoryId: String, profileName: String) {
            if (state != State.APPROVE) return
            val c = book?.cast?.firstOrNull { it.id == castId } ?: return
            if (c.tries > CAST_TRIES) return
            step(L.t("לא הצלחנו לצייר את הדמות", "Couldn't draw the character")) {
                set(State.CHARACTER, L.t("מוציאים את התמונה של ${c.name} מהרגע...", "Taking ${c.name}'s photo from the moment..."))
                val m = MemoryStore.get(ctx, memoryId)
                val face = m?.takeIf { !it.isPending }?.let { BookPhotos.fromMoment(ctx, it, profileName) }
                if (face == null) {
                    set(State.APPROVE, L.t("לא מצאנו פנים ברורות של ${c.name} ברגע הזה. נסו רגע אחר.", "No clear face of ${c.name} in that moment. Try another one."))
                    return@step
                }
                val b = book ?: return@step
                val f = "ref_${c.id}_${System.currentTimeMillis()}.jpg"
                BookStore.writeJpeg(BookStore.image(ctx, b, f), face.crop)
                book = BookStore.update(ctx, b.id) { x -> x.copy(cast = x.cast.map { if (it.id == castId) it.copy(ref = f) else it }) }
                ensureCredit(again = false)
                val ok = drawCast(castId)
                set(State.APPROVE, if (ok) "" else L.t("הציור החדש של ${c.name} לא הצליח (${Pages.refusal()}). הציור הקודם נשאר.", "${c.name}'s new drawing didn't work (${Pages.refusal()}). The previous one stays."))
            }
        }

        /** Redraws one other character on the approve screen. */
        fun redrawCast(id: String) {
            if (state != State.APPROVE) return
            val c = book?.cast?.firstOrNull { it.id == id } ?: return
            if (c.tries > CAST_TRIES) return
            step(L.t("לא הצלחנו לצייר את הדמות", "Couldn't draw the character")) {
                ensureCredit(again = false)
                val ok = drawCast(id)
                // A failed redraw used to look like "the same picture again": say so.
                set(State.APPROVE, if (ok) "" else L.t("הציור החדש של ${c.name} לא הצליח (${Pages.refusal()}). הציור הקודם נשאר.", "${c.name}'s new drawing didn't work (${Pages.refusal()}). The previous one stays."))
            }
        }

        /** Makes sure this book holds a credit (taking one only the first time, or with the parent's OK). */
        private fun ensureCredit(again: Boolean) {
            val b = book ?: return
            link.reserve(b.id, again)
            if (!b.charged) book = BookStore.update(ctx, b.id) { it.copy(charged = true) } ?: b.copy(charged = true)
        }

        private fun character(toApprove: Boolean = true) {
            val b = book ?: return
            set(State.CHARACTER, L.t("מציירים את $childName כדמות בספר...", "Drawing $childName as the book's hero..."))
            val faces = (0 until 3).mapNotNull { i -> BookStore.image(ctx, b, "face_$i.jpg").takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.absolutePath) } }
            if (faces.isEmpty()) throw IllegalStateException(L.t("התמונות של הילד/ה לא נמצאו. התחילו ספר חדש.", "The child's photos are missing. Start a new book."))
            val uris = faces.map { CartoonFilm.dataUri(it) }
            val (url, req) = link.job(mapOf("kind" to "book_character", "image" to uris.first(), "refs" to JSONArray(uris.drop(1)),
                "age" to b.ageDesc, "outfit" to b.outfit, "aspect" to "3:4"))
            val bmp = url?.let { link.download(it) }
            req?.let { link.forget(b.id, it) }
            if (bmp == null) throw IllegalStateException(Pages.refusal())
            if (cancelled.get()) return
            val name = "character_${b.characterTries}.jpg"
            BookStore.writeJpeg(BookStore.image(ctx, b, name), bmp)
            book = BookStore.update(ctx, b.id) { it.copy(character = name, characterTries = it.characterTries + 1, state = if (toApprove) "approve" else it.state) }
            if (toApprove) set(State.APPROVE, "")
        }

        fun redrawCharacter() {
            if (state != State.APPROVE || triesLeft <= 0) return
            step(L.t("לא הצלחנו לצייר את הדמות", "Couldn't draw the character")) {
                ensureCredit(again = false)
                try { character() } catch (e: IllegalStateException) {
                    // The new drawing didn't work: the approved-so-far character stays, the book isn't lost.
                    set(State.APPROVE, L.t("הציור החדש של $childName לא הצליח (${e.message}). הציור הקודם נשאר.", "$childName's new drawing didn't work (${e.message}). The previous one stays."))
                }
            }
        }

        /** The parent approved the character: draw the cover and the pages (two at a time). */
        fun approve() {
            val b = book ?: return
            if (state != State.APPROVE) return
            book = BookStore.update(ctx, b.id) { it.copy(state = "pages") } ?: b
            set(State.PAGES, L.t("מציירים את הספר...", "Drawing the book..."))
            step(L.t("לא הצלחנו לצייר את הספר", "Couldn't draw the book")) {
                ensureCredit(again = false)
                saveCharacters()
                drawPages()
            }
        }

        /** The approved characters are kept for the next books (each under their family member). Never fails the book. */
        private fun saveCharacters() {
            try {
                val b = BookStore.load(ctx, bookId) ?: return
                val dir = BookStore.dir(ctx, b.id)
                var failed = 0
                member?.id?.let { id -> if (b.character.isNotEmpty() && !CharacterStore.save(ctx, id, childName, File(dir, b.character), b.id)) failed++ }
                val family = Cast.family(ctx, "")
                for (c in b.cast) {
                    val m = c.memberId.ifEmpty { Cast.match(c.name, family)?.id.orEmpty() }
                    if (m.isNotEmpty() && c.sheet.isNotEmpty() && !CharacterStore.save(ctx, m, c.name, File(dir, c.sheet), b.id)) failed++
                }
                // The book is fine either way; the next book will simply draw the characters again.
                if (failed > 0) android.util.Log.w("Moments", "Book $bookId: $failed character(s) not kept for the next books")
            } catch (e: Exception) { android.util.Log.w("Moments", "Book $bookId: keeping the characters failed: ${e.javaClass.simpleName}") }
        }

        /** Draws every page that isn't drawn yet (all of them the first time; the missing ones after a pause). */
        private fun drawPages() {
            set(State.PAGES, L.t("מציירים את הספר...", "Drawing the book..."))
            val b = BookStore.load(ctx, bookId) ?: return
            val all = b.allPages
            val todo = all.indices.filter { all[it].status != "done" }
            val total = all.size
            val done = AtomicInteger(total - todo.size)
            pagesTotal = total
            pagesDone = done.get()
            pagesAtStart = done.get()
            pagesSince = System.currentTimeMillis()
            progress = 100 * done.get() / total; changed()
            val needCredit = AtomicBoolean(false)
            val stop = AtomicReference<Exception?>(null)
            val tasks = todo.map { i ->
                Callable<Unit> {
                    try {
                        if (!cancelled.get() && !needCredit.get() && stop.get() == null) Pages.draw(ctx, link, bookId, i)
                    } catch (_: NeedCredit) {
                        needCredit.set(true)
                    } catch (e: Net.Offline) {
                        stop.compareAndSet(null, e)
                    } catch (e: Net.Stuck) {
                        stop.compareAndSet(null, e)
                    } catch (e: CartoonFilm.FatalEdgeError) {
                        stop.compareAndSet(null, e)
                    } catch (_: Exception) {
                        // this page is marked failed in the book; it can be redrawn from there
                    } finally {
                        val n = done.incrementAndGet()
                        pagesDone = n
                        progress = 100 * n / total
                        changed()
                    }
                    Unit
                }
            }
            // The cover first, alone: every page is then drawn with it as the reference for how everyone looks.
            val coverTask = if (0 in todo) tasks[todo.indexOf(0)] else null
            coverTask?.call()
            val pool = Executors.newFixedThreadPool(2)
            try { pool.invokeAll(tasks.filter { it !== coverTask }) } finally { pool.shutdown() }
            if (cancelled.get()) return
            if (needCredit.get()) throw NeedCredit()
            stop.get()?.let { throw it }
            val fin = BookStore.load(ctx, bookId) ?: return
            val ok = fin.allPages.count { it.status == "done" }
            if (ok == 0) throw IllegalStateException(L.t("לא הצלחנו לצייר את העמודים", "Couldn't draw the pages"))
            if ((BookStore.load(ctx, bookId)?.kind ?: "digital") == "animated") { animateBook(); return }
            book = BookStore.update(ctx, bookId) { it.copy(state = "done", error = "") }
            set(State.DONE, if (ok < total) L.t("${total - ok} עמודים לא צוירו. אפשר לצייר אותם שוב מתוך הספר.", "${total - ok} pages weren't drawn. You can redraw them from the book.") else "")
        }

        /**
         * Brings every page to life (one more credit, its own reference so a resent request never takes two), then
         * joins the film. A book without the extra credit stays a finished digital book.
         */
        private fun animateBook() {
            book = BookStore.update(ctx, bookId) { it.copy(state = "animating", kind = "animated") }
            set(State.ANIMATING, L.t("מנפישים את הספר...", "Animating the book..."))
            try { link.reserve("${bookId}_anim", again = false) } catch (e: NeedCredit) { throw e } catch (e: Net.Offline) { throw e } catch (e: Exception) {
                book = BookStore.update(ctx, bookId) { it.copy(state = "done") }
                set(State.DONE, L.t("הספר מוכן כספר דיגיטלי. להנפשה צריך עוד קרדיט (${ANIMATION_EXTRA}): ${e.message ?: ""}", "The book is ready as a digital book. Animating it needs one more credit (${ANIMATION_EXTRA}): ${e.message ?: ""}"))
                return
            }
            BookFilm.animate(ctx, link, bookId, { cancelled.get() }) { d, t -> clipsDone = d; clipsTotal = t; progress = if (t > 0) 100 * d / t else 0; changed() }
            if (cancelled.get()) return
            if (clipsDone == 0) {
                // Nothing came to life: the animation credit goes back, the book stays as it is.
                Refunds.add(ctx, "${bookId}_anim"); Refunds.flush(ctx)
                book = BookStore.update(ctx, bookId) { it.copy(state = "done") }
                set(State.DONE, L.t("ההנפשה לא הצליחה הפעם, הקרדיט הוחזר. הספר עצמו מוכן. אפשר לנסות שוב.", "The animation didn't work this time; the credit was returned. The book itself is ready. You can try again."))
                return
            }
            set(State.ANIMATING, L.t("מרכיבים את הסרט...", "Putting the film together..."))
            val film = BookFilm.compose(ctx, bookId)
            book = BookStore.update(ctx, bookId) { it.copy(state = "done", film = film ?: it.film, error = "") }
            set(State.DONE, if (film == null) L.t("לא הצלחנו להרכיב את הסרט. אפשר לנסות שוב מהמסך הזה.", "Couldn't put the film together. You can try again from this screen.") else "")
        }

        internal fun animateSaved() = step(L.t("לא הצלחנו להנפיש את הספר", "Couldn't animate the book")) { animateBook() }

        /** A finished book: animate it now (also for a digital book the parent wants to upgrade). */
        fun animateNow() {
            if (state != State.DONE) return
            step(L.t("לא הצלחנו להנפיש את הספר", "Couldn't animate the book")) { animateBook() }
        }

        /** The parent cancelled: the book is dropped; its credit is returned if the server still allows it. */
        fun cancel() {
            cancelled.set(true)
            if (current === this) current = null
            worker.shutdownNow()
            val b = book
            val app = ctx
            if (state == State.ANIMATING && b != null) {
                // Stopping the animation never drops the finished book.
                Thread { BookStore.update(app, b.id) { it.copy(state = "done") } }.start()
                BookService.sync(ctx)
                return
            }
            Thread {
                if (b != null) {
                    BookStore.update(app, b.id) { it.copy(state = "failed", error = L.t("בוטל", "Cancelled")) }
                    if (b.charged || BookStore.load(app, b.id)?.charged == true) Refunds.add(app, b.id)
                }
                Refunds.flush(app)
            }.start()
            BookService.sync(ctx)
        }
    }

    /**
     * The server calls of a book, each one waiting for the connection and trying again (Net).
     * Repeating is safe: reserve/refund carry the book's reference, polling and downloads only read,
     * forget is idempotent at fal. (A repeated submit may draw one extra picture: a few cents, never a credit.)
     */
    class Link(private val ctx: Context, private val cancelled: AtomicBoolean = AtomicBoolean(false), private val onWait: (Boolean) -> Unit = {}) {
        private val edgeUrl = Circle.url(ctx) + "/functions/v1/cartoon-film"

        fun <T> net(maxWaitMs: Long = Net.WAIT_MS, block: () -> T): T = Net.retry(ctx, cancelled, maxWaitMs, onWait, block)

        fun call(body: Map<String, Any>, maxWaitMs: Long = Net.WAIT_MS): JSONObject = net(maxWaitMs) { CartoonFilm.callEdge(edgeUrl, Net.token(ctx), body) }

        /** Takes the book's credit, or confirms it already has it. Throws [NeedCredit] when the window is over. */
        fun reserve(ref: String, again: Boolean) {
            val res = call(mapOf<String, Any>("action" to "reserve", "ref" to ref) + (if (again) mapOf<String, Any>("again" to true) else emptyMap()))
            if (res.optBoolean("ok", false)) return
            if (res.optBoolean("expired", false)) {
                // The window ran out while (almost) nothing was drawn: that credit comes back first, so continuing costs nothing extra.
                if (!again && refund(ref)) return reserve(ref, true)
                throw NeedCredit()
            }
            if (res.optInt("_code", 200) == 401) throw CartoonFilm.FatalEdgeError(res.optString("error").ifBlank { L.t("צריך להתחבר מחדש", "Please sign in again") })
            throw IllegalStateException(res.optString("error", "").ifBlank { L.t("אין קרדיט פנוי ליצירה.", "No credit available.") })
        }

        /** True only when the server confirmed giving the credit back. */
        fun refund(ref: String): Boolean = try {
            call(mapOf("action" to "refund", "ref" to ref), 60_000).optBoolean("refunded", false)
        } catch (e: CancellationException) { false } catch (_: Exception) { false }

        /** Submits one drawing and waits for it; returns (result url, fal request id). */
        fun job(body: Map<String, Any>): Pair<String?, String?> {
            val res = call(mapOf<String, Any>("action" to "submit") + body)
            if (res.optInt("_code", 200) == 402) throw NeedCredit()
            CartoonFilm.checkError(res)
            val url = poll(res.optString("status_url"), res.optString("response_url"))
            return url.ifEmpty { null } to res.optString("request_id").ifEmpty { null }
        }

        private fun poll(statusUrl: String, responseUrl: String): String {
            if (statusUrl.isEmpty() || responseUrl.isEmpty()) return ""
            CartoonFilm.lastJobDetail = ""
            repeat(120) { // up to 10 minutes of drawing (time without a connection isn't counted)
                if (cancelled.get()) throw CancellationException()
                Thread.sleep(5000)
                val res = call(mapOf("action" to "poll", "status_url" to statusUrl, "response_url" to responseUrl))
                when (res.optString("status", "PENDING")) {
                    "DONE" -> return res.optString("url", "")
                    "FAILED" -> { CartoonFilm.lastJobDetail = res.optString("detail", "").take(160); return "" }
                }
            }
            return ""
        }

        /** The drawing, or null if fal no longer has it (a lost connection is waited out). */
        fun download(url: String): Bitmap? = net {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 30_000
            conn.readTimeout = 60_000
            try {
                if (conn.responseCode !in 200..299) null
                else conn.inputStream.use { BitmapFactory.decodeStream(it) } ?: throw IOException("partial image")
            } finally { conn.disconnect() }
        }

        /** Saves the result straight to [f] (big print pictures aren't decoded in memory). False if fal no longer has it. */
        fun downloadTo(url: String, f: File): Boolean = net {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 30_000
            conn.readTimeout = 120_000
            try {
                if (conn.responseCode !in 200..299) false
                else {
                    val tmp = File(f.parentFile, f.name + ".part")
                    conn.inputStream.use { i -> FileOutputStream(tmp).use { o -> i.copyTo(o) } }
                    if (tmp.length() < 10_000) { tmp.delete(); throw IOException("partial image") }
                    tmp.renameTo(f)
                }
            } finally { conn.disconnect() }
        }

        /** Asks fal to delete the request (inputs, outputs, files); records whether fal confirmed it. */
        fun forget(bookId: String, requestId: String) {
            val ok = try { call(mapOf("action" to "forget", "request_id" to requestId), 60_000).optBoolean("ok", false) } catch (_: Exception) { false }
            BookStore.update(ctx, bookId) { it.copy(forgotten = it.forgotten + (requestId to ok)) }
            PitzkiStore.recordForget(ctx, ok)
        }
    }

    /**
     * Before printing: every approved page is enlarged twice, so it stays sharp at print size (the screen pictures are
     * enough for a phone, not for paper). Cheap, and doesn't use the credit. Pages already prepared are skipped.
     * Background thread only. Returns how many pages are ready for print.
     */
    object Print {
        fun prepare(ctx: Context, bookId: String, progress: (Int, Int) -> Unit = { _, _ -> }): Int {
            val b = BookStore.load(ctx, bookId) ?: throw IllegalStateException("no book")
            val dir = BookStore.dir(ctx, b.id)
            val link = Link(ctx)
            val all = b.allPages
            var ready = 0
            all.forEachIndexed { i, p ->
                progress(i, all.size)
                if (p.image.isEmpty()) return@forEachIndexed
                if (p.print.isNotEmpty() && File(dir, p.print).exists()) { ready++; return@forEachIndexed }
                val src = File(dir, p.image).takeIf { it.exists() } ?: return@forEachIndexed
                val (url, req) = link.job(mapOf("kind" to "book_upscale", "image" to CartoonFilm.dataUriOfFile(src)))
                val name = "print_${i}_${p.tries}.jpg"
                val ok = url != null && link.downloadTo(url, File(dir, name))
                req?.let { link.forget(bookId, it) }
                if (!ok) return@forEachIndexed
                ready++
                BookStore.update(ctx, bookId) { x ->
                    if (i == 0) x.copy(cover = x.cover.copy(print = name))
                    else x.copy(pages = x.pages.mapIndexed { k, q -> if (k == i - 1) q.copy(print = name) else q })
                }
            }
            progress(all.size, all.size)
            return ready
        }
    }

    /** Drawing one page (also used from the finished book, to fix a single page). */
    object Pages {
        /**
         * The face from a character sheet, cropped and enlarged (kept next to the sheet, made once). Null if no face
         * was found. Background thread only.
         */
        private fun closeUp(dir: File, sheetName: String, sheet: Bitmap): Bitmap? {
            val f = File(dir, "close_$sheetName")
            if (f.exists()) return BitmapFactory.decodeFile(f.absolutePath)
            val detector = com.google.mlkit.vision.face.FaceDetection.getClient(
                com.google.mlkit.vision.face.FaceDetectorOptions.Builder().setPerformanceMode(com.google.mlkit.vision.face.FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE).build()
            )
            return try {
                val face = com.google.android.gms.tasks.Tasks.await(detector.process(com.google.mlkit.vision.common.InputImage.fromBitmap(sheet, 0)))
                    .maxByOrNull { it.boundingBox.width() * it.boundingBox.height() } ?: return null
                val box = face.boundingBox
                val pad = (kotlin.math.max(box.width(), box.height()) * 0.6f).toInt()
                val l = (box.left - pad).coerceAtLeast(0); val t = (box.top - pad).coerceAtLeast(0)
                val r = (box.right + pad).coerceAtMost(sheet.width); val btm = (box.bottom + pad / 2).coerceAtMost(sheet.height)
                if (r - l < 64 || btm - t < 64) return null
                val crop = Bitmap.createBitmap(sheet, l, t, r - l, btm - t)
                val scale = 640f / kotlin.math.max(crop.width, crop.height)
                val big = Bitmap.createScaledBitmap(crop, (crop.width * scale).toInt(), (crop.height * scale).toInt(), true)
                BookStore.writeJpeg(f, big)
                big
            } catch (_: Exception) { null } finally { try { detector.close() } catch (_: Exception) { } }
        }

        fun refusal(): String {
            val d = CartoonFilm.lastJobDetail.lowercase()
            return if ("content_policy" in d || "safety" in d || "flagged" in d) L.t("שירות הציור סירב לתמונה הזאת. נסו שוב, או בחרו תמונה אחרת של הילד.", "The drawing service refused this picture. Try again, or pick another photo of the child.")
            else L.t("הציור לא הצליח הפעם. נסו שוב.", "The drawing didn't work this time. Try again.")
        }

        /**
         * Draws page [i] (0 = cover) and saves it into the book as done or failed. A lost connection is waited out
         * (and if it's gone for minutes, [Net.Offline] is thrown and the page is left as it was). Throws [NeedCredit]
         * when the credit's window is over.
         */
        fun draw(ctx: Context, link: Link, bookId: String, i: Int) {
            val b = BookStore.load(ctx, bookId) ?: return
            val page = b.allPages[i]
            val dir = BookStore.dir(ctx, b.id)
            val character = BitmapFactory.decodeFile(File(dir, b.character).absolutePath) ?: return
            // Every person in this picture with their character sheet AND a close-up of the face from it (on a full-body
            // sheet the face is small, so details got lost and faces drifted), each labelled.
            val refs = ArrayList<Pair<Bitmap, String>>()
            val max = 10
            refs.add(character to "[child] ${b.child}, the main character (${b.ageDesc}); in this book wearing ${b.outfit} (even if the sheet shows other clothes)")
            closeUp(dir, b.character, character)?.let { refs.add(it to "[child] close-up of ${b.child}'s face from the sheet: copy this face exactly") }
            val inPicture = (if (i == 0 && page.cast.isEmpty()) b.cast.map { it.id } else page.cast).filter { it != "child" }
            val noSheet = ArrayList<String>()
            for (id in inPicture) {
                val c = b.cast.firstOrNull { it.id == id } ?: continue
                val sheet = c.sheet.takeIf { it.isNotEmpty() }?.let { BitmapFactory.decodeFile(File(dir, it).absolutePath) }
                if (sheet == null || refs.size >= max) { noSheet.add("[${c.id}] ${c.name}: ${c.who}"); continue }
                refs.add(sheet to "[${c.id}] ${c.name}: ${c.who}")
                if (refs.size < max) closeUp(dir, c.sheet, sheet)?.let { refs.add(it to "[${c.id}] close-up of ${c.name}'s face from the sheet: copy this face exactly") }
            }
            // The finished cover: everyone on every page looks as they do there (faces drift less from page to page).
            if (i > 0 && b.cover.image.isNotEmpty() && refs.size < max) BitmapFactory.decodeFile(File(dir, b.cover.image).absolutePath)?.let {
                refs.add(it to "[cover] this book's cover, for consistency of the whole book (the sheets and close-ups decide the faces; not the same scene)")
            }
            // A real photo of the hero, for the true likeness, while there's room.
            if (refs.size < max) File(dir, "face_0.jpg").takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.absolutePath) }?.let {
                refs.add(it to "[child] real photo of ${b.child}: use it only for the true face (the sheet gives the look and outfit)")
            }
            // Books made before the story had a cast: the parent's own face, when they gave it.
            if (b.cast.isEmpty() && b.parent.isNotEmpty()) BitmapFactory.decodeFile(File(dir, "parent_face.jpg").absolutePath)?.let {
                refs.add(it to "[parent] the child's ${b.parent} (a real photo: draw them in the book's style)")
            }
            val scene = if (i == 0) "Book cover illustration: ${page.scene}" else page.scene
            val body = mapOf(
                "kind" to "book_page", "image" to CartoonFilm.dataUri(refs[0].first),
                "refs" to JSONArray(refs.drop(1).map { CartoonFilm.dataUri(it.first) }),
                "labels" to JSONArray(refs.map { it.second }),
                "people" to noSheet.joinToString("; "),
                "cover" to (i == 0),
                "scene" to scene, "outfit" to b.outfit, "aspect" to "3:4",
            )
            val (url, req) = try { link.job(body) } catch (e: NeedCredit) { throw e } catch (e: CartoonFilm.FatalEdgeError) { throw e
            } catch (e: Net.Offline) { throw e } catch (e: Net.Stuck) { throw e } catch (e: CancellationException) { throw e } catch (_: Exception) { null to null }
            val bmp = url?.let { link.download(it) }
            req?.let { link.forget(bookId, it) }
            BookStore.update(ctx, bookId) { fresh ->
                val p = if (i == 0) fresh.cover else fresh.pages[i - 1]
                val updated = if (bmp != null) {
                    val name = if (i == 0) "cover_${p.tries}.jpg" else "page_${i}_${p.tries}.jpg"
                    BookStore.writeJpeg(File(dir, name), bmp)
                    BookReview.drawn(p, name) // the previous picture is kept, to go back to
                } else p.copy(status = if (p.image.isNotEmpty()) "done" else "failed", tries = p.tries + 1)
                if (i == 0) fresh.copy(cover = updated) else fresh.copy(pages = fresh.pages.mapIndexed { k, x -> if (k == i - 1) updated else x })
            }
            if (bmp == null) throw IllegalStateException(refusal())
        }

        /**
         * Fixes one page of a finished book. Inside the credit's 3-hour window it's free; after it, [newCredit]
         * must be true (the parent agreed to use one more credit). Background thread only.
         */
        fun redraw(ctx: Context, bookId: String, i: Int, newCredit: Boolean, wish: String = "") {
            BookStore.load(ctx, bookId) ?: throw IllegalStateException("no book")
            // What the parent asked to change is kept in the page's picture description (later redraws keep it too).
            val w = wish.trim().replace(Regex("[\\r\\n]+"), " ").take(300)
            if (w.isNotEmpty()) BookStore.update(ctx, bookId) { b ->
                fun add(p: BookPage) = p.copy(scene = (p.scene.substringBefore(" Parent's wish: ") + " Parent's wish: " + w).take(1200))
                if (i == 0) b.copy(cover = add(b.cover)) else b.copy(pages = b.pages.mapIndexed { k, p -> if (k == i - 1) add(p) else p })
            }
            val link = Link(ctx)
            // A fresh reference for this fix, fixed before any retry: a resent request can't take a second credit.
            if (newCredit) link.reserve("${bookId}_fix_${System.currentTimeMillis()}", again = false)
            draw(ctx, link, bookId, i)
        }
    }
}

/**
 * Refunds that couldn't reach the server (no connection) are kept here and sent again later
 * (when a book starts, when the book screen opens). The server refunds a book's credit at most once.
 */
object Refunds {
    private fun p(ctx: Context) = ctx.getSharedPreferences("book_refunds", Context.MODE_PRIVATE)

    @Synchronized fun add(ctx: Context, ref: String) {
        val now = p(ctx).getStringSet("refs", emptySet()).orEmpty()
        p(ctx).edit().putStringSet("refs", now + ref).apply()
    }

    @Synchronized private fun remove(ctx: Context, ref: String) {
        val now = p(ctx).getStringSet("refs", emptySet()).orEmpty()
        p(ctx).edit().putStringSet("refs", now - ref).apply()
    }

    /** Background thread only. */
    fun flush(ctx: Context) {
        if (!Account.signedIn(ctx)) return
        val refs = synchronized(this) { p(ctx).getStringSet("refs", emptySet()).orEmpty().toList() }
        if (refs.isEmpty()) return
        val edgeUrl = Circle.url(ctx) + "/functions/v1/cartoon-film"
        for (ref in refs) {
            try {
                val token = Account.token(ctx) ?: return
                val res = CartoonFilm.callEdge(edgeUrl, token, mapOf("action" to "refund", "ref" to ref))
                // The server answered (refunded, or the refund isn't allowed any more): nothing left to send.
                if (res.optInt("_code", 200) in 200..499) remove(ctx, ref)
            } catch (_: Exception) { return } // still offline: next time
        }
    }

    fun flushAsync(ctx: Context) { Thread { flush(ctx) }.start() }
}

/** The family as the story sees it, and the photos of each person (from the family's own moments). */
object Cast {
    /**
     * A pet's photos (face recognition is for people): its profile photo, then the moments where the pet was
     * recognized or tagged, newest first. Up to 3. Background thread only.
     */
    private fun petPhotos(ctx: Context, m: FamilyMember): MutableList<Bitmap> {
        val out = mutableListOf<Bitmap>()
        if (m.photo.isNotBlank()) MediaImages.decode(ctx, m.photo, 1024)?.let { out.add(it) }
        val name = m.name.trim()
        for (mem in MemoryStore.kept(ctx).filter { name in Fmt.names(it) }.sortedByDescending { it.time }) {
            if (out.size >= 3) break
            val b = if (!mem.isVideo) MediaImages.decode(ctx, mem.uri, 1024) else ComicMaker.framesFromClip(ctx, mem, 1).firstOrNull()
            b?.let { out.add(it) }
        }
        return out
    }

    /** Hebrew names are written with or without ו/י ("נועם" / "נעם"): compared without them. */
    fun norm(s: String) = s.trim().replace(Regex("[ויׁׂ\\s'\"״׳-]"), "").lowercase()

    /** The family member a story character is, by name or by role (אבא, אמא, סבתא...), or null. */
    /** Who a family member is, in English, for the illustrator ("the family's dog", "the child's mother"). */
    fun whoOf(m: FamilyMember): String = when (m.petKind) {
        "dog" -> "the family's dog, ${m.name}"
        "cat" -> "the family's cat, ${m.name}"
        "pet" -> "the family's pet, ${m.name}"
        else -> when (m.relation.trim()) {
            "אבא", "בן זוג", "הורה" -> "the child's father, a man"
            "אמא", "בת זוג" -> "the child's mother, a woman"
            "סבא" -> "the child's grandfather"
            "סבתא" -> "the child's grandmother"
            "ילד", "אח" -> "the child's brother"
            "ילדה", "אחות" -> "the child's sister"
            else -> "a member of the child's family"
        }
    }

    fun match(name: String, family: List<FamilyMember>): FamilyMember? {
        val n = norm(name)
        if (n.isEmpty()) return null
        return family.firstOrNull { norm(it.name) == n } ?: family.firstOrNull { norm(it.relation) == n }
    }

    /**
     * Everyone in this family's profile except the hero (people and pets: the dogs, the cat...), plus the parent using
     * the app ("self"). Read from each family's own profile.
     */
    fun family(ctx: Context, hero: String): List<FamilyMember> {
        val members = Profile.members(ctx).filter { it.name.trim() != hero.trim() && it.name.isNotBlank() }
        val selfName = Profile.parentName(ctx).trim()
        val role = Profile.role(ctx)
        val self = if (members.none { it.id == "self" } && (selfName.isNotEmpty() || role.isNotBlank()))
            listOf(FamilyMember("self", selfName.ifEmpty { role }, role.ifBlank { "הורה" }, photo = Profile.photo(ctx))) else emptyList()
        return self + members.map { if (it.id == "self") it.copy(photo = it.photo.ifBlank { Profile.photo(ctx) }) else it }
    }

    /**
     * Up to 3 clear photos of [m]: their best faces in the family's moments (face recognition, same rules as the
     * hero's), else their profile photo. Background thread only.
     */
    fun photosOf(ctx: Context, m: FamilyMember): MutableList<Bitmap> {
        if (m.isPet) return petPhotos(ctx, m)
        val found = try { BookPhotos.pick(ctx, m).photos.take(3).map { it.crop } } catch (_: Exception) { emptyList() }
        if (found.isNotEmpty()) return found.toMutableList()
        return listOfNotNull(try { BookPhotos.faceFromPath(ctx, m.photo) } catch (_: Exception) { null }).toMutableList()
    }
}
