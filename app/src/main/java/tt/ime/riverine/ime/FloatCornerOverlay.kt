package tt.ime.riverine.ime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import kotlin.math.roundToInt

/**
 * 浮動張卡四角外面嘅藍色圓角 handle。撳／拖底列嗰條 bar 先出；
 * 撳返鍵盤本體就收。拖一隻角 = 對角釘住、拉呢隻角改大細。
 *
 * **唔 clickable**：疊喺張卡上面但中間冇 child，DOWN 回 false 就交返
 * 俾下面張卡，唔會食晒鍵盤啲撳。
 */
@SuppressLint("ViewConstructor")
class FloatCornerOverlay(context: Context) : FrameLayout(context) {

    interface Listener {
        fun onCornerDragStart(corner: FloatGeom.Corner)
        fun onCornerDragBy(corner: FloatGeom.Corner, dxPx: Float, dyPx: Float)
        fun onCornerDragEnd()
    }

    var listener: Listener? = null

    var theme: Theme = Theme.of(context)
        set(v) { field = v; knobs.forEach { it.invalidate() } }

    private val knobs = FloatGeom.Corner.entries.map { CornerKnob(context, it) }

    init {
        isClickable = false
        isFocusable = false
        clipChildren = false
        clipToPadding = false
        knobs.forEach { k ->
            k.setOnTouchListener { _, e -> handleKnob(k.corner, e) }
            addView(k, LayoutParams(dp(HIT_DP).roundToInt(), dp(HIT_DP).roundToInt()))
        }
    }

    /** 跟張卡四隻角擺。未量到就收埋。 */
    fun syncToCard(card: View) {
        if (visibility != VISIBLE) return
        val hit = dp(HIT_DP).roundToInt()
        val overlap = dp(OVERLAP_DP).roundToInt()
        val l = card.left
        val t = card.top
        val r = card.right
        val b = card.bottom
        if (r <= l || b <= t) return
        place(FloatGeom.Corner.TL, l - hit + overlap, t - hit + overlap, hit)
        place(FloatGeom.Corner.TR, r - overlap, t - hit + overlap, hit)
        place(FloatGeom.Corner.BL, l - hit + overlap, b - overlap, hit)
        place(FloatGeom.Corner.BR, r - overlap, b - overlap, hit)
    }

    private fun place(c: FloatGeom.Corner, x: Int, y: Int, hit: Int) {
        val v = knobs[c.ordinal]
        val lp = v.layoutParams as LayoutParams
        lp.width = hit
        lp.height = hit
        lp.gravity = Gravity.TOP or Gravity.START
        lp.leftMargin = x
        lp.topMargin = y
        v.layoutParams = lp
    }

    private var downRawX = 0f
    private var downRawY = 0f
    private var active: FloatGeom.Corner? = null

    @SuppressLint("ClickableViewAccessibility")
    private fun handleKnob(corner: FloatGeom.Corner, e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = e.rawX
                downRawY = e.rawY
                active = corner
                parent?.requestDisallowInterceptTouchEvent(true)
                listener?.onCornerDragStart(corner)
            }
            MotionEvent.ACTION_MOVE -> {
                val c = active ?: return true
                listener?.onCornerDragBy(c, e.rawX - downRawX, e.rawY - downRawY)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (active != null) listener?.onCornerDragEnd()
                active = null
            }
        }
        return true
    }

    private inner class CornerKnob(
        context: Context,
        val corner: FloatGeom.Corner,
    ) : View(context) {
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val arc = RectF()

        init {
            contentDescription = when (corner) {
                FloatGeom.Corner.TL -> "拉動左上角"
                FloatGeom.Corner.TR -> "拉動右上角"
                FloatGeom.Corner.BL -> "拉動左下角"
                FloatGeom.Corner.BR -> "拉動右下角"
            }
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            val sw = dp(STROKE_DP)
            stroke.color = theme.trail
            stroke.strokeWidth = sw
            val inset = sw / 2f + dp(1f)
            val r = (minOf(w, h) - inset * 2f) * 0.55f
            // 弧貼住張卡嗰隻角（knob 入面靠卡嗰邊）
            val cx = when (corner) {
                FloatGeom.Corner.TL, FloatGeom.Corner.BL -> w - inset
                FloatGeom.Corner.TR, FloatGeom.Corner.BR -> inset
            }
            val cy = when (corner) {
                FloatGeom.Corner.TL, FloatGeom.Corner.TR -> h - inset
                FloatGeom.Corner.BL, FloatGeom.Corner.BR -> inset
            }
            arc.set(cx - r, cy - r, cx + r, cy + r)
            val start = when (corner) {
                FloatGeom.Corner.TL -> 180f
                FloatGeom.Corner.TR -> 270f
                FloatGeom.Corner.BR -> 0f
                FloatGeom.Corner.BL -> 90f
            }
            canvas.drawArc(arc, start, 90f, false, stroke)
        }
    }

    private fun dp(v: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    companion object {
        const val HIT_DP = 32f
        /** 疊入張卡幾多，剩低伸出外面 */
        const val OVERLAP_DP = 12f
        const val OUTSET_DP = HIT_DP - OVERLAP_DP
        private const val STROKE_DP = 3.5f
    }
}
