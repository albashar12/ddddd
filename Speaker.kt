package com.example.assistant

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The language Jarvis listens and speaks in. Changed by voice: "Jarvis, speak Hindi". */
object Lang {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("jarvis", Context.MODE_PRIVATE)
    fun get(ctx: Context): String = prefs(ctx).getString("lang", "en-GB") ?: "en-GB"
    fun set(ctx: Context, tag: String) { prefs(ctx).edit().putString("lang", tag).apply() }
    fun locale(ctx: Context): Locale = Locale.forLanguageTag(get(ctx))
}

object Speaker {
    /** Speaks one phrase in another language and waits until it finishes. Call from a background thread. */
    fun speakBlocking(ctx: Context, text: String, tag: String) {
        val latch = CountDownLatch(1)
        var engine: TextToSpeech? = null
        Handler(Looper.getMainLooper()).post {
            engine = TextToSpeech(ctx.applicationContext) { status ->
                val e = engine
                if (status != TextToSpeech.SUCCESS || e == null) { latch.countDown(); return@TextToSpeech }
                e.language = Locale.forLanguageTag(tag)
                e.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { latch.countDown() }
                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) { latch.countDown() }
                })
                e.speak(text, TextToSpeech.QUEUE_FLUSH, null, "once")
            }
        }
        latch.await(20, TimeUnit.SECONDS)
        Handler(Looper.getMainLooper()).post { engine?.shutdown() }
    }
}
