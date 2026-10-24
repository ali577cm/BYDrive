package com.overdrive.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** User-configured Overdrive address, stored only on the device (never in the repo). */
object Config {
    private fun prefs(c: Context) = c.getSharedPreferences("overdrive", Context.MODE_PRIVATE)

    /** Widget background: 0 = transparent (default), 1 = glass, 2 = solid. */
    fun bg(c: Context): Int = prefs(c).getInt("bg", 0)

    fun setBg(c: Context, v: Int) { prefs(c).edit().putInt("bg", v).apply() }

    /** A charge Overdrive missed, entered by hand from the charger app (kWh = energy delivered, i.e. at the meter). */
    class Manual(val id: Long, val start: Long, val end: Long, val kwh: Double)

    fun manualSessions(c: Context): List<Manual> = try {
        val a = JSONArray(prefs(c).getString("manual", "[]"))
        (0 until a.length()).map { val o = a.getJSONObject(it); Manual(o.getLong("id"), o.getLong("s"), o.getLong("e"), o.getDouble("k")) }
    } catch (_: Exception) { emptyList() }

    private fun saveManual(c: Context, list: List<Manual>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("id", it.id).put("s", it.start).put("e", it.end).put("k", it.kwh)) }
        prefs(c).edit().putString("manual", a.toString()).apply()
    }

    fun addManual(c: Context, start: Long, end: Long, kwh: Double) =
        saveManual(c, manualSessions(c) + Manual(System.currentTimeMillis(), start, end, kwh))

    fun removeManual(c: Context, id: Long) = saveManual(c, manualSessions(c).filter { it.id != id })

    /** Monthly summary notification on the 1st (default on). */
    fun monthlyEnabled(c: Context): Boolean = prefs(c).getBoolean("monthly", true)

    fun setMonthlyEnabled(c: Context, on: Boolean) { prefs(c).edit().putBoolean("monthly", on).apply() }

    /** Time-of-day electricity tariff (pence per kWh): a cheap window and a standard rate the rest of the day. */
    class Tariff(val stdPence: Double, val cheapPence: Double, val startMin: Int, val endMin: Int, val effPercent: Double = 100.0,
                 val later: Tariff? = null, val switchAt: Long = 0L) {
        fun signature(): String = "$stdPence|$cheapPence|$startMin|$endMin|$effPercent" + (later?.let { "|$switchAt>" + it.signature() } ?: "")
        /** The tariff in force for a charge that started at [start]. */
        fun at(start: Long): Tariff = if (later != null && start >= switchAt) later else this
        /** Same tariffs with charger losses ignored (for energy already measured at the meter). */
        fun atMeter(): Tariff = Tariff(stdPence, cheapPence, startMin, endMin, 100.0, later?.atMeter(), switchAt)
    }

    /** Defaults match Octopus Go: 9.5p 00:30-05:30, 36.67p otherwise. */
    fun tariff(c: Context): Tariff {
        val p = prefs(c)
        val eff = p.getFloat("tEff", 100f).toDouble()
        val later = Tariff(p.getFloat("nStd", 30.78f).toDouble(), p.getFloat("nCheap", 6.66f).toDouble(),
            p.getInt("nStart", 0), p.getInt("nEnd", 300), eff)
        return Tariff(p.getFloat("tStd", 36.67f).toDouble(), p.getFloat("tCheap", 9.5f).toDouble(),
            p.getInt("tStart", 30), p.getInt("tEnd", 330), eff, later, p.getLong("nSwitch", defaultSwitch()))
    }

    /** EDF switch-over: midnight at the start of 24 Oct 2026, local time. */
    private fun defaultSwitch(): Long = java.util.GregorianCalendar(2026, java.util.Calendar.OCTOBER, 24).timeInMillis

    fun switchText(c: Context): String =
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.UK).format(java.util.Date(tariff(c).switchAt))

    fun laterWindowText(c: Context): String {
        val t = tariff(c).later!!
        return "%02d:%02d-%02d:%02d".format(t.startMin / 60, t.startMin % 60, t.endMin / 60, t.endMin % 60)
    }

    /** Saves the tariff that applies from the switch date; returns null when OK, otherwise what was wrong. */
    fun setLaterTariff(c: Context, std: String, cheap: String, window: String, date: String): String? {
        val s = std.trim().replace(',', '.').toFloatOrNull()
        val ch = cheap.trim().replace(',', '.').toFloatOrNull()
        if (s == null || s < 0f || s > 1000f) return "New standard price not saved (pence per kWh, e.g. 30.78)"
        if (ch == null || ch < 0f || ch > 1000f) return "New cheap price not saved (pence per kWh, e.g. 6.66)"
        val m = Regex("^(\\d{1,2}):(\\d{2})\\s*-\\s*(\\d{1,2}):(\\d{2})$").matchEntire(window.trim())
            ?: return "New cheap window not saved (use HH:MM-HH:MM)"
        val (h1, m1, h2, m2) = m.destructured
        if (h1.toInt() > 23 || h2.toInt() > 23 || m1.toInt() > 59 || m2.toInt() > 59) return "New cheap window not saved (invalid time)"
        val d = Regex("^(\\d{4})-(\\d{2})-(\\d{2})$").matchEntire(date.trim())
            ?: return "Switch date not saved (use YYYY-MM-DD)"
        val (y, mo, da) = d.destructured
        if (mo.toInt() !in 1..12 || da.toInt() !in 1..31) return "Switch date not saved (invalid date)"
        prefs(c).edit().putFloat("nStd", s).putFloat("nCheap", ch)
            .putInt("nStart", h1.toInt() * 60 + m1.toInt()).putInt("nEnd", h2.toInt() * 60 + m2.toInt())
            .putLong("nSwitch", java.util.GregorianCalendar(y.toInt(), mo.toInt() - 1, da.toInt()).timeInMillis).apply()
        return null
    }

    fun windowText(c: Context): String {
        val t = tariff(c)
        return "%02d:%02d-%02d:%02d".format(t.startMin / 60, t.startMin % 60, t.endMin / 60, t.endMin % 60)
    }

    /** Saves the tariff; returns null when OK, otherwise a message saying what was wrong. */
    fun setTariff(c: Context, std: String, cheap: String, window: String, eff: String): String? {
        val s = std.trim().replace(',', '.').toFloatOrNull()
        val ch = cheap.trim().replace(',', '.').toFloatOrNull()
        if (s == null || s < 0f || s > 1000f) return "Standard price not saved (use pence per kWh, e.g. 36.67)"
        if (ch == null || ch < 0f || ch > 1000f) return "Cheap price not saved (use pence per kWh, e.g. 9.5)"
        val e = eff.trim().replace(',', '.').toFloatOrNull()
        if (e == null || e < 50f || e > 100f) return "Charger efficiency not saved (use 50 to 100; 100 = ignore losses)"
        val m = Regex("^(\\d{1,2}):(\\d{2})\\s*-\\s*(\\d{1,2}):(\\d{2})$").matchEntire(window.trim())
            ?: return "Cheap window not saved (use HH:MM-HH:MM, e.g. 00:30-05:30)"
        val (h1, m1, h2, m2) = m.destructured
        if (h1.toInt() > 23 || h2.toInt() > 23 || m1.toInt() > 59 || m2.toInt() > 59)
            return "Cheap window not saved (invalid time)"
        prefs(c).edit().putFloat("tStd", s).putFloat("tCheap", ch).putFloat("tEff", e)
            .putInt("tStart", h1.toInt() * 60 + m1.toInt()).putInt("tEnd", h2.toInt() * 60 + m2.toInt()).apply()
        return null
    }

    fun url(c: Context): String? = prefs(c).getString("url", null)

    /** Plain http is only acceptable for Tailscale (already encrypted), loopback and private home-network addresses. */
    private fun httpAllowed(u: String): Boolean {
        val host = try { android.net.Uri.parse(u).host } catch (_: Exception) { null } ?: return false
        if (Tailscale.isTailscaleAddress(u) || host == "localhost" || host.endsWith(".local")) return true
        val p = host.split(".")
        if (p.size != 4) return false
        val a = p[0].toIntOrNull()
        val b = p[1].toIntOrNull()
        return a == 10 || a == 127 || (a == 192 && b == 168) || (a == 172 && b != null && b in 16..31)
    }

    /** Normalises and saves the address; returns it, or null if it isn't a valid http(s) URL. */
    fun setUrl(c: Context, raw: String): String? {
        var u = raw.trim()
        if (u.isEmpty()) return null
        if (!u.contains("://")) u = "http://$u"
        if (!Regex("^https?://[^\\s'\"<>\\\\]+$").matches(u)) return null
        if (u.startsWith("http://") && !httpAllowed(u)) return null       // plain http only inside Tailscale / your own network
        prefs(c).edit().putString("url", u).apply()
        return u
    }
}
