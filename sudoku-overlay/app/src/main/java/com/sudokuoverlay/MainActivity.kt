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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        col.addView(button("4. Start overlay (opens your game)") { start() })
        setContentView(col)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    override fun onResume() {
        super.onResume()
        val overlay = Settings.canDrawOverlays(this)
        val fill = AutoFillService.instance != null
        info.text = "Overlay permission: ${if (overlay) "OK" else "needed (step 1)"}\n" +
            "Auto-fill: ${if (fill) "ON, the Fill button will work" else "OFF. Do step 2a then 2b, until then Fill can't tap"}\n\n" +
            "Game to open: ${gameLabel()}\n\n" +
            "Then use the floating bar in your Sudoku app, and use the floating bar:\n" +
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
