package tt.ime.riverine.core

import android.content.Context

/**
 * 闊 screen 非浮動時系統導覽列底嘅「轉輸入法／收起鍵盤」會消失，
 * 所以鍵盤自己補返四粒 chrome 掣：浮動、貼邊循環、輸入法選擇表、收起鍵盤。
 *
 * 中文／純數字：四粒打直排喺留白最外側（見 `WideChromeRail`）。
 * 英文／符號：四粒插喺工具列最左（切換掣後面）。
 * 功能表本身已經有嘅就隱藏，避免同一樣嘢出兩次。
 */
object PadChrome {

    val FUNCS: List<PadFunc> = listOf(
        PadFunc.FLOAT, PadFunc.ALIGN, PadFunc.IME_PICKER, PadFunc.HIDE_KEYBOARD
    )

    fun wide(ctx: Context): Boolean =
        Prefs.screenWidthDp(ctx) > Prefs.SPLIT_MIN_WIDTH_DP

    fun floating(ctx: Context, g: PadGroup): Boolean =
        Prefs.align(ctx, g) == PadAlign.FLOATING

    fun wideDocked(ctx: Context, g: PadGroup): Boolean =
        wide(ctx) && !floating(ctx, g)

    /**
     * 功能表要收起邊幾粒。浮動時入浮動已經唔再係「改變大小」其中一段，
     * 所以 [PadFunc.ALIGN] 唔出；[PadFunc.FLOAT] 改做「取消浮動」插返入
     * 功能表（見 [injectedBarFuncs]），原位隱藏以免出兩粒。闊 screen
     * 非浮動就四粒全部由 chrome 強加，原位隱藏。
     */
    fun hiddenFromTools(wide: Boolean, floating: Boolean): Set<PadFunc> = when {
        floating -> setOf(PadFunc.ALIGN, PadFunc.FLOAT)
        wide -> FUNCS.toSet()
        else -> emptySet()
    }

    fun hiddenFromTools(ctx: Context, g: PadGroup): Set<PadFunc> =
        hiddenFromTools(wide(ctx), floating(ctx, g))

    /**
     * 工具列最左要插入嘅掣。浮動時「取消浮動」搬上功能表；闊 screen
     * 英文／符號非浮動就四粒 chrome。中文／數字用側欄，非浮動就吉。
     */
    fun injectedBarFuncs(wide: Boolean, floating: Boolean, g: PadGroup): List<PadFunc> = when {
        floating -> listOf(PadFunc.FLOAT)
        g == PadGroup.LATIN && wide -> FUNCS
        else -> emptyList()
    }

    fun injectedBarFuncs(ctx: Context, g: PadGroup): List<PadFunc> =
        injectedBarFuncs(wide(ctx), floating(ctx, g), g)

    fun wantCjkRail(wide: Boolean, floating: Boolean, cjkPad: Boolean): Boolean =
        cjkPad && wide && !floating

    fun wantCjkRail(ctx: Context, g: PadGroup, cjkPad: Boolean): Boolean =
        wantCjkRail(wide(ctx), floating(ctx, g), cjkPad)

    /**
     * 工具列真正要砌出嚟嗰行：chrome 強加嘅排頭，跟住設定頁嗰行（撞名嘅收起）。
     */
    fun visibleToolSlots(ctx: Context, g: PadGroup): List<KeyLayout.Slot> {
        val hide = hiddenFromTools(ctx, g)
        val injected = injectedBarFuncs(ctx, g).map { KeyLayout.Slot(it) }
        val rest = KeyLayout.load(ctx).tools.filter { it.tap !in hide }
        return injected + rest
    }
}
