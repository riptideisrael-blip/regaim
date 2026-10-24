package com.regaim.app

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.graphics.Bitmap
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Cuts "15 seconds before + 15 seconds after" out of the rolling buffers into an MP4 in the gallery. */
object ClipSaver {
    /** Seconds kept before / after the trigger; set from Settings. */
    @Volatile var PRE_US = 15_000_000L
    @Volatile var POST_US = 15_000_000L

    /** Returns the saved clip, or null if there was nothing to save. */
    fun save(
        ctx: Context,
        video: List<EncodedSample>,
        audio: List<EncodedSample>,
        videoFormat: MediaFormat,
        audioFormat: MediaFormat?,
        rotation: Int,
        triggerUs: Long,
        label: String,
    ): Saved? {
        if (video.isEmpty()) return null
        val from = triggerUs - PRE_US
        val to = triggerUs + POST_US

        // A video clip must start on a key frame: take the last key frame at or before "from".
        var startIdx = -1
        for (i in video.indices) {
            val s = video[i]
            if (s.ptsUs > from) break
            if (s.isKey) startIdx = i
        }
        if (startIdx == -1) startIdx = video.indexOfFirst { it.isKey }
        if (startIdx == -1) return null

        val videoPart = video.subList(startIdx, video.size).filter { it.ptsUs <= to }
        if (videoPart.isEmpty()) return null
        val base = videoPart.first().ptsUs
        val end = videoPart.last().ptsUs
        val audioPart = audio.filter { it.ptsUs in base..end }

        val tmp = File(ctx.cacheDir, "clip_${System.currentTimeMillis()}.mp4")
        val muxer = MediaMuxer(tmp.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            muxer.setOrientationHint(rotation)
            val videoTrack = muxer.addTrack(videoFormat)
            val audioTrack = if (audioFormat != null && audioPart.isNotEmpty()) muxer.addTrack(audioFormat) else -1
            muxer.start()

            val info = MediaCodec.BufferInfo()
            var vi = 0
            var ai = 0
            // Write both tracks interleaved by time.
            while (vi < videoPart.size || (audioTrack >= 0 && ai < audioPart.size)) {
                val takeVideo = when {
                    vi >= videoPart.size -> false
                    audioTrack < 0 || ai >= audioPart.size -> true
                    else -> videoPart[vi].ptsUs <= audioPart[ai].ptsUs
                }
                val s = if (takeVideo) videoPart[vi++] else audioPart[ai++]
                info.set(0, s.data.size, s.ptsUs - base, s.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME)
                muxer.writeSampleData(if (takeVideo) videoTrack else audioTrack, ByteBuffer.wrap(s.data), info)
            }
            muxer.stop()
        } finally {
            try {
                muxer.release()
            } catch (_: Exception) {
            }
        }

        val safeLabel = label.replace(Regex("[^A-Za-z0-9]+"), "-").trim('-').lowercase(Locale.US)
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val name = "regaim_${stamp}_$safeLabel.mp4"
        return try {
            // Grab the frame at the moment itself for the memory bubble.
            var durationMs = 0L
            var frame: Bitmap? = null
            try {
                val mmr = MediaMetadataRetriever()
                mmr.setDataSource(tmp.absolutePath)
                durationMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                val atUs = (triggerUs - base).coerceIn(0L, durationMs * 1000L)
                frame = mmr.getFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                mmr.release()
            } catch (_: Exception) {
            }
            val uri = publish(ctx, tmp, name)
            Saved(name, uri, durationMs, frame, ((triggerUs - base) / 1000).coerceIn(0, durationMs))
        } finally {
            tmp.delete()
        }
    }

    class Saved(val name: String, val uri: String, val durationMs: Long, val frame: Bitmap?, val highlightMs: Long)

    /** Moments' own folder: moments kept only inside the app (not in the phone's gallery, so not backed up to Google Photos). */
    fun privateDir(ctx: Context) = File(ctx.filesDir, "moments_media").apply { mkdirs() }

    /**
     * Whether a copy of each moment goes to the phone's gallery (Movies/Moments). Off for a new family (private by
     * default); a phone that already kept moments the old way keeps doing so until the parent turns it off.
     */
    fun toGallery(ctx: Context): Boolean {
        val p = Settings.prefs(ctx)
        if (!p.contains("save_to_gallery")) {
            val before = try { MemoryStore.kept(ctx).any { it.uri.startsWith("content://") } } catch (_: Exception) { false }
            p.edit().putBoolean("save_to_gallery", before).apply()
        }
        return p.getBoolean("save_to_gallery", false)
    }

    fun setToGallery(ctx: Context, on: Boolean) = Settings.prefs(ctx).edit().putBoolean("save_to_gallery", on).apply()

    /** A moment's file in the app's own folder (deleted with the moment; a gallery file stays the family's). */
    fun isPrivate(ctx: Context, path: String) = PrivateMedia.inside(path, privateDir(ctx).absolutePath)

    fun publish(ctx: Context, file: File, name: String): String {
        if (!toGallery(ctx)) {
            val dest = File(privateDir(ctx), name)
            file.copyTo(dest, overwrite = true)
            return dest.absolutePath
        }
        if (Build.VERSION.SDK_INT >= 29) {
            val resolver = ctx.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Moments")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("gallery insert failed")
            val out = resolver.openOutputStream(uri) ?: throw IOException("gallery open failed")
            out.use { o -> file.inputStream().use { it.copyTo(o) } }
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri.toString()
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "Moments")
            dir.mkdirs()
            val dest = File(dir, name)
            file.copyTo(dest, overwrite = true)
            MediaScannerConnection.scanFile(ctx, arrayOf(dest.absolutePath), arrayOf("video/mp4"), null)
            return dest.absolutePath
        }
    }
}

/** Trims on actual keyframe boundaries and keeps imported originals intact. */
object ClipTrimmer {
    fun trim(ctx: Context, m: Memory, startMs: Long, endMs: Long): Memory {
        require(m.isVideo && startMs >= 0 && endMs > startMs) { L.t("בחרו טווח תקין בסרטון", "Choose a valid range in the video") }
        val src = if (m.uri.startsWith("content://")) android.net.Uri.parse(m.uri) else android.net.Uri.fromFile(File(m.uri))
        val ex = android.media.MediaExtractor()
        val tmp = File(ctx.cacheDir, "trim_${System.currentTimeMillis()}.mp4")
        var muxer: MediaMuxer? = null
        var baseUs = -1L
        var lastUs = 0L
        var videoTrack = -1
        try {
            ex.setDataSource(ctx, src, null)
            val rotation = try {
                val mmr = MediaMetadataRetriever()
                try {
                    mmr.setDataSource(ctx, src)
                    mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                } finally { mmr.release() }
            } catch (_: Exception) { 0 }
            val mx = MediaMuxer(tmp.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = mx
            mx.setOrientationHint(rotation)
            val map = HashMap<Int, Int>()
            for (i in 0 until ex.trackCount) {
                val format = ex.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                    ex.selectTrack(i)
                    map[i] = mx.addTrack(format)
                    if (mime.startsWith("video/")) videoTrack = i
                }
            }
            require(videoTrack >= 0) { L.t("לא נמצא סרטון בקובץ", "No video found in this file") }
            mx.start()
            ex.seekTo(startMs * 1000, android.media.MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val buf = ByteBuffer.allocate(4 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            while (true) {
                val track = ex.sampleTrackIndex
                if (track < 0 || ex.sampleTime > endMs * 1000) break
                val t = ex.sampleTime
                if (baseUs < 0) {
                    // Audio can be returned before the video sync frame by MediaExtractor.
                    if (track != videoTrack || (ex.sampleFlags and android.media.MediaExtractor.SAMPLE_FLAG_SYNC) == 0) {
                        ex.advance()
                        continue
                    }
                    baseUs = t
                }
                val size = ex.readSampleData(buf, 0)
                if (size < 0) break
                val out = map[track]
                if (out != null && t >= baseUs) {
                    val sync = (ex.sampleFlags and android.media.MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                    info.set(0, size, t - baseUs, if (sync) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                    mx.writeSampleData(out, buf, info)
                    lastUs = maxOf(lastUs, t - baseUs)
                }
                ex.advance()
            }
            require(baseUs >= 0 && lastUs > 0) { L.t("לא נמצאו תמונות בטווח שנבחר", "No frames found in the selected range") }
            mx.stop()
        } catch (e: Exception) {
            tmp.delete()
            throw e
        } finally {
            try { muxer?.release() } catch (_: Exception) {}
            ex.release()
        }

        return try {
            var actualDurationMs = lastUs / 1000
            var frame: Bitmap? = null
            val baseMs = baseUs / 1000
            var marker = if (m.highlightMs >= baseMs && m.highlightMs <= baseMs + actualDurationMs) m.highlightMs - baseMs else -1
            try {
                val mmr = MediaMetadataRetriever()
                try {
                    mmr.setDataSource(tmp.absolutePath)
                    actualDurationMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: actualDurationMs
                    marker = if (m.highlightMs >= baseMs && m.highlightMs <= baseMs + actualDurationMs) m.highlightMs - baseMs else -1
                    val atMs = if (marker >= 0) marker else actualDurationMs / 2
                    frame = mmr.getFrameAtTime(atMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST)
                } finally { mmr.release() }
            } catch (_: Exception) {}
            val name = "regaim_${System.currentTimeMillis()}_cut.mp4"
            // A moment kept in the app's own folder is cut there too (never into the gallery).
            val newUri = if (Build.VERSION.SDK_INT >= 29 && !ClipSaver.isPrivate(ctx, m.uri)) {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, name)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Moments")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                val uri = ctx.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                    ?: throw IOException("gallery insert failed")
                try {
                    val stream = ctx.contentResolver.openOutputStream(uri) ?: throw IOException("gallery open failed")
                    stream.use { o -> tmp.inputStream().use { it.copyTo(o) } }
                    values.clear()
                    values.put(MediaStore.Video.Media.IS_PENDING, 0)
                    ctx.contentResolver.update(uri, values, null, null)
                    uri.toString()
                } catch (e: Exception) {
                    try { ctx.contentResolver.delete(uri, null, null) } catch (_: Exception) {}
                    throw e
                }
            } else {
                val dir = if (m.origin == "import") File(ctx.filesDir, "edited_media").apply { mkdirs() } else File(m.uri).parentFile
                val dest = File(dir, name)
                tmp.copyTo(dest, overwrite = true)
                if (m.origin != "import" && !ClipSaver.isPrivate(ctx, dest.absolutePath)) MediaScannerConnection.scanFile(ctx, arrayOf(dest.absolutePath), arrayOf("video/mp4"), null)
                dest.absolutePath
            }
            // Existing captures are replaced; a document picked from the user's gallery is never deleted.
            if (m.origin != "import") {
                try {
                    if (m.uri.startsWith("content://")) ctx.contentResolver.delete(src, null, null) else File(m.uri).delete()
                } catch (_: Exception) {}
            }
            val selected = m.previewWindow?.takeIf { it.isSelected && it.startMs >= baseMs && it.endMs <= baseMs + actualDurationMs }
            val bubble = frame?.let { try { MemoryStore.saveBubble(ctx, m.id, it) } catch (_: Exception) { null } } ?: m.thumb
            m.copy(
                uri = newUri, name = name, durationMs = actualDurationMs, highlightMs = marker, thumb = bubble,
                highlightStartMs = selected?.let { it.startMs - baseMs } ?: -1,
                highlightEndMs = selected?.let { it.endMs - baseMs } ?: -1,
                highlightSource = if (selected != null) "user" else "",
            )
        } finally { tmp.delete() }
    }
}
