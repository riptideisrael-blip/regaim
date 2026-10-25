package com.regaim.app

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

/** The visual building blocks for memories: big cards, the hero, small covers, avatars, the heart. */
class MemoryViews(private val a: Activity) {
    private val ui = Ui(a)
    fun dp(v: Int) = ui.dp(v)
    private fun color(id: Int) = ContextCompat.getColor(a, id)
    private fun whenSaved(m: Memory): String = (if (m.timeSource == "imported") L.t("נוסף: ", "Added ") else "") + Fmt.dayTime(m.time)

    private fun matchWrap(top: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(top) }

    /** Rounded media frame with a cover image filling it. */
    private fun media(m: Memory, heightDp: Int): Pair<FrameLayout, ImageView> {
        val frame = FrameLayout(a).apply {
            setBackgroundResource(R.drawable.rounded_media)
            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(heightDp))
        }
        val img = ImageView(a).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        frame.addView(img)
        Covers.load(img, m)
        return frame to img
    }

    /** Small "▶ 0:30" pill: this memory is a video. */
    private fun videoPill(m: Memory) = TextView(a).apply {
        text = "▶ ${Fmt.shortDuration(m.durationMs)}"
        textSize = 12f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(color(R.color.white))
        setBackgroundResource(R.drawable.pill_dark)
        setPadding(dp(10), dp(4), dp(10), dp(4))
    }

    /** Heart toggle; updates the memory and pops softly. */
    fun heart(m: Memory, sizeDp: Int = 38, onDark: Boolean = true, onChange: ((Memory) -> Unit)? = null): ImageView {
        var fav = m.favorite
        // At least 48dp to touch; the heart itself keeps its size (the extra is padding).
        val box = maxOf(sizeDp, 48)
        return ImageView(a).apply {
            val p = dp(8 + (box - sizeDp) / 2)
            setPadding(p, p, p, p)
            if (onDark) setBackgroundResource(R.drawable.pill_dark)
            fun paint() {
                setImageResource(if (fav) R.drawable.ic_heart else R.drawable.ic_heart_outline)
                setColorFilter(if (fav) color(R.color.heart) else if (onDark) color(R.color.white) else color(R.color.ink))
                // Says what a tap will do, so the state isn't only the red color.
                contentDescription = if (fav) L.t("מועדף. לחצו להסרה מהמועדפים", "Favorite. Tap to remove from favorites")
                    else L.t("הוספה למועדפים", "Add to favorites")
            }
            paint()
            setOnClickListener {
                val fresh = MemoryStore.get(a, m.id) ?: m
                fav = !fresh.favorite
                val updated = fresh.copy(favorite = fav)
                if (!MemoryStore.saveFromUi(a) { MemoryStore.update(a, updated) }) { fav = fresh.favorite; return@setOnClickListener }
                if (fav) Detections.feedbackFor(a, m.id, 1)
                paint()
                scaleX = 0.6f; scaleY = 0.6f
                animate().scaleX(1f).scaleY(1f).setDuration(320).setInterpolator(OvershootInterpolator(3f)).start()
                onChange?.invoke(updated)
            }
            layoutParams = FrameLayout.LayoutParams(dp(box), dp(box))
        }
    }

    /** Soft pulse for "we're understanding the moment...". */
    fun pulse(v: View) {
        stopPulse(v)
        pulses[v] = ObjectAnimator.ofFloat(v, "alpha", 1f, 0.4f).apply {
            duration = 900
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            start()
        }
    }

    fun stopPulse(v: View) {
        pulses.remove(v)?.cancel()
        v.alpha = 1f
    }

    companion object {
        private val pulses = java.util.WeakHashMap<View, ObjectAnimator>()
    }

    /** The big "היום לפני שנה" card: photo with the story written on it. */
    fun hero(label: String, m: Memory, onOpen: () -> Unit): View {
        val wrap = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; layoutParams = matchWrap(26) }
        wrap.addView(TextView(a).apply {
            text = label
            textSize = 14f
            letterSpacing = 0.08f
            setTextColor(color(R.color.ink_soft))
            setPadding(dp(4), 0, dp(4), dp(10))
        })
        val (frame, heroImg) = media(m, 300)
        if (m.isVideo) Motion.attach(heroImg, m, width = 540)
        frame.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(300))
        frame.addView(View(a).apply {
            setBackgroundResource(R.drawable.cover_scrim)
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        })
        val texts = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(14), dp(22), dp(22))
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
            addView(TextView(a).apply {
                text = Fmt.title(m)
                textSize = 22f
                typeface = Fonts.display
                setTextColor(color(R.color.white))
            })
            addView(TextView(a).apply {
                text = (listOf(Fmt.fullDate(m.time), m.place).filter { it.isNotBlank() } + Fmt.names(m)).joinToString(" · ")
                textSize = 14f
                setTextColor(DS.onPhoto(a, 0.9f))
                setPadding(0, dp(4), 0, 0)
            })
        }
        frame.addView(texts)
        if (m.isVideo) frame.addView(videoPill(m).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
                setMargins(dp(14), dp(14), dp(14), dp(14))
            }
        })
        frame.setOnClickListener { onOpen() }
        wrap.addView(frame)
        return wrap
    }

    /**
     * A memory in the feed, like an Instagram post: who/when on top, the picture edge to edge,
     * a row of icon actions (love, share, send to family), then the title and a short caption.
     * [bleedDp] = the side padding of the page, so the picture can reach the screen edges.
     */
    fun card(m: Memory, bleedDp: Int = 0, onOpen: () -> Unit): View {
        val pad = dp(if (bleedDp > 0) bleedDp else 4)
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = matchWrap(18).apply { if (bleedDp > 0) { marginStart = -dp(bleedDp); marginEnd = -dp(bleedDp) } }
        }
        // Header: who is in it (or the family), when and where.
        val who = Fmt.names(m).firstOrNull()
        val head = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad, 0, pad, dp(10))
            setOnClickListener { onOpen() }
        }
        head.addView(avatar(who ?: Profile.homeTitle(a), 34))
        head.addView(LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(a).apply {
                text = Fmt.names(m).take(3).joinToString(", ").ifEmpty { Profile.homeTitle(a) }
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(color(R.color.ink))
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            addView(TextView(a).apply {
                text = (listOf(whenSaved(m)) + listOf(Fmt.place(m)).filter { it.isNotEmpty() }).joinToString(" · ")
                textSize = 12f
                setTextColor(color(R.color.ink_soft))
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
        })
        box.addView(head)

        // The picture, edge to edge, square.
        val side = a.resources.displayMetrics.widthPixels - (if (bleedDp > 0) 0 else dp(40))
        val frame = FrameLayout(a).apply {
            setBackgroundColor(color(R.color.fill))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, side)
            setOnClickListener { onOpen() }
        }
        val img = ImageView(a).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = Fmt.title(m)
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        frame.addView(img)
        Covers.load(img, m)
        if (m.isVideo) Motion.attach(img, m, width = 540)
        if (m.isVideo) frame.addView(videoPill(m).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
                setMargins(dp(12), dp(12), dp(12), dp(12))
            }
        })
        if (m.milestone.isNotEmpty()) frame.addView(TextView(a).apply {
            text = "★ ${m.milestone}"
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(color(R.color.white))
            setBackgroundResource(R.drawable.pill_dark)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply {
                setMargins(dp(12), dp(12), dp(12), dp(12))
            }
        })
        box.addView(frame)

        // Icon actions, like Instagram: love · share · send to the family.
        val actions = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad - dp(8), dp(4), pad - dp(8), 0)
        }
        fun iconBtn(res: Int, desc: String, go: () -> Unit) = ImageView(a).apply {
            setImageResource(res)
            setColorFilter(color(R.color.ink))
            contentDescription = desc
            setPadding(dp(8), dp(8), dp(8), dp(8))
            layoutParams = LinearLayout.LayoutParams(dp(42), dp(42))
            setBackgroundResource(android.R.drawable.list_selector_background)
            setOnClickListener { go() }
        }
        actions.addView(heart(m, 42, onDark = false).apply {
            setPadding(dp(8), dp(8), dp(8), dp(8))
            layoutParams = LinearLayout.LayoutParams(dp(42), dp(42))
        })
        actions.addView(iconBtn(R.drawable.ic_share_outline, L.t("שיתוף", "Share")) { (a as? androidx.appcompat.app.AppCompatActivity)?.let { SocialShare.show(it, m) } })
        actions.addView(iconBtn(R.drawable.ic_send, L.t("שליחה למשפחה", "Send to family")) {
            if (Circle.joined(a)) ShareActivity.share(a, m.id) else CircleActivity.open(a)
        })
        actions.addView(View(a).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })
        actions.addView(iconBtn(R.drawable.ic_chevron, L.t("פתחו זיכרון", "Open memory")) { onOpen() }.apply { if (L.isEn) scaleX = -1f })
        box.addView(actions)

        // Title and a short caption.
        box.addView(TextView(a).apply {
            text = Fmt.title(m)
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(color(R.color.ink))
            setPadding(pad, dp(2), pad, 0)
            setOnClickListener { onOpen() }
        })
        if (Fmt.processing(a, m)) {
            box.addView(TextView(a).apply {
                text = L.t("אנחנו מבינים את הרגע...", "Understanding this moment...")
                textSize = 13f
                setTextColor(color(R.color.ink_soft))
                setPadding(pad, dp(2), pad, 0)
                pulse(this)
            })
        } else if (m.description.isNotBlank()) {
            box.addView(TextView(a).apply {
                text = m.description
                textSize = 14f
                setTextColor(color(R.color.ink))
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setLineSpacing(dp(2).toFloat(), 1f)
                setPadding(pad, dp(2), pad, 0)
                setOnClickListener { onOpen() }
            })
        }
        return box
    }

    /** A memory in the two-column grid: cover with length and heart, title, when, who and where. */
    fun gridCard(m: Memory, onOpen: () -> Unit): View {
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.card_white)
            elevation = 0f
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            setPadding(dp(6), dp(6), dp(6), dp(10))
            setOnClickListener { onOpen() }
        }
        val frame = FrameLayout(a).apply {
            setBackgroundResource(R.drawable.rounded_media)
            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(140))
            setOnClickListener { onOpen() }
        }
        val img = ImageView(a).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        frame.addView(img)
        Covers.load(img, m)
        if (m.isVideo) Motion.attach(img, m)
        if (m.isVideo) frame.addView(TextView(a).apply {
            text = Fmt.shortDuration(m.durationMs)
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(color(R.color.white))
            setBackgroundResource(R.drawable.pill_dark)
            setPadding(dp(8), dp(3), dp(8), dp(3))
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
                setMargins(dp(8), dp(8), dp(8), dp(8))
            }
        })
        frame.addView(heart(m, 32).apply {
            val p = dp(6)
            setPadding(p, p, p, p)
            layoutParams = FrameLayout.LayoutParams(dp(32), dp(32), Gravity.TOP or Gravity.START).apply { setMargins(dp(6), dp(6), dp(6), dp(6)) }
        })
        if (m.milestone.isNotEmpty()) frame.addView(TextView(a).apply {
            text = "★"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(color(R.color.white))
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Categories.color("אבן דרך")) }
            layoutParams = FrameLayout.LayoutParams(dp(26), dp(26), Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(8), dp(8), dp(8), dp(8)) }
        })
        box.addView(frame)
        val title = TextView(a).apply {
            textSize = 15f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            typeface = DS.heading
            setPadding(dp(2), dp(8), dp(2), 0)
        }
        if (Fmt.processing(a, m)) {
            title.text = L.t("מבינים את הרגע...", "Understanding...")
            title.setTextColor(color(R.color.accent))
            pulse(title)
        } else {
            title.text = Fmt.title(m)
            title.setTextColor(color(R.color.ink))
        }
        box.addView(title)
        val row = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(3), dp(2), 0)
        }
        row.addView(TextView(a).apply {
            text = whenSaved(m)
            textSize = 12f
            setTextColor(color(R.color.ink_soft))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        for (n in Fmt.names(m).take(2)) row.addView(avatar(n, 20).apply {
            (layoutParams as LinearLayout.LayoutParams).marginStart = dp(2)
        })
        box.addView(row)
        val place = Fmt.place(m)
        if (place.isNotEmpty()) box.addView(TextView(a).apply {
            text = place
            textSize = 12f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(color(R.color.ink_soft))
            setPadding(dp(2), dp(1), dp(2), 0)
        })
        return box
    }

    /** Lays cards out two per row. */
    fun grid(views: List<View>, gapDp: Int = 12): LinearLayout {
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        views.chunked(2).forEach { pair ->
            val row = LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = matchWrap(16)
            }
            pair.forEachIndexed { i, v ->
                v.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (i == 0) marginEnd = dp(gapDp / 2) else marginStart = dp(gapDp / 2)
                }
                row.addView(v)
            }
            if (pair.size == 1) row.addView(View(a).apply {
                layoutParams = LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = dp(gapDp / 2) }
            })
            col.addView(row)
        }
        return col
    }

    /** Small portrait cover, for strips. */
    fun mini(m: Memory, wDp: Int = 72, hDp: Int = 96, onOpen: () -> Unit): View {
        val frame = FrameLayout(a).apply {
            setBackgroundResource(R.drawable.rounded_media)
            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(dp(wDp), dp(hDp)).apply { marginEnd = dp(8) }
            setOnClickListener { onOpen() }
        }
        val img = ImageView(a).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        frame.addView(img)
        Covers.load(img, m)
        return frame
    }

    /**
     * A round profile picture: the person's photo, or their initial (or a pet's emoji) on a soft color.
     * [ring] adds a colorful ring around it, like a story that has something new.
     */
    fun avatar(name: String, sizeDp: Int = 56, ring: Boolean = false, byName: Boolean = true, photoPath: String = ""): View {
        // [byName] false: someone from the server (a circle member). Their picture is never looked up by name, so a
        // relative who happens to share a name with someone in the family profile doesn't get that person's photo.
        val configured = if (byName) Profile.members(a).firstOrNull { it.name == name }?.photo.orEmpty() else photoPath
        val photo = if (!byName) configured else configured.ifEmpty { PeopleStore.people(a).firstOrNull { it.name == name }?.photo.orEmpty() }
        val size = dp(sizeDp)
        val inset = if (ring) dp(if (sizeDp >= 56) 4 else 3) else 0
        val bmp = Avatars.photo(photo, size * 2)
        val (bg, fg) = DS.avatar(a, name)
        val frame = FrameLayout(a).apply {
            layoutParams = LinearLayout.LayoutParams(size, size)
            if (ring) background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(DS.button(a), DS.cardAlt(a))).apply { shape = GradientDrawable.OVAL }
            else background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setStroke(dp(1), color(R.color.line)); setColor(color(R.color.ds_photo_clear)) }
        }
        val gap = if (ring) dp(2) else 0
        val innerLp = { FrameLayout.LayoutParams(size - 2 * (inset + gap), size - 2 * (inset + gap), Gravity.CENTER) }
        if (ring) frame.addView(View(a).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color(R.color.bg)) }
            layoutParams = FrameLayout.LayoutParams(size - 2 * inset, size - 2 * inset, Gravity.CENTER)
        })
        val circle = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(bg) }
        if (bmp != null) frame.addView(ImageView(a).apply {
            setImageBitmap(bmp)
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = circle
            clipToOutline = true
            contentDescription = name
            layoutParams = innerLp()
        }) else {
            val pet = if (byName) Avatars.petEmoji(a, name) else null
            frame.addView(TextView(a).apply {
                text = pet ?: Avatars.initial(name)
                textSize = (sizeDp / 2.4f)
                gravity = Gravity.CENTER
                typeface = Fonts.display
                setTextColor(fg)
                background = circle
                contentDescription = name
                layoutParams = innerLp()
            })
        }
        return frame
    }

    /**
     * People to pick from, as their pictures with the name under each (4 in a row), for "whose moments?" and
     * "who's this?". [onPick] gets the index.
     */
    fun peopleGrid(names: List<String>, onPick: (Int) -> Unit): View {
        val grid = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(4)) }
        names.chunked(4).forEachIndexed { r, row ->
            val line = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
            row.forEachIndexed { c, n ->
                val i = r * 4 + c
                line.addView(LinearLayout(a).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(0, dp(8), 0, dp(8))
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    addView(avatar(n, 60))
                    addView(TextView(a).apply {
                        text = n
                        textSize = 13f
                        gravity = Gravity.CENTER
                        maxLines = 2
                        setTextColor(color(R.color.ink))
                        setPadding(dp(2), dp(6), dp(2), 0)
                    })
                    setOnClickListener { onPick(i) }
                })
            }
            repeat(4 - row.size) { line.addView(View(a), LinearLayout.LayoutParams(0, 1, 1f)) }
            grid.addView(line)
        }
        return grid
    }

    /** Avatar with the name under it. */
    fun person(name: String, sub: String = "", onClick: (() -> Unit)? = null): View = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(0, 0, 0, 0)
        layoutParams = LinearLayout.LayoutParams(dp(78), ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) }
        addView(avatar(name))
        addView(TextView(a).apply {
            text = name
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(color(R.color.ink))
            setPadding(0, dp(6), 0, 0)
        })
        if (sub.isNotEmpty()) addView(TextView(a).apply {
            text = sub
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(color(R.color.ink_soft))
        })
        if (onClick != null) setOnClickListener { onClick() }
    }

    /** A soft chip like "😂 צחוק". */
    fun tag(text: String) = TextView(a).apply {
        this.text = text
        textSize = 14f
        setTextColor(color(R.color.ink))
        background = GradientDrawable().apply {
            cornerRadius = DS.radiusChip(a); setColor(DS.bg(a)); setStroke(dp(1), DS.line(a))
        }
        setPadding(dp(14), dp(7), dp(14), dp(7))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) }
    }

    /**
     * A moment in a circle, like a WhatsApp video message: a mocha ring, the picture inside, and for a clip "0:09 🔇".
     * The clip most in view plays silently (RoundVideo); a tap opens the moment, with sound.
     */
    fun roundClip(m: Memory, sizeDp: Int, onClick: () -> Unit): View {
        val size = dp(sizeDp)
        val ring = DS.ringWidth(a)
        val col = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(size + dp(10), ViewGroup.LayoutParams.WRAP_CONTENT)
            contentDescription = Fmt.title(m)
            setOnClickListener { onClick() }
        }
        val host = FrameLayout(a).apply {
            background = DS.oval(DS.button(a))
            layoutParams = LinearLayout.LayoutParams(size, size)
        }
        val img = ImageView(a).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            outlineProvider = RoundVideo.OVAL
            clipToOutline = true
            setBackgroundColor(DS.buttonSecondary(a))
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply { setMargins(ring, ring, ring, ring) }
        }
        host.addView(img)
        Covers.load(img, m)
        RoundVideo.badge(host, m, dp(12))?.let { host.addView(it) }
        RoundVideo.attach(host, m, ring)
        col.addView(host)
        col.addView(TextView(a).apply {
            text = Fmt.day(m.time)
            textSize = 12f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            setTextColor(DS.inkSoft(a))
            setPadding(0, dp(6), 0, 0)
        })
        return col
    }

    fun sectionTitle(text: String, top: Int = 30, icon: Int = 0) = TextView(a).apply {
        this.text = text
        textSize = 24f
        typeface = Fonts.display
        setTextColor(color(R.color.ink))
        layoutParams = matchWrap(top)
        if (icon != 0) {
            val d = androidx.core.content.ContextCompat.getDrawable(a, icon)?.mutate()
            d?.setTint(color(R.color.ink))
            d?.setBounds(0, 0, dp(20), dp(20))
            setCompoundDrawablesRelative(d, null, null, null)
            compoundDrawablePadding = dp(8)
        }
    }

    /** A small line icon (tinted). */
    fun icon(res: Int, sizeDp: Int = 22, colorRes: Int = R.color.ink) = ImageView(a).apply {
        setImageResource(res)
        setColorFilter(color(colorRes))
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    /** Fade + rise, for things that appear (AI text, filtered lists). */
    fun reveal(v: View, delay: Long = 0) {
        v.alpha = 0f
        v.translationY = dp(10).toFloat()
        v.animate().alpha(1f).translationY(0f).setStartDelay(delay).setDuration(260).start()
    }

    /** Stop every local preview when leaving a screen, including ScrollView children kept attached. */
    fun stopPreviews(v: View) {
        if (v is ImageView) Motion.stop(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) stopPreviews(v.getChildAt(i))
    }
}
