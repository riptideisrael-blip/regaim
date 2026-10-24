package com.regaim.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/**
 * Phones on cellular data lose the connection all the time (a weak signal, a tower handover, a tunnel).
 * Instead of failing on the first network error, a step waits until the phone is online again and tries again,
 * for a few minutes. Only then does it give up with [Offline] (the book is kept and can be continued later).
 */
object Net {
    /** How long a step keeps waiting for the connection before it pauses. */
    const val WAIT_MS = 5 * 60_000L

    /** The connection was gone for too long. The message is for the parent. */
    class Offline : IOException(L.t(
        "אין חיבור לאינטרנט כבר כמה דקות. הספר נשמר, ואפשר להמשיך אותו כשיהיה חיבור.",
        "There's been no internet connection for a few minutes. The book is saved; you can continue it once you're back online.",
    ))

    /**
     * The phone was online the whole time, but the call kept failing: not a connection problem, so we stop waiting
     * and say what actually failed (shown to the parent, and useful in a screenshot).
     */
    class Stuck(cause: IOException) : IOException(L.t(
        "השרת לא הצליח להשלים את הבקשה, למרות שיש חיבור. הספר נשמר ואפשר לנסות להמשיך. (פרטים: ${cause.javaClass.simpleName}: ${cause.message.orEmpty().take(80)})",
        "The server couldn't complete the request, though you're online. The book is saved; you can try to continue. (Details: ${cause.javaClass.simpleName}: ${cause.message.orEmpty().take(80)})",
    ), cause)

    fun online(ctx: Context): Boolean = try {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val n = cm?.activeNetwork
        val c = n?.let { cm.getNetworkCapabilities(it) }
        c?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    } catch (_: Exception) { true } // can't tell: just try

    /**
     * Runs [block]. On a network error (any IOException) waits for the connection and runs it again, with a growing
     * pause, for up to [maxWaitMs]. [onWait] is told when we start and stop waiting (for the screen).
     * Only use for calls that are safe to repeat. Background thread only.
     */
    fun <T> retry(ctx: Context, cancelled: AtomicBoolean, maxWaitMs: Long = WAIT_MS, onWait: (Boolean) -> Unit = {}, block: () -> T): T {
        val start = System.currentTimeMillis()
        var attempt = 0
        var offlineSeen = false
        while (true) {
            if (cancelled.get()) throw CancellationException()
            try {
                val r = block()
                if (attempt > 0) onWait(false)
                return r
            } catch (e: Offline) {
                throw e
            } catch (e: Stuck) {
                throw e
            } catch (e: IOException) {
                attempt++
                if (!online(ctx)) offlineSeen = true
                // Online all along and still failing after a few tries and a minute: something else is wrong.
                if (!offlineSeen && attempt >= 4 && System.currentTimeMillis() - start > 60_000) { onWait(false); throw Stuck(e) }
                onWait(true)
                val backoff = min(20_000L, 2_000L * attempt)
                var slept = 0L
                while (slept < backoff || !online(ctx)) {
                    if (cancelled.get()) throw CancellationException()
                    if (System.currentTimeMillis() - start > maxWaitMs) { onWait(false); throw Offline() }
                    Thread.sleep(1_000)
                    slept += 1_000
                }
            }
        }
    }

    /** A fresh sign-in token. A refresh that fails for lack of network is a network error (so it is retried). */
    fun token(ctx: Context): String {
        if (!Account.signedIn(ctx)) throw IllegalStateException(L.t("צריך להתחבר כדי ליצור ספר", "Sign in to create a book"))
        return Account.token(ctx) ?: throw IOException("token refresh")
    }
}
