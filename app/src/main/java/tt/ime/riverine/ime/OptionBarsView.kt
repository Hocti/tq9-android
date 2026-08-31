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
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import tt.ime.riverine.core.BarMode
import tt.ime.riverine.core.PadAlign
import tt.ime.riverine.core.PadGroup
import tt.ime.riverine.core.Prefs
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 鍵盤上面嗰條 bar。撳九宮格右上角嗰粒 ☰（[KeyAction.OPTION]）淨係開／關成條 bar
 * （[BarMode.OFF] ↔ 開），一開返永遠先入 [BarMode.CANDIDATES]。開住嗰陣就靠條 bar
 * 最左嗰粒切換掣（[Listener.onSwitchView]）喺 [BarMode.CANDIDATES] 同 [BarMode.TOOLS]
 * 兩個 view 之間切：
 *
 *  [BarMode.CANDIDATES] 選字／關聯字，太多揀唔晒就撳右邊嗰粒 ▼ 拉大——
 *                        向下遮住成個鍵盤本身（[expandedView]），唔會加高成個 UI
 *  [BarMode.TOOLS]      大細位置、貼上、emoji、AI
 *
 * emoji 表／剪貼簿開住嗰陣，最左嗰粒位讓返俾 ✖（[setCloseVisible]），
 * 唔會同切換掣同時出現。三段都係**得一行**，而且三段一樣高（見 [barHeightFor]），
 * 所以轉狀態唔會令個鍵盤跳高跳低。
 */
@SuppressLint("ViewConstructor")
class OptionBarsView(context: Context) : LinearLayout(context) {

    interface Listener {
        fun onPickCandidate(index: Int)
        fun onCycleAlign()
        /** 工具 bar 最左嗰粒上下拖：拉高拉低成個鍵盤（自由移動嗰個方向已經冇用，刪咗） */
        fun onSizeDrag(dyDp: Int)
        /**
         * 同一粒掣左右拖：拉闊拉窄中文本體。淨係靠左／靠右嗰兩個顯示方式先有用
         * （拉闊模式本來就用盡成行，冇位可以拉）。
         */
        fun onWidthDrag(dxDp: Int)
        /**
         * 同一粒掣長撳（撳實唔拉）：中文本體即刻拉到最闊（＝成個螢幕咁闊）。
         * 拉窄咗之後想一下子還原，唔使一路拖返出去。
         */
        fun onMaxWidth()
        /**
         * 關聯字 bar 拉大／縮返：**唔係**喺呢個 view 度自己攞位擴闊，
         * 交返俾 host（[expandedView]）覆蓋喺個鍵盤本身度，成個 UI 高度先唔會跳
         */
        fun onExpandChanged(expanded: Boolean)
        /** 工具 bar 撳咗邊粒（[KeyAction.PASTE] / [KeyAction.TO_EMOJI] / [KeyAction.AI]） */
        fun onTool(action: KeyAction)
        /** 長撳「貼上」：下面攤開 clipboard 歷史 */
        fun onPasteHistory()
        /**
         * 撳實 🎤 開始錄音（AI 語音輸入專用：撳實一路錄，放手就停）。
         * 回 false = 呢下長撳唔收（用緊系統內置嗰個 STT），粒掣照跌返落短撳。
         */
        fun onSttHoldStart(): Boolean
        /** 放開 🎤。冇喺「撳實錄音」狀態就乜都唔做。 */
        fun onSttHoldEnd()
        /** ✖：由 emoji 表／剪貼簿返去普通鍵盤 */
        fun onCloseSpecialPad()
        /** 最左嗰粒切換掣：喺關聯字／工具兩個 view 之間切 */
        fun onSwitchView()
    }

    var listener: Listener? = null
    var theme: Theme = Theme.of(context)

    /**
     * 而家見緊嘅係邊組鍵盤（中文＋數字／英文＋符號）。大細同貼邊兩組各有各存，
     * 所以「靠左／靠右／拉闊」個圖案要跟返而家嗰組（見 [refreshAlignLabel]）。
     */
    var padGroup: PadGroup = PadGroup.CJK
        set(v) { if (field != v) { field = v; refreshAlignLabel() } }

    private val sizeBtn = TextView(context)
    private val pasteBtn = TextView(context)
    private val sttBtn = TextView(context)
    private val emojiBtn = TextView(context)
    private val aiBtn = TextView(context)

    private val closeBtn = TextView(context)
    private val switchBtn = TextView(context)
    private val expandBtn = TextView(context)
    private val strip = LinearLayout(context)
    private val scroller = HorizontalScrollView(context)
    private val expandedScroll = ScrollView(context)
    private val flow = CandFlowView(context)
    private val candRow = LinearLayout(context)
    private val toolRow = LinearLayout(context)
    private val barRow = LinearLayout(context)
    private val swap = FrameLayout(context)

    /**
     * [strip] 嗰行嘅 chip 池，[syncStrip] 攞嚟 reuse（見該處）。
     *
     * **一定要喺 `init` 上面declare**：Kotlin 係由上而下逐句行落嚟嘅，
     * `init` 入面嗰句 [applyTheme] 會叫 [rebuildChips]，declare 喺下面
     * 個池就仲係 null，一開個鍵盤就 NPE（2026-08-30 踩過）。
     */
    private val stripPool = mutableListOf<TextView>()

    /** 邊粒掣用邊個圖案（＋TalkBack 讀嘅名），轉主題重新畫嗰陣要用 */
    private val icons = LinkedHashMap<TextView, Pair<ToolIcon, String>>()

    private var candidates: List<String> = emptyList()
    private var expanded = false
    private var mode = BarMode.CANDIDATES
    private var aiReady = false
    private var sttActive = false
    private var closeVisible = false
    /** 見 [setSwitchVisible] */
    private var switchAllowed = true

    private fun dp(v: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    /** 關聯字而家幾大（sp）。跟 [padGroup] 嗰組嘅字體設定，見 [Prefs.candTextSp] */
    private var candSp = 0f

    /**
     * chip 而家幾高、上下 padding 各幾多（見 [CandChip]）。條 bar 嘅高度就係
     * 由佢嚟 —— **三段（關／關聯字／工具）共用同一個高度**，工具嗰行冇關聯字
     * 都一樣要跟，唔係轉一轉段個鍵盤就跳高跳低。
     */
    private var chip = CandChip.measure(context, Prefs.candTextSp(context), PadGroup.CJK)

    init {
        orientation = VERTICAL
        candSp = Prefs.candTextSp(context, padGroup)
        chip = CandChip.measure(context, candSp, padGroup)

        @Suppress("ClickableViewAccessibility")
        sizeBtn.apply {
            gravity = Gravity.CENTER
            textSize = 15f
            setOnClickListener { listener?.onCycleAlign(); refreshAlignLabel() }
            setOnTouchListener { v, e -> handleSizeDrag(v, e) }
            // 撳實唔拉 = 一下子拉到最闊。[handleSizeDrag] 喺 DOWN／MOVE 都回 false，
            // 所以 View 本身照計長撳 —— **但係唔喺呢度做嘢，淨係記低**，
            // 放手嗰陣先分得清呢一下係「撳實唔郁」定係拖（見 [handleSizeDrag]）
            setOnLongClickListener { longPressArmed = true; true }
        }
        tool(pasteBtn, ToolIcon.PASTE, "貼上", KeyAction.PASTE)
        pasteBtn.setOnLongClickListener { listener?.onPasteHistory(); true }
        // 🎤 由九宮格搬咗上嚟，擺喺「貼上」隔籬（左上角嗰粒都揀得做錄音）
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

        expandBtn.apply {
            // 一開始都要有個字，唔係就要拉開再收埋一次先見到粒掣
            text = "▼"
            gravity = Gravity.CENTER
            textSize = 15f
            setOnClickListener { setExpanded(!expanded) }
        }
        closeBtn.apply {
            text = "✖"
            gravity = Gravity.CENTER
            textSize = 15f
            visibility = View.GONE
            setOnClickListener { listener?.onCloseSpecialPad() }
        }
        // 最左永遠有粒切換掣：關聯字／工具兩個 view 之間切。emoji 表／剪貼簿開住嗰陣
        // 冇位俾佢（要留返俾 ✖ 返去普通鍵盤），兩粒共用同一個位，一次淨係得一粒見到
        switchBtn.apply {
            text = "⇄"
            gravity = Gravity.CENTER
            textSize = 15f
            setOnClickListener { listener?.onSwitchView() }
        }

        strip.orientation = HORIZONTAL
        strip.gravity = Gravity.CENTER_VERTICAL
        scroller.isHorizontalScrollBarEnabled = false
        scroller.addView(strip, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))

        flow.setPadding(dp(4f).toInt(), dp(4f).toInt(), dp(4f).toInt(), dp(4f).toInt())
        // 攤開嗰版揀完一隻就即刻收返埋 —— 出咗字之後成版關聯字已經換晒，
        // 冇理由仲霸住成個鍵盤等 user 自己撳多次 ▲ 先見返啲鍵
        flow.onPick = { i ->
            if (expanded) setExpanded(false)
            listener?.onPickCandidate(i)
        }
        expandedScroll.addView(flow, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        // 唔加入 `this` —— 拉大嗰陣係 host 攞 expandedView 去蓋喺鍵盤度（向下遮），
        // 唔係喺呢條 bar 自己度攤開拉高成條 bar（以前會累個鍵盤跟住跳高）

        candRow.orientation = HORIZONTAL
        candRow.addView(scroller, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        candRow.addView(expandBtn, LayoutParams(dp(38f).roundToInt(), LayoutParams.MATCH_PARENT))

        toolRow.orientation = HORIZONTAL
        toolRow.setPadding(dp(4f).toInt(), dp(3f).toInt(), dp(4f).toInt(), dp(3f).toInt())
        for (v in listOf(sizeBtn, pasteBtn, sttBtn, emojiBtn, aiBtn)) {
            val lp = LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
            lp.setMargins(dp(3f).toInt(), 0, dp(3f).toInt(), 0)
            toolRow.addView(v, lp)
        }
        toolRow.visibility = View.GONE

        // ✖ 擺喺成條 bar 最左，兩段（關聯字／工具）都見到，
        // emoji 表同剪貼簿一定要有得返去普通鍵盤
        swap.addView(candRow, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        swap.addView(toolRow, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        barRow.orientation = HORIZONTAL
        val closeLp = LayoutParams(dp(42f).roundToInt(), LayoutParams.MATCH_PARENT)
        closeLp.setMargins(dp(4f).toInt(), dp(3f).toInt(), dp(2f).toInt(), dp(3f).toInt())
        barRow.addView(closeBtn, closeLp)
        // 同一個位、同一份 LayoutParams —— GONE 嗰粒唔佔位，所以永遠淨係得一粒喺最左見到
        barRow.addView(switchBtn, LayoutParams(closeLp))
        barRow.addView(swap, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        addView(barRow, LayoutParams(LayoutParams.MATCH_PARENT, CandChip.barHeightPx(context, chip)))

        applyTheme(theme)
        refreshAlignLabel()
    }

    /**
     * 一粒工具掣。個圖案係自己畫嘅單色 [ToolIconDrawable]（唔再係彩色 emoji），
     * 顏色跟主題行，所以要記低邊粒係邊個圖案，轉主題嗰陣 [styleTool] 重新砌過。
     */
    private fun tool(v: TextView, icon: ToolIcon, desc: String, action: KeyAction) {
        v.gravity = Gravity.CENTER
        v.setOnClickListener { listener?.onTool(action) }
        icons[v] = icon to desc
    }

    /**
     * 粒掣個樣：底色 + 圖案（圖案擺正中間，見 [iconChip]）。
     * 冇圖案嗰啲（`✖`／`⇄`／`▼`，佢哋本身就係單色文字符號）就淨係換底色。
     */
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
        expandedScroll.setBackgroundColor(t.background)
        for (v in listOf(sizeBtn, expandBtn, closeBtn, switchBtn, pasteBtn, sttBtn, emojiBtn, aiBtn)) {
            v.setTextColor(t.text)
            // 圖案係畫死咗色嘅 drawable，setTextColor 影響唔到，要成個底重新砌
            styleTool(v, t.keyFaceAlt)
        }
        refreshAlignLabel()
        refreshAiLook()
        refreshSttLook()
        rebuildChips()
    }

    /** 拉大關聯字嗰陣，host 攞呢個 view 去蓋喺鍵盤本身度（見 [Listener.onExpandChanged]） */
    val expandedView: View get() = expandedScroll

    /** 轉緊 pad（中／英／符號…）之前一定要叫，唔係 host 個 padHolder 會清埋覆蓋緊嘅 expandedView */
    fun forceCollapse() { if (expanded) setExpanded(false) }

    private fun chipBg(color: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(6f)
        setStroke(1, Color.argb(30, 128, 128, 128))
    }

    // ---- 三段 --------------------------------------------------------------

    fun setMode(m: BarMode) {
        mode = m
        if (m != BarMode.CANDIDATES && expanded) setExpanded(false)
        candRow.visibility = if (m == BarMode.CANDIDATES) View.VISIBLE else View.GONE
        toolRow.visibility = if (m == BarMode.TOOLS) View.VISIBLE else View.GONE
        updateExpandVisibility()
    }

    /** emoji 表／剪貼簿開住嗰陣先出 ✖，冇開就係切換掣（兩粒共用同一個位） */
    fun setCloseVisible(v: Boolean) {
        closeVisible = v
        refreshLeftBtn()
    }

    /**
     * 條 bar 常駐（[Prefs.barPinned]）而家又見緊中文九宮格嗰陣：切換關聯字／工具
     * 已經由九宮格右上角嗰粒 `⇄` 負責，呢度就唔使再擺多粒做同一件事。
     * 英文／符號頁冇嗰粒鍵，所以嗰陣一定要留返呢粒，唔係就入唔到工具列。
     */
    fun setSwitchVisible(v: Boolean) {
        if (switchAllowed == v) return
        switchAllowed = v
        refreshLeftBtn()
    }

    private fun refreshLeftBtn() {
        closeBtn.visibility = if (closeVisible) View.VISIBLE else View.GONE
        // ✖ 永遠行先（emoji 表／剪貼簿唔可以冇得返去），冇 ✖ 先輪到 ⇄
        switchBtn.visibility = if (!closeVisible && switchAllowed) View.VISIBLE else View.GONE
    }

    /** AI 要而家真係有字改先撳得（揀咗一段，或者成個欄有字） */
    fun setAiReady(ready: Boolean) {
        if (aiReady == ready) return
        aiReady = ready
        refreshAiLook()
    }

    /**
     * 未入 Gemini API key 就**成粒掣唔見咗**，唔係淨係灰咗 ——
     * 用唔到嘅嘢佔住個位冇意思，其餘幾粒會自動攤開填返佢個位。
     */
    fun setAiVisible(visible: Boolean) {
        val want = if (visible) View.VISIBLE else View.GONE
        if (aiBtn.visibility != want) aiBtn.visibility = want
    }

    private fun refreshAiLook() {
        aiBtn.isEnabled = aiReady
        aiBtn.alpha = if (aiReady) 1f else 0.4f
    }

    /** 聽緊嘢嗰陣粒 🎤 著燈 */
    fun setSttActive(on: Boolean) {
        if (sttActive == on) return
        sttActive = on
        refreshSttLook()
    }

    private fun refreshSttLook() {
        styleTool(sttBtn, if (sttActive) theme.keyAccent else theme.keyFaceAlt)
    }

    fun setCandidates(list: List<String>) {
        candidates = list
        rebuildChips()
        if (list.isEmpty() && expanded) setExpanded(false)
        scroller.scrollTo(0, 0)
    }

    /**
     * 粒 `▼` 淨係喺**啲關聯字真係一行擺唔晒**（要左右捲）嗰陣先出現，
     * 否則成粒消失（`GONE`，唔會剩返個空底色霸住個位，其餘關聯字順手攤開多一格位）。
     *
     * 比嘅係 `strip`（啲字實際闊度）同 [swap]（成行嘅闊度，即係**冇**粒 `▼` 嗰陣
     * 用得晒嘅位）—— 唔可以攞 `scroller` 嘅闊度嚟比，因為粒掣一出現就會食咗
     * 38dp，跟住又變返「要捲」，出出入入。
     */
    private fun wantExpandBtn(): Int? = when {
        expanded -> View.VISIBLE                  // 攤開咗一定要有得撳返埋
        mode != BarMode.CANDIDATES || candidates.isEmpty() -> View.GONE
        // 隻字太多俾 rebuildChips 剪咗尾（見 [COLLAPSED_CHIP_LIMIT]）：即使
        // 頭幾個啱啱好擺得晒一行，都要出返粒 ▼，唔係就永遠冇得睇埋後面嗰啲
        candidates.size > COLLAPSED_CHIP_LIMIT -> View.VISIBLE
        swap.width <= 0 -> null                   // 未排過版，判斷唔到，唔好亂郁
        strip.width > swap.width -> View.VISIBLE
        else -> View.GONE
    }

    private fun updateExpandVisibility() {
        val want = wantExpandBtn() ?: return
        if (expandBtn.visibility != want) expandBtn.visibility = want
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        val want = wantExpandBtn() ?: return
        // 排緊版嗰陣改 visibility 會即刻再 requestLayout 一次，讓返下一個 frame 先改
        if (expandBtn.visibility != want) post { expandBtn.visibility = want }
    }

    private fun setExpanded(v: Boolean) {
        expanded = v
        // expandedScroll 本身唔喺呢條 bar 度攤開；細嗰行淨係讓位（inivisible 但佔住位），
        // 大嗰份交俾 host 蓋喺鍵盤度
        scroller.visibility = if (v) View.INVISIBLE else View.VISIBLE
        expandBtn.text = if (v) "▲" else "▼"
        // 唔使 rebuildChips —— 拉大同收埋兩邊嘅內容一早就砌好（見 [rebuildChips]）
        listener?.onExpandChanged(v)
    }

    /** 用喺新起同 reuse 嘅 chip 都要——見 [syncStrip] */
    private fun styleChip(view: TextView, text: String, index: Int) {
        view.text = text
        CandFlowView.styleChip(view, candSp, chip, theme.text, theme.keyFace)
        view.setOnClickListener { listener?.onPickCandidate(index) }
    }

    /**
     * 設定頁校完字體、或者轉咗組（中↔英）之後重新計啲關聯字幾大、條 bar 幾高。
     *
     * `refreshBars()` 每撳一粒鍵都會行一次，所以**冇變就即刻返轉頭** ——
     * 唔係就次次都 `requestLayout` 成條 bar。
     */
    fun refreshFontScale() {
        val want = Prefs.candTextSp(context, padGroup)
        if (want == candSp) return
        candSp = want
        chip = CandChip.measure(context, want, padGroup)
        barRow.layoutParams = barRow.layoutParams.also {
            it.height = CandChip.barHeightPx(context, chip)
        }
        rebuildChips()
    }

    /**
     * 收埋（未撳 ▼）嗰陣條 [strip] 淨係擺得落一行，所以最多起頭
     * [COLLAPSED_CHIP_LIMIT] 個 chip —— 打緊碼嗰陣 `refreshBars()` 一鍵一次，
     * 起多咗嘅都係白起。呢廿個 chip 唔拆重起，交俾 [syncStrip] reuse。
     *
     * 拉大嗰個表就唔使剪 —— [CandFlowView] 自己識得 recycle（見該 class），
     * 成千隻字都照畀佢，佢淨係會為見得到嗰幾行起 view。
     */
    private fun rebuildChips() {
        flow.applyStyle(candSp, chip, theme.text, theme.keyFace)
        flow.setItems(candidates)
        syncStrip(candidates.take(COLLAPSED_CHIP_LIMIT))
    }

    /** 夠用就淨改 text，唔夠先加，多咗就 hide 多嗰啲 */
    private fun syncStrip(source: List<String>) {
        val gap = dp(CandChip.MARGIN_DP).toInt()
        var j = 0
        for ((i, w) in source.withIndex()) {
            if (w.isEmpty()) continue
            val view = if (j < stripPool.size) stripPool[j] else {
                TextView(context).also {
                    val lp = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
                    lp.setMargins(gap, gap, gap, gap)
                    lp.gravity = Gravity.CENTER_VERTICAL
                    strip.addView(it, lp)
                    stripPool.add(it)
                }
            }
            styleChip(view, w, i)
            j++
        }
        for (k in j until stripPool.size) stripPool[k].visibility = View.GONE
    }

    /**
     * 圖案係**貼邊**嘅樣（一條牆 + 箭嘴指住埋去），唔係一支淨嘅左／右箭咀 ——
     * 淨箭咀睇落似「向左移／向右移」，但實際上係「貼實左邊／貼實右邊」。
     */
    fun refreshAlignLabel() {
        icons[sizeBtn] = when (Prefs.align(context, padGroup)) {
            PadAlign.STRETCH -> ToolIcon.ALIGN_WIDE to "拉闊"
            PadAlign.LEFT_GAP -> ToolIcon.ALIGN_RIGHT to "靠右"
            PadAlign.RIGHT_GAP -> ToolIcon.ALIGN_LEFT to "靠左"
            PadAlign.SPLIT -> ToolIcon.ALIGN_SPLIT to "左右拆開"
        }
        styleTool(sizeBtn, theme.keyFaceAlt)
    }

    // ---- 大細：直接喺粒掣度上下拖 -------------------------------------------

    private var dragX = 0f
    private var dragY = 0f
    private var dragging = false
    /**
     * 長撳已經 fire 咗，但係**未做嘢**（見 [handleSizeDrag] 嘅 `ACTION_UP`）。
     * 撳實之後仲可以變成拖，所以要等放手先知呢一下到底係「撳實唔郁」定係拖。
     */
    private var longPressArmed = false

    /**
     * 喺粒掣度直接拖就改到鍵盤大細：
     *
     *  - **上下** = 拉高拉低成個鍵盤（永遠貼實底，唔會整個提高留返個窿喺下面）
     *  - **左右** = 拉闊拉窄中文本體（靠左／靠右嗰陣先有用）
     *
     * 兩個方向唔會撈埋一齊：一拖夠 8dp 就即刻鎖死係邊個方向，
     * 唔係斜少少就會一邊拉高一邊拉闊。
     */
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

    /** 而家拖緊嘅方向（[handleSizeDrag] 一鎖就唔會中途轉） */
    private var horizontal = false

    private companion object {
        /**
         * 工具掣個圖案畫幾大。**唔跟字體 slider 行** —— 佢哋係功能掣，
         * 同鍵盤啲功能鍵一樣（見 [Prefs.funcFontScale]）；條 bar 拉高咗就
         * 上下鬆啲，個圖案唔會跟住大。
         */
        const val ICON_DP = 21f

        /**
         * 冚咗（未撳 ▼）嗰陣，`strip` 最多起幾多個 chip。選字碼太多頁（例如
         * 一個碼夠成百頁）嗰陣 [candidates] 可以成千個，一次過起晒啲 `TextView`
         * 會令成個關聯字 bar 卡一卡先出到嚟 —— 反正冇拉大嗰陣本來就淨係見到
         * 頭幾個，起多都係白起。
         */
        const val COLLAPSED_CHIP_LIMIT = 20
    }
}
