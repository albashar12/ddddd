package com.example.assistant

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import android.speech.*
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import org.json.JSONObject

/**
 * Always-on Jarvis. Say "Jarvis" + a command in your language. "pick up" / "hang up" (English) work instantly.
 * Switch language by voice: "Jarvis, speak Hindi".
 */
class JarvisService : Service(), TextToSpeech.OnInitListener {
    private lateinit var brain: Brain
    private lateinit var actions: PhoneActions
    private lateinit var tts: TextToSpeech
    private lateinit var recognizer: SpeechRecognizer
    private val main = Handler(Looper.getMainLooper())
    private var speaking = false
    private var busy = false

    // How "Jarvis" often gets written by speech recognisers in different languages
    private val wakeWords = listOf(
        "jarvis", "jervis", "javis", "jarvice",
        "জার্ভিস", "জারভিস", "जार्विस", "जारविस", "جارفيس", "جارفس",
        "ジャービス", "자비스", "贾维斯", "賈維斯", "джарвис", "ярвис", "ژارویس", "ஜார்விஸ்"
    )

    override fun onCreate() {
        super.onCreate()
        actions = PhoneActions(this)
        brain = Brain(BuildConfig.ANTHROPIC_API_KEY, actions)
        tts = TextToSpeech(this, this)
        startForegroundNow()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer.setRecognitionListener(listener)
        main.post { listen() }
    }

    override fun onInit(code: Int) {
        tts.setPitch(0.9f)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { main.post { speaking = false } }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) { main.post { speaking = false } }
        })
    }

    private fun startForegroundNow() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("jarvis", "Jarvis", NotificationManager.IMPORTANCE_LOW))
        val n = NotificationCompat.Builder(this, "jarvis")
            .setContentTitle("Jarvis is listening")
            .setContentText("Say \"Jarvis\" followed by a command")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(1, n)
    }

    private fun listen() {
        if (speaking || busy) { main.postDelayed({ listen() }, 500); return }
        recognizer.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Lang.get(this))
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false))
    }

    private val listener = object : RecognitionListener {
        override fun onResults(r: Bundle?) {
            val heard = r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.lowercase().orEmpty()
            handle(heard)
            main.postDelayed({ listen() }, 300)
        }
        override fun onError(e: Int) { main.postDelayed({ listen() }, 700) }
        override fun onReadyForSpeech(p: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(v: Float) {}
        override fun onBufferReceived(b: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(p: Bundle?) {}
        override fun onEvent(t: Int, p: Bundle?) {}
    }

    private fun handle(heard: String) {
        if (heard.isBlank() || busy) return

        if (heard.contains("pick up") || heard.contains("answer the call")) {
            actions.run("answer_call", JSONObject()); say("Answering, sir."); return
        }
        if (heard.contains("hang up") || heard.contains("end the call")) {
            actions.run("end_call", JSONObject()); say("Call ended, sir."); return
        }

        var wakeEnd = -1
        for (w in wakeWords) {
            val i = heard.indexOf(w)
            if (i >= 0) { wakeEnd = i + w.length; break }
        }
        if (wakeEnd < 0) return
        val command = heard.substring(wakeEnd).trim(' ', ',', '.', '،', '、', '，', '।')
            .ifBlank { say("At your service, sir."); return }

        busy = true
        Thread {
            val reply = try { brain.ask(command) } catch (e: Exception) { "I'm afraid something went wrong, sir." }
            main.post { say(reply); busy = false }
        }.start()
    }

    private fun say(text: String) {
        tts.language = Lang.locale(this)   // speaks in whatever language is currently set
        speaking = true
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "j")
        main.postDelayed({ speaking = false }, 40_000) // safety net
    }

    override fun onDestroy() { recognizer.destroy(); tts.shutdown(); super.onDestroy() }
    override fun onBind(i: Intent?): IBinder? = null
}
