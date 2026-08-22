package hk.tq9

import hk.tq9.core.EnDict
import hk.tq9.swipe.GestureDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用一個 fake QWERTY 佈局（同真鍵盤格局一樣，淨係大細唔同）試 [GestureDecoder]：
 * 由字嘅每個字母鍵中心連成線，砌返條「完美」軌跡，睇下夾唔夾到自己。
 */
class EnDictTest {

    private val w = 100f
    private val h = 100f
    private val rows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")

    private fun keyCenter(c: Char): Pair<Float, Float>? {
        for ((r, row) in rows.withIndex()) {
            val i = row.indexOf(c)
            if (i < 0) continue
            val offset = when (r) { 1 -> 0.5f; 2 -> 1.5f; else -> 0f }
            return (offset + i) * w + w / 2f to r * h + h / 2f
        }
        return null
    }

    /** 由 [word] 逐個字母（連續重複嘅淨係計一次）連成線，插返幾個中間點模擬手指軌跡 */
    private fun pathFor(word: String, stepsPerSeg: Int = 8): List<Float> {
        val letters = ArrayList<Char>()
        for (c in word) if (letters.isEmpty() || letters.last() != c) letters.add(c)
        val centers = letters.map { keyCenter(it)!! }
        val pts = ArrayList<Float>()
        pts.add(centers[0].first); pts.add(centers[0].second)
        for (i in 0 until centers.size - 1) {
            val (x0, y0) = centers[i]
            val (x1, y1) = centers[i + 1]
            for (s in 1..stepsPerSeg) {
                val f = s.toFloat() / stepsPerSeg
                pts.add(x0 + (x1 - x0) * f)
                pts.add(y0 + (y1 - y0) * f)
            }
        }
        return pts
    }

    // (字, 使用頻率)
    private val dict = EnDict.fromPairs(
        listOf(
            "hello" to 900_000L,
            "help" to 800_000L,
            "halo" to 5_000L,
            "hero" to 300_000L,
            "hell" to 40_000L,
            "hollow" to 60_000L,
            "set" to 2_000_000L,
            "sit" to 800_000L,
            "sat" to 500_000L,
            "street" to 120_000L,
            "world" to 900_000L,
            "word" to 500_000L,
            "wood" to 400_000L,
            "display" to 700_000L,
            "dispute" to 90_000L,
            "diary" to 50_000L
        )
    )
    private val decoder = GestureDecoder(dict)

    private fun decode(word: String, prefix: String = "", suffix: String = "") =
        decoder.decode(pathFor(word), ::keyCenter, w, prefix, suffix)

    @Test
    fun `滑 hello 嘅完美軌跡搵到 hello`() {
        assertEquals("hello", decode("hello").first())
    }

    @Test
    fun `首尾字母一定準，唔啱嘅字唔會入候選`() {
        val r = decode("help")
        assertTrue(r.isNotEmpty())
        assertTrue(r.all { it.startsWith("h") && it.endsWith("p") })
        assertEquals("help", r.first())
    }

    @Test
    fun `s 直接掃去 t，最夾（形狀＋常用度）嗰個 set 排第一`() {
        // 唔行 set/sit/sat 任何一個嘅中間彎位，淨係打橫掃過 s 同 t
        val (sx, sy) = keyCenter('s')!!
        val (tx, ty) = keyCenter('t')!!
        val pts = ArrayList<Float>()
        for (i in 0..8) {
            val f = i / 8f
            pts.add(sx + (tx - sx) * f); pts.add(sy + (ty - sy) * f)
        }
        val r = decoder.decode(pts, ::keyCenter, w)
        assertTrue(r.isNotEmpty())
        assertTrue(r.all { it.first() == 's' && it.last() == 't' })
        assertEquals("set", r.first())
    }

    @Test
    fun `打頭幾個字母有提示，按常用度排`() {
        assertEquals(listOf("hello", "help", "hero"), dict.fromPrefix("he", limit = 3))
    }

    @Test
    fun `caret 前後打咗嘅字母會一齊計`() {
        // 個欄係 dis|y（| = caret），滑 body「pla」→ display
        assertEquals("display", decode("pla", "dis", "y").first())
    }

    @Test
    fun `有 context 嗰陣，唔夾前後嘅字唔會入候選`() {
        val r = decode("pla", "dis", "y")
        assertTrue(r.isNotEmpty())
        assertTrue(r.all { it.startsWith("dis") && it.endsWith("y") })
        assertFalse("dispute" in r)
    }
}
