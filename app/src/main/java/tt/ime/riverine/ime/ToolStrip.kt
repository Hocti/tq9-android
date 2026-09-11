package tt.ime.riverine.ime

import android.annotation.SuppressLint
import android.content.Context
import android.util.TypedValue
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import kotlin.math.roundToInt

/**
 * 工具列嗰行掣。**唔用 `weight` 平分**（2026-09-09 user 要求）：
 *
 *  - 得兩三粒嗰陣，平分就會粒粒闊到成個手掌咁 —— 條 bar 睇落似個大banner，
 *    而且撳邊度都撳到嘢，好易撳錯
 *  - 夠八粒嗰陣（[tt.ime.riverine.core.KeyLayout.MAX_TOOLS]），窄機平分落去
 *    每粒得三十幾 dp，細過隻手指
 *
 * 所以每粒鎖死喺 [minW]～[maxW] 之間，擺唔晒就交俾外面個
 * [HorizontalScrollView] 打橫捲。
 *
 * ## 點做到
 *
 * 外面個 scroll view 一定要開 `fillViewport = true`，佢就會度兩次：
 *
 *  1. **UNSPECIFIED** —— 呢度回**最窄**嗰個樣（每粒 [minW]）。
 *     咁樣「縮到最細都仲係擺唔晒」先至會捲，唔會為咗留白而白白捲。
 *  2. 上面度出嚟比 viewport 窄，先再用 **EXACTLY(viewport)** 度多次 ——
 *     嗰陣就平分返，不過封頂喺 [maxW]。
 *
 * 次序調轉（UNSPECIFIED 就回 [maxW]）會變成「八粒都用最闊嘅樣去捲」，
 * 明明縮細少少就擺得晒。
 */
@SuppressLint("ViewConstructor")
class ToolStrip(context: Context) : LinearLayout(context) {

    private fun dp(v: Float) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).roundToInt()

    /** 一粒最窄幾多（撳得中嘅下限） */
    var minW = dp(44f)
        set(v) { if (field != v) { field = v; requestLayout() } }

    /**
     * 一粒最闊幾多。**由外面跟住中文九宮格一粒鍵幾闊擺落嚟**
     * （見 `OptionBarsView.refreshToolWidth`）—— 擺一至五粒嗰陣，
     * 條 bar 啲掣就同下面啲鍵一樣闊，唔會闊過佢哋。
     * 呢個預設值淨係喺未度過之前頂住檔。
     */
    var maxW = dp(76f)
        set(v) { if (field != v) { field = v; requestLayout() } }

    init { orientation = HORIZONTAL }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val n = (0 until childCount).count { getChildAt(it).visibility != View.GONE }
        if (n > 0) {
            val mode = MeasureSpec.getMode(widthMeasureSpec)
            val want = if (mode == MeasureSpec.UNSPECIFIED) minW else {
                // 除開之前要減埋每粒自己嗰兩邊 margin，唔係加加埋埋就爆咗個 viewport
                val gaps = (0 until childCount).sumOf {
                    val lp = getChildAt(it).layoutParams as? MarginLayoutParams
                    (lp?.leftMargin ?: 0) + (lp?.rightMargin ?: 0)
                }
                val avail = MeasureSpec.getSize(widthMeasureSpec) -
                    paddingLeft - paddingRight - gaps
                (avail / n).coerceIn(minW, maxW)
            }
            for (i in 0 until childCount) {
                val c = getChildAt(i)
                if (c.visibility == View.GONE) continue
                val lp = c.layoutParams
                if (lp.width != want) { lp.width = want; c.layoutParams = lp }
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}
