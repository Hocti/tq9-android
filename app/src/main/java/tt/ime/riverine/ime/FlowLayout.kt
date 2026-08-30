package tt.ime.riverine.ime

import android.content.Context
import android.view.View
import android.view.ViewGroup

/** 簡單自動換行容器，option bar 拉開之後用 */
class FlowLayout(context: Context) : ViewGroup(context) {

    var hGap = 0
    var vGap = 0

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        var x = paddingLeft
        var y = paddingTop
        var rowH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == View.GONE) continue
            measureChild(c, MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST), heightMeasureSpec)
            if (x + c.measuredWidth > width - paddingRight && x > paddingLeft) {
                x = paddingLeft
                y += rowH + vGap
                rowH = 0
            }
            x += c.measuredWidth + hGap
            if (c.measuredHeight > rowH) rowH = c.measuredHeight
        }
        setMeasuredDimension(width, y + rowH + paddingBottom)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val width = r - l
        var x = paddingLeft
        var y = paddingTop
        var rowH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == View.GONE) continue
            if (x + c.measuredWidth > width - paddingRight && x > paddingLeft) {
                x = paddingLeft
                y += rowH + vGap
                rowH = 0
            }
            c.layout(x, y, x + c.measuredWidth, y + c.measuredHeight)
            x += c.measuredWidth + hGap
            if (c.measuredHeight > rowH) rowH = c.measuredHeight
        }
    }
}
