package tt.ime.riverine

import org.junit.Assert.assertEquals
import org.junit.Test
import tt.ime.riverine.core.GeminiLive

/** Live STT 純邏輯（語言碼／模型名／砌字）。JSON 要真機／模擬器先有 `org.json`。 */
class GeminiLiveTest {

    @Test
    fun modelNameAddsPrefix() {
        assertEquals("models/gemini-3.8-live-extended-thinking", GeminiLive.modelName(""))
        assertEquals("models/gemini-3.8-live-extended-thinking", GeminiLive.modelName("gemini-3.8-live-extended-thinking"))
        assertEquals("models/foo", GeminiLive.modelName("models/foo"))
    }

    @Test
    fun langCodesUsesBcp47OrFallsBack() {
        assertEquals(listOf("yue-Hant-HK"), GeminiLive.langCodes("yue-Hant-HK", chinese = false))
        assertEquals(listOf("en-US"), GeminiLive.langCodes("en-US", chinese = true))
        assertEquals(listOf("yue-Hant-HK"), GeminiLive.langCodes("廣東話(有機會中英夾雜)", chinese = true))
        assertEquals(listOf("en-US"), GeminiLive.langCodes("English", chinese = false))
    }

    @Test
    fun thinkingLevelFallsBackToLow() {
        assertEquals(GeminiLive.ThinkingLevel.LOW, GeminiLive.ThinkingLevel.fromPref(null))
        assertEquals(GeminiLive.ThinkingLevel.LOW, GeminiLive.ThinkingLevel.fromPref(""))
        assertEquals(GeminiLive.ThinkingLevel.LOW, GeminiLive.ThinkingLevel.fromPref("MINIMAL"))
        assertEquals(GeminiLive.ThinkingLevel.LOW, GeminiLive.ThinkingLevel.fromPref("low"))
        assertEquals(GeminiLive.ThinkingLevel.MEDIUM, GeminiLive.ThinkingLevel.fromPref("medium"))
        assertEquals(GeminiLive.ThinkingLevel.HIGH, GeminiLive.ThinkingLevel.fromPref("HIGH"))
    }

    @Test
    fun mergePrefersCumulativeThenAppends() {
        assertEquals("hello world", GeminiLive.merge("hello", "hello world"))
        assertEquals("你好世界", GeminiLive.merge("你好", "世界"))
        assertEquals("hello world", GeminiLive.merge("hello", "world"))
        assertEquals("hello", GeminiLive.merge("hello", "hello"))
    }
}
