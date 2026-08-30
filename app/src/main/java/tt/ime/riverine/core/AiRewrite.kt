package tt.ime.riverine.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * 用 AI 改寫／翻譯揀咗嘅一段字。預設用 Gemini；[Prefs.KEY_AI_USE_CUSTOM] 開咗就改用
 * 設定頁嗰三個範本（URL／headers／body）打任何接受 JSON 嘅 HTTP POST API，
 * 再用 [Prefs.KEY_AI_RESPONSE_PATH] 喺回應入面搵返個結果（見 [callCustom]）。
 *
 * API key、model、prompt 全部喺設定頁入。prompt 入面嘅 `%text%` 會換成揀咗嗰段字；
 * 如果 prompt 冇寫 `%text%`，就會直接貼喺 prompt 後面。
 */
object AiRewrite {

    private val ui = Handler(Looper.getMainLooper())

    /** [done] 一定喺 main thread 叫；失敗就 `Result.failure` */
    fun rewrite(ctx: Context, selected: String, done: (Result<String>) -> Unit) {
        val key = Prefs.aiApiKey(ctx)
        if (key.isBlank()) {
            done(Result.failure(IllegalStateException("尚未設定 API key")))
            return
        }
        val model = Prefs.aiModel(ctx)
        val template = Prefs.aiPrompt(ctx)
        val prompt =
            if (template.contains("%text%")) template.replace("%text%", selected)
            else "$template\n$selected"

        val useCustom = Prefs.aiUseCustom(ctx)
        val url = Prefs.aiCustomUrl(ctx)
        val headers = Prefs.aiCustomHeaders(ctx)
        val body = Prefs.aiCustomBody(ctx)
        val responsePath = Prefs.aiCustomResponsePath(ctx)

        Thread {
            val r = runCatching {
                if (useCustom) {
                    if (url.isBlank()) error("尚未設定 Request URL")
                    callCustom(key, model, prompt, url, headers, body, responsePath)
                } else {
                    callGemini(key, model, prompt)
                }
            }
            ui.post { done(r) }
        }.start()
    }

    /**
     * 直接叫 Gemini `generateContent`。[audio] 唔係 null 就連埋一段錄音（`inline_data`）
     * 一齊送上去，[audioMime] 係嗰段嘢嘅 MIME type —— `AiStt` 用嚟做語音轉文字。
     *
     * 唔喺呢個 object 之外叫 —— `internal` 純粹係俾同 module 嘅 [AiStt] 用，
     * 唔使將成段 HTTP／錯誤處理／回應拆解抄多次。
     */
    internal fun callGemini(
        key: String, model: String, prompt: String,
        audio: ByteArray? = null, audioMime: String = "audio/wav"
    ): String {
        val url = URL(
            "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"
        )
        val parts = JSONArray().put(JSONObject().put("text", prompt))
        if (audio != null) {
            parts.put(JSONObject().put("inline_data", JSONObject().apply {
                put("mime_type", audioMime)
                put("data", Base64.encodeToString(audio, Base64.NO_WRAP))
            }))
        }
        val body = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().put("parts", parts)))
        }.toString()

        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            // 送成段錄音上去比純文字慢好多，所以呢度放鬆到 90 秒
            readTimeout = if (audio == null) 45_000 else 90_000
            doOutput = true
            // 唔開 chunked mode，HttpURLConnection 為咗計 Content-Length 會喺 memory
            // 度再 buffer 多一份成個 body —— 錄音嗰啲 base64 動輒幾 MB，唔想要多一份
            if (audio != null) setChunkedStreamingMode(0)
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("x-goog-api-key", key)
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
            if (code !in 200..299) error(errorMessage(code, text))
            return extract(text).ifBlank { error("Gemini 沒有回應內容") }
        } finally {
            conn.disconnect()
        }
    }

    internal fun errorMessage(code: Int, body: String): String {
        val msg = runCatching {
            JSONObject(body).optJSONObject("error")?.optString("message")
        }.getOrNull()
        return if (msg.isNullOrBlank()) "HTTP $code" else "HTTP $code：$msg"
    }

    internal fun extract(json: String): String {
        val parts = JSONObject(json)
            .optJSONArray("candidates")?.optJSONObject(0)
            ?.optJSONObject("content")?.optJSONArray("parts")
            ?: return ""
        return buildString {
            for (i in 0 until parts.length()) {
                append(parts.optJSONObject(i)?.optString("text").orEmpty())
            }
        }.trim()
    }

    /**
     * Gemini 以外嘅簡單自訂 API：[url]／[headers]／[body] 三個範本入面
     * `%key%`／`%model%`／`%prompt%` 會分別換成 API key、模型名稱、
     * 已套用 prompt 範本嘅內容（落 body 之前先做 JSON escape，等 user 淨係要喺
     * 範本入面自己加返頭尾嘅引號，例如 `"content":"%prompt%"`）。
     * 回應係 JSON，用 [responsePath]（例如 `choices.0.message.content`）逐層行落去攞結果。
     */
    private fun callCustom(
        key: String, model: String, prompt: String,
        url: String, headers: String, body: String, responsePath: String
    ): String {
        val resolvedUrl = url.replace("%model%", model).replace("%key%", key)
        val resolvedBody = body
            .replace("%prompt%", jsonEscape(prompt))
            .replace("%model%", jsonEscape(model))
            .replace("%key%", jsonEscape(key))
        runCatching { JSONTokener(resolvedBody).nextValue() }
            .onFailure { error("Request Body 範本不是合法的 JSON：${it.message}") }

        val conn = (URL(resolvedUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 45_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        headers.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { line ->
                val i = line.indexOf(':')
                if (i > 0) {
                    val name = line.take(i).trim()
                    val value = line.substring(i + 1).trim()
                        .replace("%key%", key).replace("%model%", model)
                    conn.setRequestProperty(name, value)
                }
            }
        try {
            conn.outputStream.use { it.write(resolvedBody.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
            if (code !in 200..299) error(errorMessage(code, text))
            return extractByPath(text, responsePath)
                .ifBlank { error("回應中找不到內容（路徑：$responsePath）") }
        } finally {
            conn.disconnect()
        }
    }

    /** 將 [s] 做 JSON string escape，但唔連頭尾引號（範本自己負責加） */
    private fun jsonEscape(s: String): String =
        JSONObject.quote(s).let { it.substring(1, it.length - 1) }

    /** 沿住 `a.0.b` 咁樣嘅路徑喺 JSON 度逐層搵落去；數字當陣列 index，其他當 object key */
    private fun extractByPath(json: String, path: String): String {
        var current: Any? = runCatching { JSONTokener(json).nextValue() }.getOrNull() ?: return ""
        for (seg in path.split(".").filter { it.isNotBlank() }) {
            current = when (val c = current) {
                is JSONObject -> c.opt(seg)
                is JSONArray -> seg.toIntOrNull()?.let { c.opt(it) }
                else -> null
            } ?: return ""
        }
        return current.toString()
    }
}
