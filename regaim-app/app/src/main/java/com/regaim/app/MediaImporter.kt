package com.regaim.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.File
import java.util.UUID
import kotlin.math.max

/** Adds only the photo/video that the user selected. Original media is never changed. */
object MediaImporter {
    /** [knownTime]: when the source already knows the capture time (Google Photos' createTime), it wins. */
    fun add(ctx: Context, uri: Uri, title: String = "", knownTime: Long = 0L): Memory {
        val mime = ctx.contentResolver.getType(uri)?.lowercase().orEmpty()
        require(mime.startsWith("image/") || mime.startsWith("video/")) { L.t("אפשר להוסיף תמונה או סרטון בלבד", "Only photos and videos can be added") }
        val isVideo = mime.startsWith("video/")
        var name = ""
        var capturedAt = 0L
        try {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (n >= 0 && !c.isNull(n)) name = c.getString(n)
                    val d = c.getColumnIndex(MediaStore.Images.ImageColumns.DATE_TAKEN)
                    if (d >= 0 && !c.isNull(d)) capturedAt = c.getLong(d).coerceAtLeast(0)
                }
            }
        } catch (_: Exception) { /* Optional metadata is not evidence of a capture date. */ }
        // The gallery may not know the date yet (just saved) or ever (no EXIF in its index): the source's own time,
        // then the date written inside the file, before falling back to "added today".
        if (MediaDates.plausible(knownTime)) capturedAt = knownTime
        if (capturedAt <= 0L) capturedAt = MediaDates.fromFile(ctx, uri, mime.startsWith("video/"))

        var duration = 0L
        val frame: Bitmap? = if (isVideo) {
            val mmr = MediaMetadataRetriever()
            try {
                mmr.setDataSource(ctx, uri)
                duration = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
                require(duration > 0) { L.t("לא הצלחנו לקרוא את הסרטון הזה", "Couldn't read this video") }
                // A small frame (a 4K video's full frame is ~33 MB and could run the phone out of memory).
                if (android.os.Build.VERSION.SDK_INT >= 27) mmr.getScaledFrameAtTime(duration * 500, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 960, 960)
                else mmr.getFrameAtTime(duration * 500, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } finally {
                try { mmr.release() } catch (_: Exception) {}
            }
        } else {
            MediaImages.decode(ctx, uri.toString(), 960) ?: throw IllegalArgumentException(L.t("לא הצלחנו לקרוא את התמונה הזאת", "Couldn't read this photo"))
        }
        val id = UUID.randomUUID().toString()
        val thumb = frame?.let { MemoryStore.saveBubble(ctx, id, it) }.orEmpty()
        val memory = Memory(
            id = id,
            uri = uri.toString(),
            name = name,
            time = if (capturedAt > 0) capturedAt else System.currentTimeMillis(),
            label = "נוסף מהגלריה",
            lat = null,
            lon = null,
            place = "",
            thumb = thumb,
            durationMs = duration,
            title = title.trim().take(80).ifBlank { if (isVideo) L.t("רגע שהוספתם", "A moment you added") else L.t("תמונה מהחיים שלנו", "A photo from our life") },
            chosen = true,
            // Imported media has no detector marker and no semantic highlight selection.
            highlightMs = -1,
            mediaType = if (isVideo) "video" else "image",
            timeSource = if (capturedAt > 0) "captured" else "imported",
            titleByUser = title.isNotBlank(),
            origin = "import",
        )
        try {
            MemoryStore.add(ctx, memory)
        } catch (e: Exception) {
            if (thumb.isNotEmpty()) File(thumb).delete()
            throw e
        }
        return memory
    }
}

/** Bounded, orientation-aware photo decoding shared by cards, AI and face/comic sampling. */
object MediaImages {
    fun decode(ctx: Context, path: String, maxSide: Int = 960): Bitmap? {
        val uri = if (path.startsWith("content://") || path.startsWith("file://")) Uri.parse(path) else Uri.fromFile(File(path))
        return try {
            if (Build.VERSION.SDK_INT >= 28) {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { decoder, info, _ ->
                    val scale = max(info.size.width, info.size.height).toFloat() / maxSide
                    if (scale > 1f) decoder.setTargetSize((info.size.width / scale).toInt().coerceAtLeast(1), (info.size.height / scale).toInt().coerceAtLeast(1))
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            } else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
                var sample = 1
                while (max(bounds.outWidth, bounds.outHeight) / sample > maxSide * 2) sample *= 2
                val options = BitmapFactory.Options().apply { inSampleSize = sample }
                val bitmap = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null
                @Suppress("DEPRECATION")
                val orientation = try {
                    ctx.contentResolver.openInputStream(uri)?.use {
                        android.media.ExifInterface(it).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL)
                    } ?: 1
                } catch (_: Exception) { 1 }
                val matrix = Matrix().apply {
                    when (orientation) {
                        2 -> setScale(-1f, 1f)
                        3 -> setRotate(180f)
                        4 -> setScale(1f, -1f)
                        5 -> { setRotate(90f); postScale(-1f, 1f) }
                        6 -> setRotate(90f)
                        7 -> { setRotate(270f); postScale(-1f, 1f) }
                        8 -> setRotate(270f)
                    }
                }
                val upright = if (orientation in 2..8) Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true) else bitmap
                val scale = maxSide.toFloat() / max(upright.width, upright.height)
                if (scale < 1f) Bitmap.createScaledBitmap(upright, (upright.width * scale).toInt().coerceAtLeast(1), (upright.height * scale).toInt().coerceAtLeast(1), true) else upright
            }
        } catch (_: Exception) { null }
    }
}
