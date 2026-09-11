package tt.ime.riverine.core

import java.text.BreakIterator

/**
 * 剷字嗰陣要知「游標前面嗰嚿嘢」實際佔幾多個 `char`。
 *
 * `InputConnection.deleteSurroundingText` 收嘅單位係 **UTF-16 char**，唔係「一個圖」——
 * 而且收嗰邊（`BaseInputConnection`，即係一般 `EditText`）**唔會**自己補返個 surrogate
 * pair，淨係硬生生斬走一個 char。所以剷一個一律寫 `1` 嘅話：
 *
 * | 打嘅嘢 | 佔幾多 char | 要撳幾多下 ⌫ |
 * | --- | --- | --- |
 * | `字` / `a` | 1 | 1 |
 * | `👾`（U+1F47E，同 `assets/emoji.txt` 入面一千二百幾個一樣） | 2 | 2 |
 * | `🇭🇰`（兩個 regional indicator） | 4 | 4 |
 * | `👨‍👩‍👧`（ZWJ 串埋一家人） | 8 | 8 |
 *
 * 中間嗰幾下仲會喺個欄度留低半隻字（豆腐字），所以一定要一次過剷成個
 * grapheme cluster —— 即係 [BreakIterator.getCharacterInstance] 嗰個
 * 「文字元素」，同 [TTDb.splitGraphemes] 用嘅係同一套規矩。
 */
object TextEdit {

    /**
     * 要問個欄攞返幾多個 char 落嚟先夠計。一家人 emoji 連膚色可以去到十幾個 char，
     * 攞多啲唔會慢（一次 IPC），但係要有個上限 —— 唔係就成個欄拉晒落嚟。
     */
    const val LOOKBEHIND = 64

    /**
     * [before]（游標前面嗰段字）最後一個文字元素佔幾多個 `char`。
     * 空字串回 0；排唔到版（理論上唔會）就回 1，總好過剷唔郁。
     *
     * 注意 [before] 係斬短咗嘅一段，起頭嗰個 cluster 可能被斬開一半 ——
     * 唔緊要，呢度淨係要**最尾**嗰個。
     */
    fun lastClusterLength(before: CharSequence): Int {
        if (before.isEmpty()) return 0
        val s = before.toString()
        val it = BreakIterator.getCharacterInstance()
        it.setText(s)
        val start = it.preceding(s.length)
        if (start == BreakIterator.DONE || start >= s.length) return 1
        return s.length - start
    }
}
