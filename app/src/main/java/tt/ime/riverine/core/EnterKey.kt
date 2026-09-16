package tt.ime.riverine.core

import android.text.InputType
import android.view.inputmethod.EditorInfo

/**
 * `⏎` 按下要換行、執行欄位動作、還是送原生 Enter 鍵。
 *
 * 優先次序：
 * 1. [InputType.TYPE_NULL]（遊戲、自繪欄）→ 原生 Enter 鍵
 * 2. [EditorInfo.IME_FLAG_NO_ENTER_ACTION] → 換行（欄位自己說不要動作）
 * 3. 有明確動作（傳送／搜尋／完成／前往…）→ [Behavior.EDITOR_ACTION]
 * 4. 其餘 → 換行
 *
 * **有動作就不要因為多行旗標而改成換行。** 不少「送出」欄（留言、回覆）
 * 會順便標 [InputType.TYPE_TEXT_FLAG_MULTI_LINE]（文字可折行），但 `imeOptions`
 * 仍是 `IME_ACTION_SEND` —— 那是要送出，不是隔行。真的要 Enter 隔行的聊天欄
 * 應設 `IME_FLAG_NO_ENTER_ACTION`（框架建議如此）。
 *
 * 換行用 `commitText("\n")`，不要送 key event：有些 app 會攔截
 * [android.view.KeyEvent.KEYCODE_ENTER] 當送出。
 *
 * 鍵面符號（[tt.ime.riverine.ime.TTInputMethodService.enterLabelFor]）必須問同一條
 * [behavior]，否則會「寫住 ➤、按下去卻換行」。
 */
object EnterKey {

    enum class Behavior {
        /** `commitText("\n")` */
        NEWLINE,
        /** `performEditorAction` */
        EDITOR_ACTION,
        /** `sendKeyEvent(KEYCODE_ENTER)` —— 只限 [InputType.TYPE_NULL] */
        KEY_EVENT,
    }

    fun behavior(inputType: Int, imeOptions: Int): Behavior {
        if (inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_NULL) {
            return Behavior.KEY_EVENT
        }
        if (imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) return Behavior.NEWLINE
        val action = imeOptions and EditorInfo.IME_MASK_ACTION
        if (action != EditorInfo.IME_ACTION_NONE &&
            action != EditorInfo.IME_ACTION_UNSPECIFIED
        ) {
            return Behavior.EDITOR_ACTION
        }
        return Behavior.NEWLINE
    }
}
