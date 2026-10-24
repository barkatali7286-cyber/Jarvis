package com.jarvis.app

import android.Manifest
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityService
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : android.app.Activity() {
    private val perms = arrayOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.CALL_PHONE
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 160, 48, 48)
        }
        val info = TextView(this).apply {
            text = "Jarvis In Button Clicked"
            textSize = 18f
        }
        col.addView(info)
        val btn = Button(this).apply {
            text = "Start Jarvis"
            setOnClickListener {
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    if (!Settings.canDrawOverlays(this@MainActivity)) {
                        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                    } else {
                        startForegroundService(Intent(this@MainActivity, JarvisService::class.java))
                        Toast.makeText(this@MainActivity, "Jarvis chalu ho gaya hai!", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    requestPermissions(perms, 101)
                }
            }
        }
        col.addView(btn)
        setContentView(col)
    }
}

class JarvisService : AccessibilityService(), TextToSpeech.OnInitListener {

    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var isRunning = true
    private var cameraManager: CameraManager? = null
    private var cameraId: String? = null
    private val history = ArrayList<Pair<String, String>>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        tts = TextToSpeech(this, this)
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            cameraId = cameraManager?.cameraIdList?.get(0)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = android.app.PendingIntent.getActivity(this, 0, notificationIntent, android.app.PendingIntent.FLAG_IMMUTABLE)
        
        val notification = android.app.Notification.Builder(this, "jarvis_channel")
            .setContentTitle("Jarvis")
            .setContentText("Jarvis sun raha hai")
            .setSmallIcon(android.R.drawable.ic_menu_mic)
            .setContentIntent(pendingIntent)
            .build()
            
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, notification)
        }
        
        startListening()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale("hi", "IN")
        }
    }

    private fun startListening() {
        if (!isRunning) return
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                restartListening()
            }
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val command = matches[0].lowercase(Locale.ROOT)
                    handleCommand(command)
                } else {
                    restartListening()
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        try {
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun restartListening() {
        if (isRunning) {
            Handler(Looper.getMainLooper()).postDelayed({
                startListening()
            }, 500)
        }
    }

    private fun handleCommand(command: String) {
        if (command.contains("jarvis") || command.contains("jarvis")) {
            speak("Yes Boss") {
                val local = Commands.run(this, command)
                if (local != null) {
                    speak(local) { restartListening() }
                } else {
                    Thread {
                        val reply = Brain.ask(command, history)
                        history.add(Pair(command, reply))
                        if (history.size > 6) history.removeAt(0)
                        Handler(Looper.getMainLooper()).post {
                            speak(reply) { restartListening() }
                        }
                    }.start()
                }
            }
        } else {
            restartListening()
        }
    }

    private fun speak(text: String, onDone: (() -> Unit)? = null) {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                onDone?.let { Handler(Looper.getMainLooper()).post { it() } }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                onDone?.let { Handler(Looper.getMainLooper()).post { it() } }
            }
        })
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "uniqueId")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        speechRecognizer?.destroy()
        tts?.shutdown()
    }
}

object Commands {
    private val fillers = setOf("call", "phone", "number", "karo", "do", "open", "kholo", "chalao", "bajaao", "ka", "ki", "ko", "par", "mein", "wala", "wali")

    fun run(context: Context, text: String): String? {
        val words = text.split(" ")
        val t = words.filter { !fillers.contains(it) }.joinToString(" ")

        when {
            t.contains("youtube") -> {
                val intent = context.packageManager.getLaunchIntentForPackage("com.google.android.youtube")
                intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (intent != null) {
                    context.startActivity(intent)
                }
                return "YouTube khol diya hai boss."
            }
            t.contains("whatsapp") -> {
                val intent = context.packageManager.getLaunchIntentForPackage("com.whatsapp")
                intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (intent != null) {
                    context.startActivity(intent)
                }
                return "WhatsApp khol diya hai boss."
            }
            t.contains("torch on") || t.contains("light jalao") -> {
                try {
                    val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                    val id = cm.cameraIdList.getOrNull(0)
                    if (id != null) {
                        cm.setTorchMode(id, true)
                    }
                    return "Torch on kar di hai boss."
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            t.contains("torch off") || t.contains("light bujhaao") -> {
                try {
                    val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                    val id = cm.cameraIdList.getOrNull(0)
                    if (id != null) {
                        cm.setTorchMode(id, false)
                    }
                    return "Torch off kar di hai boss."
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            t.contains("time") || t.contains("samay") -> {
                val time = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date())
                return "Abhi samay $time ho raha hai boss."
            }
            t.contains("date") || t.contains("tareekh") -> {
                val date = SimpleDateFormat("dd MMMM yyyy", Locale.getDefault()).format(Date())
                return "Aaj ki tareekh $date hai boss."
            }
        }
        return null
    }
}

object Brain {
    private const val URL_API = "https://text.pollinations.ai/openai"
    private const val PERSONA = "Tum 'Jarvis' ho, mere sabhi kaam karne wale AI assistant. Hamesha chhote aur seedhe Hindi me jawab do."

    fun ask(msg: String, history: List<Pair<String, String>>): String {
        return try {
            val msgs = JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", PERSONA))
                for ((u, a) in history.takeLast(6)) {
                    put(JSONObject().put("role", "user").put("content", u))
                    put(JSONObject().put("role", "assistant").put("content", a))
                }
                put(JSONObject().put("role", "user").put("content", msg))
            }
            val body = JSONObject().put("model", "openai").put("messages", msgs)
            val conn = (URL(URL_API).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
                connectTimeout = 15000
                readTimeout = 40000
            }
            conn.outputStream.write(body.toString().toByteArray())
            if (conn.responseCode == 200) {
                val res = conn.inputStream.bufferedReader().readText()
                JSONObject(res).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
            } else {
                "Thoda fir se kijiye boss."
            }
        } catch (e: Exception) {
            "Network error ho gaya hai boss."
        }
    }
}
