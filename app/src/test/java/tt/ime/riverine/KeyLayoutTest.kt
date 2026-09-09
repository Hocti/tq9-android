package tt.ime.riverine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tt.ime.riverine.core.KeyLayout
import tt.ime.riverine.core.KeyLayout.Area
import tt.ime.riverine.core.KeyLayout.Cell
import tt.ime.riverine.core.PadFunc

/**
 * 「按鍵排位」嗰套規矩（`KeyLayout.checkDrop` / `apply`）。
 *
 * 盯死一件事：**擺唔落嘅嘢一定擺唔到**。設定頁個拖放介面同埋撳一下彈嗰個選單
 * 兩條路都係問呢度，所以呢度漏咗一條，user 就有機會存到個殘廢排位落去，
 * 返到鍵盤先發現打唔到字（例如冇咗 `⏎`）。
 *
 * 讀／寫 pref 嗰幾個（`load` / `save`）唔喺度試 —— 要 `Context`，
 * 而且 JVM test 入面 `android.*` 係 stub（見 `AGENTS.md`）。
 */
class KeyLayoutTest {

    private val def = KeyLayout.DEFAULT

    /** 左欄第 0 行嘅短撳（預設 = 關聯字） */
    private val leftTap0 = Cell(Area.LEFT, 0, false)
    private val leftLong0 = Cell(Area.LEFT, 0, true)

    /** 右欄第 1 行 = `␣`（[PadFunc.tapOnly] 嗰批） */
    private val spaceCell = Cell(Area.RIGHT, 1, false)
    private val spaceLong = Cell(Area.RIGHT, 1, true)

    // ---- 預設排位本身要企得住 ---------------------------------------------

    @Test fun `預設排位冇缺必用鍵`() {
        assertNull(KeyLayout.missingRequired(def))
    }

    @Test fun `預設排位左右欄冇重複`() {
        val f = def.sideFuncs()
        assertEquals(f.size, f.toSet().size)
    }

    @Test fun `預設排位工具列冇重複`() {
        val f = def.toolFuncs()
        assertEquals(f.size, f.toSet().size)
    }

    /** 左下角嗰粒長撳 = 下一個輸入法（2026-09-09 user 指定嘅預設） */
    @Test fun `預設左下角短撳 Eng 長撳轉輸入法`() {
        assertEquals(PadFunc.TO_LATIN, def.left[3].tap)
        assertEquals(PadFunc.IME_NEXT, def.left[3].effectiveLong)
    }

    // ---- 擺得邊 -----------------------------------------------------------

    @Test fun `必用鍵擺唔入工具列`() {
        for (f in PadFunc.entries.filter { it.required }) {
            assertNotNull("$f 唔應該入得工具列",
                KeyLayout.checkDrop(def, Cell(Area.TOOLS, 0, false), f))
        }
    }

    @Test fun `改變大小擺唔入左右欄`() {
        assertNotNull(KeyLayout.checkDrop(def, leftTap0, PadFunc.ALIGN))
    }

    @Test fun `改變大小喺工具列之內調得位`() {
        // 同工具列第二格對調 —— 一路都係喺工具列，所以准
        assertNull(KeyLayout.checkDrop(
            def, Cell(Area.TOOLS, 1, false), PadFunc.ALIGN, Cell(Area.TOOLS, 0, false)))
    }

    @Test fun `空格刪除換行淨係擺得落短撳`() {
        for (f in PadFunc.entries.filter { it.tapOnly }) {
            assertNotNull("$f 唔應該擺得落長撳", KeyLayout.checkDrop(def, leftLong0, f))
        }
    }

    /** 「放咗之後長撳會 disable」—— 所以擺得落，個長撳自己會冇咗 */
    @Test fun `空格擺落有長撳嘅位會頂走個長撳`() {
        assertNull(KeyLayout.checkDrop(def, leftTap0, PadFunc.SPACE, spaceCell))
        val out = KeyLayout.apply(def, leftTap0, PadFunc.SPACE, spaceCell)
        assertEquals(PadFunc.SPACE, out.left[0].tap)
        assertEquals(PadFunc.NONE, out.left[0].effectiveLong)
    }

    @Test fun `空格個長撳格死咗`() {
        assertEquals(PadFunc.NONE, def.right[1].effectiveLong)
    }

    // ---- 唔准重複 ---------------------------------------------------------

    @Test fun `左右欄唔准有重複`() {
        // 「同音」預設喺左欄第 1 行，唔可以再擺多粒落左欄第 0 行
        assertNotNull(KeyLayout.checkDrop(def, leftTap0, PadFunc.HOMO))
    }

    @Test fun `工具列自己唔准有重複`() {
        // 「貼上」預設已經喺工具列
        assertNotNull(KeyLayout.checkDrop(def, Cell(Area.TOOLS, 3, false), PadFunc.PASTE))
    }

    @Test fun `同一粒可以同時喺工具列同左右欄`() {
        // 「貼上」喺工具列，亦都喺左欄第 0 行嘅長撳，兩邊各計各
        assertEquals(PadFunc.PASTE, def.left[0].effectiveLong)
        assertTrue(PadFunc.PASTE in def.toolFuncs())
    }

    @Test fun `兩格對調唔算重複`() {
        // 左欄第 0 行同左欄第 2 行對調：兩邊都係本來就有嘅嘢，唔應該當撞
        val a = leftTap0
        val b = Cell(Area.LEFT, 2, false)
        assertNull(KeyLayout.checkDrop(def, b, KeyLayout.at(def, a), a))
        val out = KeyLayout.apply(def, b, KeyLayout.at(def, a), a)
        assertEquals(PadFunc.RELATE, out.left[2].tap)
        assertEquals(PadFunc.TO_SYMBOL, out.left[0].tap)
        assertNull(KeyLayout.missingRequired(out))
    }

    // ---- 必用鍵唔准消失 ---------------------------------------------------

    @Test fun `必用鍵拖唔返落 pool`() {
        for ((i, s) in def.right.withIndex()) {
            if (!s.tap.required) continue
            assertNotNull("${s.tap} 唔應該清得走",
                KeyLayout.checkDrop(def, Cell(Area.RIGHT, i, false), PadFunc.NONE))
        }
    }

    @Test fun `必用鍵嗰格覆蓋唔到`() {
        // 右欄第 3 行係 `⏎`，冚死佢就冇咗換行
        assertNotNull(KeyLayout.checkDrop(def, Cell(Area.RIGHT, 3, false), PadFunc.EMOJI))
    }

    @Test fun `必用鍵搬去第二格係得嘅`() {
        // `⏎`（右 3）同「同音」（左 1）對調：`⏎` 仲係喺左右欄，冇消失
        val enter = Cell(Area.RIGHT, 3, false)
        val homo = Cell(Area.LEFT, 1, false)
        assertNull(KeyLayout.checkDrop(def, homo, PadFunc.ENTER, enter))
        val out = KeyLayout.apply(def, homo, PadFunc.ENTER, enter)
        assertEquals(PadFunc.ENTER, out.left[1].tap)
        assertEquals(PadFunc.HOMO, out.right[3].tap)
        assertNull(KeyLayout.missingRequired(out))
    }

    // ---- 短撳吉咗就唔可以淨係得個長撳 ---------------------------------------

    @Test fun `清走短撳連個長撳一齊清`() {
        val out = KeyLayout.clear(def, leftTap0)
        assertEquals(PadFunc.NONE, out.left[0].tap)
        assertEquals(PadFunc.NONE, out.left[0].long)
    }

    @Test fun `吉格擺唔到長撳`() {
        val empty = KeyLayout.clear(def, leftTap0)
        assertNotNull(KeyLayout.checkDrop(empty, leftLong0, PadFunc.EMOJI))
    }

    // ---- 工具列加減 -------------------------------------------------------

    @Test fun `工具列尾加一粒`() {
        val out = KeyLayout.appendTool(def, PadFunc.SC_TOGGLE)
        assertEquals(def.tools.size + 1, out.tools.size)
        assertEquals(PadFunc.SC_TOGGLE, out.tools.last().tap)
    }

    @Test fun `工具列擺唔落必用鍵`() {
        assertEquals(def.tools.size, KeyLayout.appendTool(def, PadFunc.ENTER).tools.size)
    }

    @Test fun `工具列滿咗就唔加`() {
        var l = def
        // 由 pool 挑夠塞滿佢（工具列擺得落嗰啲，而且未用過）
        for (f in KeyLayout.POOL) {
            if (!f.toolOk || f in l.toolFuncs()) continue
            l = KeyLayout.appendTool(l, f)
        }
        assertEquals(KeyLayout.MAX_TOOLS, l.tools.size)
    }

    @Test fun `工具列清走一粒就成粒唔見咗`() {
        val out = KeyLayout.clear(def, Cell(Area.TOOLS, 1, false))
        assertEquals(def.tools.size - 1, out.tools.size)
        assertTrue(PadFunc.PASTE !in out.toolFuncs())
    }

    // ---- 工具列冇長撳 -----------------------------------------------------

    @Test fun `工具列冇長撳格`() {
        assertNotNull(KeyLayout.checkDrop(def, Cell(Area.TOOLS, 0, true), PadFunc.EMOJI))
    }

    @Test fun `預設工具列每粒都冇長撳`() {
        for (s in def.tools) assertEquals(PadFunc.NONE, s.effectiveLong)
    }

    /** 舊排位存過長撳落工具列都好，一經 apply 就會清走 */
    @Test fun `工具列長撳寫唔入去`() {
        val out = KeyLayout.apply(def, Cell(Area.TOOLS, 1, true), PadFunc.EMOJI)
        assertEquals(PadFunc.NONE, out.tools[1].long)
    }

    // ---- pool -------------------------------------------------------------

    @Test fun `pool 冇停用嗰粒`() {
        assertTrue(PadFunc.NONE !in KeyLayout.POOL)
        assertEquals(PadFunc.entries.size - 1, KeyLayout.POOL.size)
    }
}
