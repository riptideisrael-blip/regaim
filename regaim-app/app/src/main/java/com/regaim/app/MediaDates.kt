package com.regaim.app

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * When a photo or video was really taken, read from the file itself (EXIF for photos, the video's own date), for
 * media that came in without a date from the gallery (it got the day it was added instead).
 */
object MediaDates {
    /** Pure: EXIF "2023:06:14 17:22:05" (the phone's local time) → ms, or 0. Rejects zeros and dates out of range. */
    fun parseExif(s: String?, zone: TimeZone = TimeZone.getDefault()): Long {
        val t = s?.trim().orEmpty()
        if (t.length < 19 || t.startsWith("0000")) return 0L
        return try {
            val ms = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).apply { timeZone = zone; isLenient = false }.parse(t.take(19))?.time ?: 0L
            if (plausible(ms)) ms else 0L
        } catch (_: Exception) { 0L }
    }

    /** Pure: a video's date "20230614T172205.000Z" (UTC) → ms, or 0. Videos without a date often say 1904 or 1970. */
    fun parseVideo(s: String?): Long {
        val t = s?.trim().orEmpty()
        if (t.length < 15) return 0L
        return try {
            val ms = SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC"); isLenient = false }.parse(t.take(15))?.time ?: 0L
            if (plausible(ms)) ms else 0L
        } catch (_: Exception) { 0L }
    }

    /** Between 1990 and a day from now. */
    fun plausible(ms: Long, now: Long = System.currentTimeMillis()) = ms > 631_152_000_000L && ms < now + 86_400_000L

    /** The date written inside the file, or 0. Background thread only. */
    fun fromFile(ctx: Context, uri: Uri, video: Boolean): Long = try {
        if (video) {
            val mmr = MediaMetadataRetriever()
            try { mmr.setDataSource(ctx, uri); parseVideo(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)) } finally { try { mmr.release() } catch (_: Exception) { } }
        } else {
            ctx.contentResolver.openInputStream(uri)?.use { s ->
                @Suppress("DEPRECATION")
                val e = android.media.ExifInterface(s)
                parseExif(e.getAttribute(android.media.ExifInterface.TAG_DATETIME_ORIGINAL))
                    .takeIf { it > 0 } ?: parseExif(e.getAttribute(android.media.ExifInterface.TAG_DATETIME))
            } ?: 0L
        }
    } catch (_: Exception) { 0L }

    /**
     * Once: moments added from the gallery or Google Photos that got the day they were added (no date was found
     * then) get the date written in the file, when there is one. Moments whose date the parent set aren't touched.
     */
    fun repairOnce(ctx: Context) {
        val prefs = ctx.getSharedPreferences("regaim", Context.MODE_PRIVATE)
        if (prefs.getBoolean("dates_repaired_v1", false)) return
        // Also Google Photos copies: the gallery often dated them by the day they were copied.
        for (m in MemoryStore.all(ctx).filter { it.timeSource == "imported" || (it.origin == "import" && it.name.startsWith("gphotos_")) }) {
            val uri = if (m.uri.startsWith("content://") || m.uri.startsWith("file://")) Uri.parse(m.uri) else Uri.fromFile(java.io.File(m.uri))
            val real = fromFile(ctx, uri, m.isVideo)
            if (real > 0 && kotlin.math.abs(real - m.time) > 86_400_000L) MemoryStore.modify(ctx, m.id) { it.copy(time = real, timeSource = "captured") }
        }
        prefs.edit().putBoolean("dates_repaired_v1", true).apply()
    }
}
