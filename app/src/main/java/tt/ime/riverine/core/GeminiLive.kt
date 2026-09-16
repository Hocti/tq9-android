package tt.ime.riverine.core

import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Gemini Live 即時語音轉文字（WebSocket `BidiGenerateContent`）。
 *
 * 而家嗰套 AI STT 係整段 PCM 壓完先 `generateContent` 上傳；Live API 唔係嗰條
 * HTTP 路，一定要開 WebSocket，邊錄邊送 16 kHz PCM，先至用得官方嗰套
 * `gemini-3.8-live-extended-thinking`（同 `stt_demo.ts` 個 `ai.live.connect`：
 * AUDIO + thinkingLevel + Zephyr；字靠 `inputAudioTranscription`，唔好改 TEXT）。
 *
 * 語言碼／模型名／砌字有 `GeminiLiveTest`。
 */
object GeminiLive {

    const val DEFAULT_MODEL = "gemini-3.8-live-extended-thinking"
    const val MIME = "audio/pcm;rate=16000"

    /**
     * `gemini-3.8-live-extended-thinking` 一定要帶 `thinkingLevel`，唔帶 setup 就報錯。
     * 只得 LOW／MEDIUM／HIGH（唔支援 MINIMAL）。預設 LOW，同 `stt_demo.ts`。
     */
    enum class ThinkingLevel(val label: String) {
        LOW("低"),
        MEDIUM("中"),
        HIGH("高");

        companion object {
            val DEFAULT = LOW
            fun fromPref(raw: String?): ThinkingLevel =
                entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: DEFAULT
        }
    }

    private const val HOST = "generativelanguage.googleapis.com"
    private const val PATH =
        "/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"

    fun wsUrl(key: String): String {
        val q = URLEncoder.encode(key, Charsets.UTF_8.name())
        return "wss://$HOST$PATH?key=$q"
    }

    /** 範本要 `models/{id}`；設定頁有人會連前綴一齊貼 */
    fun modelName(raw: String): String {
        val m = raw.trim().ifBlank { DEFAULT_MODEL }
        return if (m.startsWith("models/")) m else "models/$m"
    }

    /**
     * 設定頁 `%lang%` 可以係一句人話（「廣東話…」）或者 BCP-47。
     * 睇落似語言碼就原封不動；唔似就中文鍵盤用粵語、其他用英語。
     */
    fun langCodes(hint: String, chinese: Boolean): List<String> {
        val t = hint.trim()
        if (BCP47.matches(t)) return listOf(t)
        return listOf(if (chinese) "yue-Hant-HK" else "en-US")
    }

    private val BCP47 = Regex("[A-Za-z]{2,3}(-[A-Za-z0-9]+){0,3}")

    fun setupJson(
        model: String,
        langCodes: List<String>,
        thinking: ThinkingLevel = ThinkingLevel.DEFAULT,
    ): String =
        JSONObject().put("setup", JSONObject().apply {
            put("model", modelName(model))
            // 同 `stt_demo.ts`：呢個 native audio 模型只收 AUDIO。
            // TEXT + inputAudioTranscription 會變成 AUDIO,TEXT，server 直接拒。
            put("generationConfig", JSONObject().apply {
                put("responseModalities", JSONArray().put("AUDIO"))
                put("mediaResolution", "MEDIA_RESOLUTION_MEDIUM")
                put("thinkingConfig", JSONObject().put("thinkingLevel", thinking.name))
                put("speechConfig", JSONObject().put("voiceConfig", JSONObject()
                    .put("prebuiltVoiceConfig", JSONObject().put("voiceName", "Zephyr"))))
            })
            put("contextWindowCompression", JSONObject()
                .put("triggerTokens", "104857")
                .put("slidingWindow", JSONObject().put("targetTokens", "52428")))
            // 咪掣係 push-to-talk：幾時開始／停由我哋送 activityStart／End，
            // 唔好等 server VAD 自己當你講完（講到一半就出 final）。
            put("realtimeInputConfig", JSONObject().put(
                "automaticActivityDetection", JSONObject().put("disabled", true)
            ))
            // 字由 inputTranscription 出，唔係 TEXT modality
            put("inputAudioTranscription", JSONObject().apply {
                put("languageCodes", JSONArray().also { a -> langCodes.forEach { a.put(it) } })
                put("mode", "SMART")
            })
        }).toString()

    fun audioJson(pcm: ByteArray): String =
        JSONObject().put("realtimeInput", JSONObject().put("audio", JSONObject()
            .put("data", Base64.getEncoder().encodeToString(pcm))
            .put("mimeType", MIME)
        )).toString()

    fun activityStartJson(): String =
        JSONObject().put("realtimeInput", JSONObject().put("activityStart", JSONObject())).toString()

    fun activityEndJson(): String =
        JSONObject().put("realtimeInput", JSONObject().put("activityEnd", JSONObject())).toString()

    sealed interface Msg {
        data object SetupComplete : Msg
        data class Interim(val text: String) : Msg
        data class Final(val text: String) : Msg
        data object TurnComplete : Msg
        data class Error(val message: String) : Msg
        data object Other : Msg
    }

    fun parse(json: String): Msg {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return Msg.Other
        o.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }?.let {
            return Msg.Error(it)
        }
        if (o.has("setupComplete")) return Msg.SetupComplete
        val sc = o.optJSONObject("serverContent") ?: return Msg.Other
        if (sc.optBoolean("turnComplete")) {
            transcript(sc, "inputTranscription")?.let { return Msg.Final(it) }
            return Msg.TurnComplete
        }
        transcript(sc, "interimInputTranscription")?.let { return Msg.Interim(it) }
        transcript(sc, "inputTranscription")?.let { return Msg.Final(it) }
        return Msg.Other
    }

    private fun transcript(sc: JSONObject, key: String): String? =
        sc.optJSONObject(key)?.optString("text")?.takeIf { it.isNotBlank() }

    /**
     * 一段段 final 砌返一句。有啲實作係累積（新句包住舊句），有啲係新一段。
     */
    fun merge(acc: String, incoming: String): String {
        val t = incoming.trim()
        if (t.isEmpty()) return acc
        if (acc.isEmpty()) return t
        if (t.startsWith(acc)) return t
        if (acc.endsWith(t)) return acc
        val glue =
            if (acc.last().code < 128 && t.first().code < 128 &&
                acc.last().isLetterOrDigit() && t.first().isLetterOrDigit()
            ) " " else ""
        return acc + glue + t
    }
}

/**
 * 一次錄音對應一個 Live session：開 WS → setup → 串 PCM → activityEnd → 收 final。
 *
 * [onInterim]／[onDropped]／[onDone] 都喺 **main thread** 叫。[onDone] 只會嚟一次。
 *
 * 錄音途中連線斷咗／server 報錯 → [onDropped]（**唔會**當完成）。鍵盤計時 overlay
 * 要留住直到使用者再撳停；[onDone] 淨係 [finish] 之後先至叫。
 */
class GeminiLiveSession(
    private val key: String,
    private val model: String,
    private val langCodes: List<String>,
    private val thinking: GeminiLive.ThinkingLevel = GeminiLive.ThinkingLevel.DEFAULT,
    private val onInterim: (String) -> Unit,
    private val onDropped: (String) -> Unit = {},
    private val onDone: (Result<String>) -> Unit,
) {
    @Volatile private var closed = false
    @Volatile private var setupDone = false
    @Volatile private var finishing = false
    @Volatile private var activityStarted = false
    @Volatile private var endSent = false
    private var finals = ""
    private val pending = ArrayList<ByteArray>()
    private val sendLock = Any()
    private var ws: WebSocket? = null
    private val ui = Handler(Looper.getMainLooper())

    fun connect() {
        val req = Request.Builder()
            .url(GeminiLive.wsUrl(key))
            .header("x-goog-api-key", key)
            .build()
        ws = http.newWebSocket(req, listener)
        ui.postDelayed(connectTimeout, CONNECT_MS)
    }

    /** 錄音 thread 逐嚿 PCM 掟入嚟；setup 未完就 queue，完成就即刻送 */
    fun sendPcm(pcm: ByteArray) {
        if (closed || pcm.isEmpty()) return
        synchronized(sendLock) {
            if (closed || endSent) return
            if (!setupDone) {
                pending.add(pcm.copyOf())
                return
            }
            sendAudio(pcm)
        }
    }

    val isClosed: Boolean get() = closed

    /**
     * 放手：flush 未送嘅 PCM，再送 activityEnd，等 final。
     * 回 false = 已經斷線／收咗檔，caller 唔好再等 [onDone]。
     */
    fun finish(): Boolean {
        if (closed) return false
        finishing = true
        if (setupDone) doFinish()
        ui.postDelayed(finishTimeout, FINISH_MS)
        return true
    }

    /** 唔要結果（太短／冇人聲／離開欄位） */
    fun cancel() {
        complete(null)
    }

    private fun doFinish() {
        synchronized(sendLock) {
            if (closed || endSent) return
            endSent = true
            ensureActivity()
            val queued = pending.toList()
            pending.clear()
            queued.forEach { sendAudio(it) }
            ws?.send(GeminiLive.activityEndJson())
        }
    }

    private fun ensureActivity() {
        if (activityStarted) return
        activityStarted = true
        ws?.send(GeminiLive.activityStartJson())
    }

    private fun sendAudio(pcm: ByteArray) {
        ensureActivity()
        ws?.send(GeminiLive.audioJson(pcm))
    }

    private fun onWsText(text: String) {
        if (closed) return
        when (val m = GeminiLive.parse(text)) {
            GeminiLive.Msg.SetupComplete -> {
                ui.removeCallbacks(connectTimeout)
                synchronized(sendLock) {
                    setupDone = true
                    val queued = pending.toList()
                    pending.clear()
                    if (queued.isNotEmpty()) queued.forEach { sendAudio(it) }
                }
                if (finishing) doFinish()
            }
            is GeminiLive.Msg.Interim -> {
                val shown = GeminiLive.merge(finals, m.text)
                ui.post { if (!closed) onInterim(shown) }
            }
            is GeminiLive.Msg.Final -> {
                finals = GeminiLive.merge(finals, m.text)
                ui.post { if (!closed) onInterim(finals) }
                if (finishing) complete(Result.success(finals))
            }
            GeminiLive.Msg.TurnComplete -> {
                if (finishing) complete(Result.success(finals))
            }
            is GeminiLive.Msg.Error -> failOrDrop(m.message)
            GeminiLive.Msg.Other -> {}
        }
    }

    /**
     * [result] = null → 靜靜收檔（cancel），唔叫 [onDone]。
     * 其餘一律 main thread 交一次，之後再開 WS 訊息都唔理。
     */
    private fun complete(result: Result<String>?) {
        if (closed) return
        closed = true
        ui.removeCallbacks(connectTimeout)
        ui.removeCallbacks(finishTimeout)
        runCatching { ws?.close(1000, "done") }
        ws = null
        if (result != null) ui.post { onDone(result) }
    }

    /** 錄音途中斷線：通知 [onDropped]，等使用者再撳先收 overlay */
    private fun drop(message: String) {
        if (closed) return
        closed = true
        ui.removeCallbacks(connectTimeout)
        ui.removeCallbacks(finishTimeout)
        runCatching { ws?.cancel() }
        ws = null
        ui.post { onDropped(message) }
    }

    private fun failOrDrop(message: String) {
        if (finishing) complete(Result.failure(IllegalStateException(message)))
        else drop(message)
    }

    private val connectTimeout = Runnable {
        failOrDrop("連不上 Gemini Live")
    }
    private val finishTimeout = Runnable {
        if (finals.isNotBlank()) complete(Result.success(finals))
        else complete(Result.failure(IllegalStateException("語音輸入逾時")))
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (closed) { webSocket.cancel(); return }
            webSocket.send(GeminiLive.setupJson(model, langCodes, thinking))
        }
        override fun onMessage(webSocket: WebSocket, text: String) = onWsText(text)
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) = onWsText(bytes.utf8())
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
            val msg = reason.ifBlank { "連線已關閉" }
            if (finishing) {
                if (finals.isNotBlank()) complete(Result.success(finals))
                else complete(Result.failure(IllegalStateException(msg)))
            } else {
                failOrDrop(msg)
            }
        }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val http = response?.let { "HTTP ${it.code}" }
            failOrDrop(t.message ?: http ?: "連線失敗")
        }
    }

    companion object {
        private const val CONNECT_MS = 15_000L
        private const val FINISH_MS = 12_000L
        private val http = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }
}
