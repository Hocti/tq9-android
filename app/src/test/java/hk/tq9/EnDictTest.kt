package hk.tq9

import hk.tq9.core.EnDict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EnDictTest {

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

    @Test
    fun `滑過 h-e-l-o 應該搵到 hello`() {
        assertEquals("hello", dict.fromGesture(listOf('h', 'e', 'l', 'o')).first())
    }

    @Test
    fun `首尾字母一定準，唔啱嘅字唔會入候選`() {
        val r = dict.fromGesture(listOf('h', 'e', 'l', 'p'))
        assertTrue(r.all { it.startsWith("h") && it.endsWith("p") })
        assertEquals("help", r.first())
    }

    @Test
    fun `長度一樣嗰陣，揀返使用頻率高嗰個`() {
        // s…t：set / sit / sat 三個都夾晒，長度一樣，就要靠頻率分勝負
        assertEquals(listOf("set", "sit", "sat"), dict.fromGesture(listOf('s', 't'), limit = 3))
    }

    @Test
    fun `字數同滑過嘅鍵數差得遠會被扣分`() {
        // w-o-d：word 啱曬長度，贏 wood（多咗個字母）同 world
        assertEquals("word", dict.fromGesture(listOf('w', 'o', 'd')).first())
        // s-t 之下 street 差 4 個字母，會排喺三個短字後面
        assertTrue(dict.fromGesture(listOf('s', 't')).indexOf("street") >= 3)
    }

    @Test
    fun `打頭幾個字母有提示，按常用度排`() {
        assertEquals(listOf("hello", "help", "hero"), dict.fromPrefix("he", limit = 3))
    }

    @Test
    fun `caret 前後打咗嘅字母會一齊計`() {
        // 個欄係 dis|y（| = caret），滑 p-l-a → display
        assertEquals("display", dict.fromGesture(listOf('p', 'l', 'a'), "dis", "y").first())
    }

    @Test
    fun `有 context 嗰陣，唔夾前後嘅字唔會入候選`() {
        val r = dict.fromGesture(listOf('p', 'l', 'a'), "dis", "y")
        assertTrue(r.all { it.startsWith("dis") && it.endsWith("y") })
        assertTrue("dispute" !in r)
    }

    @Test
    fun `前後都冇字嗰陣同普通滑動一模一樣`() {
        assertEquals(
            dict.fromGesture(listOf('h', 'e', 'l', 'o')),
            dict.fromGesture(listOf('h', 'e', 'l', 'o'), "", "")
        )
    }
}
