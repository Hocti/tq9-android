package tt.ime.riverine.ui

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.ToneGenerator
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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.tabs.TabLayout
import tt.ime.riverine.BuildConfig
import tt.ime.riverine.core.AiPrompt
import tt.ime.riverine.core.AiSlot
import tt.ime.riverine.core.AiStt
import tt.ime.riverine.core.BarMode
import tt.ime.riverine.core.EngLongPress
import tt.ime.riverine.core.InputLog
import tt.ime.riverine.core.KeyLayout
import tt.ime.riverine.core.KeyPressEffect
import tt.ime.riverine.core.PadFunc
import tt.ime.riverine.core.PadGroup
import tt.ime.riverine.core.PagerLayout
import tt.ime.riverine.core.Prefs
import tt.ime.riverine.core.TTDb
import tt.ime.riverine.core.TTEngine
import tt.ime.riverine.core.UsageStats
import tt.ime.riverine.ime.ChinesePadView
import tt.ime.riverine.ime.Key
import tt.ime.riverine.ime.KeyboardBaseView
import tt.ime.riverine.ime.StrokeImages
import tt.ime.riverine.ime.Theme
import java.io.File

/**
 * 三三輸入法設定。
 * 選取 sqlite 檔案後，會直接覆蓋現有字碼庫（不會保留舊版本）。
 *
 * 所有使用者看到的文字均採用正體中文書面語（不使用廣東話口語）。
 * 全名為「三三正體中文輸入法」，通常稱為「三三輸入法」，簡稱「三三」；
 * 英文是「ThreeThree」，簡稱「TT」。系統輸入法清單位窄，用簡稱（見 strings.xml）。
 */
class SettingsActivity : AppCompatActivity() {

    companion object {
        /** 開源專案位址（設定頁底部、版本號下方的連結） */
        private const val PROJECT_URL = "https://github.com/Hocti/tq9-android"

        /** 拉完「提示音音量」試響那下有多長（見 [previewTone]） */
        private const val TONE_PREVIEW_MS = 150

        /**
         * 「試打」和「預覽」兩節已按使用者要求隱藏，但程式碼仍完整保留。
         * 如需除錯鍵盤排位，將此值改為 true 即可重新顯示。
         */
        private const val SHOW_DEBUG_SECTIONS = false

        /**
         * 已隱藏的設定，一律**只隱藏，沒有刪過 code**：
         *
         *  - 最大寬度／最大高度／按鍵高度／鍵盤高度／按鍵大小
         *    （長寬目前在鍵盤上直接拖，不用再入數字）
         *  - 「英文鍵盤加一行數字」（永遠保持開啟，見 [Prefs.FORCE_LATIN_NUM_ROW]）
         */
        private const val SHOW_HIDDEN_OPTIONS = false

        /**
         * 已被「按鍵排位」那個拖放介面取代、**只隱藏沒有刪過 code** 的舊設定：
         *
         *  - 「按鍵功能」四個下拉式選單（左上角短／長按、「同音」長按、右上角長按）
         *  - 「長按中文鍵盤的『Eng』」（換輸入法改為兩顆可自由擺位的按鍵）
         *  - 「工具列常駐」（現在一律常駐，見 [Prefs.FORCE_BAR_PINNED]）
         *
         * 那四個舊 pref 仍然有用：舊裝置第一次執行新版時，`KeyLayout.load` 會讀
         * 它們砌回一模一樣的排位（見 `KeyLayout.fromLegacyPrefs`），所以升級後
         * 鍵盤不會走位。改成 true 就可以連同新介面一起顯示，方便對照除錯。
         */
        private const val SHOW_LEGACY_KEY_OPTIONS = false

        /**
         * 舊那四個下拉式選單列得出的功能 —— **只有 2.x 之前就有的那八個**。
         *
         * [PadFunc] 現在還裝著 `Eng`、`⏎`、「改變大小」這些只能靠新介面擺位的
         * 按鍵（它們有「一定要用」「只能放短按」等規矩，舊選單完全沒有這套檢查），
         * 所以不可以直接拿 `PadFunc.entries` 去填。
         */
        private val LEGACY_FUNCS = listOf(
            PadFunc.SHORTCUT, PadFunc.SC_TOGGLE, PadFunc.RELATE, PadFunc.EMOJI,
            PadFunc.PASTE, PadFunc.STT, PadFunc.AI, PadFunc.NONE,
        )

        /**
         * 連按「一般」分頁三下先至再次顯示嘅「進階隱藏設定」（見
         * [Prefs.showAdvancedSettings]、[countGeneralTabTap]）。一般人用唔著，
         * 一次過收埋唔逐項揀，避免第一次打開設定頁就俾一大堆較少用嘅選項嚇親：
         *
         *  - 「字碼資料庫」「筆形提示圖」成節
         *  - 「鍵盤大小」成節（字體大小／邊框粗細——長寬已經改喺鍵盤上直接拖）
         *  - 滑動輸入嗰兩條「停留時間」「轉角」slider
         *  - 「其他」節嘅「按鍵按下時的效果」同「長按時間」
         *  - 「顯示目前已輸入碼」開關
         *  - debug build 先有嘅「記錄輸入過程 (logcat)」
         *
         * 同 [SHOW_HIDDEN_OPTIONS] / [SHOW_LEGACY_KEY_OPTIONS] 唔同：呢個唔係
         * 編譯時開關，而係存喺 pref、user 自己連按分頁解鎖，解鎖後所有裝置設定頁
         * 都一齊出返（`Prefs.showAdvancedSettings`）。
         */
    }

    private lateinit var content: LinearLayout
    private lateinit var generalPane: LinearLayout
    private lateinit var aiContent: LinearLayout
    /** 連按「一般」分頁幾多下（見 [countGeneralTabTap]），第三下就切換「進階隱藏設定」 */
    private var generalTabTaps = 0
    private var preview: ChinesePadView? = null
    private var previewHolder: FrameLayout? = null
    private var dbLabelView: TextView? = null
    private var imgLabelView: TextView? = null
    private var imeStatus: TextView? = null
    /** 四個可選功能的位（[Prefs.FUNC_SLOTS]）各一個 spinner，一起 sync */
    private val funcPickers = ArrayList<FuncPicker>()
    /** 邊幾套 provider 設定而家攤開咗完整 API key（按「顯示」） */
    private val aiKeyShown = HashSet<AiSlot>()
    private var usageLabel: TextView? = null
    private var pagerNote: TextView? = null
    /** 「長按 1~9 開速選字表」那個掣連它的說明 —— 開了滑動輸入就整組收起，
        見 [refreshLongPressShortcut] */
    private var longPressShortcutViews: List<View> = emptyList()

    /** AI 頁兩大類別的展開狀態（不寫入 pref，重開應用程式後全部展開） */
    private var aiOpenStt = true
    private var aiOpenRewrite = true

    private val pickDb = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) replaceDb(uri)
    }

    /** 選新的筆形圖（橫 9 直 10 那幅 sprite sheet，見 [StrokeImages]） */
    private val pickImg = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) replaceImg(uri)
    }

    /**
     * 匯出目前使用的字碼庫／筆形圖（2026-08-29 使用者要求「檢視目前」）。
     *
     * 應用程式不會直接開啟檔案。匯出後，使用者可自行選擇 sqlite 檢視器或圖片應用程式，
     * 因此無須推測裝置上已安裝的應用程式，也無須額外加入 `FileProvider`。
     */
    private val saveDb = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri -> if (uri != null) exportFile(uri, TTDb.file(this), "字碼資料庫") }

    private val saveImg = registerForActivityResult(
        ActivityResultContracts.CreateDocument("image/png")
    ) { uri -> if (uri != null) exportImg(uri) }

    /** 匯出使用習慣統計（usage_stats.db）—— 儲存位置由系統檔案選擇器決定 */
    private val saveUsage = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri -> if (uri != null) exportUsage(uri) }

    private val pickUsage = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importUsage(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // targetSdk 36 預設啟用 edge-to-edge，使 ActionBar 容器浮在 content 上方
        // （遮蓋新增的 TabLayout）。單純停用 edge-to-edge 仍不足以解決，
        // 因此隱藏 ActionBar，改由 TextView 顯示標題，確保兩者不會重疊。
        //
        // 注意：Android 15（API 35）起 edge-to-edge 是強制的，這一行在 API 35+ 已經
        // 沒有作用（見下面 applyWindowInsets 的說明），只對舊機有效。
        WindowCompat.setDecorFitsSystemWindows(window, true)
        TTDb.ensureInstalled(this)
        title = "三三輸入法"
        supportActionBar?.hide()

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        applyWindowInsets(root)

        root.addView(TextView(this).apply {
            text = "三三輸入法"
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

        generalPane = LinearLayout(this).apply {
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

        rebuildGeneralSection()
        rebuildAiSection()

        aiScroll.visibility = View.GONE
        helpView.visibility = View.GONE
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                generalScroll.visibility = if (tab.position == 0) View.VISIBLE else View.GONE
                aiScroll.visibility = if (tab.position == 1) View.VISIBLE else View.GONE
                helpView.visibility = if (tab.position == 2) View.VISIBLE else View.GONE
                if (tab.position == 0) countGeneralTabTap() else generalTabTaps = 0
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {
                if (tab.position == 0) countGeneralTabTap()
            }
        })
    }

    /**
     * 「一般」頁整個重新畫過 —— 開關「進階隱藏設定」冇得逐項 toggle 個 view，
     * 索性同 [rebuildAiSection] 一樣成頁重來。
     */
    private fun rebuildGeneralSection() {
        generalPane.removeAllViews()
        content = generalPane
        funcPickers.clear()
        buildImeSection()
        buildDbSection()
        buildImgSection()
        buildSizeSection()
        buildKeysSection()
        buildSwipeSection()
        buildUsageSection()
        buildBehaviourSection()
        if (SHOW_DEBUG_SECTIONS) {
            buildTryBox()
            buildPreview()
        }
    }

    /**
     * 連按「一般」分頁三下 = 開／關[進階隱藏設定][Prefs.showAdvancedSettings]。
     * 冇任何畫面提示會教人咁做，純粹俾知道呢招嘅人（自己）隨時解鎖返嚟。
     */
    private fun countGeneralTabTap() {
        generalTabTaps++
        if (generalTabTaps < 3) return
        generalTabTaps = 0
        val next = !Prefs.showAdvancedSettings(this)
        Prefs.setShowAdvancedSettings(this, next)
        rebuildGeneralSection()
        toast(if (next) "已顯示進階設定" else "已隱藏進階設定")
    }

    /**
     * Android 15（API 35）起 edge-to-edge 是強制的：decor 不再幫我們避開狀態列／導覽列，
     * `windowSoftInputMode=adjustResize` 也不會再縮細 app 的 window
     * （window frame 一直維持全螢幕，只是鍵盤疊在上面）。結果是 AI 頁最底幾格
     * 輸入框（例如「模型名稱」）一按下去就整格被鍵盤蓋住，看不到自己打甚麼。
     *
     * 所以在 API 35+ 自己讀 insets 補回 padding：
     *
     *  - 上／左／右：系統列與 display cutout，讓標題不再被狀態列切走
     *  - 下：導覽列與**鍵盤**取較大者 —— 鍵盤一彈出，[root] 底部就縮短，
     *    `pages`（weight = 1）連帶 ScrollView 一起變矮，ScrollView 收到
     *    `onSizeChanged` 便會自動捲到目前 focus 那格，鍵盤與輸入框同時看得見。
     *
     * API 35 以下不做任何事：那些機的 decor 仍會自行 inset，重複加 padding 反而會多一截。
     */
    private fun applyWindowInsets(root: View) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime))
            insets
        }
    }

    override fun onResume() {
        super.onResume()
        refreshImeStatus()
        // 記錄數字會隨鍵盤輸入而變動，回到此頁時必須重新讀取（onCreate 時的值已過時）
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
            button("切換至三三") {
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
            active -> "✅ 目前正在使用三三"
            enabled -> "☑️ 已啟用，但尚未設為預設輸入法"
            else -> "⚠️ 尚未在系統中啟用三三"
        }
    }

    private fun buildDbSection() {
        if (!Prefs.showAdvancedSettings(this)) return
        header("字碼資料庫")
        dbLabelView = note("目前使用：" + Prefs.dbLabel(this))
        note("選取的 sqlite 會立即覆蓋現有資料庫，須有 mapped_table、" +
            "related_candidates_table、ts_chinese_table、word_meta 四張表。")
        note("未自訂過的話升級會自動換上新版內置那份；自訂過就不會被覆蓋，" +
            "按「還原內置字碼表」才換回。")
        note("「檢視目前」會把正在使用的檔案另存一份，用你自己的程式開啟查看。")
        row(
            button("選取 sqlite 檔案…") {
                pickDb.launch(arrayOf("*/*"))
            },
            button("還原內置字碼表") {
                TTDb.installFromAssets(this)
                dbLabelView?.text = "目前使用：" + Prefs.dbLabel(this)
                toast("已還原內置 dataset.db")
                rebuildPreview()
            }
        )
        row(button("檢視目前 sqlite…") { saveDb.launch(TTDb.DB_NAME) })
    }

    private fun replaceDb(uri: Uri) {
        val name = displayName(uri)
        TTDb.replaceFrom(this, uri)
            .onSuccess {
                Prefs.setDbLabel(this, name)
                dbLabelView?.text = "目前使用：$name"
                toast("字碼資料庫已更換為 $name")
                rebuildPreview()
            }
            .onFailure { toast("更換失敗：" + (it.message ?: "未知錯誤")) }
    }

    /**
     * 筆形圖：成套 90 格提示圖只一幅 png（`assets/default90.png`），
     * 更換方式與字碼庫相同：將選取的圖片複製至 `filesDir/strokes.png`；
     * 「還原」則刪除該檔案（見 [StrokeImages]）。
     */
    private fun buildImgSection() {
        if (!Prefs.showAdvancedSettings(this)) return
        header("筆形提示圖")
        imgLabelView = note(imgStatusText())
        note("九宮格的 90 格筆形提示由同一幅圖切出：橫切 9 份、直切 10 份，" +
            "左上是 0_1、右下是 9_9（前面是第幾行，後面是第幾列）。")
        note("任何尺寸都會照比例切成 90 格，每格最好是正方形，建議用透明背景的 png。")
        note("「檢視目前」會把正在使用的圖另存一份，用你自己的程式開啟查看。")
        row(
            button("選取圖片檔案…") {
                pickImg.launch(arrayOf("image/*"))
            },
            button("還原內置圖片") {
                StrokeImages.restoreBuiltin(this)
                Prefs.setImgLabel(this, Prefs.BUILTIN_IMG_LABEL)
                imgLabelView?.text = imgStatusText()
                toast("已還原內置 " + StrokeImages.ASSET)
                rebuildPreview()
            }
        )
        row(button("檢視目前圖片…") {
            saveImg.launch(if (StrokeImages.isCustom(this)) StrokeImages.FILE else StrokeImages.ASSET)
        })
    }

    /** 「目前使用：xxx（每格 100×100）」—— 圖片載入失敗時不顯示尺寸 */
    private fun imgStatusText(): String {
        val name = if (StrokeImages.isCustom(this)) Prefs.imgLabel(this)
                   else Prefs.BUILTIN_IMG_LABEL
        val size = StrokeImages.tileSize(this)
        return "目前使用：" + name + if (size == null) "" else "（每格 ${size.first}×${size.second}）"
    }

    private fun replaceImg(uri: Uri) {
        val name = displayName(uri)
        StrokeImages.replaceFrom(this, uri)
            .onSuccess {
                Prefs.setImgLabel(this, name)
                imgLabelView?.text = imgStatusText()
                toast("筆形提示圖已更換為 $name")
                rebuildPreview()
            }
            .onFailure { toast("更換失敗：" + (it.message ?: "未知錯誤")) }
    }

    /**
     * 複製一份供使用者自行開啟。[src] 必須是 `filesDir` 內的檔案
     * （即鍵盤目前實際讀取的版本），不能改用 assets 的檔案，否則就會變成
     * 「檢視內置版本」，無法查看使用者自行更換的版本。
     */
    private fun exportFile(uri: Uri, src: File, what: String) {
        runCatching {
            if (!src.exists()) error("目前沒有這個檔案")
            contentResolver.openOutputStream(uri)?.use { out ->
                src.inputStream().use { it.copyTo(out) }
            } ?: error("無法寫入所選位置")
        }
            .onSuccess { toast("已匯出$what") }
            .onFailure { toast("匯出失敗：" + (it.message ?: "未知錯誤")) }
    }

    /** 筆形圖沒有自訂過就 `filesDir` 內根本沒有檔案，要由 assets 抄回出去 */
    private fun exportImg(uri: Uri) {
        if (StrokeImages.isCustom(this)) {
            exportFile(uri, StrokeImages.file(this), "筆形提示圖")
            return
        }
        runCatching {
            contentResolver.openOutputStream(uri)?.use { out ->
                assets.open(StrokeImages.ASSET).use { it.copyTo(out) }
            } ?: error("無法寫入所選位置")
        }
            .onSuccess { toast("已匯出筆形提示圖") }
            .onFailure { toast("匯出失敗：" + (it.message ?: "未知錯誤")) }
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
     * 鍵盤大小。
     *
     * 長寬不再於此輸入數值，而是直接拖動鍵盤工具列最左側的按鍵調整
     * （上下拖動調整高度，左右拖動調整寬度）。因此，數條尺寸滑桿已隱藏，
     * 只保留需要在此調整的「字體」和「邊框粗細」。
     *
     * 「顯示方式」（拉寬／靠左／靠右／左右拆開）**2026-08-29 由此處移走了** ——
     * 按一下工具列最左側的按鍵即可切換至下一種方式。設定頁的重複入口因此已移除。
     * `Prefs.setAlign` / `nextAlign` 的程式碼仍完整保留，
     * `TTInputMethodService.onCycleAlign` 也仍在使用。
     */
    private fun buildSizeSection() {
        if (!Prefs.showAdvancedSettings(this)) return
        header("鍵盤大小")
        note("高度與寬度請直接在鍵盤上調整：拖動工具列最左的按鍵，上下改高度、左右改寬度，" +
            "長按則可一次拉至最寬。按一下同一個鍵，可切換配置方式（拉寬、靠左、靠右）。")
        note("鍵盤大小會按鍵盤組別（中文與純數字一組、英文與符號另一組）及當前的螢幕尺寸分別儲存，" +
            "在哪個畫面調整就只影響該畫面。字體大小同樣分兩套，但不分螢幕尺寸。")
        // 字體大小跟大小設定一樣分兩組：中文字要夠大先看得清，英文字母與數字
        // 用同一個倍數就會擠滿按鍵（見 [Prefs.fontScale]）。
        // 英文那條拉得去 200%，而且畫時仍會再乘 [Prefs.LATIN_FONT_BOOST]。
        slider("中文鍵盤字體大小", 70, 140, (Prefs.fontScalePref(this) * 100).toInt(), "%") { v ->
            Prefs.sp(this).edit().putFloat(Prefs.KEY_FONT_SCALE, v / 100f).apply(); rebuildPreview()
        }
        slider("英文鍵盤字體大小", 70, Prefs.MAX_FONT_SCALE_LATIN_PCT,
            (Prefs.fontScalePref(this, PadGroup.LATIN) * 100).toInt(), "%") { v ->
            Prefs.sp(this).edit().putFloat(Prefs.KEY_FONT_SCALE_LATIN, v / 100f).apply()
            rebuildPreview()
        }
        slider("邊框粗細", 0, 8, Prefs.gapDp(this), "dp") { v ->
            Prefs.sp(this).edit().putInt(Prefs.KEY_GAP_DP, v).apply(); rebuildPreview()
        }
        if (SHOW_HIDDEN_OPTIONS) {
            slider("按鍵大小", 60, 140, (Prefs.keyScale(this) * 100).toInt(), "%") { v ->
                Prefs.sp(this).edit().putFloat(Prefs.KEY_SCALE, v / 100f).apply(); rebuildPreview()
            }
            slider("最大寬度", 260, 900, Prefs.maxWidthDp(this), "dp") { v ->
                Prefs.sp(this).edit().putInt(Prefs.KEY_MAX_W_DP, v).apply(); rebuildPreview()
            }
            slider("最大高度", 180, 560, Prefs.maxHeightDp(this), "dp") { v ->
                Prefs.sp(this).edit().putInt(Prefs.KEY_MAX_H_DP, v).apply(); rebuildPreview()
            }
            slider("按鍵高度（相對寬度）", 55, 110, (Prefs.keyHeightRatio(this) * 100).toInt(), "%") { v ->
                Prefs.sp(this).edit().putFloat(Prefs.KEY_H_RATIO, v / 100f).apply(); rebuildPreview()
            }
            slider("鍵盤高度", (Prefs.MIN_HEIGHT_SCALE * 100).toInt(),
                (Prefs.MAX_HEIGHT_SCALE * 100).toInt(),
                (Prefs.heightScale(this) * 100).toInt(), "%") { v ->
                Prefs.setHeightScale(this, v / 100f); rebuildPreview()
            }
        }
        note("選了「靠左」或「靠右」而中文鍵盤只佔螢幕六成或以下時，" +
            "上方工具列會自動收起，功能鍵與關聯字改為顯示在空出來的一側。")
        note("寬螢幕（打橫、摺機內屏、平板）未自己調整過高度之前，鍵盤最高只佔螢幕一半；" +
            "打橫時上方那條 bar 亦預設收起。按英文鍵盤底行那顆、或中文鍵盤右上角的 " +
            "「轉換工具列」，都可以叫它回來。")
        note("英文與符號鍵盤在螢幕寬過 ${Prefs.SPLIT_MIN_WIDTH_DP}dp 時，" +
            "「靠左」「靠右」會換成「左右拆開」：每行鍵分成兩半各貼一邊，中間留空，" +
            "橫向雙手持機時兩隻拇指各顧一邊。")
    }

    /**
     * 四個可自訂功能的位置（[Prefs.FUNC_SLOTS]）各有一個下拉式選單。
     *
     * 四個位置**可選擇相同功能**。選單始終會列出所有選項，不會因其他位置已選用而隱藏。
     * 如有重複，該位置只會顯示紅色外框和提示文字（見 [FuncPicker]），
     * 不會阻止儲存，四個位置仍會各自生效。
     */
    /**
     * 以前這裡有個「按鍵功能」節（四個下拉式選單）。四個選單已被「按鍵排位」
     * 取代並隱藏之後，整節只剩下標題與幾段說明，看上去像個空欄，
     * 所以 2026-09-09 把標題拆走 —— 剩下那顆掣與說明併進「按鍵排位」。
     * 舊那四個選單的 code 沒有刪，見 [SHOW_LEGACY_KEY_OPTIONS]。
     */
    private fun buildKeysSection() {
        buildLayoutSection()
        buildPagerSection()
    }

    /**
     * 「按鍵排位」：拖放砌左欄四顆、右欄四顆與整條工具列（見 [KeyLayoutEditor]）。
     *
     * 這裡只負責把編輯器放進頁面、把結果寫回 pref；甚麼放得下、甚麼放不下
     * 全部由 [KeyLayout] 判斷，兩邊不會各有一套規則。
     */
    private fun buildLayoutSection() {
        header("按鍵排位")
        if (SHOW_LEGACY_KEY_OPTIONS) {
            funcPicker("左上角按鍵 短按", Prefs.KEY_TL_TAP, allowNone = false)
            funcPicker("左上角按鍵 長按", Prefs.KEY_TL_LONG, allowNone = true)
            funcPicker("「同音」鍵 長按", Prefs.KEY_HOMO_LONG, allowNone = true)
            funcPicker("右上角按鍵 長按", Prefs.KEY_TR_LONG, allowNone = true)
            syncFuncPickers() // 四個位置建立後才能比對重複項目，並補上初始紅框狀態
            val engOptions = EngLongPress.entries.toList()
            enumPicker("長按中文鍵盤的「Eng」", engOptions.map { it.label },
                engOptions.indexOf(Prefs.engLongPress(this))) { i ->
                Prefs.setEngLongPress(this, engOptions[i])
                rebuildPreview()
            }
        }
        note("中文鍵盤左欄四顆、右欄四顆，以及上方工具列，每一顆的短按與長按都可以自己排。" +
            "九宮格 1~9、兩格寬的 0 與「取消」是三三的打法本身，不能改動。")
        note("由下面的按鍵池往上拖 = 加一顆（池裡不會減少）；格子與格子互拖 = 兩格對調；" +
            "拖回池裡 = 清走那一格。格子太小拖不準，按一下它也可以直接選。")
        note("空格、刪除、換行／送出、轉換工具列這四顆只能放在左右兩欄，不能放進工具列；" +
            "空格、刪除、換行只能放短按，放了之後同一格的長按會停用；" +
            "「改變大小」與「中文鍵盤」相反，只能放在工具列" +
            "（「改變大小」要在那顆按鍵上直接拖才拉得動鍵盤大小）。")
        note("四顆轉鍵盤的（Eng、?123 符號、123 純數字、中文鍵盤）兩邊都可以放" +
            "（「中文鍵盤」只能放工具列），但 Eng 一定要有一顆留在左右兩欄 —— " +
            "工具列收起來時就只剩鍵盤本身那顆切換得回英文。")
        note("左右兩欄之間不可以有重複的按鍵，工具列本身也不可以；" +
            "但同一顆可以同時出現在工具列與左右兩欄。")

        val editor = KeyLayoutEditor(this, KeyLayout.load(this)) { l ->
            KeyLayout.save(this, l)
            rebuildPreview()
        }
        content.addView(editor, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        note("鍵面左上角的小字（或小圖案）就是長按會做的事。「同音」鍵左下角另有即時提示：" +
            "正在輸入的字碼、正在查詢哪個字的同音，或該字本身的正常打法。")

        if (Prefs.showAdvancedSettings(this)) {
            switch("顯示目前已輸入碼", Prefs.KEY_SHOW_CURR_CODE, true)
            note("開啟後，輸入字碼時「同音」鍵左下角會寫出目前已按的碼（1 → 12 → 123），" +
                "選完字或取消後就會消失。它與上述另外兩種提示共用同一位置，" +
                "極少同時出現，真的撞在一起時以正在輸入的字碼為準。")
        }

        note("九宮格 1~9 按下即出碼，不必等放開手指，長按等於連按兩下。" +
            "選字狀態、以及關閉滑動輸入後開了「長按 1~9 開速選字表」而未輸入字碼時例外，" +
            "那兩種情況要放開手指才出碼。")
        note("英文鍵盤固定有一行數字：長按數字出對應符號（1 → !），4 另有各國貨幣符號；" +
            "長按字母可選大小寫與重音寫法；長按 , . / 可選其餘標點。")
        note("長按 ␣ 後不放手，上下左右拖動即可移動游標；長按 ?123 直接跳至純數字鍵盤。")
        if (SHOW_HIDDEN_OPTIONS) {
            switch("英文鍵盤上方加一行數字", Prefs.KEY_LATIN_NUM_ROW, false)
        }

        row(button("還原預設排位") {
            AlertDialog.Builder(this)
                .setTitle("還原預設排位")
                .setMessage("左右兩欄與工具列會回到出廠時的排法，目前的排位不會留底。")
                .setPositiveButton("還原") { _, _ ->
                    KeyLayout.reset(this)
                    editor.setLayout(KeyLayout.load(this))
                    rebuildPreview()
                    toast("已還原預設排位")
                }
                .setNegativeButton("取消", null)
                .show()
        })
    }

    /**
     * 選字內容達兩頁時，底行佔兩格寬的 `0` 鍵會如何變化（見 [PagerLayout]）。
     * 五種排法在 `ChinesePadView` 實作，此處只負責選擇。
     *
     * 排在「按鍵排位」下面是有原因的：選了 [PagerLayout.NO_CHANGE]（0 鍵維持原樣）
     * 之後，翻頁就要靠上面那個介面拖一顆「下頁」／「上頁」出來。
     */
    private fun buildPagerSection() {
        header("選字翻頁")
        note("關聯字多於一頁時，底行兩格寬的 0 鍵會變成翻頁鍵。")
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
                "0 鍵拆成兩個，左「上頁」右「下頁」，頁數（如 1/10）顯示在「下頁」左上角。"
            PagerLayout.NEXT_PREV ->
                "0 鍵拆成兩個，左「下頁」右「上頁」，頁數（如 1/10）顯示在「下頁」左上角。"
            PagerLayout.WIDE_NEXT ->
                "0 鍵維持兩格寬，整顆是「下頁」（較容易按中），長按為「上頁」。" +
                "選字期間長按 0 的成對標點會暫停，頁數改在右上角，離開選字即回復。"
            PagerLayout.WIDE_NEXT_LEFT_PREV ->
                "0 鍵維持兩格寬且整顆是「下頁」，「上頁」則在選字翻頁期間借用左下角那顆鍵。" +
                "長按 0 的成對標點與左上角頁數都保持原樣，離開選字左下角即回復原本功能。"
            PagerLayout.NO_CHANGE ->
                "0 鍵完全不變樣：仍是兩格寬，長按仍是成對標點。選字時按下去照樣翻下一頁，" +
                "只是不會為了翻頁而改動排位。想要一顆明確的「下頁」／「上頁」，" +
                "請在上面的「按鍵排位」把它拖到左右兩欄。"
        }
    }

    private fun syncFuncPickers() = funcPickers.forEach { it.sync() }

    /** AI tab 整個內容會因為載入／刪除 profile、切換「自訂 API」而成組重畫，所以獨立一個 function 可以再叫 */
    private fun rebuildAiSection() {
        aiContent.removeAllViews()
        content = aiContent
        buildAiSection()
    }

    /**
     * AI 頁分為兩大類，每類均可收合（[collapsible]）：
     *
     *  1. **語音輸入**（[buildSttCategory]）—— 用 AI 取代系統那個語音輸入
     *  2. **AI 改寫**（[buildRewriteCategory]）—— 工具列顆 ✨
     *
     * 兩類各自有個總開關，也各自有一組 provider 設定（[buildAiProviderBlock]：
     * profile／API key／模型／自訂 API）。語音輸入預設「和 AI 改寫共用」，
     * 關了共用才顯示它自己那組。
     */
    private fun buildAiSection() {
        collapsible("語音輸入 (STT)", aiOpenStt, { aiOpenStt = !aiOpenStt; rebuildAiSection() }) {
            buildSttCategory()
        }
        collapsible("AI 改寫", aiOpenRewrite, { aiOpenRewrite = !aiOpenRewrite; rebuildAiSection() }) {
            buildRewriteCategory()
        }
    }

    /**
     * 用 AI 做語音輸入。用的是 [Prefs.aiSttSlot] 那組 provider 設定；
     * 那組是自訂 API 但 Body 範本沒有 `%audio%`（放不進錄音）時，
     * [Prefs.aiSttOn] 一律當關閉，這裡只出警告。
     */
    private fun buildSttCategory() {
        note("開啟後，工具列的「語音輸入」改由 AI 辨識：按一下開始錄音，再按一次停止；" +
            "按住不放則放手即停。太短或聽不到說話的錄音不會上傳。")
        note("錄音同等待期間鍵盤會變灰，並以提示音告知開始、結束、成功與失敗。" +
            "提示音的音量在「其他 → 提示音音量」調整，拉到「關閉」就完全不出聲。")
        switch("使用 AI 語音輸入", Prefs.KEY_AI_STT_ON, false) { rebuildAiSection() }

        // 不用 aiSttOn()：範本放不進錄音時它回 false，但設定仍要攤開讓使用者改
        if (Prefs.sp(this).getBoolean(Prefs.KEY_AI_STT_ON, false)) {
            val share = Prefs.aiSttShare(this)
            val provider = Prefs.aiProvider(this, Prefs.aiSttSlot(this))
            if (!Prefs.aiAudioCapable(provider)) {
                note("⚠️ " + (if (share) "「AI 改寫」" else "語音輸入") + "的 AI 設定使用自訂 API，" +
                    "但 Body 範本中沒有 %audio%，無法送出錄音，暫時改用系統語音辨識。" +
                    (if (share) "請關閉下面「和 AI 改寫共用 Profile」，另外設定語音用的 API。" else ""))
            } else if (provider.key.isBlank()) {
                note("⚠️ 尚未設定 API key，請在下面的「AI 設定」" +
                    (if (share) "（與「AI 改寫」共用）" else "") + "貼上。")
            }

            textField("Prompt（%text% = 輸入框現有內容，只作上下文；%lang% = 目標語言）",
                Prefs.KEY_AI_STT_PROMPT, Prefs.DEFAULT_AI_STT_PROMPT, multiline = true)
            note("預設 prompt 已要求只輸出辨識結果、逐字轉錄、中文只用繁體字，" +
                "並把輸入框現有內容當成上下文。改動時請保留這些要求，否則 AI 容易自行加話或改寫。")
            row(button("還原預設 Prompt") {
                Prefs.sp(this).edit().putString(Prefs.KEY_AI_STT_PROMPT, Prefs.DEFAULT_AI_STT_PROMPT).apply()
                rebuildAiSection()
                toast("已還原預設 Prompt")
            })
            textField("中文鍵盤按語音時的 %lang%",
                Prefs.KEY_AI_STT_LANG_ZH, Prefs.DEFAULT_AI_STT_LANG_ZH)
            textField("其他鍵盤（英文／符號／數字等）按語音時的 %lang%",
                Prefs.KEY_AI_STT_LANG_OTHER, Prefs.DEFAULT_AI_STT_LANG_OTHER)
            note("錄音最長 " + (AiStt.MAX_RECORD_MS / 1000) + " 秒，屆時自動停止送出。" +
                "首次使用需授權錄音權限。")

            slider("短錄音改用系統辨識", 0, Prefs.MAX_AI_STT_SYS_SEC, Prefs.aiSttSysSec(this), "秒",
                format = { if (it == 0) "關閉（一律用 AI）" else "$it 秒以內" }) { v ->
                Prefs.sp(this).edit().putInt(Prefs.KEY_AI_STT_SYS_SEC, v).apply()
                rebuildAiSection()
            }
            if (Prefs.aiSttSysSec(this) > 0) {
                note("按下錄音時兩邊同時開始：講夠 " + Prefs.aiSttSysSec(this) +
                    " 秒之前放手，就用系統內置的語音辨識（快、不耗 API 額度）；" +
                    "超過就取消系統那邊，整段送去 AI。系統那邊聽不到內容時，仍會自動改用 AI。")
                note("⚠️ 兩邊同時開麥克風要看裝置是否允許；部分裝置只會讓其中一邊收到聲音。" +
                    "若短錄音經常失準或變慢，把這裡調成「關閉」即可回到只用 AI。")
                switch("蓋住系統辨識的提示聲", Prefs.KEY_STT_MUTE_EARCON, true)
                note("系統的語音辨識服務自己會播「開始／完結」兩下提示聲，" +
                    "那是它自己的程序播的，上面的「提示音音量」管不到，也沒有 API 叫它不要播。" +
                    "開啟這項就在錄音期間暫時靜音（媒體與系統音，不包括我們自己的提示音），" +
                    "錄完立即還原。代價是錄音那幾秒聽不到正在播放的音樂。")
            }

            subHeader("AI 設定")
            switch("和 AI 改寫共用 Profile", Prefs.KEY_AI_STT_SHARE, true) { rebuildAiSection() }
            if (share) {
                note("使用「AI 改寫」那組 Profile／API key／模型／自訂 API 設定。" +
                    "想語音用另一個服務（例如 Whisper），就關閉這項。")
            } else {
                buildAiProviderBlock(AiSlot.STT)
            }
        }
    }

    /** AI 頁類別內的小標題（例如每類底下那個「AI 設定」） */
    private fun subHeader(text: String) {
        content.addView(TextView(this).apply {
            this.text = text
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(16), 0, dp(2))
        })
    }

    /**
     * AI 改寫（工具列的 ✨）。整個功能可以關閉；關閉後連按鍵也不會顯示
     * （見 `TTInputMethodService.applyAiState`）。
     */
    private fun buildRewriteCategory() {
        note("在任何應用程式按工具列的「AI 改寫」鍵：有選取文字就只改選取的部分，" +
            "否則改寫整個輸入框。")
        switch("啟用 AI 改寫", Prefs.KEY_AI_REWRITE_ON, true) { rebuildAiSection() }
        if (!Prefs.aiRewriteOn(this)) {
            note("已關閉：工具列不會出現「AI 改寫」按鍵。")
            return
        }
        if (Prefs.aiApiKey(this).isBlank()) {
            note("⚠️ 尚未設定 API key，「AI 改寫」按鍵不會出現，請在下面的「AI 設定」貼上。")
        }
        buildPromptList()
        subHeader("AI 設定")
        buildAiProviderBlock(AiSlot.REWRITE)
    }

    /**
     * Prompt 清單（[Prefs.KEY_AI_PROMPTS]）。**次序有意思**：按一下工具列那顆
     * 「AI 改寫」用第一個，長按才彈出整張清單讓使用者選，所以最常用那個
     * 應該排第一（下面「設為預設」就是把它搬到最前）。
     *
     * 名稱必須唯一 —— 長按那張清單只靠名字認人，重名會分不出誰是誰
     * （儲存時擋住，見 [editAiPrompt]）。
     */
    private fun buildPromptList() {
        val prompts = Prefs.aiPrompts(this)
        note("按一下「AI 改寫」鍵會直接用清單第一個 Prompt；長按則彈出整張清單讓你選。")
        for ((i, p) in prompts.withIndex()) {
            content.addView(TextView(this).apply {
                text = if (i == 0) "${p.name}（預設）" else p.name
                textSize = 15f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(0, dp(10), 0, 0)
            })
            content.addView(TextView(this).apply {
                // 整段 prompt 攤開會佔滿整頁，這裡只印頭一截認得出是哪個就夠
                text = p.text.replace(Regex("\\s+"), " ").trim().take(90)
                textSize = 12f
                alpha = 0.7f
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            })
            row(
                button("編輯") { editAiPrompt(i) },
                button(if (i == 0) "已是預設" else "設為預設") {
                    if (i == 0) { toast("已經是預設"); return@button }
                    val list = prompts.toMutableList()
                    list.add(0, list.removeAt(i))
                    Prefs.setAiPrompts(this, list)
                    rebuildAiSection()
                    toast("「${p.name}」已設為預設")
                },
                button("刪除") {
                    // 至少要剩一個：短按那下沒有清單可以退回去
                    if (prompts.size <= 1) { toast("至少要保留一個 Prompt"); return@button }
                    Prefs.setAiPrompts(this, prompts.filterIndexed { j, _ -> j != i })
                    rebuildAiSection()
                    toast("已刪除「${p.name}」")
                }
            )
        }
        row(
            button("新增 Prompt") { editAiPrompt(-1) },
            button("還原預設清單") {
                Prefs.setAiPrompts(this, Prefs.defaultAiPrompts())
                rebuildAiSection()
                toast("已還原預設清單（英譯／回答／修飾）")
            }
        )
    }

    /**
     * 新增（[index] < 0）或編輯一個 Prompt。
     *
     * 正面按鍵**不是**用 `setPositiveButton` 那個 listener：那個一按就關窗，
     * 名稱重覆或空白時使用者剛打好的整段 Prompt 就沒有了。改成 show 之後
     * 自己接管 click，驗不過就只出提示、不關窗。
     */
    private fun editAiPrompt(index: Int) {
        val prompts = Prefs.aiPrompts(this).toMutableList()
        val cur = prompts.getOrNull(index)
        val nameEdit = EditText(this).apply {
            hint = "名稱（例如：英譯）"
            setSingleLine()
            setText(cur?.name.orEmpty())
        }
        val textEdit = EditText(this).apply {
            hint = "Prompt（%text% 代表要改寫的文字）"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 4
            gravity = Gravity.TOP or Gravity.START
            setText(cur?.text ?: Prefs.DEFAULT_AI_PROMPT)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(nameEdit, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(textEdit, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (cur == null) "新增 Prompt" else "編輯「${cur.name}」")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("儲存", null)
            .setNegativeButton("取消", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = nameEdit.text.toString().trim()
                val text = textEdit.text.toString()
                if (name.isEmpty()) { toast("請輸入名稱"); return@setOnClickListener }
                if (text.isBlank()) { toast("Prompt 不可以留空"); return@setOnClickListener }
                if (prompts.withIndex().any { (j, q) -> j != index && q.name == name }) {
                    toast("已有名為「$name」的 Prompt"); return@setOnClickListener
                }
                if (cur == null) prompts.add(AiPrompt(name, text))
                else prompts[index] = AiPrompt(name, text)
                Prefs.setAiPrompts(this, prompts)
                dialog.dismiss()
                rebuildAiSection()
                toast("已儲存「$name」")
            }
        }
        dialog.show()
    }

    /**
     * 一組 provider 設定（可重用：「AI 改寫」和「語音輸入」各放一個，[slot] 分開存）。
     *
     * API key 不使用普通輸入框，因為使用者通常只會從其他位置複製後貼上，或直接刪除。
     * 因此介面只提供「貼上／刪除／顯示」三個按鍵和一行狀態文字。
     *
     * 預設用 Gemini；「使用自訂 API」開啟後就改用下面那組範本打任何
     * HTTP POST API（見 `AiRewrite.callCustom`）。整組設定可以用最上面的
     * profile 按鍵 save/load/delete，profile 名單兩邊共用。
     */
    private fun buildAiProviderBlock(slot: AiSlot) {
        buildAiProfileRow(slot)

        content.addView(TextView(this).apply {
            text = "API key"
            textSize = 14f
            setPadding(0, dp(10), 0, 0)
        })
        val keyLabel = note("")
        refreshAiKeyLabel(keyLabel, slot)
        val keyPref = slot.key(Prefs.KEY_AI_KEY)
        row(
            button("貼上") {
                val clip = (getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
                    ?.primaryClip
                val text = clip?.takeIf { it.itemCount > 0 }
                    ?.getItemAt(0)?.coerceToText(this)?.toString()?.trim().orEmpty()
                if (text.isEmpty()) { toast("剪貼簿是空的"); return@button }
                Prefs.sp(this).edit().putString(keyPref, text).apply()
                // 語音輸入的警告字眼跟 key 有沒有設定走，整頁重畫
                rebuildAiSection()
                toast("已貼上 API key")
            },
            button("刪除") {
                Prefs.sp(this).edit().remove(keyPref).apply()
                aiKeyShown.remove(slot)
                rebuildAiSection()
                toast("已刪除 API key")
            },
            button("顯示") {
                if (!aiKeyShown.add(slot)) aiKeyShown.remove(slot)
                refreshAiKeyLabel(keyLabel, slot)
            }
        )

        textField("模型名稱", slot.key(Prefs.KEY_AI_MODEL), Prefs.DEFAULT_AI_MODEL)

        switch("使用自訂 API（而非 Gemini）", slot.key(Prefs.KEY_AI_USE_CUSTOM), false) {
            rebuildAiSection()
        }
        val p = Prefs.aiProvider(this, slot)
        if (!p.useCustom) return

        if (slot == AiSlot.STT) {
            note("錄音會壓縮成 m4a（AAC）送出。Body 範本中寫 %audio% 的位置就是音訊檔：")
            note("• multipart/form-data：每行一個「名稱=值」，值只寫 %audio% 的那行" +
                "會以檔案 audio.m4a 上傳。\n" +
                "• JSON：%audio% 換成 base64 音訊（自己加頭尾引號），%audio_mime% 換成 MIME type。")
            note("預設範本是 OpenAI Whisper：模型名稱填 whisper-1，Body 如下，回應內容路徑填 text。\n" +
                "file=%audio%\nmodel=%model%\nprompt=%text%\nresponse_format=json")
            note("其他代號：%key% = API key、%model% = 模型名稱、%prompt% = 套用後的 STT Prompt、" +
                "%text% = 輸入框現有內容、%lang% = 目標語言。Whisper 的 prompt 欄只是前文提示、" +
                "不是指令，所以範例放 %text% 而不是 %prompt%。")
        } else {
            note("下面範本預設是 OpenAI 相容的 chat completions（JSON）" +
                "（OpenAI、Groq、DeepSeek、OpenRouter、Ollama 等大多適用）。" +
                "範本中 %key% = API key、%model% = 模型名稱、%prompt% = 套用範本後的內容、" +
                "%text% = 待改寫的文字。")
        }
        textField("Request URL", slot.key(Prefs.KEY_AI_URL), Prefs.defaultAiUrl(slot))
        textField("Request Headers（每行一個，例如 Authorization: Bearer %key%）",
            slot.key(Prefs.KEY_AI_HEADERS), Prefs.DEFAULT_AI_HEADERS, multiline = true)
        switch("以 multipart/form-data 送出（而非 JSON）", slot.key(Prefs.KEY_AI_MULTIPART),
            Prefs.defaultAiMultipart(slot)) { rebuildAiSection() }
        textField(
            if (p.multipart) "Request Body（multipart，每行一個 名稱=值）" else "Request Body 範本（JSON）",
            slot.key(Prefs.KEY_AI_BODY), Prefs.defaultAiBody(slot), multiline = true)
        textField("回應內容路徑（例如 " + Prefs.defaultAiResponsePath(slot) + "）",
            slot.key(Prefs.KEY_AI_RESPONSE_PATH), Prefs.defaultAiResponsePath(slot))
        row(button("還原預設範本") {
            val e = Prefs.sp(this).edit()
            for (k in listOf(Prefs.KEY_AI_URL, Prefs.KEY_AI_HEADERS, Prefs.KEY_AI_BODY,
                    Prefs.KEY_AI_MULTIPART, Prefs.KEY_AI_RESPONSE_PATH)) e.remove(slot.key(k))
            e.apply()
            rebuildAiSection()
            toast("已還原預設範本")
        })
    }

    /** 已存 profile 的下拉選單 + 載入／另存新檔／刪除三個按鍵（名單兩組共用，載入只寫 [slot]） */
    private fun buildAiProfileRow(slot: AiSlot) {
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
                Prefs.loadAiProfile(this, name, slot)
                aiKeyShown.remove(slot)
                rebuildAiSection()
                toast("已載入「$name」")
            },
            button("另存新檔") { promptSaveAiProfile(slot) },
            button("刪除") {
                val name = names.getOrNull(spinner.selectedItemPosition)
                if (name == null) { toast("未有已存 Profile"); return@button }
                Prefs.deleteAiProfile(this, name)
                rebuildAiSection()
                toast("已刪除「$name」")
            }
        )
    }

    /** 顯示對話框詢問 profile 名稱，按「儲存」後才寫入 */
    private fun promptSaveAiProfile(slot: AiSlot) {
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
                Prefs.saveAiProfile(this, name, slot)
                rebuildAiSection()
                toast("已儲存「$name」")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 「說明」tab：只一個 WebView 直接顯示 `assets/help.html`，沒有任何互動 */
    private fun buildHelpView(): WebView = WebView(this).apply {
        loadUrl("file:///android_asset/help.html")
    }

    /** 沒有 key 就明確說明沒有，有 key 就預設遮住中間（按「顯示」先查看完整內容） */
    private fun refreshAiKeyLabel(label: TextView, slot: AiSlot) {
        val key = Prefs.aiApiKey(this, slot)
        label.text = when {
            key.isEmpty() && slot == AiSlot.REWRITE -> "尚未設定（「AI 改寫」按鍵不會出現）"
            key.isEmpty() -> "尚未設定"
            slot in aiKeyShown -> key
            key.length <= 8 -> "已設定（" + "•".repeat(key.length) + "）"
            else -> "已設定（" + key.take(4) + "…" + key.takeLast(4) + "）"
        }
    }

    private fun buildSwipeSection() {
        header("滑動輸入 (Swipe)")
        // 「其他」那節的「長按 1~9 開速選字表」與這個掣互斥，開關要即時反映
        // （兩節同在「一般」頁，改完不會重建畫面），見 [refreshLongPressShortcut]
        switch("啟用滑動輸入", Prefs.KEY_SWIPE, true) { refreshLongPressShortcut() }
        note("中文滑動即時出碼：滑過 7→9→3 等與順序按了三下。" +
            "中間經過的格子會依停留時間、轉向角度與字碼表的使用頻率一併判斷。")
        note("開啟滑動輸入時，「長按 1~9 開速選字表」不能同時使用（該選項會收起）。")
        // 「停留」與「轉角」2026-08-29 隱藏過（一般人不需要進行如此細緻的調整），
        // 2026-08-31 應 使用者要求重新顯示做 debug 測試用，
        // 2026-09-13 併入「進階隱藏設定」（連按「一般」分頁三下先顯示）
        if (Prefs.showAdvancedSettings(this)) {
            slider("停留多久當作按下", 60, 400, Prefs.swipeDwellMs(this).toInt(), "ms") { v ->
                Prefs.sp(this).edit().putInt(Prefs.KEY_SWIPE_DWELL, v).apply()
            }
            slider("轉多少度當作轉角", 25, 110, Prefs.swipeAngleDeg(this).toInt(), "°") { v ->
                Prefs.sp(this).edit().putInt(Prefs.KEY_SWIPE_ANGLE, v).apply()
            }
            note("這兩項是滑動途中判斷「這格到底有沒有按過」的門檻，" +
                "調整後可觀察滑動出碼的鬆緊，一般不必改動。")
        }
    }

    /**
     * 使用習慣統計（`usage_stats.db`）：每字打過多少次、連續兩個字的組合各打過多少次。
     *
     * 這檔案與字碼庫（`dataset.db`）**分開存**，換字碼表不會影響它。此處可以
     * 匯出備份、匯入還原、或者整個清除（等於換回一張新表），
     * 也可以關閉「常用字排前」—— 關閉仍然繼續記數，只是不用於排關聯字。
     * （「常用字排前」只移動第 10 位起那部分，第一頁永遠保持不變，見 `TTEngine.reorderByUsage`。）
     */
    private fun buildUsageSection() {
        header("使用習慣統計")
        usageLabel = note("")
        refreshUsageLabel()
        note("每個字輸入過多少次、連續兩個字的組合都記錄在 usage_stats.db，" +
            "與字碼資料庫分開存放，更換字碼資料庫不受影響。")
        switch("常用字排前", Prefs.KEY_USAGE_REORDER, true)
        note("開啟後，第一頁（前 9 個字）永遠維持字碼表次序不變，第 10 個字起" +
            "才依與前一個字的組合次數推前（打過 ${TTEngine.MIN_USAGE_COUNT} 次以上才調動，" +
            "按錯一下不算）。關閉則完全依字碼表次序，但仍會繼續記錄。")
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

    /** 清除後無法復原，必須先徵詢使用者確認 */
    private fun confirmClearUsage() {
        AlertDialog.Builder(this)
            .setTitle("清除使用記錄")
            .setMessage("將刪除所有字數與前後字組合的記錄，無法復原。")
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
        switch("預覽關聯字", Prefs.KEY_RELATE_PREVIEW, true)
        note("開啟後，打完一個字、尚未輸入下一個字碼時，九宮格 1～9 左上角會預覽該字的關聯字，筆形圖縮小至右下角。" +
            "按一次「取消」即恢復原樣。")
        if (SHOW_LEGACY_KEY_OPTIONS) {
            switch("工具列常駐", Prefs.KEY_BAR_PINNED, true)
            note("關閉：右上角的 ☰ 改為開關整條工具列，切換關聯字與工具改按工具列最左的 ⇄。")
        }
        note("工具列固定顯示。切換「關聯字 ⇄ 工具」靠一顆「轉換工具列」按鍵，" +
            "預設在鍵盤右欄最上，位置可以在「按鍵排位」自行調整。")
        // 「按鍵按下時的效果」2026-09-13 併入「進階隱藏設定」（連按「一般」分頁三下先顯示）
        if (Prefs.showAdvancedSettings(this)) {
            val pressEffects = KeyPressEffect.entries.toList()
            enumPicker("按鍵按下時的效果", pressEffects.map { it.label },
                pressEffects.indexOf(Prefs.keyPressEffect(this))) { i ->
                Prefs.setKeyPressEffect(this, pressEffects[i])
                rebuildPreview()
            }
        }
        slider("按鍵震動", 0, Prefs.MAX_VIBRATE_LEVEL, Prefs.vibrateLevel(this), "",
            format = { Prefs.vibrateLevelLabel(it) }) { v ->
            Prefs.setVibrateLevel(this, v)
            previewVibrate(v)
        }
        slider("提示音音量", 0, Prefs.MAX_TONE_LEVEL, Prefs.toneLevel(this), "",
            format = { Prefs.toneLevelLabel(it) }) { v ->
            Prefs.setToneLevel(this, v)
            previewTone(v)
        }
        note("語音輸入的開始、結束、成功、失敗四個提示音，以及載入失敗那下的音量。" +
            "與按鍵聲是兩件事。拉到「關閉」就完全不出聲。")
        note("0 為關閉，1 最輕，2、3 震幅同時間都加大。放手時會震一下讓你試效果。")
        switch("按鍵聲音", Prefs.KEY_SOUND, false)
        // 「長按時間」2026-09-13 併入「進階隱藏設定」（連按「一般」分頁三下先顯示）；
        // 下面那段說明講嘅係「長按有咩用」，同條 slider 無關，所以照舊常駐顯示。
        if (Prefs.showAdvancedSettings(this)) {
            slider("長按時間", 200, 700, Prefs.longPressMs(this).toInt(), "ms", step = 10) { v ->
                Prefs.sp(this).edit().putInt(Prefs.KEY_LONG_PRESS_MS, v).apply()
            }
        }
        note("長按 0 = 成對標點（「」之類）；長按「同音」= 關聯字；" +
            "長按工具列的「貼上」= 剪貼簿記錄；長按 1~9 = 連按兩下。純數字鍵盤沒有長按。")
        longPressShortcutViews = listOf(
            switch("長按 1~9 開速選字表", Prefs.KEY_LONG_PRESS_SHORTCUT, false),
            note("開啟後，未輸入字碼時長按 1~9 直接打開該格的速選字表，" +
                "代價是打不到 77、88 這類要連按兩下的字碼。已輸入字碼後不受影響。" +
                "只在關閉滑動輸入時才有這個選項。")
        )
        refreshLongPressShortcut()

        // 查「明明按了 793，為何出現了第二字」用的 —— 見 `core/InputLog`
        // 與 `scripts/debug-input.sh`。**只有 debug build 看得見**
        // （2026-09-09 使用者要求）：正式版的人開了它只會白白把自己打的字寫進
        // logcat。pref 與 `InputLog` 一行都沒有刪，release 版仍可以用
        // `adb shell setprop log.tag.TTInput DEBUG` 打開，見 AGENTS.md。
        // 2026-09-13 再併入「進階隱藏設定」——debug build 都要先連按「一般」三下。
        if (BuildConfig.DEBUG && Prefs.showAdvancedSettings(this)) {
            switch("記錄輸入過程 (logcat)", Prefs.KEY_INPUT_LOG, false) {
                // 設定頁與 IME service 同一個 process，寫一次立即生效 ——
                // 不用等 `onStartInputView` 重新讀（那句照留，保持開啟鍵盤改設定時先要）
                InputLog.pref = it
            }
            note("除錯用，預設關閉。開啟後每按一鍵、每次滑動判定、每次選字都會寫進 " +
                "Android logcat（標籤 TTInput），電腦端執行 scripts/debug-input.sh 即可" +
                "在 terminal 看到「我按了什麼」與「輸入法收到什麼」的對照。")
            note("紀錄內含你正在輸入的字碼與文字，查完問題請關閉。")
        }

        buildVersionFooter()
    }

    /**
     * 「長按 1~9 開速選字表」只在**關閉滑動輸入**時才出現。
     *
     * 兩者本質上衝突：滑動輸入靠 1~9「按下即出碼」（`ChinesePadView.instantKey`）
     * 才滑得順，而長按開速選字表必須等放開手指才知道是不是長按，一開就把整組
     * 數字鍵改成放手才出碼，滑動立刻變得遲鈍。所以開了滑動就把這個掣連說明一起
     * 收起，功能本身也一定關閉（[Prefs.longPressShortcut] 那邊擋住）。
     *
     * 收起時**不動那個 pref**：關掉滑動輸入後掣會帶著使用者上次的選擇再出現。
     */
    private fun refreshLongPressShortcut() {
        val vis = if (Prefs.swipeEnabled(this)) View.GONE else View.VISIBLE
        longPressShortcutViews.forEach { it.visibility = vis }
    }

    /** 放在「一般」頁最底，以小字顯示目前是哪一個版本（回報問題時有用） */
    private fun buildVersionFooter() {
        val pi = runCatching { packageManager.getPackageInfo(packageName, 0) }.getOrNull()
        val name = pi?.versionName ?: "?"
        val code = when {
            pi == null -> "?"
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> pi.longVersionCode.toString()
            else -> @Suppress("DEPRECATION") pi.versionCode.toString()
        }
        content.addView(TextView(this).apply {
            text = "三三輸入法　版本 $name（build $code）"
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

    /** 深色主題用淺藍，不是使用系統那隻深藍會看不到 */
    private fun linkColor(): Int =
        if (Theme.of(this).dark) Color.parseColor("#7FB3FF") else Color.parseColor("#1A56C4")

    /** 裝置（例如沒有裝過瀏覽器的模擬器）無法開啟就顯示提示，避免應用程式崩潰 */
    private fun openUrl(url: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { toast("無法開啟：$url") }
    }

    /** 拖動完成後震動一下，等 使用者立即感覺到選了那級有多強 */
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

    /** 拖動完成後嘟一聲，等使用者立即聽到選了那級有多大聲（同 [previewVibrate]） */
    private fun previewTone(level: Int) {
        val vol = Prefs.toneVolume(level)
        if (vol <= 0) return
        runCatching {
            val tg = ToneGenerator(AudioManager.STREAM_NOTIFICATION, vol)
            tg.startTone(ToneGenerator.TONE_PROP_BEEP, TONE_PREVIEW_MS)
            // 與 `TTInputMethodService.playTone` 一樣，響完就即刻 release，
            // 不要留住個 audio session
            content.postDelayed({ runCatching { tg.release() } }, TONE_PREVIEW_MS + 100L)
        }
    }

    /**
     * 每種特別欄位一個，用來試「跟輸入欄類型換排位」與「⏎ 跟 imeOptions 換樣」
     * 兩套邏輯（見 AGENTS.md）。整段預設隱藏（[SHOW_DEBUG_SECTIONS]）。
     */
    private fun buildTryBox() {
        header("試打")
        note("每種欄位的鍵盤排位都不同：email 出 @、. 與 .com、網址出 /、. 與 .com、" +
            "密碼收起 , 與 / 改為 - 與 _、電話出 ( ) + #、數字欄只在 signed／decimal " +
            "才出 - 與 .。⏎ 亦會跟 imeOptions 換符號。")
        tryField("普通欄（句首會自動大階）",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE)
        tryField("email 欄", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        tryField("網址欄", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        tryField("密碼欄", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        tryField("PIN 欄", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        tryField("電話欄", InputType.TYPE_CLASS_PHONE)
        tryField("數字欄（純 number）", InputType.TYPE_CLASS_NUMBER)
        tryField("金額欄（signed + decimal）", InputType.TYPE_CLASS_NUMBER or
            InputType.TYPE_NUMBER_FLAG_SIGNED or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        tryField("日期欄", InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_DATE)
        tryField("時間欄", InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME)
        tryField("搜尋（⏎ = ⌕）", InputType.TYPE_CLASS_TEXT, EditorInfo.IME_ACTION_SEARCH)
        tryField("完成（⏎ = ✓）", InputType.TYPE_CLASS_TEXT, EditorInfo.IME_ACTION_DONE)
        tryField("傳送（⏎ = ➤）", InputType.TYPE_CLASS_TEXT, EditorInfo.IME_ACTION_SEND)
        tryField("多行＋傳送（⏎ = ➤）",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE,
            EditorInfo.IME_ACTION_SEND)
        tryField("多行＋不准動作（⏎ 應換行）",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE,
            EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_ENTER_ACTION)
        tryField("前往（⏎ = →）", InputType.TYPE_CLASS_TEXT, EditorInfo.IME_ACTION_GO)
        tryField("下一個（⏎ = ⇥）", InputType.TYPE_CLASS_TEXT, EditorInfo.IME_ACTION_NEXT)
        tryField("上一個（⏎ = ⇤）", InputType.TYPE_CLASS_TEXT, EditorInfo.IME_ACTION_PREVIOUS)
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

    /** 預覽已隱藏（[SHOW_DEBUG_SECTIONS]）時 `previewHolder` 是 null，整個函式會立即結束 */
    private fun rebuildPreview() {
        val holder = previewHolder ?: return
        holder.removeAllViews()
        val db = runCatching { TTDb.open(this) }.getOrNull() ?: return
        val engine = TTEngine(db)
        val pad = ChinesePadView(this, engine).apply {
            applyTheme(Theme.of(this@SettingsActivity))
            chineseHost = object : ChinesePadView.ChineseHost {
                override fun pressDigit(digit: Int) { engine.press(digit); invalidate() }
                override val optionOn: Boolean
                    get() = Prefs.barMode(this@SettingsActivity) != BarMode.OFF
                override val aiReady: Boolean get() = false
                override val copyReady: Boolean get() = false
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

    // ---- 輔助元件 -------------------------------------------------------------

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
     * 一個 [PadFunc] 下拉式選單（左上角鍵的短按／長按）。
     *
     * **不可以用「跳過第一下 callback」那招**去擋開頭那次 programmatic selection：
     * `Spinner` 第一下 `onItemSelected` 何時 fire（甚至 fire 不 fire）是看 layout
     * 時序的，擋錯了就會攔截了 使用者真正那次 —— 按鍵看起來移動了、但 pref 沒有改過、
     * 上面標籤 也仍是舊那個，然後去第二個 spinner 選回同一樣內容就會提示
     * 「功能重覆」。
     *
     * 所以改成**與目前真正儲存那值比**：一樣就當是回位／開場，任何事都不做；
     * 值不同才表示使用者確實作出了選擇。標籤每次都由 [get] 重新讀取，
     * 就算 [onPick] 拒絕了都不會與個 pref 不夾。
     *
     * 選單**不會因為其他位置正在使用就隱藏選項**——四個位置可以選同一件事
     * （見 [Prefs.FUNC_SLOTS]），重複時只在這位變紅框、加句提示字
     * （見 [refreshDuplicateState]），不會阻止儲存，兩位仍然各自生效。
     */
    private inner class FuncPicker(
        private val titleText: String,
        private val options: () -> List<PadFunc>,
        private val get: () -> PadFunc,
        private val onPick: (PadFunc) -> Unit
    ) {
        private var shown: List<PadFunc> = emptyList()
        private var listener: AdapterView.OnItemSelectedListener? = null

        private val title = TextView(this@SettingsActivity).apply {
            textSize = 14f
            setPadding(0, dp(6), 0, dp(2))
        }
        private val spinner = Spinner(this@SettingsActivity)
        private val border = GradientDrawable().apply {
            setColor(android.graphics.Color.TRANSPARENT)
            cornerRadius = dp(10).toFloat()
        }
        private val warning = TextView(this@SettingsActivity).apply {
            textSize = 12f
            setTextColor(android.graphics.Color.rgb(200, 40, 40))
            setPadding(0, 0, 0, dp(8))
            visibility = View.GONE
        }

        init {
            fill()
            content.addView(title)
            // 選單 要有框先似可按的元件（Spinner 只有文字與支箭頭）
            val box = FrameLayout(this@SettingsActivity).apply {
                background = border
                setPadding(dp(6), dp(2), dp(6), dp(2))
                addView(spinner, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            content.addView(box, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(4)) })
            content.addView(warning)

            listener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    val picked = shown.getOrNull(pos)
                    // 與目前儲存那個一樣 = 開場／回位，不算 使用者選過內容
                    if (picked != null && picked != get()) onPick(picked)
                    refreshTitle()
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
            spinner.onItemSelectedListener = listener
            refreshTitle()
            refreshDuplicateState()
        }

        private fun fill() {
            spinner.onItemSelectedListener = null
            shown = options()
            spinner.adapter = ArrayAdapter(
                this@SettingsActivity, android.R.layout.simple_spinner_item,
                shown.map { it.label }
            ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            spinner.setSelection(shown.indexOf(get()).coerceAtLeast(0), false)
            spinner.onItemSelectedListener = listener
        }

        private fun refreshTitle() {
            title.text = "$titleText：${get().label}"
        }

        /**
         * 這位目前選那個是否與其他位重複時——重複時就紅框加提示字，但不會擋，
         * 重複的數個位置仍然各自生效（見 [Prefs.FUNC_SLOTS]）。
         */
        private fun refreshDuplicateState() {
            val mine = get()
            val duplicate = mine != PadFunc.NONE &&
                funcPickers.any { it !== this && it.current() == mine }
            border.setStroke(
                dp(1).coerceAtLeast(1),
                if (duplicate) android.graphics.Color.rgb(200, 40, 40)
                else android.graphics.Color.argb(110, 128, 128, 128)
            )
            warning.visibility = if (duplicate) View.VISIBLE else View.GONE
            if (duplicate) warning.text = "「${mine.label}」與另一位置重複，兩位會同時生效。"
        }

        /** 這個位置目前儲存的值（供 [refreshDuplicateState] 兩兩比較） */
        fun current(): PadFunc = get()

        /** 由 pref 將目前狀態同步至按鍵和標籤（[onPick] 完成後就要叫） */
        fun sync() {
            if (shown != options()) {
                fill()
            } else {
                val i = shown.indexOf(get()).coerceAtLeast(0)
                if (spinner.selectedItemPosition != i) spinner.setSelection(i)
            }
            refreshTitle()
            refreshDuplicateState()
        }
    }

    /**
     * 將一個功能選單加入版面。
     *
     * [allowNone] = false 只左上角短按（顆按鍵按下後不能沒有任何作用）。
     */
    private fun funcPicker(title: String, slot: String, allowNone: Boolean) {
        funcPickers.add(FuncPicker(
            title, { availableFuncs(allowNone) }, { Prefs.funcSlot(this, slot) }
        ) { picked ->
            Prefs.setFunc(this, slot, picked)
            syncFuncPickers()
            rebuildPreview()
        })
    }

    /**
     * 這位置可選擇的項目：**永遠是整個 [PadFunc] 清單**，不會因為其他位置正在使用就在
     * 在選單中隱藏——四個位置可以選同一件事，重複時該位置顯示紅色外框和提示文字
     * （見 [FuncPicker.refreshDuplicateState]）。[allowNone] = false 只左上角短按。
     */
    private fun availableFuncs(allowNone: Boolean): List<PadFunc> =
        LEGACY_FUNCS.filter { allowNone || it != PadFunc.NONE }

    /**
     * 一個普通下拉式選單（[options] 是已經寫好的字，[onPick] 收第幾個）。
     *
     * 與 [FuncPicker] 一樣要擋開頭那次 programmatic selection，但此處沒有 pref
     * 可以比對，所以自己記住目前是第幾個：`Spinner` 第一下 `onItemSelected`
     * 何時 fire（甚至 fire 不 fire）是看 layout 時序的，不擋就會當 使用者選過內容。
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
        // 與 FuncPicker 一樣畫外框，不是看起來不似可按的元件
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
     * 一段可收合的內容：按標題即可開啟／關閉。[body] 展開時才執行，
     * 收合後不會建立任何 view（成段內容是 rebuild 出來的，不用隱藏容器）。
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
     * 一條 slider。[step] = 拖一格跳多少（例如長按時間逐 10ms 一格，
     * 不用在 500 個值之間慢慢選擇），[format] = 值的顯示方式（不寫就 `值+單位`）。
     *
     * `onChange` 只在**放手**（`onStopTrackingTouch`）先叫，拖動期間只更新上方文字。
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

    /**
     * [enabled] = false：按保持不變（目前不允許開啟的項目）
     *
     * 回傳那個 `Switch`，需要事後收起／改動它的（見 [refreshLongPressShortcut]）才用得著。
     */
    @Suppress("DEPRECATION")
    private fun switch(label: String, key: String, def: Boolean, enabled: Boolean = true,
                       onChange: ((Boolean) -> Unit)? = null): Switch {
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
        return s
    }

    /** 一格文字設定，輸入後自動儲存（不用再按「儲存」）。多行欄位會畫個圓角外框。 */
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
                // 多行 prompt 沒有外框很難看出哪裡到哪裡，加上圓角外框
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
