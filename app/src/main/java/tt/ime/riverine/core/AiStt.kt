package tt.ime.riverine.core

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 用 AI 做語音輸入（[Prefs.KEY_AI_STT_ON] 開咗就取代系統嗰個 `SpeechRecognizer`）。
 *
 * 分三件事：[VoiceRecorder] 喺部機度錄一段 PCM、[VoiceActivity] 判斷段嘢究竟有冇人
 * 講過嘢、[transcribe] 壓縮完連 [Prefs.aiSttPrompt] 一齊掟上 AI，
 * 回返一段**淨係得結果**嘅字。
 *
 * 用 [Prefs.aiSttSlot] 嗰套 provider 設定：Gemini 用 `inline_data` 送 ADTS AAC；
 * 自訂 API 送 m4a，範本入面 `%audio%` 話事段錄音放喺邊（見 `AiRewrite.callCustom`）。
 * 範本冇 `%audio%` 嘅話 [Prefs.aiSttOn] 一律當閂咗，跌返落系統內置嗰個 STT。
 */
object AiStt {

    private val ui = Handler(Looper.getMainLooper())

    /** 錄超過呢個時間就自動收工（唔封頂嘅話一個 request 可以掟幾十 MB 上去） */
    const val MAX_RECORD_MS = 90_000L

    /**
     * [done] 一定喺 main thread 叫。[contextText] 會填入 prompt 嘅 `%text%`
     * （＝輸入框而家嘅內容，純粹畀個上下文 AI 知，唔會出現喺結果度），
     * [lang] 填入 `%lang%`（見 [Prefs.aiSttLang]）。
     *
     * **壓縮喺呢度做，唔喺 [VoiceRecorder.stop] 做** —— 一分鐘錄音 encode 落 AAC
     * 要成幾百毫秒，擺喺 `stop()` 就係擺咗喺 main thread 度，放手嗰下會窒。
     */
    fun transcribe(ctx: Context, clip: VoiceClip.Ready, contextText: String, lang: String,
                   done: (Result<String>) -> Unit) {
        val p = Prefs.aiProvider(ctx, Prefs.aiSttSlot(ctx))
        if (p.key.isBlank()) {
            done(Result.failure(IllegalStateException("尚未設定 API key")))
            return
        }
        // 冇 %text% 就當 user 特登唔要上下文，唔好好似改寫嗰邊咁貼落尾 ——
        // 貼落尾 AI 好易當咗嗰段字都係要轉錄嘅嘢，一併照抄出嚟
        val vars = mapOf("text" to contextText.ifBlank { "（空白）" }, "lang" to lang)
        val prompt = AiTemplate.fill(Prefs.aiSttPrompt(ctx), vars)
        val cacheDir = ctx.cacheDir

        Thread {
            val r = runCatching {
                if (p.useCustom) {
                    // Whisper 嗰類 API 唔收裸 ADTS（.aac），要 m4a
                    val (bytes, mime) = SttAudio.encodeM4a(clip.pcm, clip.sampleRate, cacheDir)
                    val audio = AiRewrite.Audio(bytes, mime, SttAudio.fileName(mime))
                    // multipart 嗰邊 %text% 係直接送出去嘅值，空白就留空，唔好送「（空白）」
                    AiRewrite.callCustom(p, prompt, vars + ("text" to contextText), audio)
                } else {
                    val (bytes, mime) = SttAudio.encode(clip.pcm, clip.sampleRate)
                    AiRewrite.callGemini(p.key, p.model, prompt, bytes, mime)
                }
            }
            ui.post { done(r) }
        }.start()
    }
}

/** [VoiceRecorder.stop] 嘅結果 */
sealed interface VoiceClip {
    /**
     * 錄到嘢，可以送上 Gemini。入面係**未壓縮**嘅 16-bit little-endian PCM ——
     * 壓縮留返俾 [AiStt.transcribe] 喺背景 thread 度做。
     */
    class Ready(val pcm: ByteArray, val sampleRate: Int) : VoiceClip

    /** 短過 [VoiceRecorder.MIN_CLIP_MS]，當撳錯／彈手 */
    data object TooShort : VoiceClip

    /** 夠長，但由頭到尾都係環境雜音，冇人講過嘢（見 [VoiceActivity]） */
    data object Silent : VoiceClip
}

/**
 * 錄一段 16kHz / mono / 16-bit PCM。
 *
 * 特登唔用 `MediaRecorder`：佢一定要寫落檔案，而且各家機出嚟嘅容器唔一定啱
 * Gemini 收。呢度自己攞原始 PCM，之後想包 WAV 定 encode 落 AAC 都由 [SttAudio] 話事。
 */
class VoiceRecorder(private val sampleRate: Int = 16_000) {

    private var record: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    private val pcm = ByteArrayOutputStream()
    private var startedAt = 0L

    /** 錄咗幾耐（毫秒）—— 畫面上個計時器用 */
    val elapsedMs: Long get() = if (startedAt == 0L) 0L else SystemClock.elapsedRealtime() - startedAt

    /** 開咪。回 false = 開唔到（冇權限、俾人霸咗、部機唔支援呢個 format…） */
    @SuppressLint("MissingPermission") // call 之前 IME 先會問權限，見 TTInputMethodService.startAiStt
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

    /**
     * 收工，順手篩走唔值得上傳嘅嘢：
     *
     *  - 短過 [MIN_CLIP_MS] → [VoiceClip.TooShort]（撳錯／彈手）
     *  - 由頭到尾都係雜音 → [VoiceClip.Silent]（撳咗但係冇出過聲）
     *
     * 兩樣都**唔會**叫 API。[VoiceActivity.hasSpeech] 行一次成段嘢係幾毫秒嘅事，
     * 擺喺 main thread 度冇問題；真正慢嗰步（壓縮）就留返喺 [AiStt.transcribe]。
     */
    fun stop(): VoiceClip {
        val data = finish() ?: return VoiceClip.TooShort
        val minBytes = sampleRate * 2 * MIN_CLIP_MS / 1000
        if (data.size < minBytes) return VoiceClip.TooShort
        if (!VoiceActivity.hasSpeech(data, sampleRate)) return VoiceClip.Silent
        return VoiceClip.Ready(data, sampleRate)
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

    companion object {
        /**
         * 短過咁就當撳錯，唔會上傳。
         *
         * 誤觸（撳落即刻放）通常 200ms 都唔夠；真係講嘢就算得一個字都要三幾百毫秒。
         */
        const val MIN_CLIP_MS = 400L
    }
}

/**
 * 判斷一段 PCM 究竟有冇人講過嘢（能量式 VAD）。
 *
 * 目的**唔係**做語音辨識，係擋走「撳親咪但係冇出聲」嗰啲 —— 一個 request
 * 掟幾百 KB 上去，等足幾秒，出返一句「（沒有聲音）」係好嘥。
 *
 * 做法：逐 20ms 一格計 RMS，
 *
 *  1. 全段最響嗰啲格都細過 [ABS_PEAK] → 由頭到尾都係靜，實冇人講過嘢；
 *  2. 響過「噪音底 × [SNR_RATIO]」嘅格夠 [MIN_VOICED_FRAMES] 格（＝ 160ms）
 *     先當有人講嘢。噪音底用第 20 百分位（唔用最細嗰格，一格靜音就搞亂晒），
 *     所以喺嘈同靜嘅地方都夾到。
 *
 * 特登**寧鬆莫緊**：漏咗一次擋唔到最多嘥個 API call，但係錯手擋咗人哋真係
 * 細聲講嗰句，user 就會覺得粒掣壞咗。
 */
object VoiceActivity {

    /** 全段最響都細過呢個 RMS 就當靜（16-bit 滿刻度 32768，即係大約 -40dBFS） */
    private const val ABS_PEAK = 320f

    /** 要響過噪音底幾多倍先當係講緊嘢（2.5 倍 ≈ +8dB） */
    private const val SNR_RATIO = 2.5f

    /** 夠幾多格（20ms 一格）「講緊嘢」先當真 */
    private const val MIN_VOICED_FRAMES = 8

    fun hasSpeech(pcm: ByteArray, sampleRate: Int): Boolean {
        val frameSamples = sampleRate / 50            // 20ms
        if (frameSamples <= 0) return true
        val frames = pcm.size / 2 / frameSamples
        if (frames < MIN_VOICED_FRAMES) return false

        val rms = FloatArray(frames)
        for (f in 0 until frames) {
            var sum = 0.0
            var i = f * frameSamples * 2
            val end = i + frameSamples * 2
            while (i < end) {
                // little-endian：高位 byte 照 sign-extend，shl 8 之後就係 signed 16-bit
                val v = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toDouble()
                sum += v * v
                i += 2
            }
            rms[f] = sqrt(sum / frameSamples).toFloat()
        }

        val sorted = rms.clone().also { it.sort() }
        fun pct(p: Float) = sorted[((frames - 1) * p).toInt().coerceIn(0, frames - 1)]
        if (pct(0.95f) < ABS_PEAK) return false

        val gate = max(pct(0.2f) * SNR_RATIO, ABS_PEAK * 0.6f)
        return rms.count { it >= gate } >= MIN_VOICED_FRAMES
    }
}

/**
 * 段錄音點包先掟得上 Gemini。
 *
 * 首選 **AAC-LC（ADTS）**：16kHz mono 24kbps，一分鐘得返 ~180KB，
 * 而未壓縮嘅 WAV 要 ~1.9MB —— 少咗成十倍，upload 快好多，
 * 而個 encode 本身係硬件／系統 codec 做，一分鐘錄音都係幾百毫秒嘅事。
 *
 * 容器係自己逐 frame 加 7 byte ADTS header 出嚟嘅**裸 AAC stream**，
 * 唔經 `MediaMuxer` 出 MP4／3GP —— 以前唔敢用 `MediaRecorder` 就係因為
 * 各家機出嚟嘅容器唔一定啱 Gemini 收，而 ADTS 係自己砌，每個 byte 都揸得住。
 *
 * 部機冇 AAC encoder（或者中途 fail）就跌返落 WAV，段嘢一樣送得上去，
 * 淨係大份啲 —— **唔可以**因為 encode 唔到就當今次語音輸入失敗。
 */
object SttAudio {

    /** 16kHz mono 講嘢，24kbps AAC-LC 已經好清楚，再高係嘥 upload */
    private const val BITRATE = 24_000
    private const val TIMEOUT_US = 10_000L

    /** 封頂：encoder 有咩意外唔出 EOS，都唔可以喺度等到天光 */
    private const val ENCODE_DEADLINE_MS = 20_000L

    /** ADTS header 入面個 sampling frequency index 表 */
    private val ADTS_RATES = intArrayOf(
        96000, 88200, 64000, 48000, 44100, 32000,
        24000, 22050, 16000, 12000, 11025, 8000, 7350
    )

    /** Gemini 用：回（ADTS AAC bytes、MIME type），encode 唔到就 WAV */
    fun encode(pcm: ByteArray, sampleRate: Int): Pair<ByteArray, String> {
        val aac = runCatching { encodeAac(pcm, sampleRate) }.getOrNull()
        return if (aac != null) aac to "audio/aac" else wav(pcm, sampleRate) to "audio/wav"
    }

    /**
     * 自訂 API（Whisper 嗰類）用：同一批 AAC frame 用 `MediaMuxer` 包做 **m4a**。
     * Whisper 收 m4a 但唔收裸 ADTS。`MediaMuxer` 淨係寫得落檔案，所以借 [tmpDir]
     * 寫個臨時檔再讀返出嚟。做唔到一樣跌返落 WAV。
     */
    fun encodeM4a(pcm: ByteArray, sampleRate: Int, tmpDir: File): Pair<ByteArray, String> {
        val m4a = runCatching {
            aacFrames(pcm, sampleRate)?.let { muxM4a(it, tmpDir) }
        }.getOrNull()
        return if (m4a != null) m4a to "audio/mp4" else wav(pcm, sampleRate) to "audio/wav"
    }

    /** multipart 上傳個檔名 —— Whisper 靠副檔名認格式 */
    fun fileName(mime: String) = when (mime) {
        "audio/mp4" -> "audio.m4a"
        "audio/aac" -> "audio.aac"
        else -> "audio.wav"
    }

    private class AacFrame(val bytes: ByteArray, val ptsUs: Long)

    /** encoder 出嚟嘅 frame（唔包 header）連埋個 output format（入面有 muxer 要嘅 csd-0） */
    private class AacStream(val format: MediaFormat, val frames: List<AacFrame>)

    /** 回 null = 呢部機做唔到，由 [encode] 跌返落 WAV */
    private fun encodeAac(pcm: ByteArray, sampleRate: Int): ByteArray? {
        val freqIdx = freqIndex(sampleRate)
        if (freqIdx < 0) return null
        val stream = aacFrames(pcm, sampleRate) ?: return null
        val out = ByteArrayOutputStream(stream.frames.sumOf { it.bytes.size + 7 })
        for (f in stream.frames) {
            out.write(adtsHeader(f.bytes.size, freqIdx))
            out.write(f.bytes)
        }
        return out.toByteArray()
    }

    private fun muxM4a(stream: AacStream, tmpDir: File): ByteArray? {
        val file = File.createTempFile("stt", ".m4a", tmpDir)
        try {
            val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            try {
                val track = muxer.addTrack(stream.format)
                muxer.start()
                val info = MediaCodec.BufferInfo()
                for (f in stream.frames) {
                    info.set(0, f.bytes.size, f.ptsUs, MediaCodec.BUFFER_FLAG_KEY_FRAME)
                    muxer.writeSampleData(track, ByteBuffer.wrap(f.bytes), info)
                }
                muxer.stop()
            } finally {
                runCatching { muxer.release() }
            }
            return file.readBytes().takeIf { it.isNotEmpty() }
        } finally {
            file.delete()
        }
    }

    /** 回 null = 呢部機做唔到 */
    private fun aacFrames(pcm: ByteArray, sampleRate: Int): AacStream? {
        if (pcm.isEmpty()) return null
        val codec = runCatching {
            MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        }.getOrNull() ?: return null

        return try {
            val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1)
                .apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE,
                        MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
                }
            codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            val frames = ArrayList<AacFrame>()
            var outFormat: MediaFormat? = null
            val info = MediaCodec.BufferInfo()
            val deadline = SystemClock.elapsedRealtime() + ENCODE_DEADLINE_MS
            var offset = 0
            var eosSent = false
            var eosSeen = false

            while (!eosSeen) {
                if (SystemClock.elapsedRealtime() > deadline) return null
                if (!eosSent) {
                    val inIdx = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx) ?: return null
                        buf.clear()
                        // 一定要偶數：16-bit sample 拆一半落兩個 buffer 就會爆音
                        val n = min(buf.capacity() and 1.inv(), pcm.size - offset)
                        if (n > 0) buf.put(pcm, offset, n)
                        // pts 要跟住樣本走，唔係 encoder 會當啲 frame 全部同一時間
                        val ptsUs = offset.toLong() * 1_000_000L / (sampleRate * 2)
                        eosSent = offset + n >= pcm.size
                        codec.queueInputBuffer(inIdx, 0, n, ptsUs,
                            if (eosSent) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                        offset += n
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    outFormat = codec.outputFormat
                } else if (outIdx >= 0) {
                    // CODEC_CONFIG 嗰嚿係 AudioSpecificConfig：ADTS header 已經包含晒
                    // 同樣嘅資料，m4a 就由 outputFormat 個 csd-0 帶，兩邊都唔好當 frame 寫
                    val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                    if (info.size > 0 && !isConfig) {
                        val buf = codec.getOutputBuffer(outIdx) ?: return null
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        val chunk = ByteArray(info.size)
                        buf.get(chunk)
                        frames.add(AacFrame(chunk, info.presentationTimeUs))
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) eosSeen = true
                }
            }
            val muxFormat = outFormat ?: return null
            if (frames.isEmpty()) null else AacStream(muxFormat, frames)
        } catch (_: Exception) {
            null
        } catch (_: OutOfMemoryError) {
            null
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
    }

    /** [ADTS_RATES] 入面搵返個 index，-1 = 呢個 sample rate 冇得寫落 ADTS header */
    internal fun freqIndex(sampleRate: Int) = ADTS_RATES.indexOf(sampleRate)

    /**
     * 7 byte ADTS header（AAC-LC、mono、冇 CRC）。[payload] = 呢個 frame 幾多 byte。
     *
     * `internal` 純粹係想 `SttAudioTest` 釘死啲 bit 點砌 —— 呢度砌錯一個 bit，
     * Gemini 只會回一句「聽唔到內容」，喺機上面查會查到死。
     */
    internal fun adtsHeader(payload: Int, freqIdx: Int): ByteArray {
        val len = payload + 7
        val profile = 2 // AAC-LC
        val chan = 1
        return byteArrayOf(
            0xFF.toByte(),                                            // syncword
            0xF1.toByte(),                                            // MPEG-4, Layer 0, 冇 CRC
            (((profile - 1) shl 6) or (freqIdx shl 2) or (chan shr 2)).toByte(),
            (((chan and 3) shl 6) or (len shr 11)).toByte(),
            ((len shr 3) and 0xFF).toByte(),
            (((len and 7) shl 5) or 0x1F).toByte(),                   // buffer fullness = 全開
            0xFC.toByte()
        )
    }

    /** 44 byte 標準 RIFF/WAVE header + 原封不動嘅 PCM（AAC encode 唔到先用） */
    private fun wav(pcmData: ByteArray, sampleRate: Int): ByteArray {
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
