package com.regaim.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.Executors

/**
 * "Pitzki walks by the arrows" ([ArrowGame]): the child presses an arrow and Pitzki walks one stone that way —
 * toward the child, away, to a side — with his legs moving ([ArrowArt]'s poses), jumps over a log with the frog
 * button, says "oops!" at a rock. Two ways to play: step by step, or lining up the arrows first and then "go!".
 * Until the admin has drawn the game's pictures, Pitzki's own picture hops instead and the things are emoji.
 */
class ArrowGameActivity : AppCompatActivity() {
    companion object {
        fun open(ctx: Context, name: String, girl: Boolean) =
            ctx.startActivity(Intent(ctx, ArrowGameActivity::class.java).putExtra("name", name).putExtra("girl", girl))
    }

    private val main = Handler(Looper.getMainLooper())
    private val bg = Executors.newSingleThreadExecutor()
    private lateinit var ui: Ui
    private lateinit var voice: PitzkiVoice
    private lateinit var board: Board
    private lateinit var say: TextView
    private lateinit var slots: LinearLayout
    private lateinit var planBox: LinearLayout
    private val levelChips = ArrayList<TextView>()
    private var level = 0
    private var planning = false
    /** The lined-up moves (a direction, or a jump in that direction). */
    private val plan = ArrayList<Pair<ArrowGame.Dir, Boolean>>()
    private var busy = false
    private var destroyed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        voice = PitzkiVoice(this)
        val ink = Color.rgb(255, 248, 231)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(29, 42, 31))
            setPadding(ui.dp(16), ui.dp(10), ui.dp(16), ui.dp(16))
        }
        // Top: close, the name, the levels.
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(TextView(this).apply { text = "✕"; textSize = 22f; setTextColor(ink); setPadding(ui.dp(4), 0, ui.dp(12), 0); contentDescription = L.t("סגירה", "Close"); minWidth = ui.dp(48); minHeight = ui.dp(48); gravity = Gravity.CENTER; setOnClickListener { finish() } })
        top.addView(TextView(this).apply {
            text = L.t("פיצקי הולך לפי החצים", "Pitzki follows the arrows"); textSize = 18f; typeface = DS.heading; setTextColor(ink)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        for (i in ArrowGame.LEVELS.indices) {
            val chip = TextView(this).apply {
                text = (i + 1).toString(); textSize = 15f; gravity = Gravity.CENTER; typeface = DS.emphasis
                contentDescription = L.t("שלב ${i + 1}", "Level ${i + 1}")
                layoutParams = LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)).apply { marginStart = ui.dp(6) }
                setOnClickListener { if (!busy) setLevel(i) }
            }
            levelChips.add(chip); top.addView(chip)
        }
        root.addView(top)
        board = Board(this).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0).apply { topMargin = ui.dp(10) } }
        root.addView(board)
        say = TextView(this).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE; textSize = 17f; gravity = Gravity.CENTER; setTextColor(Color.argb(210, 255, 248, 231)); setPadding(0, ui.dp(10), 0, ui.dp(6)) }
        root.addView(say)
        // The two ways to play.
        val modes = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val now = modeButton(L.t("👆 צעד אחרי צעד", "👆 Step by step"))
        val think = modeButton(L.t("🧠 מתכננים ואז יאללה", "🧠 Plan, then go"))
        now.setOnClickListener { if (!busy) { planning = false; styleModes(now, think); setLevel(level) } }
        think.setOnClickListener { if (!busy) { planning = true; styleModes(now, think); setLevel(level); say(L.t("מסדרים את כל החצים קודם, ואז לוחצים יאללה!", "Line up all the arrows first, then press go!")) } }
        modes.addView(now); modes.addView(think)
        root.addView(modes)
        // The plan: the arrows lined up, go and undo.
        planBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; visibility = View.GONE; setPadding(0, ui.dp(8), 0, 0) }
        slots = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; layoutDirection = View.LAYOUT_DIRECTION_LTR }
        planBox.addView(slots)
        val runRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, ui.dp(8), 0, 0) }
        runRow.addView(bigButton("▶ " + L.t("יאללה!", "Go!"), Color.rgb(95, 143, 62), Color.WHITE) { run() })
        runRow.addView(bigButton("⌫", Color.argb(40, 255, 255, 255), ink) { if (!busy && plan.isNotEmpty()) { plan.removeAt(plan.size - 1); drawPlan() } })
        planBox.addView(runRow)
        root.addView(planBox)
        // Only now that the plan's box exists (it's shown or hidden here): styling the modes before it crashed the game.
        styleModes(now, think)
        // The arrows (a cross, as on a remote) and the frog jump.
        val pad = FrameLayout(this).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(170)).apply { topMargin = ui.dp(10) } }
        fun arrow(label: String, d: ArrowGame.Dir, g: Int, dx: Int, dy: Int) = pad.addView(TextView(this).apply {
            text = label; textSize = 34f; gravity = Gravity.CENTER
            background = DS.rounded(Color.rgb(255, 207, 74), ui.dp(20).toFloat()); elevation = ui.dp(4).toFloat()
            layoutParams = FrameLayout.LayoutParams(ui.dp(74), ui.dp(74), g).apply { leftMargin = dx; topMargin = dy }
            contentDescription = d.word
            setOnClickListener { press(d, false) }
        })
        arrow("⬆️", ArrowGame.Dir.FORWARD, Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, 0)
        arrow("⬇️", ArrowGame.Dir.BACK, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, 0)
        arrow("⬅️", ArrowGame.Dir.LEFT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, -ui.dp(84), 0)
        arrow("➡️", ArrowGame.Dir.RIGHT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, ui.dp(84), 0)
        pad.addView(TextView(this).apply {
            text = "🐸"; textSize = 32f; gravity = Gravity.CENTER
            background = DS.rounded(Color.rgb(123, 211, 137), ui.dp(37).toFloat()); elevation = ui.dp(4).toFloat()
            layoutParams = FrameLayout.LayoutParams(ui.dp(74), ui.dp(74), Gravity.TOP or Gravity.START)
            contentDescription = L.t("קפיצה", "Jump")
            setOnClickListener { press(board.facing, true) }
        })
        pad.layoutDirection = View.LAYOUT_DIRECTION_LTR
        root.addView(pad)
        setContentView(root)
        // The board takes the room that's left (about 5 wide by 4.4 tall).
        board.post { val w = board.width; board.layoutParams = (board.layoutParams as LinearLayout.LayoutParams).apply { height = (w * 4.4f / 5f).toInt() }; board.requestLayout() }
        // The pictures (drawn ones, or Pitzki's own picture until then).
        bg.execute {
            val art = Board.Art.load(applicationContext)
            main.post { if (!destroyed) { board.art = art; board.invalidate() } }
        }
        setLevel(0)
    }

    private fun modeButton(label: String) = TextView(this).apply {
        text = label; textSize = 14f; typeface = DS.emphasis; gravity = Gravity.CENTER
        minHeight = ui.dp(48)
        setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = ui.dp(4); marginEnd = ui.dp(4) }
    }

    private fun styleModes(now: TextView, think: TextView) {
        for ((v, on) in listOf(now to !planning, think to planning)) {
            v.setTextColor(if (on) Color.rgb(255, 207, 74) else Color.rgb(255, 248, 231))
            v.isSelected = on
            androidx.core.view.ViewCompat.setStateDescription(v, if (on) L.t("נבחר", "Selected") else null)
            v.background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = ui.dp(14).toFloat(); setStroke(ui.dp(2), if (on) Color.rgb(255, 207, 74) else Color.argb(60, 255, 255, 255))
            }
        }
        planBox.visibility = if (planning) View.VISIBLE else View.GONE
    }

    private fun bigButton(label: String, fill: Int, color: Int, go: () -> Unit) = TextView(this).apply {
        text = label; textSize = 18f; typeface = DS.heading; gravity = Gravity.CENTER; setTextColor(color)
        background = DS.rounded(fill, ui.dp(16).toFloat())
        setPadding(ui.dp(20), ui.dp(10), ui.dp(20), ui.dp(10))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = ui.dp(6); marginEnd = ui.dp(6) }
        setOnClickListener { go() }
    }

    private fun say(text: String, aloud: Boolean = false) {
        say.text = text
        if (aloud) try { voice.say(text) {} } catch (_: Exception) {}
    }

    private fun setLevel(i: Int) {
        level = i
        plan.clear(); drawPlan()
        levelChips.forEachIndexed { k, c ->
            c.background = DS.rounded(if (k == i) Color.rgb(255, 207, 74) else Color.argb(30, 255, 255, 255), ui.dp(24).toFloat())
            c.isSelected = k == i
            androidx.core.view.ViewCompat.setStateDescription(c, if (k == i) L.t("השלב הנוכחי", "Current level") else null)
            c.setTextColor(if (k == i) Color.rgb(43, 33, 24) else Color.rgb(255, 248, 231))
        }
        board.setLevel(ArrowGame.LEVELS[i])
        say(ArrowGame.LEVELS[i].say, aloud = true)
    }

    private fun press(d: ArrowGame.Dir, jump: Boolean) {
        if (busy) return
        if (planning) { if (plan.size < 10) { plan.add(d to jump); drawPlan() }; return }
        busy = true
        board.move(d, jump, { w -> voice.say(w) {} }) { ok ->
            busy = false
            if (ok && board.won()) won()
        }
    }

    private fun run() {
        if (busy || plan.isEmpty()) return
        busy = true
        fun next(i: Int) {
            if (destroyed) return
            if (i >= plan.size) { busy = false; drawPlan(); say(L.t("עוד לא הגענו. עוד צעדים?", "Not there yet. More steps?"), aloud = true); return }
            drawPlan(i)
            val (d, j) = plan[i]
            board.move(d, j, { w -> voice.say(w) {} }) { ok ->
                if (!ok) { busy = false; drawPlan(i); say(L.t("פיצקי נתקע. נתקן?", "Pitzki is stuck. Fix it?"), aloud = true); return@move }
                if (board.won()) { busy = false; drawPlan(); won(); return@move }
                main.postDelayed({ next(i + 1) }, 120)
            }
        }
        next(0)
    }

    private fun drawPlan(now: Int = -1) {
        slots.removeAllViews()
        for (i in 0 until maxOf(6, plan.size + 1)) {
            val p = plan.getOrNull(i)
            slots.addView(TextView(this).apply {
                text = p?.let { (if (it.second) "🐸" else "") + when (it.first) { ArrowGame.Dir.FORWARD -> "⬆️"; ArrowGame.Dir.BACK -> "⬇️"; ArrowGame.Dir.LEFT -> "⬅️"; else -> "➡️" } } ?: ""
                textSize = if (p?.second == true) 13f else 20f; gravity = Gravity.CENTER
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = ui.dp(10).toFloat()
                    setColor(if (p != null) Color.rgb(255, 248, 231) else Color.argb(20, 255, 255, 255))
                    if (i == now) setStroke(ui.dp(3), Color.rgb(255, 207, 74))
                }
                layoutParams = LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)).apply { marginStart = ui.dp(3); marginEnd = ui.dp(3) }
            })
        }
    }

    private fun won() {
        board.cheer()
        voice.say("הגענו!") {}
        if (level < ArrowGame.LEVELS.size - 1) {
            say(L.t("כל הכבוד! עוד רגע השלב הבא…", "Well done! Next level in a moment…"))
            main.postDelayed({ if (!destroyed) setLevel(level + 1) }, 2600)
        } else say(L.t("כל הכבוד! עברתם את כל השלבים!", "Well done! All the levels!"))
    }

    override fun onDestroy() {
        destroyed = true
        main.removeCallbacksAndMessages(null)
        try { voice.shutdown() } catch (_: Exception) {}
        bg.shutdownNow()
        super.onDestroy()
    }

    /** The clearing, the stones, the things and Pitzki, drawn and moved here. */
    class Board(ctx: Context) : View(ctx) {
        /** The pictures: the drawn ones when they exist, else Pitzki's own picture and emoji. */
        class Art(val ground: Bitmap?, val poses: Map<String, Bitmap>, val things: Map<String, Bitmap>, val plain: Bitmap?) {
            companion object {
                fun load(ctx: Context): Art {
                    val poses = ArrowArt.POSES.mapNotNull { k -> ArrowArt.picture(ctx, k)?.let { k to it } }.toMap()
                    val things = ArrowArt.THING_ORDER.mapNotNull { t -> ArrowArt.picture(ctx, "arrow_$t")?.let { t to it } }.toMap()
                    val ground = PitzkiStore.sharedScene(ctx, ArrowArt.GROUND).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.absolutePath) }
                    val plain = try { ctx.assets.open("pitzki/pitzki_cut.png").use { BitmapFactory.decodeStream(it) } } catch (_: Exception) { null }
                    return Art(ground, poses, things, plain)
                }
            }
        }

        var art: Art? = null
        private var lv: ArrowGame.Level = ArrowGame.LEVELS[0]
        private var px = 0f; private var py = 0f          // Pitzki's place on the board (in stones, may be between)
        private var at = ArrowGame.Cell(0, 0)
        var facing = ArrowGame.Dir.BACK; private set
        private var frame = "arrow_front"
        private var lift = 0f; private var tilt = 0f; private var squash = 1f
        private var bubble = ""
        private val main = Handler(Looper.getMainLooper())
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

        fun setLevel(l: ArrowGame.Level) {
            lv = l; at = l.start; px = at.x.toFloat(); py = at.y.toFloat(); facing = ArrowGame.Dir.BACK; frame = "arrow_front"; bubble = ""
            contentDescription = ArrowGame.describe(lv, at)
            invalidate()
        }

        fun won() = at == lv.goal

        // Board geometry: the stones' rows from a third of the way down, farther rows a little smaller.
        private fun cw() = width / (ArrowGame.COLS + 0.4f)
        private fun rh() = height * 0.62f / ArrowGame.ROWS
        private fun cx(x: Float) = cw() * 0.7f + x * cw()
        private fun cy(y: Float) = height * 0.30f + rh() * (y + 0.5f)
        private fun depth(y: Float) = 0.78f + 0.08f * y

        /** One move with its animation; [say] gets his word, [done] whether he got there. */
        fun move(d: ArrowGame.Dir, jump: Boolean, say: (String) -> Unit, done: (Boolean) -> Unit) {
            facing = d
            val to = if (jump) ArrowGame.jump(lv, at, d) else ArrowGame.step(lv, at, d)
            val word = if (to == null) "אופס!" else if (jump) "קפצה קפיצה!" else d.word
            // Said with niqqud, so the voice reads "kaftza kfitza" (without it, "קפצה" can come out "kaftzah" or "kafatz").
            bubble = word; say(if (jump && to != null) "קַפְצָה קְפִיצָה!" else word)
            val fx = px; val fy = py
            val dur = if (to == null) 600L else if (jump) 760L else 600L
            val start = android.os.SystemClock.uptimeMillis()
            val walk = walkFrames(d)
            val tick = object : Runnable {
                override fun run() {
                    val p = ((android.os.SystemClock.uptimeMillis() - start).toFloat() / dur).coerceIn(0f, 1f)
                    val s = kotlin.math.sin(p * Math.PI).toFloat()
                    if (to == null) {
                        // Half a step, a wobble, back: "oops".
                        px = fx + d.dx * s * 0.22f; py = fy + d.dy * s * 0.22f
                        frame = if (p > 0.35f) "arrow_oops" else walk[0]
                        tilt = (kotlin.math.sin(p * Math.PI * 6) * 7 * (1 - p)).toFloat(); lift = s * 4f
                    } else {
                        val e = if (p < .5f) 2 * p * p else 1 - (-2 * p + 2) * (-2 * p + 2) / 2
                        px = fx + (to.x - fx) * e; py = fy + (to.y - fy) * e
                        if (jump) { frame = if (p in 0.12f..0.88f) "arrow_jump" else walk[0]; lift = s * rh() * 1.1f; tilt = 0f; squash = 1f }
                        else {
                            // The legs: the two steps one after the other, a small bob with each.
                            frame = walk[((p * 4).toInt()) % 2]
                            lift = kotlin.math.abs(kotlin.math.sin(p * Math.PI * 2)).toFloat() * rh() * 0.12f
                            tilt = 0f
                        }
                    }
                    invalidate()
                    if (p < 1f) main.postDelayed(this, 16)
                    else {
                        lift = 0f; tilt = 0f; squash = 1f
                        if (to != null) { at = to; px = to.x.toFloat(); py = to.y.toFloat() } else { px = fx; py = fy }
                        contentDescription = ArrowGame.describe(lv, at)
                        frame = walk[0]
                        main.postDelayed({ bubble = ""; invalidate() }, 700)
                        invalidate()
                        done(to != null)
                    }
                }
            }
            main.post(tick)
        }

        fun cheer() { frame = "arrow_cheer"; bubble = "הגענו! 🎉"; invalidate() }

        /** Away: his back; toward: his front; sides: his profile (flipped for left). */
        private fun walkFrames(d: ArrowGame.Dir) = when (d) {
            ArrowGame.Dir.FORWARD -> listOf("arrow_back", "arrow_back_step")
            ArrowGame.Dir.BACK -> listOf("arrow_front_step", "arrow_front")
            else -> listOf("arrow_side", "arrow_side_step")
        }

        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            if (w <= 0f) return
            val a = art
            // The clearing.
            val ground = a?.ground
            // Pictures and gradients take the paint's see-through level: it's set back to whole first (a shadow's
            // faint paint made Pitzki look faded and the whole clearing darken now and then).
            paint.shader = null; paint.color = Color.WHITE
            if (ground != null) {
                val sc = maxOf(w / ground.width, h / ground.height)
                val m = Matrix().apply { setScale(sc, sc); postTranslate((w - ground.width * sc) / 2f, (h - ground.height * sc) / 2f) }
                c.drawBitmap(ground, m, paint)
            } else {
                paint.shader = LinearGradient(0f, 0f, 0f, h, intArrayOf(Color.rgb(246, 227, 164), Color.rgb(141, 187, 90), Color.rgb(75, 122, 48)), floatArrayOf(0f, .3f, 1f), Shader.TileMode.CLAMP)
                c.drawRect(0f, 0f, w, h, paint); paint.shader = null
            }
            // The stones, far rows first.
            for (y in 0 until ArrowGame.ROWS) for (x in 0 until ArrowGame.COLS) {
                val s = cw() * 0.78f * depth(y.toFloat())
                val r = RectF(cx(x.toFloat()) - s / 2, cy(y.toFloat()) - s * 0.28f, cx(x.toFloat()) + s / 2, cy(y.toFloat()) + s * 0.28f)
                paint.shader = null; paint.color = Color.argb(90, 40, 30, 15)
                c.drawOval(RectF(r.left, r.top + s * 0.06f, r.right, r.bottom + s * 0.06f), paint)
                paint.color = Color.WHITE
                paint.shader = RadialGradient(r.centerX() - s * 0.08f, r.centerY() - s * 0.08f, s * 0.6f,
                    intArrayOf(Color.rgb(230, 214, 173), Color.rgb(201, 180, 138), Color.rgb(156, 133, 88)), floatArrayOf(0f, .6f, 1f), Shader.TileMode.CLAMP)
                c.drawOval(r, paint); paint.shader = null
            }
            // The things and Pitzki, back to front (farther first, so nearer ones stand in front).
            val drawables = ArrayList<Pair<Float, () -> Unit>>()
            for ((cell, t) in lv.things) drawables.add(cell.y.toFloat() to { thing(c, a, t.name.lowercase(), cell, 0.66f) })
            drawables.add(lv.goal.y.toFloat() to { thing(c, a, lv.goalIs.name.lowercase(), lv.goal, 0.62f) })
            drawables.add(py + 0.01f to { pitzki(c, a) })
            drawables.sortedBy { it.first }.forEach { it.second() }
        }

        private fun thing(c: Canvas, a: Art?, name: String, cell: ArrowGame.Cell, size: Float) {
            val y = cell.y.toFloat(); val hgt = cw() * size * depth(y) * 1.15f
            val b = a?.things?.get(name)
            val bottom = cy(y) + rh() * 0.1f
            if (b != null) {
                val wid = hgt * b.width / b.height
                paint.shader = null; paint.color = Color.WHITE
                c.drawBitmap(b, null, RectF(cx(cell.x.toFloat()) - wid / 2, bottom - hgt, cx(cell.x.toFloat()) + wid / 2, bottom), paint)
            } else {
                text.textSize = hgt * 0.8f
                c.drawText(when (name) { "rock" -> "🪨"; "mushroom" -> "🍄"; "log" -> "🪵"; "acorn" -> "🌰"; "hedgehog" -> "🦔"; else -> "🏡" }, cx(cell.x.toFloat()), bottom - hgt * 0.12f, text)
            }
        }

        private fun pitzki(c: Canvas, a: Art?) {
            val hgt = rh() * 2.15f * depth(py)
            val b = a?.poses?.get(frame) ?: a?.plain ?: return
            val wid = hgt * b.width / b.height
            val x = cx(px); val feet = cy(py) + rh() * 0.18f
            // His shadow on the stone (smaller in the air).
            paint.color = Color.argb((90 * (1 - minOf(1f, lift / (rh() * 1.2f)) * 0.6f)).toInt(), 0, 0, 0)
            val sw = cw() * 0.5f * depth(py)
            c.drawOval(RectF(x - sw / 2, feet - sw * 0.12f, x + sw / 2, feet + sw * 0.12f), paint)
            c.save()
            c.translate(x, feet - lift)
            c.rotate(tilt)
            c.scale(if (facing == ArrowGame.Dir.LEFT) -1f else 1f, squash)
            paint.color = Color.WHITE
            c.drawBitmap(b, null, RectF(-wid / 2, -hgt, wid / 2, 0f), paint)
            c.restore()
            if (bubble.isNotEmpty()) {
                text.textSize = rh() * 0.42f
                val tw = text.measureText(bubble) + rh() * 0.5f
                val top = feet - lift - hgt - rh() * 0.65f
                paint.color = Color.rgb(255, 248, 231)
                c.drawRoundRect(RectF(x - tw / 2, top, x + tw / 2, top + rh() * 0.6f), rh() * 0.3f, rh() * 0.3f, paint)
                text.color = Color.rgb(43, 33, 24)
                c.drawText(bubble, x, top + rh() * 0.44f, text)
            }
        }
    }
}
