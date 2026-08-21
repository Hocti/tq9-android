package hk.tq9.core

import android.content.Context
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
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
    private val weight: FloatArray,    // ln(頻率)，越大越常用
    private val buckets: Array<IntArray>,
    /** 淨係頭 [FUZZY_POOL] 個最常用字，按第一個字母分桶，畀 [looseFromGesture] 用 */
    private val bucketsByFirst: Array<IntArray>
) {

    val size: Int get() = starts.size - 1

    private fun wordLen(i: Int) = starts[i + 1] - starts[i]
    private fun word(i: Int) = blob.substring(starts[i], starts[i + 1])

    /**
     * 由一次 swipe 得出嘅字母序列砌字。首尾字母一定準，中間可能漏咗（掃得快）
     * 或者多咗（誤觸），所以只要求 [seq] 係個字嘅 subsequence。
     *
     * 排名 = 使用頻率 - 長度差罰分。即係話幾個字都夾到嗰陣，會揀返最常用嗰個。
     */
    fun fromGesture(seq: List<Char>, limit: Int = 8): List<String> =
        fromGesture(seq, "", "", limit)

    /**
     * 同上，但係連 caret 前後已經打咗嘅字母一齊計。
     *
     * 例：個欄本身係 `dis|y`（`|` = caret），滑 `p→l→a`，
     * 就淨係搵「`dis` 開頭、`y` 結尾、中間食得住 `pla`」嘅字 → `display`。
     * 冇 context（兩邊都係空）就同 [fromGesture] 一模一樣。
     */
    fun fromGesture(seq: List<Char>, prefix: String, suffix: String, limit: Int = 8): List<String> {
        if (seq.size < 2) return emptyList()
        // 精準路徑：首尾字母、成串都要夾（原本嗰套，滑得靚嗰陣好準）
        val strict = strictFromGesture(seq, prefix, suffix, limit)
        if (strict.isNotEmpty()) return strict
        // 精準路徑乜都揾唔到（多數係中間掃錯／多咗粒鍵，例如 v 畫咗喺 b 度），
        // 就淨係信第一個字母，喺頭 2 萬個常用字度揾個形狀夾得最似、又夠常用嘅頂上
        return looseFromGesture(seq, prefix, suffix, limit)
    }

    private fun strictFromGesture(seq: List<Char>, prefix: String, suffix: String, limit: Int): List<String> {
        val head = prefix.firstOrNull() ?: seq.first()
        val tail = suffix.lastOrNull() ?: seq.last()
        val bucket = bucketOf(head, tail) ?: return emptyList()
        val need = prefix.length + seq.size + suffix.length

        // 細個 top-N，唔使成個 bucket sort
        val bestIdx = IntArray(limit) { -1 }
        val bestScore = FloatArray(limit) { Float.NEGATIVE_INFINITY }

        for (idx in bucket) {
            val len = wordLen(idx)
            val delta = len - need
            if (delta < 0) continue
            if (prefix.isNotEmpty() && !startsWith(idx, prefix)) continue
            if (suffix.isNotEmpty() && !endsWith(idx, suffix)) continue
            if (!isSubsequence(seq, starts[idx] + prefix.length, starts[idx + 1] - suffix.length)) continue
            val penalty = delta + 0.35f * delta * delta
            val score = weight[idx] - penalty + if (delta == 0) 1.5f else 0f
            insert(bestIdx, bestScore, idx, score)
        }
        return bestIdx.filter { it >= 0 }.map { word(it) }
    }

    /**
     * 鬆路徑：唔要求成串字母都係 subsequence（容忍中間掃錯、多咗粒鍵），
     * 亦都唔要求尾字母準——淨係第一個字母（`prefix` 有就用佢，冇就用 `seq.first()`）。
     *
     * 常用度為主，形狀（LCS）淨係用嚟拉開排名：情願估一個形狀差好遠但成日見嘅字，
     * 都好過估一個形狀啱晒但冧巴掂都未見過嘅怪字。
     */
    private fun looseFromGesture(seq: List<Char>, prefix: String, suffix: String, limit: Int): List<String> {
        val head = prefix.firstOrNull() ?: seq.firstOrNull() ?: return emptyList()
        if (head !in 'a'..'z') return emptyList()
        val pool = bucketsByFirst[head - 'a']
        val need = prefix.length + suffix.length

        val bestIdx = IntArray(limit) { -1 }
        val bestScore = FloatArray(limit) { Float.NEGATIVE_INFINITY }

        for (idx in pool) {
            val len = wordLen(idx)
            if (len <= need) continue
            if (prefix.isNotEmpty() && !startsWith(idx, prefix)) continue
            if (suffix.isNotEmpty() && !endsWith(idx, suffix)) continue
            val bodyStart = starts[idx] + prefix.length
            val bodyEnd = starts[idx + 1] - suffix.length
            val shape = shapeScore(seq, bodyStart, bodyEnd)
            val lenDiff = abs((bodyEnd - bodyStart) - seq.size)
            // 常用度為主：weight 差幾多就要 shape 差成倍先追得返（見 EnDict 頭注解嗰句原則）
            val score = weight[idx] + 4.0f * shape - (0.6f * lenDiff + 0.2f * lenDiff * lenDiff)
            insert(bestIdx, bestScore, idx, score)
        }
        return bestIdx.filter { it >= 0 }.map { word(it) }
    }

    /** seq 同 blob[from,to) 有幾夾：LCS 長度 / seq 長度，唔使成個 seq 都係 subsequence 先有分 */
    private fun shapeScore(seq: List<Char>, from: Int, to: Int): Float {
        val n = seq.size
        val m = to - from
        if (m <= 0 || n == 0) return 0f
        var prev = IntArray(m + 1)
        var curr = IntArray(m + 1)
        for (i in 1..n) {
            val si = seq[i - 1]
            for (j in 1..m) {
                curr[j] = if (si == blob[from + j - 1]) prev[j - 1] + 1 else maxOf(prev[j], curr[j - 1])
            }
            val tmp = prev; prev = curr; curr = tmp
        }
        return prev[m].toFloat() / n
    }

    /** 打字途中嘅提示。字表本身已經按常用度排好，所以行到夠數就收工。 */
    fun fromPrefix(prefix: String, limit: Int = 8): List<String> {
        if (prefix.length < 2) return emptyList()
        val p = prefix.lowercase()
        val out = ArrayList<String>(limit)
        for (i in 0 until size) {
            if (wordLen(i) <= p.length) continue
            if (!startsWith(i, p)) continue
            out.add(word(i))
            if (out.size >= limit) break
        }
        return out
    }

    // ---- 內部 -------------------------------------------------------------

    private fun bucketOf(a: Char, b: Char): IntArray? {
        val i = a - 'a'
        val j = b - 'a'
        if (i !in 0..25 || j !in 0..25) return null
        return buckets[i * 26 + j]
    }

    /** seq 係咪 blob[from,to) 呢段嘅 subsequence（hello 只會滑過一次 l，重複字母自然食得住） */
    private fun isSubsequence(seq: List<Char>, from: Int, to: Int): Boolean {
        var k = 0
        var p = from
        while (p < to) {
            if (k < seq.size && blob[p] == seq[k]) k++
            p++
        }
        return k == seq.size
    }

    private fun startsWith(idx: Int, p: String): Boolean {
        val s = starts[idx]
        for (k in p.indices) if (blob[s + k] != p[k]) return false
        return true
    }

    private fun endsWith(idx: Int, p: String): Boolean {
        val e = starts[idx + 1] - p.length
        if (e < starts[idx]) return false
        for (k in p.indices) if (blob[e + k] != p[k]) return false
        return true
    }

    private fun insert(idxs: IntArray, scores: FloatArray, idx: Int, score: Float) {
        if (score <= scores[scores.size - 1]) return
        var pos = scores.size - 1
        while (pos > 0 && scores[pos - 1] < score) {
            scores[pos] = scores[pos - 1]
            idxs[pos] = idxs[pos - 1]
            pos--
        }
        scores[pos] = score
        idxs[pos] = idx
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
            }, "tq9-endict").apply { priority = Thread.MIN_PRIORITY }.start()
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
            val tmp = Array(676) { ArrayList<Int>() }
            for (i in 0 until row) {
                val a = blob[starts[i]] - 'a'
                val b = blob[starts[i + 1] - 1] - 'a'
                if (a in 0..25 && b in 0..25) tmp[a * 26 + b].add(i)
            }
            return EnDict(blob, starts.copyOf(row + 1), weight.copyOf(row),
                Array(676) { tmp[it].toIntArray() }, buildFirstBuckets(blob, starts, row))
        }

        /** 淨係頭 [FUZZY_POOL] 個字（file 已經由高到低排好），按第一個字母分桶 */
        private const val FUZZY_POOL = 20_000

        private fun buildFirstBuckets(blob: String, starts: IntArray, n: Int): Array<IntArray> {
            val cap = minOf(n, FUZZY_POOL)
            val tmp = Array(26) { ArrayList<Int>() }
            for (i in 0 until cap) {
                if (starts[i] >= starts[i + 1]) continue
                val a = blob[starts[i]] - 'a'
                if (a in 0..25) tmp[a].add(i)
            }
            return Array(26) { tmp[it].toIntArray() }
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
            val tmp = Array(676) { ArrayList<Int>() }
            for (i in pairs.indices) {
                if (starts[i] == starts[i + 1]) continue
                val a = blob[starts[i]] - 'a'
                val b = blob[starts[i + 1] - 1] - 'a'
                if (a in 0..25 && b in 0..25) tmp[a * 26 + b].add(i)
            }
            return EnDict(blob, starts, weight, Array(676) { tmp[it].toIntArray() },
                buildFirstBuckets(blob, starts, pairs.size))
        }
    }
}
