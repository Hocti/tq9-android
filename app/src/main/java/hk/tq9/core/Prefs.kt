package hk.tq9.core

import android.content.Context
import android.content.SharedPreferences

/** 顯示方式：中文輸入本體超過 max size 之後可以 toggle 的狀態 */
enum class PadAlign(val label: String) {
    STRETCH("拉長"),
    LEFT_GAP("左留白"),
    RIGHT_GAP("右留白");

    fun next(): PadAlign = entries[(ordinal + 1) % entries.size]
}

/** 上面條 bar 嘅三段：關 → 候選字 → 工具 */
enum class BarMode(val label: String) {
    OFF("關"),
    CANDIDATES("候選字"),
    TOOLS("工具");

    fun next(): BarMode = entries[(ordinal + 1) % entries.size]
}

/**
 * 可以喺設定度換走嘅鍵功能（而家用喺左上角嗰粒）。
 * 除咗 [EMOJI]，粒面全部改返寫中文，唔再用意義不明嘅 icon。
 */
enum class PadFunc(val label: String, val icon: String) {
    SHORTCUT("速選字", "速選"),
    SC_TOGGLE("簡體開關", "简"),
    EMOJI("Emoji", "😀"),
    PASTE("貼上", "貼上"),
    STT("錄音", "錄音"),
    AI("AI 改寫", "AI改"),
    NONE("無效", "");

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
    const val KEY_SOUND = "sound"
    const val KEY_LONG_PRESS_MS = "long_press_ms"
    const val KEY_STT_LOCALE = "stt_locale"
    const val KEY_DB_LABEL = "db_label"
    const val KEY_LATIN_NUM_ROW = "latin_num_row"  // 英文鍵盤上面加一行數字

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

    fun latinNumberRow(ctx: Context) = sp(ctx).getBoolean(KEY_LATIN_NUM_ROW, false)

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

    fun swipeEnabled(ctx: Context) = sp(ctx).getBoolean(KEY_SWIPE, true)
    fun swipeDwellMs(ctx: Context) = sp(ctx).getInt(KEY_SWIPE_DWELL, 150).toLong()
    fun swipeAngleDeg(ctx: Context) = sp(ctx).getInt(KEY_SWIPE_ANGLE, 55).toFloat()

    fun vibrate(ctx: Context) = sp(ctx).getBoolean(KEY_VIBRATE, true)
    fun sound(ctx: Context) = sp(ctx).getBoolean(KEY_SOUND, false)
    fun longPressMs(ctx: Context) = sp(ctx).getInt(KEY_LONG_PRESS_MS, 380).toLong()
    fun sttLocale(ctx: Context): String = sp(ctx).getString(KEY_STT_LOCALE, "yue-Hant-HK")!!

    fun aiApiKey(ctx: Context): String = sp(ctx).getString(KEY_AI_KEY, "")!!
    fun aiModel(ctx: Context): String =
        sp(ctx).getString(KEY_AI_MODEL, DEFAULT_AI_MODEL)!!.ifBlank { DEFAULT_AI_MODEL }
    fun aiPrompt(ctx: Context): String =
        sp(ctx).getString(KEY_AI_PROMPT, DEFAULT_AI_PROMPT)!!.ifBlank { DEFAULT_AI_PROMPT }

    fun dbLabel(ctx: Context): String = sp(ctx).getString(KEY_DB_LABEL, "內置 dataset.db")!!
    fun setDbLabel(ctx: Context, v: String) = sp(ctx).edit().putString(KEY_DB_LABEL, v).apply()
}
