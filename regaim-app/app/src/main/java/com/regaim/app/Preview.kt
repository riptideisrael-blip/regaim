package com.regaim.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import java.io.ByteArrayOutputStream

/** Turns a small YUV camera frame into an upright Bitmap for the on-screen viewfinder. */
object Preview {
    fun toBitmap(image: Image, rotation: Int): Bitmap? {
        val w = image.width
        val h = image.height
        val nv21 = ByteArray(w * h * 3 / 2)

        val yPlane = image.planes[0]
        val yBuf = yPlane.buffer
        var pos = 0
        for (row in 0 until h) {
            yBuf.position(row * yPlane.rowStride)
            yBuf.get(nv21, pos, w)
            pos += w
        }

        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer
        val chromaH = h / 2
        val chromaW = w / 2
        for (row in 0 until chromaH) {
            for (col in 0 until chromaW) {
                val vIdx = row * vPlane.rowStride + col * vPlane.pixelStride
                val uIdx = row * uPlane.rowStride + col * uPlane.pixelStride
                nv21[pos++] = vBuf.get(vIdx)
                nv21[pos++] = uBuf.get(uIdx)
            }
        }

        val out = ByteArrayOutputStream()
        YuvImage(nv21, ImageFormat.NV21, w, h, null).compressToJpeg(Rect(0, 0, w, h), 70, out)
        val bytes = out.toByteArray()
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        if (rotation == 0) return bmp
        val m = Matrix().apply { postRotate(rotation.toFloat()) }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }
}
