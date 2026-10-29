package com.regaim.app

import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.VideoView

/**
 * A video that fills its whole frame, like a story: scaled up to cover it (keeping its proportions), the edges that
 * don't fit cut off, centered. A VideoView on its own only fits inside, leaving black bars.
 */
object VideoCover {
    /** The size that covers [frameW]×[frameH] with a [videoW]×[videoH] picture (null if a size is unknown). */
    fun size(videoW: Int, videoH: Int, frameW: Int, frameH: Int): Pair<Int, Int>? {
        if (videoW <= 0 || videoH <= 0 || frameW <= 0 || frameH <= 0) return null
        val scale = maxOf(frameW.toFloat() / videoW, frameH.toFloat() / videoH)
        return Math.round(videoW * scale) to Math.round(videoH * scale)
    }

    /** Call when the video is prepared (its size is known). */
    fun apply(video: VideoView, frame: View, videoW: Int, videoH: Int) {
        val s = size(videoW, videoH, frame.width, frame.height) ?: return
        video.layoutParams = FrameLayout.LayoutParams(s.first, s.second, Gravity.CENTER)
    }
}
