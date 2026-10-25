package com.regaim.app

import android.content.Context
import java.io.IOException

/**
 * When describing a moment fails, why it failed decides what happens next:
 *  - no connection (nothing reached the AI): try again any time, not counted;
 *  - anything else (the AI answered something unusable, an error on our server): try again later, at most
 *    [MAX] times, waiting longer each time, so a moment that always fails isn't sent again and again.
 * After that the moment shows "couldn't describe" with a "try again" the parent can tap ([reset]).
 */
object AnalysisRetry {
    const val MAX = 3
    /** Wait after the 1st and 2nd failure before trying again. */
    private val WAIT_MS = longArrayOf(60 * 60_000L, 6 * 60 * 60_000L)

    enum class Kind { NETWORK, OTHER }

    /** Pure: what kind of failure [e] is. */
    fun classify(e: Throwable): Kind {
        var c: Throwable? = e
        while (c != null) {
            if (c is IOException) return Kind.NETWORK
            c = c.cause
        }
        val m = (e.message ?: "").lowercase()
        return if ("timeout" in m || "unable to resolve" in m || "network" in m || "connection" in m) Kind.NETWORK else Kind.OTHER
    }

    /** Pure: may a moment that failed [count] times (last at [last]) be tried at [now]? */
    fun mayTry(count: Int, last: Long, now: Long): Boolean = when {
        count <= 0 -> true
        count >= MAX -> false
        else -> now - last >= WAIT_MS[(count - 1).coerceAtMost(WAIT_MS.size - 1)]
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("analysis_retry", Context.MODE_PRIVATE)

    fun count(ctx: Context, id: String) = prefs(ctx).getInt("n_$id", 0)
    fun gaveUp(ctx: Context, id: String) = count(ctx, id) >= MAX
    fun mayTry(ctx: Context, id: String, now: Long = System.currentTimeMillis()) =
        mayTry(count(ctx, id), prefs(ctx).getLong("t_$id", 0L), now)

    /** Records a failure. Network failures aren't counted. Only the error's type is kept (no content). */
    fun failed(ctx: Context, id: String, e: Throwable) {
        val kind = classify(e)
        android.util.Log.w("Moments", "Describing a moment failed ($kind): ${e.javaClass.simpleName}")
        if (kind == Kind.NETWORK) return
        prefs(ctx).edit().putInt("n_$id", count(ctx, id) + 1).putLong("t_$id", System.currentTimeMillis()).apply()
    }

    fun succeeded(ctx: Context, id: String) {
        if (prefs(ctx).contains("n_$id")) prefs(ctx).edit().remove("n_$id").remove("t_$id").apply()
    }

    /** The parent asked to try again: starts counting from zero. */
    fun reset(ctx: Context, id: String) = succeeded(ctx, id)
}
