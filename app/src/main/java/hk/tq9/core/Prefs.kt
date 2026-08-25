package hk.tq9.core

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/** 顯示方式：中文輸入本體超過 max size 之後可以 toggle 的狀態 */
enum class PadAlign(val label: String) {
    STRETCH("拉闊"),
    LEFT_GAP("靠右（左邊留白）"),
    RIGHT_GAP("靠左（右邊留白）");

    fun next(): PadAlign = entries[(ordinal + 1) % entries.size]
}

/** 上面條 bar 嘅三段：關 → 候選字 → 工具 */
enum class BarMode(val label: String) {
    OFF("關閉"),
    CANDIDATES("候選字"),
    TOOLS("工具");

    fun next(): BarMode = entries[(ordinal + 1) % entries.size]
}

/**
 * 選字夠兩頁嗰陣，底行兩格闊嗰粒 `0` 點排（設定頁揀，預設 [WIDE_NEXT]）。
 *
 * [PREV_NEXT] / [NEXT_PREV] 都係拆做兩粒正常闊，淨係左右調轉；
 * [WIDE_NEXT] 唔拆，成兩格闊嗰粒直接做「下頁」，返上一頁改為**長撳**佢
 * （所以嗰個狀態下長撳嘅「成對標點」冇咗，個位讓咗俾「上頁」——
 * 見 `ChinesePadView.drawDigit` 同 `TQ9InputMethodService.onLongPress`）。
 */
enum class PagerLayout(val label: String) {
    PREV_NEXT("拆兩粒：上頁、下頁"),
    NEXT_PREV("拆兩粒：下頁、上頁"),
    WIDE_NEXT("大格「下頁」（長按 = 上頁）");
}

/**
 * 可以喺設定度換走嘅鍵功能（而家用喺左上角嗰粒）。
 * 除咗 [EMOJI]，粒面全部改返寫中文，唔再用意義不明嘅 icon。
 *
 * 設定頁而家用 spinner 揀，[next] / [nextFor] 淨係留返俾舊 code path 用。
 */
enum class PadFunc(val label: String, val icon: String) {
    SHORTCUT("速選字", "速選"),
    SC_TOGGLE("簡體開關", "简"),
    EMOJI("表情符號", "😀"),
    PASTE("貼上", "貼上"),
    STT("語音輸入", "錄音"),
    AI("AI 改寫", "AI改"),
    NONE("停用", "");

    fun next(): PadFunc = entries[(ordinal + 1) % entries.size]

    companion object {
        /**
         * 揀下一個功能，但係跳過唔准揀嗰啲。
         * 短撳唔可以係「無效」（粒掣撳落去乜都唔做冇道理），
         * 而且短撳同長撳唔可以做同一件事（咁樣就嘥咗一格）。
         */
        fun nextFor(cur: PadFunc, other: PadFunc, allowNone: Boolean): PadFunc {
            var f = cur.next()
            var guard = entries.size
            while (guard-- > 0 && (f == other || (!allowNone && f == NONE))) f = f.next()
            return f
        }
    }
}

object Prefs {

    const val FILE = "tq9_settings"

    // size / layout
    const val KEY_SCALE = "key_scale"              // 0.6 ~ 1.4
    const val KEY_MAX_W_DP = "key_max_w_dp"        // 中文本體最大闊度
    const val KEY_MAX_H_DP = "key_max_h_dp"        // 中文本體最大高度
    const val KEY_ALIGN = "key_align"              // PadAlign.name
    const val KEY_HEIGHT_SCALE = "key_height_scale" // 成個鍵盤高度倍數（拉高／拉低）
    const val KEY_WIDTH_SCALE = "key_width_scale"  // 中文本體闊度倍數（靠左／靠右嗰陣左右拉）
    const val KEY_H_RATIO = "key_h_ratio"          // 中文格仔高度 / 闊度
    const val KEY_GAP_DP = "key_gap_dp"
    const val KEY_FONT_SCALE = "key_font_scale"

    // behaviour
    const val KEY_SC_OUTPUT = "sc_output"          // 輸出簡體
    const val KEY_BAR_MODE = "bar_mode"            // BarMode.name
    const val KEY_SWIPE = "swipe_enabled"
    const val KEY_SWIPE_DWELL = "swipe_dwell_ms"
    const val KEY_SWIPE_ANGLE = "swipe_angle_deg"
    const val KEY_VIBRATE = "vibrate"
    /** 震動強度 0～3（0 = 冇震）。舊版嗰個 boolean [KEY_VIBRATE] 照留返做 migration */
    const val KEY_VIBRATE_LEVEL = "vibrate_level"
    const val KEY_SOUND = "sound"
    const val KEY_LONG_PRESS_MS = "long_press_ms"
    const val KEY_STT_LOCALE = "stt_locale"
    const val KEY_DB_LABEL = "db_label"
    const val KEY_LATIN_NUM_ROW = "latin_num_row"  // 英文鍵盤上面加一行數字
    const val KEY_USAGE_REORDER = "usage_reorder"  // 打得多嘅字推前（usage_stats.db）
    const val KEY_PAGER_LAYOUT = "pager_layout"    // PagerLayout.name（選字揭頁嗰兩粒點排）

    // 左上角嗰粒鍵
    const val KEY_TL_TAP = "topleft_tap"
    const val KEY_TL_LONG = "topleft_long"

    // AI
    const val KEY_AI_KEY = "ai_api_key"
    const val KEY_AI_MODEL = "ai_model"
    const val KEY_AI_PROMPT = "ai_prompt"

    const val DEFAULT_AI_MODEL = "gemini-3.7-flash"
    const val DEFAULT_AI_PROMPT =
        "Rewrite the following text in natural B2-C1 level English " +
        "(if it is already English, just fix the grammar). " +
        "Output ONLY the rewritten text itself - no preamble, no explanation, " +
        "no quotation marks, no comments, nothing else.\n\n%text%"

    /**
     * 自訂 API（Gemini 以外嘅簡單 provider）。預設關閉 = 用返 Gemini。
     * 開咗之後改用 [KEY_AI_URL] / [KEY_AI_HEADERS] / [KEY_AI_BODY] 三個範本打 HTTP POST，
     * 再用 [KEY_AI_RESPONSE_PATH] 喺 JSON 回應入面搵返改寫完嘅字（見 `AiRewrite.callCustom`）。
     * 三個範本入面 `%key%`＝API key、`%model%`＝模型名稱、`%prompt%`＝套用咗
     * [KEY_AI_PROMPT] 之後嘅內容（落 body 範本時已經自動做咗 JSON escape）。
     * 預設值係 OpenAI 相容嘅 chat completions 格式，OpenAI、Groq、DeepSeek、
     * OpenRouter、Ollama 呢類大多數都啱用，唔啱就照住實際 API 文件改就得。
     */
    const val KEY_AI_USE_CUSTOM = "ai_use_custom"
    const val KEY_AI_URL = "ai_custom_url"
    const val KEY_AI_HEADERS = "ai_custom_headers"
    const val KEY_AI_BODY = "ai_custom_body"
    const val KEY_AI_RESPONSE_PATH = "ai_custom_response_path"

    const val DEFAULT_AI_URL = "https://api.openai.com/v1/chat/completions"
    const val DEFAULT_AI_HEADERS = "Authorization: Bearer %key%"
    const val DEFAULT_AI_BODY =
        "{\"model\":\"%model%\",\"messages\":[{\"role\":\"user\",\"content\":\"%prompt%\"}]}"
    const val DEFAULT_AI_RESPONSE_PATH = "choices.0.message.content"

    /**
     * AI 改寫（✨）成個功能嘅總開關。熄咗就算入咗 API key，工具列都唔會出粒 ✨
     * （見 `TQ9InputMethodService.applyAiState`）。
     */
    const val KEY_AI_REWRITE_ON = "ai_rewrite_on"

    /**
     * 用 AI 做語音輸入（取代系統嗰個 `SpeechRecognizer`）。
     * **淨係 Gemini 做得**（要送成段錄音上去），所以 [KEY_AI_USE_CUSTOM] 開咗就唔准開。
     * 見 `AiStt` 同 `TQ9InputMethodService.startAiStt`。
     */
    const val KEY_AI_STT_ON = "ai_stt_on"
    const val KEY_AI_STT_PROMPT = "ai_stt_prompt"

    /**
     * STT 個 prompt 寫到咁死板係有原因嘅：Gemini 好鍾意喺結果前面加句
     * 「以下是錄音的轉錄內容：」，又鍾意自動幫你執順啲句子。呢兩樣落到輸入框
     * 都係垃圾，所以逐條寫死唔准做乜。`%text%` 會換成輸入框而家嘅內容（上下文）。
     */
    const val DEFAULT_AI_STT_PROMPT =
        "You are a speech-to-text transcription engine. Transcribe the attached audio " +
        "recording into Traditional Chinese (Hong Kong usage).\n" +
        "\n" +
        "Rules, all mandatory:\n" +
        "1. Output ONLY the transcription itself. No preamble, no closing remark, " +
        "no explanation, no apology, no quotation marks, no markdown, and no label " +
        "such as \"Transcription:\".\n" +
        "2. Transcribe verbatim. Do NOT translate, paraphrase, summarise, reorder, " +
        "shorten, expand, or \"improve\" the wording in any way.\n" +
        "3. The ONLY corrections allowed are removing obvious stutters, repeated " +
        "false starts, and filler sounds.\n" +
        "4. Keep the speaker's own words, including English words, numbers, slang and " +
        "proper nouns, exactly as spoken.\n" +
        "5. Use Traditional Chinese characters only, never Simplified. Add natural " +
        "punctuation.\n" +
        "6. If the audio contains no intelligible speech, output nothing at all.\n" +
        "\n" +
        "The text below is what is already typed in the input field. It is context " +
        "only - do NOT repeat it, translate it, or include any part of it in your " +
        "output:\n" +
        "%text%"

    /** 成套 AI 設定（provider／key／model／prompt／自訂範本）打包做一個 profile，存喺呢個 key 底下嘅一個 JSON object */
    const val KEY_AI_PROFILES = "ai_profiles"

    // 內部 state（唔喺設定頁出現）
    const val KEY_CLIP_HISTORY = "clip_history"
    const val KEY_EMOJI_RECENT = "emoji_recent"

    fun sp(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // ---- typed accessors -------------------------------------------------

    fun keyScale(ctx: Context) = sp(ctx).getFloat(KEY_SCALE, 1.0f)
    fun maxWidthDp(ctx: Context) = sp(ctx).getInt(KEY_MAX_W_DP, 460)
    fun maxHeightDp(ctx: Context) = sp(ctx).getInt(KEY_MAX_H_DP, 300)
    /** 正方形會太高，預設矮 20% */
    fun keyHeightRatio(ctx: Context) = sp(ctx).getFloat(KEY_H_RATIO, 0.8f)
    fun gapDp(ctx: Context) = sp(ctx).getInt(KEY_GAP_DP, 2)
    fun fontScale(ctx: Context) = sp(ctx).getFloat(KEY_FONT_SCALE, 1.0f)

    /** 拉高／拉低成個鍵盤 */
    fun heightScale(ctx: Context) = sp(ctx).getFloat(KEY_HEIGHT_SCALE, 1.0f)
    fun setHeightScale(ctx: Context, v: Float) =
        sp(ctx).edit().putFloat(KEY_HEIGHT_SCALE, v.coerceIn(MIN_HEIGHT_SCALE, MAX_HEIGHT_SCALE)).apply()

    const val MIN_HEIGHT_SCALE = 0.6f
    const val MAX_HEIGHT_SCALE = 1.8f

    /**
     * 中文本體闊度倍數。淨係 [PadAlign.LEFT_GAP] / [PadAlign.RIGHT_GAP] 有用
     * （[PadAlign.STRETCH] 本來就用盡成行），喺工具 bar 最左嗰粒掣左右拖就改到。
     */
    fun widthScale(ctx: Context) = sp(ctx).getFloat(KEY_WIDTH_SCALE, 1.0f)
    fun setWidthScale(ctx: Context, v: Float) =
        sp(ctx).edit().putFloat(KEY_WIDTH_SCALE, v.coerceIn(MIN_WIDTH_SCALE, MAX_WIDTH_SCALE)).apply()

    const val MIN_WIDTH_SCALE = 0.45f
    const val MAX_WIDTH_SCALE = 1.6f

    /**
     * 中文本體窄到淨低咁多位（佔螢幕嘅比例以下）就唔好再喺上面擺條 bar ——
     * 空出嚟嗰邊夠位擺得晒功能掣同候選字，見 `TQ9InputMethodService.sidePanelActive`。
     */
    const val SIDE_PANEL_MAX_RATIO = 0.60f

    fun align(ctx: Context): PadAlign =
        runCatching { PadAlign.valueOf(sp(ctx).getString(KEY_ALIGN, PadAlign.STRETCH.name)!!) }
            .getOrDefault(PadAlign.STRETCH)

    fun setAlign(ctx: Context, a: PadAlign) =
        sp(ctx).edit().putString(KEY_ALIGN, a.name).apply()

    fun scOutput(ctx: Context) = sp(ctx).getBoolean(KEY_SC_OUTPUT, false)
    fun setScOutput(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean(KEY_SC_OUTPUT, v).apply()

    fun barMode(ctx: Context): BarMode =
        runCatching { BarMode.valueOf(sp(ctx).getString(KEY_BAR_MODE, BarMode.CANDIDATES.name)!!) }
            .getOrDefault(BarMode.CANDIDATES)

    fun setBarMode(ctx: Context, m: BarMode) =
        sp(ctx).edit().putString(KEY_BAR_MODE, m.name).apply()

    /**
     * 英文鍵盤上面永遠有一行數字。設定頁嗰個開關收埋咗（見
     * `SettingsActivity.SHOW_HIDDEN_OPTIONS`），個 pref 本身冇刪，
     * 想再開返出嚟就將 [FORCE_LATIN_NUM_ROW] 改做 false。
     */
    const val FORCE_LATIN_NUM_ROW = true

    fun latinNumberRow(ctx: Context) =
        if (FORCE_LATIN_NUM_ROW) true else sp(ctx).getBoolean(KEY_LATIN_NUM_ROW, false)

    /** 短撳唔准係「無效」，舊設定入面有就當返預設 */
    fun topLeftTap(ctx: Context): PadFunc {
        val f = func(ctx, KEY_TL_TAP, PadFunc.SHORTCUT)
        return if (f == PadFunc.NONE) PadFunc.SHORTCUT else f
    }

    /** 長撳唔准同短撳撞 */
    fun topLeftLong(ctx: Context): PadFunc {
        val f = func(ctx, KEY_TL_LONG, PadFunc.SC_TOGGLE)
        return if (f == topLeftTap(ctx)) PadFunc.NONE else f
    }
    fun setFunc(ctx: Context, key: String, f: PadFunc) =
        sp(ctx).edit().putString(key, f.name).apply()

    private fun func(ctx: Context, key: String, def: PadFunc): PadFunc =
        runCatching { PadFunc.valueOf(sp(ctx).getString(key, def.name)!!) }.getOrDefault(def)

    /**
     * 候選字要唔要按打過幾多次推前（見 `Q9Engine.reorderByUsage`）。
     * 預設開住；熄咗就完全跟返字碼表本身嘅次序，但 `UsageStats` 照樣繼續記數。
     */
    fun usageReorder(ctx: Context) = sp(ctx).getBoolean(KEY_USAGE_REORDER, true)

    fun swipeEnabled(ctx: Context) = sp(ctx).getBoolean(KEY_SWIPE, true)
    fun swipeDwellMs(ctx: Context) = sp(ctx).getInt(KEY_SWIPE_DWELL, 150).toLong()
    fun swipeAngleDeg(ctx: Context) = sp(ctx).getInt(KEY_SWIPE_ANGLE, 55).toFloat()

    /**
     * 震動強度 0～3：0 = 完全冇震，1 = 以前唯一嗰個力度（最細），2／3 逐級大力啲。
     * 舊版淨係得個 boolean，未寫過新 key 就由 [KEY_VIBRATE] 轉返過嚟（開 = 1、閂 = 0）。
     */
    fun vibrateLevel(ctx: Context): Int {
        val sp = sp(ctx)
        if (!sp.contains(KEY_VIBRATE_LEVEL)) return if (sp.getBoolean(KEY_VIBRATE, true)) 1 else 0
        return sp.getInt(KEY_VIBRATE_LEVEL, 1).coerceIn(0, MAX_VIBRATE_LEVEL)
    }

    /** 順手寫返個舊 boolean，萬一有邊度仲讀緊佢都唔會同新設定唔夾 */
    fun setVibrateLevel(ctx: Context, level: Int) {
        val v = level.coerceIn(0, MAX_VIBRATE_LEVEL)
        sp(ctx).edit().putInt(KEY_VIBRATE_LEVEL, v).putBoolean(KEY_VIBRATE, v > 0).apply()
    }

    const val MAX_VIBRATE_LEVEL = 3

    /** 每級震幾耐（index = level，0 = 唔震） */
    fun vibrateDurationMs(level: Int): Long =
        longArrayOf(0L, 12L, 18L, 26L)[level.coerceIn(0, MAX_VIBRATE_LEVEL)]

    /** 每級幾大力（1～255，部機唔支援自訂震幅就用返 DEFAULT_AMPLITUDE） */
    fun vibrateAmplitude(level: Int): Int =
        intArrayOf(0, 40, 110, 200)[level.coerceIn(0, MAX_VIBRATE_LEVEL)]

    fun vibrateLevelLabel(level: Int): String =
        arrayOf("關閉", "1（最輕）", "2（中）", "3（最強）")[level.coerceIn(0, MAX_VIBRATE_LEVEL)]

    fun sound(ctx: Context) = sp(ctx).getBoolean(KEY_SOUND, false)
    fun longPressMs(ctx: Context) = sp(ctx).getInt(KEY_LONG_PRESS_MS, 380).toLong()

    /** 選字揭頁嗰兩粒點排（見 [PagerLayout]） */
    fun pagerLayout(ctx: Context): PagerLayout =
        runCatching { PagerLayout.valueOf(sp(ctx).getString(KEY_PAGER_LAYOUT, PagerLayout.WIDE_NEXT.name)!!) }
            .getOrDefault(PagerLayout.WIDE_NEXT)

    fun setPagerLayout(ctx: Context, p: PagerLayout) =
        sp(ctx).edit().putString(KEY_PAGER_LAYOUT, p.name).apply()
    fun sttLocale(ctx: Context): String = sp(ctx).getString(KEY_STT_LOCALE, "yue-Hant-HK")!!

    fun aiApiKey(ctx: Context): String = sp(ctx).getString(KEY_AI_KEY, "")!!
    fun aiModel(ctx: Context): String =
        sp(ctx).getString(KEY_AI_MODEL, DEFAULT_AI_MODEL)!!.ifBlank { DEFAULT_AI_MODEL }
    fun aiPrompt(ctx: Context): String =
        sp(ctx).getString(KEY_AI_PROMPT, DEFAULT_AI_PROMPT)!!.ifBlank { DEFAULT_AI_PROMPT }

    fun aiUseCustom(ctx: Context) = sp(ctx).getBoolean(KEY_AI_USE_CUSTOM, false)

    /** ✨ 改寫功能開唔開（熄咗連粒掣都唔出） */
    fun aiRewriteOn(ctx: Context) = sp(ctx).getBoolean(KEY_AI_REWRITE_ON, true)

    /**
     * AI 語音輸入開唔開。**自訂 API 一律當閂咗**——送錄音上去嗰段係
     * Gemini 專用格式（`inline_data`），自訂範本冇得表達，所以就算個 pref
     * 之前開過，切咗去自訂 API 都要跌返落系統內置嗰個 STT。
     */
    fun aiSttOn(ctx: Context) =
        sp(ctx).getBoolean(KEY_AI_STT_ON, false) && !aiUseCustom(ctx)

    fun aiSttPrompt(ctx: Context): String =
        sp(ctx).getString(KEY_AI_STT_PROMPT, DEFAULT_AI_STT_PROMPT)!!.ifBlank { DEFAULT_AI_STT_PROMPT }
    fun aiCustomUrl(ctx: Context): String =
        sp(ctx).getString(KEY_AI_URL, DEFAULT_AI_URL)!!.ifBlank { DEFAULT_AI_URL }
    fun aiCustomHeaders(ctx: Context): String =
        sp(ctx).getString(KEY_AI_HEADERS, DEFAULT_AI_HEADERS)!!
    fun aiCustomBody(ctx: Context): String =
        sp(ctx).getString(KEY_AI_BODY, DEFAULT_AI_BODY)!!.ifBlank { DEFAULT_AI_BODY }
    fun aiCustomResponsePath(ctx: Context): String =
        sp(ctx).getString(KEY_AI_RESPONSE_PATH, DEFAULT_AI_RESPONSE_PATH)!!.ifBlank { DEFAULT_AI_RESPONSE_PATH }

    fun dbLabel(ctx: Context): String = sp(ctx).getString(KEY_DB_LABEL, "內置 dataset.db")!!
    fun setDbLabel(ctx: Context, v: String) = sp(ctx).edit().putString(KEY_DB_LABEL, v).apply()

    // ---- AI profiles：save/load/delete 成套 AI 設定 -----------------------

    private fun aiProfilesJson(ctx: Context): JSONObject =
        runCatching { JSONObject(sp(ctx).getString(KEY_AI_PROFILES, "{}")!!) }
            .getOrDefault(JSONObject())

    fun aiProfileNames(ctx: Context): List<String> =
        aiProfilesJson(ctx).keys().asSequence().toList().sorted()

    /** 將而家用緊嗰套 AI 設定存做一個叫 [name] 嘅 profile（同名就覆蓋） */
    fun saveAiProfile(ctx: Context, name: String) {
        val profiles = aiProfilesJson(ctx)
        val p = JSONObject().apply {
            put("useCustom", aiUseCustom(ctx))
            put("key", aiApiKey(ctx))
            put("model", aiModel(ctx))
            put("prompt", aiPrompt(ctx))
            put("url", aiCustomUrl(ctx))
            put("headers", aiCustomHeaders(ctx))
            put("body", aiCustomBody(ctx))
            put("responsePath", aiCustomResponsePath(ctx))
            put("rewriteOn", aiRewriteOn(ctx))
            // 唔用 aiSttOn()：嗰個會俾「自訂 API」壓成 false，存 profile 要存返個原本設定
            put("sttOn", sp(ctx).getBoolean(KEY_AI_STT_ON, false))
            put("sttPrompt", aiSttPrompt(ctx))
        }
        profiles.put(name, p)
        sp(ctx).edit().putString(KEY_AI_PROFILES, profiles.toString()).apply()
    }

    /** 將叫 [name] 嘅 profile 讀返做而家用緊嗰套 AI 設定；搵唔到就乜都唔做，返 false */
    fun loadAiProfile(ctx: Context, name: String): Boolean {
        val p = aiProfilesJson(ctx).optJSONObject(name) ?: return false
        sp(ctx).edit()
            .putBoolean(KEY_AI_USE_CUSTOM, p.optBoolean("useCustom", false))
            .putString(KEY_AI_KEY, p.optString("key", ""))
            .putString(KEY_AI_MODEL, p.optString("model", DEFAULT_AI_MODEL))
            .putString(KEY_AI_PROMPT, p.optString("prompt", DEFAULT_AI_PROMPT))
            .putString(KEY_AI_URL, p.optString("url", DEFAULT_AI_URL))
            .putString(KEY_AI_HEADERS, p.optString("headers", DEFAULT_AI_HEADERS))
            .putString(KEY_AI_BODY, p.optString("body", DEFAULT_AI_BODY))
            .putString(KEY_AI_RESPONSE_PATH, p.optString("responsePath", DEFAULT_AI_RESPONSE_PATH))
            .putBoolean(KEY_AI_REWRITE_ON, p.optBoolean("rewriteOn", true))
            .putBoolean(KEY_AI_STT_ON, p.optBoolean("sttOn", false))
            .putString(KEY_AI_STT_PROMPT, p.optString("sttPrompt", DEFAULT_AI_STT_PROMPT))
            .apply()
        return true
    }

    fun deleteAiProfile(ctx: Context, name: String) {
        val profiles = aiProfilesJson(ctx)
        profiles.remove(name)
        sp(ctx).edit().putString(KEY_AI_PROFILES, profiles.toString()).apply()
    }
}
