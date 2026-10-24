package com.regaim.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * "The family's characters": every character the parent approved in a book is kept here, on the phone only, so the
 * next book uses it by default (no new drawing: cheaper, faster, and the same characters in every book). The parent
 * can always pick another saved version, draw from a photo, or draw a new one. A few versions per person are kept.
 */
object CharacterStore {
    private const val KEEP = 5

    private fun dir(ctx: Context) = File(ctx.filesDir, "characters").apply { mkdirs() }
    private fun index(ctx: Context) = CharacterIndex(dir(ctx))

    /** Every saved character, newest first. Empty if the list can't be read right now (nothing is overwritten). */
    @Synchronized
    fun all(ctx: Context): List<SavedCharacter> = index(ctx).load().orEmpty()

    /** The newest saved character of [member], or null. */
    fun latest(ctx: Context, member: String): SavedCharacter? = if (member.isBlank()) null else all(ctx).firstOrNull { it.member == member }

    fun versions(ctx: Context, member: String): List<SavedCharacter> = all(ctx).filter { it.member == member }

    fun file(ctx: Context, c: SavedCharacter) = File(dir(ctx), c.file)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("characters", Context.MODE_PRIVATE)
    private fun mark(bytes: ByteArray) = "${bytes.size}:${bytes.contentHashCode()}"

    /**
     * Keeps an approved character. The same picture isn't kept twice, and one the parent deleted never comes back.
     * True when it's kept (or was already); false when it couldn't be saved (the caller must not count on it).
     */
    @Synchronized
    fun save(ctx: Context, member: String, name: String, src: File, book: String, created: Long = System.currentTimeMillis()): Boolean {
        if (member.isBlank() || !src.exists()) return false
        val idx = index(ctx)
        val list = idx.load() ?: return false // the list can't be read: never write over it
        val bytes = try { src.readBytes() } catch (_: Exception) { return false }
        if (mark(bytes) in prefs(ctx).getStringSet("deleted", emptySet()).orEmpty()) return true
        if (list.any { it.member == member && file(ctx, it).let { f -> f.length() == bytes.size.toLong() && f.readBytes().contentEquals(bytes) } }) return true
        val f = "char_${UUID.randomUUID()}.jpg"
        val pic = File(dir(ctx), f)
        if (!SafeFiles.writeBytes(pic, bytes)) return false
        val fresh = listOf(SavedCharacter(UUID.randomUUID().toString(), member, name.trim(), f, created, book)) + list
        // A few versions per person: the oldest go (only after the new list is safely written).
        val keep = fresh.groupBy { it.member }.flatMap { (_, v) -> v.sortedByDescending { it.created }.take(KEEP) }.toSet()
        if (!idx.write(fresh.filter { it in keep })) { pic.delete(); return false }
        fresh.filter { it !in keep }.forEach { File(dir(ctx), it.file).delete() }
        return true
    }

    /**
     * Once: the characters of books made before characters were kept (the newest finished book of each child) are
     * kept too, so the next book can use them. Background thread only. Marked done only when it finished.
     */
    fun backfill(ctx: Context) {
        val prefs = ctx.getSharedPreferences("regaim", Context.MODE_PRIVATE)
        if (prefs.getBoolean("characters_backfilled_v2", false)) return
        var ok = true
        try {
            val members = Profile.members(ctx)
            val family = Cast.family(ctx, "")
            for (b in BookStore.all(ctx).filter { it.done || it.state == "pages" }.sortedBy { it.created }) {
                val dir = BookStore.dir(ctx, b.id)
                members.firstOrNull { it.name.trim() == b.child.trim() }?.let { m ->
                    if (b.character.isNotEmpty() && File(dir, b.character).exists() && !save(ctx, m.id, b.child, File(dir, b.character), b.id, b.created)) ok = false
                }
                for (c in b.cast) {
                    val member = c.memberId.ifEmpty { Cast.match(c.name, family)?.id.orEmpty() }
                    if (member.isNotEmpty() && c.sheet.isNotEmpty() && File(dir, c.sheet).exists() && !save(ctx, member, c.name, File(dir, c.sheet), b.id, b.created)) ok = false
                }
            }
        } catch (_: Exception) { ok = false }
        if (ok) prefs.edit().putBoolean("characters_backfilled_v2", true).apply()
    }

    /** Deletes one version; it's remembered, so it isn't brought back from an old book. False if it couldn't. */
    @Synchronized
    fun delete(ctx: Context, id: String): Boolean {
        val idx = index(ctx)
        val list = idx.load() ?: return false
        val c = list.firstOrNull { it.id == id } ?: return true
        val f = File(dir(ctx), c.file)
        // Remember it first: if that fails, keep the picture rather than let an old book bring it back unnoticed.
        val marked = try {
            prefs(ctx).edit().putStringSet("deleted", prefs(ctx).getStringSet("deleted", emptySet()).orEmpty() + mark(f.readBytes())).commit()
        } catch (_: Exception) { false }
        if (!marked) return false
        if (!idx.write(list.filterNot { it.id == id })) return false
        f.delete()
        return true
    }

    /** Deletes every version of [member]. False if any couldn't be deleted. */
    fun deleteAll(ctx: Context, member: String): Boolean = versions(ctx, member).map { delete(ctx, it.id) }.all { it }

    /** Makes [id] the version the next book uses. False if it couldn't be saved. */
    @Synchronized
    fun makeMain(ctx: Context, id: String): Boolean {
        val idx = index(ctx)
        val list = idx.load() ?: return false
        return idx.write(list.map { if (it.id == id) it.copy(created = System.currentTimeMillis()) else it })
    }
}
