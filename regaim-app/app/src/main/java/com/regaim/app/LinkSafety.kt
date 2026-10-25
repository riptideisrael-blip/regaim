package com.regaim.app

/** Pure checks for links that open the app from outside (no Android here, so they are unit-tested). */
object LinkSafety {
    /** A Google sign-in that comes back must follow one started on this phone in the last 15 minutes. */
    fun redirectExpected(startedAt: Long, now: Long): Boolean =
        startedAt > 0 && now >= startedAt && now - startedAt <= 15 * 60 * 1000L

    /** An invite code: exactly 32 hex characters. */
    fun inviteCodeValid(code: String): Boolean = code.matches(Regex("[A-Fa-f0-9]{32}"))

    /**
     * The server an invite link names may only be Moments' own one, compared by exact host (so
     * "https://<ours>.supabase.co.evil.com" or "https://evil.com/<ours>.supabase.co" are refused).
     */
    fun sameServer(url: String, ours: String): Boolean {
        fun host(u: String): String? {
            val t = u.trim()
            if (!t.startsWith("https://")) return null
            val rest = t.removePrefix("https://")
            val end = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
            val h = (if (end >= 0) rest.substring(0, end) else rest).lowercase()
            if (h.isEmpty() || '@' in h || ':' in h) return null
            return h
        }
        val a = host(url) ?: return false
        return a == host(ours)
    }
}
