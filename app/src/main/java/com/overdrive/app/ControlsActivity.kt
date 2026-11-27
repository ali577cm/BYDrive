package com.overdrive.app

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

/** Controls: switch pet mode and holiday mode (pausing the warm-ups) on and off. */
class ControlsActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var petSwitch: Switch
    private lateinit var petStatus: TextView
    private lateinit var holSwitch: Switch
    private lateinit var holStatus: TextView
    private var updating = false

    private val bg = Color.parseColor("#0A1626")
    private val card = Color.parseColor("#12263D")
    private val accent = Color.parseColor("#2BD4A7")
    private val dim = Color.parseColor("#8FA6BD")
    private val warn = Color.parseColor("#FFC857")
    private val bad = Color.parseColor("#FF8A8A")

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun text(t: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        this.text = t
        textSize = sp
        setTextColor(color)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg) }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(4))
        }
        bar.addView(text("←", 24f, Color.WHITE, true).apply {
            setPadding(dp(12), dp(4), dp(12), dp(4))
            setOnClickListener { finish() }
            contentDescription = "Back"
        })
        bar.addView(text("Controls", 20f, Color.WHITE, true))
        root.addView(bar)

        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(24)) }
        petSwitch = Switch(this)
        petStatus = text("", 12f, dim)
        content.addView(row("Pet mode", "Keeps the car at ${PetMode.TEMP_C.toInt()}° with the screen picture up, until you unlock the car, a door opens, or the battery reaches ${PetMode.MIN_SOC}%.", petSwitch, petStatus))
        holSwitch = Switch(this)
        holStatus = text("", 12f, dim)
        content.addView(row("Holiday mode", "Pauses the weekday warm-up rules in Overdrive until you switch it off. Create the rules first from Settings & updates.", holSwitch, holStatus))
        root.addView(ScrollView(this).apply { addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        petSwitch.setOnCheckedChangeListener { _, _ -> if (!updating) { PetMode.toggle(this); handler.postDelayed({ refresh() }, 600) } }
        holSwitch.setOnCheckedChangeListener { _, _ -> if (!updating) { HolidayMode.toggle(this); handler.postDelayed({ refresh() }, 600) } }
    }

    private fun row(title: String, blurb: String, sw: Switch, status: TextView): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply { setColor(card); cornerRadius = dp(20).toFloat() }
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }
        }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(text(title, 17f, Color.WHITE, true), LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(sw)
        c.addView(head)
        c.addView(text(blurb, 12f, dim).apply { setPadding(0, dp(4), 0, dp(6)) })
        c.addView(status)
        return c
    }

    private val tick = object : Runnable {
        override fun run() { refresh(); handler.postDelayed(this, 2000) }
    }

    override fun onResume() { super.onResume(); tick.run() }
    override fun onPause() { handler.removeCallbacks(tick); super.onPause() }

    /** Redraws both switches and status lines from the saved state (pet mode and holiday run elsewhere). */
    private fun refresh() {
        updating = true
        try {
            val pet = PetMode.isActive(this)
            petSwitch.isChecked = pet
            val cabin = PetMode.cabin(this)
            val dash = PetMode.p(this).getString("dash", null)
            val note = PetMode.note(this)
            petStatus.text = if (pet) listOfNotNull(cabin?.let { "cabin $it" }, note.ifEmpty { null }, dash).joinToString(" · ").ifEmpty { "Starting…" }
                else if (note.isNotEmpty()) note else "Off"
            petStatus.setTextColor(if (pet) accent else dim)

            holSwitch.isChecked = HolidayMode.isOn(this)
            holSwitch.isEnabled = !HolidayMode.busy(this)
            val hn = HolidayMode.note(this)
            holStatus.text = when {
                HolidayMode.busy(this) -> "Updating the rules in Overdrive…"
                hn.isNotEmpty() -> hn
                HolidayMode.isOn(this) -> "ON · warm-ups paused"
                else -> "Off · warm-ups running"
            }
            holStatus.setTextColor(when {
                HolidayMode.busy(this) -> dim
                hn.isNotEmpty() -> bad
                HolidayMode.isOn(this) -> warn
                else -> dim
            })
        } finally {
            updating = false
        }
    }
}
