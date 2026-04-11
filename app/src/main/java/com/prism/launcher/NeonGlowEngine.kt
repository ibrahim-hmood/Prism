package com.prism.launcher

import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.RoundRectShape
import android.view.View
import android.widget.TextView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.TextInputLayout

/**
 * Utility to apply high-intensity neon glow effects to UI elements.
 */
object NeonGlowEngine {

    /**
     * Applies a neon color and shadow layer to a TextView.
     */
    fun applyNeonText(textView: TextView, color: Int, intensity: Float = 12f) {
        textView.setTextColor(color)
        textView.setShadowLayer(intensity, 0f, 0f, color)
    }

    /**
     * Clears neon effect from a TextView.
     */
    fun clearNeonText(textView: TextView, defaultColor: Int) {
        textView.setTextColor(defaultColor)
        textView.setShadowLayer(0f, 0f, 0f, 0)
    }

    /**
     * Applies a neon glow border to a MaterialCardView.
     * Uses cardElevation for the shadow and a custom stroke logic if needed,
     * but primarily relies on the card's native stroke + a custom shadow layer.
     */
    fun applyNeonCard(card: MaterialCardView, color: Int) {
        card.strokeColor = color
        // MaterialCardView doesn't support blurred strokes natively well,
        // so we use a high elevation with a colored shadow if on API 28+
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            card.outlineAmbientShadowColor = color
            card.outlineSpotShadowColor = color
            card.cardElevation = 12f
        }
    }

    /**
     * Applies neon glow to a TextInputLayout box.
     */
    fun applyNeonInput(layout: TextInputLayout, color: Int) {
        layout.setBoxStrokeColor(color)
        layout.setStartIconTintList(android.content.res.ColorStateList.valueOf(color))
        //TextInputLayout uses a complex internal background, so we primarily color the stroke.
    }
    
    /**
     * Wraps a View in a simple neon glow by applying a shadow layer to its parent if possible,
     * or by applying a RenderEffect on API 31+.
     */
    fun applyNeonGlow(view: View, color: Int) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            // Future: Implement RenderEffect based outer glow
        }
    }
}
