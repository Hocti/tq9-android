package hk.tq9.ime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import hk.tq9.core.Prefs
import hk.tq9.swipe.GestureKeyTracker
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * 所有鍵盤 view 嘅共同部份：排版、畫鍵、掂觸（撳／長撳／連撳／滑動畫線）。
 */
abstract class KeyboardBaseView(context: Context) : View(context) {

    companion object {
        /** 撳落鍵同鍵之間（或者最邊）幾多 dp 之內都當撳咗最近嗰粒 */
        private const val SNAP_DP = 14f
    }

    interface Host {
        fun onKey(key: Key)
        /** 回傳 true = 已經處理咗，唔使再當短撳 */
        fun onLongPress(key: Key): Boolean
        fun feedback(key: Key)
        /** 長撳 ␣ 之後拖手指：一格一格咁郁 caret */
        fun moveCursor(dx: Int, dy: Int)
    }

    var host: Host? = null
    var theme: Theme = Theme.of(context)

    /** ⏎ 個樣：搜尋欄要出放大鏡 */
    var enterLabel: String = "⏎"
        set(v) { if (field != v) { field = v; invalidate() } }

    protected val boxes = ArrayList<KeyBox>()
    protected val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    protected val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    protected val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    protected val gapPx get() = dp(Prefs.gapDp(context).toFloat())
    protected val fontScale get() = Prefs.fontScale(context)
    protected val radius get() = dp(6f)

    protected val tracker = GestureKeyTracker(
        Prefs.swipeDwellMs(context), Prefs.swipeAngleDeg(context)
    )

    private val handler = Handler(Looper.getMainLooper())
    private var pressed: KeyBox? = null
    private var downX = 0f
    private var downY = 0f
    private var swiping = false
    private var longFired = false
    /** 子類喺 gesture 中途叫咗停（見 [abortSwipe]）：唔再出鍵，連條線都唔畫 */
    private var swipeAborted = false
    // 長撳 ␣ 之後入咗「郁 caret」模式
    private var cursorMode = false
    private var cursorX = 0f
    private var cursorY = 0f
    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val trailPath = Path()

    /** 長撳變體、滑動 hover 兩個浮窗，兩者都出得鍵盤範圍外面（見 [KeyPopup]） */
    private val variantPopup by lazy { KeyPopup(context) }
    private val hoverPopup by lazy { KeyPopup(context) }
    /** hover 浮窗而家指住邊粒鍵：同一粒就唔好再郁個窗（每次 update 都係一次 relayout） */
    private var hoverBox: KeyBox? = null

    // 長撳變體 popup
    private var popupBox: KeyBox? = null
    private var popupItems: List<String> = emptyList()
    private var popupIndex = 0
    private var popupLeft = 0f
    private var popupTop = 0f
    private var popupItemW = 0f
    private var popupItemH = 0f
    private var popupAnchorX = 0f
    /** 手指行夠 [slop] 之後先至跟住揀，之前一律當第一個（見 [updateVariantPopup]） */
    private var popupMoved = false

    private val longPressRunnable = Runnable {
        val b = pressed ?: return@Runnable
        if (b.key.action == KeyAction.SPACE) {
            // 本來係彈輸入法揀選視窗，改咗做上下左右郁 caret
            cursorMode = true
            longFired = true
            cursorX = downX
            cursorY = downY
            host?.feedback(b.key)
            invalidate()
        } else if (b.key.variants.isNotEmpty()) {
            openVariantPopup(b)
            longFired = true
            host?.feedback(b.key)
            invalidate()
        } else if (host?.onLongPress(b.key) == true) {
            longFired = true
            host?.feedback(b.key)
            invalidate()
        } else if (b.key.holdRepeat) {
            // 長撳 = 連撳。而家出一次，放手（或者滑去下一格）嗰陣會再出多一次，
            // 所以「長撳 7 再拉去 0」= 7 7 0。唔好設 longFired，UP 嗰下要照計。
            host?.onKey(b.key)
            host?.feedback(b.key)
            invalidate()
        }
    }
    private var repeatRunnable: Runnable? = null

    init {
        isFocusable = false
        isHapticFeedbackEnabled = false
    }

    fun dp(v: Float) = v * resources.displayMetrics.density

    fun applyTheme(t: Theme) {
        theme = t
        StrokeImages.configure(t.dark)
        invalidate()
    }

    /** 有啲鍵係睇實時狀態先知撳唔撳得（例如 AI 鍵要揀咗字先） */
    protected open fun keyEnabled(k: Key): Boolean = k.enabled

    /**
     * 呢粒鍵撳耐咗有冇效果（變體 popup、[Host.onLongPress]、連撳…）。
     * 純數字頁成頁 false —— 打號碼撳耐咗少少就彈嘢出嚟好煩（見 [NumberPadView]）。
     */
    protected open fun allowLongPress(k: Key): Boolean = true

    /** ⏎ 一定要跟返而家個欄要做乜，唔可以淨係睇 [Key.label] */
    protected fun labelOf(k: Key): String =
        if (k.action == KeyAction.ENTER) enterLabel else k.label

    fun refreshSwipeSettings() {
        tracker.dwellMs = Prefs.swipeDwellMs(context)
        tracker.angleDeg = Prefs.swipeAngleDeg(context)
    }

    // ---- 排版 -------------------------------------------------------------

    /** 子類喺呢度砌 [boxes] */
    protected abstract fun buildLayout(w: Int, h: Int)

    /** 呢個鍵撳落去畫成點 */
    protected abstract fun drawKey(canvas: Canvas, box: KeyBox, isDown: Boolean)

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        boxes.clear()
        if (w > 0 && h > 0) buildLayout(w, h)
    }

    protected fun relayout() {
        boxes.clear()
        if (width > 0 && height > 0) buildLayout(width, height)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        bg.color = theme.background
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bg)
        val down = pressed
        for (b in boxes) drawKey(canvas, b, b === down && !swiping)
        drawTrail(canvas)
    }

    private fun drawTrail(canvas: Canvas) {
        if (swipeAborted) return
        if (!swiping || tracker.points.size < 4) return
        trailPath.reset()
        val p = tracker.points
        trailPath.moveTo(p[0], p[1])
        var i = 2
        while (i + 1 < p.size) { trailPath.lineTo(p[i], p[i + 1]); i += 2 }
        trailPaint.color = Color.argb(70, Color.red(theme.trail), Color.green(theme.trail), Color.blue(theme.trail))
        trailPaint.strokeWidth = dp(13f)
        canvas.drawPath(trailPath, trailPaint)
        trailPaint.color = theme.trail
        trailPaint.strokeWidth = dp(5f)
        canvas.drawPath(trailPath, trailPaint)
    }

    // ---- 長撳變體 popup ----------------------------------------------------

    /**
     * 好似一般英文鍵盤咁：長撳彈出一行變體，跟住唔好放手，左右拉去揀，放手先入。
     *
     * **永遠向上彈**，唔會向下 —— 向下彈就一定俾手指遮住。最頂嗰行都照樣向上彈：
     * 用 [KeyPopup]（PopupWindow）畫，出得鍵盤範圍外面，彈上 app 嗰邊，
     * 所以唔使再夾硬 `max(0f, …)` 頂住鍵盤個頂同粒鍵疊埋一舊。
     */
    private fun openVariantPopup(box: KeyBox) {
        val items = box.key.variants
        popupBox = box
        popupItems = items
        popupItemW = maxOf(box.w * 1.1f, dp(50f))
        // 格多過螢幕裝得落（`/` 有八個）就一齊迫窄，剛剛好一行鋪滿成個闊度 ——
        // 情願粒粒細啲，都好過有幾個推咗出螢幕外面永遠揀唔到。
        // 字太大 KeyPopup 自己會縮返（見 Content.onDraw 嗰句 avail / need）。
        if (popupItemW * items.size > width) popupItemW = width.toFloat() / items.size
        popupItemH = box.h
        val total = popupItemW * items.size
        popupLeft = (box.cx - popupItemW / 2f).coerceIn(0f, maxOf(0f, width - total))
        popupTop = box.top - popupItemH - dp(8f)
        popupAnchorX = downX
        popupIndex = 0
        popupMoved = false
        dismissHover()
        variantPopup.setStyle(theme, fontScale)
        // 畫用 variantDisplay（Tab → ⇥），commit 就照用返 popupItems 入面嗰個真字元
        variantPopup.showRow(this, items.map(::variantDisplay), popupIndex,
            popupLeft, popupTop, popupItemW, popupItemH)
    }

    /**
     * 揀邊個 = **手指而家喺邊個格上面**（絕對位置），唔係「行咗幾多步」——
     * 用相對步數嗰陣，貼邊嘅鍵（例如 `p`、`0`）成行變體會被迫住向左推開，
     * 睇到嘅高亮同手指喺邊完全對唔上，變成點拉都揀唔到，所以改咗做絕對位置。
     *
     * 但係「長撳完唔郁直接放手 = 打返粒鍵本身」呢個習慣要保住：手指未行夠
     * 一個 [slop] 之前一律當第一個（＝粒鍵自己），行夠先至跟手指走。
     */
    private fun updateVariantPopup(x: Float) {
        if (popupItems.isEmpty()) return
        if (!popupMoved) {
            if (abs(x - popupAnchorX) < slop) return
            popupMoved = true
        }
        popupIndex = ((x - popupLeft) / popupItemW).toInt().coerceIn(0, popupItems.size - 1)
        variantPopup.highlight(popupIndex)
    }

    private fun closeVariantPopup(commit: Boolean) {
        val items = popupItems
        val box = popupBox
        popupBox = null
        popupItems = emptyList()
        variantPopup.dismiss()
        if (commit && box != null && popupIndex in items.indices) {
            val v = items[popupIndex]
            // literal：個 list 本身已經有大細階揀，唔好再俾 shift 覆寫返
            host?.onKey(Key(KeyAction.CHAR, label = v, text = v, literal = true))
        }
    }

    // ---- 掂觸 -------------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        val t = SystemClock.uptimeMillis()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val b = boxNear(x, y) ?: return true
                if (!keyEnabled(b.key)) return true
                pressed = b
                downX = x; downY = y
                swiping = false
                longFired = false
                swipeAborted = false
                cursorMode = false
                host?.feedback(b.key)
                if (allowLongPress(b.key)) {
                    handler.postDelayed(longPressRunnable, Prefs.longPressMs(context))
                }
                if (b.key.repeatable) startRepeat(b)
                if (canSwipe(b.key)) {
                    tracker.attach(swipeDelegate)
                    tracker.minSegPx = min(b.w, b.h) * 0.3f
                    tracker.start(x, y, t)
                }
                // 一撳落去就即刻浮個大字出嚟，唔使等滑動先出（見 [hoverLabel]）；
                // 長撳出變體 popup 嗰陣 [openVariantPopup] 自己會 [dismissHover]，換返嗰個
                updateHoverPopup(x, y)
                invalidate()
            }

            MotionEvent.ACTION_MOVE -> {
                if (cursorMode) { dragCursor(x, y); return true }
                if (popupItems.isNotEmpty()) {
                    updateVariantPopup(x)
                    invalidate()
                    return true
                }
                val moved = hypot(x - downX, y - downY)
                if (!swiping && moved > slop) {
                    if (tracker.active) {
                        // 郁咗手就唔好再彈長撳嗰啲嘢出嚟，但係未行夠 [swipeStartDistPx]
                        // 都仲當普通撳（放手照出粒鍵），唔算滑動
                        cancelPending()
                        if (moved >= swipeStartDistPx(pressed)) {
                            swiping = true
                            onSwipeStart()
                        }
                    } else if (pressed?.let { canFlick(it.key) } == true) {
                        // 掃鍵（例如選字模式嘅 0）：唔好拖去隔離格，
                        // 亦都唔好等長撳彈嘢出嚟，放手嗰陣先算方向
                        cancelPending()
                    } else {
                        // 唔支援滑動嘅鍵：可以拖去隔離格
                        val b = boxNear(x, y)
                        if (b !== pressed) {
                            cancelPending()
                            pressed = b
                            downX = x; downY = y
                            if (b != null) host?.feedback(b.key)
                        }
                    }
                }
                if (tracker.active) tracker.move(x, y, t)
                // 唔止滑動先跟：手指拖去邊，大字提示都要跟到邊（[updateHoverPopup] 同格就唔郁）
                updateHoverPopup(x, y)
                if (swiping) invalidate()
            }

            MotionEvent.ACTION_UP -> {
                cancelPending()
                dismissHover()
                if (popupItems.isNotEmpty()) {
                    closeVariantPopup(commit = true)
                    pressed = null
                    invalidate()
                    return true
                }
                if (cursorMode) {
                    cursorMode = false
                    tracker.cancel()
                    pressed = null
                    invalidate()
                    return true
                }
                if (swiping) {
                    // 中途叫咗停（例如中文入咗選字模式）就乜都唔做 ——
                    // 連 finish() 補嗰下都唔可以出，唔係最尾嗰格會走咗去揀字／揭頁
                    if (swipeAborted) {
                        tracker.cancel()
                    } else {
                        tracker.move(x, y, t)
                        tracker.finish(x, y, t)
                        onSwipeEnd()
                    }
                    swiping = false
                } else {
                    tracker.cancel()
                    val b = pressed
                    if (b != null && !longFired) {
                        val dx = x - downX
                        val dy = y - downY
                        val flicked = canFlick(b.key) && abs(dx) >= flickMinPx &&
                            abs(dx) > abs(dy) && onFlick(b.key, dx)
                        if (!flicked) host?.onKey(b.key)
                    }
                }
                pressed = null
                invalidate()
            }

            MotionEvent.ACTION_CANCEL -> {
                cancelPending()
                dismissHover()
                closeVariantPopup(commit = false)
                tracker.cancel()
                swiping = false
                cursorMode = false
                pressed = null
                invalidate()
            }
        }
        return true
    }

    /**
     * 拖夠幾遠先當**真係喺度滑**（開始畫線、放手會出成個字）。
     *
     * 預設 = [slop]（中文九宮格：滑去隔離格就係下一碼，要即刻收）。英文就要成粒鍵
     * 咁遠先算，見 [LatinPadView.swipeStartDistPx] —— 單撳輕輕帶咗一下好易變咗
     * 短 swipe，而英文根本冇兩個字母喺 qwerty 上面貼住嘅詞，所以拉到隔離格咁遠
     * 就放手，一律當誤觸、照出粒鍵本身。
     */
    protected open fun swipeStartDistPx(box: KeyBox?): Float = slop

    /**
     * 手指底下嗰粒鍵會俾自己隻手遮住，所以喺上面浮返個大字出嚟話你知而家撳緊
     * （或者掃緊）邊粒 —— 一撳落去就即刻出，唔使等長撳、亦唔使等滑動
     * （同長撳個變體 popup 一樣用 [KeyPopup]，出得鍵盤範圍外面）。
     *
     * 邊粒鍵有呢個提示由 [hoverLabel] 決定（null = 唔浮）；長撳出咗變體 popup
     * 就會由 [openVariantPopup] 換走佢（見嗰度嘅 [dismissHover]）。
     */
    private fun updateHoverPopup(x: Float, y: Float) {
        if (swipeAborted) { dismissHover(); return }
        val b = boxAt(x, y)
        val label = if (b == null) null else hoverLabel(b)
        if (b == null || label.isNullOrEmpty()) { dismissHover(); return }
        if (b === hoverBox) return
        hoverBox = b
        val w = maxOf(b.w * 1.1f, dp(50f))
        val h = b.h
        hoverPopup.setStyle(theme, fontScale)
        hoverPopup.showOne(this, label, b.cx, b.top - h - dp(8f), w, h)
    }

    private fun dismissHover() {
        hoverBox = null
        hoverPopup.dismiss()
    }

    /** 撳落／滑動經過呢粒鍵嗰陣，浮窗要寫乜（null = 唔使浮）。淨係英文／數字用 */
    protected open fun hoverLabel(box: KeyBox): String? = null

    /**
     * 拖幾多先郁一格：橫向密啲（逐個字），直向疏啲（逐行），
     * 唔係就會少少郁手都跳咗幾行。
     */
    private fun dragCursor(x: Float, y: Float) {
        val stepX = dp(13f)
        val stepY = dp(22f)
        var dx = 0
        while (x - cursorX >= stepX) { dx++; cursorX += stepX }
        while (cursorX - x >= stepX) { dx--; cursorX -= stepX }
        var dy = 0
        while (y - cursorY >= stepY) { dy++; cursorY += stepY }
        while (cursorY - y >= stepY) { dy--; cursorY -= stepY }
        if (dx != 0 || dy != 0) host?.moveCursor(dx, dy)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        variantPopup.dismiss()
        dismissHover()
    }

    private fun cancelPending() {
        handler.removeCallbacks(longPressRunnable)
        repeatRunnable?.let { handler.removeCallbacks(it) }
        repeatRunnable = null
    }

    private fun startRepeat(b: KeyBox) {
        val r = object : Runnable {
            override fun run() {
                if (pressed !== b) return
                host?.onKey(b.key)
                handler.postDelayed(this, 55)
            }
        }
        repeatRunnable = r
        handler.postDelayed(r, 420)
    }

    protected fun boxAt(x: Float, y: Float): KeyBox? = boxes.firstOrNull { it.contains(x, y) }

    /**
     * 撳鍵用嘅寬鬆判定：鍵同鍵之間畫面上見到嘅隙係畫出嚟嘅（[drawFace] 縮咗 [gapPx]），
     * 實際 [KeyBox] 係貼實嘅，所以正路唔會有死位。但係取整、留白邊、
     * 最後一格夠唔夠位去到最右都可能爭少少，撳落隙度就唔可以當冇撳過 ——
     * 搵唔到就攞最近嗰粒（限 [SNAP_DP] 之內，唔好連留白區都當撳咗邊上嗰粒）。
     */
    protected fun boxNear(x: Float, y: Float): KeyBox? {
        boxAt(x, y)?.let { return it }
        val limit = dp(SNAP_DP)
        var best: KeyBox? = null
        var bestD = Float.MAX_VALUE
        for (b in boxes) {
            val dx = when { x < b.left -> b.left - x; x >= b.right -> x - b.right; else -> 0f }
            val dy = when { y < b.top -> b.top - y; y >= b.bottom -> y - b.bottom; else -> 0f }
            val d = hypot(dx, dy)
            if (d < bestD) { bestD = d; best = b }
        }
        return if (bestD <= limit) best else null
    }

    /** 呢個鍵可唔可以做滑動起點 */
    protected open fun canSwipe(key: Key): Boolean = false

    protected open fun onSwipeStart() {}
    protected open fun onSwipeEnd() {}
    protected open fun onGestureKey(index: Int) {}
    protected open fun gesturePlausibility(index: Int): Float = 0f

    /** 滑動時只會考慮部份鍵（中文 0~9、英文 a~z），子類決定 */
    protected open fun swipeKeyAt(x: Float, y: Float): Int = GestureKeyTracker.NO_KEY

    /**
     * gesture 行到一半先知道唔應該再收（例如中文打夠碼入咗選字模式）：
     * 之後嗰啲 [onGestureKey] 全部唔算，條線亦都即刻唔畫，放手嗰下亦都唔會補多下。
     */
    protected fun abortSwipe() {
        if (swipeAborted) return
        swipeAborted = true
        invalidate()
    }

    /**
     * 呢粒鍵撳住向左／右掃有冇特別意思（例如選字模式嘅 `0`：向左掃 = 上一頁）。
     * 回 true 就唔會再當佢「拖去隔離格」，亦都唔會等長撳。
     */
    protected open fun canFlick(key: Key): Boolean = false

    /** 掃咗。[dx] 負數 = 向左。回傳 true = 處理咗，唔使再當短撳 */
    protected open fun onFlick(key: Key, dx: Float): Boolean = false

    /** 掃幾遠先算數（唔好同普通手震撞） */
    private val flickMinPx: Float get() = max(slop * 2f, dp(20f))

    private val swipeDelegate = object : GestureKeyTracker.Delegate {
        override fun keyAt(x: Float, y: Float) = swipeKeyAt(x, y)
        override fun plausibility(key: Int) = gesturePlausibility(key)
        override fun onGestureKey(key: Int) {
            if (swipeAborted) return
            this@KeyboardBaseView.onGestureKey(key)
        }
    }

    // ---- 畫鍵小工具 --------------------------------------------------------

    private val rect = RectF()

    protected fun drawFace(canvas: Canvas, box: KeyBox, color: Int) {
        rect.set(box.left + gapPx, box.top + gapPx, box.right - gapPx, box.bottom - gapPx)
        bg.color = color
        bg.style = Paint.Style.FILL
        canvas.drawRoundRect(rect, radius, radius, bg)
    }

    protected fun faceColor(box: KeyBox, isDown: Boolean, enabled: Boolean = true): Int = when {
        !enabled -> theme.keyDisabled
        isDown -> theme.keyFaceDown
        box.key.accent -> theme.keyAccent
        else -> theme.keyFace
    }

    /** 置中文字，會按格闊自動縮細 */
    protected fun drawLabel(
        canvas: Canvas, box: KeyBox, s: String,
        sizeRatio: Float = 0.42f, color: Int = theme.text, bold: Boolean = false
    ) {
        if (s.isEmpty()) return
        textPaint.isFakeBoldText = bold
        textPaint.color = color
        val base = min(box.w, box.h) * sizeRatio * fontScale
        var size = base
        textPaint.textSize = size
        val avail = box.w - gapPx * 2 - dp(4f)
        val wNeeded = textPaint.measureText(s)
        if (wNeeded > avail) {
            size *= avail / wNeeded
            textPaint.textSize = size
        }
        val fm = textPaint.fontMetrics
        canvas.drawText(s, box.cx, box.cy - (fm.ascent + fm.descent) / 2f, textPaint)
    }

    protected fun drawCornerHint(canvas: Canvas, box: KeyBox, s: String, color: Int = theme.textDim) {
        if (s.isEmpty()) return
        textPaint.isFakeBoldText = false
        textPaint.color = color
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = min(box.w, box.h) * 0.2f * fontScale
        val avail = box.w - gapPx * 2 - dp(4f)
        val need = textPaint.measureText(s)
        if (need > avail) textPaint.textSize *= avail / need
        canvas.drawText(s, box.left + gapPx + dp(3f), box.top + gapPx + textPaint.textSize, textPaint)
        textPaint.textAlign = Paint.Align.CENTER
    }

    /** 右上角細字（長撳彈得出嘅符號） */
    protected fun drawCornerHintRight(canvas: Canvas, box: KeyBox, s: String, color: Int = theme.textDim) {
        if (s.isEmpty()) return
        textPaint.isFakeBoldText = false
        textPaint.color = color
        textPaint.textAlign = Paint.Align.RIGHT
        textPaint.textSize = min(box.w, box.h) * 0.2f * fontScale
        val avail = box.w - gapPx * 2 - dp(4f)
        val need = textPaint.measureText(s)
        if (need > avail) textPaint.textSize *= avail / need
        canvas.drawText(s, box.right - gapPx - dp(3f), box.top + gapPx + textPaint.textSize, textPaint)
        textPaint.textAlign = Paint.Align.CENTER
    }
}
