package tt.ime.riverine.ime

import kotlin.math.roundToInt

/**
 * 浮動鍵盤張卡喺螢幕入面嘅位置：分數（0～1）↔ pixel，同埋唔好拖出畫面。
 *
 * 純函數、唔接觸 Android UI，所以 JVM unit test 盯得死。
 */
object FloatGeom {

    /**
     * 將張卡嘅左上角夾返入可用範圍（螢幕減四邊 inset：status bar／導覽列／cutout）。
     * 張卡高過可用高度就釘喺頂；闊過就釘喺左。
     */
    fun clamp(
        x: Int, y: Int, cardW: Int, cardH: Int,
        screenW: Int, screenH: Int,
        insetL: Int, insetT: Int, insetR: Int, insetB: Int
    ): Pair<Int, Int> {
        val minX = insetL
        val minY = insetT
        val maxX = (screenW - insetR - cardW).coerceAtLeast(minX)
        val maxY = (screenH - insetB - cardH).coerceAtLeast(minY)
        return x.coerceIn(minX, maxX) to y.coerceIn(minY, maxY)
    }

    /** [fx]/[fy] 0～1 = 可用範圍嘅最左／最頂 → 最右／最底 */
    fun fromFrac(
        fx: Float, fy: Float, cardW: Int, cardH: Int,
        screenW: Int, screenH: Int,
        insetL: Int, insetT: Int, insetR: Int, insetB: Int
    ): Pair<Int, Int> {
        val minX = insetL
        val minY = insetT
        val rangeX = (screenW - insetR - cardW - minX).coerceAtLeast(0)
        val rangeY = (screenH - insetB - cardH - minY).coerceAtLeast(0)
        val x = minX + (fx.coerceIn(0f, 1f) * rangeX).roundToInt()
        val y = minY + (fy.coerceIn(0f, 1f) * rangeY).roundToInt()
        return clamp(x, y, cardW, cardH, screenW, screenH, insetL, insetT, insetR, insetB)
    }

    fun toFrac(
        x: Int, y: Int, cardW: Int, cardH: Int,
        screenW: Int, screenH: Int,
        insetL: Int, insetT: Int, insetR: Int, insetB: Int
    ): Pair<Float, Float> {
        val minX = insetL
        val minY = insetT
        val rangeX = (screenW - insetR - cardW - minX).coerceAtLeast(0)
        val rangeY = (screenH - insetB - cardH - minY).coerceAtLeast(0)
        val fx = if (rangeX <= 0) 0.5f else ((x - minX) / rangeX.toFloat()).coerceIn(0f, 1f)
        val fy = if (rangeY <= 0) 0.5f else ((y - minY) / rangeY.toFloat()).coerceIn(0f, 1f)
        return fx to fy
    }
}
