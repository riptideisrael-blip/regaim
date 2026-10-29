package com.regaim.app

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.max

/**
 * Talks to the Claude API.
 *  - analyze(): looks at 3 frames of a moment and writes a Hebrew description + tags.
 *  - ask(): answers a question about all moments and points at the matching ones.
 */
object ClaudeClient {
    private const val URL_MESSAGES = "https://api.anthropic.com/v1/messages"
    private const val VISION_MODEL = "claude-sonnet-5-5"
    private const val CHAT_MODEL = "claude-haiku-4-5-20251001"

    /** Admin test: how the cheaper model (Haiku) describes [m]. Nothing is saved. Background thread only. */
    fun describeWithHaiku(ctx: Context, m: Memory): Memory = analyze(ctx, m, CHAT_MODEL, "describe_compare", record = false)

    class ApiException(msg: String) : Exception(msg)

    /** Bump when the description prompt changes, and run the lab's AI honesty test against the fixed clips. */
    const val PROMPT_VERSION = 2

    /** The last raw description reply, before the honesty rules cleaned it (for the lab's AI test). */
    @Volatile var lastRaw: String = ""

    /** A personal key (developers only, encrypted on the phone); families use the AI through the server. */
    fun apiKey(ctx: Context): String = AiKeys.get(ctx, "claude")

    fun setApiKey(ctx: Context, key: String) = AiKeys.set(ctx, "claude", key)

    /** AI is available: through Moments' server (signed in and agreed), or a personal developer key. */
    fun hasKey(ctx: Context) = apiKey(ctx).startsWith("sk-") || AiGate.ready(ctx)

    // ---------- analysis of one moment ----------

    /** Returns the memory with description/tags filled in. Runs on a background thread. */
    /**
     * [model]/[purpose]: the admin's comparison asks another model ("describe_compare", which the server never
     * switches); [record] = false keeps such a test out of the moment's facts.
     */
    fun analyze(ctx: Context, m: Memory, model: String = VISION_MODEL, purpose: String = "describe", record: Boolean = true): Memory {
        val timed = extractTimedFrames(ctx, m, listOf(0.2, 0.5, 0.8))
        val frames = timed.map { it.second }
        if (frames.isEmpty()) throw ApiException(L.t("לא הצלחתי לקרוא תמונות מהקליפ", "Couldn't read images from this clip"))

        val when_ = describeTime(m.time)
        val context = buildString {
            append(if (m.timeSource == "imported") "זמן הוספת הזיכרון (זמן הצילום לא ידוע): $when_. " else "זמן הצילום: $when_. ")
            if (m.place.isNotEmpty()) append("מקום: ${m.place}. ")
            append("מה הפעיל את השמירה: ${Labels.he(m.label)}.")
            if (m.chosen) append(" ההורה בחר לשמור את הרגע הזה בעצמו, כלומר הוא חשוב לו.")
            if (m.allPeople.isNotEmpty()) append(" מופיעים בקליפ (לפי ההורה וזיהוי הפנים): ${m.allPeople}. השתמש בשמות האלה בתיאור.")
            // Only the names approved for this moment are sent (the whole family profile isn't).
            val taste = MemoryStore.taste(ctx)
            if (taste.isNotEmpty()) append("\nמה ידוע על מה שחשוב להורה הזה לשמור:\n$taste")
        }
        val prompt = """
            ${if (m.isVideo) "אלה ${frames.size} תמונות דגימה מתוך סרטון משפחתי." else "זאת תמונה משפחתית שנבחרה על ידי המשתמש."}
            $context
            תאר מה קורה $inLang פשוטה, כדי שאפשר יהיה לחפש את הרגע אחר כך.
            אין לך אודיו או תמליל. סימן צחוק או דיבור הוא רק השערת הגלאי, לא הוכחה למה שנאמר או נשמע.
            אין לך רצף וידאו מלא; אי אפשר להסיק מה קרה בין הדגימות או בסוף הסרטון. אין לקבוע שזה אירוע ראשון בחיים מתוך תמונות בלבד.
            אל תמציא רגשות, קשרים משפחתיים או פעולות שאינן נראות. אם אי אפשר לתאר בביטחון, כתוב שהתמונות אינן מספיקות לתיאור מדויק.
            שמות מותרים רק מתוך רשימת המשתתפים שזוהו או סומנו בזיכרון; פרופיל המשפחה אינו הוכחה לנוכחות. אל תשייך פעולה לשם מסוים כשאין התאמה בין הפנים לשם.
            אבן דרך רק אם המשתמש כבר סימן אותה; אחרת החזר milestone ריק. אם רואים בבירור משהו שנראה כמו אבן דרך (צעדים ראשונים, יום הולדת, ראשון בגן, מפגש משפחתי גדול), כתוב ב-milestone_guess הצעה קצרה של 2-4 מילים, שההורה יאשר או ידחה; זו רק שאלה, ואל תזכיר אותה בתיאור. אחרת milestone_guess ריק. החזר רק JSON בפורמט הזה, בלי שום טקסט נוסף:
            {"title":"כותרת חמה של 3 עד 5 מילים","description":"משפט או שניים","category":"אחת מהרשימה","milestone":"שם אבן הדרך אם רואים אחת בבירור, אחרת ריק","milestone_guess":"הצעה לאבן דרך לאישור ההורה, או ריק","people":"מי מופיע","activity":"הפעילות העיקרית","tags":["תגית","תגית"],"reason":"משפט קצר: מה הופך את הרגע הזה למיוחד להורה (למשל: הילד רץ אליו בהתלהבות, חיבוק, צחוק משותף)","signals":["סימני התלהבות שרואים: חיוך גדול, ידיים באוויר, ריצה לכיוון המצלמה, קפיצה, חיבוק"],"score":"מספר 1 עד 10: כמה הרגע מתאים למה שההורה אוהב לשמור","confidence":"מספר בין 0 ל-1: עד כמה התמונות באמת תומכות בתיאור ובציון (0.3 אם מנחשים)"}
            הקטגוריות האפשריות: ${Categories.ALL.joinToString(", ")}
        """.trimIndent() + L.t("", "\nWrite every text value in ${L.aiLanguage}. The \"category\" value must stay exactly one of the Hebrew categories listed above.")

        val content = JSONArray()
        for (f in frames) {
            content.put(JSONObject().apply {
                put("type", "image")
                put("source", JSONObject().apply {
                    put("type", "base64")
                    put("media_type", "image/jpeg")
                    put("data", f)
                })
            })
        }
        content.put(JSONObject().put("type", "text").put("text", prompt))

        AiGate.lastModel.set("")
        val text = call(ctx, model, null, content, 500, purpose = purpose, images = frames.size)
        lastRaw = text
        val json = extractJson(text)
        // Facts, kept apart from the interpretation: which frames were seen, by which model (the one that really
        // answered: the server may describe with a cheaper one), no transcript.
        val usedModel = AiGate.lastModel.get().orEmpty().ifEmpty { model }
        if (record) try { Facts.frames(ctx, m.id, timed.map { it.first }, usedModel) } catch (_: Exception) { }
        val tags = json.optJSONArray("tags")
        val tagList = if (tags != null) (0 until tags.length()).map { tags.optString(it) } else emptyList()
        val extra = listOf(json.optString("people"), json.optString("activity")).filter { it.isNotBlank() }
        val category = json.optString("category").trim().let { c -> if (c in Categories.ALL) c else "אחר" }
        val description = json.optString("description").trim()
        if (description.isEmpty()) throw ApiException(L.t("התקבלה תשובה ללא תיאור. ננסה שוב מאוחר יותר", "The response had no description. We'll try again later"))
        val signals = json.optJSONArray("signals")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
        val score = json.optString("score").filter { it.isDigit() }.take(2).toIntOrNull()?.coerceIn(1, 10) ?: 0
        val confidence = json.optString("confidence").toFloatOrNull()?.coerceIn(0f, 1f) ?: -1f
        if (record) try { Facts.ai(ctx, m.id, score, confidence) } catch (_: Exception) { }
        // The "never say" list: speech nobody heard, people who weren't recognized, "first time", the video's end.
        val honesty = HonestyRules.Context(
            m.allPeople.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet(), Profile.names(ctx), milestoneMarked = m.milestone.isNotEmpty(),
        )
        fun honest(t: String) = HonestyRules.clean(t, honesty)
        return m.copy(
            reason = honest(json.optString("reason").trim()),
            score = score,
            title = honest(json.optString("title").trim()).take(40),
            description = honest(description).ifEmpty { L.t("התמונות שנבחרו לא מספיקות לתיאור מדויק של הרגע.", "The sampled pictures aren't enough to describe this moment accurately.") },
            descriptionSource = "sampled_images",
            tags = (tagList + extra + signals).filter { it.isNotBlank() && HonestyRules.check(it, honesty).isEmpty() }.joinToString(", "),
            category = if (m.milestone.isNotEmpty()) "אבן דרך" else category.takeIf { it != "אבן דרך" } ?: "אחר",
            milestone = m.milestone,
            // A suggestion for the parent to confirm; never once they marked one or said no ("-").
            milestoneGuess = if (m.milestone.isNotEmpty() || m.milestoneGuess == "-") m.milestoneGuess
                else json.optString("milestone_guess").trim().take(30), // a question, so "first steps?" is fine here
            analyzed = true,
        )
    }

    /** Frames at fractions of the clip, with their time in ms (photos: one "frame" at 0). */
    private fun extractTimedFrames(ctx: Context, m: Memory, fractions: List<Double>): List<Pair<Long, String>> {
        if (!m.isVideo) return MediaImages.decode(ctx, m.uri, 640)?.let { listOf(0L to toJpegBase64(it)) } ?: emptyList()
        val mmr = MediaMetadataRetriever()
        try {
            if (m.uri.startsWith("content://")) mmr.setDataSource(ctx, Uri.parse(m.uri)) else mmr.setDataSource(m.uri)
            val durMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: max(m.durationMs, 1L)
            return fractions.mapNotNull { p ->
                val ms = (durMs * p).toLong()
                mmr.getFrameAtTime(ms * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { ms to toJpegBase64(it) }
            }
        } catch (_: Exception) {
            return emptyList()
        } finally {
            try { mmr.release() } catch (_: Exception) {}
        }
    }

    /**
     * The extended look, only when the family asks for it: 8 frames (with their times) around the detector's mark,
     * and the AI returns the moment's [start, end] with its confidence. Only then is it called "the moment".
     * Returns (start, end, confidence) or null when the pictures don't show a clear moment.
     */
    fun findMoment(ctx: Context, m: Memory): Triple<Long, Long, Float>? {
        if (!m.isVideo || m.durationMs < 2_000L) return null
        val center = m.highlight
        val from = (center - 4_000L).coerceAtLeast(0L)
        val to = (from + 8_000L).coerceAtMost(m.durationMs)
        val times = (0 until 8).map { from + (to - from) * it / 7 }
        val mmr = MediaMetadataRetriever()
        val frames = try {
            if (m.uri.startsWith("content://")) mmr.setDataSource(ctx, Uri.parse(m.uri)) else mmr.setDataSource(m.uri)
            times.mapNotNull { t -> mmr.getFrameAtTime(t * 1000, MediaMetadataRetriever.OPTION_CLOSEST)?.let { t to toJpegBase64(it) } }
        } catch (_: Exception) { emptyList() } finally { try { mmr.release() } catch (_: Exception) {} }
        if (frames.size < 4) return null
        val content = JSONArray()
        for ((t, f) in frames) {
            content.put(JSONObject().put("type", "text").put("text", "t=${t}ms"))
            content.put(JSONObject().put("type", "image").put("source", JSONObject().put("type", "base64").put("media_type", "image/jpeg").put("data", f)))
        }
        content.put(JSONObject().put("type", "text").put("text", """
            These are ${frames.size} frames from a family video, each labelled with its time. The detector marked ${center}ms (${Labels.he(m.label)}).
            Find the span where the emotional moment itself happens (a laugh, a smile, a hug, play), using only what is visible.
            You have no audio. If the frames don't show a clear moment, return confidence below 0.4.
            Return only JSON: {"start_ms": number, "end_ms": number, "confidence": number between 0 and 1}
        """.trimIndent()))
        val json = extractJson(call(ctx, VISION_MODEL, null, content, 200, purpose = "moment", images = frames.size))
        val s = json.optLong("start_ms", -1L)
        val e = json.optLong("end_ms", -1L)
        val c = json.optDouble("confidence", 0.0).toFloat()
        if (s < 0 || e <= s || e > m.durationMs || c < 0.6f) return null
        // A moment is at least a second; at most the 8 seconds that were looked at.
        val start = s.coerceIn(from, to)
        val end = e.coerceIn(start + 1_000L, to.coerceAtLeast(start + 1_000L)).coerceAtMost(m.durationMs)
        return Triple(start, end, c)
    }

    private fun extractFrames(ctx: Context, m: Memory): List<String> {
        if (!m.isVideo) return MediaImages.decode(ctx, m.uri, 640)?.let { listOf(toJpegBase64(it)) } ?: emptyList()
        val mmr = MediaMetadataRetriever()
        try {
            if (m.uri.startsWith("content://")) mmr.setDataSource(ctx, Uri.parse(m.uri)) else mmr.setDataSource(m.uri)
            val durMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                ?: max(m.durationMs, 1L)
            return listOf(0.2, 0.5, 0.8).mapNotNull { p ->
                val bmp = mmr.getFrameAtTime((durMs * p * 1000).toLong(), MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                bmp?.let { toJpegBase64(it) }
            }
        } catch (_: Exception) {
            return emptyList()
        } finally {
            try { mmr.release() } catch (_: Exception) {}
        }
    }

    private fun toJpegBase64(src: Bitmap): String {
        val maxSide = 640
        val scale = maxSide.toFloat() / max(src.width, src.height)
        val bmp = if (scale < 1f) {
            Bitmap.createScaledBitmap(src, (src.width * scale).toInt(), (src.height * scale).toInt(), true)
        } else src
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 70, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    // ---------- story of the day ----------

    fun dayStory(ctx: Context, items: List<Memory>): String {
        val lines = items.joinToString("\n") { m ->
            val t = SimpleDateFormat("HH:mm", Locale.US).format(Date(m.time))
            "- $t ${if (m.timeSource == "imported") "נוסף לאוסף; מועד האירוע אינו ידוע. " else ""}${m.place}: ${m.title}. ${m.description}" + if (m.allPeople.isNotEmpty()) " (משתתפים: ${m.allPeople})" else ""
        }
        val system = """
            את כותבת יומן משפחתי חם $inLang. מתוך רשימת הרגעים של יום אחד, כתבי 2 עד 3 משפטים קצרים
            שמספרים את סיפור היום, בגוף ראשון רבים ("היום..."), בחום ובלי פאתוס.
            היצמדי רק למה שכתוב ברשימה: אל תוסיפי חפצים, רגשות או פעולות שלא מופיעים בה. שמות מותר רק אם מופיעים ברשימה.
            זיכרון שנוסף לאוסף עם מועד אירוע לא ידוע אינו אירוע שהתרחש היום. תארי את עצם ההוספה בלי לשייך את הפעילות לתאריך ההוספה.
            אם יש רק 2 עד 3 רגעים, מספיק משפט אחד או שניים. החזירי רק את הטקסט.
            ${Profile.describe(ctx, items.first().time)}
        """.trimIndent() + L.t("", "\nWrite the story in ${L.aiLanguage}.")
        val messages = JSONArray().put(JSONObject().put("role", "user").put("content", "הרגעים של ${describeTime(items.first().time).substringBeforeLast(" ")}:\n$lines"))
        return callMessages(ctx, CHAT_MODEL, system, messages, 300, purpose = "day_story").trim()
    }

    // ---------- what this parent likes to keep ----------

    class Learned(val text: String, val like: Boolean, val liked: List<Int>, val deleted: List<Int>)

    /**
     * Learns what this parent likes to keep. Each item says which moments it is based on
     * (m1.. = moments kept/loved, d1.. = moments deleted), so the app can show the evidence.
     */
    fun learn(ctx: Context, liked: List<Memory>, deleted: List<String>, mine: List<Insight>, dismissed: List<String>): List<Learned> {
        val likedText = liked.mapIndexed { i, m ->
            val why = listOfNotNull(
                if (m.chosen) "שמר בעצמו" else null,
                if (m.favorite) "סימן כמועדף" else null,
                if (m.milestone.isNotEmpty()) "אבן דרך" else null,
            ).joinToString(", ")
            "m${i + 1} ($why) ${m.title}. ${m.description} ${m.reason}" + if (m.allPeople.isNotEmpty()) " [משתתפים: ${m.allPeople}]" else ""
        }.joinToString("\n")
        val deletedText = deleted.mapIndexed { i, t -> "d${i + 1} $t" }.joinToString("\n")
        val family = Profile.describe(ctx)
        val system = """
            את עוזרת ללמוד מה הורה אוהב לשמור כזיכרונות משפחתיים, כדי לזהות רגעים כאלה בעתיד.
            מתוך הרגעים שהוא שמר או אהב (m1, m2...) ומהרגעים שמחק (d1, d2...), כתבי עד 5 תובנות על מה שחשוב לו
            ועד 3 על מה שפחות מעניין אותו. כל תובנה: משפט קצר (עד 12 מילים), ספציפי, $inLang פשוטה.
            לכל תובנה ציין על אילו רגעים היא מבוססת. כתבי רק תובנה שיש לה לפחות רגע אחד שתומך בה.
            החזירי רק JSON: {"items":[{"text":"...","kind":"like או dislike","based_on":["m1","d2"]}]}
        """.trimIndent() + L.t("", "\nWrite each insight text in ${L.aiLanguage}.")
        val content = buildString {
            if (family.isNotEmpty()) append(family).append("\n\n")
            append("רגעים ששמר או אהב:\n").append(likedText.ifEmpty { "- אין עדיין" })
            append("\n\nרגעים שמחק:\n").append(deletedText.ifEmpty { "- אין עדיין" })
            if (mine.isNotEmpty()) {
                append("\n\nכללים שההורה כתב בעצמו (הם כבר שמורים: אל תחזרי עליהם ואל תסתרי אותם):\n")
                append(mine.joinToString("\n") { (if (it.like) "✓ " else "✗ ") + it.text })
            }
            if (dismissed.isNotEmpty()) {
                append("\n\nתובנות שההורה מחק כי הן לא נכונות (אל תכתבי אותן שוב, וגם לא משהו דומה):\n")
                append(dismissed.takeLast(20).joinToString("\n") { "- $it" })
            }
        }
        val messages = JSONArray().put(JSONObject().put("role", "user").put("content", content))
        val json = extractJson(callMessages(ctx, CHAT_MODEL, system, messages, 900, purpose = "learn"))
        val arr = json.optJSONArray("items") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val text = o.optString("text").trim()
            if (text.isEmpty()) return@mapNotNull null
            val refs = o.optJSONArray("based_on")?.let { a -> (0 until a.length()).map { a.optString(it).trim().lowercase() } } ?: emptyList()
            fun idx(prefix: String) = refs.filter { it.startsWith(prefix) }.mapNotNull { it.drop(1).toIntOrNull()?.minus(1) }.distinct()
            Learned(text, o.optString("kind") != "dislike", idx("m"), idx("d"))
        }
    }

    // ---------- comic ----------

    class ComicScript(val title: String, val panels: List<Pair<String, String>>)

    /** Writes a title, captions and speech bubbles for a comic of these moments. */
    fun comicScript(ctx: Context, moments: List<Memory>, panelCount: Int): ComicScript =
        comicScripts(ctx, moments, panelCount, 0, 0, emptyList(), 1).first()

    /**
     * Writes [versions] clearly different comic scripts in one call.
     * tone = index in ComicStyles.TONES; page > 0 continues a story whose earlier page titles are in [previous].
     */
    fun comicScripts(
        ctx: Context, moments: List<Memory>, panelCount: Int, tone: Int, page: Int,
        previous: List<String>, versions: Int,
    ): List<ComicScript> {
        val lines = moments.mapIndexed { i, m ->
            "${i + 1}. ${m.title}. ${m.description} (${Labels.he(m.label)})" + if (m.allPeople.isNotEmpty()) " [משתתפים: ${m.allPeople}]" else ""
        }.joinToString("\n")
        val single = moments.distinctBy { it.id }.size == 1
        val n = versions.coerceIn(1, 3)
        val toneText = ComicStyles.TONE_PROMPTS.getOrElse(tone) { ComicStyles.TONE_PROMPTS[0] }
        val system = """
            את כותבת קומיקס משפחתי קצר $inLang מתוך רגעים אמיתיים. הסגנון: $toneText.
            ${if (single) "כל הפאנלים הם אותו רגע, לפי הסדר: פתיחה, אמצע ושיא." else "כל פאנל הוא רגע אחר, לפי הסדר."}
            ${if (page > 0) "זה עמוד ${page + 1} של אותו סיפור. העמודים הקודמים נקראו: ${previous.joinToString(" / ")}. המשיכי את הסיפור, אל תחזרי על מה שכבר נאמר." else ""}
            לכל פאנל: caption = משפט מספר קצר (עד 9 מילים), bubble = משפט דיבור קצר (עד 6 מילים) שמתאים למה שקורה.
            אל תמציאי אנשים או חפצים שלא מופיעים בתיאור. שמות מותר רק מתוך "משתתפים".
            ${Profile.describe(ctx, moments.first().time)}
            כתבי $n ${if (n > 1) "גרסאות שונות מאוד זו מזו (כותרת, זווית ובדיחות אחרות)" else "גרסה"}.
            החזירי רק JSON: {"versions":[{"title":"כותרת של 2 עד 5 מילים","panels":[{"caption":"...","bubble":"..."}]}]} עם בדיוק $panelCount פאנלים בכל גרסה.
        """.trimIndent() + L.t("", "\nWrite the titles, captions and bubbles in ${L.aiLanguage}.")
        val messages = JSONArray().put(JSONObject().put("role", "user").put("content", lines))
        val json = extractJson(callMessages(ctx, CHAT_MODEL, system, messages, 600 * n, purpose = "comic"))
        val list = json.optJSONArray("versions") ?: JSONArray().put(json)
        val out = (0 until list.length()).mapNotNull { v ->
            val o = list.optJSONObject(v) ?: return@mapNotNull null
            val arr = o.optJSONArray("panels") ?: JSONArray()
            val panels = (0 until arr.length()).map {
                val p = arr.optJSONObject(it) ?: JSONObject()
                p.optString("caption").trim() to p.optString("bubble").trim()
            }
            ComicScript(o.optString("title").ifBlank { L.t("הסיפור שלנו", "Our story") }, panels)
        }
        if (out.isEmpty()) throw ApiException(L.t("תשובה לא צפויה מה-AI", "Unexpected response from the AI"))
        return out
    }

    // ---------- chat ----------

    class Answer(val text: String, val ids: List<String>)

    /**
     * Answers a question from [memories] — already picked on the phone by [Retrieval] (15–20, not the whole catalog).
     * The answer separates what's known from the record (date, place, names, milestone) from what was only seen
     * in sampled images (descriptions). Ids the AI makes up are dropped; a broken reply becomes "I didn't find it".
     */
    fun ask(ctx: Context, question: String, memories: List<Memory>, history: List<Pair<String, String>>, matched: Boolean = true): Answer {
        val list = JSONArray()
        for (m in memories) {
            list.put(JSONObject().apply {
                put("id", m.id)
                put("when", describeTime(m.time))
                put("time_source", m.timeSource)
                put("media_type", m.mediaType)
                if (m.place.isNotEmpty()) put("place", m.place)
                put("detector", Labels.he(m.label))
                if (m.title.isNotEmpty()) put("title", m.title)
                if (m.category.isNotEmpty()) put("category", m.category)
                if (m.milestone.isNotEmpty()) put("milestone_marked_by_parent", m.milestone)
                if (m.favorite) put("favorite", true)
                if (m.allPeople.isNotEmpty()) put("people", m.allPeople)
                if (m.description.isNotEmpty()) put("seen_in_images", m.description)
                if (m.tags.isNotEmpty()) put("seen_tags", m.tags)
            })
        }
        val system = """
            את "Moments", עוזרת זיכרונות משפחתית חמה. את עונה $inLang, בקצרה ובחום.
            עכשיו: ${describeTime(System.currentTimeMillis())}.
            ${Profile.describe(ctx)}
            "people" ברגע = מי זוהה בזיהוי פנים או נוסף ע"י ההורה. אם שואלים על "הבן שלי" או על ילד לפי שם, השתמשי בזה.
            ${if (matched) "אלה הרגעים שנמצאו מתאימים לשאלה מתוך כל הזיכרונות (לא כולם)." else "לא נמצאו רגעים שמתאימים לשאלה לפי הפרטים השמורים; אלה רק הרגעים האחרונים. אם אף אחד מהם לא עונה, אמרי שלא מצאת."}
            ידוע = שדות הרשומה: when, place, people, milestone_marked_by_parent, favorite, detector (מה הגלאי שמע/ראה, לא הוכחה).
            נראה בתמונות = seen_in_images ו-seen_tags: תיאור של 3 תמונות דגימה בלבד. אין לך אודיו, תמליל או רצף וידאו מלא.
            אל תמציאי רגעים שלא ברשימה. אל תצטטי דיבור, אל תסיקי מה קרה בסוף סרטון, ואל תקבעי "פעם ראשונה" אלא אם ההורה סימן אבן דרך.
            time_source=imported אומר שהזמן הוא זמן ההוספה; תאריך הצילום המקורי אינו ידוע. פרופיל המשפחה אינו הוכחה שמישהו נכח בזיכרון.
            אם שואלים על "הפעם הראשונה" של משהו, מצאי את הרגע המוקדם ביותר שמתאים ואמרי שזה הראשון שתועד.
            אם אין רגע מתאים, אמרי את זה בכנות.
            החזירי רק JSON בפורמט: {"known":"מה ידוע מהרשומות (או ריק)","seen":"מה נראה בתמונות (או ריק)","ids":["מזהי הרגעים להצגה, מהמתאים ביותר"]}

            הרגעים:
            $list
        """.trimIndent() + L.t("", "\n\nWrite the answer in ${L.aiLanguage}.")

        val messages = JSONArray()
        for ((role, text) in history.takeLast(6)) {
            messages.put(JSONObject().put("role", role).put("content", text))
        }
        messages.put(JSONObject().put("role", "user").put("content", question))

        val text = callMessages(ctx, CHAT_MODEL, system, messages, 700, purpose = "ask")
        val notFound = Answer(L.t("לא מצאתי תשובה ברורה בזיכרונות. אפשר לנסח אחרת, או לחפש לפי שם, מקום או תאריך.", "I didn't find a clear answer in your memories. Try asking differently, or search by a name, place or date."), emptyList())
        return try {
            val json = extractJson(text)
            val sent = memories.map { it.id }.toSet()
            val ids = json.optJSONArray("ids")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty().filter { it in sent }.distinct()
            val known = json.optString("known").trim()
            val seen = json.optString("seen").trim()
            val legacy = json.optString("answer").trim()
            val body = when {
                known.isNotEmpty() && seen.isNotEmpty() -> known + "\n" + L.t("לפי מה שנראה בתמונות: ", "From what's seen in the pictures: ") + seen
                known.isNotEmpty() -> known
                seen.isNotEmpty() -> L.t("לפי מה שנראה בתמונות: ", "From what's seen in the pictures: ") + seen
                else -> legacy
            }
            val shown = ids.mapNotNull { id -> memories.firstOrNull { it.id == id } }
            val profileNames = Profile.names(ctx)
            // Names the parent asked about may be named back ("I didn't find moments with Noa").
            val allowed = shown.flatMap { m -> m.allPeople.split(",").map(String::trim) }.filter(String::isNotEmpty).toSet() +
                profileNames.filter { HonestyRules.mentions(question, it) }
            val honest = HonestyRules.clean(body, HonestyRules.Context(allowed, profileNames, milestoneMarked = shown.any { it.milestone.isNotEmpty() }))
            if (honest.isEmpty()) notFound else Answer(honest, ids)
        } catch (_: Exception) {
            notFound
        }
    }

    /**
     * "שאל את הזיכרון": answers a question about one moment, looking at its frames,
     * and can point at other moments like it ("find more moments like this").
     */
    fun askMoment(ctx: Context, m: Memory, question: String, others: List<Memory>): Answer {
        val frames = extractFrames(ctx, m)
        val list = JSONArray()
        for (o in others.filter { it.id != m.id }.take(150)) {
            list.put(JSONObject().apply {
                put("id", o.id)
                put("when", describeTime(o.time))
                if (o.title.isNotEmpty()) put("title", o.title)
                if (o.description.isNotEmpty()) put("description", o.description.take(160))
                if (o.category.isNotEmpty()) put("category", o.category)
                if (o.allPeople.isNotEmpty()) put("people", o.allPeople)
            })
        }
        val about = JSONObject().apply {
            put("when", describeTime(m.time))
            put("time_source", m.timeSource)
            put("media_type", m.mediaType)
            if (m.place.isNotEmpty()) put("place", m.place)
            put("trigger", Labels.he(m.label))
            if (m.title.isNotEmpty()) put("title", m.title)
            if (m.description.isNotEmpty()) put("description", m.description)
            if (m.reason.isNotEmpty()) put("why_it_matters", m.reason)
            if (m.allPeople.isNotEmpty()) put("people_recognized", m.allPeople)
            if (m.tags.isNotEmpty()) put("tags", m.tags)
        }
        val system = """
            את "Moments", עוזרת זיכרונות משפחתית חמה. ההורה צופה עכשיו ברגע אחד ושואל עליו שאלה.
            ענו $inLang, בקצרה (1 עד 3 משפטים), בחום ובכנות, לפי התמונות מהרגע והפרטים עליו.
            אם אי אפשר לדעת מהתמונות או מהפרטים, אמרי במפורש שאינך יודעת ואל תמציאי.
            אין אודיו, אין תמליל ואין רצף וידאו מלא. אי אפשר לדעת מה נאמר, מי דיבר או מה קרה בסוף הסרטון מהדגימות בלבד.
            שמות מותר רק מתוך people_recognized; פרופיל המשפחה אינו הוכחה לנוכחות. אין לשייך פעולה לפנים בשם מסוים בלי התאמה מזוהה.
            time_source=imported אומר שזה זמן הוספה, לא תאריך צילום ידוע. אין לקבוע אירוע ראשון בחיים מתוך תיעוד חלקי.
            ${Profile.describe(ctx, m.time)}
            אם מבקשים רגעים דומים או קשורים, בחרי עד 6 מתוך הרשימה "רגעים אחרים" והחזירי את ה-id שלהם.
            החזירי רק JSON: {"answer":"התשובה","ids":["..."]}
        """.trimIndent() + L.t("", "\nWrite the answer in ${L.aiLanguage}.")
        val content = JSONArray()
        for (f in frames) {
            content.put(JSONObject().put("type", "image").put("source", JSONObject()
                .put("type", "base64").put("media_type", "image/jpeg").put("data", f)))
        }
        content.put(JSONObject().put("type", "text").put("text", "הרגע: $about\n\nרגעים אחרים: $list\n\nהשאלה: $question"))
        val messages = JSONArray().put(JSONObject().put("role", "user").put("content", content))
        val text = callMessages(ctx, VISION_MODEL, system, messages, 500, purpose = "ask_moment", images = frames.size)
        return try {
            val json = extractJson(text)
            val ids = json.optJSONArray("ids")
            Answer(json.optString("answer").ifBlank { text }, if (ids != null) (0 until ids.length()).map { ids.optString(it) } else emptyList())
        } catch (_: Exception) {
            Answer(text, emptyList())
        }
    }

    // ---------- helpers ----------

    private val HE_DAYS = arrayOf("", "ראשון", "שני", "שלישי", "רביעי", "חמישי", "שישי", "שבת")
    private val EN_DAYS = arrayOf("", "Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")

    /** "בעברית" in Hebrew mode; tells the AI to write in English in English mode. */
    private val inLang: String get() = L.t("בעברית", "באנגלית (in English)")

    /** e.g. "יום שני 05.10.2026 17:42" — gives the model enough to answer "yesterday" or "last week". */
    /**
     * Pitzki's answer in the chat with a small child ([PitzkiChat] writes the rules and the topic): one or two short
     * Hebrew sentences, or "" if the AI isn't available. Background thread.
     */
    fun pitzkiReply(ctx: Context, system: String, user: String): String = try {
        call(ctx, CHAT_MODEL, system, JSONArray().put(JSONObject().put("type", "text").put("text", user)), 160, purpose = "pitzki_chat").trim()
    } catch (_: Exception) { "" }

    /**
     * Auto-fill in the family file: reads a receipt, document, coupon or voucher (a picture, or shared text) and
     * suggests its details as JSON. The parent agreed to this on the family file's privacy screen and taps
     * "Auto-fill" each time, so it doesn't depend on "moment understanding" being on. Background thread only.
     */
    fun readFamilyItem(ctx: Context, jpegBase64: String?, text: String?, children: List<String>, today: String, pdfBase64: String? = null): JSONObject {
        val system = """
            You read one item a parent shared into their family file: a receipt, a warranty certificate, a store credit, an exchange slip, a document, a coupon, a voucher/gift card, or a gift.
            Answer with ONE JSON object only, no other text:
            {"kind": "receipt|warranty|credit|exchange|document|coupon|voucher|gift" (warranty = תעודת אחריות; credit = זיכוי/תעודת זיכוי from a store; exchange = פתק החלפה),
             "title": "short title in the item's language, e.g. 'שכר לימוד אוקטובר'",
             "vendor": "who issued it (store, kindergarten, company) or null",
             "amount": number in shekels or null (the total paid; for a coupon/voucher its value),
             "date": "yyyy-MM-dd the document's date, or null",
             "expires": "yyyy-MM-dd expiry date (for a warranty: when it ends; for an exchange slip: the last day to exchange), or null",
             "code": "coupon/voucher code or card number exactly as written, or null",
             "category": "for a receipt or document, one of: daycare (מעון/גן/צהרון), classes (חוגים), health (רופא, בדיקות, תרופות, קופת חולים), vacation (חופשה, מלון, טיסה), phone (סלולר, אינטרנט), clothes (ביגוד, הנעלה), equipment (ציוד, צעצועים), gifts (מתנות, אירועים), home (חשמל, מים, ארנונה, בית), other; or null",
             "child": "one of these names only if it is clearly written in the item: ${children.joinToString(", ").ifEmpty { "(none)" }}; else null"}
            Rules: copy numbers, codes and dates exactly; never guess. If something isn't written, use null.
            Dates in the item are usually day/month/year (Israel). Today is $today.
        """.trimIndent()
        val content = JSONArray()
        if (!jpegBase64.isNullOrEmpty()) content.put(JSONObject().put("type", "image")
            .put("source", JSONObject().put("type", "base64").put("media_type", "image/jpeg").put("data", jpegBase64)))
        // A PDF the phone couldn't turn into a picture (e.g. a protected one): the document itself.
        if (jpegBase64.isNullOrEmpty() && !pdfBase64.isNullOrEmpty()) content.put(JSONObject().put("type", "document")
            .put("source", JSONObject().put("type", "base64").put("media_type", "application/pdf").put("data", pdfBase64)))
        content.put(JSONObject().put("type", "text").put("text", if (text.isNullOrBlank()) "Read this item." else "The shared text:\n" + text.take(4000)))
        val body = JSONObject().put("model", CHAT_MODEL).put("max_tokens", 400).put("system", system)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
        TestMode.guard()
        val answer = AiGate.claude(ctx, body, "family_file", if (jpegBase64.isNullOrEmpty()) 0 else 1, consentedNow = true)
        return extractJson(answer)
    }

    /**
     * Smart search in the family file: the parent's question in their words ("כמה הוצאנו על הגן בספטמבר",
     * "שובר לסופר שעוד בתוקף") over a list of the items without codes or notes ([FamilyItems.searchIndex]).
     * Answers {"ids": [...], "answer": "one short sentence"}. Only when the parent taps the search. Background thread only.
     */
    fun searchFamilyFile(ctx: Context, query: String, index: String, today: String): JSONObject {
        val system = """
            You help a parent find things in their family file. Each line is one item:
            id|kind|title|from (store/chain/kindergarten)|category|child|amount in shekels|date yyyy-MM-dd|expires yyyy-MM-dd
            Kinds: receipt, warranty, credit (store credit), exchange (exchange slip), voucher (gift card), coupon, document, gift.
            Today is $today. An item whose expiry is before today has expired.
            Answer with ONE JSON object only: {"ids": ["the ids of the matching items, best first"], "answer": "one short sentence in the question's language, e.g. a total, a count, or what was found; empty if nothing to add"}.
            Understand Hebrew and English, chain names in either script (BuyMe = ביימי), and loose wording. If nothing matches, ids is [].
            Never invent items or numbers; totals only from the amounts listed.
        """.trimIndent()
        val content = JSONArray().put(JSONObject().put("type", "text").put("text", "Items:\n" + index.take(30000) + "\n\nQuestion: " + query.take(300)))
        val body = JSONObject().put("model", CHAT_MODEL).put("max_tokens", 500).put("system", system)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
        TestMode.guard()
        val answer = AiGate.claude(ctx, body, "family_file", 0, consentedNow = true)
        return extractJson(answer)
    }

    /**
     * Reads a child's savings quarterly report (a picture or the PDF itself): which fund, which track, the balance on
     * the report's date, and the deposits. Only when the parent taps it (the family file's consent covers it).
     * Background thread only.
     */
    fun readSavingsReport(ctx: Context, jpegBase64: String?, pdfBase64: String?, children: List<String>, today: String): JSONObject {
        val system = """
            You read an Israeli savings report (דוח רבעוני / דוח שנתי / דוח פעילות חודשי) of a child's savings: "חיסכון לכל ילד" (the
            state's savings for every child, deposits from ביטוח לאומי), a private fund (קופת גמל להשקעה, פוליסת חיסכון, תוכנית חיסכון
            בבנק), or a crypto account (e.g. Horizon / הוריזון, Bits of Gold: holdings in BTC, ETH…).
            Answer with ONE JSON object only, no other text:
            {"kind": "child_savings|private|crypto",
             "provider": "the managing company, e.g. מור, הראל, מגדל, אלטשולר שחם, ילין לפידות, מיטב, הפניקס, כלל, מנורה, בנק …",
             "fund_name": "the fund / plan name as written",
             "track": "the investment track (מסלול) as written, e.g. מניות, כללי, הלכה, סיכון מוגבר, סיכון מועט",
             "report_date": "yyyy-MM-dd the date the balance is for (end of the quarter)",
             "balance": number in shekels (the accumulated balance / יתרה צבורה / סך החיסכון on that date); null if the document shows the value only in dollars (never put dollars here),
             "deposits_in_period": number in shekels deposited during the report's period, or null,
             "period_months": how many months the report covers (a quarterly report = 3, a yearly one = 12), or null,
             "state_monthly_deposit": number the state deposits each month if written, or null,
             "parent_monthly_deposit": number the parents deposit each month if written, or null,
             "return_pct": the report period's net return in percent as a number (e.g. 4.2, or -1.3 for a loss), or null,
             "fees_pct": the management fee from balance in percent, or null,
             "coins": for crypto only: [{"symbol": "BTC", "units": the balance in that coin exactly as written, "monthly_ils": shekels bought each month by a standing order (הוראת קבע) if shown, else null}], else null,
             "value_usd": for crypto: the account's value in dollars if written (שערוך יתרה), else null,
             "fund_number": the fund's / track's number at the Ministry of Finance if written (מספר קופה / קוד קופה / מס' אישור קופה / מספר מסלול, usually 3-5 digits), as a number, else null,
             "child_name": "one of these names only if it is clearly the account holder: ${children.joinToString(", ").ifEmpty { "(none)" }}; else null"}
            Rules: copy numbers and dates exactly; never guess; if something isn't written, use null. Dates are usually day/month/year.
            Today is $today.
        """.trimIndent()
        val content = JSONArray()
        if (!jpegBase64.isNullOrEmpty()) content.put(JSONObject().put("type", "image")
            .put("source", JSONObject().put("type", "base64").put("media_type", "image/jpeg").put("data", jpegBase64)))
        if (!pdfBase64.isNullOrEmpty()) content.put(JSONObject().put("type", "document")
            .put("source", JSONObject().put("type", "base64").put("media_type", "application/pdf").put("data", pdfBase64)))
        content.put(JSONObject().put("type", "text").put("text", "Read this report."))
        // The stronger reader: Hebrew statements with tables, dollars and several coins (the small model failed on them).
        val body = JSONObject().put("model", VISION_MODEL).put("max_tokens", 1200).put("system", system)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
        TestMode.guard()
        val answer = AiGate.claude(ctx, body, "family_file", if (jpegBase64.isNullOrEmpty()) 0 else 1, consentedNow = true)
        return extractJson(answer)
    }

    fun describeTime(t: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = t }
        if (L.isEn) return EN_DAYS[c.get(Calendar.DAY_OF_WEEK)] + " " + SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.US).format(Date(t))
        val day = HE_DAYS[c.get(Calendar.DAY_OF_WEEK)]
        return "יום $day " + SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.US).format(Date(t))
    }

    private fun extractJson(text: String): JSONObject {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) throw ApiException(L.t("תשובה לא צפויה מה-AI", "Unexpected response from the AI"))
        return JSONObject(text.substring(start, end + 1))
    }

    private fun call(ctx: Context, model: String, system: String?, content: JSONArray, maxTokens: Int, purpose: String = "other", images: Int = 0): String {
        val messages = JSONArray().put(JSONObject().put("role", "user").put("content", content))
        return callMessages(ctx, model, system, messages, maxTokens, purpose, images)
    }

    /**
     * Writes a personal picture book for [child]. The family agreed to this specific use on the book's consent screen,
     * so it goes through even if general "moment understanding" is off. Returns null if the answer wasn't usable twice.
     */
    /**
     * [family]: the family profile, as (id, name, relation) - so the story can bring in the real people the child
     * loves (by how the child calls them), and say who they are so their photos can be used for the drawings.
     */
    fun bookStory(ctx: Context, child: String, ageMonths: Int?, girl: Boolean?, theme: BookTheme, details: String, pages: Int,
                  family: List<Triple<String, String, String>> = emptyList(), mustInclude: Boolean = false,
                  events: List<String> = emptyList(), script: String = ""): BookJson.Story? {
        val own = script.isNotBlank()
        val scriptBlock = if (!own) "" else
            "THE PARENT WROTE THIS STORY. It is the book's text:\n<<<\n" + script.take(4000) + "\n>>>\n" +
                "Split it into exactly $pages pages, in order, so each page is one moment that can be drawn. Keep the parent's words, " +
                "names, places and plot; only fix spelling and grammar, and gently shorten a page only if it is far too long to read " +
                "aloud. Do not add new events or a different ending. The title: the parent's title if they wrote one, else a short " +
                "warm title. (The rules below about writing the story apply only where they don't change the parent's text.)"
        val hebrew = !L.isEn
        val ageLine = when {
            ageMonths == null -> "a young child"
            ageMonths < 24 -> "$ageMonths months old"
            else -> "${ageMonths / 12} years old"
        }
        val who = when (girl) { true -> "a girl (use feminine Hebrew forms)"; false -> "a boy (use masculine Hebrew forms)"; null -> "a child" }
        val familyLines = family.joinToString("\n") { (id, name, rel) ->
            val pet = when (rel.trim()) { "כלב", "כלבה" -> ", the family's dog"; "חתול", "חתולה" -> ", the family's cat"; "חיית מחמד" -> ", the family's pet"; else -> "" }
            "  - family_id \"$id\": $name ($rel$pet)"
        }
        fun systemFor(compact: Boolean) = """
            You are an award-winning author of personal picture books for young children, writing with a child psychologist's care
            and a film director's eye. The hero is $child, $who, $ageLine.
            The book is in ${if (hebrew) "Hebrew (rich, natural, warm Hebrew that a parent loves reading aloud; no niqqud)" else "English"}.
            Theme: ${theme.en}. Guidance: ${theme.guidance}
            ${if (familyLines.isNotEmpty()) "The family (real people and pets; the child calls parents and grandparents by their role, e.g. אבא, אמא, סבתא):\n$familyLines" else ""}
            ${if (details.isNotBlank()) "Details from the parent (use them naturally): ${details.take(500)}" else ""}
            ${if (mustInclude && family.isNotEmpty()) "The parent chose who is in this book: every family member listed above must appear in the story and the pictures (each at least twice), and no other family member." else ""}
            ${if (events.isNotEmpty()) "Real moments from this family's life that the parent chose (build the story around them, in this order when it makes sense; keep the real places, people and details, gently turned into a picture book; do not invent other real events):\n" + events.joinToString("\n") { "  - $it" } else ""}
            $scriptBlock
            Rules for the story:
            - Call the hero mostly by the short name a family uses (${child.trim().split(" ").first()}), the full name at most once or twice.
            - Exactly $pages pages. Each page: at most ${BookJson.wordsPerPage(ageMonths)} words - count them; shorter is better.
            - It is read ALOUD by a parent to a ${ageLine} child, so it must flow when spoken: short sentences (mostly 3-8 words),
              one simple idea per page, everyday spoken Hebrew a toddler knows (no literary or rare words, no long subordinate clauses),
              a gentle rhythm, and at most one short line of dialogue per page (who says it is clear). Sound words only when they
              are fun to say aloud. Read every page aloud in your head before you write it.
            - A real, moving picture book, not a summary: a warm opening in the child's world, a feeling the child really has,
              small brave steps, a touching turning point, and a proud, cozy ending. One short refrain (3-6 words, always exactly the
              same) that the child can say along, on 3-4 pages, often as the page's last line.
            - Every page's text must match its picture (what is seen is what is told).
            - ${if (mustInclude) "Use the family members listed (as the child calls them)." else "Bring in 1-2 family members from the list when it helps the theme (use them as the child calls them)."} Do not invent other
              named relatives. Validate feelings; never shame, threaten or frighten. No brands, no famous characters, no medical advice.
            Rules for the pictures (for the illustrator, in English):
            - "cast": the people (and pets) besides $child who appear in the pictures (${if (mustInclude && family.isNotEmpty()) "exactly the ${family.size} family members listed above, each one" else "at most 3"}). For each: "id" (short english id like "father"),
              "name" (how the story calls them), "who" (English: relation to the child, gender, approximate age - e.g. "the child's
              father, a man in his thirties", or for a pet "the family's dog" - do not invent their looks, photos will be used),
              "family_id" (from the list, or ""). Family pets are characters too (with their names) when they fit the story.
            - For each page "scene": ONE illustration, max ${if (compact) 40 else 60} words, like a shot in an animated film: what happens, each person's action
              and feeling, the place, time of day and light, and the framing (close-up / medium / wide). Vary places, framing and light
              across the book. Refer to people by their cast id in brackets, e.g. [child], [father]. No text, letters or signs in the picture.
              Set scenes in real, detailed, lived-in places the family knows (their home in Israel, a neighborhood park, the street,
              the beach, grandma's kitchen), mostly in warm daylight or golden-hour sun, with lots of small concrete details.
            - For each page "cast": the ids in the picture (always include "child").
            - "outfit": one outfit $child wears on every page (English, with colors). "cover_scene" and "cover_cast" for the cover.
            - "dedication": one short line in the book's language from the parent to $child (e.g. "ל$child, ...").
            Format (very important, the app reads it by machine):
            - Return only JSON, nothing before or after it, no code fences.
            - Inside the texts never use the " character: write dialogue with a dash or with Hebrew quotation marks ״ ״,
              and Hebrew abbreviations with ״ (e.g. צה״ל). No line breaks inside a text.
            ${if (compact) "- Keep it short: the whole answer must fit in about 2500 words, and every page well under the word limit." else ""}
            {"title":"...","dedication":"...","outfit":"...","cover_scene":"...","cover_cast":["child"],
            "cast":[{"id":"...","name":"...","who":"...","family_id":"..."}],"pages":[{"text":"...","scene":"...","cast":["child"]}]}
        """.trimIndent()
        // Sonnet writes the best story. Through our server the story streams back while it's written; if Sonnet is too
        // slow for the server's time limit (150 s on Supabase's free plan), the faster Haiku writes it instead.
        // A story cut at the length limit, or not readable as JSON, is asked again in a shorter, stricter form.
        var model = VISION_MODEL
        var compact = false
        var tries = 0
        var why = ""
        while (tries < 3) {
            val system = systemFor(compact)
            val body = JSONObject().put("model", model).put("max_tokens", 8000).put("system", system)
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "Write the book.")))
            val got = if (apiKey(ctx).startsWith("sk-")) Sse.Streamed(callMessages(ctx, model, system, body.getJSONArray("messages"), 8000, "book"), true, "")
            else try {
                AiGate.claudeStreamFull(ctx, body, "book", consentedNow = true)
            } catch (e: AiGate.Incomplete) {
                if (e.tooSlow && model != CHAT_MODEL) { model = CHAT_MODEL; compact = true; continue }
                throw e // a dropped connection: the caller waits for the network and tries again
            }
            tries++
            val story = BookJson.story(got.text, minPages = pages - 1, maxPages = pages)
            // Too long to read aloud: asked once more, shorter (the last try is taken as it is).
            if (story != null && !own && tries < 3 && BookJson.tooWordy(story.pages.map { it.first }, BookJson.wordsPerPage(ageMonths))) {
                why = L.t("הטקסט יצא ארוך מדי", "The text came out too long")
                compact = true
                continue
            }
            story?.let { return it }
            why = when {
                got.stopReason == "max_tokens" -> L.t("הסיפור יצא ארוך מדי ונקטע", "The story came out too long and was cut")
                got.text.isBlank() -> L.t("לא הגיעה תשובה מה-AI", "No answer came from the AI")
                else -> L.t("הסיפור הגיע בפורמט שלא הצלחנו לקרוא", "The story came back in a form we couldn't read")
            }
            android.util.Log.w("Book", "story try $tries ($model, compact=$compact) failed: stop=${got.stopReason} len=${got.text.length}")
            compact = true
        }
        throw IllegalStateException(L.t("לא הצלחנו לכתוב את הסיפור ($why). נסו שוב.", "Couldn't write the story ($why). Try again."))
    }

    private fun callMessages(ctx: Context, model: String, system: String?, messages: JSONArray, maxTokens: Int, purpose: String = "other", images: Int = 0): String {
        val body = JSONObject().apply {
            put("model", model)
            put("max_tokens", maxTokens)
            if (system != null) put("system", system)
            put("messages", messages)
        }
        val key = apiKey(ctx)
        TestMode.guard()
        // No personal key (the normal case): through Moments' server, which holds the key and the family's budget.
        if (!key.startsWith("sk-")) return AiGate.claude(ctx, body, purpose, images)
        val conn = (URL(URL_MESSAGES).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 90_000
            doOutput = true
            setRequestProperty("content-type", "application/json")
            setRequestProperty("x-api-key", key)
            setRequestProperty("anthropic-version", "2023-06-01")
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val raw = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                val msg = try {
                    JSONObject(raw).optJSONObject("error")?.optString("message") ?: raw
                } catch (_: Exception) {
                    raw
                }
                throw ApiException(
                    when (code) {
                        401 -> L.t("מפתח ה-API לא תקין", "The API key isn't valid")
                        429 -> L.t("יותר מדי בקשות, נסה שוב בעוד רגע", "Too many requests. Try again in a moment")
                        else -> L.t("שגיאה מה-AI ($code): ${msg.take(150)}", "AI error ($code): ${msg.take(150)}")
                    }
                )
            }
            val arr = JSONObject(raw).optJSONArray("content") ?: JSONArray()
            val sb = StringBuilder()
            for (i in 0 until arr.length()) {
                val block = arr.getJSONObject(i)
                if (block.optString("type") == "text") sb.append(block.optString("text"))
            }
            return sb.toString()
        } finally {
            conn.disconnect()
        }
    }
}
