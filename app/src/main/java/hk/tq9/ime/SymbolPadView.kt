package hk.tq9.ime

import android.content.Context

/**
 * 符號鍵盤，五行。
 *
 * 第一頁要一頁過打得晒普通 qwerty 鍵盤打得出嘅符號（`~ % [] {} \ | < >` 呢啲），
 * 所以最底嗰行放棄咗「中」、「🌐」同埋長長條 space，換返多兩行位出嚟。
 * 第二頁（extra 符號）就係其他符號（銀紙、數學、標點）。
 *
 * 最底行右返去 ABC／中 兩條路都要有 —— 由英文入嚟就撳 ABC，由中文撳 `?123` 入嚟就撳 中，
 * 唔使兜個圈（例如中文 → ABC → 中）。純數字 keypad 淨係喺 extra 符號頁先入得，
 * 擺喺 enter 上一行最右（[KeyAction.TO_NUMBER]），減咗嗰行最唔常用嗰幾個符號讓位。
 *
 * 上面第一行係數字，長撳會出返 shift 嗰個符號（`1` → `!`），同英文鍵盤一樣。
 */
class SymbolPadView(context: Context) : RowsPadView(context) {

    var page = 0
        set(v) { field = v; rebuild() }

    private fun ch(c: String, w: Float = 1f) = Key(KeyAction.CHAR, label = c, text = c, weight = w)

    private fun row(s: String) = s.map { ch(it.toString()) }

    override fun rows(): List<List<Key>> {
        val r0: List<Key>
        val r1: List<Key>
        val r2: List<Key>
        val r3: List<Key>
        val toggleLabel: String
        if (page == 0) {
            r0 = "1234567890".map { digitKey(it.toString()) }
            r1 = row("!@#$%^&*()")
            r2 = row("`~-_=+[]{}")
            r3 = row("\\|;:'\"<>/?")
            toggleLabel = "=\\<"
        } else {
            r0 = row("€£¥¢₩₹₱¤฿₫")
            r1 = row("•√π÷×¶∆°±≠")
            r2 = listOf("©", "®", "™", "✓", "§", "¡", "¿", "…", "‰", "∞").map { ch(it) }
            // 最唔常用嗰兩個（¦ ¬）減咗，讓位俾 numpad 掣
            r3 = listOf("«", "»", "“", "”", "‘", "’", "–", "—").map { ch(it) } +
                listOf(Key(KeyAction.TO_NUMBER, label = "numpad", weight = 1.6f))
            toggleLabel = "?123"
        }
        val r4 = listOf(
            Key(KeyAction.SYM_PAGE, label = toggleLabel, weight = 1.4f),
            Key(KeyAction.TO_LATIN, label = "ABC", weight = 1.4f),
            Key(KeyAction.TO_CHINESE, label = "中", weight = 1.4f),
            ch(","),
            Key(KeyAction.SPACE, label = "␣", weight = 2.6f),
            ch("."),
            Key(KeyAction.BACKSPACE, label = "⌫", weight = 1.4f, repeatable = true),
            Key(KeyAction.ENTER, label = "⏎", weight = 1.6f, accent = true)
        )
        return listOf(r0, r1, r2, r3, r4)
    }
}
