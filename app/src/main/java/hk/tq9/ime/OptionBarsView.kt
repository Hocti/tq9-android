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
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import hk.tq9.core.BarMode
import hk.tq9.core.PadAlign
import hk.tq9.core.Prefs
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
 * 唔會同切換掣同時出現。無論邊個狀態都係得一行 42dp，所以轉狀態唔會令個鍵盤跳高跳低。
 */
@SuppressLint("ViewConstructor")
class OptionBarsView(context: Context) : LinearLayout(context) {

    interface Listener {
        fun onPickCandidate(index: Int)
        fun onCycleAlign()
        /** 工具 bar 最左嗰粒上下拖：拉高拉低成個鍵盤（自由移動嗰個方向已經冇用，刪咗） */
        fun onSizeDrag(dyDp: Int)
        /**
         * 候選字 bar 拉大／縮返：**唔係**喺呢個 view 度自己攞位擴闊，
         * 交返俾 host（[expandedView]）覆蓋喺個鍵盤本身度，成個 UI 高度先唔會跳
         */
        fun onExpandChanged(expanded: Boolean)
        /** 工具 bar 撳咗邊粒（[KeyAction.PASTE] / [KeyAction.TO_EMOJI] / [KeyAction.AI]） */
        fun onTool(action: KeyAction)
        /** 長撳「貼上」：下面攤開 clipboard 歷史 */
        fun onPasteHistory()
        /** ✖：由 emoji 表／剪貼簿返去普通鍵盤 */
        fun onCloseSpecialPad()
        /** 最左嗰粒切換掣：喺候選字／工具兩個 view 之間切 */
        fun onSwitchView()
    }

    var listener: Listener? = null
    var theme: Theme = Theme.of(context)

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
    private val flow = FlowLayout(context)
    private val candRow = LinearLayout(context)
    private val toolRow = LinearLayout(context)
    private val barRow = LinearLayout(context)
    private val swap = FrameLayout(context)

    private var candidates: List<String> = emptyList()
    private var expanded = false
    private var mode = BarMode.CANDIDATES
    private var aiReady = false
    private var sttActive = false

    private fun dp(v: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    private val barH get() = dp(42f).roundToInt()

    init {
        orientation = VERTICAL

        sizeBtn.apply {
            gravity = Gravity.CENTER
            textSize = 15f
            setOnClickListener { listener?.onCycleAlign(); refreshAlignLabel() }
            setOnTouchListener { _, e -> handleSizeDrag(e) }
        }
        tool(pasteBtn, "📋", KeyAction.PASTE)
        pasteBtn.setOnLongClickListener { listener?.onPasteHistory(); true }
        // 🎤 由九宮格搬咗上嚟，擺喺「貼上」隔籬（左上角嗰粒都揀得做錄音）
        tool(sttBtn, "🎤", KeyAction.STT)
        tool(emojiBtn, "😀", KeyAction.TO_EMOJI)
        tool(aiBtn, "✨", KeyAction.AI)

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
        // 最左永遠有粒切換掣：候選字／工具兩個 view 之間切。emoji 表／剪貼簿開住嗰陣
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

        flow.hGap = dp(4f).toInt()
        flow.vGap = dp(4f).toInt()
        flow.setPadding(dp(4f).toInt(), dp(4f).toInt(), dp(4f).toInt(), dp(4f).toInt())
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

        // ✖ 擺喺成條 bar 最左，兩段（候選字／工具）都見到，
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
        addView(barRow, LayoutParams(LayoutParams.MATCH_PARENT, barH))

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
        expandedScroll.setBackgroundColor(t.background)
        for (v in listOf(sizeBtn, expandBtn, closeBtn, switchBtn, pasteBtn, sttBtn, emojiBtn, aiBtn)) {
            v.setTextColor(t.text)
            v.background = chipBg(t.keyFaceAlt)
        }
        refreshAiLook()
        refreshSttLook()
        rebuildChips()
    }

    /** 拉大候選字嗰陣，host 攞呢個 view 去蓋喺鍵盤本身度（見 [Listener.onExpandChanged]） */
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
    }

    /** emoji 表／剪貼簿開住嗰陣先出 ✖，冇開就係切換掣（兩粒共用同一個位） */
    fun setCloseVisible(v: Boolean) {
        closeBtn.visibility = if (v) View.VISIBLE else View.GONE
        switchBtn.visibility = if (v) View.GONE else View.VISIBLE
    }

    /** AI 要而家真係揀咗一段字先撳得 */
    fun setAiReady(ready: Boolean) {
        if (aiReady == ready) return
        aiReady = ready
        refreshAiLook()
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
        sttBtn.background = chipBg(if (sttActive) theme.keyAccent else theme.keyFaceAlt)
    }

    fun setCandidates(list: List<String>) {
        candidates = list
        rebuildChips()
        if (list.isEmpty() && expanded) setExpanded(false)
        expandBtn.visibility = if (list.size > 12) View.VISIBLE else View.INVISIBLE
        scroller.scrollTo(0, 0)
    }

    private fun setExpanded(v: Boolean) {
        expanded = v
        // expandedScroll 本身唔喺呢條 bar 度攤開；細嗰行淨係讓位（inivisible 但佔住位），
        // 大嗰份交俾 host 蓋喺鍵盤度
        scroller.visibility = if (v) View.INVISIBLE else View.VISIBLE
        expandBtn.text = if (v) "▲" else "▼"
        rebuildChips()
        listener?.onExpandChanged(v)
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

    private fun rebuildChips() {
        strip.removeAllViews()
        flow.removeAllViews()
        val target: ViewGroup = if (expanded) flow else strip
        val gap = dp(3f).toInt()
        for ((i, w) in candidates.withIndex()) {
            if (w.isEmpty()) continue
            val chip = makeChip(w, i)
            if (target === strip) {
                val lp = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
                lp.setMargins(gap, gap, gap, gap)
                lp.gravity = Gravity.CENTER_VERTICAL
                strip.addView(chip, lp)
            } else {
                flow.addView(chip)
            }
        }
    }

    /** 顯示方式只關中文九宮格事，英文／符號模式就淨係剩返「拉高拉低」 */
    fun refreshAlignLabel() {
        sizeBtn.text = when (Prefs.align(context)) {
            PadAlign.STRETCH -> "↔"
            PadAlign.LEFT_GAP -> "⬅"
            PadAlign.RIGHT_GAP -> "➡"
        }
    }

    // ---- 大細：直接喺粒掣度上下拖 -------------------------------------------

    private var dragY = 0f
    private var dragging = false

    /** 上下拖 = 拉高拉低成個鍵盤。鍵盤永遠貼實底，唔會整個提高留返個窿喺下面。 */
    private fun handleSizeDrag(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { dragY = e.rawY; dragging = false }
            MotionEvent.ACTION_MOVE -> {
                val dy = e.rawY - dragY
                if (!dragging && abs(dy) > dp(8f)) dragging = true
                if (dragging) {
                    val d = resources.displayMetrics.density
                    listener?.onSizeDrag((-dy / d).roundToInt())
                    dragY = e.rawY
                }
            }
            MotionEvent.ACTION_UP -> if (dragging) { dragging = false; return true }
        }
        return false
    }
}
