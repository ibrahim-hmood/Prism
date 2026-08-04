package com.prism.launcher.messaging

import android.os.Handler
import android.os.Looper
import android.widget.TextView

/**
 * Animates a TextView through "<name> is thinking", "<name> is thinking.", "<name> is thinking..",
 * "<name> is thinking..." on a fixed interval — the placeholder shown while an AI response is
 * still being generated. Shared by MessagesAdapter (Sam) and NebulaChatAdapter (Nebula bots).
 *
 * Always call [stop] when a bound view is recycled or rebound to non-thinking content, or the
 * animation keeps posting callbacks against a view it no longer owns.
 */
object ThinkingTextAnimator {

    private const val INTERVAL_MS = 450L

    private val handler = Handler(Looper.getMainLooper())
    private val activeRunnables = mutableMapOf<TextView, Runnable>()

    fun start(view: TextView, name: String) {
        stop(view)

        var dots = 0
        val runnable = object : Runnable {
            override fun run() {
                view.text = "$name is thinking${".".repeat(dots)}"
                dots = (dots + 1) % 4
                handler.postDelayed(this, INTERVAL_MS)
            }
        }
        activeRunnables[view] = runnable
        handler.post(runnable)
    }

    fun stop(view: TextView) {
        activeRunnables.remove(view)?.let { handler.removeCallbacks(it) }
    }
}
