package com.sudokuoverlay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.widget.Toast
import androidx.core.content.IntentCompat

/** Hears back from Android's installer: shows the confirmation screen when it wants one, reports failures. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        DebugLog.init(ctx)
        DebugLog.i("install result: status=$status $msg")
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                if (confirm != null) ctx.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> {}
            PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                Toast.makeText(ctx, "Update refused: this copy was signed differently. Uninstall it once and install the release APK from GitHub.", Toast.LENGTH_LONG).show()
            else -> Toast.makeText(ctx, "Update not installed: ${msg ?: "status $status"}", Toast.LENGTH_LONG).show()
        }
    }
}
