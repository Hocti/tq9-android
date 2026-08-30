package tt.ime.riverine.core

/**
 * 英文 unigram trie（AOSP 標準做法）：一個節點一個字母，每個節點 bottom-up 快取住
 * 自己呢個 prefix 之下最常用嘅幾個完整字 —— 查 prefix completion 淨係睇緊嗰個節點
 * 已經快取埋嘅結果，唔使成個 subtree 行晒。
 */
class EnTrie private constructor(private val root: Node) {

    private class Node {
        var children: HashMap<Char, Node>? = null
        var isWord = false
        var freq = 0f
        var top: List<String> = emptyList()
    }

    /** [prefix] 開頭、常用度排先嘅完整字（冇 context 嗰陣嘅 fallback） */
    fun completions(prefix: String, limit: Int = 8): List<String> {
        val n = find(prefix) ?: return emptyList()
        // n.top 存嘅係「相對呢個節點」嘅尾巴（bottom-up 砌嗰陣慳咗成串重複嘅 prefix concat）
        return n.top.take(limit).map { prefix + it }
    }

    /** 冇任何 context 嗰陣嘅預設提示：全域最常用嘅幾個字 */
    fun topWords(limit: Int = 8): List<String> = root.top.take(limit)

    private fun find(prefix: String): Node? {
        var n = root
        for (c in prefix) n = n.children?.get(c) ?: return null
        return n
    }

    companion object {
        private const val TOP_K = 12

        /** 由已經載好嘅 [EnDict]（已經按常用度排好）起底 */
        fun fromDict(dict: EnDict): EnTrie {
            val root = Node()
            for (i in 0 until dict.size) {
                val len = dict.wordLength(i)
                var n = root
                for (k in 0 until len) {
                    val c = dict.charAt(i, k)
                    val kids = n.children ?: HashMap<Char, Node>().also { n.children = it }
                    n = kids.getOrPut(c) { Node() }
                }
                n.isWord = true
                n.freq = dict.weightAt(i)
            }
            fillTop(root)
            return EnTrie(root)
        }

        /** bottom-up：一個節點嘅 top = 自己（如果啱好係完整字）+ 晒啲仔嘅 top 夾埋揀最高分嗰幾個 */
        private fun fillTop(n: Node): List<Pair<String, Float>> {
            val merged = ArrayList<Pair<String, Float>>()
            if (n.isWord) merged.add("" to n.freq)
            val kids = n.children
            if (kids != null) {
                for ((c, child) in kids) {
                    val childTop = fillTop(child)
                    for ((w, f) in childTop) merged.add((c + w) to f)
                }
            }
            merged.sortByDescending { it.second }
            val trimmed = if (merged.size > TOP_K) merged.subList(0, TOP_K) else merged
            n.top = trimmed.map { it.first }
            return trimmed
        }
    }
}
