package com.regaim.app

/**
 * The "never say" list, checked on every AI text before it reaches the family, and used by the lab's AI test:
 *  - speech that wasn't heard (we never send sound or a transcript),
 *  - a family member who wasn't recognized in this moment,
 *  - "the first time" when the parent didn't mark a milestone,
 *  - what happened at the end of the video (the AI saw 3 sampled frames, not the end).
 * Pure text rules, unit tested; a sentence that breaks one is removed.
 */
object HonestyRules {
    enum class Kind { SPEECH, UNRECOGNIZED_PERSON, FIRST_TIME, VIDEO_END }

    class Context(
        /** Names recognized or added for this moment (allowed). */
        val recognized: Set<String>,
        /** Every name in the family profile (a profile name not recognized here must not appear). */
        val profileNames: Set<String>,
        val hasTranscript: Boolean = false,
        val milestoneMarked: Boolean = false,
    )

    class Violation(val kind: Kind, val sentence: String)

    private val speechVerb = Regex("(אמר|אמרה|אומר|אומרת|אומרים|קרא|קראה|צעק|צעקה|לחש|לחשה|שאל|שאלה|said|says|saying|shouted|whispered|asked|told)\\s*[:,]?\\s*[\"״“”'׳]", RegexOption.IGNORE_CASE)
    private val quoted = Regex("[\"״“”]([^\"״“”]{3,})[\"״“”]")
    private val firstTime = Regex("(פעם ראשונה|בפעם הראשונה|לראשונה|הצעד(ים)? הראשו(ן|נים)|המילה הראשונה|first time|for the first time|first steps?|first words?)", RegexOption.IGNORE_CASE)
    private val firstRecorded = Regex("(שתועד|שתועדה|שנשמר|שנשמרה|שצולם|recorded|captured|saved)", RegexOption.IGNORE_CASE)
    private val videoEnd = Regex("(בסוף הסרטון|בסוף הקליפ|בסופו של הסרטון|לקראת הסוף|at the end of the (video|clip)|by the end of the (video|clip))", RegexOption.IGNORE_CASE)

    fun sentences(text: String): List<String> =
        Regex("[^.!?\\n]+[.!?]*").findAll(text).map { it.value.trim() }.filter { it.isNotEmpty() }.toList()

    fun check(text: String, ctx: Context): List<Violation> {
        val out = ArrayList<Violation>()
        for (s in sentences(text)) {
            if (!ctx.hasTranscript && (speechVerb.containsMatchIn(s) || quoted.findAll(s).any { it.groupValues[1].trim().contains(' ') })) out.add(Violation(Kind.SPEECH, s))
            if (!ctx.milestoneMarked) {
                val m = firstTime.find(s)
                if (m != null && !firstRecorded.containsMatchIn(s.substring(m.range.last + 1).take(20))) out.add(Violation(Kind.FIRST_TIME, s))
            }
            if (videoEnd.containsMatchIn(s)) out.add(Violation(Kind.VIDEO_END, s))
            for (name in ctx.profileNames) {
                if (name.isBlank() || ctx.recognized.any { it.equals(name, ignoreCase = true) }) continue
                if (mentions(s, name)) { out.add(Violation(Kind.UNRECOGNIZED_PERSON, s)); break }
            }
        }
        return out
    }

    /** [name] as a word, also with a Hebrew one-letter prefix (ו, ה, ב, ל, מ, ש, כ). */
    fun mentions(text: String, name: String): Boolean =
        Regex("(?<![\\p{L}\\p{N}])[והבלמשכ]?${Regex.escape(name)}(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE).containsMatchIn(text)

    /** The text without the sentences that break a rule (empty if nothing honest is left). */
    fun clean(text: String, ctx: Context): String {
        val bad = check(text, ctx).map { it.sentence }.toSet()
        if (bad.isEmpty()) return text
        return sentences(text).filter { it !in bad }.joinToString(" ").trim()
    }
}
