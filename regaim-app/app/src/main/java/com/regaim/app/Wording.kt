package com.regaim.app

/**
 * Words the app says to parents (and to screen readers), kept in one place so they are plain and testable.
 * No Android here (unit-tested).
 */
object Wording {
    /** The capture button, read by a screen reader: what a tap does right now. */
    fun shutter(videoMode: Boolean, recording: Boolean): String = when {
        recording -> L.t("עצירת ההקלטה", "Stop recording")
        videoMode -> L.t("התחלת הקלטה", "Start recording")
        else -> L.t("צילום תמונה", "Take a photo")
    }

    /** A two-state button, read with its state ("Microphone: off"). */
    fun state(name: String, on: Boolean, onWord: String = L.t("פועל", "on"), offWord: String = L.t("כבוי", "off")) =
        "$name: ${if (on) onWord else offWord}"

    /** Developer words that a parent should never see. */
    private val TECH = Regex("(?i)supabase|\\bsql\\b|\\.sql|edge function|anonymous sign-?ins|debug|\\bhttp\\s*\\d{3}|\\(\\d{3}\\)|\\bjson\\b|\\bapi\\b|stack ?trace|exception")

    fun technical(text: String?): Boolean = text != null && TECH.containsMatchIn(text)

    /**
     * What to show a parent for a failure: the message itself when it is plain words, otherwise [fallback].
     * The admin always sees the real message (it helps fix things).
     */
    fun forParent(raw: String?, admin: Boolean, fallback: String = L.t("השירות לא זמין כרגע. נסו שוב בעוד כמה דקות.", "This isn't available right now. Try again in a few minutes.")): String =
        when {
            raw.isNullOrBlank() -> fallback
            admin -> raw
            technical(raw) -> fallback
            else -> raw
        }
}
