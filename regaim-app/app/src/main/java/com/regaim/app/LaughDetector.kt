package com.regaim.app

import android.content.Context
import org.tensorflow.lite.support.audio.TensorAudio
import org.tensorflow.lite.task.audio.classifier.AudioClassifier

/**
 * Wraps Google's YAMNet sound classifier (runs fully on the phone, offline).
 * Input: ~1 second of 16 kHz mono audio. Output: the strongest "moment" sound.
 */
class LaughDetector(context: Context) {

    data class Result(val score: Float, val label: String, val top: String, val top3: String)

    companion object {
        /** Real laughter only. Shouts and yells used to count here too, so a loud toddler saved "moments" with no laugh. */
        private val LAUGH = setOf("Laughter", "Baby laughter", "Giggle", "Belly laugh", "Chuckle, chortle", "Snicker")
        /** Excited shouting: counted only when "happy shouts" is turned on, and must be clearly stronger. */
        private val SHOUT = setOf("Children shouting", "Cheering", "Whoop", "Shout", "Yell")
        /** Sounds that look like laughter to the model but aren't: if one of them is stronger, nothing is saved. */
        private val NOT_LAUGH = setOf("Crying, sobbing", "Baby cry, infant cry", "Screaming", "Whimper", "Babbling", "Wail, moan")
    }

    private val classifier: AudioClassifier = AudioClassifier.createFromFile(context, "yamnet.tflite")
    private val tensor: TensorAudio = classifier.createInputTensorAudio()

    /** Number of samples the model expects (15600 for YAMNet). */
    val requiredSamples: Int = classifier.requiredInputBufferSize.toInt()

    fun classify(window: FloatArray): Result {
        tensor.load(window)
        val output = classifier.classify(tensor)
        // Laughter is spread over several classes (Laughter, Giggle, Baby laughter...),
        // so we add them up instead of taking only the strongest one.
        var laugh = 0f
        var laughBest = 0f
        var laughLabel = ""
        var sing = 0f
        var singBest = 0f
        var singLabel = ""
        var music = 0f
        val categories = output.firstOrNull()?.categories ?: emptyList()
        for (c in categories) {
            if (c.label == "Music") music = c.score
            if (!Labels.TRIGGERS.containsKey(c.label) || c.label in Labels.VISUAL) continue
            if (c.label in Labels.SINGING) {
                sing += c.score
                if (c.score > singBest) { singBest = c.score; singLabel = c.label }
            } else if (c.label in LAUGH) {
                laugh += c.score
                if (c.score > laughBest) { laughBest = c.score; laughLabel = c.label }
            } else if (c.label in SHOUT && Status.shoutOn) {
                // Half weight: a shout has to be much clearer than a laugh to save a moment.
                laugh += c.score * 0.5f
                if (c.score * 0.5f > laughBest) { laughBest = c.score * 0.5f; laughLabel = c.label }
            }
        }
        // Crying, screaming or baby babbling stronger than the "laugh": not a happy moment.
        // Babbling often comes with a toddler's real giggle, so it must be clearly stronger to cancel it;
        // crying, screaming and whimpers cancel as soon as they're stronger.
        val cry = categories.filter { it.label in NOT_LAUGH && it.label != "Babbling" }.maxOfOrNull { it.score } ?: 0f
        val babble = categories.firstOrNull { it.label == "Babbling" }?.score ?: 0f
        if (cry > laugh || babble > laugh * 1.6f) { laugh = 0f; laughLabel = "" }
        if (!Status.laughOn) { laugh = 0f; laughLabel = "" }
        // Singing counts only when it's people singing, not a song playing from a speaker.
        if (!Status.singOn || music > sing) { sing = 0f; singLabel = "" }
        val group = if (sing > laugh) sing else laugh
        val bestLabel = if (sing > laugh) singLabel else laughLabel
        val sorted = categories.sortedByDescending { it.score }
        val top = sorted.firstOrNull()?.label ?: ""
        val top3 = sorted.take(3).joinToString(" · ") { "${Labels.he(it.label)} ${(it.score * 100).toInt()}%" }
        return Result(group.coerceAtMost(1f), bestLabel, top, top3)
    }

    /** Raw sums of the class groups the moment finder needs (for imported videos), before any calibration. */
    class Groups(val laugh: Float, val cheer: Float, val singing: Float, val child: Float, val cry: Float, val music: Float)

    fun groups(window: FloatArray): Groups {
        tensor.load(window)
        val categories = classifier.classify(tensor).firstOrNull()?.categories ?: emptyList()
        var laugh = 0f; var cheer = 0f; var sing = 0f; var child = 0f; var cry = 0f; var music = 0f
        for (c in categories) when (c.label) {
            in LAUGH -> laugh += c.score
            "Cheering", "Applause", "Clapping", "Whoop" -> cheer += c.score
            in Labels.SINGING -> sing += c.score
            "Children shouting", "Children playing", "Babbling", "Squeal" -> child += c.score
            "Child speech, kid speaking" -> child += c.score * 0.5f
            "Crying, sobbing", "Baby cry, infant cry", "Whimper", "Wail, moan" -> cry += c.score
            "Screaming" -> cry += c.score * 0.5f
            "Music" -> music = maxOf(music, c.score)
        }
        return Groups(laugh, cheer, sing, child, cry, music)
    }

    fun close() {
        try {
            classifier.close()
        } catch (_: Exception) {
        }
    }
}
