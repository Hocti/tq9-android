package tt.ime.riverine.ime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import tt.ime.riverine.core.PadAlign
import tt.ime.riverine.core.PadChrome
import tt.ime.riverine.core.PadFunc
import tt.ime.riverine.core.PadGroup
import tt.ime.riverine.core.Prefs
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 闊 screen 非浮動時，中文九宮格／純數字鍵盤留白最外側嗰欄四粒掣
 * （浮動、貼邊循環、輸入法選擇表、收起鍵盤）。置左就排最右，其餘排最左。
 *
 * 高度寫死做鍵盤本體咁高（同 [SidePanelView]），唔用 `MATCH_PARENT`。
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class WideChromeRail(context: Context) : LinearLayout(context) {

    var listener: OptionBarsView.Listener? = null
    var theme: Theme = Theme.of(context)
        set(v) { field = v; applyTheme() }

    private val buttons = LinkedHashMap<PadFunc, TextView>()
    private val icons = LinkedHashMap<TextView, Pair<ToolIcon, String>>()

    private val alignBtn: TextView? get() = buttons[PadFunc.ALIGN]

    init {
        orientation = VERTICAL
        for (f in PadChrome.FUNCS) {
            val v = TextView(context)
            v.gravity = Gravity.CENTER
            v.contentDescription = f.label
            if (f == PadFunc.ALIGN) {
                v.setOnClickListener { listener?.onCycleAlign(); refreshAlignLabel() }
                v.setOnTouchListener { view, e -> handleSizeDrag(view, e) }
                v.setOnLongClickListener { longPressArmed = true; true }
            } else {
                v.setOnClickListener { listener?.onTool(f.action()) }
            }
            val icon = iconOf(f)
            if (icon != null) icons[v] = icon to f.label else v.text = f.face
            val lp = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
            val g = gap()
            lp.setMargins(g, g, g, g)
            addView(v, lp)
            buttons[f] = v
        }
        applyTheme()
        refreshAlignLabel()
    }

    fun applyTheme() {
        setBackgroundColor(theme.background)
        for ((f, v) in buttons) {
            v.setTextColor(theme.text)
            if (f != PadFunc.ALIGN) {
                val icon = iconOf(f)
                if (icon != null) icons[v] = icon to f.label
            }
            styleTool(v)
        }
        refreshAlignLabel()
    }

    fun refreshAlignLabel() {
        val v = alignBtn ?: return
        icons[v] = when (Prefs.align(context, PadGroup.CJK)) {
            PadAlign.STRETCH -> ToolIcon.ALIGN_WIDE to "拉闊"
            PadAlign.LEFT_GAP -> ToolIcon.ALIGN_RIGHT to "靠右"
            PadAlign.RIGHT_GAP -> ToolIcon.ALIGN_LEFT to "靠左"
            PadAlign.SPLIT -> ToolIcon.ALIGN_SPLIT to "左右拆開"
            PadAlign.CENTER -> ToolIcon.ALIGN_CENTER to "置中"
            PadAlign.FLOATING -> ToolIcon.ALIGN_FLOAT to "浮動"
        }
        styleTool(v)
    }

    private fun iconOf(f: PadFunc): ToolIcon? = when (f) {
        PadFunc.FLOAT -> ToolIcon.ALIGN_FLOAT
        PadFunc.IME_PICKER -> ToolIcon.GLOBE_LIST
        PadFunc.HIDE_KEYBOARD -> ToolIcon.CHEVRON_DOWN
        else -> f.toolIcon()
    }

    private fun styleTool(v: TextView) {
        val spec = icons[v]
        if (spec == null) { v.background = chipBg(); return }
        v.text = ""
        v.contentDescription = spec.second
        v.background = iconChip(chipBg(), spec.first, iconPx(), theme.text)
    }

    private fun chipBg() = GradientDrawable().apply {
        setColor(theme.keyFaceAlt)
        cornerRadius = dp(6f)
    }

    private fun iconPx(): Int {
        val row = (height.takeIf { it > 0 } ?: dp(48f).roundToInt()) / childCount.coerceAtLeast(1)
        return (row * 0.45f).roundToInt().coerceAtLeast(1)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (h != oldh) for (v in buttons.values) styleTool(v)
    }

    private fun gap() = dp(Prefs.gapDp(context).toFloat()).roundToInt()

    private var dragX = 0f
    private var dragY = 0f
    private var dragging = false
    private var longPressArmed = false
    private var horizontal = false

    @SuppressLint("ClickableViewAccessibility")
    private fun handleSizeDrag(v: View, e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragX = e.rawX; dragY = e.rawY; dragging = false; horizontal = false
                longPressArmed = false
                v.parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - dragX
                val dy = e.rawY - dragY
                if (!dragging && (abs(dx) > dp(8f) || abs(dy) > dp(8f))) {
                    dragging = true
                    horizontal = abs(dx) > abs(dy)
                    v.cancelLongPress()
                }
                if (dragging) {
                    val d = resources.displayMetrics.density
                    if (horizontal) listener?.onWidthDrag((dx / d).roundToInt())
                    else listener?.onSizeDrag((-dy / d).roundToInt())
                    dragX = e.rawX
                    dragY = e.rawY
                }
            }
            MotionEvent.ACTION_UP -> {
                val hold = longPressArmed && !dragging
                longPressArmed = false
                if (dragging) { dragging = false; return true }
                if (hold) { listener?.onMaxWidth(); return true }
            }
            MotionEvent.ACTION_CANCEL -> { dragging = false; longPressArmed = false }
        }
        return false
    }

    private fun dp(v: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
}
