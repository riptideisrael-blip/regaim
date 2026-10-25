package com.regaim.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.UUID

/** All user settings live in one prefs file; load() copies them into the live Status used by the recorder. */
object Settings {
    fun prefs(ctx: Context) = ctx.getSharedPreferences("regaim", Context.MODE_PRIVATE)

    val BEFORE_OPTIONS = listOf(10, 15, 20)
    val AFTER_OPTIONS = listOf(10, 15, 20)

    fun load(ctx: Context) {
        val p = prefs(ctx)
        Status.zoom = p.getFloat("zoom", 0.6f)
        // New key: the old default (15%) saved too many moments without a laugh; 30% is the new starting point.
        Status.threshold = p.getFloat("threshold3", 0.30f)
        Status.wordsOn = p.getBoolean("words_on", true)
        Status.phrases = Phrases.parse(p.getString("phrases", null) ?: Phrases.DEFAULT.joinToString(", "))
        Status.facesOn = p.getBoolean("faces_on", true)
        Status.laughOn = p.getBoolean("laugh_on", true)
        Status.shoutOn = p.getBoolean("shout_on", false)
        Status.gapMin = p.getInt("gap_min", 3)
        Status.buzzOn = p.getBoolean("buzz_on", true)
        Status.hugOn = p.getBoolean("hug_on", true)
        Status.singOn = p.getBoolean("sing_on", true)
        Status.loveOn = p.getBoolean("love_on", true)
        ClipSaver.PRE_US = p.getInt("before_s", 15).coerceIn(5, 20) * 1_000_000L
        ClipSaver.POST_US = p.getInt("after_s", 15).coerceIn(5, 20) * 1_000_000L
    }

    fun putBool(ctx: Context, key: String, v: Boolean) = prefs(ctx).edit().putBoolean(key, v).apply()
    fun putInt(ctx: Context, key: String, v: Int) = prefs(ctx).edit().putInt(key, v).apply()
    fun putFloat(ctx: Context, key: String, v: Float) = prefs(ctx).edit().putFloat(key, v).apply()
    fun putString(ctx: Context, key: String, v: String) = prefs(ctx).edit().putString(key, v).apply()
}

/** A child in the family profile. birth is yyyy-MM-dd, or empty if not given. */
data class Kid(val name: String, val birth: String)

/** A person configured by the family; naming them does not claim that their face was recognized. */
data class FamilyMember(
    val id: String,
    val name: String,
    val relation: String,
    val birth: String = "",
    val photo: String = "",
) {
    val isChild: Boolean get() = relation == "ילד" || relation == "ילדה"
    /** "dog", "cat" or "pet" (any other animal) for a pet; null for a person. */
    val petKind: String? get() = when (relation.trim()) { "כלב", "כלבה" -> "dog"; "חתול", "חתולה" -> "cat"; "חיית מחמד" -> "pet"; else -> null }
    val isPet: Boolean get() = petKind != null
}

/** The family profile: who is the parent, who are the kids and how old they are. Used by the AI. */
object Profile {
    private fun p(ctx: Context) = Settings.prefs(ctx)

    fun parentName(ctx: Context) = p(ctx).getString("parent_name", "") ?: ""
    fun role(ctx: Context) = p(ctx).getString("parent_role", "") ?: ""
    fun setParent(ctx: Context, name: String, role: String) =
        p(ctx).edit().putString("parent_name", name.trim()).putString("parent_role", role).apply()

    private fun legacyKids(ctx: Context): List<Kid> = try {
        val arr = JSONArray(p(ctx).getString("kids", "[]") ?: "[]")
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Kid(o.optString("name"), o.optString("birth"))
        }.filter { it.name.isNotBlank() }
    } catch (_: Exception) {
        emptyList()
    }

    /** Includes the current user, optional partner and any relatives; legacy child profiles migrate lazily. */
    fun members(ctx: Context): List<FamilyMember> {
        val pref = p(ctx)
        val others = if (pref.contains("family_members")) try {
            val arr = JSONArray(pref.getString("family_members", "[]") ?: "[]")
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                FamilyMember(o.optString("id"), o.optString("name"), o.optString("relation"), o.optString("birth"), o.optString("photo"))
            }.filter { it.id != "self" && it.name.isNotBlank() }
        } catch (_: Exception) { emptyList() } else legacyKids(ctx).mapIndexed { i, kid ->
            FamilyMember("legacy_child_$i", kid.name, "ילד", kid.birth)
        }
        val me = parentName(ctx).takeIf { it.isNotBlank() }?.let {
            FamilyMember("self", it, role(ctx).ifEmpty { "אני" }, photo = pref.getString("profile_photo", "") ?: "")
        }
        return listOfNotNull(me) + others
    }

    fun saveMembers(ctx: Context, members: List<FamilyMember>) {
        members.firstOrNull { it.id == "self" }?.let {
            setParent(ctx, it.name, it.relation)
            setPhoto(ctx, it.photo)
        }
        val arr = JSONArray()
        val clean = members.filter { it.id != "self" && it.name.isNotBlank() }
        clean.forEach { m ->
            arr.put(JSONObject().put("id", m.id.ifBlank { UUID.randomUUID().toString() })
                .put("name", m.name.trim()).put("relation", m.relation).put("birth", m.birth).put("photo", m.photo))
        }
        val children = JSONArray()
        clean.filter { it.isChild }.forEach { children.put(JSONObject().put("name", it.name.trim()).put("birth", it.birth)) }
        p(ctx).edit().putString("family_members", arr.toString()).putString("kids", children.toString()).apply()
    }

    /** Names compared the way people read them: spaces and letter case don't make two names different. */
    fun sameName(a: String, b: String) = norm(a) == norm(b)
    private fun norm(s: String) = s.trim().replace(Regex("\\s+"), " ").lowercase()

    /** Someone else in the family profile already called [name] (names are how moments find people). */
    fun nameTaken(ctx: Context, name: String, exceptId: String): FamilyMember? =
        members(ctx).firstOrNull { it.id != exceptId && sameName(it.name, name) }

    fun photo(ctx: Context) = p(ctx).getString("profile_photo", "") ?: ""
    fun setPhoto(ctx: Context, path: String) = p(ctx).edit().putString("profile_photo", path).apply()

    fun kids(ctx: Context): List<Kid> = if (p(ctx).contains("family_members"))
        members(ctx).filter { it.isChild }.map { Kid(it.name, it.birth) } else legacyKids(ctx)

    fun saveKids(ctx: Context, kids: List<Kid>) {
        val before = members(ctx)
        val oldChildren = before.filter { it.isChild }
        val children = kids.map { k ->
            oldChildren.firstOrNull { it.name == k.name }?.copy(birth = k.birth)
                ?: FamilyMember(UUID.randomUUID().toString(), k.name, "ילד", k.birth)
        }
        saveMembers(ctx, before.filterNot { it.isChild } + children)
    }

    fun isEmpty(ctx: Context) = familyName(ctx).isEmpty() && parentName(ctx).isEmpty() && members(ctx).isEmpty()

    /** Every name in the family profile (for checking the AI doesn't place someone who wasn't recognized). */
    fun names(ctx: Context): Set<String> = (members(ctx).map { it.name.trim() } + parentName(ctx).trim()).filter { it.isNotEmpty() }.toSet()

    fun familyName(ctx: Context) = p(ctx).getString("family_name", "") ?: ""
    fun setFamilyName(ctx: Context, name: String) = p(ctx).edit().putString("family_name", name.trim()).apply()

    /** Use only the family name the user chose; never guess a surname from the account name. */
    fun homeTitle(ctx: Context): String {
        val fam = familyName(ctx)
        return if (fam.isEmpty()) L.t("הבית שלנו", "Our home")
        else if (fam.startsWith("משפחת ")) (if (L.isEn) "The ${fam.removePrefix("משפחת ").trim()} family" else fam)
        else L.t("משפחת $fam", "The $fam family")
    }

    /** Age in whole months from a yyyy-MM-dd birthday; null if unknown. */
    fun ageMonths(birth: String, now: Long = System.currentTimeMillis()): Int? {
        val parts = birth.split("-").mapNotNull { it.toIntOrNull() }
        if (parts.size != 3) return null
        val b = Calendar.getInstance().apply { set(parts[0], parts[1] - 1, parts[2], 0, 0, 0) }
        val n = Calendar.getInstance().apply { timeInMillis = now }
        var months = (n.get(Calendar.YEAR) - b.get(Calendar.YEAR)) * 12 + (n.get(Calendar.MONTH) - b.get(Calendar.MONTH))
        if (n.get(Calendar.DAY_OF_MONTH) < b.get(Calendar.DAY_OF_MONTH)) months--
        return months.takeIf { it >= 0 }
    }

    /** "גיל 3", "גיל 8 חודשים", "גיל 2 וחצי"; empty if no birthday. */
    fun ageText(birth: String, now: Long = System.currentTimeMillis()): String {
        val parts = birth.split("-").mapNotNull { it.toIntOrNull() }
        if (parts.size != 3) return ""
        val b = Calendar.getInstance().apply { set(parts[0], parts[1] - 1, parts[2], 0, 0, 0) }
        val n = Calendar.getInstance().apply { timeInMillis = now }
        var months = (n.get(Calendar.YEAR) - b.get(Calendar.YEAR)) * 12 + (n.get(Calendar.MONTH) - b.get(Calendar.MONTH))
        if (n.get(Calendar.DAY_OF_MONTH) < b.get(Calendar.DAY_OF_MONTH)) months--
        if (months < 0) return ""
        return when {
            months == 0 -> L.t("פחות מחודש", "Under a month")
            months == 1 -> L.t("גיל חודש", "1 month old")
            months < 24 && months % 12 != 0 && months < 18 -> L.t("גיל $months חודשים", "$months months old")
            months % 12 in 5..7 -> L.t("גיל ${months / 12} וחצי", "${months / 12}½ years old")
            else -> {
                val years = if (months % 12 >= 8) months / 12 + 1 else months / 12
                L.t("גיל $years", if (years == 1) "1 year old" else "$years years old")
            }
        }
    }

    private val RELATION_EN = mapOf(
        "אני" to "Me", "אבא" to "Dad", "אמא" to "Mom", "סבא" to "Grandpa", "סבתא" to "Grandma", "אחר" to "Other",
        "בן זוג" to "Husband", "בת זוג" to "Wife", "ילד" to "Son", "ילדה" to "Daughter",
        "אח" to "Brother", "אחות" to "Sister", "בן משפחה" to "Male relative", "בת משפחה" to "Female relative",
        "כלב" to "Dog", "חתול" to "Cat", "חיית מחמד" to "Pet",
    )

    /** The relation a new member starts with: "child" when added as a child (so it counts as one everywhere). */
    fun newMemberRelation(kid: Boolean): String = if (kid) "ילד" else "בן משפחה"

    /** A role / relation (stored in Hebrew) shown in the current language. */
    fun relationLabel(r: String): String = if (L.isEn) RELATION_EN[r] ?: r else r

    /** One line for AI prompts, at the time of a given moment. Empty if there's no profile. */
    fun describe(ctx: Context, at: Long = System.currentTimeMillis()): String {
        val name = parentName(ctx)
        val role = role(ctx)
        val kids = kids(ctx)
        val relatives = members(ctx).filter { it.id != "self" && !it.isChild && !it.isPet }
        val pets = members(ctx).filter { it.isPet }
        if (name.isEmpty() && role.isEmpty() && kids.isEmpty() && relatives.isEmpty() && pets.isEmpty()) return ""
        return buildString {
            append(L.t("המשפחה: ", "Family: "))
            if (name.isNotEmpty() || role.isNotEmpty()) {
                append(L.t("המשתמש שמצלם הוא ", "The user filming is "))
                append(listOf(name, if (role.isNotEmpty()) "(${relationLabel(role)})" else "").filter { it.isNotEmpty() }.joinToString(" "))
                append(". ")
            }
            if (kids.isNotEmpty()) {
                append(L.t("הילדים: ", "Kids: "))
                append(kids.joinToString(", ") { k ->
                    val age = ageText(k.birth, at)
                    if (age.isEmpty()) k.name else "${k.name} ($age)"
                })
                append(".")
            }
            if (relatives.isNotEmpty()) {
                append(L.t(" בני המשפחה שהוגדרו: ", " Family members in the profile: "))
                append(relatives.joinToString(", ") { "${it.name}${if (it.relation.isNotEmpty()) " (${relationLabel(it.relation)})" else ""}" })
                append(L.t(". אלה פרטי הפרופיל, ולא הוכחה שמישהו הופיע ברגע.", ". These are profile details, not proof that anyone appeared in the moment."))
            }
            if (pets.isNotEmpty()) {
                append(L.t(" חיות המחמד: ", " Pets: "))
                append(pets.joinToString(", ") { "${it.name} (${relationLabel(it.relation)})" })
                append(L.t(". אל תניח שהן בקליפ אם לא זוהו.", ". Don't assume they're in the clip unless recognized."))
            }
        }
    }
}
