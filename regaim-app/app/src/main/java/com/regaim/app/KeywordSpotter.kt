package com.regaim.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Phrase list + forgiving Hebrew matching ("יששש" = "יש", "יואוו" = "יואו"). */
object Phrases {
    val DEFAULT = listOf(
        "אבא תראה", "אמא תראה", "תראה", "תראי", "וואו", "יואו", "יששש", "איזה כיף", "הצלחתי",
        "שמור הקלטה", "שמור רגע",
    )

    /** "I love you" — always worth keeping, saved even right after another moment. */
    val LOVE = listOf("אוהב אותך", "אוהבת אותך", "אוהבים אותך")

    fun isLove(p: String) = LOVE.any { norm(it) == norm(p) }

    /** Code words that always save, even right after another moment. */
    fun isCommand(p: String) = norm(p).startsWith("שמור")

    fun norm(s: String): String = s.lowercase()
        .replace(Regex("[^\\p{L}\\p{N} ]"), " ")
        .replace(Regex("(\\p{L})\\1+"), "$1")
        .replace(Regex("\\s+"), " ")
        .trim()

    fun match(text: String, phrases: List<String>): String? {
        val t = " " + norm(text) + " "
        return phrases.firstOrNull { p -> val n = norm(p); n.isNotEmpty() && t.contains(" $n ") }
    }

    fun parse(raw: String): List<String> =
        raw.split(',', '\n', '،').map { it.trim() }.filter { it.isNotEmpty() }
}

/**
 * Listens for Hebrew words in the same audio stream we already record (no second microphone),
 * using the phone's speech recognizer (Android 13+). Calls [onPhrase] when a phrase is heard.
 */
class KeywordSpotter(
    private val ctx: Context,
    private val phrases: () -> List<String>,
    private val onPhrase: (String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    @Volatile private var out: OutputStream? = null
    @Volatile private var active = false
    private val queue = LinkedBlockingQueue<ByteArray>(40)
    private var writer: Thread? = null
    private var errors = 0
    private var lastHitMs = 0L
    private var lastHitPhrase = ""

    fun start() {
        if (Build.VERSION.SDK_INT < 33) {
            Status.wordsState = L.t("דורש אנדרואיד 13 ומעלה", "Requires Android 13 or later")
            return
        }
        active = true
        writer = Thread({ writeLoop() }, "regaim-words").also { it.start() }
        main.post { begin() }
    }

    fun stop() {
        active = false
        main.post {
            try { recognizer?.destroy() } catch (_: Exception) {}
            recognizer = null
        }
        closeOut()
        writer?.interrupt()
        writer = null
        queue.clear()
    }

    /** Called from the audio thread with 16 kHz samples. Never blocks. */
    fun feed(samples: FloatArray) {
        if (!active || out == null || samples.isEmpty()) return
        val bytes = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val v = (samples[i].coerceIn(-1f, 1f) * 32767f).toInt()
            bytes[2 * i] = (v and 0xff).toByte()
            bytes[2 * i + 1] = ((v shr 8) and 0xff).toByte()
        }
        queue.offer(bytes) // drops audio if the recognizer falls behind, instead of stalling recording
    }

    private fun writeLoop() {
        while (active) {
            val b = try { queue.poll(500, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { break } ?: continue
            val o = out ?: continue
            try {
                o.write(b)
            } catch (_: IOException) {
                closeOut()
            }
        }
    }

    private fun closeOut() {
        val o = out
        out = null
        try { o?.close() } catch (_: Exception) {}
    }

    private fun createRecognizer(): SpeechRecognizer? {
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) return null
        val services = ctx.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
        val google = services.firstOrNull { it.serviceInfo.packageName == "com.google.android.googlequicksearchbox" }
            ?: services.firstOrNull { it.serviceInfo.packageName.startsWith("com.google") }
        return if (google != null) {
            SpeechRecognizer.createSpeechRecognizer(ctx, ComponentName(google.serviceInfo.packageName, google.serviceInfo.name))
        } else {
            SpeechRecognizer.createSpeechRecognizer(ctx)
        }
    }

    private fun begin() {
        if (!active || Build.VERSION.SDK_INT < 33) return
        val rec = recognizer ?: createRecognizer()
        if (rec == null) {
            Status.wordsState = L.t("אין בטלפון שירות זיהוי דיבור", "No speech recognition service on this phone")
            return
        }
        recognizer = rec
        closeOut()
        queue.clear()
        val pipe = try { ParcelFileDescriptor.createPipe() } catch (e: IOException) { return }
        out = ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "he-IL")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16000)
            // Keep one long session going over our audio stream instead of stopping after one sentence.
            putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
        }
        rec.setRecognitionListener(listener)
        try {
            rec.startListening(intent)
            Status.wordsState = L.t("מקשיב למילים", "Listening for words")
        } catch (e: Exception) {
            Log.w("Regaim", "words", e)
            retry(5000)
        }
        try { pipe[0].close() } catch (_: Exception) {}
    }

    private fun retry(delayMs: Long) {
        closeOut()
        if (active) main.postDelayed({ begin() }, delayMs)
    }

    private fun check(results: Bundle?) {
        val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return
        val first = texts.firstOrNull() ?: return
        if (first.isNotBlank()) Status.heard = first
        val list = phrases()
        for (t in texts) {
            val hit = Phrases.match(t, list) ?: continue
            val now = System.currentTimeMillis()
            // Partial results repeat the same words; count each phrase once per few seconds.
            if (hit == lastHitPhrase && now - lastHitMs < 6000) return
            lastHitPhrase = hit
            lastHitMs = now
            onPhrase(hit)
            return
        }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { errors = 0 }
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) = check(partialResults)
        override fun onSegmentResults(segmentResults: Bundle) = check(segmentResults)
        override fun onEndOfSegmentedSession() = retry(300)
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onResults(results: Bundle?) {
            check(results)
            retry(300)
        }

        override fun onError(error: Int) {
            errors++
            when (error) {
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> {
                    Status.wordsState = L.t("זיהוי דיבור בעברית לא זמין בטלפון", "Hebrew speech recognition isn't available on this phone")
                    return
                }
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    Status.wordsState = L.t("חסרה הרשאה לזיהוי דיבור", "Speech recognition permission is missing")
                    return
                }
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                    Status.wordsState = L.t("אין אינטרנט לזיהוי מילים, מנסה שוב...", "No internet for word detection. Trying again...")
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> Status.wordsState = L.t("מקשיב למילים", "Listening for words")
                else -> Status.wordsState = L.t("זיהוי מילים: תקלה $error, מנסה שוב...", "Word detection error $error. Trying again...")
            }
            if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || errors > 3) {
                try { recognizer?.destroy() } catch (_: Exception) {}
                recognizer = null
            }
            retry(if (errors > 5) 10_000 else 1_000)
        }
    }
}
