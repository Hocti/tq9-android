package tt.ime.riverine

import tt.ime.riverine.core.VoiceActivity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * [VoiceActivity.hasSpeech] 係「撳親咪但係冇出過聲」嗰下嘅擋箭牌，
 * 擋錯咗 user 就會覺得粒掣壞咗，所以兩個方向都要試：
 * 靜嘅要擋得住，細細聲講嘅唔可以擋。
 */
class VoiceActivityTest {

    private val rate = 16_000

    /** 砌一段 [ms] 毫秒嘅 PCM：底噪 [noise]（振幅），[speech] 段落就再加把「聲」 */
    private fun pcm(ms: Int, noise: Int, speech: List<Triple<Int, Int, Int>> = emptyList()):
        ByteArray {
        val n = rate * ms / 1000
        val out = ByteArray(n * 2)
        val rnd = Random(42)
        for (i in 0 until n) {
            var v = if (noise > 0) rnd.nextInt(-noise, noise + 1) else 0
            val t = i * 1000 / rate
            for ((from, to, amp) in speech) {
                if (t in from until to) {
                    // 一把 180Hz 嘅「人聲」，加返啲隨機振幅令佢唔係死板嘅正弦
                    val env = 0.6 + 0.4 * sin(2 * PI * 3 * i / rate)
                    v += (amp * env * sin(2 * PI * 180 * i / rate)).roundToInt()
                }
            }
            val s = v.coerceIn(-32768, 32767)
            out[i * 2] = (s and 0xFF).toByte()
            out[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
        }
        return out
    }

    @Test
    fun `死靜當冇人講嘢`() {
        assertFalse(VoiceActivity.hasSpeech(pcm(ms = 1500, noise = 0), rate))
    }

    @Test
    fun `齋底噪當冇人講嘢`() {
        assertFalse(VoiceActivity.hasSpeech(pcm(ms = 2000, noise = 120), rate))
    }

    @Test
    fun `嘈但係冇人講嘢一樣要擋`() {
        // 街邊咁嘈，但係由頭到尾都係同一個 level，冇人出過聲
        assertFalse(VoiceActivity.hasSpeech(pcm(ms = 2000, noise = 900), rate))
    }

    @Test
    fun `靜嘅地方講一句就要收`() {
        val data = pcm(ms = 2000, noise = 80,
            speech = listOf(Triple(500, 1400, 4000)))
        assertTrue(VoiceActivity.hasSpeech(data, rate))
    }

    @Test
    fun `細聲講都唔可以擋`() {
        val data = pcm(ms = 2000, noise = 100,
            speech = listOf(Triple(600, 1300, 900)))
        assertTrue(VoiceActivity.hasSpeech(data, rate))
    }

    @Test
    fun `嘈嘅地方講嘢一樣要收`() {
        val data = pcm(ms = 2500, noise = 700,
            speech = listOf(Triple(800, 1900, 6000)))
        assertTrue(VoiceActivity.hasSpeech(data, rate))
    }

    @Test
    fun `短過八格就唔使諗`() {
        // 20ms 一格，得 100ms 即係五格，點都夠唔到 MIN_VOICED_FRAMES
        assertFalse(VoiceActivity.hasSpeech(pcm(ms = 100, noise = 0,
            speech = listOf(Triple(0, 100, 8000))), rate))
    }

    @Test
    fun `一兩下爆音唔算講嘢`() {
        // 撳嘢／擘手指嗰啲短促聲：夠響，但係得三幾格
        val data = pcm(ms = 2000, noise = 90,
            speech = listOf(Triple(700, 740, 9000)))
        assertFalse(VoiceActivity.hasSpeech(data, rate))
    }
}
