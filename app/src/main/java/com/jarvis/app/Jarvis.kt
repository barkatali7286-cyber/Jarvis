package com.jarvis.app

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Icon
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.*
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.telecom.TelecomManager
import android.telephony.SmsManager
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

// =====================================================================
//  BHASHA (language) - Jarvis kis bhasha me sunega aur bolega
// =====================================================================
object Lang {
    @Volatile
    var code: String = "hi-IN"
    @Volatile
    var name: String = "Hindi"
}

// =====================================================================
//  SCREEN: Start / Stop / Settings / Test
// =====================================================================
class MainActivity : Activity() {
    private val perms: Array<String>
        get() = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.READ_CONTACTS)
            add(Manifest.permission.CALL_PHONE)
            add(Manifest.permission.SEND_SMS)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()

    private fun addButton(col: LinearLayout, label: String, f: () -> Unit) {
        val btn = Button(this)
        btn.text = label
        btn.setOnClickListener { f() }
        col.addView(btn)
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(48, 120, 48, 48)

        val info = TextView(this)
        info.text = "Jarvis\n\n1) Start Jarvis dabao aur permissions allow karo\n" +
            "2) Accessibility chalu karo (scroll, click, WhatsApp send ke liye)\n" +
            "3) Battery me Unrestricted karo\n\nPhir bolo: \"Jarvis\""
        info.textSize = 16f
        col.addView(info)

        addButton(col, "Start Jarvis") { startJarvis() }
        addButton(col, "Stop Jarvis (band karo)") {
            stopService(Intent(this, JarvisService::class.java))
            Toast.makeText(this, "Jarvis band ho gaya", Toast.LENGTH_SHORT).show()
        }
        addButton(col, "Accessibility chalu karo") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        addButton(col, "Battery: Unrestricted karo") {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }

        val et = EditText(this)
        et.hint = "Test: command likho (jaise: youtube kholo)"
        col.addView(et)
        addButton(col, "Test command chalao") {
            val r = try {
                Commands.run(this, et.text.toString().lowercase().trim())
            } catch (e: Exception) {
                "Error: " + e.message
            }
            Toast.makeText(this, r ?: "Local command nahi mila (AI se poochhna padega)", Toast.LENGTH_LONG).show()
        }

        val sv = ScrollView(this)
        sv.addView(col)
        setContentView(sv)
    }

    private fun startJarvis() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) proceed()
        else requestPermissions(perms, 1)
    }

    override fun onRequestPermissionsResult(code: Int, p: Array<out String>, r: IntArray) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) proceed()
        else Toast.makeText(this, "Mic permission zaroori hai", Toast.LENGTH_LONG).show()
    }

    private fun proceed() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            Toast.makeText(this, "'Display over other apps' ON karo, phir Start Jarvis dobara dabao", Toast.LENGTH_LONG).show()
            return
        }
        startForegroundService(Intent(this, JarvisService::class.java))
        Toast.makeText(this, "Jarvis chalu! Ab bolo: Jarvis", Toast.LENGTH_LONG).show()
    }
}

// =====================================================================
//  BACKGROUND SERVICE: sunta rehta hai, "Jarvis" sunte hi "Yes boss"
// =====================================================================
class JarvisService : Service(), TextToSpeech.OnInitListener {
    companion object {
        const val ACTION_STOP = "com.jarvis.app.STOP"
    }

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var ttsTag = ""
    private var rec: SpeechRecognizer? = null
    private val main = Handler(Looper.getMainLooper())
    private val history = ArrayList<Pair<String, String>>()
    private var afterSpeech: (() -> Unit)? = null
    private var alive = true
    private var started = false
    private var gen = 0
    private var wakeRun: Runnable? = null
    private var againRun: Runnable? = null
    private var dot: View? = null
    private var wl: PowerManager.WakeLock? = null
    private val wakeWords = listOf("jarvi", "जार्वि", "जार्व", "जारव", "जर्व", "जरव", "जार्भ")

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        if (i?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("jarvis", "Jarvis", NotificationManager.IMPORTANCE_LOW))
        val stopPi = PendingIntent.getService(
            this, 0,
            Intent(this, JarvisService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopAct = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
            "Jarvis band karo", stopPi
        ).build()
        val n = Notification.Builder(this, "jarvis")
            .setContentTitle("Jarvis sun raha hai")
            .setContentText("\"Jarvis\" bolo")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .addAction(stopAct)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(1, n)
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!started) {
            started = true
            showOverlayDot()
            val pmgr = getSystemService(PowerManager::class.java)
            wl = pmgr.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "jarvis:listen")
            wl?.acquire()
            tts = TextToSpeech(this, this)
        }
        return START_STICKY
    }

    // 1x1 chhoti overlay window: isse Android background se app kholne deta hai
    private fun showOverlayDot() {
        if (dot != null || !Settings.canDrawOverlays(this)) return
        try {
            val v = View(this)
            val lp = WindowManager.LayoutParams(
                1, 1,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            )
            getSystemService(WindowManager::class.java).addView(v, lp)
            dot = v
        } catch (e: Exception) {
        }
    }

    override fun onInit(status: Int) {
        val t = tts
        if (t != null && status == TextToSpeech.SUCCESS) {
            t.setSpeechRate(1.1f)
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) {
                    main.post { val f = afterSpeech; afterSpeech = null; f?.invoke() }
                }
                override fun onError(id: String?) { onDone(id) }
            })
            ttsReady = true
        }
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val g = if (h < 12) "सुप्रभात बॉस" else if (h < 17) "नमस्ते बॉस" else "शुभ संध्या बॉस"
        say("$g, जार्विस तैयार है") { again(100L) }
    }

    // text me Devanagari ho to Hindi awaaz, warna Lang wali awaaz
    private fun applyVoice(text: String) {
        val tag = if (text.any { it in '\u0900'..'\u097F' }) "hi-IN"
        else if (Lang.code.startsWith("hi")) "en-IN"
        else Lang.code
        if (tag != ttsTag) {
            tts?.setLanguage(Locale.forLanguageTag(tag))
            ttsTag = tag
        }
    }

    private fun say(t: String, then: (() -> Unit)?) {
        afterSpeech = then
        if (!ttsReady) {
            main.post { val f = afterSpeech; afterSpeech = null; f?.invoke() }
            return
        }
        applyVoice(t)
        tts?.speak(t, TextToSpeech.QUEUE_FLUSH, null, "u" + System.nanoTime())
    }

    private fun cancelWake() {
        wakeRun?.let { main.removeCallbacks(it) }
        wakeRun = null
    }

    private fun again(ms: Long) {
        againRun?.let { main.removeCallbacks(it) }
        val r = Runnable { listen(false) }
        againRun = r
        main.postDelayed(r, ms)
    }

    private fun first(b: Bundle?): String? =
        b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    private fun norm(s: String): String = s.lowercase()
        .replace("\u093C", "")
        .replace('\u0958', 'क').replace('\u0959', 'ख').replace('\u095A', 'ग').replace('\u095B', 'ज')
        .replace('\u095C', 'ड').replace('\u095D', 'ढ').replace('\u095E', 'फ').replace('\u095F', 'य')

    // wake word ke baad ka text ("" = sirf Jarvis bola, null = Jarvis bola hi nahi)
    private fun afterWake(low: String): String? {
        var best = -1
        for (w in wakeWords) {
            val i = low.indexOf(w)
            if (i >= 0 && (best < 0 || i < best)) best = i
        }
        if (best < 0) return null
        val sp = low.indexOf(' ', best)
        return if (sp < 0) "" else low.substring(sp + 1).trim()
    }

    private fun wakeNow() {
        cancelWake()
        gen++
        try { rec?.cancel(); rec?.destroy() } catch (e: Exception) {}
        rec = null
        say("Yes boss") { listen(true, false) }
    }

    // command=false: sirf "Jarvis" ka intezaar. command=true: aapka hukum sunna.
    // localOnly=true: sirf phone-command maanega (shor se AI na chale)
    private fun listen(command: Boolean, localOnly: Boolean = false) {
        if (!alive) return
        cancelWake()
        againRun?.let { main.removeCallbacks(it) }
        againRun = null
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            say("आवाज़ पहचान उपलब्ध नहीं है", null)
            return
        }
        val my = ++gen
        rec?.destroy()
        rec = SpeechRecognizer.createSpeechRecognizer(this)
        rec?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(t: Int, b: Bundle?) {}

            override fun onPartialResults(b: Bundle?) {
                if (my != gen || command) return
                val t = first(b) ?: return
                val after = afterWake(norm(t)) ?: return
                cancelWake()
                if (after.length <= 2) {
                    val r = Runnable { if (my == gen) wakeNow() }
                    wakeRun = r
                    main.postDelayed(r, 350L)
                }
            }

            override fun onError(e: Int) {
                if (my != gen) return
                if (command) Commands.pending = null
                val d = when (e) {
                    SpeechRecognizer.ERROR_NO_MATCH -> 150L
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> 150L
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> 1000L
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> 5000L
                    else -> 700L
                }
                again(d)
            }

            override fun onResults(b: Bundle?) {
                if (my != gen) return
                val t = first(b)
                if (t.isNullOrBlank()) {
                    if (command) Commands.pending = null
                    again(150L)
                    return
                }
                val low = norm(t)
                if (command) { handle(low, localOnly); return }
                val cmd = afterWake(low)
                if (cmd == null) { again(100L); return }
                if (cmd.length > 2) handle(cmd, false) else wakeNow()
            }
        })
        val rl = if (command) Lang.code else "hi-IN"
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, rl)
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1000L)
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 800L)
        rec?.startListening(i)
    }

    private fun safeRun(s: String): String? =
        try { Commands.run(this, s) } catch (e: Exception) { "ये काम नहीं हो पाया" }

    // kaam ke baad: bina "Jarvis" bole agla hukum sun lo
    private fun afterLocal() {
        if (Commands.pending != null) listen(true, false) else listen(true, true)
    }

    // "fir/phir/uske baad/karke" wale kai kaam ek ke baad ek
    private fun runSteps(steps: List<String>, idx: Int) {
        if (!alive) return
        if (idx >= steps.size) {
            listen(true, true)
            return
        }
        val r = safeRun(steps[idx])
        if (r == null) {
            say("ये काम मुझे समझ नहीं आया") { main.postDelayed({ runSteps(steps, idx + 1) }, 600L) }
            return
        }
        if (r == Commands.STOP) {
            say("ठीक है बॉस, मैं बंद हो रहा हूँ") { stopSelf() }
            return
        }
        if (Commands.pending != null) {
            say(r) { listen(true, false) }
            return
        }
        say(r) { main.postDelayed({ runSteps(steps, idx + 1) }, 1200L) }
    }

    private fun handle(low: String, localOnly: Boolean) {
        if (Commands.pending == null) {
            val steps = Commands.split(low)
            if (steps.size > 1) {
                runSteps(steps, 0)
                return
            }
        }
        val local = safeRun(low)
        if (local == Commands.STOP) {
            say("ठीक है बॉस, मैं बंद हो रहा हूँ") { stopSelf() }
            return
        }
        if (local != null) {
            say(local) { afterLocal() }
            return
        }
        if (localOnly) {
            again(100L)
            return
        }
        val langName = Lang.name
        Thread {
            val reply = try { Brain.ask(history, low, langName) } catch (e: Exception) { "इंटरनेट में दिक्कत लग रही है बॉस" }
            main.post {
                if (!alive) return@post
                history.add(low to reply)
                say(reply) { listen(true, false) }
            }
        }.start()
    }

    override fun onDestroy() {
        alive = false
        main.removeCallbacksAndMessages(null)
        try { rec?.destroy() } catch (e: Exception) {}
        try { tts?.shutdown() } catch (e: Exception) {}
        try {
            dot?.let { getSystemService(WindowManager::class.java).removeView(it) }
        } catch (e: Exception) {}
        try { if (wl?.isHeld == true) wl?.release() } catch (e: Exception) {}
        super.onDestroy()
    }
}

// =====================================================================
//  ACCESSIBILITY SERVICE: scroll, back, home, click, type
// =====================================================================
class JarvisAccess : AccessibilityService() {
    companion object {
        @Volatile
        var inst: JarvisAccess? = null
    }

    override fun onServiceConnected() { inst = this }
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onUnbind(i: Intent?): Boolean { inst = null; return super.onUnbind(i) }
    override fun onDestroy() { inst = null; super.onDestroy() }

    private fun swipe(x1: Float, y1: Float, x2: Float, y2: Float): Boolean {
        val p = Path()
        p.moveTo(x1, y1)
        p.lineTo(x2, y2)
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0L, 300L))
            .build()
        return dispatchGesture(g, null, null)
    }

    fun scroll(dir: String): String {
        val d = resources.displayMetrics
        val w = d.widthPixels.toFloat()
        val h = d.heightPixels.toFloat()
        val ok = when (dir) {
            "up" -> swipe(w / 2, h * 0.30f, w / 2, h * 0.80f)
            "left" -> swipe(w * 0.85f, h / 2, w * 0.15f, h / 2)
            "right" -> swipe(w * 0.15f, h / 2, w * 0.85f, h / 2)
            else -> swipe(w / 2, h * 0.80f, w / 2, h * 0.30f)
        }
        val name = when (dir) {
            "up" -> "ऊपर"
            "left" -> "बाएं"
            "right" -> "दाएं"
            else -> "नीचे"
        }
        return if (ok) "$name स्क्रॉल कर दिया" else "स्क्रॉल नहीं हो पाया"
    }

    fun global(action: Int, okMsg: String): String =
        if (performGlobalAction(action)) okMsg else "ये नहीं हो पाया"

    fun clickText(t: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val list = root.findAccessibilityNodeInfosByText(t)
        for (n in list) {
            var x: AccessibilityNodeInfo? = n
            while (x != null) {
                if (x.isClickable) return x.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                x = x.parent
            }
        }
        return false
    }

    fun clickAny(labels: List<String>): Boolean {
        for (l in labels) {
            if (clickText(l)) return true
        }
        return false
    }

    fun typeText(t: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val n = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, t)
        return n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }
}

// =====================================================
// =====================================================================
//  AI DIMAAG (free, bina key): Pollinations
// =====================================================================
object Brain {
    private const val URL_API = "https://text.pollinations.ai/openai"

    private val liveWords = listOf(
        "news", "khabar", "खबर", "samachar", "समाचार", "weather", "mausam", "मौसम", "score", "price", "rate",
        "bhav", "भाव", "latest", "live", "stock", "who won", "cricket", "क्रिकेट", "match"
    )

    private fun persona(lang: String): String {
        val l = if (lang == "Hindi") "देवनागरी हिंदी (ज़रूरी अंग्रेज़ी शब्द चल सकते हैं)" else lang
        return "तुम 'जार्विस' हो, मेरे सबसे करीबी, वफादार और भावुक दोस्त जैसे AI साथी। " +
            "मुझे हमेशा 'बॉस' बुलाओ। मेरे मूड को पहचानो: उदास हूँ तो प्यार से हौसला दो, खुश हूँ तो साथ में खुश हो, " +
            "थका हूँ तो ख्याल रखो। जवाब $l भाषा में दो। जवाब बोलकर सुनाया जाएगा, " +
            "इसलिए सिर्फ 1-2 छोटे वाक्य, कोई इमोजी, स्टार, लिस्ट या मार्कडाउन नहीं। " +
            "अगर मैं फोन चलाने का कोई काम बोलूँ जो तुम नहीं कर पाए, तो झूठ मत बोलो, सीधे बता दो।"
    }

    private fun msg(role: String, text: String) = JSONObject().put("role", role).put("content", text)

    private fun post(model: String, msgs: JSONArray): String? {
        val body = JSONObject().put("model", model).put("messages", msgs).put("max_tokens", 120)
        val conn = URL(URL_API).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        conn.connectTimeout = 8000
        conn.readTimeout = 25000
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = conn.responseCode
        if (code !in 200..299) return null
        val raw = conn.inputStream.bufferedReader().use { it.readText() }
        return try {
            JSONObject(raw).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim()
        } catch (e: Exception) {
            raw.trim()
        }
    }

    fun ask(history: List<Pair<String, String>>, text: String, lang: String): String {
        val msgs = JSONArray().put(msg("system", persona(lang)))
        for ((u, a) in history.takeLast(6)) {
            msgs.put(msg("user", u))
            msgs.put(msg("assistant", a))
        }
        msgs.put(msg("user", text))
        if (liveWords.any { text.contains(it) }) {
            val r = try { post("searchgpt", msgs) } catch (e: Exception) { null }
            if (r != null) return r
        }
        return post("openai", msgs) ?: "AI अभी व्यस्त है बॉस, थोड़ी देर बाद बोलिए"
    }
}
