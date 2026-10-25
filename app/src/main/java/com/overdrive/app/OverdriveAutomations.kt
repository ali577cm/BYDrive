package com.overdrive.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Creates (or updates) a small set of rules in Overdrive's own Automations; they then run on the car, with no phone needed:
 *  - when the car locks, turn Child Presence Detection off
 *  - whenever Speed Limit Warning turns on, turn it off again
 *  - if a window or the sunroof opens while the car is locked, close them and send a notification
 *  - if the cabin goes over 26 C while the car is locked, send a notification
 *  - at 11pm, if the car is not plugged in, send a notification
 *  - at 11:15pm, if the car is not charging, send a notification
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

    private val locked = cond("lock", "eq", "locked")

    private val rules = listOf(
        Rule(
            "BYDrive: CPD off when locked",
            listOf(trigger("lock")),
            listOf(locked),
            listOf(action("cpd", "action" to "2")),                                   // 2 = off
        ),
        Rule(
            "BYDrive: speed limit warning off whenever it turns on",
            listOf(trigger("slw")),
            listOf(cond("slw", "eq", "on")),
            listOf(action("adas_slw", "payload" to "off")),
        ),
        Rule(
            "BYDrive: close windows if opened while locked",
            listOf("lf", "rf", "lr", "rr", "sunroof").map { trigger("windowState", "area" to it) },
            listOf(locked),
            listOf(
                action("windowsAll", "command" to "2"),                               // 2 = close
                action("sunroof", "payload" to "close"),
                action("notification", "message" to "A window or the sunroof opened while the car was locked. BYDrive closed it."),
            ),
        ),
        Rule(
            "BYDrive: cabin over 26C alert",
            listOf(trigger("temperature")),
            listOf(locked, cond("temperature", "gt", 26)),
            listOf(action("notification", "message" to "The cabin is over 26C while the car is locked. Check on your pet.")),
        ),
        Rule(
            "BYDrive: not plugged in at 11pm",
            listOf(trigger("time")),
            listOf(cond("time", "eq", 23 * 60), cond("chargeGun", "eq", "disconnected")),
            listOf(action("notification", "message" to "It is 11pm and the car is not plugged in.")),
        ),
        Rule(
            "BYDrive: not charging at 11:15pm",
            listOf(trigger("time")),
            listOf(cond("time", "eq", 23 * 60 + 15), cond("chargingState", "eq", "off")),
            listOf(action("notification", "message" to "It is 11:15pm and the car is not charging.")),
        ),
    )

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
            for (r in rules) {
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
