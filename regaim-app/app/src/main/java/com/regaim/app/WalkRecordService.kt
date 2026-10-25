package com.regaim.app

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Surface
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.io.File

/**
 * Records a walk with Pitzki for the parent to keep and share: the screen, and the app's own sound (Pitzki's voice,
 * the song, the animals) taken straight from the app, not from the microphone. So the microphone stays free and
 * Pitzki still hears the child during a recorded walk (Android lets only one recorder hear the microphone). The
 * child is seen in the video (the round camera bubble), not heard. Before Android 10 there's no way to take the
 * app's sound: the video is silent there. Asked for each time (Android shows its own "start recording?"), kept only
 * on this phone until the parent shares it. The watermark is drawn by the lesson screen itself.
 */
class WalkRecordService : Service() {
    companion object {
        private const val CHANNEL = "pitzki_record"
        private const val ID = 7311
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        @Volatile var recording = false
            private set
        /** The video being recorded now (or just finished). */
        @Volatile var file: File? = null
            private set
        @Volatile private var instance: WalkRecordService? = null
        /** Called on the main thread when the recording couldn't start (no permission, the phone refused). */
        @Volatile var onFailed: ((String) -> Unit)? = null

        fun start(ctx: Context, code: Int, data: Intent) {
            val i = Intent(ctx, WalkRecordService::class.java).putExtra(EXTRA_CODE, code).putExtra(EXTRA_DATA, data)
            androidx.core.content.ContextCompat.startForegroundService(ctx, i)
        }

        /** Stops and returns the finished video (null if nothing was recorded; [problem] says why). */
        fun stop(): File? {
            val s = instance
            if (s == null) { problem = if (problem.isEmpty()) L.t("ההקלטה נעצרה באמצע (הטלפון סגר אותה)", "The recording was stopped by the phone") else problem; return null }
            s.finishRecording()
            s.stopSelf()
            val f = file?.takeIf { it.exists() && it.length() > 10_000 }
            if (f == null && problem.isEmpty()) problem = L.t("לא נקלט וידאו", "No video was captured")
            return f
        }

        /** Why the last recording has no video, for the parent (empty: no problem known). */
        @Volatile var problem = ""
    }

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var video: MediaCodec? = null
    private var videoSurface: Surface? = null
    private var audio: MediaCodec? = null
    private var mic: AudioRecord? = null
    private var muxer: MediaMuxer? = null
    private val lock = Object()
    private var videoTrack = -1
    private var audioTrack = -1
    private var muxing = false
    private var wantAudio = false
    @Volatile private var stopping = false
    private var videoThread: Thread? = null
    private var audioThread: Thread? = null
    private var lastVideoUs = -1L
    private var lastAudioUs = -1L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        instance = this
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm?.createNotificationChannel(NotificationChannel(CHANNEL, L.t("הקלטת טיול", "Recording a walk"), NotificationManager.IMPORTANCE_LOW))
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle(L.t("מקליטים את הטיול עם פיצקי 🎥", "Recording the walk with Pitzki 🎥"))
            .setOngoing(true).build()
        try {
            // The screen only (the sound is the app's own, through the screen recording): the microphone is Pitzki's.
            val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0
            ServiceCompat.startForeground(this, ID, n, type)
        } catch (e: Exception) { fail(e.message ?: ""); return START_NOT_STICKY }
        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        @Suppress("DEPRECATION")
        val data: Intent? = intent?.getParcelableExtra(EXTRA_DATA)
        if (data == null) { fail(""); return START_NOT_STICKY }
        try { begin(code, data) } catch (e: Exception) { fail(e.message ?: "") }
        return START_NOT_STICKY
    }

    private fun begin(code: Int, data: Intent) {
        problem = ""
        stopping = false
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val p = mpm.getMediaProjection(code, data) ?: throw IllegalStateException("no projection")
        projection = p
        // The screen, scaled to 1280 on its long side (even sizes, as the encoder needs). Sideways, like a film.
        val m = resources.displayMetrics
        val long = maxOf(m.widthPixels, m.heightPixels); val short = minOf(m.widthPixels, m.heightPixels)
        val scale = 1280f / long
        val w = (long * scale).toInt() / 2 * 2
        val h = (short * scale).toInt() / 2 * 2
        val out = File(File(cacheDir, "walks").apply { mkdirs() }, "pitzki_walk_${System.currentTimeMillis()}.mp4")
        file = out
        muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        // The picture: an H.264 encoder fed straight from the screen.
        val vf = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 5_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val vc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        vc.configure(vf, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        videoSurface = vc.createInputSurface()
        video = vc

        // The sound: what the app itself plays (Android 10 and up), taken from the screen recording.
        wantAudio = Build.VERSION.SDK_INT >= 29 && startAppSound(p)

        if (Build.VERSION.SDK_INT >= 34) p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { if (recording) problem = L.t("הטלפון עצר את הקלטת המסך באמצע", "The phone stopped the screen recording"); finishRecording() }
        }, Handler(Looper.getMainLooper()))
        vc.start()
        display = p.createVirtualDisplay("pitzki-walk", w, h, m.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, videoSurface, null, null)
        recording = true
        videoThread = Thread({ drain(vc, isVideo = true) }, "walk-video").apply { start() }
        if (wantAudio) audioThread = Thread({ audioLoop() }, "walk-audio").apply { start() }
    }

    /** The app's own sound (voice, song, animals: media and game sounds), as 44.1 kHz stereo AAC. */
    @SuppressLint("MissingPermission")
    private fun startAppSound(p: MediaProjection): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        return try {
            val config = AudioPlaybackCaptureConfiguration.Builder(p)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val fmt = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(44_100)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build()
            val min = AudioRecord.getMinBufferSize(44_100, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(8192)
            val rec = AudioRecord.Builder().setAudioFormat(fmt).setBufferSizeInBytes(min * 2).setAudioPlaybackCaptureConfig(config).build()
            if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return false }
            val af = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 44_100, 2).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
            }
            val ac = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            ac.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            ac.start()
            rec.startRecording()
            mic = rec; audio = ac
            true
        } catch (_: Exception) {
            // No sound then (the picture is still recorded).
            try { mic?.release() } catch (_: Exception) {}
            mic = null; audio = null
            false
        }
    }

    /** Reads the app's sound and feeds the AAC encoder (and writes what comes out) until the end. */
    private fun audioLoop() {
        val rec = mic ?: return
        val ac = audio ?: return
        val buf = ByteArray(4096)
        val start = System.nanoTime()
        var frames = 0L
        var eosSent = false
        try {
            while (true) {
                val end = stopping
                val n = if (end || eosSent) 0 else rec.read(buf, 0, buf.size)
                val i = if (eosSent) -1 else ac.dequeueInputBuffer(10_000)
                if (i >= 0) {
                    val inBuf = ac.getInputBuffer(i) ?: continue
                    inBuf.clear()
                    val len = n.coerceIn(0, inBuf.capacity())
                    if (len > 0) inBuf.put(buf, 0, len)
                    // Its time: from the samples counted (steady), on the same clock as the screen's frames.
                    val pts = start / 1000 + frames * 1_000_000L / 44_100
                    frames += len / 4
                    ac.queueInputBuffer(i, 0, len, pts, if (end) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                    if (end) eosSent = true
                }
                if (drainOnce(ac, isVideo = false)) break
            }
        } catch (_: Exception) {}
    }

    /** Writes what the video encoder makes until its end. */
    private fun drain(codec: MediaCodec, isVideo: Boolean) {
        try { while (!drainOnce(codec, isVideo)) {} } catch (_: Exception) {}
    }

    /** One pass over an encoder's output; true at its end. */
    private fun drainOnce(codec: MediaCodec, isVideo: Boolean): Boolean {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val o = codec.dequeueOutputBuffer(info, 10_000)
            when {
                o == MediaCodec.INFO_TRY_AGAIN_LATER -> return false
                o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> synchronized(lock) {
                    val mx = muxer ?: return true
                    if (isVideo) videoTrack = mx.addTrack(codec.outputFormat) else audioTrack = mx.addTrack(codec.outputFormat)
                    // Both tracks there (or the picture alone, when there's no sound): the file starts.
                    if (videoTrack >= 0 && (!wantAudio || audioTrack >= 0) && !muxing) { mx.start(); muxing = true }
                }
                o >= 0 -> {
                    val data = codec.getOutputBuffer(o)
                    val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (data != null && info.size > 0 && !config) synchronized(lock) {
                        val track = if (isVideo) videoTrack else audioTrack
                        val last = if (isVideo) lastVideoUs else lastAudioUs
                        // (Before both tracks are in, the first moment is dropped; times only go forward.)
                        if (muxing && track >= 0 && info.presentationTimeUs > last) {
                            data.position(info.offset); data.limit(info.offset + info.size)
                            muxer?.writeSampleData(track, data, info)
                            if (isVideo) lastVideoUs = info.presentationTimeUs else lastAudioUs = info.presentationTimeUs
                        }
                    }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return true
                }
            }
        }
    }

    private fun finishRecording() {
        if (!recording && video == null) return
        recording = false
        stopping = true
        try { display?.release() } catch (_: Exception) {}
        display = null
        try { video?.signalEndOfInputStream() } catch (_: Exception) {}
        try { videoThread?.join(3000) } catch (_: Exception) {}
        try { audioThread?.join(3000) } catch (_: Exception) {}
        try { mic?.stop() } catch (_: Exception) {}
        try { mic?.release() } catch (_: Exception) {}
        mic = null
        try { video?.stop() } catch (_: Exception) {}
        try { video?.release() } catch (_: Exception) {}
        video = null
        try { audio?.stop() } catch (_: Exception) {}
        try { audio?.release() } catch (_: Exception) {}
        audio = null
        try { videoSurface?.release() } catch (_: Exception) {}
        videoSurface = null
        synchronized(lock) {
            try {
                if (muxing) muxer?.stop() else { file?.delete(); if (problem.isEmpty()) problem = L.t("לא נקלט וידאו", "No video was captured") }
            } catch (e: Exception) {
                file?.delete(); problem = L.t("שמירת הסרטון נכשלה", "Saving the video failed") + (e.message?.let { " ($it)" } ?: "")
            }
            try { muxer?.release() } catch (_: Exception) {}
            muxer = null
            muxing = false; videoTrack = -1; audioTrack = -1; lastVideoUs = -1L; lastAudioUs = -1L
        }
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
    }

    private fun fail(why: String) {
        finishRecording()
        val cb = onFailed
        Handler(Looper.getMainLooper()).post { cb?.invoke(why) }
        stopSelf()
    }

    override fun onDestroy() {
        finishRecording()
        if (instance === this) instance = null
        super.onDestroy()
    }
}
