package tt.ime.riverine.ime

enum class KeyAction {
    DIGIT,        // 中文九宮格 0~9
    CANCEL,       // 取消
    SHORTCUT,     // 速選字
    SC_TOGGLE,    // 簡體輸出開關
    HOMO,         // 同音 toggle（長撳做乜由設定頁話事，預設 [RELATE]）
    RELATE,       // 游標前面嗰隻字嘅關聯字（TTCmd.RELATE）
    PREV_PAGE,    // 選字上一頁（淨係選字模式、夠兩頁先出現喺 0 隔籬或者左下角）
    /**
     * 選字下一頁。撳兩格闊嗰粒 `0` 一路都做得到（`TTEngine.press(0)`），
     * 呢粒係俾人擺粒實牙實齒嘅「下頁」落左右欄用 —— 見
     * [tt.ime.riverine.core.PagerLayout.NO_CHANGE]。
     */
    NEXT_PAGE,
    TO_CHINESE,
    TO_LATIN,
    TO_SYMBOL,
    TO_NUMBER,
    TO_EMOJI,     // 開 emoji 表
    PASTE,        // 貼上（長撳 = clipboard 歷史）
    /**
     * 複製：有揀字就複製揀住嗰段（`android.R.id.copy`），冇揀就複製成個輸入框
     * （見 `TTInputMethodService.copy`）。個欄一隻字都冇就撳唔到
     * （`ChinesePadView.ChineseHost.copyReady`，同 [AI] 個 `aiReady` 一樣做法）。
     */
    COPY,
    /**
     * 剪下：有揀字就叫個欄自己剪（`android.R.id.cut`）；冇揀就先全選成個輸入框
     * 先剪（見 `TTInputMethodService.cut`）。
     */
    CUT,
    /**
     * 全選：叫個欄自己做（`android.R.id.selectAll`），唔係我哋自己數字數。
     */
    SELECT_ALL,
    /** 復原：向個欄發 Ctrl+Z（見 `TTInputMethodService.undo`） */
    UNDO,
    /** 重做：向個欄發 Ctrl+Shift+Z（同上） */
    REDO,
    AI,           // 用 AI 改寫揀咗嘅字
    IME_SWITCH,   // 地球：直接跳去下一個輸入法
    IME_PICKER,   // 彈出系統嘅輸入法選單（長撳 Eng 揀得，見 [tt.ime.riverine.core.EngLongPress]）
    STT,          // 語音輸入
    OPTION,       // 上面條 bar 三段循環：關聯字 → 工具 → 兩行一齊
    /**
     * 上面條 bar **四段**循環：關聯字 → 工具 → 兩行一齊 → 收起。
     * 淨係闊 keyboard 嘅英文底行先有呢粒（見
     * [tt.ime.riverine.core.Prefs.barToggleAllowed] 同 `LatinPadView.rows`），
     * 即係話「收起」淨係嗰度做得到。
     */
    BAR_HIDE,
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
    /**
     * 長撳 popup 最頂再加嗰行（成對符號，見 [CARET]）。空 = 冇。
     *
     * 唔併埋落 [variants] 度：呢行同下面嗰啲唔同類（一個係淨係打一個字元，
     * 一個係開埋收），撈亂咗排就分唔出邊行係邊行。
     */
    val variantsTop: List<String> = emptyList(),
    /** 長撳做另一件事；[KeyAction.NOOP] = 用返 host 預設嗰個 */
    val longAction: KeyAction = KeyAction.NOOP,
    val repeatable: Boolean = false,
    /** 長撳當撳多一次（九宮格：長撳 7 拉去 0 = 770） */
    val holdRepeat: Boolean = false,
    val accent: Boolean = false,
    val swipeable: Boolean = false,
    val bigLabel: Boolean = false,
    /** false = 畫成灰色兼且撳唔到（AI 鍵未揀字嗰陣） */
    val enabled: Boolean = true,
    /**
     * 淨係佔位、唔畫亦都撳唔到（英文 `asdfghjkl` 行兩頭嗰半格）。
     * 排版嗰陣唔會加入 `boxes`，所以撳落去會 snap 去隔籬真嗰粒鍵。
     */
    val spacer: Boolean = false,
    /**
     * [text] 已經係最終要出嘅字，唔好再套 shift。
     *
     * 長撳變體 popup 揀返嚟嗰啲鍵先會 true —— 大細階兩樣都喺個 list 度揀得到，
     * shift 開住嗰陣特登揀個細階 `a`，就唔應該畀 shift 再夾硬變返 `A`。
     */
    val literal: Boolean = false
)

/**
 * 鍵面符號一律**單色**，不用彩色 emoji（🔍 那些）—— 其餘鍵面全部單色，
 * 夾一粒彩色 emoji 進去很突兀，而且很多機的 emoji 字型會畫到整顆鍵那麼大。
 *
 * 這些符號不是每個字型都有，缺了就會出一格豆腐，所以先問一問
 * [android.graphics.Paint.hasGlyph]，真的沒有才寫回中文字。
 */
private fun glyphOr(glyph: String, fallback: String): String =
    if (android.graphics.Paint().hasGlyph(glyph)) glyph else fallback

/**
 * 英文底行那顆按鍵（`KeyAction.BAR_HIDE`）四段循環的字面 ——
 * 關聯字 → 工具 → 兩行一齊 → 收起（見 `LatinPadView.barCycleGlyph`）。
 *
 * 字面講的一律是**按完會怎樣**：`⇄` 轉去工具、`⇅` 兩行一齊、`▴` 收上去、
 * `▾` 拉回來。收起那對用細三角（`▴` U+25B4 / `▾` U+25BE），與候選欄那顆
 * 拉大的 `▼` 分得開；字型沒有細那對就跌回大三角。
 */
val BAR_HIDE_GLYPH: String by lazy { glyphOr("▴", "▲") }
val BAR_SHOW_GLYPH: String by lazy { glyphOr("▾", "▼") }
val BAR_TOOLS_GLYPH: String by lazy { glyphOr("⇄", "工具") }
val BAR_BOTH_GLYPH: String by lazy { glyphOr("⇅", "兩行") }

/** 「搜尋」個樣（`⌕` U+2315）：搜尋欄的 `⏎` 與 emoji 表那粒搵字掣兩處都用它 */
val SEARCH_GLYPH: String by lazy { glyphOr("⌕", "搜尋") }

/**
 * `⏎` 跟 `imeOptions` 換樣（見 `TTInputMethodService.enterLabelFor`）。
 * 六個動作各有自己的符號；`actionUnspecified` / `actionNone` /
 * `IME_FLAG_NO_ENTER_ACTION` 就照出 `⏎`。有傳送／搜尋就出對應符號，
 * 唔好因為多行旗標就扮換行。
 */
val DONE_GLYPH: String by lazy { glyphOr("✓", "完成") }
val SEND_GLYPH: String by lazy { glyphOr("➤", "傳送") }
val GO_GLYPH: String by lazy { glyphOr("→", "前往") }
val NEXT_GLYPH: String by lazy { glyphOr("⇥", "下一") }
val PREV_GLYPH: String by lazy { glyphOr("⇤", "上一") }

/** 一格空位（英文第二行兩頭）。淨係佔 [weight] 咁多闊，唔畫亦都撳唔到。 */
fun spacerKey(weight: Float) = Key(KeyAction.NOOP, weight = weight, spacer = true)

/**
 * 變體字串入面擺住呢個字元 = commit 完 caret 要停返喺嗰個位。
 *
 * 成對符號用得着：`'` + [CARET] + `'` 一下打晒開同收，caret 停返中間，
 * 唔使打完再自己撳返轉頭。呢個字元本身**唔會**出街 —— 畫嗰陣
 * [variantDisplay] 剷走佢，commit 嗰陣就喺佢度斬開兩橛
 * （見 `TTInputMethodService.typeChar`）。
 */
const val CARET = '\u0000'

/**
 * 變體 popup／角落提示要點寫。
 *
 * Tab（`\t`）冇字形，畫出嚟係一片空白，所以寫個 `⇥` 代替 ——
 * **真正 commit 出去嗰個仲係 `\t`**，淨係畫面換咗個樣。
 */
fun variantDisplay(s: String): String =
    if (s == "\t") "⇥" else s.filter { it != CARET }

class KeyBox(val key: Key) {
    var left = 0f; var top = 0f; var right = 0f; var bottom = 0f
    val cx get() = (left + right) / 2f
    val cy get() = (top + bottom) / 2f
    val w get() = right - left
    val h get() = bottom - top
    fun set(l: Float, t: Float, r: Float, b: Float) { left = l; top = t; right = r; bottom = b }
    fun contains(x: Float, y: Float) = x >= left && x < right && y >= top && y < bottom
}
