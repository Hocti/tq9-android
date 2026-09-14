package tt.ime.riverine.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.RectF
import tt.ime.riverine.core.EmojiDict
import tt.ime.riverine.core.KeyLayout
import tt.ime.riverine.core.PadFunc
import tt.ime.riverine.core.PagerLayout
import tt.ime.riverine.core.Prefs
import tt.ime.riverine.core.TTEngine
import tt.ime.riverine.swipe.GestureKeyTracker
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 三三中文輸入本體。
 *
 * 5 欄 × 4 行。中間三欄（`1`~`9`、兩格闊嗰粒 `0`、「取消」）寫死，
 * **左欄同右欄嗰八個位由 user 自己排**（設定頁「按鍵排位」，見 [KeyLayout]）：
 *
 * ```
 *   [左 0]  7 8 9    [右 0]
 *   [左 1]  4 5 6    [右 1]
 *   [左 2]  1 2 3    [右 2]
 *   [左 3]  0 0 取消  [右 3]
 * ```
 *
 * 預設排位（[KeyLayout.DEFAULT]）就係 2026-09-09 之前寫死嗰個：
 * 左欄由上而下「關聯字／同音／?123／Eng」，右欄「⇄／␣／⌫／⏎」。
 * `␣` 同 `⌫` 喺 2026-08-27 對調咗（user 要求）—— 順帶令中文都跟返
 * 英文／符號／純數字嗰條「`⏎` 上面嗰粒一定係 `⌫`」嘅規矩，
 * 而家 user 自己拖散咗就當然唔關事。
 *
 * 選字夠兩頁嗰陣底行兩格闊嗰粒 `0` 點變，由設定頁嗰個 [PagerLayout] 話事
 * （以前係撳住「下頁」向左掃先返到上一頁，太難撳，收咗）：拆做「下頁」＋「上頁」
 * 兩粒正常闊（左右次序兩個選擇），或者唔拆、成兩格闊嗰粒做「下頁」，
 * 「上頁」就擺喺長撳（[PagerLayout.WIDE_NEXT]）或者左下角
 * （[PagerLayout.WIDE_NEXT_LEFT_PREV]，嗰陣左欄最底嗰粒暫時讓位）。
 *
 * 「左上角細字 = 長撳做乜」呢條規矩喺八個位全部照用。長撳嗰個功能冇字好寫
 * （轉輸入法嗰兩粒）就改為喺左上角畫個圖案，見 [drawFunction]。
 */
class ChinesePadView(context: Context, private val engine: TTEngine) : KeyboardBaseView(context) {

    interface ChineseHost {
        fun pressDigit(digit: Int)
        /** 上面條 bar 而家係咪開住（⚙ 要唔要著燈） */
        val optionOn: Boolean
        /** AI 鍵要而家真係揀咗一段字先撳得 */
        val aiReady: Boolean
        /** 複製鍵要個欄有字（揀咗字或者成個欄有嘢）先撳得，同 [aiReady] 一樣做法 */
        val copyReady: Boolean
    }

    var chineseHost: ChineseHost? = null

    init { tracker.holdRepeatMs = Prefs.longPressMs(context) }

    private val digitBoxes = arrayOfNulls<KeyBox>(10)
    private var metrics: PadMetrics? = null
    private val dstRect = RectF()

    /**
     * 底行而家係咪「下頁／上頁」兩粒（唔係就係兩格闊嗰粒 `0`）。
     * 排完版記住實際排咗邊個樣，engine 狀態變咗先知使唔使重排（見 [onEngineState]）。
     */
    private var splitPager = false

    /**
     * 左下角而家係咪暫時借咗俾「上頁」（[PagerLayout.WIDE_NEXT_LEFT_PREV]）。
     * 同 [splitPager] 一樣，記住實際排咗邊個樣，好等 [onEngineState] 知使唔使重排。
     */
    private var leftPrevPager = false

    /** 而家係咪「選字、夠兩頁」——變樣嗰幾個 [PagerLayout] 都係喺呢個狀態先郁 */
    private fun paging() = engine.selectMode && engine.totalPage > 1

    /**
     * 拆兩粒嗰兩個選擇先至要重排；[PagerLayout.WIDE_NEXT] 同
     * [PagerLayout.NO_CHANGE] 排位由頭到尾唔郁。
     */
    private fun wantSplitPager() = paging() && Prefs.pagerLayout(context).let {
        it == PagerLayout.PREV_NEXT || it == PagerLayout.NEXT_PREV
    }

    /**
     * 大格「下頁」模式：兩格闊嗰粒 `0` 而家係「下頁」，長撳 = 上頁。
     * 呢個狀態下長撳嘅「成對標點」（`「」`）冇咗 —— 左上角個位讓咗俾「上頁」，
     * 右上角寫頁數（見 [drawDigit]、`TTInputMethodService.onLongPress`）。
     */
    fun wideNextPage() = paging() && Prefs.pagerLayout(context) == PagerLayout.WIDE_NEXT

    /**
     * 左下角「上頁」模式：兩格闊嗰粒 `0` 一樣係「下頁」，但「上頁」有返粒自己嘅鍵——
     * 揭緊頁嗰陣借咗左欄最底嗰個位（見 [PagerLayout.WIDE_NEXT_LEFT_PREV]）。
     * 粒 `0` 本身冇變樣：長撳照舊係成對標點，頁數照舊喺左上角。
     */
    private fun wantLeftPrevPager() =
        paging() && Prefs.pagerLayout(context) == PagerLayout.WIDE_NEXT_LEFT_PREV

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

        // 左欄：由設定頁嗰個排位砌（見 [KeyLayout]）。以前八個位寫死喺呢度，
        // 而家淨係 [KeyLayout.DEFAULT] 仲留住嗰個排法
        val layout = KeyLayout.load(context)
        // 左欄最底（左下角）揭緊頁嗰陣可以暫時讓咗俾「上頁」（見 [leftPrevPager]）
        leftPrevPager = wantLeftPrevPager()
        val lastLeftRow = layout.left.size - 1
        for ((row, slot) in layout.left.withIndex()) {
            val key = if (leftPrevPager && row == lastLeftRow)
                Key(KeyAction.PREV_PAGE, label = "上頁") else slotKey(slot)
            add(key, 0f, row)
        }

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

        // 右欄，同左欄一樣跟排位砌
        for ((row, slot) in layout.right.withIndex()) add(slotKey(slot), 4f, row)
    }

    /**
     * 一個位（短撳＋長撳）→ 一粒 [Key]。
     *
     * `␣`／`⌫`／`⏎` 嗰類（[PadFunc.tapOnly]）冇長撳，`KeyLayout.Slot.effectiveLong`
     * 已經幫手隔咗，呢度唔使再理。
     */
    private fun slotKey(slot: KeyLayout.Slot): Key = slot.tap.toKey(slot.effectiveLong)

    override fun keyEnabled(k: Key): Boolean =
        k.enabled &&
            (k.action != KeyAction.AI || chineseHost?.aiReady == true) &&
            (k.action != KeyAction.COPY || chineseHost?.copyReady == true)

    /**
     * engine 狀態變咗要重畫。入／出「夠兩頁嘅選字模式」嗰陣底行會由
     * 兩格闊嘅 `0` 變成「下頁」＋「上頁」兩粒，所以仲要重排一次。
     */
    fun onEngineState() {
        if (splitPager != wantSplitPager() || leftPrevPager != wantLeftPrevPager())
            relayout() else invalidate()
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
     * （每個 [PagerLayout] 都一樣，大格「下頁」嗰啲一樣係選字模式）。
     */
    override fun canSwipe(key: Key) =
        key.action == KeyAction.DIGIT && Prefs.swipeEnabled(context) && !engine.selectMode

    /**
     * **撳落即出碼**（唔等放手，冇得熄）。淨係 `1`~`9`，而且淨係喺「長撳 = 連撳」
     * 嗰個狀態先做得 —— 嗰陣粒鍵之後唯一會發生嘅事就係再出多次佢自己，
     * 撳落一下 + 長撳一下 = 連撳兩下，同以前一模一樣。
     *
     * 兩種情況照舊等放手，因為粒鍵長撳有第二個意思，一撳落就出咗碼會兩樣一齊做：
     *  - **選字模式**：長撳一格 = 開嗰個字嘅同音字表（`TTEngine.homoAt`）
     *  - **未打過碼 + 開咗 [Prefs.longPressShortcut]**：長撳 = 開速選字表
     *
     * `0` 亦都唔做（長撳 = 開關標點／上頁）。
     */
    override fun instantKey(k: Key): Boolean =
        k.action == KeyAction.DIGIT && k.digit in 1..9 &&
            !engine.selectMode &&
            !(Prefs.longPressShortcut(context) && engine.currCode.isEmpty())

    /**
     * 成對標點表（長撳 `0` 出嗰個，見 [tt.ime.riverine.core.TTCmd.OPENCLOSE]）
     * 長撳其中一格 → 彈「左、右」兩隻出嚟，**淨係打一隻**：
     * 打 `「` 或者 `」` 之類單邊標點唔使去符號頁揀。
     *
     * 次序跟返成對本身（左嗰隻排頭），所以長撳完唔郁直接放手 = 出左邊嗰隻
     * （同英文鍵盤嘅變體 popup 一樣，見 [KeyboardBaseView.openVariantPopup]）。
     * 其餘字表長撳一格照舊係開同音字表（`TTEngine.homoAt`）。
     * 左右欄嗰粒「表情」就借同一套彈速選 emoji（見 [QuickEmoji]）——
     * 粒掣本身個長撳吉住嗰陣先至有。
     */
    override fun variantsOf(k: Key): List<String> = when {
        k.action == KeyAction.DIGIT -> engine.pairSidesAt(k.digit)
        QuickEmoji.appliesTo(k) -> QuickEmoji.items(context)
        else -> k.variants
    }

    /** 揀咗邊一邊就交返俾 engine 出字兼收表（唔好行 `typeChar`，嗰條路唔會清選字狀態） */
    override fun commitVariant(key: Key, variant: String): Boolean {
        // 速選咗個 emoji：記返落「最近用過」（同喺 emoji 表揀嗰下一樣），
        // 出字就照行返平時嗰條路（回 false = base 出返粒 [KeyAction.CHAR]）
        if (key.action == KeyAction.TO_EMOJI) {
            EmojiDict.addRecent(context, variant)
            return false
        }
        if (key.action != KeyAction.DIGIT) return false
        val side = engine.pairSidesAt(key.digit).indexOf(variant)
        return side >= 0 && engine.pickPairSide(key.digit, side)
    }

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
            val side = min(box.w, box.h) * 0.74f
            dstRect.set(box.cx - side / 2f, box.cy - side / 2f, box.cx + side / 2f, box.cy + side / 2f)
            StrokeImages.draw(canvas, context, img, dstRect, pk.dim)
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
            on -> theme.keyAccent
            k.accent -> theme.keyAccent
            else -> theme.keyFaceAlt
        }
        drawFace(canvas, box, pressedFaceColor(color, isDown && usable))
        // `on` 淨係同音／簡體／工具 bar 三粒先會 true，嗰陣粒鍵係 accent 色底
        val hintColor = if (on) theme.onAccentText else theme.textDim
        // 有專屬圖案（複製／剪下／全選／復原／重做／表情／錄音／轉輸入法嗰兩粒）
        // 就一律畫圖案代替文字，唔畫圖案先寫返 face 嗰幾隻字 —— 同工具列
        // （`PadFuncKeys.toolIcon`）睇齊，唔好一個功能兩個地方兩個樣
        val centerIcon = longIconOf(k.action)
        if (centerIcon != null) {
            drawCenterIcon(canvas, box, centerIcon,
                if (usable) theme.text else theme.textDim, scale = funcFontScale)
        } else {
            // 功能鍵成粒都行 [funcFontScale]：設定頁條字體 slider 係為咗睇清楚啲**字**
            // （下面啲關聯字）而拉，「同音」「取消」「Eng」呢啲跟住一齊大就逼爆粒鍵
            drawLabel(
                canvas, box, labelOf(k),
                sizeRatio = if (k.action == KeyAction.CANCEL) 0.36f else 0.40f,
                color = if (usable) theme.text else theme.textDim,
                scale = funcFontScale
            )
        }
        // 左上角跟返成個 app 嘅規矩：**一律寫「長撳做乜」**。長撳嗰個動作有專屬
        // 圖案就畫圖案，冇就照舊寫返長撳個 face（一個字都冇就乜都唔畫）
        val hintIcon = longIconOf(k.longAction)
        if (hintIcon != null) drawCornerIcon(canvas, box, hintIcon, hintColor, funcFontScale)
        else if (k.hint.isNotEmpty()) drawCornerHint(canvas, box, k.hint, hintColor, funcFontScale)
        if (k.action == KeyAction.HOMO) {
            // 同音鍵嘅即時提示喺**左下角**（左上角個位讓咗俾長撳）。三樣嘢輪住用
            // 呢個位，排住嘅次序就係優先次序：
            //  1. 攤開緊同音字表：寫住而家搵緊邊隻字嘅同音（成頁都係同音字，
            //     冇個字擺喺度就唔知搵緊邊隻）
            //  2. 打緊嘅碼（`1` → `12` → `123`，[Prefs.showCurrCode] 熄得）——
            //     打夠三碼入咗選字模式都仲寫住，因為第三個碼一撳落就入選字，
            //     唔留住就永遠見唔到自己撳咗嘅第三個碼（見 `TTEngine.startSelectWord`
            //     嘅 `keepCode`）
            //  3. 揀完之後：嗰個字正路點打嘅字碼
            // 頭兩個實際上唔會撞（同音字表嗰條路唔留碼），2 同 3 就會 ——
            // 「打完同音字，跟住打緊下一個字」嗰下打緊嗰個碼行先，即時狀態緊要過
            // 返轉頭提你上一個字點打
            val code = if (Prefs.showCurrCode(context)) engine.currCode else ""
            val tip = engine.homoWord.ifEmpty { code.ifEmpty { engine.homoCodeHint } }
            if (tip.isNotEmpty()) drawCornerHintBottom(canvas, box, tip, hintColor, funcFontScale)
        }
    }
}
