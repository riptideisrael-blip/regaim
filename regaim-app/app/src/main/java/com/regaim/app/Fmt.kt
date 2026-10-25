package com.regaim.app

import android.content.Context
import android.text.format.DateUtils
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Human, Hebrew wording for memories: "היום · 16:24", "6 באוקטובר 2025", "48 שניות". No file names, no tech. */
object Fmt {
    private val MONTHS = arrayOf("בינואר", "בפברואר", "במרץ", "באפריל", "במאי", "ביוני", "ביולי", "באוגוסט", "בספטמבר", "באוקטובר", "בנובמבר", "בדצמבר")
    private val DAYS = arrayOf("", "ראשון", "שני", "שלישי", "רביעי", "חמישי", "שישי", "שבת")

    fun fullDate(t: Long): String {
        if (L.isEn) return SimpleDateFormat("MMMM d, yyyy", Locale.ENGLISH).format(Date(t))
        val c = Calendar.getInstance().apply { timeInMillis = t }
        return "${c.get(Calendar.DAY_OF_MONTH)} ${MONTHS[c.get(Calendar.MONTH)]} ${c.get(Calendar.YEAR)}"
    }

    fun day(t: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = t }
        val now = Calendar.getInstance()
        val ageMs = System.currentTimeMillis() - t
        return when {
            DateUtils.isToday(t) -> L.t("היום", "Today")
            DateUtils.isToday(t + DateUtils.DAY_IN_MILLIS) -> L.t("אתמול", "Yesterday")
            ageMs in 0 until 6 * DateUtils.DAY_IN_MILLIS -> if (L.isEn) SimpleDateFormat("EEEE", Locale.ENGLISH).format(Date(t)) else "יום ${DAYS[c.get(Calendar.DAY_OF_WEEK)]}"
            c.get(Calendar.YEAR) == now.get(Calendar.YEAR) -> if (L.isEn) SimpleDateFormat("MMMM d", Locale.ENGLISH).format(Date(t)) else "${c.get(Calendar.DAY_OF_MONTH)} ${MONTHS[c.get(Calendar.MONTH)]}"
            else -> fullDate(t)
        }
    }

    private val MONTH_NAMES = arrayOf("ינואר", "פברואר", "מרץ", "אפריל", "מאי", "יוני", "יולי", "אוגוסט", "ספטמבר", "אוקטובר", "נובמבר", "דצמבר")

    /** "אוקטובר 2026" */
    fun month(t: Long): String {
        if (L.isEn) return SimpleDateFormat("MMMM yyyy", Locale.ENGLISH).format(Date(t))
        val c = Calendar.getInstance().apply { timeInMillis = t }
        return "${MONTH_NAMES[c.get(Calendar.MONTH)]} ${c.get(Calendar.YEAR)}"
    }

    /** "2026-10" */
    fun monthKey(t: Long): String = SimpleDateFormat("yyyy-MM", Locale.US).format(Date(t))

    fun time(t: Long): String = SimpleDateFormat("HH:mm", Locale.US).format(Date(t))
    fun dayTime(t: Long) = "${day(t)} · ${time(t)}"

    fun duration(ms: Long): String {
        val s = (ms / 1000).toInt()
        return if (s < 60) L.t("$s שניות", "$s sec") else String.format(Locale.US, L.t("%d:%02d דקות", "%d:%02d min"), s / 60, s % 60)
    }

    fun shortDuration(ms: Long): String {
        val s = (ms / 1000).toInt()
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60)
    }

    /** The AI title, or a warm fallback from what caught the moment. */
    fun title(m: Memory): String {
        if (m.title.isNotBlank()) return m.title
        return when {
            m.label == "manual" -> L.t("רגע ששמרתם", "A moment you saved")
            m.label.startsWith("״") -> L.t("רגע של ${m.label}", "${m.label} moment")
            m.label == "Approach" -> L.t("ריצה אליך", "Running to you")
            m.label == "Smile" -> L.t("חיוך גדול", "Big smile")
            else -> L.t("רגע של ${Labels.he(m.label)}", "${Labels.he(m.label)} moment")
        }
    }

    /** First part of the address: "רחוב הגפן 5, באר שבע" → "רחוב הגפן 5". */
    fun place(m: Memory): String = m.place.split(",").firstOrNull()?.trim().orEmpty()

    fun names(m: Memory): List<String> = m.allPeople.split(",").map { it.trim() }.filter { it.isNotEmpty() }

    /** "אורי · נועם · רחוב הגפן" */
    fun peopleLine(m: Memory): String = (names(m) + listOf(place(m))).filter { it.isNotEmpty() }.joinToString(" · ")

    fun processing(ctx: Context, m: Memory) = !m.analyzed && ClaudeClient.hasKey(ctx)

    val CATEGORY_EMOJI = mapOf(
        "צחוק" to "😂", "משחק" to "🎮", "אוכל" to "🍽", "בחוץ" to "🌳", "אמבטיה" to "🛁",
        "שינה" to "😴", "משפחה" to "👨‍👩‍👦", "אבן דרך" to "⭐", "אחר" to "✨",
    )
}
