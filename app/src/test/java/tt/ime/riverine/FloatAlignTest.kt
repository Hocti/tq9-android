package tt.ime.riverine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tt.ime.riverine.core.PadAlign
import tt.ime.riverine.core.PadGroup
import tt.ime.riverine.core.Prefs
import tt.ime.riverine.ime.CandChip
import tt.ime.riverine.ime.FloatGeom

/**
 * 浮動顯示方式：闊 screen 先揀得；窄咗就唔喺 list 入面。
 * 位置分數 ↔ pixel 夾返畫面入面。
 */
class FloatAlignTest {

    @Test fun `闊 screen 中英兩組都有浮動`() {
        val cjk = Prefs.alignOptions(wide = true, PadGroup.CJK)
        val latin = Prefs.alignOptions(wide = true, PadGroup.LATIN)
        assertTrue(PadAlign.FLOATING in cjk)
        assertTrue(PadAlign.FLOATING in latin)
        assertTrue(PadAlign.LEFT_GAP in cjk)
        assertFalse(PadAlign.LEFT_GAP in latin)
        assertTrue(PadAlign.SPLIT in latin)
    }

    @Test fun `窄 screen 冇浮動`() {
        assertFalse(PadAlign.FLOATING in Prefs.alignOptions(wide = false, PadGroup.CJK))
        assertFalse(PadAlign.FLOATING in Prefs.alignOptions(wide = false, PadGroup.LATIN))
    }

    @Test fun `闊 screen 循環最後一個係浮動`() {
        val opts = Prefs.alignOptions(wide = true, PadGroup.CJK)
        assertEquals(PadAlign.FLOATING, opts.last())
    }

    @Test fun `位置夾喺畫面入面`() {
        val (x, y) = FloatGeom.clamp(
            x = -40, y = 900, cardW = 200, cardH = 100,
            screenW = 800, screenH = 400,
            insetL = 0, insetT = 20, insetR = 0, insetB = 40
        )
        assertEquals(0, x)
        assertEquals(400 - 40 - 100, y)
    }

    @Test fun `分數 1 係貼底靠右`() {
        val (x, y) = FloatGeom.fromFrac(
            fx = 1f, fy = 1f, cardW = 200, cardH = 80,
            screenW = 1000, screenH = 500,
            insetL = 0, insetT = 0, insetR = 0, insetB = 50
        )
        assertEquals(800, x)
        assertEquals(370, y)
        val (fx, fy) = FloatGeom.toFrac(
            x, y, 200, 80, 1000, 500, 0, 0, 0, 50
        )
        assertEquals(1f, fx, 0.001f)
        assertEquals(1f, fy, 0.001f)
    }

    @Test fun `功能表封頂係一行鍵嘅八成`() {
        assertEquals(40, CandChip.capToKeyRow(natural = 120f, keyRowH = 50))
        assertEquals(120, CandChip.capToKeyRow(natural = 120f, keyRowH = 200))
        assertEquals(120, CandChip.capToKeyRow(natural = 120f, keyRowH = 0))
    }

    @Test fun `封頂之後都要夠高擺得落隻字`() {
        assertEquals(48, CandChip.resolveHeight(capped = 40, glyphMin = 48))
        assertEquals(40, CandChip.resolveHeight(capped = 40, glyphMin = 36))
    }

    @Test fun `分數 0 係貼頂靠左`() {
        val (x, y) = FloatGeom.fromFrac(
            fx = 0f, fy = 0f, cardW = 120, cardH = 80,
            screenW = 800, screenH = 400,
            insetL = 10, insetT = 30, insetR = 10, insetB = 20
        )
        assertEquals(10, x)
        assertEquals(30, y)
    }
}
