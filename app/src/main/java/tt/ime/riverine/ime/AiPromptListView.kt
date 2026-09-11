package tt.ime.riverine.ime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import tt.ime.riverine.core.AiPrompt
import tt.ime.riverine.core.Prefs
import kotlin.math.roundToInt

/**
 * 長撳「AI改」之後，成個鍵盤位置變咗做 prompt 揀擇表
 * （同長撳「貼上」出剪貼簿歷史嗰個 [ClipboardListView] 一模一樣嘅做法）。
 *
 * 撳一下就用嗰個 prompt 即刻改寫。短撳粒掣**唔會**入到呢度 ——
 * 嗰下一律用名單第一個（見 [Prefs.aiPrompt]）。
 */
@SuppressLint("ViewConstructor")
class AiPromptListView(context: Context) : LinearLayout(context) {

    fun interface PromptHost {
        fun onPromptPick(prompt: AiPrompt)
    }

    var promptHost: PromptHost? = null
    var theme: Theme = Theme.of(context)

    private val list = LinearLayout(context)
    private val scroll = ScrollView(context)
    private val title = TextView(context)

    private fun dp(v: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    init {
        orientation = VERTICAL
        isClickable = true          // 唔好俾下面個鍵盤食咗啲掂觸

        title.apply {
            text = "AI 改寫（按一下選用哪個 Prompt）"
            textSize = 13f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8f).toInt(), 0, dp(8f).toInt(), 0)
        }
        addView(title, LayoutParams(LayoutParams.MATCH_PARENT, dp(38f).roundToInt()))

        list.orientation = VERTICAL
        list.setPadding(dp(4f).toInt(), 0, dp(4f).toInt(), dp(4f).toInt())
        scroll.addView(list, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        applyTheme(theme)
    }

    fun applyTheme(t: Theme) {
        theme = t
        setBackgroundColor(t.background)
        title.setTextColor(t.textDim)
        rebuild()
    }

    private fun bgOf(color: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(6f)
        setStroke(1, Color.argb(30, 128, 128, 128))
    }

    /** 大過 0 就跟住嗰個高度（等如而家嗰個鍵盤），0 = 用返預設（同 [ClipboardListView]） */
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
        // [Prefs.aiPrompts] 一定唔會空，所以唔使做「未有記錄」嗰段
        for (p in Prefs.aiPrompts(context)) list.addView(row(p), LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).also {
            it.setMargins(0, dp(3f).toInt(), 0, dp(3f).toInt())
        })
    }

    /** 上面粗體係個名，下面灰色細字係段 prompt 本身（剪到兩行，認得返邊個係邊個就夠） */
    private fun row(p: AiPrompt): PromptRow = PromptRow(context).apply {
        background = bgOf(theme.keyFace)
        setPadding(dp(10f).toInt(), dp(8f).toInt(), dp(10f).toInt(), dp(8f).toInt())
        name.text = p.name
        name.setTextColor(theme.text)
        body.text = p.text.replace(Regex("\\s+"), " ").trim()
        body.setTextColor(theme.textDim)
        setOnClickListener { promptHost?.onPromptPick(p) }
    }

    /** 一行兩層字 */
    @SuppressLint("ViewConstructor")
    class PromptRow(context: Context) : LinearLayout(context) {
        val name = TextView(context).apply {
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
        }
        val body = TextView(context).apply {
            textSize = 12f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        init {
            orientation = VERTICAL
            isClickable = true
            addView(name, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            addView(body, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
    }
}
