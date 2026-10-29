package com.regaim.app

import android.content.Context

/**
 * The other parent's new moments as a story, like on Instagram: what they shared with this parent (the shared
 * gallery) in the last day that this phone hasn't watched yet. Their picture on the home screen gets a ring until it's
 * watched. Only what's already on the phone ([Circle.cachedPartnerMoments]); [refresh] brings the list up to date.
 */
object PartnerStories {
    private const val DAY_MS = 24 * 60 * 60 * 1000L
    private const val SEEN = "partner_story_seen"

    /** The other parent's moments from the last day not watched here yet, oldest first (the order they're played). */
    fun unseen(ctx: Context, now: Long = System.currentTimeMillis()): List<Circle.Post> {
        if (PartnerShare.partner(ctx).isEmpty()) return emptyList()
        val seen = seen(ctx)
        return Circle.cachedPartnerMoments(ctx).filter { !it.pitzki && it.createdAt >= now - DAY_MS && it.id !in seen }.sortedBy { it.createdAt }
    }

    /** The other parent's first name, as their moments are signed (empty before any moment came). */
    fun name(ctx: Context): String = Circle.cachedPartnerMoments(ctx).firstOrNull()?.author.orEmpty().trim().substringBefore(' ')

    /** The other parent's moments of the last day, oldest first: what's played when their picture is tapped. */
    fun recent(ctx: Context, now: Long = System.currentTimeMillis()): List<Circle.Post> =
        Circle.cachedPartnerMoments(ctx).filter { !it.pitzki && it.createdAt >= now - DAY_MS }.sortedBy { it.createdAt }

    fun markSeen(ctx: Context, id: String) {
        val s = seen(ctx)
        if (id in s) return
        // Only the last few hundred are kept (older ones are past the day anyway).
        Settings.prefs(ctx).edit().putString(SEEN, (s.toList().takeLast(300) + id).joinToString(",")).apply()
    }

    private fun seen(ctx: Context): Set<String> =
        Settings.prefs(ctx).getString(SEEN, "").orEmpty().split(',').filter { it.isNotBlank() }.toSet()

    /** Brings the other parent's list up to date (at most every few minutes). Background thread only; true when it changed. */
    fun refresh(ctx: Context): Boolean {
        if (PartnerShare.partner(ctx).isEmpty() || !Account.signedIn(ctx) || !Circle.joined(ctx)) return false
        if (System.currentTimeMillis() - Circle.partnerMomentsAt(ctx) < 5 * 60_000) return false
        val before = Circle.cachedPartnerMoments(ctx).map { it.id }
        val after = try { Circle.partnerMoments(ctx).map { it.id } } catch (_: Exception) { return false }
        return before != after
    }
}
