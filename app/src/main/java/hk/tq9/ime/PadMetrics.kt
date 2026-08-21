package hk.tq9.ime

import android.content.Context
import android.util.TypedValue
import hk.tq9.core.PadAlign
import hk.tq9.core.Prefs
import kotlin.math.max
import kotlin.math.min

/**
 * 中文九宮格嘅尺寸計算。
 *
 * 重點：闊 screen（摺機／平板／打橫）唔可以任由九宮格拉到變長方形，
 * 所以超過 setting 入面嘅 max width / max height 之後就定形，
 * 剩低嘅空間交返俾 user 用 [PadAlign] 決定點擺。
 *
 * 高度就永遠用盡：鍵盤唔會浮起留空喺下面，想高啲矮啲就改 [Prefs.heightScale]
 * （工具 bar 嗰粒掣上下拖，或者設定頁嗰條 slider）。
 */
class PadMetrics(ctx: Context, availW: Int, val cols: Int = 5, val rows: Int = 4) {

    val align: PadAlign = Prefs.align(ctx)

    val cellW: Float
    val cellH: Float
    val offsetX: Float
    val contentW: Float

    init {
        val dm = ctx.resources.displayMetrics
        fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, dm)

        val maxW = min(availW.toFloat(), dp(Prefs.maxWidthDp(ctx).toFloat()))
        val maxH = dp(Prefs.maxHeightDp(ctx).toFloat())
        val scale = Prefs.keyScale(ctx)

        var unit = min(maxW / cols, maxH / rows) * scale
        unit = min(unit, availW.toFloat() / cols)
        unit = max(unit, dp(32f))

        // 正方格喺手機上面太高，預設壓扁 20%（設定入面可以校）
        val hRatio = Prefs.keyHeightRatio(ctx)
        cellW = if (align == PadAlign.STRETCH) availW.toFloat() / cols else unit
        cellH = unit * hRatio * Prefs.heightScale(ctx)
        contentW = cellW * cols

        val slack = max(0f, availW - contentW)
        offsetX = when (align) {
            PadAlign.STRETCH -> 0f
            PadAlign.RIGHT_GAP -> 0f                                   // 右邊留白 → 內容貼左
            PadAlign.LEFT_GAP -> slack                                 // 左邊留白 → 內容貼右
        }
    }

    val totalHeight: Float get() = cellH * rows

    companion object {
        /**
         * 所有鍵盤（中文九宮格、英文、符號、純數字、emoji）**成塊嘅高度**都係呢個。
         *
         * 英文開咗數字行就有 5 行、符號頁有 5 行、中文永遠 4 行 —— 行數唔同，
         * 但總高度一樣，即係英文每行會矮少少。咁樣中英文切換先唔會成個窗跳高跳低。
         */
        fun padHeightPx(ctx: Context, availW: Int): Float = PadMetrics(ctx, availW).totalHeight

        /** 一行行嗰啲鍵盤（英文／符號／emoji）用嘅行高 */
        fun rowHeightPx(ctx: Context, rowHeightDp: Float, rowCount: Int): Float {
            val dm = ctx.resources.displayMetrics
            fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, dm)
            val d = dp(rowHeightDp)
            val cap = dp(Prefs.maxHeightDp(ctx).toFloat()) / max(1, rowCount)
            val h = max(dp(34f), min(d * Prefs.keyScale(ctx), cap * 1.15f))
            return h * Prefs.heightScale(ctx)
        }

        /** emoji 表／剪貼簿一類 view 想同普通鍵盤一樣高嗰陣用 */
        fun defaultPadHeightPx(ctx: Context): Float {
            val w = ctx.resources.displayMetrics.widthPixels
            return if (w > 0) padHeightPx(ctx, w) else rowHeightPx(ctx, 52f, 4) * 4
        }
    }
}
