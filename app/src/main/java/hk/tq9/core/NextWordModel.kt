package hk.tq9.core

import android.content.Context
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 英文選字後，估下一個字用嘅 N-gram / Trie（AOSP 標準做法）：
 * bigram（上一個字 → 下一個字統計，見 assets/en_bigram.txt）做主，
 * 冇 context 或者 bigram 揾唔到就跌落 [EnTrie] 嘅全域常用字。
 *
 * 用戶開始打緊下一個字（有 prefix）嗰陣，bigram 入面夾 prefix 嘅候選字擺前面，
 * 唔夠再用 trie 嘅 prefix completion 補位 —— 即係話提示會跟住 context 走，
 * 唔淨係跟緊打緊嗰幾個字母。
 */
class NextWordModel internal constructor(
    private val trie: EnTrie,
    private val bigram: Map<String, List<String>>
) {

    /** 揀完一個字、未打下一個字之前 */
    fun predictNext(prevWord: String, limit: Int = 8): List<String> {
        val fromBigram = bigram[prevWord.lowercase()].orEmpty()
        if (fromBigram.size >= limit) return fromBigram.take(limit)
        val extra = trie.topWords(limit + fromBigram.size).filter { it !in fromBigram }
        return (fromBigram + extra).take(limit)
    }

    /** 開始打緊下一個字（已經有幾個字母） */
    fun suggestWithPrefix(prevWord: String, prefix: String, limit: Int = 8): List<String> {
        if (prefix.isEmpty()) return predictNext(prevWord, limit)
        val fromBigram = bigram[prevWord.lowercase()].orEmpty().filter { it.startsWith(prefix) }
        if (fromBigram.size >= limit) return fromBigram.take(limit)
        val extra = trie.completions(prefix, limit + fromBigram.size).filter { it !in fromBigram }
        return (fromBigram + extra).take(limit)
    }

    companion object {
        private const val TAG = "NextWordModel"
        private const val ASSET = "en_bigram.txt"
        private const val WAIT_ENDICT_MS = 5_000L

        @Volatile private var instance: NextWordModel? = null
        private val loading = AtomicBoolean(false)

        fun get(): NextWordModel? = instance

        /** 同 [EnDict.preloadAsync] 一齊喺見到英文 view 嗰陣叫，唔會阻住 UI */
        fun preloadAsync(ctx: Context) {
            if (instance != null) return
            if (!loading.compareAndSet(false, true)) return
            val app = ctx.applicationContext
            Thread({
                val t0 = System.currentTimeMillis()
                runCatching {
                    var dict = EnDict.get()
                    var waited = 0L
                    while (dict == null && waited < WAIT_ENDICT_MS) {
                        Thread.sleep(50); waited += 50
                        dict = EnDict.get()
                    }
                    val d = requireNotNull(dict) { "EnDict 都未載好" }
                    val trie = EnTrie.fromDict(d)
                    val bg = parseBigram(app)
                    instance = NextWordModel(trie, bg)
                }.onSuccess { Log.i(TAG, "next-word model 載好，用咗 ${System.currentTimeMillis() - t0}ms") }
                    .onFailure { Log.e(TAG, "next-word model 載唔到", it); loading.set(false) }
            }, "tq9-nextword").apply { priority = Thread.MIN_PRIORITY }.start()
        }

        private fun parseBigram(ctx: Context): Map<String, List<String>> {
            val out = HashMap<String, ArrayList<Pair<String, Long>>>()
            runCatching {
                ctx.assets.open(ASSET).bufferedReader().useLines { lines ->
                    for (line in lines) {
                        val parts = line.trim().split(' ')
                        if (parts.size < 3) continue
                        val freq = parts[2].toLongOrNull() ?: continue
                        out.getOrPut(parts[0]) { ArrayList() }.add(parts[1] to freq)
                    }
                }
            }
            return out.mapValues { (_, v) -> v.sortedByDescending { it.second }.map { it.first } }
        }
    }
}
