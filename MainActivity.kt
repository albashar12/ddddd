package com.example.assistant

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {
    private lateinit var brain: Brain
    private lateinit var tts: TextToSpeech
    private lateinit var recognizer: SpeechRecognizer
    private lateinit var status: TextView

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        // TODO: don't ship a raw key in a real app; call your own backend that holds the key.
        brain = Brain(BuildConfig.ANTHROPIC_API_KEY, PhoneActions(this))
        tts = TextToSpeech(this, this)
        recognizer = SpeechRecognizer.createSpeechRecognizer(this)

        status = TextView(this).apply { text = "Tap and talk"; textSize = 18f; setPadding(40, 80, 40, 40) }
        val talk = Button(this).apply { text = "🎤 Talk"; setOnClickListener { listen() } }
        val access = Button(this).apply {
            text = "Enable screen control"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        val start = Button(this).apply {
            text = "⚡ Start Jarvis (always listening)"
            setOnClickListener { androidx.core.content.ContextCompat.startForegroundService(this@MainActivity, Intent(this@MainActivity, JarvisService::class.java)) }
        }
        val stop = Button(this).apply {
            text = "Stop Jarvis"
            setOnClickListener { stopService(Intent(this@MainActivity, JarvisService::class.java)) }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(status); addView(start); addView(stop); addView(talk); addView(access)
        })

        requestPermissions(arrayOf(
            Manifest.permission.RECORD_AUDIO, Manifest.permission.CALL_PHONE,
            Manifest.permission.ANSWER_PHONE_CALLS, Manifest.permission.CAMERA, Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.READ_CONTACTS, Manifest.permission.ACCESS_FINE_LOCATION
        ), 1)
    }

    override fun onInit(code: Int) { tts.language = Locale.getDefault() }

    private fun listen() {
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onResults(r: Bundle?) {
                val said = r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
                status.text = "You: $said"
                Thread {
                    val reply = try { brain.ask(said) } catch (e: Exception) { "Something went wrong: ${e.message}" }
                    runOnUiThread { status.text = "You: $said\n\nAI: $reply" }
                    tts.speak(reply, TextToSpeech.QUEUE_FLUSH, null, "reply")
                }.start()
            }
            override fun onError(e: Int) { status.text = "Didn't catch that, try again" }
            override fun onReadyForSpeech(p: Bundle?) { status.text = "Listening…" }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(p: Bundle?) {}
            override fun onEvent(t: Int, p: Bundle?) {}
        })
        recognizer.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM))
    }
}
