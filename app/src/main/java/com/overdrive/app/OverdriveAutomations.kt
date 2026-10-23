package com.overdrive.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import android.content.Context

/**
 * Creates two rules in Overdrive's own Automations (they then run on the car, with no phone needed):
 *  - every time the car is locked, turn Child Presence Detection off
 *  - whenever Speed Limit Warning turns on (the car re-enables it when it starts), turn it off again
 * Uses the login the app already holds, only talks to the configured address, and only does this when you tap the button.
 */
object OverdriveAutomations {

    private class Rule(val name: String, val trigger: JSONObject, val action: JSONObject)

    private val rules = listOf(
        Rule(
            "BYDrive: CPD off when locked",
            JSONObject().put("type", "lock").put("variables", JSONObject().put("state", "locked")),
            JSONObject().put("type", "cpd").put("variables", JSONObject().put("action", "2")),   // 2 = off
        ),
        Rule(
            "BYDrive: speed limit warning off whenever it turns on",
            JSONObject().put("type", "slw").put("variables", JSONObject().put("state", "on")),
            JSONObject().put("type", "adas_slw").put("variables", JSONObject().put("payload", "off")),
        ),
    )

    private fun body(r: Rule): String = JSONObject()
        .put("name", r.name)
        .put("triggers", JSONArray().put(r.trigger))
        .put("conditions", JSONArray())
        .put("conditionLogic", "and")
        .put("delay", 0)
        .put("actions", JSONArray().put(r.action))
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
            val (lc, existing) = call(base, cookie, "GET", "/api/automations/list", null)
            if (lc == 401 || lc == 403 || lc in 300..399) return "Sign in via the app first"
            if (lc != 200) return "Overdrive answered HTTP $lc"
            var made = 0
            var skipped = 0
            val problems = ArrayList<String>()
            for (r in rules) {
                if (existing.contains(r.name)) { skipped++; continue }          // already there from an earlier tap
                val (code, text) = call(base, cookie, "POST", "/api/automations/automation", body(r))
                if (code == 200) made++ else problems.add("${r.name}: HTTP $code ${text.take(80)}")
            }
            when {
                problems.isNotEmpty() -> "Could not create: " + problems.joinToString("; ")
                made == 0 -> "Both rules are already in Overdrive"
                else -> "Created $made rule(s) in Overdrive" + (if (skipped > 0) " ($skipped already existed)" else "") + ". Lock the car and check."
            }
        } catch (e: java.io.IOException) {
            "Couldn't reach Overdrive (is Tailscale connected and the car awake?)"
        }
    }
}
