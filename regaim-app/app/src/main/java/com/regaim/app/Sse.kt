package com.regaim.app

import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException

/** Reads Claude's streamed answer (server-sent events). Pure, unit tested. */
object Sse {
    /**
     * The text so far and whether Claude reached the end ("message_stop"). An "overloaded" error is a network-like
     * error (try again); any other error is thrown with its message.
     */
    fun read(r: BufferedReader, onChars: (Int) -> Unit = {}): Pair<String, Boolean> = readFull(r, onChars).let { it.text to it.finished }

    /** [stopReason]: why Claude stopped ("end_turn", or "max_tokens" when the answer was cut at the length limit). */
    class Streamed(val text: String, val finished: Boolean, val stopReason: String)

    fun readFull(r: BufferedReader, onChars: (Int) -> Unit = {}): Streamed {
        val sb = StringBuilder()
        var finished = false
        var stop = ""
        while (true) {
            val line = r.readLine() ?: break
            if (!line.startsWith("data:")) continue
            val ev = try { JSONObject(line.substring(5).trim()) } catch (_: Exception) { continue }
            when (ev.optString("type")) {
                "content_block_delta" -> ev.optJSONObject("delta")?.takeIf { it.optString("type") == "text_delta" }?.let {
                    sb.append(it.optString("text")); onChars(sb.length)
                }
                "message_delta" -> ev.optJSONObject("delta")?.optString("stop_reason")?.takeIf { it.isNotEmpty() && it != "null" }?.let { stop = it }
                "message_stop" -> finished = true
                "error" -> {
                    val e = ev.optJSONObject("error")
                    if (e?.optString("type") == "overloaded_error") throw IOException("overloaded")
                    throw IllegalStateException(L.t("שגיאה מה-AI: ", "AI error: ") + (e?.optString("message") ?: "").take(150))
                }
            }
        }
        return Streamed(sb.toString(), finished, stop)
    }
}
