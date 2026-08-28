package hk.tq9.ime

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import kotlin.math.min

/**
 * 工具 bar（同側邊欄）嗰幾粒掣嘅圖案。
 *
 * 以前係直接寫 emoji（📋 🎤 😀 ✨）落 `TextView` 度，**唔用得**：
 *
 *  - emoji 一律由系統嘅彩色 emoji 字型畫，鍵盤其餘全部單色，夾埋一齊好突兀
 *  - 每部機每個 Android 版本嘅字型都唔同樣，畫出嚟大細同顏色都唔受控
 *  - 深色主題嗰陣 [Theme.text] 套唔到落彩色 emoji 度，永遠都係嗰個彩色樣
 *
 * 所以全部改成喺度自己畫嘅**單色**圖案：一律喺一個 24×24 嘅座標度砌，
 * `draw()` 先 scale 去實際大細，任何 dp 都唔會起格。
 * 顏色由外面傳入（跟 [Theme.text]），轉主題重新砌一次就得。
 */
enum class ToolIcon {
    /** 貼上：夾紙板 */
    PASTE,

    /** 語音輸入：咪高峰 */
    MIC,

    /** 表情符號：笑臉（單色線條，唔係彩色 emoji） */
    EMOJI,

    /** AI 改寫：閃粉 */
    AI,

    /** 顯示方式「靠左」：左邊一條牆，箭嘴指住埋去 */
    ALIGN_LEFT,

    /** 顯示方式「靠右」：右邊一條牆，箭嘴指住埋去 */
    ALIGN_RIGHT,

    /** 顯示方式「拉闊」：兩邊都有牆，箭嘴向外撐開 */
    ALIGN_WIDE,

    /** 顯示方式「左右拆開」：兩邊都有牆，兩橛鍵盤各自貼實，中間裂開 */
    ALIGN_SPLIT,

    /**
     * 地球：轉輸入法。畫喺 `Eng` 粒鍵左上角（＝長撳做乜），
     * 所以特登畫得簡單 —— 一個圓、一條赤道、一個經線橢圓，
     * 縮到得十零 dp 都仲認得出，唔會糊成一嚿。
     */
    GLOBE,
}

/**
 * 一個 [ToolIcon] 嘅 [Drawable]。顏色喺 constructor 傳，唔支援 tint ——
 * 轉主題係整粒新嘅出嚟（見 `OptionBarsView.styleTool`），
 * 唔使為咗一粒掣去實作成套 `setTintList`。
 */
class ToolIconDrawable(private val icon: ToolIcon, color: Int) : Drawable() {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        this.color = color
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        this.color = color
    }
    private val path = Path()
    private val rect = RectF()

    override fun draw(canvas: Canvas) {
        val b = bounds
        val size = min(b.width(), b.height()).toFloat()
        if (size <= 0f) return
        val save = canvas.save()
        // 永遠喺 24×24 嘅座標度畫，畫之前先 scale 去粒掣實際咁大
        canvas.translate(b.left + (b.width() - size) / 2f, b.top + (b.height() - size) / 2f)
        canvas.scale(size / 24f, size / 24f)
        stroke.strokeWidth = STROKE_W
        when (icon) {
            ToolIcon.PASTE -> drawPaste(canvas)
            ToolIcon.MIC -> drawMic(canvas)
            ToolIcon.EMOJI -> drawEmoji(canvas)
            ToolIcon.AI -> drawAi(canvas)
            ToolIcon.ALIGN_LEFT -> drawAlign(canvas, left = true, both = false)
            ToolIcon.ALIGN_RIGHT -> drawAlign(canvas, left = false, both = false)
            ToolIcon.ALIGN_WIDE -> drawAlign(canvas, left = true, both = true)
            ToolIcon.ALIGN_SPLIT -> drawSplit(canvas)
            ToolIcon.GLOBE -> drawGlobe(canvas)
        }
        canvas.restoreToCount(save)
    }

    /** 夾紙板：外框 + 上面個夾（實心，蓋住外框條頂邊）+ 入面兩行字 */
    private fun drawPaste(canvas: Canvas) {
        rect.set(4.5f, 4.5f, 19.5f, 21.5f)
        canvas.drawRoundRect(rect, 2.6f, 2.6f, stroke)
        rect.set(8.5f, 2f, 15.5f, 6.6f)
        canvas.drawRoundRect(rect, 1.6f, 1.6f, fill)
        val thin = stroke.strokeWidth
        stroke.strokeWidth = thin * 0.8f
        canvas.drawLine(8.2f, 11.5f, 15.8f, 11.5f, stroke)
        canvas.drawLine(8.2f, 15.5f, 15.8f, 15.5f, stroke)
        stroke.strokeWidth = thin
    }

    /** 咪高峰：實心波棍 + 下面個 U 形托 + 支柱 + 底座 */
    private fun drawMic(canvas: Canvas) {
        rect.set(9f, 2f, 15f, 14f)
        canvas.drawRoundRect(rect, 3f, 3f, fill)
        rect.set(5.5f, 7.5f, 18.5f, 19.5f)
        path.reset()
        path.addArc(rect, 0f, 180f)
        canvas.drawPath(path, stroke)
        canvas.drawLine(12f, 19.5f, 12f, 21.8f, stroke)
        canvas.drawLine(8.6f, 21.8f, 15.4f, 21.8f, stroke)
    }

    /** 笑臉：圓框 + 兩點眼 + 一條嘴 */
    private fun drawEmoji(canvas: Canvas) {
        canvas.drawCircle(12f, 12f, 9f, stroke)
        canvas.drawCircle(8.8f, 9.8f, 1.25f, fill)
        canvas.drawCircle(15.2f, 9.8f, 1.25f, fill)
        rect.set(6.5f, 7.5f, 17.5f, 17.5f)
        path.reset()
        path.addArc(rect, 25f, 130f)
        canvas.drawPath(path, stroke)
    }

    /** 閃粉：一大一細嘅四角星 */
    private fun drawAi(canvas: Canvas) {
        path.reset()
        sparkle(path, 9.6f, 9.8f, 7.6f)
        sparkle(path, 18.2f, 18.2f, 4.4f)
        canvas.drawPath(path, fill)
    }

    /**
     * 四角星：四個尖角之間用 cubic 拉返入去，出返個閃粉嘅腰身。
     * [k] 越細個腰越窄（＝啲角越尖）。
     */
    private fun sparkle(p: Path, cx: Float, cy: Float, r: Float) {
        val k = r * 0.26f
        p.moveTo(cx, cy - r)
        p.cubicTo(cx, cy - k, cx + k, cy, cx + r, cy)
        p.cubicTo(cx + k, cy, cx, cy + k, cx, cy + r)
        p.cubicTo(cx, cy + k, cx - k, cy, cx - r, cy)
        p.cubicTo(cx - k, cy, cx, cy - k, cx, cy - r)
        p.close()
    }

    /**
     * 顯示方式：**一條牆 + 一支箭嘴指住佢**，唔係一支淨嘅左／右箭咀
     * （淨箭咀睇落似「移動」，但呢粒掣係「貼實邊」）。
     *
     * [both] = 兩邊都有牆、箭嘴向外撐（＝「拉闊」用盡成行）。
     */
    private fun drawAlign(canvas: Canvas, left: Boolean, both: Boolean) {
        val wall = stroke.strokeWidth
        stroke.strokeWidth = wall * 1.35f
        // 「拉闊」兩邊都有牆，中間要留位畀支雙箭咀，所以兩條牆推埋去邊
        val wx = if (both) 2.6f else 3.4f
        if (left || both) canvas.drawLine(wx, 3.6f, wx, 20.4f, stroke)
        if (!left || both) canvas.drawLine(24f - wx, 3.6f, 24f - wx, 20.4f, stroke)
        stroke.strokeWidth = wall

        path.reset()
        if (both) {
            // 兩頭都係尖：由中間向兩邊撐開。個頭要細啲，唔係兩個頭撞埋一齊
            // 就會變咗個菱形，睇唔出係支箭
            path.moveTo(5.6f, 12f); path.lineTo(18.4f, 12f)
            arrowHead(path, 5.6f, -1f, 3.4f, 3.2f)
            arrowHead(path, 18.4f, 1f, 3.4f, 3.2f)
        } else if (left) {
            path.moveTo(19.5f, 12f); path.lineTo(7.2f, 12f)
            arrowHead(path, 7.2f, -1f)
        } else {
            path.moveTo(4.5f, 12f); path.lineTo(16.8f, 12f)
            arrowHead(path, 16.8f, 1f)
        }
        canvas.drawPath(path, stroke)
    }

    /**
     * 「左右拆開」：跟返其餘幾個顯示方式圖案嗰套**牆**嘅講法 ——
     * 兩邊都有牆，兩橛鍵盤各自貼實一邊牆，中間裂開條罅。
     */
    private fun drawSplit(canvas: Canvas) {
        val wall = stroke.strokeWidth
        stroke.strokeWidth = wall * 1.35f
        canvas.drawLine(2.6f, 3.6f, 2.6f, 20.4f, stroke)
        canvas.drawLine(21.4f, 3.6f, 21.4f, 20.4f, stroke)
        stroke.strokeWidth = wall
        rect.set(4.9f, 7.4f, 10.6f, 16.6f)
        canvas.drawRoundRect(rect, 1.5f, 1.5f, fill)
        rect.set(13.4f, 7.4f, 19.1f, 16.6f)
        canvas.drawRoundRect(rect, 1.5f, 1.5f, fill)
    }

    /** 地球：圓框 + 赤道 + 一個窄橢圓做經線 */
    private fun drawGlobe(canvas: Canvas) {
        canvas.drawCircle(12f, 12f, 9f, stroke)
        canvas.drawLine(3.2f, 12f, 20.8f, 12f, stroke)
        rect.set(6.9f, 3f, 17.1f, 21f)
        canvas.drawOval(rect, stroke)
    }

    /** 箭嘴頭：尖喺 ([tipX], 12)，[dir] = -1 指左、+1 指右，[back] 長、[half] 半高 */
    private fun arrowHead(p: Path, tipX: Float, dir: Float, back: Float = 4.3f,
                          half: Float = 4.1f) {
        val bx = tipX - dir * back
        p.moveTo(bx, 12f - half); p.lineTo(tipX, 12f); p.lineTo(bx, 12f + half)
    }

    override fun setAlpha(alpha: Int) { fill.alpha = alpha; stroke.alpha = alpha }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fill.colorFilter = colorFilter
        stroke.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Drawable", ReplaceWith("PixelFormat.TRANSLUCENT"))
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    private companion object {
        /** 線粗（24×24 座標入面）—— 太幼喺細粒掣度會唔見，太粗就糊 */
        const val STROKE_W = 1.9f
    }
}

/**
 * 粒掣個底：圓角底色 + [icon] 擺**正中間**。
 *
 * **唔用 compound drawable** —— `TextView` 個 `gravity` 淨係管啲字：
 * 左格嗰個 drawable 永遠貼死 `paddingLeft`（淨係上下置中），上格嗰個就永遠貼死
 * `paddingTop`（淨係左右置中），兩樣都唔會真係擺正中間。用 `LayerDrawable`
 * 疊喺底色上面，`setLayerGravity(CENTER)` 就乜情況都啱。
 */
fun iconChip(bg: Drawable, icon: ToolIcon, sizePx: Int, color: Int): Drawable =
    LayerDrawable(arrayOf(bg, ToolIconDrawable(icon, color))).apply {
        setLayerSize(1, sizePx, sizePx)
        setLayerGravity(1, Gravity.CENTER)
    }
