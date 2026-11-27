package com.overdrive.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Creates (or updates) a rule in Overdrive's own Automations; it then runs on the car, with no phone needed:
 *  - on weekdays, in the morning, at lunch and in the afternoon, if it is colder than the chosen outside temperature, start the AC, defrost and
 *    heat the steering wheel and driver's seat
 * Uses the login the app already holds, only talks to the configured address, and only does this when you tap the button.
 */
object OverdriveAutomations {

    private class Rule(val name: String, val triggers: List<JSONObject>, val conditions: List<JSONObject>, val actions: List<JSONObject>)

    /** A trigger fires when this signal changes; extra choices (like which window) go in [vars]. */
    private fun trigger(type: String, vararg vars: Pair<String, String>) =
        JSONObject().put("type", type).put("variables", JSONObject().also { o -> vars.forEach { o.put(it.first, it.second) } })

    /** A condition compares the signal's value; [cmp] is eq, neq, gt, lt, gte or lte. */
    private fun cond(type: String, cmp: String, value: Any, vararg vars: Pair<String, String>) =
        trigger(type, *vars).put("comparator", cmp).put("value", value)

    private fun action(type: String, vararg vars: Pair<String, Any>) =
        JSONObject().put("type", type).put("variables", JSONObject().also { o -> vars.forEach { o.put(it.first, it.second) } })

    private fun warmActions(ctx: Context) = listOf(
        action("powerOn"),                                                          // wake the whole car first (local, no BYD cloud)
        action("pause", "milliseconds" to 20000),                                  // give it time to come up
        action("setAc", "action" to "on", "temp" to Config.warmTempC(ctx), "remoteDurationMinutes" to "30", "autoOffMinutes" to "30"),
        action("frontDefrost", "action" to "on"),
        action("rearDefrost", "action" to "on"),
        action("steeringHeat", "action" to "on"),
        action("seat", "type" to "heat", "area" to "driver", "payload" to "high"),
    )

    /** The rules BYDrive creates in Overdrive: weekday morning, lunch and afternoon warm-ups when it is cold outside. */
    private fun rules(ctx: Context): List<Rule> {
        val below = cond("outsideTemp", "lt", Config.warmBelowC(ctx))
        val list = ArrayList<Rule>()
        // weekdays only: one rule per weekday, since all of a rule's conditions have to be true
        for ((label, minutes) in listOf("morning" to Config.warmTimeMin(ctx), "lunch" to Config.lunchTimeMin(ctx), "afternoon" to Config.pmTimeMin(ctx))) {
            val at = cond("time", "eq", minutes)
            listOf("monday", "tuesday", "wednesday", "thursday", "friday").forEach { d ->
                list.add(Rule("BYDrive: $label warm-up when cold (${d.take(3)})", listOf(trigger("time")),
                    listOf(at, below, cond("day", "eq", d)), warmActions(ctx)))
            }
        }
        return list
    }

    /** Earlier versions made every-day rules under these names; they are removed so nothing runs at weekends. */
    private val obsolete = listOf("BYDrive: morning warm-up when cold", "BYDrive: lunch warm-up when cold", "BYDrive: afternoon warm-up when cold")

    private fun body(r: Rule, disabled: Boolean): String = JSONObject()
        .put("name", r.name)
        .put("triggers", JSONArray(r.triggers))
        .put("conditions", JSONArray(r.conditions))
        .put("conditionLogic", "and")
        .put("delay", 0)
        .put("actions", JSONArray(r.actions))
        .put("elseActions", JSONArray())
        .put("disabled", disabled)
        .toString()

    private fun call(base: String, cookie: String, method: String, path: String, payload: String?): Pair<Int, String> {
        val c = URL(base.trimEnd('/') + path).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = false
        c.connectTimeout = 8000
        c.readTimeout = 12000
        c.requestMethod = method
        c.setRequestProperty("Cookie", cookie)
        c.setRequestProperty("Accept", "application/json")
        if (payload != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(payload.toByteArray()) }
        }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        c.disconnect()
        return code to text
    }

    /** Switches every BYDrive warm-up rule in Overdrive off (holiday) or on, without touching anything else. Blocking. */
    fun applyHoliday(ctx: Context, on: Boolean): String {
        val (base, cookie) = CarStatus.auth(ctx) ?: return "Set the address and sign in first"
        return try {
            val (lc, listText) = call(base, cookie, "GET", "/api/automations/picker", null)
            if (lc == 401 || lc == 403 || lc in 300..399) return "Sign in via the app first"
            if (lc != 200) return "Overdrive answered HTTP $lc"
            var done = 0
            var failed = 0
            val arr = JSONArray(listText)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val name = o.optString("name")
                val id = o.optString("id")
                if (!name.startsWith("BYDrive: ") || !name.contains("warm-up") || id.isEmpty()) continue
                val (code, _) = call(base, cookie, "POST", "/api/automations/disable/" + java.net.URLEncoder.encode(id, "UTF-8"),
                    JSONObject().put("disabled", on).toString())
                if (code == 200) done++ else failed++
            }
            when {
                done == 0 && failed == 0 -> "No warm-up rules found - tap Create / update first"
                failed > 0 -> "$done changed, $failed failed"
                on -> "Holiday mode on: $done warm-ups paused"
                else -> "Holiday mode off: $done warm-ups running"
            }
        } catch (e: java.io.IOException) {
            "Couldn't reach Overdrive (is Tailscale connected and the car awake?)"
        } catch (e: org.json.JSONException) {
            "Unexpected answer from Overdrive"
        }
    }

    /** Returns a short message for the user. Blocking: call from a background thread. */
    fun install(ctx: Context): String {
        val (base, cookie) = CarStatus.auth(ctx) ?: return "Set the address and sign in first"
        return try {
            val (lc, listText) = call(base, cookie, "GET", "/api/automations/picker", null)
            if (lc == 401 || lc == 403 || lc in 300..399) return "Sign in via the app first"
            if (lc != 200) return "Overdrive answered HTTP $lc"
            // name -> id of rules already in Overdrive, so a second tap updates them instead of duplicating
            val ids = HashMap<String, String>()
            try {
                val arr = JSONArray(listText)
                for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { ids[it.optString("name")] = it.optString("id") }
            } catch (_: Exception) {}
            var created = 0
            var updated = 0
            var removed = 0
            for (name in obsolete) {
                val id = ids[name]
                if (!id.isNullOrEmpty() && call(base, cookie, "DELETE", "/api/automations/automation/" + java.net.URLEncoder.encode(id, "UTF-8"), null).first == 200) removed++
            }
            val problems = ArrayList<String>()
            for (r in rules(ctx)) {
                val id = ids[r.name]
                val (code, text) = if (id.isNullOrEmpty()) call(base, cookie, "POST", "/api/automations/automation", body(r, Config.holiday(ctx)))
                                   else call(base, cookie, "PUT", "/api/automations/automation/" + java.net.URLEncoder.encode(id, "UTF-8"), body(r, Config.holiday(ctx)))
                if (code == 200) { if (id.isNullOrEmpty()) created++ else updated++ }
                else problems.add("${r.name}: HTTP $code ${text.take(80)}")
            }
            when {
                problems.isNotEmpty() -> "Problem with: " + problems.joinToString("; ") + (if (created + updated > 0) " (others done)" else "")
                else -> "Overdrive rules ready: $created created, $updated updated" + (if (removed > 0) ", $removed old removed" else "")
            }
        } catch (e: java.io.IOException) {
            "Couldn't reach Overdrive (is Tailscale connected and the car awake?)"
        }
    }
}
