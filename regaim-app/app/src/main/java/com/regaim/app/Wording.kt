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

    /** How sure the detector is, in words a parent understands (instead of "73%"). */
    fun sure(score: Float): String = when {
        score >= 0.85f -> L.t("כמעט בטוח", "almost sure")
        score >= 0.6f -> L.t("די בטוח", "fairly sure")
        else -> L.t("אולי", "maybe")
    }

    /** The one-time "your moments aren't backed up" card: once there are moments, only if no copy goes anywhere yet. */
    fun showBackupTip(hasMoments: Boolean, copiesToGallery: Boolean, answered: Boolean): Boolean = hasMoments && !copiesToGallery && !answered

    /**
     * A line of the grandparents' digest: the moment's title, with who is in it when the app knows (recognized faces
     * or names the parent added) and the title doesn't already say so: "רוקדים ביחד בסלון · נעם ואבא".
     */
    fun digestLine(title: String, names: List<String>): String {
        val missing = names.map { it.trim() }.filter { it.isNotEmpty() && !title.contains(it) }.distinct()
        if (missing.isEmpty()) return title
        val who = if (missing.size == 1) missing[0] else missing.dropLast(1).joinToString(", ") + L.t(" ו", " and ") + missing.last()
        return "$title · $who"
    }

    /** A family's name in the app's language: "The X family" ↔ "משפחת X" (a name saved in the other language). */
    fun familyName(saved: String): String {
        val n = saved.trim()
        val en = Regex("^The (.+) family$", RegexOption.IGNORE_CASE).find(n)?.groupValues?.get(1)?.trim()
        val he = if (n.startsWith("משפחת ")) n.removePrefix("משפחת ").trim() else null
        return when {
            en != null -> L.t("משפחת $en", "The $en family")
            he != null -> L.t("משפחת $he", "The $he family")
            else -> n
        }
    }

    /** A circle member's relation as the server has it: a placeholder ("בן/בת") says what to do instead. */
    fun memberRelation(saved: String, label: String): String =
        if (saved.isBlank() || saved.trim() in setOf("בן/בת", "בן משפחה", "בת משפחה", "member")) L.t("נגיעה לקישור", "Tap to link") else label

    /** The "new from the family" notification: title and line, for (author, title) of the new posts, newest first. */
    fun familyNews(news: List<Pair<String, String>>): Pair<String, String> {
        val (author, title) = news.first()
        val who = author.ifBlank { L.t("מישהו מהמשפחה", "Someone in the family") }
        return if (news.size == 1) L.t("$who שיתף/ה רגע חדש", "$who shared a new moment") to title.ifBlank { L.t("לחצו כדי לראות", "Tap to see it") }
        else L.t("${news.size} רגעים חדשים במשפחה", "${news.size} new family moments") to L.t("האחרון מ$who", "The latest from $who")
    }

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
