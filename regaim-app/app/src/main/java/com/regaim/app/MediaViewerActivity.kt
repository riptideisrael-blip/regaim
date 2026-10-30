package com.regaim.app

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.MediaController
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity

/**
 * A shared photo or video opened right here, full screen, with one tap: no "open with" list of other apps.
 * The video has the usual play / pause / seek controls (also for screen readers) and a Close button.
 */
class MediaViewerActivity : AppCompatActivity() {
    companion object {
        private const val EXTRA_URL = "url"
        private const val EXTRA_PHOTO = "photo"
        private const val EXTRA_TITLE = "title"

        fun open(ctx: Context, url: String, photo: Boolean, title: String = "") {
            if (url.isBlank()) return
            ctx.startActivity(Intent(ctx, MediaViewerActivity::class.java)
                .putExtra(EXTRA_URL, url).putExtra(EXTRA_PHOTO, photo).putExtra(EXTRA_TITLE, title))
        }
    }

    private var video: VideoView? = null
    @Volatile private var destroyed = false

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        val photo = intent.getBooleanExtra(EXTRA_PHOTO, false)
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val wait = ProgressBar(this).apply { contentDescription = L.t("טוען", "Loading") }
        root.addView(wait, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER))

        if (photo) {
            val image = ImageView(this).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                contentDescription = title.ifBlank { L.t("תמונה משותפת", "Shared photo") }
            }
            root.addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            Thread {
                val bmp = try { Circle.image(applicationContext, url) } catch (_: Throwable) { null }
                runOnUiThread {
                    if (destroyed) return@runOnUiThread
                    wait.visibility = View.GONE
                    if (bmp != null) image.setImageBitmap(bmp) else failed(root)
                }
            }.start()
        } else {
            val v = VideoView(this).apply { contentDescription = title.ifBlank { L.t("סרטון משותף", "Shared video") } }
            video = v
            root.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            val controls = MediaController(this).also { it.setAnchorView(v) }
            v.setMediaController(controls)
            v.setOnPreparedListener { mp ->
                wait.visibility = View.GONE
                mp.isLooping = false
                v.start()
                controls.show(3000)
            }
            v.setOnErrorListener { _, _, _ -> wait.visibility = View.GONE; failed(root); true }
            v.setVideoURI(Uri.parse(url))
        }

        // Close, top corner, 48dp and named.
        root.addView(TextView(this).apply {
            text = "✕"; textSize = 22f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            minWidth = dp(48); minHeight = dp(48)
            background = DS.rounded(0x66000000, dp(24).toFloat())
            contentDescription = L.t("סגירה", "Close")
            A11y.button(this)
            setOnClickListener { finish() }
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
            topMargin = dp(36); marginStart = dp(12)
        })
        if (title.isNotBlank()) root.addView(TextView(this).apply {
            text = title; textSize = 16f; setTextColor(Color.WHITE)
            setShadowLayer(8f, 0f, 2f, 0xCC000000.toInt())
            setPadding(dp(20), 0, dp(20), dp(80))
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        setContentView(root)
    }

    /** Couldn't load it (no connection, or the link expired): said in words, with a way back. */
    private fun failed(root: FrameLayout) {
        root.addView(TextView(this).apply {
            text = L.t("לא הצלחנו לפתוח את זה. בדקו את החיבור ונסו שוב.", "Couldn't open it. Check the connection and try again.")
            textSize = 16f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setPadding(dp(24), 0, dp(24), 0)
            A11y.live(this, urgent = true)
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
    }

    override fun onPause() {
        super.onPause()
        video?.pause()
    }

    override fun onDestroy() {
        destroyed = true
        video?.stopPlayback()
        super.onDestroy()
    }
}
