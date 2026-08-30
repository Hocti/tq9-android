package tt.ime.riverine

import tt.ime.riverine.core.EnDict
import tt.ime.riverine.core.EnTrie
import tt.ime.riverine.core.NextWordModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EnTrieTest {

    private val dict = EnDict.fromPairs(
        listOf(
            "the" to 900_000L,
            "then" to 50_000L,
            "there" to 400_000L,
            "these" to 300_000L,
            "that" to 800_000L,
            "hello" to 200_000L,
            "help" to 190_000L
        )
    )
    private val trie = EnTrie.fromDict(dict)

    @Test
    fun `prefix completion 按常用度排先`() {
        assertEquals(listOf("the", "there", "these", "then"), trie.completions("the"))
    }

    @Test
    fun `冇個 prefix 存在就乜都冇`() {
        assertTrue(trie.completions("xyz").isEmpty())
    }

    @Test
    fun `全域最常用字`() {
        assertEquals("the", trie.topWords(1).first())
    }
}

class NextWordModelTest {

    private val dict = EnDict.fromPairs(
        listOf(
            "am" to 900_000L,
            "have" to 800_000L,
            "the" to 950_000L,
            "and" to 900_000L,
            "apple" to 10_000L
        )
    )

    private fun model(bigram: Map<String, List<String>> = emptyMap()) =
        NextWordModel(EnTrie.fromDict(dict), bigram)

    @Test
    fun `有 bigram 就用 bigram 嘅次序`() {
        val m = model(mapOf("i" to listOf("am", "have")))
        assertEquals(listOf("am", "have"), m.predictNext("i").take(2))
    }

    @Test
    fun `冇 bigram context 就跌落去全域常用字`() {
        val m = model()
        assertEquals("the", m.predictNext("xyz").first())
    }

    @Test
    fun `打緊下一個字嗰陣，bigram 夾 prefix 嘅擺前面`() {
        val m = model(mapOf("i" to listOf("am", "have")))
        // "a" 開頭：bigram 度有 am，trie 度仲有 apple，am 要行先
        assertEquals("am", m.suggestWithPrefix("i", "a").first())
    }

    @Test
    fun `bigram 夾唔到 prefix 就淨係跌落 trie`() {
        val m = model(mapOf("i" to listOf("am", "have")))
        assertEquals("the", m.suggestWithPrefix("i", "th").first())
    }
}
