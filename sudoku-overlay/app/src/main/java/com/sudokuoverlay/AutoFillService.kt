package com.sudokuoverlay

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.PointF
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent

/**
 * Types the answers by tapping. To be fast, one gesture carries up to 5 (cell, number) tap pairs; the taps are
 * staggered inside the gesture so the app sees cell -> number -> cell -> number ... in order.
 */
class AutoFillService : AccessibilityService() {
    class Step(val cell: PointF, val pad: PointF)

    companion object {
        @Volatile var instance: AutoFillService? = null
        private const val TAP_MS = 40L      // how long each tap lasts
        private const val JITTER_PX = 14f  // each tap lands up to this far from the centre
        private val rnd = java.util.Random()
    }

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var cancelled = false

    override fun onServiceConnected() { instance = this }
    override fun onUnbind(intent: Intent?): Boolean { instance = null; cancelled = true; return super.onUnbind(intent) }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() { cancelled = true }

    fun cancel() { cancelled = true }

    fun fill(steps: List<Step>, onDone: (Boolean) -> Unit) {
        cancelled = false
        next(steps, 0, onDone)
    }

    private fun next(steps: List<Step>, from: Int, onDone: (Boolean) -> Unit) {
        if (cancelled) return onDone(false)
        if (from >= steps.size) return onDone(true)
        val to = minOf(steps.size, from + 2) // 2 pairs per gesture
        val g = GestureDescription.Builder()
        var t = 0L
        for (i in from until to) {
            g.addStroke(tap(steps[i].cell, t)); t += 110 + rnd.nextInt(40)  // let the game select the cell before the number
            g.addStroke(tap(steps[i].pad, t)); t += 90 + rnd.nextInt(40)
        }
        val ok = dispatchGesture(g.build(), object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                main.postDelayed({ next(steps, to, onDone) }, 40L + rnd.nextInt(60))
            }
            override fun onCancelled(gestureDescription: GestureDescription?) = onDone(false)
        }, main)
        if (!ok) onDone(false)
    }

    private fun tap(p: PointF, start: Long) =
        GestureDescription.StrokeDescription(
            Path().apply { moveTo(p.x + (rnd.nextFloat() * 2 - 1) * JITTER_PX, p.y + (rnd.nextFloat() * 2 - 1) * JITTER_PX) },
            start, TAP_MS + rnd.nextInt(10))
}
