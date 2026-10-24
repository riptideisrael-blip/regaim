package com.regaim.app

import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView

/** Plays a video so it fills the whole frame (cropped from the centre), like Instagram/TikTok, with no bars. */
class FillVideoView(ctx: Context) : TextureView(ctx), TextureView.SurfaceTextureListener {
    private var mp: MediaPlayer? = null
    private var surface: Surface? = null
    private var url: String? = null
    private var vw = 0
    private var vh = 0
    private var prepared = false
    var onReady: () -> Unit = {}
    var onFailed: () -> Unit = {}
    /** No sound (a looping picture on a card). */
    var muted = false

    init { surfaceTextureListener = this }

    val isPlaying: Boolean get() = try { mp?.isPlaying == true } catch (_: Exception) { false }
    fun start() { try { if (prepared) mp?.start() } catch (_: Exception) { } }
    fun pause() { try { mp?.pause() } catch (_: Exception) { } }

    fun play(source: String) {
        url = source
        if (isAvailable) begin(surfaceTexture!!)
    }

    fun release() {
        prepared = false
        try { mp?.release() } catch (_: Exception) { }
        mp = null
        try { surface?.release() } catch (_: Exception) { }
        surface = null
    }

    private fun begin(st: SurfaceTexture) {
        val source = url ?: return
        release()
        try {
            val s = Surface(st)
            surface = s
            mp = MediaPlayer().apply {
                setSurface(s)
                setDataSource(source)
                isLooping = true
                setOnVideoSizeChangedListener { _, w, h -> vw = w; vh = h; fill() }
                setOnPreparedListener {
                    if (muted) it.setVolume(0f, 0f)
                    prepared = true
                    vw = it.videoWidth; vh = it.videoHeight
                    fill()
                    it.start()
                    onReady()
                }
                setOnErrorListener { _, _, _ -> onFailed(); true }
                prepareAsync()
            }
        } catch (_: Exception) { onFailed() }
    }

    /** TextureView stretches the video to the view; undo that and scale to cover. */
    private fun fill() {
        if (vw <= 0 || vh <= 0 || width <= 0 || height <= 0) return
        val scale = maxOf(width.toFloat() / vw, height.toFloat() / vh)
        val m = Matrix()
        m.setScale(scale * vw / width, scale * vh / height, width / 2f, height / 2f)
        setTransform(m)
    }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) { if (url != null) begin(st) }
    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) { fill() }
    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { release(); return true }
    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
}
