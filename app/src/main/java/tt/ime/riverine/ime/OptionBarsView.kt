package tt.ime.riverine.ime

import android.annotation.SuppressLint
import android.content.Context
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
import tt.ime.riverine.core.KeyLayout
import tt.ime.riverine.core.PadAlign
import tt.ime.riverine.core.PadChrome
import tt.ime.riverine.core.PadFunc
import tt.ime.riverine.core.PadGroup
import tt.ime.riverine.core.Prefs
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 鍵盤上面嗰條 bar。**兩行**：工具（[toolLine]）喺上、關聯字（[candLine]）喺下，
 * 邊行見得到就睇 [BarMode]（2026-09-11 user 要求兩樣可以一齊出）。
 *
 * 上下次序係 2026-09-13 調轉嘅：本來關聯字喺上，但係窄螢幕嗰個側邊欄
 * （[SidePanelView]）一路都係**工具喺上、關聯字喺下**，兩個排法唔一樣，
 * 打橫打直換來換去就要重新搵過粒掣喺邊。而家兩邊一樣 —— 關聯字永遠係最貼近
 * 鍵盤嗰行（揀字嗰下手指行得最短），工具釘喺最上。
 *
 *  [BarMode.CANDIDATES] 淨係關聯字。選字／關聯字太多揀唔晒就撳右邊嗰粒 ▼ 拉大——
 *                        向下遮住成個鍵盤本身（[expandedView]），唔會加高成個 UI
 *  [BarMode.TOOLS]      淨係工具掣。有邊幾粒、乜次序，全部由設定頁「按鍵排位」
 *                        話事（[KeyLayout.Layout.tools]，預設就係大細位置、
 *                        貼上、錄音、emoji、AI 呢五粒）
 *  [BarMode.BOTH]       兩行一齊（條 bar 因此**真係高一倍**）
 *  [BarMode.OFF]        成條 bar 唔見（host 自己 `GONE` 佢），淨係闊 screen 入得到
 *
 * 切換掣（`⇄`，[Listener.onSwitchView]）喺頭三段之間轉。佢**跟住工具嗰行走**：
 * 有工具嗰行就喺工具嗰行，淨係得關聯字嗰陣就搬去關聯字嗰行（[refreshLeftBtn]）——
 * 兩行一齊嗰陣關聯字嗰行就冇咗粒掣，成行讓晒俾啲字。
 *
 * 剪貼簿／AI prompt 嗰啲 overlay 開住嗰陣，工具嗰行最左嗰粒位讓返俾 ✖（[setCloseVisible]），
 * 唔會同切換掣同時出現。每行都係**得一行**，而且兩行一樣高（見 [applyBarSize]），
 * 所以喺頭兩段之間轉唔會令個鍵盤跳高跳低。
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
        /** 長撳「表情」嗰行速選揀咗個 emoji（見 [QuickEmoji]）：直接打出嚟 */
        fun onQuickEmoji(emoji: String)
        /**
         * 工具列粒掣**長撳**。
         *
         * 工具列冇得配長撳（[tt.ime.riverine.core.KeyLayout.TOOLS_HAVE_LONG]），
         * 所以呢度一律係粒掣自己嗰個內置動作：「貼上」＝剪貼簿歷史、
         * 🎤 ＝撳實一路錄。照樣掉返俾 host 嗰個 `onLongPress`，
         * 同鍵盤上面啲鍵行同一套規矩。回 false = 呢下唔收，照跌返落短撳。
         */
        fun onToolLong(key: Key): Boolean
        /**
         * 撳實 🎤 開始錄音（AI 語音輸入專用：撳實一路錄，放手就停）。
         * 回 false = 呢下長撳唔收（用緊系統內置嗰個 STT），粒掣照跌返落短撳。
         */
        fun onSttHoldStart(): Boolean
        /** 放開 🎤。冇喺「撳實錄音」狀態就乜都唔做。 */
        fun onSttHoldEnd()
        /** ✖：由剪貼簿／AI prompt 嗰啲 overlay 返去普通鍵盤（emoji 表嗰粒喺佢自己個 header） */
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
        set(v) {
            if (field == v) return
            field = v
            barSizeFor = "" // 轉組一定要重新度：中英 chip／字體唔同，唔可以承繼舊高度
            refreshAlignLabel()
        }

    /**
     * 工具列而家有邊幾粒（連埋佢哋各自嘅短撳／長撳功能）。
     *
     * 以前係五粒寫死嘅 field，而家成行由設定頁嗰個排位砌（[KeyLayout.Layout.tools]），
     * 加減次序全部拖得，所以要 keep 住個 list 先搵得返「邊粒係 🎤」嗰類問題
     * （見 [btnOf]）。
     */
    private class ToolBtn(val view: TextView, val slot: KeyLayout.Slot)

    private val toolBtns = ArrayList<ToolBtn>()

    /** 上次砌嗰陣個排位係點。冇變就唔重砌（[refreshTools] 每撳一粒鍵都會行） */
    private var toolSlots: List<KeyLayout.Slot> = emptyList()

    private fun btnOf(f: PadFunc): TextView? =
        toolBtns.firstOrNull { it.slot.tap == f }?.view

    /** 「改變大小」粒掣（拖佢就拉鍵盤大細）—— user 可以由工具列拉走，所以會冇 */
    private val sizeBtn: TextView? get() = btnOf(PadFunc.ALIGN)
    private val sttBtn: TextView? get() = btnOf(PadFunc.STT)
    private val aiBtn: TextView? get() = btnOf(PadFunc.AI)
    private val copyBtn: TextView? get() = btnOf(PadFunc.COPY)

    private val closeBtn = TextView(context)
    private val switchBtn = TextView(context)
    /** 淨係得關聯字嗰行嗰陣用嘅切換掣（見 [refreshLeftBtn]） */
    private val candSwitchBtn = TextView(context)
    private val expandBtn = TextView(context)
    private val strip = LinearLayout(context)
    private val scroller = HorizontalScrollView(context)
    private val expandedScroll = ScrollView(context)
    /**
     * 拉大咗嗰版嘅外殼：[expandedScroll] 鋪滿，右上角**浮住**粒 ▲（[collapseBtn]）。
     *
     * 拉大嗰陣成塊嘢連條 bar 都蓋埋（見 [Listener.onExpandChanged]），
     * 條 bar 嗰粒 [expandBtn] 已經俾佢遮住撳唔到，所以呢度要自己有粒收返埋嘅掣。
     */
    private val expandedBox = FrameLayout(context)
    private val collapseBtn = TextView(context)
    private val flow = CandFlowView(context)
    private val candRow = LinearLayout(context)
    /** 工具掣嗰行（每粒鎖死闊度，擺唔晒就 [toolScroll] 打橫捲，見 [ToolStrip]） */
    private val toolRow = ToolStrip(context)
    private val toolScroll = HorizontalScrollView(context)
    /** 關聯字嗰行（連埋最左嗰粒掣）—— 擺喺 [toolLine] **下面**，貼實個鍵盤 */
    private val candLine = LinearLayout(context)
    /** 工具嗰行（連埋最左嗰粒 ✖／⇄）—— 兩行一齊嗰陣喺最上 */
    private val toolLine = LinearLayout(context)

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

    /** 長撳「表情」彈嗰行速選（冇擺「表情」落工具列就係 null，見 [QuickEmoji]） */
    private var quickEmoji: QuickEmojiPopup? = null

    private var candidates: List<String> = emptyList()
    private var expanded = false
    private var mode = BarMode.CANDIDATES
    private var aiReady = false
    private var copyReady = false
    private var sttActive = false
    private var closeVisible = false
    /** 見 [setSwitchVisible] */
    private var switchAllowed = true

    private fun dp(v: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    /** 關聯字而家幾大（sp）。跟 [padGroup] 嗰組嘅字體設定，見 [Prefs.candTextSp] */
    private var candSp = 0f

    /** 一行 bar 而家幾高（px）。浮動底列跟呢個數，轉鍵盤即刻對得齊。 */
    val lineHeightPx: Int get() = candLine.layoutParams?.height ?: 0

    /**
     * 下面鍵盤**一行鍵**幾高（px，0 = 未知）。條 bar 跟呢個數 × 八成封頂。
     * 由 host 每次 `refreshBars()` 擺落嚟（見 `TTInputMethodService.keyRowHeightPx`）。
     */
    var keyRowHeightPx = 0

    /**
     * 轉鍵盤：清 cache，下一句 [refreshFontScale] 一定重新度高度／chip。
     */
    fun invalidateBarSize() { barSizeFor = "" }

    /** 圖案跟條 bar 高度縮；打橫嗰陣唔可以仲用寫死 21dp（會大過下面啲鍵） */
    private var toolIconPx = dp(ICON_DP).roundToInt()
    private var facePx = dp(15f)

    /** 俾一行鍵封頂嗰陣，啲關聯字再縮都唔可以細過呢個 sp（細過就睇唔到） */
    private val minCandSp = Prefs.CAND_TEXT_SP * 0.6f

    /**
     * 上次計條 bar 高度嗰陣啲 input 係點（字體 sp ／ 邊組 ／ 一行鍵幾高）。
     * 一模一樣就唔使再計 —— [applyBarSize] 每撳一粒鍵都會行，而入面度個 chip
     * 係真係起個 `TextView` 去度（見 [CandChip]），唔可以逐粒鍵度一次。
     */
    private var barSizeFor = ""

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
        // 最底嗰行最左永遠有粒切換掣：關聯字 → 工具 → 兩行一齊，一路撳落去。
        // overlay 開住嗰陣冇位俾佢（要留返俾 ✖ 返去普通鍵盤），
        // 同 ✖ 共用同一個位，一次淨係得一粒見到（見 [refreshLeftBtn]）
        for (b in listOf(switchBtn, candSwitchBtn)) b.apply {
            text = "⇄"
            gravity = Gravity.CENTER
            textSize = 15f
            setOnClickListener { listener?.onSwitchView() }
        }

        strip.orientation = HORIZONTAL
        strip.gravity = Gravity.CENTER_VERTICAL
        scroller.isHorizontalScrollBarEnabled = false
        scroller.addView(strip, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))

        dp(FLOW_PAD_DP).roundToInt().let { flow.setPadding(it, it, it, it) }
        // 攤開嗰版揀完一隻就即刻收返埋 —— 出咗字之後成版關聯字已經換晒，
        // 冇理由仲霸住成個鍵盤等 user 自己撳多次 ▲ 先見返啲鍵
        flow.onPick = { i ->
            if (expanded) setExpanded(false)
            listener?.onPickCandidate(i)
        }
        expandedScroll.addView(flow, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        collapseBtn.apply {
            text = "▲"
            gravity = Gravity.CENTER
            textSize = 15f
            contentDescription = "收起關聯字"
            setOnClickListener { setExpanded(false) }
        }
        // 粒 ▲ 擺喺個 scroll **之上**（唔跟住捲）—— 捲到去邊都收得返埋
        expandedBox.addView(expandedScroll, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        expandedBox.addView(collapseBtn, FrameLayout.LayoutParams(0, 0).also {
            it.gravity = Gravity.TOP or Gravity.END
        })
        refreshCollapseBtn()
        // [expandedBox] 唔加入 `this` —— 拉大嗰陣係 host 攞 expandedView 去蓋喺
        // 成個鍵盤度，唔係喺呢條 bar 自己度攤開拉高成條 bar（以前會累個鍵盤跟住跳高）

        candRow.orientation = HORIZONTAL
        candRow.addView(scroller, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        candRow.addView(expandBtn,
            LayoutParams(dp(EXPAND_BTN_DP).roundToInt(), LayoutParams.MATCH_PARENT))

        // 罅全部由粒掣自己嘅 margin 出（同啲鍵一樣），呢度唔再加 padding
        toolRow.setPadding(0, 0, 0, 0)
        // 工具列有邊幾粒、乜次序，全部由設定頁嗰個排位話事（見 [rebuildTools]）
        refreshToolWidth()
        rebuildTools(PadChrome.visibleToolSlots(context, padGroup))
        // fillViewport 一定要開：[ToolStrip] 就係靠佢度兩次先分得清
        // 「縮到最細都擺唔晒」（要捲）同「有位攤開」（平分封頂）
        toolScroll.isHorizontalScrollBarEnabled = false
        toolScroll.isFillViewport = true
        toolScroll.addView(toolRow, LayoutParams(
            LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))

        // 兩行各自一個 view，**工具嗰行喺上、關聯字嗰行喺下**（同側邊欄一樣，
        // 見 class doc）。兩行一樣高、各自 GONE 得，所以 [BarMode] 四段全部
        // 係呢兩個 visibility 嘅組合（見 [setMode]）
        val lineH = CandChip.barHeightPx(context, chip)
        fun btnLp() = LayoutParams(dp(42f).roundToInt(), LayoutParams.MATCH_PARENT).also {
            it.setMargins(gap(), gap(), gap(), gap())
        }

        // ✖ 擺喺工具嗰行最左（overlay 開住嗰陣一定係 [BarMode.TOOLS]），
        // 剪貼簿嗰類 overlay 一定要有得返去普通鍵盤。同粒 ⇄ 共用個位 —— GONE 嗰粒
        // 唔佔位，所以永遠淨係得一粒喺最左見到
        toolLine.orientation = HORIZONTAL
        toolLine.addView(closeBtn, btnLp())
        toolLine.addView(switchBtn, btnLp())
        toolLine.addView(toolScroll, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        toolLine.visibility = View.GONE
        addView(toolLine, LayoutParams(LayoutParams.MATCH_PARENT, lineH))

        candLine.orientation = HORIZONTAL
        candLine.addView(candSwitchBtn, btnLp())
        candLine.addView(candRow, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        addView(candLine, LayoutParams(LayoutParams.MATCH_PARENT, lineH))

        applyTheme(theme)
        refreshAlignLabel()
    }

    /**
     * 條 bar 入面啲嘢要**擺喺鍵盤本體上面**，唔好鋪滿成行：鍵盤靠左／靠右／置中
     * （[PadAlign]）嗰陣，兩邊就留返同鍵盤一樣咁闊嘅白（2026-09-11 user 要求）——
     * 工具掣、切換掣／✖、關聯字同粒 ▼ 全部跟住郁，唔係鍵盤企咗一邊，
     * 上面啲掣仍然霸住成行，對唔上。
     *
     * **淨係加兩行嘅 padding**：條 bar 自己嘅底色照舊鋪滿成行
     * （睇落仍然係一條完整嘅 bar），高度亦都唔關事。
     *
     * 每次 `refreshBars()` 由 host 擺落嚟（見 `TTInputMethodService.padInsets`）。
     * 「拉闊」同「左右拆開」本來就用盡成行，傳 0 入嚟。
     */
    fun setContentInsets(left: Int, right: Int) {
        for (line in lines) {
            if (line.paddingLeft == left && line.paddingRight == right) continue
            line.setPadding(left, 0, right, 0)
        }
    }

    /** 兩行（關聯字、工具）—— 高度、padding 呢啲兩行一定要一齊改 */
    private val lines get() = listOf(candLine, toolLine)

    /**
     * 設定頁改完排位就要重砌成行（[refreshTools] 見到冇變就唔會叫落嚟）。
     *
     * 每粒掣三層嘢：
     *  - **短撳** → [Listener.onTool]
     *  - **長撳** → [Listener.onToolLong]，即係粒掣本身嗰個內置動作
     *    （貼上 → 剪貼簿歷史、🎤 → 撳實一路錄）；工具列冇得自己配長撳
     *  - 「改變大小」再多一層：喺粒掣度**直接拖**就拉鍵盤大細（見 [handleSizeDrag]）
     *
     * 個圖案係自己畫嘅單色 [ToolIconDrawable]（唔係彩色 emoji），顏色跟主題行，
     * 所以要記低邊粒係邊個圖案，轉主題嗰陣 [styleTool] 重新砌過。
     * 冇圖案嗰啲（例如「下頁」）就寫返 [PadFunc.face] 嗰幾隻字。
     */
    @Suppress("ClickableViewAccessibility")
    private fun rebuildTools(slots: List<KeyLayout.Slot>) {
        toolSlots = slots
        for (b in toolBtns) icons.remove(b.view)
        toolBtns.clear()
        toolRow.removeAllViews()
        quickEmoji?.dismiss()
        quickEmoji = null

        for (slot in slots) {
            val f = slot.tap
            val v = TextView(context)
            v.gravity = Gravity.CENTER
            v.setTextSize(TypedValue.COMPLEX_UNIT_PX, facePx)
            // 工具列冇長撳（[KeyLayout.TOOLS_HAVE_LONG]），所以粒 key 冇 longAction ——
            // 撳實嗰下一律跌落粒掣自己嗰個內置動作（見 [Listener.onToolLong]）
            val key = f.toKey()
            if (f == PadFunc.ALIGN) {
                // 撳一下 = 轉顯示方式；拖 = 拉大細；撳實唔郁 = 一下子拉到最闊
                // （**唔喺長撳度即刻做**，見 [handleSizeDrag] 嗰段 ACTION_UP）
                v.setOnClickListener { listener?.onCycleAlign(); refreshAlignLabel() }
                v.setOnTouchListener { view, e -> handleSizeDrag(view, e) }
                v.setOnLongClickListener { longPressArmed = true; true }
            } else {
                v.setOnClickListener { listener?.onTool(f.action()) }
                v.setOnLongClickListener { listener?.onToolLong(key) == true }
            }
            // 長撳「表情」= 彈一行最近用過嘅 emoji 速選（見 [QuickEmoji]）。
            // 彈唔出（一個 emoji 都冇）就回 false，粒掣照跌返落 [Listener.onToolLong]
            if (f == PadFunc.EMOJI) {
                val pick = QuickEmojiPopup(v) { e -> listener?.onQuickEmoji(e) }
                quickEmoji = pick
                v.setOnLongClickListener {
                    pick.open(theme, Prefs.fontScale(context, padGroup)) ||
                        listener?.onToolLong(key) == true
                }
                v.setOnTouchListener { _, e -> pick.onTouch(e) }
            }
            // 🎤 放手就收工。onTouch 回 false，粒掣本身嘅短撳／長撳照行；
            // ACTION_UP 一定喺 performClick 之前到，所以撳一下唔會誤當放手收工
            if (f == PadFunc.STT) v.setOnTouchListener { _, e ->
                if (e.actionMasked == MotionEvent.ACTION_UP ||
                    e.actionMasked == MotionEvent.ACTION_CANCEL) listener?.onSttHoldEnd()
                false
            }
            val icon = f.toolIcon()
            if (icon != null) icons[v] = icon to f.label else v.text = f.face
            v.contentDescription = f.label

            // 闊度交俾 [ToolStrip] 逐次度嗰陣填（min～max 之間），呢度隨便畀個數
            val lp = LayoutParams(toolRow.minW, LayoutParams.MATCH_PARENT)
            lp.setMargins(gap(), gap(), gap(), gap())
            toolRow.addView(v, lp)
            toolBtns.add(ToolBtn(v, slot))
        }
        for (b in toolBtns) { b.view.setTextColor(theme.text); styleTool(b.view, theme.keyFaceAlt) }
        refreshAlignLabel()
        refreshFloatDockLabel()
        refreshAiLook()
        refreshCopyLook()
        refreshSttLook()
    }

    /**
     * 設定頁改咗排位未？改咗就重砌成行工具列。
     *
     * `refreshBars()` 每撳一粒鍵都會行一次，所以**冇變就即刻返轉頭** ——
     * 唔係就次次都拆晒啲 view 起過。
     */
    fun refreshTools() {
        refreshToolWidth()
        val want = PadChrome.visibleToolSlots(context, padGroup)
        if (want != toolSlots) rebuildTools(want)
    }

    /**
     * 工具掣封頂幾闊 ＝ **中文九宮格一粒鍵嘅闊度**（2026-09-11 user 要求）。
     *
     * 以前封頂寫死 76dp，擺一至五粒嗰陣條 bar 啲掣唔係闊過就係窄過下面啲鍵，
     * 兩截嘢對唔正。而家跟返 [PadMetrics.cellW] —— 嗰個係**連埋兩邊嗰浸罅**
     * 嘅格仔闊度（粒鍵畫嗰陣自己縮咗 `gapPx`），而 [ToolStrip.maxW] 唔計 margin，
     * 所以要減返兩浸 [gap]，見到嗰個闊度先至真係一樣。
     *
     * 粒鍵幾闊會跟住拉大細（[Prefs.widthScale] 嗰啲）郁，所以每次 [refreshTools]
     * 都度多次 —— [ToolStrip.maxW] 冇變就唔會 `requestLayout`。
     */
    private fun refreshToolWidth() {
        val availW = width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        if (availW <= 0) return
        val cell = PadMetrics(context, availW, group = PadGroup.CJK).cellW - gap() * 2
        toolRow.maxW = cell.roundToInt().coerceAtLeast(toolRow.minW)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw) refreshToolWidth()
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
        v.background = iconChip(chipBg(faceColor), spec.first, toolIconPx, theme.text)
    }

    fun applyTheme(t: Theme) {
        theme = t
        setBackgroundColor(t.background)
        expandedScroll.setBackgroundColor(t.background)
        expandedBox.setBackgroundColor(t.background)
        for (v in listOf(expandBtn, closeBtn, switchBtn, candSwitchBtn, collapseBtn) +
            toolBtns.map { it.view }) {
            v.setTextColor(t.text)
            // 圖案係畫死咗色嘅 drawable，setTextColor 影響唔到，要成個底重新砌
            styleTool(v, t.keyFaceAlt)
        }
        refreshAlignLabel()
        refreshFloatDockLabel()
        refreshAiLook()
        refreshCopyLook()
        refreshSttLook()
        rebuildChips()
    }

    /** 拉大關聯字嗰陣，host 攞呢個 view 去蓋住成個鍵盤（見 [Listener.onExpandChanged]） */
    val expandedView: View get() = expandedBox

    /** 轉緊 pad（中／英／符號…）之前一定要叫，唔係 host 個 padHolder 會清埋覆蓋緊嘅 expandedView */
    fun forceCollapse() { if (expanded) setExpanded(false) }

    /**
     * 一粒掣個底。**同鍵盤啲鍵一模一樣**（2026-09-09 user 要求）：
     * 圓角 6dp（`KeyboardBaseView.radius`）、**冇邊框**。
     *
     * 以前多咗一條 1px 灰邊，鍵盤上面啲鍵一條都冇，行落嚟兩截嘢唔似同一套。
     * 粒粒之間嗰浸罅亦都改咗跟設定頁條「邊框粗細」（[Prefs.gapDp]，
     * 同啲鍵嗰個 `gapPx` 同一個數），唔再係寫死嘅 3dp。
     */
    private fun chipBg(color: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(6f)
    }

    /** 一粒掣周圍留幾多位 —— 同啲鍵嗰個 `gapPx` 同一個數（見 [chipBg]） */
    private fun gap() = dp(Prefs.gapDp(context).toFloat()).roundToInt()

    // ---- 三段 --------------------------------------------------------------

    fun setMode(m: BarMode) {
        mode = m
        if (!m.hasCands && expanded) setExpanded(false)
        candLine.visibility = if (m.hasCands) View.VISIBLE else View.GONE
        toolLine.visibility = if (m.hasTools) View.VISIBLE else View.GONE
        refreshLeftBtn()
        updateExpandVisibility()
    }

    /**
     * 剪貼簿／AI prompt 嗰啲 overlay 開住嗰陣先出 ✖，冇開就係切換掣（兩粒共用同一個位）。
     * emoji 表 2026-09-13 起唔行呢條路 —— 粒 ✖ 搬咗入 `EmojiPadView` 個 header
     * （擺喺搵字掣左邊），呢度收到嘅係 `false`。
     */
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

    /**
     * 最左嗰粒邊個出。粒 `⇄` **跟住工具嗰行走**（冇工具嗰行先搬去關聯字嗰行）——
     * 兩行一齊嗰陣關聯字嗰行就成行讓晒俾啲字，唔會上下兩粒 `⇄` 做同一件事。
     */
    private fun refreshLeftBtn() {
        closeBtn.visibility = if (closeVisible) View.VISIBLE else View.GONE
        // ✖ 永遠行先（剪貼簿嗰類 overlay 唔可以冇得返去），冇 ✖ 先輪到 ⇄
        val swit = !closeVisible && switchAllowed
        switchBtn.visibility = if (swit && mode.hasTools) View.VISIBLE else View.GONE
        candSwitchBtn.visibility = if (swit && !mode.hasTools) View.VISIBLE else View.GONE
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
        val v = aiBtn ?: return
        if (v.visibility != want) v.visibility = want
    }

    /** 複製要個欄有字（揀咗字或者成個欄有嘢）先撳得，同 [setAiReady] 一樣做法 */
    fun setCopyReady(ready: Boolean) {
        if (copyReady == ready) return
        copyReady = ready
        refreshCopyLook()
    }

    private fun refreshCopyLook() {
        val v = copyBtn ?: return
        v.isEnabled = copyReady
        v.alpha = if (copyReady) 1f else 0.4f
    }

    private fun refreshAiLook() {
        val v = aiBtn ?: return
        v.isEnabled = aiReady
        v.alpha = if (aiReady) 1f else 0.4f
    }

    /** 聽緊嘢嗰陣粒 🎤 著燈 */
    fun setSttActive(on: Boolean) {
        if (sttActive == on) return
        sttActive = on
        refreshSttLook()
    }

    private fun refreshSttLook() {
        styleTool(sttBtn ?: return, if (sttActive) theme.keyAccent else theme.keyFaceAlt)
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
     * 比嘅係 `strip`（啲字實際闊度）同 [candRow]（關聯字嗰橛嘅闊度，即係**冇**粒
     * `▼` 嗰陣用得晒嘅位）—— 唔可以攞 `scroller` 嘅闊度嚟比，因為粒掣一出現就會
     * 食咗 38dp，跟住又變返「要捲」，出出入入。
     */
    private fun wantExpandBtn(): Int? = when {
        expanded -> View.VISIBLE                  // 攤開咗一定要有得撳返埋
        !mode.hasCands || candidates.isEmpty() -> View.GONE
        // 隻字太多俾 rebuildChips 剪咗尾（見 [COLLAPSED_CHIP_LIMIT]）：即使
        // 頭幾個啱啱好擺得晒一行，都要出返粒 ▼，唔係就永遠冇得睇埋後面嗰啲
        candidates.size > COLLAPSED_CHIP_LIMIT -> View.VISIBLE
        candRow.width <= 0 -> null                // 未排過版，判斷唔到，唔好亂郁
        strip.width > candRow.width -> View.VISIBLE
        else -> View.GONE
    }

    private fun updateExpandVisibility() {
        val want = wantExpandBtn() ?: return
        if (expandBtn.visibility != want) expandBtn.visibility = want
    }

    /** 鍵盤收起／view 拆走：速選 popup 係 `PopupWindow`，唔收就會漏喺度 */
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        quickEmoji?.dismiss()
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
        if (!applyBarSize()) return
        rebuildChips()
    }

    /**
     * 計啱 [candSp]、[chip] 同條 bar 嘅高度，回傳有冇變過（冇變就唔好 `requestLayout`）。
     *
     * 兩步：
     *  1. 用設定頁嗰個字體大細度個 chip，條 bar 本來就係咁高；
     *  2. 但係條 bar 俾下面一行鍵封咗頂（[keyRowHeightPx]）嘅話，個 chip 就擺唔落 ——
     *     一係俾 `AT_MOST` 迫窄（個字裁頂兼且上下唔對稱，見 [CandChip] 個 doc），
     *     一係自己主動縮細。梗係縮細：啲關聯字細少少仲睇得，裁咗一橛就唔知係乜字。
     */
    private fun applyBarSize(): Boolean {
        val want = Prefs.candTextSp(context, padGroup)
        val input = "$want/${padGroup.name}/$keyRowHeightPx"
        if (input == barSizeFor) return false
        barSizeFor = input
        var sp = want
        var c = CandChip.measure(context, sp, padGroup)
        var h = CandChip.barHeightPx(context, c, keyRowHeightPx)
        val vGap = dp(CandChip.MARGIN_Y_DP * 2)
        var need = c.chipH + vGap
        // 行鍵封咗頂 → 縮字。縮到 [minCandSp] 都仲高過條 bar，就**拉高條 bar**
        // （唔好裁走字嘅下半）
        if (need > h) {
            sp = max(want * h / need, minCandSp)
            c = CandChip.measure(context, sp, padGroup)
            need = c.chipH + vGap
            h = CandChip.resolveHeight(h, need.roundToInt())
        }
        val icon = min(dp(ICON_DP), (h - gap() * 2) * 0.9f).roundToInt().coerceAtLeast(1)
        candSp = sp
        chip = c
        toolIconPx = icon
        // 兩行一定要一樣高，唔係兩行一齊出嗰陣上下唔對稱
        for (line in lines) line.layoutParams = line.layoutParams.also { it.height = h }
        // ⇄ ✖ ▼ 同工具掣面嗰幾個字：條 bar 矮過 15sp 就要跟住縮，唔係裁頂
        facePx = min(dp(15f), h * 0.42f).coerceAtLeast(dp(10f))
        for (v in listOf(expandBtn, closeBtn, switchBtn, candSwitchBtn, collapseBtn) +
            toolBtns.map { it.view }) {
            v.setTextSize(TypedValue.COMPLEX_UNIT_PX, facePx)
            styleTool(v, theme.keyFaceAlt)
        }
        // 粒 ▲ 跟一行關聯字咁高，所以啲字一縮細就要度過
        refreshCollapseBtn()
        return true
    }

    /**
     * 拉大咗嗰版右上角粒 ▲ 幾大、擺喺邊，兼同步留返個空位俾佢
     * （[CandFlowView.firstRowInsetRight]，2026-09-13 user 要求）。
     *
     * 粒掣**啱啱好一行咁高**（[CandChip.chipH]），四邊對齊 [flow] 嗰浸 padding ——
     * 睇落就似第一行最右嗰粒 chip，唔會浮咗喺半空。
     */
    private fun refreshCollapseBtn() {
        val w = dp(EXPAND_BTN_DP).roundToInt()
        val pad = dp(FLOW_PAD_DP).roundToInt()
        val lp = collapseBtn.layoutParams as? FrameLayout.LayoutParams ?: return
        if (lp.width != w || lp.height != chip.chipH ||
            lp.topMargin != pad || lp.rightMargin != pad) {
            lp.width = w
            lp.height = chip.chipH
            lp.setMargins(0, pad, pad, 0)
            collapseBtn.layoutParams = lp
        }
        // 留位 = 粒掣本身 + 同下一粒 chip 之間嗰浸罅
        flow.firstRowInsetRight = w + dp(CandChip.MARGIN_DP).roundToInt()
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
        val gx = dp(CandChip.MARGIN_DP).toInt()
        val gy = dp(CandChip.MARGIN_Y_DP).toInt()
        var j = 0
        for ((i, w) in source.withIndex()) {
            if (w.isEmpty()) continue
            val view = if (j < stripPool.size) {
                stripPool[j].also { v ->
                    (v.layoutParams as? LayoutParams)?.setMargins(gx, gy, gx, gy)
                }
            } else {
                TextView(context).also {
                    val lp = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
                    lp.setMargins(gx, gy, gx, gy)
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
        val v = sizeBtn ?: return
        icons[v] = when (Prefs.align(context, padGroup)) {
            PadAlign.STRETCH -> ToolIcon.ALIGN_WIDE to "拉闊"
            PadAlign.LEFT_GAP -> ToolIcon.ALIGN_RIGHT to "靠右"
            PadAlign.RIGHT_GAP -> ToolIcon.ALIGN_LEFT to "靠左"
            PadAlign.SPLIT -> ToolIcon.ALIGN_SPLIT to "左右拆開"
            PadAlign.CENTER -> ToolIcon.ALIGN_CENTER to "置中"
            PadAlign.FLOATING -> ToolIcon.ALIGN_FLOAT to "浮動"
        }
        styleTool(v, theme.keyFaceAlt)
    }

    /** 浮動時呢粒係「取消浮動」（底列嗰粒搬咗上嚟）；貼底就係入浮動。 */
    fun refreshFloatDockLabel() {
        val v = btnOf(PadFunc.FLOAT) ?: return
        val dock = Prefs.align(context, padGroup) == PadAlign.FLOATING
        icons[v] = if (dock) ToolIcon.KEYBOARD to "取消浮動"
            else ToolIcon.ALIGN_FLOAT to PadFunc.FLOAT.label
        styleTool(v, theme.keyFaceAlt)
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
                // 條工具列 2026-09-09 起擺咗入 `HorizontalScrollView`（擺唔晒就捲），
                // 而粒「改變大小」**左右拖 = 拉闊拉窄鍵盤** —— 唔搶返個 gesture，
                // 個 scroll view 就會喺 onInterceptTouchEvent 度食咗，
                // 變成捲條 bar，闊度點拖都唔郁。拉大細行先，捲讓路。
                v.parent?.requestDisallowInterceptTouchEvent(true)
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
        /** 粒 ▼／▲ 幾闊（條 bar 嗰粒同拉大咗嗰版右上角嗰粒一樣，睇落先似同一粒掣） */
        const val EXPAND_BTN_DP = 38f

        /** [flow] 四邊嗰浸 padding —— 粒 ▲ 四邊都要對返佢（見 [refreshCollapseBtn]） */
        const val FLOW_PAD_DP = 4f

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
