package tt.ime.riverine

import tt.ime.riverine.core.AutoCaps
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 英文鍵盤句首自動大階（`AutoCaps.atSentenceStart`）。
 *
 * 盯死兩件事：
 *
 *  1. `.` **一定要跟着空格**先算句尾 —— 唔係打 `google.com` 就會變 `google.Com`。
 *     `!` `?` 同全形 `。！？` 就唔使（網址／小數入面唔會出現）。
 *  2. 欄位開頭、換行都算句首。
 */
class AutoCapsTest {

    @Test fun `欄位開頭算句首`() {
        assertTrue(AutoCaps.atSentenceStart(""))
        assertTrue(AutoCaps.atSentenceStart("   "))
    }

    @Test fun `換行之後算句首`() {
        assertTrue(AutoCaps.atSentenceStart("hello\n"))
        assertTrue(AutoCaps.atSentenceStart("hello\n  "))
    }

    @Test fun `問號驚嘆號唔使隔空格`() {
        assertTrue(AutoCaps.atSentenceStart("really?"))
        assertTrue(AutoCaps.atSentenceStart("really? "))
        assertTrue(AutoCaps.atSentenceStart("wow!"))
        assertTrue(AutoCaps.atSentenceStart("點呀？"))
        assertTrue(AutoCaps.atSentenceStart("食咗飯。"))
    }

    @Test fun `句號一定要跟住空格`() {
        assertTrue(AutoCaps.atSentenceStart("Hello. "))
        assertTrue(AutoCaps.atSentenceStart("Hello.  "))
        // 打緊 google.com、3.14、file.txt —— 呢個時候唔可以自動大階
        assertFalse(AutoCaps.atSentenceStart("google."))
        assertFalse(AutoCaps.atSentenceStart("3."))
    }

    @Test fun `句子中間唔算`() {
        assertFalse(AutoCaps.atSentenceStart("hello"))
        assertFalse(AutoCaps.atSentenceStart("hello "))
        assertFalse(AutoCaps.atSentenceStart("hello, "))
        assertFalse(AutoCaps.atSentenceStart("1234"))
    }

    @Test fun `收結引號括號唔阻住`() {
        assertTrue(AutoCaps.atSentenceStart("he said \"ok!\" "))
        assertTrue(AutoCaps.atSentenceStart("（好。）"))
        assertFalse(AutoCaps.atSentenceStart("(ok) "))
    }
}
