package hk.tq9.swipe

import hk.tq9.core.EnDict
import kotlin.math.hypot
import kotlin.math.max

/**
 * 英文 swipe 認字：唔再逐格判斷「撳咗邊粒鍵」（嗰套 dwell/轉角 heuristic 已經放棄），
 * 改為 AOSP 手勢輸入嗰套概念嘅 Kotlin 版 —— 成條手指軌跡一次過同候選字嘅
 * 「理想路徑」（逐個字母嘅鍵中心連成線，重疊嘅字母收埋做一格）比對形狀＋位置，
 * 揀路徑夾得最貼、又夠常用嘅字（shape-writing / SHARK2 嗰個原理）。
 *
 * 候選字用「首尾字母」分桶做粗篩（同舊 [EnDict] 嘅設計一樣，「首尾字母一定準」），
 * 夾唔到就放寬做淨係信第一個字母嘅 fallback。
 */
class GestureDecoder(private val dict: EnDict) {

    companion object {
        /** 軌跡重新取樣做幾多點先比對（$1 recognizer 嗰套做法） */
        private const val RESAMPLE_N = 32
        /** 淨係頭幾多個字（已經按常用度排好）入 fallback pool，太多會拖慢又冧巴少見字 */
        private const val FUZZY_POOL = 20_000
        private const val SPATIAL_WEIGHT = 1f
        private const val ENDPOINT_WEIGHT = 0.6f
        private const val LM_WEIGHT = 0.16f
    }

    private val byFirstLast: Array<IntArray>
    private val byFirst: Array<IntArray>

    init {
        val tmpFL = Array(676) { ArrayList<Int>() }
        val tmpF = Array(26) { ArrayList<Int>() }
        for (i in 0 until dict.size) {
            val len = dict.wordLength(i)
            if (len < 2) continue
            val a = dict.charAt(i, 0) - 'a'
            val b = dict.charAt(i, len - 1) - 'a'
            if (a !in 0..25 || b !in 0..25) continue
            tmpFL[a * 26 + b].add(i)
            if (i < FUZZY_POOL) tmpF[a].add(i)
        }
        byFirstLast = Array(676) { tmpFL[it].toIntArray() }
        byFirst = Array(26) { tmpF[it].toIntArray() }
    }

    /**
     * @param path 原始軌跡，x,y 交替（[hk.tq9.ime.KeyboardBaseView] 個 tracker 出嗰種格式）
     * @param keyCenter 邊個字母個鍵中心喺邊 —— 用嚟砌「理想路徑」
     * @param keyWidth 一粒鍵大概幾闊，用嚟將距離正規化（唔同螢幕、唔同鍵盤大細都夾到）
     * @param prefix caret 前面已經打咗嘅字母（`dis|y` 滑 `pla` 嘅 `dis`）
     * @param suffix caret 後面已經打咗嘅字母（`dis|y` 嘅 `y`）
     */
    fun decode(
        path: List<Float>,
        keyCenter: (Char) -> Pair<Float, Float>?,
        keyWidth: Float,
        prefix: String = "",
        suffix: String = "",
        limit: Int = 8
    ): List<String> {
        if (path.size < 4 || keyWidth <= 0f) return emptyList()
        val userPath = resample(path.toFloatArray(), RESAMPLE_N)

        val headChar = prefix.firstOrNull()
            ?: nearestKey(path[0], path[1], keyCenter)
            ?: return emptyList()
        val tailChar = suffix.lastOrNull()
            ?: nearestKey(path[path.size - 2], path[path.size - 1], keyCenter)
            ?: return emptyList()

        val strictPool = bucketOf(headChar, tailChar)
        var result = score(strictPool, userPath, keyCenter, keyWidth, prefix, suffix, limit)
        if (result.isEmpty() && headChar in 'a'..'z') {
            // 精準桶夾唔到 → 淨係信第一個字母，喺常用字入面搵形狀最似嗰個
            result = score(byFirst[headChar - 'a'], userPath, keyCenter, keyWidth, prefix, suffix, limit)
        }
        return result
    }

    private fun bucketOf(a: Char, b: Char): IntArray {
        val i = a - 'a'; val j = b - 'a'
        if (i !in 0..25 || j !in 0..25) return IntArray(0)
        return byFirstLast[i * 26 + j]
    }

    private fun score(
        pool: IntArray,
        userPath: FloatArray,
        keyCenter: (Char) -> Pair<Float, Float>?,
        keyWidth: Float,
        prefix: String,
        suffix: String,
        limit: Int
    ): List<String> {
        val bestIdx = IntArray(limit) { -1 }
        val bestScore = FloatArray(limit) { Float.NEGATIVE_INFINITY }
        val n = RESAMPLE_N

        for (idx in pool) {
            val len = dict.wordLength(idx)
            val bodyStart = prefix.length
            val bodyEnd = len - suffix.length
            if (bodyEnd - bodyStart < 1) continue
            if (prefix.isNotEmpty() && !matchesPrefix(idx, prefix)) continue
            if (suffix.isNotEmpty() && !matchesSuffix(idx, suffix, len)) continue

            val ideal = idealPath(idx, bodyStart, bodyEnd, keyCenter) ?: continue
            val idealResampled = resample(ideal, n)

            val spatial = pathCost(userPath, idealResampled) / keyWidth
            val endCost = (
                hypot((userPath[0] - idealResampled[0]).toDouble(), (userPath[1] - idealResampled[1]).toDouble()) +
                hypot(
                    (userPath[(n - 1) * 2] - idealResampled[(n - 1) * 2]).toDouble(),
                    (userPath[(n - 1) * 2 + 1] - idealResampled[(n - 1) * 2 + 1]).toDouble()
                )
            ).toFloat() / keyWidth

            val geoScore = -(spatial + ENDPOINT_WEIGHT * endCost) * SPATIAL_WEIGHT
            val score = geoScore + LM_WEIGHT * dict.weightAt(idx)
            insert(bestIdx, bestScore, idx, score)
        }
        return bestIdx.filter { it >= 0 }.map { dict.word(it) }
    }

    private fun matchesPrefix(idx: Int, prefix: String): Boolean {
        for (k in prefix.indices) if (dict.charAt(idx, k) != prefix[k]) return false
        return true
    }

    private fun matchesSuffix(idx: Int, suffix: String, len: Int): Boolean {
        val offset = len - suffix.length
        if (offset < 0) return false
        for (k in suffix.indices) if (dict.charAt(idx, offset + k) != suffix[k]) return false
        return true
    }

    /** 逐個字母嘅鍵中心連成線，重複嘅字母（`hello` 嘅 `ll`）淨係算一格 */
    private fun idealPath(idx: Int, from: Int, to: Int, keyCenter: (Char) -> Pair<Float, Float>?): FloatArray? {
        val pts = ArrayList<Float>((to - from) * 2)
        var last = ' '
        var have = false
        for (k in from until to) {
            val c = dict.charAt(idx, k)
            if (have && c == last) continue
            val p = keyCenter(c) ?: return null
            pts.add(p.first); pts.add(p.second)
            last = c; have = true
        }
        if (pts.isEmpty()) return null
        return pts.toFloatArray()
    }

    /** 按弧長重新取樣做固定 [n] 個點，等唔同長度嘅軌跡可以逐點比對 */
    private fun resample(pts: FloatArray, n: Int): FloatArray {
        val m = pts.size / 2
        val out = FloatArray(n * 2)
        if (m == 0) return out
        if (m == 1) {
            for (i in 0 until n) { out[i * 2] = pts[0]; out[i * 2 + 1] = pts[1] }
            return out
        }
        val segLen = FloatArray(m - 1)
        var total = 0f
        for (i in 0 until m - 1) {
            val dx = pts[(i + 1) * 2] - pts[i * 2]
            val dy = pts[(i + 1) * 2 + 1] - pts[i * 2 + 1]
            segLen[i] = hypot(dx, dy)
            total += segLen[i]
        }
        if (total <= 0f) {
            for (i in 0 until n) { out[i * 2] = pts[0]; out[i * 2 + 1] = pts[1] }
            return out
        }
        val step = total / (n - 1)
        var seg = 0
        var segStart = 0f
        for (i in 0 until n) {
            val target = (step * i).coerceAtMost(total)
            while (seg < m - 2 && segStart + segLen[seg] < target) { segStart += segLen[seg]; seg++ }
            val segT = if (segLen[seg] > 0f) ((target - segStart) / segLen[seg]).coerceIn(0f, 1f) else 0f
            val x0 = pts[seg * 2]; val y0 = pts[seg * 2 + 1]
            val x1 = pts[(seg + 1) * 2]; val y1 = pts[(seg + 1) * 2 + 1]
            out[i * 2] = x0 + (x1 - x0) * segT
            out[i * 2 + 1] = y0 + (y1 - y0) * segT
        }
        return out
    }

    private fun pathCost(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        val n = a.size / 2
        for (i in 0 until n) {
            val dx = a[i * 2] - b[i * 2]
            val dy = a[i * 2 + 1] - b[i * 2 + 1]
            sum += hypot(dx, dy)
        }
        return sum / max(1, n)
    }

    private fun nearestKey(x: Float, y: Float, keyCenter: (Char) -> Pair<Float, Float>?): Char? {
        var best: Char? = null
        var bestD = Float.MAX_VALUE
        for (c in 'a'..'z') {
            val p = keyCenter(c) ?: continue
            val d = hypot((x - p.first).toDouble(), (y - p.second).toDouble()).toFloat()
            if (d < bestD) { bestD = d; best = c }
        }
        return best
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
}
