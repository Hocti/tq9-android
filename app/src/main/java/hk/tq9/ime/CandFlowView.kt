package hk.tq9.ime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.text.TextPaint
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.TextView
import hk.tq9.core.PadGroup
import hk.tq9.core.Prefs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 一大版候選字（自動摺行），**只起見得到嗰幾個 `TextView`**。
 *
 * 兩個地方用：撳 ▼ 拉大嗰個表（[OptionBarsView.expandedView]），同埋闊 screen
 * 自動彈出嚟嗰個側邊欄（[SidePanelView]）—— 兩邊都係擺喺 `ScrollView` 入面
 * 由上向下捲。
 *
 * ## 點解要自己 recycle
 *
 * 一個字碼夠 900 幾隻字（`mapped_table` 最長嗰格 951 個），以前係一個字起一個
 * `TextView` 全部塞晒落個 `FlowLayout` —— 開個表就要 new 900 幾個 view、
 * 每次 measure／layout 又行足 900 幾個仔，一拉大就頓一頓；仲要嗰堆 view 之後
 * 一世掛喺度唔會放。
 *
 * 呢度改成**同 `RecyclerView` 一樣嘅諗法**：見得到嗰個窗口（＋上下各一行 buffer）
 * 先起 view，捲走咗就收返入 [scrap] 等下一個位重用。所以 900 隻字同 20 隻字
 * 一樣咁快，掛住嘅 view 永遠得成屏咁多。
 *
 * ## 點解排版唔使起 view 都計到
 *
 * 每個 chip 一樣高（[CandChip.chipH]），所以行高固定，第 r 行個頂就係
 * `paddingTop + r * (chipH + vGap)`——淨係要知道每隻字幾闊就摺得到行。
 * 闊度問 [probe] 呢個 `TextPaint` 度就得（`measureText` 自己識轉 CJK fallback
 * 字型度）：**唔可以用佢度高度**，點解見 [CandChip] 個 doc。
 *
 * 度完之後直接用 `EXACTLY` 個闊度去 measure 粒 chip，所以就算 paint 度出嚟同
 * `TextView` 自己排版爭一兩 px，都唔會累到隻字對唔正位。
 */
@SuppressLint("ViewConstructor")
class CandFlowView(context: Context) : ViewGroup(context) {

    /** 撳咗第幾個。index 係成個 [items] 嘅**絕對位置**，同以前逐個起 chip 嗰陣一樣 */
    var onPick: ((Int) -> Unit)? = null

    private fun dp(v: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    private val hGap = dp(CandChip.MARGIN_DP).toInt()
    private val vGap = dp(CandChip.MARGIN_DP).toInt()

    /** chip 左右 padding／最窄幾多，同條 bar 收埋嗰行一模一樣（見 [styleChip]） */
    private val padH = dp(10f).roundToInt()
    private val minW = dp(38f).roundToInt()

    private var items: List<String> = emptyList()

    // ---- 一粒 chip 個樣（[applyStyle] 設定）--------------------------------

    private var textSp = 0f
    private var chip = CandChip.measure(context, Prefs.candTextSp(context), PadGroup.CJK)
    private var textColor = Color.BLACK
    private var faceColor = Color.WHITE

    /** 淨係攞嚟度字闊 —— 高度一定要問真 `TextView`，點解見 [CandChip] */
    private val probe = TextPaint(Paint.ANTI_ALIAS_FLAG)

    // ---- 排好嘅版（[buildRows] 計，唔使起 view）-----------------------------

    private var widths = IntArray(0)
    private var xs = IntArray(0)
    private var rows = IntArray(0)
    /** 每行由第幾個字開始，尾多一格 = [items] 個數（方便攞「呢行去到邊」） */
    private val rowStart = ArrayList<Int>()
    private var rowCount = 0
    private var contentH = 0
    /** 上次係用邊個闊度排嘅版，－1 = 要重排 */
    private var builtForWidth = -1

    // ---- 而家掛住嘅 view ----------------------------------------------------

    /** 位置 → 而家喺嗰個位嘅 chip */
    private val active = HashMap<Int, TextView>()
    /** 捲走咗、等緊派去第二個位嘅 chip（照樣掛住，淨係 `GONE`） */
    private val scrap = ArrayList<TextView>()
    private var winFrom = 0
    private var winTo = 0
    /** [updateWindow] 度可見範圍用。捲親就叫一次，唔好逐次 new 個 `Rect` */
    private val visRect = Rect()

    /** 粒粒 chip 共用同一個 listener，撳邊個位擺喺 `tag` 度（唔使每次 bind 都 new） */
    private val clickListener = OnClickListener { v ->
        (v.tag as? Int)?.let { onPick?.invoke(it) }
    }

    /**
     * 捲親就要換窗口。`ScrollView` 本身冇 API 通知個仔，
     * 所以喺 view tree 度聽 —— 窗口冇變就即刻返轉頭，唔會拖慢個捲。
     */
    private val scrollListener = ViewTreeObserver.OnScrollChangedListener { updateWindow(false) }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnScrollChangedListener(scrollListener)
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnScrollChangedListener(scrollListener)
        super.onDetachedFromWindow()
    }

    // ---- 出面用嘅 ----------------------------------------------------------

    fun setItems(list: List<String>) {
        if (items == list) return
        items = list
        recycleAll()
        // 連 [scrap] 入面啲舊 `tag` 都要清：唔係下次啱啱好派返同一個位嗰粒 chip
        // 會以為自己冇變過（見 [updateWindow]），出返上一個 list 嘅字
        for (v in scrap) v.tag = null
        builtForWidth = -1
        requestLayout()
    }

    /**
     * 轉主題／校字體之後叫。**啲 chip 會全部拆咗重起** —— 底色係一個 chip 一個
     * `GradientDrawable`（bounds 唔可以共用），一係就要逐個 view 記住佢係邊個版本，
     * 倒不如轉樣呢一下（好耐先一次）重新派過，`bind` 就淨係要改個 text。
     */
    fun applyStyle(textSp: Float, chip: CandChip, textColor: Int, faceColor: Int) {
        // 冇變就唔好拆 —— call 嗰邊每次砌 chip 都會順手叫一次
        if (this.textSp == textSp && this.chip === chip &&
            this.textColor == textColor && this.faceColor == faceColor) return
        this.textSp = textSp
        this.chip = chip
        this.textColor = textColor
        this.faceColor = faceColor
        probe.textSize =
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, textSp, resources.displayMetrics)
        removeAllViewsInLayout()
        active.clear()
        scrap.clear()
        winFrom = 0
        winTo = 0
        builtForWidth = -1
        requestLayout()
    }

    // ---- 排版 --------------------------------------------------------------

    /**
     * 摺行：逐個字度闊，擺唔落就落下一行。O(n) 而且**冇 view**，
     * 所以 900 幾個字都係幾百微秒嘅事。
     */
    private fun buildRows(width: Int) {
        if (builtForWidth == width) return
        builtForWidth = width

        val n = items.size
        if (widths.size < n) {
            widths = IntArray(n)
            xs = IntArray(n)
            rows = IntArray(n)
        }
        rowStart.clear()
        contentH = 0
        rowCount = 0
        if (n == 0) return

        val avail = (width - paddingLeft - paddingRight).coerceAtLeast(minW)
        var x = 0
        var row = 0
        var placed = false
        rowStart.add(0)
        for (i in 0 until n) {
            val w = items[i]
            // 空格位（資料入面嘅 `*` 已經俾人濾走，但一頁排唔滿都會有）唔起 chip
            if (w.isEmpty()) {
                widths[i] = 0
                xs[i] = paddingLeft
                rows[i] = row
                continue
            }
            val cw = chipWidth(w).coerceAtMost(avail)
            if (x > 0 && x + cw > avail) {
                row++
                rowStart.add(i)
                x = 0
            }
            widths[i] = cw
            xs[i] = paddingLeft + x
            rows[i] = row
            x += cw + hGap
            placed = true
        }
        rowStart.add(n)
        if (!placed) return          // 成 list 都係吉嘅：當冇嘢，唔好留一行吉位
        rowCount = row + 1
        contentH = paddingTop + rowCount * chip.chipH + (rowCount - 1) * vGap + paddingBottom
    }

    /**
     * 闊 1px 位鬆容：`TextView` 自己排版同 [probe] 度出嚟可能爭少少，
     * 度窄咗就會切爛隻字（尤其係 emoji），度闊少少肉眼睇唔出。
     */
    private fun chipWidth(text: String): Int =
        max(minW, ceil(probe.measureText(text)).toInt() + padH * 2 + 1)

    private fun rowTop(row: Int) = paddingTop + row * (chip.chipH + vGap)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        buildRows(width)
        setMeasuredDimension(width, contentH)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        // 排完版一定要重新派一次（就算窗口冇變，啲 view 個位可能已經郁咗）
        updateWindow(force = true)
    }

    // ---- 窗口：見得到嗰幾行先起 view ----------------------------------------

    /**
     * 睇下而家見緊邊幾行，跟住**淨係**派 view 俾嗰個範圍（上下各多一行 buffer，
     * 捲快啲都唔會見到吉位）。出咗範圍嗰啲收返入 [scrap] 等下一個位重用。
     */
    private fun updateWindow(force: Boolean) {
        if (rowCount == 0 || items.isEmpty()) {
            if (active.isNotEmpty()) recycleAll()
            return
        }
        val rowH = chip.chipH + vGap
        val vis = visRect
        val top: Int
        val bottom: Int
        if (getLocalVisibleRect(vis) && vis.bottom > vis.top) {
            top = vis.top
            bottom = vis.bottom
        } else {
            // 未排好版（或者暫時完全遮住咗）—— 判斷唔到就當見到成屏咁高，
            // 唔可以當「乜都見唔到」就唔起，第一下拉大會吉住
            top = 0
            bottom = (parent as? View)?.height?.takeIf { it > 0 }
                ?: resources.displayMetrics.heightPixels
        }

        val firstRow = ((top - paddingTop) / rowH - 1).coerceIn(0, rowCount - 1)
        val lastRow = ((bottom - paddingTop) / rowH + 1).coerceIn(0, rowCount - 1)
        val from = rowStart[firstRow]
        val to = rowStart[lastRow + 1]
        if (!force && from == winFrom && to == winTo) return
        winFrom = from
        winTo = to

        val iter = active.entries.iterator()
        while (iter.hasNext()) {
            val e = iter.next()
            if (e.key < from || e.key >= to) {
                e.value.visibility = GONE
                scrap.add(e.value)
                iter.remove()
            }
        }
        for (i in from until to) {
            if (widths[i] == 0) continue
            val v = active[i] ?: obtain().also { active[i] = it }
            if (v.tag != i) {
                v.tag = i
                v.text = items[i]
            }
            v.visibility = VISIBLE
            place(v, i)
        }
        invalidate()
    }

    private fun obtain(): TextView =
        scrap.removeLastOrNull() ?: TextView(context).also {
            styleChip(it, textSp, chip, textColor, faceColor)
            it.setOnClickListener(clickListener)
            // 排緊版／捲緊嗰陣加仔，唔可以用 `addView`（會 requestLayout，
            // 排版途中加就會撞）—— `addViewInLayout` 就係做呢件事嘅
            addViewInLayout(it, -1, generateDefaultLayoutParams(), true)
        }

    /** 個位係計出嚟嘅，所以直接 `EXACTLY` measure 完就擺低，唔使等下一個 layout pass */
    private fun place(v: TextView, i: Int) {
        val w = widths[i]
        val h = chip.chipH
        val x = xs[i]
        val y = rowTop(rows[i])
        v.measure(
            MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY),
        )
        v.layout(x, y, x + w, y + h)
    }

    private fun recycleAll() {
        for (v in active.values) {
            v.visibility = GONE
            v.tag = null
            scrap.add(v)
        }
        active.clear()
        winFrom = 0
        winTo = 0
    }

    companion object {
        /**
         * 一粒候選字 chip 個樣。條 bar 收埋嗰行（[OptionBarsView] 個 `strip`）同
         * 呢度攤開嗰版一模一樣，所以擺喺呢度寫一次兩邊共用。
         */
        fun styleChip(v: TextView, textSp: Float, chip: CandChip, textColor: Int, faceColor: Int) {
            v.textSize = textSp
            v.gravity = Gravity.CENTER
            // 熄咗 `includeFontPadding`，再用 [CandChip] 計出嚟嗰個**唔對稱** padding
            // —— 兩樣都要，個字先至真係睇落上下置中（點解見 [CandChip]）
            v.includeFontPadding = false
            v.setTextColor(textColor)
            v.background = GradientDrawable().apply {
                setColor(faceColor)
                cornerRadius = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP, 6f, v.resources.displayMetrics)
                setStroke(1, Color.argb(30, 128, 128, 128))
            }
            val padH = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 10f, v.resources.displayMetrics).roundToInt()
            v.setPadding(padH, chip.padTop, padH, chip.padBottom)
            v.minWidth = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 38f, v.resources.displayMetrics).roundToInt()
            v.visibility = VISIBLE
        }
    }
}
