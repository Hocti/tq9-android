package tt.ime.riverine

import tt.ime.riverine.core.EnDict
import tt.ime.riverine.swipe.GestureDecoder
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
        return decoder.decode(pathFor(seq), emptyList(), ::keyCenter, w)
    }

    /** 20ms 一格、每粒字母停 80ms 嘅軌跡（＝使用者講嘅「喺每粒字母都有短停留」） */
    private fun swipeWithDwell(word: String): List<String> {
        val seq = StringBuilder()
        for (c in word) if (seq.isEmpty() || seq.last() != c) seq.append(c)
        val centers = seq.map { keyCenter(it)!! }
        val pts = ArrayList<Float>()
        val ts = ArrayList<Long>()
        var t = 0L
        fun add(x: Float, y: Float) { t += 20; pts.add(x); pts.add(y); ts.add(t) }
        add(centers[0].first, centers[0].second)
        repeat(4) { add(centers[0].first, centers[0].second) }
        for (i in 0 until centers.size - 1) {
            val (x0, y0) = centers[i]
            val (x1, y1) = centers[i + 1]
            for (s in 1..10) {
                val f = s / 10f
                add(x0 + (x1 - x0) * f, y0 + (y1 - y0) * f)
            }
            repeat(4) { add(x1, y1) }
        }
        return decoder.decode(pts, ts, ::keyCenter, w)
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
        assertEquals("hello", decoder.decode(jittered, emptyList(), ::keyCenter, w).first())
    }

    @Test
    fun `完美軌跡揀返最常用嗰個`() {
        assertEquals("the", swipe("the").first())
        assertEquals("and", swipe("and").first())
    }

    /**
     * 形狀夾得啱嘅字，**唔可以**輸畀一個順路而又更常用嘅字。
     *
     * `GestureDecoder.SPATIAL_WEIGHT` 本來係 1f，詞頻（`ln(頻率) × LM_WEIGHT`，
     * 落差成 1.9 分）就大過形狀（好極差極之間得 0.7 分左右），所以滑得幾準都好
     * 一樣會出錯字 —— 呢度每一個都係當時實測衰咗嘅個案。
     */
    @Test
    fun `滑得準就唔可以輸畀更常用嗰個字`() {
        for (word in listOf("there", "sorry", "forget", "suppose", "slow", "snow", "cold", "coffee", "really")) {
            assertEquals("滑 $word", word, swipe(word).first())
        }
    }

    /**
     * `swipe` 呢個字本身 —— 使用者 2026-09-10 報：滑親都出 `style`，
     * 候選淨係得 `store` / `site` / `some` / `swope`。兩個原因夾埋：
     * 維基語料嘅 `swipe` 排 80494（裁到 5 萬就冇咗），而形狀一模一樣嘅
     * `swope`（一個姓）反而喺 5 萬以內。見 `scripts/build-en-freq.sh`。
     */
    @Test
    fun `日常詞唔會俾維基語料嘅專有名詞逼走`() {
        for (word in listOf("swipe", "selfie", "emoji", "username", "website")) {
            assertTrue("詞庫應該有 $word", (0 until dict.size).any { dict.word(it) == word })
        }
        assertEquals("swipe", swipe("swipe").first())
    }

    /**
     * 明確噉滑咗幾粒字母，但砌出嚟唔屬詞庫任何一個字 —— 首選就要係嗰串字母本身，
     * 唔好屈硬夾個形狀差好遠嘅詞庫字出嚟（2026-09-10 使用者要求）。
     */
    @Test
    fun `滑咗個唔喺詞庫嘅字，首選就係嗰串字母`() {
        for (word in listOf("hocti", "zylph", "blorf", "kwlm")) {
            assertTrue("$word 唔應該喺詞庫", (0 until dict.size).none { dict.word(it) == word })
            assertEquals("滑 $word", word, swipeWithDwell(word).first())
        }
    }

    /**
     * 反過嚟：**有**詞庫字夾得到就一定要出詞庫嗰個。
     * [tt.ime.riverine.swipe.GesturePivots] 抽字母係逐個停留抽，抽唔返疊字
     * （`hello` 只會抽到 `helo`），所以呢個 case 一失手就會打少個字母。
     */
    @Test
    fun `詞庫夾得到就唔可以出返嗰串生字母`() {
        for (word in listOf("hello", "people", "message", "swipe", "keyboard", "tomorrow", "sorry")) {
            assertEquals("滑 $word", word, swipeWithDwell(word).first())
        }
    }

    @Test
    fun `打頭幾個字母嘅提示都係按常用度`() {
        assertEquals("the", dict.fromPrefix("th").first())
        assertEquals("people", dict.fromPrefix("peo").first())
    }
}
