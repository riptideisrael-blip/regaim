package com.regaim.app

import android.media.MediaCodec

/** Monotonic clock in microseconds, shared by the video and audio streams. */
fun nowUs(): Long = System.nanoTime() / 1000

/** One encoded frame (video) or packet (audio) kept in memory. */
class EncodedSample(val data: ByteArray, val ptsUs: Long, val flags: Int) {
    val isKey: Boolean get() = (flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
}

/**
 * Rolling in-memory buffer: keeps only the last [keepUs] of encoded samples.
 * Nothing is written to disk unless a moment is detected.
 */
class SampleRing(private val keepUs: Long) {
    private val queue = ArrayDeque<EncodedSample>()

    @Synchronized
    fun add(sample: EncodedSample) {
        queue.addLast(sample)
        val cutoff = sample.ptsUs - keepUs
        while (queue.isNotEmpty() && queue.first().ptsUs < cutoff) queue.removeFirst()
    }

    @Synchronized
    fun snapshot(): List<EncodedSample> = queue.toList()

    @Synchronized
    fun clear() = queue.clear()
}

/** Live state shared between the capture service and the screen. */
object Status {
    @Volatile var running = false
    @Volatile var startedAt = 0L
    @Volatile var lastScore = 0f
    @Volatile var lastLabel = ""
    @Volatile var topLabel = ""
    @Volatile var clips = 0
    @Volatile var pending = false
    @Volatile var lastClipLabel = ""
    @Volatile var lastClipAt = 0L
    @Volatile var error: String? = null
    @Volatile var threshold = 0.3f
    @Volatile var preview: android.graphics.Bitmap? = null
    @Volatile var uiVisible = false
    @Volatile var videoFrames = 0L
    @Volatile var videoSize = ""
    @Volatile var peakScore = 0f
    @Volatile var wideLens = true
    @Volatile var zoom = 0.6f
    @Volatile var facesOn = true
    @Volatile var faces = 0
    @Volatile var smile = 0f
    @Volatile var zoomApplied = 1f
    @Volatile var wordsOn = true
    @Volatile var laughOn = true
    /** Excited shouts (cheering, "woohoo") as a trigger. Off by default: loud voices are not always happy moments. */
    @Volatile var shoutOn = false
    /** Minutes between two automatic moments (a button press or a code word always saves). */
    @Volatile var gapMin = 3
    @Volatile var hugOn = true
    @Volatile var singOn = true
    @Volatile var loveOn = true
    @Volatile var buzzOn = true
    @Volatile var phrases: List<String> = Phrases.DEFAULT
    @Volatile var wordsState = ""
    @Volatile var heard = ""
    @Volatile var lastFrameMs = 0L
    @Volatile var lensInfo = ""
    @Volatile var top3 = ""

    fun reset() {
        running = false
        startedAt = 0L
        lastScore = 0f
        lastLabel = ""
        topLabel = ""
        clips = 0
        pending = false
        lastClipLabel = ""
        lastClipAt = 0L
        error = null
        preview = null
        videoFrames = 0L
        peakScore = 0f
        top3 = ""
        wordsState = ""
        heard = ""
    }
}

/** YAMNet sound classes that count as a "moment", with Hebrew names. */
object Labels {
    val TRIGGERS: Map<String, String> = mapOf(
        "Laughter" to "צחוק",
        "Baby laughter" to "צחוק תינוק",
        "Giggle" to "צחקוק",
        "Belly laugh" to "צחוק מתגלגל",
        "Chuckle, chortle" to "צחקוק",
        "Snicker" to "גיחוך",
        "Children shouting" to "צעקות שמחה",
        "Cheering" to "תשואות",
        "Whoop" to "קריאת התלהבות",
        "Shout" to "קריאת התלהבות",
        "Yell" to "קריאת התלהבות",
        "Smile" to "חיוך גדול",
        "Approach" to "רץ אליך",
        "Hug" to "חיבוק",
        "Singing" to "שירה",
        "Child singing" to "ילד שר",
        "Choir" to "שירה ביחד",
    )

    /** Singing classes: counted separately, so laughter and singing can be turned on and off apart. */
    val SINGING = setOf("Singing", "Child singing", "Choir")

    private val EXTRA: Map<String, String> = mapOf(
        "manual" to "שמירה ידנית",
        "Speech" to "דיבור",
        "Child speech, kid speaking" to "ילד מדבר",
        "Silence" to "שקט",
        "Crying, sobbing" to "בכי",
        "Baby cry, infant cry" to "בכי תינוק",
        "Music" to "מוזיקה",
        "Television" to "טלוויזיה",
        "Joy" to "רגע שמח",
        "Play" to "משחק",
        "Tickle" to "דגדוגים",
        "Together" to "רגע ביחד",
        "Celebration" to "חגיגה",
    )

    val VISUAL = setOf("Smile", "Approach", "Hug")

    /** Saved because the user asked (button / code word) — these teach the app what matters to them. */
    fun isChosen(label: String) = label == "manual" || (label.startsWith("״") && Phrases.isCommand(label.trim('״')))

    /** English display names for the same labels (used when the app is in English). */
    private val EN: Map<String, String> = mapOf(
        "Laughter" to "Laughter",
        "Baby laughter" to "Baby laughter",
        "Giggle" to "Giggle",
        "Belly laugh" to "Belly laugh",
        "Chuckle, chortle" to "Giggle",
        "Snicker" to "Snicker",
        "Children shouting" to "Happy shouts",
        "Cheering" to "Cheering",
        "Whoop" to "Excited shout",
        "Shout" to "Excited shout",
        "Yell" to "Excited shout",
        "Smile" to "Big smile",
        "Approach" to "Running to you",
        "Hug" to "Hug",
        "Singing" to "Singing",
        "Child singing" to "Child singing",
        "Choir" to "Singing together",
        "manual" to "Saved by you",
        "Speech" to "Talking",
        "Child speech, kid speaking" to "Child talking",
        "Silence" to "Quiet",
        "Crying, sobbing" to "Crying",
        "Baby cry, infant cry" to "Baby crying",
        "Music" to "Music",
        "Television" to "TV",
        "Joy" to "Happy moment",
        "Play" to "Playtime",
        "Tickle" to "Tickles",
        "Together" to "Together",
        "Celebration" to "Celebration",
        "נוסף מהגלריה" to "Added from gallery",
    )

    /** Display name of a label in the current language (Hebrew by default; the name is historical). */
    fun he(label: String): String = if (L.isEn) EN[label] ?: label else TRIGGERS[label] ?: EXTRA[label] ?: label
}

/** Fixed moment categories (Hebrew), with a color for chips and dots. */
object Categories {
    val ALL = listOf("צחוק", "משחק", "אוכל", "בחוץ", "אמבטיה", "שינה", "משפחה", "אבן דרך", "אחר")

    fun color(c: String): Int = android.graphics.Color.parseColor(
        when (c) {
            "צחוק" -> "#F2A93B"
            "משחק" -> "#E57A6F"
            "אוכל" -> "#D9825B"
            "בחוץ" -> "#7FA36B"
            "אמבטיה" -> "#5B9BB5"
            "שינה" -> "#8C7FB8"
            "משפחה" -> "#C9667A"
            "אבן דרך" -> "#87664F"
            else -> "#A8998B"
        }
    )
}

/**
 * Low-pass filter + decimation (e.g. 48 kHz -> 16 kHz) for the laughter model,
 * so high frequencies don't fold back as fake "buzzing" sounds.
 */
class Downsampler(private val factor: Int) {
    private val taps: FloatArray = if (factor <= 1) floatArrayOf(1f) else design(31, 0.45f / factor)
    private var tail = FloatArray(0)
    private var next = taps.size - 1

    fun process(input: ShortArray, n: Int): FloatArray {
        val k = taps.size
        val combined = FloatArray(tail.size + n)
        System.arraycopy(tail, 0, combined, 0, tail.size)
        for (i in 0 until n) combined[tail.size + i] = input[i] / 32768f
        val out = FloatArray(n / maxOf(factor, 1) + 2)
        var count = 0
        var p = next
        while (p < combined.size) {
            var acc = 0f
            for (t in 0 until k) acc += taps[t] * combined[p - t]
            out[count++] = acc
            p += maxOf(factor, 1)
        }
        val keep = minOf(k - 1, combined.size)
        tail = combined.copyOfRange(combined.size - keep, combined.size)
        next = p - (combined.size - keep)
        return out.copyOf(count)
    }

    private fun design(n: Int, cutoff: Float): FloatArray {
        val m = (n - 1) / 2.0
        val h = FloatArray(n)
        var sum = 0.0
        for (i in 0 until n) {
            val x = i - m
            val sinc = if (x == 0.0) 2.0 * cutoff else Math.sin(2.0 * Math.PI * cutoff * x) / (Math.PI * x)
            val win = 0.54 - 0.46 * Math.cos(2.0 * Math.PI * i / (n - 1))
            h[i] = (sinc * win).toFloat()
            sum += h[i]
        }
        for (i in 0 until n) h[i] = (h[i] / sum).toFloat()
        return h
    }
}
