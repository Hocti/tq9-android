package hk.tq9

import hk.tq9.core.EnDict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 直接用真嘅 assets/en_freq.txt（5 萬字，正式版精簡版）試，確保 parser 同評分喺真數據上面work */
class EnDictRealDataTest {

    private val dict: EnDict by lazy {
        val f = File("src/main/assets/en_freq.txt")
        assertTrue("搵唔到 ${f.absolutePath}", f.exists())
        f.inputStream().use { EnDict.parse(it) }
    }

    private fun swipe(word: String): List<String> {
        // 模擬滑動：起點終點一定準，中間重複字母只會經過一次
        val seq = ArrayList<Char>()
        for (c in word) if (seq.isEmpty() || seq.last() != c) seq.add(c)
        return dict.fromGesture(seq)
    }

    @Test
    fun `5 萬字全部載得入`() {
        assertEquals(50000, dict.size)
    }

    @Test
    fun `常見字滑得返出嚟`() {
        for (w in listOf("hello", "people", "because", "keyboard", "message", "tomorrow", "chinese")) {
            assertEquals("滑 $w", w, swipe(w).first())
        }
    }

    @Test
    fun `漏咗中間鍵都夾得返`() {
        // 掃得快，中間嘅 e 同 l 漏咗
        assertTrue("hello" in dict.fromGesture(listOf('h', 'l', 'o')))
        assertTrue("about" in dict.fromGesture(listOf('a', 'b', 't')))
    }

    @Test
    fun `幾個字都夾到就出最常用嗰個`() {
        // t-h-e 之下有 the / thee / there…，最常用嘅 the 要排第一
        assertEquals("the", dict.fromGesture(listOf('t', 'h', 'e')).first())
        // a-n-d 之下 and 遠比 android 常用
        assertEquals("and", dict.fromGesture(listOf('a', 'n', 'd')).first())
    }

    @Test
    fun `打頭幾個字母嘅提示都係按常用度`() {
        assertEquals("the", dict.fromPrefix("th").first())
        assertEquals("people", dict.fromPrefix("peo").first())
    }
}
