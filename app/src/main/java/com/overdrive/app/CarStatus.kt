package com.overdrive.app

import android.content.Context
import android.net.Uri
import android.webkit.CookieManager
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Read-only status fetch from the Overdrive server. Reuses the login cookie the app's WebView already holds,
 * so no password or device token is ever stored. Only GET requests are made, and only to the configured address.
 */
object CarStatus {

    class Snapshot(
        val soc: Double?, val rangeKm: Double?,
        val locked: Int,            // 1 locked, 2 unlocked, other unknown
        val doorsOpen: Int,
        val windowsOpen: Int?,      // null = unknown
        val sentryOn: Boolean?, val sentryRecording: Boolean,
        val charging: Boolean = false,
        val chargeEtaMin: Int? = null,      // minutes to full while charging
        val chargeSessionKwh: Double? = null, // energy added so far this charging session
        val sessionCostPence: Double? = null, // cost of the current charging session so far
        val todayKwh: Double? = null,         // energy used by all of today's trips (since local midnight)
        val lastChargeCostPence: Double? = null, // cost of the most recent completed charge, by low/high-rate minutes
    )

    sealed class Result {
        class Ok(val s: Snapshot) : Result()
        object NoAddress : Result()
        class SignIn(val why: String) : Result()
        class Offline(val why: String) : Result()
        class NoData(val why: String) : Result()
    }

    private class Resp(val code: Int, val json: JSONObject?, val err: String? = null)

    private fun get(base: String, path: String, cookie: String, readMs: Int = 4000): Resp? = try {
        val c = URL(base.trimEnd('/') + path).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = false          // a redirect means "go to login"
        c.connectTimeout = 3000
        c.readTimeout = readMs
        c.requestMethod = "GET"
        c.setRequestProperty("Cookie", cookie)
        c.setRequestProperty("Accept", "application/json")
        val code = c.responseCode
        val body = if (code == 200) c.inputStream.bufferedReader().use { it.readText() } else ""
        c.disconnect()
        Resp(code, if (body.trimStart().startsWith("{")) try { JSONObject(body) } catch (_: Exception) { null } else null)
    } catch (e: IOException) { Resp(-1, null, e.javaClass.simpleName) }

    /** Energy for one trip: measured when available, else Overdrive's estimate (energyPerKm in kWh/km x distance). */
    internal fun tripEnergy(o: JSONObject): Double {
        var e = o.optDouble("energyUsedKwh", 0.0)
        if (e.isNaN() || e <= 0) {
            val perKm = o.optDouble("energyPerKm", 0.0)
            val km = o.optDouble("distanceKm", 0.0)
            e = if (!perKm.isNaN() && !km.isNaN() && perKm > 0 && km > 0) perKm * km else 0.0
        }
        return e
    }

    /** Energy used by every trip that started today (local midnight onwards). */
    private fun todayKwh(base: String, cookie: String): Double? {
        val today = dayStart(System.currentTimeMillis())
        val arr = get(base, "/api/trips?days=2&limit=500&offset=0", cookie, 15000)?.json?.optJSONArray("trips") ?: return null
        var sum = 0.0
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optLong("startTime", 0L) >= today) sum += tripEnergy(o)
        }
        return sum
    }

    /** Cost of the most recent COMPLETED charge: its kWh split across the cheap and standard minutes it actually ran. */
    private fun lastChargeCost(ctx: Context, base: String, cookie: String): Double? {
        val arr = fetchSessions(base, cookie, 90, 10, 0)         // newest first
        val t = Config.tariff(ctx)
        var odStart = 0L
        var odEnd = 0L
        var odCost: Double? = null
        if (arr != null) for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optBoolean("inProgress", false)) continue
            val start = o.optLong("startTime", 0L)
            val end = o.optLong("endTime", 0L)
            val e = o.optDouble("energyAdded", Double.NaN)
            if (start > 0 && end > 0 && !e.isNaN() && e > 0) { odStart = start; odEnd = end; odCost = priceSession(start, end, e, t); break }
        }
        // a charge you added by hand that is newer than Overdrive's latest (and not the same charge) wins
        val t0 = t.atMeter()
        val m = Config.manualSessions(ctx).filter { it.end <= System.currentTimeMillis() && it.kwh > 0 && it.end > it.start }
            .maxByOrNull { it.start }
        if (m != null && (odCost == null || m.start > odStart)) {
            val dur = m.end - m.start
            val same = odCost != null && maxOf(0L, minOf(odEnd, m.end) - maxOf(odStart, m.start)) > dur / 2
            if (!same) return priceSession(m.start, m.end, m.kwh, t0)
        }
        if (arr == null && m == null) return null
        return odCost
    }

    // ---------------- time-of-day pricing of charging sessions ----------------

    /** Milliseconds of [a, b) that fall inside the cheap window (local time). */
    private fun cheapOverlapMs(a: Long, b: Long, t: Config.Tariff): Long {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = dayStart(a)
        cal.add(java.util.Calendar.DAY_OF_MONTH, -1)          // a window that began the previous evening
        var total = 0L
        while (cal.timeInMillis < b) {
            val d0 = cal.timeInMillis
            val ws = d0 + t.startMin * 60_000L
            val we = if (t.endMin > t.startMin) d0 + t.endMin * 60_000L else d0 + 86_400_000L + t.endMin * 60_000L
            total += maxOf(0L, minOf(we, b) - maxOf(ws, a))
            cal.add(java.util.Calendar.DAY_OF_MONTH, 1)
        }
        return total
    }

    /** Cost in pence of [kwh] delivered evenly between [start] and [end], split by the cheap/standard windows. */
    internal fun priceSession(start: Long, end: Long, batteryKwh: Double, tariff: Config.Tariff): Double {
        if (batteryKwh <= 0) return 0.0
        val t = tariff.at(start)
        val kwh = batteryKwh * 100.0 / t.effPercent      // energy drawn from the meter, incl. charger losses
        if (end <= start) return kwh * (if (cheapOverlapMs(start, start + 1, t) > 0) t.cheapPence else t.stdPence)
        val cheapFrac = cheapOverlapMs(start, end, t).toDouble() / (end - start)
        return kwh * (cheapFrac * t.cheapPence + (1 - cheapFrac) * t.stdPence)
    }

    private fun fetchSessions(base: String, cookie: String, days: Int, limit: Int, offset: Int) =
        get(base, "/api/charging?days=$days&limit=$limit&offset=$offset", cookie, 20000)?.json?.optJSONArray("sessions")

    class ChargeCosts(val liveStart: Long?, val liveKwh: Double?)

    /** Start time and energy so far of the charge in progress (both null when not charging). */
    private fun chargeCosts(base: String, cookie: String): ChargeCosts? {
        val recent = fetchSessions(base, cookie, 3, 200, 0) ?: return null
        var liveStart: Long? = null
        var liveKwh: Double? = null
        for (i in 0 until recent.length()) {
            val o = recent.optJSONObject(i) ?: continue
            if (!o.optBoolean("inProgress", false)) continue
            val start = o.optLong("startTime", 0L)
            val kwh = o.optDouble("energyAdded", Double.NaN).takeIf { !it.isNaN() && it > 0 }
            if (liveStart == null || start > liveStart) { liveStart = start; liveKwh = kwh }
        }
        return ChargeCosts(liveStart, liveKwh)
    }

    /** Address + login cookie, or null when not set up / not signed in. */
    internal fun auth(ctx: Context): Pair<String, String>? {
        val base = Config.url(ctx) ?: return null
        val cookie = CookieManager.getInstance().getCookie(base)
        return if (cookie.isNullOrEmpty()) null else base to cookie
    }

    /** GET returning parsed JSON, or null on any failure (network, login, non-JSON). */
    internal fun getJson(base: String, path: String, cookie: String, readMs: Int = 20000): JSONObject? = get(base, path, cookie, readMs)?.json

    private fun dayStart(t: Long): Long {
        val c = java.util.Calendar.getInstance()
        c.timeInMillis = t
        c.set(java.util.Calendar.HOUR_OF_DAY, 0)
        c.set(java.util.Calendar.MINUTE, 0)
        c.set(java.util.Calendar.SECOND, 0)
        c.set(java.util.Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    fun fetch(ctx: Context): Result {
        val base = Config.url(ctx) ?: return Result.NoAddress
        val cookie = CookieManager.getInstance().getCookie(base)
        if (cookie.isNullOrEmpty()) return Result.SignIn("no login in app")

        // sentry + trips in parallel with the main state call
        var sentryResp: Resp? = null
        var statusResp: Resp? = null
        var costs: ChargeCosts? = null
        var todayUsed: Double? = null
        var lastCharge: Double? = null
        val t1 = thread { sentryResp = get(base, "/api/surveillance/status", cookie) }
        val t3 = thread { statusResp = get(base, "/status", cookie, 8000) }
        val t4 = thread { costs = try { chargeCosts(base, cookie) } catch (_: Exception) { null } }
        val t5 = thread { todayUsed = try { todayKwh(base, cookie) } catch (_: Exception) { null } }
        val t6 = thread { lastCharge = try { lastChargeCost(ctx, base, cookie) } catch (_: Exception) { null } }
        val state = get(base, "/api/vehicle/state", cookie)
        t1.join(); t3.join(); t4.join(); t5.join(); t6.join()

        if (state == null || state.code == -1) {
            val tsOff = Tailscale.isTailscaleAddress(base) && !Tailscale.isConnected()
            return Result.Offline(if (tsOff) "Tailscale not connected" else (state?.err ?: "no response"))
        }
        val j = state.json ?: return Result.SignIn(if (state.code == 200) "got a web page" else "HTTP ${state.code}")
        if (!j.optBoolean("success", false)) return Result.NoData(j.optString("error", "car data unavailable").take(40))

        val battery = j.optJSONObject("battery")
        val soc = battery?.optDouble("soc", Double.NaN)?.takeIf { !it.isNaN() }
        val range = battery?.optDouble("rangeKm", Double.NaN)?.takeIf { !it.isNaN() && it > 0 }

        val locked = j.optJSONObject("doors")?.optInt("overall", -1) ?: -1

        var doorsOpen = 0
        j.optJSONObject("doorOpen")?.let { o -> o.keys().forEach { k -> if (k != "fuelCap" && o.optBoolean(k, false)) doorsOpen++ } }

        var winOpen = 0
        var winKnown = false
        val wins = j.optJSONObject("windows")
        val winFlags = j.optJSONObject("windowOpen")
        for (k in listOf("lf", "rf", "lr", "rr")) {
            val pct = wins?.optInt(k, -1) ?: -1
            val flag = winFlags?.let { if (it.has(k)) it.optBoolean(k) else null }
            if (pct >= 0 || flag != null) winKnown = true
            if (pct > 0 || flag == true) winOpen++
        }

        val st = sentryResp?.json?.optJSONObject("status")
        val sentryOn = st?.let { if (it.has("enabled")) it.optBoolean("enabled") else null }
        val sentryRec = st?.optBoolean("recording", false) ?: false

        val ch = statusResp?.json?.optJSONObject("charging")
        val charging = ch?.optBoolean("charging", false) ?: false
        val eta = ch?.optInt("timeToFullMin", -1)?.takeIf { it > 0 }
        val sess = ch?.optDouble("sessionKwh", Double.NaN)?.takeIf { !it.isNaN() && it > 0 }

        val tariff = Config.tariff(ctx)
        val now = System.currentTimeMillis()
        val liveKwh = if (charging) (sess ?: costs?.liveKwh) else null
        val sessionCost = if (charging && liveKwh != null)
            priceSession(costs?.liveStart ?: now, now, liveKwh, tariff) else null

        return Result.Ok(Snapshot(soc, range, locked, doorsOpen, if (winKnown) winOpen else null, sentryOn, sentryRec,
            charging, if (charging) eta else null, liveKwh, sessionCost, todayUsed, lastCharge))
    }
}
