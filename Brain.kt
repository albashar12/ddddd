package com.example.assistant

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** The brain: talks to Claude, runs phone tools, can see screenshots, and can search the web. */
class Brain(private val apiKey: String, private val actions: PhoneActions) {
    private val client = OkHttpClient.Builder().readTimeout(90, TimeUnit.SECONDS).build()
    private val history = JSONArray()
    private val system = JARVIS_PROMPT

    fun ask(userText: String): String {
        history.put(JSONObject().put("role", "user").put("content", userText))
        val carry = StringBuilder()
        repeat(10) {
            val res = call()
            val content = res.getJSONArray("content")
            history.put(JSONObject().put("role", "assistant").put("content", content))

            val text = StringBuilder(carry)
            val results = JSONArray()
            for (i in 0 until content.length()) {
                val b = content.getJSONObject(i)
                when (b.getString("type")) {
                    "text" -> text.append(b.getString("text"))
                    "tool_use" -> results.put(toolResult(b.getString("id"), actions.run(b.getString("name"), b.getJSONObject("input"))))
                }
            }
            if (results.length() == 0) {
                if (res.optString("stop_reason") == "pause_turn") { carry.clear().append(text); return@repeat }
                trim()
                return text.toString().trim()
            }
            history.put(JSONObject().put("role", "user").put("content", results))
        }
        trim()
        return "Sorry, I got a bit stuck on that one."
    }

    /** Tool results that start with IMG: are screenshots; send them to Claude as images. */
    private fun toolResult(id: String, out: String): JSONObject {
        val r = JSONObject().put("type", "tool_result").put("tool_use_id", id)
        if (out.startsWith("IMG:")) {
            r.put("content", JSONArray()
                .put(JSONObject().put("type", "image").put("source", JSONObject()
                    .put("type", "base64").put("media_type", "image/jpeg").put("data", out.removePrefix("IMG:"))))
                .put(JSONObject().put("type", "text").put("text", "This is the phone screen right now.")))
        } else r.put("content", out)
        return r
    }

    /** Keep the conversation short enough; always restart at a plain user message. */
    private fun trim() {
        if (history.length() <= 30) return
        while (history.length() > 0) {
            history.remove(0)
            if (history.length() == 0) break
            val m = history.getJSONObject(0)
            if (m.getString("role") == "user" && m.get("content") is String) break
        }
    }

    private fun call(): JSONObject {
        val tools = actions.toolSchemas().put(
            JSONObject().put("type", "web_search_20250305").put("name", "web_search").put("max_uses", 3))
        val body = JSONObject()
            .put("model", "claude-sonnet-5-5")
            .put("max_tokens", 1500)
            .put("system", system + "\nCurrent local time: " + java.time.ZonedDateTime.now())
            .put("tools", tools)
            .put("messages", history)
            .toString().toRequestBody("application/json".toMediaType())

        val req = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .post(body).build()

        client.newCall(req).execute().use { r ->
            val s = r.body?.string().orEmpty()
            if (!r.isSuccessful) error("API ${r.code}: $s")
            return JSONObject(s)
        }
    }
}
