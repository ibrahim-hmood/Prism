package com.prism.launcher.nora

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.PathInterpolator
import kotlin.math.max

/**
 * UISegmentedControl, near enough.
 *
 * A rounded track with a sliding selected pill, matching the iOS 13+ appearance: the thumb has
 * a soft shadow, the labels sit on top of it rather than inside it, and the selection animates
 * with a spring-ish ease rather than a linear slide.
 */
class IosSegmentedControl @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var segments: List<String> = emptyList()

    var selectedIndex: Int = 0
        private set

    var onSelected: ((Int) -> Unit)? = null

    /** Animated position of the thumb, in segment units. Fractional while sliding. */
    private var thumbPosition = 0f
    private var animator: ValueAnimator? = null

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val rect = RectF()

    // Apple's standard ease for this control is close to a decelerating cubic.
    private val ease = PathInterpolator(0.25f, 0.1f, 0.25f, 1f)

    init {
        // The thumb's shadow needs a software layer. Set once here -- calling setLayerType from
        // inside onDraw invalidates the view and re-enters the draw pass.
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    fun setSegments(items: List<String>, initial: Int = 0) {
        segments = items
        selectedIndex = initial.coerceIn(0, max(0, items.size - 1))
        thumbPosition = selectedIndex.toFloat()
        invalidate()
    }

    fun select(index: Int, animate: Boolean = true, notify: Boolean = true) {
        if (segments.isEmpty()) return
        val target = index.coerceIn(0, segments.size - 1)
        if (target == selectedIndex && !animate) return
        selectedIndex = target

        animator?.cancel()
        if (animate) {
            animator = ValueAnimator.ofFloat(thumbPosition, target.toFloat()).apply {
                duration = 240
                interpolator = ease
                addUpdateListener {
                    thumbPosition = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        } else {
            thumbPosition = target.toFloat()
            invalidate()
        }
        if (notify) onSelected?.invoke(target)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, IosUi.dp(context, 32f))
    }

    override fun onDraw(canvas: Canvas) {
        if (segments.isEmpty()) return
        val w = width.toFloat()
        val h = height.toFloat()
        val radius = h * 0.5f - IosUi.dp(context, 0.5f)

        trackPaint.color = IosUi.fill(context)
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, h * 0.5f, h * 0.5f, trackPaint)

        val segWidth = w / segments.size
        val inset = IosUi.dp(context, 2f).toFloat()

        // Dividers between unselected segments, hidden near the thumb the way iOS does.
        dividerPaint.color = IosUi.separator(context)
        dividerPaint.strokeWidth = IosUi.dp(context, 1f).toFloat()
        for (i in 1 until segments.size) {
            val distance = kotlin.math.abs(thumbPosition - (i - 0.5f))
            if (distance < 0.65f) continue
            val x = segWidth * i
            canvas.drawLine(x, h * 0.26f, x, h * 0.74f, dividerPaint)
        }

        // Thumb.
        val left = thumbPosition * segWidth + inset
        rect.set(left, inset, left + segWidth - inset * 2, h - inset)
        thumbPaint.color = if (IosUi.isDark(context)) 0xFF636366.toInt() else 0xFFFFFFFF.toInt()
        thumbPaint.setShadowLayer(
            IosUi.dp(context, 3f).toFloat(), 0f, IosUi.dp(context, 1f).toFloat(),
            if (IosUi.isDark(context)) 0x66000000 else 0x30000000
        )
        canvas.drawRoundRect(rect, radius, radius, thumbPaint)

        // Labels.
        textPaint.textSize = IosUi.dp(context, 13.5f).toFloat()
        val baseline = h / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
        for (i in segments.indices) {
            val selectedness = (1f - kotlin.math.abs(thumbPosition - i)).coerceIn(0f, 1f)
            textPaint.isFakeBoldText = selectedness > 0.5f
            textPaint.color = if (IosUi.isDark(context)) {
                IosUi.label(context)
            } else {
                blend(IosUi.secondaryLabel(context), IosUi.label(context), selectedness)
            }
            canvas.drawText(segments[i], segWidth * (i + 0.5f), baseline, textPaint)
        }
    }

    private fun blend(from: Int, to: Int, t: Float): Int {
        fun ch(shift: Int): Int {
            val a = (from shr shift) and 0xFF
            val b = (to shr shift) and 0xFF
            return (a + (b - a) * t).toInt().coerceIn(0, 255)
        }
        return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (segments.isEmpty()) return false
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_UP -> {
                val index = (event.x / (width.toFloat() / segments.size)).toInt()
                    .coerceIn(0, segments.size - 1)
                if (index != selectedIndex) select(index)
                performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }
}
