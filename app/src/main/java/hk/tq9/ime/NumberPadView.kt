package hk.tq9.ime

import android.content.Context
import hk.tq9.core.PadGroup

/**
 * 純數字鍵盤。兩種用法：
 *
 *  - [pinMode]：入 PIN／密碼嗰陣自動出，三欄，唔畀轉去其他 view
 *  - 平時：由符號頁撳 `123` 入嚟，五欄 —— 最左一欄係 `+ - * /`
 *    （計數／打算式唔使再走去符號頁），右邊四欄係數字同 `中`／`Eng`／`⌫`／`⏎`。
 *    打完一個數字**唔會**自動彈返去英文頁，可以一個接一個咁打落去。
 *
 * 闊度同擺位跟返中文九宮格（[PadMetrics]，一樣係 5 欄）—— 兩邊都係 numpad 排法，
 * 中英切換嗰陣啲鍵唔應該左右彈嚟彈去（以前呢頁自己置中，同九宮格對唔上）。
 */
class NumberPadView(context: Context) : RowsPadView(context) {

    /** 呢頁一律唔准長撳（打號碼撳耐咗就彈 popup 出嚟好煩） */
    override fun allowLongPress(k: Key) = false

    /** true = 密碼／PIN，唔畀轉去其他 view */
    var pinMode: Boolean = false
        set(v) { field = v; rebuild() }

    private fun num(n: Int) = Key(KeyAction.CHAR, label = n.toString(), text = n.toString(), bigLabel = true)

    /** 四則運算符號（最左一欄）。`-` 由底行搬咗過嚟，讓返個位俾 `000` */
    private fun op(s: String) = Key(KeyAction.CHAR, label = s, text = s, bigLabel = true)

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
        // `中` / `Eng` 喺右上角（唔喺底行），`0` `000` `.` 就喺底行，
        // ⌫ 照舊喺 ⏎ 上面。`000` = 一次過打三個 0（金額、電話號碼常用）。
        return listOf(
            listOf(op("+"), num(1), num(2), num(3),
                Key(KeyAction.TO_CHINESE, label = "中", bigLabel = true)),
            listOf(op("-"), num(4), num(5), num(6), Key(KeyAction.TO_LATIN, label = "Eng")),
            listOf(op("*"), num(7), num(8), num(9),
                Key(KeyAction.BACKSPACE, label = "⌫", repeatable = true)),
            listOf(
                op("/"),
                num(0),
                Key(KeyAction.CHAR, label = "000", text = "000", bigLabel = true),
                Key(KeyAction.CHAR, label = ".", text = ".", bigLabel = true),
                Key(KeyAction.ENTER, label = "⏎", accent = true)
            )
        )
    }

    /**
     * 高度／闊度／貼邊完全跟中文九宮格（同一套 [PadGroup.CJK] 設定）：
     * 「拉闊」就鋪滿成行，「靠左」／「靠右」就同九宮格喺同一邊、同一個闊度，
     * 連工具 bar 左右拖出嚟嗰個闊度倍數都一齊跟。
     */
    override val padGroup get() = PadGroup.CJK
}
