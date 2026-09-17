package tt.ime.riverine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tt.ime.riverine.core.PadChrome
import tt.ime.riverine.core.PadFunc
import tt.ime.riverine.core.PadGroup

class PadChromeTest {

    @Test fun `闊 screen 非浮動要隱藏四粒 chrome 功能`() {
        val hide = PadChrome.hiddenFromTools(wide = true, floating = false)
        assertEquals(PadChrome.FUNCS.toSet(), hide)
    }

    @Test fun `浮動時收起改變大小並插入取消浮動`() {
        val hide = PadChrome.hiddenFromTools(wide = true, floating = true)
        assertEquals(setOf(PadFunc.ALIGN, PadFunc.FLOAT), hide)
        assertEquals(
            listOf(PadFunc.FLOAT),
            PadChrome.injectedBarFuncs(wide = true, floating = true, PadGroup.LATIN)
        )
        assertEquals(
            listOf(PadFunc.FLOAT),
            PadChrome.injectedBarFuncs(wide = true, floating = true, PadGroup.CJK)
        )
        assertFalse(PadFunc.IME_PICKER in hide)
        assertFalse(PadFunc.HIDE_KEYBOARD in hide)
    }

    @Test fun `窄 screen 唔隱藏`() {
        assertTrue(PadChrome.hiddenFromTools(wide = false, floating = false).isEmpty())
    }

    @Test fun `英文闊 screen 非浮動先插入四粒`() {
        assertEquals(
            PadChrome.FUNCS,
            PadChrome.injectedBarFuncs(wide = true, floating = false, PadGroup.LATIN)
        )
        assertTrue(
            PadChrome.injectedBarFuncs(wide = true, floating = false, PadGroup.CJK).isEmpty()
        )
        assertEquals(
            listOf(PadFunc.FLOAT),
            PadChrome.injectedBarFuncs(wide = true, floating = true, PadGroup.LATIN)
        )
        assertTrue(
            PadChrome.injectedBarFuncs(wide = false, floating = false, PadGroup.LATIN).isEmpty()
        )
    }

    @Test fun `中文純數字闊 screen 非浮動先出側欄 chrome`() {
        assertTrue(PadChrome.wantCjkRail(wide = true, floating = false, cjkPad = true))
        assertFalse(PadChrome.wantCjkRail(wide = true, floating = false, cjkPad = false))
        assertFalse(PadChrome.wantCjkRail(wide = true, floating = true, cjkPad = true))
        assertFalse(PadChrome.wantCjkRail(wide = false, floating = false, cjkPad = true))
    }
}
