package com.regaim.app

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** A theme for a personal book, with the guidance the story writer follows (gentle, never shaming or scary). */
data class BookTheme(val key: String, val he: String, val en: String, val guidance: String) {
    val title: String get() = L.t(he, en)
}

object BookThemes {
    val ALL = listOf(
        BookTheme("pacifier", "נפרדים מהמוצץ", "Saying goodbye to the pacifier",
            "The child gently and gradually says goodbye to the pacifier (for example giving it to someone who needs it, or a special goodbye ritual). " +
                "Validate that it is hard and that it's okay to miss it. A loving parent helps with comfort alternatives (a hug, a song, a soft toy). " +
                "The child feels proud and grown-up at the end. Never shame the child or call the pacifier bad."),
        BookTheme("diaper", "להתחיל להשתמש בסיר", "Starting to use the potty",
            "The child learns to use the potty/toilet step by step. Accidents are normal and nobody is angry. Celebrate small successes. " +
                "The body is natural; no shame, no pressure, no rewards that feel like bribes. Proud, happy ending."),
        BookTheme("dark", "לא מפחדים מהחושך", "Not afraid of the dark",
            "The child feels afraid in the dark at bedtime. Validate the feeling. Together with a parent the child discovers the room is the same " +
                "room in the dark (a night light, a brave cuddly toy, naming the familiar shapes). No monsters described in detail, nothing scary. " +
                "Calm, safe, sleepy ending."),
        BookTheme("sibling", "אח או אחות חדשים", "A new baby in the family",
            "A new baby joins the family. The child has mixed feelings (excited, a little jealous, missing special time) and that's okay. " +
                "The parents' love is not divided; the child has a special role as a big sibling and still gets one-on-one time. Warm ending."),
        BookTheme("confidence", "אני יכול/ה!", "I can do it!",
            "The child tries something new that feels hard (climbing, a new place, trying again after failing). Effort matters more than winning. " +
                "Small brave steps, a supportive parent, pride in trying. Encouraging ending."),
        BookTheme("sleep", "נרדמים לבד", "Falling asleep on my own",
            "The child learns to fall asleep in their own bed. A calming bedtime routine (bath, story, cuddle, a favorite toy), the parent is near, " +
                "the child learns to calm their body. Very soothing rhythm, sleepy and safe ending."),
    )

    fun of(key: String): BookTheme = ALL.firstOrNull { it.key == key } ?: ALL.first()

    /** Any subject the parent writes ("a trip to grandma's", "Uri's first day in kindergarten"). */
    fun custom(idea: String): BookTheme {
        val t = idea.trim().replace(Regex("\\s+"), " ").take(200)
        return BookTheme("custom", t, t, "The parent's own idea for this book: \"$t\". Follow it faithfully; keep it warm, gentle and right for the child's age.")
    }

    /** The parent wrote the story; the book is drawn from their words. */
    val SCRIPT = BookTheme("script", "הסיפור שלי", "My own story", "The parent wrote the story themselves.")
}

data class BookPage(
    val text: String,
    /** English description of the picture (sent to the illustrator). */
    val scene: String,
    /** File name inside the book's folder, empty until drawn. */
    val image: String = "",
    /** "todo", "done" or "failed". */
    val status: String = "todo",
    val tries: Int = 0,
    /** Who is in the picture: cast ids ("child" is the hero). Empty = just the hero. */
    val cast: List<String> = emptyList(),
    /** The picture before the last redraw (file name), to go back to it; empty if none. */
    val prev: String = "",
    /** The parent checked this page before printing (text and picture). Any change clears it. */
    val ok: Boolean = false,
    /** The print-quality (upscaled) picture file, empty until the book is prepared for print. */
    val print: String = "",
    /** The parent reading this page aloud (audio file in the book's folder), empty if not recorded. */
    val voice: String = "",
    /** The page brought to life (an animated book): a short video in the book's folder, empty until made. */
    val clip: String = "",
)

/**
 * A person in the story besides the hero (or the hero itself, id "child"). Each gets a character sheet (one drawing
 * every page copies), drawn from a real photo when the parent gave one ([ref]), else invented in the book's style.
 */
data class CastMember(
    val id: String,
    /** As the story calls them ("אבא", "נעם"). */
    val name: String,
    /** For the illustrator, in English ("the child's father, a man in his thirties"). */
    val who: String,
    /** The family profile member this is (to find their photos), or empty. */
    val memberId: String = "",
    /** Real face photo file in the book folder, empty = drawn without one. */
    val ref: String = "",
    /** The character sheet file, empty until drawn. */
    val sheet: String = "",
    val tries: Int = 0,
)

data class Book(
    val id: String,
    val child: String,
    /** English age description for the illustrator ("3-year-old boy"). */
    val ageDesc: String,
    val theme: String,
    val lang: String,
    val title: String,
    val outfit: String,
    val cover: BookPage,
    val pages: List<BookPage>,
    /** The approved character drawing (file name), empty until drawn. */
    val character: String = "",
    val characterTries: Int = 0,
    /** "photos", "writing", "character", "approve", "pages", "done", "failed". */
    val state: String = "writing",
    val created: Long = System.currentTimeMillis(),
    /** Memories the face photos came from (never uploaded except as the face crops themselves). */
    val photoSources: List<String> = emptyList(),
    /** Every fal request id, and whether fal confirmed deleting it. */
    val forgotten: Map<String, Boolean> = emptyMap(),
    val error: String = "",
    /** One credit was taken for this book (its server reference is the book id). */
    val charged: Boolean = false,
    /** The adult drawn from a real photo (parent_face.jpg), in English ("father"), or empty. */
    val parent: String = "",
    /** The other people in the story (not the hero). */
    val cast: List<CastMember> = emptyList(),
    /** "To Uri, with love from Dad" - the first page. */
    val dedication: String = "",
    /** The parent checked the dedication page before printing. */
    val dedicationOk: Boolean = false,
    /** "digital" (pages to turn) or "animated" (also a film where every page comes to life). */
    val kind: String = "digital",
    /** The animated book's film in the gallery (its address), empty until made. */
    val film: String = "",
) {
    val allPages: List<BookPage> get() = listOf(cover) + pages
    val done: Boolean get() = state == "done"
}

/**
 * The parent's check before printing: every page (cover, dedication, story pages) is approved one by one, and any
 * change to a page (its text, a new drawing, going back to the old one) takes its approval away. Pure, unit tested.
 */
object BookReview {
    /** (approved, total) */
    fun count(b: Book): Pair<Int, Int> {
        val pages = b.allPages
        val ded = if (b.dedication.isNotBlank()) 1 else 0
        return (pages.count { it.ok && it.status == "done" } + (if (ded == 1 && b.dedicationOk) 1 else 0)) to (pages.size + ded)
    }

    fun allApproved(b: Book): Boolean = count(b).let { (a, t) -> a == t }

    /** Approves (or un-approves) page [i] (0 = cover, -1 = dedication). */
    fun approve(b: Book, i: Int, ok: Boolean): Book = when {
        i == -1 -> b.copy(dedicationOk = ok)
        i == 0 -> b.copy(cover = b.cover.copy(ok = ok))
        else -> b.copy(pages = b.pages.mapIndexed { k, p -> if (k == i - 1) p.copy(ok = ok) else p })
    }

    /** Back to the picture before the last redraw (the newer one becomes the "previous", so it can be undone too). */
    fun swapBack(p: BookPage): BookPage = if (p.prev.isEmpty()) p else p.copy(image = p.prev, prev = p.image, ok = false, print = "")

    /** A new drawing for the page: the old one is kept to go back to. */
    fun drawn(p: BookPage, file: String): BookPage =
        p.copy(prev = if (p.image.isNotEmpty() && p.image != file) p.image else p.prev, image = file, status = "done", tries = p.tries + 1, ok = false, print = "")
}

/** The book's characters besides the hero. Unit tested. */
object BookCast {
    /** At most this many (each one gets a careful character sheet, and every page is drawn from all of them). */
    const val MAX = 5

    /**
     * Everyone the parent chose is in the book, even if the story writer left someone out (it happened with a dog when
     * four were chosen): a missing one is added as a character, to the cover and to the last pages (the happy ending).
     * [chosen]: (family id, name, who in English). Returns the cast, the cover's cast and each page's cast.
     */
    fun ensure(cast: List<CastMember>, cover: List<String>, pages: List<List<String>>, chosen: List<Triple<String, String, String>>):
        Triple<List<CastMember>, List<String>, List<List<String>>> {
        val missing = chosen.filter { (id, _, _) -> cast.none { it.memberId == id } }
        if (missing.isEmpty()) return Triple(cast, cover, pages)
        val used = cast.map { it.id }.toMutableSet()
        val added = missing.mapIndexedNotNull { k, (id, name, who) ->
            if (cast.size + k >= MAX) return@mapIndexedNotNull null
            var cid = "family${k + 1}"; var n = k + 1
            while (cid in used) { n++; cid = "family$n" }
            used.add(cid)
            CastMember(cid, name.take(40), who.take(200), id)
        }
        val ids = added.map { it.id }
        val last = pages.size - 2 // the last two pages
        return Triple(
            cast + added,
            (cover + ids).distinct(),
            pages.mapIndexed { i, p -> if (i >= last) (p + ids).distinct() else p },
        )
    }
}

/** Pure helpers: story JSON, book JSON, the age line. Unit tested. */
object BookJson {
    class Story(
        val title: String, val outfit: String, val coverScene: String, val pages: List<Pair<String, String>>,
        /** Who appears in each page (same order as [pages]); "child" = the hero. */
        val pageCast: List<List<String>> = emptyList(),
        val cast: List<CastMember> = emptyList(),
        val dedication: String = "",
        val coverCast: List<String> = emptyList(),
    )

    private fun ids(a: JSONArray?): List<String> = if (a == null) emptyList() else (0 until a.length()).map { a.optString(it).trim() }.filter { it.isNotEmpty() }.distinct()

    /** Parses and checks the story writer's answer. Null when it isn't usable (then we ask once more). */
    fun story(text: String, minPages: Int = 5, maxPages: Int = 8): Story? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val o = try { JSONObject(text.substring(start, end + 1)) } catch (_: Exception) { return null }
        val title = o.optString("title").trim()
        val outfit = o.optString("outfit").trim()
        val cover = o.optString("cover_scene").trim()
        val arr = o.optJSONArray("pages") ?: return null
        // The people besides the hero (at most 3, so every one can get a careful character sheet).
        val castArr = o.optJSONArray("cast")
        val cast = if (castArr == null) emptyList() else (0 until castArr.length()).mapNotNull { i ->
            val c = castArr.optJSONObject(i) ?: return@mapNotNull null
            val id = c.optString("id").trim().lowercase().replace(Regex("[^a-z0-9_]"), "")
            val name = c.optString("name").trim()
            if (id.isEmpty() || id == "child" || name.isEmpty()) null
            else CastMember(id, name.take(40), c.optString("who").trim().ifEmpty { name }.take(200), c.optString("family_id").trim().take(60))
        }.distinctBy { it.id }.take(BookCast.MAX)
        val known = cast.map { it.id }.toSet() + "child"
        val parsed = (0 until arr.length()).mapNotNull { i ->
            val p = arr.optJSONObject(i) ?: return@mapNotNull null
            val t = p.optString("text").trim()
            val s = p.optString("scene").trim()
            if (t.isEmpty() || s.isEmpty()) null else Triple(t.take(700), s.take(900), ids(p.optJSONArray("cast")).filter { it in known })
        }
        if (title.isEmpty() || parsed.size < minPages) return null
        val pages = parsed.take(maxPages)
        return Story(
            title.take(80), outfit.ifEmpty { "a simple comfortable everyday outfit" }.take(200), cover.ifEmpty { pages.first().second }.take(900),
            pages.map { it.first to it.second }, pages.map { it.third }, cast,
            o.optString("dedication").trim().take(200), ids(o.optJSONArray("cover_cast")).filter { it in known },
        )
    }

    /** One real moment, as a line for the story writer: "14.03.2026, at the beach (Uri, Dad): building a sand castle ...". */
    fun event(time: Long, title: String, description: String, place: String, people: String): String {
        val date = java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale.US).format(java.util.Date(time))
        val where = place.trim().take(60).let { if (it.isEmpty()) "" else ", $it" }
        val who = people.trim().take(80).let { if (it.isEmpty()) "" else " ($it)" }
        val what = listOf(title.trim(), description.trim()).filter { it.isNotEmpty() }.distinct().joinToString(" - ").replace(Regex("\\s+"), " ").take(320)
        return "$date$where$who: $what"
    }

    /** "3-year-old boy", "toddler girl of about 18 months", "young child" (no birth date). */
    fun ageDesc(months: Int?, girl: Boolean?): String {
        val who = when (girl) { true -> "girl"; false -> "boy"; null -> "child" }
        return when {
            months == null || months < 0 -> "young $who"
            months < 12 -> "baby $who of about $months months"
            months < 24 -> "toddler $who of about $months months"
            else -> "${months / 12}-year-old $who"
        }
    }

    /** How long each page's text may be, by age (shorter for little ones). */
    fun wordsPerPage(months: Int?): Int = when {
        months == null -> 35
        months < 30 -> 22
        months < 48 -> 32
        else -> 50
    }

    fun words(t: String): Int = t.split(Regex("\\s+")).count { w -> w.any { it.isLetter() } }

    /** Too long to read aloud: two pages or more well over the limit. */
    fun tooWordy(pages: List<String>, limit: Int): Boolean = pages.count { words(it) > limit * 1.35 } >= 2

    /** For showing: a closing quote mark never starts a new line (it stays glued to its word). */
    fun display(t: String): String = t.replace(Regex("(\\S)([״\"”])"), "$1\u2060$2")

    fun toJson(b: Book): JSONObject {
        fun page(p: BookPage) = JSONObject().put("text", p.text).put("scene", p.scene).put("image", p.image).put("status", p.status).put("tries", p.tries)
            .put("cast", JSONArray(p.cast)).put("prev", p.prev).put("ok", p.ok).put("print", p.print).put("voice", p.voice).put("clip", p.clip)
        fun member(c: CastMember) = JSONObject().put("id", c.id).put("name", c.name).put("who", c.who).put("memberId", c.memberId)
            .put("ref", c.ref).put("sheet", c.sheet).put("tries", c.tries)
        return JSONObject()
            .put("id", b.id).put("child", b.child).put("age", b.ageDesc).put("theme", b.theme).put("lang", b.lang)
            .put("title", b.title).put("outfit", b.outfit).put("cover", page(b.cover))
            .put("pages", JSONArray().also { a -> b.pages.forEach { a.put(page(it)) } })
            .put("character", b.character).put("characterTries", b.characterTries).put("state", b.state).put("created", b.created)
            .put("photoSources", JSONArray(b.photoSources))
            .put("forgotten", JSONObject().also { o -> b.forgotten.forEach { (k, v) -> o.put(k, v) } })
            .put("error", b.error)
            .put("charged", b.charged)
            .put("parent", b.parent)
            .put("cast", JSONArray().also { a -> b.cast.forEach { a.put(member(it)) } })
            .put("dedication", b.dedication)
            .put("dedicationOk", b.dedicationOk)
            .put("kind", b.kind).put("film", b.film)
    }

    fun fromJson(o: JSONObject): Book {
        fun page(p: JSONObject?) = BookPage(p?.optString("text").orEmpty(), p?.optString("scene").orEmpty(), p?.optString("image").orEmpty(),
            p?.optString("status", "todo") ?: "todo", p?.optInt("tries") ?: 0, ids(p?.optJSONArray("cast")),
            p?.optString("prev").orEmpty(), p?.optBoolean("ok") ?: false, p?.optString("print").orEmpty(), p?.optString("voice").orEmpty(), p?.optString("clip").orEmpty())
        val castArr = o.optJSONArray("cast") ?: JSONArray()
        val cast = (0 until castArr.length()).mapNotNull { castArr.optJSONObject(it) }.map { c ->
            CastMember(c.optString("id"), c.optString("name"), c.optString("who"), c.optString("memberId"), c.optString("ref"), c.optString("sheet"), c.optInt("tries"))
        }
        val pages = o.optJSONArray("pages") ?: JSONArray()
        val srcs = o.optJSONArray("photoSources") ?: JSONArray()
        val forgot = o.optJSONObject("forgotten") ?: JSONObject()
        return Book(
            o.optString("id"), o.optString("child"), o.optString("age"), o.optString("theme"), o.optString("lang", "he"),
            o.optString("title"), o.optString("outfit"), page(o.optJSONObject("cover")),
            (0 until pages.length()).map { page(pages.optJSONObject(it)) },
            o.optString("character"), o.optInt("characterTries"), o.optString("state", "writing"), o.optLong("created"),
            (0 until srcs.length()).map { srcs.optString(it) },
            forgot.keys().asSequence().associateWith { forgot.optBoolean(it) },
            o.optString("error"),
            o.optBoolean("charged"),
            o.optString("parent"),
            cast,
            o.optString("dedication"),
            o.optBoolean("dedicationOk"),
            o.optString("kind", "digital"),
            o.optString("film"),
        )
    }
}

/** Choosing the best face photos of the child automatically. Pure scoring, unit tested; the Android part is in BookPhotos. */
object FaceQuality {
    /** What we measured about one face of the child. */
    data class Stats(
        val key: String,
        /** Same day / same memory = the same "moment" (for variety). */
        val group: String,
        /** Face size in pixels (shorter side of the box). */
        val side: Int,
        /** 0 = blur, 1 = sharp (of the face area). */
        val sharp: Float,
        /** Mean brightness of the face 0..255, and how much is burnt or black (0..1). */
        val mean: Float,
        val clipped: Float,
        /** Head angles in degrees (ML Kit). */
        val yaw: Float,
        val roll: Float,
        /** Both eyes open probability, -1 unknown. */
        val eyes: Float,
        /** Face-print distance to the child (lower = surer). */
        val dist: Float,
        val ageDays: Long,
        /** Another face overlapping this one (a hug, a sibling): confusing for the illustrator. */
        val crowded: Boolean = false,
    )

    private fun smooth(a: Float, b: Float, x: Float): Float {
        val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
        return t * t * (3 - 2 * t)
    }

    fun score(s: Stats): Float {
        val size = smooth(110f, 380f, s.side.toFloat())
        val sharp = s.sharp.coerceIn(0f, 1f)
        val exposure = (1f - abs(s.mean - 140f) / 140f).coerceIn(0f, 1f) * (1f - s.clipped.coerceIn(0f, 1f))
        val pose = (1f - smooth(12f, 40f, abs(s.yaw))) * (1f - smooth(10f, 30f, abs(s.roll)))
        val eyes = if (s.eyes < 0f) 0.7f else smooth(0.3f, 0.8f, s.eyes)
        // Children change fast: the last three months count fully, a year-old photo about half.
        val recency = when {
            s.ageDays <= 90 -> 1f
            s.ageDays <= 365 -> 1f - 0.5f * (s.ageDays - 90) / 275f
            else -> 0.35f
        }
        val sure = 1f - smooth(0.55f, 0.82f, s.dist)
        var q = 0.25f * size + 0.25f * sharp + 0.15f * exposure + 0.2f * pose + 0.15f * eyes
        if (s.crowded) q *= 0.7f
        // A blurry face is useless to the illustrator however big or recent it is.
        q *= 0.55f + 0.45f * smooth(0.15f, 0.5f, sharp)
        return (q * recency * (0.5f + 0.5f * sure)).coerceIn(0f, 1f)
    }

    /** Good enough to draw from. */
    const val GOOD = 0.42f

    /**
     * Up to [n] faces: the best first, then from other days, and at least one looking at the camera when possible.
     * Returns the chosen stats (best first).
     */
    fun pick(all: List<Stats>, n: Int = 3, min: Float = GOOD): List<Stats> {
        val good = all.filter { score(it) >= min }.sortedByDescending { score(it) }
        val out = ArrayList<Stats>()
        for (s in good) {
            if (out.size >= n) break
            if (out.any { it.group == s.group }) continue
            out.add(s)
        }
        // Fill from the same days if there weren't enough different ones.
        for (s in good) { if (out.size >= n) break; if (s !in out) out.add(s) }
        // One frontal face in the set (the illustrator needs it).
        if (out.none { abs(it.yaw) < 15f }) good.firstOrNull { abs(it.yaw) < 15f }?.let { f -> if (out.size >= n) out[out.size - 1] = f else out.add(f) }
        return out.sortedByDescending { score(it) }
    }
}

/**
 * Making sure the book's photos are all the same child. Face recognition passes faces that are close enough to the
 * child, but siblings can pass too. Two checks, pure and unit tested:
 *  - faces that look more like a face the parent already said "this isn't him/her" are dropped;
 *  - when the faces split clearly into two groups (two children), only the group closer to the child's own
 *    reference photos (the profile photo and the faces named as the child) is kept.
 */
object FaceGroups {
    /** [anchorDist]: distance to the child's reference prints (lower = more like the child). */
    class Cand(val key: String, val print: FloatArray, val anchorDist: Float)

    fun dist(a: FloatArray, b: FloatArray): Float = FacePrints.dist(a, b)

    /** A remembered face this close to a face the parent rejected is that rejected child (a sibling), not ours. */
    const val REJECT_SAME = 0.55f
    /** A remembered face farther than this from the child's own profile photo is someone else that slipped in. */
    const val PROFILE_FAR = 0.82f

    /**
     * The child's faces the book can trust. Everyday recognition may have learned a sibling's face by mistake
     * (before the stricter rules, or from one wrong name); for the book:
     *  - faces like ones the parent rejected on the confirm screen are dropped;
     *  - with a profile photo (the parent chose it, so it's surely the child): it leads, and remembered faces far
     *    from it are dropped;
     *  - without one: only the most typical faces are kept (one odd face doesn't pull a sibling in).
     */
    fun trusted(prints: List<FloatArray>, profile: FloatArray?, rejected: List<FloatArray>): List<FloatArray> {
        val ok = prints.filter { p -> FacePrints.valid(p) && rejected.none { r -> dist(p, r) < REJECT_SAME } }
        if (profile != null && FacePrints.valid(profile)) {
            val near = ok.filter { dist(it, profile) < PROFILE_FAR }
            // An old profile photo (a baby picture) may not look like the child today: then it doesn't lead.
            if (ok.isEmpty() || near.size >= maxOf(2, ok.size / 3)) return listOf(profile) + near
        }
        return FacePrints.typical(ok, 15).ifEmpty { ok }
    }

    fun notRejected(c: List<Cand>, rejected: List<FloatArray>): List<Cand> =
        c.filter { x -> rejected.none { r -> r.size == x.print.size && dist(x.print, r) < LOOKALIKE * x.anchorDist } }

    /** A face is dropped with a rejected one only if it's clearly nearer to it than to the child (siblings are close). */
    const val LOOKALIKE = 0.8f

    /** How clearly the faces form two groups (silhouette, -1..1; above ~0.15 = two different faces). */
    fun silhouette(g1: List<Cand>, g2: List<Cand>): Float {
        val all = g1 + g2
        if (g1.isEmpty() || g2.isEmpty()) return 0f
        var sum = 0f
        for (x in all) {
            val own = if (x in g1) g1 else g2
            val other = if (x in g1) g2 else g1
            if (own.size < 2) continue // a single face: no information (counts as 0)
            val a = own.filter { it !== x }.map { dist(x.print, it.print) }.average().toFloat()
            val b = other.map { dist(x.print, it.print) }.average().toFloat()
            sum += (b - a) / max(a, b).coerceAtLeast(1e-6f)
        }
        return sum / all.size
    }

    private fun medoid(g: List<Cand>): Cand = g.minByOrNull { x -> g.sumOf { dist(x.print, it.print).toDouble() } }!!

    fun sameChild(c: List<Cand>, split: Float = 0.15f): List<Cand> {
        if (c.size < 3) return c
        var m1 = c.minByOrNull { it.anchorDist }!!
        var m2 = c.maxByOrNull { dist(it.print, m1.print) }!!
        var g1 = c; var g2 = emptyList<Cand>()
        repeat(6) {
            g1 = c.filter { dist(it.print, m1.print) <= dist(it.print, m2.print) }
            g2 = c.filter { it !in g1 }
            if (g2.isEmpty()) return c
            m1 = medoid(g1); m2 = medoid(g2)
        }
        if (silhouette(g1, g2) < split) return c // one child
        val a1 = g1.map { it.anchorDist }.average()
        val a2 = g2.map { it.anchorDist }.average()
        return if (a1 <= a2) g1 else g2
    }
}


/**
 * Which faces go on the "are these the child?" screen. Strict first; if that leaves fewer than 3 photos, a little
 * looser, step by step (the parent checks the photos before anything is sent, and removes a wrong one with ✕).
 * Better three photos to check than a dead end. Pure, unit tested.
 */
object BookPick {
    /** One face seen in the family's moments. [dist]: to the child; [other]: to the nearest other named person;
     *  [reject]: to the nearest face the parent said isn't the child; [named]: recognition named it as the child;
     *  [tagged]: the moment is tagged with the child and this is the face in it most like the child. */
    class Seen(val st: FaceQuality.Stats, val print: FloatArray, val dist: Float, val other: Float, val reject: Float, val named: Boolean,
               val tagged: Boolean = false)

    const val STRICT = 0.82f
    const val SAME_PICTURE = 0.3f

    private class Level(val margin: Float, val rejectMargin: Float, val min: Float, val far: Float, val trustTags: Boolean = false)
    private val LEVELS = listOf(
        Level(margin = 0.10f, rejectMargin = 0.10f, min = 0.30f, far = STRICT),
        Level(margin = 0.06f, rejectMargin = 0.04f, min = 0.24f, far = STRICT),
        Level(margin = 0.02f, rejectMargin = 0.0f, min = 0.18f, far = STRICT + 0.04f),
        // Last: the family's own tags. A moment tagged with the child, its face most like the child.
        Level(margin = -9f, rejectMargin = 0.0f, min = 0.15f, far = 9f, trustTags = true),
    )

    /** Loosest rule any level uses: faces outside it don't need to be kept at all. */
    fun maybe(s: Seen): Boolean = s.reject > s.dist &&
        ((s.dist < STRICT + 0.04f && FaceQuality.score(s.st) >= 0.18f) || ((s.tagged || s.named) && FaceQuality.score(s.st) >= 0.15f))

    fun choose(all: List<Seen>, rejected: List<FloatArray>, offer: Int): List<Seen> {
        var best: List<Seen> = emptyList()
        for (l in LEVELS) {
            val ok = all.filter { s ->
                (if (l.trustTags) s.tagged || s.named else s.dist < l.far && (s.named || s.other - s.dist >= l.margin)) &&
                    s.reject - s.dist >= l.rejectMargin && FaceQuality.score(s.st) >= l.min
            }
            val byKey = ok.associateBy { it.st.key }
            val cands = ok.map { FaceGroups.Cand(it.st.key, it.print, it.dist) }
            val notRej = FaceGroups.notRejected(cands, rejected)
            // With the family's tags, every face is already one per tagged moment: no sibling split needed.
            val kept = (if (l.trustTags) notRej else FaceGroups.sameChild(notRej)).mapNotNull { byKey[it.key] }
            val ranked = FaceQuality.pick(kept.map { it.st }, offer * 2, l.min).sortedByDescending { FaceQuality.score(it) }
            val unique = ArrayList<Seen>()
            for (st in ranked) {
                val x = byKey[st.key] ?: continue
                if (unique.none { FaceGroups.dist(it.print, x.print) < SAME_PICTURE }) unique.add(x)
                if (unique.size >= offer) break
            }
            if (unique.size >= 3) return unique
            if (unique.size > best.size) best = unique
        }
        return best
    }
}
