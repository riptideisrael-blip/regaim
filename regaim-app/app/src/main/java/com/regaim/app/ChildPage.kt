package com.regaim.app

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * The words and choices of a child's page ("הדף של אורי"), kept apart from the screen so they are tested.
 * No Android here (unit-tested).
 */
object ChildPage {

    /** Days until the next birthday (0 = today); null without a yyyy-MM-dd birthday. Feb 29 counts as Feb 28 in other years. */
    fun birthdayInDays(birth: String, today: LocalDate = LocalDate.now()): Int? {
        val p = birth.split("-").mapNotNull { it.trim().toIntOrNull() }
        if (p.size != 3 || p[1] !in 1..12 || p[2] !in 1..31) return null
        fun on(year: Int): LocalDate? = try {
            val ym = java.time.YearMonth.of(year, p[1])
            ym.atDay(minOf(p[2], ym.lengthOfMonth()))
        } catch (_: Exception) { null }
        val thisYear = on(today.year) ?: return null
        val next = if (thisYear.isBefore(today)) on(today.year + 1) ?: return null else thisYear
        return ChronoUnit.DAYS.between(today, next).toInt()
    }

    /** "יום הולדת היום! 🎂", "יום הולדת מחר", "יום הולדת בעוד 47 ימים"; empty when far away (over two months) or unknown. */
    fun birthdayLine(days: Int?): String = when {
        days == null || days > 60 -> ""
        days == 0 -> L.t("יום הולדת היום! 🎂", "Birthday today! 🎂")
        days == 1 -> L.t("יום הולדת מחר", "Birthday tomorrow")
        else -> L.t("יום הולדת בעוד $days ימים", "Birthday in $days days")
    }

    /** The line under the child's name: age, then the birthday when it's close. */
    fun subtitle(age: String, days: Int?): String = listOf(age, birthdayLine(days)).filter { it.isNotBlank() }.joinToString(" · ")

    /** The child's moments (newest first, as given): those where the child's name is among the people in it. */
    fun <T> mine(all: List<T>, name: String, names: (T) -> List<String>): List<T> {
        val n = name.trim()
        if (n.isEmpty()) return emptyList()
        return all.filter { m -> names(m).any { it.trim() == n } }
    }

    /** "128 רגעים · 9 אהובים"; "עוד אין רגעים" when none. */
    fun counts(moments: Int, favorites: Int): String = when (moments) {
        0 -> L.t("עוד אין רגעים", "No moments yet")
        else -> listOf(
            if (moments == 1) L.t("רגע אחד", "1 moment") else L.t("$moments רגעים", "$moments moments"),
            when (favorites) { 0 -> ""; 1 -> L.t("אהוב אחד", "1 favorite"); else -> L.t("$favorites אהובים", "$favorites favorites") },
        ).filter { it.isNotEmpty() }.joinToString(" · ")
    }
}
