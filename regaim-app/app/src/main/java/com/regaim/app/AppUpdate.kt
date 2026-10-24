package com.regaim.app

import org.json.JSONObject

/**
 * What the server says about the newest version (no Android here, unit-tested). The GitHub build puts it there after
 * each build: the app's number, its name, and the APK in parts (one file on the server can't be over 50 MB).
 */
object AppUpdate {
    data class Info(val code: Long, val name: String, val parts: List<String>, val size: Long, val sha256: String)

    /** Null when the text isn't a version note (or is missing something). */
    fun parse(text: String): Info? = try {
        val o = JSONObject(text)
        val parts = o.getJSONArray("parts").let { a -> (0 until a.length()).map { a.getString(it) } }
        Info(o.getLong("code"), o.optString("name"), parts, o.getLong("size"), o.optString("sha256").lowercase()).takeIf { valid(it) }
    } catch (_: Exception) { null }

    /** A part's name is a plain path inside the updates folder (never a link elsewhere), and the fingerprint is whole. */
    fun valid(i: Info) = i.code > 0 && i.size > 0 && i.parts.isNotEmpty() &&
        i.parts.all { it.matches(Regex("[vb]\\d+/[A-Za-z0-9_-]+(\\.[A-Za-z0-9_-]+)*")) } && i.sha256.matches(Regex("[0-9a-f]{64}"))

    /** Newer than the one installed. */
    fun isNewer(info: Info?, installed: Long) = info != null && info.code > installed

    /** The build's number in a version's name ("0.42 · build 121" → 121), or null. */
    fun number(name: String?): Int? = name?.let { Regex("(\\d+)\\s*$").find(it.trim())?.groupValues?.get(1)?.toIntOrNull() }

    /** Asked about this version at [now] after "not now" at [declinedAt]: asked again only a day later. */
    fun askAgain(declinedCode: Long, declinedAt: Long, code: Long, now: Long) = declinedCode != code || now - declinedAt > 24 * 3600_000L

    /** "45.2 MB" */
    fun mb(bytes: Long) = "%.1f MB".format(java.util.Locale.US, bytes / 1_048_576.0)
}
