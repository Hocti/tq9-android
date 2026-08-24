package hk.tq9.ime

import android.content.Context
import android.util.TypedValue
import hk.tq9.core.Prefs
import kotlin.math.min

/**
 * 純數字鍵盤。兩種用法：
 *
 *  - [pinMode]：入 PIN／密碼嗰陣自動出，三欄，唔畀轉去其他 view
 *  - 平時：由符號頁撳 `123` 入嚟，四欄，有 `.` `,` `-`。
 *    打完一個數字**唔會**自動彈返去英文頁，可以一個接一個咁打落去。
 */
class NumberPadView(context: Context) : RowsPadView(context) {

    /** 呢頁一律唔准長撳（打號碼撳耐咗就彈 popup 出嚟好煩） */
    override fun allowLongPress(k: Key) = false

    /** true = 密碼／PIN，唔畀轉去其他 view */
    var pinMode: Boolean = false
        set(v) { field = v; rebuild() }

    private fun num(n: Int) = Key(KeyAction.CHAR, label = n.toString(), text = n.toString(), bigLabel = true)

    override fun rows(): List<List<Key>> {
        if (pinMode) {
            return listOf(
                listOf(num(1), num(2), num(3)),
                listOf(num(4), num(5), num(6)),
                listOf(num(7), num(8), num(9)),
                listOf(Key(KeyAction.CHAR, label = "-", text = "-"), num(0),
                    Key(KeyAction.BACKSPACE, label = "⌫", repeatable = true))
            )
        }
        // 呢頁**冇一粒鍵有長撳效果** —— 打電話號碼／金額嗰陣撳耐咗少少就彈個
        // 符號 popup 出嚟好煩，所以數字一律用淨得個 label 嘅 [num]，唔用 digitKey。
        // `中` / `ABC` 喺右上角（唔喺底行），`0` `.` `-` 就喺左下角，
        // ⌫ 照舊喺 ⏎ 上面。
        return listOf(
            listOf(num(1), num(2), num(3), Key(KeyAction.TO_CHINESE, label = "中", bigLabel = true)),
            listOf(num(4), num(5), num(6), Key(KeyAction.TO_LATIN, label = "ABC")),
            listOf(num(7), num(8), num(9), Key(KeyAction.BACKSPACE, label = "⌫", repeatable = true)),
            listOf(
                num(0),
                Key(KeyAction.CHAR, label = ".", text = "."),
                Key(KeyAction.CHAR, label = "-", text = "-"),
                Key(KeyAction.ENTER, label = "⏎", accent = true)
            )
        )
    }

    override fun contentBounds(w: Int): Pair<Float, Float> {
        val maxW = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, min(Prefs.maxWidthDp(context), 360).toFloat(),
            resources.displayMetrics
        )
        val cw = min(w.toFloat(), maxW)
        return ((w - cw) / 2f) to cw
    }
}
