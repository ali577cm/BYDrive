package com.sudokuoverlay

import android.Manifest
import android.app.Activity
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
        col.addView(button("3. Start overlay") { start() })
        setContentView(col)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    override fun onResume() {
        super.onResume()
        val overlay = Settings.canDrawOverlays(this)
        val fill = AutoFillService.instance != null
        info.text = "Overlay permission: ${if (overlay) "OK" else "needed (step 1)"}\n" +
            "Auto-fill: ${if (fill) "ON, the Fill button will work" else "OFF. Do step 2a then 2b, until then Fill can't tap"}\n\n" +
            "Then open your Sudoku app, and use the floating bar:\n" +
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
            moveTaskToBack(true)
        }
    }
}
