package hk.tq9.ime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import hk.tq9.core.ClipHistory
import kotlin.math.roundToInt

/**
 * 長撳「貼上」之後，成個鍵盤位置變咗做 clipboard 歷史 scroll view。
 * 撳一下就貼，長撳一下就喺歷史入面剷咗佢。
 */
@SuppressLint("ViewConstructor")
class ClipboardListView(context: Context) : LinearLayout(context) {

    fun interface ClipHost {
        fun onClipPick(text: String)
    }

    var clipHost: ClipHost? = null
    var theme: Theme = Theme.of(context)

    private val list = LinearLayout(context)
    private val scroll = ScrollView(context)
    private val title = TextView(context)
    private val clearBtn = TextView(context)

    private fun dp(v: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    init {
        orientation = VERTICAL
        isClickable = true          // 唔好俾下面個鍵盤食咗啲掂觸

        title.apply {
            text = "剪貼簿歷史（撳一下貼，長撳剷走）"
            textSize = 13f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8f).toInt(), 0, dp(8f).toInt(), 0)
        }
        chip(clearBtn, "清空") { ClipHistory.clear(context); rebuild() }

        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            setPadding(dp(3f).toInt(), dp(3f).toInt(), dp(3f).toInt(), dp(3f).toInt())
        }
        header.addView(title, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        header.addView(clearBtn, chipLp(dp(56f).roundToInt()))
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, dp(38f).roundToInt()))

        list.orientation = VERTICAL
        list.setPadding(dp(4f).toInt(), 0, dp(4f).toInt(), dp(4f).toInt())
        scroll.addView(list, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        applyTheme(theme)
    }

    private fun chipLp(w: Int): LayoutParams {
        val lp = LayoutParams(w, LayoutParams.MATCH_PARENT)
        lp.setMargins(dp(2f).toInt(), 0, dp(2f).toInt(), 0)
        return lp
    }

    private fun chip(v: TextView, label: String, onClick: () -> Unit) {
        v.apply {
            text = label
            textSize = 13f
            gravity = Gravity.CENTER
            setOnClickListener { onClick() }
        }
    }

    fun applyTheme(t: Theme) {
        theme = t
        setBackgroundColor(t.background)
        title.setTextColor(t.textDim)
        for (v in listOf(clearBtn)) {
            v.setTextColor(t.text)
            v.background = bgOf(t.keyFaceAlt)
        }
        rebuild()
    }

    private fun bgOf(color: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(6f)
        setStroke(1, Color.argb(30, 128, 128, 128))
    }

    /** 大過 0 就跟住嗰個高度（等如而家嗰個鍵盤），0 = 用返預設 */
    var forcedHeightPx: Int = 0
        set(v) { if (field != v) { field = v; requestLayout() } }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = if (forcedHeightPx > 0) forcedHeightPx
                else PadMetrics.defaultPadHeightPx(context).roundToInt()
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
    }

    fun rebuild() {
        list.removeAllViews()
        scroll.scrollTo(0, 0)
        val items = ClipHistory.list(context)
        if (items.isEmpty()) {
            list.addView(TextView(context).apply {
                text = "未有記錄。喺其他 app 複製過嘅字會出喺呢度。"
                textSize = 13f
                setTextColor(theme.textDim)
                setPadding(dp(6f).toInt(), dp(10f).toInt(), dp(6f).toInt(), dp(10f).toInt())
            })
            return
        }
        for (s in items) list.addView(row(s), LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).also {
            it.setMargins(0, dp(3f).toInt(), 0, dp(3f).toInt())
        })
    }

    private fun row(text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 15f
        maxLines = 3
        ellipsize = TextUtils.TruncateAt.END
        setTextColor(theme.text)
        background = bgOf(theme.keyFace)
        setPadding(dp(10f).toInt(), dp(8f).toInt(), dp(10f).toInt(), dp(8f).toInt())
        setOnClickListener { clipHost?.onClipPick(text) }
        setOnLongClickListener { ClipHistory.remove(context, text); rebuild(); true }
    }
}
