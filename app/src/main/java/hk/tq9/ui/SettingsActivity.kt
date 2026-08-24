package hk.tq9.ui

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
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
import hk.tq9.core.BarMode
import hk.tq9.core.PadFunc
import hk.tq9.core.Prefs
import hk.tq9.core.Q9Db
import hk.tq9.core.Q9Engine
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

    private val pickDb = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) replaceDb(uri)
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
     * AI 改寫。
     *
     * API key 唔用普通輸入框 —— 冇人會逐個字撳，一係喺其他地方複製完貼過嚟，
     * 一係就係刪走，所以淨係得「貼上／刪除／顯示」三粒掣加一行狀態字。
     *
     * 預設用 Gemini；[Prefs.KEY_AI_USE_CUSTOM] 開咗就改用下面嗰組範本打任何
     * 接受 JSON 嘅 HTTP POST API（見 `AiRewrite.callCustom`）。成套設定
     * （provider／key／model／prompt／範本）可以用下面嘅 profile 掣 save/load/delete。
     */
    private fun buildAiSection() {
        header("AI 改寫")
        note("在任何應用程式中按工具列的 ✨ 即可用 AI 改寫：" +
            "有選取文字就只改選取的部分，沒有選取則會改寫整個輸入框的內容。" +
            "尚未輸入 API key 時，✨ 按鍵不會出現。")

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
        textField("Prompt（%text% 代表要改寫的文字）", Prefs.KEY_AI_PROMPT, Prefs.DEFAULT_AI_PROMPT,
            multiline = true)

        switch("使用自訂 API（而非 Gemini）", Prefs.KEY_AI_USE_CUSTOM, false) { rebuildAiSection() }

        if (Prefs.aiUseCustom(this)) {
            note("自訂 API 須為接受 JSON 的 HTTP POST 端點。下面範本預設是 OpenAI 相容格式" +
                "（OpenAI、Groq、DeepSeek、OpenRouter、Ollama 等大多適用），可按實際 API 文件調整。" +
                "範本中 %key% = API key，%model% = 上面的模型名稱，" +
                "%prompt% = 套用了上面 Prompt 範本後的內容（已自動處理 JSON 逸出字元）。")
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
            key.isEmpty() -> "尚未設定（✨ 按鍵不會出現）"
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

    private fun buildBehaviourSection() {
        header("其他")
        switch("輸出簡體字", Prefs.KEY_SC_OUTPUT, false)
        note("九宮格右上角的 ☰ 只負責開關整條工具列，開啟後會先進入候選字。" +
            "開啟後可按工具列最左的 ⇄ 在候選字與工具（大小位置、貼上、語音、" +
            "表情符號、AI）之間切換。英文與符號頁一律強制顯示工具列。")
        switch("按鍵震動", Prefs.KEY_VIBRATE, true)
        switch("按鍵聲音", Prefs.KEY_SOUND, false)
        slider("長按時間", 200, 700, Prefs.longPressMs(this).toInt(), "ms") { v ->
            Prefs.sp(this).edit().putInt(Prefs.KEY_LONG_PRESS_MS, v).apply()
        }
        note("長按 0 = 開關成對標點（「」之類）；長按「同音」= 關聯字；" +
            "長按 📋 = 剪貼簿記錄。九宮格 1~9 長按 = 連按兩下（長按 7 再拖到 0 = 770），" +
            "滑到最後一格停留足夠時間才放手亦作兩下計。純數字鍵盤全頁沒有長按功能。")
    }

    private fun buildTryBox() {
        header("試打")
        note("email 欄會自動顯示 @ 與 .com；密碼數字欄會自動切換純數字鍵盤；" +
            "搜尋欄的 ⏎ 會變成放大鏡。")
        tryField("在這裡試打", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE)
        tryField("email 欄", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        tryField("PIN 欄", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        tryField("搜尋欄（⏎ 會變 🔍）", InputType.TYPE_CLASS_TEXT,
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

    private fun slider(label: String, min: Int, max: Int, value: Int, unit: String, onChange: (Int) -> Unit) {
        val title = TextView(this).apply {
            text = "$label：$value$unit"
            textSize = 14f
            setPadding(0, dp(6), 0, 0)
        }
        val bar = SeekBar(this).apply {
            this.max = max - min
            progress = (value - min).coerceIn(0, max - min)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    title.text = "$label：${min + p}$unit"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) { onChange(min + progress) }
            })
        }
        content.addView(title)
        content.addView(bar)
    }

    @Suppress("DEPRECATION")
    private fun switch(label: String, key: String, def: Boolean, onChange: ((Boolean) -> Unit)? = null) {
        val s = Switch(this).apply {
            text = label
            textSize = 15f
            gravity = Gravity.CENTER_VERTICAL
            ellipsize = TextUtils.TruncateAt.END
            isChecked = Prefs.sp(this@SettingsActivity).getBoolean(key, def)
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
