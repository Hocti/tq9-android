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
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

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
    // 長撳 ␣ 之後入咗「郁 caret」模式
    private var cursorMode = false
    private var cursorX = 0f
    private var cursorY = 0f
    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val trailPath = Path()

    // 長撳變體 popup
    private var popupBox: KeyBox? = null
    private var popupItems: List<String> = emptyList()
    private var popupIndex = 0
    private var popupLeft = 0f
    private var popupTop = 0f
    private var popupItemW = 0f
    private var popupItemH = 0f
    private var popupAnchorX = 0f

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
        drawVariantPopup(canvas)
    }

    private fun drawTrail(canvas: Canvas) {
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
     * 頂行嘅鍵冇位向上彈就改為向下彈。
     *
     * 揀邊個係按**手指行咗幾遠**，唔係按絕對位置 —— 變體多起上嚟成行會迫到貼邊，
     * 絕對位置嘅話喺左右兩邊嘅鍵永遠揀唔到第一個。用相對移動就一定由第一個開始，
     * 想要數字就長撳完直接放手，唔使拖。
     */
    private fun openVariantPopup(box: KeyBox) {
        val items = box.key.variants
        popupBox = box
        popupItems = items
        popupItemW = maxOf(box.w, dp(46f))
        popupItemH = box.h * 0.92f
        val total = popupItemW * items.size
        popupLeft = (box.cx - popupItemW / 2f).coerceIn(0f, maxOf(0f, width - total))
        val above = box.top - popupItemH - dp(6f)
        popupTop = if (above >= 0f) above else box.bottom + dp(6f)
        popupAnchorX = downX
        popupIndex = 0
    }

    private fun updateVariantPopup(x: Float) {
        if (popupItems.isEmpty()) return
        val steps = ((x - popupAnchorX) / popupItemW).roundToInt()
        popupIndex = steps.coerceIn(0, popupItems.size - 1)
    }

    private fun closeVariantPopup(commit: Boolean) {
        val items = popupItems
        val box = popupBox
        popupBox = null
        popupItems = emptyList()
        if (commit && box != null && popupIndex in items.indices) {
            val v = items[popupIndex]
            host?.onKey(Key(KeyAction.CHAR, label = v, text = v))
        }
    }

    private val popupRect = RectF()

    private fun drawVariantPopup(canvas: Canvas) {
        val items = popupItems
        if (items.isEmpty()) return
        val total = popupItemW * items.size
        popupRect.set(popupLeft - dp(3f), popupTop - dp(3f),
            popupLeft + total + dp(3f), popupTop + popupItemH + dp(3f))
        bg.color = theme.keyFaceAlt
        bg.style = Paint.Style.FILL
        canvas.drawRoundRect(popupRect, radius, radius, bg)
        for (i in items.indices) {
            val l = popupLeft + i * popupItemW
            popupRect.set(l + dp(2f), popupTop + dp(2f),
                l + popupItemW - dp(2f), popupTop + popupItemH - dp(2f))
            bg.color = if (i == popupIndex) theme.keyAccent else theme.keyFace
            canvas.drawRoundRect(popupRect, radius, radius, bg)
            textPaint.isFakeBoldText = i == popupIndex
            textPaint.color = if (i == popupIndex) theme.onAccentText else theme.text
            textPaint.textSize = popupItemH * 0.44f * fontScale
            val fm = textPaint.fontMetrics
            canvas.drawText(items[i], (popupRect.left + popupRect.right) / 2f,
                (popupRect.top + popupRect.bottom) / 2f - (fm.ascent + fm.descent) / 2f, textPaint)
        }
        textPaint.isFakeBoldText = false
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
                cursorMode = false
                host?.feedback(b.key)
                handler.postDelayed(longPressRunnable, Prefs.longPressMs(context))
                if (b.key.repeatable) startRepeat(b)
                if (canSwipe(b.key)) {
                    tracker.attach(swipeDelegate)
                    tracker.minSegPx = min(b.w, b.h) * 0.3f
                    tracker.start(x, y, t)
                }
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
                        swiping = true
                        cancelPending()
                        onSwipeStart()
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
                if (tracker.active) {
                    tracker.move(x, y, t)
                    if (swiping) invalidate()
                }
            }

            MotionEvent.ACTION_UP -> {
                cancelPending()
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
                    tracker.move(x, y, t)
                    tracker.finish(x, y, t)
                    onSwipeEnd()
                    swiping = false
                } else {
                    tracker.cancel()
                    val b = pressed
                    if (b != null && !longFired) host?.onKey(b.key)
                }
                pressed = null
                invalidate()
            }

            MotionEvent.ACTION_CANCEL -> {
                cancelPending()
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

    private val swipeDelegate = object : GestureKeyTracker.Delegate {
        override fun keyAt(x: Float, y: Float) = swipeKeyAt(x, y)
        override fun plausibility(key: Int) = gesturePlausibility(key)
        override fun onGestureKey(key: Int) = this@KeyboardBaseView.onGestureKey(key)
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
