package hk.tq9.swipe

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max

/**
 * Swype 式滑動：除咗起點同終點之外，靠「軌跡」推斷中途撳過邊幾個鍵。
 *
 * 兩個線索（同 spec 一樣）：
 *  1. 喺某鍵停得耐 / 明顯減速再加速走
 *  2. 入咗某鍵之後轉角度離開
 *
 * 再加第三個：[Delegate.plausibility] —— 用字碼表 weight 判斷「加咗呢個鍵之後
 * 仲有冇字」。純幾何唔肯定嘅時候（例如 90° 轉角但角位好淺）就靠佢加減分。
 *
 * 中文係即時出鍵（離開一格就 callback），因為每入一碼 UI 都會變；
 * 英文就由 caller 自己 buffer 到放手先砌字。
 */
class GestureKeyTracker(
    var dwellMs: Long = 150,
    var angleDeg: Float = 55f
) {

    interface Delegate {
        /** (x,y) 落喺邊個可滑動嘅鍵，冇就回傳 [NO_KEY] */
        fun keyAt(x: Float, y: Float): Int
        /** 由字碼表 weight 得出嘅可能性，-1f ~ +1f；唔想用就回 0f */
        fun plausibility(key: Int): Float = 0f
        /** 判定咗撳過呢個鍵 */
        fun onGestureKey(key: Int)
    }

    companion object {
        const val NO_KEY = -1
        /** weight 對最終分數嘅影響力 */
        private const val WEIGHT_INFLUENCE = 0.35f
        private const val ACCEPT = 0.62f
        /** 計「入角 / 出角」時向後望幾耐，太短會俾手震影響，太長就變咗弦線方向 */
        private const val DIR_WINDOW_MS = 60L
    }

    /** 角位判定至少要喺格內行過幾遠（由 view 按格大小設定） */
    var minSegPx: Float = 0f

    /**
     * 放手之前喺最尾嗰格停夠幾耐就當撳咗兩下（`8` 拉去 `1` 停一停再放手 = `811`）。
     * 0 = 熄咗（英文滑動唔使呢樣）。
     */
    var holdRepeatMs: Long = 0

    private class Visit(
        val key: Int,
        val enterX: Float, val enterY: Float, val enterT: Long,
        val inDx: Float, val inDy: Float            // 入格嗰陣嘅即時方向
    ) {
        var lastX = enterX; var lastY = enterY; var lastT = enterT
        var pathLen = 0f
    }

    private var cur: Visit? = null
    private var isFirst = true
    private var totalPath = 0f
    private var startT = 0L
    private var lastX = 0f
    private var lastY = 0f

    val points = ArrayList<Float>(256)   // x,y 交替，畫線用
    private val times = ArrayList<Long>(128)
    var active = false; private set

    fun start(x: Float, y: Float, t: Long) {
        points.clear()
        times.clear()
        points.add(x); points.add(y); times.add(t)
        totalPath = 0f
        startT = t
        lastX = x; lastY = y
        isFirst = true
        active = true
        val key = keyOrNull(x, y)
        cur = if (key != NO_KEY) Visit(key, x, y, t, 0f, 0f) else null
    }

    private var delegate: Delegate? = null
    fun attach(d: Delegate) { delegate = d }

    private fun keyOrNull(x: Float, y: Float): Int = delegate?.keyAt(x, y) ?: NO_KEY

    fun move(x: Float, y: Float, t: Long) {
        if (!active) return
        val step = hypot(x - lastX, y - lastY)
        totalPath += step
        points.add(x); points.add(y); times.add(t)

        val v = cur
        val key = keyOrNull(x, y)

        if (v == null) {
            if (key != NO_KEY) {
                val (dx, dy) = dirBack(t)
                cur = Visit(key, x, y, t, dx, dy)
            }
        } else if (key == v.key || key == NO_KEY) {
            v.pathLen += step
            v.lastX = x; v.lastY = y; v.lastT = t
        } else {
            // 離開咗上一格 → 依家先決定佢算唔算撳過
            decideAndEmit(v, x, y, t)
            // 出上一格嘅方向，就係入呢一格嘅方向
            val (dx, dy) = dirBack(t)
            cur = Visit(key, x, y, t, dx, dy)
            isFirst = false
        }
        lastX = x; lastY = y
    }

    /**
     * 由而家向後望 [DIR_WINDOW_MS] 毫秒，得出即時移動方向。
     * 用弦線（入口→出口）會喺 90° 轉角度數變成 45°，所以一定要用即時方向。
     */
    private fun dirBack(now: Long): Pair<Float, Float> {
        if (times.size < 2) return 0f to 0f
        val cx = points[points.size - 2]
        val cy = points[points.size - 1]
        var i = times.size - 1
        while (i > 0 && now - times[i] < DIR_WINDOW_MS) i--
        return (cx - points[i * 2]) to (cy - points[i * 2 + 1])
    }

    /** 放手：最尾嗰格一定計；喺度停夠耐就當連撳兩下 */
    fun finish(x: Float, y: Float, t: Long) {
        if (!active) return
        val v = cur
        if (v != null) {
            v.lastX = x; v.lastY = y; v.lastT = t
            emit(v.key)
            if (holdRepeatMs > 0 && t - v.enterT >= holdRepeatMs) emit(v.key)
        }
        active = false
        cur = null
    }

    fun cancel() {
        active = false
        cur = null
        points.clear()
        times.clear()
    }

    /** 由 down 到而家行咗幾遠 */
    fun travelled(): Float = totalPath

    // ---- 判定 -------------------------------------------------------------

    private fun decideAndEmit(v: Visit, exitX: Float, exitY: Float, exitT: Long) {
        if (isFirst) { emit(v.key); return }

        val dwell = max(0L, exitT - v.enterT)
        val elapsed = max(1L, exitT - startT)
        val refSpeed = totalPath / elapsed
        val inSpeed = (v.pathLen + hypot(exitX - v.lastX, exitY - v.lastY)) / max(1L, dwell)

        // 1) 停留 / 減速
        var geo = (dwell.toFloat() / dwellMs).coerceIn(0f, 1f) * 0.6f
        if (dwell >= dwellMs) geo = 1f
        if (dwell >= dwellMs / 2 && refSpeed > 0f && inSpeed < 0.4f * refSpeed) {
            geo = max(geo, 0.8f)
        }

        // 2) 轉角：比較「入格嘅即時方向」同「離格嘅即時方向」
        val travelled = v.pathLen + hypot(exitX - v.lastX, exitY - v.lastY)
        if (travelled >= minSegPx) {
            val (outDx, outDy) = dirBack(exitT)
            val ang = angleBetween(v.inDx, v.inDy, outDx, outDy)
            if (ang >= angleDeg) {
                val extra = ((ang - angleDeg) / (180f - angleDeg)).coerceIn(0f, 1f)
                geo = max(geo, 0.6f + 0.4f * extra)
            }
        }

        // 3) 字碼表 weight 幫手拆歧義
        val bias = delegate?.plausibility(v.key) ?: 0f
        val score = geo + WEIGHT_INFLUENCE * bias
        if (score >= ACCEPT) emit(v.key)
    }

    private fun emit(key: Int) {
        if (key == NO_KEY) return
        delegate?.onGestureKey(key)
    }

    private fun angleBetween(ax: Float, ay: Float, bx: Float, by: Float): Float {
        if ((ax == 0f && ay == 0f) || (bx == 0f && by == 0f)) return 0f
        var d = Math.toDegrees((atan2(by, bx) - atan2(ay, ax)).toDouble()).toFloat()
        d = abs(d)
        if (d > 180f) d = 360f - d
        return d
    }
}
