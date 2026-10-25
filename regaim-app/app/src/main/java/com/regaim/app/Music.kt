package com.regaim.app

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * Gentle background music made on the phone (no files, no licensing): soft piano-like arpeggios,
 * a warm pad and a bass line, with a little echo. Loops seamlessly. Three moods.
 */
object Music {
    enum class Mood(private val he: String, private val en: String) {
        WARM("חם", "Warm"), HAPPY("שמח", "Happy"), CALM("רגוע", "Calm"), NOSTALGIC("נוסטלגי", "Nostalgic");
        val title: String get() = L.t(he, en)
    }

    private const val RATE = 44_100
    private var track: AudioTrack? = null

    private fun freq(midi: Int) = 440.0 * Math.pow(2.0, (midi - 69) / 12.0)

    /** MIDI notes of each bar's chord (root position, around middle C). */
    private fun progression(mood: Mood): Pair<List<IntArray>, Double> = when (mood) {
        // C – G – Am – F
        Mood.WARM -> listOf(intArrayOf(60, 64, 67), intArrayOf(55, 59, 62), intArrayOf(57, 60, 64), intArrayOf(53, 57, 60)) to 84.0
        // F – C – G – C (brighter, faster)
        Mood.HAPPY -> listOf(intArrayOf(53, 57, 60), intArrayOf(60, 64, 67), intArrayOf(55, 59, 62), intArrayOf(60, 64, 67)) to 108.0
        // Am – F – C – G (slow, floating)
        Mood.CALM -> listOf(intArrayOf(57, 60, 64), intArrayOf(53, 57, 60), intArrayOf(60, 64, 67), intArrayOf(55, 59, 62)) to 68.0
        // Dm – Bb – F – C (wistful, medium-slow)
        Mood.NOSTALGIC -> listOf(intArrayOf(62, 65, 69), intArrayOf(58, 62, 65), intArrayOf(53, 57, 60), intArrayOf(60, 64, 67)) to 76.0
    }

    /** Builds 8 bars of music (two passes of the progression), seamless when looped. */
    fun build(mood: Mood): ShortArray {
        val (chords, bpm) = progression(mood)
        val beat = 60.0 / bpm
        val bars = 8
        val loopLen = (bars * 4 * beat * RATE).toInt()
        // Render two loops and keep the second, so echoes from the end flow into the start.
        val buf = FloatArray(loopLen * 2)

        fun note(startSec: Double, midi: Int, durSec: Double, amp: Double, decay: Double, attack: Double, bright: Double) {
            val f = freq(midi)
            val s0 = (startSec * RATE).toInt()
            val n = (durSec * RATE).toInt()
            for (i in 0 until n) {
                val idx = s0 + i
                if (idx >= buf.size) break
                val t = i.toDouble() / RATE
                val env = min(1.0, t / attack) * exp(-t * decay)
                val w = 2 * PI * f * t
                val v = sin(w) + bright * 0.5 * sin(2 * w) + bright * 0.2 * sin(3 * w)
                buf[idx] += (v * env * amp).toFloat()
            }
        }

        for (pass in 0 until 2) {
            val passStart = pass * loopLen.toDouble() / RATE
            for (bar in 0 until bars) {
                val chord = chords[bar % chords.size]
                val barStart = passStart + bar * 4 * beat
                // Pad: the chord, soft and slow.
                for (m in chord) note(barStart, m, 4 * beat + 0.4, 0.05, 0.45, 0.6, 0.1)
                // Bass on beats 1 and 3.
                note(barStart, chord[0] - 24, 2 * beat, 0.16, 1.6, 0.01, 0.15)
                note(barStart + 2 * beat, chord[0] - 24, 2 * beat, 0.12, 1.6, 0.01, 0.15)
                // Piano arpeggio up and down the chord, an octave higher.
                val arp = intArrayOf(chord[0] + 12, chord[1] + 12, chord[2] + 12, chord[0] + 24, chord[2] + 12, chord[1] + 12, chord[0] + 12, chord[1] + 12)
                val step = if (mood == Mood.CALM) beat else beat / 2
                val count = if (mood == Mood.CALM) 4 else 8
                for (k in 0 until count) {
                    note(barStart + k * step, arp[k % arp.size], 1.6, if (k == 0) 0.13 else 0.09, 2.2, 0.004, 0.6)
                }
            }
        }
        // A little echo for space.
        val d = (0.27 * RATE).toInt()
        for (i in d until buf.size) buf[i] += buf[i - d] * 0.28f
        // Keep the second loop, normalise softly.
        val out = ShortArray(loopLen)
        var peak = 0.0001f
        for (i in 0 until loopLen) peak = maxOf(peak, kotlin.math.abs(buf[loopLen + i]))
        val gain = 0.55f / peak
        for (i in 0 until loopLen) out[i] = (buf[loopLen + i] * gain * Short.MAX_VALUE).toInt().coerceIn(-32767, 32767).toShort()
        return out
    }

    /**
     * Writes [mood] music as a stereo WAV of exactly [durationMs], with a soft fade in and a long fade out,
     * for mixing into a video. [gain] 1.0 = full level; use ~0.35 to sit quietly under voices. Background thread only.
     */
    fun renderWav(mood: Mood, durationMs: Long, gain: Float, out: java.io.File) {
        val loop = build(mood)
        val frames = (durationMs.coerceAtLeast(1000L) * RATE / 1000L).toInt()
        val fadeIn = (1.0 * RATE).toInt().coerceAtMost(frames / 2)
        val fadeOut = (2.5 * RATE).toInt().coerceAtMost(frames / 2)
        val bytes = ByteArray(frames * 4)
        for (i in 0 until frames) {
            var g = gain
            if (i < fadeIn) g *= i.toFloat() / fadeIn
            val left = frames - i
            if (left < fadeOut) g *= left.toFloat() / fadeOut
            val v = (loop[i % loop.size] * g).toInt().coerceIn(-32767, 32767)
            val lo = (v and 0xff).toByte()
            val hi = ((v shr 8) and 0xff).toByte()
            bytes[i * 4] = lo; bytes[i * 4 + 1] = hi; bytes[i * 4 + 2] = lo; bytes[i * 4 + 3] = hi
        }
        java.io.FileOutputStream(out).use { f ->
            fun le(v: Int, n: Int) = ByteArray(n) { ((v shr (8 * it)) and 0xff).toByte() }
            f.write("RIFF".toByteArray()); f.write(le(36 + bytes.size, 4)); f.write("WAVE".toByteArray())
            f.write("fmt ".toByteArray()); f.write(le(16, 4)); f.write(le(1, 2)); f.write(le(2, 2))
            f.write(le(RATE, 4)); f.write(le(RATE * 4, 4)); f.write(le(4, 2)); f.write(le(16, 2))
            f.write("data".toByteArray()); f.write(le(bytes.size, 4)); f.write(bytes)
        }
    }

    // ---------- the music a person picked for videos ----------

    /** "none" or a [Mood] name. Remembered between shares. */
    fun choice(ctx: android.content.Context, key: String = "share_music"): Mood? =
        Mood.values().firstOrNull { it.name == Settings.prefs(ctx).getString(key, "none") }

    fun setChoice(ctx: android.content.Context, mood: Mood?, key: String = "share_music") {
        Settings.prefs(ctx).edit().putString(key, mood?.name ?: "none").apply()
    }

    /** A dialog to pick the music (with a short preview of each). [onPicked] gets the final choice. */
    fun chooser(a: androidx.appcompat.app.AppCompatActivity, current: Mood?, onPicked: (Mood?) -> Unit) {
        val moods = Mood.values().toList()
        val labels = (listOf(L.t("ללא מוזיקה (הסאונד המקורי)", "No music (original sound)")) + moods.map { "♪ ${it.title}" }).toTypedArray()
        var sel = if (current == null) 0 else moods.indexOf(current) + 1
        fun preview() {
            stop()
            val m = moods.getOrNull(sel - 1) ?: return
            Thread { play(m) }.start()
        }
        androidx.appcompat.app.AlertDialog.Builder(a)
            .setTitle(L.t("מוזיקה לסרטון", "Video music"))
            .setSingleChoiceItems(labels, sel) { _, which -> sel = which; preview() }
            .setPositiveButton(L.t("בחירה", "Choose")) { _, _ -> onPicked(moods.getOrNull(sel - 1)) }
            .setNegativeButton(L.t("ביטול", "Cancel"), null)
            .setOnDismissListener { stop() }
            .show()
    }

    @Volatile private var wanted = 0

    /** Builds and plays the music in a loop (a newer call or stop() cancels it). Call from a background thread. */
    fun play(mood: Mood, stillWanted: () -> Boolean = { true }) {
        val id = synchronized(this) { ++wanted }
        val pcm = build(mood) // slow part, outside the lock
        synchronized(this) {
            if (id != wanted || !stillWanted()) return
            startTrack(pcm)
        }
    }

    private fun startTrack(pcm: ShortArray) {
        try { track?.stop() } catch (_: Exception) {}
        try { track?.release() } catch (_: Exception) {}
        track = null
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
        t.write(pcm, 0, pcm.size)
        t.setLoopPoints(0, pcm.size, -1)
        t.setVolume(0f)
        t.play()
        track = t
        // Fade in over ~1.5 seconds.
        Thread {
            for (i in 1..15) {
                try { Thread.sleep(100) } catch (_: InterruptedException) { return@Thread }
                synchronized(this) { if (track === t) t.setVolume(i / 15f * 0.8f) }
            }
        }.start()
    }

    @Synchronized
    fun pause() { try { track?.pause() } catch (_: Exception) {} }

    @Synchronized
    fun resume() { try { track?.play() } catch (_: Exception) {} }

    @Synchronized
    fun stop() {
        wanted++
        try { track?.stop() } catch (_: Exception) {}
        try { track?.release() } catch (_: Exception) {}
        track = null
    }
}
