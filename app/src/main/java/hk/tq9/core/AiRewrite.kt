package hk.tq9.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * 用 Gemini 改寫／翻譯揀咗嘅一段字。
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
            done(Result.failure(IllegalStateException("未設定 Gemini API key")))
            return
        }
        val model = Prefs.aiModel(ctx)
        val template = Prefs.aiPrompt(ctx)
        val prompt =
            if (template.contains("%text%")) template.replace("%text%", selected)
            else "$template\n$selected"

        Thread {
            val r = runCatching { call(key, model, prompt) }
            ui.post { done(r) }
        }.start()
    }

    private fun call(key: String, model: String, prompt: String): String {
        val url = URL(
            "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"
        )
        val body = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().apply {
                put("parts", JSONArray().put(JSONObject().put("text", prompt)))
            }))
        }.toString()

        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 45_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("x-goog-api-key", key)
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
            if (code !in 200..299) error(errorMessage(code, text))
            return extract(text).ifBlank { error("Gemini 冇回應內容") }
        } finally {
            conn.disconnect()
        }
    }

    private fun errorMessage(code: Int, body: String): String {
        val msg = runCatching {
            JSONObject(body).optJSONObject("error")?.optString("message")
        }.getOrNull()
        return if (msg.isNullOrBlank()) "HTTP $code" else "HTTP $code：$msg"
    }

    private fun extract(json: String): String {
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
}
