package ai.tinkered.jarvis

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.view.View
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.Future
import javax.net.ssl.HttpsURLConnection

// Retained only in memory across configuration changes. No Activity or View references.
private class Session {
    var key = ""
    var draft = ""
    var transcript = "JARVIS: Merhaba. Saat, tarih veya pil durumunu sorabilirsin. Serbest sohbet için AI ayarlarını aç.\n\n"
    val history = mutableListOf<Pair<String, String>>()
}

class MainActivity : Activity(), TextToSpeech.OnInitListener {
    private var session = Session()
    private val tr = Locale.forLanguageTag("tr-TR")
    private lateinit var transcript: TextView
    private lateinit var status: TextView
    private lateinit var input: EditText
    private lateinit var scroll: ScrollView
    private lateinit var send: Button
    private lateinit var listen: Button
    private lateinit var settings: Button
    private lateinit var tts: TextToSpeech
    private var speech: SpeechRecognizer? = null
    private var ttsReady = false
    private var voice = true
    private var busy = false
    private var listening = false
    private var foreground = false
    @Volatile private var requestId = 0
    private var pendingRequest: Future<*>? = null
    private var listeningId = 0
    @Volatile private var destroyed = false
    private val connectionLock = Any()
    private var activeConnection: HttpsURLConnection? = null
    private val worker = Executors.newSingleThreadExecutor()
    private var endpoint = "https://api.openai.com/v1/chat/completions"
    private var model = "gpt-4o-mini"
    private var apiKey: String
        get() = session.key
        set(value) { session.key = value }
    private val history get() = session.history
    private val personality = """
        Sen Türkçe konuşan, JARVIS'ten esinlenen nazik, sakin ve hafif esprili bir asistansın.
        Kısa ve anlaşılır cevap ver. Film karakterinin gerçek yeteneklerine sahip olduğunu iddia etme.
        Telefonu, e-postaları, kameraları veya evi kontrol edemezsin; bu sohbetin dışında araçların yok.
        Yapmadığın bir işlemi yaptığını söyleme. Tıbbi teşhis koyma; acil durumda 112 öner.
        İzinsiz sistem erişimi veya silah kontrolüne yardımcı olma. Duyguları ses tonundan ölçtüğünü iddia etme.
    """.trimIndent()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session = lastNonConfigurationInstance as? Session ?: Session()
        val preferences = getSharedPreferences("assistant_settings", MODE_PRIVATE)
        endpoint = preferences.getString("endpoint", endpoint) ?: endpoint
        model = preferences.getString("model", model) ?: model
        voice = preferences.getBoolean("voice", true)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        window.statusBarColor = 0xff101820.toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            setBackgroundColor(0xff101820.toInt())
            // Keep controls outside system bars when targeting Android 15.
            setOnApplyWindowInsetsListener { v, insets ->
                v.setPadding(24 + insets.systemWindowInsetLeft, 24 + insets.systemWindowInsetTop,
                    24 + insets.systemWindowInsetRight, 24 + insets.systemWindowInsetBottom)
                insets
            }
        }
        fun label(text: String, size: Float) = TextView(this).apply {
            this.text = text; textSize = size; setTextColor(0xffe6f4ff.toInt())
        }
        root.addView(label("JARVIS · Android Asistan", 24f))
        root.addView(label("Mikrofon yalnızca Dinle ile açılır. Ses tanıma hizmeti sesi sunucuya gönderebilir. AI açıksa mesajlar seçtiğin hizmete gönderilir.", 13f))
        status = label(if (apiKey.isBlank()) "Temel komutlar hazır · AI bağlantısı kapalı" else "Hazır · AI anahtarı oturumda", 14f)
        root.addView(status)
        transcript = label(session.transcript, 17f)
        scroll = ScrollView(this).apply { addView(transcript) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        input = EditText(this).apply {
            hint = "Bir komut yaz…"; setTextColor(0xffe6f4ff.toInt())
            setHintTextColor(0xffb0bec5.toInt()); maxLines = 4
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setText(session.draft)
            setSelection(text.length)
        }
        root.addView(input)
        send = Button(this).apply { text = "Gönder"; setOnClickListener { submit(input.text.toString()) } }
        root.addView(send)
        val row = LinearLayout(this)
        listen = Button(this).apply { text = "Dinle"; setOnClickListener { requestListening() } }
        settings = Button(this).apply { text = "AI ayarları"; setOnClickListener { showSettings() } }
        row.addView(listen, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(settings, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(row)
        root.addView(Button(this).apply {
            text = "Sohbeti temizle"
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity).setTitle("Sohbeti temizle?")
                    .setMessage("Ekrandaki metin ve bu oturumun AI bağlamı silinir. Hizmete daha önce gönderilen veriler oradan silinmez.")
                    .setNegativeButton("Vazgeç", null)
                    .setPositiveButton("Temizle") { _, _ ->
                        cancelRequest()
                        ++listeningId; speech?.cancel(); listening = false; controls()
                        tts.stop(); history.clear(); input.text.clear()
                        session.draft = ""; session.transcript = ""; transcript.text = ""
                        status.text = "Sohbet temizlendi"
                    }.show()
            }
        })
        root.addView(Switch(this).apply {
            text = "Yanıtları seslendir"; setTextColor(0xffe6f4ff.toInt()); isChecked = voice
            setOnCheckedChangeListener { _, checked ->
                voice = checked
                preferences.edit().putBoolean("voice", checked).apply()
                if (!checked && ::tts.isInitialized) tts.stop()
            }
        })
        setContentView(root)
        tts = TextToSpeech(this, this)
    }

    override fun onInit(result: Int) {
        if (destroyed || !::tts.isInitialized) return
        if (result == TextToSpeech.SUCCESS) {
            val supported = tts.setLanguage(tr)
            ttsReady = supported != TextToSpeech.LANG_MISSING_DATA && supported != TextToSpeech.LANG_NOT_SUPPORTED
            tts.setSpeechRate(0.95f)
        }
        if (!ttsReady) status.text = "Türkçe ses motoru yok; yazılı yanıtlar çalışır."
    }

    private fun append(who: String, text: String) {
        transcript.append("$who: $text\n\n")
        if (transcript.length() > 24000) transcript.text = transcript.text.takeLast(24000)
        session.transcript = transcript.text.toString()
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun answer(text: String) {
        append("JARVIS", text)
        if (foreground && voice && ttsReady) tts.speak(text.take(3500), TextToSpeech.QUEUE_FLUSH, null, "reply")
    }

    private fun controls() {
        send.isEnabled = !busy && !listening
        listen.isEnabled = !busy && !listening
        settings.isEnabled = !busy && !listening
    }

    private fun submit(raw: String) {
        val text = raw.trim()
        if (text.isEmpty() || busy || listening) return
        if (text.length > 4000) { status.text = "Lütfen mesajı 4000 karakterden kısa tut."; return }
        input.text.clear()
        append("Sen", text)
        val local = when (CommandRouter.parse(text)) {
            LocalCommand.TIME -> "Saat ${SimpleDateFormat("HH:mm", tr).format(Date())}."
            LocalCommand.DATE -> SimpleDateFormat("d MMMM yyyy, EEEE", tr).format(Date())
            LocalCommand.BATTERY -> battery()
            LocalCommand.HELP -> "Saat, tarih ve pil durumunu söyleyebilirim. AI bağlantısıyla sohbet edebiliriz. Henüz akıllı ev veya takvim kontrolüm yok."
            null -> null
        }
        if (local != null) { answer(local); return }
        if (apiKey.isBlank()) {
            answer("Serbest sohbet için AI ayarlarında HTTPS adresi, model ve API anahtarı gir. Şimdilik saat, tarih ve pil komutları kullanılabilir.")
            return
        }
        busy = true; controls(); status.text = "Yanıt bekleniyor…"
        val thisRequest = ++requestId
        val url = endpoint; val key = apiKey; val chosenModel = model
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", personality))
        history.takeLast(12).forEach { (role, content) -> messages.put(JSONObject().put("role", role).put("content", content)) }
        messages.put(JSONObject().put("role", "user").put("content", text))
        pendingRequest = worker.submit {
            val result = runCatching { chat(url, key, chosenModel, messages, thisRequest) }
            runOnUiThread {
                if (!destroyed && thisRequest == requestId) {
                    busy = false; controls()
                    result.onSuccess { reply ->
                        history.add("user" to text); history.add("assistant" to reply)
                        while (history.size > 12) history.removeAt(0)
                        status.text = "Hazır · AI bağlantısı açık"; answer(reply)
                    }.onFailure { error ->
                        if (input.text.isBlank()) { input.setText(text); input.setSelection(input.text.length) }
                        status.text = "Bağlantı başarısız"
                        val detail = when (error) {
                            is ChatFailure -> error.message ?: "Hizmet yanıtı işlenemedi."
                            is java.net.SocketTimeoutException -> "Bağlantı zaman aşımına uğradı; tekrar deneyebilirsin."
                            is java.net.UnknownHostException -> "Hizmet adresi bulunamadı. Adresi ve internet bağlantını kontrol et."
                            is javax.net.ssl.SSLException -> "Güvenli bağlantı kurulamadı. Sertifika denetimi kapatılmadı."
                            else -> "Yanıt alınamadı. İnterneti ve AI ayarlarını kontrol et."
                        }
                        answer(detail + " Temel komutlar çalışmaya devam eder.")
                    }
                }
            }
        }
    }

    private fun battery(): String {
        val state = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return "Pil bilgisi alınamadı."
        val level = state.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = state.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return "Pil bilgisi alınamadı."
        return "Pil seviyesi yüzde ${level * 100 / scale}."
    }

    private fun chat(url: String, key: String, model: String, messages: JSONArray, id: Int): String {
        val connection = URL(url).openConnection() as HttpsURLConnection
        synchronized(connectionLock) {
            if (destroyed || id != requestId || Thread.currentThread().isInterrupted) {
                connection.disconnect()
                throw ChatFailure("İstek iptal edildi.")
            }
            activeConnection = connection
        }
        try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15000; connection.readTimeout = 45000
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer $key")
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            val body = JSONObject().put("model", model).put("messages", messages)
                .put("max_tokens", 600).put("stream", false).toString()
            if (id != requestId || Thread.currentThread().isInterrupted) throw ChatFailure("İstek iptal edildi.")
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            if (code !in 200..299) throw ChatFailure(when (code) {
                401, 403 -> "Hizmet erişimi reddetti (HTTP $code). API anahtarını ve hesap izinlerini kontrol et."
                429 -> "Hizmet kota veya hız sınırına ulaştı (HTTP 429). Hesabını kontrol et veya daha sonra dene."
                in 500..599 -> "AI hizmetinde sunucu hatası var (HTTP $code)."
                else -> "Hizmet isteği kabul etmedi (HTTP $code). Adres, model ve hizmet uyumluluğunu kontrol et."
            })
            val data = connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val buffer = CharArray(4096); val out = StringBuilder()
                while (true) {
                    if (id != requestId || Thread.currentThread().isInterrupted) throw ChatFailure("İstek iptal edildi.")
                    val count = reader.read(buffer)
                    if (count == -1) break
                    out.append(buffer, 0, count)
                    if (out.length > 1000000) throw ChatFailure("Hizmet yanıtı boyut sınırını aştı.")
                }
                out.toString()
            }
            val message = runCatching {
                JSONObject(data).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
            }.getOrNull()
            val reply = (message?.opt("content") as? String)?.trim()
            if (reply.isNullOrEmpty()) throw ChatFailure("Hizmet beklenen metin yanıtını döndürmedi. Chat Completions uyumluluğunu kontrol et.")
            return reply.take(12000)
        } finally {
            synchronized(connectionLock) {
                if (activeConnection === connection) activeConnection = null
            }
            connection.disconnect()
        }
    }

    private class ChatFailure(message: String) : Exception(message)

    private fun cancelRequest() {
        ++requestId
        pendingRequest?.cancel(true); pendingRequest = null
        val connection = synchronized(connectionLock) { activeConnection.also { activeConnection = null } }
        // Disconnect may involve I/O: do not block Android's UI thread.
        if (connection != null) Thread { connection.disconnect() }.start()
        if (busy) status.text = "İstek iptal edildi; gerekirse komutunu yeniden gönder."
        busy = false; controls()
    }

    private fun showSettings() {
        val fields = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 8, 24, 8) }
        val url = EditText(this).apply { hint = "HTTPS chat/completions adresi"; setText(endpoint); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI }
        val name = EditText(this).apply { hint = "Model adı"; setText(model) }
        val key = EditText(this).apply { hint = "API anahtarı (yalnızca bu oturum)"; setText(apiKey); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        fields.addView(TextView(this).apply { text = "Yalnızca güvendiğin bir HTTPS hizmeti kullan. Anahtar ve sohbet bu adrese gider. Kullanım ücretli olabilir. Adres, model ve ses tercihi cihazda kaydedilir. Anahtar ve sohbet yalnızca bellekte tutulur; ekran döndürmede korunur, oturum veya süreç bitince silinir. Dağıtım için sunucu tarafı kimlik doğrulama gerekir." })
        fields.addView(url); fields.addView(name); fields.addView(key)
        val dialog = AlertDialog.Builder(this).setTitle("AI bağlantısı").setView(fields)
            .setNegativeButton("Vazgeç", null)
            .setNeutralButton("Bağlantıyı kapat") { _, _ -> apiKey = ""; history.clear(); status.text = "AI bağlantısı kapalı" }
            .setPositiveButton("Uygula", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val candidate = url.text.toString().trim()
                val valid = runCatching { URL(candidate).let { it.protocol == "https" && it.host.isNotBlank() && it.userInfo == null } }.getOrDefault(false)
                if (!valid || name.text.isBlank()) {
                    Toast.makeText(this, "Geçerli bir HTTPS adresi ve model adı gerekli.", Toast.LENGTH_LONG).show()
                } else {
                    endpoint = candidate; model = name.text.toString().trim(); apiKey = key.text.toString().trim()
                    getSharedPreferences("assistant_settings", MODE_PRIVATE).edit()
                        .putString("endpoint", endpoint).putString("model", model).apply()
                    history.clear(); status.text = if (apiKey.isBlank()) "AI bağlantısı kapalı" else "AI ayarları alındı; bağlantı henüz denenmedi"
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }

    private fun requestListening() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 10)
        } else startListening()
    }

    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (code == 10) {
            if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                if (foreground) startListening()
                else status.text = "Mikrofon izni verildi. Dinlemek için Dinle'ye yeniden bas."
            }
            else status.text = "Mikrofon izni verilmedi; yazılı komut kullanabilirsin."
        }
    }

    private fun startListening() {
        if (busy || listening || !foreground) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            status.text = "Ses tanıma hizmeti yok; yazılı komut kullanabilirsin."; return
        }
        tts.stop()
        ++listeningId
        speech?.destroy(); speech = null
        listening = true; controls()
        runCatching {
            speech = SpeechRecognizer.createSpeechRecognizer(this)
            val recognitionId = listeningId
            speech?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { if (foreground && listening && recognitionId == listeningId) status.text = "Dinliyorum…" }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { if (foreground && listening && recognitionId == listeningId) status.text = "Ses çözümleniyor…" }
                override fun onError(error: Int) {
                    if (!foreground || !listening || destroyed || recognitionId != listeningId) return
                    listening = false; controls()
                    status.text = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Konuşma anlaşılamadı; tekrar deneyebilirsin."
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Mikrofon izni gerekli; yazılı giriş kullanılabilir."
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Ses tanıma bağlantısı kurulamadı; yazılı giriş kullanılabilir."
                        else -> "Ses alınamadı ($error). Tekrar deneyebilir veya yazabilirsin."
                    }
                    speech?.destroy(); speech = null
                }
                override fun onResults(results: Bundle?) {
                    if (!foreground || !listening || destroyed || recognitionId != listeningId) return
                    listening = false; controls(); status.text = "Hazır"
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (!text.isNullOrBlank()) {
                        input.setText(text)
                        input.setSelection(input.text.length)
                        status.text = "Metni kontrol et; iletmek için Gönder'e bas."
                    }
                    speech?.destroy(); speech = null
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            speech?.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tr-TR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            })
        }.onFailure {
            ++listeningId; speech?.destroy(); speech = null
            listening = false; controls(); status.text = "Ses tanıma başlatılamadı; yazılı giriş kullanılabilir."
        }
    }

    override fun onResume() {
        super.onResume(); foreground = true
    }

    override fun onPause() {
        foreground = false
        ++listeningId
        session.draft = input.text.toString()
        listening = false
        super.onPause()
        speech?.cancel(); speech?.destroy(); speech = null; controls(); tts.stop()
    }

    override fun onStop() {
        cancelRequest()
        super.onStop()
    }

    @Suppress("DEPRECATION")
    override fun onRetainNonConfigurationInstance(): Any {
        session.draft = input.text.toString()
        session.transcript = transcript.text.toString()
        return session
    }

    override fun onDestroy() {
        destroyed = true; cancelRequest()
        if (!isChangingConfigurations) { apiKey = ""; history.clear() }
        speech?.destroy(); tts.stop(); tts.shutdown(); worker.shutdownNow()
        super.onDestroy()
    }
}
