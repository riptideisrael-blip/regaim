package com.regaim.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Pitzki's ears: listens a few seconds for the child's answer (the phone's own speech recognition, Hebrew; English
 * color words come through too). Main thread only. [listen]'s callback runs once: what was heard (best guesses first,
 * possibly empty) and whether the child said anything at all.
 */
class PitzkiEars(private val ctx: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var rec: SpeechRecognizer? = null
    private var done: ((List<String>, Boolean) -> Unit)? = null
    private var partial: List<String> = emptyList()
    private var spoke = false
    private var seq = 0
    /** The default recognizer failed this way before: Google's is used. */
    private var useGoogle = false
    /** Remembered for the session once Google's worked better. */
    private val preferGoogle get() = useGoogle

    private fun googleService(): android.content.ComponentName? = try {
        ctx.packageManager.queryIntentServices(Intent(android.speech.RecognitionService.SERVICE_INTERFACE), 0)
            .map { it.serviceInfo }
            .firstOrNull { it.packageName == "com.google.android.googlequicksearchbox" || it.packageName == "com.google.android.tts" }
            ?.let { android.content.ComponentName(it.packageName, it.name) }
    } catch (_: Exception) { null }

    /** Fresh tries after "busy" in this question (a few at most). */
    private var retries = 0
    /** The recognizer's last error code (0 = none), shown to the admin to see why a question wasn't heard. */
    @Volatile var lastError = 0
        private set

    fun available(): Boolean = try { SpeechRecognizer.isRecognitionAvailable(ctx) } catch (_: Exception) { false }

    fun listen(ms: Long, onHeard: (List<String>, Boolean) -> Unit) = listen(ms, emptyList(), onHeard)

    /**
     * [words]: the answers that end the listening at once, as soon as one is heard in the middle (a child doesn't wait
     * for the phone to decide the sentence ended); [Beat.ANY] there: any word at all.
     */
    fun listen(ms: Long, words: List<String>, onHeard: (List<String>, Boolean) -> Unit) {
        retries = 0; lastError = 0; wanted = words; listen(ms, onHeard, offline = true)
    }
    @Volatile private var wanted: List<String> = emptyList()

    /**
     * [offline]: on the phone itself first. A phone without the offline Hebrew pack answers at once with an error;
     * then it's asked again through the phone's online speech service, for the time that's left.
     */
    private fun listen(ms: Long, onHeard: (List<String>, Boolean) -> Unit, offline: Boolean) {
        stop()
        val started = System.currentTimeMillis()
        // Google's speech service when the phone has it (best for Hebrew); otherwise the phone's default one.
        val google = if (useGoogle || preferGoogle) googleService() else null
        val r = try {
            if (google != null) SpeechRecognizer.createSpeechRecognizer(ctx, google) else SpeechRecognizer.createSpeechRecognizer(ctx)
        } catch (_: Exception) { try { SpeechRecognizer.createSpeechRecognizer(ctx) } catch (_: Exception) { null } }
        if (r == null) { onHeard(emptyList(), false); return }
        rec = r
        done = onHeard
        partial = emptyList()
        spoke = false
        val my = ++seq
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() { spoke = true }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                if (my != seq) return
                // 12/13: language not supported / not downloaded offline; 5 (client), 9 (permissions), 11 (server
                // disconnected) also happen on some phones when offline isn't possible.
                val left = ms - (System.currentTimeMillis() - started)
                if (offline && !spoke && left > 2_000 && error in setOf(5, 11, 12, 13)) {
                    val cb = done ?: return
                    android.util.Log.i("PitzkiEars", "offline recognition failed ($error), trying online")
                    main.post { listen(left, cb, offline = false) }
                    return
                }
                // 6/7: "nobody spoke" / "nothing matched" — some phones say it after a second or two. A child of 3 needs
                // longer to answer: the phone listens again for the time that's left.
                if (!spoke && left > 1_500 && error in setOf(6, 7)) {
                    val cb = done ?: return
                    main.post { listen(left, cb, offline) }
                    return
                }
                // 8 busy / 5 client / 3 audio: the phone's recognizer wasn't free yet (the previous one, or Pitzki's
                // voice still holding the speaker). A fresh one, a moment later, for the time that's left.
                // 11 "server disconnected": the phone's own recognizer (e.g. Samsung's) dropped; Google's is tried.
                if (!spoke && left > 1_500 && error in setOf(3, 5, 8, 11) && retries < 3) {
                    if (error == 11 || error == 5) useGoogle = true
                    val cb = done ?: return
                    retries++
                    // This recognizer is let go; a stop() meanwhile (pause, next scene) changes seq and cancels the retry.
                    seq++; release()
                    val gen = seq
                    main.postDelayed({ if (seq == gen && done === cb) listen(left - 400, cb, offline) }, 400)
                    return
                }
                android.util.Log.i("PitzkiEars", "recognition error $error")
                lastError = error
                finish(partial, spoke)
            }
            override fun onResults(results: Bundle?) {
                if (my != seq) return
                val l = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                finish(if (l.isEmpty()) partial else l, true)
            }
            override fun onPartialResults(partialResults: Bundle?) {
                if (my != seq) return
                val l = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                if (l.isNotEmpty()) partial = l
                // A color already heard: answer at once, a little child doesn't wait for the end of the sentence.
                if (PitzkiScript.heard(l) != null) finish(l, true)
                // The answer the question waits for, already said: Pitzki answers right away.
                else if (wanted.isNotEmpty() && l.any { it.isNotBlank() } &&
                    (Beat.ANY in wanted || PitzkiScript.heardAny(l, wanted))) finish(l, true)
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "he-IL")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            // On the phone itself when it can (the child's voice stays on the phone).
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, offline)
            // A little child thinks before answering and pauses in the middle: a longer quiet is still "speaking".
            // (Shorter than before: the answer itself ends it at once ([wanted]); this is only for other answers.)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1300L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 2000L)
        }
        try { r.startListening(intent) } catch (_: Exception) { finish(emptyList(), false); return }
        main.postDelayed({ if (my == seq) finish(partial, spoke) }, ms)
    }

    private fun finish(heard: List<String>, said: Boolean) {
        val cb = done ?: return
        done = null
        seq++
        release()
        cb(heard, said)
    }

    /** Stops listening; the callback is not called. */
    fun stop() {
        done = null
        seq++
        release()
    }

    private fun release() {
        val r = rec ?: return
        rec = null
        try { r.cancel() } catch (_: Exception) {}
        try { r.destroy() } catch (_: Exception) {}
    }
}
