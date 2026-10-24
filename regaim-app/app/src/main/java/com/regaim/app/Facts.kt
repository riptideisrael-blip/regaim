package com.regaim.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * What was actually observed for each memory, kept apart from what the AI interpreted:
 *  - the detector's label and score (what set off the save),
 *  - which frames the AI was shown (timestamps, how many, which model),
 *  - whether there was a transcript (never, today),
 *  - the AI's score with its confidence, and a moment range only if the extended (8-frame) look found one.
 * The moment screen shows these facts ("Laughter · 3 pictures · Noa"), and an AI score without confidence never ranks.
 */
object Facts {
    private val lock = Any()
    private fun file(ctx: Context) = File(ctx.filesDir, "facts.json")

    private fun read(ctx: Context): JSONObject = try { JSONObject(file(ctx).readText()) } catch (_: Exception) { JSONObject() }

    private fun edit(ctx: Context, id: String, change: (JSONObject) -> Unit) = synchronized(lock) {
        val all = read(ctx)
        val o = all.optJSONObject(id) ?: JSONObject()
        change(o)
        all.put(id, o)
        try {
            val tmp = File(ctx.filesDir, "facts.json.tmp")
            tmp.writeText(all.toString())
            tmp.renameTo(file(ctx))
        } catch (_: Exception) { }
    }

    fun remove(ctx: Context, id: String) = synchronized(lock) {
        val all = read(ctx)
        all.remove(id)
        try { file(ctx).writeText(all.toString()) } catch (_: Exception) { }
    }

    fun get(ctx: Context, id: String): JSONObject? = synchronized(lock) { read(ctx).optJSONObject(id) }

    fun trigger(ctx: Context, id: String, label: String, score: Float) = edit(ctx, id) {
        it.put("trigger", label).put("trigger_score", Math.round(score * 100) / 100.0)
    }

    fun frames(ctx: Context, id: String, ms: List<Long>, model: String) = edit(ctx, id) {
        it.put("frames", JSONArray(ms)).put("model", model).put("transcript", false).put("analyzed_at", System.currentTimeMillis())
    }

    fun ai(ctx: Context, id: String, score: Int, confidence: Float) = edit(ctx, id) {
        it.put("ai_score", score).put("ai_confidence", Math.round(confidence * 100) / 100.0)
    }

    fun segment(ctx: Context, id: String, startMs: Long, endMs: Long, confidence: Float) = edit(ctx, id) {
        it.put("segment", JSONObject().put("start", startMs).put("end", endMs).put("confidence", Math.round(confidence * 100) / 100.0))
    }

    /** Memories whose AI score may rank: the AI said how sure it is, and it's fairly sure (≥ 0.6). */
    fun confident(ctx: Context): Set<String> = synchronized(lock) {
        val all = read(ctx)
        all.keys().asSequence().filter { (all.optJSONObject(it)?.optDouble("ai_confidence", -1.0) ?: -1.0) >= 0.6 }.toSet()
    }

    /** "Laughter · 3 pictures · Noa" — only what was observed, nothing interpreted. */
    fun line(ctx: Context, m: Memory): String {
        val f = get(ctx, m.id)
        val parts = ArrayList<String>()
        val label = f?.optString("trigger")?.takeIf { it.isNotEmpty() } ?: m.label
        if (label.isNotEmpty() && label != "נוסף מהגלריה") parts.add(Labels.he(label))
        val n = f?.optJSONArray("frames")?.length() ?: 0
        if (n > 0) parts.add(if (n == 1) L.t("תמונה אחת", "1 picture") else L.t("$n תמונות", "$n pictures"))
        val seg = f?.optJSONObject("segment")
        if (seg != null) parts.add(L.t("הרגע: ${Fmt.duration(seg.optLong("start"))}–${Fmt.duration(seg.optLong("end"))}", "The moment: ${Fmt.duration(seg.optLong("start"))}–${Fmt.duration(seg.optLong("end"))}"))
        if (m.allPeople.isNotEmpty()) parts.add(m.allPeople)
        if (n > 0 && f?.optBoolean("transcript", false) != true) parts.add(L.t("בלי קול", "no sound"))
        return parts.joinToString(" · ")
    }
}
