package tt.ime.riverine

import tt.ime.riverine.core.TTEngine
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 九宮格預覽關聯字：第一頁 1～9 格坐邊隻字（`TTEngine.relatePadSlots`）。
 *
 * 同 [SlotOrderTest] 一樣盯死第一頁順序 —— 預覽要同撳「關聯字」之後
 * 實際攤開嗰版對得上，佔位符唔可以令後面啲字走位。
 */
class RelatePadPreviewTest {

    private fun slots(vararg words: String) = TTEngine.relatePadSlots(words.toList())

    @Test fun `第一頁由 1 排到 9`() {
        val words = listOf("甲", "乙", "丙", "丁", "戊", "己", "庚", "辛", "壬")
        val s = TTEngine.relatePadSlots(words)
        for (i in 1..9) assertEquals(words[i - 1], s[i])
    }

    @Test fun `佔位符留空格後面唔走位`() {
        val s = slots("你", "我", TTEngine.PLACEHOLDER, "他")
        assertEquals("你", s[1])
        assertEquals("我", s[2])
        assertEquals("", s[3])
        assertEquals("他", s[4])
        for (i in 5..9) assertEquals("", s[i])
    }

    @Test fun `超過九個只取第一頁`() {
        val words = (1..12).map { "$it" }
        val s = TTEngine.relatePadSlots(words)
        for (i in 1..9) assertEquals("$i", s[i])
        assertEquals(10, s.size)
    }

    @Test fun `空表九格都吉`() {
        val s = TTEngine.relatePadSlots(emptyList())
        for (i in 1..9) assertEquals("", s[i])
    }

    @Test fun `多字詞整串坐一格`() {
        val s = slots("可以")
        assertEquals("可以", s[1])
        for (i in 2..9) assertEquals("", s[i])
    }
}
