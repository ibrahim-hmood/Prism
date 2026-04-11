package com.prism.launcher

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.ProgressBar
import android.widget.TextView
import com.prism.launcher.databinding.LayoutPrismDialogBinding

/**
 * Factory for creating high-fidelity Neon Prism dialogs with blur and glow.
 */
object PrismDialogFactory {

    fun show(
        context: Context,
        title: String,
        message: String,
        positiveText: String? = "OK",
        negativeText: String? = "Cancel",
        showProgress: Boolean = false,
        customView: View? = null,
        onPositive: (() -> Unit)? = null,
        onNegative: (() -> Unit)? = null
    ): Dialog {
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        
        val binding = LayoutPrismDialogBinding.inflate(LayoutInflater.from(context))
        dialog.setContentView(binding.root)

        val glowColor = PrismSettings.getGlowColor(context)

        // Setup Window Style with Gaussian Blur (API 31+)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
            
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                setBackgroundBlurRadius(180)
                attributes.blurBehindRadius = 180
                addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            } else {
                // Fallback: Heavier dim for older versions
                setDimAmount(0.7f)
            }
        }

        // 2. Neon Glow Border
        val radius = 24f * context.resources.displayMetrics.density
        binding.dialogGlowFrame.background = NeonGlowDrawable(glowColor, radius)
        
        // Setup Content
        binding.dialogTitle.text = title
        binding.dialogMessage.text = message
        
        if (positiveText != null) {
            binding.dialogPositiveBtn.text = positiveText
            binding.dialogPositiveBtn.setOnClickListener {
                onPositive?.invoke()
                dialog.dismiss()
            }
        } else {
            binding.dialogPositiveBtn.visibility = View.GONE
        }

        if (negativeText != null) {
            binding.dialogNegativeBtn.text = negativeText
            binding.dialogNegativeBtn.setOnClickListener {
                onNegative?.invoke()
                dialog.dismiss()
            }
        } else {
            binding.dialogNegativeBtn.visibility = View.GONE
        }

        if (showProgress) {
            binding.dialogProgress.visibility = View.VISIBLE
            // Color the progress bar to match the glow
            binding.dialogProgress.progressDrawable?.setTint(glowColor)
        }

        if (customView != null) {
            binding.dialogCustomView.visibility = View.VISIBLE
            binding.dialogCustomView.addView(customView)
        }

        dialog.show()
        return dialog
    }

    /**
     * Update the progress bar inside a dialog created by this factory.
     */
    fun updateProgress(dialog: Dialog, progress: Int, message: String? = null) {
        dialog.findViewById<ProgressBar>(R.id.dialogProgress)?.let {
            it.progress = progress
        }
        if (message != null) {
            dialog.findViewById<TextView>(R.id.dialogMessage)?.let {
                it.text = message
            }
        }
    }
}
