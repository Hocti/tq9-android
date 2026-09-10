package tt.ime.riverine.swipe

import tt.ime.riverine.core.EnDict
import kotlin.math.hypot
import kotlin.math.max

/**
 * 英文 swipe 認字：唔再逐格判斷「撳咗邊粒鍵」（嗰套 dwell/轉角 heuristic 已經放棄），
 * 改為 AOSP 手勢輸入嗰套概念嘅 Kotlin 版 —— 成條手指軌跡一次過同關聯字嘅
 * 「理想路徑」（逐個字母嘅鍵中心連成線，重疊嘅字母收埋做一格）比對形狀＋位置，
 * 揀路徑夾得最貼、又夠常用嘅字（shape-writing / SHARK2 嗰個原理）。
 *
 * 關聯字用「首尾字母」分桶做粗篩（同舊 [EnDict] 嘅設計一樣，「首尾字母一定準」），
 * 夾唔到就放寬做淨係信第一個字母嘅 fallback。
 */
class GestureDecoder(private val dict: EnDict) {

    companion object {
        /** 軌跡重新取樣做幾多點先比對（$1 recognizer 嗰套做法） */
        private const val RESAMPLE_N = 32
        /** 淨係頭幾多個字（已經按常用度排好）入 fallback pool，太多會拖慢又冧巴少見字 */
        private const val FUZZY_POOL = 20_000
        /**
         * 形狀夾唔夾，比「呢個字常唔常用」重要得多。
         *
         * 本來係 1f，而 `ln(頻率)` 嘅範圍係 5.5（罕見字）到 17.3（`the`），
         * 乘 [LM_WEIGHT] 之後有成 1.9 分嘅落差；但形狀夾唔夾，好極同差極
         * 之間先得 0.7 分左右 —— 即係詞頻嘅影響力係形狀嘅兩倍幾，
         * 結果**滑得幾準都好，都會輸畀一個順路而又更常用嘅字**：
         * 滑 `there` 出 `the`、滑 `forget` 出 `first`、滑 `sorry` 出 `story`。
         * 調到 3f 之後，詞頻淨係喺形狀夾到差唔多嗰陣先拆得郁（`swipe` / `swope`）。
         */
        private const val SPATIAL_WEIGHT = 3f
        private const val ENDPOINT_WEIGHT = 0.6f
        private const val LM_WEIGHT = 0.16f

        /**
         * [GesturePivots] 砌出嚟嗰個「唔喺詞庫嘅字」當幾常用。
         *
         * 詞庫入面 `LM_WEIGHT * ln(頻率)` 嘅範圍係 0.96（最罕見嗰個）到 2.77（`the`），
         * 呢個值**特登低過最罕見嗰個** —— 形狀夾成點都好，只要有個詞庫字夾得
         * 差唔多咁貼，都應該出詞庫嗰個（滑 `hello` 唔可以因為抽多咗粒字母
         * 就出 `hjello`）。要贏就一定要形狀明顯夾得好過所有詞庫字，
         * 即係「呢串字母真係唔屬任何字」嗰種情況。
         */
        private const val LITERAL_LM = 0.8f

        /** 排行榜嘅空位 */
        private const val EMPTY = -1
        /** 排行榜入面代表「[GesturePivots] 砌嗰個字」，唔係詞庫 index */
        private const val LITERAL = -2
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
     * @param path 原始軌跡，x,y 交替（[tt.ime.riverine.ime.KeyboardBaseView] 個 tracker 出嗰種格式）
     * @param times [path] 每一點嘅時間，數目 = `path.size / 2`。有嘅話就會用
     *   [GesturePivots] 多砌一個「唔喺詞庫嘅字」候選出嚟鬥（見 [LITERAL_LM]）；
     *   冇（或者對唔上數）就淨係查詞庫，行為同以前一樣。
     * @param keyCenter 邊個字母個鍵中心喺邊 —— 用嚟砌「理想路徑」
     * @param keyWidth 一粒鍵大概幾闊，用嚟將距離正規化（唔同螢幕、唔同鍵盤大細都夾到）
     * @param prefix caret 前面已經打咗嘅字母（`dis|y` 滑 `pla` 嘅 `dis`）
     * @param suffix caret 後面已經打咗嘅字母（`dis|y` 嘅 `y`）
     */
    fun decode(
        path: List<Float>,
        times: List<Long> = emptyList(),
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

        val literal = if (times.size * 2 == path.size) {
            GesturePivots.letters(path, times, keyCenter, keyWidth).takeIf { it.length >= 2 }
        } else null

        val strictPool = bucketOf(headChar, tailChar)
        var result = score(strictPool, userPath, keyCenter, keyWidth, prefix, suffix, limit, literal)
        if (result.isEmpty() && headChar in 'a'..'z') {
            // 精準桶夾唔到 → 淨係信第一個字母，喺常用字入面搵形狀最似嗰個
            result = score(byFirst[headChar - 'a'], userPath, keyCenter, keyWidth, prefix, suffix, limit, literal)
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
        limit: Int,
        literal: String?
    ): List<String> {
        val bestIdx = IntArray(limit) { EMPTY }
        val bestScore = FloatArray(limit) { Float.NEGATIVE_INFINITY }

        for (idx in pool) {
            val len = dict.wordLength(idx)
            val bodyStart = prefix.length
            val bodyEnd = len - suffix.length
            if (bodyEnd - bodyStart < 1) continue
            if (prefix.isNotEmpty() && !matchesPrefix(idx, prefix)) continue
            if (suffix.isNotEmpty() && !matchesSuffix(idx, suffix, len)) continue

            val ideal = idealPath(idx, bodyStart, bodyEnd, keyCenter) ?: continue
            insert(bestIdx, bestScore, idx, geoScore(userPath, ideal, keyWidth) + LM_WEIGHT * dict.weightAt(idx))
        }

        // 唔喺詞庫嗰個 —— 形狀照計，但當佢罕見過詞庫任何一個字（見 [LITERAL_LM]）
        if (literal != null) {
            idealPathOf(literal, keyCenter)?.let {
                insert(bestIdx, bestScore, LITERAL, geoScore(userPath, it, keyWidth) + LITERAL_LM)
            }
        }

        return bestIdx.filter { it != EMPTY }
            .map { if (it == LITERAL) prefix + literal + suffix else dict.word(it) }
            .distinct()
    }

    /** 兩條軌跡夾唔夾：逐點距離＋首尾點嘅額外罰分，全部用 [keyWidth] 正規化 */
    private fun geoScore(userPath: FloatArray, ideal: FloatArray, keyWidth: Float): Float {
        val n = RESAMPLE_N
        val r = resample(ideal, n)
        val spatial = pathCost(userPath, r) / keyWidth
        val endCost = (
            hypot(userPath[0] - r[0], userPath[1] - r[1]) +
            hypot(userPath[(n - 1) * 2] - r[(n - 1) * 2], userPath[(n - 1) * 2 + 1] - r[(n - 1) * 2 + 1])
        ) / keyWidth
        return -(spatial + ENDPOINT_WEIGHT * endCost) * SPATIAL_WEIGHT
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

    /**
     * 同 [idealPath] 一樣，但係由 [GesturePivots] 出嗰個字砌。
     * 唔使似 [idealPath] 咁隔走疊字 —— 條軌跡上面一個停留就一粒字母，
     * 出嚟嗰串本身已經冇連續重複。
     */
    private fun idealPathOf(word: String, keyCenter: (Char) -> Pair<Float, Float>?): FloatArray? {
        val pts = ArrayList<Float>(word.length * 2)
        for (c in word) {
            val p = keyCenter(c) ?: return null
            pts.add(p.first); pts.add(p.second)
        }
        return if (pts.isEmpty()) null else pts.toFloatArray()
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
