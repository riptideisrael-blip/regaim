package com.regaim.app

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.UUID

/**
 * Memories made on purpose with the in-app camera ("this is the moment", not "a moment that was").
 * Background thread only. Each function saves the file in the phone's gallery (folder Moments)
 * and adds the memory to the list.
 */
object NewMemory {
    /** The longest story video. */
    const val MAX_STORY_MS = 60_000L

    private fun stamp(time: Long) = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(java.util.Date(time))

    private fun lastLocation(ctx: Context): Location? {
        if (ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
        return try {
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            lm.getProviders(true)
                .mapNotNull { p -> try { lm.getLastKnownLocation(p) } catch (_: SecurityException) { null } }
                .filter { System.currentTimeMillis() - it.time < 6 * 3600_000L }
                .maxByOrNull { it.time }
        } catch (_: Exception) { null }
    }

    private fun placeName(ctx: Context, loc: Location?): String {
        if (loc == null || !Geocoder.isPresent()) return ""
        return try {
            @Suppress("DEPRECATION")
            val a = Geocoder(ctx, L.locale).getFromLocation(loc.latitude, loc.longitude, 1)?.firstOrNull() ?: return ""
            listOfNotNull(a.thoroughfare, a.locality).joinToString(", ").ifEmpty { a.featureName ?: "" }
        } catch (_: Exception) { "" }
    }

    private fun add(ctx: Context, uri: String, name: String, time: Long, durationMs: Long, cover: Bitmap?, video: Boolean): Memory {
        val id = UUID.randomUUID().toString()
        val bubble = cover?.let { try { MemoryStore.saveBubble(ctx, id, it) } catch (_: Exception) { "" } }.orEmpty()
        val loc = lastLocation(ctx)
        val memory = Memory(
            id = id, uri = uri, name = name, time = time, label = "manual",
            lat = loc?.latitude, lon = loc?.longitude, place = placeName(ctx, loc),
            thumb = bubble, durationMs = durationMs, chosen = true,
            highlightMs = -1,
            mediaType = if (video) "video" else "image",
            timeSource = "captured",
            origin = "camera",
        )
        try {
            MemoryStore.add(ctx, memory)
        } catch (e: Exception) {
            if (bubble.isNotEmpty()) File(bubble).delete()
            throw e
        }
        return memory
    }

    /** A picture from the camera, as JPEG bytes (already upright). */
    fun photo(ctx: Context, jpeg: ByteArray): Memory {
        val now = System.currentTimeMillis()
        val name = "Moments_${stamp(now)}.jpg"
        val tmp = File(ctx.cacheDir, name)
        try {
            tmp.writeBytes(jpeg)
            val uri = publishPhoto(ctx, tmp, name)
            return add(ctx, uri, name, now, 0L, MediaImages.decode(ctx, uri, 720), false)
        } finally {
            tmp.delete()
        }
    }

    /** A finished story video (mp4). The file is deleted afterwards. */
    fun video(ctx: Context, file: File): Memory {
        val now = System.currentTimeMillis()
        val name = "Moments_${stamp(now)}.mp4"
        try {
            var dur = 0L
            var frame: Bitmap? = null
            val mmr = MediaMetadataRetriever()
            try {
                mmr.setDataSource(file.absolutePath)
                dur = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                frame = mmr.getFrameAtTime(dur * 500, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } finally {
                try { mmr.release() } catch (_: Exception) {}
            }
            if (dur <= 0L) throw IOException(L.t("הסרטון ריק", "The video is empty"))
            val uri = ClipSaver.publish(ctx, file, name)
            return add(ctx, uri, name, now, dur, frame, true)
        } finally {
            file.delete()
        }
    }

    private fun publishPhoto(ctx: Context, file: File, name: String): String {
        if (!ClipSaver.toGallery(ctx)) {
            val dest = File(ClipSaver.privateDir(ctx), name)
            file.copyTo(dest, overwrite = true)
            return dest.absolutePath
        }
        if (Build.VERSION.SDK_INT >= 29) {
            val resolver = ctx.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Moments")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: throw IOException("gallery insert failed")
            val out = resolver.openOutputStream(uri) ?: throw IOException("gallery open failed")
            out.use { o -> file.inputStream().use { it.copyTo(o) } }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri.toString()
        }
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Moments")
        dir.mkdirs()
        val dest = File(dir, name)
        file.copyTo(dest, overwrite = true)
        MediaScannerConnection.scanFile(ctx, arrayOf(dest.absolutePath), arrayOf("image/jpeg"), null)
        return dest.absolutePath
    }
}
