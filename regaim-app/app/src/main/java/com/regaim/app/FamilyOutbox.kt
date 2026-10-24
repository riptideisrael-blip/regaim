package com.regaim.app

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Family chat messages waiting to be sent (no connection, or the server didn't answer). Each message gets its own id
 * on the phone ([Item.clientId]) and keeps it on every try, so the server stores it once even if it was sent twice
 * (needs family-chat-outbox.sql; without it messages still send, as before, just without that guard).
 *
 * Without a connection a message stays "pending" and goes out by itself when the connection is back (WorkManager,
 * even if the app was closed). A message the server refused (not a connection problem) becomes "failed" and waits
 * for the parent to tap "try again". Only the account that wrote a message ever sends it.
 */
object FamilyOutbox {
    const val PENDING = "pending"
    const val FAILED = "failed"

    class Item(
        val clientId: String, val circleId: String, val userId: String, val postId: String?,
        val author: String, val body: String, val created: Long, val state: String, val error: String,
    ) {
        fun json(): JSONObject = JSONObject().put("client_id", clientId).put("circle_id", circleId).put("user_id", userId)
            .put("post_id", postId ?: "").put("author", author).put("body", body).put("created", created)
            .put("state", state).put("error", error)

        fun with(state: String, error: String = "") = Item(clientId, circleId, userId, postId, author, body, created, state, error)

        companion object {
            fun of(o: JSONObject) = Item(
                o.optString("client_id"), o.optString("circle_id"), o.optString("user_id"), o.optString("post_id").ifEmpty { null },
                o.optString("author"), o.optString("body"), o.optLong("created"), o.optString("state", PENDING), o.optString("error"),
            )
        }
    }

    private const val PREFS = "family_outbox"
    private const val WORK = "family_outbox_send"
    private val lock = Any()
    private val sendLock = Any()

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun all(ctx: Context): List<Item> = synchronized(lock) {
        try {
            val a = JSONArray(prefs(ctx).getString("items", "[]") ?: "[]")
            (0 until a.length()).map { Item.of(a.getJSONObject(it)) }.filter { it.clientId.isNotEmpty() && it.body.isNotBlank() }
        } catch (_: Exception) { emptyList() }
    }

    private fun save(ctx: Context, items: List<Item>) = synchronized(lock) {
        prefs(ctx).edit().putString("items", JSONArray().apply { items.forEach { put(it.json()) } }.toString()).commit()
    }

    private fun change(ctx: Context, f: (List<Item>) -> List<Item>) = synchronized(lock) { save(ctx, f(all(ctx))) }

    /** Queues a message for this circle (and memory thread). It shows in the chat at once as "pending". */
    fun add(ctx: Context, body: String, postId: String?): Item {
        val item = Item(
            UUID.randomUUID().toString(), Circle.circleId(ctx), Account.id(ctx), postId, Circle.me(ctx), body.trim(),
            System.currentTimeMillis(), PENDING, "",
        )
        change(ctx) { it + item }
        schedule(ctx)
        return item
    }

    /** This account's waiting messages for one thread, oldest first. */
    fun waiting(ctx: Context, postId: String?): List<Item> {
        val me = Account.id(ctx); val circle = Circle.circleId(ctx)
        return all(ctx).filter { it.userId == me && it.circleId == circle && it.postId == postId }.sortedBy { it.created }
    }

    fun retry(ctx: Context, clientId: String) {
        change(ctx) { l -> l.map { if (it.clientId == clientId) it.with(PENDING) else it } }
        schedule(ctx)
    }

    fun discard(ctx: Context, clientId: String) = change(ctx) { l -> l.filterNot { it.clientId == clientId } }

    /** Every message of this account (all circles) removed, e.g. on sign-out or account deletion. */
    fun clear(ctx: Context) = synchronized(lock) { prefs(ctx).edit().clear().commit() }

    /**
     * Sends this account's pending messages, oldest first. Background thread only. Returns how many are still
     * pending because the connection failed (the caller may try again later).
     */
    fun flush(ctx: Context): Int {
        synchronized(sendLock) { return flushLocked(ctx) }
    }

    private fun flushLocked(ctx: Context): Int {
        val me = Account.id(ctx)
        if (me.isEmpty()) return 0
        var offline = 0
        for (item in all(ctx).filter { it.userId == me && it.state == PENDING }.sortedBy { it.created }) {
            if (offline > 0) { offline++; continue } // no connection: don't hammer, keep the order
            try {
                Circle.sendQueued(ctx, item)
                change(ctx) { l -> l.filterNot { it.clientId == item.clientId } }
            } catch (_: IOException) {
                offline++
            } catch (e: Exception) {
                val why = e.message ?: L.t("ההודעה לא נשלחה", "The message wasn't sent")
                change(ctx) { l -> l.map { if (it.clientId == item.clientId) it.with(FAILED, why) else it } }
            }
        }
        return offline
    }

    /** Sends in the background as soon as there's a connection, even after the app is closed. Never throws. */
    fun schedule(ctx: Context) {
        try {
            val ok = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            WorkManager.getInstance(ctx.applicationContext).enqueueUniqueWork(
                WORK, ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<FamilyOutboxWorker>().setConstraints(ok)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build(),
            )
        } catch (_: Exception) { }
    }
}

/** The WorkManager side of [FamilyOutbox]: tries again later while the connection keeps failing. */
class FamilyOutboxWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        val left = try { FamilyOutbox.flush(applicationContext) } catch (_: Exception) { 1 }
        return if (left > 0 && runAttemptCount < 20) Result.retry() else Result.success()
    }
}
