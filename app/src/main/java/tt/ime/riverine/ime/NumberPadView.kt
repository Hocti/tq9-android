package tt.ime.riverine.ime

import android.content.Context
import tt.ime.riverine.core.KeyLayout
import tt.ime.riverine.core.PadFunc
import tt.ime.riverine.core.PadGroup

/**
 * 純數字鍵盤要出哪一套排位。由 `TTInputMethodService.onStartInputView`
 * 看 `inputType` 決定，或者由符號頁撳 `123` 入來（[CALC]）。
 *
 * 全部都是 5 欄 4 行（[PIN] 除外），闊度與擺位跟中文九宮格一樣 —— 最左那一欄
 * 是「跟着欄位變」的一欄，欄位用不着的就留空（[spacerKey]，撳落去會 snap 去
 * 隔籬那粒），數字永遠在同一個位置，中英切換時不會左右彈。
 */
enum class NumField {
    /** 由符號頁撳 `123` 入來：最左一欄是 `+ - * /`，計數／打算式用 */
    CALC,
    /** `numberPassword`：三欄，不准轉去其他 view */
    PIN,
    /** `phone`：`( ) - +` 與 `*` `#`，跟一般撥號鍵盤 */
    PHONE,
    /** `number`：`-` 與 `.` 只在 `numberSigned` / `numberDecimal` 才出 */
    NUMBER,
    /** `datetime` / `date` / `time`：出該類型收得的分隔符（`. - /` 或 `:`） */
    DATE, TIME, DATETIME
}

/**
 * 純數字鍵盤。排位跟 [NumField]：
 *
 *  - 打電話號碼、日期、金額那些欄位，用不着的鍵（`* / +`、`.`、`000`）全部
 *    收起 —— 那些字元會被輸入框自己的 `KeyListener` 濾走，留着就是撳極都沒有反應的死鍵。
 *  - 欄位收得的分隔符就補回去（`( ) # *`、`. - /`、`:`）。
 *
 * 闊度同擺位跟返中文九宮格（[PadMetrics]，一樣係 5 欄）—— 兩邊都係 numpad 排法，
 * 中英切換嗰陣啲鍵唔應該左右彈嚟彈去（以前呢頁自己置中，同九宮格對唔上）。
 */
class NumberPadView(context: Context) : RowsPadView(context) {

    /**
     * 呢頁**淨係粒 `Eng` 長撳得**（打號碼撳耐咗就彈 popup 出嚟好煩，
     * 所以數字同符號一律唔准）。
     *
     * `Eng` 係例外，因為佢個長撳係**換輸入法** —— 同中文九宮格嗰粒一模一樣
     * （見 [toLatin]）。呢頁好多時係打電話號碼／金額，中途想跳去第二個輸入法
     * 一樣要有得跳，唔應該逼人先返中文頁。
     */
    override fun allowLongPress(k: Key) = k.action == KeyAction.TO_LATIN

    /** 而家出緊邊套排位（見 [NumField]），連埋 `number` 欄收唔收 `-` / `.` */
    private var fieldKind: NumField = NumField.CALC
    private var allowSign = false
    private var allowDecimal = false

    /** 一次過設定（三個值一齊變，唔好逐個 setter 各自 rebuild 一次） */
    fun setField(kind: NumField, sign: Boolean = false, decimal: Boolean = false) {
        if (kind == fieldKind && sign == allowSign && decimal == allowDecimal) return
        fieldKind = kind
        allowSign = sign
        allowDecimal = decimal
        rebuild()
    }

    private fun num(n: Int) = Key(KeyAction.CHAR, label = n.toString(), text = n.toString(), bigLabel = true)

    /** 符號鍵（最左一欄、`*` `#` 那些）。`-` 由底行搬咗過嚟，讓返個位俾 `000` */
    private fun op(s: String) = Key(KeyAction.CHAR, label = s, text = s, bigLabel = true)

    private fun toChinese() = Key(KeyAction.TO_CHINESE, label = "中", bigLabel = true)

    /**
     * `Eng`。長撳做乜**跟返中文九宮格嗰粒**（`KeyLayout.longFor`）——
     * user 喺「按鍵排位」度將佢改做「彈輸入法選擇表」，兩頁一齊變。
     * 冇配過就冇長撳，粒鍵左上角亦都唔會畫嘢。
     */
    private fun toLatin(): Key {
        val long = KeyLayout.longFor(KeyLayout.load(context), PadFunc.TO_LATIN)
        return Key(KeyAction.TO_LATIN, label = "Eng",
            hint = long.face, longAction = long.action())
    }
    private fun backspace() = Key(KeyAction.BACKSPACE, label = "⌫", repeatable = true)
    private fun enter() = Key(KeyAction.ENTER, label = "⏎", accent = true)

    override fun rows(): List<List<Key>> = when (fieldKind) {
        NumField.PIN -> pinRows()
        NumField.PHONE -> phoneRows()
        NumField.NUMBER -> numberRows()
        NumField.DATE, NumField.TIME, NumField.DATETIME -> dateRows()
        NumField.CALC -> calcRows()
    }

    /**
     * PIN／密碼：三欄，一粒轉頁掣都冇。底行本來有粒 `-`（PIN 入面完全用唔着），
     * 換咗做 `⏎` —— 好多 PIN 欄嘅 `imeOptions` 係 `actionDone`（見
     * `TTInputMethodService.enterLabelFor`，粒鍵會寫住 ✓）。
     */
    private fun pinRows() = listOf(
        listOf(num(1), num(2), num(3)),
        listOf(num(4), num(5), num(6)),
        listOf(num(7), num(8), num(9)),
        listOf(enter(), num(0), backspace())
    )

    // 數字一律用淨得個 label 嘅 [num]（唔用 digitKey）—— 打電話號碼／金額嗰陣
    // 撳耐咗少少就彈個符號 popup 出嚟好煩。`0` `000` `.` 喺底行，
    // ⌫ 照舊喺 ⏎ 上面。`000` = 一次過打三個 0（金額、電話號碼常用）。
    //
    // **`中` / `Eng` 2026-09-09 由右上角搬返落左下角**（user 要求）——
    // 同其餘三款鍵盤嗰條「左下兩粒係返回英文／中文」嘅規矩睇齊，
    // 而且 `Eng` 落到最左下，同中文九宮格嗰粒企喺同一個位。
    // 最左嗰欄啲符號向上推（`* /` 上到頂），讓走嗰對（`+ -`）搬去右上角。
    private fun calcRows() = listOf(
        listOf(op("*"), num(1), num(2), num(3), op("+")),
        listOf(op("/"), num(4), num(5), num(6), op("-")),
        listOf(toChinese(), num(7), num(8), num(9), backspace()),
        listOf(toLatin(), num(0), op("000"), op("."), enter())
    )

    /**
     * 電話：`+ - ( )` 同 `*` `#` 都係撥號串常用（`+852`、`(852) 1234-5678`、
     * 分機 `#`），`/` 呢啲計數符號就唔要。`中` / `Eng` 佔咗左下兩格
     * （見 [calcRows]），所以 `( )` 讓咗去右上角。
     */
    private fun phoneRows() = listOf(
        listOf(op("-"), num(1), num(2), num(3), op("(")),
        listOf(op("+"), num(4), num(5), num(6), op(")")),
        listOf(toChinese(), num(7), num(8), num(9), backspace()),
        listOf(toLatin(), op("*"), num(0), op("#"), enter())
    )

    /**
     * `number` 欄：`+ * /` 一律唔要（會俾輸入框濾走），`-` 淨係 `numberSigned`
     * 先出（喺右上角），`.` 淨係 `numberDecimal` 先出（喺底行）。兩樣都冇
     * （淨係 `number`，例如數量、年齡）就上面兩行左右各留一格白。
     */
    private fun numberRows() = listOf(
        listOf(spacerKey(1f), num(1), num(2), num(3),
            if (allowSign) op("-") else spacerKey(1f)),
        listOf(spacerKey(1f), num(4), num(5), num(6), spacerKey(1f)),
        listOf(toChinese(), num(7), num(8), num(9), backspace()),
        listOf(
            toLatin(), num(0), op("000"),
            if (allowDecimal) op(".") else spacerKey(1f),
            enter()
        )
    )

    /**
     * 日期／時間：最左一欄擺分隔符。`date` 收 `. - /`、`time` 收 `:`、
     * 冇指定變體（`datetime`）就四粒都出 —— 出多咗嗰啲會俾 `DateKeyListener` /
     * `TimeKeyListener` 濾走，所以要按變體分開。底行嘅 `0` 拉闊三格
     * （日期唔會打 `000`，讓個位出嚟粒 `0` 大啲）。
     */
    private fun dateRows(): List<List<Key>> {
        val date = fieldKind != NumField.TIME
        val time = fieldKind != NumField.DATE
        val col = listOf(
            if (date) op(".") else spacerKey(1f),
            if (date) op("-") else spacerKey(1f),
            if (date) op("/") else spacerKey(1f),
            if (time) op(":") else spacerKey(1f)
        )
        // 同 [calcRows] 一樣：`中` / `Eng` 佔咗左下兩格，啲分隔符向上推，
        // 讓走嗰對（`.` `-`）搬去右上角
        return listOf(
            listOf(col[2], num(1), num(2), num(3), col[0]),
            listOf(col[3], num(4), num(5), num(6), col[1]),
            listOf(toChinese(), num(7), num(8), num(9), backspace()),
            listOf(toLatin(), num(0).copy(weight = 3f), enter())
        )
    }

    /**
     * 高度／闊度／貼邊完全跟中文九宮格（同一套 [PadGroup.CJK] 設定）：
     * 「拉闊」就鋪滿成行，「靠左」／「靠右」就同九宮格喺同一邊、同一個闊度，
     * 連工具 bar 左右拖出嚟嗰個闊度倍數都一齊跟。
     */
    override val padGroup get() = PadGroup.CJK
}
