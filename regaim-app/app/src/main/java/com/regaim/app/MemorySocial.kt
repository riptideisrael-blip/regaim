package com.regaim.app

import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.Executors

/** Social data is fetched for this exact shared memory, never inferred from local favorites. */
class MemorySocial(
    private val activity: AppCompatActivity,
    private val memoryId: String,
    private val share: () -> Unit,
) {
    private val ui = Ui(activity)
    private val executor = Executors.newSingleThreadExecutor()
    val view = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private var post: Circle.Post? = null
    private var comments = emptyList<Circle.Comment>()
    private var reactions = emptyList<Circle.Reaction>()
    private var busy = false
    private var closed = false
    private var loaded = false
    private var error = ""
    private var draft = ""
    private var field: EditText? = null
    private data class Scope(val server: String, val account: String, val circle: String)
    private fun currentScope() = Scope(Circle.url(activity), Account.id(activity), Circle.circleId(activity))
    private var scope = currentScope()
    private var requestSerial = 0L

    /** A comment draft and cached discussion belong to the family in which they were opened. */
    private fun syncScope(): Boolean {
        val current = currentScope()
        if (current == scope) return false
        scope = current
        requestSerial++
        post = null
        comments = emptyList()
        reactions = emptyList()
        field = null
        draft = ""
        loaded = false
        error = ""
        busy = false
        return true
    }

    fun load() {
        syncScope()
        if (closed || busy) return
        if (!Circle.joined(activity)) { render(); return }
        val requestedScope = scope
        val serial = ++requestSerial
        busy = true
        render()
        executor.execute {
            val result = runCatching {
                val found = Circle.postForMemory(activity.applicationContext, memoryId)
                Triple(found, found?.let { Circle.commentsForPost(activity.applicationContext, it.id) }.orEmpty(),
                    found?.let { Circle.reactionsForPost(activity.applicationContext, it.id) }.orEmpty())
            }
            activity.runOnUiThread {
                if (closed || activity.isDestroyed || activity.isFinishing) return@runOnUiThread
                if (syncScope()) { load(); return@runOnUiThread }
                if (serial != requestSerial || requestedScope != scope) return@runOnUiThread
                busy = false
                result.onSuccess { (found, discussion, hearts) ->
                    post = found; comments = discussion; reactions = hearts; error = ""; loaded = true
                }.onFailure { error = L.t("לא הצלחנו לטעון את השיחה במשפחה. בדקו את החיבור והגישה למעגל ונסו שוב.", "Couldn't load the family chat. Check your connection and circle access, then try again.") }
                render()
            }
        }
    }

    private fun mutate(action: () -> Unit, clearDraft: Boolean = false) {
        if (syncScope()) { load(); return }
        if (closed || busy) return
        val requestedScope = scope
        val serial = ++requestSerial
        draft = field?.text?.toString() ?: draft
        busy = true
        error = ""
        render()
        executor.execute {
            val result = runCatching {
                check(currentScope() == requestedScope) { "המעגל השתנה" }
                action()
            }
            activity.runOnUiThread {
                if (closed || activity.isDestroyed || activity.isFinishing) return@runOnUiThread
                if (syncScope()) { load(); return@runOnUiThread }
                if (serial != requestSerial || requestedScope != scope) return@runOnUiThread
                busy = false
                result.onSuccess { if (clearDraft) draft = ""; field = null; load() }
                    .onFailure { error = L.t("השינוי לא נשמר. הטקסט שלכם נשאר כאן ואפשר לנסות שוב.", "Your change wasn't saved. Your text is still here, so you can try again."); render() }
            }
        }
    }

    private fun render() {
        syncScope()
        draft = field?.text?.toString() ?: draft
        field = null
        view.removeAllViews()
        view.addView(ui.section(L.t("המשפחה בתוך הזיכרון", "Family in this memory")))
        if (!Circle.joined(activity)) {
            view.addView(ui.hint(L.t("הזיכרון הזה נשמר בטלפון. אתם בוחרים מתי לשתף אותו עם המשפחה.", "This memory is saved on your phone. You choose when to share it with your family.")))
            view.addView(ui.button(L.t("שתף עם המשפחה", "Share with family"), true) { share() })
            return
        }
        if (busy) view.addView(ui.hint(L.t("רגע, אנחנו שומרים מקום לשיחה המשפחתית…", "Setting up a family conversation…")))
        if (error.isNotEmpty()) {
            view.addView(ui.hint(error, 8))
            view.addView(ui.action(L.t("נסו שוב", "Try again")) { load() })
        }
        val shared = post
        if (shared == null) {
            if (loaded && error.isEmpty()) view.addView(ui.hint(L.t("הזיכרון הזה עדיין לא שותף עם ${Circle.name(activity)}.", "This memory hasn't been shared with ${Circle.name(activity)} yet.")))
            view.addView(ui.button(L.t("שתף עם המשפחה", "Share with family"), true) { share() }.apply { isEnabled = !busy })
            return
        }
        view.addView(ui.hint(L.t("שותף עם ${Circle.name(activity)} · תגובות המשפחה", "Shared with ${Circle.name(activity)} · Family comments")))
        val row = ui.horizontal(8)
        for (emoji in Circle.EMOJIS) {
            val same = reactions.filter { it.emoji == emoji }
            val mine = same.any { it.authorId.isNotBlank() && it.authorId == Account.id(activity) }
            row.addView(ui.action("$emoji ${same.size}") {
                mutate({ Circle.react(activity.applicationContext, shared.id, emoji, mine) })
            }.apply { isEnabled = !busy; alpha = if (mine) 1f else 0.75f; minimumHeight = ui.dp(48) })
        }
        view.addView(ui.scrollRow(row))
        view.addView(ui.text(if (comments.isEmpty()) L.t("תהיו הראשונים להוסיף משהו לזיכרון", "Be the first to add to this memory") else L.t("${comments.size} תגובות", "${comments.size} comments"), 15f, bold = true, top = 12))
        comments.takeLast(5).forEach { comment ->
            val card = ui.card(8)
            card.addView(ui.text(comment.member, 14f, bold = true))
            card.addView(ui.text(comment.body, 15f, top = 4))
            if (comment.createdAt > 0) card.addView(ui.hint(Fmt.dayTime(comment.createdAt)))
            view.addView(card)
        }
        if (comments.size > 5) view.addView(ui.action(L.t("כל התגובות במעגל המשפחה", "All comments in the family circle")) { CircleActivity.open(activity, shared.id) })
        val input = ui.field(L.t("כתבו תגובה למשפחה…", "Write a comment for your family…"), draft, multiline = true).apply { isEnabled = !busy }
        field = input
        view.addView(input)
        view.addView(ui.button(if (busy) L.t("שומרים את התגובה…", "Saving comment…") else L.t("שלחו תגובה", "Send comment")) {
            val body = input.text.toString().trim()
            if (body.isNotEmpty()) mutate({ Circle.comment(activity.applicationContext, shared.id, body) }, clearDraft = true)
        }.apply { isEnabled = !busy })
        view.addView(ui.button(L.t("💬 שיחה על הזיכרון", "💬 Chat about this memory")) { FamilyChatActivity.open(activity, shared.id, shared.title) })
    }

    fun close() { closed = true; executor.shutdownNow() }
}
