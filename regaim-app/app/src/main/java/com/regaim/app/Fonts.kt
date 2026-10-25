package com.regaim.app

import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

/**
 * One typography for the whole app (the design system's type, see [DS]): Heebo Regular for text, SemiBold for
 * buttons, Bold for headings, and a serif for the English wordmark.
 * To use your own font, put `regular.ttf` and `bold.ttf` (e.g. Heebo or Assistant) in app/src/main/assets/fonts/ —
 * they are picked up automatically; without them the phone's own font is used.
 */
object Fonts {
    private var regular: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    private var strong: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    /** The medium-strong weight (Heebo SemiBold), for buttons and emphasis. */
    val strongFace: Typeface get() = strong
    /** Body text (Heebo Regular). */
    val regularFace: Typeface get() = regular
    /** Hebrew headings: Heebo Bold (put `display.ttf` in assets/fonts/ to use another). */
    var display: Typeface = Typeface.create("sans-serif", Typeface.BOLD)
        private set
    /** The English wordmark "Moments": an elegant serif in the spirit of Georgia (Gelasio). */
    var serif: Typeface = Typeface.SERIF
        private set

    /** One weight of a variable font from assets (null if the file isn't there). */
    private fun weight(ctx: Context, path: String, w: Int): Typeface? = try {
        ctx.assets.open(path).close()
        Typeface.Builder(ctx.assets, path).setFontVariationSettings("'wght' $w").build()
    } catch (_: Exception) { null }

    fun init(ctx: Context) {
        // Minimal Luxury: Heebo for all Hebrew text (Regular / SemiBold / Bold headings), Gelasio for the logo.
        weight(ctx, "fonts/heebo.ttf", 400)?.let { regular = it }
        weight(ctx, "fonts/heebo.ttf", 600)?.let { strong = it }
        weight(ctx, "fonts/heebo.ttf", 700)?.let { display = it }
        weight(ctx, "fonts/gelasio.ttf", 400)?.let { serif = it }
        // Optional overrides, as before.
        try { display = Typeface.createFromAsset(ctx.assets, "fonts/display.ttf") } catch (_: Exception) { }
        try { regular = Typeface.createFromAsset(ctx.assets, "fonts/regular.ttf") } catch (_: Exception) { }
        try { strong = Typeface.createFromAsset(ctx.assets, "fonts/bold.ttf") } catch (_: Exception) { }
    }

    /** Applies the app font to every text in [root] (once per view; later bold requests are honoured). */
    fun apply(root: View) {
        if (root is TextView) {
            val current = root.typeface
            if (current === Typeface.SERIF || current === Typeface.MONOSPACE || current === display || current === serif) return
            if (root.getTag(R.id.fontApplied) !== current) {
                val wantsBold = current != null && current.isBold
                val big = root.textSize >= android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 24f, root.resources.displayMetrics)
                val face = if (wantsBold && big) display else if (wantsBold) strong else regular
                root.typeface = face
                root.setTag(R.id.fontApplied, root.typeface)
            }
        }
        if (root is ViewGroup) for (i in 0 until root.childCount) apply(root.getChildAt(i))
    }

    fun install(app: Application) {
        init(app)
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(a: Activity, b: Bundle?) {
                val decor = a.window.decorView
                var pending = false
                decor.viewTreeObserver.addOnGlobalLayoutListener {
                    if (!pending) {
                        pending = true
                        decor.postDelayed({ pending = false; apply(decor) }, 60)
                    }
                }
            }
            override fun onActivityResumed(a: Activity) { apply(a.window.decorView) }
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }
}
