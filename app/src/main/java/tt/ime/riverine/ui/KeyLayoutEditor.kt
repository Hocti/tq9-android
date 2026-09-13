package tt.ime.riverine.ui

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Point
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.DragEvent
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import tt.ime.riverine.core.KeyLayout
import tt.ime.riverine.core.PadFunc
import tt.ime.riverine.ime.FlowLayout
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 設定頁「按鍵排位」：**拖放**砌中文鍵盤左右欄同工具列。
 *
 * 個排位**跟足鍵盤本身嘅樣**（上、左、右），一眼對得返：
 *
 * ```
 *  ┌─ 工具列（上面條 bar，最多八粒，冇長撳）─┐
 *  │ [    ][    ][    ][    ]                 │  兩行四個，唔捲
 *  │ [    ][    ][    ][    ]                 │  吉格 = 拖落去就加喺尾
 *  └──────────────────────────────────────────┘
 *   短  長                              長  短
 *  ┌───────────────┐              ┌───────────────┐
 *  │ ┌────┐        │              │        ┌────┐ │
 *  │ │短撳│┌──┐    │              │    ┌──┐│短撳│ │
 *  │ └────┘│長撳│  │              │  │長撳│└────┘ │
 *  │       └──┘    │              │  └──┘         │
 *  │ …四行…        │              │ …四行…        │
 *  └───────────────┘              └───────────────┘
 *  所有按鍵： [速選字][簡體開關][關聯字]…（拖上去 = 複製，唔會少）
 * ```
 *
 * 兩橛**左右鏡像**：左欄嘅短撳貼實最左、右欄嘅短撳貼實最右，同鍵盤上面
 * 嗰兩條欄企喺螢幕邊嗰個位一樣。長撳嗰格**細粒啲、貼實短撳、低少少**
 * （底對底），一眼睇得出佢係附屬喺隔籬嗰粒，唔係另一粒鍵。
 *
 * `␣`／`⌫`／`⏎` 嗰類（[PadFunc.tapOnly]）**索性冇長撳格**——
 * 佢哋本來就冇長撳（`KeyLayout.Slot.effectiveLong`），畫個灰格喺度
 * 淨係引人去撳。短撳吉住嘅位一樣冇（冇鍵本身，長撳邊個？）。
 *
 * ## 兩種改法
 *
 *  - **拖**：由下面個 pool 拖上去 = 複製一粒過去（pool 嗰堆拖極都唔會少）；
 *    格對格拖 = **兩格對調**；拖返落 pool = 清走嗰格。工具列**冇 `＋` 掣** ——
 *    拖去任何一個吉格就係喺尾加多一粒
 *  - **撳**：撳一下格仔就彈個選單，入面淨係列出**嗰格擺得落**嗰啲功能
 *
 * 兩條路都行同一個 [KeyLayout.checkDrop]：擺唔落嘅嘢**根本擺唔到**，
 * 唔會有「存咗個殘廢排位落去，返到鍵盤先發現打唔到字」呢回事。
 */
@SuppressLint("ViewConstructor")
class KeyLayoutEditor(
    context: Context,
    private var layout: KeyLayout.Layout,
    /** 改完（拖完／揀完／還原預設）就即刻存返落 pref，順手叫個預覽重畫 */
    private val onChange: (KeyLayout.Layout) -> Unit,
) : LinearLayout(context) {

    private fun dp(v: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    private fun dpi(v: Float) = dp(v).roundToInt()

    /**
     * 而家拖緊邊個功能、由邊格拖出嚟（由 pool 拖就係 `null`）。
     *
     * 特登**唔擺落 [ClipData]**：個 clip 淨係擺個名落去做 fallback，
     * 真正嘅嘢喺呢兩個 field —— 同一個 process 之內拖，唔使 serialise 嚟 serialise 去。
     */
    private var dragFunc: PadFunc? = null
    private var dragFrom: KeyLayout.Cell? = null

    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    /**
     * 手指而家喺邊格上面（[hintCell]）、嗰格擺唔擺得落（[hintOk]），
     * 同埋而家排咗出嚟嗰啲格（[cells]，[rebuild] 每次重填）。
     *
     * **一定要 declare 喺 `init` 上面**：Kotlin 由上而下逐句行落嚟，`init`
     * 入面嗰句 [rebuild] 一開波就會掂 [cells]，declare 喺下面就仲係 null，
     * 一開設定頁即刻 NPE（`OptionBarsView.stripPool` 2026-08-30 踩過同一個窿）。
     */
    private var hintCell: KeyLayout.Cell? = null
    private var hintOk = false
    private val cells = LinkedHashMap<KeyLayout.Cell, View>()

    /** 下面成堆掣（拖上去 = 複製）。清空一格就係拖返落嚟呢度 */
    private val pool = FlowLayout(context)

    /** 上、左、右三橛，每次改完就成橛重砌（見 [rebuild]） */
    private val body = LinearLayout(context)

    init {
        orientation = VERTICAL
        // 圓角同高亮全部畫到貼邊，clip 咗就會斬走粒掣下面嗰浸圓角
        clipChildren = false
        clipToPadding = false
        setPadding(0, dpi(2f), 0, dpi(8f))
        body.orientation = VERTICAL
        body.clipChildren = false
        body.clipToPadding = false
        addView(body, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        addView(label("所有按鍵（拖上去 = 加一粒，這裡不會減少）"),
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        pool.hGap = dpi(6f)
        pool.vGap = dpi(6f)
        pool.setPadding(0, dpi(4f), 0, dpi(6f))
        pool.clipChildren = false
        pool.clipToPadding = false
        buildPool()
        // 拖返落 pool = 清走嗰格（必用鍵除外，[KeyLayout.checkDrop] 會擋住）
        pool.setOnDragListener { _, e -> onPoolDrag(e) }
        addView(pool, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        rebuild()
    }

    /** 撳「還原預設排位」之後由外面叫返落嚟 */
    fun setLayout(l: KeyLayout.Layout) {
        layout = l
        rebuild()
    }

    // ---- 砌畫面 -----------------------------------------------------------

    private fun rebuild() {
        body.removeAllViews()
        cells.clear()

        body.addView(toolBlock(), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        val cols = LinearLayout(context)
        cols.orientation = HORIZONTAL
        cols.clipChildren = false
        cols.clipToPadding = false
        // 左欄嘅短撳貼實最左、右欄嘅短撳貼實最右 —— 同鍵盤上面兩條欄企嘅位一樣
        cols.addView(sideBlock("鍵盤左欄", KeyLayout.Area.LEFT, layout.left, tapFirst = true),
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        cols.addView(sideBlock("鍵盤右欄", KeyLayout.Area.RIGHT, layout.right, tapFirst = false),
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        body.addView(cols, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    /**
     * 工具列：[KeyLayout.MAX_TOOLS] 個格，**兩行、一行四個**，冇長撳
     * （[KeyLayout.TOOLS_HAVE_LONG]）。
     *
     * **唔用打橫捲**（2026-09-09 user 要求）—— 一般手機嘅闊度一行擺得落五個
     * 都嫌逼，八個一行就一定要捲，而捲起上嚟後面嗰幾個根本冇人見到，
     * 「最多八個」變咗個講咗等於冇講嘅數。兩行四個就一屏見晒。
     *
     * **冇 `＋` 掣**：吉格自己就係「加多一粒」嘅 drop target，
     * 由下面個 pool 拖上嚟就得（見 [onAppendDrag]）。
     */
    private fun toolBlock(): View {
        val box = LinearLayout(context)
        box.orientation = VERTICAL
        box.clipChildren = false
        box.clipToPadding = false
        box.setPadding(dpi(2f), dpi(2f), dpi(2f), dpi(10f))
        box.addView(label("上方工具列（最多 ${KeyLayout.MAX_TOOLS} 顆，沒有長按）"))

        for (r in 0 until (KeyLayout.MAX_TOOLS + TOOLS_PER_ROW - 1) / TOOLS_PER_ROW) {
            val row = LinearLayout(context)
            row.orientation = HORIZONTAL
            row.clipChildren = false
            row.clipToPadding = false
            for (i in r * TOOLS_PER_ROW until (r + 1) * TOOLS_PER_ROW) {
                val lp = LayoutParams(0, dpi(TAP_H), 1f)
                lp.setMargins(dpi(2f), dpi(3f), dpi(2f), dpi(3f))
                row.addView(toolCell(i), lp)
            }
            box.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        return box
    }

    /**
     * 工具列第 [i] 格。已經有嘢就係普通一格（拖得走、撳得改）；
     * 吉格就係「喺尾加多一粒」—— **唔理係第幾個吉格都加喺尾**，
     * 中間唔會留窿（`KeyLayout.normalise` 本來就唔准工具列有吉格）。
     */
    private fun toolCell(i: Int): View {
        if (i < layout.tools.size) return cell(KeyLayout.Cell(KeyLayout.Area.TOOLS, i, false))
        val append = KeyLayout.Cell(KeyLayout.Area.TOOLS, layout.tools.size, false)
        val v = TextView(context)
        v.gravity = Gravity.CENTER
        v.textSize = 9.5f
        v.contentDescription = "工具列空位，把按鍵拖到這裡就會加在最後"
        v.background = cellBg(Color.TRANSPARENT, dashed = true)
        v.setOnDragListener { _, e -> onAppendDrag(e, append) }
        // 頭一個吉格先至係真正嘅「加喺尾」，之後嗰啲淨係佔位（一樣接得到 drop，
        // 因為加極都係加喺尾）—— 但淨係頭一個記落 cells，唔係幾個格會爭住著色
        if (i == layout.tools.size) cells[append] = v
        return v
    }

    /**
     * 一條欄：標題 + 兩隻欄名（短／長）+ 四行。
     *
     * [tapFirst] = 短撳擺左邊（左欄）定右邊（右欄）。兩橛左右鏡像，
     * 所以除咗次序，兩邊嘅 weight／高度全部一模一樣。
     */
    private fun sideBlock(
        title: String, area: KeyLayout.Area, slots: List<KeyLayout.Slot>, tapFirst: Boolean,
    ): View {
        val box = LinearLayout(context)
        box.orientation = VERTICAL
        box.clipChildren = false
        box.clipToPadding = false
        box.setPadding(dpi(2f), dpi(2f), dpi(2f), dpi(6f))
        box.addView(label(title))

        // 欄名跟返下面嗰兩隻 weight，先至對得正
        val head = LinearLayout(context)
        head.orientation = HORIZONTAL
        val names = if (tapFirst) listOf("短按" to TAP_W, "長按" to LONG_W)
                    else listOf("長按" to LONG_W, "短按" to TAP_W)
        for ((n, w) in names) head.addView(TextView(context).apply {
            text = n
            textSize = 11f
            alpha = 0.7f
            gravity = Gravity.CENTER
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, w))
        box.addView(head, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        for (i in slots.indices) box.addView(sideRow(area, i, tapFirst),
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        return box
    }

    /**
     * 一行：短撳一格 + 長撳一格。
     *
     * 長撳嗰格**矮啲、底對底**（`Gravity.BOTTOM`），所以個頂低過隔籬粒短撳 ——
     * 睇落就係「掛喺短撳下面」，唔會誤會成另一粒鍵。兩格之間**冇罅**
     * （margin 全部落喺出邊），貼得實先似同一粒鍵嘅兩件事。
     */
    private fun sideRow(area: KeyLayout.Area, index: Int, tapFirst: Boolean): View {
        val row = LinearLayout(context)
        row.orientation = HORIZONTAL
        row.gravity = Gravity.BOTTOM
        row.clipChildren = false
        row.clipToPadding = false

        val tapCell = KeyLayout.Cell(area, index, false)
        val tap = cell(tapCell)
        val tapLp = LayoutParams(0, dpi(TAP_H), TAP_W)
        // 貼實嗰邊唔留 margin，出邊先留
        tapLp.setMargins(if (tapFirst) dpi(2f) else 0, dpi(3f), if (tapFirst) 0 else dpi(2f), dpi(3f))

        // 短撳吉住、或者係 `␣`／`⌫`／`⏎` 嗰類 → 冇長撳格，讓個吉位出嚟對齊
        val f = KeyLayout.at(layout, tapCell)
        val longView: View
        val longLp: LayoutParams
        if (f == PadFunc.NONE || f.tapOnly) {
            longView = View(context)
            longLp = LayoutParams(0, dpi(1f), LONG_W)
        } else {
            longView = cell(KeyLayout.Cell(area, index, true))
            longLp = LayoutParams(0, dpi(LONG_H), LONG_W)
            longLp.setMargins(if (tapFirst) 0 else dpi(2f), 0, if (tapFirst) dpi(2f) else 0, dpi(3f))
        }

        if (tapFirst) { row.addView(tap, tapLp); row.addView(longView, longLp) }
        else { row.addView(longView, longLp); row.addView(tap, tapLp) }
        return row
    }

    private fun label(s: String) = TextView(context).apply {
        text = s
        textSize = 12f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(dpi(2f), dpi(2f), dpi(2f), dpi(2f))
    }

    // ---- 一格 -------------------------------------------------------------

    private fun cellBg(fill: Int, dashed: Boolean) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(6f)
        if (dashed) setStroke(dpi(1f), Color.argb(90, 128, 128, 128), dp(4f), dp(3f))
        else setStroke(dpi(1f), Color.argb(90, 128, 128, 128))
    }

    /** 吉嘅畫虛線框（＝「呢度擺得嘢落去」），有嘢就實線 + 寫住個功能名 */
    private fun cell(c: KeyLayout.Cell): TextView {
        val v = TextView(context)
        val f = KeyLayout.at(layout, c)
        v.gravity = Gravity.CENTER
        v.textSize = if (c.long) 8.5f else 9.5f
        v.maxLines = 2
        v.ellipsize = TextUtils.TruncateAt.END
        v.setPadding(dpi(2f), 0, dpi(2f), 0)
        v.text = if (f == PadFunc.NONE) "" else f.label
        v.contentDescription =
            "${if (c.long) "長按" else "短按"}：${if (f == PadFunc.NONE) "空" else f.label}"
        v.background = cellBg(
            if (f == PadFunc.NONE) Color.TRANSPARENT else Color.argb(40, 120, 160, 220),
            f == PadFunc.NONE)
        v.setOnClickListener { pickDialog(c) }
        if (f != PadFunc.NONE) dragSource(v, f, c)
        v.setOnDragListener { _, e -> onCellDrag(e, c) }
        cells[c] = v
        return v
    }

    // ---- pool -------------------------------------------------------------

    private fun buildPool() {
        pool.removeAllViews()
        for (f in KeyLayout.POOL) {
            val v = TextView(context)
            v.text = f.label
            v.textSize = 12f
            v.gravity = Gravity.CENTER
            v.setPadding(dpi(9f), dpi(7f), dpi(9f), dpi(7f))
            v.background = cellBg(Color.argb(30, 128, 128, 128), dashed = false)
            v.contentDescription = f.label
            dragSource(v, f, null)
            // 撳一下唔拖：講返呢粒擺得去邊，省得逐格試
            v.setOnClickListener { toast(whereHint(f)) }
            pool.addView(v)
        }
    }

    private fun whereHint(f: PadFunc): String = when {
        !f.toolOk -> "「${f.label}」只能放在鍵盤左右兩欄，不能放進工具列。"
        !f.sideOk -> "「${f.label}」只能放在工具列。"
        f.tapOnly -> "「${f.label}」只能放在短按那一行，放了之後同一格不會有長按格。"
        // `Eng`：兩邊都放得，但左右兩欄一定要留一個（見 `PadFunc.required`）
        f.required -> "「${f.label}」左右兩欄與工具列都可以放，但左右兩欄一定要留一個。"
        else -> "「${f.label}」左右兩欄與工具列都可以放。"
    }

    // ---- 拖 ---------------------------------------------------------------

    /**
     * 拖緊嗰陣跟住隻手指行嗰個影。
     *
     * 唔用 `View.DragShadowBuilder` 個預設樣 —— 佢係將粒掣原封不動咁畫一次，
     * 同底下嗰格一模一樣，睇落似冇郁過。呢度畫大少少 + 半透明，
     * 一眼就分得出「呢個係跟緊手指嗰粒」。
     */
    private class ChipShadow(private val src: View) : View.DragShadowBuilder(src) {
        override fun onProvideShadowMetrics(size: Point, touch: Point) {
            val w = (src.width * SHADOW_SCALE).roundToInt().coerceAtLeast(1)
            val h = (src.height * SHADOW_SCALE).roundToInt().coerceAtLeast(1)
            size.set(w, h)
            // 擺喺指尖正中，唔係手指會遮住半粒
            touch.set(w / 2, h / 2)
        }

        override fun onDrawShadow(canvas: Canvas) {
            canvas.scale(SHADOW_SCALE, SHADOW_SCALE)
            canvas.saveLayerAlpha(0f, 0f, src.width.toFloat(), src.height.toFloat(), 220)
            src.draw(canvas)
            canvas.restore()
        }

        private companion object { const val SHADOW_SCALE = 1.15f }
    }

    /**
     * 邊粒可以拖走。
     *
     * **唔用長撳開始拖** —— 拖放介面本來就係想快手執位，撳實成半秒先郁得
     * 太笨；一拖夠 [ViewConfiguration.getScaledTouchSlop] 就開始。
     *
     * `ACTION_DOWN` 嗰下即刻 [ViewGroup.requestDisallowInterceptTouchEvent] ——
     * **唔做就成日拖唔郁**（2026-09-09 user 報）：設定頁成版喺個 `ScrollView`
     * 入面，隻手指一向上／向下郁夠 slop，個 ScrollView 就會喺 `onInterceptTouchEvent`
     * 度搶咗成串 event 去捲版，我哋連 `ACTION_MOVE` 都收唔到，粒掣自然唔會郁。
     * 由 pool 拖去上面工具列必定要向上行一大截，所以每次都撞正。
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun dragSource(v: View, f: PadFunc, from: KeyLayout.Cell?) {
        var downX = 0f
        var downY = 0f
        var started = false
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.x; downY = e.y; started = false
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!started && (abs(e.x - downX) > slop || abs(e.y - downY) > slop)) {
                        started = true
                        dragFunc = f
                        dragFrom = from
                        val clip = ClipData.newPlainText("padfunc", f.name)
                        view.startDragAndDrop(clip, ChipShadow(view), null, 0)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    view.parent?.requestDisallowInterceptTouchEvent(false)
            }
            false
        }
    }

    /**
     * 拖緊經過／放低咗喺一格度。
     *
     * 手指行到一格上面就即刻**著返擺唔擺得落**（綠＝得、紅＝唔得），
     * 唔使放咗手食一個 toast 先知 —— 用同一條 [KeyLayout.checkDrop]，
     * 所以睇到綠就一定放得低。
     */
    private fun onCellDrag(e: DragEvent, c: KeyLayout.Cell): Boolean {
        when (e.action) {
            DragEvent.ACTION_DRAG_STARTED -> return dragFunc != null
            DragEvent.ACTION_DRAG_ENTERED -> {
                val f = dragFunc ?: return true
                hintDrop(c, KeyLayout.checkDrop(layout, c, f, dragFrom) == null)
            }
            DragEvent.ACTION_DRAG_EXITED -> clearHintFor(c)
            DragEvent.ACTION_DROP -> {
                clearHint()
                val f = dragFunc ?: return false
                val why = KeyLayout.checkDrop(layout, c, f, dragFrom)
                if (why != null) { toast(why); return false }
                commit(KeyLayout.apply(layout, c, f, dragFrom))
                return true
            }
            DragEvent.ACTION_DRAG_ENDED -> { clearHint(); dragFunc = null; dragFrom = null }
        }
        return true
    }

    /** 跌喺工具列嘅**吉格**度 = 喺尾加多一粒（冇 `＋` 掣，見 [toolBlock]） */
    private fun onAppendDrag(e: DragEvent, append: KeyLayout.Cell): Boolean {
        when (e.action) {
            DragEvent.ACTION_DRAG_STARTED -> return dragFunc != null
            DragEvent.ACTION_DRAG_ENTERED -> {
                val f = dragFunc ?: return true
                hintDrop(append, KeyLayout.checkDrop(layout, append, f, dragFrom) == null)
            }
            DragEvent.ACTION_DRAG_EXITED -> clearHintFor(append)
            DragEvent.ACTION_DROP -> {
                clearHint()
                val f = dragFunc ?: return false
                if (layout.tools.size >= KeyLayout.MAX_TOOLS) {
                    toast("工具列最多 ${KeyLayout.MAX_TOOLS} 顆，要先拖走一顆。")
                    return false
                }
                val why = KeyLayout.checkDrop(layout, append, f, dragFrom)
                if (why != null) { toast(why); return false }
                commit(KeyLayout.appendTool(layout, f, dragFrom))
                return true
            }
            DragEvent.ACTION_DRAG_ENDED -> { clearHint(); dragFunc = null; dragFrom = null }
        }
        return true
    }

    /** 拖返落 pool = 清走嗰格（由 pool 拖出去又放返落 pool 就乜都唔做） */
    private fun onPoolDrag(e: DragEvent): Boolean {
        when (e.action) {
            DragEvent.ACTION_DRAG_STARTED -> return dragFunc != null
            DragEvent.ACTION_DRAG_ENTERED -> clearHint()
            DragEvent.ACTION_DROP -> {
                val from = dragFrom ?: return false
                val why = KeyLayout.checkDrop(layout, from, PadFunc.NONE)
                if (why != null) { toast(why); return false }
                commit(KeyLayout.clear(layout, from))
                return true
            }
            DragEvent.ACTION_DRAG_ENDED -> { clearHint(); dragFunc = null; dragFrom = null }
        }
        return true
    }

    private fun hintDrop(c: KeyLayout.Cell, ok: Boolean) {
        if (hintCell == c && hintOk == ok) return
        hintCell = c
        hintOk = ok
        applyHint()
    }

    private fun clearHint() {
        if (hintCell == null) return
        hintCell = null
        applyHint()
    }

    /**
     * 行出咗某一格先清。
     *
     * **唔可以照 [clearHint]** —— 由條 strip 行入去佢自己一粒掣嗰陣，
     * 兩個 listener 都會響（strip 收 `EXITED`、粒掣收 `ENTERED`），
     * 清錯咗就會著咗又即刻熄，睇落似個高亮壞咗。
     */
    private fun clearHintFor(c: KeyLayout.Cell) {
        if (hintCell == c) clearHint()
    }

    /**
     * 手指而家喺邊格上面就喺嗰格**上面**冚層淺色，唔改佢個 background ——
     * 一改就要記住原本個樣，而啲格仔隨時重砌。
     */
    private fun applyHint() {
        for ((c, v) in cells) v.foreground = when {
            c != hintCell -> null
            hintOk -> tintOf(Color.argb(70, 60, 170, 90))
            else -> tintOf(Color.argb(70, 210, 70, 70))
        }
    }

    private fun tintOf(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(6f)
    }

    // ---- 撳一下：彈選單 ----------------------------------------------------

    /**
     * 細粒格仔拖唔準，所以撳一下都改得到：選單入面**淨係列出嗰格真係擺得落嗰啲**
     * （同一條 [KeyLayout.checkDrop]，所以兩條路唔會有唔同結果）。
     */
    private fun pickDialog(c: KeyLayout.Cell) {
        val now = KeyLayout.at(layout, c)
        val opts = ArrayList<PadFunc>()
        // 「清空」擺第一（最常用），必用鍵嗰格自然會揀唔到（下面隔走咗）
        if (KeyLayout.checkDrop(layout, c, PadFunc.NONE) == null) opts.add(PadFunc.NONE)
        for (f in KeyLayout.POOL) {
            if (f == now) continue
            if (KeyLayout.checkDrop(layout, c, f) == null) opts.add(f)
        }
        if (opts.isEmpty()) {
            toast("這一格沒有其他可以放的按鍵。")
            return
        }
        val names = opts.map { if (it == PadFunc.NONE) "清空這一格" else it.label }
        AlertDialog.Builder(context)
            .setTitle("${if (c.long) "長按" else "短按"}：${if (now == PadFunc.NONE) "空" else now.label}")
            .setItems(names.toTypedArray()) { _, i ->
                commit(KeyLayout.apply(layout, c, opts[i]))
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---- 存 ---------------------------------------------------------------

    private fun commit(l: KeyLayout.Layout) {
        layout = l
        rebuild()
        onChange(l)
    }

    private fun toast(s: String) =
        Toast.makeText(context, s, Toast.LENGTH_SHORT).show()

    private companion object {
        /** 短撳格幾高（dp）。兩行字（例如「轉換／工具列」）啱啱好塞得落 */
        const val TAP_H = 46f

        /** 長撳格矮啲、底對底，所以個頂低過隔籬粒短撳（見 [sideRow]） */
        const val LONG_H = 34f

        /** 短撳／長撳兩格嘅闊度比例 —— 長撳細粒啲，一眼睇得出佢係附屬 */
        const val TAP_W = 1.5f
        const val LONG_W = 1f

        /**
         * 工具列一行擺幾多格。八格分兩行 —— 一行八個平分落去每格得三十幾 dp，
         * 撳都撳唔準；打橫捲就後面嗰幾個永遠見唔到。
         */
        const val TOOLS_PER_ROW = 4
    }
}
