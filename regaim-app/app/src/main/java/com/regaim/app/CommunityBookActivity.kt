package com.regaim.app

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.Executors

/**
 * Reading a book another parent shared in the community: full screen, page by page. Tap the left side for the next
 * page and the right side for the previous one (Hebrew reading direction); the page's words are under the picture.
 */
class CommunityBookActivity : AppCompatActivity() {
    companion object {
        /** The post to show (its links are short-lived, so it's handed over as it is, not re-read). */
        private var post: Community.Post? = null
        fun open(ctx: Context, p: Community.Post) {
            post = p
            ctx.startActivity(Intent(ctx, CommunityBookActivity::class.java))
        }
    }

    private lateinit var pic: ImageView
    private lateinit var words: TextView
    private lateinit var count: TextView
    private lateinit var wait: ProgressBar
    private var pages: List<Pair<String, String>> = emptyList()
    private var at = 0
    private val loader = Executors.newFixedThreadPool(2)

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = post
        pages = p?.pages.orEmpty().ifEmpty { if (p != null && p.image.isNotEmpty()) listOf(p.image to p.title) else emptyList() }
        if (pages.isEmpty()) { finish(); return }

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val stage = FrameLayout(this)
        pic = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        stage.addView(pic, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        wait = ProgressBar(this)
        stage.addView(wait, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER))
        column.addView(stage, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        words = TextView(this).apply {
            setTextColor(0xFF2A2420.toInt()); textSize = 18f; gravity = Gravity.CENTER
            setLineSpacing(dp(4).toFloat(), 1f)
            setPadding(dp(20), dp(14), dp(20), dp(14))
            background = GradientDrawable().apply { setColor(0xFFF8F2E7.toInt()) }
            typeface = Fonts.display
        }
        column.addView(words, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Taps: left = next, right = previous.
        val touch = View(this)
        touch.setOnClickListener(null)
        touch.setOnTouchListener { v, e ->
            if (e.action == android.view.MotionEvent.ACTION_UP) { if (e.x < v.width / 2f) go(at + 1) else go(at - 1) }
            true
        }
        root.addView(touch, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(10), dp(10))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x99000000.toInt(), 0))
        }
        count = TextView(this).apply { setTextColor(Color.WHITE); textSize = 14f }
        top.addView(TextView(this).apply {
            text = p?.title.orEmpty(); setTextColor(Color.WHITE); textSize = 15f; setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(count)
        top.addView(TextView(this).apply {
            text = "✕"; textSize = 20f; setTextColor(Color.WHITE); setPadding(dp(14), dp(4), dp(6), dp(4))
            setOnClickListener { finish() }
        })
        root.addView(top, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        setContentView(root)
        // Black behind the clock bar too (the reader is dark), with light icons.
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.BLACK))
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightNavigationBars = false
        go(0)
        // The next pages load in the background, so turning is quick.
        pages.drop(1).take(3).forEach { (url, _) -> loader.execute { Community.image(applicationContext, url) } }
    }

    private fun go(i: Int) {
        if (i < 0 || i >= pages.size) { if (i >= pages.size) finish(); return }
        at = i
        val (url, text) = pages[i]
        count.text = if (i == 0) L.t("כריכה · ${pages.size - 1} עמודים", "Cover · ${pages.size - 1} pages") else "$i / ${pages.size - 1}"
        words.text = BookJson.display(text)
        words.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
        pic.setImageDrawable(null)
        wait.visibility = View.VISIBLE
        loader.execute {
            val bmp = Community.image(applicationContext, url)
            runOnUiThread {
                if (isDestroyed || at != i) return@runOnUiThread
                wait.visibility = View.GONE
                pic.setImageBitmap(bmp)
            }
            pages.getOrNull(i + 2)?.let { (next, _) -> Community.image(applicationContext, next) }
        }
    }

    override fun onDestroy() {
        loader.shutdownNow()
        super.onDestroy()
    }
}
