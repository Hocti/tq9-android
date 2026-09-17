package tt.ime.riverine.ime

import android.content.Context
import android.util.TypedValue
import tt.ime.riverine.core.PadAlign
import tt.ime.riverine.core.PadGroup
import tt.ime.riverine.core.Prefs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 鍵盤本體嘅尺寸計算。
 *
 * 重點：闊 screen（摺機／平板／打橫）唔可以任由鍵盤拉到變長方形，
 * 所以超過 setting 入面嘅 max width / max height 之後就定形，
 * 剩低嘅空間交返俾 user 用 [PadAlign] 決定點擺。
 *
 * 高度就永遠用盡：鍵盤唔會浮起留空喺下面，想高啲矮啲就改 [Prefs.heightScale]
 * （工具 bar 嗰粒掣上下拖，或者設定頁嗰條 slider）。
 *
 * [group] 話畀佢知攞邊套大細設定：中文九宮格＋純數字係一套，英文＋符號另一套，
 * 兩套仲要再按螢幕尺寸分開存（見 `Prefs.profKey`）。英文／符號嗰邊係一行行排，
 * 唔使 [cellW]，但係一樣攞呢度嘅 [totalHeight]／[offsetX]／[contentW] ——
 * **闊度嗰條式兩組唔同**（見 `init` 入面 `contentW`）：九宮格要保持格仔嘅高闊比，
 * 英數就由最窄到成個螢幕線性拉。
 */
class PadMetrics(
    ctx: Context,
    availW: Int,
    val cols: Int = 5,
    val rows: Int = 4,
    val group: PadGroup = PadGroup.CJK
) {

    val align: PadAlign = Prefs.align(ctx, group)

    val cellW: Float
    val cellH: Float
    val offsetX: Float
    val contentW: Float

    /**
     * 真正用咗嘅高度倍數。多數就係 [Prefs.heightScale]，**除非**闊 screen
     * 未校過高度而撞到「最多半個螢幕」嗰個封頂（見 [AUTO_HEIGHT_RATIO]）——
     * 嗰陣呢度細過設定嗰個數。工具列粒掣上下拖就係由呢個數開始加減
     * （見 `TTInputMethodService.onSizeDrag`），唔係一拖就由封頂彈返去 100%。
     */
    val heightScale: Float

    init {
        val dm = ctx.resources.displayMetrics
        fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, dm)

        val unit = baseUnitPx(ctx, availW, cols, rows)

        // 正方格喺手機上面太高，預設壓扁 20%（設定入面可以校）
        val hRatio = Prefs.keyHeightRatio(ctx)
        // 高度**唔跟**闊度倍數行：左右拉淨係應該改到闊度，唔可以順手拉埋高度
        // （上下拉係另一件事，行 Prefs.heightScale）
        val wantH = if (align == PadAlign.FLOATING) Prefs.floatHeightScale(ctx, group)
            else Prefs.heightScale(ctx, group)
        // 浮動窗四角可以拉大細，唔好再用「闊 screen 未校過就封半個螢幕」嗰個 cap
        heightScale = if (align == PadAlign.FLOATING) wantH
            else autoCapped(ctx, unit * hRatio, wantH, group)
        cellH = unit * hRatio * heightScale
        // 本體再窄都要有 [MIN_CONTENT_DP] 咁闊（螢幕本身窄過呢個數就用盡螢幕）——
        // 拉到得幾格咁窄嘅鍵盤，每粒鍵細過隻手指，根本撳唔中
        val minContent = min(dp(MIN_CONTENT_DP), availW.toFloat())
        // 左右拆開嗰陣，兩橛夾埋唔可以鋪滿成行：中間永遠要留返 [MIN_SPLIT_GAP_DP]
        // 咁闊條罅，唔係拉到最闊就兩橛併埋，同「拉闊」一模一樣，分割等於冇咗
        // （2026-08-28 user 踩到）。最闊嗰個位收窄咗，拉嘅範圍照樣由頭用到尾。
        val maxContent =
            if (align == PadAlign.SPLIT) max(minContent, availW - dp(MIN_SPLIT_GAP_DP))
            else availW.toFloat()
        val ws = if (align == PadAlign.FLOATING) Prefs.floatWidthScale(ctx, group)
            else Prefs.widthScale(ctx, group)
        contentW = when {
            // 拉闊、同埋浮動窗入面：用盡 [availW]。浮動嗰陣張卡已經係想要嗰個闊度
            // （見 [floatingWidthPx]），入面啲鍵再縮一次就變雙重倍數。
            align == PadAlign.STRETCH || align == PadAlign.FLOATING -> availW.toFloat()
            // 英數鍵盤：一行行排，格仔闊度同高度冇關係，所以闊度**直接由倍數線性拉**
            // ——最窄 [MIN_CONTENT_DP]、最闊用盡成個螢幕，成段都拉得到。
            // （跟九宮格嗰條「`unit` × 倍數」就會俾 `unit` 封住頂，闊 screen 拉極
            // 都去唔到盡頭，窄 screen 就成段都撞住個最窄值，變成點拉都一個闊度。）
            group == PadGroup.LATIN -> {
                val t = ((ws - Prefs.MIN_WIDTH_SCALE) /
                    (Prefs.MAX_WIDTH_SCALE - Prefs.MIN_WIDTH_SCALE)).coerceIn(0f, 1f)
                minContent + (maxContent - minContent) * t
            }
            // 九宮格：格仔要保持返個高闊比，所以由 `unit`（高度嗰個 unit）出闊度
            else -> max(min(unit * ws * cols, maxContent), minContent)
        }
        cellW = contentW / cols

        val slack = max(0f, availW - contentW)
        offsetX = when (align) {
            PadAlign.STRETCH -> 0f
            PadAlign.RIGHT_GAP -> 0f                                   // 右邊留白 → 內容貼左
            PadAlign.LEFT_GAP -> slack                                 // 左邊留白 → 內容貼右
            PadAlign.CENTER -> slack / 2f                              // 兩邊各留一半白
            PadAlign.SPLIT -> 0f                                       // 兩橛各自貼邊，見 RowsPadView
            PadAlign.FLOATING -> 0f                                    // 張卡就係成個鍵盤闊
        }
    }

    /** [PadAlign.SPLIT] 之下每橛幾闊（兩橛夾埋 = [contentW]） */
    val halfW: Float get() = contentW / 2f

    val totalHeight: Float get() = cellH * rows

    /**
     * 闊 screen（打橫／摺機內屏／平板）**未校過高度**嗰陣，成塊鍵盤最多佔
     * 螢幕 [AUTO_HEIGHT_RATIO]（一半）（2026-09-11 user 要求：「首次打橫時
     * 經常高到遮住整個screen」）。
     *
     * 點解唔直接改細個預設倍數：倍數係乘落 `unit` 度，而 `unit` 本身跟
     * 「最大闊度／最大高度」兩條 slider 行，同螢幕幾高冇關係 —— 打橫嗰陣
     * 300dp 高嘅 `maxH` 夾 4 行，出嚟就已經超過半個螢幕。所以要喺呢度
     * **度返真螢幕幾高**先封得到頂。
     *
     * 封頂淨係當 user 未郁過高度（[Prefs.heightScaleSet]）先做 —— 撳過設定頁
     * 條 slider、或者喺工具列粒掣度拖過一下，之後就完全聽返 user 嗰個數。
     * 再細都唔會細過 [Prefs.MIN_HEIGHT_SCALE]（細過就撳唔中）。
     */
    private fun autoCapped(ctx: Context, rowBase: Float, want: Float, group: PadGroup): Float {
        if (rowBase <= 0f) return want
        if (Prefs.screenWidthDp(ctx) <= Prefs.SPLIT_MIN_WIDTH_DP) return want
        if (Prefs.heightScaleSet(ctx, group)) return want
        val cap = ctx.resources.displayMetrics.heightPixels * AUTO_HEIGHT_RATIO / rows
        if (cap <= 0f || rowBase * want <= cap) return want
        return max(cap / rowBase, Prefs.MIN_HEIGHT_SCALE)
    }

    companion object {
        /** 見 [autoCapped] */
        const val AUTO_HEIGHT_RATIO = 0.5f

        /**
         * 鍵盤本體最少要咁闊。螢幕本身窄過呢個數就用盡螢幕闊度
         * （細機唔會被迫到橫向 scroll），闊過就一定夠 320dp。
         *
         * 拉窄（工具 bar 最左嗰粒左右拖）同 [Prefs.KEY_WIDTH_SCALE] 都收唔過呢條線。
         */
        const val MIN_CONTENT_DP = 320f

        /**
         * [PadAlign.SPLIT] 之下，兩橛中間最少要留咁闊條罅。
         *
         * 冇咗呢條線，`widthScale` 拉到盡（或者長撳粒掣「一下子拉到最闊」）
         * 就會兩橛併埋鋪滿成行 —— 睇落同「拉闊」一模一樣，用家以為分割壞咗。
         * 而家最闊 = 螢幕闊度減呢條罅，兩橛永遠分得開。
         */
        const val MIN_SPLIT_GAP_DP = 80f

        /**
         * 一組鍵盤（[PadGroup]）**成塊嘅高度**。
         *
         * 同一組入面行數唔同都係一樣高：英文開咗數字行有 5 行、符號頁有 5 行、
         * 中文永遠 4 行，行數多嗰啲每行矮少少，咁切換嗰陣個窗先唔會跳高跳低。
         * 兩組之間就各有各高度（各自喺鍵盤度拖出嚟）。
         */
        fun padHeightPx(ctx: Context, availW: Int, group: PadGroup = PadGroup.CJK): Float =
            PadMetrics(ctx, availW, group = group).totalHeight

        /**
         * 浮動窗應該幾闊：用 [Prefs.floatWidthScale] 行返「置中」嗰條拉闊公式，
         * 但係以**成個螢幕**做 [availW]（唔係張卡自己）。張卡 set 咗呢個闊度之後，
         * 入面啲 pad 見 [PadAlign.FLOATING] 就用盡張卡，唔會再縮一次。
         */
        fun floatingWidthPx(ctx: Context, screenW: Int, group: PadGroup): Int {
            val dm = ctx.resources.displayMetrics
            fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, dm)
            val cols = 5
            val rows = 4
            val unit = baseUnitPx(ctx, screenW, cols, rows)
            val minContent = min(dp(MIN_CONTENT_DP), screenW.toFloat())
            val maxContent = screenW.toFloat()
            val ws = Prefs.floatWidthScale(ctx, group)
            val w = if (group == PadGroup.LATIN) {
                val t = ((ws - Prefs.MIN_WIDTH_SCALE) /
                    (Prefs.MAX_WIDTH_SCALE - Prefs.MIN_WIDTH_SCALE)).coerceIn(0f, 1f)
                minContent + (maxContent - minContent) * t
            } else {
                max(min(unit * ws * cols, maxContent), minContent)
            }
            return w.roundToInt().coerceIn(minContent.roundToInt(), screenW)
        }

        /**
         * 想要嘅浮動內寬 → [Prefs.floatWidthScale]。同 [floatingWidthPx] 對調。
         */
        fun floatWidthScaleFor(ctx: Context, screenW: Int, innerW: Int, group: PadGroup): Float {
            val dm = ctx.resources.displayMetrics
            fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, dm)
            val cols = 5
            val minContent = min(dp(MIN_CONTENT_DP), screenW.toFloat())
            val maxContent = screenW.toFloat()
            val w = innerW.toFloat().coerceIn(minContent, maxContent)
            return if (group == PadGroup.LATIN) {
                val span = (maxContent - minContent).coerceAtLeast(1f)
                val t = ((w - minContent) / span).coerceIn(0f, 1f)
                Prefs.MIN_WIDTH_SCALE + t * (Prefs.MAX_WIDTH_SCALE - Prefs.MIN_WIDTH_SCALE)
            } else {
                val unit = baseUnitPx(ctx, screenW, cols, 4)
                val den = (unit * cols).coerceAtLeast(1f)
                (w / den).coerceIn(Prefs.MIN_WIDTH_SCALE, Prefs.MAX_WIDTH_SCALE)
            }
        }

        /**
         * 想要嘅鍵盤本體高度 → [Prefs.floatHeightScale]。
         * [availW] 用張卡內寬（[PadMetrics] 計 `unit` 跟呢個闊）。
         */
        fun floatHeightScaleFor(ctx: Context, availW: Int, padHeightPx: Int, group: PadGroup): Float {
            val unit = baseUnitPx(ctx, availW, cols = 5, rows = 4)
            val hRatio = Prefs.keyHeightRatio(ctx)
            val base = unit * hRatio * 4f
            if (base <= 1f) return Prefs.floatHeightScale(ctx, group)
            return (padHeightPx / base).coerceIn(Prefs.MIN_HEIGHT_SCALE, Prefs.MAX_HEIGHT_SCALE)
        }

        private fun baseUnitPx(ctx: Context, availW: Int, cols: Int, rows: Int): Float {
            val dm = ctx.resources.displayMetrics
            fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, dm)
            val maxW = min(availW.toFloat(), dp(Prefs.maxWidthDp(ctx).toFloat()))
            val maxH = dp(Prefs.maxHeightDp(ctx).toFloat())
            val scale = Prefs.keyScale(ctx)
            var unit = min(maxW / cols, maxH / rows) * scale
            unit = min(unit, availW.toFloat() / cols)
            return max(unit, dp(32f))
        }

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
