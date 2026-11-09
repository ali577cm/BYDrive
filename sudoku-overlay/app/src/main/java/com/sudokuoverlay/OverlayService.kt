package com.sudokuoverlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PointF
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import com.sudokuoverlay.core.Grid
import com.sudokuoverlay.core.GridFinder
import com.sudokuoverlay.core.SudokuSolver
import java.util.concurrent.Executors

/** [givens] are the puzzle's own clues, [current] is what is on the board now (clues plus anything typed in). */
class Solution(val grid: Grid, val givens: IntArray, val current: IntArray, val solved: IntArray, val pad: Array<PointF>?) {
    fun needsDigit(i: Int) = givens[i] == 0 && current[i] != solved[i]
}

/** Draws the missing digits in green exactly on top of the empty cells. Never touchable. */
class AnswerView(ctx: Context) : View(ctx) {
    var solution: Solution? = null
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0, 230, 118); textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textAlign = Paint.Align.CENTER; style = Paint.Style.STROKE; isFakeBoldText = true }

    override fun onDraw(canvas: Canvas) {
        val s = solution ?: return
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        canvas.translate(-loc[0].toFloat(), -loc[1].toFloat())
        val size = (s.grid.cell * 0.62).toFloat()
        fill.textSize = size; edge.textSize = size; edge.strokeWidth = size * 0.08f
        val off = -(fill.fontMetrics.ascent + fill.fontMetrics.descent) / 2
        for (i in 0 until 81) {
            if (!s.needsDigit(i)) continue
            val x = s.grid.centerX(i % 9).toFloat()
            val y = s.grid.centerY(i / 9).toFloat() + off
            val t = s.solved[i].toString()
            canvas.drawText(t, x, y, edge)
            canvas.drawText(t, x, y, fill)
        }
    }
}

class OverlayService : Service() {
    companion object {
        const val ACTION_STOP = "stop"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        private const val CHANNEL = "overlay"
    }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var camThread: HandlerThread? = null
    private lateinit var wm: WindowManager
    private var projection: MediaProjection? = null
    private var grabber: ScreenGrabber? = null
    private var reader: BoardReader? = null

    private var bar: LinearLayout? = null
    private var barParams: WindowManager.LayoutParams? = null
    private var status: TextView? = null
    private var solveBtn: TextView? = null
    private var answers: AnswerView? = null
    private var solution: Solution? = null
    private var busy = false
    private var alive = true
    private val clearStatus = Runnable { status?.visibility = View.GONE }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        if (projection != null) return START_NOT_STICKY
        DebugLog.init(this)
        DebugLog.i("=== overlay started: ${android.os.Build.MODEL} sdk ${Build.VERSION.SDK_INT} screen ${Screen.real(this).w}x${Screen.real(this).h} dpi ${Screen.real(this).dpi}")
        startForegroundNow()
        val data = intent?.let { IntentCompat.getParcelableExtra(it, EXTRA_DATA, Intent::class.java) }
        if (data == null) { stopSelf(); return START_NOT_STICKY }
        try {
            wm = getSystemService(WindowManager::class.java)
            val p = getSystemService(MediaProjectionManager::class.java)
                .getMediaProjection(intent.getIntExtra(EXTRA_CODE, 0), data)
            p.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { main.post { stopSelf() } }
            }, main)
            projection = p
            camThread = HandlerThread("grab").also { it.start() }
            grabber = ScreenGrabber(p, Handler(camThread!!.looper), Screen.real(this))
            reader = BoardReader()
            buildBar()
            AutoFillService.foregroundListener = { onForeground(it) }
            if (prefs.getBoolean("autoupdate", true)) worker.execute { Updater.run(this, false) { msg -> main.post { say(msg) } } }
            showBar()
        } catch (e: Exception) {
            android.widget.Toast.makeText(this, "Couldn't start: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startForegroundNow() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Sudoku Overlay", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 0, Intent(this, OverlayService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("Sudoku Overlay is running")
            .setContentText("Use the floating bar over your Sudoku app")
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .build()
        ServiceCompat.startForeground(this, 1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun overlayParams(touchable: Boolean, w: Int, h: Int) = WindowManager.LayoutParams(
        w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            (if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }

    private fun buildBar() {
        fun chip(t: String) = TextView(this).apply {
            text = t; setTextColor(Color.WHITE); textSize = 15f
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        val handle = chip("⠿").apply { setTextColor(0xFF8B93A1.toInt()) }
        val solve = chip("Solve").also { solveBtn = it }
        // Fill is the main action: a green pill
        val fill = chip("Fill").apply {
            setTextColor(0xFF00170A.toInt()); typeface = android.graphics.Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply { setColor(0xFF00E676.toInt()); cornerRadius = dp(18).toFloat() }
        }
        val close = chip("✕").apply { setTextColor(0xFF8B93A1.toInt()) }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            addView(handle); addView(solve)
            addView(fill, LinearLayout.LayoutParams(-2, -2).apply { setMargins(dp(4), 0, dp(4), 0) })
            addView(close)
        }
        val st = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 12f; visibility = View.GONE
            setPadding(dp(14), 0, dp(14), dp(8)); maxWidth = dp(260)
        }.also { status = it }
        val b = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(0xF2171A21.toInt()); cornerRadius = dp(26).toFloat(); setStroke(dp(1), 0x33FFFFFF) }
            addView(row); addView(st)
        }
        val lp = overlayParams(true, WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT)
        lp.x = dp(12); lp.y = dp(120)
        wm.addView(b, lp)
        bar = b; barParams = lp

        var sx = 0f; var sy = 0f; var ox = 0; var oy = 0
        handle.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = lp.x; oy = lp.y }
                MotionEvent.ACTION_MOVE -> { lp.x = ox + (e.rawX - sx).toInt(); lp.y = oy + (e.rawY - sy).toInt(); wm.updateViewLayout(b, lp) }
            }
            true
        }
        solve.setOnClickListener { silentTry = false; if (answers != null) hideAnswers() else solve(false) }
        fill.setOnClickListener {
            // answers on screen = you have checked them: type exactly those, no second reading
            val shown = solution
            silentTry = false
            if (answers != null && shown != null) startFill(shown) else solve(true)
        }
        close.setOnClickListener { stopSelf() }
    }

    private fun say(msg: String, sticky: Boolean = false) {
        val s = status ?: return
        s.text = msg; s.visibility = View.VISIBLE
        main.removeCallbacks(clearStatus)
        if (!sticky) main.postDelayed(clearStatus, 6000)
    }

    private fun hideAnswers() {
        answers?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        answers = null
        solution = null // a hidden answer is not trusted any more; Solve/Fill read the board afresh
        solveBtn?.text = "Solve"
    }

    private fun showAnswers(s: Solution) {
        hideAnswers()
        val v = AnswerView(this).also { it.solution = s }
        wm.addView(v, overlayParams(false, WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT))
        answers = v
        solveBtn?.text = "Hide"
        // keep the bar above the answers
        bar?.let { wm.removeView(it); wm.addView(it, barParams) }
    }

    private var fillPass = 0

    // ---- following the game: the bar is only shown while the game is in front, and can start by itself ----
    private var inGame = false
    private var autoDone = false
    private var autoTries = 0
    private var silentTry = false
    private val prefs get() = getSharedPreferences("settings", MODE_PRIVATE)

    private fun gamePackage(): String? {
        val saved = prefs.getString("game", null)
        if (saved != null && packageManager.getLaunchIntentForPackage(saved) != null) return saved
        return "com.easybrain.sudoku.android".takeIf { packageManager.getLaunchIntentForPackage(it) != null }
    }

    /** Without a known game, or without the Accessibility service to tell us what is in front, the bar stays visible. */
    private fun showBar() {
        val always = gamePackage() == null || AutoFillService.instance == null
        bar?.visibility = if (always || inGame) View.VISIBLE else View.GONE
    }

    private fun onForeground(pkg: String) {
        val now = pkg == gamePackage()
        DebugLog.i("foreground: $pkg (game=$now)")
        if (now == inGame) return
        inGame = now
        if (!busy) showBar()
        if (now) {
            autoDone = false; autoTries = 0
            if (prefs.getBoolean("autorun", true)) scheduleAutoTry(1800)
        } else {
            hideAnswers()
            main.removeCallbacks(autoRunnable)
        }
    }

    private val autoRunnable = Runnable {
        if (!alive || !inGame || busy || autoDone || AutoFillService.instance == null) return@Runnable
        autoTries++
        silentTry = true
        solve(true)
    }

    /** Tries again in a moment, quietly, until a puzzle is on screen (menus have no grid). */
    private fun scheduleAutoTry(delay: Long = 2500) {
        main.removeCallbacks(autoRunnable)
        if (inGame && !autoDone && autoTries < 8 && prefs.getBoolean("autorun", true)) main.postDelayed(autoRunnable, delay)
    }

    private fun solve(thenFill: Boolean, pass: Int = 0) {
        if (busy) return
        fillPass = pass
        val g = grabber ?: return
        if (thenFill && AutoFillService.instance == null) {
            say("Turn on \"Sudoku Overlay\" in Accessibility settings to use Fill", true)
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        DebugLog.i("solve: thenFill=$thenFill silent=$silentTry")
        busy = true
        hideAnswers()
        if (!silentTry) say("Reading the board…", true)
        bar?.visibility = View.INVISIBLE // so the bar isn't in the screenshot
        g.capture(Screen.real(this), 120, 1200) { bmp ->
            worker.execute { process(bmp, thenFill) }
        }
    }

    private fun process(bmp: android.graphics.Bitmap?, thenFill: Boolean) {
        if (bmp == null) return finish("Screen capture returned nothing")
        DebugLog.shot("read.jpg", bmp)
        val res = try { reader?.read(bmp) } catch (t: Throwable) { DebugLog.i("read crashed: $t"); null } finally { bmp.recycle() }
        val board = res?.board
        val grid = res?.grid
        if (res == null || board == null || grid == null) return finish(res?.error ?: "Couldn't read the screen")
        // Solve from the puzzle's own clues only: digits typed earlier (blue) may be wrong, so they are not trusted.
        val kind = res.kind ?: IntArray(81) { if (board[it] == 0) 0 else 1 }
        // Clues are grey digits. Grey digits on a blue background (the selected cell, or every cell holding the selected
        // number) are clues too, unless counting them leaves no single solution; then try treating them as typed.
        val withSelected = IntArray(81) { if (kind[it] == 1 || kind[it] == 3) board[it] else 0 }
        val withoutSelected = IntArray(81) { if (kind[it] == 1) board[it] else 0 }
        val givens = listOf(withSelected, withoutSelected).firstOrNull { SudokuSolver.countSolutions(it) == 1 }
            ?: return finish("Couldn't read the clues reliably (not a single solution). Try Solve again")
        val solved = SudokuSolver.solve(givens) ?: return finish("No solution: a clue was probably misread")
        val s = Solution(grid, givens, board, solved, res.pad)
        DebugLog.i("solved: givens=${DebugLog.rows(givens)}\n  solution=${DebugLog.rows(solved)}\n  to type: ${(0 until 81).count { s.needsDigit(it) }} cells")
        main.post {
            if (!alive) return@post
            solution = s
            busy = false
            showBar()
            if (thenFill) { if (silentTry) { silentTry = false; autoDone = true }; startFill(s) } else {
                showAnswers(s)
                say("Solved: ${(0 until 81).count { s.needsDigit(it) }} cells to fill")
            }
        }
    }

    private fun finish(msg: String) {
        DebugLog.i("finish: $msg")
        main.post {
            busy = false; showBar()
            if (silentTry) { silentTry = false; status?.visibility = View.GONE; scheduleAutoTry() } else say(msg)
        }
    }

    private fun startFill(s: Solution, only: List<Int>? = null, pass: Int = 0) {
        val svc = AutoFillService.instance ?: return say("Accessibility service is off")
        val pad = s.pad ?: return say("Couldn't find the 1-9 buttons. Use Solve and type the green digits")
        val steps = ArrayList<AutoFillService.Step>()
        for (d in 1..9) for (i in (only ?: (0 until 81).filter { s.needsDigit(it) })) if (s.solved[i] == d) { // all 1s, then all 2s, ...
            steps.add(AutoFillService.Step(PointF(s.grid.centerX(i % 9).toFloat(), s.grid.centerY(i / 9).toFloat()), pad[d - 1]))
        }
        if (steps.isEmpty()) return say("Nothing left to fill")
        val total = steps.size
        DebugLog.i("fill pass $pass: ${steps.size} cells, pad=${pad.joinToString(" ") { "${it.x.toInt()},${it.y.toInt()}" }}")
        hideAnswers()
        busy = true
        bar?.visibility = View.INVISIBLE
        touches.set(0); gridLost = false; sigs.clear(); oursFrozen = null; userSig = false; gridMisses = 0
        addWatcher()
        var perTap = -1.0
        var checks = 0
        // Asked after every batch of taps: stop if a real finger touched the screen or the board is gone.
        fun boundary(done: Int): String? {
            val t = touches.get()
            DebugLog.i("boundary: strokes=$done touches=$t perTap=$perTap gridLost=$gridLost")
            // Our own taps produce either one touch event each or none. Learn which on the first batch, rounding, because an
            // event or two can arrive late or go missing; a real finger adds events on top of that.
            if (perTap < 0) {
                perTap = if (t * 2 >= done) 1.0 else 0.0
                oursFrozen = sigs.keys.toSet() // sources of our own taps: anything else later is a real touch
                DebugLog.i("learned own touch sources: $sigs")
            }
            if (prefs.getBoolean("stopOnTouch", true)) {
                if (userSig) return "you touched the screen"
                // fallback for when a real finger looks just like our taps: a few more events than we sent
                if (t > Math.round(perTap * done) + 2) return "you touched the screen"
            }
            if (gridLost) return "I can't see the grid"
            if (++checks % 2 == 0) checkGrid(s.grid)
            return null
        }
        svc.fill(steps, { done -> boundary(done) }) { reason ->
            removeWatcher()
            if (reason == null) verify(s, pass, total) else endFill("Stopped: $reason")
        }
    }

    private fun endFill(msg: String) {
        DebugLog.i("end: $msg")
        busy = false
        showBar()
        solution = null
        say(msg)
    }

    /**
     * Reads the board again and compares every cell with the answers that were approved (never re-solving);
     * cells that are empty or wrong, because a tap was missed or landed elsewhere, are typed again.
     */
    private fun verify(s: Solution, pass: Int, total: Int) {
        val g = grabber ?: return endFill("Done: filled $total cells")
        say("Checking…", true)
        g.capture(Screen.real(this), 350, 1500) { bmp ->
            worker.execute {
                if (bmp != null) DebugLog.shot("after.jpg", bmp)
                val res = try { if (bmp != null) reader?.read(bmp) else null } catch (t: Throwable) { null } finally { bmp?.recycle() }
                val board = res?.board
                main.post {
                    if (!alive) return@post
                    if (board == null) return@post endFill("Done: filled $total cells (couldn't double-check)")
                    val wrong = (0 until 81).filter { s.givens[it] == 0 && board[it] != s.solved[it] }
                    DebugLog.i("verify pass $pass: ${wrong.size} wrong/empty ${wrong.joinToString(" ") { "r${it / 9 + 1}c${it % 9 + 1}:read${board[it]}want${s.solved[it]}" }}")
                    when {
                        wrong.isEmpty() -> endFill("Done: every cell checked ✓")
                        pass >= 3 -> endFill("Done, but ${wrong.size} cell(s) may still be wrong")
                        else -> { busy = false; startFill(s, wrong, pass + 1) }
                    }
                }
            }
        }
    }

    private val touches = java.util.concurrent.atomic.AtomicInteger()
    private val sigs = java.util.concurrent.ConcurrentHashMap<String, Int>()
    @Volatile private var oursFrozen: Set<String>? = null
    @Volatile private var userSig = false
    @Volatile private var gridLost = false
    @Volatile private var gridMisses = 0
    private var watcher: View? = null

    /** A 1-pixel window that is told about every touch that lands anywhere else on the screen. */
    private fun addWatcher() {
        val v = View(this)
        v.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                touches.incrementAndGet()
                // Where the event came from: our injected taps and a real finger usually differ in device/source/tool.
                val sig = "${e.deviceId}/${e.source}/${e.getToolType(0)}"
                sigs.merge(sig, 1, Int::plus)
                val ours = oursFrozen
                if (ours != null && sig !in ours) { userSig = true; DebugLog.i("touch from a different source: $sig") }
            }
            false
        }
        val lp = WindowManager.LayoutParams(1, 1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START }
        wm.addView(v, lp)
        watcher = v
    }

    private fun removeWatcher() {
        watcher?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        watcher = null
    }

    /** Looks at the newest frame in the background; flags [gridLost] if the board is no longer where it was. */
    private fun checkGrid(expected: Grid) {
        grabber?.latest { bmp ->
            if (bmp == null) return@latest
            worker.execute {
                val w = bmp.width; val h = bmp.height
                val px = IntArray(w * h)
                bmp.getPixels(px, 0, w, 0, 0, w, h)
                val lum = IntArray(w * h) { val p = px[it]; (((p shr 16) and 255) * 299 + ((p shr 8) and 255) * 587 + (p and 255) * 114) / 1000 }
                val ok = GridFinder.verify(lum, w, h, expected)
                DebugLog.i("grid check: ${if (ok) "ok" else "MISSING"} (misses in a row: ${if (ok) 0 else gridMisses + 1})")
                if (ok) gridMisses = 0 else {
                    if (++gridMisses == 1) DebugLog.shot("lost.jpg", bmp)
                    if (gridMisses >= 2) gridLost = true // one odd frame (an animation, a popup) is not enough
                }
                bmp.recycle()
            }
        }
    }

    override fun onDestroy() {
        alive = false
        AutoFillService.foregroundListener = null
        main.removeCallbacks(autoRunnable)
        AutoFillService.instance?.cancel()
        removeWatcher()
        hideAnswers()
        bar?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        grabber?.release()
        worker.shutdown()
        reader?.close()
        projection?.stop()
        camThread?.quitSafely()
        super.onDestroy()
    }
}
