package com.sudokuoverlay

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Self-updater. New builds are published as GitHub Releases tagged "sudoku-v<N>" (never as the repository's "latest"
 * release, which belongs to the BYDrive app) with the file SudokuOverlay.apk. Android always has the final say: it only
 * accepts an update signed with the same key, and may ask for one tap to confirm.
 */
object Updater {
    private const val API = "https://api.github.com/repos/ali577cm/BYDrive/releases?per_page=30"
    private const val TAG = "sudoku-v"
    private const val ASSET = "SudokuOverlay.apk"
    const val ACTION_RESULT = "com.sudokuoverlay.INSTALL_RESULT"
    private const val SIX_HOURS = 6 * 3600 * 1000L

    @Volatile private var running = false

    private fun trusted(u: String): Boolean {
        val uri = Uri.parse(u)
        val h = uri.host ?: return false
        return uri.scheme == "https" && (h == "github.com" || h.endsWith(".githubusercontent.com"))
    }

    @Suppress("DEPRECATION")
    fun installedCode(ctx: Context): Int {
        val i = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) i.longVersionCode.toInt() else i.versionCode
    }

    private class Release(val code: Int, val url: String)

    /** The highest "sudoku-v<N>" release that has the APK, or null. Throws on network trouble. */
    private fun newest(): Release? {
        val c = URL(API).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 10000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        if (c.responseCode != 200) throw RuntimeException("GitHub answered HTTP ${c.responseCode}")
        val list = JSONArray(c.inputStream.bufferedReader().use { it.readText() })
        var best: Release? = null
        for (i in 0 until list.length()) {
            val r = list.getJSONObject(i)
            if (r.optBoolean("draft") || r.optBoolean("prerelease")) continue
            val tag = r.optString("tag_name")
            if (!tag.startsWith(TAG)) continue
            val code = tag.removePrefix(TAG).toIntOrNull() ?: continue
            val assets = r.getJSONArray("assets")
            for (k in 0 until assets.length()) {
                val a = assets.getJSONObject(k)
                val url = a.getString("browser_download_url")
                if (a.getString("name") == ASSET && trusted(url) && (best == null || code > best.code)) best = Release(code, url)
            }
        }
        return best
    }

    /**
     * Checks for a newer build and installs it. Call from a background thread. [force] ignores the 6-hour pause.
     * [say] reports progress in plain words.
     */
    fun run(ctx: Context, force: Boolean, say: (String) -> Unit) {
        if (running) return
        val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        if (!force && System.currentTimeMillis() - prefs.getLong("lastUpdateCheck", 0) < SIX_HOURS) return
        running = true
        try {
            val mine = installedCode(ctx)
            val rel = newest()
            prefs.edit().putLong("lastUpdateCheck", System.currentTimeMillis()).apply()
            DebugLog.i("update check: installed=$mine newest=${rel?.code}")
            if (rel == null) return say("No update published yet")
            if (rel.code <= mine) return say("Up to date (build $mine)")
            if (!ctx.packageManager.canRequestPackageInstalls()) {
                say("Allow \"Install unknown apps\" for Sudoku Overlay, then check again")
                ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            }
            say("Downloading build ${rel.code}…")
            val file = download(ctx, rel.url)
            say("Installing build ${rel.code}…")
            install(ctx, file)
        } catch (e: Exception) {
            DebugLog.i("update failed: $e")
            say("Update check failed: ${e.message ?: e.javaClass.simpleName}")
        } finally { running = false }
    }

    private fun download(ctx: Context, url: String): File {
        val out = File(ctx.cacheDir, "update.apk")
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10000; c.readTimeout = 30000
        if (c.responseCode != 200) throw RuntimeException("download HTTP ${c.responseCode}")
        if (!trusted(c.url.toString())) throw RuntimeException("redirected to an untrusted host")
        c.inputStream.use { i -> out.outputStream().use { o -> i.copyTo(o) } }
        if (out.length() < 1_000_000) throw RuntimeException("download looks incomplete")
        return out
    }

    private fun install(ctx: Context, apk: File) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        // later updates can go in without a tap when Android allows it for the app that installed us
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            apk.inputStream().use { i -> session.openWrite("update", 0, apk.length()).use { o -> i.copyTo(o); session.fsync(o) } }
            val pending = PendingIntent.getBroadcast(ctx, id, Intent(ctx, InstallReceiver::class.java).setAction(ACTION_RESULT),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            session.commit(pending.intentSender)
        }
    }
}
