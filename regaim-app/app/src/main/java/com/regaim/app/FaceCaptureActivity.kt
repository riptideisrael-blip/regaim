package com.regaim.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Size
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.io.File
import java.util.concurrent.Executors

/**
 * The child photographed for Pitzki's film, with guidance: the selfie camera (or the back one), a circle to put the
 * face in, one short hint at a time ([FaceGuide]: closer, more light, eyes open, hold still), and three pictures
 * taken by themselves when the face is right: from the front and a little to each side. The sharpest of a few frames
 * is kept, cropped around the face. The pictures replace the child's earlier face photos. Nothing leaves the phone
 * here (the film is drawn from them later, as before).
 */
class FaceCaptureActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_MEMBER = "member"
        const val EXTRA_NAME = "name"
        fun intent(ctx: Context, member: String, name: String) =
            Intent(ctx, FaceCaptureActivity::class.java).putExtra(EXTRA_MEMBER, member).putExtra(EXTRA_NAME, name)
    }

    private val main = Handler(Looper.getMainLooper())
    private val work = Executors.newSingleThreadExecutor()
    private lateinit var texture: TextureView
    private lateinit var hint: TextView
    private lateinit var ask: TextView
    private lateinit var dots: TextView
    private lateinit var manual: TextView
    private lateinit var flash: View
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var camThread: HandlerThread? = null
    private var camHandler: Handler? = null
    private var preview: Size? = null
    private var front = true
    private var destroyed = false
    private val detector = FaceDetection.getClient(FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
        .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
        .build())

    private var step = FaceGuide.Step.FRONT
    private var firstSide = 0f
    private var readyFrames = 0
    private var busy = false
    private var stepStarted = 0L
    private var lastCenter: Pair<Float, Float>? = null
    /** The face's box in the last small frame (0..1 of it), for the crop. */
    private var lastBox: RectF? = null
    private val shots = ArrayList<Bitmap>()

    private val member get() = intent.getStringExtra(EXTRA_MEMBER).orEmpty()
    private val name get() = intent.getStringExtra(EXTRA_NAME).orEmpty()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ui = Ui(this)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.rgb(16, 16, 16)) }
        val w = resources.displayMetrics.widthPixels
        texture = TextureView(this).apply { layoutParams = FrameLayout.LayoutParams(w, w * 4 / 3, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = ui.dp(56) } }
        root.addView(texture)
        root.addView(Guide(this).apply { layoutParams = FrameLayout.LayoutParams(w, w * 4 / 3, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = ui.dp(56) } })
        flash = View(this).apply { setBackgroundColor(Color.WHITE); alpha = 0f; layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT) }
        root.addView(flash)
        // Top: close, the step, switch camera.
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(4))
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP)
        }
        top.addView(TextView(this).apply { text = "✕"; textSize = 22f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; minWidth = ui.dp(48); minHeight = ui.dp(48); setPadding(ui.dp(8), 0, ui.dp(8), 0); setOnClickListener { finish() } })
        dots = TextView(this).apply { textSize = 15f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
        top.addView(dots)
        top.addView(TextView(this).apply {
            text = "🔄"; textSize = 22f; gravity = Gravity.CENTER; minWidth = ui.dp(48); minHeight = ui.dp(48); setPadding(ui.dp(8), 0, ui.dp(8), 0)
            contentDescription = L.t("להחליף מצלמה", "Switch camera")
            setOnClickListener { front = !front; closeCamera(); openCamera() }
        })
        root.addView(top)
        // Bottom: what to ask for, the hint, the manual button.
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(ui.dp(20), ui.dp(10), ui.dp(20), ui.dp(26))
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
        }
        ask = TextView(this).apply {
            textSize = 19f; typeface = DS.heading; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            // Readable over a bright camera picture, and read out when the step changes.
            background = DS.rounded(Color.argb(150, 0, 0, 0), ui.dp(16).toFloat())
            setPadding(ui.dp(14), ui.dp(6), ui.dp(14), ui.dp(6))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        hint = TextView(this).apply {
            textSize = 16f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            background = DS.rounded(Color.argb(150, 0, 0, 0), ui.dp(16).toFloat())
            setPadding(ui.dp(14), ui.dp(6), ui.dp(14), ui.dp(6))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.dp(8) }
        }
        manual = TextView(this).apply {
            text = L.t("📷 לצלם עכשיו", "📷 Take it now"); textSize = 16f; typeface = DS.emphasis; gravity = Gravity.CENTER
            setTextColor(Color.BLACK)
            background = DS.rounded(Color.WHITE, ui.dp(22).toFloat())
            setPadding(ui.dp(22), ui.dp(10), ui.dp(22), ui.dp(10))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.dp(12) }
            visibility = View.INVISIBLE
            setOnClickListener { if (!busy) take() }
        }
        bottom.addView(ask); bottom.addView(hint); bottom.addView(manual)
        root.addView(bottom)
        setContentView(root)
        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) { openCamera() }
            override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(s: SurfaceTexture): Boolean { closeCamera(); return true }
            override fun onSurfaceTextureUpdated(s: SurfaceTexture) {}
        }
        showStep()
        if (checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(android.Manifest.permission.CAMERA), 5)
        main.postDelayed(looker, 400)
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (checkSelfPermission(android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) { if (texture.isAvailable) openCamera() }
        else {
            android.widget.Toast.makeText(this, L.t("בלי מצלמה אפשר לבחור תמונות מהגלריה", "Without the camera you can choose photos from the gallery"), android.widget.Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun showStep() {
        val n = step.ordinal
        dots.text = (0 until 3).joinToString("  ") { if (it < n) "●" else if (it == n) "◉" else "○" }
        ask.text = FaceGuide.ask(step)
        readyFrames = 0
        stepStarted = System.currentTimeMillis()
        manual.visibility = View.INVISIBLE
    }

    /** About five times a second: the face in the frame is measured, the hint shown, and the picture taken when right. */
    private val looker = object : Runnable {
        override fun run() {
            if (destroyed) return
            if (busy || camera == null || !texture.isAvailable) { main.postDelayed(this, 250); return }
            // After a while without the picture: the parent can take it.
            if (System.currentTimeMillis() - stepStarted > 10_000) manual.visibility = View.VISIBLE
            val small = try { texture.getBitmap(360, 480) } catch (_: Throwable) { null }
            if (small == null) { main.postDelayed(this, 250); return }
            detector.process(InputImage.fromBitmap(small, 0))
                .addOnSuccessListener { faces ->
                    if (destroyed) return@addOnSuccessListener
                    val f = faces.maxByOrNull { it.boundingBox.width() }
                    val face = f?.let {
                        val b = it.boundingBox
                        val cx = b.exactCenterX() / small.width; val cy = b.exactCenterY() / small.height
                        val moved = lastCenter?.let { (x, y) -> kotlin.math.hypot((cx - x).toDouble(), (cy - y).toDouble()).toFloat() } ?: 1f
                        lastCenter = cx to cy
                        lastBox = RectF(b.left.toFloat() / small.width, b.top.toFloat() / small.height, b.right.toFloat() / small.width, b.bottom.toFloat() / small.height)
                        val eyes = listOfNotNull(it.leftEyeOpenProbability, it.rightEyeOpenProbability).minOrNull()
                        FaceGuide.Face(it.headEulerAngleY, b.width().toFloat() / small.width, cx, cy, eyes, light(small, b), moved)
                    }
                    if (f == null) lastCenter = null
                    val h = FaceGuide.check(step, faces.size, face, firstSide)
                    hint.text = if (h == FaceGuide.READY) L.t("מעולה! לא זזים…", "Great! Hold still…") else h
                    readyFrames = if (h == FaceGuide.READY) readyFrames + 1 else 0
                    if (readyFrames >= 3) { if (step == FaceGuide.Step.SIDE_A) firstSide = face?.yaw ?: 0f; take() }
                    small.recycle()
                    main.postDelayed(this, 200)
                }
                .addOnFailureListener { small.recycle(); main.postDelayed(this, 300) }
        }
    }

    /** The face's average brightness (a few pixels sampled). */
    private fun light(b: Bitmap, r: android.graphics.Rect): Int {
        var sum = 0L; var n = 0
        val x0 = r.left.coerceIn(0, b.width - 1); val x1 = r.right.coerceIn(0, b.width - 1)
        val y0 = r.top.coerceIn(0, b.height - 1); val y1 = r.bottom.coerceIn(0, b.height - 1)
        var y = y0
        while (y <= y1) {
            var x = x0
            while (x <= x1) {
                val p = b.getPixel(x, y)
                sum += (299 * Color.red(p) + 587 * Color.green(p) + 114 * Color.blue(p)) / 1000; n++
                x += 4
            }
            y += 4
        }
        return if (n == 0) 0 else (sum / n).toInt()
    }

    /** A few frames, the sharpest one kept (cropped around the face), then the next step (or the end). */
    private fun take() {
        busy = true
        hint.text = L.t("📸", "📸")
        val frames = ArrayList<Bitmap>()
        fun grab(left: Int) {
            if (destroyed) return
            try { texture.bitmap?.let { frames.add(it) } } catch (_: Throwable) {}
            if (left > 1) { main.postDelayed({ grab(left - 1) }, 120); return }
            flash.alpha = 0.8f; flash.animate().alpha(0f).setDuration(300).start()
            val box = lastBox
            val mirror = front
            work.execute {
                val best = frames.map { crop(it, box, mirror) }.maxByOrNull { sharp(it) }
                frames.forEach { it.recycle() }
                main.post {
                    if (destroyed) return@post
                    if (best != null) shots.add(best)
                    next()
                }
            }
        }
        grab(4)
    }

    private fun crop(full: Bitmap, box: RectF?, mirror: Boolean): Bitmap {
        val w = full.width; val h = full.height
        val (l, t, side) = if (box != null) FaceGuide.crop(box.left * w, box.top * h, box.width() * w, box.height() * h, w, h)
            else Triple(0, (h - w).coerceAtLeast(0) / 2, minOf(w, h))
        val m = Matrix()
        val scale = minOf(1f, 1024f / side)
        m.postScale(scale, scale)
        // The selfie camera shows a mirror: the picture is turned back, as the child really is.
        if (mirror) m.postScale(-1f, 1f)
        return Bitmap.createBitmap(full, l, t, side, side, m, true)
    }

    private fun sharp(b: Bitmap): Double {
        val s = Bitmap.createScaledBitmap(b, 160, 160, true)
        val px = IntArray(160 * 160)
        s.getPixels(px, 0, 160, 0, 0, 160, 160)
        if (s !== b) s.recycle()
        val gray = IntArray(px.size) { (299 * Color.red(px[it]) + 587 * Color.green(px[it]) + 114 * Color.blue(px[it])) / 1000 }
        return FaceGuide.sharpness(gray, 160, 160)
    }

    private fun next() {
        busy = false
        lastCenter = null
        if (step != FaceGuide.Step.SIDE_B) { step = FaceGuide.Step.values()[step.ordinal + 1]; showStep(); return }
        // All three: saved as the child's face photos.
        hint.text = L.t("שומרים…", "Saving…")
        busy = true
        val pics = ArrayList(shots)
        work.execute {
            val n = PitzkiStore.saveFaceBitmaps(applicationContext, member, pics)
            main.post {
                if (destroyed) return@post
                android.widget.Toast.makeText(this, if (n > 0) L.t("נשמרו $n תמונות של $name ✓", "$n photos of $name saved ✓") else L.t("לא הצלחנו לשמור", "Couldn't save"), android.widget.Toast.LENGTH_SHORT).show()
                setResult(if (n > 0) Activity.RESULT_OK else Activity.RESULT_CANCELED)
                finish()
            }
        }
    }

    // ---------- the camera ----------

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        if (camera != null || destroyed) return
        if (checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        try {
            val mgr = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val want = if (front) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
            val id = mgr.cameraIdList.firstOrNull { mgr.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == want }
                ?: mgr.cameraIdList.firstOrNull() ?: return
            val map = mgr.getCameraCharacteristics(id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return
            val sizes = map.getOutputSizes(SurfaceTexture::class.java) ?: return
            // 4:3, about 1280 wide (sharp enough for a 1024 face, light enough for the phone).
            preview = sizes.filter { kotlin.math.abs(it.width.toDouble() / it.height - 4.0 / 3) < 0.02 && it.width <= 1920 }.maxByOrNull { it.width } ?: sizes.first()
            val t = HandlerThread("face-cam").apply { start() }
            camThread = t; camHandler = Handler(t.looper)
            mgr.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) { if (destroyed) { d.close(); return }; camera = d; startPreview(d) }
                override fun onDisconnected(d: CameraDevice) { d.close(); if (camera === d) camera = null }
                override fun onError(d: CameraDevice, error: Int) { d.close(); if (camera === d) camera = null }
            }, camHandler)
        } catch (_: Exception) {
            android.widget.Toast.makeText(this, L.t("לא הצלחנו לפתוח את המצלמה", "Couldn't open the camera"), android.widget.Toast.LENGTH_LONG).show()
        }
    }

    @Suppress("DEPRECATION")
    private fun startPreview(d: CameraDevice) {
        try {
            val p = preview ?: return
            val st = texture.surfaceTexture ?: return
            st.setDefaultBufferSize(p.width, p.height)
            val surface = Surface(st)
            val req = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            }
            d.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (camera !== d) { s.close(); return }
                    session = s
                    try { s.setRepeatingRequest(req.build(), null, camHandler) } catch (_: Exception) {}
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {}
            }, camHandler)
        } catch (_: Exception) {}
    }

    private fun closeCamera() {
        try { session?.close() } catch (_: Exception) {}
        session = null
        try { camera?.close() } catch (_: Exception) {}
        camera = null
        camThread?.quitSafely(); camThread = null; camHandler = null
    }

    override fun onPause() { super.onPause(); closeCamera() }
    override fun onResume() { super.onResume(); if (::texture.isInitialized && texture.isAvailable) openCamera() }

    override fun onDestroy() {
        destroyed = true
        main.removeCallbacksAndMessages(null)
        closeCamera()
        try { detector.close() } catch (_: Exception) {}
        work.shutdown()
        super.onDestroy()
    }

    /** The darkened frame with a clear oval where the face goes. */
    private class Guide(ctx: Context) : View(ctx) {
        private val shade = Paint().apply { color = Color.argb(120, 0, 0, 0) }
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE; strokeWidth = 3 * ctx.resources.displayMetrics.density }
        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            val oval = RectF(w * 0.2f, h * 0.45f - w * 0.38f, w * 0.8f, h * 0.45f + w * 0.38f)
            val p = Path().apply { fillType = Path.FillType.EVEN_ODD; addRect(0f, 0f, w, h, Path.Direction.CW); addOval(oval, Path.Direction.CW) }
            c.drawPath(p, shade)
            c.drawOval(oval, line)
        }
    }
}
