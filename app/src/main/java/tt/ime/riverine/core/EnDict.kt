package tt.ime.riverine.core

import android.content.Context
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ln

/**
 * 英文詞庫：20 萬個最常用字連使用頻率（assets/en_freq.txt，已經由高到低排好）。
 *
 * 為咗慳記憶體，所有字砌埋做一條 [blob]，用 [starts] 記住每個字喺邊度開始，
 * 唔會有 20 萬個 String object。比對嗰陣直接喺 blob 上面行，唔使 substring。
 *
 * 唔會喺開機或者開輸入法嗰陣載入 —— 淨係第一次見到英文 view 先喺背景 thread 偷偷載，
 * 未載完就當冇提示，唔會令 UI lag。
 */
class EnDict private constructor(
    private val blob: String,
    private val starts: IntArray,      // size = n + 1
    private val weight: FloatArray     // ln(頻率)，越大越常用
) {

    val size: Int get() = starts.size - 1

    fun wordLength(i: Int) = starts[i + 1] - starts[i]
    fun word(i: Int): String = blob.substring(starts[i], starts[i + 1])
    fun charAt(i: Int, offset: Int): Char = blob[starts[i] + offset]
    fun weightAt(i: Int): Float = weight[i]

    /** 打字途中嘅提示。字表本身已經按常用度排好，所以行到夠數就收工。 */
    fun fromPrefix(prefix: String, limit: Int = 8): List<String> {
        if (prefix.length < 2) return emptyList()
        val p = prefix.lowercase()
        val out = ArrayList<String>(limit)
        for (i in 0 until size) {
            if (wordLength(i) <= p.length) continue
            if (!startsWith(i, p)) continue
            out.add(word(i))
            if (out.size >= limit) break
        }
        return out
    }

    // ---- 內部 -------------------------------------------------------------

    private fun startsWith(idx: Int, p: String): Boolean {
        val s = starts[idx]
        for (k in p.indices) if (blob[s + k] != p[k]) return false
        return true
    }

    companion object {
        private const val TAG = "EnDict"
        private const val ASSET = "en_freq.txt"

        @Volatile private var instance: EnDict? = null
        private val loading = AtomicBoolean(false)

        /** 未載完就回 null，caller 當冇提示就得 */
        fun get(): EnDict? = instance

        /**
         * 開英文 view 嗰陣叫。已經載咗、或者載緊，都會即刻返轉頭，唔會阻住 UI。
         */
        fun preloadAsync(ctx: Context) {
            if (instance != null) return
            if (!loading.compareAndSet(false, true)) return
            val app = ctx.applicationContext
            Thread({
                val t0 = System.currentTimeMillis()
                runCatching { instance = parse(app) }
                    .onSuccess { Log.i(TAG, "詞庫載好 ${instance?.size} 個字，用咗 ${System.currentTimeMillis() - t0}ms") }
                    .onFailure { Log.e(TAG, "詞庫載唔到", it); loading.set(false) }
            }, "tt-endict").apply { priority = Thread.MIN_PRIORITY }.start()
        }

        private fun parse(ctx: Context): EnDict =
            ctx.assets.open(ASSET).use { parse(it) }

        /** 一次過讀晒入 ByteArray 再自己掃，唔用 split / BufferedReader，快好多 */
        fun parse(input: java.io.InputStream): EnDict {
            val raw = input.readBytes()
            val n = countLines(raw)
            val letters = ByteArray(raw.size)
            val starts = IntArray(n + 1)
            val weight = FloatArray(n)

            var p = 0
            var w = 0
            var row = 0
            while (p < raw.size && row < n) {
                starts[row] = w
                // 字母
                while (p < raw.size && raw[p] != SPACE) { letters[w++] = raw[p++] }
                p++ // 食咗個空格
                // 頻率
                var freq = 0L
                while (p < raw.size && raw[p] >= ZERO && raw[p] <= NINE) {
                    freq = freq * 10 + (raw[p] - ZERO); p++
                }
                while (p < raw.size && raw[p] != NEWLINE) p++
                p++ // 食咗個換行
                weight[row] = ln(1.0 + freq).toFloat()
                row++
            }
            starts[row] = w

            val blob = String(letters, 0, w, Charsets.US_ASCII)
            return EnDict(blob, starts.copyOf(row + 1), weight.copyOf(row))
        }

        private const val SPACE = ' '.code.toByte()
        private const val NEWLINE = '\n'.code.toByte()
        private const val ZERO = '0'.code.toByte()
        private const val NINE = '9'.code.toByte()

        private fun countLines(raw: ByteArray): Int {
            var c = 0
            for (b in raw) if (b == NEWLINE) c++
            if (raw.isNotEmpty() && raw[raw.size - 1] != NEWLINE) c++
            return c
        }

        /** 由 (字, 頻率) 直接砌，test 同自訂詞庫用 */
        fun fromPairs(pairs: List<Pair<String, Long>>): EnDict {
            val sb = StringBuilder()
            val starts = IntArray(pairs.size + 1)
            val weight = FloatArray(pairs.size)
            for ((i, wf) in pairs.withIndex()) {
                starts[i] = sb.length
                sb.append(wf.first)
                weight[i] = ln(1.0 + wf.second).toFloat()
            }
            starts[pairs.size] = sb.length
            val blob = sb.toString()
            return EnDict(blob, starts, weight)
        }
    }
}
