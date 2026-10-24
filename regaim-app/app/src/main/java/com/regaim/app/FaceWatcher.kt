package com.regaim.app

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Looks at the camera about once a second, fully on the phone (Google ML Kit):
 *  - a big smile on a face in front of you, seen in two frames in a row -> "smile" moment
 *  - a face growing fast in the frame (a child running to you) -> "approach" moment
 *  - a hug: a face comes very close and right after that the camera on your chest goes from a normal picture
 *    to covered for ~3 seconds, or two similar faces stay pressed together in front of you for 3+ seconds -> "hug"
 * No face is recognized or stored; it only measures smiles, sizes and how dark/covered the picture is.
 */
class FaceWatcher(private val onMoment: (String) -> Unit) {

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setMinFaceSize(0.12f)
            .build()
    )
    private val busy = AtomicBoolean(false)
    private var lastRunMs = 0L
    /** The rules themselves (smile in two frames in a row, stricter hugs): see [FaceSignals]. */
    private val signals = FaceSignals()

    /** Feed an upright bitmap; skips frames while busy or too soon. */
    fun feed(bmp: Bitmap) {
        val now = System.currentTimeMillis()
        if (now - lastRunMs < 800 || !busy.compareAndSet(false, true)) return
        lastRunMs = now
        detector.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { faces ->
                val covered = isCovered(bmp)
                val frameArea = (bmp.width * bmp.height).toFloat()
                var smile = 0f
                val boxes = faces.map { f ->
                    val r = f.boundingBox
                    val area = r.width() * r.height() / frameArea
                    val s = f.smilingProbability ?: 0f
                    if (area > 0.02f && s > smile) smile = s
                    FaceSignals.Box(r.exactCenterX(), r.exactCenterY(), r.width().toFloat(), r.height().toFloat(), area)
                }
                Status.faces = faces.size
                Status.smile = smile
                signals.frame(now, boxes, smile, covered, Status.hugOn, Status.facesOn)?.let(onMoment)
            }
            .addOnCompleteListener { busy.set(false) }
    }

    /** Mostly dark or flat picture = the camera is pressed against someone (or covered). */
    private fun isCovered(bmp: Bitmap): Boolean {
        val n = 12
        var sum = 0.0
        var sumSq = 0.0
        for (i in 0 until n) for (j in 0 until n) {
            val p = bmp.getPixel((bmp.width - 1) * i / (n - 1), (bmp.height - 1) * j / (n - 1))
            val y = 0.299 * ((p shr 16) and 0xff) + 0.587 * ((p shr 8) and 0xff) + 0.114 * (p and 0xff)
            sum += y
            sumSq += y * y
        }
        val count = n * n
        val mean = sum / count
        val std = kotlin.math.sqrt((sumSq / count - mean * mean).coerceAtLeast(0.0))
        return mean < 40 || std < 14
    }

    fun close() {
        try { detector.close() } catch (_: Exception) {}
    }
}
