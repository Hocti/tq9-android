package tt.ime.riverine.ime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * 浮動鍵盤最底嗰條 handle：
 *
 *  - 左：彈系統輸入法選單（[Listener.onFloatImePicker]）
 *  - 左中：調整大小（[Listener.onFloatResize]）
 *  - 中：拖去郁張卡（[Listener.onFloatDragBy]）—— X／Y 都得
 *  - 右：收起鍵盤（[Listener.onFloatHide]）
 */
@SuppressLint("ViewConstructor")
class FloatHandleView(context: Context) : LinearLayout(context) {

    interface Listener {
        fun onFloatImePicker()
        fun onFloatResize()
        fun onFloatHide()
        fun onFloatDragStart()
        /** 由 DOWN 起計嘅總位移（px），唔係逐格增量 */
        fun onFloatDragBy(dxPx: Float, dyPx: Float)
        fun onFloatDragEnd()
    }

    var listener: Listener? = null

    var theme: Theme = Theme.of(context)
        set(v) { field = v; applyTheme() }

    private var barH = dp(HEIGHT_DP).roundToInt()

    private val imeBtn = TextView(context)
    private val resizeBtn = TextView(context)
    private val hideBtn = TextView(context)
    private val dragArea = FrameLayout(context)
    private val pill = View(context)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = barH
        val side = barH

        styleSide(imeBtn, ToolIcon.GLOBE_LIST, "選擇輸入法")
        imeBtn.setOnClickListener { listener?.onFloatImePicker() }
        addView(imeBtn, LayoutParams(side, LayoutParams.MATCH_PARENT))

        styleSide(resizeBtn, ToolIcon.RESIZE, "調整大小")
        resizeBtn.setOnClickListener { listener?.onFloatResize() }
        addView(resizeBtn, LayoutParams(side, LayoutParams.MATCH_PARENT))

        pill.background = pillBg()
        dragArea.addView(pill, FrameLayout.LayoutParams(
            dp(40f).roundToInt(), dp(4f).roundToInt(), Gravity.CENTER
        ))
        dragArea.contentDescription = "拖動鍵盤"
        dragArea.setOnTouchListener { _, e -> handleDrag(e) }
        addView(dragArea, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))

        styleSide(hideBtn, ToolIcon.CHEVRON_DOWN, "收起鍵盤")
        hideBtn.setOnClickListener { listener?.onFloatHide() }
        addView(hideBtn, LayoutParams(side, LayoutParams.MATCH_PARENT))

        applyTheme()
    }

    /**
     * 跟中文一粒鍵嘅八成走（見 [CandChip.BAR_TO_KEY_RATIO]）。側邊三粒掣改做
     * 正方形，圖案跟高度縮，唔係 40dp 條 bar 會高過下面啲鍵。
     */
    fun setBarHeight(hPx: Int) {
        if (hPx <= 0 || hPx == barH) return
        barH = hPx
        minimumHeight = hPx
        layoutParams?.let { lp ->
            if (lp.height != hPx) {
                lp.height = hPx
                layoutParams = lp
            }
        }
        val sideLp = LayoutParams(hPx, LayoutParams.MATCH_PARENT)
        imeBtn.layoutParams = sideLp
        resizeBtn.layoutParams = LayoutParams(hPx, LayoutParams.MATCH_PARENT)
        hideBtn.layoutParams = LayoutParams(hPx, LayoutParams.MATCH_PARENT)
        val pillH = (hPx * 0.12f).roundToInt().coerceAtLeast(2)
        val pillW = (hPx * 0.9f).roundToInt().coerceAtLeast(pillH * 4)
        (pill.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
            lp.width = pillW
            lp.height = pillH
            pill.layoutParams = lp
        }
        applyTheme()
    }

    fun applyTheme() {
        setBackgroundColor(theme.keyFaceAlt)
        styleSide(imeBtn, ToolIcon.GLOBE_LIST, "選擇輸入法")
        styleSide(resizeBtn, ToolIcon.RESIZE, "調整大小")
        styleSide(hideBtn, ToolIcon.CHEVRON_DOWN, "收起鍵盤")
        pill.background = pillBg()
    }

    private fun styleSide(v: TextView, icon: ToolIcon, desc: String) {
        v.gravity = Gravity.CENTER
        v.text = ""
        v.contentDescription = desc
        val bg = GradientDrawable().apply {
            setColor(theme.keyFaceAlt)
            cornerRadius = dp(6f)
        }
        v.background = iconChip(bg, icon, iconPx(), theme.text)
    }

    private fun iconPx(): Int =
        (barH * 0.55f).roundToInt().coerceAtLeast(1)

    private fun pillBg() = GradientDrawable().apply {
        setColor(theme.textDim)
        cornerRadius = dp(2f)
    }

    private var downRawX = 0f
    private var downRawY = 0f
    private var dragging = false

    @SuppressLint("ClickableViewAccessibility")
    private fun handleDrag(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = e.rawX
                downRawY = e.rawY
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(true)
                listener?.onFloatDragStart()
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - downRawX
                val dy = e.rawY - downRawY
                if (!dragging && (dx * dx + dy * dy) > dp(4f) * dp(4f)) dragging = true
                listener?.onFloatDragBy(dx, dy)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                listener?.onFloatDragEnd()
                dragging = false
            }
        }
        return true
    }

    private fun dp(v: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    companion object {
        const val HEIGHT_DP = 40f
    }
}
