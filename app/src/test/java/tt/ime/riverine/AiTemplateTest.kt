package tt.ime.riverine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tt.ime.riverine.core.AiTemplate
import tt.ime.riverine.core.Prefs

/** 自訂 API 範本（`AiRewrite.callCustom`）嘅 placeholder 同 multipart 拆行 */
class AiTemplateTest {

    @Test
    fun fillReplacesKnownAndKeepsUnknown() {
        assertEquals("a=1 b=%b%", AiTemplate.fill("a=%a% b=%b%", mapOf("a" to "1")))
    }

    /** 換入去嘅值本身有 `%model%` 都唔可以再俾人換一次 */
    @Test
    fun fillIsSinglePass() {
        val out = AiTemplate.fill("%prompt%|%model%", mapOf("prompt" to "say %model%", "model" to "m"))
        assertEquals("say %model%|m", out)
    }

    @Test
    fun whisperDefaultBodyParses() {
        val fields = AiTemplate.parseFormFields(Prefs.DEFAULT_AI_STT_BODY)!!
        assertEquals(
            listOf("file" to "%audio%", "model" to "%model%", "prompt" to "%text%",
                "response_format" to "json"),
            fields
        )
    }

    @Test
    fun formFieldsSplitOnFirstEqualsAndSkipBlankLines() {
        assertEquals(listOf("q" to "a=b"), AiTemplate.parseFormFields("\n  q = a=b \n\n"))
    }

    @Test
    fun formFieldsRejectLineWithoutName() {
        assertNull(AiTemplate.parseFormFields("file=%audio%\njunk"))
        assertNull(AiTemplate.parseFormFields("=x"))
    }
}
