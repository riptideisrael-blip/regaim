package com.regaim.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * One thing Moments learned about what you like to keep (like = true) or don't (like = false).
 * evidence = ids of moments it was learned from; deleted = descriptions of deleted moments it was learned from.
 * byUser = you wrote or corrected it, so the AI never overwrites it.
 */
data class Insight(
    val id: String,
    val text: String,
    val like: Boolean,
    val evidence: List<String>,
    val deleted: List<String>,
    val byUser: Boolean,
)

/** A moment you deleted (auto-saved, already described), remembered only as text for learning. */
data class Rejected(val text: String, val time: Long)

/** "What Moments learned": structured, with evidence, and correctable by you. */
object Learning {
    private fun file(ctx: Context) = File(ctx.filesDir, "insights.json")
    private fun rejectedFile(ctx: Context) = File(ctx.filesDir, "rejected.json")

    private fun root(ctx: Context): JSONObject = try {
        JSONObject(file(ctx).readText())
    } catch (_: Exception) {
        JSONObject()
    }

    private fun strings(a: JSONArray?) = if (a == null) emptyList() else (0 until a.length()).map { a.optString(it) }
    private fun arr(list: List<String>) = JSONArray().also { j -> list.forEach { j.put(it) } }

    @Synchronized
    fun items(ctx: Context): List<Insight> {
        val r = root(ctx)
        val a = r.optJSONArray("items")
        if (a == null) return migrateOldTaste(ctx)
        return (0 until a.length()).map {
            val o = a.getJSONObject(it)
            Insight(
                o.optString("id"), o.optString("text"), o.optBoolean("like", true),
                strings(o.optJSONArray("evidence")), strings(o.optJSONArray("deleted")), o.optBoolean("byUser"),
            )
        }
    }

    fun dismissed(ctx: Context): List<String> = strings(root(ctx).optJSONArray("dismissed"))
    fun updatedAt(ctx: Context): Long = root(ctx).optLong("updated", 0L)

    @Synchronized
    private fun save(ctx: Context, items: List<Insight>, dismissed: List<String> = dismissed(ctx), updated: Long = updatedAt(ctx)) {
        val a = JSONArray()
        items.forEach { i ->
            a.put(
                JSONObject().put("id", i.id).put("text", i.text).put("like", i.like)
                    .put("evidence", arr(i.evidence)).put("deleted", arr(i.deleted)).put("byUser", i.byUser)
            )
        }
        val r = JSONObject().put("items", a).put("dismissed", arr(dismissed.takeLast(40))).put("updated", updated)
        // All or nothing: a cut-off file would lose the rules the parent wrote.
        if (!SafeFiles.write(file(ctx), r.toString())) throw java.io.IOException(L.t("לא הצלחנו לשמור", "Couldn't save"))
    }

    /** The old version kept a plain ✓/✗ text; turn it into items (without evidence). */
    private fun migrateOldTaste(ctx: Context): List<Insight> {
        val old = Settings.prefs(ctx).getString("taste", "") ?: ""
        val items = old.lines().map { it.trim() }.filter { it.startsWith("✓") || it.startsWith("✗") }.map {
            Insight(UUID.randomUUID().toString(), it.drop(1).trim(), it.startsWith("✓"), emptyList(), emptyList(), false)
        }
        if (items.isNotEmpty()) save(ctx, items)
        return items
    }

    // ---------- your corrections ----------

    @Synchronized
    fun add(ctx: Context, text: String, like: Boolean) {
        val t = text.trim()
        if (t.isEmpty()) return
        save(ctx, items(ctx) + Insight(UUID.randomUUID().toString(), t, like, emptyList(), emptyList(), true))
    }

    /** Editing an item makes it yours: the AI keeps it as-is from now on. */
    @Synchronized
    fun edit(ctx: Context, id: String, text: String, like: Boolean) {
        val t = text.trim()
        if (t.isEmpty()) return
        save(ctx, items(ctx).map { if (it.id == id) it.copy(text = t, like = like, byUser = true) else it })
    }

    /** Removing something the AI learned also tells it not to learn it again. */
    @Synchronized
    fun remove(ctx: Context, id: String) {
        val list = items(ctx)
        val gone = list.firstOrNull { it.id == id } ?: return
        val dis = if (gone.byUser) dismissed(ctx) else dismissed(ctx) + gone.text
        save(ctx, list.filterNot { it.id == id }, dis)
    }

    @Synchronized
    fun clearDismissed(ctx: Context) = save(ctx, items(ctx), emptyList())

    /** Forget everything learned (your own rules too) and every deleted-moment example. */
    fun resetAll(ctx: Context) {
        synchronized(this) {
            file(ctx).delete()
            rejectedFile(ctx).delete()
            CaptureFilter.forgetDeleted(ctx)
            Settings.prefs(ctx).edit().remove("taste").remove("taste_sig").apply()
            save(ctx, emptyList(), emptyList(), 0L)
        }
        for (m in MemoryStore.all(ctx).filter { it.noLearn }) MemoryStore.update(ctx, m.copy(noLearn = false))
    }

    // ---------- deleted moments ----------

    @Synchronized
    fun rememberRejected(ctx: Context, m: Memory) {
        // Deleting an automatic clip teaches the recorder not to save pictures that look like it.
        if (!m.chosen && !m.noLearn && m.origin == "capture") CaptureFilter.rememberDeleted(ctx, m.look)
        if (m.chosen || m.noLearn || m.description.isEmpty()) return
        val list = rejected(ctx) + Rejected(m.title + ". " + m.description, System.currentTimeMillis())
        saveRejected(ctx, list.takeLast(30))
    }

    @Synchronized
    fun rejected(ctx: Context): List<Rejected> = try {
        val a = JSONArray(rejectedFile(ctx).readText())
        (0 until a.length()).map { i ->
            val o = a.optJSONObject(i)
            if (o != null) Rejected(o.optString("text"), o.optLong("time")) else Rejected(a.optString(i), 0L)
        }
    } catch (_: Exception) {
        emptyList()
    }

    private fun saveRejected(ctx: Context, list: List<Rejected>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("text", it.text).put("time", it.time)) }
        if (!SafeFiles.write(rejectedFile(ctx), a.toString())) throw java.io.IOException(L.t("לא הצלחנו לשמור", "Couldn't save"))
    }

    /** "I didn't delete it because of that": drop a deleted example from learning. */
    @Synchronized
    fun forgetRejected(ctx: Context, text: String) {
        saveRejected(ctx, rejected(ctx).filterNot { it.text == text })
        save(ctx, items(ctx).map { it.copy(deleted = it.deleted.filterNot { d -> d == text }) })
    }

    // ---------- the moments it learns from ----------

    fun liked(ctx: Context): List<Memory> =
        // 👍 from the approval queue counts too; it still only shapes descriptions and scores (via tasteText).
        MemoryStore.kept(ctx).filter { it.analyzed && !it.noLearn && (it.chosen || it.favorite || it.approved || it.milestone.isNotEmpty()) }

    /** Text given to the AI when it describes and scores a moment. */
    fun tasteText(ctx: Context): String = items(ctx).joinToString("\n") { i ->
        (if (i.like) "✓ " else "✗ ") + i.text + if (i.byUser) " (ההורה קבע)" else ""
    }

    /**
     * Re-learns from the moments you chose, starred or marked as milestones, and the ones you deleted.
     * Your own rules are kept; things you removed are not learned again. Background thread only.
     */
    fun refresh(ctx: Context, force: Boolean = false): Boolean {
        if (!ClaudeClient.hasKey(ctx)) return false
        val liked = liked(ctx).take(25)
        val deleted = rejected(ctx).takeLast(15)
        val signature = liked.size + deleted.size * 1000
        val last = Settings.prefs(ctx).getInt("taste_sig", -1)
        if (!force && (liked.size < 2 || kotlin.math.abs(signature - last) < 3)) return false
        if (liked.isEmpty() && deleted.isEmpty()) return false
        return try {
            val mine = items(ctx).filter { it.byUser }
            val learned = ClaudeClient.learn(ctx, liked, deleted.map { it.text }, mine, dismissed(ctx))
            val fresh = learned.map { l ->
                Insight(
                    UUID.randomUUID().toString(), l.text, l.like,
                    l.liked.mapNotNull { liked.getOrNull(it)?.id },
                    l.deleted.mapNotNull { deleted.getOrNull(it)?.text },
                    false,
                )
            }
            save(ctx, mine + fresh, dismissed(ctx), System.currentTimeMillis())
            Settings.prefs(ctx).edit().putInt("taste_sig", signature).apply()
            true
        } catch (_: Exception) {
            false
        }
    }
}
