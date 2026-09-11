package tt.ime.riverine.ime

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import tt.ime.riverine.core.EmojiDict
import kotlin.math.abs

/**
 * **長撳「表情」= 彈一行最近用過嘅 emoji 出嚟速選**，唔使入去成個 emoji 表揀
 * （撳一下先係入去嗰個表，同以前一樣）。
 *
 * 揀法同英文鍵盤長撳彈變體一模一樣：撳實彈出、**唔好放手**拉去揀、放手先入。
 * 撳實咗唔郁就放手 = 打返最近用過嗰個（排頭嗰個）。
 *
 * ## 幾時先至有
 *
 * **淨係粒「表情」掣本身個長撳吉住嗰陣**（見 [appliesTo]）。粒掣長撳已經有
 * 第二個意思就一定唔可以食咗佢 —— 而家有兩種情況：
 *
 *  - 九宮格左右欄嗰個位配咗長撳功能（`KeyLayout.Slot.long`，例如短撳表情、
 *    長撳速選字）：長撳照做嗰個功能。
 *  - 「表情」係擺喺**第二粒掣嘅長撳格**（例如短撳「關聯字」、長撳先開表情表）：
 *    嗰粒掣個 [Key.action] 根本唔係 [KeyAction.TO_EMOJI]，長撳照樣開返個表。
 *
 * 工具列／側邊欄嗰粒冇得配長撳（`KeyLayout.TOOLS_HAVE_LONG`），所以一定有。
 *
 * ## 兩條路
 *
 * 九宮格嗰粒唔使呢個檔案入面個 [QuickEmojiPopup]：鍵盤本體本來就有成套長撳
 * 變體 popup，餵佢一行 emoji 就得（見 `ChinesePadView.variantsOf`）。
 * 工具列／側邊欄啲掣係 `TextView`，冇嗰套，先至喺呢度用返同一個 [KeyPopup] 砌多次。
 */
object QuickEmoji {

    /** 最多彈幾多個（2026-09-11 user 要求） */
    const val MAX = 10

    fun appliesTo(k: Key): Boolean =
        k.action == KeyAction.TO_EMOJI && k.longAction == KeyAction.NOOP

    fun items(ctx: Context): List<String> = EmojiDict.quick(ctx, MAX)
}

/**
 * 工具列／側邊欄嗰粒「表情」掣嘅速選 popup（見 [QuickEmoji]）。
 *
 * 粒掣係 `TextView`，佢自己識短撳同長撳，所以呢度**唔食**任何 touch
 * （[onTouch] 永遠回 `false`）—— 淨係喺長撳彈咗出嚟之後跟住手指行。
 */
class QuickEmojiPopup(
    private val anchor: View,
    private val onPick: (String) -> Unit,
) {

    private val popup = KeyPopup(anchor.context)
    private val slop = ViewConfiguration.get(anchor.context).scaledTouchSlop.toFloat()

    private var items: List<String> = emptyList()
    private var index = 0
    private var left = 0f
    private var itemW = 0f
    private var downX = 0f
    /** 手指行夠 [slop] 先至跟住揀（同 `KeyboardBaseView.updateVariantPopup`） */
    private var moved = false

    val showing: Boolean get() = items.isNotEmpty()

    private fun dp(v: Float) = v * anchor.resources.displayMetrics.density

    /**
     * 長撳嗰下。回 `true` = 彈咗出嚟（粒掣要當呢下長撳用咗，唔好再行返短撳）。
     *
     * 成行 emoji **闊過粒掣好多**（粒掣得四五十 dp，十格要成個螢幕），所以：
     *
     *  - 擺位夾返喺螢幕入面，唔可以推咗出去揀唔到（座標用返 [anchor] 入面嗰套，
     *    負數 = 出咗粒掣左邊，[KeyPopup] 收嘅就係呢套）；
     *  - 手指一橫拉就會離開粒掣，出咗去外面個 `HorizontalScrollView` 手上
     *    （工具列擺滿掣就捲得），所以要即刻叫佢哋唔好截糊。
     */
    fun open(theme: Theme, fontScale: Float): Boolean {
        val list = QuickEmoji.items(anchor.context)
        if (list.isEmpty()) return false
        items = list
        index = 0
        moved = false
        anchor.parent?.requestDisallowInterceptTouchEvent(true)

        val loc = IntArray(2)
        anchor.getLocationInWindow(loc)
        val screenW = anchor.resources.displayMetrics.widthPixels.toFloat()
        val itemH = maxOf(anchor.height.toFloat(), dp(44f))
        itemW = minOf(maxOf(anchor.width * 1.1f, dp(50f)), screenW / list.size)
        val total = itemW * list.size
        // 粒掣左邊界喺螢幕最左嗰度 = -loc[0]，成行就喺 [minLeft, minLeft + 螢幕闊] 入面
        val minLeft = -loc[0].toFloat()
        left = (anchor.width / 2f - total / 2f)
            .coerceIn(minLeft, maxOf(minLeft, minLeft + screenW - total))
        // 永遠向上彈（向下實俾手指遮住），工具列喺最頂就彈上 app 嗰邊 —— [KeyPopup] 出得去
        popup.setStyle(theme, fontScale)
        popup.showGrid(anchor, listOf(items), index, left, -itemH - dp(8f), itemW, itemH)
        return true
    }

    /** 粒掣收到嘅 touch。**永遠回 `false`**：粒掣自己嗰個短撳／長撳照行 */
    fun onTouch(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> downX = e.x
            MotionEvent.ACTION_MOVE -> if (showing) update(e.x)
            MotionEvent.ACTION_UP -> close(commit = true)
            MotionEvent.ACTION_CANCEL -> close(commit = false)
        }
        return false
    }

    /** 主題換咗、排位重砌、鍵盤收起…… 總之要收檔 */
    fun dismiss() = close(commit = false)

    private fun update(x: Float) {
        if (!moved) {
            if (abs(x - downX) < slop) return
            moved = true
        }
        val i = ((x - left) / itemW).toInt().coerceIn(0, items.size - 1)
        if (i == index) return
        index = i
        popup.highlight(i)
    }

    private fun close(commit: Boolean) {
        if (!showing) return
        val picked = items.getOrNull(index)
        items = emptyList()
        popup.dismiss()
        if (commit && picked != null) onPick(picked)
    }
}
