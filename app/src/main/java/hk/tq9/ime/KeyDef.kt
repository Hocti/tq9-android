package hk.tq9.ime

enum class KeyAction {
    DIGIT,        // 中文九宮格 0~9
    CANCEL,       // 取消
    SHORTCUT,     // 速選字
    SC_TOGGLE,    // 簡體輸出開關
    HOMO,         // 同音 toggle（長撳 = 關聯字）
    TO_CHINESE,
    TO_LATIN,
    TO_SYMBOL,
    TO_NUMBER,
    TO_EMOJI,     // 開 emoji 表
    PASTE,        // 貼上（長撳 = clipboard 歷史）
    AI,           // 用 AI 改寫揀咗嘅字
    IME_SWITCH,   // 地球
    STT,          // 語音輸入
    OPTION,       // 上面條 bar：關 → 候選字 → 工具
    BACKSPACE,
    SPACE,
    ENTER,
    CHAR,         // 直接輸出 text
    SHIFT,
    SYM_PAGE,     // 符號分頁
    NOOP
}

data class Key(
    val action: KeyAction,
    val label: String = "",
    val text: String = "",
    val digit: Int = -1,
    /** 左上角細字（英文鍵盤嘅數字、同音鍵嘅字碼…） */
    val hint: String = "",
    /** 右上角細字（長撳彈出嘅符號提示） */
    val hintRight: String = "",
    /** 橫向闊度比重（英文鍵盤用） */
    val weight: Float = 1f,
    /** 長撳彈出嘅變體（數字、符號、重音字母…），空 = 冇 popup */
    val variants: List<String> = emptyList(),
    /** 長撳做另一件事；[KeyAction.NOOP] = 用返 host 預設嗰個 */
    val longAction: KeyAction = KeyAction.NOOP,
    val repeatable: Boolean = false,
    /** 長撳當撳多一次（九宮格：長撳 7 拉去 0 = 770） */
    val holdRepeat: Boolean = false,
    val accent: Boolean = false,
    val swipeable: Boolean = false,
    val bigLabel: Boolean = false,
    /** false = 畫成灰色兼且撳唔到（AI 鍵未揀字嗰陣） */
    val enabled: Boolean = true
)

class KeyBox(val key: Key) {
    var left = 0f; var top = 0f; var right = 0f; var bottom = 0f
    val cx get() = (left + right) / 2f
    val cy get() = (top + bottom) / 2f
    val w get() = right - left
    val h get() = bottom - top
    fun set(l: Float, t: Float, r: Float, b: Float) { left = l; top = t; right = r; bottom = b }
    fun contains(x: Float, y: Float) = x >= left && x < right && y >= top && y < bottom
}
