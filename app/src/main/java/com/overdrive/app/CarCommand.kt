package com.overdrive.app

import android.content.Context
import android.webkit.CookieManager
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Commands sent to the car through Overdrive. Unlike [CarStatus] these CHANGE something, so they are only called from
 * pet mode, which you switched on yourself. Uses the login the app's WebView already holds.
 */
object CarCommand {

    class Outcome(val ok: Boolean, val message: String, val path: String = "")   // path: how Overdrive sent it ("cloud" or "sdk" = local)

    /** POSTs [body] as JSON to [path] on the configured Overdrive address and reports whether the car accepted it. */
    private fun post(ctx: Context, path: String, body: JSONObject, readMs: Int = 15000): Outcome {
        // a sleeping car can take a while to wake its head unit and Tailscale, so try a few times before saying "Offline"
        var last = Outcome(false, "Offline")
        for (attempt in 0 until 3) {
            last = postOnce(ctx, path, body, readMs)
            if (!last.message.startsWith("Offline")) break
            if (attempt < 2) try { Thread.sleep(6000) } catch (_: InterruptedException) { return last }
        }
        return last
    }

    private fun postOnce(ctx: Context, path: String, body: JSONObject, readMs: Int): Outcome {
        val base = Config.url(ctx) ?: return Outcome(false, "Set address in app")
        val cookie = CookieManager.getInstance().getCookie(base)
        if (cookie.isNullOrEmpty()) return Outcome(false, "Sign in via app")
        return try {
            val c = URL(base.trimEnd('/') + path).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 8000
            c.readTimeout = readMs
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Cookie", cookie)
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("Accept", "application/json")
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = c.responseCode
            val text = (if (code == 200) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            c.disconnect()
            if (code != 200) return Outcome(false, if (code == 401 || code == 403 || code in 300..399) "Sign in via app" else "HTTP $code")
            val j = try { JSONObject(text) } catch (_: Exception) { return Outcome(false, "Sign in via app") }
            if (j.optBoolean("success", false)) Outcome(true, "Sent", j.optString("path", ""))
            else Outcome(false, j.optString("error", j.optString("reason", "Failed")).ifEmpty { "Failed" }.take(40))
        } catch (e: IOException) {
            Outcome(false, "Offline (${e.javaClass.simpleName.removeSuffix("Exception")})")
        }
    }

    /** Sets Child Presence Detection: value 1 = on, 2 = off (Overdrive's own values). */
    fun setChildPresenceDetection(ctx: Context, value: Int): Outcome =
        post(ctx, "/api/vehicle/setting", JSONObject().put("target", "childPresenceDetection").put("value", value))

    /** Turns the climate ON at [tempC] for [minutes] (Overdrive allows 10, 15, 20, 25 or 30). */
    fun startClimate(ctx: Context, tempC: Double, minutes: Int): Outcome {
        val r = post(ctx, "/api/vehicle/climate",
            JSONObject().put("action", "power_on").put("temp", tempC).put("remoteDurationMinutes", minutes), 70_000)
        return if (r.ok) Outcome(true, "On", r.path) else r
    }

    /** Turns the climate OFF. */
    fun stopClimate(ctx: Context): Outcome {
        val r = post(ctx, "/api/vehicle/climate", JSONObject().put("action", "power_off"))
        return if (r.ok) Outcome(true, "Off", r.path) else r
    }

    /** Shows a message on the car's main screen (Overdrive shows it as a pop-up while parked). Best effort. */
    fun showMessage(ctx: Context, title: String, message: String, timeoutSec: Int): Outcome =
        post(ctx, "/api/vehicle/message", JSONObject().put("kind", "dialog").put("title", title).put("message", message)
            .put("button", "OK").put("severity", "info").put("timeoutSec", timeoutSec))

    /** Sets the driver dashboard (instrument cluster) brightness, 0-100, through Overdrive. */
    fun setClusterBrightness(ctx: Context, percent: Int): Outcome =
        post(ctx, "/api/vehicle/media", JSONObject().put("target", "cluster_brightness").put("value", percent.coerceIn(0, 100)))
}
