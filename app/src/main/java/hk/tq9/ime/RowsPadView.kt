package hk.tq9.ime

import android.content.Context
import android.graphics.Canvas
import kotlin.math.roundToInt

/** 一行行、按 weight 分闊度嘅鍵盤（英文／符號／純數字都用呢個） */
abstract class RowsPadView(context: Context) : KeyboardBaseView(context) {

    protected abstract fun rows(): List<List<Key>>

    /**
     * 高度跟中文九宮格，唔係逐行算 —— 英文有 4~5 行、符號有 5 行、中文永遠 4 行，
     * 總高度一樣，行數多嗰啲每行就矮少少。轉鍵盤先唔會成個窗跳高跳低。
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, PadMetrics.padHeightPx(context, w).roundToInt())
    }

    /** 回傳 (左邊起點, 可用闊度)，預設用盡成行 */
    protected open fun contentBounds(w: Int): Pair<Float, Float> = 0f to w.toFloat()

    override fun buildLayout(w: Int, h: Int) {
        val rs = rows()
        val rh = h.toFloat() / rs.size
        val (ox, cw) = contentBounds(w)
        for ((r, row) in rs.withIndex()) {
            val total = row.sumOf { it.weight.toDouble() }.toFloat()
            var x = ox
            // 最後一格夾硬去到最右，最後一行夾硬去到最底：唔可以喺邊位留條撳唔到嘅隙
            for ((i, k) in row.withIndex()) {
                val kw = cw * (k.weight / total)
                val b = KeyBox(k)
                val right = if (i == row.size - 1) ox + cw else x + kw
                val bottom = if (r == rs.size - 1) h.toFloat() else (r + 1) * rh
                b.set(x, r * rh, right, bottom)
                boxes.add(b)
                x += kw
            }
        }
    }

    fun rebuild() {
        requestLayout()
        relayout()
    }

    override fun drawKey(canvas: Canvas, box: KeyBox, isDown: Boolean) {
        val k = box.key
        val face = when {
            !keyEnabled(k) -> theme.keyDisabled
            isDown -> theme.keyFaceDown
            k.accent -> theme.keyAccent
            isFunctionKey(k) -> theme.keyFaceAlt
            else -> theme.keyFace
        }
        drawFace(canvas, box, face)
        drawLabel(
            canvas, box, displayLabel(k),
            sizeRatio = if (k.bigLabel) 0.44f else 0.36f,
            color = when {
                !keyEnabled(k) -> theme.textDim
                k.accent -> theme.onAccentText
                else -> theme.text
            }
        )
        if (k.hint.isNotEmpty()) drawCornerHint(canvas, box, k.hint)
        if (k.hintRight.isNotEmpty()) drawCornerHintRight(canvas, box, k.hintRight)
    }

    protected open fun displayLabel(k: Key): String = labelOf(k)

    protected open fun isFunctionKey(k: Key): Boolean = when (k.action) {
        KeyAction.CHAR -> false
        else -> true
    }
}
