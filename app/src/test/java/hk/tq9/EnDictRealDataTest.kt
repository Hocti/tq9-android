package hk.tq9

import hk.tq9.core.EnDict
import hk.tq9.swipe.GestureDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 直接用真嘅 assets/en_freq.txt（5 萬字，正式版精簡版）試，確保 parser 同 [GestureDecoder] 喺真數據上面 work */
class EnDictRealDataTest {

    private val dict: EnDict by lazy {
        val f = File("src/main/assets/en_freq.txt")
        assertTrue("搵唔到 ${f.absolutePath}", f.exists())
        f.inputStream().use { EnDict.parse(it) }
    }
    private val decoder by lazy { GestureDecoder(dict) }

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

    /** 模擬滑動：由 [letters] 逐個字母鍵中心連成線 */
    private fun pathFor(letters: List<Char>, stepsPerSeg: Int = 8): List<Float> {
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

    private fun swipe(word: String): List<String> {
        val seq = ArrayList<Char>()
        for (c in word) if (seq.isEmpty() || seq.last() != c) seq.add(c)
        return decoder.decode(pathFor(seq), ::keyCenter, w)
    }

    @Test
    fun `5 萬字全部載得入`() {
        assertEquals(50000, dict.size)
    }

    @Test
    fun `常見字完美滑動軌跡都搵得返出嚟`() {
        for (word in listOf("hello", "people", "because", "keyboard", "message", "tomorrow", "chinese")) {
            assertEquals("滑 $word", word, swipe(word).first())
        }
    }

    @Test
    fun `手震令軌跡有雜訊，都仲係搵到嗰個字`() {
        // 每點加返 ±8px（8% 鍵闊）嘅隨機偏移，模擬手指冇踩得咁準
        val rnd = kotlin.random.Random(42)
        val jittered = pathFor(listOf('h', 'e', 'l', 'o')).map { it + rnd.nextFloat() * 16f - 8f }
        assertEquals("hello", decoder.decode(jittered, ::keyCenter, w).first())
    }

    @Test
    fun `完美軌跡揀返最常用嗰個`() {
        assertEquals("the", swipe("the").first())
        assertEquals("and", swipe("and").first())
    }

    @Test
    fun `打頭幾個字母嘅提示都係按常用度`() {
        assertEquals("the", dict.fromPrefix("th").first())
        assertEquals("people", dict.fromPrefix("peo").first())
    }
}
