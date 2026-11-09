package com.sudokuoverlay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * "Report a problem" without any password or token in the app: opens GitHub's new-issue page for this repository with
 * the failure and the tail of the log already filled in. One tap on Submit and the report is readable on GitHub.
 */
object Reporter {
    private const val REPO = "ali577cm/BYDrive"
    private const val CHANNEL = "reports"
    private const val MAX_URL = 7000 // GitHub refuses very long URLs

    /** The log from the most recent Solve/Fill onwards, shortened to fit in a link. */
    private fun logTail(limit: Int): String {
        val log = runCatching { DebugLog.files().firstOrNull { it.name == "log.txt" }?.readText() }.getOrNull().orEmpty()
        val at = log.lastIndexOf(" solve: thenFill")
        var run = if (at >= 0) log.substring(log.lastIndexOf('\n', at) + 1) else log
        if (run.length > limit) run = run.takeLast(limit).substringAfter('\n')
        return run
    }

    fun issueUri(ctx: Context, reason: String): Uri {
        var limit = 3500
        while (true) {
            val body = "**What happened:** $reason\n\n" +
                "**Build:** ${Updater.installedCode(ctx)} on ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})\n\n" +
                "<details><summary>Log</summary>\n\n```\n${logTail(limit)}\n```\n</details>\n"
            val url = "https://github.com/$REPO/issues/new?title=${Uri.encode("[Sudoku Overlay] $reason".take(120))}&body=${Uri.encode(body)}"
            if (url.length <= MAX_URL || limit < 500) return Uri.parse(url)
            limit -= 500
        }
    }

    /** A quiet notification; tapping it opens the prefilled report. */
    fun notify(ctx: Context, reason: String) {
        DebugLog.i("report offered: $reason")
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Problem reports", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(ctx, 2, Intent(Intent.ACTION_VIEW, issueUri(ctx, reason)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        nm.notify(2, NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("That didn't work. Tap to report it")
            .setContentText(reason)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$reason\nTap to open a report with the log filled in; press Submit."))
            .setContentIntent(open).setAutoCancel(true).build())
    }
}
