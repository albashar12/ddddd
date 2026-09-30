package com.example.assistant

import android.annotation.SuppressLint
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.telecom.TelecomManager
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** Everything the assistant is allowed to do on the phone. Add more tools here. */
class PhoneActions(private val ctx: Context) {

    private val prefs get() = ctx.getSharedPreferences("jarvis", Context.MODE_PRIVATE)
    private val life = LifeTools(ctx)

    private fun tool(name: String, desc: String, props: JSONObject = JSONObject(), req: List<String> = emptyList()) =
        JSONObject().put("name", name).put("description", desc).put(
            "input_schema", JSONObject().put("type", "object").put("properties", props).put("required", JSONArray(req))
        )

    private fun str(desc: String) = JSONObject().put("type", "string").put("description", desc)
    private fun num(desc: String) = JSONObject().put("type", "integer").put("description", desc)

    fun toolSchemas() = JSONArray().apply {
        // --- basics ---
        put(tool("open_app", "Open an installed app by its name", JSONObject().put("name", str("App name, e.g. WhatsApp")), listOf("name")))
        put(tool("call_number", "Start a phone call", JSONObject().put("number", str("Phone number")), listOf("number")))
        put(tool("answer_call", "Answer the currently ringing call"))
        put(tool("end_call", "Hang up the current call"))
        put(tool("open_camera", "Open the camera app to take a photo"))
        put(tool("flashlight", "Turn flashlight on or off", JSONObject().put("on", JSONObject().put("type", "boolean")), listOf("on")))
        put(tool("set_alarm", "Set an alarm", JSONObject().put("hour", num("0-23")).put("minute", num("0-59")), listOf("hour", "minute")))
        put(tool("compose_sms", "Open a text message ready to send", JSONObject().put("number", str("Phone number")).put("text", str("Message")), listOf("number", "text")))
        put(tool("show_in_browser", "Open a web search in the browser so the user can see the results on screen", JSONObject().put("query", str("Search query")), listOf("query")))
        put(tool("ui_action", "Control the screen: back, home, recents, notifications, scroll_down, scroll_up, screenshot, lock_screen, or tap_text (taps the on-screen element with the given text)",
            JSONObject().put("action", str("back|home|recents|notifications|scroll_down|scroll_up|screenshot|lock_screen|tap_text")).put("text", str("Text to tap, for tap_text")), listOf("action")))
        put(tool("read_screen", "Read the text currently visible on screen"))

        // --- daily life ---
        put(tool("get_datetime", "Get the current date, day of week and time. Always call this before doing anything date-related."))
        put(tool("set_timer", "Start a countdown timer (cooking, workouts, breaks)",
            JSONObject().put("seconds", num("Length in seconds")).put("label", str("What the timer is for")), listOf("seconds")))
        put(tool("add_event", "Add an event to the calendar (opens it pre-filled for the user to save)",
            JSONObject().put("title", str("Event title")).put("start", str("Local start time, ISO format like 2026-10-05T15:30"))
                .put("duration_minutes", num("Length, default 60")), listOf("title", "start")))
        put(tool("navigate", "Start turn-by-turn navigation to a place", JSONObject().put("place", str("Address or place name")), listOf("place")))
        put(tool("play_music", "Play a song, artist or playlist", JSONObject().put("query", str("Song or artist")), listOf("query")))
        put(tool("battery_status", "Get battery percentage and whether it's charging"))
        put(tool("set_volume", "Set media volume", JSONObject().put("percent", num("0-100")), listOf("percent")))
        put(tool("find_contact", "Look up a contact's phone number by name. Use this before calling or messaging someone by name.",
            JSONObject().put("name", str("Contact name")), listOf("name")))
        put(tool("whatsapp_message", "Open a WhatsApp chat with a prefilled message (user taps send)",
            JSONObject().put("number", str("Number with country code, digits only")).put("text", str("Message")), listOf("number", "text")))
        put(tool("add_note", "Save a quick note, list item or reminder text", JSONObject().put("text", str("Note")), listOf("text")))
        put(tool("read_notes", "Read back all saved notes"))
        put(tool("clear_notes", "Delete all saved notes"))
        put(tool("read_notifications", "Read the most recent notifications on the phone"))
        put(tool("open_settings", "Open a settings screen: wifi, bluetooth, volume, airplane, location, display, notification_access",
            JSONObject().put("which", str("wifi|bluetooth|volume|airplane|location|display|notification_access")), listOf("which")))
        val l = life.schemas(); for (i in 0 until l.length()) put(l.get(i))
    }

    @SuppressLint("MissingPermission")
    fun run(name: String, a: JSONObject): String = try {
        when (name) {
            "open_app" -> openApp(a.getString("name"))
            "call_number" -> launch(Intent(Intent.ACTION_CALL, Uri.parse("tel:" + a.getString("number")))).let { "Calling" }
            "answer_call" -> { tm().acceptRingingCall(); "Answered" }
            "end_call" -> { if (tm().endCall()) "Call ended" else "Couldn't end call" }
            "open_camera" -> launch(Intent(MediaStore.ACTION_IMAGE_CAPTURE)).let { "Camera opened" }
            "flashlight" -> {
                val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                cm.setTorchMode(cm.cameraIdList[0], a.getBoolean("on")); "Done"
            }
            "set_alarm" -> launch(Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, a.getInt("hour"))
                .putExtra(AlarmClock.EXTRA_MINUTES, a.getInt("minute"))
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)).let { "Alarm set" }
            "compose_sms" -> launch(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + a.getString("number")))
                .putExtra("sms_body", a.getString("text"))).let { "Message ready; user must press send" }
            "show_in_browser" -> launch(Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, a.getString("query"))).let { "Searching" }
            "ui_action" -> AssistantAccessibilityService.instance?.perform(a.getString("action"), a.optString("text"))
                ?: "Accessibility service is not enabled"
            "read_screen" -> AssistantAccessibilityService.instance?.readScreen() ?: "Accessibility service is not enabled"

            // --- daily life ---
            "get_datetime" -> ZonedDateTime.now().let {
                "${it.dayOfWeek} ${it.toLocalDate()} ${it.toLocalTime().withNano(0)} (${it.zone})"
            }
            "set_timer" -> launch(Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, a.getInt("seconds"))
                .putExtra(AlarmClock.EXTRA_MESSAGE, a.optString("label", "Timer"))
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)).let { "Timer started" }
            "add_event" -> {
                val start = LocalDateTime.parse(a.getString("start")).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                val end = start + a.optInt("duration_minutes", 60) * 60_000L
                launch(Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
                    .putExtra(CalendarContract.Events.TITLE, a.getString("title"))
                    .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start)
                    .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end))
                "Calendar event ready; user must tap save"
            }
            "navigate" -> {
                val q = Uri.encode(a.getString("place"))
                try { launch(Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$q")).setPackage("com.google.android.apps.maps")) }
                catch (e: Exception) { launch(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$q"))) }
                "Navigation started"
            }
            "play_music" -> {
                val q = a.getString("query")
                try {
                    launch(Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
                        .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                        .putExtra(SearchManager.QUERY, q))
                } catch (e: Exception) {
                    launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(q))))
                }
                "Playing $q"
            }
            "battery_status" -> {
                val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                "${bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)}%, " +
                    if (bm.isCharging) "charging" else "not charging"
            }
            "set_volume" -> {
                val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                am.setStreamVolume(AudioManager.STREAM_MUSIC, max * a.getInt("percent").coerceIn(0, 100) / 100, AudioManager.FLAG_SHOW_UI)
                "Volume set"
            }
            "find_contact" -> findContact(a.getString("name"))
            "whatsapp_message" -> launch(Intent(Intent.ACTION_VIEW,
                Uri.parse("https://wa.me/${a.getString("number")}?text=${Uri.encode(a.getString("text"))}"))).let { "WhatsApp chat ready; user must tap send" }
            "add_note" -> {
                val old = prefs.getString("notes", "").orEmpty()
                prefs.edit().putString("notes", (old + "\n• " + a.getString("text")).trim()).apply(); "Saved"
            }
            "read_notes" -> prefs.getString("notes", "").orEmpty().ifBlank { "No notes yet" }
            "clear_notes" -> { prefs.edit().remove("notes").apply(); "Notes cleared" }
            "read_notifications" -> JarvisNotificationListener.snapshot()
            "open_settings" -> {
                val action = when (a.getString("which")) {
                    "wifi" -> Settings.Panel.ACTION_INTERNET_CONNECTIVITY
                    "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
                    "volume" -> Settings.Panel.ACTION_VOLUME
                    "airplane" -> Settings.ACTION_AIRPLANE_MODE_SETTINGS
                    "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
                    "display" -> Settings.ACTION_DISPLAY_SETTINGS
                    "notification_access" -> Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
                    else -> Settings.ACTION_SETTINGS
                }
                launch(Intent(action)); "Opened settings"
            }
            else -> life.run(name, a)
        }
    } catch (e: Exception) { "Failed: ${e.message}" }

    private fun findContact(name: String): String {
        val p = ContactsContract.CommonDataKinds.Phone
        val out = mutableListOf<String>()
        ctx.contentResolver.query(p.CONTENT_URI, arrayOf(p.DISPLAY_NAME, p.NUMBER),
            p.DISPLAY_NAME + " LIKE ?", arrayOf("%$name%"), null)?.use {
            while (it.moveToNext() && out.size < 5) out.add("${it.getString(0)}: ${it.getString(1)}")
        }
        return if (out.isEmpty()) "No contact found" else out.joinToString("\n")
    }

    private fun tm() = ctx.getSystemService(Context.TELECOM_SERVICE) as TelecomManager

    private fun launch(i: Intent) = ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    private fun openApp(name: String): String {
        val pm = ctx.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val hit = pm.queryIntentActivities(main, 0)
            .firstOrNull { it.loadLabel(pm).toString().contains(name, ignoreCase = true) }
            ?: return "No app called $name"
        launch(pm.getLaunchIntentForPackage(hit.activityInfo.packageName)!!)
        return "Opened ${hit.loadLabel(pm)}"
    }
}
