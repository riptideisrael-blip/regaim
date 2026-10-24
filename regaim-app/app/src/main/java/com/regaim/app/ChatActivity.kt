package com.regaim.app

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Search the saved memory records, with optional cloud understanding when explicitly configured. */
class ChatActivity : AppCompatActivity() {

    private lateinit var scroll: ScrollView
    private lateinit var thread: LinearLayout
    private lateinit var input: EditText
    private lateinit var sendBtn: TextView
    private lateinit var analyzeStatus: TextView
    private val worker = Executors.newSingleThreadExecutor()
    private val history = mutableListOf<Pair<String, String>>() // role, text
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)
        scroll = findViewById(R.id.chatScroll)
        thread = findViewById(R.id.chatThread)
        input = findViewById(R.id.chatInput)
        sendBtn = findViewById(R.id.btnSend)
        analyzeStatus = findViewById(R.id.analyzeStatus)

        Nav.setup(this, Nav.Tab.ASK)
        findViewById<TextView>(R.id.btnKey).apply {
            text = L.t("אפשרויות", "Options")
            contentDescription = L.t("הגדרת הבנת זיכרונות בעזרת AI", "Set up AI memory understanding")
            setOnClickListener { askForKey() }
            // Personal AI keys are a developer tool: families use the AI through Moments' server.
            visibility = if (AiKeys.usable(this@ChatActivity)) View.VISIBLE else View.GONE
        }
        sendBtn.setOnClickListener { send(input.text.toString()) }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                send(input.text.toString())
                true
            } else false
        }

        input.setText(savedInstanceState?.getString("draft") ?: "")
        addAssistant(
            L.t(
                "איזה רגע תרצו למצוא?\nכאן מחפשים בזיכרונות שנשמרו במכשיר לפי שמות, מקום ותיאור. " +
                    "תכנים מהמעגל המשפחתי עדיין אינם נכללים בחיפוש. בלי שירות AI, התשובות מבוססות על הפרטים שנשמרו בלבד. " +
                    "להבנת תוכן נוספת אפשר לבחור ״אפשרויות״.",
                "Which moment are you looking for?\nSearch the memories saved on your device by names, places and descriptions. " +
                    "Family circle content isn't included in search yet. Without an AI service, answers are based only on saved details. " +
                    "For deeper understanding, tap \"Options\".",
            ),
            emptyList(),
        )
    }

    override fun onResume() {
        super.onResume()
        renderSuggestions()
        analyzeInBackground()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("draft", input.text.toString())
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    // ---------- AI ----------

    private fun renderSuggestions() {
        val memories = MemoryStore.kept(this)
        val questions = mutableListOf(L.t("הזיכרונות האחרונים", "Recent memories"))
        if (memories.any { it.favorite }) questions.add(L.t("הזיכרונות האהובים", "Favorite memories"))
        memories.flatMap { it.allPeople.split(",").map(String::trim).filter(String::isNotEmpty) }
            .distinct().take(2).forEach { questions.add(L.t("זיכרונות עם $it", "Memories with $it")) }
        memories.firstOrNull { it.place.isNotBlank() }?.let { questions.add(L.t("זיכרונות מ${it.place}", "Memories from ${it.place}")) }
        if (memories.isNotEmpty()) questions.add(L.t("מתי צולם הזיכרון האחרון?", "When was the latest memory taken?"))
        val chips = findViewById<LinearLayout>(R.id.suggestions)
        chips.removeAllViews()
        for (q in questions.take(5)) {
            val chip = layoutInflater.inflate(R.layout.item_chip, chips, false) as TextView
            chip.text = q
            chip.setOnClickListener { send(q) }
            chips.addView(chip)
        }
    }

    private fun analyzeInBackground() {
        if (!ClaudeClient.hasKey(this)) {
            analyzeStatus.visibility = View.GONE
            return
        }
        val pending = MemoryStore.kept(this).count { !it.analyzed }
        if (pending == 0) {
            analyzeStatus.visibility = View.GONE
            return
        }
        analyzeStatus.visibility = View.VISIBLE
        analyzeStatus.text = L.t("אנחנו מבינים את הרגעים החדשים...", "Getting to know your new moments…")
        worker.execute {
            try { MemoryStore.analyzePending(applicationContext) } catch (_: Exception) { }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val left = MemoryStore.kept(this).count { !it.analyzed }
                if (left == 0) {
                    analyzeStatus.visibility = View.GONE
                } else {
                    analyzeStatus.text = L.t("חלק מהזיכרונות עדיין מחכים לתיאור. אפשר לצפות בהם ולנסות שוב בהמשך.", "Some memories are still waiting for a description. You can view them and try again later.")
                }
                renderSuggestions()
            }
        }
    }

    private fun send(raw: String) {
        val q = raw.trim()
        if (q.isEmpty() || busy) return
        input.setText(q)
        val userMessage = addUser(q)
        val thinking = addAssistant(L.t("רגע, אנחנו מחפשים בזיכרונות...", "Searching your memories…"), emptyList())
        busy = true
        sendBtn.isEnabled = false
        input.isEnabled = false
        val past = history.toList()
        worker.execute {
            val result = runCatching {
                val memories = MemoryStore.kept(applicationContext)
                if (memories.isEmpty()) {
                    ClaudeClient.Answer(L.t("עדיין אין לנו זיכרונות ❤️\nאפשר לשמור רגע ראשון ממסך הבית. כשיצטברו זיכרונות, נוכל למצוא אותם כאן.", "No memories yet ❤️\nSave your first moment from the home screen. Once you have some, you can find them here."), emptyList())
                } else if (!ClaudeClient.hasKey(applicationContext)) {
                    localAnswer(q, memories)
                } else {
                    // Only the moments the question is about go to the AI (found here on the phone first).
                    val picked = Retrieval.pick(q, memories)
                    ClaudeClient.ask(applicationContext, q, picked.memories, past, picked.matched)
                }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                thread.removeView(thinking)
                busy = false
                sendBtn.isEnabled = true
                input.isEnabled = true
                result.onSuccess { answer ->
                    val byId = MemoryStore.kept(this).associateBy { it.id }
                    addAssistant(answer.text, answer.ids.mapNotNull { byId[it] })
                    history.add("user" to q)
                    history.add("assistant" to answer.text)
                    input.setText("")
                }.onFailure {
                    thread.removeView(userMessage)
                    addAssistant(L.t("לא הצלחנו להשלים את החיפוש כרגע. השאלה נשמרה בשדה למטה, ואפשר לנסות שוב. אם בחרת שירות AI, כדאי לבדוק את החיבור ואת ההגדרה ב״אפשרויות״.", "We couldn't finish the search right now. Your question is saved below so you can try again. If you're using an AI service, check your connection and the setup under \"Options\"."), emptyList())
                    input.setText(q)
                    input.setSelection(input.text.length)
                }
            }
        }
    }

    /** A bounded search of explicit saved fields; it does not understand unseen video content. */
    private fun localAnswer(question: String, memories: List<Memory>): ClaudeClient.Answer {
        val q = question.lowercase(Locale.ROOT)
        if ("הזיכרון האחרון" in q || "latest memory" in q || "last memory" in q) {
            val latest = memories.maxByOrNull { it.time }!!
            val facts = MemoryFacts.answer(latest, question)
            return ClaudeClient.Answer(facts ?: L.t("מהפרטים שנשמרו איני יודעת לענות על השאלה הזאת. זה הזיכרון האחרון שנשמר במכשיר, ואפשר לפתוח אותו כדי לראות את הסיפור והפרטים שלו.", "I can't answer that from the saved details. This is the latest memory saved on your device. Open it to see its story and details."), listOf(latest.id))
        }
        if (listOf("האחרונים", "אחרונות", "מה שמרנו", "recent").any { it in q }) {
            return localList(L.t("אלה הזיכרונות האחרונים שנשמרו במכשיר", "Here are the latest memories saved on your device"), memories)
        }

        var candidates = memories
        var filtered = false
        if (listOf("אהובים", "מועדפים", "אהובות", "favorite").any { it in q }) {
            candidates = candidates.filter { it.favorite }
            filtered = true
        }
        val dayStart = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val addedDateRequested = "נוספו" in q || "הוספ" in q || "נוסף" in q
        when {
            "אתמול" in q || "yesterday" in q -> {
                val end = dayStart.timeInMillis
                dayStart.add(Calendar.DAY_OF_YEAR, -1)
                candidates = candidates.filter { it.time in dayStart.timeInMillis until end && (addedDateRequested || it.timeSource != "imported") }
                filtered = true
            }
            "היום" in q || "today" in q -> {
                val start = dayStart.timeInMillis
                dayStart.add(Calendar.DAY_OF_YEAR, 1)
                candidates = candidates.filter { it.time in start until dayStart.timeInMillis && (addedDateRequested || it.timeSource != "imported") }
                filtered = true
            }
            "השבוע" in q || "this week" in q -> {
                // Israeli calendar week begins on Sunday.
                dayStart.add(Calendar.DAY_OF_YEAR, -(dayStart.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY))
                val start = dayStart.timeInMillis
                dayStart.add(Calendar.DAY_OF_YEAR, 7)
                candidates = candidates.filter { it.time in start until dayStart.timeInMillis && (addedDateRequested || it.timeSource != "imported") }
                filtered = true
            }
        }
        val names = memories.flatMap { it.allPeople.split(",").map(String::trim).filter(String::isNotEmpty) }.distinct()
        val requestedNames = names.filter { hasName(q, it.lowercase(Locale.ROOT)) }
        if (requestedNames.isNotEmpty()) {
            candidates = candidates.filter { m -> m.allPeople.split(",").map(String::trim).any { it in requestedNames } }
            filtered = true
        }
        val places = memories.map { it.place.trim() }.filter { it.isNotEmpty() }.distinct()
        val requestedPlaces = places.filter { it.lowercase(Locale.ROOT) in q }
        if (requestedPlaces.isNotEmpty()) {
            candidates = candidates.filter { it.place.trim() in requestedPlaces }
            filtered = true
        }
        if (!filtered) {
            val ignored = setOf("תראי", "תראה", "לי", "את", "זיכרונות", "רגעים", "של", "עם", "מה", "קרה", "היה", "מי", "איפה", "מתי", "אני", "מצא", "למצוא", "האם", "אפשר", "show", "memories", "me", "my", "the", "with", "from", "what", "when", "where", "who", "find", "moments")
            val tokens = q.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 1 && it !in ignored }
            candidates = candidates.filter { m ->
                val record = listOf(m.title, m.description, m.tags, m.category, m.milestone).joinToString(" ").lowercase(Locale.ROOT)
                tokens.isNotEmpty() && tokens.any { it in record }
            }
            filtered = tokens.isNotEmpty()
        }
        if (!filtered) return ClaudeClient.Answer(
            L.t("החיפוש המקומי יודע למצוא זיכרונות לפי הפרטים שנשמרו, ולא להסיק מה קרה בתוך סרטון. אפשר לבקש ״הזיכרונות האחרונים״, שם שמופיע בזיכרון או מקום שנשמר. לשאלה על זיכרון מסוים, כדאי לפתוח אותו. להבנת תוכן נוספת אפשר לבחור ״אפשרויות״.", "On-device search finds memories by their saved details. It can't tell what happens inside a video. Try \"Recent memories\", a name in a memory, or a saved place. For questions about a specific memory, open it. For deeper understanding, tap \"Options\"."),
            emptyList(),
        )
        if (candidates.isEmpty()) return ClaudeClient.Answer(
            L.t("לא נמצאו זיכרונות מתאימים בפרטים שנשמרו במכשיר. זה לא אומר שהרגע לא קרה; ייתכן שהוא לא תועד או שהפרטים חסרים.", "No matching memories in the details saved on your device. That doesn't mean it didn't happen. It may not have been captured, or some details are missing."),
            emptyList(),
        )
        val specificFact = if (candidates.size == 1) MemoryFacts.answer(candidates.first(), question) else null
        return if (specificFact != null) ClaudeClient.Answer(specificFact, listOf(candidates.first().id))
        else localList(L.t("מצאתי זיכרונות לפי הפרטים שנשמרו. החיפוש הזה אינו מנתח את תוכן הסרטונים", "Found memories by their saved details. This search doesn't analyze video content"), candidates)
    }

    private fun hasName(question: String, name: String): Boolean =
        Regex("(?<![\\p{L}\\p{N}])${Regex.escape(name)}(?![\\p{L}\\p{N}])").containsMatchIn(question)

    private fun localList(intro: String, memories: List<Memory>): ClaudeClient.Answer {
        val selected = memories.sortedByDescending { it.time }.take(6)
        val lines = selected.joinToString("\n") { m ->
            val date = if (m.time > 0) (if (m.timeSource == "imported") L.t("נוסף ב־", "Added ") else "") + SimpleDateFormat("d.M.yyyy", L.locale).format(Date(m.time)) else L.t("ללא תאריך", "No date")
            "$date · ${m.displayTitle.ifBlank { L.t("רגע ששמרנו", "A saved moment") }}" + if (m.place.isNotBlank()) " · ${m.place}" else ""
        }
        val suffix = if (memories.size > selected.size) L.t("\nמוצגים ${selected.size} מתוך ${memories.size} זיכרונות מתאימים.", "\nShowing ${selected.size} of ${memories.size} matching memories.") else ""
        return ClaudeClient.Answer("$intro:\n$lines$suffix", selected.map { it.id })
    }

    // ---------- chat views ----------

    private fun addUser(text: String): View {
        val tv = bubbleText(text, R.drawable.chat_user_bg, ContextCompat.getColor(this, R.color.white))
        val row = LinearLayout(this).apply {
            gravity = Gravity.START // RTL: user on the right
            addView(tv)
        }
        thread.addView(row)
        scrollDown()
        return row
    }

    private fun addAssistant(text: String, memories: List<Memory>): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(LinearLayout(this).apply {
            gravity = Gravity.END
            addView(bubbleText(text, R.drawable.chat_ai_bg, ContextCompat.getColor(this@ChatActivity, R.color.ink)))
        })
        if (memories.isNotEmpty()) {
            val strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (m in memories.take(12)) strip.addView(memoryBubble(m))
            box.addView(HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                addView(strip)
            })
        }
        thread.addView(box)
        scrollDown()
        return box
    }

    private fun bubbleText(text: String, bg: Int, color: Int): TextView {
        val pad = dp(14)
        return TextView(this).apply {
            this.text = text
            setTextColor(color)
            textSize = 16f
            setLineSpacing(dp(3).toFloat(), 1f)
            setBackgroundResource(bg)
            setPadding(pad, dp(10), pad, dp(10))
            maxWidth = (resources.displayMetrics.widthPixels * 0.78).toInt()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(0, dp(6), 0, dp(6)) }
        }
    }

    private fun memoryBubble(m: Memory): View {
        val v = LayoutInflater.from(this).inflate(R.layout.item_bubble, null, false)
        v.layoutParams = LinearLayout.LayoutParams(dp(120), dp(160))
        v.findViewById<View>(R.id.bubbleScrim).visibility = View.VISIBLE
        val img = v.findViewById<ImageView>(R.id.bubbleImage)
        val bmp = if (m.thumb.isNotEmpty()) BitmapFactory.decodeFile(m.thumb) else null
        if (bmp != null) img.setImageBitmap(bmp) else img.setImageResource(R.drawable.bubble_placeholder)
        v.findViewById<TextView>(R.id.bubbleTitle).text = if (m.time > 0) (if (m.timeSource == "imported") L.t("נוסף ב־", "Added ") else "") + SimpleDateFormat("d.M HH:mm", L.locale).format(Date(m.time)) else L.t("זיכרון שמור", "Saved memory")
        v.findViewById<TextView>(R.id.bubbleSub).text = m.displayTitle.ifBlank { L.t("רגע ששמרנו", "A saved moment") }
        v.setOnClickListener { MomentActivity.open(this, m.id) }
        return v
    }

    private fun askForKey() {
        val field = EditText(this).apply {
            hint = "sk-ant-..."
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(ClaudeClient.apiKey(this@ChatActivity))
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        AlertDialog.Builder(this)
            .setTitle(L.t("הבנת זיכרונות בעזרת AI", "AI memory understanding"))
            .setMessage(L.t("הגדרה מתקדמת ואופציונלית: מפתח Claude אישי. לאחר ההפעלה, תמונות נבחרות מהזיכרונות נשלחות ל-Anthropic ליצירת תיאורים. בשאלות נשלחים גם הפרטים שנשמרו, שמות ותוכן השיחה. השימוש עשוי להיות בתשלום בחשבון שלך. אפשר להמשיך בחיפוש מקומי בלי מפתח.", "Advanced and optional: your own Claude API key. Once on, selected photos from your memories are sent to Anthropic to create descriptions. Questions also send saved details, names and the conversation. Usage may be billed to your account. You can keep using on-device search without a key."))
            .setView(field)
            .setPositiveButton(L.t("שמור", "Save")) { _, _ ->
                val candidate = field.text.toString().trim()
                if (candidate.isNotEmpty() && !candidate.startsWith("sk-")) {
                    Toast.makeText(this, L.t("לא ניתן להפעיל את השירות עם המפתח הזה. בדוק שהעתקת אותו במלואו.", "This key can't be used. Make sure you copied all of it."), Toast.LENGTH_LONG).show()
                } else {
                    ClaudeClient.setApiKey(this, candidate)
                    analyzeInBackground()
                }
            }
            .setNeutralButton(L.t("איפה משיגים?", "Where do I get one?")) { _, _ ->
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://console.anthropic.com/settings/keys")))
                } catch (_: Exception) {
                }
            }
            .setNegativeButton(L.t("אחר כך", "Later"), null)
            .show()
    }

    private fun scrollDown() = scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
