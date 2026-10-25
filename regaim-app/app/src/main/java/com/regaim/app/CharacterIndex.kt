package com.regaim.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A family member drawn as a book character and approved by the parent (kept for the next books). */
data class SavedCharacter(
    val id: String,
    /** The family member it is (profile member id, "self" for the parent using the app). */
    val member: String,
    val name: String,
    /** The picture, in the characters folder. */
    val file: String,
    val created: Long,
    /** The book it was approved in. */
    val book: String,
)

/**
 * The list of saved characters on disk (pure Kotlin, tested on the computer). The list is written all-or-nothing and
 * a second copy is kept; if the list can't be read, the copy is used, and if neither can be read nothing is
 * written over it (better to save no new character than to lose all the old ones).
 */
internal class CharacterIndex(private val dir: File) {
    private val main get() = File(dir, "characters.json")
    private val backup get() = File(dir, "characters.json.bak")

    /** The saved list (only characters whose picture still exists), or null when the list exists but can't be read. */
    fun load(): List<SavedCharacter>? {
        val first = SafeFiles.readArray(main)
        if (first is SafeFiles.Read.Ok) return parse(first.value)
        val second = SafeFiles.readArray(backup)
        if (second is SafeFiles.Read.Ok) {
            val list = parse(second.value)
            SafeFiles.write(main, second.value.toString()) // repair the main list from the copy
            return list
        }
        // Nothing saved yet at all: an empty start is right. Otherwise: unreadable, don't pretend it's empty.
        return if (first is SafeFiles.Read.Missing && second is SafeFiles.Read.Missing) emptyList() else null
    }

    /** True only when the list was really written. */
    fun write(list: List<SavedCharacter>): Boolean {
        val a = JSONArray()
        list.forEach { c -> a.put(JSONObject().put("id", c.id).put("member", c.member).put("name", c.name).put("file", c.file).put("created", c.created).put("book", c.book)) }
        val text = a.toString()
        if (!SafeFiles.write(main, text)) return false
        SafeFiles.write(backup, text)
        return true
    }

    private fun parse(a: JSONArray): List<SavedCharacter> =
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map {
            SavedCharacter(it.optString("id"), it.optString("member"), it.optString("name"), it.optString("file"), it.optLong("created"), it.optString("book"))
        }.filter { it.file.isNotEmpty() && File(dir, it.file).exists() }.sortedByDescending { it.created }
}
