package com.sudokuoverlay

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity() {
    private val BG = 0xFF0E1014.toInt()
    private val CARD = 0xFF171A21.toInt()
    private val CARD2 = 0xFF222733.toInt()
    private val TEXT = 0xFFF2F4F8.toInt()
    private val SUB = 0xFF8B93A1.toInt()
    private val ACCENT = 0xFF00E676.toInt()
    private val BAD = 0xFFFF6B6B.toInt()

    private var updateText = ""
    private var updateView: TextView? = null
    private var scroll: ScrollView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DebugLog.init(this)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        updateText = "Installed build ${Updater.installedCode(this)}"
        render()
        if (prefs.getBoolean("autoupdate", true)) checkUpdates(force = false)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    // ---- small view helpers ----
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun shape(color: Int, r: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(r).toFloat() }
    private fun label(t: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = sp; setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD
    }
    private fun gap(h: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(h)) }

    private fun card(title: String?, vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = shape(CARD, 20)
        setPadding(dp(18), dp(16), dp(18), dp(16))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) }
        if (title != null) { addView(label(title.uppercase(), 11f, SUB, true)); addView(gap(10)) }
        views.forEach { addView(it) }
    }

    private fun pill(t: String, filled: Boolean, onClick: () -> Unit) = TextView(this).apply {
        text = t; textSize = 14f; gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD
        setTextColor(if (filled) 0xFF00170A.toInt() else TEXT)
        background = shape(if (filled) ACCENT else CARD2, 14)
        setPadding(dp(16), dp(10), dp(16), dp(10))
        setOnClickListener { onClick() }
    }

    /** A line like "● Draw over other apps — Allowed", with a button when it needs attention. */
    private fun statusRow(title: String, sub: String, ok: Boolean, action: String, onAction: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(8), 0, dp(8))
        addView(View(context).apply { background = shape(if (ok) ACCENT else BAD, 6); layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).apply { rightMargin = dp(12) } })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(title, 15f, TEXT, true)); addView(label(sub, 12f, SUB))
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })
        addView(pill(action, !ok, onAction))
    }

    private fun switchRow(title: String, sub: String, key: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(8), 0, dp(8))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(title, 15f, TEXT, true)); addView(label(sub, 12f, SUB))
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })
        addView(Switch(context).apply {
            isChecked = prefs.getBoolean(key, true)
            thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(ACCENT, 0xFFB0B6C3.toInt()))
            trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(0x6600E676, 0xFF3A4150.toInt()))
            setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean(key, on).apply() }
        })
    }

    private fun speedSelector() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        background = shape(CARD2, 14)
        setPadding(dp(4), dp(4), dp(4), dp(4))
        val current = prefs.getInt("speed", 1)
        listOf("Fast", "Normal", "Safe").forEachIndexed { i, name ->
            addView(TextView(context).apply {
                text = name; gravity = Gravity.CENTER; textSize = 14f; typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (i == current) 0xFF00170A.toInt() else SUB)
                background = if (i == current) shape(ACCENT, 11) else null
                setPadding(0, dp(10), 0, dp(10))
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                setOnClickListener { prefs.edit().putInt("speed", i).apply(); render() }
            })
        }
    }

    private fun render() {
        val keep = scroll?.scrollY ?: 0
        val overlayOk = Settings.canDrawOverlays(this)
        val fillOk = AutoFillService.instance != null
        val installOk = packageManager.canRequestPackageInstalls()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(24), dp(16), dp(32)) }

        col.addView(label("Sudoku Overlay", 30f, TEXT, true))
        col.addView(label("Reads the board, solves it, and types the answers in.", 14f, SUB))
        col.addView(gap(20))

        col.addView(card("Setup",
            statusRow("Draw over other apps", if (overlayOk) "Allowed" else "Needed to show the floating bar", overlayOk, "Allow") {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            },
            statusRow("Auto-fill", if (fillOk) "On: Fill can tap for you" else "Needed for Fill (Solve works without it)", fillOk, "Turn on") {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            },
            *(if (fillOk) arrayOf() else arrayOf<View>(
                label("Greyed out or \"restricted\"? Open App info, tap the three dots, then Allow restricted settings.", 12f, SUB).apply {
                    setPadding(dp(22), 0, 0, dp(6))
                },
                pill("Open App info", false) {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                }.apply { layoutParams = LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(22); bottomMargin = dp(6) } }
            )),
            statusRow("Game", gameLabel(), gamePackage() != null, "Change") { chooseGame() },
            statusRow("Install updates", if (installOk) "Allowed" else "Needed to update itself", installOk, "Allow") {
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            }))

        col.addView(pill("Start overlay and open the game", true) { start() }.apply {
            textSize = 17f; setPadding(dp(16), dp(18), dp(16), dp(18))
            background = shape(ACCENT, 18)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) }
        })

        col.addView(card("Behaviour",
            switchRow("Fill when a puzzle opens", "Starts by itself a moment after the game shows a board", "autorun"),
            switchRow("Stop if I touch the screen", "Fill cancels when it sees your finger", "stopOnTouch"),
            switchRow("Offer to report failures", "Shows a notification you can tap to send me the log", "reportFailures"),
            gap(6), label("Fill speed", 13f, SUB), gap(6), speedSelector(),
            label("If taps go wrong, pick a slower one.", 12f, SUB).apply { setPadding(0, dp(6), 0, 0) }))

        updateView = label(updateText, 12f, SUB)
        col.addView(card("Updates",
            switchRow("Update automatically", "Checks GitHub when the app or overlay starts", "autoupdate"),
            updateView!!, gap(10),
            pill("Check now", false) { checkUpdates(force = true) }))

        col.addView(card("Using it",
            label("Open your game: the bar appears.\nSolve shows the missing digits in green.\nFill types every answer.\nKeep pencil mode off in the game.", 13f, SUB)))

        col.addView(card("Troubleshooting",
            label("When something fails you get a notification: tap it, then Submit. Or report by hand:", 12f, SUB), gap(10),
            pill("Report a problem", true) {
                startActivity(Intent(Intent.ACTION_VIEW, Reporter.issueUri(this, "Manual report")))
            }, gap(8),
            pill("Copy log text", false) { copyLog() }, gap(8),
            pill("Save screenshots to Photos", false) { saveShots() }, gap(8),
            pill("Share log files", false) { shareLog() }, gap(8),
            pill("Clear log", false) { DebugLog.clear(); android.widget.Toast.makeText(this, "Log cleared", android.widget.Toast.LENGTH_SHORT).show() }))

        col.addView(label("Build ${Updater.installedCode(this)}", 11f, SUB).apply { gravity = Gravity.CENTER; setPadding(0, dp(8), 0, 0) })

        val sv = ScrollView(this).apply { setBackgroundColor(BG); isFillViewport = true; addView(col) }
        scroll = sv
        setContentView(sv)
        sv.post { sv.scrollTo(0, keep) }
    }

    private fun start() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        startActivityForResult(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent(), 42)
    }

    @Deprecated("simple one-shot result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 42 && resultCode == RESULT_OK && data != null) {
            startForegroundService(Intent(this, OverlayService::class.java)
                .putExtra(OverlayService.EXTRA_CODE, resultCode).putExtra(OverlayService.EXTRA_DATA, data))
            val game = gamePackage()?.let { packageManager.getLaunchIntentForPackage(it) }
            if (game != null) startActivity(game) else moveTaskToBack(true)
        }
    }

    private val prefs get() = getSharedPreferences("settings", MODE_PRIVATE)

    /** The chosen game, else Sudoku.com if it is installed. */
    private fun gamePackage(): String? {
        val saved = prefs.getString("game", null)
        if (saved != null && packageManager.getLaunchIntentForPackage(saved) != null) return saved
        val default = "com.easybrain.sudoku.android"
        return default.takeIf { packageManager.getLaunchIntentForPackage(it) != null }
    }

    private fun gameLabel(): String {
        val pkg = gamePackage() ?: return "None chosen"
        return try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() } catch (e: PackageManager.NameNotFoundException) { pkg }
    }

    private fun checkUpdates(force: Boolean) {
        Thread { Updater.run(this, force) { msg -> runOnUiThread { updateText = "Build ${Updater.installedCode(this)}: $msg"; updateView?.text = updateText } } }.start()
    }

    private fun copyLog() {
        DebugLog.init(this)
        val f = DebugLog.files().firstOrNull { it.name == "log.txt" }
        val text = f?.readText()?.takeLast(12000)
        if (text.isNullOrBlank()) return android.widget.Toast.makeText(this, "Nothing logged yet", android.widget.Toast.LENGTH_SHORT).show()
        (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager)
            .setPrimaryClip(android.content.ClipData.newPlainText("Sudoku Overlay log", text))
        android.widget.Toast.makeText(this, "Copied ${text.length} characters. Paste them into the chat", android.widget.Toast.LENGTH_LONG).show()
    }

    /** Puts the latest screenshots in Photos (Pictures/SudokuOverlay) so they can be attached like any picture. */
    private fun saveShots() {
        DebugLog.init(this)
        val shots = DebugLog.files().filter { it.name.endsWith(".jpg") }
        if (shots.isEmpty()) return android.widget.Toast.makeText(this, "No screenshots yet. Run Solve or Fill first", android.widget.Toast.LENGTH_SHORT).show()
        if (Build.VERSION.SDK_INT < 29) return android.widget.Toast.makeText(this, "Needs Android 10 or newer", android.widget.Toast.LENGTH_SHORT).show()
        val stamp = System.currentTimeMillis() / 1000
        var saved = 0
        for (f in shots) {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "sudoku_${f.nameWithoutExtension}_$stamp.jpg")
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SudokuOverlay")
            }
            val uri = contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: continue
            contentResolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }
            saved++
        }
        android.widget.Toast.makeText(this, "Saved $saved screenshots to Photos > SudokuOverlay", android.widget.Toast.LENGTH_LONG).show()
    }

    private fun shareLog() {
        DebugLog.init(this)
        val files = DebugLog.files()
        if (files.isEmpty()) return android.widget.Toast.makeText(this, "Nothing logged yet", android.widget.Toast.LENGTH_SHORT).show()
        val uris = ArrayList(files.map { androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", it) })
        val send = Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, "Share log"))
    }

    private fun chooseGame() {
        val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packageManager.queryIntentActivities(launch, 0)
            .filter { it.activityInfo.packageName != packageName }
            .map { it.loadLabel(packageManager).toString() to it.activityInfo.packageName }
            .sortedBy { it.first.lowercase() }
        AlertDialog.Builder(this).setTitle("Open which game?")
            .setItems(apps.map { it.first }.toTypedArray()) { _, i ->
                prefs.edit().putString("game", apps[i].second).apply()
                onResume()
            }.show()
    }
}
