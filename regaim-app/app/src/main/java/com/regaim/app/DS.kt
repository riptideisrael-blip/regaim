package com.regaim.app

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import androidx.core.content.ContextCompat

/**
 * The Moments design system in code (Minimal Luxury). Every screen takes its colors, type, radii and depth from here
 * (values live in res/values/colors.xml and dimens.xml). No screen defines a color of its own.
 */
object DS {
    // ---------- colors ----------
    fun bg(c: Context) = col(c, R.color.ds_bg)
    fun card(c: Context) = col(c, R.color.ds_card)
    fun cardAlt(c: Context) = col(c, R.color.ds_card_alt)
    fun line(c: Context) = col(c, R.color.ds_line)
    fun primary(c: Context) = col(c, R.color.ds_primary)
    fun button(c: Context) = col(c, R.color.ds_button)
    fun buttonPressed(c: Context) = col(c, R.color.ds_button_pressed)
    fun buttonSecondary(c: Context) = col(c, R.color.ds_button_secondary)
    fun buttonSecondaryPressed(c: Context) = col(c, R.color.ds_button_secondary_pressed)
    fun onButton(c: Context) = col(c, R.color.ds_on_button)
    fun ink(c: Context) = col(c, R.color.ds_ink)
    fun inkSoft(c: Context) = col(c, R.color.ds_ink_soft)
    fun onPhoto(c: Context) = col(c, R.color.ds_on_photo)
    fun selected(c: Context) = col(c, R.color.ds_selected_bg)
    fun error(c: Context) = col(c, R.color.ds_error)
    fun errorBg(c: Context) = col(c, R.color.ds_error_bg)
    fun heart(c: Context) = col(c, R.color.ds_heart)
    fun star(c: Context) = col(c, R.color.ds_star)
    fun scrim(c: Context) = col(c, R.color.ds_scrim)
    fun scrimSoft(c: Context) = col(c, R.color.ds_scrim_soft)
    fun disabledBg(c: Context) = col(c, R.color.ds_disabled_bg)
    fun disabledInk(c: Context) = col(c, R.color.ds_disabled_ink)
    fun ripple(c: Context, onDark: Boolean = false) = col(c, if (onDark) R.color.ds_ripple_on_dark else R.color.ds_ripple)

    private fun col(c: Context, id: Int) = ContextCompat.getColor(c, id)

    /** A token at a lighter opacity (for example white text over a photo at 80%). [a]: 0..1. */
    fun alpha(color: Int, a: Float): Int = (color and 0x00FFFFFF) or ((a.coerceIn(0f, 1f) * 255).toInt() shl 24)

    /** White text / icons over photos, at a softer opacity for secondary lines. */
    fun onPhoto(c: Context, a: Float) = alpha(onPhoto(c), a)

    /** The small handle at the top of a bottom sheet. */
    fun handle(c: Context) = col(c, R.color.ds_handle)

    /** Soft colors for name initials (content palette): (background, letter), the same name always the same pair. */
    fun avatar(c: Context, name: String): Pair<Int, Int> {
        val pairs = listOf(
            R.color.ds_avatar_1_bg to R.color.ds_avatar_1_ink, R.color.ds_avatar_2_bg to R.color.ds_avatar_2_ink,
            R.color.ds_avatar_3_bg to R.color.ds_avatar_3_ink, R.color.ds_avatar_4_bg to R.color.ds_avatar_4_ink,
            R.color.ds_avatar_5_bg to R.color.ds_avatar_5_ink, R.color.ds_avatar_6_bg to R.color.ds_avatar_6_ink,
        )
        val p = pairs[(name.hashCode() and 0x7fffffff) % pairs.size]
        return col(c, p.first) to col(c, p.second)
    }

    // ---------- shape and depth ----------
    fun radiusCard(c: Context) = c.resources.getDimension(R.dimen.ds_radius_card)
    fun radiusButton(c: Context) = c.resources.getDimension(R.dimen.ds_radius_button)
    fun radiusMedia(c: Context) = c.resources.getDimension(R.dimen.ds_radius_media)
    fun radiusChip(c: Context) = c.resources.getDimension(R.dimen.ds_radius_chip)
    fun padCard(c: Context) = c.resources.getDimensionPixelSize(R.dimen.ds_pad_card)
    fun buttonHeight(c: Context) = c.resources.getDimensionPixelSize(R.dimen.ds_button_height)
    /** The thin ring around round moments and avatars (1.5dp: delicate, still visible). */
    fun ringWidth(c: Context) = (1.5f * c.resources.displayMetrics.density).toInt().coerceAtLeast(2)
    fun elevationCard(c: Context) = c.resources.getDimension(R.dimen.ds_elevation_card)

    /** A rounded fill, optionally with a hairline border. */
    fun rounded(color: Int, radius: Float, stroke: Int = 0, strokeColor: Int = 0) = GradientDrawable().apply {
        cornerRadius = radius
        setColor(color)
        if (stroke > 0) setStroke(stroke, strokeColor)
    }

    /**
     * A hand-made button's background: mocha (primary) or cream with a hairline (secondary — it stays visible on a
     * beige card), rounded like every button, with touch feedback.
     */
    fun buttonBackground(c: Context, primary: Boolean) =
        if (primary) pressable(c, rounded(button(c), radiusButton(c)), onDark = true)
        else pressable(c, rounded(bg(c), radiusButton(c), (c.resources.displayMetrics.density).toInt().coerceAtLeast(1), line(c)))

    /** A pill / circle fill. */
    fun oval(color: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }

    /** A card: warm beige, large rounded corners, a whisper of shadow. */
    fun cardBackground(v: View, color: Int = card(v.context)) {
        v.background = rounded(color, radiusCard(v.context))
        v.elevation = elevationCard(v.context)
        v.outlineAmbientShadowColorCompat(col(v.context, R.color.ds_shadow))
    }

    /** Touch feedback in the brand's soft tone, on top of [content]. */
    fun pressable(c: Context, content: GradientDrawable, onDark: Boolean = false) =
        RippleDrawable(ColorStateList.valueOf(ripple(c, onDark)), content, null)

    // ---------- type ----------
    /** Hebrew headings: Heebo Bold. */
    val heading: Typeface get() = Fonts.display
    /** Body text: Heebo Regular. */
    val body: Typeface get() = Fonts.regularFace
    /** Buttons and emphasis: Heebo SemiBold. */
    val emphasis: Typeface get() = Fonts.strongFace
    /** The English wordmark: an elegant serif (Gelasio, metric-compatible with Georgia). */
    val logo: Typeface get() = Fonts.serif

    private fun View.outlineAmbientShadowColorCompat(color: Int) {
        if (android.os.Build.VERSION.SDK_INT >= 28) { outlineAmbientShadowColor = color; outlineSpotShadowColor = color }
    }
}
