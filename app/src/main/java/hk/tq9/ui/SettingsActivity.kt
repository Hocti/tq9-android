package hk.tq9.ui

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.google.android.material.tabs.TabLayout
import hk.tq9.core.AiStt
import hk.tq9.core.BarMode
import hk.tq9.core.PadFunc
import hk.tq9.core.PagerLayout
import hk.tq9.core.Prefs
import hk.tq9.core.Q9Db
import hk.tq9.core.Q9Engine
import hk.tq9.core.UsageStats
import hk.tq9.ime.ChinesePadView
import hk.tq9.ime.Key
import hk.tq9.ime.KeyboardBaseView
import hk.tq9.ime.Theme

/**
 * 九万輸入法設定。
 * 揀一個 sqlite 檔案就會直接覆蓋而家嘅字碼庫（舊嗰個唔會保留）。
 *
 * 畀人睇嘅字全部係正體中文**書面語**（唔用廣東話口語），
 * 個名亦都淨係叫「九万輸入法」／「九万」——「TQ9」係 project 個英文名，
 * 淨係喺 code 入面出現，唔會擺出嚟畀 user 睇。
 */
class SettingsActivity : AppCompatActivity() {

    companion object {
        /** 開源版本喺邊度（設定頁最底、版本號下面嗰條 link） */
        private const val PROJECT_URL = "https://github.com/Hocti/tq9-android"

        /**
         * 「試打」同「預覽」兩段收埋咗（user 要求），但係 code 一個字都冇刪 ——
         * 想 debug 鍵盤排位嗰陣改返做 true 就會即刻出返。
         */
        private const val SHOW_DEBUG_SECTIONS = false

        /**
         * 收埋咗嘅設定：最大闊度／最大高度／按鍵高度／鍵盤高度／按鍵大細
         * （而家成個鍵盤嘅長闊都係喺鍵盤度直接拖出嚟嘅，唔使再入數字），
         * 同埋「英文鍵盤加一行數字」（永遠開住，見 [Prefs.FORCE_LATIN_NUM_ROW]）。
         * 一樣係淨係收埋，冇刪過 code。
         */
        private const val SHOW_HIDDEN_OPTIONS = false
    }

    private lateinit var content: LinearLayout
    private lateinit var aiContent: LinearLayout
    private var preview: ChinesePadView? = null
    private var previewHolder: FrameLayout? = null
    private var dbLabelView: TextView? = null
    private var alignBtn: Button? = null
    private var imeStatus: TextView? = null
    private var tlTap: FuncPicker? = null
    private var tlLong: FuncPicker? = null
    private var aiKeyLabel: TextView? = null
    private var aiKeyShown = false
    private var usageLabel: TextView? = null
    private var pagerNote: TextView? = null

    /** AI 頁三大類各自攤開咗未（唔入 pref，重新開個 app 就當全部攤開） */
    private var aiOpenStt = true
    private var aiOpenRewrite = true
    private var aiOpenSetup = true

    private val pickDb = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) replaceDb(uri)
    }

    /** 匯出使用習慣統計（usage_stats.db）—— 揀邊度存由系統個檔案揀選器話事 */
    private val saveUsage = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri -> if (uri != null) exportUsage(uri) }

    private val pickUsage = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importUsage(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // targetSdk 36 預設 edge-to-edge，令到 ActionBar 個 container 浮喺 content 上面
        // （蓋住新加嘅 TabLayout）。淨係 opt-out edge-to-edge 都唔夠解決，
        // 索性收埋 ActionBar，個標題改由自己畫嘅 TextView 負責，先至保證唔會再疊埋一齊。
        WindowCompat.setDecorFitsSystemWindows(window, true)
        Q9Db.ensureInstalled(this)
        title = "九万輸入法"
        supportActionBar?.hide()

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        root.addView(TextView(this).apply {
            text = "九万輸入法"
            textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(16), dp(16), dp(16), dp(12))
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val tabs = TabLayout(this).apply {
            addTab(newTab().setText("一般"))
            addTab(newTab().setText("AI"))
            addTab(newTab().setText("說明"))
        }
        root.addView(tabs, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val pages = FrameLayout(this)
        root.addView(pages, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        val generalPane = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }
        val generalScroll = ScrollView(this).apply { addView(generalPane) }

        aiContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }
        val aiScroll = ScrollView(this).apply { addView(aiContent) }

        val helpView = buildHelpView()

        val panelLp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        pages.addView(generalScroll, panelLp)
        pages.addView(aiScroll, FrameLayout.LayoutParams(panelLp))
        pages.addView(helpView, FrameLayout.LayoutParams(panelLp))

        content = generalPane
        buildImeSection()
        buildDbSection()
        buildSizeSection()
        buildKeysSection()
        buildSwipeSection()
        buildUsageSection()
        buildBehaviourSection()
        if (SHOW_DEBUG_SECTIONS) {
            buildTryBox()
            buildPreview()
        }

        rebuildAiSection()

        aiScroll.visibility = View.GONE
        helpView.visibility = View.GONE
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                generalScroll.visibility = if (tab.position == 0) View.VISIBLE else View.GONE
                aiScroll.visibility = if (tab.position == 1) View.VISIBLE else View.GONE
                helpView.visibility = if (tab.position == 2) View.VISIBLE else View.GONE
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
    }

    override fun onResume() {
        super.onResume()
        refreshImeStatus()
        // 個數字喺鍵盤度打字嗰陣一路都會變，返到嚟就要重新讀（onCreate 嗰次已經舊咗）
        refreshUsageLabel()
    }

    // ---- sections ---------------------------------------------------------

    private fun buildImeSection() {
        header("輸入法")
        imeStatus = note("")
        row(
            button("啟用 / 停用輸入法") {
                startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            },
            button("切換至九万") {
                (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showInputMethodPicker()
            }
        )
    }

    private fun refreshImeStatus() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val enabled = imm.enabledInputMethodList.any { it.packageName == packageName }
        val current = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) ?: ""
        val active = current.startsWith("$packageName/")
        imeStatus?.text = when {
            active -> "✅ 目前正在使用九万"
            enabled -> "☑️ 已啟用，但尚未設為預設輸入法"
            else -> "⚠️ 尚未在系統中啟用九万"
        }
    }

    private fun buildDbSection() {
        header("字碼資料庫")
        dbLabelView = note("目前使用：" + Prefs.dbLabel(this))
        note("選取一個 sqlite 檔案後會立即覆蓋現有資料庫（舊版不會保留）。" +
            "檔案須包含 mapped_table、related_candidates_table、ts_chinese_table、" +
            "word_meta 四張表。")
        row(
            button("選取 sqlite 檔案…") {
                pickDb.launch(arrayOf("*/*"))
            },
            button("還原內置字碼表") {
                Q9Db.installFromAssets(this)
                dbLabelView?.text = "目前使用：" + Prefs.dbLabel(this)
                toast("已還原內置 dataset.db")
                rebuildPreview()
            }
        )
    }

    private fun replaceDb(uri: Uri) {
        val name = displayName(uri)
        Q9Db.replaceFrom(this, uri)
            .onSuccess {
                Prefs.setDbLabel(this, name)
                dbLabelView?.text = "目前使用：$name"
                toast("字碼資料庫已更換為 $name")
                rebuildPreview()
            }
            .onFailure { toast("更換失敗：" + (it.message ?: "未知錯誤")) }
    }

    private fun displayName(uri: Uri): String {
        runCatching {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0 && c.moveToFirst()) return c.getString(i)
            }
        }
        return uri.lastPathSegment ?: "自訂資料庫"
    }

    /**
     * 鍵盤大細。
     *
     * 長闊已經唔喺呢度入數字 —— 直接在鍵盤上的工具列最左那顆鍵拖動就改到
     * （上下拖 = 高度，左右拖 = 闊度），所以幾條尺寸 slider 都收埋咗，
     * 剩返「字體」同「邊框粗幼」兩樣係真係要喺呢度校嘅。
     */
    private fun buildSizeSection() {
        header("鍵盤大細")
        note("鍵盤的高度與闊度請直接在鍵盤上調整：工具列最左的按鍵，" +
            "上下拖動改高度，左右拖動改闊度（限「靠左」或「靠右」顯示方式）。")
        slider("字體大細", 70, 140, (Prefs.fontScale(this) * 100).toInt(), "%") { v ->
            Prefs.sp(this).edit().putFloat(Prefs.KEY_FONT_SCALE, v / 100f).apply(); rebuildPreview()
        }
        slider("邊框粗幼", 0, 8, Prefs.gapDp(this), "dp") { v ->
            Prefs.sp(this).edit().putInt(Prefs.KEY_GAP_DP, v).apply(); rebuildPreview()
        }
        if (SHOW_HIDDEN_OPTIONS) {
            slider("按鍵大細", 60, 140, (Prefs.keyScale(this) * 100).toInt(), "%") { v ->
                Prefs.sp(this).edit().putFloat(Prefs.KEY_SCALE, v / 100f).apply(); rebuildPreview()
            }
            slider("最大闊度", 260, 900, Prefs.maxWidthDp(this), "dp") { v ->
                Prefs.sp(this).edit().putInt(Prefs.KEY_MAX_W_DP, v).apply(); rebuildPreview()
            }
            slider("最大高度", 180, 560, Prefs.maxHeightDp(this), "dp") { v ->
                Prefs.sp(this).edit().putInt(Prefs.KEY_MAX_H_DP, v).apply(); rebuildPreview()
            }
            slider("按鍵高度（相對闊度）", 55, 110, (Prefs.keyHeightRatio(this) * 100).toInt(), "%") { v ->
                Prefs.sp(this).edit().putFloat(Prefs.KEY_H_RATIO, v / 100f).apply(); rebuildPreview()
            }
            slider("鍵盤高度", (Prefs.MIN_HEIGHT_SCALE * 100).toInt(),
                (Prefs.MAX_HEIGHT_SCALE * 100).toInt(),
                (Prefs.heightScale(this) * 100).toInt(), "%") { v ->
                Prefs.setHeightScale(this, v / 100f); rebuildPreview()
            }
        }
        note("螢幕較闊（摺疊機、平板、橫向）時，九宮格不會無限拉長，" +
            "餘下的空位由下面這個選項決定如何擺放。")
        alignBtn = button(alignLabel()) {
            Prefs.setAlign(this, Prefs.align(this).next())
            alignBtn?.text = alignLabel()
            rebuildPreview()
        }
        content.addView(alignBtn)
        note("選了「靠左」或「靠右」之後，若中文鍵盤的闊度只佔螢幕六成或以下，" +
            "上方的工具列會自動收起，功能按鍵與候選字會改為顯示在空出來的一側：" +
            "上方是功能按鍵，下方整片是候選字（可捲動）。")
    }

    private fun alignLabel() = "顯示方式：" + Prefs.align(this).label

    /**
     * 左上角嗰粒鍵短撳／長撳做乜。以前係「撳一下換一個」，
     * 而家改咗做兩個下拉式選單，一眼睇晒有咩揀。
     */
    private fun buildKeysSection() {
        header("按鍵功能")
        note("左上角按鍵的短按與長按功能均可自訂。預設為短按速選字、長按簡體開關。" +
            "短按不可設為「停用」，短按與長按亦不可設為同一功能（否則等於浪費一格）。")

        val tapOptions = PadFunc.entries.filter { it != PadFunc.NONE }
        val longOptions = PadFunc.entries.toList()

        tlTap = funcPicker("短按", tapOptions, { Prefs.topLeftTap(this) }) { picked ->
            Prefs.setFunc(this, Prefs.KEY_TL_TAP, picked)
            // 撞咗長撳 → 長撳讓返出嚟。`Prefs.topLeftLong()` 撞嗰陣本身就會回
            // NONE，但**淨係計出嚟**，個 pref 入面仲係舊嗰個；唔寫實落去，
            // 個 spinner 同 pref 就會各講各話。
            if (Prefs.topLeftLong(this) == PadFunc.NONE) {
                Prefs.setFunc(this, Prefs.KEY_TL_LONG, PadFunc.NONE)
            }
            syncFuncPickers()
            rebuildPreview()
        }
        tlLong = funcPicker("長按", longOptions, { Prefs.topLeftLong(this) }) { picked ->
            if (picked != PadFunc.NONE && picked == Prefs.topLeftTap(this)) {
                toast("長按不可與短按設為同一功能")
                syncFuncPickers() // 彈返去真正存住嗰個
                return@funcPicker
            }
            Prefs.setFunc(this, Prefs.KEY_TL_LONG, picked)
            syncFuncPickers()
            rebuildPreview()
        }

        if (SHOW_HIDDEN_OPTIONS) {
            switch("英文鍵盤上方加一行數字", Prefs.KEY_LATIN_NUM_ROW, false)
        }
        note("英文鍵盤上方固定有一行數字，長按數字鍵可輸入對應符號（與實體鍵盤按 Shift 相同，" +
            "1 → !），4 另可選各國貨幣符號。長按字母可選大小寫及各地重音寫法；" +
            "長按 , . / 三鍵可選其餘標點（左上角小字為長按時的預設項）。")
        note("長按 ␣ 後不要放手，上下左右拖動即可移動游標（切換輸入法改為長按中文鍵盤的 Eng 鍵）。" +
            "長按 ?123 可直接跳至純數字鍵盤。")

        buildPagerSection()
    }

    /**
     * 選字夠兩頁嗰陣，底行兩格闊嗰粒 `0` 點變（見 [PagerLayout]）。
     * 三個選擇本身喺 `ChinesePadView` 度實現，呢度淨係揀。
     */
    private fun buildPagerSection() {
        header("選字翻頁")
        note("候選字多於一頁時，底行原本兩格闊的 0 鍵會變成翻頁鍵，排法可自選。")
        val options = PagerLayout.entries.toList()
        enumPicker("翻頁鍵排法", options.map { it.label },
            options.indexOf(Prefs.pagerLayout(this))) { i ->
            Prefs.setPagerLayout(this, options[i])
            refreshPagerNote()
            rebuildPreview()
        }
        pagerNote = note("")
        refreshPagerNote()
    }

    private fun refreshPagerNote() {
        pagerNote?.text = when (Prefs.pagerLayout(this)) {
            PagerLayout.PREV_NEXT ->
                "0 鍵拆成兩顆正常闊度的鍵，左「上頁」右「下頁」。" +
                "「下頁」左上角會顯示目前頁數（例如 1/10，由 1 起算）。"
            PagerLayout.NEXT_PREV ->
                "0 鍵拆成兩顆正常闊度的鍵，左「下頁」右「上頁」。" +
                "「下頁」左上角會顯示目前頁數（例如 1/10，由 1 起算）。"
            PagerLayout.WIDE_NEXT ->
                "0 鍵維持兩格闊，整顆都是「下頁」（較易按中）；長按這顆鍵則是「上頁」。" +
                "此時長按 0 的成對標點（「」）功能會暫時失效，該位置改為以左上角小字顯示" +
                "「上頁」，頁數（例如 1/10）則移到右上角。離開選字狀態後「」即回復正常。"
        }
    }

    private fun syncFuncPickers() {
        tlTap?.sync()
        tlLong?.sync()
    }

    /** AI tab 成個內容會因為載入／刪除 profile、切換「自訂 API」而成組重畫，所以獨立一個 function 可以再叫 */
    private fun rebuildAiSection() {
        aiContent.removeAllViews()
        content = aiContent
        buildAiSection()
    }

    /**
     * AI 頁分三大類，每類都收埋得（[collapsible]）：
     *
     *  1. **語音輸入**（[buildSttCategory]）—— 用 AI 取代系統嗰個語音輸入
     *  2. **AI 改寫**（[buildRewriteCategory]）—— 工具列粒 ✨
     *  3. **AI 設定**（[buildSetupCategory]）—— API key、模型、自訂 API、profile
     *
     * 頭兩類各自有個總開關，第三類係兩邊共用嘅 provider 設定：
     * 一個 API key、一個模型，唔會分開兩份。
     */
    private fun buildAiSection() {
        collapsible("語音輸入 (STT)", aiOpenStt, { aiOpenStt = !aiOpenStt; rebuildAiSection() }) {
            buildSttCategory()
        }
        collapsible("AI 改寫", aiOpenRewrite, { aiOpenRewrite = !aiOpenRewrite; rebuildAiSection() }) {
            buildRewriteCategory()
        }
        collapsible("AI 設定", aiOpenSetup, { aiOpenSetup = !aiOpenSetup; rebuildAiSection() }) {
            buildSetupCategory()
        }
    }

    /**
     * 用 AI 做語音輸入。**只限 Gemini** —— 要把整段錄音送上去，
     * 自訂 API 那組範本表達不了（見 [Prefs.aiSttOn]），所以開了「自訂 API」
     * 這個開關就會被鎖住兼且強制關閉。
     */
    private fun buildSttCategory() {
        val custom = Prefs.aiUseCustom(this)
        val hasKey = Prefs.aiApiKey(this).isNotBlank()
        note("開啟後，工具列的「語音輸入」鍵會由系統內置的語音輸入改為交給 AI 辨識：" +
            "按一下開始錄音，再按一次（或輕觸畫面）停止；按住不放則會一直錄，放手即停。" +
            "太短或聽不到說話的錄音會直接當成按錯，不會上傳。" +
            "錄音與等待結果期間鍵盤會變灰不能按，錄音時顯示計時器，等待時顯示載入動畫；" +
            "開始錄音、錄音完結、成功、失敗會分別發出四種不同的提示音。")
        if (custom) {
            note("⚠️ 目前使用「自訂 API」，AI 語音輸入無法啟用 —— 錄音須以 Gemini 專用的格式上傳，" +
                "自訂 API 的範本無法表達。要用這個功能請先在「AI 設定」關閉自訂 API。")
        } else if (!hasKey) {
            note("⚠️ 尚未設定 API key，請先在「AI 設定」貼上 Gemini API key。")
        }
        switch("使用 AI 語音輸入", Prefs.KEY_AI_STT_ON, false, enabled = !custom) { rebuildAiSection() }

        if (Prefs.aiSttOn(this)) {
            textField("Prompt（%text% 代表輸入框現有內容，只作上下文）",
                Prefs.KEY_AI_STT_PROMPT, Prefs.DEFAULT_AI_STT_PROMPT, multiline = true)
            note("預設 prompt 已寫明只輸出辨識結果、逐字轉錄不作潤飾、只用繁體字，" +
                "並把輸入框現有的內容當成上下文（不會重複輸出）。改動前請保留這些要求，" +
                "否則 AI 很容易多加一句開場白，或者自作主張把你說的話改寫。")
            row(button("還原預設 Prompt") {
                Prefs.sp(this).edit().putString(Prefs.KEY_AI_STT_PROMPT, Prefs.DEFAULT_AI_STT_PROMPT).apply()
                rebuildAiSection()
                toast("已還原預設 Prompt")
            })
            note("錄音最長 " + (AiStt.MAX_RECORD_MS / 1000) + " 秒，到時會自動停止並送出。" +
                "首次使用需要授權錄音權限。")
        }
    }

    /**
     * AI 改寫（工具列粒 ✨）。整個功能可以熄咗 —— 熄咗就連粒掣都唔會出
     * （見 `TQ9InputMethodService.applyAiState`）。
     */
    private fun buildRewriteCategory() {
        note("在任何應用程式中按工具列的「AI 改寫」鍵即可用 AI 改寫：" +
            "有選取文字就只改選取的部分，沒有選取則會改寫整個輸入框的內容。")
        switch("啟用 AI 改寫", Prefs.KEY_AI_REWRITE_ON, true) { rebuildAiSection() }
        if (!Prefs.aiRewriteOn(this)) {
            note("已關閉：工具列不會出現「AI 改寫」按鍵。")
            return
        }
        if (Prefs.aiApiKey(this).isBlank()) {
            note("⚠️ 尚未設定 API key，「AI 改寫」按鍵不會出現。請先在「AI 設定」貼上 API key。")
        }
        textField("Prompt（%text% 代表要改寫的文字）", Prefs.KEY_AI_PROMPT, Prefs.DEFAULT_AI_PROMPT,
            multiline = true)
        row(button("還原預設 Prompt") {
            Prefs.sp(this).edit().putString(Prefs.KEY_AI_PROMPT, Prefs.DEFAULT_AI_PROMPT).apply()
            rebuildAiSection()
            toast("已還原預設 Prompt")
        })
    }

    /**
     * 兩邊共用嘅 provider 設定。
     *
     * API key 唔用普通輸入框 —— 冇人會逐個字撳，一係喺其他地方複製完貼過嚟，
     * 一係就係刪走，所以淨係得「貼上／刪除／顯示」三粒掣加一行狀態字。
     *
     * 預設用 Gemini；[Prefs.KEY_AI_USE_CUSTOM] 開咗就改用下面嗰組範本打任何
     * 接受 JSON 嘅 HTTP POST API（見 `AiRewrite.callCustom`）。成套設定
     * （provider／key／model／prompt／範本）可以用下面嘅 profile 掣 save/load/delete。
     */
    private fun buildSetupCategory() {
        note("API key 與模型名稱由「AI 改寫」和「語音輸入」共用。")

        buildAiProfileRow()

        content.addView(TextView(this).apply {
            text = "API key"
            textSize = 14f
            setPadding(0, dp(10), 0, 0)
        })
        aiKeyLabel = note("")
        refreshAiKeyLabel()
        row(
            button("貼上") {
                val clip = (getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
                    ?.primaryClip
                val text = clip?.takeIf { it.itemCount > 0 }
                    ?.getItemAt(0)?.coerceToText(this)?.toString()?.trim().orEmpty()
                if (text.isEmpty()) { toast("剪貼簿是空的"); return@button }
                Prefs.sp(this).edit().putString(Prefs.KEY_AI_KEY, text).apply()
                refreshAiKeyLabel()
                toast("已貼上 API key")
            },
            button("刪除") {
                Prefs.sp(this).edit().remove(Prefs.KEY_AI_KEY).apply()
                aiKeyShown = false
                refreshAiKeyLabel()
                toast("已刪除 API key")
            },
            button("顯示") {
                aiKeyShown = !aiKeyShown
                refreshAiKeyLabel()
            }
        )

        textField("模型名稱", Prefs.KEY_AI_MODEL, Prefs.DEFAULT_AI_MODEL)

        switch("使用自訂 API（而非 Gemini）", Prefs.KEY_AI_USE_CUSTOM, false) { rebuildAiSection() }

        if (Prefs.aiUseCustom(this)) {
            note("自訂 API 只適用於「AI 改寫」，語音輸入必須用 Gemini。")
            note("自訂 API 須為接受 JSON 的 HTTP POST 端點。下面範本預設是 OpenAI 相容格式" +
                "（OpenAI、Groq、DeepSeek、OpenRouter、Ollama 等大多適用），可按實際 API 文件調整。" +
                "範本中 %key% = API key，%model% = 上面的模型名稱，" +
                "%prompt% = 套用了改寫 Prompt 範本後的內容（已自動處理 JSON 逸出字元）。")
            textField("Request URL", Prefs.KEY_AI_URL, Prefs.DEFAULT_AI_URL)
            textField("Request Headers（每行一個，例如 Authorization: Bearer %key%）",
                Prefs.KEY_AI_HEADERS, Prefs.DEFAULT_AI_HEADERS, multiline = true)
            textField("Request Body 範本（JSON）", Prefs.KEY_AI_BODY, Prefs.DEFAULT_AI_BODY,
                multiline = true)
            textField("回應內容路徑（例如 choices.0.message.content）",
                Prefs.KEY_AI_RESPONSE_PATH, Prefs.DEFAULT_AI_RESPONSE_PATH)
        }
    }

    /** 已存 profile 嘅下拉選單 + 載入／另存新檔／刪除三粒掣 */
    private fun buildAiProfileRow() {
        content.addView(TextView(this).apply {
            text = "已儲存的 Profile"
            textSize = 14f
            setPadding(0, dp(6), 0, dp(2))
        })
        val names = Prefs.aiProfileNames(this)
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@SettingsActivity, android.R.layout.simple_spinner_item,
                if (names.isEmpty()) listOf("（未有已存 Profile）") else names
            ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        }
        content.addView(spinner, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        row(
            button("載入") {
                val name = names.getOrNull(spinner.selectedItemPosition)
                if (name == null) { toast("未有已存 Profile"); return@button }
                Prefs.loadAiProfile(this, name)
                aiKeyShown = false
                rebuildAiSection()
                toast("已載入「$name」")
            },
            button("另存新檔") { promptSaveAiProfile() },
            button("刪除") {
                val name = names.getOrNull(spinner.selectedItemPosition)
                if (name == null) { toast("未有已存 Profile"); return@button }
                Prefs.deleteAiProfile(this, name)
                rebuildAiSection()
                toast("已刪除「$name」")
            }
        )
    }

    /** 彈個對話框問 profile 個名，撳「儲存」先真係存 */
    private fun promptSaveAiProfile() {
        val input = EditText(this).apply { hint = "Profile 名稱" }
        val pad = dp(20)
        val wrap = FrameLayout(this).apply {
            setPadding(pad, dp(8), pad, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle("另存 AI Profile")
            .setView(wrap)
            .setPositiveButton("儲存") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) { toast("請輸入名稱"); return@setPositiveButton }
                Prefs.saveAiProfile(this, name)
                rebuildAiSection()
                toast("已儲存「$name」")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 「說明」tab：淨係一個 WebView 直接顯示 `assets/help.html`，冇任何互動 */
    private fun buildHelpView(): WebView = WebView(this).apply {
        loadUrl("file:///android_asset/help.html")
    }

    /** 冇 key 就講明冇，有 key 就預設遮住中間（撳「顯示」先睇到全個） */
    private fun refreshAiKeyLabel() {
        val key = Prefs.aiApiKey(this)
        aiKeyLabel?.text = when {
            key.isEmpty() -> "尚未設定（「AI 改寫」按鍵不會出現）"
            aiKeyShown -> key
            key.length <= 8 -> "已設定（" + "•".repeat(key.length) + "）"
            else -> "已設定（" + key.take(4) + "…" + key.takeLast(4) + "）"
        }
    }

    private fun buildSwipeSection() {
        header("滑動輸入 (Swype)")
        switch("啟用滑動輸入", Prefs.KEY_SWIPE, true)
        note("中文滑動會即時出碼：滑過 7→9→3 等同順序按了三下，" +
            "每出一碼九宮格會立即更新。中間的格子會依停留時間、轉向角度，" +
            "再配合字碼表的使用頻率一併判斷。")
        slider("停留多久當作按下", 60, 400, Prefs.swipeDwellMs(this).toInt(), "ms") { v ->
            Prefs.sp(this).edit().putInt(Prefs.KEY_SWIPE_DWELL, v).apply()
        }
        slider("轉多少度當作轉角", 25, 110, Prefs.swipeAngleDeg(this).toInt(), "°") { v ->
            Prefs.sp(this).edit().putInt(Prefs.KEY_SWIPE_ANGLE, v).apply()
        }
    }

    /**
     * 使用習慣統計（`usage_stats.db`）：每隻字打過幾多次、連續兩個字嘅組合各打過幾多次。
     *
     * 呢個檔同字碼庫（`dataset.db`）**分開存**，換字碼表唔會累到佢。呢度可以
     * 匯出備份、匯入還原、或者成個清走（等於換返一張新表），
     * 亦都可以熄咗「常用字排前」—— 熄咗照樣繼續記數，淨係唔攞嚟排候選字。
     */
    private fun buildUsageSection() {
        header("使用習慣統計")
        usageLabel = note("")
        refreshUsageLabel()
        note("每隻字打過多少次、以及連續兩個字的組合都會記錄在 usage_stats.db，" +
            "與字碼資料庫分開存放（更換字碼資料庫不會影響這些記錄）。")
        switch("常用字排前", Prefs.KEY_USAGE_REORDER, true)
        note("開啟後，候選字第一頁會依「與前一個字的組合」打過的次數推前（至少三次才會調動），" +
            "第九個之後則依該字本身打過的次數推前。關閉則完全依字碼表原本的次序，" +
            "但仍然會繼續記錄次數。")
        row(
            button("匯出…") { saveUsage.launch("usage_stats.db") },
            button("匯入…") { pickUsage.launch(arrayOf("*/*")) },
            button("清除記錄") { confirmClearUsage() }
        )
    }

    private fun refreshUsageLabel() {
        val (chars, pairs) = runCatching { UsageStats.counts(this) }.getOrDefault(0 to 0)
        usageLabel?.text = if (chars == 0 && pairs == 0) "目前未有任何記錄"
                           else "目前記錄：$chars 個字、$pairs 組前後字組合"
    }

    private fun exportUsage(uri: Uri) {
        runCatching {
            contentResolver.openOutputStream(uri)?.use { out ->
                UsageStats.exportTo(this, out).getOrThrow()
            } ?: error("無法寫入所選檔案")
        }
            .onSuccess { toast("已匯出使用記錄") }
            .onFailure { toast("匯出失敗：" + (it.message ?: "未知錯誤")) }
    }

    private fun importUsage(uri: Uri) {
        runCatching {
            contentResolver.openInputStream(uri)?.use { input ->
                UsageStats.importFrom(this, input).getOrThrow()
            } ?: error("無法開啟所選檔案")
        }
            .onSuccess { refreshUsageLabel(); toast("已匯入使用記錄") }
            .onFailure { toast("匯入失敗：" + (it.message ?: "未知錯誤")) }
    }

    /** 清除係救唔返嘅，一定要問一次 */
    private fun confirmClearUsage() {
        AlertDialog.Builder(this)
            .setTitle("清除使用記錄")
            .setMessage("將會刪除所有字數與前後字組合的記錄，並重設為新的空白記錄。此動作無法復原。")
            .setPositiveButton("清除") { _, _ ->
                UsageStats.clear(this)
                    .onSuccess { refreshUsageLabel(); toast("已清除使用記錄") }
                    .onFailure { toast("清除失敗：" + (it.message ?: "未知錯誤")) }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun buildBehaviourSection() {
        header("其他")
        switch("輸出簡體字", Prefs.KEY_SC_OUTPUT, false)
        note("九宮格右上角的 ☰ 只負責開關整條工具列，開啟後會先進入候選字。" +
            "開啟後可按工具列最左的 ⇄ 在候選字與工具（大小位置、貼上、語音、" +
            "表情符號、AI）之間切換。英文與符號頁一律強制顯示工具列。")
        slider("按鍵震動", 0, Prefs.MAX_VIBRATE_LEVEL, Prefs.vibrateLevel(this), "",
            format = { Prefs.vibrateLevelLabel(it) }) { v ->
            Prefs.setVibrateLevel(this, v)
            previewVibrate(v)
        }
        note("0 為完全關閉，1 是最輕（舊版唯一的力度），2 與 3 除了加大震幅，時間也拉長了" +
            "（34 與 60 毫秒），感覺會明顯得多，放手時會震一下試效果。" +
            "部分機款不支援自訂震幅，那些機只感覺到震動時間長短的分別。")
        switch("按鍵聲音", Prefs.KEY_SOUND, false)
        slider("長按時間", 200, 700, Prefs.longPressMs(this).toInt(), "ms", step = 10) { v ->
            Prefs.sp(this).edit().putInt(Prefs.KEY_LONG_PRESS_MS, v).apply()
        }
        note("長按 0 = 開關成對標點（「」之類）；長按「同音」= 關聯字；" +
            "長按工具列的「貼上」= 剪貼簿記錄。九宮格 1~9 長按 = 連按兩下（長按 7 再拖到 0 = 770），" +
            "滑到最後一格停留足夠時間才放手亦作兩下計。純數字鍵盤全頁沒有長按功能。")
        switch("長按 1~9 開速選字表", Prefs.KEY_LONG_PRESS_SHORTCUT, false)
        note("開啟後，尚未輸入任何字碼時長按 1~9 會直接打開該格的速選字表，" +
            "不必先按字碼再按「速選」。代價是那一下長按不再等於「連按兩下」，" +
            "要輸入 77、88 這類字碼時請關閉。已經輸入字碼後長按仍然是連按兩下，不受影響。")

        buildVersionFooter()
    }

    /** 放喺「一般」頁最底，細細行寫住而家係邊個版本（回報問題嗰陣用得着） */
    private fun buildVersionFooter() {
        val pi = runCatching { packageManager.getPackageInfo(packageName, 0) }.getOrNull()
        val name = pi?.versionName ?: "?"
        val code = when {
            pi == null -> "?"
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> pi.longVersionCode.toString()
            else -> @Suppress("DEPRECATION") pi.versionCode.toString()
        }
        content.addView(TextView(this).apply {
            text = "九万輸入法　版本 $name（build $code）"
            textSize = 12f
            alpha = 0.55f
            gravity = Gravity.CENTER
            setPadding(0, dp(28), 0, dp(4))
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        content.addView(TextView(this).apply {
            text = PROJECT_URL
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(linkColor())
            paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
            setPadding(0, 0, 0, dp(24))
            setOnClickListener { openUrl(PROJECT_URL) }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    /** 深色主題用淺藍，唔係用返系統嗰隻深藍會睇唔到 */
    private fun linkColor(): Int =
        if (Theme.of(this).dark) Color.parseColor("#7FB3FF") else Color.parseColor("#1A56C4")

    /** 部機（例如冇裝過瀏覽器嘅模擬器）開唔到就講聲，唔好死 app */
    private fun openUrl(url: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { toast("無法開啟：$url") }
    }

    /** 拖完震一下，等 user 即刻感覺到揀咗嗰級有幾大力 */
    private fun previewVibrate(level: Int) {
        if (level <= 0) return
        val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        } ?: return
        val amp = if (v.hasAmplitudeControl()) Prefs.vibrateAmplitude(level)
                  else VibrationEffect.DEFAULT_AMPLITUDE
        runCatching { v.vibrate(VibrationEffect.createOneShot(Prefs.vibrateDurationMs(level), amp)) }
    }

    private fun buildTryBox() {
        header("試打")
        note("email 欄會自動顯示 @ 與 .com；密碼數字欄會自動切換純數字鍵盤；" +
            "搜尋欄的 ⏎ 會變成放大鏡（單色符號，非彩色表情）。")
        tryField("在這裡試打", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE)
        tryField("email 欄", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        tryField("PIN 欄", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        tryField("搜尋欄（⏎ 會變放大鏡）", InputType.TYPE_CLASS_TEXT,
            EditorInfo.IME_ACTION_SEARCH)
    }

    private fun tryField(hintText: String, type: Int, imeAction: Int = 0) {
        content.addView(EditText(this).apply {
            hint = hintText
            inputType = type
            if (imeAction != 0) imeOptions = imeAction
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun buildPreview() {
        header("預覽")
        previewHolder = FrameLayout(this).apply {
            setBackgroundColor(if (Theme.of(this@SettingsActivity).dark) Color.parseColor("#12151A")
                               else Color.parseColor("#DCE0E6"))
        }
        content.addView(previewHolder, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        rebuildPreview()
    }

    /** 預覽收埋咗（[SHOW_DEBUG_SECTIONS]）嗰陣 `previewHolder` 係 null，成個 function 會即刻返 */
    private fun rebuildPreview() {
        val holder = previewHolder ?: return
        holder.removeAllViews()
        val db = runCatching { Q9Db.open(this) }.getOrNull() ?: return
        val engine = Q9Engine(db)
        val pad = ChinesePadView(this, engine).apply {
            applyTheme(Theme.of(this@SettingsActivity))
            chineseHost = object : ChinesePadView.ChineseHost {
                override fun pressDigit(digit: Int) { engine.press(digit); invalidate() }
                override val optionOn: Boolean
                    get() = Prefs.barMode(this@SettingsActivity) != BarMode.OFF
                override val aiReady: Boolean get() = false
            }
            host = object : KeyboardBaseView.Host {
                override fun onKey(key: Key) {}
                override fun onLongPress(key: Key) = false
                override fun feedback(key: Key) {}
                override fun moveCursor(dx: Int, dy: Int) {}
            }
        }
        preview = pad
        holder.addView(pad, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    // ---- 細件 -------------------------------------------------------------

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun header(text: String) {
        content.addView(TextView(this).apply {
            this.text = text
            textSize = 17f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(20), 0, dp(6))
        })
    }

    private fun note(text: String): TextView {
        val t = TextView(this).apply {
            this.text = text
            textSize = 13f
            alpha = 0.75f
            setPadding(0, 0, 0, dp(8))
        }
        content.addView(t)
        return t
    }

    private fun button(text: String, onClick: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            isAllCaps = false
            setOnClickListener { onClick() }
        }

    private fun row(vararg views: View) {
        val l = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (v in views) l.addView(v, LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        content.addView(l)
    }

    /**
     * 一個 [PadFunc] 下拉式選單（左上角鍵嘅短撳／長撳）。
     *
     * **唔可以用「跳過第一下 callback」嗰招**去擋開頭嗰下 programmatic selection：
     * `Spinner` 第一下 `onItemSelected` 幾時 fire（甚至 fire 唔 fire）係睇 layout
     * 時序嘅，擋錯咗就會食咗 user 真正嗰下 —— 個掣睇落郁咗、但係 pref 冇改過、
     * 上面個 label 亦都仲係舊嗰個，跟住去第二個 spinner 揀返同一樣嘢就會話你
     * 「功能重覆」。
     *
     * 所以改成**同而家真正存住嗰個值比**：一樣就當係回位／開場，乜都唔做；
     * 唔一樣先至係 user 真係揀過嘢。個 label 每次都由 [get] 重新讀，
     * 就算 [onPick] 拒絕咗都唔會同個 pref 唔夾。
     */
    private inner class FuncPicker(
        private val labelText: String,
        private val options: List<PadFunc>,
        private val get: () -> PadFunc,
        private val onPick: (PadFunc) -> Unit
    ) {
        private val title = TextView(this@SettingsActivity).apply {
            textSize = 14f
            setPadding(0, dp(6), 0, dp(2))
        }
        private val spinner = Spinner(this@SettingsActivity).apply {
            adapter = ArrayAdapter(
                this@SettingsActivity, android.R.layout.simple_spinner_item,
                options.map { it.label }
            ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            setSelection(options.indexOf(get()).coerceAtLeast(0), false)
        }

        init {
            content.addView(title)
            // 個 select 要有框先似粒可以撳嘅嘢（Spinner 淨係得個字同支箭咀）
            val box = FrameLayout(this@SettingsActivity).apply {
                background = GradientDrawable().apply {
                    setColor(android.graphics.Color.TRANSPARENT)
                    cornerRadius = dp(10).toFloat()
                    setStroke(dp(1).coerceAtLeast(1), android.graphics.Color.argb(110, 128, 128, 128))
                }
                setPadding(dp(6), dp(2), dp(6), dp(2))
                addView(spinner, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            content.addView(box, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(4)) })

            spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    val picked = options.getOrNull(pos)
                    // 同而家存住嗰個一樣 = 開場／回位，唔算 user 揀過嘢
                    if (picked != null && picked != get()) onPick(picked)
                    refreshTitle()
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
            refreshTitle()
        }

        private fun refreshTitle() {
            title.text = "左上角按鍵 $labelText：${get().label}"
        }

        /** 由 pref 拉返個現況落個掣同個 label（[onPick] 做完嘢就要叫） */
        fun sync() {
            val i = options.indexOf(get()).coerceAtLeast(0)
            if (spinner.selectedItemPosition != i) spinner.setSelection(i)
            refreshTitle()
        }
    }

    private fun funcPicker(
        label: String, options: List<PadFunc>, get: () -> PadFunc, onPick: (PadFunc) -> Unit
    ) = FuncPicker(label, options, get, onPick)

    /**
     * 一個普通下拉式選單（[options] 係已經寫好嘅字，[onPick] 收第幾個）。
     *
     * 同 [FuncPicker] 一樣要擋開頭嗰下 programmatic selection，但呢度冇 pref
     * 可以比對，所以自己記住而家係第幾個：`Spinner` 第一下 `onItemSelected`
     * 幾時 fire（甚至 fire 唔 fire）係睇 layout 時序嘅，唔擋就會當 user 揀過嘢。
     */
    private fun enumPicker(label: String, options: List<String>, selected: Int, onPick: (Int) -> Unit) {
        content.addView(TextView(this).apply {
            text = label
            textSize = 14f
            setPadding(0, dp(6), 0, dp(2))
        })
        var current = selected.coerceIn(0, options.size - 1)
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@SettingsActivity, android.R.layout.simple_spinner_item, options
            ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            setSelection(current, false)
        }
        // 同 FuncPicker 一樣畫個框，唔係睇落唔似粒可以撳嘅嘢
        val box = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1).coerceAtLeast(1), Color.argb(110, 128, 128, 128))
            }
            setPadding(dp(6), dp(2), dp(6), dp(2))
            addView(spinner, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        content.addView(box, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 0, 0, dp(4)) })
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (pos == current) return
                current = pos
                onPick(pos)
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    /**
     * 一段可以收埋嘅嘢：撳個標題就開／關。[body] 攤開嗰陣先至行，
     * 收埋咗就一件 view 都唔會砌（成段嘢係 rebuild 出嚟嘅，唔使收埋容器）。
     */
    private fun collapsible(title: String, open: Boolean, onToggle: () -> Unit, body: () -> Unit) {
        content.addView(TextView(this).apply {
            text = (if (open) "▾  " else "▸  ") + title
            textSize = 17f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(18), 0, dp(8))
            setOnClickListener { onToggle() }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        content.addView(View(this).apply {
            setBackgroundColor(Color.argb(60, 128, 128, 128))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1).coerceAtLeast(1))
            .apply { setMargins(0, 0, 0, dp(4)) })
        if (open) body()
    }

    /**
     * 一條 slider。[step] = 拖一格跳幾多（例如長按時間逐 10ms 一格，
     * 唔使喺 500 個位度慢慢揀），[format] = 個值點寫（唔寫就 `值+單位`）。
     *
     * `onChange` 淨係喺**放手**（`onStopTrackingTouch`）先叫，拖緊嗰陣只係改上面個字。
     */
    private fun slider(
        label: String, min: Int, max: Int, value: Int, unit: String,
        step: Int = 1, format: ((Int) -> String)? = null, onChange: (Int) -> Unit
    ) {
        fun show(v: Int) = format?.invoke(v) ?: "$v$unit"
        val steps = (max - min) / step
        val title = TextView(this).apply {
            text = "$label：${show(value)}"
            textSize = 14f
            setPadding(0, dp(6), 0, 0)
        }
        // SeekBar 只認整數 progress，所以 progress = 第幾格，值 = min + 格數 × step
        fun valueAt(p: Int) = (min + p * step).coerceIn(min, max)
        val bar = SeekBar(this).apply {
            this.max = steps
            progress = ((value - min + step / 2) / step).coerceIn(0, steps)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    title.text = "$label：${show(valueAt(p))}"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) { onChange(valueAt(progress)) }
            })
        }
        content.addView(title)
        content.addView(bar)
    }

    /** [enabled] = false：撳唔郁（而家唔准開嗰啲，例如自訂 API 之下嘅 AI 語音輸入） */
    @Suppress("DEPRECATION")
    private fun switch(label: String, key: String, def: Boolean, enabled: Boolean = true,
                       onChange: ((Boolean) -> Unit)? = null) {
        val s = Switch(this).apply {
            text = label
            textSize = 15f
            gravity = Gravity.CENTER_VERTICAL
            ellipsize = TextUtils.TruncateAt.END
            isChecked = Prefs.sp(this@SettingsActivity).getBoolean(key, def)
            isEnabled = enabled
            alpha = if (enabled) 1f else 0.45f
            setPadding(0, dp(8), 0, dp(8))
            setOnCheckedChangeListener { _, v ->
                Prefs.sp(this@SettingsActivity).edit().putBoolean(key, v).apply()
                rebuildPreview()
                onChange?.invoke(v)
            }
        }
        content.addView(s, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    /** 一格文字設定，打完自動存（唔使再撳「儲存」）。多行嗰啲會畫個圓角外框。 */
    private fun textField(label: String, key: String, def: String,
                          password: Boolean = false, multiline: Boolean = false) {
        content.addView(TextView(this).apply {
            text = label
            textSize = 14f
            setPadding(0, dp(6), 0, 0)
        })
        val edit = EditText(this).apply {
            setText(Prefs.sp(this@SettingsActivity).getString(key, def))
            inputType = when {
                password -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                multiline -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                else -> InputType.TYPE_CLASS_TEXT
            }
            if (multiline) {
                minLines = 3
                gravity = Gravity.TOP or Gravity.START
                // 多行 prompt 冇個框好難睇出邊度到邊度，畫返個圓角外框
                background = GradientDrawable().apply {
                    setColor(Color.TRANSPARENT)
                    cornerRadius = dp(10).toFloat()
                    setStroke(dp(1).coerceAtLeast(1), Color.argb(110, 128, 128, 128))
                }
                setPadding(dp(10), dp(8), dp(10), dp(8))
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    Prefs.sp(this@SettingsActivity).edit()
                        .putString(key, s?.toString().orEmpty()).apply()
                }
            })
        }
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        if (multiline) lp.setMargins(0, dp(4), 0, dp(4))
        content.addView(edit, lp)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
