package hk.tq9

import hk.tq9.core.Q9Engine
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 選字擺入九宮格，一頁啲字由邊格排起（`Q9Engine.slotOrder`）。
 *
 * 盯死兩件事：
 *  - **多過一頁嘅第一頁一定要 `1`~`9` 順住排** —— 嗰個格號就係隻字個碼最後嗰個
 *    數字，調過位就即刻累到所有打熟咗嘅手勢（2026-08-28 真係整錯過一次）。
 *  - 得一頁嗰陣**最後撳嗰個碼排最前**，其餘照 `5 4 6 2 8 1 3 7 9`。
 */
class SlotOrderTest {

    private val centre = listOf(5, 4, 6, 2, 8, 1, 3, 7, 9)
    private val natural = (1..9).toList()

    @Test fun `得一頁就成頁由中間格排起`() {
        assertEquals(centre, Q9Engine.slotOrder(page = 0, totalPage = 1, priority = 0))
    }

    @Test fun `唔止一頁嘅第一頁順住由 1 排到 9`() {
        for (total in 2..5) {
            assertEquals(natural, Q9Engine.slotOrder(page = 0, totalPage = total, priority = 0))
        }
    }

    @Test fun `第二頁開始由中間格排起`() {
        for (page in 1..4) {
            assertEquals(centre, Q9Engine.slotOrder(page = page, totalPage = 5, priority = 0))
        }
    }

    /** 打 `159`、出得一版字 → `9` 抽出嚟排頭，其餘照舊 */
    @Test fun `得一頁嗰陣最後撳嗰個碼排最前`() {
        assertEquals(
            listOf(9, 5, 4, 6, 2, 8, 1, 3, 7),
            Q9Engine.slotOrder(page = 0, totalPage = 1, priority = 9)
        )
        // 本來就排頭嗰個（`5`）攞嚟做優先，個表唔應該有任何變化
        assertEquals(centre, Q9Engine.slotOrder(page = 0, totalPage = 1, priority = 5))
        // 每個碼做優先都要係「自己排頭 + 其餘照 SLOT_ORDER」，一個唔少一個唔多
        for (p in 1..9) {
            val order = Q9Engine.slotOrder(page = 0, totalPage = 1, priority = p)
            assertEquals(p, order.first())
            assertEquals(centre.filter { it != p }, order.drop(1))
            assertEquals(natural, order.sorted())
        }
    }

    /** 唔止一頁就冇呢個特例：第一頁要留返俾字碼，第二頁隻手指老早郁咗去撳「下頁」 */
    @Test fun `唔止一頁嗰陣唔理最後撳嗰個碼`() {
        assertEquals(natural, Q9Engine.slotOrder(page = 0, totalPage = 3, priority = 9))
        assertEquals(centre, Q9Engine.slotOrder(page = 1, totalPage = 3, priority = 9))
    }

    /** 冇碼可攞（`0` 收尾、同音／關聯字表）就照原本次序 */
    @Test fun `冇優先碼就照原本次序`() {
        for (p in listOf(0, -1, 10)) {
            assertEquals(centre, Q9Engine.slotOrder(page = 0, totalPage = 1, priority = p))
        }
    }
}
