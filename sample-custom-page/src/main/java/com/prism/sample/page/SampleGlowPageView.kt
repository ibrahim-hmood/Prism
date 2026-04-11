package com.prism.sample.page

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * Custom page loaded dynamically by Prism via [Context.createPackageContext] and reflection.
 * Must expose a [View] constructor taking [Context] (the foreign context is fine for resources).
 */
class SampleGlowPageView(context: Context) : FrameLayout(context) {

    init {
        val pad = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            20f,
            resources.displayMetrics,
        ).toInt()
        setPadding(pad, pad, pad, pad)
        background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(0xFF2AF598.toInt(), 0xFF009EFD.toInt()),
        ).apply {
            cornerRadius = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                24f,
                resources.displayMetrics,
            )
        }
        val title = TextView(context).apply {
            text = "Custom Prism page"
            textSize = 20f
            setTextColor(ContextCompat.getColor(context, android.R.color.white))
            gravity = Gravity.CENTER
        }
        addView(
            title,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
    }
}
