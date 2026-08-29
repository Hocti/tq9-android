package hk.tq9.ime

import android.content.Context
import android.graphics.Canvas
import hk.tq9.core.PadAlign
import hk.tq9.core.PadGroup
import kotlin.math.roundToInt

/** 一行行、按 weight 分闊度嘅鍵盤（英文／符號／純數字都用呢個） */
abstract class RowsPadView(context: Context) : KeyboardBaseView(context) {

    protected abstract fun rows(): List<List<Key>>

    /**
     * 攞邊套大細／字體設定。英文同符號自成一套（[PadGroup.LATIN]），
     * 純數字 keypad 就跟返中文九宮格（見 [NumberPadView]）。
     */
    override val padGroup: PadGroup get() = PadGroup.LATIN

    /**
     * 高度唔係逐行算，係成組一個高度 —— 英文有 4~5 行、符號有 5 行，
     * 總高度一樣，行數多嗰啲每行就矮少少，轉頁先唔會成個窗跳高跳低。
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, PadMetrics.padHeightPx(context, w, padGroup).roundToInt())
    }

    /**
     * 闊度同擺位跟返 [padGroup] 嗰套設定（同中文九宮格一樣，喺工具列最左嗰粒掣
     * 左右拖就拉得闊窄）：「拉闊」用盡成行，「靠左／靠右」貼一邊，
     * [PadAlign.SPLIT] 就拆做兩橛貼實兩邊。
     */
    override fun buildLayout(w: Int, h: Int) {
        val rs = rows()
        val rh = h.toFloat() / rs.size
        val m = PadMetrics(context, w, group = padGroup)
        if (m.align == PadAlign.SPLIT) {
            for ((r, row) in rs.withIndex()) {
                val (left, right) = splitRow(row)
                placeRow(left, 0f, m.halfW, r, rs.size, rh, h)
                placeRow(right, w - m.halfW, m.halfW, r, rs.size, rh, h)
            }
            return
        }
        for ((r, row) in rs.withIndex()) placeRow(row, m.offsetX, m.contentW, r, rs.size, rh, h)
    }

    /** 將一（半）行鍵按 weight 鋪落 [ox] 起、[cw] 咁闊嗰橛度 */
    private fun placeRow(
        row: List<Key>, ox: Float, cw: Float, r: Int, rowCount: Int, rh: Float, h: Int
    ) {
        val total = row.sumOf { it.weight.toDouble() }.toFloat()
        if (total <= 0f) return
        var x = ox
        // 最後一格夾硬去到最右，最後一行夾硬去到最底：唔可以喺邊位留條撳唔到嘅隙
        for ((i, k) in row.withIndex()) {
            val kw = cw * (k.weight / total)
            val right = if (i == row.size - 1) ox + cw else x + kw
            val bottom = if (r == rowCount - 1) h.toFloat() else (r + 1) * rh
            // 空位淨係食位，唔入 boxes —— 咁撳落去先會 snap 去隔籬真嗰粒鍵
            // （boxNear 嘅 SNAP_DP），唔會變成一撳乜都唔發生嘅死位
            if (!k.spacer) {
                val b = KeyBox(k)
                b.set(x, r * rh, right, bottom)
                boxes.add(b)
            }
            x += kw
        }
    }

    /**
     * 一行拆做左右兩橛：由左邊夾夠一半 weight 為止（`asdfg` | `hjkl`、
     * `⇧zxcv` | `bnm⌫`）。夾到一半嗰粒啱啱係 `␣` 就索性**拆佢做兩粒**，
     * 兩邊各有一條 space，唔會得一隻姆指撳到。
     */
    private fun splitRow(row: List<Key>): Pair<List<Key>, List<Key>> {
        val half = row.sumOf { it.weight.toDouble() }.toFloat() / 2f
        var acc = 0f
        for ((i, k) in row.withIndex()) {
            acc += k.weight
            if (acc < half) continue
            if (k.action != KeyAction.SPACE) return row.take(i + 1) to row.drop(i + 1)
            val piece = k.copy(weight = k.weight / 2f)
            return (row.take(i) + piece) to (listOf(piece) + row.drop(i + 1))
        }
        return row to emptyList()
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
        // 功能鍵（Eng／中／⌫／⏎／⇧／?123…）唔跟設定頁條字體 slider ——
        // 條 slider 淨係郁得到真係打得出嚟嗰啲字符（見 [Prefs.funcFontScale]）
        val scale = if (isFunctionKey(k)) funcFontScale else fontScale
        drawLabel(
            canvas, box, displayLabel(k),
            sizeRatio = if (k.bigLabel) 0.44f else 0.36f,
            color = when {
                !keyEnabled(k) -> theme.textDim
                k.accent -> theme.onAccentText
                else -> theme.text
            },
            scale = scale
        )
        if (k.hint.isNotEmpty()) drawCornerHint(canvas, box, k.hint, scale = scale)
        if (k.hintRight.isNotEmpty()) drawCornerHintRight(canvas, box, k.hintRight, scale = scale)
    }

    protected open fun displayLabel(k: Key): String = labelOf(k)

    protected open fun isFunctionKey(k: Key): Boolean = when (k.action) {
        KeyAction.CHAR -> false
        else -> true
    }
}
