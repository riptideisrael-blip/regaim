package com.regaim.app

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Answers from the stored record only. This does not inspect or infer what happened in a video. */
object MemoryFacts {
    /** Null means this question needs more than the record's explicit metadata. */
    fun answer(memory: Memory, question: String): String? {
        val q = question.trim().lowercase(Locale.ROOT)
        if (q.isBlank()) return null
        return when {
            listOf("באיזו שעה", "באיזה שעה", "מה השעה", "what time").any { it in q } ->
                if (memory.time <= 0) L.t("לא נשמרה שעה לזיכרון הזה.", "No time was saved for this memory.")
                else if (memory.timeSource == "imported") L.t("הזיכרון נוסף בשעה ${format(memory.time, "HH:mm")}. שעת הצילום המקורית לא ידועה.", "This memory was added at ${format(memory.time, "HH:mm")}. The original capture time isn't known.")
                else L.t("לפי הזמן שנשמר, הזיכרון תועד בשעה ${format(memory.time, "HH:mm")}.", "Based on the saved time, this memory was captured at ${format(memory.time, "HH:mm")}.")

            listOf("באיזה יום", "באיזה תאריך", "מה התאריך", "מתי צולם", "מתי זה צולם", "מתי תועד", "מתי זה קרה", "when was", "what date").any { it in q } ->
                if (memory.time <= 0) L.t("לא נשמר תאריך לזיכרון הזה.", "No date was saved for this memory.")
                else if (memory.timeSource == "imported") L.t("הזיכרון נוסף ב־${format(memory.time, "EEEE, d.M.yyyy")} בשעה ${format(memory.time, "HH:mm")}. תאריך הצילום המקורי לא ידוע.", "This memory was added on ${format(memory.time, "EEEE, MMMM d, yyyy")} at ${format(memory.time, "HH:mm")}. The original capture date isn't known.")
                else L.t("לפי הזמן שנשמר, הזיכרון תועד ב־${format(memory.time, "EEEE, d.M.yyyy")} בשעה ${format(memory.time, "HH:mm")}.", "Based on the saved time, this memory was captured on ${format(memory.time, "EEEE, MMMM d, yyyy")} at ${format(memory.time, "HH:mm")}.")

            listOf("איפה זה", "איפה צולם", "איפה היינו", "באיזה מקום", "מה המיקום", "איפה זה היה", "where was", "where were").any { it in q } ->
                if (memory.place.isNotBlank()) L.t("המקום שנשמר לזיכרון הזה: ${memory.place}.", "The place saved for this memory: ${memory.place}.")
                else L.t("לא נשמר מקום לזיכרון הזה, אז איני יודעת איפה הוא צולם.", "No place was saved for this memory, so I don't know where it was taken.")

            listOf("מי היה שם", "מי היה ברגע", "מי מופיע", "מי נמצא", "מי השתתף", "מי היו שם", "who was there", "who is in").any { it in q } -> {
                val identified = people(memory.people).filterNot { it in people(memory.hiddenPeople) }
                val added = people(memory.extraPeople).filterNot { it in people(memory.hiddenPeople) || it in identified }
                val pets = people(memory.pets).filterNot { it in people(memory.hiddenPeople) }
                if (identified.isEmpty() && added.isEmpty() && pets.isEmpty()) {
                    L.t("לא נשמרו שמות של משתתפים בזיכרון הזה. איני יכולה לדעת מי היה שם.", "No names were saved for this memory, so I can't tell who was there.")
                } else buildString {
                    if (identified.isNotEmpty()) append(L.t("שמות מזיהוי הפנים: ${identified.joinToString(", ")}. הזיהוי עשוי לטעות.", "Names from face recognition: ${identified.joinToString(", ")}. Recognition can be wrong."))
                    if (pets.isNotEmpty()) {
                        if (isNotEmpty()) append("\n")
                        append(L.t("חיות מחמד שזוהו: ${pets.joinToString(", ")}. הזיהוי עשוי לטעות.", "Pets recognized: ${pets.joinToString(", ")}. Recognition can be wrong."))
                    }
                    if (added.isNotEmpty()) {
                        if (isNotEmpty()) append("\n")
                        append(L.t("שמות שהוספת לזיכרון: ${added.joinToString(", ")}.", "Names you added: ${added.joinToString(", ")}."))
                    }
                }
            }

            listOf("מה קרה ברגע", "מה קרה בזיכרון", "מה קרה כאן", "מה קרה פה", "what happened here").any { it in q } ->
                if (memory.description.isNotBlank()) L.t("לפי התיאור שנשמר לזיכרון: ${memory.description}", "From the saved description: ${memory.description}")
                else L.t("עדיין אין תיאור לזיכרון הזה. מהפרטים שנשמרו איני יודעת לומר מה קרה בו.", "There's no description for this memory yet, so I can't say what happened from the saved details.")

            else -> null
        }
    }

    private fun people(value: String) = value.split(",").map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    private fun format(time: Long, pattern: String) = SimpleDateFormat(pattern, L.locale).format(Date(time))
}
