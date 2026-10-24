package com.regaim.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout

/**
 * The child's face in a small circle during a recorded walk (the front camera, a preview only: nothing is saved by
 * it, the screen recording is what keeps it). The camera doesn't take the microphone, so Pitzki still hears the
 * child. Started by [start], always stopped by [stop] (the end of the walk, leaving the screen).
 */
class SelfieBubble(ctx: Context) : FrameLayout(ctx) {
    private val texture = TextureView(ctx)
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var preview: Size? = null
    @Volatile private var wanted = false

    init {
        addView(texture, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        // Round, with a soft white ring.
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) { outline.setOval(0, 0, view.width, view.height) }
        }
        clipToOutline = true
        foreground = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setStroke((3 * resources.displayMetrics.density).toInt(), android.graphics.Color.WHITE)
        }
        elevation = 8 * resources.displayMetrics.density
        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) { if (wanted) open() }
            override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) { fit() }
            override fun onSurfaceTextureDestroyed(s: SurfaceTexture): Boolean { close(); return true }
            override fun onSurfaceTextureUpdated(s: SurfaceTexture) {}
        }
    }

    fun start() {
        wanted = true
        if (texture.isAvailable) open()
    }

    fun stop() {
        wanted = false
        close()
    }

    private fun manager() = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    @SuppressLint("MissingPermission")
    private fun open() {
        if (camera != null) return
        if (context.checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) { visibility = GONE; return }
        try {
            val mgr = manager()
            val id = mgr.cameraIdList.firstOrNull { mgr.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT }
                ?: run { visibility = GONE; return }
            val map = mgr.getCameraCharacteristics(id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return
            // A small preview is enough for a little circle: the smallest one from 480 up, about 4:3.
            val sizes = map.getOutputSizes(SurfaceTexture::class.java) ?: return
            preview = sizes.filter { it.width >= 480 && kotlin.math.abs(it.width.toDouble() / it.height - 4.0 / 3) < 0.05 }.minByOrNull { it.width }
                ?: sizes.filter { it.width >= 480 }.minByOrNull { it.width } ?: sizes.first()
            val t = HandlerThread("selfie").apply { start() }
            thread = t; handler = Handler(t.looper)
            mgr.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) {
                    if (!wanted) { d.close(); return }
                    camera = d
                    startPreview(d)
                }
                override fun onDisconnected(d: CameraDevice) { d.close(); if (camera === d) camera = null }
                override fun onError(d: CameraDevice, error: Int) { d.close(); if (camera === d) camera = null; post { visibility = GONE } }
            }, handler)
        } catch (_: Exception) { visibility = GONE; close() }
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
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            }
            d.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (camera !== d) { s.close(); return }
                    session = s
                    try { s.setRepeatingRequest(req.build(), null, handler) } catch (_: Exception) {}
                    post { fit() }
                }
                override fun onConfigureFailed(s: CameraCaptureSession) { post { visibility = GONE } }
            }, handler)
        } catch (_: Exception) { post { visibility = GONE } }
    }

    /** The picture fills the circle (cropped, not stretched), upright in the sideways screen. */
    private fun fit() {
        val p = preview ?: return
        val vw = texture.width.toFloat(); val vh = texture.height.toFloat()
        if (vw <= 0f || vh <= 0f) return
        @Suppress("DEPRECATION")
        val rotation = (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
        val m = Matrix()
        val view = RectF(0f, 0f, vw, vh)
        val cx = view.centerX(); val cy = view.centerY()
        if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
            val buf = RectF(0f, 0f, p.height.toFloat(), p.width.toFloat())
            buf.offset(cx - buf.centerX(), cy - buf.centerY())
            m.setRectToRect(view, buf, Matrix.ScaleToFit.FILL)
            val s = maxOf(vh / p.height, vw / p.width)
            m.postScale(s, s, cx, cy)
            m.postRotate(90f * (rotation - 2), cx, cy)
        } else {
            // Upright phone: the picture is tall (short x long); stretched into the circle, so squeezed back.
            val ratio = p.width.toFloat() / p.height
            m.setScale(1f, ratio, cx, cy)
            if (rotation == Surface.ROTATION_180) m.postRotate(180f, cx, cy)
        }
        texture.setTransform(m)
    }

    private fun close() {
        try { session?.close() } catch (_: Exception) {}
        session = null
        try { camera?.close() } catch (_: Exception) {}
        camera = null
        thread?.quitSafely()
        thread = null; handler = null
    }
}
