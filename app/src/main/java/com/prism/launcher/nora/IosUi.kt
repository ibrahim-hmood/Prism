package com.prism.launcher.nora

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/**
 * A small iOS-flavoured design system, built in code.
 *
 * Values are taken from Apple's Human Interface Guidelines semantic colours and standard
 * metrics -- 10pt corner radius on grouped cards, 16pt margins, the systemBlue/systemRed
 * accents, and the two-tier grouped background that gives iOS settings its characteristic
 * card-on-tinted-backdrop look. Both appearances are defined because Prism follows the system
 * theme.
 */
object IosUi {

    fun isDark(ctx: Context): Boolean =
        (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    // ── Semantic colours ────────────────────────────────────────────────────

    fun groupedBackground(ctx: Context) = if (isDark(ctx)) 0xFF000000.toInt() else 0xFFF2F2F7.toInt()
    fun cardBackground(ctx: Context) = if (isDark(ctx)) 0xFF1C1C1E.toInt() else 0xFFFFFFFF.toInt()
    fun label(ctx: Context) = if (isDark(ctx)) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
    fun secondaryLabel(ctx: Context) = if (isDark(ctx)) 0x99EBEBF5.toInt() else 0x993C3C43.toInt()
    fun tertiaryLabel(ctx: Context) = if (isDark(ctx)) 0x4DEBEBF5.toInt() else 0x4D3C3C43.toInt()
    fun separator(ctx: Context) = if (isDark(ctx)) 0x40545458.toInt() else 0x243C3C43.toInt()
    fun accent(ctx: Context) = if (isDark(ctx)) 0xFF0A84FF.toInt() else 0xFF007AFF.toInt()
    fun destructive(ctx: Context) = if (isDark(ctx)) 0xFFFF453A.toInt() else 0xFFFF3B30.toInt()
    fun fill(ctx: Context) = if (isDark(ctx)) 0x3D767680.toInt() else 0x1F767680.toInt()

    fun dp(ctx: Context, value: Float): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value, ctx.resources.displayMetrics
        ).toInt()

    // ── Building blocks ─────────────────────────────────────────────────────

    /** The grouped-inset card that everything sits on in modern iOS settings screens. */
    fun card(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            setColor(cardBackground(ctx))
            cornerRadius = dp(ctx, 12f).toFloat()
        }
        val p = dp(ctx, 16f)
        setPadding(p, dp(ctx, 14f), p, dp(ctx, 14f))
    }

    /** Section header: uppercase, small, secondary — the iOS grouped-table convention. */
    fun sectionHeader(ctx: Context, text: String): TextView = TextView(ctx).apply {
        this.text = text.uppercase()
        textSize = 13f
        letterSpacing = 0.06f
        setTextColor(secondaryLabel(ctx))
        setPadding(dp(ctx, 20f), dp(ctx, 18f), dp(ctx, 20f), dp(ctx, 7f))
    }

    /** Footer text under a card, explaining what the section does. */
    fun sectionFooter(ctx: Context, text: String): TextView = TextView(ctx).apply {
        this.text = text
        textSize = 13f
        setTextColor(secondaryLabel(ctx))
        setPadding(dp(ctx, 20f), dp(ctx, 7f), dp(ctx, 20f), dp(ctx, 4f))
    }

    fun hairline(ctx: Context): View = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(ctx, 0.5f))
        ).apply { topMargin = dp(ctx, 10f); bottomMargin = dp(ctx, 10f) }
        setBackgroundColor(separator(ctx))
    }

    /** Filled capsule button — iOS's prominent action style. */
    fun filledButton(ctx: Context, text: String, tint: Int = accent(ctx)): TextView =
        TextView(ctx).apply {
            this.text = text
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            // Fake bold rather than a bold Typeface: PrismFontEngine reassigns typeface after
            // layout is built, and the single-argument setter would drop a style set here.
            paint.isFakeBoldText = true
            setPadding(0, dp(ctx, 14f), 0, dp(ctx, 14f))
            background = filledBackground(ctx, tint)
            isClickable = true
            isFocusable = true
        }

    /** The filled button's background, exposed so a tint change keeps its ripple. */
    fun filledBackground(ctx: Context, tint: Int): Drawable =
        rippleOf(ctx, GradientDrawable().apply {
            setColor(tint)
            cornerRadius = dp(ctx, 12f).toFloat()
        })

    /** Tinted (low-emphasis) button — iOS's secondary action style. */
    fun tintedButton(ctx: Context, text: String, tint: Int = accent(ctx)): TextView =
        TextView(ctx).apply {
            this.text = text
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(tint)
            setPadding(0, dp(ctx, 13f), 0, dp(ctx, 13f))
            background = rippleOf(ctx, GradientDrawable().apply {
                setColor((tint and 0x00FFFFFF) or 0x1F000000)
                cornerRadius = dp(ctx, 12f).toFloat()
            })
            isClickable = true
            isFocusable = true
        }

    private fun rippleOf(ctx: Context, content: Drawable): Drawable =
        RippleDrawable(
            ColorStateList.valueOf(
                if (isDark(ctx)) 0x22FFFFFF else 0x18000000
            ),
            content, null
        )

    /** Thin rounded progress track, iOS-style rather than Material's square bar. */
    fun progressDrawable(ctx: Context): Drawable {
        val radius = dp(ctx, 3f).toFloat()
        val track = GradientDrawable().apply {
            setColor(fill(ctx))
            cornerRadius = radius
        }
        val bar = GradientDrawable().apply {
            setColor(accent(ctx))
            cornerRadius = radius
        }
        return LayerDrawable(
            arrayOf(track, ClipDrawable(bar, Gravity.START, ClipDrawable.HORIZONTAL))
        ).apply {
            setId(0, android.R.id.background)
            setId(1, android.R.id.progress)
        }
    }

    /** Text field styled as an inset iOS field rather than an underlined Material one. */
    fun fieldBackground(ctx: Context): Drawable = GradientDrawable().apply {
        setColor(fill(ctx))
        cornerRadius = dp(ctx, 9f).toFloat()
    }
}
