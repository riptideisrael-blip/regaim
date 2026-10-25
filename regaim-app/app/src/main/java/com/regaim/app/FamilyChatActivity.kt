package com.regaim.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.Executors

/** Human family conversation; separate from ChatActivity, which is AI Ask. */
class FamilyChatActivity : AppCompatActivity() {
    companion object {
        fun open(ctx: Context, postId: String? = null, title: String? = null) = ctx.startActivity(
            Intent(ctx, FamilyChatActivity::class.java).putExtra("post_id", postId).putExtra("title", title))
    }
    private lateinit var ui: Ui
    private lateinit var thread: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var send: TextView
    private lateinit var status: TextView
    private val worker = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private var postId: String? = null
    private var loading = false
    private var sending = false
    private var active = false
    private var lastIds = emptyList<String>()
    private var shown = emptyList<Circle.Message>()
    private var loadedUser = ""
    private var loadedCircle = ""
    private val refresh = object : Runnable {
        override fun run() {
            if (!active) return
            // Messages still waiting are tried again with each refresh (and by WorkManager when the app is closed).
            if (FamilyOutbox.waiting(this@FamilyChatActivity, postId).any { it.state == FamilyOutbox.PENDING }) flushThenLoad() else load()
            handler.postDelayed(this, 10_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        postId = intent.getStringExtra("post_id")?.takeIf { it.isNotBlank() }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            setPadding(ui.dp(20), ui.dp(28), ui.dp(20), ui.dp(16))
        }
        root.addView(ui.action(L.t("חזרה", "Back")) { finish() })
        root.addView(ui.text(if (postId == null) Circle.name(this).ifBlank { L.t("שיחה במשפחה", "Family chat") } else L.t("שיחה על הזיכרון", "Chat about this memory"), 24f, bold = true))
        intent.getStringExtra("title")?.takeIf { it.isNotBlank() }?.let { root.addView(ui.hint(it, top = 4)) }
        root.addView(ui.hint(L.t("רק לחברים המאושרים במעגל · הודעות מהמשפחה", "Only approved circle members · family messages"), top = 5))
        status = ui.hint(L.t("רגע, פותחים את השיחה...", "Opening the chat…"), top = 10)
        root.addView(status)
        root.addView(ui.action(L.t("רעננו שיחה", "Refresh chat")) { load() })
        thread = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll = ScrollView(this).apply {
            isFillViewport = true; addView(thread)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        root.addView(scroll)
        input = ui.field(L.t("כתבו למשפחה...", "Write to your family…"), "", multiline = true).apply { maxLines = 4 }
        input.setText(savedInstanceState?.getString("draft").orEmpty())
        root.addView(input)
        send = ui.button(L.t("שלחו הודעה", "Send message"), primary = true, top = 8) { sendMessage() }
        root.addView(send)
        setContentView(root)
    }
    override fun onSaveInstanceState(outState: Bundle) { outState.putString("draft", input.text.toString()); super.onSaveInstanceState(outState) }
    override fun onResume() {
        super.onResume()
        if (loadedUser != Account.id(this) || loadedCircle != Circle.circleId(this)) { thread.removeAllViews(); lastIds = emptyList(); shown = emptyList() }
        if (!Circle.joined(this) || !Account.signedIn(this)) { thread.removeAllViews(); lastIds = emptyList(); status.text = L.t("התחברו למעגל כדי לפתוח את השיחה", "Join a circle to open the chat"); send.isEnabled = false; return }
        send.isEnabled = true; active = true
        draw(shown, force = true)
        flushThenLoad()
        handler.postDelayed(refresh, 10_000)
    }
    override fun onPause() { active = false; handler.removeCallbacks(refresh); super.onPause() }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); worker.shutdownNow(); super.onDestroy() }

    private fun load() {
        if (loading || worker.isShutdown || sending) return
        val requestedUser = Account.id(this)
        val requestedCircle = Circle.circleId(this)
        loading = true
        worker.execute {
            val result = try { Result.success(Circle.messages(applicationContext, postId)) } catch (e: Exception) { Result.failure(e) }
            runOnUiThread {
                loading = false
                if (isDestroyed || requestedUser != Account.id(this) || requestedCircle != Circle.circleId(this)) return@runOnUiThread
                result.onSuccess { messages ->
                    loadedUser = requestedUser; loadedCircle = requestedCircle
                    status.text = if (messages.size >= 100) L.t("מוצגות 100 ההודעות האחרונות", "Showing the latest 100 messages") else ""
                    draw(messages)
                }.onFailure {
                    status.text = if (it is java.io.IOException) L.t("אין חיבור כרגע. הודעות שכתבתם יישלחו לבד כשהחיבור יחזור.", "No connection right now. Messages you write will send by themselves when it's back.")
                        else it.message ?: L.t("אין חיבור לשיחה כרגע.", "Can't reach the chat right now.")
                    draw(shown)
                }
            }
        }
    }
    /**
     * The family's messages, then this account's messages that are still on their way ("pending", greyed) or were
     * refused ("not sent", with try again / delete). Redrawn only when something changed.
     */
    private fun draw(messages: List<Circle.Message>, force: Boolean = false) {
        shown = messages
        val waiting = FamilyOutbox.waiting(this, postId)
        val ids = messages.map { it.id } + waiting.map { it.clientId + ":" + it.state }
        if (!force && ids == lastIds && thread.childCount > 0) return
        lastIds = ids
        thread.removeAllViews()
        if (messages.isEmpty() && waiting.isEmpty()) thread.addView(ui.text(L.t("עוד לא התחילה שיחה ❤️\nזה המקום לשאול, להיזכר ולדבר יחד.", "No messages yet ❤️\nThis is the place to ask, reminisce and talk together."), 17f, R.color.ink_soft, top = 30))
        for (message in messages) {
            val bubble = ui.card(top = 10)
            bubble.gravity = if (message.authorId == Account.id(this)) Gravity.START else Gravity.END
            bubble.addView(ui.hint("${message.author} · ${Fmt.dayTime(message.createdAt)}", top = 0))
            bubble.addView(ui.text(message.body, 16f, top = 4))
            thread.addView(bubble)
        }
        for (item in waiting) {
            val bubble = ui.card(top = 10)
            bubble.gravity = Gravity.START
            bubble.alpha = if (item.state == FamilyOutbox.FAILED) 1f else 0.6f
            val label = if (item.state == FamilyOutbox.FAILED) L.t("⚠️ לא נשלחה", "⚠️ Not sent") else L.t("⏳ ממתינה לשליחה", "⏳ Waiting to send")
            bubble.addView(ui.hint("${item.author} · $label", top = 0))
            bubble.addView(ui.text(item.body, 16f, top = 4))
            if (item.state == FamilyOutbox.FAILED) {
                if (item.error.isNotBlank()) bubble.addView(ui.hint(item.error, top = 4))
                bubble.addView(ui.action(L.t("לנסות שוב", "Try again")) { FamilyOutbox.retry(this, item.clientId); draw(shown, force = true); flushThenLoad() })
                bubble.addView(ui.action(L.t("למחוק את ההודעה", "Delete the message")) { FamilyOutbox.discard(this, item.clientId); draw(shown, force = true) })
            }
            thread.addView(bubble)
        }
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    /** Sends what's waiting (if there is a connection), then shows the thread as the server has it. */
    private fun flushThenLoad() {
        if (sending || worker.isShutdown) { load(); return }
        sending = true
        worker.execute {
            val offline = try { FamilyOutbox.flush(applicationContext) } catch (_: Exception) { 0 }
            if (offline > 0) FamilyOutbox.schedule(applicationContext)
            runOnUiThread {
                sending = false
                if (isDestroyed) return@runOnUiThread
                if (offline > 0) status.text = L.t("אין חיבור כרגע. ההודעות יישלחו לבד כשהחיבור יחזור.", "No connection right now. The messages will send by themselves when it's back.")
                draw(shown)
                load()
            }
        }
    }

    /** The message goes into the outbox at once (shown as pending) and is sent from there; nothing is lost offline. */
    private fun sendMessage() {
        val body = input.text.toString().trim()
        if (body.isBlank() || worker.isShutdown) return
        if (body.length > 4000) { Toast.makeText(this, L.t("אפשר לשלוח עד 4,000 תווים", "Messages can be up to 4,000 characters"), Toast.LENGTH_LONG).show(); return }
        FamilyOutbox.add(this, body, postId)
        input.setText("")
        draw(shown, force = true)
        flushThenLoad()
    }
}
