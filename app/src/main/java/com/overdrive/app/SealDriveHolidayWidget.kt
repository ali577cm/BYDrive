package com.overdrive.app

import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.widget.RemoteViews
import kotlin.concurrent.thread

/** 1x1 holiday button: tap to pause all the warm-up rules in Overdrive, tap again to turn them back on. */
class SealDriveHolidayWidget : AppWidgetProvider() {

    companion object {
        private const val ACTION_TOGGLE = "com.overdrive.app.HOLIDAY_TOGGLE"
        private const val JOB_ID = 4203
        private fun prefs(c: Context) = c.getSharedPreferences("holiday_widget", Context.MODE_PRIVATE)

        private fun render(ctx: Context): RemoteViews {
            val on = Config.holiday(ctx)
            val busy = prefs(ctx).getBoolean("busy", false)
            val note = prefs(ctx).getString("note", "") ?: ""
            val (text, color) = when {
                busy -> "Updating…" to "#9FB3C8"
                note.isNotEmpty() && (note.startsWith("Couldn't") || note.contains("failed") || note.startsWith("No ") || note.startsWith("Sign") || note.startsWith("Set")) -> note to "#FF8A8A"
                on -> "ON · paused" to "#FFC857"
                else -> "Warm-ups on" to "#2BD4A7"
            }
            val tap = PendingIntent.getBroadcast(ctx, 7,
                Intent(ctx, SealDriveHolidayWidget::class.java).setAction(ACTION_TOGGLE),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            return RemoteViews(ctx.packageName, R.layout.widget_holiday).apply {
                setTextViewText(R.id.holiday_status, text)
                setTextColor(R.id.holiday_status, Color.parseColor(color))
                setInt(R.id.holiday_icon, "setColorFilter", Color.parseColor(if (on) "#FFC857" else "#8FA6BD"))
                setInt(R.id.widget_root, "setBackgroundResource", when (Config.bg(ctx)) {
                    0 -> R.drawable.widget_bg_clear
                    1 -> R.drawable.widget_bg_glass
                    else -> R.drawable.widget_bg
                })
                setOnClickPendingIntent(R.id.widget_root, tap)
            }
        }

        fun redraw(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, SealDriveHolidayWidget::class.java))
            if (ids.isNotEmpty()) mgr.updateAppWidget(ids, render(ctx))
        }

        /** Applies the current holiday setting to the rules in Overdrive, in a job (a receiver is killed after ~10 s). */
        fun apply(ctx: Context) {
            prefs(ctx).edit().putBoolean("busy", true).putString("note", "").apply()
            redraw(ctx)
            val b = JobInfo.Builder(JOB_ID, ComponentName(ctx, HolidayJobService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            if (android.os.Build.VERSION.SDK_INT >= 31) b.setExpedited(true) else b.setOverrideDeadline(0)
            try {
                ctx.getSystemService(JobScheduler::class.java).schedule(b.build())
            } catch (_: Exception) {
                ctx.getSystemService(JobScheduler::class.java).schedule(
                    JobInfo.Builder(JOB_ID, ComponentName(ctx, HolidayJobService::class.java))
                        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setOverrideDeadline(0).build())
            }
        }

        internal fun finish(ctx: Context, message: String) {
            prefs(ctx).edit().putBoolean("busy", false).putString("note", message).apply()
            redraw(ctx)
        }
    }

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        mgr.updateAppWidget(ids, render(ctx))
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        if (intent.action != ACTION_TOGGLE) return
        if (prefs(ctx).getBoolean("busy", false)) return
        Config.setHoliday(ctx, !Config.holiday(ctx))
        apply(ctx)
    }
}

/** Does the work for the holiday button, so Android does not cut it off. */
class HolidayJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        thread {
            try {
                val on = Config.holiday(this)
                val msg = try { OverdriveAutomations.applyHoliday(this, on) } catch (e: Exception) { "Couldn't reach Overdrive" }
                // if it did not work, put the switch back so the button does not claim a state the car is not in
                if (msg.startsWith("Couldn't") || msg.startsWith("Sign") || msg.startsWith("Set") || msg.startsWith("No ") || msg.contains("failed") || msg.startsWith("Overdrive answered") || msg.startsWith("Unexpected"))
                    Config.setHoliday(this, !on)
                SealDriveHolidayWidget.finish(this, if (msg.startsWith("Holiday")) "" else msg)
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters) = false
}
