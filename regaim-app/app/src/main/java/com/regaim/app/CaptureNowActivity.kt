package com.regaim.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Size
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * "This is the moment": the in-app camera for a photo or a story video (up to one minute).
 * Story video: hold the button to record, or tap once to start and once more to stop.
 * Uses the phone's camera directly, so the automatic capture is stopped first if it is running.
 */
class CaptureNowActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_VIDEO = "video"
        private const val HOLD_MS = 380L

        fun open(ctx: Context, video: Boolean) =
            ctx.startActivity(Intent(ctx, CaptureNowActivity::class.java).putExtra(EXTRA_VIDEO, video))
    }

    // ---- views ----
    private lateinit var root: FrameLayout
    private lateinit var textureHolder: FrameLayout
    private lateinit var texture: TextureView
    private lateinit var shutter: ShutterView
    private lateinit var timerText: TextView
    private lateinit var hintText: TextView
    private lateinit var photoTab: TextView
    private lateinit var videoTab: TextView
    private lateinit var flipBtn: TextView
    private lateinit var busy: TextView

    // ---- camera ----
    private val cameraThread = HandlerThread("capture-now").apply { start() }
    private val cam = Handler(cameraThread.looper)
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var previewSurface: Surface? = null
    private var imageReader: ImageReader? = null
    @Volatile private var recorder: MediaRecorder? = null
    private var videoFile: File? = null
    private var openGen = 0
    private var sensorOrientation = 90
    private var front = false
    private var videoMode = false
    private var recording = false
    private var recordStart = 0L
    private var saving = false
    private var started = false
    private var camAllowed = false
    @Volatile private var opening = false
    private var visible = false
    private var haveAudio = false

    // ---- shutter gesture ----
    private var holdStarted = false
    private var tapStops = false
    private val holdRunnable = Runnable {
        holdStarted = true
        startRecording()
    }

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants[Manifest.permission.CAMERA] != true && !has(Manifest.permission.CAMERA)) {
            Toast.makeText(this, L.t("בלי הרשאת מצלמה אי אפשר לצלם", "Camera access is needed to take photos and videos"), Toast.LENGTH_LONG).show()
            finish()
        } else begin()
    }

    private fun has(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        videoMode = intent.getBooleanExtra(EXTRA_VIDEO, false)
        buildUi()
        val need = (listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO) +
            (if (Build.VERSION.SDK_INT < 29) listOf(Manifest.permission.WRITE_EXTERNAL_STORAGE) else emptyList())).filter { !has(it) }
        if (need.isEmpty()) begin() else permissions.launch(need.toTypedArray())
    }

    /** Asks to stop the automatic capture if it holds the camera, then opens ours. */
    private fun begin() {
        if (started) return
        started = true
        haveAudio = has(Manifest.permission.RECORD_AUDIO)
        if (Status.running) {
            AlertDialog.Builder(this)
                .setTitle(L.t("הצילום האוטומטי פעיל", "Auto capture is on"))
                .setMessage(L.t("הוא משתמש במצלמה כרגע. לעצור אותו כדי לצלם רגע עכשיו? אפשר להפעיל אותו שוב אחר כך.", "It's using the camera right now. Stop it to capture a moment now? You can turn it back on later."))
                .setPositiveButton(L.t("עצור וצלם", "Stop and capture")) { _, _ ->
                    startService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_STOP))
                    main.postDelayed({ if (!isFinishing) { camAllowed = true; startCamera() } }, 1200)
                }
                .setNegativeButton(L.t("ביטול", "Cancel")) { _, _ -> finish() }
                .setCancelable(false)
                .show()
        } else { camAllowed = true; startCamera() }
    }

    // ---------- UI ----------

    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        setContentView(root)

        textureHolder = FrameLayout(this)
        root.addView(textureHolder, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        texture = TextureView(this)
        textureHolder.addView(texture, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) { if (camAllowed && camera == null) startCamera() }
            override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(s: SurfaceTexture): Boolean = true
            override fun onSurfaceTextureUpdated(s: SurfaceTexture) {}
        }

        // Top bar: close, timer, flip.
        val top = FrameLayout(this).apply { setPadding(dp(8), dp(36), dp(8), dp(8)) }
        val close = pill("✕", 20f).apply { setOnClickListener { finish() } }
        top.addView(close, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.START or Gravity.CENTER_VERTICAL))
        timerText = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(6), dp(14), dp(6))
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(0x99E5484D.toInt()) }
            visibility = View.GONE
        }
        top.addView(timerText, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        flipBtn = pill("🔄", 20f).apply { setOnClickListener { if (!recording && !saving) { front = !front; restartCamera() } } }
        top.addView(flipBtn, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.END or Gravity.CENTER_VERTICAL))
        root.addView(top, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        // Bottom: mode tabs, shutter, hint.
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), dp(16), dp(16), dp(28))
            setBackgroundColor(0x66000000)
        }
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        photoTab = tab(L.t("תמונה", "Photo")) { setMode(false) }
        videoTab = tab(L.t("סטורי", "Story")) { setMode(true) }
        tabs.addView(photoTab)
        tabs.addView(videoTab)
        bottom.addView(tabs)
        shutter = ShutterView(this)
        bottom.addView(shutter, LinearLayout.LayoutParams(dp(88), dp(88)).apply { topMargin = dp(14) })
        hintText = TextView(this).apply {
            textSize = 13f
            setTextColor(0xCCFFFFFF.toInt())
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
        }
        bottom.addView(hintText)
        root.addView(bottom, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        busy = TextView(this).apply {
            text = L.t("שומרים את הרגע...", "Saving your moment...")
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setBackgroundColor(0xCC000000.toInt())
            visibility = View.GONE
            isClickable = true
        }
        root.addView(busy, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        shutter.setOnTouchListener { _, e -> onShutterTouch(e); true }
        refreshMode()
    }

    private fun pill(text: String, size: Float) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(0x66000000) }
    }

    private fun tab(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 15f
        gravity = Gravity.CENTER
        setPadding(dp(18), dp(8), dp(18), dp(8))
        setOnClickListener { onClick() }
    }

    private fun refreshMode() {
        fun style(t: TextView, on: Boolean) {
            t.setTextColor(if (on) Color.BLACK else Color.WHITE)
            t.background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(if (on) Color.WHITE else Color.TRANSPARENT)
            }
        }
        style(photoTab, !videoMode)
        style(videoTab, videoMode)
        hintText.text = if (videoMode) L.t("החזיקו להקלטה, או לחצו להתחלה ולחצו שוב לעצירה · עד דקה", "Hold to record, or tap to start and tap again to stop · up to 1 min") else L.t("לחצו לצילום", "Tap to take a photo")
        shutter.videoMode = videoMode
        shutter.invalidate()
    }

    private fun setMode(video: Boolean) {
        if (video == videoMode || recording || saving) return
        videoMode = video
        refreshMode()
        cam.post { startSession() }
    }

    // ---------- shutter ----------

    private fun onShutterTouch(e: MotionEvent) {
        if (saving) return
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                holdStarted = false
                tapStops = recording
                if (videoMode && !recording) main.postDelayed(holdRunnable, HOLD_MS)
                shutter.down = true
                shutter.invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                main.removeCallbacks(holdRunnable)
                shutter.down = false
                shutter.invalidate()
                if (e.actionMasked == MotionEvent.ACTION_CANCEL && !holdStarted && !tapStops) return
                when {
                    !videoMode -> takePhoto()
                    holdStarted -> { holdStarted = false; stopRecording() }
                    tapStops -> { tapStops = false; stopRecording() }
                    else -> startRecording()
                }
            }
        }
    }

    // ---------- camera ----------

    private fun manager() = getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private fun cameraId(): String? {
        val mgr = manager()
        val want = if (front) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
        return mgr.cameraIdList.firstOrNull { mgr.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == want }
            ?: mgr.cameraIdList.firstOrNull()
    }

    private fun restartCamera() {
        cam.post {
            closeCamera()
            main.post { startCamera() }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startCamera() {
        if (!has(Manifest.permission.CAMERA) || isFinishing) return
        if (!texture.isAvailable || opening || camera != null) return // the listener starts us when ready
        opening = true
        val gen = ++openGen
        cam.post {
            try {
                val id = cameraId() ?: run { opening = false; main.post { fail(L.t("לא נמצאה מצלמה בטלפון", "No camera found on this phone")) }; return@post }
                sensorOrientation = manager().getCameraCharacteristics(id).get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
                manager().openCamera(id, object : CameraDevice.StateCallback() {
                    override fun onOpened(d: CameraDevice) {
                        opening = false
                        if (gen != openGen || isFinishing) {
                            d.close()
                            main.post { if (visible && camAllowed && camera == null && !saving) startCamera() }
                            return
                        }
                        camera = d
                        startSession()
                    }
                    override fun onDisconnected(d: CameraDevice) { opening = false; d.close(); if (camera === d) camera = null }
                    override fun onError(d: CameraDevice, error: Int) {
                        opening = false
                        d.close()
                        if (camera === d) camera = null
                        main.post { fail(L.t("אי אפשר לפתוח את המצלמה עכשיו. ייתכן שאפליקציה אחרת משתמשת בה.", "Can't open the camera right now. Another app may be using it.")) }
                    }
                }, cam)
            } catch (e: Exception) {
                opening = false
                main.post { fail(L.t("אי אפשר לפתוח את המצלמה: ${e.message ?: ""}", "Can't open the camera: ${e.message ?: ""}")) }
            }
        }
    }

    private fun fail(msg: String) {
        if (isFinishing) return
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        finish()
    }

    private fun pick(sizes: Array<Size>?, ratio: Double, maxW: Int, maxArea: Int = Int.MAX_VALUE): Size {
        val all = sizes?.toList().orEmpty()
        return all.filter { abs(it.width.toDouble() / it.height - ratio) < 0.03 && it.width <= maxW && it.width * it.height <= maxArea }
            .maxByOrNull { it.width * it.height }
            ?: all.filter { it.width <= maxW }.maxByOrNull { it.width * it.height }
            ?: all.firstOrNull() ?: Size(1280, 720)
    }

    /** Builds the preview (and the photo reader or the video recorder) for the current mode. Camera thread. */
    @Suppress("DEPRECATION")
    private fun startSession() {
        val d = camera ?: return
        try {
            try { session?.close() } catch (_: Exception) {}
            session = null
            releaseRecorder()
            try { imageReader?.close() } catch (_: Exception) {}
            imageReader = null
            try { previewSurface?.release() } catch (_: Exception) {}
            previewSurface = null

            val chars = manager().getCameraCharacteristics(d.id)
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return
            val previewSize = if (videoMode) pick(map.getOutputSizes(SurfaceTexture::class.java), 16.0 / 9, 1280)
                              else pick(map.getOutputSizes(SurfaceTexture::class.java), 4.0 / 3, 1440)
            val st = texture.surfaceTexture ?: return
            st.setDefaultBufferSize(previewSize.width, previewSize.height)
            val preview = Surface(st)
            previewSurface = preview
            main.post { fitPreview(previewSize) }

            val targets = mutableListOf(preview)
            var template = CameraDevice.TEMPLATE_PREVIEW
            if (videoMode) {
                val videoSize = pick(map.getOutputSizes(MediaRecorder::class.java), 16.0 / 9, 1920)
                val r = prepareRecorder(videoSize)
                targets.add(r.surface)
                template = CameraDevice.TEMPLATE_RECORD
            } else {
                val photoSize = pick(map.getOutputSizes(ImageFormat.JPEG), 4.0 / 3, 4100, 12_500_000)
                val reader = ImageReader.newInstance(photoSize.width, photoSize.height, ImageFormat.JPEG, 2)
                reader.setOnImageAvailableListener({ rd ->
                    val img = (try { rd.acquireNextImage() } catch (_: Exception) { null }) ?: return@setOnImageAvailableListener
                    val bytes = try {
                        val buf = img.planes[0].buffer
                        ByteArray(buf.remaining()).also { buf.get(it) }
                    } finally { img.close() }
                    main.post { savePhoto(bytes) }
                }, cam)
                imageReader = reader
                targets.add(reader.surface)
            }
            val builderTemplate = template
            d.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (camera !== d) { try { s.close() } catch (_: Exception) {}; return }
                    session = s
                    try {
                        val req = d.createCaptureRequest(builderTemplate).apply {
                            addTarget(preview)
                            if (builderTemplate == CameraDevice.TEMPLATE_RECORD) recorder?.surface?.let { addTarget(it) }
                            set(CaptureRequest.CONTROL_AF_MODE,
                                if (builderTemplate == CameraDevice.TEMPLATE_RECORD) CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                                else CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                        }
                        s.setRepeatingRequest(req.build(), null, cam)
                    } catch (e: Exception) {
                        main.post { fail(L.t("בעיה בהפעלת המצלמה", "Couldn't start the camera")) }
                    }
                }
                override fun onConfigureFailed(s: CameraCaptureSession) { main.post { fail(L.t("בעיה בהפעלת המצלמה", "Couldn't start the camera")) } }
            }, cam)
        } catch (e: Exception) {
            main.post { fail(L.t("בעיה בהפעלת המצלמה: ${e.message ?: ""}", "Couldn't start the camera: ${e.message ?: ""}")) }
        }
    }

    /** Fills the screen with the preview (cropping the edges) without stretching it. Main thread. */
    private fun fitPreview(buffer: Size) {
        val w = textureHolder.width
        val h = textureHolder.height
        if (w == 0 || h == 0) {
            textureHolder.post { fitPreview(buffer) }
            return
        }
        // The phone is held upright: the camera image is rotated, so its shape is height:width.
        val aspect = buffer.height.toFloat() / buffer.width
        val (tw, th) = if (w.toFloat() / h > aspect) w to (w / aspect).toInt() else (h * aspect).toInt() to h
        texture.layoutParams = FrameLayout.LayoutParams(tw, th, Gravity.CENTER)
        texture.scaleX = if (front) -1f else 1f
    }

    private fun closeCamera() {
        try { session?.close() } catch (_: Exception) {}
        session = null
        try { camera?.close() } catch (_: Exception) {}
        camera = null
        releaseRecorder()
        try { imageReader?.close() } catch (_: Exception) {}
        imageReader = null
        try { previewSurface?.release() } catch (_: Exception) {}
        previewSurface = null
    }

    // ---------- photo ----------

    private fun takePhoto() {
        if (saving) return
        val d = camera ?: return
        val s = session ?: return
        val reader = imageReader ?: return
        cam.post {
            try {
                val req = d.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(reader.surface)
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                    set(CaptureRequest.JPEG_ORIENTATION, sensorOrientation)
                }
                s.capture(req.build(), null, cam)
            } catch (_: Exception) {
                main.post { Toast.makeText(this, L.t("לא הצלחנו לצלם, נסו שוב", "Couldn't take the photo. Try again"), Toast.LENGTH_SHORT).show() }
            }
        }
    }

    private fun savePhoto(bytes: ByteArray) {
        if (saving) return
        saving = true
        busy.visibility = View.VISIBLE
        worker.execute {
            val memory = try { NewMemory.photo(applicationContext, bytes) } catch (_: Exception) { null }
            main.post { done(memory) }
        }
    }

    // ---------- story video ----------

    private fun prepareRecorder(size: Size): MediaRecorder {
        val file = File(cacheDir, "story_${System.currentTimeMillis()}.mp4")
        videoFile = file
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
        if (haveAudio) r.setAudioSource(MediaRecorder.AudioSource.MIC)
        r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setOutputFile(file.absolutePath)
        r.setVideoEncodingBitRate(if (size.width >= 1920) 9_000_000 else 5_000_000)
        r.setVideoFrameRate(30)
        r.setVideoSize(size.width, size.height)
        r.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        if (haveAudio) {
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(128_000)
            r.setAudioSamplingRate(44_100)
        }
        r.setOrientationHint(sensorOrientation)
        r.setMaxDuration(NewMemory.MAX_STORY_MS.toInt())
        r.setOnInfoListener { _, what, _ ->
            if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) main.post { stopRecording() }
        }
        r.prepare()
        recorder = r
        return r
    }

    private fun releaseRecorder() {
        try { recorder?.reset() } catch (_: Exception) {}
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null
        if (!recording) videoFile?.delete()
        if (!recording) videoFile = null
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (!recording) return
            val ms = SystemClock.elapsedRealtime() - recordStart
            shutter.progress = (ms.toFloat() / NewMemory.MAX_STORY_MS).coerceIn(0f, 1f)
            shutter.invalidate()
            val s = (ms / 1000).toInt()
            timerText.text = "● %d:%02d / 1:00".format(s / 60, s % 60)
            main.postDelayed(this, 100)
        }
    }

    private fun startRecording() {
        if (!videoMode || recording || saving) return
        val r = recorder ?: return
        if (session == null) return
        try {
            r.start()
        } catch (_: Exception) {
            Toast.makeText(this, L.t("לא הצלחנו להתחיל להקליט", "Couldn't start recording"), Toast.LENGTH_SHORT).show()
            return
        }
        recording = true
        recordStart = SystemClock.elapsedRealtime()
        timerText.visibility = View.VISIBLE
        flipBtn.alpha = 0.3f
        photoTab.alpha = 0.3f
        videoTab.alpha = 0.3f
        shutter.recording = true
        hintText.text = L.t("מקליטים... לחצו כדי לעצור", "Recording... tap to stop")
        main.post(ticker)
    }

    private fun stopRecording() {
        if (!recording) return
        recording = false
        main.removeCallbacks(ticker)
        val r = recorder
        val file = videoFile
        var ok = true
        cam.post { try { session?.stopRepeating(); session?.abortCaptures() } catch (_: Exception) {} }
        try { r?.stop() } catch (_: RuntimeException) { ok = false }
        try { r?.reset(); r?.release() } catch (_: Exception) {}
        recorder = null
        videoFile = null
        shutter.recording = false
        shutter.progress = 0f
        shutter.invalidate()
        timerText.visibility = View.GONE
        if (!ok || file == null || !file.exists() || file.length() < 2_000) {
            file?.delete()
            Toast.makeText(this, L.t("הסרטון היה קצר מדי. נסו שוב", "That video was too short. Try again"), Toast.LENGTH_SHORT).show()
            flipBtn.alpha = 1f; photoTab.alpha = 1f; videoTab.alpha = 1f
            refreshMode()
            cam.post { startSession() }
            return
        }
        saving = true
        busy.visibility = View.VISIBLE
        worker.execute {
            val memory = try { NewMemory.video(applicationContext, file) } catch (_: Exception) { null }
            main.post { done(memory) }
        }
    }

    private fun done(memory: Memory?) {
        if (isFinishing || isDestroyed) return
        if (memory == null) {
            saving = false
            busy.visibility = View.GONE
            flipBtn.alpha = 1f; photoTab.alpha = 1f; videoTab.alpha = 1f
            Toast.makeText(this, L.t("לא הצלחנו לשמור את הרגע. נסו שוב", "Couldn't save the moment. Try again"), Toast.LENGTH_LONG).show()
            refreshMode()
            cam.post { startSession() }
            return
        }
        MomentActivity.open(this, memory.id)
        finish()
    }

    override fun onResume() {
        super.onResume()
        visible = true
        // After any close queued by onPause has run on the camera thread.
        cam.post { main.post { if (visible && camAllowed && camera == null && !saving) startCamera() } }
    }

    override fun onPause() {
        super.onPause()
        visible = false
        main.removeCallbacks(holdRunnable)
        if (recording) {
            // Leaving the screen mid-recording drops the clip (nothing half-saved).
            recording = false
            main.removeCallbacks(ticker)
            try { recorder?.stop() } catch (_: Exception) {}
            videoFile?.delete()
            videoFile = null
            shutter.recording = false
            shutter.progress = 0f
            timerText.visibility = View.GONE
            flipBtn.alpha = 1f; photoTab.alpha = 1f; videoTab.alpha = 1f
            refreshMode()
        }
        if (!saving) {
            openGen++
            cam.post { closeCamera() }
        }
    }

    override fun onDestroy() {
        openGen++
        main.removeCallbacksAndMessages(null)
        cam.post { closeCamera(); cameraThread.quitSafely() }
        worker.shutdown()
        super.onDestroy()
    }

    /** The round capture button: white ring, a red dot while recording, and a progress ring up to one minute. */
    private class ShutterView(ctx: Context) : View(ctx) {
        var videoMode = false
        var recording = false
        var progress = 0f
        var down = false
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFFE5484D.toInt(); strokeCap = Paint.Cap.ROUND }
        private val box = RectF()

        override fun onDraw(c: Canvas) {
            val d = resources.displayMetrics.density
            val cx = width / 2f
            val cy = height / 2f
            val r = minOf(width, height) / 2f - 5 * d
            ring.strokeWidth = 4 * d
            c.drawCircle(cx, cy, r, ring)
            if (recording) {
                arc.strokeWidth = 5 * d
                box.set(cx - r, cy - r, cx + r, cy + r)
                c.drawArc(box, -90f, 360f * progress, false, arc)
                fill.color = 0xFFE5484D.toInt()
                val s = r * 0.42f
                box.set(cx - s, cy - s, cx + s, cy + s)
                c.drawRoundRect(box, 6 * d, 6 * d, fill)
            } else {
                fill.color = if (videoMode) 0xFFE5484D.toInt() else Color.WHITE
                c.drawCircle(cx, cy, (r - 8 * d) * (if (down) 0.85f else 1f), fill)
            }
        }
    }
}
