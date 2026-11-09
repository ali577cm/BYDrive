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
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var info: TextView
    private lateinit var updateInfo: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DebugLog.init(this)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        info = TextView(this).apply { textSize = 15f }
        fun button(t: String, onClick: () -> Unit) = Button(this).apply { text = t; setOnClickListener { onClick() } }
        col.addView(TextView(this).apply {
            text = "Sudoku Overlay"; textSize = 24f
        })
        col.addView(info)
        col.addView(button("1. Allow drawing over other apps") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
        col.addView(button("2a. If 2b is greyed out: App info, tap the 3 dots, Allow restricted settings") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        })
        col.addView(button("2b. Turn on Auto-fill (Accessibility > Installed apps > Sudoku Overlay)") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        col.addView(button("3. Choose the game to open with the overlay") { chooseGame() })
        col.addView(android.widget.CheckBox(this).apply {
            text = "Fill automatically when the game opens a puzzle"
            isChecked = prefs.getBoolean("autorun", true)
            setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean("autorun", on).apply() }
        })
        col.addView(android.widget.CheckBox(this).apply {
            text = "Stop Fill if I touch the screen"
            isChecked = prefs.getBoolean("stopOnTouch", true)
            setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean("stopOnTouch", on).apply() }
        })
        col.addView(TextView(this).apply { text = "Fill speed (if taps go wrong, pick a slower one):"; setPadding(0, 16, 0, 0) })
        col.addView(android.widget.RadioGroup(this).apply {
            orientation = android.widget.RadioGroup.HORIZONTAL
            listOf("Fast", "Normal", "Safe").forEachIndexed { i, label ->
                addView(android.widget.RadioButton(this@MainActivity).apply { text = label; id = 100 + i })
            }
            check(100 + prefs.getInt("speed", 1))
            setOnCheckedChangeListener { _, id -> prefs.edit().putInt("speed", id - 100).apply() }
        })
        col.addView(button("4. Start overlay (opens your game)") { start() })
        updateInfo = TextView(this).apply { textSize = 13f; setPadding(0, 16, 0, 0) }
        col.addView(android.widget.CheckBox(this).apply {
            text = "Update automatically when a new version is published"
            isChecked = prefs.getBoolean("autoupdate", true)
            setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean("autoupdate", on).apply() }
        })
        col.addView(button("Check for updates now") { checkUpdates(force = true) })
        col.addView(updateInfo)
        col.addView(button("Copy log text (then paste it into the chat)") { copyLog() })
        col.addView(button("Save screenshots to Photos (then attach them in the chat)") { saveShots() })
        col.addView(button("Share log files (other apps)") { shareLog() })
        col.addView(button("Clear log") { DebugLog.clear(); android.widget.Toast.makeText(this, "Log cleared", android.widget.Toast.LENGTH_SHORT).show() })
        setContentView(col)
        updateInfo.text = "Installed build ${Updater.installedCode(this)}"
        if (prefs.getBoolean("autoupdate", true)) checkUpdates(force = false)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    override fun onResume() {
        super.onResume()
        val overlay = Settings.canDrawOverlays(this)
        val fill = AutoFillService.instance != null
        info.text = "Overlay permission: ${if (overlay) "OK" else "needed (step 1)"}\n" +
            "Auto-fill: ${if (fill) "ON, the Fill button will work" else "OFF. Do step 2a then 2b, until then Fill can't tap"}\n\n" +
            "Game to open: ${gameLabel()}\n\n" +
            "Then use the floating bar in your Sudoku app:\n" +
            "• Solve: shows the missing digits in green\n" +
            "• Fill: types every answer for you\n" +
            "Keep pencil mode OFF in the game."
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
        val pkg = gamePackage() ?: return "none chosen (step 3)"
        return try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() } catch (e: PackageManager.NameNotFoundException) { pkg }
    }

    private fun checkUpdates(force: Boolean) {
        Thread { Updater.run(this, force) { msg -> runOnUiThread { updateInfo.text = "Build ${Updater.installedCode(this)}: $msg" } } }.start()
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
