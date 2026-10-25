package com.regaim.app

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.tanh

/**
 * The film's sound, mixed on the phone into one WAV: the moment's own voices and laughs (each scene gets the
 * sound from its own spot in the clip), with soft music underneath.
 * One ready track is far more reliable than asking the video exporter to mix several sources (that is what
 * silently fell back to "music only").
 */
object AudioMix {
    const val RATE = 44_100

    /** One piece of the film: [atMs] in the original clip, [lengthMs] long. */
    class Part(val atMs: Long, val lengthMs: Long)

    /** The original sound for each part, back to back, at [RATE] mono. Null if the clip has no sound. Background thread. */
    fun voices(ctx: Context, m: Memory, parts: List<Part>): FloatArray? {
        val uri = if (m.uri.startsWith("content://") || m.uri.startsWith("file://")) Uri.parse(m.uri) else Uri.fromFile(File(m.uri))
        val total = parts.sumOf { (it.lengthMs * RATE / 1000L).toInt() }
        val out = FloatArray(total)
        var pos = 0
        var any = false
        for (p in parts) {
            val n = (p.lengthMs * RATE / 1000L).toInt()
            // Start a moment before the scene's frame, so the laugh that made it isn't cut.
            val from = (p.atMs - 1000L).coerceAtLeast(0L)
            val piece = try { decode(ctx, uri, from, p.lengthMs) } catch (_: Exception) { null }
            if (piece != null && piece.isNotEmpty()) {
                any = true
                System.arraycopy(piece, 0, out, pos, minOf(n, piece.size))
                // Short fades at the joins, so scenes don't click.
                val fade = minOf(RATE / 20, n / 4)
                for (i in 0 until fade) {
                    val g = i.toFloat() / fade
                    if (pos + i < out.size) out[pos + i] *= g
                    val j = pos + minOf(n, piece.size) - 1 - i
                    if (j >= pos && j < out.size) out[j] *= g
                }
            }
            pos += n
        }
        if (!any) return null
        // Quiet phone recordings: bring the voices up (at most ×3), never past 0.9.
        val peak = out.maxOfOrNull { abs(it) } ?: 0f
        if (peak in 0.001f..0.9f) {
            val g = minOf(3f, 0.9f / peak)
            for (i in out.indices) out[i] *= g
        }
        return out
    }

    /** Writes the final track: voices (if any) + music at [musicGain] (if a mood was picked). */
    fun write(mood: Music.Mood?, voices: FloatArray?, durationMs: Long, musicGain: Float, out: File) {
        val frames = (durationMs.coerceAtLeast(1000L) * RATE / 1000L).toInt()
        val loop = mood?.let { Music.build(it) }
        val fadeIn = (1.0 * RATE).toInt().coerceAtMost(frames / 2)
        val fadeOut = (2.5 * RATE).toInt().coerceAtMost(frames / 2)
        val bytes = ByteArray(frames * 2)
        for (i in 0 until frames) {
            var mg = musicGain
            if (i < fadeIn) mg *= i.toFloat() / fadeIn
            val left = frames - i
            if (left < fadeOut) mg *= left.toFloat() / fadeOut
            val music = if (loop != null) loop[i % loop.size] / 32768f * mg else 0f
            val voice = if (voices != null && i < voices.size) voices[i] else 0f
            // A soft limiter: loud laughs on top of music never crackle.
            val v = (tanh((voice + music) * 1.1f) * 32000f).toInt().coerceIn(-32767, 32767)
            bytes[i * 2] = (v and 0xff).toByte()
            bytes[i * 2 + 1] = ((v shr 8) and 0xff).toByte()
        }
        FileOutputStream(out).use { f ->
            fun le(v: Int, n: Int) = ByteArray(n) { ((v shr (8 * it)) and 0xff).toByte() }
            f.write("RIFF".toByteArray()); f.write(le(36 + bytes.size, 4)); f.write("WAVE".toByteArray())
            f.write("fmt ".toByteArray()); f.write(le(16, 4)); f.write(le(1, 2)); f.write(le(1, 2))
            f.write(le(RATE, 4)); f.write(le(RATE * 2, 4)); f.write(le(2, 2)); f.write(le(16, 2))
            f.write("data".toByteArray()); f.write(le(bytes.size, 4)); f.write(bytes)
        }
    }

    /** A piece of a memory's own sound (mono, [RATE]), or null when it has none. Background thread only. */
    fun segment(ctx: Context, m: Memory, fromMs: Long, lengthMs: Long): FloatArray? {
        val uri = if (m.uri.startsWith("content://") || m.uri.startsWith("file://")) Uri.parse(m.uri) else Uri.fromFile(File(m.uri))
        return try { decode(ctx, uri, fromMs, lengthMs).takeIf { it.isNotEmpty() } } catch (_: Exception) { null }
    }

    /**
     * The reel's soundtrack: the composed score, with the moments' own sound (laughs, voices) on the shots that keep it.
     * The music ducks softly under them. Writes a stereo WAV of the whole film.
     */
    fun reel(ctx: Context, plan: ReelPlan, byId: Map<String, Memory>, music: ReelScore.Track, out: File) {
        val n = music.frames
        val l = music.left.copyOf()
        val r = music.right.copyOf()
        val duck = FloatArray(n) { 1f }
        for (s in plan.shots) {
            if (!s.keepSound) continue
            val m = byId[s.id] ?: continue
            val v = segment(ctx, m, s.sourceStartMs, s.lengthMs) ?: continue
            var peak = 1e-4f
            for (x in v) peak = maxOf(peak, abs(x))
            val g = minOf(3f, 0.8f / peak)
            val at = (s.atMs * RATE / 1000L).toInt()
            val fade = RATE / 25
            for (i in v.indices) {
                val k = at + i
                if (k >= n) break
                var e = 1f
                if (i < fade) e = i.toFloat() / fade
                if (v.size - i < fade) e = (v.size - i).toFloat() / fade
                val x = v[i] * g * e * 0.9f
                l[k] += x; r[k] += x
            }
            // Music steps back to ~35% under the voices, with 0.25 s ramps.
            val ramp = RATE / 4
            for (i in -ramp until v.size + ramp) {
                val k = at + i
                if (k < 0 || k >= n) continue
                val d = when {
                    i < 0 -> 1f - 0.65f * (i + ramp).toFloat() / ramp
                    i >= v.size -> 1f - 0.65f * (v.size + ramp - i).toFloat() / ramp
                    else -> 0.35f
                }
                duck[k] = minOf(duck[k], d)
            }
        }
        val bytes = ByteArray(n * 4)
        for (i in 0 until n) {
            val ml = music.left[i] * duck[i] + (l[i] - music.left[i])
            val mr = music.right[i] * duck[i] + (r[i] - music.right[i])
            val a = (tanh(ml * 1.05f) * 32000f).toInt().coerceIn(-32767, 32767)
            val b = (tanh(mr * 1.05f) * 32000f).toInt().coerceIn(-32767, 32767)
            bytes[i * 4] = (a and 0xff).toByte(); bytes[i * 4 + 1] = ((a shr 8) and 0xff).toByte()
            bytes[i * 4 + 2] = (b and 0xff).toByte(); bytes[i * 4 + 3] = ((b shr 8) and 0xff).toByte()
        }
        FileOutputStream(out).use { f ->
            fun le(v: Int, k: Int) = ByteArray(k) { ((v shr (8 * it)) and 0xff).toByte() }
            f.write("RIFF".toByteArray()); f.write(le(36 + bytes.size, 4)); f.write("WAVE".toByteArray())
            f.write("fmt ".toByteArray()); f.write(le(16, 4)); f.write(le(1, 2)); f.write(le(2, 2))
            f.write(le(RATE, 4)); f.write(le(RATE * 4, 4)); f.write(le(4, 2)); f.write(le(16, 2))
            f.write("data".toByteArray()); f.write(le(bytes.size, 4)); f.write(bytes)
        }
    }

    /** [lengthMs] of the clip's sound from [fromMs], mono, at [RATE]. */
    private fun decode(ctx: Context, uri: Uri, fromMs: Long, lengthMs: Long): FloatArray {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        val want = (lengthMs * RATE / 1000L).toInt()
        val out = FloatArray(want)
        var filled = 0
        try {
            ex.setDataSource(ctx, uri, null)
            val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?: return FloatArray(0)
            ex.selectTrack(track)
            val format = ex.getTrackFormat(track)
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var isFloat = false
            val startUs = fromMs * 1000L
            ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val c = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec = c
            c.configure(format, null, null, 0)
            c.start()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var pos = 0.0 // position in the source-rate stream, relative to startUs
            var srcIndex = 0L // source samples seen after startUs
            var prev = 0f
            while (filled < want) {
                if (!inputDone) {
                    val inIdx = c.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val size = ex.readSampleData(c.getInputBuffer(inIdx)!!, 0)
                        if (size < 0) { c.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else { c.queueInputBuffer(inIdx, 0, size, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val outIdx = c.dequeueOutputBuffer(info, 10_000)
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = c.outputFormat
                    rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    isFloat = f.containsKey(MediaFormat.KEY_PCM_ENCODING) && f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                } else if (outIdx >= 0) {
                    if (info.size > 0) {
                        val buf = c.getOutputBuffer(outIdx)!!
                        buf.position(info.offset); buf.limit(info.offset + info.size)
                        val ch = channels.coerceAtLeast(1)
                        val mono: FloatArray = if (isFloat) {
                            val fb = buf.order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
                            FloatArray(fb.remaining() / ch) { i -> var s = 0f; for (k in 0 until ch) s += fb.get(i * ch + k); s / ch }
                        } else {
                            val sb = buf.order(java.nio.ByteOrder.nativeOrder()).asShortBuffer()
                            FloatArray(sb.remaining() / ch) { i -> var s = 0f; for (k in 0 until ch) s += sb.get(i * ch + k); s / ch / 32768f }
                        }
                        // Skip what's before the start (the seek lands a little early), then resample to RATE.
                        var skip = 0
                        val bufStartUs = info.presentationTimeUs
                        if (bufStartUs < startUs) skip = (((startUs - bufStartUs) * rate) / 1_000_000L).toInt().coerceIn(0, mono.size)
                        val step = rate.toDouble() / RATE
                        for (i in skip until mono.size) {
                            val sample = mono[i]
                            // Linear interpolation between prev and this sample for every output point that falls here.
                            while (pos <= srcIndex && filled < want) {
                                val frac = (1.0 - (srcIndex - pos)).toFloat().coerceIn(0f, 1f)
                                out[filled++] = prev + (sample - prev) * frac
                                pos += step
                            }
                            prev = sample
                            srcIndex++
                            if (filled >= want) break
                        }
                    }
                    c.releaseOutputBuffer(outIdx, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                }
            }
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            ex.release()
        }
        return if (filled == want) out else out.copyOf(filled)
    }
}
