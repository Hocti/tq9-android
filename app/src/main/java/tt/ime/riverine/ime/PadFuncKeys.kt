package tt.ime.riverine.ime

import tt.ime.riverine.core.PadFunc

/**
 * [PadFunc]（設定頁嗰堆可以拖嚟拖去嘅功能）↔ 鍵盤本身識做嘅嘢。
 *
 * 特登唔將呢啲擺入 `core/PadFunc.kt`：`core` 嗰邊淨係講「有邊啲功能、擺得邊」，
 * 至於粒鍵撳落去行邊段 code（[KeyAction]）、畫個乜圖案（[ToolIcon]），
 * 係 `ime` 呢邊嘅事。
 */
fun PadFunc.action(): KeyAction = when (this) {
    PadFunc.NONE -> KeyAction.NOOP
    PadFunc.SHORTCUT -> KeyAction.SHORTCUT
    PadFunc.SC_TOGGLE -> KeyAction.SC_TOGGLE
    PadFunc.RELATE -> KeyAction.RELATE
    PadFunc.EMOJI -> KeyAction.TO_EMOJI
    PadFunc.PASTE -> KeyAction.PASTE
    PadFunc.STT -> KeyAction.STT
    PadFunc.AI -> KeyAction.AI
    PadFunc.HOMO -> KeyAction.HOMO
    PadFunc.TO_LATIN -> KeyAction.TO_LATIN
    PadFunc.TO_SYMBOL -> KeyAction.TO_SYMBOL
    PadFunc.TO_NUMBER -> KeyAction.TO_NUMBER
    PadFunc.SPACE -> KeyAction.SPACE
    PadFunc.BACKSPACE -> KeyAction.BACKSPACE
    PadFunc.ENTER -> KeyAction.ENTER
    PadFunc.BAR_SWITCH -> KeyAction.OPTION
    PadFunc.IME_NEXT -> KeyAction.IME_SWITCH
    PadFunc.IME_PICKER -> KeyAction.IME_PICKER
    PadFunc.NEXT_PAGE -> KeyAction.NEXT_PAGE
    PadFunc.PREV_PAGE -> KeyAction.PREV_PAGE
    PadFunc.SELECT_ALL -> KeyAction.SELECT_ALL
    PadFunc.UNDO -> KeyAction.UNDO
    PadFunc.REDO -> KeyAction.REDO
    // 「改變大小」冇 [KeyAction]：佢唔係撳一下就算，而係喺粒掣度直接拖
    // （見 `OptionBarsView.handleSizeDrag`），所以淨係工具列擺得，
    // 亦都淨係喺嗰度接駁（見 `PadFunc.place`）
    PadFunc.ALIGN -> KeyAction.NOOP
}

/**
 * 工具列（同側邊欄）嗰粒掣畫個乜圖案。`null` = 冇圖案，寫返 [PadFunc.face] 嗰幾隻字。
 *
 * [PadFunc.ALIGN] 特登回 `null`：佢個圖案跟住而家嘅顯示方式變
 * （拉闊／靠左／靠右／左右拆開），由 `refreshAlignLabel` 逐次砌。
 */
fun PadFunc.toolIcon(): ToolIcon? = when (this) {
    PadFunc.PASTE -> ToolIcon.PASTE
    PadFunc.STT -> ToolIcon.MIC
    PadFunc.EMOJI -> ToolIcon.EMOJI
    PadFunc.AI -> ToolIcon.AI
    PadFunc.IME_NEXT -> ToolIcon.GLOBE
    PadFunc.IME_PICKER -> ToolIcon.GLOBE_LIST
    else -> null
}

/**
 * 鍵面圖案（中文九宮格左右欄嗰八個位）。
 *
 * **淨係 [PadFunc.face] 吉嗰啲先有**：「貼上」「錄音」呢啲喺鍵盤度一路都係
 * 寫中文（鍵面全部單色兼且全部係字，夾一兩個圖案入去反而唔一致），
 * 圖案留返俾工具列用。得換輸入法嗰兩粒冇字好寫，先至畫圖案。
 */
fun PadFunc.faceIcon(): ToolIcon? = if (face.isEmpty()) toolIcon() else null

/**
 * 砌返粒 [Key] 出嚟。[long] 係同一個位嘅長撳做乜（[PadFunc.NONE] = 冇長撳）。
 *
 * 左上角細字一律寫「長撳做乜」（成個 app 嘅規矩）：長撳嗰個功能有字就寫字，
 * 冇字（換輸入法嗰兩粒）就交俾 `ChinesePadView.drawFunction` 畫個角落圖案。
 */
fun PadFunc.toKey(long: PadFunc = PadFunc.NONE): Key = Key(
    action = action(),
    label = face,
    hint = long.face,
    longAction = long.action(),
    accent = this == PadFunc.ENTER,
    repeatable = this == PadFunc.BACKSPACE,
    enabled = this != PadFunc.NONE,
)

/**
 * 粒鍵**左上角**應唔應該畫個圖案代替文字（＝嗰個長撳動作對應嘅 [PadFunc]
 * 冇 `face` 可寫）。
 *
 * 而家淨係轉輸入法嗰兩粒：直接跳去下一個係一個地球，彈選擇表就係地球加張表
 * （[ToolIcon.GLOBE_LIST]）—— 兩粒可以同時擺喺鍵盤度，淨用個地球就分唔出
 * 邊粒係邊粒。
 *
 * 中文九宮格（`ChinesePadView.drawFunction`）同純數字鍵盤
 * （`RowsPadView.drawKey`）兩邊都用佢，所以擺喺呢度唔擺喺其中一邊。
 */
fun longIconOf(a: KeyAction): ToolIcon? = when (a) {
    KeyAction.IME_SWITCH -> ToolIcon.GLOBE
    KeyAction.IME_PICKER -> ToolIcon.GLOBE_LIST
    else -> null
}
