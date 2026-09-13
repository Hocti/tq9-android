package tt.ime.riverine.core

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * 鍵盤大細（高度／闊度／顯示方式）分開兩套嚟存：
 *
 *  - [CJK]：中文九宮格同純數字 keypad（兩者本來就係同一個 5 欄排位）
 *  - [LATIN]：英文、符號、數字符號頁
 *
 * 兩組嘅排位差好遠（一個係九宮格、一個係 qwerty），啱用嘅高度同闊度亦都唔同，
 * 所以拉大細嗰陣淨係郁到而家見緊嗰組。再加埋 [Prefs.profKey] 嗰個螢幕尺寸，
 * 摺機開合／打直打橫／兩組鍵盤加埋一共存 8 套。
 */
enum class PadGroup { CJK, LATIN }

/**
 * 顯示方式：鍵盤本體超過 max size 之後可以 toggle 的狀態。
 *
 * **唔係每個都成日揀得**，見 [Prefs.alignOptions] —— [SPLIT] 淨係英數鍵盤
 * 兼且螢幕夠闊先出現，而嗰陣就輪到 [LEFT_GAP] / [RIGHT_GAP] 收起。
 */
enum class PadAlign(val label: String) {
    STRETCH("拉闊"),
    LEFT_GAP("靠右（左邊留白）"),
    RIGHT_GAP("靠左（右邊留白）"),

    /**
     * 置中：本體一樣拉得窄，但係**兩邊各留一半白**（2026-09-11 user 要求）。
     * 靠一邊係為咗單手，置中就係為咗打字企中間唔歪 —— 闊 screen（平板／打橫）
     * 尤其啱用。拉闊拉窄一樣係拖粒大細掣（[Prefs.widthScale] 照用）。
     *
     * 呢個顯示方式**冇側邊欄**：空出嚟嗰啲位一開二，兩邊都窄過擺得落工具掣，
     * 所以照用返上面條 bar（見 `TTInputMethodService.sideGeom`）。
     */
    CENTER("置中（兩邊留白）"),

    /**
     * 左右拆開：一行鍵拆做兩橛，一橛貼實左邊、一橛貼實右邊，中間裂開條罅
     * （闊 screen 打橫捧住部機，兩隻姆指各顧一邊）。
     * 兩橛都係 `contentW / 2` 咁闊，所以拉闊拉窄一樣係拖粒大細掣。
     */
    SPLIT("左右拆開")
}

/**
 * 上面條 bar 而家出緊乜。
 *
 * [CANDIDATES]／[TOOLS]／[BOTH] 係切換掣（`⇄`）一路撳落去嗰個圈 ——
 * 關聯字 → 工具 → **兩行一齊**（2026-09-11 user 要求，關聯字嗰行喺工具嗰行上面）。
 *
 * [OFF]（收起成條 bar）**淨係闊 screen 入得到**：英文底行嗰粒 `▴` 撳到第四下先至
 * 收起（見 [barHidden] 同 `TTInputMethodService.toggleLatinBar`）。窄機收唔起 ——
 * 打字提示、滑出嚟嗰個字、關聯字全部喺條 bar 度，收起咗就等於打盲舖。
 *
 * enum 個 `name` 存落 SharedPreferences（[Prefs.KEY_BAR_MODE]），改名等於現有
 * user 嗰個狀態失效；加喺最後就冇所謂。
 */
enum class BarMode(val label: String) {
    OFF("關閉"),
    CANDIDATES("關聯字"),
    TOOLS("工具"),
    BOTH("關聯字＋工具");

    /** 而家有冇關聯字嗰行 */
    val hasCands: Boolean get() = this == CANDIDATES || this == BOTH

    /** 而家有冇工具嗰行 */
    val hasTools: Boolean get() = this == TOOLS || this == BOTH

    /**
     * 切換掣撳落去下一段。**永遠唔會行到 [OFF]** —— 收起淨係闊 screen 嗰粒
     * `▴` 做得到（見上面）。
     */
    fun nextVisible(): BarMode = when (this) {
        CANDIDATES -> TOOLS
        TOOLS -> BOTH
        else -> CANDIDATES
    }
}

/**
 * 選字夠兩頁嗰陣，底行兩格闊嗰粒 `0` 點排（設定頁揀，預設 [WIDE_NEXT]）。
 *
 * [PREV_NEXT] / [NEXT_PREV] 都係拆做兩粒正常闊，淨係左右調轉；
 * [WIDE_NEXT] 唔拆，成兩格闊嗰粒直接做「下頁」，返上一頁改為**長撳**佢
 * （所以嗰個狀態下長撳嘅「成對標點」冇咗，個位讓咗俾「上頁」——
 * 見 `ChinesePadView.drawDigit` 同 `TTInputMethodService.onLongPress`）。
 */
enum class PagerLayout(val label: String) {
    PREV_NEXT("拆兩粒：上頁、下頁"),
    NEXT_PREV("拆兩粒：下頁、上頁"),
    WIDE_NEXT("大格「下頁」（長按 = 上頁）"),

    /**
     * **大格「下頁」＋左下角「上頁」**：兩格闊嗰粒 `0` 照 [WIDE_NEXT] 咁做「下頁」，
     * 但「上頁」唔再收埋喺長撳度，而係揭緊頁嗰陣暫時借用**左欄最底嗰個位**
     * （左下角，即係 `KeyLayout` 左欄第 4 格）。
     *
     * 同 [WIDE_NEXT] 比：長撳 `0` 嘅成對標點（`「」`）保得住，頁數亦都照舊喺
     * 粒 `0` 左上角；代價係左下角原本嗰粒（預設 `Eng`）選字揭頁期間撳唔到，
     * 離開選字即刻返返嚟。
     */
    WIDE_NEXT_LEFT_PREV("大格「下頁」＋左下角「上頁」"),

    /**
     * **粒 `0` 由頭到尾唔變樣**：兩格闊照舊，長撳照舊係成對標點（`「」`）。
     *
     * 選字模式撳落去一樣係揭下一頁（`TTEngine.press` 收到 `0` 就 `TTCmd.NEXT`，
     * 呢個係三三本身嘅打法，唔關排位事），所以唔會揭唔到頁；淨係唔會為咗
     * 揭頁而搶走個位。想要一粒實牙實齒嘅「下頁」／「上頁」，就喺設定頁
     * 「按鍵排位」度拖 [PadFunc.NEXT_PAGE] / [PadFunc.PREV_PAGE] 落左右欄。
     */
    NO_CHANGE("無效果（0 鍵維持原樣）");
}

/**
 * 中文九宮格長撳 `Eng` 做乜（設定頁揀，預設 [NEXT_IME]）。
 *
 * 🌐 收埋咗之後，換輸入法就淨係靠呢粒鍵，所以兩種做法都要畀得 user 揀：
 * 有兩個輸入法輪流用嘅人想一撳就跳，成堆輸入法嘅人就想見到個選單。
 */
enum class EngLongPress(val label: String) {
    NEXT_IME("直接切換至下一個輸入法"),
    PICKER("彈出輸入法選單");
}

/** 手指按住按鍵時的視覺效果；「變光」是既有版本的效果。 */
enum class KeyPressEffect(val label: String) {
    NONE("無效果"),
    LIGHTEN("變光"),
    DARKEN("變暗"),
    ENLARGE("略為放大");
}

/**
 * 一個有名嘅 AI 改寫 prompt。**個名要唯一**（長撳個表就係靠個名認人），
 * 存喺 [Prefs.KEY_AI_PROMPTS] 嗰個 JSON array 入面。
 */
data class AiPrompt(val name: String, val text: String)

object Prefs {

    /**
     * SharedPreferences 個檔名。
     *
     * 2.0.0 改名嗰陣**特登冇郁**呢個舊名 —— 改咗個舊裝機嘅設定（鍵盤高度、
     * 各項選項）就會一次過 reset 返做預設。純內部檔名，user 見唔到。
     */
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
    /** 英文／符號鍵盤嘅字體大細（中文嗰組行 [KEY_FONT_SCALE]，見 [fontScale]） */
    const val KEY_FONT_SCALE_LATIN = "key_font_scale_latin"

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
    const val KEY_PRESS_EFFECT = "key_press_effect"
    const val KEY_LONG_PRESS_MS = "long_press_ms"
    /** 未打過碼嗰陣長撳 1~9 開速選字表（預設熄，唔係就搶咗「長撳 = 連撳」） */
    const val KEY_LONG_PRESS_SHORTCUT = "long_press_shortcut"
    /** 同音鍵左下角寫住而家打咗嘅碼（見 [showCurrCode]） */
    const val KEY_SHOW_CURR_CODE = "show_curr_code"
    /** 打字過程寫落 logcat（見 [InputLog]，預設熄） */
    const val KEY_INPUT_LOG = "input_log"
    const val KEY_STT_LOCALE = "stt_locale"

    /** 提示音音量（0～[MAX_TONE_LEVEL]），見 [toneLevel] */
    const val KEY_TONE_LEVEL = "tone_level"
    const val KEY_DB_LABEL = "db_label"
    const val KEY_DB_CUSTOM = "db_custom"
    const val KEY_DB_ASSET_VER = "db_asset_ver"
    /** User 自己換嘅筆形圖叫乜名（幅圖本身喺 `filesDir/strokes.png`） */
    const val KEY_IMG_LABEL = "img_label"
    const val KEY_LATIN_NUM_ROW = "latin_num_row"  // 英文鍵盤上面加一行數字
    const val KEY_USAGE_REORDER = "usage_reorder"  // 打得多嘅字推前（usage_stats.db）
    const val KEY_PAGER_LAYOUT = "pager_layout"    // PagerLayout.name（選字揭頁嗰兩粒點排）
    const val KEY_BAR_PINNED = "bar_pinned"        // 上面條 bar 常駐（右上角嗰粒改做 ⇄）
    /**
     * 闊 keyboard 收起咗上面條 bar（英文底行嗰粒 `▴`／`▾` 掣話事，見 [barHidden]）。
     *
     * **個字串仲叫 `latin_bar_hidden`** —— 2026-09-11 之前呢個狀態淨係英文頁理，
     * 而家四款鍵盤都跟。改字串等於現有 user 收起咗嘅狀態失效，冇必要。
     * 真正存落去嗰個 key 仲會加埋螢幕尺寸（見 [screenKey]）。
     */
    const val KEY_BAR_HIDDEN = "latin_bar_hidden"
    const val KEY_ENG_LONG = "eng_long"            // EngLongPress.name（長撳 Eng 做乜）

    /**
     * 自由擺位嘅鍵盤排位（左欄四粒、右欄四粒、工具列成行，各分短撳／長撳），
     * 存做一段 JSON —— 見 [KeyLayout]。
     *
     * 未寫過呢個 key 嘅舊裝機**唔會跌返做預設**：[KeyLayout.load] 會攞下面
     * 五個舊 key（[KEY_TL_TAP]／[KEY_TL_LONG]／[KEY_HOMO_LONG]／[KEY_TR_LONG]／
     * [KEY_ENG_LONG]）砌返個一模一樣嘅排位出嚟。
     */
    const val KEY_LAYOUT = "key_layout"

    // 揀得功能嗰四個位（見 [FUNC_SLOTS]）
    const val KEY_TL_TAP = "topleft_tap"
    const val KEY_TL_LONG = "topleft_long"
    /** 同音鍵長撳（預設 [PadFunc.SHORTCUT]） */
    const val KEY_HOMO_LONG = "homo_long"
    /** 右上角嗰粒（☰／⇄）長撳（預設 [PadFunc.STT]） */
    const val KEY_TR_LONG = "topright_long"

    // AI
    const val KEY_AI_KEY = "ai_api_key"
    const val KEY_AI_MODEL = "ai_model"
    const val KEY_AI_PROMPT = "ai_prompt"

    const val DEFAULT_AI_MODEL = "gemini-3.8-flash"
    const val DEFAULT_AI_PROMPT =
        "Rewrite the following text in natural B2-C1 level English " +
        "(if it is already English, just fix the grammar). " +
        "Output ONLY the rewritten text itself - no preamble, no explanation, " +
        "no quotation marks, no comments, nothing else.\n\n%text%"

    /**
     * 多個有名嘅改寫 prompt（短撳工具列粒「AI改」用第一個，長撳就彈個表揀）。
     *
     * 存法係 JSON array：`[{"name":"英譯","text":"…"}]`，**次序有意思** ——
     * 第一個就係短撳直接用嗰個（見 [aiPrompt]）。個名要唯一，讀嗰陣
     * 撞名嘅淨係留頭一個（見 [parseAiPrompts]）。
     *
     * 舊版淨係得 [KEY_AI_PROMPT] 一個 prompt。未寫過呢個 key 嘅裝置會即場
     * 攞返嗰個舊值砌個名單出嚟（[defaultAiPrompts]），所以升級上嚟
     * 自訂咗嘅 prompt 唔會唔見咗，照樣係第一個。
     */
    const val KEY_AI_PROMPTS = "ai_prompts"

    /** 「回答」：當原文係個問題，答完**取代**原文，所以要夾硬要求簡短、可以直接貼出街 */
    const val DEFAULT_AI_ANSWER_PROMPT =
        "以下是一個問題或訊息，請直接回答。答案要簡短（最多兩三句），" +
        "用正體中文，語氣自然，適合直接貼在聊天程式裡發出。\n" +
        "只輸出答案本身 —— 不要前言、不要解釋、不要引號、不要 markdown 標記。\n\n%text%"

    /** 「修飾」：改寫成辦公室通告嗰種語氣 */
    const val DEFAULT_AI_POLISH_PROMPT =
        "請將以下文字改寫成正體中文的辦公室通告：用字流暢、典雅、得體，" +
        "語氣正式而不生硬，保留原意與所有事實細節，不要自行增加內容。\n" +
        "只輸出改寫後的文字本身 —— 不要前言、不要解釋、不要引號、不要 markdown 標記。\n\n%text%"

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
     * （見 `TTInputMethodService.applyAiState`）。
     */
    const val KEY_AI_REWRITE_ON = "ai_rewrite_on"

    /**
     * 用 AI 做語音輸入（取代系統嗰個 `SpeechRecognizer`）。
     * **淨係 Gemini 做得**（要送成段錄音上去），所以 [KEY_AI_USE_CUSTOM] 開咗就唔准開。
     * 見 `AiStt` 同 `TTInputMethodService.startAiStt`。
     */
    const val KEY_AI_STT_ON = "ai_stt_on"
    const val KEY_AI_STT_PROMPT = "ai_stt_prompt"

    /**
     * 短過幾多秒嘅錄音改用系統內置嗰個 `SpeechRecognizer`（快、免費、唔使等
     * upload），長過就照送上 Gemini。0 = 熄咗呢招，一律用 AI（即係舊有行為）。
     *
     * 做法係**兩邊一齊開**：一撳錄音就同時開 `VoiceRecorder` 同系統 recognizer，
     * 夠鐘就 cancel 系統嗰個，未夠鐘就放手嗰下攞系統嗰個嘅結果。
     * 見 `TTInputMethodService.startAiStt`。
     */
    const val KEY_AI_STT_SYS_SEC = "ai_stt_sys_sec"

    /** [KEY_AI_STT_SYS_SEC] 個 slider 拉得去邊（秒） */
    const val MAX_AI_STT_SYS_SEC = 30

    /**
     * 開系統 recognizer 嗰陣暫時靜咗部機，遮住佢自己嗰兩下「開始／完結」提示聲。
     *
     * 嗰兩下係語音辨識服務（多數係 Google app）自己個 process 播嘅，唔經我哋
     * `playTone`，[KEY_TONE_LEVEL] 管唔到，亦冇公開 API 叫佢唔好播。
     * 見 `TTInputMethodService.muteEarcons`。
     */
    const val KEY_STT_MUTE_EARCON = "stt_mute_earcon"

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

    /**
     * 「進階隱藏設定」開唔開（見 `SettingsActivity` 連按「一般」分頁三下）。
     *
     * 呢啲設定（swipe 停留時間／轉角、logcat、鍵盤大小、顯示目前輸入碼、
     * 字碼資料庫、筆形提示圖）一般人唔會用到，預設收埋；連按分頁三下就會
     * 切換返出嚟，一次過切換晒，唔逐項揀。
     */
    const val KEY_SHOW_ADVANCED = "show_advanced_settings"

    fun showAdvancedSettings(ctx: Context) = sp(ctx).getBoolean(KEY_SHOW_ADVANCED, false)
    fun setShowAdvancedSettings(ctx: Context, v: Boolean) =
        sp(ctx).edit().putBoolean(KEY_SHOW_ADVANCED, v).apply()

    fun sp(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /**
     * 大細設定（[KEY_HEIGHT_SCALE]／[KEY_WIDTH_SCALE]／[KEY_ALIGN]）唔係得一套 ——
     * 每個「螢幕尺寸 × [PadGroup]」各有各存。
     *
     * 用 dp 嘅螢幕闊高做名，一次過分開晒摺機嘅外／內屏（尺寸唔同）同打直打橫
     * （闊高調轉）。舊版嗰個冇螢幕名嘅 key 照留返做預設值，升級之後大細唔會走位。
     */
    private fun profKey(ctx: Context, base: String, g: PadGroup): String =
        "${screenKey(ctx, base)}_${g.name}"

    /**
     * 淨係分螢幕尺寸、唔分 [PadGroup] 嗰啲設定（而家得 [KEY_BAR_HIDDEN] 一個）。
     * 打直打橫闊高調轉，出嚟就係兩個唔同嘅 key —— 打橫收起咗條 bar，
     * 轉返打直唔會連埋收埋。
     */
    private fun screenKey(ctx: Context, base: String): String {
        val dm = ctx.resources.displayMetrics
        val h = (dm.heightPixels / dm.density).roundToInt()
        return "${base}_${screenWidthDp(ctx)}x${h}"
    }

    fun screenWidthDp(ctx: Context): Int {
        val dm = ctx.resources.displayMetrics
        return (dm.widthPixels / dm.density).roundToInt()
    }

    /** 螢幕闊過呢個數（dp）先至有 [PadAlign.SPLIT] 揀 —— 再窄拆開兩橛就細到撳唔到 */
    const val SPLIT_MIN_WIDTH_DP = 500

    /**
     * 呢組鍵盤而家揀得邊幾個顯示方式。
     *
     * 闊 screen（`> [SPLIT_MIN_WIDTH_DP]`）嘅**英數鍵盤**冇「靠左／靠右」：
     * 咁闊嘅螢幕再靠實一邊，另一邊嗰大橛位就係嘥咗，拆開兩橛兩隻姆指啱用好多。
     * 其餘情況（中文那組、或者窄螢幕）就係「拉闊／靠右／靠左」。
     *
     * [PadAlign.CENTER]（置中）**兩邊都揀得到** —— 拉窄咗企中間，
     * 闊 screen 同窄 screen 一樣用得着。
     */
    fun alignOptions(ctx: Context, g: PadGroup): List<PadAlign> =
        if (g == PadGroup.LATIN && screenWidthDp(ctx) > SPLIT_MIN_WIDTH_DP)
            listOf(PadAlign.STRETCH, PadAlign.SPLIT, PadAlign.CENTER)
        else listOf(PadAlign.STRETCH, PadAlign.LEFT_GAP, PadAlign.RIGHT_GAP, PadAlign.CENTER)

    /** 撳一下粒大細掣：喺 [alignOptions] 入面轉去下一個 */
    fun nextAlign(ctx: Context, g: PadGroup): PadAlign {
        val opts = alignOptions(ctx, g)
        return opts[(opts.indexOf(align(ctx, g)) + 1) % opts.size]
    }

    // ---- typed accessors -------------------------------------------------

    fun keyScale(ctx: Context) = sp(ctx).getFloat(KEY_SCALE, 1.0f)
    fun maxWidthDp(ctx: Context) = sp(ctx).getInt(KEY_MAX_W_DP, 460)
    fun maxHeightDp(ctx: Context) = sp(ctx).getInt(KEY_MAX_H_DP, 300)
    /** 正方形會太高，預設矮 20% */
    fun keyHeightRatio(ctx: Context) = sp(ctx).getFloat(KEY_H_RATIO, 0.8f)
    fun gapDp(ctx: Context) = sp(ctx).getInt(KEY_GAP_DP, 2)
    /**
     * 設定頁見到嗰個百分比（**未**乘 [LATIN_FONT_BOOST]）。**兩組各有各一個**
     * （2026-08-28 user 要求）：中文九宮格＋純數字 keypad 一套（[KEY_FONT_SCALE]），
     * 英文＋符號另一套（[KEY_FONT_SCALE_LATIN]）—— 中文字要夠大先睇得清，
     * 英文字母同數字用同一個倍數就會逼爆粒鍵。
     *
     * 英文嗰個未校過就跟返中文嗰個，升級之後個樣唔會即刻變。
     */
    fun fontScalePref(ctx: Context, g: PadGroup = PadGroup.CJK): Float {
        val cjk = sp(ctx).getFloat(KEY_FONT_SCALE, 1.0f)
        return if (g == PadGroup.LATIN) sp(ctx).getFloat(KEY_FONT_SCALE_LATIN, cjk) else cjk
    }

    /**
     * 英文／符號鍵盤額外乘呢個倍數（2026-08-29 user 要求：「140% 都唔算大」；
     * 2026-09-11 再由 1.2 加到 1.3 —— 條 slider 停喺 100% 嗰陣就已經係
     * 舊版 130% 嗰個樣）。
     *
     * **唔可以直接改個 pref 嘅意思**（例如將 100 當 130 寫落去）—— 存住嗰個數字
     * 就係設定頁見到嗰個百分比，反推返嚟一定有 rounding 誤差，拖幾次就會走位。
     * 所以個倍數淨係喺 [fontScale]（真正畫嗰陣）先乘，
     * 上面條候選字 bar（[candTextSp]）就**唔乘**，個樣同以前一模一樣。
     */
    const val LATIN_FONT_BOOST = 1.3f

    /** 英文嗰條 slider 拉得幾盡（中文嗰條照舊 140%） */
    const val MAX_FONT_SCALE_LATIN_PCT = 200

    /** 真正畫鍵面用嘅倍數（[fontScalePref] × 英文嗰個 [LATIN_FONT_BOOST]） */
    fun fontScale(ctx: Context, g: PadGroup = PadGroup.CJK): Float =
        fontScalePref(ctx, g) * if (g == PadGroup.LATIN) LATIN_FONT_BOOST else 1f

    /**
     * **功能鍵**（同音、Eng、中、⌫、⏎、␣、?123…）鍵面用嘅倍數 ——
     * 固定係 100%，**唔跟設定頁條 slider**（2026-08-29 user 要求）。
     *
     * 條 slider 係為咗睇清楚**啲字**（九宮格出嘅關聯字、英文字母、符號）而拉嘅；
     * 功能鍵寫住嗰兩三隻中文字跟住一齊大就會逼爆粒鍵，而且粒鍵做乜早就記熟咗，
     * 根本唔使睇得咁清。英文組照樣乘 [LATIN_FONT_BOOST]，所以條 slider 停喺 100%
     * 嗰陣個樣同以前一模一樣。
     */
    fun funcFontScale(g: PadGroup = PadGroup.CJK): Float =
        if (g == PadGroup.LATIN) LATIN_FONT_BOOST else 1f

    /** 上面條 bar／側邊欄嘅關聯字，倍數 100% 嗰陣幾大（sp） */
    const val CAND_TEXT_SP = 20f

    /**
     * 關聯字幾大（sp）。跟 [fontScalePref]（**唔乘** [LATIN_FONT_BOOST] ——
     * 嗰個倍數係為咗補返鍵面咁窄，條 bar 唔關事），條 bar 高度亦都跟住佢大
     * （見 `OptionBarsView.barHeightFor`）。
     */
    fun candTextSp(ctx: Context, g: PadGroup = PadGroup.CJK): Float =
        CAND_TEXT_SP * fontScalePref(ctx, g)

    /** 拉高／拉低成個鍵盤（每個螢幕尺寸 × [PadGroup] 各有各套，見 [profKey]） */
    fun heightScale(ctx: Context, g: PadGroup = PadGroup.CJK) =
        sp(ctx).getFloat(profKey(ctx, KEY_HEIGHT_SCALE, g), sp(ctx).getFloat(KEY_HEIGHT_SCALE, 1.0f))

    /**
     * User 自己校過高度未（設定頁條 slider、或者工具列粒掣上下拖）。
     *
     * 未校過嘅闊 screen 有個「最多佔螢幕一半」嘅封頂（見 [PadMetrics]）——
     * 一校過就淨係聽 user 嗰個數，唔再封。舊版嗰個冇螢幕名嘅 key 都算數。
     */
    fun heightScaleSet(ctx: Context, g: PadGroup = PadGroup.CJK) =
        sp(ctx).contains(profKey(ctx, KEY_HEIGHT_SCALE, g)) || sp(ctx).contains(KEY_HEIGHT_SCALE)

    fun setHeightScale(ctx: Context, v: Float, g: PadGroup = PadGroup.CJK) =
        sp(ctx).edit()
            .putFloat(profKey(ctx, KEY_HEIGHT_SCALE, g), v.coerceIn(MIN_HEIGHT_SCALE, MAX_HEIGHT_SCALE))
            .apply()

    const val MIN_HEIGHT_SCALE = 0.6f
    const val MAX_HEIGHT_SCALE = 1.8f

    /**
     * 鍵盤本體闊度倍數。[PadAlign.LEFT_GAP] / [PadAlign.RIGHT_GAP] /
     * [PadAlign.CENTER] / [PadAlign.SPLIT] 有用（[PadAlign.STRETCH] 本來就用盡成行），
     * 喺工具 bar 最左嗰粒掣左右拖就改到。
     */
    fun widthScale(ctx: Context, g: PadGroup = PadGroup.CJK) =
        sp(ctx).getFloat(profKey(ctx, KEY_WIDTH_SCALE, g), sp(ctx).getFloat(KEY_WIDTH_SCALE, 1.0f))

    fun setWidthScale(ctx: Context, v: Float, g: PadGroup = PadGroup.CJK) =
        sp(ctx).edit()
            .putFloat(profKey(ctx, KEY_WIDTH_SCALE, g), v.coerceIn(MIN_WIDTH_SCALE, MAX_WIDTH_SCALE))
            .apply()

    const val MIN_WIDTH_SCALE = 0.45f
    const val MAX_WIDTH_SCALE = 1.6f

    /**
     * 中文本體窄到淨低咁多位（佔螢幕嘅比例以下）就唔好再喺上面擺條 bar ——
     * 空出嚟嗰邊夠位擺得晒功能掣同關聯字，見 `TTInputMethodService.sidePanelActive`。
     */
    const val SIDE_PANEL_MAX_RATIO = 0.60f

    /**
     * 存住嗰個顯示方式，但係**唔喺 [alignOptions] 入面就當「拉闊」** ——
     * 摺機打開／打橫嗰陣可揀嘅嘢會變（見 [alignOptions]），
     * 舊 profile 或者舊版留低嘅值唔可以夾硬繼續用。
     */
    fun align(ctx: Context, g: PadGroup = PadGroup.CJK): PadAlign {
        val fallback = sp(ctx).getString(KEY_ALIGN, PadAlign.STRETCH.name)!!
        val stored = runCatching {
            PadAlign.valueOf(sp(ctx).getString(profKey(ctx, KEY_ALIGN, g), fallback)!!)
        }.getOrDefault(PadAlign.STRETCH)
        return if (stored in alignOptions(ctx, g)) stored else PadAlign.STRETCH
    }

    fun setAlign(ctx: Context, a: PadAlign, g: PadGroup = PadGroup.CJK) =
        sp(ctx).edit().putString(profKey(ctx, KEY_ALIGN, g), a.name).apply()

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

    /**
     * 中文九宮格上面**四個揀得功能嘅位**，排住嘅次序就係優先次序：
     * 左上短撳 → 左上長撳 → 同音長撳 → 右上長撳。
     *
     * 四個位**可以揀同一件事**——設定頁唔會因為第二個位揀咗就喺選單度
     * 收埋嗰個選項，撞咗淨係喺該位出紅框同提示字（見
     * `SettingsActivity.availableFuncs` / `FuncPicker`），四個位照樣各自生效。
     */
    val FUNC_SLOTS = listOf(KEY_TL_TAP, KEY_TL_LONG, KEY_HOMO_LONG, KEY_TR_LONG)

    private fun funcDefault(key: String): PadFunc = when (key) {
        KEY_TL_TAP -> PadFunc.RELATE
        KEY_TL_LONG -> PadFunc.PASTE
        KEY_HOMO_LONG -> PadFunc.SHORTCUT
        KEY_TR_LONG -> PadFunc.STT
        else -> PadFunc.NONE
    }

    /**
     * [FUNC_SLOTS] 其中一個位而家做緊乜。
     *
     * 左上短撳係唯一唔准 [PadFunc.NONE] 嗰個（粒掣撳落去乜都唔做冇道理），
     * 跌返做預設嘅 [PadFunc.RELATE]。其餘三個位冇任何「同其他位撞咗就讓位」
     * 嘅邏輯——重複係俾用嘅（見 [FUNC_SLOTS]）。
     */
    fun funcSlot(ctx: Context, key: String): PadFunc {
        val f = func(ctx, key, funcDefault(key))
        return if (key == KEY_TL_TAP && f == PadFunc.NONE) funcDefault(key) else f
    }

    fun topLeftTap(ctx: Context): PadFunc = funcSlot(ctx, KEY_TL_TAP)
    fun topLeftLong(ctx: Context): PadFunc = funcSlot(ctx, KEY_TL_LONG)
    /** 同音鍵長撳（短撳永遠都係開關同音，換唔到） */
    fun homoLong(ctx: Context): PadFunc = funcSlot(ctx, KEY_HOMO_LONG)
    /** 右上角嗰粒長撳（短撳永遠都係開關上面條 bar，換唔到） */
    fun topRightLong(ctx: Context): PadFunc = funcSlot(ctx, KEY_TR_LONG)

    fun setFunc(ctx: Context, key: String, f: PadFunc) =
        sp(ctx).edit().putString(key, f.name).apply()

    private fun func(ctx: Context, key: String, def: PadFunc): PadFunc =
        runCatching { PadFunc.valueOf(sp(ctx).getString(key, def.name)!!) }.getOrDefault(def)

    /**
     * 關聯字要唔要按打過幾多次推前（見 `TTEngine.reorderByUsage`）。
     * 預設開住；熄咗就完全跟返字碼表本身嘅次序，但 `UsageStats` 照樣繼續記數。
     */
    fun usageReorder(ctx: Context) = sp(ctx).getBoolean(KEY_USAGE_REORDER, true)

    fun swipeEnabled(ctx: Context) = sp(ctx).getBoolean(KEY_SWIPE, true)
    fun swipeDwellMs(ctx: Context) = sp(ctx).getInt(KEY_SWIPE_DWELL, 150).toLong()
    fun swipeAngleDeg(ctx: Context) = sp(ctx).getInt(KEY_SWIPE_ANGLE, 45).toFloat()

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

    /**
     * 每級震幾耐（index = level，0 = 唔震）。
     *
     * **level 1 永遠係 12ms**（舊版唯一嗰個力度，唔可以郁）。2／3 加長咗
     * （18／26 → 34／60）—— user 話舊嗰個「最大」仲係唔夠明顯，而好多機
     * 淨係校 amplitude 係封頂咗嘅（見 [vibrateAmplitude]），真正感覺得到
     * 大力咗嘅係**震耐咗**。
     */
    fun vibrateDurationMs(level: Int): Long =
        longArrayOf(0L, 12L, 60L, 300L)[level.coerceIn(0, MAX_VIBRATE_LEVEL)]

    /** 每級幾大力（1～255，部機唔支援自訂震幅就用返 DEFAULT_AMPLITUDE） */
    fun vibrateAmplitude(level: Int): Int =
        intArrayOf(0, 40, 170, 255)[level.coerceIn(0, MAX_VIBRATE_LEVEL)]

    fun vibrateLevelLabel(level: Int): String =
        arrayOf("關閉", "1（最輕）", "2（中）", "3（最強）")[level.coerceIn(0, MAX_VIBRATE_LEVEL)]

    /**
     * 提示音音量 0～4（語音輸入開始／結束／成功／失敗，同埋載入失敗嗰下）。
     * 3 = 以前寫死嗰個音量，所以做預設；0 = 索性唔出聲。
     *
     * 同 [sound]（按鍵聲）**冇關係** —— 嗰個係打字嗰下嘅 click，呢個係提示音。
     */
    fun toneLevel(ctx: Context): Int =
        sp(ctx).getInt(KEY_TONE_LEVEL, 3).coerceIn(0, MAX_TONE_LEVEL)

    fun setToneLevel(ctx: Context, level: Int) =
        sp(ctx).edit().putInt(KEY_TONE_LEVEL, level.coerceIn(0, MAX_TONE_LEVEL)).apply()

    const val MAX_TONE_LEVEL = 4

    /**
     * `ToneGenerator` 個 volume 收 0～100。level 3 = 80，即係以前寫死嗰個數，
     * 唔可以郁 —— 郁咗現有 user 一升級就覺得啲提示音無端端變咗。
     * 0 級唔會叫到（見 `TTInputMethodService.playTone` 直接 return）。
     */
    fun toneVolume(level: Int): Int =
        intArrayOf(0, 25, 50, 80, 100)[level.coerceIn(0, MAX_TONE_LEVEL)]

    fun toneLevelLabel(level: Int): String =
        arrayOf("關閉（靜音）", "1（最細）", "2", "3（預設）", "4（最大）")[
            level.coerceIn(0, MAX_TONE_LEVEL)]

    fun sound(ctx: Context) = sp(ctx).getBoolean(KEY_SOUND, false)

    /**
     * 舊版本一律使用 [KeyPressEffect.LIGHTEN]；2026-09-13 起新裝置預設改為
     * [KeyPressEffect.ENLARGE]（略為放大），已經揀過其他效果的舊 user 不受影響。
     */
    fun keyPressEffect(ctx: Context): KeyPressEffect = runCatching {
        KeyPressEffect.valueOf(
            sp(ctx).getString(KEY_PRESS_EFFECT, KeyPressEffect.ENLARGE.name)!!
        )
    }.getOrDefault(KeyPressEffect.ENLARGE)

    fun setKeyPressEffect(ctx: Context, effect: KeyPressEffect) =
        sp(ctx).edit().putString(KEY_PRESS_EFFECT, effect.name).apply()

    fun longPressMs(ctx: Context) = sp(ctx).getInt(KEY_LONG_PRESS_MS, 380).toLong()

    /**
     * 未打過碼嗰陣長撳 1~9 = 直接開嗰格嘅速選字表（`TTEngine.shortcutDigit`）。
     *
     * **預設熄**：呢個功能會食咗「長撳 = 連撳」（長撳 7 = 77）嘅頭一下，
     * 打 `77x` 呢啲碼嘅人會覺得撳極都唔出，所以要 user 自己喺設定頁開。
     *
     * **開住 [swipeEnabled] 就當熄咗**（設定頁嗰個掣一齊收埋，見
     * `SettingsActivity.refreshLongPressShortcut`）：滑動輸入要「撳落即出碼」
     * （`ChinesePadView.instantKey`）先至滑得順，而呢個功能一開就要等放手先出碼，
     * 兩者本質上衝突。呢度唔清個 pref —— 熄返滑動就攞返 user 上次揀嘅嘢。
     */
    fun longPressShortcut(ctx: Context) =
        !swipeEnabled(ctx) && sp(ctx).getBoolean(KEY_LONG_PRESS_SHORTCUT, false)

    /**
     * 同音鍵左下角要唔要寫住**而家已經打咗嘅碼**（`1` → `12` → `123`）。
     *
     * 預設開住 —— 打到一半唔記得撳咗乜，望粒鍵就見返。左下角本來嗰段提示
     * （搵緊邊隻字嘅同音／個字正路點打，見 `TTEngine.homoWord` /
     * `TTEngine.homoCodeHint`）唔會為咗呢個而消失：兩者本來就少有撞埋
     * （攤開緊同音字表嗰陣冇碼可打），真係撞到就讓咗個位俾打緊嗰個碼，
     * 因為即時狀態緊要過返轉頭嗰個提示（見 `ChinesePadView.drawFunction`）。
     */
    fun showCurrCode(ctx: Context) = sp(ctx).getBoolean(KEY_SHOW_CURR_CODE, true)

    /**
     * 打字過程逐粒鍵寫落 logcat（`adb logcat -s TTInput`，見 [InputLog] 同
     * `scripts/debug-input.sh`）。**預設熄**：啲 log 寫住打緊乜，唔應該平時都出。
     */
    fun inputLog(ctx: Context) = sp(ctx).getBoolean(KEY_INPUT_LOG, false)

    /**
     * 選字揭頁嗰兩粒點排（見 [PagerLayout]）。2026-09-13 起新裝置預設改為
     * [PagerLayout.WIDE_NEXT_LEFT_PREV]（左下角變上頁），已經揀過其他排法的
     * 舊 user 不受影響。
     */
    fun pagerLayout(ctx: Context): PagerLayout =
        runCatching {
            PagerLayout.valueOf(
                sp(ctx).getString(KEY_PAGER_LAYOUT, PagerLayout.WIDE_NEXT_LEFT_PREV.name)!!
            )
        }.getOrDefault(PagerLayout.WIDE_NEXT_LEFT_PREV)

    fun setPagerLayout(ctx: Context, p: PagerLayout) =
        sp(ctx).edit().putString(KEY_PAGER_LAYOUT, p.name).apply()

    /**
     * 上面條 bar 常駐：關唔熄得。中文九宮格右上角嗰粒本來係「開／關成條 bar」，
     * 常駐之後冇嘢好開關，改咗做**關聯字 ⇄ 工具**嘅切換掣，而條 bar 自己最左
     * 嗰粒 `⇄` 就收埋（兩粒做同一件事冇意思）。見
     * `TTInputMethodService.toggleBar` 同 [PadFunc.BAR_SWITCH]。
     */
    /**
     * 工具列常駐**永遠開住**（2026-09-09 user 要求）。設定頁嗰個掣收埋咗
     * （見 `SettingsActivity.SHOW_LEGACY_KEY_OPTIONS`），個 pref 本身冇刪；
     * 想再開返「熄得條 bar」呢個玩法，將呢個值改做 false 就得。
     *
     * 常駐即係話「開／關成條 bar」冇嘢好做，所以九宮格嗰粒 `☰` 一律變咗
     * [PadFunc.BAR_SWITCH]（`⇄`＝關聯字 ⇄ 工具），而條 bar 自己最左嗰粒 `⇄`
     * 就收埋（兩粒做同一件事冇意思，見 `OptionBarsView.setSwitchVisible`）。
     */
    const val FORCE_BAR_PINNED = true

    fun barPinned(ctx: Context) =
        if (FORCE_BAR_PINNED) true else sp(ctx).getBoolean(KEY_BAR_PINNED, true)

    /**
     * 英文鍵盤收唔收得起上面條 bar —— **淨係闊 screen（打橫／摺機內屏／平板）先得**。
     *
     * 窄機收起咗就等於打盲舖：打字提示、滑出嚟嗰個字、關聯字全部喺條 bar 度，
     * 所以窄嗰陣條 bar 常駐，底行嗰粒切換掣亦都唔會出現（見 `LatinPadView.rows`）。
     * 用返 [SPLIT_MIN_WIDTH_DP] 嗰條線 —— 同「左右拆開」揀唔揀得係同一個意思嘅「闊」。
     */
    fun barToggleAllowed(ctx: Context) = screenWidthDp(ctx) > SPLIT_MIN_WIDTH_DP

    /**
     * 而家收起咗條 bar 未。要 [barToggleAllowed] 先算數 —— 窄機根本冇粒掣好撳，
     * 打橫收起咗再轉返打直，條 bar 自己會出返嚟，唔使 user 摸黑撳返。
     *
     * **打橫未撳過就預設收起**（2026-09-11 user 要求）：打橫個螢幕本來就矮，
     * 首次開鍵盤成塊連條 bar 遮到成個螢幕。撳返英文底行嗰粒 `▾`、或者中文
     * 右上角嗰粒 `⇄`（切換掣一定行去有嘢見嘅一段）就出返嚟，而且嗰下會寫低
     * 呢個螢幕尺寸嘅選擇（[screenKey]），之後打橫都聽返 user 嗰下。
     *
     * 舊版嗰個冇螢幕名嘅 key 照留返做預設，升級之後打直嗰邊唔會走位。
     */
    fun barHidden(ctx: Context): Boolean {
        if (!barToggleAllowed(ctx)) return false
        val sp = sp(ctx)
        val key = screenKey(ctx, KEY_BAR_HIDDEN)
        if (sp.contains(key)) return sp.getBoolean(key, false)
        return landscape(ctx) || sp.getBoolean(KEY_BAR_HIDDEN, false)
    }

    fun setBarHidden(ctx: Context, v: Boolean) =
        sp(ctx).edit().putBoolean(screenKey(ctx, KEY_BAR_HIDDEN), v).apply()

    fun landscape(ctx: Context) =
        ctx.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    /** 長撳中文九宮格粒 `Eng`（🌐 收埋咗之後唯一嘅換輸入法入口） */
    fun engLongPress(ctx: Context): EngLongPress =
        runCatching { EngLongPress.valueOf(sp(ctx).getString(KEY_ENG_LONG, EngLongPress.NEXT_IME.name)!!) }
            .getOrDefault(EngLongPress.NEXT_IME)

    fun setEngLongPress(ctx: Context, e: EngLongPress) =
        sp(ctx).edit().putString(KEY_ENG_LONG, e.name).apply()

    fun sttLocale(ctx: Context): String = sp(ctx).getString(KEY_STT_LOCALE, "yue-Hant-HK")!!

    fun aiApiKey(ctx: Context): String = sp(ctx).getString(KEY_AI_KEY, "")!!
    fun aiModel(ctx: Context): String =
        sp(ctx).getString(KEY_AI_MODEL, DEFAULT_AI_MODEL)!!.ifBlank { DEFAULT_AI_MODEL }
    /** 短撳「AI改」用嘅 prompt ＝ 名單第一個（見 [KEY_AI_PROMPTS]） */
    fun aiPrompt(ctx: Context): String = aiPrompts(ctx).first().text

    /**
     * 內置嗰三個 prompt。[first] ＝第一個（「英譯」）用邊段字 —— 由舊版
     * [KEY_AI_PROMPT] 升上嚟嗰陣就係嗰個舊值，唔會冚咗 user 改過嘅嘢。
     */
    fun defaultAiPrompts(first: String = DEFAULT_AI_PROMPT): List<AiPrompt> = listOf(
        AiPrompt("英譯", first.ifBlank { DEFAULT_AI_PROMPT }),
        AiPrompt("回答", DEFAULT_AI_ANSWER_PROMPT),
        AiPrompt("修飾", DEFAULT_AI_POLISH_PROMPT),
    )

    /** 一定**唔會空**：讀唔到／讀出嚟係空就跌返落 [defaultAiPrompts] */
    fun aiPrompts(ctx: Context): List<AiPrompt> =
        parseAiPrompts(sp(ctx).getString(KEY_AI_PROMPTS, null))
            ?: defaultAiPrompts(sp(ctx).getString(KEY_AI_PROMPT, DEFAULT_AI_PROMPT)!!)

    fun setAiPrompts(ctx: Context, list: List<AiPrompt>) {
        sp(ctx).edit().putString(KEY_AI_PROMPTS, aiPromptsJson(list).toString()).apply()
    }

    private fun aiPromptsJson(list: List<AiPrompt>): JSONArray {
        val arr = JSONArray()
        for (p in list) arr.put(JSONObject().put("name", p.name).put("text", p.text))
        return arr
    }

    /** 壞 JSON／空 array／全部係空白，一律回 null（＝叫個 caller 用返預設嗰批） */
    private fun parseAiPrompts(json: String?): List<AiPrompt>? {
        val arr = runCatching { JSONArray(json ?: return null) }.getOrNull() ?: return null
        val out = ArrayList<AiPrompt>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("name").trim()
            val text = o.optString("text")
            if (name.isEmpty() || text.isBlank()) continue
            // 撞名嘅留頭一個：個表淨係靠個名分得出邊個係邊個
            if (out.none { it.name == name }) out.add(AiPrompt(name, text))
        }
        return out.ifEmpty { null }
    }

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

    /** 見 [KEY_STT_MUTE_EARCON] */
    fun sttMuteEarcon(ctx: Context) = sp(ctx).getBoolean(KEY_STT_MUTE_EARCON, true)

    /** 見 [KEY_AI_STT_SYS_SEC]。0 = 一律用 AI */
    fun aiSttSysSec(ctx: Context): Int =
        sp(ctx).getInt(KEY_AI_STT_SYS_SEC, 8).coerceIn(0, MAX_AI_STT_SYS_SEC)
    fun aiCustomUrl(ctx: Context): String =
        sp(ctx).getString(KEY_AI_URL, DEFAULT_AI_URL)!!.ifBlank { DEFAULT_AI_URL }
    fun aiCustomHeaders(ctx: Context): String =
        sp(ctx).getString(KEY_AI_HEADERS, DEFAULT_AI_HEADERS)!!
    fun aiCustomBody(ctx: Context): String =
        sp(ctx).getString(KEY_AI_BODY, DEFAULT_AI_BODY)!!.ifBlank { DEFAULT_AI_BODY }
    fun aiCustomResponsePath(ctx: Context): String =
        sp(ctx).getString(KEY_AI_RESPONSE_PATH, DEFAULT_AI_RESPONSE_PATH)!!.ifBlank { DEFAULT_AI_RESPONSE_PATH }

    /** 冇自己換過字碼表嗰陣個 label（`TTDb` 靠佢認返「而家用緊內置嗰份」） */
    const val BUILTIN_DB_LABEL = "內置 dataset.db"

    fun dbLabel(ctx: Context): String = sp(ctx).getString(KEY_DB_LABEL, BUILTIN_DB_LABEL)!!
    fun setDbLabel(ctx: Context, v: String) = sp(ctx).edit().putString(KEY_DB_LABEL, v).apply()

    /**
     * User 自己揀過 sqlite 檔換走字碼表未（設定頁「選取 sqlite 檔案…」）。
     *
     * **升級唔可以踩親自己換嗰份**，所以呢個 flag 一 true 就永遠唔會再由 assets
     * 覆蓋，要撳「還原內置字碼表」先返得轉頭。舊版冇記過呢樣嘢，
     * 所以 [TTDb] 嗰邊會連個 [dbLabel] 一齊睇（換過就唔會係 [BUILTIN_DB_LABEL]）。
     */
    fun dbCustom(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_DB_CUSTOM, false)
    fun setDbCustom(ctx: Context, v: Boolean) =
        sp(ctx).edit().putBoolean(KEY_DB_CUSTOM, v).apply()

    /**
     * 而家部機入面嗰份內置字碼表，係邊個 versionCode 嘅 apk 抄出嚟
     * （`0` = 未記過／舊版裝落嚟嗰份）。裝咗新版 apk 之後對唔上就抄多次，
     * 唔係舊版嗰份會一路留到死，新版嘅字碼表點改都冇效。
     */
    fun dbAssetVersion(ctx: Context): Long = sp(ctx).getLong(KEY_DB_ASSET_VER, 0L)
    fun setDbAssetVersion(ctx: Context, v: Long) =
        sp(ctx).edit().putLong(KEY_DB_ASSET_VER, v).apply()

    /** 冇自己換過筆形圖嗰陣個 label */
    const val BUILTIN_IMG_LABEL = "內置 default90.png"

    /**
     * 而家用緊嘅筆形圖叫乜名。
     *
     * 「係咪自訂」**唔喺呢度記** —— 由 `StrokeImages.isCustom`（即係
     * `filesDir/strokes.png` 喺唔喺度）話事，個 label 純粹係設定頁攞嚟顯示。
     * 咁樣就唔會出現「pref 話自訂但個檔唔見咗」呢種對唔上嘅狀態。
     */
    fun imgLabel(ctx: Context): String = sp(ctx).getString(KEY_IMG_LABEL, BUILTIN_IMG_LABEL)!!
    fun setImgLabel(ctx: Context, v: String) = sp(ctx).edit().putString(KEY_IMG_LABEL, v).apply()

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
            // "prompt" 留返俾舊版讀（降級返去都仲有返個 prompt 用）；
            // 新版讀 "prompts" 嗰個名單，見 [loadAiProfile]
            put("prompt", aiPrompt(ctx))
            put("prompts", aiPromptsJson(aiPrompts(ctx)))
            put("url", aiCustomUrl(ctx))
            put("headers", aiCustomHeaders(ctx))
            put("body", aiCustomBody(ctx))
            put("responsePath", aiCustomResponsePath(ctx))
            put("rewriteOn", aiRewriteOn(ctx))
            // 唔用 aiSttOn()：嗰個會俾「自訂 API」壓成 false，存 profile 要存返個原本設定
            put("sttOn", sp(ctx).getBoolean(KEY_AI_STT_ON, false))
            put("sttPrompt", aiSttPrompt(ctx))
            put("sttSysSec", aiSttSysSec(ctx))
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
            // 舊 profile 冇 "prompts"：攞佢嗰個單一 prompt 砌返成個名單
            .putString(KEY_AI_PROMPTS, aiPromptsJson(
                parseAiPrompts(p.optJSONArray("prompts")?.toString())
                    ?: defaultAiPrompts(p.optString("prompt", DEFAULT_AI_PROMPT))
            ).toString())
            .putString(KEY_AI_URL, p.optString("url", DEFAULT_AI_URL))
            .putString(KEY_AI_HEADERS, p.optString("headers", DEFAULT_AI_HEADERS))
            .putString(KEY_AI_BODY, p.optString("body", DEFAULT_AI_BODY))
            .putString(KEY_AI_RESPONSE_PATH, p.optString("responsePath", DEFAULT_AI_RESPONSE_PATH))
            .putBoolean(KEY_AI_REWRITE_ON, p.optBoolean("rewriteOn", true))
            .putBoolean(KEY_AI_STT_ON, p.optBoolean("sttOn", false))
            .putString(KEY_AI_STT_PROMPT, p.optString("sttPrompt", DEFAULT_AI_STT_PROMPT))
            .putInt(KEY_AI_STT_SYS_SEC, p.optInt("sttSysSec", 8).coerceIn(0, MAX_AI_STT_SYS_SEC))
            .apply()
        return true
    }

    fun deleteAiProfile(ctx: Context, name: String) {
        val profiles = aiProfilesJson(ctx)
        profiles.remove(name)
        sp(ctx).edit().putString(KEY_AI_PROFILES, profiles.toString()).apply()
    }
}
