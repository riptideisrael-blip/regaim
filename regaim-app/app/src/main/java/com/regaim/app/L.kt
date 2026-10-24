package com.regaim.app

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

/**
 * App language (Hebrew / English). Every user-visible text is written as `L.t("עברית", "English")` at the call site,
 * so both languages live side by side and string templates work naturally. Data stored on disk (categories, labels,
 * AI keys) stays in Hebrew; use the display helpers below to show it.
 */
object L {
    const val HE = "he"
    const val EN = "en"
    private const val PREF = "app_lang"

    @Volatile var lang: String = HE
        private set

    val isEn: Boolean get() = lang == EN
    val locale: Locale get() = if (isEn) Locale.ENGLISH else Locale("he", "IL")
    /** For AI prompts: the language the answer should be written in. */
    val aiLanguage: String get() = if (isEn) "English" else "Hebrew (עברית)"

    fun t(he: String, en: String): String = if (isEn) en else he

    fun init(ctx: Context) {
        val saved = Settings.prefs(ctx).getString(PREF, null)
        lang = saved ?: if (Locale.getDefault().language in listOf("he", "iw")) HE else EN
        apply()
    }

    fun set(ctx: Context, newLang: String) {
        if (newLang != HE && newLang != EN) return
        val changed = newLang != lang
        Settings.prefs(ctx).edit().putString(PREF, newLang).apply()
        lang = newLang
        apply()
        // Stories and recent descriptions were written in the old language: write them again in the new one.
        if (changed) try { MemoryStore.relanguage(ctx) } catch (_: Exception) { }
    }

    /** Switches resources (XML texts) and layout direction (RTL for Hebrew, LTR for English); open screens are recreated. */
    private fun apply() {
        try { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(lang)) } catch (_: Exception) { }
    }

    // ---------- display of data that is stored in Hebrew ----------

    private val CATEGORY_EN = mapOf(
        "צחוק" to "Laughter", "משחק" to "Play", "אוכל" to "Food", "בחוץ" to "Outdoors", "אמבטיה" to "Bath",
        "שינה" to "Sleep", "משפחה" to "Family", "אבן דרך" to "Milestone", "אחר" to "Other",
        "הכל" to "All", "מועדפים" to "Favorites",
    )

    /** A category name (stored in Hebrew) shown in the current language. */
    fun category(c: String): String = if (isEn) CATEGORY_EN[c] ?: c else c
}
