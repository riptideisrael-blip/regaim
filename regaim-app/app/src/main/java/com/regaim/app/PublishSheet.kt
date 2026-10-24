package com.regaim.app

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.util.concurrent.Executors

/**
 * "איפה לפרסם?": one sheet to send a memory to the family, to the community, or keep it private.
 * The privacy facts for the chosen audience are shown before anything leaves the phone.
 */
object PublishSheet {
    private class Option(val id: String, val icon: String, val title: String, val sub: String)

    fun show(a: AppCompatActivity, m: Memory, preselect: String = Community.lastAudience(a), startCaption: String = "", onDone: () -> Unit = {}) {
        val ui = Ui(a)
        val mv = MemoryViews(a)
        val worker = Executors.newSingleThreadExecutor()
        val dialog = BottomSheetDialog(a)
        dialog.setOnDismissListener { worker.shutdown() }
        var audience = if (preselect in listOf("family", "community", "private")) preselect else "family"
        var busy = false

        val root = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            setPadding(ui.dp(20), ui.dp(18), ui.dp(20), ui.dp(24))
        }
        root.addView(ui.text(L.t("איפה לפרסם?", "Where to share?"), 22f, bold = true))

        // The memory: small picture and its title.
        val top = ui.horizontal(top = 14)
        top.gravity = Gravity.CENTER_VERTICAL
        val thumb = ImageView(a).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply { cornerRadius = ui.dp(12).toFloat(); setColor(0xFFF0E6DC.toInt()) }
            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(ui.dp(64), ui.dp(80))
        }
        top.addView(thumb)
        Covers.load(thumb, m)
        val title = EditText(a).apply {
            hint = L.t("כותרת", "Title")
            setText(Fmt.title(m))
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 2
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = ui.dp(12) }
        }
        top.addView(title)
        root.addView(top)
        val caption = EditText(a).apply {
            hint = L.t("מה תרצו לספר על הרגע? (לא חובה)", "Say something about this moment (optional)")
            textSize = 15f
            minLines = 2
            maxLines = 5
            gravity = Gravity.TOP or Gravity.START
            setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10))
            background = GradientDrawable().apply { cornerRadius = ui.dp(16).toFloat(); setColor(0xFFF0E6DC.toInt()) }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.dp(12) }
        }
        if (startCaption.isNotEmpty()) caption.setText(startCaption)
        root.addView(caption)

        root.addView(ui.text(L.t("מי יראה?", "Who can see it?"), 16f, bold = true, top = 18))
        val options = listOf(
            Option("family", "👨‍👩‍👧", L.t("המשפחה שלי", "My family"), if (Circle.joined(a)) L.t("רק מי שהזמנתם למעגל ${Circle.name(a)}", "Only people you invited to ${Circle.name(a)}") else L.t("צריך קודם לפתוח מעגל משפחה", "Create a family circle first")),
            Option("community", "🌍", L.t("הקהילה", "Community"), L.t("כל משתמשי Moments יוכלו לראות", "Everyone on Moments can see it")),
            Option("private", "🔒", L.t("רק אצלי", "Just me"), L.t("נשאר בטלפון, אפשר לפרסם בכל זמן", "Stays on your phone. You can share it anytime")),
        )
        val optionBox = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        root.addView(optionBox)
        val details = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        root.addView(details)
        val consent = CheckBox(a).apply {
            text = L.t("יש לי זכות לפרסם את הרגע הזה, וקראתי את כללי הקהילה", "I have the right to share this moment, and I've read the community guidelines")
            textSize = 14f
        }
        val action = ui.button("", primary = true, top = 18) {}
        root.addView(action)

        fun actionLabel() = when (audience) {
            "family" -> L.t("שלחו למשפחה", "Send to family")
            "community" -> L.t("פרסמו בקהילה", "Share to community")
            else -> L.t("שמרו אצלי", "Keep private")
        }

        fun refresh() {
            optionBox.removeAllViews()
            for (o in options) {
                val on = o.id == audience
                val row = LinearLayout(a).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12))
                    background = GradientDrawable().apply {
                        cornerRadius = ui.dp(16).toFloat()
                        setColor(if (on) 0xFFF0E6DC.toInt() else 0xFFFFFFFF.toInt())
                        setStroke(ui.dp(if (on) 2 else 1), if (on) ContextCompat.getColor(a, R.color.accent) else 0xFFF0E6DC.toInt())
                    }
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.dp(8) }
                    setOnClickListener { if (!busy) { audience = o.id; refresh() } }
                }
                row.addView(TextView(a).apply { text = o.icon; textSize = 24f; setPadding(0, 0, ui.dp(12), 0) })
                val texts = LinearLayout(a).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                texts.addView(ui.text(o.title, 16f, bold = true))
                texts.addView(ui.hint(o.sub, top = 1))
                row.addView(texts)
                row.addView(TextView(a).apply {
                    text = if (on) "●" else "○"
                    textSize = 20f
                    setTextColor(ContextCompat.getColor(a, if (on) R.color.accent else R.color.ink_soft))
                })
                optionBox.addView(row)
            }
            details.removeAllViews()
            when (audience) {
                "community" -> {
                    val card = LinearLayout(a).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12))
                        background = GradientDrawable().apply { cornerRadius = ui.dp(16).toFloat(); setColor(0xFFFFF6DD.toInt()) }
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.dp(12) }
                    }
                    card.addView(ui.text(L.t("מה יוצג ומה לא", "What's shown and what's not"), 14f, bold = true))
                    val lines = mutableListOf(
                        L.t("יוצג: השם הפרטי שלכם, הכותרת והמילים שכתבתם", "Shown: your first name, the title and what you wrote"),
                        L.t("לא יוצג: מיקום ושמות של אנשים", "Not shown: location and people's names"),
                        L.t("אפשר למחוק בכל רגע", "You can delete it anytime"),
                    )
                    if (m.isVideo) lines.add(L.t("יפורסם קטע של כ-10 שניות סביב רגע השיא", "A ~10-second clip around the highlight will be shared"))
                    card.addView(ui.text(lines.joinToString("\n") { "• $it" }, 13.5f, R.color.ink_soft, top = 4))
                    if (Fmt.names(m).isNotEmpty()) {
                        card.addView(ui.text(L.t("ברגע הזה יש אנשים מזוהים. הקהילה גלויה לכולם, ובפרט כשמדובר בילדים כדאי לשקול פעמיים.", "People are recognizable in this moment. The community is visible to everyone, so think twice, especially when kids are involved."), 13.5f, R.color.ink, top = 8))
                    }
                    details.addView(card)
                    (consent.parent as? ViewGroup)?.removeView(consent)
                    consent.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.dp(8) }
                    details.addView(consent)
                }
                "family" -> {
                    if (Circle.joined(a) && m.isVideo) {
                        details.addView(ui.action(L.t("רוצים לבחור קטע או סגנון? ›", "Want to choose a clip or style? ›")) {
                            dialog.dismiss(); ShareActivity.share(a, m.id)
                        })
                    }
                }
                else -> details.addView(ui.hint(L.t("הרגע נשאר רק בטלפון שלכם.", "This moment stays on your phone only."), top = 10))
            }
            action.text = when {
                busy -> L.t("רגע...", "One moment…")
                audience != "private" && !Account.signedIn(a) -> L.t("התחברו כדי לפרסם", "Sign in to share")
                audience == "family" && !Circle.joined(a) -> L.t("פתחו מעגל משפחה", "Create a family circle")
                else -> actionLabel()
            }
            action.isEnabled = !busy
        }

        action.setOnClickListener {
            if (busy) return@setOnClickListener
            if (audience == "private") { Community.setLastAudience(a, "private"); savePrivacy(a, m, "private"); dialog.dismiss(); return@setOnClickListener }
            if (!Account.signedIn(a)) { dialog.dismiss(); WelcomeActivity.open(a); return@setOnClickListener }
            if (audience == "family" && !Circle.joined(a)) { dialog.dismiss(); CircleActivity.open(a); return@setOnClickListener }
            if (audience == "community" && !Community.termsAccepted(a)) {
                // The community rules (and "I'm 18 or older") come first.
                dialog.dismiss(); CommunityActivity.open(a); return@setOnClickListener
            }
            if (audience == "community" && !consent.isChecked) {
                Toast.makeText(a, L.t("כדי לפרסם בקהילה צריך לאשר את התיבה", "Check the box to share with the community"), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val t = title.text.toString()
            val c = caption.text.toString()
            val target = audience
            busy = true
            refresh()
            worker.execute {
                val err = try {
                    if (target == "community") {
                        // The community is public: the children's names never leave with the post (the AI title often has them).
                        val names = (Fmt.names(m) + Profile.members(a).map { it.name }).map { it.trim() }.filter { it.length >= 2 }.distinct()
                        fun strip(x: String) = names.fold(x) { acc, n -> acc.replace(n, "") }.replace(Regex("\\s{2,}"), " ").trim(' ', ',', '·', '-')
                        Community.publish(a.applicationContext, m, strip(t).ifBlank { L.t("רגע מהחיים שלנו", "A moment from our life") }, strip(c)) { }
                    }
                    else Circle.shareMoment(a.applicationContext, m, Circle.Draft(t, c, false, "plain")) { }
                    null
                } catch (e: Exception) { e.message ?: L.t("משהו השתבש", "Something went wrong") }
                a.runOnUiThread {
                    busy = false
                    if (a.isFinishing || a.isDestroyed) return@runOnUiThread
                    if (err != null) {
                        Toast.makeText(a, err, Toast.LENGTH_LONG).show()
                        refresh()
                    } else {
                        Community.setLastAudience(a, target)
                        savePrivacy(a, m, target)
                        dialog.dismiss()
                        Toast.makeText(a, if (target == "community") L.t("פורסם בקהילה ✓", "Shared to community ✓") else L.t("נשלח למשפחה ❤", "Sent to family ❤"), Toast.LENGTH_LONG).show()
                        if (target == "community") { CommunityActivity.dirty = true; CommunityActivity.open(a) }
                        onDone()
                    }
                }
            }
        }

        refresh()
        dialog.setContentView(ScrollView(a).apply { addView(root) })
        dialog.show()
    }

    /** Records the audience on the memory itself (see Privacy). A memory with a live community post stays Public. */
    private fun savePrivacy(a: AppCompatActivity, m: Memory, target: String) {
        val level = when {
            Community.wasPublished(a, m) -> Privacy.PUBLIC
            target == "community" -> Privacy.PUBLIC
            target == "family" -> Privacy.FAMILY
            else -> Privacy.PRIVATE
        }
        try { MemoryStore.get(a, m.id)?.let { MemoryStore.update(a, it.copy(privacy = level.key)) } } catch (_: Exception) { }
    }
}
