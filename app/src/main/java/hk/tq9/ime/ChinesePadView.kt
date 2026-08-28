package hk.tq9.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import hk.tq9.core.EngLongPress
import hk.tq9.core.PadFunc
import hk.tq9.core.PagerLayout
import hk.tq9.core.Prefs
import hk.tq9.core.Q9Engine
import hk.tq9.swipe.GestureKeyTracker
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 九万中文輸入本體。
 *
 * 5 欄 × 4 行：
 *   [速選/簡]  1 2 3  [☰/⇄]
 *   [同音]     4 5 6  [␣]
 *   [?123]     7 8 9  [⌫]
 *   [Eng]      0 0 取消 [⏎]
 *
 * `␣` 同 `⌫` 喺 2026-08-27 對調咗（user 要求）—— 順帶令中文都跟返
 * 英文／符號／純數字嗰條「`⏎` 上面嗰粒一定係 `⌫`」嘅規矩。
 *
 * 選字夠兩頁嗰陣底行兩格闊嗰粒 `0` 點變，由設定頁嗰個 [PagerLayout] 話事
 * （以前係撳住「下頁」向左掃先返到上一頁，太難撳，收咗）：拆做「下頁」＋「上頁」
 * 兩粒正常闊（左右次序兩個選擇），或者唔拆、成兩格闊嗰粒做「下頁」＋長撳做「上頁」。
 *
 * 🎤 搬咗上面條工具 bar（貼上隔籬），左上角嗰粒都揀得。
 * 🌐（轉輸入法）收埋咗，長撳「Eng」照樣叫得出嚟（見 [KeyAction.IME_SWITCH]）。
 */
class ChinesePadView(context: Context, private val engine: Q9Engine) : KeyboardBaseView(context) {

    interface ChineseHost {
        fun pressDigit(digit: Int)
        /** 上面條 bar 而家係咪開住（⚙ 要唔要著燈） */
        val optionOn: Boolean
        /** AI 鍵要而家真係揀咗一段字先撳得 */
        val aiReady: Boolean
    }

    var chineseHost: ChineseHost? = null

    init { tracker.holdRepeatMs = Prefs.longPressMs(context) }

    private val digitBoxes = arrayOfNulls<KeyBox>(10)
    private var metrics: PadMetrics? = null
    private val imgRect = Rect()
    private val dstRect = RectF()

    /**
     * 底行而家係咪「下頁／上頁」兩粒（唔係就係兩格闊嗰粒 `0`）。
     * 排完版記住實際排咗邊個樣，engine 狀態變咗先知使唔使重排（見 [onEngineState]）。
     */
    private var splitPager = false

    /** 而家係咪「選字、夠兩頁」——三個 [PagerLayout] 都係喺呢個狀態先變樣 */
    private fun paging() = engine.selectMode && engine.totalPage > 1

    /** 拆兩粒嗰兩個選擇先至要重排；[PagerLayout.WIDE_NEXT] 排位由頭到尾唔郁 */
    private fun wantSplitPager() =
        paging() && Prefs.pagerLayout(context) != PagerLayout.WIDE_NEXT

    /**
     * 大格「下頁」模式：兩格闊嗰粒 `0` 而家係「下頁」，長撳 = 上頁。
     * 呢個狀態下長撳嘅「成對標點」（`「」`）冇咗 —— 左上角個位讓咗俾「上頁」，
     * 右上角寫頁數（見 [drawDigit]、`TQ9InputMethodService.onLongPress`）。
     */
    fun wideNextPage() = paging() && Prefs.pagerLayout(context) == PagerLayout.WIDE_NEXT

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val m = PadMetrics(context, w)
        metrics = m
        setMeasuredDimension(w, m.totalHeight.roundToInt())
    }

    override fun buildLayout(w: Int, h: Int) {
        // 一定要重新計：改咗顯示方式之後高度可能一樣，onSizeChanged 唔會再叫
        val m = PadMetrics(context, w)
        metrics = m
        val cw = m.cellW
        val ch = m.cellH
        val ox = m.offsetX
        digitBoxes.fill(null)

        fun add(key: Key, col: Float, row: Int, colSpan: Float = 1f): KeyBox {
            val b = KeyBox(key)
            b.set(ox + col * cw, row * ch, ox + (col + colSpan) * cw, (row + 1) * ch)
            boxes.add(b)
            if (key.action == KeyAction.DIGIT) digitBoxes[key.digit] = b
            return b
        }

        // 左欄（最上嗰粒短撳／長撳做乜，設定頁揀得）
        add(topLeftKey(), 0f, 0)
        // 左上角嗰段細字係 engine 出嘅字碼提示，喺 drawFunction 度即時攞
        add(Key(KeyAction.HOMO, label = "同音"), 0f, 1)
        // 長撳 ?123 唔使經符號頁，直接跳去純數字 keypad（英文鍵盤嗰粒一樣）
        add(Key(KeyAction.TO_SYMBOL, label = "?123", longAction = KeyAction.TO_NUMBER), 0f, 2)
        // 🌐 收埋咗，換輸入法就淨係靠長撳「Eng」。跳去下一個定係彈個選單出嚟，
        // 由設定頁話事（見 [EngLongPress]）——「地球」個 code 一路都冇刪
        add(Key(KeyAction.TO_LATIN, label = "Eng", longAction = engLongAction()), 0f, 3)

        // 九宮格 1~9：跟足 numpad 排法，7 8 9 喺最上面
        for (i in 1..9) {
            val col = ((i - 1) % 3) + 1
            val row = 2 - (i - 1) / 3
            add(Key(KeyAction.DIGIT, digit = i, swipeable = true, holdRepeat = true), col.toFloat(), row)
        }
        // 0（兩格闊）＋ 取消。0 長撳係開關標點，所以冇「長撳當連撳」。
        // 選字夠兩頁嗰陣兩格闊嗰粒 0 就拆做「下頁」＋「上頁」兩粒正常闊（見 [splitPager]）
        splitPager = wantSplitPager()
        if (splitPager) {
            val next = Key(KeyAction.DIGIT, digit = 0, hint = "「」")
            val prev = Key(KeyAction.PREV_PAGE, label = "上頁")
            // 「上頁」擺前定擺後由設定話事。粒 `0`（＝「下頁」）跟住郁位，
            // 因為呢個狀態下佢已經唔再係數字鍵，冇「撳開嗰個位」可言
            if (Prefs.pagerLayout(context) == PagerLayout.PREV_NEXT) {
                add(prev, 1f, 3); add(next, 2f, 3)
            } else {
                add(next, 1f, 3); add(prev, 2f, 3)
            }
        } else {
            add(Key(KeyAction.DIGIT, digit = 0, swipeable = true, hint = "「」"), 1f, 3, 2f)
        }
        add(Key(KeyAction.CANCEL, label = "取消"), 3f, 3)

        // 右欄。☰ 代表「揭開上面條 bar」，⚙ 太似「設定」，人哋撳落去唔知做乜
        add(optionKey(), 4f, 0)
        // ␣ 同 ⌫ 對調咗：⌫ 落咗去 ⏎ 上面（同英文／符號／純數字一致）
        add(Key(KeyAction.SPACE, label = "␣"), 4f, 1)
        add(Key(KeyAction.BACKSPACE, label = "⌫", repeatable = true), 4f, 2)
        add(Key(KeyAction.ENTER, label = "⏎", accent = true), 4f, 3)
    }

    /** 長撳 `Eng`：跳去下一個輸入法，定係彈個系統選單出嚟（設定頁揀） */
    private fun engLongAction(): KeyAction = when (Prefs.engLongPress(context)) {
        EngLongPress.NEXT_IME -> KeyAction.IME_SWITCH
        EngLongPress.PICKER -> KeyAction.IME_PICKER
    }

    /**
     * 右上角嗰粒。平時係 `☰`＝開／關成條 bar；**條 bar 常駐**
     * （[Prefs.barPinned]）就冇嘢好開關，改咗做 `⇄`＝候選字 ⇄ 工具，
     * 而條 bar 自己最左嗰粒 `⇄` 就收埋（見 `OptionBarsView.setSwitchVisible`）。
     */
    private fun optionKey(): Key =
        Key(KeyAction.OPTION, label = if (Prefs.barPinned(context)) "⇄" else "☰")

    /**
     * 左上角嗰粒。預設短撳 = 速選字、長撳 = 簡體開關，
     * 兩樣都可以喺設定頁換做 emoji／貼上／AI／無效。
     */
    private fun topLeftKey(): Key {
        val tap = Prefs.topLeftTap(context)
        val long = Prefs.topLeftLong(context)
        return Key(
            action = actionOf(tap),
            label = tap.icon,
            hint = long.icon,
            longAction = actionOf(long),
            enabled = tap != PadFunc.NONE
        )
    }

    private fun actionOf(f: PadFunc): KeyAction = when (f) {
        PadFunc.SHORTCUT -> KeyAction.SHORTCUT
        PadFunc.SC_TOGGLE -> KeyAction.SC_TOGGLE
        PadFunc.EMOJI -> KeyAction.TO_EMOJI
        PadFunc.PASTE -> KeyAction.PASTE
        PadFunc.STT -> KeyAction.STT
        PadFunc.AI -> KeyAction.AI
        PadFunc.NONE -> KeyAction.NOOP
    }

    override fun keyEnabled(k: Key): Boolean =
        k.enabled && (k.action != KeyAction.AI || chineseHost?.aiReady == true)

    /**
     * engine 狀態變咗要重畫。入／出「夠兩頁嘅選字模式」嗰陣底行會由
     * 兩格闊嘅 `0` 變成「下頁」＋「上頁」兩粒，所以仲要重排一次。
     */
    fun onEngineState() {
        if (splitPager != wantSplitPager()) relayout() else invalidate()
    }

    fun onSettingsChanged() {
        refreshSwipeSettings()
        // 滑到最後一格停一停再放手 = 撳咗兩下（8 拉去 1 停一停 = 811）
        tracker.holdRepeatMs = Prefs.longPressMs(context)
        requestLayout()
        relayout()
    }

    // ---- 滑動 -------------------------------------------------------------

    /**
     * **淨係打碼階段先至滑得**。入咗選字模式啲數字鍵已經唔再係碼，
     * 而係「揀第幾個字」同埋 `0` = 揭下一頁，滑過去等於亂咁揀字揭頁
     * （三個 [PagerLayout] 都一樣，大格「下頁」嗰個一樣係選字模式）。
     */
    override fun canSwipe(key: Key) =
        key.action == KeyAction.DIGIT && Prefs.swipeEnabled(context) && !engine.selectMode

    /**
     * **撳落即出碼**（唔等放手，冇得熄）。淨係 `1`~`9`，而且淨係喺「長撳 = 連撳」
     * 嗰個狀態先做得 —— 嗰陣粒鍵之後唯一會發生嘅事就係再出多次佢自己，
     * 撳落一下 + 長撳一下 = 連撳兩下，同以前一模一樣。
     *
     * 兩種情況照舊等放手，因為粒鍵長撳有第二個意思，一撳落就出咗碼會兩樣一齊做：
     *  - **選字模式**：長撳一格 = 開嗰個字嘅同音字表（`Q9Engine.homoAt`）
     *  - **未打過碼 + 開咗 [Prefs.longPressShortcut]**：長撳 = 開速選字表
     *
     * `0` 亦都唔做（長撳 = 開關標點／上頁）。
     */
    override fun instantKey(k: Key): Boolean =
        k.action == KeyAction.DIGIT && k.digit in 1..9 &&
            !engine.selectMode &&
            !(Prefs.longPressShortcut(context) && engine.currCode.isEmpty())

    override fun swipeKeyAt(x: Float, y: Float): Int {
        for (d in 0..9) {
            val b = digitBoxes[d] ?: continue
            if (b.contains(x, y)) return d
        }
        return GestureKeyTracker.NO_KEY
    }

    override fun gesturePlausibility(index: Int): Float = engine.plausibility(index)

    /**
     * 中文係即時出鍵：滑過 7→9→3 就等於順序撳咗三下，
     * 每出一碼九宮格嘅內容就會即刻變（唔係放手先一次過計）。
     *
     * 打夠碼入咗選字模式就 [abortSwipe] —— 唔係最尾嗰下（包括
     * `GestureKeyTracker.finish()` 補嗰下）會變成揀字／揭頁。
     * 例：滑 `7→9→0`，出到 `79` 已經夠碼出字，跟落嚟嗰個 `0` 唔可以攞去揭第二頁。
     */
    override fun onGestureKey(index: Int) {
        if (engine.selectMode) { abortSwipe(); return }
        chineseHost?.pressDigit(index)
        // 撳完呢一碼啱啱夠字出候選 → 之後嗰啲鍵唔可以再當碼用
        if (engine.selectMode) abortSwipe()
    }

    // ---- 畫面 -------------------------------------------------------------

    override fun drawKey(canvas: Canvas, box: KeyBox, isDown: Boolean) {
        val k = box.key
        when (k.action) {
            KeyAction.DIGIT -> drawDigit(canvas, box, isDown)
            else -> drawFunction(canvas, box, isDown)
        }
    }

    private fun drawDigit(canvas: Canvas, box: KeyBox, isDown: Boolean) {
        val d = box.key.digit
        if (d == 0) {
            drawFace(canvas, box, faceColor(box, isDown))
            drawLabel(canvas, box, engine.key0Label, sizeRatio = 0.40f)
            if (wideNextPage()) {
                // 大格「下頁」：長撳唔再係 `「」` 而係「上頁」，所以左上角寫返「上頁」
                // （同其他鍵一樣，左上角細字＝長撳做乜），頁數讓去右上角
                drawCornerHint(canvas, box, "上頁")
                drawCornerHintRight(canvas, box, engine.pageHint)
                return
            }
            // 「下頁」嗰陣左上角寫住而家第幾頁／總共幾頁（`1/10`，由 1 起計），
            // 唔使數住撳咗幾多下。冇分頁先讓返個位俾長撳提示（`「」`）。
            drawCornerHint(canvas, box, engine.pageHint.ifEmpty { box.key.hint })
            return
        }
        val pk = engine.keys[d]
        drawFace(canvas, box, faceColor(box, isDown, pk.enabled))
        val img = pk.img
        if (img != null) {
            val bm = StrokeImages.get(context, img)
            if (bm != null) {
                val side = min(box.w, box.h) * 0.74f
                dstRect.set(box.cx - side / 2f, box.cy - side / 2f, box.cx + side / 2f, box.cy + side / 2f)
                imgRect.set(0, 0, bm.width, bm.height)
                canvas.drawBitmap(bm, imgRect, dstRect, if (pk.dim) StrokeImages.dimPaint else StrokeImages.paint)
            }
        }
        if (pk.text.isNotEmpty()) drawLabel(canvas, box, pk.text, sizeRatio = 0.46f)
        if (pk.hint.isNotEmpty()) drawCornerHint(canvas, box, pk.hint)
    }

    private fun drawFunction(canvas: Canvas, box: KeyBox, isDown: Boolean) {
        val k = box.key
        val usable = keyEnabled(k)
        // 簡體開關擺得喺短撳定長撳都好，著燈都係睇 engine.scOutput
        val scKey = k.action == KeyAction.SC_TOGGLE || k.longAction == KeyAction.SC_TOGGLE
        val on = when {
            k.action == KeyAction.HOMO -> engine.homo
            scKey -> engine.scOutput
            k.action == KeyAction.OPTION -> chineseHost?.optionOn == true
            else -> false
        }
        val color = when {
            !usable -> theme.keyDisabled
            isDown -> theme.keyFaceDown
            on -> theme.keyAccent
            k.accent -> theme.keyAccent
            else -> theme.keyFaceAlt
        }
        drawFace(canvas, box, color)
        drawLabel(
            canvas, box, labelOf(k),
            sizeRatio = if (k.action == KeyAction.CANCEL) 0.36f else 0.40f,
            color = if (usable) theme.text else theme.textDim
        )
        // 同音鍵左上角：攤開緊同音字表就寫住而家搵緊邊隻字嘅同音（成頁都係同音字，
        // 冇個字擺喺度就唔知搵緊邊隻）；揀完就換做嗰個字正路應該點打嘅字碼
        val hint = when {
            k.action != KeyAction.HOMO -> k.hint
            engine.homoWord.isNotEmpty() -> engine.homoWord
            else -> engine.homoCodeHint
        }
        if (hint.isNotEmpty()) {
            drawCornerHint(
                canvas, box, hint,
                if (on && (scKey || k.action == KeyAction.HOMO)) theme.onAccentText else theme.textDim
            )
        }
    }
}
