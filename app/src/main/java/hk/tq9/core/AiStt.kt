package hk.tq9.core

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.ByteArrayOutputStream

/**
 * 用 Gemini 做語音輸入（[Prefs.KEY_AI_STT_ON] 開咗就取代系統嗰個 `SpeechRecognizer`）。
 *
 * 分兩件事：[WavRecorder] 喺部機度錄一段 WAV，[transcribe] 將段錄音連
 * [Prefs.aiSttPrompt] 一齊掟上 Gemini，回返一段**淨係得結果**嘅字。
 *
 * **淨係 Gemini 做得**：段錄音要用 `inline_data` 呢個 Gemini 專用格式送上去，
 * 設定頁嗰套自訂 API 範本表達唔到，所以 [Prefs.aiSttOn] 見到「自訂 API」開咗
 * 就一律當閂咗，跌返落系統內置嗰個 STT。
 */
object AiStt {

    private val ui = Handler(Looper.getMainLooper())

    /** 錄超過呢個時間就自動收工（唔封頂嘅話一個 request 可以掟幾十 MB 上去） */
    const val MAX_RECORD_MS = 90_000L

    /**
     * [done] 一定喺 main thread 叫。[contextText] 會填入 prompt 嘅 `%text%`
     * （＝輸入框而家嘅內容，純粹畀個上下文 AI 知，唔會出現喺結果度）。
     */
    fun transcribe(ctx: Context, wav: ByteArray, contextText: String, done: (Result<String>) -> Unit) {
        val key = Prefs.aiApiKey(ctx)
        if (key.isBlank()) {
            done(Result.failure(IllegalStateException("尚未設定 API key")))
            return
        }
        val model = Prefs.aiModel(ctx)
        val template = Prefs.aiSttPrompt(ctx)
        // 冇 %text% 就當 user 特登唔要上下文，唔好好似改寫嗰邊咁貼落尾 ——
        // 貼落尾 AI 好易當咗嗰段字都係要轉錄嘅嘢，一併照抄出嚟
        val prompt = template.replace("%text%", contextText.ifBlank { "（空白）" })

        Thread {
            val r = runCatching { AiRewrite.callGemini(key, model, prompt, wav, "audio/wav") }
            ui.post { done(r) }
        }.start()
    }
}

/**
 * 錄一段 16kHz / mono / 16-bit PCM，[stop] 嗰陣包返個 WAV header 出嚟。
 *
 * 特登唔用 `MediaRecorder`：佢一定要寫落檔案，而且各家機出嚟嘅 AAC 容器唔一定
 * 啱 Gemini 收；WAV 係自己砌個 44 byte header 就得，冇得靠害。16kHz mono
 * 一秒 32KB，[AiStt.MAX_RECORD_MS] 封住 90 秒 ＝ 最多 ~2.9MB。
 */
class WavRecorder(private val sampleRate: Int = 16_000) {

    private var record: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    private val pcm = ByteArrayOutputStream()
    private var startedAt = 0L

    /** 錄咗幾耐（毫秒）—— 畫面上個計時器用 */
    val elapsedMs: Long get() = if (startedAt == 0L) 0L else SystemClock.elapsedRealtime() - startedAt

    /** 開咪。回 false = 開唔到（冇權限、俾人霸咗、部機唔支援呢個 format…） */
    @SuppressLint("MissingPermission") // call 之前 IME 先會問權限，見 TQ9InputMethodService.startAiStt
    fun start(): Boolean {
        if (running) return true
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) return false
        // buffer 開大四倍：IME 個 main thread 有時會頂住幾十毫秒（排版、畫鍵），
        // buffer 啱啱夠嘅話嗰陣就會掉 frame，錄出嚟一嗒嗒咁
        val bufSize = minBuf * 4
        val r = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize
            )
        }.getOrNull() ?: return false
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { r.release() }
            return false
        }
        pcm.reset()
        record = r
        running = true
        startedAt = SystemClock.elapsedRealtime()
        runCatching { r.startRecording() }.onFailure {
            running = false
            record = null
            runCatching { r.release() }
            return false
        }
        val buf = ByteArray(bufSize)
        thread = Thread {
            while (running) {
                val n = runCatching { r.read(buf, 0, buf.size) }.getOrDefault(-1)
                if (n > 0) synchronized(pcm) { pcm.write(buf, 0, n) } else if (n < 0) break
            }
        }.also { it.start() }
        return true
    }

    /** 收工，回一段完整 WAV。冇錄到嘢（太短、開唔到咪）就回 null。 */
    fun stop(): ByteArray? {
        val data = finish() ?: return null
        // 少過 0.3 秒實係撳錯／彈手，唔好嘥個 API call
        if (data.size < sampleRate * 2 * 3 / 10) return null
        return wav(data)
    }

    /** 唔要段錄音，淨係收返啲資源 */
    fun cancel() {
        finish()
    }

    private fun finish(): ByteArray? {
        if (!running && record == null) return null
        running = false
        // 一定要等收聲個 thread 行完先 release，唔係佢仲喺 read() 度企緊，
        // 一 release 就 use-after-free。join 封 500ms：正路一個 buffer 週期
        // （幾十毫秒）就返到，封頂純粹係唔想有咩意外就吊死喺 main thread 度。
        runCatching { thread?.join(500) }
        thread = null
        record?.let { r ->
            runCatching { if (r.recordingState == AudioRecord.RECORDSTATE_RECORDING) r.stop() }
            runCatching { r.release() }
        }
        record = null
        startedAt = 0L
        return synchronized(pcm) { pcm.toByteArray() }
    }

    /** 44 byte 標準 RIFF/WAVE header + 原封不動嘅 PCM */
    private fun wav(pcmData: ByteArray): ByteArray {
        val channels = 1
        val bits = 16
        val byteRate = sampleRate * channels * bits / 8
        val out = ByteArrayOutputStream(44 + pcmData.size)

        fun ascii(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
        fun le32(v: Int) {
            out.write(v and 0xFF); out.write((v ushr 8) and 0xFF)
            out.write((v ushr 16) and 0xFF); out.write((v ushr 24) and 0xFF)
        }
        fun le16(v: Int) { out.write(v and 0xFF); out.write((v ushr 8) and 0xFF) }

        ascii("RIFF"); le32(36 + pcmData.size); ascii("WAVE")
        ascii("fmt "); le32(16); le16(1); le16(channels)
        le32(sampleRate); le32(byteRate); le16(channels * bits / 8); le16(bits)
        ascii("data"); le32(pcmData.size)
        out.write(pcmData)
        return out.toByteArray()
    }
}
