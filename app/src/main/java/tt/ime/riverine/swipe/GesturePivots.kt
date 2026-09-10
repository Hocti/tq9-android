package tt.ime.riverine.swipe

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * 由手指軌跡＋時間，抽返「使用者**明確噉**撳過邊幾粒字母」。
 *
 * [GestureDecoder] 嗰套形狀比對淨係識喺詞庫入面揀，滑一個詞庫冇嘅字
 * （人名、代號、新字）就一定出錯字。呢度做返另一條線索：**唔查詞庫**，
 * 淨係睇條軌跡邊幾點係「特登停低／特登拗彎」，砌返個字母串出嚟，
 * 交返畀 [GestureDecoder] 做多一個候選同詞庫嗰啲鬥。
 *
 * 三種算「明確」嘅證據：
 *
 *  1. **起點同終點** —— 手指按落去同放手嗰兩點，永遠算數。
 *  2. **停低** —— 連續一段時間都冇行出 [DWELL_RADIUS_RATIO] 個鍵咁遠，
 *     而且維持夠 [DWELL_MS]。取嗰段嘅中間點做代表。
 *  3. **拗彎** —— 入嚟同出去嘅方向爭 [CORNER_DEG] 以上，而且係附近角度最大嗰點。
 *
 * 「停低」**特登用「行咗幾遠」唔用「幾快」**：即時速度係向後望一個窗計出嚟，
 * 手指停低之後個窗仲蓋住停低之前嗰段快速移動，要成 50ms 之後先跌到落去，
 * 量到嘅「停留」會短過真實停留一大截（實測停 80ms 淨係量到 20ms，
 * `swipe` 抽出嚟變咗 `swpe`）。直接睇「有冇行出過一個細圈」就冇呢個滯後。
 * 順便仲擋埋「慢慢地拉一條直線」—— 慢極都好，一路都喺行緊，唔會困喺個圈入面。
 *
 * 抽唔到嘢（滑得太順、冇停過又冇拗過）就會淨係剩返起點終點兩粒，
 * 咁樣砌出嚟嘅「字」形狀一定夾唔到條軌跡，自然會喺評分度輸畀詞庫，
 * 唔使另外再定一條「夠唔夠信」嘅界線。
 */
object GesturePivots {

    /**
     * 停夠幾耐先算數。**特登定得比 [GestureKeyTracker] 嘅預設 150ms 短**：
     * 英文滑字快好多，使用者講嘅係「短停留」，唔係中文九宮格嗰種撳實。
     */
    private const val DWELL_MS = 70L

    /** 喺幾大個圈入面郁都當「冇行過」（一粒鍵嘅闊度計） */
    private const val DWELL_RADIUS_RATIO = 0.33f

    /** 入角出角爭幾多度先當拗過彎 */
    private const val CORNER_DEG = 50f

    /** 計入角出角嘅窗。太短俾手震影響，太長就變咗弦線方向 */
    private const val DIR_WINDOW_MS = 60L

    /**
     * @param points x,y 交替（[GestureKeyTracker.points] 嗰種格式）
     * @param times  每點嘅時間，數目要 = `points.size / 2`
     * @param keyCenter 邊粒字母個鍵中心喺邊
     * @param keyWidth 一粒鍵大概幾闊，用嚟定「停低」個圈幾大
     * @return 明確撳過嘅字母，連續重複嘅已經收埋做一粒；夾唔到就 empty
     */
    fun letters(
        points: List<Float>,
        times: List<Long>,
        keyCenter: (Char) -> Pair<Float, Float>?,
        keyWidth: Float
    ): String {
        val n = times.size
        if (n < 2 || points.size != n * 2 || keyWidth <= 0f) return ""

        val pivots = sortedSetOf(0, n - 1)
        addDwells(points, times, keyWidth * DWELL_RADIUS_RATIO, pivots)
        addCorners(points, times, pivots)

        val sb = StringBuilder()
        for (i in pivots) {
            val c = nearestKey(points[i * 2], points[i * 2 + 1], keyCenter) ?: continue
            if (sb.isEmpty() || sb.last() != c) sb.append(c)
        }
        return sb.toString()
    }

    /** 連續一段都冇行出 [radius]、又維持夠 [DWELL_MS] 嘅每一段，取中間嗰點 */
    private fun addDwells(points: List<Float>, times: List<Long>, radius: Float, out: MutableSet<Int>) {
        val n = times.size
        var i = 0
        while (i < n) {
            var j = i + 1
            while (j < n && hypot(points[j * 2] - points[i * 2], points[j * 2 + 1] - points[i * 2 + 1]) <= radius) j++
            val last = j - 1
            if (times[last] - times[i] >= DWELL_MS) {
                out.add((i + last) / 2)
                i = j
            } else {
                i++
            }
        }
    }

    /** 入角同出角爭夠 [CORNER_DEG]，而且係附近角度最大嗰點 */
    private fun addCorners(points: List<Float>, times: List<Long>, out: MutableSet<Int>) {
        val n = times.size
        val ang = FloatArray(n)
        for (i in 1 until n - 1) {
            val (ix, iy) = dirBefore(points, times, i)
            val (ox, oy) = dirAfter(points, times, i)
            ang[i] = angleBetween(ix, iy, ox, oy)
        }
        for (i in 1 until n - 1) {
            if (ang[i] < CORNER_DEG) continue
            // 一個彎會連住幾點都超過門檻，淨係要最尖嗰點
            var peak = true
            for (j in max(1, i - 3)..min(n - 2, i + 3)) {
                if (ang[j] > ang[i] || (ang[j] == ang[i] && j < i)) { peak = false; break }
            }
            if (peak) out.add(i)
        }
    }

    private fun dirBefore(points: List<Float>, times: List<Long>, i: Int): Pair<Float, Float> {
        var k = i
        while (k > 0 && times[i] - times[k] < DIR_WINDOW_MS) k--
        return (points[i * 2] - points[k * 2]) to (points[i * 2 + 1] - points[k * 2 + 1])
    }

    private fun dirAfter(points: List<Float>, times: List<Long>, i: Int): Pair<Float, Float> {
        var k = i
        val last = times.size - 1
        while (k < last && times[k] - times[i] < DIR_WINDOW_MS) k++
        return (points[k * 2] - points[i * 2]) to (points[k * 2 + 1] - points[i * 2 + 1])
    }

    private fun nearestKey(x: Float, y: Float, keyCenter: (Char) -> Pair<Float, Float>?): Char? {
        var best: Char? = null
        var bestD = Float.MAX_VALUE
        for (c in 'a'..'z') {
            val p = keyCenter(c) ?: continue
            val d = hypot(x - p.first, y - p.second)
            if (d < bestD) { bestD = d; best = c }
        }
        return best
    }

    private fun angleBetween(ax: Float, ay: Float, bx: Float, by: Float): Float {
        if ((ax == 0f && ay == 0f) || (bx == 0f && by == 0f)) return 0f
        var d = Math.toDegrees((atan2(by, bx) - atan2(ay, ax)).toDouble()).toFloat()
        d = abs(d)
        if (d > 180f) d = 360f - d
        return d
    }
}
