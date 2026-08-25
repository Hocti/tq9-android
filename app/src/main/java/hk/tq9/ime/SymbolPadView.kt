package hk.tq9.ime

import android.content.Context

/**
 * 符號鍵盤，五行。
 *
 * 第一頁要一頁過打得晒普通 qwerty 鍵盤打得出嘅符號（`~ % [] {} \ | < >` 呢啲），
 * 第二頁（extra 符號）就係其他符號（銀紙、數學、標點）。
 *
 * 底行嘅規矩（同英文鍵盤一樣，全部鍵盤都跟）：
 *
 *  - **左下兩粒一定係 `Eng` 同 `中`**（英行先），即係「返去英文／中文」，唔使兜圈。
 *  - **`⏎` 上面嗰粒一定係 `⌫`**，所以 `⌫` 同分頁掣（`€£¥`／`?123`）都搬咗上
 *    倒數第二行嘅最左同最右，底行淨返轉鍵盤、space、標點同 `⏎`。
 *
 * 第一頁底行 space 右邊順住排 `, . ? ; /` 五粒（都係打字最常用嗰啲），
 * 呢五粒本來喺上面兩行，讓返位出嚟畀分頁掣（`€£¥`）同 `⌫`。
 * 第二頁就唔要標點（space 同 `⏎` 拉長），純數字 keypad 擺喺 numpad 掣（
 * [KeyAction.TO_NUMBER]），又再向上升多一行，個位留返俾 `⌫`。
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
        val r4: List<Key>
        if (page == 0) {
            r0 = "1234567890".map { digitKey(it.toString()) }
            r1 = row("!@#$%^&*()")
            r2 = row("`~-_=+[]{}")
            // `/ ? ;` 搬咗落底行 space 隔籬，呢行兩頭讓咗位出嚟畀分頁掣同 ⌫
            // 分頁掣寫住 `€£¥` —— 第二頁頭一行就係啲銀紙符號，寫 `=\<` 冇人知係乜
            r3 = listOf(Key(KeyAction.SYM_PAGE, label = "€£¥", weight = 1.4f)) +
                row("\\|:'\"<>") +
                listOf(Key(KeyAction.BACKSPACE, label = "⌫", weight = 1.4f, repeatable = true))
            r4 = listOf(
                Key(KeyAction.TO_LATIN, label = "Eng", weight = 1.3f),
                Key(KeyAction.TO_CHINESE, label = "中", weight = 1.3f),
                Key(KeyAction.SPACE, label = "␣", weight = 2.4f),
                ch(","), ch("."), ch("?"), ch(";"), ch("/"),
                Key(KeyAction.ENTER, label = "⏎", weight = 1.5f, accent = true)
            )
        } else {
            r0 = row("€£¥¢₩₹₱¤฿₫")
            r1 = row("•√π÷×¶∆°±≠")
            // 最唔常用嗰個（∞）減咗，讓位俾由下面升上嚟嘅 numpad 掣
            r2 = listOf("©", "®", "™", "✓", "§", "¡", "¿", "…", "‰").map { ch(it) } +
                listOf(Key(KeyAction.TO_NUMBER, label = "numpad", weight = 1.6f))
            r3 = listOf(Key(KeyAction.SYM_PAGE, label = "?123", weight = 1.4f)) +
                listOf("«", "»", "\u201c", "\u201d", "\u2018", "\u2019", "–", "—").map { ch(it) } +
                listOf(Key(KeyAction.BACKSPACE, label = "⌫", weight = 1.4f, repeatable = true))
            r4 = listOf(
                Key(KeyAction.TO_LATIN, label = "Eng", weight = 1.3f),
                Key(KeyAction.TO_CHINESE, label = "中", weight = 1.3f),
                Key(KeyAction.SPACE, label = "␣", weight = 5.4f),
                Key(KeyAction.ENTER, label = "⏎", weight = 2f, accent = true)
            )
        }
        return listOf(r0, r1, r2, r3, r4)
    }
}
