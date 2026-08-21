package hk.tq9

import hk.tq9.swipe.GestureKeyTracker
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 用九宮格嘅座標砌一啲真實速度嘅軌跡，睇下中間格判唔判得啱。
 * 每格 200px，1~9 跟 numpad 排（7 8 9 喺最上）。
 */
class GestureKeyTrackerTest {

    private val cell = 200f

    private fun center(digit: Int): Pair<Float, Float> {
        val col = (digit - 1) % 3
        val row = 2 - (digit - 1) / 3
        return (col * cell + cell / 2) to (row * cell + cell / 2)
    }

    private fun keyAt(x: Float, y: Float): Int {
        val col = (x / cell).toInt()
        val row = (y / cell).toInt()
        if (col !in 0..2 || row !in 0..2) return GestureKeyTracker.NO_KEY
        return (2 - row) * 3 + col + 1
    }

    /** @param weights 給 plausibility 用；預設全部中性 */
    private fun run(
        path: List<Triple<Float, Float, Long>>,
        weights: (Int) -> Float = { 0f },
        dwellMs: Long = 150,
        angleDeg: Float = 55f,
        holdRepeatMs: Long = 0
    ): List<Int> {
        val out = ArrayList<Int>()
        val t = GestureKeyTracker(dwellMs, angleDeg)
        t.minSegPx = cell * 0.3f
        t.holdRepeatMs = holdRepeatMs
        t.attach(object : GestureKeyTracker.Delegate {
            override fun keyAt(x: Float, y: Float) = this@GestureKeyTrackerTest.keyAt(x, y)
            override fun plausibility(key: Int) = weights(key)
            override fun onGestureKey(key: Int) { out.add(key) }
        })
        val (x0, y0, t0) = path.first()
        t.start(x0, y0, t0)
        for (p in path.drop(1).dropLast(1)) t.move(p.first, p.second, p.third)
        val last = path.last()
        t.move(last.first, last.second, last.third)
        t.finish(last.first, last.second, last.third)
        return out
    }

    /** 由 a 直線行去 b，每 16ms 一點 */
    private fun leg(
        from: Pair<Float, Float>, to: Pair<Float, Float>,
        durationMs: Long, startT: Long
    ): List<Triple<Float, Float, Long>> {
        val steps = (durationMs / 16).toInt().coerceAtLeast(1)
        return (1..steps).map { i ->
            val f = i.toFloat() / steps
            Triple(from.first + (to.first - from.first) * f,
                   from.second + (to.second - from.second) * f,
                   startT + durationMs * i / steps)
        }
    }

    @Test
    fun `直角轉彎：7 到 9 到 3，中間嘅 8 唔算，9 算`() {
        val path = ArrayList<Triple<Float, Float, Long>>()
        path.add(Triple(center(7).first, center(7).second, 0L))
        path.addAll(leg(center(7), center(9), 200, 0))       // 打橫掃過 8
        path.addAll(leg(center(9), center(3), 200, 200))     // 喺 9 度轉 90° 落去 3
        assertEquals(listOf(7, 9, 3), run(path))
    }

    @Test
    fun `一條直線 7 到 9：中間嘅 8 唔應該出`() {
        val path = ArrayList<Triple<Float, Float, Long>>()
        path.add(Triple(center(7).first, center(7).second, 0L))
        path.addAll(leg(center(7), center(9), 220, 0))
        assertEquals(listOf(7, 9), run(path))
    }

    @Test
    fun `喺 8 度停低：就算冇轉角都要出`() {
        val path = ArrayList<Triple<Float, Float, Long>>()
        path.add(Triple(center(7).first, center(7).second, 0L))
        path.addAll(leg(center(7), center(8), 120, 0))
        // 喺 8 度企定 250ms
        var t = 120L
        repeat(15) { t += 16; path.add(Triple(center(8).first, center(8).second, t)) }
        path.addAll(leg(center(8), center(9), 120, t))
        assertEquals(listOf(7, 8, 9), run(path))
    }

    @Test
    fun `weight 幫手：轉角唔夠肯定嗰陣，冇字嘅組合會被剔走`() {
        // 7 → 8 快速掃過，喺 8 度打直落 5，係一個 90° 但停留唔夠耐嘅轉角
        val path = ArrayList<Triple<Float, Float, Long>>()
        path.add(Triple(center(7).first, center(7).second, 0L))
        path.addAll(leg(center(7), center(8), 100, 0))
        path.addAll(leg(center(8), center(5), 100, 100))

        // 中性 weight → 轉角夠深，8 會出
        assertEquals(listOf(7, 8, 5), run(path))
        // 字碼表話 78 呢個 prefix 冇字 → 8 應該剔走
        assertEquals(listOf(7, 5), run(path, weights = { k -> if (k == 8) -1f else 0f }))
    }

    @Test
    fun `放手之前停一停 = 連撳兩下`() {
        // 8 拉去 1，喺 1 度停夠耐先放手 → 8 1 1
        val path = ArrayList<Triple<Float, Float, Long>>()
        path.add(Triple(center(8).first, center(8).second, 0L))
        path.addAll(leg(center(8), center(1), 150, 0))
        var t = 150L
        repeat(30) { t += 16; path.add(Triple(center(1).first, center(1).second, t)) }
        assertEquals(listOf(8, 1, 1), run(path, holdRepeatMs = 380))
        // 冇停就照舊得一下
        val quick = ArrayList<Triple<Float, Float, Long>>()
        quick.add(Triple(center(8).first, center(8).second, 0L))
        quick.addAll(leg(center(8), center(1), 150, 0))
        assertEquals(listOf(8, 1), run(quick, holdRepeatMs = 380))
    }

    @Test
    fun `單撳都要出返嗰個鍵`() {
        val c = center(5)
        val path = listOf(Triple(c.first, c.second, 0L), Triple(c.first + 2f, c.second, 30L))
        assertEquals(listOf(5), run(path))
    }
}
