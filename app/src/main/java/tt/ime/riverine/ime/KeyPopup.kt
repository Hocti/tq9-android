package tt.ime.riverine.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.widget.PopupWindow
import kotlin.math.roundToInt

/**
 * 浮喺鍵盤**上面**嘅細窗：長撳彈出嗰行變體、同埋滑動時「而家喺邊粒鍵」嘅提示。
 *
 * 點解唔喺 view 入面畫？因為要**超出鍵盤範圍**——最頂嗰行長撳，變體要彈到鍵盤
 * 外面（app 嗰邊）先唔會俾手指遮住。喺 [KeyboardBaseView.onDraw] 度畫就一定會
 * 俾 view 邊界剪走，所以改用 PopupWindow：
 *
 *  - `isClippingEnabled = false` + `isAttachedInDecor = false`：話畀 WindowManager
 *    聽唔好夾返入 IME 個窗／decor 入面，個 popup 出得去 app 嗰邊。
 *  - `isTouchable = false`：popup 唔收 touch，長撳完照樣拉得去揀
 *    （所有 MotionEvent 一路都係 [KeyboardBaseView] 收）。
 */
class KeyPopup(context: Context) {

    companion object {
        /** 變體字比鍵面大約大 30%（原本 0.40×格高，而家 0.53×） */
        private const val TEXT_RATIO = 0.53f
    }

    private val content = Content(context)
    private val window = PopupWindow(content, 0, 0).apply {
        isClippingEnabled = false
        isTouchable = false
        isFocusable = false
        setBackgroundDrawable(null)
        animationStyle = 0
        inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
        isAttachedInDecor = false
    }
    private val loc = IntArray(2)

    fun setStyle(theme: Theme, fontScale: Float) {
        content.theme = theme
        content.fontScale = fontScale
    }

    /** 長撳彈出嗰行變體。[left]／[top] 係 [anchor] 入面嘅座標，負數 = 彈出鍵盤外面 */
    fun showRow(
        anchor: View, items: List<String>, index: Int,
        left: Float, top: Float, itemW: Float, itemH: Float
    ) {
        content.items = items
        content.index = index
        content.itemW = itemW
        content.itemH = itemH
        show(anchor, left, top, itemW * items.size, itemH)
    }

    /** 淨係一格（滑動時嘅 hover 提示）。[cx] 係中心 x */
    fun showOne(anchor: View, label: String, cx: Float, top: Float, w: Float, h: Float) {
        content.items = listOf(label)
        content.index = 0
        content.itemW = w
        content.itemH = h
        show(anchor, cx - w / 2f, top, w, h)
    }

    /** 淨係換高亮邊格，唔使郁個窗 */
    fun highlight(index: Int) {
        if (content.index == index) return
        content.index = index
        content.invalidate()
    }

    fun dismiss() {
        if (window.isShowing) window.dismiss()
    }

    private fun show(anchor: View, left: Float, top: Float, w: Float, h: Float) {
        if (!anchor.isAttachedToWindow) return
        val pad = content.pad
        anchor.getLocationInWindow(loc)
        val x = (loc[0] + left - pad).roundToInt()
        val y = (loc[1] + top - pad).roundToInt()
        val ww = (w + pad * 2).roundToInt()
        val hh = (h + pad * 2).roundToInt()
        if (window.isShowing) {
            window.update(x, y, ww, hh)
        } else {
            window.width = ww
            window.height = hh
            window.showAtLocation(anchor, Gravity.NO_GRAVITY, x, y)
        }
        content.invalidate()
    }

    private class Content(context: Context) : View(context) {
        val pad = context.resources.displayMetrics.density * 4f
        var theme: Theme = Theme.of(context)
        var fontScale = 1f
        var items: List<String> = emptyList()
        var index = 0
        var itemW = 0f
        var itemH = 0f

        private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
        private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
        private val r = RectF()

        override fun onDraw(canvas: Canvas) {
            if (items.isEmpty() || itemW <= 0f || itemH <= 0f) return
            val radius = pad * 1.6f
            r.set(0f, 0f, width.toFloat(), height.toFloat())
            bg.color = theme.keyFaceAlt
            bg.style = Paint.Style.FILL
            canvas.drawRoundRect(r, radius, radius, bg)
            for (i in items.indices) {
                val l = pad + i * itemW
                r.set(l + 1f, pad + 1f, l + itemW - 1f, pad + itemH - 1f)
                bg.color = if (i == index) theme.keyAccent else theme.keyFace
                canvas.drawRoundRect(r, radius, radius, bg)
                tp.isFakeBoldText = i == index
                tp.color = if (i == index) theme.onAccentText else theme.text
                tp.textSize = itemH * TEXT_RATIO * fontScale
                val avail = itemW - pad * 2
                val need = tp.measureText(items[i])
                if (need > avail) tp.textSize *= avail / need
                val fm = tp.fontMetrics
                canvas.drawText(
                    items[i], (r.left + r.right) / 2f,
                    (r.top + r.bottom) / 2f - (fm.ascent + fm.descent) / 2f, tp
                )
            }
        }
    }
}
