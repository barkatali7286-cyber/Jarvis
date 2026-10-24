package com.jarvis.app

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.*
import android.provider.ContactsContract
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

// ---------------- Screen: permissions + start (no keys, no sign-up) ----------------
class MainActivity : Activity() {
    private val perms: Array<String>
        get() = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.READ_CONTACTS)
            add(Manifest.permission.CALL_PHONE)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(48, 160, 48, 48)
        val info = TextView(this)
        info.text = "Jarvis\n\nButton dabao, permissions allow karo, phir bolo: \"Jarvis\""
        info.textSize = 18f
        col.addView(info)
        val btn = Button(this)
        btn.text = "Start Jarvis"
        btn.setOnClickListener {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) proceed()
            else requestPermissions(perms, 1)
        }
        col.addView(btn)
        setContentView(col)
    }

    override fun onRequestPermissionsResult(code: Int, p: Array<out String>, r: IntArray) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) proceed()
        else Toast.makeText(this, "Mic permission zaroori hai", Toast.LENGTH_LONG).show()
    }

    private fun proceed() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            Toast.makeText(this, "'Display over other apps' ON karo, phir button dobara dabao", Toast.LENGTH_LONG).show()
            return
        }
        startForegroundService(Intent(this, JarvisService::class.java))
        Toast.makeText(this, "Jarvis chalu! Ab bolo: Jarvis", Toast.LENGTH_LONG).show()
    }
}

// ---------------- Background voice service ----------------
class JarvisService : Service(), TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    private var rec: SpeechRecognizer? = null
    private val main = Handler(Looper.getMainLooper())
    private val history = ArrayList<Pair<String, String>>()
    private var afterSpeech: (() -> Unit)? = null
    private var alive = true
    private val wakeWords = listOf("jarvis", "जार्विस", "जारविस", "जार्विश", "जर्विस", "जरविस", "जार्विज", "जारविज")

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("jarvis", "Jarvis", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "jarvis")
            .setContentTitle("Jarvis sun raha hai")
            .setContentText("\"Jarvis\" bolo")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(1, n)
        if (tts == null) tts = TextToSpeech(this, this)
        return START_STICKY
    }

    override fun onInit(status: Int) {
        tts?.language = Locale("hi", "IN")
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) {
                main.post { val f = afterSpeech; afterSpeech = null; f?.invoke() }
            }
            override fun onError(id: String?) { onDone(id) }
        })
        again(300L)
    }

    private fun say(t: String, then: (() -> Unit)?) {
        afterSpeech = then
        tts?.speak(t, TextToSpeech.QUEUE_FLUSH, null, "u" + System.nanoTime())
    }

    private fun again(ms: Long) { main.postDelayed({ listen(false) }, ms) }

    // command=false: sirf "Jarvis" shabd ka intezaar. command=true: aapka hukum sunna.
    private fun listen(command: Boolean) {
        if (!alive) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            say("आवाज़ पहचान उपलब्ध नहीं है", null)
            return
        }
        rec?.destroy()
        rec = SpeechRecognizer.createSpeechRecognizer(this)
        rec?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(b: Bundle?) {}
            override fun onEvent(t: Int, b: Bundle?) {}
            override fun onError(e: Int) { again(if (command) 1000L else 600L) }
            override fun onResults(b: Bundle?) {
                val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (t.isNullOrBlank()) { again(300L); return }
                if (command) { handle(t); return }
                val low = t.lowercase()
                val w = wakeWords.firstOrNull { low.contains(it) }
                if (w == null) { again(300L); return }
                val cmd = low.substringAfter(w).trim()
                if (cmd.length > 2) handle(cmd) else say("जी, बोलिए") { listen(true) }
            }
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
        rec?.startListening(i)
    }

    private fun handle(text: String) {
        val local = Commands.run(this, text.lowercase())
        if (local != null) { say(local) { again(300L) }; return }
        Thread {
            val reply = try { Brain.ask(history, text) } catch (e: Exception) { "इंटरनेट में दिक्कत लग रही है" }
            main.post {
                history.add(text to reply)
                say(reply) { again(300L) }
            }
        }.start()
    }

    override fun onDestroy() {
        alive = false
        main.removeCallbacksAndMessages(null)
        rec?.destroy()
        tts?.shutdown()
        super.onDestroy()
    }
}

// ---------------- Phone commands (no AI needed) ----------------
object Commands {
    private fun has(t: String, vararg w: String) = w.any { t.contains(it) }
    private fun go(c: Context, i: Intent) { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); c.startActivity(i) }

    private val fillers = setOf("call", "phone", "karo", "kar", "do", "lagao", "लगाओ", "कॉल", "करो", "को", "फोन",
        "कर", "दो", "open", "kholo", "खोलो", "खोल", "please", "ko", "app", "एप", "ऐप", "मुझे", "mujhe")
    private val alias = mapOf("व्हाट्सएप" to "whatsapp", "व्हाट्सऐप" to "whatsapp", "यूट्यूब" to "youtube",
        "कैमरा" to "camera", "इंस्टाग्राम" to "instagram", "गैलरी" to "gallery", "सेटिंग" to "settings",
        "सेटिंग्स" to "settings", "क्रोम" to "chrome", "मैप्स" to "maps")

    private fun rest(t: String) = t.split(" ").filter { it.isNotBlank() && it !in fillers }.joinToString(" ").trim()

    fun run(c: Context, t: String): String? {
        val words = t.split(" ")
        if (has(t, "torch", "flashlight", "टॉर्च", "टोर्च", "फ्लैशलाइट"))
            return torch(c, !has(t, "off", "band", "बंद", "बुझा", "bujha"))
        if (has(t, "volume", "वॉल्यूम", "आवाज")) {
            val up = has(t, "badha", "बढ़ा", "बढ़ाओ", "up", "zyada", "ज्यादा", "ज़्यादा")
            val am = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            repeat(3) {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                    if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            }
            return if (up) "आवाज़ बढ़ा दी" else "आवाज़ कम कर दी"
        }
        if (has(t, "kholo", "open", "खोलो", "खोल ") || t.endsWith("खोल")) return openApp(c, rest(t))
        if (has(t, "call", "कॉल", "फोन")) return call(c, rest(t))
        if ("time" in words || "टाइम" in words || "समय" in words || has(t, "कितने बजे", "kitne baje"))
            return "अभी " + SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date()) + " हुए हैं"
        if (has(t, "search", "सर्च", "google", "गूगल")) {
            val q = listOf("search", "सर्च", "google", "गूगल", "करो", "पर", "kar", "karo").fold(t) { a, w -> a.replace(w, "") }.trim()
            go(c, Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, q))
            return "$q खोज रहा हूँ"
        }
        return null
    }

    private fun torch(c: Context, on: Boolean): String = try {
        val cm = c.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.first { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
        cm.setTorchMode(id, on)
        if (on) "टॉर्च चालू कर दी" else "टॉर्च बंद कर दी"
    } catch (e: Exception) { "टॉर्च नहीं चल पाई" }

    private fun call(c: Context, name: String): String {
        if (name.isBlank()) return "किसे कॉल करना है?"
        if (c.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED)
            return "कॉन्टैक्ट की अनुमति दीजिए"
        val cur = c.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?",
            arrayOf("%$name%"),
            null
        )
        cur?.use {
            if (it.moveToFirst()) {
                val n = it.getString(0)
                val num = it.getString(1)
                go(c, Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(num))))
                return "$n को कॉल लगा रहा हूँ"
            }
        }
        return "$name नाम का कॉन्टैक्ट नहीं मिला"
    }

    private fun openApp(c: Context, raw: String): String {
        val name = (alias[raw] ?: raw).lowercase()
        if (name.isBlank()) return "कौन सा ऐप खोलूँ?"
        val pm = c.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val hit = pm.queryIntentActivities(main, 0).firstOrNull { it.loadLabel(pm).toString().lowercase().contains(name) }
            ?: return "$name ऐप नहीं मिला"
        val i = pm.getLaunchIntentForPackage(hit.activityInfo.packageName) ?: return "ऐप नहीं खुल पाया"
        go(c, i)
        return "$name खोल रहा हूँ"
    }
}

// ---------------- Free AI brain: Pollinations (no key, no sign-up) ----------------
object Brain {
    private const val URL_API = "https://text.pollinations.ai/openai"

    private const val PERSONA = "तुम 'जार्विस' हो, मेरे सबसे करीबी दोस्त जैसा इमोशनल AI साथी। तुम गर्मजोशी वाले, " +
        "परवाह करने वाले और थोड़े मज़ाकिया हो। मेरे मूड को समझो: उदास हूँ तो हौसला दो, खुश हूँ तो साथ में खुश हो। " +
        "हमेशा देवनागरी हिंदी में जवाब दो (ज़रूरी अंग्रेज़ी शब्द चल सकते हैं)। जवाब बोलकर सुनाया जाएगा, " +
        "इसलिए सिर्फ 1-3 छोटे वाक्य, कोई इमोजी, स्टार या मार्कडाउन नहीं।"

    private fun msg(role: String, text: String) = JSONObject().put("role", role).put("content", text)

    fun ask(history: List<Pair<String, String>>, text: String): String {
        val msgs = JSONArray().put(msg("system", PERSONA))
        for ((u, a) in history.takeLast(6)) { msgs.put(msg("user", u)); msgs.put(msg("assistant", a)) }
        msgs.put(msg("user", text))
        val body = JSONObject().put("model", "openai").put("messages", msgs).put("max_tokens", 200)
        val conn = URL(URL_API).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        conn.connectTimeout = 15000
        conn.readTimeout = 40000
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = conn.responseCode
        if (code == 429) return "थोड़ा रुककर फिर बोलिए"
        if (code !in 200..299) return "AI अभी जवाब नहीं दे पा रहा"
        val raw = conn.inputStream.bufferedReader().use { it.readText() }
        return try {
            JSONObject(raw).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim()


            
