package com.regaim.app

import android.content.ContextWrapper
import android.content.SharedPreferences
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

/** Exercises real profile serialization and legacy migration with isolated in-memory preferences. */
class ProfilePersistenceTest {
    private class TestContext : ContextWrapper(null) {
        val preferences = MemoryPreferences()
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = preferences
    }

    @Test fun separateFamiliesRenderOnlyTheirConfiguredData() {
        val first = TestContext()
        val second = TestContext()
        Profile.setFamilyName(first, "משפחה א")
        Profile.setFamilyName(second, "משפחה ב")
        Profile.setParent(first, "משתמש ראשון", "אבא")
        Profile.setParent(second, "משתמש שני", "אני")
        Profile.saveMembers(first, Profile.members(first) + FamilyMember("child-a", "ילד ראשון", "ילד", "2020-01-01"))
        Profile.saveMembers(second, Profile.members(second) + FamilyMember("partner-b", "בן משפחה שני", "בן זוג"))

        assertEquals("משפחת משפחה א", Profile.homeTitle(first))
        assertEquals("משפחת משפחה ב", Profile.homeTitle(second))
        assertEquals(listOf("ילד ראשון"), Profile.kids(first).map { it.name })
        assertTrue(Profile.kids(second).isEmpty())
        assertFalse(Profile.describe(first).contains("בן משפחה שני"))
        assertFalse(Profile.describe(second).contains("ילד ראשון"))
    }

    @Test fun familyWithoutChildrenIsCompleteAndDoesNotGuessSurname() {
        val context = TestContext()
        assertTrue(Profile.isEmpty(context))
        Profile.setParent(context, "שם פרטי שם נוסף", "אני")
        assertFalse(Profile.isEmpty(context))
        assertEquals("הבית שלנו", Profile.homeTitle(context))
        assertTrue(Profile.kids(context).isEmpty())
        Profile.setFamilyName(context, "השם שבחרנו")
        assertEquals("משפחת השם שבחרנו", Profile.homeTitle(context))
    }

    @Test fun legacyChildrenKeepBirthdaysWhenGeneralMembersAreAdded() {
        val context = TestContext()
        context.preferences.edit().putString("kids", "[{\"name\":\"ילד קיים\",\"birth\":\"2021-05-06\"}]").commit()
        val legacy = Profile.members(context).single()
        assertEquals("ילד קיים", legacy.name)
        assertEquals("2021-05-06", legacy.birth)

        Profile.saveMembers(context, listOf(legacy.copy(photo = "/local/child.jpg"), FamilyMember("relative", "קרוב משפחה", "סבא")))
        val persisted = JSONArray(context.preferences.getString("family_members", "[]"))
        assertEquals(2, persisted.length())
        val child = Profile.members(context).single { it.isChild }
        assertEquals(legacy.id, child.id)
        assertEquals("/local/child.jpg", child.photo)
        assertEquals("2021-05-06", Profile.kids(context).single().birth)
    }

    @Test fun legacyChildUpdatesPreservePartnerAndSelfPhoto() {
        val context = TestContext()
        Profile.setParent(context, "משתמש", "אני")
        Profile.setPhoto(context, "/local/self.jpg")
        Profile.saveMembers(context, Profile.members(context) + listOf(
            FamilyMember("partner", "בת זוג", "בת זוג", photo = "/local/partner.jpg"),
            FamilyMember("child", "ילדה", "ילדה", "2022-04-03", "/local/child.jpg"),
        ))
        Profile.saveKids(context, listOf(Kid("ילדה", "2022-04-04")))
        val members = Profile.members(context)
        assertEquals("/local/self.jpg", members.single { it.id == "self" }.photo)
        assertEquals("/local/partner.jpg", members.single { it.id == "partner" }.photo)
        assertEquals("child", members.single { it.isChild }.id)
        assertEquals("/local/child.jpg", members.single { it.isChild }.photo)
        assertEquals("2022-04-04", Profile.kids(context).single().birth)
    }

    @Test fun memberRenameKeepsIdentityAndChildCompatibility() {
        val context = TestContext()
        Profile.saveMembers(context, listOf(FamilyMember("stable-child", "שם קודם", "ילד", "2020-02-02")))
        val original = Profile.members(context).single()
        Profile.saveMembers(context, listOf(original.copy(name = "שם מעודכן", relation = "ילדה")))
        assertEquals("stable-child", Profile.members(context).single().id)
        assertEquals("שם מעודכן", Profile.kids(context).single().name)
        assertEquals("ילדה", JSONArray(context.preferences.getString("family_members", "[]")).getJSONObject(0).getString("relation"))
    }

    private class MemoryPreferences : SharedPreferences {
        private val data = mutableMapOf<String, Any?>()
        override fun getAll(): MutableMap<String, *> = data.toMutableMap()
        override fun getString(key: String?, defValue: String?): String? = data[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            (data[key] as? Set<*>)?.filterIsInstance<String>()?.toMutableSet() ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = data[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = data[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = data[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = data[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = data.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val writes = mutableMapOf<String, Any?>()
            private val removes = mutableSetOf<String>()
            private var clear = false
            private fun put(key: String?, value: Any?): SharedPreferences.Editor { if (key != null) writes[key] = value; return this }
            override fun putString(key: String?, value: String?) = put(key, value)
            override fun putStringSet(key: String?, values: MutableSet<String>?) = put(key, values?.toSet())
            override fun putInt(key: String?, value: Int) = put(key, value)
            override fun putLong(key: String?, value: Long) = put(key, value)
            override fun putFloat(key: String?, value: Float) = put(key, value)
            override fun putBoolean(key: String?, value: Boolean) = put(key, value)
            override fun remove(key: String?): SharedPreferences.Editor { if (key != null) removes.add(key); return this }
            override fun clear(): SharedPreferences.Editor { clear = true; return this }
            override fun commit(): Boolean {
                if (clear) data.clear()
                removes.forEach { data.remove(it) }
                writes.forEach { (key, value) -> if (value == null) data.remove(key) else data[key] = value }
                return true
            }
            override fun apply() { commit() }
        }
    }
}
