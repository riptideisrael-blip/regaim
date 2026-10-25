package com.regaim.app

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaPlayer

/**
 * Real recorded music the family's videos and stories use (licensed tracks the owner added, kept inside the app,
 * nothing downloaded). Where none fits, the music composed on the phone ([Music], [ReelScore]) is used as before.
 * Credits: assets/music/CREDITS.txt.
 */
object LicensedMusic {
    enum class Piece(val res: Int, private val he: String, private val en: String) {
        NOSTALGIC_PIANO(R.raw.music_nostalgic_piano, "פסנתר נוסטלגי", "Nostalgic Piano"),
        THE_WAY_HOME(R.raw.music_the_way_home, "הדרך הביתה", "The Way Home"),
        GENTLE_CINEMATIC(R.raw.music_gentle_cinematic, "פסנתר קולנועי עדין", "Gentle Cinematic Piano");
        val title: String get() = L.t(he, en)
    }

    /** Pure: the piece for a week/month video of [style]; [seed] (another version) alternates between two. */
    fun forStyle(style: ReelStyle, seed: Int): Piece? = when (style) {
        ReelStyle.EMOTIONAL -> if (seed % 2 == 0) Piece.NOSTALGIC_PIANO else Piece.THE_WAY_HOME
        ReelStyle.CINEMATIC -> if (seed % 2 == 0) Piece.GENTLE_CINEMATIC else Piece.THE_WAY_HOME
        ReelStyle.HAPPY -> null // none of the pieces is upbeat: the composed happy music stays
    }

    /** Pure: the piece that plays under a story of [mood]. */
    fun forMood(mood: Music.Mood): Piece? = when (mood) {
        Music.Mood.NOSTALGIC -> Piece.NOSTALGIC_PIANO
        Music.Mood.WARM -> Piece.THE_WAY_HOME
        Music.Mood.CALM -> Piece.GENTLE_CINEMATIC
        Music.Mood.HAPPY -> null
    }

    /**
     * [piece] as a stereo track of exactly [frames] samples at 44.1 kHz for a video: looped if the video is longer,
     * a soft fade in and a 2.5 s fade out at the end. Null if it can't be decoded (then the composed music is used).
     * Background thread only.
     */
    fun track(ctx: Context, piece: Piece, frames: Int): ReelScore.Track? {
        val pcm = decode(ctx, piece, frames) ?: return null
        val (l, r) = pcm
        if (l.isEmpty()) return null
        val outL = FloatArray(frames); val outR = FloatArray(frames)
        val fadeIn = (0.8 * AudioMix.RATE).toInt().coerceAtMost(frames / 2)
        val fadeOut = (2.5 * AudioMix.RATE).toInt().coerceAtMost(frames / 2)
        for (i in 0 until frames) {
            var g = 1f
            if (i < fadeIn) g *= i.toFloat() / fadeIn
            if (frames - i < fadeOut) g *= (frames - i).toFloat() / fadeOut
            val k = i % l.size
            outL[i] = l[k] * g; outR[i] = r[k] * g
        }
        return ReelScore.Track(outL, outR)
    }

    /** Up to [limit] frames of the piece as float stereo at 44.1 kHz (only what the video needs), or null. */
    private fun decode(ctx: Context, piece: Piece, limit: Int): Pair<FloatArray, FloatArray>? {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ctx.resources.openRawResourceFd(piece.res).use { fd -> ex.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length) }
            val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: return null
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            if (fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) != AudioMix.RATE) return null
            val c = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec = c
            c.configure(fmt, null, null, 0)
            c.start()
            val durUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
            val cap = minOf(limit.toLong(), if (durUs > 0) durUs * AudioMix.RATE / 1_000_000L + AudioMix.RATE else limit.toLong()).toInt().coerceAtLeast(1)
            var left = FloatArray(cap); var right = FloatArray(cap)
            var filled = 0
            var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val info = MediaCodec.BufferInfo()
            var inDone = false
            var outDone = false
            while (!outDone) {
                if (!inDone) {
                    val i = c.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buf = c.getInputBuffer(i)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) { c.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                        else { c.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val o = c.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    channels = c.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                } else if (o >= 0) {
                    val buf = c.getOutputBuffer(o)!!.order(java.nio.ByteOrder.nativeOrder()).asShortBuffer()
                    val n = info.size / 2
                    var k = 0
                    while (k + channels - 1 < n && filled < limit) {
                        if (filled == left.size) { left = left.copyOf(minOf(limit, left.size * 2)); right = right.copyOf(left.size) }
                        val a = buf.get(k) / 32768f
                        val b = if (channels > 1) buf.get(k + 1) / 32768f else a
                        left[filled] = a; right[filled] = b; filled++
                        k += channels
                    }
                    c.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 || filled >= limit) outDone = true
                }
            }
            if (filled == 0) return null
            return left.copyOf(filled) to right.copyOf(filled)
        } catch (_: Exception) {
            return null
        } finally {
            try { codec?.stop() } catch (_: Exception) { }
            try { codec?.release() } catch (_: Exception) { }
            try { ex.release() } catch (_: Exception) { }
        }
    }

    // ---------- playing under a story ----------

    private var player: MediaPlayer? = null

    /** Plays [piece] in a loop (replacing what plays). False if it couldn't start. */
    @Synchronized
    fun play(ctx: Context, piece: Piece): Boolean {
        stop()
        return try {
            player = MediaPlayer.create(ctx.applicationContext, piece.res)?.apply { isLooping = true; setVolume(0.8f, 0.8f); start() }
            player != null
        } catch (_: Exception) { false }
    }

    @Synchronized fun pause() { try { player?.pause() } catch (_: Exception) { } }
    @Synchronized fun resume() { try { player?.start() } catch (_: Exception) { } }
    @Synchronized fun stop() {
        try { player?.stop() } catch (_: Exception) { }
        try { player?.release() } catch (_: Exception) { }
        player = null
    }
}
