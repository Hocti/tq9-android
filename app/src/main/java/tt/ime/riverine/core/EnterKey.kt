package tt.ime.riverine.core

import android.text.InputType
import android.view.inputmethod.EditorInfo

/**
 * `⏎` 按下要換行、執行欄位動作、還是送原生 Enter 鍵。
 *
 * 聊天欄（Discord 一類）經常是 **多行 + `IME_ACTION_SEND`**：框架本應再加
 * [EditorInfo.IME_FLAG_NO_ENTER_ACTION]，但不少 app 會清掉這旗標，又另外攔截
 * [android.view.KeyEvent.KEYCODE_ENTER] 當「送出」。若 IME 跟著 `performEditorAction`
 * 或 `sendKeyEvent(ENTER)`，Enter 就變成送出；其他鍵盤則用 `commitText("\n")` 隔行。
 *
 * 所以 **`TYPE_TEXT_FLAG_MULTI_LINE` 一律當換行**（用 `commitText`，不要送 key event）。
 * 單行的搜尋／完成／傳送仍然走 editor action。`TYPE_NULL`（遊戲、自繪欄）才送原生鍵。
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
        if (inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0) return Behavior.NEWLINE
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
