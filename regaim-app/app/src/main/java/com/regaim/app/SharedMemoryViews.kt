package com.regaim.app

import android.content.Intent
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.Executors

/** Real shared posts, reusable on Home/Family. Owns lightweight image/action workers. */
class SharedMemoryViews(private val activity: AppCompatActivity) {
    private val ui = Ui(activity)
    private val mv = MemoryViews(activity)
    private val worker = Executors.newFixedThreadPool(2)
    fun close() = worker.shutdownNow()

    fun card(post: Circle.Post, feed: Circle.Feed, onChanged: () -> Unit): View {
        val requestedUser = Account.id(activity)
        val requestedCircle = Circle.circleId(activity)
        val card = ui.card(top = 16)
        val frame = FrameLayout(activity).apply {
            setBackgroundResource(R.drawable.rounded_media)
            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(250))
        }
        val image = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            colorFilter = Circle.styleFilter(post.style)
            contentDescription = post.title.ifBlank { L.t("זיכרון משותף", "Shared memory") }
        }
        frame.addView(image)
        val cover = post.cover.ifBlank { if (post.kind == "photo") post.video else "" }
        if (cover.isNotEmpty() && !worker.isShutdown) worker.execute {
            val bmp = Circle.image(activity.applicationContext, cover)
            activity.runOnUiThread { if (!activity.isDestroyed && requestedUser == Account.id(activity) && requestedCircle == Circle.circleId(activity) && bmp != null) image.setImageBitmap(bmp) }
        }
        if (post.kind != "photo" || post.pitzki) frame.addView(TextView(activity).apply {
            text = "▶"; textSize = 26f; setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(ui.dp(52), ui.dp(52), Gravity.CENTER)
            setBackgroundResource(R.drawable.play_circle)
        })
        frame.setOnClickListener {
            // Pitzki's film is many scenes: it plays in Pitzki's screen (downloaded there for the other parent).
            if (post.pitzki) { PitzkiActivity.open(activity); return@setOnClickListener }
            // Opens right here (no list of other apps to choose from).
            MediaViewerActivity.open(activity, post.video, photo = post.kind == "photo", title = post.title)
        }
        card.addView(frame)
        card.addView(ui.text(post.title.ifBlank { L.t("זיכרון מהמשפחה", "Family memory") }, 19f, bold = true, top = 12))
        val date = (post.takenAt.takeIf { it > 0 } ?: post.createdAt).takeIf { it > 0 }?.let { Fmt.dayTime(it) }.orEmpty()
        card.addView(ui.hint(listOf(post.author, if (post.onlyFor.isNotEmpty()) "🔒 " + L.t("רק בינינו", "Just us two") else L.t("שותף עם ${Circle.name(activity)}", "Shared with ${Circle.name(activity)}"), if (post.takenAt > 0) date else L.t("שותף: $date", "Shared: $date")).filter { it.isNotBlank() }.joinToString(" · "), top = 3))
        if (post.caption.isNotBlank()) card.addView(ui.text(post.caption, 16f, top = 8))
        if (post.story.isNotBlank()) card.addView(ui.text(post.story, 15f, R.color.ink_soft, top = 8))
        val actions = ui.horizontal(top = 12)
        for (emoji in Circle.EMOJIS) {
            val reactions = feed.reactions.filter { it.postId == post.id && it.emoji == emoji }
            val mine = reactions.any { it.authorId == Account.id(activity) && it.authorId.isNotEmpty() }
            val chip = mv.tag(emoji + if (reactions.isEmpty()) "" else " ${reactions.size}")
            chip.contentDescription = L.t("תגובה $emoji, ${reactions.size}", "Reaction $emoji, ${reactions.size}") + if (mine) L.t(", בחרת", ", selected by you") else ""
            chip.setOnClickListener {
                if (worker.isShutdown || !chip.isEnabled) return@setOnClickListener
                chip.isEnabled = false
                worker.execute {
                    val failure = try { Circle.react(activity.applicationContext, post.id, emoji, mine); null } catch (e: Exception) { e.message ?: L.t("התגובה לא נשמרה", "Reaction wasn't saved") }
                    activity.runOnUiThread {
                        if (!activity.isDestroyed) {
                            chip.isEnabled = true
                            if (failure == null) onChanged() else Toast.makeText(activity, failure, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            actions.addView(chip)
        }
        card.addView(ui.scrollRow(actions, top = 0))
        val count = feed.comments.count { it.postId == post.id }
        card.addView(ui.action(if (count == 0) L.t("פתחו את הזיכרון המשפחתי", "Open family memory") else L.t("$count תגובות · פתחו את הזיכרון", "$count comments · Open memory")) {
            CircleActivity.open(activity, post.id)
        })
        card.addView(ui.action(L.t("שיחה על הזיכרון", "Chat about this memory")) { FamilyChatActivity.open(activity, post.id, post.title) })
        return card
    }
}
