package tt.ime.riverine

import org.junit.Assert.assertEquals
import org.junit.Test
import tt.ime.riverine.core.TextEdit

/**
 * 剷一下 ⌫ 到底剷幾多個 `char`（見 [TextEdit]）。
 *
 * 重點係 emoji：`assets/emoji.txt` 入面一千二百幾個都係增補字符（兩個 char），
 * 以前寫死剷 `1` 就要撳兩下先剷得走。
 */
class TextEditTest {

    private fun len(s: String) = TextEdit.lastClusterLength(s)

    @Test fun empty() = assertEquals(0, len(""))

    @Test fun ascii() = assertEquals(1, len("abc"))

    @Test fun han() = assertEquals(1, len("三三"))

    /** `👾` U+1F47E —— user 2026-09-11 報嘅嗰個 */
    @Test fun alienMonster() = assertEquals(2, len("好嘢👾"))

    @Test fun otherAstralEmoji() {
        assertEquals(2, len("😀"))
        assertEquals(2, len("🎤"))
    }

    /** BMP 嗰啲符號本來就冇事（`♥` U+2665），唔好一竹篙打一船人剷多咗 */
    @Test fun bmpSymbol() = assertEquals(1, len("♥"))

    /** 旗＝兩個 regional indicator，夾埋四個 char */
    @Test fun flag() = assertEquals(4, len("香港🇭🇰"))
}
