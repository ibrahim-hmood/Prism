package com.prism.launcher.social

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.PopupWindow
import android.widget.TextView
import com.prism.launcher.R

/**
 * Custom Popup to show interaction details (likes/shares) in a bubble-like UI.
 */
object NebulaInteractionBubble {

    fun show(anchor: View, title: String, names: List<String>) {
        val context = anchor.context
        val inflater = LayoutInflater.from(context)
        val root = inflater.inflate(android.R.layout.simple_list_item_1, null) // Placeholder, we'll customize
        
        val bubbleLayout = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
            background = com.prism.launcher.NeonGlowDrawable(
                color = Color.parseColor("#00BA7C"),
                cornerRadius = 24f * context.resources.displayMetrics.density,
                strokeWidth = 2f * context.resources.displayMetrics.density
            )
            layoutParams = ViewGroup.LayoutParams(500, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val titleTv = TextView(context).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(0, 0, 0, 8)
            alpha = 0.8f
        }
        bubbleLayout.addView(titleTv)

        val namesLayout = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
        }
        
        if (names.isEmpty()) {
            namesLayout.addView(TextView(context).apply {
                text = "No interaction yet"
                setTextColor(Color.WHITE)
                textSize = 16f
            })
        } else {
            names.take(5).forEach { name ->
                namesLayout.addView(TextView(context).apply {
                    text = "• $name"
                    setTextColor(Color.WHITE)
                    textSize = 16f
                    setPadding(0, 4, 0, 4)
                })
            }
            if (names.size > 5) {
                namesLayout.addView(TextView(context).apply {
                    text = "...and ${names.size - 5} others"
                    setTextColor(Color.WHITE)
                    textSize = 12f
                    alpha = 0.6f
                })
            }
        }
        bubbleLayout.addView(namesLayout)

        val popup = PopupWindow(bubbleLayout, 500, ViewGroup.LayoutParams.WRAP_CONTENT, true)
        popup.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        popup.elevation = 20f

        val location = IntArray(2)
        anchor.getLocationOnScreen(location)
        
        // Offset to appear above the finger
        popup.showAtLocation(anchor, Gravity.NO_GRAVITY, location[0], location[1] - 150)
    }
}
