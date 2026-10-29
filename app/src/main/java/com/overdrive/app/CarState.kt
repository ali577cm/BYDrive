package com.overdrive.app

import android.content.Context

/** The few live values the climate and pet-mode widgets need, read from Overdrive's vehicle state (GET only). */
object CarState {

    class Snap(
        val acOn: Boolean?,        // null = car did not report it
        val insideC: Double?,      // cabin temperature
        val locked: Int,           // 1 locked, 2 unlocked, other unknown
        val soc: Double?,          // battery %
        val cpdOn: Boolean?,       // Child Presence Detection
        val doorsOpen: Int = 0,    // number of open doors
    )

    /** Why the last [read] returned null (for the pet-mode notification). */
    @Volatile var lastWhy: String = ""

    /** Returns null when the car/Overdrive cannot be reached or the login is missing. Blocking. */
    fun read(ctx: Context): Snap? {
        val (base, cookie) = CarStatus.auth(ctx) ?: run { lastWhy = "no address or login in the app"; return null }
        val (j, why) = CarStatus.getWithReason(base, "/api/vehicle/state", cookie, 8000)
        if (j == null) { lastWhy = why; return null }
        if (!j.optBoolean("success", false)) { lastWhy = "car data unavailable: " + j.optString("error", "").take(30); return null }
        lastWhy = ""
        val climate = j.optJSONObject("climate")
        val setting = j.optJSONObject("setting")
        return Snap(
            acOn = climate?.let { if (it.has("acOn")) it.optBoolean("acOn") else null },
            insideC = climate?.optDouble("insideTempC", Double.NaN)?.takeIf { !it.isNaN() },
            locked = j.optJSONObject("doors")?.optInt("overall", -1) ?: -1,
            soc = j.optJSONObject("battery")?.optDouble("soc", Double.NaN)?.takeIf { !it.isNaN() },
            cpdOn = setting?.let { if (it.has("childPresenceDetection")) it.optBoolean("childPresenceDetection") else null },
            doorsOpen = j.optJSONObject("doorOpen")?.let { o -> o.keys().asSequence().count { k -> k != "fuelCap" && k != "trunk" && o.optBoolean(k, false) } } ?: 0,
        )
    }

    fun fmtTemp(t: Double?): String = if (t == null) "—" else "%.1f°".format(t).replace(".0°", "°")
}
