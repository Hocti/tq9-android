package tt.ime.riverine.ime

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
import tt.ime.riverine.core.PadAlign
import tt.ime.riverine.core.PadGroup
import tt.ime.riverine.core.Prefs
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 中文本體拉到好窄（靠左／靠右，闊度喺螢幕 [Prefs.SIDE_PANEL_MAX_RATIO] 以下）嗰陣
 * 用嘅側邊欄。
 *
 * 呢個情況下空出嚟嗰四成幾位本來係吉住嘅，同時上面又要再霸一條 bar 去擺功能掣同
 * 關聯字 —— 好嘥。所以一夠窄就**收起上面條 [OptionBarsView]**，成條 bar 嘅內容
 * 搬晒落嚟呢邊：
 *
 *  - 上面一行（或者兩行，唔夠闊會自動摺）：功能掣（大細位置、貼上、錄音、
 *    emoji、AI），同工具 bar 嗰幾粒一模一樣
 *  - 下面成塊位：關聯字，可以直接向下 scroll，唔使再撳 ▼ 拉大
 *
 * 兩樣嘢一次過見晒，亦都唔使再喺關聯字／工具之間切（[OptionBarsView] 個 ⇄ 喺呢度
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
    private val candFlow = CandFlowView(context)

    /** 邊粒掣用邊個圖案（＋TalkBack 讀嘅名），轉主題重新畫嗰陣要用 */
    private val icons = LinkedHashMap<TextView, Pair<ToolIcon, String>>()

    private var candidates: List<String> = emptyList()
    private var aiReady = false
    private var sttActive = false

    /** 關聯字而家幾大（sp），見 [Prefs.candTextSp]。側邊欄淨係中文，所以永遠 CJK 嗰組 */
    private var candSp = Prefs.candTextSp(context)

    /** chip 上下 padding（**特登唔對稱**，點解見 [CandChip]），同上面條 bar 一模一樣 */
    private var chip = CandChip.measure(context, candSp, PadGroup.CJK)

    private fun dp(v: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    init {
        orientation = VERTICAL

        @Suppress("ClickableViewAccessibility")
        sizeBtn.apply {
            gravity = Gravity.CENTER
            textSize = 15f
            setOnClickListener { listener?.onCycleAlign(); refreshAlignLabel() }
            setOnTouchListener { v, e -> handleSizeDrag(v, e) }
            // 撳實唔拉 = 一下子拉到最闊（同工具 bar 嗰粒一樣：呢度淨係記低，
            // 放手嗰陣先算 —— 見 [handleSizeDrag]）
            setOnLongClickListener { longPressArmed = true; true }
        }
        tool(pasteBtn, ToolIcon.PASTE, "貼上", KeyAction.PASTE)
        pasteBtn.setOnLongClickListener { listener?.onPasteHistory(); true }
        tool(sttBtn, ToolIcon.MIC, "語音輸入", KeyAction.STT)
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
        tool(emojiBtn, ToolIcon.EMOJI, "表情符號", KeyAction.TO_EMOJI)
        tool(aiBtn, ToolIcon.AI, "AI 改寫", KeyAction.AI)
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

        candFlow.setPadding(dp(4f).toInt(), dp(2f).toInt(), dp(4f).toInt(), dp(4f).toInt())
        candFlow.onPick = { listener?.onPickCandidate(it) }
        candScroll.isVerticalScrollBarEnabled = true
        candScroll.addView(candFlow, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        addView(toolFlow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        // 淨低幾多位就俾關聯字食晒（weight = 1），咁樣拉極都唔會撐爆個鍵盤
        addView(candScroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        applyTheme(theme)
        refreshAlignLabel()
    }

    /** 同 [OptionBarsView.tool] 一樣：單色圖案（唔係彩色 emoji），顏色跟主題行 */
    private fun tool(v: TextView, icon: ToolIcon, desc: String, action: KeyAction) {
        v.gravity = Gravity.CENTER
        v.setOnClickListener { listener?.onTool(action) }
        icons[v] = icon to desc
    }

    /** 同 [OptionBarsView.styleTool] 一樣：底色 + 圖案擺正中間 */
    private fun styleTool(v: TextView, faceColor: Int) {
        val spec = icons[v]
        if (spec == null) { v.background = chipBg(faceColor); return }
        v.text = ""
        v.contentDescription = spec.second
        v.background = iconChip(chipBg(faceColor), spec.first, dp(ICON_DP).roundToInt(), theme.text)
    }

    fun applyTheme(t: Theme) {
        theme = t
        setBackgroundColor(t.background)
        candScroll.setBackgroundColor(t.background)
        for (v in listOf(sizeBtn, closeBtn, pasteBtn, sttBtn, emojiBtn, aiBtn)) {
            v.setTextColor(t.text)
            // 圖案係畫死咗色嘅 drawable，setTextColor 影響唔到，要成個底重新砌
            styleTool(v, t.keyFaceAlt)
        }
        refreshAlignLabel()
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
        styleTool(sttBtn, if (sttActive) theme.keyAccent else theme.keyFaceAlt)
    }

    fun setCandidates(list: List<String>) {
        if (candidates == list) return
        candidates = list
        rebuildChips()
        candScroll.scrollTo(0, 0)
    }

    /**
     * 設定頁校完字體之後重新砌啲 chip。**唔可以靠 [setCandidates]** ——
     * 佢見到個 list 冇變就唔會做嘢，改完字體返嚟啲字仲係舊 size。
     *
     * 側邊欄啲 chip 係 flow 排、下面又有得 scroll，字大咗自己會摺多行，
     * 所以唔使似 [OptionBarsView] 咁計高度。
     */
    fun refreshFontScale() {
        val want = Prefs.candTextSp(context)
        if (want == candSp) return
        candSp = want
        chip = CandChip.measure(context, want, PadGroup.CJK)
        rebuildChips()
    }

    /**
     * 側邊欄係闊 screen 自動彈出嚟嗰版關聯字，冇 ▼ 都要睇得晒 ——
     * 所以**唔剪尾**，成千隻字都照畀 [CandFlowView]，佢自己識得 recycle
     * （淨係為見得到嗰幾行起 view，見該 class）。
     */
    private fun rebuildChips() {
        candFlow.applyStyle(candSp, chip, theme.text, theme.keyFace)
        candFlow.setItems(candidates)
    }

    /**
     * 同工具 bar 嗰粒一樣，圖案係「貼邊」嘅樣（一條牆 + 箭嘴指住埋去）。
     * 側邊欄淨係中文九宮格先出（[PadGroup.CJK]），所以永遠唔會撞到
     * [PadAlign.SPLIT]（嗰個係英數鍵盤專用），但個 `when` 都要寫齊。
     */
    fun refreshAlignLabel() {
        icons[sizeBtn] = when (Prefs.align(context)) {
            PadAlign.STRETCH -> ToolIcon.ALIGN_WIDE to "拉闊"
            PadAlign.LEFT_GAP -> ToolIcon.ALIGN_RIGHT to "靠右"
            PadAlign.RIGHT_GAP -> ToolIcon.ALIGN_LEFT to "靠左"
            PadAlign.SPLIT -> ToolIcon.ALIGN_SPLIT to "左右拆開"
        }
        styleTool(sizeBtn, theme.keyFaceAlt)
    }

    // ---- 大細：同工具 bar 嗰粒掣一樣，上下拖高低、左右拖闊窄 ------------------

    private var dragX = 0f
    private var dragY = 0f
    private var dragging = false
    /**
     * 長撳已經 fire 咗，但係**未做嘢**（見 [handleSizeDrag] 嘅 `ACTION_UP`）。
     * 撳實之後仲可以變成拖，所以要等放手先知呢一下到底係「撳實唔郁」定係拖。
     */
    private var longPressArmed = false
    private var horizontal = false

    private fun handleSizeDrag(v: View, e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragX = e.rawX; dragY = e.rawY; dragging = false; horizontal = false
                longPressArmed = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - dragX
                val dy = e.rawY - dragY
                if (!dragging && (abs(dx) > dp(8f) || abs(dy) > dp(8f))) {
                    dragging = true
                    horizontal = abs(dx) > abs(dy)
                    // 開始拖 = 唔會再有長撳。`View` 自己淨係喺手指行出粒掣範圍先會
                    // 取消，而粒掣好闊（打橫成 170dp），慢慢拖根本行唔出去
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
                // 撳實唔郁（長撳）先至「一下子拉到最闊」。**一定要等到放手先做**：
                // 好多人係撳落、停一停、先至拖，長撳（約半秒）嗰陣手指仲未郁，
                // 即刻做就會拖到一半突然彈晒去最闊（2026-08-28 user 踩到：
                // 左右拆開拖拖下兩橛突然併埋）。有拖過就當拖，唔理個長撳
                val hold = longPressArmed && !dragging
                longPressArmed = false
                if (dragging) { dragging = false; return true }
                if (hold) { listener?.onMaxWidth(); return true }
            }
            // 拖到一半俾人搶咗個 gesture（重排／view 換走）：清返個狀態
            MotionEvent.ACTION_CANCEL -> { dragging = false; longPressArmed = false }
        }
        return false
    }

    private companion object {
        /** 側邊欄啲掣 40×36dp，圖案細少少（同工具 bar 睇落一樣大） */
        const val ICON_DP = 20f
    }
}
