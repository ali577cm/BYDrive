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
import com.sudokuoverlay.core.SudokuSolver
import java.util.concurrent.Executors

class Solution(val grid: Grid, val givens: IntArray, val solved: IntArray, val pad: Array<PointF>?)

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
            if (s.givens[i] != 0) continue
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
        val handle = chip("⠿")
        val solve = chip("Solve").also { solveBtn = it }
        val fill = chip("Fill")
        val close = chip("✕")
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(handle); addView(solve); addView(fill); addView(close)
        }
        val st = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 12f; visibility = View.GONE
            setPadding(dp(14), 0, dp(14), dp(8)); maxWidth = dp(260)
        }.also { status = it }
        val b = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(0xEB202124.toInt()); cornerRadius = dp(22).toFloat() }
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
        solve.setOnClickListener { if (answers != null) hideAnswers() else solve(false) }
        fill.setOnClickListener { solve(true) }
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

    private fun solve(thenFill: Boolean) {
        if (busy) return
        val g = grabber ?: return
        if (thenFill && AutoFillService.instance == null) {
            say("Turn on \"Sudoku Overlay\" in Accessibility settings to use Fill", true)
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        busy = true
        hideAnswers()
        say("Reading the board…", true)
        bar?.visibility = View.INVISIBLE // so the bar isn't in the screenshot
        g.capture(Screen.real(this), 120, 1200) { bmp ->
            worker.execute { process(bmp, thenFill) }
        }
    }

    private fun process(bmp: android.graphics.Bitmap?, thenFill: Boolean) {
        if (bmp == null) return finish("Screen capture returned nothing")
        val res = try { reader?.read(bmp) } catch (t: Throwable) { null } finally { bmp.recycle() }
        val board = res?.board
        val grid = res?.grid
        if (res == null || board == null || grid == null) return finish(res?.error ?: "Couldn't read the screen")
        val solved = SudokuSolver.solve(board)
            ?: return finish("No solution: a digit was probably misread, or a wrong entry is on the board")
        val s = Solution(grid, board, solved, res.pad)
        main.post {
            if (!alive) return@post
            solution = s
            busy = false
            bar?.visibility = View.VISIBLE
            if (thenFill) startFill(s) else {
                showAnswers(s)
                say("Solved: ${board.count { it == 0 }} cells to fill")
            }
        }
    }

    private fun finish(msg: String) {
        main.post { busy = false; bar?.visibility = View.VISIBLE; say(msg) }
    }

    private fun startFill(s: Solution) {
        val svc = AutoFillService.instance ?: return say("Accessibility service is off")
        val pad = s.pad ?: return say("Couldn't find the 1-9 buttons. Use Solve and type the green digits")
        val steps = ArrayList<AutoFillService.Step>()
        for (i in 0 until 81) if (s.givens[i] == 0) {
            steps.add(AutoFillService.Step(PointF(s.grid.centerX(i % 9).toFloat(), s.grid.centerY(i / 9).toFloat()), pad[s.solved[i] - 1]))
        }
        if (steps.isEmpty()) return say("Nothing left to fill")
        busy = true
        bar?.visibility = View.INVISIBLE
        svc.fill(steps) { ok ->
            busy = false
            bar?.visibility = View.VISIBLE
            say(if (ok) "Done: filled ${steps.size} cells" else "Fill stopped")
        }
    }

    override fun onDestroy() {
        alive = false
        AutoFillService.instance?.cancel()
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
