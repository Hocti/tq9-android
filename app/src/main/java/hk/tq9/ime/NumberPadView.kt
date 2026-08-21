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
        // 數字鍵長撳出符號（1 → !、4 → 各國銀紙），同英文鍵盤嗰行數字一樣
        fun d(n: Int) = digitKey(n.toString(), bigLabel = true)
        return listOf(
            listOf(d(1), d(2), d(3), Key(KeyAction.BACKSPACE, label = "⌫", repeatable = true)),
            listOf(d(4), d(5), d(6), Key(KeyAction.CHAR, label = "-", text = "-")),
            listOf(d(7), d(8), d(9), Key(KeyAction.CHAR, label = ".", text = ".")),
            listOf(
                Key(KeyAction.TO_LATIN, label = "ABC"), d(0),
                Key(KeyAction.TO_CHINESE, label = "中", bigLabel = true),
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
