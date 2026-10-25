package com.regaim.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * The global layer of capture learning. On each 👍 / 👎 — only when signed in and "help detection accuracy" is on —
 * the server gets the trigger label, a score bucket and the vote. Never video, frames, audio, names, places, the
 * picture fingerprint, a memory id, or any taste / description text.
 * Once a day the phone fetches the per-label summary (up, down, multiplier) and keeps it; offline, the last one stays.
 * Turning the switch off stops sending; the phone's own learning (capture_votes.json) keeps working either way.
 */
object GlobalVotes {
    private const val DAY_MS = 24L * 60 * 60 * 1000
    private fun p(ctx: Context) = Settings.prefs(ctx)
    private fun file(ctx: Context) = File(ctx.filesDir, "global_votes.json")

    fun helping(ctx: Context) = p(ctx).getBoolean("help_accuracy", true)
    fun setHelping(ctx: Context, on: Boolean) = p(ctx).edit().putBoolean("help_accuracy", on).apply()

    /** Pure: the score buckets the server accepts (null when the trigger has no score, e.g. a smile). */
    fun bucket(score: Float): String? = when {
        score < 0f -> null
        score < 0.4f -> "0.2-0.4"
        score < 0.6f -> "0.4-0.6"
        score < 0.8f -> "0.6-0.8"
        else -> "0.8+"
    }

    /** Exactly what one vote sends. Pure, so it's tested that nothing else is in it. */
    fun payload(label: String, score: Float, vote: Int): JSONObject =
        JSONObject().put("p_label", label.take(60)).put("p_bucket", bucket(score) ?: JSONObject.NULL).put("p_vote", if (vote > 0) 1 else -1)

    fun send(ctx: Context, label: String, score: Float, vote: Int) {
        if (label.isEmpty() || !CaptureVotes.Rules.sendsGlobally(helping(ctx), Account.signedIn(ctx))) return
        val app = ctx.applicationContext
        Thread { try { rpc(app, "capture_vote", payload(label, score, vote)) } catch (_: Exception) { } }.start()
    }

    @Volatile private var cache: Map<String, Float>? = null

    /** The global multiplier for a label from the last summary (1 when unknown). */
    fun multiplier(ctx: Context, label: String): Float {
        val m = cache ?: load(ctx)
        return (m[label] ?: 1f).coerceIn(0f, 5f)
    }

    private fun load(ctx: Context): Map<String, Float> {
        val o = try { JSONObject(file(ctx).readText()) } catch (_: Exception) { JSONObject() }
        val m = HashMap<String, Float>()
        for (k in o.keys()) o.optJSONObject(k)?.let { m[k] = it.optDouble("m", 1.0).toFloat() }
        cache = m
        return m
    }

    /** Fetches the summary at most once a day (background thread). Without network the last summary stays. */
    fun refresh(ctx: Context, force: Boolean = false) {
        val last = p(ctx).getLong("global_votes_at", 0L)
        if (!force && System.currentTimeMillis() - last < DAY_MS) return
        if (!Account.signedIn(ctx)) return
        try {
            val rows = JSONArray(rpc(ctx, "capture_votes_summary", JSONObject()))
            val out = JSONObject()
            for (i in 0 until rows.length()) {
                val r = rows.optJSONObject(i) ?: continue
                out.put(r.optString("label"), JSONObject().put("up", r.optInt("up")).put("down", r.optInt("down")).put("m", r.optDouble("multiplier", 1.0)))
            }
            file(ctx).writeText(out.toString())
            cache = null
            p(ctx).edit().putLong("global_votes_at", System.currentTimeMillis()).apply()
        } catch (_: Exception) { }
    }

    private fun rpc(ctx: Context, name: String, body: JSONObject): String {
        val token = Account.token(ctx) ?: throw IllegalStateException("not signed in")
        val conn = (URL(Circle.url(ctx) + "/rest/v1/rpc/" + name).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 20_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("apikey", Circle.key(ctx))
            setRequestProperty("Authorization", "Bearer $token")
        }
        return try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IllegalStateException("rpc $code")
            text
        } finally {
            conn.disconnect()
        }
    }
}
