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
        private const val TAP_MS = 12L      // how long each tap lasts
        private const val TAP_GAP = 38L     // time between consecutive taps inside a gesture
        private const val PAIRS_PER_GESTURE = 5
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
        val to = minOf(steps.size, from + PAIRS_PER_GESTURE)
        val g = GestureDescription.Builder()
        var t = 0L
        for (i in from until to) {
            g.addStroke(tap(steps[i].cell, t)); t += TAP_GAP
            g.addStroke(tap(steps[i].pad, t)); t += TAP_GAP
        }
        val ok = dispatchGesture(g.build(), object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) = next(steps, to, onDone)
            override fun onCancelled(gestureDescription: GestureDescription?) = onDone(false)
        }, main)
        if (!ok) onDone(false)
    }

    private fun tap(p: PointF, start: Long) =
        GestureDescription.StrokeDescription(Path().apply { moveTo(p.x, p.y) }, start, TAP_MS)
}
