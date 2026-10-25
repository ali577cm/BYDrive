package com.overdrive.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * After a charge that was not at home, sends a notification asking what it cost (the home tariff would be wrong for it).
 * Home is the place Overdrive calls "Home", or failing that the spot where most of your charges start.
 * Each charge is asked about once. Runs from the periodic background refresh.
 */
object AwayCharges {
    private const val CHANNEL = "away"
    private fun prefs(c: Context) = c.getSharedPreferences("away", Context.MODE_PRIVATE)

    private class S(val id: Long, val start: Long, val end: Long, val lat: Double, val lng: Double, val place: String, val done: Boolean)

    private fun metres(a: S, lat: Double, lng: Double): Double {
        val dLat = (a.lat - lat) * 111_320.0
        val dLng = (a.lng - lng) * 111_320.0 * Math.cos(Math.toRadians(lat))
        return Math.hypot(dLat, dLng)
    }

    fun check(ctx: Context) {
        val (base, cookie) = CarStatus.auth(ctx) ?: return
        val arr = CarStatus.getJson(base, "/api/charging?days=90&limit=200&offset=0", cookie)?.optJSONArray("sessions") ?: return
        val all = ArrayList<S>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.isNull("lat") || o.isNull("lng")) continue
            val e = o.optDouble("energyAdded", Double.NaN)
            all.add(S(o.optLong("id", 0L), o.optLong("startTime", 0L), o.optLong("endTime", 0L), o.optDouble("lat"), o.optDouble("lng"),
                o.optString("placeLabel", "").let { if (it == "null") "" else it }, !o.optBoolean("inProgress", false) && !e.isNaN() && e > 0))
        }
        if (all.isEmpty()) return
        // home: where Overdrive says "Home", else the most common start spot (~100 m grid)
        val labelled = all.firstOrNull { it.place.contains("home", ignoreCase = true) }
        val (hLat, hLng) = if (labelled != null) labelled.lat to labelled.lng else {
            val top = all.groupBy { Math.round(it.lat * 1000) to Math.round(it.lng * 1000) }.maxByOrNull { it.value.size }!!.value.first()
            top.lat to top.lng
        }
        val asked = prefs(ctx).getStringSet("asked", emptySet())!!.toMutableSet()
        val fixed = Config.overrides(ctx)
        val first = prefs(ctx).getLong("since", 0L).let { if (it == 0L) System.currentTimeMillis().also { n -> prefs(ctx).edit().putLong("since", n).apply() } else it }
        for (s in all) {
            if (!s.done || s.id == 0L || s.end < first) continue                      // only charges that finish after this feature is on
            if (asked.contains(s.id.toString()) || fixed.containsKey(s.id)) continue
            if (metres(s, hLat, hLng) < 300.0 || s.place.contains("home", ignoreCase = true)) continue
            ask(ctx, s)
            asked.add(s.id.toString())
        }
        prefs(ctx).edit().putStringSet("asked", asked).apply()
    }

    private fun ask(ctx: Context, s: S) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Charges away from home", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(ctx, s.id.toInt(),
            Intent(ctx, StatsActivity::class.java).putExtra("editId", s.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val where = if (s.place.isNotEmpty()) " at ${s.place}" else " away from home"
        nm.notify(5000 + (s.id % 1000).toInt(), Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_bolt)
            .setContentTitle("How much was that charge?")
            .setContentText("You charged$where. Tap to enter what it cost.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build())
    }
}
