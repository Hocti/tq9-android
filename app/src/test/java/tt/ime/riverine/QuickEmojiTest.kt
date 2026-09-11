package tt.ime.riverine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tt.ime.riverine.core.PadFunc
import tt.ime.riverine.ime.Key
import tt.ime.riverine.ime.KeyAction
import tt.ime.riverine.ime.QuickEmoji
import tt.ime.riverine.ime.toKey

/**
 * 長撳「表情」幾時先至彈速選（`QuickEmoji.appliesTo`）。
 *
 * 盯死嗰件事：**唔可以食咗 user 特登配落去嘅長撳**。呢個規矩兩邊都問同一句
 * （九宮格行 `ChinesePadView.variantsOf`、工具列行 `QuickEmojiPopup.open`），
 * 所以喺呢度試一次就夠。至於彈邊幾個 emoji（`EmojiDict.quick`）要 `Context`，
 * 同 `KeyLayout.load` 一樣唔喺 unit test 度試。
 */
class QuickEmojiTest {

    /** 個位配咗長撳（例如短撳表情、長撳速選字）：長撳照做嗰個功能，唔彈速選 */
    @Test fun `配咗長撳就唔彈`() {
        assertFalse(QuickEmoji.appliesTo(PadFunc.EMOJI.toKey(PadFunc.SHORTCUT)))
    }

    /** 個位個長撳吉住（工具列成行都係咁）：先至彈得出 */
    @Test fun `冇配長撳先彈`() {
        assertTrue(QuickEmoji.appliesTo(PadFunc.EMOJI.toKey()))
        assertTrue(QuickEmoji.appliesTo(PadFunc.EMOJI.toKey(PadFunc.NONE)))
    }

    /** 「表情」擺喺第二粒掣嘅長撳格：嗰粒長撳係開成個表，一樣唔彈速選 */
    @Test fun `擺喺第二粒掣嘅長撳格就唔彈`() {
        assertFalse(QuickEmoji.appliesTo(PadFunc.RELATE.toKey(PadFunc.EMOJI)))
    }

    /** 其餘掣一律唔關事 */
    @Test fun `唔係表情掣就唔彈`() {
        assertFalse(QuickEmoji.appliesTo(PadFunc.PASTE.toKey()))
        assertFalse(QuickEmoji.appliesTo(Key(KeyAction.DIGIT, digit = 5)))
    }
}
