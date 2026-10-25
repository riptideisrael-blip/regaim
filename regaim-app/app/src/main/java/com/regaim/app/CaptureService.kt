package com.regaim.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import androidx.core.content.ContextCompat
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.graphics.ImageFormat
import android.media.ImageReader
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.nio.ByteOrder
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/**
 * Runs in the background (screen can be off):
 *  - camera -> H.264 encoder -> rolling 40s buffer in memory
 *  - microphone -> AAC encoder -> rolling 40s buffer, and -> YAMNet laughter detection
 *  - when a moment is detected, waits 15s and saves 15s before + 15s after to the gallery
 */
class CaptureService : Service() {

    companion object {
        const val ACTION_START = "com.regaim.app.START"
        const val ACTION_STOP = "com.regaim.app.STOP"
        const val ACTION_SAVE_NOW = "com.regaim.app.SAVE_NOW"
        const val ACTION_ZOOM = "com.regaim.app.ZOOM"

        private const val TAG = "Regaim"
        private const val CHANNEL = "regaim_capture"
        private const val MOMENTS_CHANNEL = "regaim_moments"
        private const val NOTIF_ID = 7
        private const val MODEL_RATE = 16000
        private const val KEEP_US = 50_000_000L
        private const val COOLDOWN_US = 5_000_000L
    }

    private val videoRing = SampleRing(KEEP_US)
    private val audioRing = SampleRing(KEEP_US)
    @Volatile private var videoFormat: MediaFormat? = null
    @Volatile private var audioFormat: MediaFormat? = null

    private var camThread: HandlerThread? = null
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var videoEncoder: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var previewReader: ImageReader? = null
    @Volatile private var lastPreviewMs = 0L
    private var videoDrainThread: Thread? = null
    private var audioThread: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var rotation = 90
    private var zoomRatio = 1f
    private var zoomRange: Pair<Float, Float>? = null
    @Volatile private var repeatingBuilder: CaptureRequest.Builder? = null

    /** Changes zoom live, without stopping the recording. */
    private fun applyZoom() {
        val range = zoomRange ?: return
        val b = repeatingBuilder ?: return
        camHandler?.post {
            try {
                zoomRatio = Status.zoom.coerceIn(range.first, range.second)
                if (Build.VERSION.SDK_INT >= 30) b.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoomRatio)
                session?.setRepeatingRequest(b.build(), null, camHandler)
                Status.zoomApplied = zoomRatio
            } catch (e: Exception) {
                Log.w(TAG, "zoom", e)
            }
        }
    }

    @Volatile private var running = false
    @Volatile private var pendingTriggerUs = -1L
    @Volatile private var pendingLabel = ""
    @Volatile private var lastTriggerUs = 0L
    @Volatile private var lastAutoUs = 0L
    @Volatile private var lastAutoScore = 0f
    @Volatile private var lastAutoMemoryId: String? = null
    @Volatile private var pendingAuto = false
    @Volatile private var pendingReplace: String? = null
    @Volatile private var pendingScore = 0f
    @Volatile private var lastVisualUs = 0L
    @Volatile private var pendingForce = false
    private val recentLooks = CaptureFilter.Recent()
    @Volatile private var coveredBySensor = false
    private var proximityListener: android.hardware.SensorEventListener? = null

    /** The phone's proximity sensor reads "near" when it is in a pocket or bag. */
    private fun startProximity() {
        try {
            val sm = getSystemService(SENSOR_SERVICE) as android.hardware.SensorManager
            val sensor = sm.getDefaultSensor(android.hardware.Sensor.TYPE_PROXIMITY) ?: return
            val l = object : android.hardware.SensorEventListener {
                override fun onSensorChanged(e: android.hardware.SensorEvent) {
                    coveredBySensor = e.values.isNotEmpty() && e.values[0] < sensor.maximumRange.coerceAtMost(5f)
                }
                override fun onAccuracyChanged(s: android.hardware.Sensor?, a: Int) {}
            }
            proximityListener = l
            sm.registerListener(l, sensor, android.hardware.SensorManager.SENSOR_DELAY_NORMAL)
        } catch (_: Exception) { }
    }

    private fun stopProximity() {
        try { proximityListener?.let { (getSystemService(SENSOR_SERVICE) as android.hardware.SensorManager).unregisterListener(it) } } catch (_: Exception) { }
        proximityListener = null
        coveredBySensor = false
    }

    @Volatile private var lastLocation: Location? = null
    @Volatile private var pendingLocation: Location? = null
    private var listeningLocation = false
    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            lastLocation = location
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
        }

        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var spotter: KeywordSpotter? = null
    @Volatile private var faceWatcher: FaceWatcher? = null
    @Volatile private var lastFaceMs = 0L
    private val saveExecutor = Executors.newSingleThreadExecutor()
    // The AI description of a saved moment runs here, never on [saveExecutor]: a slow network must not hold up
    // saving the next clips (or keep their video in memory meanwhile).
    private val aiExecutor = Executors.newSingleThreadExecutor()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Queue items nobody answered for 36 hours go to the trash (no new background service for this).
        Thread {
            try { MemoryStore.expirePending(applicationContext) } catch (_: Exception) { }
            try { GlobalVotes.refresh(applicationContext) } catch (_: Exception) { } // at most once a day
        }.start()
        when (intent?.action) {
            ACTION_START -> if (!running) startCapture()
            ACTION_STOP -> {
                stopCapture()
                stopSelf()
            }
            ACTION_SAVE_NOW -> if (running) trigger("manual")
            ACTION_ZOOM -> if (running) applyZoom()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopCapture()
        saveExecutor.shutdown()
        aiExecutor.shutdown()
        unwatchHeat()
        super.onDestroy()
    }

    // ---------- heat ----------

    private var heatListener: Any? = null

    /** A very hot phone (sun, long session): tell the parent, so they can give it a break. */
    private fun watchHeat() {
        if (Build.VERSION.SDK_INT < 29 || heatListener != null) return
        try {
            val pm = getSystemService(android.os.PowerManager::class.java) ?: return
            val l = android.os.PowerManager.OnThermalStatusChangedListener { status ->
                if (status >= android.os.PowerManager.THERMAL_STATUS_SEVERE)
                    Status.error = L.t("הטלפון חם מאוד. כדאי לעצור את הצילום לכמה דקות, ולהוציא אותו מהשמש.", "The phone is very hot. Pause capturing for a few minutes, out of the sun.")
            }
            pm.addThermalStatusListener(l)
            heatListener = l
        } catch (_: Exception) { }
    }

    private fun unwatchHeat() {
        if (Build.VERSION.SDK_INT < 29) return
        val l = heatListener as? android.os.PowerManager.OnThermalStatusChangedListener ?: return
        try { getSystemService(android.os.PowerManager::class.java)?.removeThermalStatusListener(l) } catch (_: Exception) { }
        heatListener = null
    }

    // ---------- start / stop ----------

    private fun startCapture() {
        watchHeat()
        Settings.load(this)
        createChannel()
        try {
            var type = 0
            if (Build.VERSION.SDK_INT >= 30) {
                type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                if (hasLocationPermission()) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            }
            ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(L.t("מאזין לרגעים...", "Listening for moments...")), type)
        } catch (e: Exception) {
            Log.e(TAG, "startForeground", e)
            Status.error = L.t("לא ניתן להפעיל הקלטה ברקע: ${e.message}", "Couldn't start background recording: ${e.message}")
            stopSelf()
            return
        }

        Status.reset()
        running = true
        Status.running = true
        Status.startedAt = System.currentTimeMillis()

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "regaim:capture").apply {
            acquire(6 * 60 * 60 * 1000L)
        }

        try {
            startVideo()
        } catch (e: Exception) {
            Log.e(TAG, "video", e)
            Status.error = L.t("שגיאת מצלמה: ${e.message}", "Camera error: ${e.message}")
            stopCapture()
            stopSelf()
            return
        }
        audioThread = Thread({ audioLoop() }, "regaim-audio").also { it.start() }
        startLocation()
        startProximity()
        if (Status.facesOn || Status.hugOn) {
            faceWatcher = try {
                FaceWatcher { label -> main.post { trigger(label) } }
            } catch (e: Exception) {
                Log.w(TAG, "faces", e)
                null
            }
        }
        if (Status.wordsOn || Status.loveOn) {
            val phrases = {
                (if (Status.wordsOn) Status.phrases else emptyList()) + (if (Status.loveOn) Phrases.LOVE else emptyList())
            }
            spotter = KeywordSpotter(this, phrases) { phrase ->
                trigger("״$phrase״", force = Phrases.isCommand(phrase) || Phrases.isLove(phrase))
            }.also { it.start() }
        }
    }

    // ---------- location (optional: "where was I?") ----------

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun startLocation() {
        if (!hasLocationPermission()) return
        try {
            val lm = getSystemService(LOCATION_SERVICE) as LocationManager
            val candidates = mutableListOf<String>()
            if (Build.VERSION.SDK_INT >= 31) candidates.add(LocationManager.FUSED_PROVIDER)
            candidates.add(LocationManager.NETWORK_PROVIDER)
            candidates.add(LocationManager.GPS_PROVIDER)
            lastLocation = candidates.mapNotNull { p ->
                try { lm.getLastKnownLocation(p) } catch (_: Exception) { null }
            }.maxByOrNull { it.time }
            val provider = candidates.firstOrNull { p -> try { lm.isProviderEnabled(p) } catch (_: Exception) { false } } ?: return
            lm.requestLocationUpdates(provider, 60_000L, 30f, locationListener, Looper.getMainLooper())
            listeningLocation = true
        } catch (e: Exception) {
            Log.w(TAG, "location", e)
        }
    }

    private fun stopLocation() {
        if (!listeningLocation) return
        try {
            (getSystemService(LOCATION_SERVICE) as LocationManager).removeUpdates(locationListener)
        } catch (_: Exception) {
        }
        listeningLocation = false
    }

    private fun placeName(loc: Location?): String {
        if (loc == null || !Geocoder.isPresent()) return ""
        return try {
            @Suppress("DEPRECATION")
            val a = Geocoder(this, L.locale).getFromLocation(loc.latitude, loc.longitude, 1)?.firstOrNull()
                ?: return ""
            listOfNotNull(a.thoroughfare, a.locality).joinToString(", ").ifEmpty { a.featureName ?: "" }
        } catch (_: Exception) {
            ""
        }
    }

    private fun stopCapture() {
        if (!running) return
        main.removeCallbacksAndMessages(null)
        val pending = pendingTriggerUs
        if (pending >= 0) saveClip(pending, pendingLabel) // keep a moment that was mid-way

        running = false
        spotter?.stop()
        spotter = null
        faceWatcher?.close()
        faceWatcher = null
        try { session?.close() } catch (_: Exception) {}
        session = null
        try { camera?.close() } catch (_: Exception) {}
        camera = null

        try { videoDrainThread?.join(1500) } catch (_: Exception) {}
        try { audioThread?.join(3000) } catch (_: Exception) {}
        videoDrainThread = null
        audioThread = null

        try { videoEncoder?.stop() } catch (_: Exception) {}
        try { videoEncoder?.release() } catch (_: Exception) {}
        videoEncoder = null
        try { inputSurface?.release() } catch (_: Exception) {}
        inputSurface = null
        try { previewReader?.close() } catch (_: Exception) {}
        previewReader = null
        Status.preview = null
        camHandler?.removeCallbacksAndMessages(null)
        camHandler = null
        camThread?.quitSafely()
        camThread = null

        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        stopLocation()
        stopProximity()

        videoRing.clear()
        audioRing.clear()
        videoFormat = null
        audioFormat = null
        Status.running = false
        Status.pending = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    // ---------- video ----------

    @SuppressLint("MissingPermission")
    private fun startVideo() {
        val cm = getSystemService(CAMERA_SERVICE) as CameraManager
        val backIds = cm.cameraIdList.filter {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }
        var camId = backIds.firstOrNull() ?: cm.cameraIdList.first()
        zoomRatio = 1f
        val range = if (Build.VERSION.SDK_INT >= 30) {
            cm.getCameraCharacteristics(camId).get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
        } else null
        zoomRange = range?.let { it.lower to it.upper }
        if (range != null) {
            // The main camera switches lenses by itself: 0.6x = ultra-wide, 3x / 5x = telephoto.
            zoomRatio = Status.zoom.coerceIn(range.lower, range.upper)
        } else if (Status.zoom < 0.99f) {
            run {
                // Fallback: pick the back camera with the shortest focal length (= widest).
                val widest = backIds.minByOrNull {
                    cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull() ?: Float.MAX_VALUE
                }
                if (widest != null && widest != camId) camId = widest
            }
        }
        Status.zoomApplied = zoomRatio
        val chars = cm.getCameraCharacteristics(camId)
        rotation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90

        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: throw IllegalStateException("no stream map")
        val sizes = map.getOutputSizes(MediaCodec::class.java) ?: emptyArray()
        // 720p by default: hours on the chest at 1080p heat the phone and drain the battery. "High quality" in Settings.
        val hq = Settings.prefs(this).getBoolean("hq_video", false)
        val size = (if (hq) sizes.firstOrNull { it.width == 1920 && it.height == 1080 } else null)
            ?: sizes.firstOrNull { it.width == 1280 && it.height == 720 }
            ?: sizes.firstOrNull { it.width == 1920 && it.height == 1080 }
            ?: sizes.filter { it.width <= 1920 && it.width * 9 == it.height * 16 }.maxByOrNull { it.width * it.height }
            ?: sizes.filter { it.width <= 1920 }.maxByOrNull { it.width * it.height }
            ?: Size(1280, 720)
        val bitrate = if (size.width >= 1920) 6_000_000 else 4_000_000
        Status.videoSize = "${size.height}p"
        Status.videoFrames = 0L

        // Small extra stream for the live "bubble" viewfinder on screen.
        val yuvSizes = map.getOutputSizes(ImageFormat.YUV_420_888) ?: emptyArray()
        val pSize = yuvSizes.filter { it.width in 320..960 && it.width * 9 == it.height * 16 }.minByOrNull { it.width }
            ?: yuvSizes.filter { it.width in 320..960 }.minByOrNull { it.width }
        previewReader = pSize?.let { ImageReader.newInstance(it.width, it.height, ImageFormat.YUV_420_888, 2) }

        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, size.width, size.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = enc.createInputSurface()
        enc.start()
        videoEncoder = enc
        inputSurface = surface
        videoDrainThread = Thread({ drainVideo(enc) }, "regaim-video").also { it.start() }

        val thread = HandlerThread("regaim-camera").also { it.start() }
        camThread = thread
        val handler = Handler(thread.looper)
        previewReader?.setOnImageAvailableListener({ r ->
            val img = try { r.acquireLatestImage() } catch (_: Exception) { null } ?: return@setOnImageAvailableListener
            try {
                val now = System.currentTimeMillis()
                val wantFace = faceWatcher != null && now - lastFaceMs > 800
                if ((Status.uiVisible && now - lastPreviewMs > 350) || wantFace) {
                    lastPreviewMs = now
                    val bmp = Preview.toBitmap(img, rotation)
                    if (Status.uiVisible) Status.preview = bmp
                    if (wantFace && bmp != null) {
                        lastFaceMs = now
                        faceWatcher?.feed(bmp)
                    }
                }
            } catch (_: Exception) {
            } finally {
                img.close()
            }
        }, handler)
        cameraId = camId
        camHandler = handler
        reopenAttempts = 0
        openCam()
    }

    private var cameraId = ""
    private var camHandler: Handler? = null
    private var reopenAttempts = 0

    /** Opens the camera; if it gets taken away (another app, system), keeps trying to come back. */
    @SuppressLint("MissingPermission")
    private fun openCam() {
        val handler = camHandler ?: return
        val surface = inputSurface ?: return
        if (!running) return
        try {
            val cm = getSystemService(CAMERA_SERVICE) as CameraManager
            cm.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    if (!running) {
                        device.close()
                        return
                    }
                    camera = device
                    createSession(device, surface, handler, previewReader != null)
                }

                override fun onDisconnected(device: CameraDevice) {
                    device.close()
                    camera = null
                    session = null
                    scheduleReopen(L.t("המצלמה נלקחה לרגע על ידי אפליקציה אחרת, מתחבר מחדש...", "Another app briefly took the camera. Reconnecting..."))
                }

                override fun onError(device: CameraDevice, error: Int) {
                    device.close()
                    camera = null
                    session = null
                    scheduleReopen(L.t("תקלת מצלמה (קוד $error), מתחבר מחדש...", "Camera error (code $error). Reconnecting..."))
                }
            }, handler)
        } catch (e: Exception) {
            Log.w(TAG, "openCam", e)
            scheduleReopen(L.t("המצלמה לא זמינה כרגע, מנסה שוב...", "Camera isn't available right now. Trying again..."))
        }
    }

    private fun scheduleReopen(msg: String) {
        if (!running) return
        reopenAttempts++
        if (reopenAttempts > 30) {
            Status.error = L.t("המצלמה לא חוזרת. עצור והתחל שוב.", "The camera isn't coming back. Stop and start again.")
            return
        }
        Status.error = msg
        camHandler?.postDelayed({ if (running && camera == null) openCam() }, 2000)
    }

    @Suppress("DEPRECATION")
    private fun createSession(device: CameraDevice, surface: Surface, handler: Handler, withPreview: Boolean) {
        val previewSurface = if (withPreview) previewReader?.surface else null
        try {
            device.createCaptureSession(listOfNotNull(surface, previewSurface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (!running) {
                        s.close()
                        return
                    }
                    session = s
                    reopenAttempts = 0
                    if (Status.error?.let { it.contains("מצלמה") || it.contains("camera", ignoreCase = true) } == true) Status.error = null
                    try {
                        val req = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                            addTarget(surface)
                            if (previewSurface != null) addTarget(previewSurface)
                            set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                            if (Build.VERSION.SDK_INT >= 30 && zoomRange != null) {
                                set(CaptureRequest.CONTROL_ZOOM_RATIO, zoomRatio)
                            }
                        }
                        repeatingBuilder = req
                        s.setRepeatingRequest(req.build(), null, handler)
                    } catch (e: Exception) {
                        Log.e(TAG, "repeating", e)
                        Status.error = L.t("המצלמה לא התחילה לצלם: ${e.message}", "The camera didn't start recording: ${e.message}")
                    }
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    // Some cameras refuse two streams: keep recording, just without the live viewfinder.
                    if (previewSurface != null && running) createSession(device, surface, handler, false)
                    else Status.error = L.t("הגדרת המצלמה נכשלה", "Camera setup failed")
                }
            }, handler)
        } catch (e: Exception) {
            Log.e(TAG, "session", e)
            Status.error = L.t("הגדרת המצלמה נכשלה: ${e.message}", "Camera setup failed: ${e.message}")
        }
    }

    private fun drainVideo(enc: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (running) {
            val idx = try {
                enc.dequeueOutputBuffer(info, 10_000)
            } catch (e: IllegalStateException) {
                break
            }
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                videoFormat = enc.outputFormat
            } else if (idx >= 0) {
                val buf = enc.getOutputBuffer(idx)
                if (buf != null && info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                    val data = ByteArray(info.size)
                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)
                    buf.get(data)
                    videoRing.add(EncodedSample(data, nowUs(), info.flags))
                    Status.videoFrames = Status.videoFrames + 1
                    Status.lastFrameMs = System.currentTimeMillis()
                }
                enc.releaseOutputBuffer(idx, false)
            }
        }
    }

    // ---------- audio + detection ----------

    /** Opens the microphone: 48 kHz (good clip audio) with the clean voice-recognition source, with fallbacks. */
    @SuppressLint("MissingPermission")
    private fun openMic(): AudioRecord? {
        val sources = listOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)
        for (rate in listOf(48_000, 16_000)) {
            for (src in sources) {
                try {
                    val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                    if (minBuf <= 0) continue
                    val rec = AudioRecord(src, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minBuf, rate * 2 * 2))
                    if (rec.state == AudioRecord.STATE_INITIALIZED) return rec
                    rec.release()
                } catch (_: Exception) {
                }
            }
        }
        return null
    }

    private fun audioLoop() {
        val detector = try {
            LaughDetector(this)
        } catch (e: Exception) {
            Log.e(TAG, "detector", e)
            Status.error = L.t("מודל זיהוי הצחוק לא נטען: ${e.message}", "The laugh detection model didn't load: ${e.message}")
            null
        }

        val rec = openMic()
        if (rec == null) {
            Status.error = L.t("המיקרופון לא זמין", "Microphone isn't available")
            detector?.close()
            return
        }
        val rate = rec.sampleRate
        val down = Downsampler(rate / MODEL_RATE)

        val aac = createAacEncoder(rate)
        val window = FloatArray(detector?.requiredSamples ?: 15600)
        val chunk = ShortArray(rate / 10) // 0.1 second
        val info = MediaCodec.BufferInfo()
        var totalSamples = 0L
        var chunksSinceClassify = 0
        var recentHits = 0 // last 3 laugh checks, as bits

        try {
            rec.startRecording()
            val anchorUs = nowUs()
            var micErrors = 0
            while (running) {
                val n = rec.read(chunk, 0, chunk.size)
                if (n < 0) {
                    // The microphone failed (e.g. after a phone call): don't spin the CPU; give up after ~3 seconds.
                    micErrors++
                    if (micErrors >= 60) {
                        Status.error = L.t("המיקרופון הפסיק לעבוד. עצרו והפעילו שוב את הצילום.", "The microphone stopped working. Stop and start capturing again.")
                        break
                    }
                    try { Thread.sleep(50) } catch (_: InterruptedException) { break }
                    continue
                }
                if (n == 0) continue
                micErrors = 0
                val ptsUs = anchorUs + totalSamples * 1_000_000L / rate
                totalSamples += n

                if (aac != null) {
                    feedAac(aac, chunk, n, ptsUs)
                    drainAac(aac, info)
                }

                // Model input: 16 kHz, low-passed and decimated from the mic stream.
                val small = down.process(chunk, n)
                spotter?.feed(small)
                val m = small.size
                val w = window.size
                if (m >= w) {
                    System.arraycopy(small, m - w, window, 0, w)
                } else if (m > 0) {
                    System.arraycopy(window, m, window, 0, w - m)
                    System.arraycopy(small, 0, window, w - m, m)
                }

                // Classify twice a second.
                if (detector != null && ++chunksSinceClassify >= 5) {
                    chunksSinceClassify = 0
                    val r = detector.classify(window)
                    Status.lastScore = r.score
                    if (r.score > Status.peakScore) Status.peakScore = r.score
                    Status.top3 = r.top3
                    Status.lastLabel = r.label
                    Status.topLabel = r.top
                    // One loud second isn't a laugh: it has to show up in 2 of the last 3 checks (~1.5 s),
                    // unless it is very clear on its own.
                    // The family's 👍/👎 from the approval queue tune each label's bar (the slider itself is never changed).
                    val hit = r.label.isNotEmpty() && r.score >= CaptureVotes.effective(applicationContext, r.label, Status.threshold)
                    recentHits = ((recentHits shl 1) or (if (hit) 1 else 0)) and 0b111
                    val confirmed = hit && (recentHits.countOneBits() >= 2 || r.score >= maxOf(0.6f, Status.threshold * 2f))
                    if (confirmed) { recentHits = 0; trigger(r.label, score = r.score) }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "audio loop", e)
            Status.error = L.t("שגיאת אודיו: ${e.message}", "Audio error: ${e.message}")
        } finally {
            try { rec.stop() } catch (_: Exception) {}
            rec.release()
            try { aac?.stop() } catch (_: Exception) {}
            try { aac?.release() } catch (_: Exception) {}
            detector?.close()
        }
    }

    private fun createAacEncoder(rate: Int): MediaCodec? = try {
        val f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, if (rate >= 44_100) 128_000 else 64_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 32_768)
        }
        MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
            configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }
    } catch (e: Exception) {
        Log.e(TAG, "aac", e)
        null
    }

    private fun feedAac(enc: MediaCodec, chunk: ShortArray, n: Int, ptsUs: Long) {
        val idx = enc.dequeueInputBuffer(5_000)
        if (idx < 0) return
        val buf = enc.getInputBuffer(idx) ?: return
        buf.clear()
        val samples = min(n, buf.remaining() / 2)
        buf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(chunk, 0, samples)
        enc.queueInputBuffer(idx, 0, samples * 2, ptsUs, 0)
    }

    private fun drainAac(enc: MediaCodec, info: MediaCodec.BufferInfo) {
        while (true) {
            val idx = enc.dequeueOutputBuffer(info, 0)
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                audioFormat = enc.outputFormat
                continue
            }
            if (idx < 0) break
            val buf = enc.getOutputBuffer(idx)
            if (buf != null && info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                val data = ByteArray(info.size)
                buf.position(info.offset)
                buf.limit(info.offset + info.size)
                buf.get(data)
                audioRing.add(EncodedSample(data, info.presentationTimeUs, info.flags))
            }
            enc.releaseOutputBuffer(idx, false)
        }
    }

    // ---------- moments ----------

    @Synchronized
    private fun trigger(label: String, force: Boolean = label == "manual", score: Float = 0f) {
        if (!running) return
        if (pendingTriggerUs >= 0) {
            if (force || Labels.isChosen(label)) {
                // You pressed save (or said the word) while an automatic moment is on its way: it's now yours —
                // never filtered, never replaced by a later laugh.
                pendingForce = force || pendingForce
                // It was opening a new automatic window: that window never got its moment, so start fresh.
                if (pendingAuto && pendingReplace == null) lastAutoUs = 0L
                pendingAuto = false
                pendingReplace = null
                pendingLabel = label
                return
            }
            // A hug or "I love you" during a moment that's already being saved: name the moment after it.
            if (label == "Hug" || (label.startsWith("״") && Phrases.isLove(label.trim('״')))) pendingLabel = label
            return
        }
        val now = nowUs()
        // (The proximity sensor is not a gate any more: worn on the chest the screen faces you and reads "near"
        // all the time, which silently blocked every automatic moment. Pocket footage is caught by its dark frame.)
        if (!force && now - lastTriggerUs < COOLDOWN_US) return
        // Smiles and faces can last long: at most one visual moment a minute — longer when the family keeps
        // turning that trigger down in the queue (the model itself isn't changed). Hugs are never held back.
        if (label in Labels.VISUAL && label != "Hug") {
            if (now - lastVisualUs < CaptureVotes.visualWindowMs(applicationContext, label) * 1000L) return
        }
        // Automatic moments: one per window of a few minutes (Settings → "time between moments"), and the
        // strongest laugh in the window wins: a clearly bigger laugh replaces the earlier one (which goes to the
        // trash, restorable for 30 days). Saving by hand or with a code word always works.
        var replace: String? = null
        val auto = !force && !Labels.isChosen(label)
        if (auto && lastAutoUs > 0 && now - lastAutoUs < Status.gapMin * 60_000_000L) {
            val clearlyBigger = score >= lastAutoScore * 1.3f && score >= lastAutoScore + 0.1f
            if (!clearlyBigger) return
            replace = lastAutoMemoryId ?: return // the first one is still being saved: keep it
        } else if (auto) {
            lastAutoUs = now
            lastAutoScore = score
            lastAutoMemoryId = null
        }
        if (label in Labels.VISUAL && label != "Hug") lastVisualUs = now
        pendingTriggerUs = now
        pendingForce = force
        pendingAuto = auto
        pendingReplace = replace
        pendingScore = score
        pendingLabel = label
        pendingLocation = lastLocation
        lastTriggerUs = now
        Status.pending = true
        buzz()
        main.postDelayed({ saveClip(now, pendingLabel.ifEmpty { label }) }, ClipSaver.POST_US / 1000)
    }

    /** A short double buzz so you know a moment was caught, even with the phone in your pocket. */
    private fun buzz() {
        if (!Status.buzzOn) return
        try {
            val v = if (Build.VERSION.SDK_INT >= 31) {
                (getSystemService(VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(VIBRATOR_SERVICE) as android.os.Vibrator
            }
            v.vibrate(android.os.VibrationEffect.createWaveform(longArrayOf(0, 60, 80, 60), -1))
        } catch (_: Exception) {
        }
    }

    private fun saveClip(triggerUs: Long, label: String) {
        val vf = videoFormat
        val video = videoRing.snapshot()
        val audio = audioRing.snapshot()
        val af = audioFormat
        val rot = rotation
        val loc = pendingLocation
        val forced = pendingForce
        val wasAuto = pendingAuto
        val replaceId = pendingReplace
        val score = pendingScore
        pendingTriggerUs = -1L
        Status.pending = false
        if (vf == null) return

        saveExecutor.execute {
            try {
                // A full phone: don't start a clip that can't be finished; say so instead of failing silently.
                if (!StorageGuard.enough(StorageGuard.free(applicationContext))) {
                    Status.error = StorageGuard.message()
                    if (wasAuto && replaceId == null && lastAutoMemoryId == null) lastAutoUs = 0L
                    Log.w(TAG, "not enough free space, clip not saved")
                    return@execute
                }
                val saved = ClipSaver.save(applicationContext, video, audio, vf, af, rot, triggerUs, label)
                if (saved == null && wasAuto && replaceId == null && lastAutoMemoryId == null) lastAutoUs = 0L
                var look = 0L
                if (saved != null && saved.frame != null) {
                    val luma = CaptureFilter.lumaGrid(saved.frame!!)
                    look = CaptureFilter.dHash(luma)
                    val nowMs = System.currentTimeMillis()
                    if (!forced && label != "manual") {
                        // Throw away pocket footage, a near-copy of a recent clip, or something you deleted before.
                        // A replacement is the same scene on purpose (a bigger laugh): don't call it a duplicate.
                        val skip = CaptureFilter.looksCovered(luma) || (replaceId == null && recentLooks.isSimilar(look, nowMs)) ||
                            CaptureFilter.looksLikeDeleted(applicationContext, look)
                        if (skip) {
                            // The window's first automatic clip was dropped: let the next laugh open a fresh window.
                            if (wasAuto && replaceId == null && lastAutoMemoryId == null) lastAutoUs = 0L
                            try {
                                if (saved.uri.startsWith("content://")) applicationContext.contentResolver.delete(android.net.Uri.parse(saved.uri), null, null)
                                else java.io.File(saved.uri).delete()
                            } catch (_: Exception) { }
                            Log.i(TAG, "filtered a pocket/duplicate/deleted-like clip")
                            return@execute
                        }
                    }
                    recentLooks.add(look, nowMs)
                }
                if (saved != null) {
                    val id = UUID.randomUUID().toString()
                    val bubble = saved.frame?.let {
                        try { MemoryStore.saveBubble(applicationContext, id, it) } catch (_: Exception) { "" }
                    } ?: ""
                    val memory = Memory(
                        id = id,
                        uri = saved.uri,
                        name = saved.name,
                        time = System.currentTimeMillis(),
                        label = label,
                        lat = loc?.latitude,
                        lon = loc?.longitude,
                        place = placeName(loc),
                        thumb = bubble,
                        durationMs = saved.durationMs,
                        chosen = Labels.isChosen(label),
                        highlightMs = saved.highlightMs,
                        look = look,
                        // Automatic captures wait in the approval queue; a button press, a code word, a hug or
                        // "I love you" go straight to the archive (the label may have switched to them before saving).
                        review = if (QueueRules.goesToQueue(forced, label)) "pending" else "kept",
                    )
                    MemoryStore.add(applicationContext, memory)
                    if (!forced) try { Facts.trigger(applicationContext, id, label, score) } catch (_: Exception) { }
                    if (wasAuto) { lastAutoMemoryId = id; lastAutoScore = score }
                    // A bigger laugh in the same window: the earlier automatic moment steps aside (to the trash),
                    // unless the family already marked it as a favorite or shared it.
                    if (replaceId != null) {
                        val old = MemoryStore.get(applicationContext, replaceId)
                        // Only a moment still waiting in the queue steps aside; one the family kept or approved stays.
                        if (old != null && old.isPending && !old.approved && !old.favorite && !old.chosen && old.milestone.isEmpty() &&
                            !Circle.wasShared(applicationContext, old) && !Community.wasPublished(applicationContext, old)) {
                            try { MemoryStore.moveToTrash(applicationContext, setOf(replaceId), learn = false) } catch (_: Exception) { }
                        }
                    }
                    Status.clips = Status.clips + 1
                    Status.lastClipLabel = label
                    Status.lastClipAt = memory.time
                    if (running) updateNotification()
                    notifyMoment(memory)
                    // Let the AI describe it right away — only kept moments; a queued one waits for 👍 (nothing leaves the phone).
                    if (!memory.isPending) try {
                        aiExecutor.execute { try { MemoryStore.analyzePending(applicationContext) } catch (e: Exception) { Log.w(TAG, "analyze", e) } }
                    } catch (_: java.util.concurrent.RejectedExecutionException) { /* stopping: the next app open describes it */ }
                }
            } catch (e: Exception) {
                Log.e(TAG, "save", e)
                Status.error = L.t("שמירת קליפ נכשלה: ${e.message}", "Couldn't save clip: ${e.message}")
            }
        }
    }

    // ---------- notification ----------

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, L.t("הקלטת רגעים", "Moment recording"), NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(MOMENTS_CHANNEL, L.t("רגעים חדשים", "New moments"), NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    private fun buildNotification(text: String): Notification {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, CaptureService::class.java).setAction(ACTION_STOP), flags
        )
        val save = PendingIntent.getService(
            this, 3, Intent(this, CaptureService::class.java).setAction(ACTION_SAVE_NOW), flags
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(L.t("Moments פועל", "Moments is on"))
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, L.t("♥ שמור רגע", "♥ Save moment"), save)
            .addAction(0, L.t("עצור", "Stop"), stop)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /** A small bubble notification for each new memory. */
    private fun notifyMoment(m: Memory) {
        try {
            val open = PendingIntent.getActivity(
                this, 2, Intent(this, if (m.isPending) ReviewActivity::class.java else MemoriesActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val text = Labels.he(m.label) + if (m.place.isNotEmpty()) " · ${m.place}" else ""
            val queued = m.isPending
            val b = NotificationCompat.Builder(this, MOMENTS_CHANNEL)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle(if (queued) L.t("רגע חדש מחכה לאישור", "A new moment is waiting for your OK") else L.t("רגע חדש נשמר", "New moment saved"))
                .setContentText(text)
                .setContentIntent(open)
                .setAutoCancel(true)
                .setSilent(true)
            if (m.thumb.isNotEmpty()) BitmapFactory.decodeFile(m.thumb)?.let { b.setLargeIcon(it) }
            getSystemService(NotificationManager::class.java).notify(m.id.hashCode(), b.build())
        } catch (e: Exception) {
            Log.w(TAG, "notify", e)
        }
    }

    private fun updateNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(L.t("נשמרו ${Status.clips} רגעים", "${Status.clips} moments saved")))
    }
}
