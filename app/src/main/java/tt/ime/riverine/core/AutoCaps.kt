package tt.ime.riverine.core

/**
 * 英文鍵盤「句首自動大階」的判斷。純 Kotlin、不接觸 Android，方便寫 unit test
 * （見 `AutoCapsTest`）—— IME 只負責向輸入框取游標前面那一小段字。
 *
 * 條件（`TTInputMethodService.updateAutoCaps`）：
 *
 *  - 游標前面沒有字（欄位開頭）
 *  - 前面是換行（新一段）
 *  - 前面是 `!` `?`（中英文全形都算），中間可以隔空格
 *  - 前面是 `.`，**而且一定要跟着至少一個空格**
 *
 * `.` 特別嚴格是因為「句號 + 新句」與「網址／小數／檔名／縮寫」在打字的一刻
 * 分不開：`google.` 與 `Hello.` 前面那截一模一樣。要求先按空格才轉大階，
 * 就不會打 `google.com` 打出 `google.Com`。這與 `autoSpaceAfterPunct` 特意
 * 不理會 `.` 是同一個道理，AOSP 的 `TextUtils.getCapsMode` 亦是這樣做。
 * 全形的 `。！？` 不會在網址中出現，所以不用跟空格。
 */
object AutoCaps {

    /** 要向輸入框取多少個字元。夠用來越過「句號 + 幾個空格 + 收結引號」就可以了。 */
    const val LOOKBACK = 12

    /** 句尾標點。`.` 不在此，它另有一條要跟空格的規矩（見上面）。 */
    private const val SENTENCE_END = "!?！？。"

    /** 句號之後仍可以有的收結符號：引號、括號等（`他說「好。」` 後面仍是句首）。 */
    private const val CLOSERS = "\"'’”)]}）」』〉》>"

    /**
     * [before] = 游標前面那段字（不足 [LOOKBACK] 就代表真的是欄位開頭）。
     * 回傳 true = 下一個字母應該自動大階。
     */
    fun atSentenceStart(before: CharSequence): Boolean {
        var i = before.length
        var sawSpace = false
        while (i > 0 && (before[i - 1] == ' ' || before[i - 1] == '\t')) { i--; sawSpace = true }
        while (i > 0 && before[i - 1] in CLOSERS) i--
        if (i == 0) return true
        val c = before[i - 1]
        if (c == '\n' || c == '\r') return true
        if (c in SENTENCE_END) return true
        return (c == '.' || c == '…') && sawSpace
    }
}
