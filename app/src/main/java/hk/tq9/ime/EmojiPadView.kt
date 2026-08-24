package hk.tq9.ime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import hk.tq9.core.EmojiDict
import kotlin.math.roundToInt

/**
 * Emoji 表：上面一行係分類同搵字，下面攤開成個 grid。
 *
 * 搵字唔係喺呢度打，撳 🔍 會轉去英文鍵盤，打嘅字淨係用嚟篩，
 * 夾到嘅 emoji 出喺上面條 bar（睇 `TQ9InputMethodService.emojiQuery`）。
 * 咁樣就唔使喺鍵盤入面再塞多個輸入框，個鍵盤高度都唔會變。
 */
@SuppressLint("ViewConstructor")
class EmojiPadView(context: Context) : LinearLayout(context) {

    interface EmojiHost {
        fun onEmojiPicked(emoji: String)
        fun onEmojiSearch()
        fun onEmojiBackspace()
    }

    var emojiHost: EmojiHost? = null
    var theme: Theme = Theme.of(context)

    private val tabStrip = LinearLayout(context)
    private val tabScroll = HorizontalScrollView(context)
    private val grid = FlowLayout(context)
    private val gridScroll = ScrollView(context)
    private val header = LinearLayout(context)
    private val searchBtn = TextView(context)
    private val backspaceBtn = TextView(context)

    private var cat = EmojiDict.CATEGORIES[1].first
    private val ui = Handler(Looper.getMainLooper())

    private fun dp(v: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    private val headerH get() = dp(40f).roundToInt()

    init {
        orientation = VERTICAL

        chip(searchBtn, "🔍") { emojiHost?.onEmojiSearch() }
        chip(backspaceBtn, "⌫") { emojiHost?.onEmojiBackspace() }

        tabStrip.orientation = HORIZONTAL
        tabStrip.gravity = Gravity.CENTER_VERTICAL
        tabScroll.isHorizontalScrollBarEnabled = false
        tabScroll.addView(tabStrip, LayoutParams(
            LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))

        header.orientation = HORIZONTAL
        header.setPadding(dp(3f).toInt(), dp(3f).toInt(), dp(3f).toInt(), dp(3f).toInt())
        header.addView(searchBtn, chipLp(dp(42f).roundToInt()))
        header.addView(tabScroll, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        header.addView(backspaceBtn, chipLp(dp(42f).roundToInt()))
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, headerH))

        grid.hGap = dp(2f).toInt()
        grid.vGap = dp(2f).toInt()
        grid.setPadding(dp(4f).toInt(), dp(4f).toInt(), dp(4f).toInt(), dp(4f).toInt())
        gridScroll.addView(grid, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(gridScroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

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
            textSize = 16f
            gravity = Gravity.CENTER
            setOnClickListener { onClick() }
        }
    }

    fun applyTheme(t: Theme) {
        theme = t
        setBackgroundColor(t.background)
        for (v in listOf(searchBtn, backspaceBtn)) {
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

    /** emoji 表要同而家個鍵盤一樣高，唔好一開就成個窗跳高跳低；0 = 用返預設高度 */
    var forcedHeightPx: Int = 0
        set(v) { if (field != v) { field = v; requestLayout() } }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = if (forcedHeightPx > 0) forcedHeightPx
                else PadMetrics.defaultPadHeightPx(context).roundToInt()
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
    }

    fun rebuild() {
        buildTabs()
        buildGrid()
    }

    private fun buildTabs() {
        tabStrip.removeAllViews()
        for ((key, label) in EmojiDict.CATEGORIES) {
            if (key == "recent" && EmojiDict.recents(context).isEmpty()) continue
            val t = TextView(context).apply {
                text = label
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(if (key == cat) theme.onAccentText else theme.text)
                background = bgOf(if (key == cat) theme.keyAccent else theme.keyFaceAlt)
                setPadding(dp(9f).toInt(), 0, dp(9f).toInt(), 0)
                setOnClickListener { cat = key; rebuild() }
            }
            val lp = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT)
            lp.setMargins(dp(2f).toInt(), 0, dp(2f).toInt(), 0)
            tabStrip.addView(t, lp)
        }
    }

    private fun buildGrid() {
        grid.removeAllViews()
        if (EmojiDict.peek() == null) {
            grid.addView(TextView(context).apply {
                text = "載入中…"
                setTextColor(theme.textDim)
            })
            EmojiDict.preloadAsync(context)
            ui.postDelayed({ if (isAttachedToWindow) buildGrid() }, 180)
            return
        }
        gridScroll.scrollTo(0, 0)
        for (e in EmojiDict.byCategory(context, cat)) grid.addView(cell(e))
    }

    private fun cell(emoji: String): TextView = TextView(context).apply {
        text = emoji
        textSize = 22f
        gravity = Gravity.CENTER
        width = dp(44f).roundToInt()
        height = dp(42f).roundToInt()
        setOnClickListener {
            EmojiDict.addRecent(context, emoji)
            emojiHost?.onEmojiPicked(emoji)
        }
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == View.VISIBLE) buildTabs()
    }
}
