package com.example.assistant

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.Settings
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime

/** Extra "help me in life" tools: language, weather, location, safety, money, reminders, vision. */
class LifeTools(private val ctx: Context) {
    private val prefs get() = ctx.getSharedPreferences("jarvis", Context.MODE_PRIVATE)
    private val http = OkHttpClient()

    private fun tool(name: String, desc: String, props: JSONObject = JSONObject(), req: List<String> = emptyList()) =
        JSONObject().put("name", name).put("description", desc).put(
            "input_schema", JSONObject().put("type", "object").put("properties", props).put("required", JSONArray(req)))
    private fun str(d: String) = JSONObject().put("type", "string").put("description", d)
    private fun num(d: String) = JSONObject().put("type", "integer").put("description", d)
    private fun dbl(d: String) = JSONObject().put("type", "number").put("description", d)

    fun schemas() = JSONArray().apply {
        put(tool("look_at_screen", "Take a screenshot and look at it: read, translate or explain whatever is on the phone screen right now."))
        put(tool("set_language", "Change the language Jarvis listens and speaks in",
            JSONObject().put("language_tag", str("BCP-47 tag like hi-IN, bn-BD, es-ES")), listOf("language_tag")))
        put(tool("speak_in", "Say a phrase aloud with correct pronunciation in another language (translation help)",
            JSONObject().put("language_tag", str("BCP-47 tag")).put("text", str("Phrase in that language")), listOf("language_tag", "text")))
        put(tool("get_weather", "Weather now and the next 3 days. Leave city empty to use the phone's location.",
            JSONObject().put("city", str("City name, optional"))))
        put(tool("get_location", "Get the phone's last known location as coordinates and a map link"))
        put(tool("save_parking", "Remember where the car/bike is parked right now"))
        put(tool("find_parking", "Navigate on foot back to the saved parking spot"))
        put(tool("set_emergency_contact", "Save an emergency contact for SOS messages",
            JSONObject().put("name", str("Name")).put("number", str("Phone number")), listOf("name", "number")))
        put(tool("send_sos", "Prepare an SOS text with the current location to the emergency contact (user taps send)",
            JSONObject().put("message", str("Optional message, default 'I need help.'"))))
        put(tool("share_location_sms", "Prepare a text with the current location map link for someone (user taps send)",
            JSONObject().put("number", str("Phone number, optional if an emergency contact is saved"))))
        put(tool("dial_number", "Open the dialer with a number ready (user taps call). Use for emergency services.",
            JSONObject().put("number", str("Number")), listOf("number")))
        put(tool("add_expense", "Log money the user spent",
            JSONObject().put("amount", dbl("Amount")).put("category", str("food, transport, bills, shopping, other..."))
                .put("note", str("Optional note")), listOf("amount")))
        put(tool("expense_summary", "Summarise spending", JSONObject().put("period", str("today|week|month")), listOf("period")))
        put(tool("compose_email", "Open an email draft (user taps send)",
            JSONObject().put("to", str("Email address")).put("subject", str("Subject")).put("body", str("Body")), listOf("to", "subject", "body")))
        put(tool("open_url", "Open a web address in the browser", JSONObject().put("url", str("Full URL")), listOf("url")))
        put(tool("remind_in", "Remind the user after some minutes (up to 23 hours) with a message",
            JSONObject().put("minutes", num("Minutes from now")).put("message", str("What to remind")), listOf("minutes", "message")))
        put(tool("set_brightness", "Set screen brightness", JSONObject().put("percent", num("0-100")), listOf("percent")))
    }

    fun run(name: String, a: JSONObject): String = when (name) {
        "look_at_screen" -> AssistantAccessibilityService.instance?.screenshotBase64() ?: "Accessibility service is not enabled"
        "set_language" -> { Lang.set(ctx, a.getString("language_tag")); "Language set to ${a.getString("language_tag")}. Reply in that language from now on." }
        "speak_in" -> { Speaker.speakBlocking(ctx, a.getString("text"), a.getString("language_tag")); "Spoken aloud" }
        "get_weather" -> weather(a.optString("city"))
        "get_location" -> location()?.let { "${it.latitude},${it.longitude} https://maps.google.com/?q=${it.latitude},${it.longitude}" }
            ?: "No recent location available (is location on and permission granted?)"
        "save_parking" -> location()?.let {
            prefs.edit().putString("parking", "${it.latitude},${it.longitude}").apply(); "Parking spot saved"
        } ?: "Couldn't get the location"
        "find_parking" -> prefs.getString("parking", null)?.let {
            launch(Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$it&mode=w"))); "Navigating to the parked spot"
        } ?: "No parking spot saved yet"
        "set_emergency_contact" -> {
            prefs.edit().putString("sos_name", a.getString("name")).putString("sos_number", a.getString("number")).apply(); "Emergency contact saved"
        }
        "send_sos" -> shareLocation(null, a.optString("message").ifBlank { "I need help." })
        "share_location_sms" -> shareLocation(a.optString("number").ifBlank { null }, "Here is my location.")
        "dial_number" -> { launch(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + a.getString("number")))); "Dialer opened; user must tap call" }
        "add_expense" -> addExpense(a)
        "expense_summary" -> summary(a.getString("period"))
        "compose_email" -> {
            launch(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + a.getString("to")))
                .putExtra(Intent.EXTRA_SUBJECT, a.getString("subject")).putExtra(Intent.EXTRA_TEXT, a.getString("body")))
            "Email draft ready; user must tap send"
        }
        "open_url" -> { launch(Intent(Intent.ACTION_VIEW, Uri.parse(a.getString("url")))); "Opened" }
        "remind_in" -> {
            val t = LocalDateTime.now().plusMinutes(a.getLong("minutes").coerceIn(1, 1439))
            launch(Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, t.hour)
                .putExtra(AlarmClock.EXTRA_MINUTES, t.minute).putExtra(AlarmClock.EXTRA_MESSAGE, a.getString("message"))
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true))
            "Reminder set for ${t.toLocalTime().withNano(0)}"
        }
        "set_brightness" -> setBrightness(a.getInt("percent"))
        else -> "Unknown tool"
    }

    private fun launch(i: Intent) = ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    @SuppressLint("MissingPermission")
    private fun location(): Location? {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
    }

    private fun shareLocation(number: String?, msg: String): String {
        val to = number ?: prefs.getString("sos_number", null)
            ?: return "No number given and no emergency contact saved. Ask the user for one."
        val loc = location()
        val where = loc?.let { "My location: https://maps.google.com/?q=${it.latitude},${it.longitude}" } ?: "Location unavailable."
        launch(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$to")).putExtra("sms_body", "$msg $where"))
        return "Text ready; the user must press send. If this is an emergency they should also call local emergency services."
    }

    private fun addExpense(a: JSONObject): String {
        val arr = JSONArray(prefs.getString("expenses", "[]"))
        arr.put(JSONObject().put("t", System.currentTimeMillis()).put("amt", a.getDouble("amount"))
            .put("cat", a.optString("category", "other")).put("note", a.optString("note", "")))
        prefs.edit().putString("expenses", arr.toString()).apply()
        return "Logged ${a.getDouble("amount")} for ${a.optString("category", "other")}"
    }

    private fun summary(period: String): String {
        val arr = JSONArray(prefs.getString("expenses", "[]"))
        val day = 86_400_000L
        val span = when (period) { "today" -> day; "week" -> 7 * day; else -> 30 * day }
        val now = System.currentTimeMillis()
        val byCat = mutableMapOf<String, Double>()
        var total = 0.0
        for (i in 0 until arr.length()) {
            val e = arr.getJSONObject(i)
            if (now - e.getLong("t") <= span) { total += e.getDouble("amt"); byCat.merge(e.getString("cat"), e.getDouble("amt"), Double::plus) }
        }
        return if (total == 0.0) "No expenses logged for this period"
        else "Total $total. " + byCat.entries.joinToString(", ") { "${it.key}: ${it.value}" }
    }

    private fun setBrightness(percent: Int): String {
        if (!Settings.System.canWrite(ctx)) {
            launch(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${ctx.packageName}")))
            return "Needs a one-time permission. I opened the screen; ask the user to allow it, then try again."
        }
        Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, percent.coerceIn(0, 100) * 255 / 100)
        return "Brightness set"
    }

    private fun weather(city: String): String {
        val lat: Double; val lon: Double; val label: String
        if (city.isBlank()) {
            val l = location() ?: return "I need a city name, or location access."
            lat = l.latitude; lon = l.longitude; label = "your location"
        } else {
            val g = JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?name=${Uri.encode(city)}&count=1"))
            val r = g.optJSONArray("results") ?: return "I couldn't find a place called $city"
            val p = r.getJSONObject(0)
            lat = p.getDouble("latitude"); lon = p.getDouble("longitude"); label = p.getString("name")
        }
        return "Weather for $label (WMO weather_code, Celsius): " + get(
            "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,apparent_temperature,precipitation,weather_code,wind_speed_10m" +
            "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max&timezone=auto&forecast_days=3")
    }

    private fun get(url: String): String =
        http.newCall(Request.Builder().url(url).build()).execute().use { it.body?.string().orEmpty() }
}
