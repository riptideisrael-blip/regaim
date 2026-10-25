package com.regaim.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Why each found moment was found (for "why was this saved?" and for debugging), and what the family said about it.
 *  - detections.json: memory id → the full evidence (kinds, times, confidence, each sense's score)
 *  - moment_feedback.json: ❤️ kept / ✕ "not a moment" answers, which tune the ranking per kind ([learned])
 * Everything stays on the phone.
 */
object Detections {
    private const val MAX_FEEDBACK = 600
    private val lock = Any()

    private fun file(ctx: Context) = File(ctx.filesDir, "detections.json")
    private fun feedbackFile(ctx: Context) = File(ctx.filesDir, "moment_feedback.json")

    fun toJson(c: Candidate, source: String = ""): JSONObject = JSONObject()
        .put("v", MomentFusion.VERSION)
        .put("type", c.primary.key)
        .put("types", JSONArray().also { a -> c.types.forEach { a.put(JSONObject().put("k", it.first.key).put("s", round(it.second))) } })
        .put("pattern", c.pattern)
        .put("start", c.startMs).put("end", c.endMs).put("peak", c.peakMs)
        .put("clip_start", c.clipStartMs).put("clip_end", c.clipEndMs)
        .put("confidence", round(c.confidence)).put("rank", round(c.rank)).put("tier", c.tier.name.lowercase())
        .put("audio", c.audio?.let { round(it) } ?: JSONObject.NULL)
        .put("visual", c.visual?.let { round(it) } ?: JSONObject.NULL)
        .put("interaction", round(c.interaction)).put("temporal", round(c.temporal))
        .put("evidence", JSONArray(c.evidence))
        .put("source", source)

    private fun round(f: Float) = Math.round(f * 100) / 100.0

    fun save(ctx: Context, memoryId: String, c: Candidate, source: String) = synchronized(lock) {
        val all = read(file(ctx))
        all.put(memoryId, toJson(c, source))
        write(file(ctx), all)
    }

    fun get(ctx: Context, memoryId: String): JSONObject? = synchronized(lock) { read(file(ctx)).optJSONObject(memoryId) }

    /** "Why was this saved": the honest words, built from the stored evidence. */
    fun explain(ctx: Context, memoryId: String): String? {
        val o = get(ctx, memoryId) ?: return null
        val ev = o.optJSONArray("evidence") ?: JSONArray()
        val list = (0 until ev.length()).map { ev.optString(it) }
        val fake = Candidate(
            o.optLong("start"), o.optLong("end"), o.optLong("peak"), 0, 0, 0f, 0f,
            Tier.valueOf(o.optString("tier", "good").uppercase()), null, null, 0f, 0f,
            listOf((MomentType.values().firstOrNull { it.key == o.optString("type") } ?: MomentType.JOY) to 1f), list,
        )
        val why = MomentWords.why(fake)
        return if (why.isEmpty()) null else why
    }

    /** The family's answer about a found moment: +1 kept / loved, -1 "not a moment" / deleted. */
    fun feedback(ctx: Context, c: Candidate, vote: Int) = record(ctx, c.pattern, c.primary.key, vote)

    /** Same, for a moment already saved (deleted → -1, loved → +1). Ignores memories that weren't found by the scanner. */
    fun feedbackFor(ctx: Context, memoryId: String, vote: Int) {
        val o = get(ctx, memoryId) ?: return
        if (o.optInt("voted", 0) == vote) return
        record(ctx, o.optString("pattern"), o.optString("type"), vote)
        synchronized(lock) {
            val all = read(file(ctx))
            all.optJSONObject(memoryId)?.put("voted", vote)
            write(file(ctx), all)
        }
    }

    private fun record(ctx: Context, pattern: String, type: String, vote: Int) = synchronized(lock) {
        val a = try { JSONArray(feedbackFile(ctx).readText()) } catch (_: Exception) { JSONArray() }
        a.put(JSONObject().put("pattern", pattern).put("type", type).put("vote", vote).put("t", System.currentTimeMillis()))
        val trimmed = if (a.length() > MAX_FEEDBACK) JSONArray().also { n -> for (i in a.length() - MAX_FEEDBACK until a.length()) n.put(a.get(i)) } else a
        SafeFiles.write(feedbackFile(ctx), trimmed.toString())
    }

    /** Ranking multipliers learned from the family's answers (by pattern and by kind). */
    fun learned(ctx: Context): Map<String, Float> = synchronized(lock) {
        val a = try { JSONArray(feedbackFile(ctx).readText()) } catch (_: Exception) { return emptyMap() }
        val votes = ArrayList<Pair<String, Int>>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val v = o.optInt("vote")
            if (o.optString("pattern").isNotEmpty()) votes.add(o.optString("pattern") to v)
            if (o.optString("type").isNotEmpty() && o.optString("type") != o.optString("pattern")) votes.add(o.optString("type") to v)
        }
        MomentFusion.learn(votes)
    }

    fun feedbackCount(ctx: Context): Int = try { JSONArray(feedbackFile(ctx).readText()).length() } catch (_: Exception) { 0 }

    fun reset(ctx: Context) = synchronized(lock) { feedbackFile(ctx).delete() }

    private fun read(f: File): JSONObject = try { JSONObject(f.readText()) } catch (_: Exception) { JSONObject() }
    private fun write(f: File, o: JSONObject) {
        try {
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(o.toString())
            tmp.renameTo(f)
        } catch (_: Exception) { }
    }
}
