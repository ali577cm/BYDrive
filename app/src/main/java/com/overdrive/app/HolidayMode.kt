package com.overdrive.app

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import kotlin.concurrent.thread

/** Holiday mode: pauses (or resumes) all the BYDrive warm-up rules in Overdrive. Switched from the Controls screen. */
object HolidayMode {
    private const val JOB_ID = 4203
    private fun prefs(c: Context) = c.getSharedPreferences("holiday", Context.MODE_PRIVATE)

    fun isOn(c: Context) = Config.holiday(c)
    fun busy(c: Context) = prefs(c).getBoolean("busy", false)
    /** The last problem, or empty. */
    fun note(c: Context): String = prefs(c).getString("note", "") ?: ""

    /** Flips holiday mode and applies it to the rules in Overdrive, in a job (a UI thread would be cut off). */
    fun toggle(ctx: Context) {
        if (busy(ctx)) return
        Config.setHoliday(ctx, !Config.holiday(ctx))
        prefs(ctx).edit().putBoolean("busy", true).putString("note", "").apply()
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
    }
}

/** Does the work for holiday mode, so Android does not cut it off. */
class HolidayJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        thread {
            try {
                val on = Config.holiday(this)
                val msg = try { OverdriveAutomations.applyHoliday(this, on) } catch (e: Exception) { "Couldn't reach Overdrive" }
                val ok = msg.startsWith("Holiday")
                // if it did not work, put the switch back so the screen does not claim a state the car is not in
                if (!ok) Config.setHoliday(this, !on)
                HolidayMode.finish(this, if (ok) "" else msg)
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters) = false
}
