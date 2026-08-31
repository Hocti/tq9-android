package tt.ime.riverine.ime

import android.content.Context
import android.graphics.Rect
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.TextView
import tt.ime.riverine.core.PadGroup
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 關聯字 chip 嘅大細計算。上面條 bar（[OptionBarsView]）同側邊欄（[SidePanelView]）
 * 兩邊個 chip 一模一樣，所以條數擺喺呢度計一次，兩邊共用。
 *
 * ## 點解要用真 `TextView` 度，唔可以問 `Paint`
 *
 * `Paint.getFontMetrics()` 回嘅係**primary typeface（拉丁字型）**嗰套 metrics。
 * 中文字根本唔係佢畫 —— 係跌落 CJK fallback 字型畫嘅，而嗰個字型高成 1.43em，
 * 拉丁嗰個得 1.17em。實機度到（2026-08-29，20sp / density 2.625）：
 *
 * | | px |
 * | --- | --- |
 * | `Paint` 嗰個 `descent - ascent`（拉丁） | 61.5 |
 * | 真正排出嚟嘅 `layout.height`（CJK） | 75 |
 *
 * 用細嗰個數去定條 bar 幾高，chip 就會俾 `AT_MOST` 迫窄（105 → 96），
 * 上下 padding 寫死一樣都冇用，個字實會偏。所以一定要度真嘢。
 *
 * ## 點解 padding 上下唔一樣
 *
 * 就算唔迫窄，`gravity = CENTER` 置中嘅係**成個 line box**，唔係**個字嘅墨**。
 * CJK 個墨喺 box 入面本來就企得高（實機：baseline 上面 box 有 60px 但個墨
 * 淨係去到 44，下面 box 有 15 個墨得 5）—— 即係 box 上面鬆 16、下面鬆 10，
 * 置中完個字就偏低，睇落上面 padding 多過下面。
 *
 * 所以 [padTop] / [padBottom] 係**特登唔對稱**嘅，差額啱啱好抵消返個墨嘅偏移，
 * 令「chip 頂到墨頂」同「墨底到 chip 底」真係一樣。
 */
class CandChip private constructor(
    /** 一行字實際幾高（連字型 fallback 一齊算） */
    val lineH: Int,
    val padTop: Int,
    val padBottom: Int
) {

    /** 一個 chip 連 padding 幾高 */
    val chipH: Int get() = lineH + padTop + padBottom

    companion object {

        /** chip 上下 padding（未計 [padTop] / [padBottom] 嗰個唔對稱修正） */
        const val PAD_DP = 6f

        /** chip 四邊嘅 margin */
        const val MARGIN_DP = 3f

        /**
         * 條 bar 再矮都要咁高。以前係寫死嘅高度 —— 啲功能掣（✖／⇄／▼／工具列）
         * 撳唔撳得中就係睇呢個數，所以字體拉細咗都唔可以再矮。
         */
        const val MIN_BAR_DP = 42f

        /**
         * 度大細用嘅參考字。**唔可以用 chip 自己嗰個字度** —— 唔同字個墨唔同高
         * （「一」淨係一橫，「我」佔成格），逐個 chip 各自置中就會粒粒 baseline
         * 唔同，一行睇落高高低低。同一組入面全部 chip 要共用同一條數。
         *
         * 用返嗰組平時真係會出嘅嘢：中文組出中文字，英文組出英文字（`Ag` ——
         * 有大階頂到 cap height，又有 `g` 拖落 descender）。
         */
        private fun refText(group: PadGroup) = if (group == PadGroup.LATIN) "Ag" else "字"

        fun measure(ctx: Context, textSp: Float, group: PadGroup): CandChip {
            val dm = ctx.resources.displayMetrics
            fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, dm)

            val ref = refText(group)
            val probe = TextView(ctx).apply {
                text = ref
                textSize = textSp
                gravity = Gravity.CENTER
                includeFontPadding = false
                setPadding(0, 0, 0, 0)
            }
            val unspec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            probe.measure(unspec, unspec)

            val layout = probe.layout
            val pad = dp(PAD_DP)
            // 排唔到版（理論上唔會）就跌返落對稱 padding，總好過計出個負數
            if (layout == null || layout.lineCount == 0) {
                val p = pad.roundToInt()
                return CandChip(probe.measuredHeight, p, p)
            }

            val lineH = layout.height
            val baseline = layout.getLineBaseline(0)
            val ink = Rect()
            probe.paint.getTextBounds(ref, 0, ref.length, ink)

            // 要「chip 頂 → 墨頂」等於「墨底 → chip 底」：
            //   padTop + baseline + ink.top == padBottom + lineH - baseline - ink.bottom
            // 即係 padTop - padBottom = lineH - 2*baseline - ink.top - ink.bottom
            val skew = (lineH - 2 * baseline - ink.top - ink.bottom) / 2f
            return CandChip(
                lineH,
                (pad + skew).roundToInt().coerceAtLeast(0),
                (pad - skew).roundToInt().coerceAtLeast(0)
            )
        }

        /**
         * 條 bar 幾高：一個 chip 連上下 margin，最矮 [MIN_BAR_DP]。
         * 一定要夠位擺得落成個 chip，唔係就會俾 `AT_MOST` 迫窄（見成個 class 嘅講法）。
         */
        fun barHeightPx(ctx: Context, chip: CandChip): Int {
            val dm = ctx.resources.displayMetrics
            fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, dm)
            return max(dp(MIN_BAR_DP), chip.chipH + dp(MARGIN_DP * 2)).roundToInt()
        }
    }
}
