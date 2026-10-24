package com.regaim.app

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.format.DateUtils
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

/**
 * "השבוע שלנו": joins the heart of the week's best moments (a few seconds around each trigger) into one video.
 * No re-encoding: clips recorded by Moments share the same format, so their samples are copied one after another.
 * (Music and captions are not added yet.)
 */
object ReelMaker {
    private const val SECONDS_EACH = 6
    private const val MAX_MOMENTS = 6

    fun weekMoments(ctx: Context): List<Memory> {
        val since = System.currentTimeMillis() - 7 * DateUtils.DAY_IN_MILLIS
        return MemoryStore.kept(ctx).filter { it.time >= since }
    }

    /** Best first: milestones, favorites, moments you saved yourself, then the AI's score. */
    fun best(list: List<Memory>): List<Memory> = list.sortedByDescending {
        (if (it.milestone.isNotEmpty()) 30 else 0) + (if (it.favorite) 20 else 0) + (if (it.chosen) 10 else 0) + it.score
    }

    class Reel(val uri: String, val count: Int)

    /** Background thread only. */
    fun make(ctx: Context, moments: List<Memory>): Reel {
        val picked = best(moments.filter { it.isVideo }).take(MAX_MOMENTS).sortedBy { it.time }
        if (picked.size < 2) throw IllegalStateException(L.t("צריך לפחות 2 רגעים", "You need at least 2 moments"))
        val tmp = File(ctx.cacheDir, "week_${System.currentTimeMillis()}.mp4")
        var muxer: MediaMuxer? = null
        var videoOut = -1
        var audioOut = -1
        var videoFmt: MediaFormat? = null
        var audioFmt: MediaFormat? = null
        var outputRotation = 0
        var offsetUs = 0L
        var used = 0
        val buf = ByteBuffer.allocate(4 * 1024 * 1024)
        val info = MediaCodec.BufferInfo()
        try {
            for (m in picked) {
                val ex = MediaExtractor()
                try {
                    ex.setDataSource(ctx, uriOf(m), null)
                    var vIn = -1
                    var aIn = -1
                    for (i in 0 until ex.trackCount) {
                        val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                        if (mime.startsWith("video/") && vIn < 0) vIn = i
                        if (mime.startsWith("audio/") && aIn < 0) aIn = i
                    }
                    if (vIn < 0) continue
                    val vf = ex.getTrackFormat(vIn)
                    val af = if (aIn >= 0) ex.getTrackFormat(aIn) else null
                    val rotation = rotationOf(ctx, m)
                    if (muxer == null) {
                        muxer = MediaMuxer(tmp.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).also { mx ->
                            mx.setOrientationHint(rotation)
                            videoOut = mx.addTrack(vf)
                            if (aIn >= 0) audioOut = mx.addTrack(ex.getTrackFormat(aIn))
                            mx.start()
                        }
                        videoFmt = vf
                        audioFmt = af
                        outputRotation = rotation
                    } else if (!sameFormat(videoFmt!!, vf) || !sameAudioFormat(audioFmt, af) || outputRotation != rotation) {
                        continue // recorded in another quality: can't be joined without re-encoding
                    }
                    ex.selectTrack(vIn)
                    if (aIn >= 0 && audioOut >= 0) ex.selectTrack(aIn)

                    val durUs = if (vf.containsKey(MediaFormat.KEY_DURATION)) vf.getLong(MediaFormat.KEY_DURATION) else m.durationMs * 1000
                    val selected = m.previewWindow?.takeIf { it.isSelected }
                    val mid = (m.highlight * 1000).coerceIn(0, durUs) // capture marker or a neutral midpoint
                    val startUs = selected?.let { it.startMs * 1000 } ?: (mid - SECONDS_EACH * 500_000L).coerceAtLeast(0)
                    val endUs = selected?.let { it.endMs * 1000 } ?: (mid + SECONDS_EACH * 500_000L).coerceAtMost(durUs)
                    ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    var base = -1L
                    var last = 0L
                    while (true) {
                        val track = ex.sampleTrackIndex
                        if (track < 0) break
                        val t = ex.sampleTime
                        if (t > endUs) break
                        val size = ex.readSampleData(buf, 0)
                        if (size < 0) break
                        if (base < 0) {
                            // Start on the video key frame.
                            if (track != vIn || (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) == 0) { ex.advance(); continue }
                            base = t
                        }
                        if (t >= base) {
                            val out = if (track == vIn) videoOut else audioOut
                            if (out >= 0) {
                                val sync = (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                                info.set(0, size, offsetUs + (t - base), if (sync) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                                muxer!!.writeSampleData(out, buf, info)
                                if (t - base > last) last = t - base
                            }
                        }
                        ex.advance()
                    }
                    if (base >= 0) {
                        offsetUs += last + 40_000
                        used++
                    }
                } catch (_: Exception) {
                    // Skip a clip that can't be read (e.g. deleted from the gallery).
                } finally {
                    ex.release()
                }
            }
        } finally {
            try { muxer?.stop() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
        }
        if (used < 2) {
            tmp.delete()
            throw IllegalStateException(L.t("לא הצלחתי לחבר את הרגעים לסרטון", "Couldn't put the moments together into a video"))
        }
        val uri = publish(ctx, tmp)
        tmp.delete()
        Settings.prefs(ctx).edit().putString("reel_uri", uri).putLong("reel_time", System.currentTimeMillis()).apply()
        return Reel(uri, used)
    }

    fun lastReel(ctx: Context): Pair<String, Long>? {
        val p = Settings.prefs(ctx)
        val uri = p.getString("reel_uri", null) ?: return null
        val t = p.getLong("reel_time", 0L)
        if (System.currentTimeMillis() - t > 7 * DateUtils.DAY_IN_MILLIS) return null
        return uri to t
    }

    private fun uriOf(m: Memory): Uri = if (m.uri.startsWith("content://")) Uri.parse(m.uri) else Uri.fromFile(File(m.uri))

    private fun rotationOf(ctx: Context, m: Memory): Int = try {
        val mmr = MediaMetadataRetriever()
        mmr.setDataSource(ctx, uriOf(m))
        val r = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        mmr.release()
        r
    } catch (_: Exception) {
        0
    }

    private fun sameFormat(a: MediaFormat, b: MediaFormat): Boolean {
        fun int(f: MediaFormat, k: String) = if (f.containsKey(k)) f.getInteger(k) else -1
        if (a.getString(MediaFormat.KEY_MIME) != b.getString(MediaFormat.KEY_MIME)) return false
        if (int(a, MediaFormat.KEY_WIDTH) != int(b, MediaFormat.KEY_WIDTH)) return false
        if (int(a, MediaFormat.KEY_HEIGHT) != int(b, MediaFormat.KEY_HEIGHT)) return false
        return sameCodecData(a, b)
    }

    private fun sameAudioFormat(a: MediaFormat?, b: MediaFormat?): Boolean {
        if (a == null || b == null) return a == null && b == null
        if (a.getString(MediaFormat.KEY_MIME) != b.getString(MediaFormat.KEY_MIME)) return false
        for (k in listOf(MediaFormat.KEY_SAMPLE_RATE, MediaFormat.KEY_CHANNEL_COUNT)) {
            if (!a.containsKey(k) || !b.containsKey(k) || a.getInteger(k) != b.getInteger(k)) return false
        }
        return sameCodecData(a, b)
    }

    private fun sameCodecData(a: MediaFormat, b: MediaFormat): Boolean = listOf("csd-0", "csd-1").all { key ->
        val ca = a.getByteBuffer(key)?.duplicate()
        val cb = b.getByteBuffer(key)?.duplicate()
        if (ca == null || cb == null) ca == null && cb == null else ca == cb
    }

    fun publish(ctx: Context, tmp: File, name: String = "Moments_week_${System.currentTimeMillis()}.mp4"): String {
        return if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Moments")
            }
            val uri = ctx.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("gallery insert failed")
            ctx.contentResolver.openOutputStream(uri)?.use { o -> tmp.inputStream().use { it.copyTo(o) } }
            uri.toString()
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "Moments").apply { mkdirs() }
            val dest = File(dir, name)
            tmp.copyTo(dest, overwrite = true)
            MediaScannerConnection.scanFile(ctx, arrayOf(dest.absolutePath), arrayOf("video/mp4"), null)
            dest.absolutePath
        }
    }
}
