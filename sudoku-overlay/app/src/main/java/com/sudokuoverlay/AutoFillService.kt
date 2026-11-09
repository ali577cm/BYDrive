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
        /** Called (on the main thread) with the package of the app that just came to the front. */
        @Volatile var foregroundListener: ((String) -> Unit)? = null
        private const val TAP_MS = 15L        // how long each tap lasts
        private const val JITTER_PX = 14f  // each tap lands up to this far from the centre
        private val rnd = java.util.Random()
    }

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var cancelled = false
    // set from the speed choice: ms from a cell tap to its number tap, from a number tap to the next cell, cells per gesture
    private var cellToPad = 80L
    private var padToNext = 40L
    private var pairs = 8

    override fun onServiceConnected() { instance = this; DebugLog.init(this); DebugLog.i("accessibility service connected") }
    override fun onUnbind(intent: Intent?): Boolean { instance = null; cancelled = true; return super.onUnbind(intent) }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        // our own windows, the notification shade and the like say nothing about which app the user is in
        if (pkg == packageName || pkg.startsWith("com.android.systemui")) return
        main.post { foregroundListener?.invoke(pkg) }
    }
    override fun onInterrupt() { cancelled = true }

    fun cancel() { cancelled = true }

    /**
     * Types the steps in batches of PAIRS cell+number taps per gesture. After every batch [boundary] is asked whether
     * to carry on (it returns a reason to stop, or null); [onDone] gets null on success, else the reason.
     */
    fun fill(steps: List<Step>, boundary: (strokesDone: Int) -> String?, onDone: (String?) -> Unit) {
        cancelled = false
        when (getSharedPreferences("settings", MODE_PRIVATE).getInt("speed", 1)) {
            0 -> { cellToPad = 45L; padToNext = 35L; pairs = 8 }   // fast
            2 -> { cellToPad = 130L; padToNext = 70L; pairs = 4 }  // safe
            else -> { cellToPad = 80L; padToNext = 40L; pairs = 8 } // normal
        }
        DebugLog.i("fill start: ${steps.size} cells, cellToPad=$cellToPad padToNext=$padToNext pairs=$pairs")
        batch(steps, 0, 0, boundary, onDone)
    }

    private fun batch(steps: List<Step>, from: Int, strokesDone: Int, boundary: (Int) -> String?, onDone: (String?) -> Unit) {
        if (cancelled) return onDone("stopped")
        if (from >= steps.size) return onDone(null)
        val to = minOf(steps.size, from + pairs)
        val g = GestureDescription.Builder()
        var t = 0L
        for (i in from until to) {
            g.addStroke(tap(steps[i].cell, t)); t += cellToPad + rnd.nextInt(8)
            g.addStroke(tap(steps[i].pad, t)); t += padToNext + rnd.nextInt(8)
        }
        val done = strokesDone + (to - from) * 2
        val sent = android.os.SystemClock.uptimeMillis()
        DebugLog.i("gesture cells ${from + 1}-$to: " + (from until to).joinToString(" ") { "(${steps[it].cell.x.toInt()},${steps[it].cell.y.toInt()})>(${steps[it].pad.x.toInt()},${steps[it].pad.y.toInt()})" })
        val ok = dispatchGesture(g.build(), object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                DebugLog.i("gesture done in ${android.os.SystemClock.uptimeMillis() - sent} ms")
                main.postDelayed({ // let late touch events arrive before judging
                    val reason = boundary(done)
                    if (reason != null) { DebugLog.i("stop: $reason"); cancelled = true; onDone(reason) } else batch(steps, to, done, boundary, onDone)
                }, 60)
            }
            override fun onCancelled(gestureDescription: GestureDescription?) { DebugLog.i("gesture CANCELLED by the phone"); onDone("the phone cancelled the taps") }
        }, main)
        if (!ok) onDone("the phone refused the taps")
    }

    private fun tap(p: PointF, start: Long) =
        GestureDescription.StrokeDescription(
            Path().apply { moveTo(p.x + (rnd.nextFloat() * 2 - 1) * JITTER_PX, p.y + (rnd.nextFloat() * 2 - 1) * JITTER_PX) },
            start, TAP_MS)
}
