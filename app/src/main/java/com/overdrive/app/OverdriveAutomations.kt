package com.overdrive.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Creates (or updates) a rule in Overdrive's own Automations; it then runs on the car, with no phone needed:
 *  - in the morning and the afternoon, if it is colder than the chosen outside temperature, start the AC, defrost and
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
        action("setAc", "action" to "on", "temp" to Config.warmTempC(ctx), "remoteDurationMinutes" to "30", "autoOffMinutes" to "30"),
        action("frontDefrost", "action" to "on"),
        action("rearDefrost", "action" to "on"),
        action("steeringHeat", "action" to "on"),
        action("seat", "type" to "heat", "area" to "driver", "payload" to "high"),
    )

    /** The rules BYDrive creates in Overdrive: a morning and an afternoon warm-up when it is cold outside. */
    private fun rules(ctx: Context): List<Rule> {
        val below = cond("outsideTemp", "lt", Config.warmBelowC(ctx))
        val list = ArrayList<Rule>()
        list.add(Rule("BYDrive: morning warm-up when cold", listOf(trigger("time")),
            listOf(cond("time", "eq", Config.warmTimeMin(ctx)), below), warmActions(ctx)))
        val pm = cond("time", "eq", Config.pmTimeMin(ctx))
        if (Config.pmWeekdays(ctx)) {
            // one rule per weekday (a rule's conditions all have to be true, so "Mon or Tue or ..." needs separate rules)
            listOf("monday", "tuesday", "wednesday", "thursday", "friday").forEach { d ->
                list.add(Rule("BYDrive: afternoon warm-up when cold (${d.take(3)})", listOf(trigger("time")),
                    listOf(pm, below, cond("day", "eq", d)), warmActions(ctx)))
            }
        } else {
            list.add(Rule("BYDrive: afternoon warm-up when cold", listOf(trigger("time")), listOf(pm, below), warmActions(ctx)))
        }
        return list
    }

    private fun body(r: Rule): String = JSONObject()
        .put("name", r.name)
        .put("triggers", JSONArray(r.triggers))
        .put("conditions", JSONArray(r.conditions))
        .put("conditionLogic", "and")
        .put("delay", 0)
        .put("actions", JSONArray(r.actions))
        .put("elseActions", JSONArray())
        .put("disabled", false)
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
            val problems = ArrayList<String>()
            for (r in rules(ctx)) {
                val id = ids[r.name]
                val (code, text) = if (id.isNullOrEmpty()) call(base, cookie, "POST", "/api/automations/automation", body(r))
                                   else call(base, cookie, "PUT", "/api/automations/automation/" + java.net.URLEncoder.encode(id, "UTF-8"), body(r))
                if (code == 200) { if (id.isNullOrEmpty()) created++ else updated++ }
                else problems.add("${r.name}: HTTP $code ${text.take(80)}")
            }
            when {
                problems.isNotEmpty() -> "Problem with: " + problems.joinToString("; ") + (if (created + updated > 0) " (others done)" else "")
                else -> "Overdrive rules ready: $created created, $updated updated"
            }
        } catch (e: java.io.IOException) {
            "Couldn't reach Overdrive (is Tailscale connected and the car awake?)"
        }
    }
}
