package tt.ime.riverine.core

/**
 * 一個功能可以擺喺邊。
 *
 * 分開兩個地方係因為兩邊嘅**做法**唔同：中文九宮格左右欄嗰八個位係鍵盤本體
 * 嘅一部分（撳落去即刻打字／換頁），工具列嗰行係鍵盤上面條 bar（撳落去多數
 * 係開第二樣嘢）。有幾個功能兩邊都擺得，但有兩類唔得：
 *
 *  - [SIDE_ONLY]：`␣`、`⌫`、`⏎`、`⇄` —— 打字必用，冇咗就打唔到字。
 *    條 bar 收埋咗（窄螢幕變側邊欄）就會搵唔到，所以一定要釘死喺鍵盤本體。
 *    **四粒轉鍵盤嘅（`Eng`／`?123`／`123`／`中`）全部唔喺呢類**（2026-09-13
 *    user 要求）：佢哋做嘅嘢都係「轉去另一個鍵盤」，同工具列本來就擺得嘅
 *    [TO_CJK] 一模一樣，冇理由淨得轉返中文擺得。`Eng` 照樣係 [required]，
 *    所以擺咗落工具列都一定仲有一個喺左右欄，條 bar 收埋咗都返得返英文。
 *  - [TOOL_ONLY]：「改變大小」—— 佢唔係撳一下就算，要**喺粒掣度直接拖**
 *    （上下拉高低、左右拉闊窄，見 `OptionBarsView.handleSizeDrag`）。
 *    九宮格啲鍵行嘅係 `KeyboardBaseView` 嗰套（撳落即出、滑動輸入…），
 *    冇呢種拖法，搬過去就淨係剩返「撳一下轉顯示方式」，一半功能唔見咗。
 */
enum class FuncPlace { BOTH, SIDE_ONLY, TOOL_ONLY }

/**
 * 一粒**可以自由擺位**嘅鍵。
 *
 * 中文九宮格左欄四粒、右欄四粒，加上工具列成行，每個位都分「短撳」同「長撳」，
 * 全部喺設定頁「按鍵排位」嗰個拖放介面度砌（見 `KeyLayout` 同 `ui/KeyLayoutEditor`）。
 * 九宮格 `1`~`9`、兩格闊嗰粒 `0`、同埋「取消」**唔喺呢個 list 入面** ——
 * 嗰幾粒係三三嘅打字本身，郁咗就唔係三三。
 *
 * ## 粒面寫乜
 *
 * [face] 係鍵面嗰幾隻字，**全部寫中文，一個彩色 emoji 都冇** —— 鍵面其餘全部
 * 單色，夾一粒彩色 emoji 好突兀，而且好多機嘅 emoji 字型會畫到成粒鍵咁大。
 * [face] 吉嘅（[IME_NEXT]／[IME_PICKER]／[ALIGN]）就改為畫個**單色圖案**
 * 喺粒鍵正中（見 `ime/ToolIcons.kt` 嘅 `PadFunc.toolIcon`）。
 *
 * ## enum 個名唔可以亂改
 *
 * 存落 SharedPreferences 嘅就係 `name`（見 [KeyLayout] 個 JSON），改咗名等於
 * 現有 user 嗰個排位一次過失效。頭八個係 2.x 之前就有嘅四個位揀得嗰批，
 * 名一定要同舊版一模一樣，唔係升級就會跌返做預設。
 */
enum class PadFunc(
    /** 設定頁見到嘅名 */
    val label: String,
    /** 鍵面寫乜（吉 = 改為畫圖案，見上面） */
    val face: String,
    val place: FuncPlace = FuncPlace.BOTH,
    /**
     * **一定要用**：喺左／右欄八個位入面至少出現一次，唔准淨係擺喺 pool 度不用。
     * 拖放介面會擋住任何令佢消失嘅動作（見 `KeyLayout.checkDrop`）。
     */
    val required: Boolean = false,
    /**
     * **淨係擺得喺短撳嗰行**，而且擺咗之後同一個位嘅長撳會強制熄咗
     * （見 [KeyLayout.Slot.effectiveLong]）。
     *
     * `␣`／`⌫`／`⏎` 三粒係咁：佢哋自己個長撳早就有咗意思（撳實 `␣` 拖動游標、
     * 撳實 `⌫` 連續刪、`⏎` 撳實唔應該連發），再喺上面疊多個功能就會撞。
     */
    val tapOnly: Boolean = false,
) {
    // ---- 舊版四個位揀得嗰批（名唔可以改，見上面） ----
    /** 停用：個位吉住，粒鍵畫成灰色兼撳唔到 */
    NONE("停用（留空）", ""),
    SHORTCUT("速選字", "速選"),
    SC_TOGGLE("簡體開關", "简"),
    /** 游標前面嗰隻字嘅關聯字（`TTCmd.RELATE`） */
    RELATE("關聯字", "關聯字"),
    EMOJI("表情符號", "表情"),
    PASTE("貼上", "貼上"),
    STT("語音輸入", "錄音"),
    AI("AI 改寫", "AI改"),

    // ---- 2.x 開始可以自由擺位嗰批 ----
    /** 短撳開關同音；長撳做乜就睇同一個位嘅長撳格 */
    HOMO("同音", "同音", FuncPlace.SIDE_ONLY),
    /**
     * 工具列都擺得（見 [FuncPlace]）。[required] 淨係管左右欄 ——
     * 擺落工具列唔算數，所以一定仲有一粒 `Eng` 喺鍵盤本體度。
     */
    TO_LATIN("英文鍵盤", "Eng", required = true),
    /** 同 [TO_LATIN] 一樣，工具列都擺得 */
    TO_SYMBOL("符號鍵盤", "?123"),
    /** 同 [TO_LATIN] 一樣，工具列都擺得 */
    TO_NUMBER("純數字鍵盤", "123"),
    SPACE("空格", "␣", FuncPlace.SIDE_ONLY, required = true, tapOnly = true),
    BACKSPACE("刪除", "⌫", FuncPlace.SIDE_ONLY, required = true, tapOnly = true),
    ENTER("換行／送出", "⏎", FuncPlace.SIDE_ONLY, required = true, tapOnly = true),
    /**
     * `⇄`：上面條 bar 三段循環 —— 關聯字 → 工具 → 兩行一齊，
     * 即係入工具列嘅唯一入口（見 [BarMode]）
     */
    BAR_SWITCH("轉換工具列", "⇄", FuncPlace.SIDE_ONLY, required = true),
    IME_NEXT("下一個輸入法", ""),
    IME_PICKER("彈出輸入法選擇表", ""),
    NEXT_PAGE("下頁", "下頁"),
    PREV_PAGE("上頁", "上頁"),

    // ---- 編輯個欄嗰批：唔關輸入法事，一律叫個欄自己做（見 `PadFuncKeys.action`）----
    /**
     * 有揀字就複製揀住嗰段；冇揀就複製成個輸入框。個欄一隻字都冇（冇揀亦都
     * 冇字可複製）就撳唔到，見 `ChinesePadView.ChineseHost.copyReady`。
     */
    COPY("複製", "複製"),
    CUT("剪下", "剪下"),
    SELECT_ALL("全選", "全選"),
    UNDO("復原", "復原"),
    REDO("重做", "重做"),

    /** 撳一下轉顯示方式、喺粒掣度直接拖就拉大細（所以擺唔入九宮格，見 [FuncPlace]） */
    ALIGN("改變大小", "", FuncPlace.TOOL_ONLY),

    /**
     * 轉返中文九宮格。英文／符號／純數字鍵盤本身一律已經有粒寫死嘅「中」
     * （`KeyAction.TO_CHINESE`，見 `LatinPadView`／`SymbolPadView`／`NumberPadView`），
     * 呢個係俾條工具列都揀得返呢個功能——工具列喺邊個鍵盤都會出（見
     * `TTInputMethodService.refreshBars`），所以擺喺度就轉得返中文，
     * 唔使淨係靠嗰粒寫死嘅鍵。中文九宮格本身已經係中文，擺落去冇意思，
     * 所以同「改變大小」一樣淨係工具列擺得（見 [FuncPlace]）。
     */
    TO_CJK("中文鍵盤", "中", FuncPlace.TOOL_ONLY);

    val sideOk: Boolean get() = place != FuncPlace.TOOL_ONLY
    val toolOk: Boolean get() = place != FuncPlace.SIDE_ONLY
}
