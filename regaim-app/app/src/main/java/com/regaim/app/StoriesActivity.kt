package com.regaim.app

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/** "סיפורים": stories made automatically by topic. Each row plays as a full-screen story with music. */
class StoriesActivity : AppCompatActivity() {

    companion object {
        fun open(ctx: Context) = ctx.startActivity(Intent(ctx, StoriesActivity::class.java))
    }

    private lateinit var mv: MemoryViews
    private lateinit var ui: Ui
    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_screen)
        mv = MemoryViews(this)
        ui = Ui(this)
        content = findViewById(R.id.screenContent)
        findViewById<TextView>(R.id.screenTitle).text = L.t("סיפורים", "Stories")
        findViewById<View>(R.id.btnClose).setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        content.removeAllViews()
        val topics = Topics.all(this)
        content.addView(ui.hint(if (topics.isEmpty()) L.t("כשיצטברו כמה רגעים, Moments יחבר אותם לסיפורים.", "Once you have a few moments, Moments will weave them into stories.") else L.t("${topics.size} נושאים", "${topics.size} topics"), top = 0))
        topics.forEachIndexed { i, t ->
            val v = topicRow(t)
            content.addView(v)
            if (i < 6) mv.reveal(v, i * 40L)
        }
    }

    private fun topicRow(t: Topics.Topic): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, mv.dp(16), 0, mv.dp(16))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val head = ui.horizontal(top = 0)
        head.addView(TextView(this).apply { text = t.icon; textSize = 20f })
        head.addView(TextView(this).apply {
            text = t.title
            textSize = 17f
            setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD))
            setTextColor(ContextCompat.getColor(this@StoriesActivity, R.color.ink))
            setPadding(mv.dp(10), 0, mv.dp(10), 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        head.addView(TextView(this).apply {
            text = L.t("${t.moments.size} רגעים", "${t.moments.size} moments")
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@StoriesActivity, R.color.ink_soft))
        })
        head.addView(mv.icon(R.drawable.ic_chevron, 20, R.color.ink_soft))
        head.setOnClickListener { play(t) }
        box.addView(head)

        val strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (m in t.moments.take(6)) {
            val frame = FrameLayout(this).apply {
                setBackgroundResource(R.drawable.rounded_media)
                clipToOutline = true
                layoutParams = LinearLayout.LayoutParams(mv.dp(112), mv.dp(160)).apply { marginEnd = mv.dp(10) }
                setOnClickListener { play(t) }
            }
            val iv = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            }
            frame.addView(iv)
            Covers.load(iv, m)
            Motion.attach(iv, m, width = 240)
            Fmt.names(m).firstOrNull()?.let { n ->
                frame.addView(mv.avatar(n, 28).apply {
                    layoutParams = FrameLayout.LayoutParams(mv.dp(28), mv.dp(28), Gravity.TOP or Gravity.START).apply {
                        setMargins(mv.dp(8), mv.dp(8), mv.dp(8), mv.dp(8))
                    }
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(0xFFEEF3F9.toInt())
                        setStroke(mv.dp(2), 0xFFFFFFFF.toInt())
                    }
                })
            }
            strip.addView(frame)
        }
        box.addView(ui.scrollRow(strip, top = 12))
        box.addView(View(this).apply {
            setBackgroundColor(0xFFEEF3F9.toInt())
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, mv.dp(1)).apply { topMargin = mv.dp(16) }
        })
        return box
    }

    private fun play(t: Topics.Topic) = StoryActivity.of(this, t.title, t.moments.map { it.id })
}
