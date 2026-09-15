package tt.ime.riverine

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Test
import tt.ime.riverine.core.EnterKey
import tt.ime.riverine.core.EnterKey.Behavior

/**
 * `⏎`：多行一律隔行；單行先跟 `imeOptions` 做搜尋／傳送。
 *
 * Discord 聊天欄 = 多行 + `IME_ACTION_SEND`（有時仲冇 `IME_FLAG_NO_ENTER_ACTION`），
 * 以前會 `performEditorAction(SEND)` 或者 `sendKeyEvent(ENTER)`，app 兩邊都當成送出。
 */
class EnterKeyTest {

    private fun kind(type: Int, opts: Int) = EnterKey.behavior(type, opts)

    @Test fun `多行加傳送都係換行`() {
        val type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        assertEquals(Behavior.NEWLINE, kind(type, EditorInfo.IME_ACTION_SEND))
        assertEquals(Behavior.NEWLINE, kind(type, EditorInfo.IME_ACTION_DONE))
        assertEquals(
            Behavior.NEWLINE,
            kind(type, EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_ENTER_ACTION),
        )
    }

    @Test fun `單行傳送搜尋完成仍然係動作`() {
        val type = InputType.TYPE_CLASS_TEXT
        assertEquals(Behavior.EDITOR_ACTION, kind(type, EditorInfo.IME_ACTION_SEND))
        assertEquals(Behavior.EDITOR_ACTION, kind(type, EditorInfo.IME_ACTION_SEARCH))
        assertEquals(Behavior.EDITOR_ACTION, kind(type, EditorInfo.IME_ACTION_DONE))
        assertEquals(Behavior.EDITOR_ACTION, kind(type, EditorInfo.IME_ACTION_GO))
        assertEquals(Behavior.EDITOR_ACTION, kind(type, EditorInfo.IME_ACTION_NEXT))
    }

    @Test fun `單行標了不准動作就換行`() {
        val type = InputType.TYPE_CLASS_TEXT
        assertEquals(
            Behavior.NEWLINE,
            kind(type, EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_ENTER_ACTION),
        )
    }

    @Test fun `未指定動作就換行`() {
        val type = InputType.TYPE_CLASS_TEXT
        assertEquals(Behavior.NEWLINE, kind(type, EditorInfo.IME_ACTION_UNSPECIFIED))
        assertEquals(Behavior.NEWLINE, kind(type, EditorInfo.IME_ACTION_NONE))
    }

    @Test fun `TYPE_NULL 送原生 Enter`() {
        assertEquals(Behavior.KEY_EVENT, kind(InputType.TYPE_NULL, EditorInfo.IME_ACTION_NONE))
        assertEquals(Behavior.KEY_EVENT, kind(0, EditorInfo.IME_ACTION_SEND))
    }
}
