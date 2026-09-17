package tt.ime.riverine.ime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * 浮動「調整大小」遮罩：**蓋住張卡、唔加高張卡**（確定／取消疊喺鍵盤底，
 * 先至預覽到真正拉高拉矮之後幾高）。X 橫排、Y 直排（＋上、－下）。
 *
 * 量度上要報 0×0（見 [onMeasure]），大細由 host 抄 [FloatHandleView] 條 column
 * 過嚟 —— 唔係 FrameLayout WRAP_CONTENT 會將 MATCH_PARENT 子 view 當 wrap
 * 量，遮罩自己嘅確定／取消就會撐高張卡。
 */
@SuppressLint("ViewConstructor")
class FloatResizeOverlay(context: Context) : FrameLayout(context) {

    interface Listener {
        fun onNudgeWidth(deltaScale: Float)
        fun onNudgeHeight(deltaScale: Float)
        fun onResizeConfirm()
        fun onResizeCancel()
    }

    var listener: Listener? = null

    var theme: Theme = Theme.of(context)
        set(v) { field = v; applyTheme() }

    private val handler = Handler(Looper.getMainLooper())
    private var repeat: Runnable? = null

    private val xMinus = TextView(context)
    private val xPlus = TextView(context)
    private val yMinus = TextView(context)
    private val yPlus = TextView(context)
    private val xLabel = TextView(context)
    private val yLabel = TextView(context)
    private val cancelBtn = TextView(context)
    private val confirmBtn = TextView(context)
    private val xRow = LinearLayout(context)
    private val yCol = LinearLayout(context)
    private val bot = LinearLayout(context)

    init {
        isClickable = true
        isFocusable = true
        setBackgroundColor(Color.argb(170, 0, 0, 0))
        val pad = dp(10f).roundToInt()

        xRow.orientation = LinearLayout.HORIZONTAL
        xRow.gravity = Gravity.CENTER_VERTICAL
        xLabel.text = "X"
        xLabel.textSize = 18f
        xLabel.gravity = Gravity.CENTER
        xLabel.contentDescription = "闊度"
        xMinus.contentDescription = "縮小闊度"
        xPlus.contentDescription = "增大闊度"
        val btn = dp(BTN_DP).roundToInt()
        xRow.addView(xMinus, LinearLayout.LayoutParams(btn, btn))
        xRow.addView(xLabel, LinearLayout.LayoutParams(dp(28f).roundToInt(), btn))
        xRow.addView(xPlus, LinearLayout.LayoutParams(btn, btn))
        addView(xRow, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).also {
            it.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            it.topMargin = pad
        })

        yCol.orientation = LinearLayout.VERTICAL
        yCol.gravity = Gravity.CENTER_HORIZONTAL
        yLabel.text = "Y"
        yLabel.textSize = 18f
        yLabel.gravity = Gravity.CENTER
        yLabel.contentDescription = "高度"
        yPlus.contentDescription = "增大高度"
        yMinus.contentDescription = "縮小高度"
        yCol.addView(yPlus, LinearLayout.LayoutParams(btn, btn))
        yCol.addView(yLabel, LinearLayout.LayoutParams(btn, dp(28f).roundToInt()))
        yCol.addView(yMinus, LinearLayout.LayoutParams(btn, btn))
        addView(yCol, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).also {
            it.gravity = Gravity.END or Gravity.CENTER_VERTICAL
            it.marginEnd = pad
        })

        bot.orientation = LinearLayout.HORIZONTAL
        bot.gravity = Gravity.CENTER
        cancelBtn.text = "取消"
        cancelBtn.gravity = Gravity.CENTER
        cancelBtn.setOnClickListener { listener?.onResizeCancel() }
        confirmBtn.text = "確定"
        confirmBtn.gravity = Gravity.CENTER
        confirmBtn.setOnClickListener { listener?.onResizeConfirm() }
        val btnH = dp(40f).roundToInt()
        bot.addView(cancelBtn, LinearLayout.LayoutParams(0, btnH, 1f).also {
            it.marginEnd = dp(8f).roundToInt()
        })
        bot.addView(confirmBtn, LinearLayout.LayoutParams(0, btnH, 1f).also {
            it.marginStart = dp(8f).roundToInt()
        })
        addView(bot, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).also {
            it.gravity = Gravity.BOTTOM
            it.leftMargin = pad
            it.rightMargin = pad
            it.bottomMargin = pad
        })

        applyTheme()
    }

    /** Host 抄 column 大細過嚟之後先 VISIBLE；呢度報 0 以免撐高張卡 */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val lp = layoutParams
        if (lp != null && lp.width <= 0 && lp.height <= 0) {
            setMeasuredDimension(0, 0)
            return
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    fun applyTheme() {
        xLabel.setTextColor(theme.onAccentText)
        yLabel.setTextColor(theme.onAccentText)
        styleNudge(xMinus, "－")
        styleNudge(xPlus, "＋")
        styleNudge(yMinus, "－")
        styleNudge(yPlus, "＋")
        styleAction(cancelBtn, theme.keyFace, theme.text)
        styleAction(confirmBtn, theme.keyAccent, theme.onAccentText)
        bindRepeat(xMinus) { listener?.onNudgeWidth(-STEP) }
        bindRepeat(xPlus) { listener?.onNudgeWidth(STEP) }
        bindRepeat(yMinus) { listener?.onNudgeHeight(-STEP) }
        bindRepeat(yPlus) { listener?.onNudgeHeight(STEP) }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopRepeat()
    }

    private fun styleNudge(v: TextView, face: String) {
        v.text = face
        v.textSize = 22f
        v.gravity = Gravity.CENTER
        v.setTextColor(theme.text)
        v.background = GradientDrawable().apply {
            setColor(theme.keyFace)
            cornerRadius = dp(6f)
        }
    }

    private fun styleAction(v: TextView, bg: Int, fg: Int) {
        v.textSize = 16f
        v.setTextColor(fg)
        v.background = GradientDrawable().apply {
            setColor(bg)
            cornerRadius = dp(6f)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun bindRepeat(v: TextView, step: () -> Unit) {
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    step()
                    startRepeat(step)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> stopRepeat()
            }
            true
        }
    }

    private fun startRepeat(step: () -> Unit) {
        stopRepeat()
        val r = object : Runnable {
            override fun run() {
                step()
                handler.postDelayed(this, REPEAT_MS)
            }
        }
        repeat = r
        handler.postDelayed(r, REPEAT_DELAY_MS)
    }

    private fun stopRepeat() {
        repeat?.let { handler.removeCallbacks(it) }
        repeat = null
    }

    private fun dp(v: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    companion object {
        const val STEP = 0.025f
        private const val BTN_DP = 44f
        private const val REPEAT_DELAY_MS = 380L
        private const val REPEAT_MS = 45L
    }
}
