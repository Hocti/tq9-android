package tt.ime.riverine.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 用 AI 改寫／翻譯揀咗嘅一段字。用 [AiSlot.REWRITE] 嗰套 provider 設定：預設 Gemini；
 * 開咗「自訂 API」就改用設定頁嗰三個範本（URL／headers／body）打任何 HTTP POST API，
 * 再用回應路徑喺 JSON 入面搵返個結果（見 [callCustom]）。
 *
 * API key、model、prompt 全部喺設定頁入。prompt 入面嘅 `%text%` 會換成揀咗嗰段字；
 * 如果 prompt 冇寫 `%text%`，就會直接貼喺 prompt 後面。
 */
object AiRewrite {

    private val ui = Handler(Looper.getMainLooper())

    /** 送上自訂 API 嘅一段錄音（`AiStt` 用）。[fileName] 係 multipart 嗰個檔名，Whisper 靠副檔名認格式 */
    class Audio(val bytes: ByteArray, val mime: String, val fileName: String)

    /**
     * [template] 係要用邊個 prompt 範本（`%text%` 會換成 [selected]）——
     * 唔畀就用返名單第一個（＝短撳工具列粒「AI改」嗰個，見 [Prefs.aiPrompt]）；
     * 長撳粒掣揀第二個 prompt 嗰陣就由 caller 傳入。
     *
     * [done] 一定喺 main thread 叫；失敗就 `Result.failure`。
     */
    fun rewrite(
        ctx: Context,
        selected: String,
        template: String = Prefs.aiPrompt(ctx),
        done: (Result<String>) -> Unit
    ) {
        val p = Prefs.aiProvider(ctx, AiSlot.REWRITE)
        if (p.key.isBlank()) {
            done(Result.failure(IllegalStateException("尚未設定 API key")))
            return
        }
        val prompt =
            if (template.contains("%text%")) template.replace("%text%", selected)
            else "$template\n$selected"

        Thread {
            val r = runCatching {
                if (p.useCustom) callCustom(p, prompt, mapOf("text" to selected))
                else callGemini(p.key, p.model, prompt)
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
        audio: ByteArray? = null, audioMime: String = "audio/aac"
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
     * Gemini 以外嘅簡單自訂 API。[AiProvider.url]／[AiProvider.headers]／[AiProvider.body]
     * 三個範本入面 `%key%`／`%model%`／`%prompt%`（已套用 prompt 範本嘅內容）
     * 同 [vars] 入面嘅嘢（例如 `%text%`、`%lang%`）會換成實際值（見 [AiTemplate.fill]）。
     *
     * Body 兩種格式：
     *
     *  - **JSON**：落 body 之前先做 JSON escape，user 淨係要喺範本自己加返頭尾引號，
     *    例如 `"content":"%prompt%"`。有 [audio] 嘅話 `%audio%` = base64、
     *    `%audio_mime%` = MIME type。
     *  - **multipart/form-data**（[AiProvider.multipart]）：每行一個 `名稱=值`
     *    （見 [AiTemplate.parseFormFields]），值**淨係得** `%audio%` 嗰行就係段錄音個檔案 ——
     *    Whisper 嗰類要上傳檔案嘅 API 就係咁收。
     *
     * 回應係 JSON，用 [AiProvider.responsePath]（例如 `choices.0.message.content`）逐層行落去攞結果。
     */
    internal fun callCustom(
        p: AiProvider, prompt: String,
        vars: Map<String, String> = emptyMap(), audio: Audio? = null
    ): String {
        if (p.url.isBlank()) error("尚未設定 Request URL")
        val plain = vars + mapOf(
            "key" to p.key, "model" to p.model, "prompt" to prompt,
            "audio_mime" to (audio?.mime ?: "")
        )
        val resolvedUrl = AiTemplate.fill(p.url, mapOf("key" to p.key, "model" to p.model))

        val payload: ByteArray
        val contentType: String
        if (p.multipart) {
            val boundary = "----tt" + System.nanoTime().toString(16)
            payload = multipartBody(p.body, plain, audio, boundary)
            contentType = "multipart/form-data; boundary=$boundary"
        } else {
            val escaped = plain.mapValues { jsonEscape(it.value) } +
                ("audio" to (audio?.let { Base64.encodeToString(it.bytes, Base64.NO_WRAP) } ?: ""))
            // 驗 JSON 嗰陣唔好連幾 MB base64 都 parse 一次，擺個空字串落去驗就夠
            runCatching { JSONTokener(AiTemplate.fill(p.body, escaped + ("audio" to ""))).nextValue() }
                .onFailure { error("Request Body 範本不是合法的 JSON：${it.message}") }
            payload = AiTemplate.fill(p.body, escaped).toByteArray(Charsets.UTF_8)
            contentType = "application/json; charset=utf-8"
        }

        val conn = (URL(resolvedUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = if (audio == null) 45_000 else 90_000
            doOutput = true
            if (audio != null) setChunkedStreamingMode(0)
        }
        headers(p.headers).forEach { (name, value) ->
            conn.setRequestProperty(name, AiTemplate.fill(value, mapOf("key" to p.key, "model" to p.model)))
        }
        // 放喺 user 嗰啲 header 之後：multipart 個 boundary 一定要係我哋砌嗰個
        conn.setRequestProperty("Content-Type", contentType)
        try {
            conn.outputStream.use { it.write(payload) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
            if (code !in 200..299) error(errorMessage(code, text))
            return extractByPath(text, p.responsePath)
                .ifBlank { error("回應中找不到內容（路徑：${p.responsePath}）") }
        } finally {
            conn.disconnect()
        }
    }

    private fun headers(template: String): List<Pair<String, String>> =
        template.lineSequence()
            .map { it.trim() }
            .mapNotNull { line ->
                val i = line.indexOf(':')
                if (i > 0) line.take(i).trim() to line.substring(i + 1).trim() else null
            }
            .toList()

    private fun multipartBody(
        template: String, vars: Map<String, String>, audio: Audio?, boundary: String
    ): ByteArray {
        val fields = AiTemplate.parseFormFields(template)
            ?: error("multipart Body 每行要寫成「名稱=值」")
        val out = ByteArrayOutputStream((audio?.bytes?.size ?: 0) + 1024)
        fun w(s: String) = out.write(s.toByteArray(Charsets.UTF_8))
        for ((name, value) in fields) {
            w("--$boundary\r\n")
            if (value.trim() == "%audio%") {
                if (audio == null) error("這個功能沒有音訊可以放入 %audio%")
                w("Content-Disposition: form-data; name=\"$name\"; filename=\"${audio.fileName}\"\r\n")
                w("Content-Type: ${audio.mime}\r\n\r\n")
                out.write(audio.bytes)
                w("\r\n")
            } else {
                w("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
                w(AiTemplate.fill(value, vars))
                w("\r\n")
            }
        }
        w("--$boundary--\r\n")
        return out.toByteArray()
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

/** 自訂 API 範本嘅純字串處理（冇 `android.*`，JVM unit test 行得） */
internal object AiTemplate {

    private val PLACEHOLDER = Regex("%([a-z_]+)%")

    /**
     * `%name%` 換成 [vars] 入面嘅值，**一次過掃一轉**：換入去嘅內容（例如 prompt
     * 入面啱啱有 `%model%` 呢串字）唔會再俾人換多次。唔識嘅 placeholder 原封不動。
     */
    fun fill(template: String, vars: Map<String, String>): String =
        PLACEHOLDER.replace(template) { m -> vars[m.groupValues[1]] ?: m.value }

    /**
     * multipart body 範本：每行一個 `名稱=值`（只喺第一個 `=` 度切），空行跳過。
     * 有一行冇 `=`／名稱空白就回 null。**要喺換 placeholder 之前拆行** ——
     * 換入去嘅 prompt 本身可以有換行。
     */
    fun parseFormFields(template: String): List<Pair<String, String>>? {
        val out = ArrayList<Pair<String, String>>()
        for (raw in template.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val i = line.indexOf('=')
            if (i <= 0) return null
            out.add(line.take(i).trim() to line.substring(i + 1).trim())
        }
        return out
    }
}
