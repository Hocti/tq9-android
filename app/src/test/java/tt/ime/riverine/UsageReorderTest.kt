package tt.ime.riverine

import tt.ime.riverine.core.TTEngine
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「常用字排前」點排（`TTEngine.reorderByUsage`）。
 *
 * 盯死一件事：**頭 9 位（第一頁）永遠唔郁**，打得幾多都好 —— 第一頁個格號就係
 * 隻字個碼最後嗰個數字，調過位就即刻累到所有打熟咗嘅手勢（同 [SlotOrderTest]
 * 守住嘅係同一條規矩）。常用但唔喺頭九位嗰啲字，淨係喺第 10 位起嗰橛度排。
 */
class UsageReorderTest {

    private val head = listOf("一", "二", "三", "四", "五", "六", "七", "八", "九")

    /** `count` 表：冇寫嘅當打過 0 次 */
    private fun sort(words: List<String>, counts: Map<String, Int> = emptyMap()) =
        TTEngine.reorderByUsage(words) { counts[it] ?: 0 }

    @Test fun `頭九位打到幾多次都唔會調位`() {
        val counts = head.withIndex().associate { (i, w) -> w to (100 - i * 10) } // 「一」最少
        val words = head + listOf("十", "廿")
        assertEquals(head, sort(words, counts).take(9))
    }

    @Test fun `常用但唔喺頭九位嘅字推到第十位`() {
        val words = head + listOf("十", "廿", "卅", "百")
        assertEquals(
            head + listOf("百", "十", "廿", "卅"),
            sort(words, mapOf("百" to TTEngine.MIN_USAGE_COUNT))
        )
    }

    @Test fun `尾巴入面次數越大越前`() {
        val words = head + listOf("十", "廿", "卅", "百")
        assertEquals(
            head + listOf("卅", "百", "十", "廿"),
            sort(words, mapOf("百" to 3, "卅" to 9, "十" to 1))
        )
    }

    @Test fun `未打夠 MIN_USAGE_COUNT 次唔算常用`() {
        val words = head + listOf("十", "廿", "卅")
        val counts = mapOf("卅" to TTEngine.MIN_USAGE_COUNT - 1)
        assertEquals(words, sort(words, counts))
    }

    @Test fun `冇資格嘅照排返原本次序（穩定排序）`() {
        val words = head + listOf("十", "廿", "卅", "百", "千")
        assertEquals(
            head + listOf("千", "十", "廿", "卅", "百"),
            sort(words, mapOf("千" to 5))
        )
    }

    @Test fun `佔位符同非中文唔會被推前`() {
        val words = head + listOf(TTEngine.PLACEHOLDER, "，", "A", "百")
        val counts = mapOf(TTEngine.PLACEHOLDER to 99, "，" to 99, "A" to 99, "百" to 2)
        assertEquals(
            head + listOf("百", TTEngine.PLACEHOLDER, "，", "A"),
            sort(words, counts)
        )
    }

    @Test fun `得一頁就完全唔郁`() {
        val counts = head.associateWith { 99 }
        for (n in 1..9) {
            val words = head.take(n)
            assertEquals(words, sort(words, counts))
        }
    }
}
