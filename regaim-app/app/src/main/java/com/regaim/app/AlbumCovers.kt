package com.regaim.app

import android.content.Context

/** The main picture of an album, chosen by the family. Albums are identified by a key like "m2026-10" or "milestones". */
object AlbumCovers {
    private fun key(album: String) = "album_cover_$album"
    fun get(ctx: Context, album: String): String? = Settings.prefs(ctx).getString(key(album), null)
    fun set(ctx: Context, album: String, memoryId: String?) {
        Settings.prefs(ctx).edit().apply { if (memoryId == null) remove(key(album)) else putString(key(album), memoryId) }.apply()
    }

    /**
     * Best covers first, and a cover always has someone in it: moments with recognized people come first,
     * moments where we looked and found nobody (grass, feet, the ceiling) go last.
     */
    fun faceFirst(ms: List<Memory>, confident: Set<String> = emptySet()): List<Memory> = ms.sortedByDescending { m ->
        val people = when {
            Fmt.names(m).isNotEmpty() -> 40
            m.facesScanned -> -25
            else -> 0
        }
        people + (if (m.milestone.isNotEmpty()) 30 else 0) + (if (m.favorite) 20 else 0) + (if (m.chosen) 10 else 0) + (if (m.id in confident) m.score else 0) // an AI score without confidence doesn't rank
    }

    /** The album's moments with the chosen cover first (if it still belongs to the album), else best first. */
    fun ordered(ctx: Context, album: String, ms: List<Memory>): List<Memory> {
        val best = faceFirst(ms, Facts.confident(ctx))
        val chosen = get(ctx, album)?.let { id -> ms.firstOrNull { it.id == id } } ?: return best
        return listOf(chosen) + best.filter { it.id != chosen.id }
    }
}
