package tt.ime.riverine

import tt.ime.riverine.core.TTEngine
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 選字擺入九宮格，一頁啲字由邊格排起（`TTEngine.slotOrder`）。
 *
 * 盯死一件事：**第一頁永遠 `1`~`9`，冇任何例外** —— 嗰個格號就係隻字個碼最後
 * 嗰個數字，調過位就即刻累到所有打熟咗嘅手勢（2026-08-28 試過兩次特例：
 * 「成個表得一頁就中間格行先」同「啱啱撳嗰個碼行先」，兩次都收返）。
 */
class SlotOrderTest {

    private val centre = listOf(5, 4, 6, 2, 8, 1, 3, 7, 9)
    private val natural = (1..9).toList()

    @Test fun `第一頁順住由 1 排到 9`() {
        assertEquals(natural, TTEngine.slotOrder(0))
    }

    @Test fun `第二頁開始由中間格排起`() {
        for (page in 1..4) assertEquals(centre, TTEngine.slotOrder(page))
    }

    /** 九個格一個唔少一個唔多 */
    @Test fun `每頁都係 1 到 9 九個格`() {
        for (page in 0..4) assertEquals(natural, TTEngine.slotOrder(page).sorted())
    }
}
