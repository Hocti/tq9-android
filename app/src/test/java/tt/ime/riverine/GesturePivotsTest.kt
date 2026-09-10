package tt.ime.riverine

import tt.ime.riverine.swipe.GesturePivots
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [GesturePivots]：由軌跡＋時間抽返「明確噉撳過邊幾粒字母」。
 * 純幾何＋時間，唔關詞庫事。
 */
class GesturePivotsTest {

    private val w = 100f
    private val rows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")

    private fun keyCenter(c: Char): Pair<Float, Float>? {
        for ((r, row) in rows.withIndex()) {
            val i = row.indexOf(c)
            if (i < 0) continue
            val offset = when (r) { 1 -> 0.5f; 2 -> 1.5f; else -> 0f }
            return (offset + i) * w + w / 2f to r * w + w / 2f
        }
        return null
    }

    /** 一條 20ms 一格、每粒字母停 [dwellMs] 嘅軌跡（`dwellMs = 0` 就係一氣呵成滑過去） */
    private fun swipePath(word: String, dwellMs: Long, jitter: Float = 0f): Pair<List<Float>, List<Long>> {
        val rnd = kotlin.random.Random(9)
        val seq = StringBuilder()
        for (c in word) if (seq.isEmpty() || seq.last() != c) seq.append(c)
        val centers = seq.map { keyCenter(it)!! }
        val pts = ArrayList<Float>()
        val ts = ArrayList<Long>()
        var t = 0L
        fun add(x: Float, y: Float, dt: Long) {
            t += dt
            pts.add(x + (rnd.nextFloat() * 2f - 1f) * jitter)
            pts.add(y + (rnd.nextFloat() * 2f - 1f) * jitter)
            ts.add(t)
        }
        add(centers[0].first, centers[0].second, 0)
        // 起點都要停 —— 手指按落去到開始郁，一定有一段時間
        repeat((dwellMs / 20L).toInt()) { add(centers[0].first, centers[0].second, 20) }
        for (i in 0 until centers.size - 1) {
            val (x0, y0) = centers[i]
            val (x1, y1) = centers[i + 1]
            for (s in 1..10) {
                val f = s / 10f
                add(x0 + (x1 - x0) * f, y0 + (y1 - y0) * f, 20)
            }
            repeat((dwellMs / 20L).toInt()) { add(x1, y1, 20) }
        }
        return pts to ts
    }

    private fun letters(word: String, dwellMs: Long, jitter: Float = 0f): String {
        val (p, t) = swipePath(word, dwellMs, jitter)
        return GesturePivots.letters(p, t, ::keyCenter, w)
    }

    @Test
    fun `每粒字母都短停留，就抽得返成串字母`() {
        assertEquals("swipe", letters("swipe", 80))
        assertEquals("qwerty", letters("qwerty", 80))
        assertEquals("android", letters("android", 80))
    }

    /**
     * 疊字（`hello` 嘅 `ll`）抽唔返兩個 —— 手指喺 `l` 度**得一個**停留，
     * 條軌跡根本冇「撳兩次」呢個資訊。要還原疊字係詞庫嘅責任，唔係呢度。
     */
    @Test
    fun `疊字淨係抽得返一個`() {
        assertEquals("helo", letters("hello", 80))
    }

    @Test
    fun `停耐啲一樣抽得返`() {
        assertEquals("swipe", letters("swipe", 160))
    }

    @Test
    fun `手指冇踩得準都抽得返`() {
        assertEquals("swipe", letters("swipe", 100, jitter = 10f))
    }

    @Test
    fun `一氣呵成冇停過，就淨係靠拗彎`() {
        // 冇停留就只有起點、終點同啲夠尖嘅彎，抽唔齊係正常 ——
        // 砌出嚟嗰個字形狀夾唔到條軌跡，自然會喺 GestureDecoder 度輸畀詞庫
        val r = letters("swipe", 0)
        assertEquals("起點一定要啱", 's', r.first())
        assertEquals("終點一定要啱", 'e', r.last())
    }

    @Test
    fun `直線滑過中間嗰粒鍵，唔可以當撳咗`() {
        // q 一條直線拉到 p，中間 wertyuio 全部經過，但一粒都冇停過亦冇拗過彎
        assertEquals("qp", letters("qp", 0))
    }

    @Test
    fun `冇時間資料就乜都唔抽`() {
        val (p, _) = swipePath("swipe", 80)
        assertEquals("", GesturePivots.letters(p, emptyList(), ::keyCenter, w))
    }
}
