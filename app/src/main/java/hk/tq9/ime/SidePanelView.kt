package hk.tq9.ime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import hk.tq9.core.PadAlign
import hk.tq9.core.Prefs
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 中文本體拉到好窄（靠左／靠右，闊度喺螢幕 [Prefs.SIDE_PANEL_MAX_RATIO] 以下）嗰陣
 * 用嘅側邊欄。
 *
 * 呢個情況下空出嚟嗰四成幾位本來係吉住嘅，同時上面又要再霸一條 bar 去擺功能掣同
 * 候選字 —— 好嘥。所以一夠窄就**收起上面條 [OptionBarsView]**，成條 bar 嘅內容
 * 搬晒落嚟呢邊：
 *
 *  - 上面一行（或者兩行，唔夠闊會自動摺）：功能掣（大細位置、貼上、錄音、
 *    emoji、AI），同工具 bar 嗰幾粒一模一樣
 *  - 下面成塊位：候選字，可以直接向下 scroll，唔使再撳 ▼ 拉大
 *
 * 兩樣嘢一次過見晒，亦都唔使再喺候選字／工具之間切（[OptionBarsView] 個 ⇄ 喺呢度
 * 用唔著，所以冇擺）。
 */
@SuppressLint("ViewConstructor")
class SidePanelView(context: Context) : LinearLayout(context) {

    var listener: OptionBarsView.Listener? = null
    var theme: Theme = Theme.of(context)

    private val sizeBtn = TextView(context)
    private val pasteBtn = TextView(context)
    private val sttBtn = TextView(context)
    private val emojiBtn = TextView(context)
    private val aiBtn = TextView(context)
    private val closeBtn = TextView(context)

    private val toolFlow = FlowLayout(context)
    private val candScroll = ScrollView(context)
    private val candFlow = FlowLayout(context)

    private var candidates: List<String> = emptyList()
    private var aiReady = false
    private var sttActive = false

    private fun dp(v: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    init {
        orientation = VERTICAL

        @Suppress("ClickableViewAccessibility")
        sizeBtn.apply {
            gravity = Gravity.CENTER
            textSize = 15f
            setOnClickListener { listener?.onCycleAlign(); refreshAlignLabel() }
            setOnTouchListener { _, e -> handleSizeDrag(e) }
            // 撳實唔拉 = 一下子拉到最闊（同工具 bar 嗰粒一樣）
            setOnLongClickListener { listener?.onMaxWidth(); true }
        }
        tool(pasteBtn, "📋", KeyAction.PASTE)
        pasteBtn.setOnLongClickListener { listener?.onPasteHistory(); true }
        tool(sttBtn, "🎤", KeyAction.STT)
        // 撳實 🎤 = 一路錄，放手就停（淨係 AI 語音輸入先做得到，所以由 host 話
        // 收唔收呢下長撳）。onTouch 回 false，粒掣本身嘅短撳／長撳照行；
        // ACTION_UP 一定喺 performClick 之前到，所以撳一下唔會誤當放手收工。
        @Suppress("ClickableViewAccessibility")
        sttBtn.setOnLongClickListener { listener?.onSttHoldStart() == true }
        sttBtn.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_UP ||
                e.actionMasked == MotionEvent.ACTION_CANCEL) listener?.onSttHoldEnd()
            false
        }
        tool(emojiBtn, "😀", KeyAction.TO_EMOJI)
        tool(aiBtn, "✨", KeyAction.AI)
        closeBtn.apply {
            text = "✖"
            gravity = Gravity.CENTER
            textSize = 15f
            visibility = View.GONE
            setOnClickListener { listener?.onCloseSpecialPad() }
        }

        // 側邊欄好窄，一行擺唔晒五六粒就自動摺落第二行（唔會迫到粒粒細過隻手指）
        toolFlow.hGap = dp(4f).toInt()
        toolFlow.vGap = dp(4f).toInt()
        toolFlow.setPadding(dp(4f).toInt(), dp(4f).toInt(), dp(4f).toInt(), dp(2f).toInt())
        for (v in listOf(closeBtn, sizeBtn, pasteBtn, sttBtn, emojiBtn, aiBtn)) {
            v.minWidth = dp(40f).roundToInt()
            v.minHeight = dp(36f).roundToInt()
            toolFlow.addView(v)
        }

        candFlow.hGap = dp(4f).toInt()
        candFlow.vGap = dp(4f).toInt()
        candFlow.setPadding(dp(4f).toInt(), dp(2f).toInt(), dp(4f).toInt(), dp(4f).toInt())
        candScroll.isVerticalScrollBarEnabled = true
        candScroll.addView(candFlow, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        addView(toolFlow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        // 淨低幾多位就俾候選字食晒（weight = 1），咁樣拉極都唔會撐爆個鍵盤
        addView(candScroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        applyTheme(theme)
        refreshAlignLabel()
    }

    private fun tool(v: TextView, icon: String, action: KeyAction) {
        v.apply {
            text = icon
            gravity = Gravity.CENTER
            textSize = 17f
            setOnClickListener { listener?.onTool(action) }
        }
    }

    fun applyTheme(t: Theme) {
        theme = t
        setBackgroundColor(t.background)
        candScroll.setBackgroundColor(t.background)
        for (v in listOf(sizeBtn, closeBtn, pasteBtn, sttBtn, emojiBtn, aiBtn)) {
            v.setTextColor(t.text)
            v.background = chipBg(t.keyFaceAlt)
        }
        refreshAiLook()
        refreshSttLook()
        rebuildChips()
    }

    private fun chipBg(color: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(6f)
        setStroke(1, Color.argb(30, 128, 128, 128))
    }

    // ---- 同 OptionBarsView 一樣嘅幾個開關 -----------------------------------

    fun setCloseVisible(v: Boolean) {
        closeBtn.visibility = if (v) View.VISIBLE else View.GONE
    }

    fun setAiReady(ready: Boolean) {
        if (aiReady == ready) return
        aiReady = ready
        refreshAiLook()
    }

    /** 未入 Gemini API key 就成粒掣唔見咗（同 [OptionBarsView.setAiVisible] 一樣） */
    fun setAiVisible(visible: Boolean) {
        val want = if (visible) View.VISIBLE else View.GONE
        if (aiBtn.visibility != want) aiBtn.visibility = want
    }

    private fun refreshAiLook() {
        aiBtn.isEnabled = aiReady
        aiBtn.alpha = if (aiReady) 1f else 0.4f
    }

    fun setSttActive(on: Boolean) {
        if (sttActive == on) return
        sttActive = on
        refreshSttLook()
    }

    private fun refreshSttLook() {
        sttBtn.background = chipBg(if (sttActive) theme.keyAccent else theme.keyFaceAlt)
    }

    fun setCandidates(list: List<String>) {
        if (candidates == list) return
        candidates = list
        rebuildChips()
        candScroll.scrollTo(0, 0)
    }

    private fun rebuildChips() {
        candFlow.removeAllViews()
        for ((i, w) in candidates.withIndex()) {
            if (w.isEmpty()) continue
            candFlow.addView(makeChip(w, i))
        }
    }

    private fun makeChip(text: String, index: Int): TextView = TextView(context).apply {
        this.text = text
        textSize = 20f
        gravity = Gravity.CENTER
        setTextColor(theme.text)
        background = chipBg(theme.keyFace)
        val h = dp(6f).toInt()
        setPadding(dp(10f).toInt(), h, dp(10f).toInt(), h)
        minWidth = dp(38f).roundToInt()
        setOnClickListener { listener?.onPickCandidate(index) }
    }

    fun refreshAlignLabel() {
        sizeBtn.text = when (Prefs.align(context)) {
            PadAlign.STRETCH -> "↔"
            PadAlign.LEFT_GAP -> "⬅"
            PadAlign.RIGHT_GAP -> "➡"
        }
    }

    // ---- 大細：同工具 bar 嗰粒掣一樣，上下拖高低、左右拖闊窄 ------------------

    private var dragX = 0f
    private var dragY = 0f
    private var dragging = false
    private var horizontal = false

    private fun handleSizeDrag(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragX = e.rawX; dragY = e.rawY; dragging = false; horizontal = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - dragX
                val dy = e.rawY - dragY
                if (!dragging && (abs(dx) > dp(8f) || abs(dy) > dp(8f))) {
                    dragging = true
                    horizontal = abs(dx) > abs(dy)
                }
                if (dragging) {
                    val d = resources.displayMetrics.density
                    if (horizontal) listener?.onWidthDrag((dx / d).roundToInt())
                    else listener?.onSizeDrag((-dy / d).roundToInt())
                    dragX = e.rawX
                    dragY = e.rawY
                }
            }
            MotionEvent.ACTION_UP -> if (dragging) { dragging = false; return true }
        }
        return false
    }
}
