package tt.ime.riverine.ime

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import tt.ime.riverine.core.AiRewrite
import tt.ime.riverine.core.AiStt
import tt.ime.riverine.core.AutoCaps
import tt.ime.riverine.core.BarMode
import tt.ime.riverine.core.ClipHistory
import tt.ime.riverine.core.EmojiDict
import tt.ime.riverine.core.EnDict
import tt.ime.riverine.core.InputLog
import tt.ime.riverine.core.KeyLayout
import tt.ime.riverine.core.NextWordModel
import tt.ime.riverine.core.PadAlign
import tt.ime.riverine.core.PadGroup
import tt.ime.riverine.core.PagerLayout
import tt.ime.riverine.core.Prefs
import tt.ime.riverine.core.TTDb
import tt.ime.riverine.core.TextEdit
import tt.ime.riverine.core.TTCmd
import tt.ime.riverine.core.TTEngine
import tt.ime.riverine.core.UsageStats
import tt.ime.riverine.core.VoiceClip
import tt.ime.riverine.core.VoiceRecorder
import tt.ime.riverine.swipe.GestureDecoder
import tt.ime.riverine.ui.MicPermissionActivity
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

enum class PadMode { CHINESE, LATIN, SYMBOL, NUMBER, EMOJI }

/** 三三輸入法 (ThreeThree) */
class TTInputMethodService : android.inputmethodservice.InputMethodService(),
    TTEngine.Host, KeyboardBaseView.Host, ChinesePadView.ChineseHost,
    LatinPadView.LatinHost, EmojiPadView.EmojiHost, OptionBarsView.Listener {

    private var db: TTDb? = null
    private lateinit var engine: TTEngine

    /** 最外層：平時淨係包住 [root]，AI 處理緊嗰陣加多層 disable overlay 蓋晒佢 */
    private lateinit var outer: FrameLayout
    private lateinit var root: LinearLayout
    private lateinit var bars: OptionBarsView
    private lateinit var padHolder: FrameLayout
    private var chinesePad: ChinesePadView? = null
    private var latinPad: LatinPadView? = null
    private var symbolPad: SymbolPadView? = null
    private var numberPad: NumberPadView? = null
    private var emojiPad: EmojiPadView? = null
    private var overlay: View? = null
    /**
     * 中文本體拉到夠窄嗰陣，上面條 bar 收埋、內容搬去空出嚟嗰邊（見 [refreshSidePanel]）。
     * 唔夠窄就一路係 null／detach 咗，成套行為同以前一模一樣。
     */
    private var sidePanel: SidePanelView? = null
    /** 關聯字 bar 拉大咗：`bars.expandedView` 蓋住成個鍵盤（見 [onExpandChanged]） */
    private var candidatesExpanded = false
    private var aiOverlay: View? = null
    private var aiGeneration = 0

    private var mode = PadMode.CHINESE
    private var theme = Theme(false)
    private var barMode = BarMode.CANDIDATES
    private var enterLabel = "⏎"

    /** 英文鍵盤底行要跟邊套排位（見 [LatinField]） */
    private var latinField = LatinField.NORMAL
    /** 密碼欄：唔滑動、唔出打字提示（打緊嘅密碼唔應該喺候選欄現形） */
    private val passwordField get() = latinField == LatinField.PASSWORD
    /** 呢個欄準唔準句首自動大階（見 [updateAutoCaps]） */
    private var autoCapsField = false
    /** 純數字鍵盤要出邊套排位（見 [NumField]），連埋 `number` 欄收唔收 `-` / `.` */
    private var numField = NumField.CALC
    private var numSigned = false
    private var numDecimal = false
    /** URL／email／密碼／關咗提示嘅欄：唔好自作聰明補空格（見 [autoSpaceAfterPunct]） */
    private var noAutoSpaceField = false
    private var hasSelection = false
    /** 而家有冇嘢俾 AI 改（揀咗一段，或者成個輸入框有字） */
    private var aiUsable = false
    /** 設定頁有冇入 Gemini API key —— 冇就成粒 ✨ 唔見咗，唔係淨係灰咗 */
    private var aiKeySet = false
    /** 個欄係咪一隻字都冇（揀咗字或者成個欄有嘢就唔算），複製鍵撳唔撳得靠呢個 */
    private var fieldHasText = false
    private val latinComposing = StringBuilder()
    private var latinSuggestions: List<String> = emptyList()

    /** 見 [latinDefaultSuggestions]（載好詞庫之前一路係吉） */
    private var defaultLatinSuggestions: List<String> = emptyList()

    /**
     * 啱啱出咗一個完整嘅英文字（滑出嚟嘅，或者喺候選欄揀咗嘅），中間冇再郁過。
     * 下一次滑就係下一個字 → 自動加返個空格，亦都唔會攞前面嗰個字當 context。
     */
    private var latinWordDone = false
    /**
     * `latinComposing` 而家嗰個字係啱啱滑出嚟、仲未經手打過一個字母嘅（underline 狀態，
     * 代表未決定係咪呢個字）。呢個狀態下撳 backspace 要成個字一次過剷晒
     * （唔係逐個字母剷）——打字先，一打字就代表 user 肯定咗呢個字，翻返做逐字母刪。
     */
    private var latinSwiped = false
    /** 滑完之後夾硬出候選欄，等 user 揀第二個字（就算條 bar 本身係關住） */
    private var forceCandidates = false
    /** 啱啱完成嘅上一個英文字，畀 [tt.ime.riverine.core.NextWordModel] 估下一個字用 */
    private var lastCommittedWord = ""
    private var lastShiftTapAt = 0L
    /**
     * user 自己撳過粒 ⇧（開咗或者熄咗），句首自動大階要收手 ——
     * 一打到落字就清返（見 [updateAutoCaps]）。
     */
    private var shiftManual = false

    // 搵 emoji：打嘅字唔會入去個欄，淨係用嚟篩
    private var emojiSearch = false
    private val emojiQuery = StringBuilder()
    private var emojiResults: List<String> = emptyList()
    private var emojiReturnMode = PadMode.CHINESE

    /**
     * 中文候選欄「未打碼、未選字」嗰陣頂上嘅字。**唔再係速選字表（id 1000）**——
     * 而家跟返**游標前面嗰隻字**嘅關聯字（見 [contextPicks]），
     * 前面吉住／唔係中文就用 [DEFAULT_PICK_ID]（`mapped_table` id 1010）。
     */
    private var defaultPicks: List<String> = emptyList()
    /**
     * 條 bar 而家出緊嘅唔係 engine 嘅選字表，而係 [contextPicks] 或者
     * [codePreview] —— 撳落去要行 `TTEngine.pickQuick()`（根本未入過選字模式）。
     */
    private var showingContextPicks = false
    /** 上面兩者而家出緊嗰個 list（[onPickCandidate] 要攞返個字） */
    private var contextBarPicks: List<String> = emptyList()

    // 打咗 1~2 個碼嗰陣嘅「最常用嗰九隻字」預覽，同一個碼唔使查兩次
    private var codePreviewFor = ""
    private var codePreviewList: List<String> = emptyList()

    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private val ui = Handler(Looper.getMainLooper())

    // ---- AI 語音輸入（[Prefs.aiSttOn] 開咗就頂走上面嗰個系統 recognizer）--------
    /** 錄緊嘢就唔係 null。放咗手／出咗結果就清返 */
    private var sttRecorder: VoiceRecorder? = null
    /** 撳實 🎤 錄嗰種（放手就收工）；撳一下開始嗰種係 false，要再撳一下先停 */
    private var sttHold = false
    /** 錄緊或者等緊 Gemini 回覆：成個鍵盤蓋住咗，唔好再開多一次 */
    private var sttBusy = false
    /** 同 [aiGeneration] 一樣：逾時之後遲到嘅回覆要當第 */
    private var sttGeneration = 0
    private var sttTimerLabel: TextView? = null

    // ---- 短錄音改用系統 STT（[Prefs.aiSttSysSec]）----------------------------
    /** 今次錄音陪住一齊開嗰個系統 recognizer。過咗界 cancel 咗就變返 null */
    private var sysStt: SpeechRecognizer? = null
    /** 錄到幾多毫秒就唔再靠系統嗰邊（0 = 呢招熄咗，一律用 AI） */
    private var sysSttDeadlineMs = 0L
    /** 系統嗰邊而家最好嗰句：有 final 就 final，冇就最後嗰段 partial */
    private var sysSttText: String? = null
    /** 系統嗰邊派過 final callback（`onResults`／`onError`），唔使再等 */
    private var sysSttFinished = false
    /** 上面嗰下發生嗰陣錄咗幾耐；-1 = 放咗手先發生（正路） */
    private var sysSttEndedAtMs = -1L
    /** 放咗手、等緊系統嗰邊交貨；佢一 final 就叫呢個 */
    private var sysSttPending: ((String?) -> Unit)? = null
    /** 等到夠鐘就自己埋單嗰個 timeout（[SYS_STT_WAIT_MS]） */
    private var sysSttTimeout: Runnable? = null
    /** 而家夾硬靜咗邊幾條 stream（見 [muteEarcons]），吉 = 冇靜過 */
    private var mutedStreams: List<Int> = emptyList()
    /** 還原音量嗰個 runnable（收工嗰下同埋 [EARCON_MUTE_MAX_MS] 安全網共用） */
    private var unmuteRun: Runnable? = null

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        runCatching { ClipHistory.current(this) }
    }

    /**
     * 九宮格右上角嗰粒要唔要著燈。平時 = 條 bar 開住；條 bar 常駐嗰陣粒鍵已經
     * 唔再係開關，而係關聯字 ⇄ 工具嘅切換掣，所以改為代表「而家見到工具嗰行」——
     * 一路著住藍燈冇資訊可言。收起咗成條 bar（闊 screen）就梗係唔著。
     */
    override val optionOn: Boolean
        get() = if (Prefs.barPinned(this)) barMode.hasTools && !Prefs.barHidden(this)
                else barMode != BarMode.OFF
    override val aiReady: Boolean get() = aiUsable
    override val copyReady: Boolean get() = fieldHasText

    // ---- lifecycle --------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        TTDb.ensureInstalled(this)
        db = runCatching { TTDb.open(this) }.onFailure { Log.e(TAG, "開唔到資料庫", it) }.getOrNull()
        engine = TTEngine(db ?: run {
            // 資料庫壞咗就用返內置嗰個
            TTDb.installFromAssets(this)
            TTDb.open(this).also { db = it }
        })
        engine.host = this
        engine.scOutput = Prefs.scOutput(this)
        engine.usageReorder = Prefs.usageReorder(this)
        InputLog.pref = Prefs.inputLog(this)
        barMode = Prefs.barMode(this)
        // 好舊嘅 dataset.db 冇 id 1010（亦都冇 word_meta.freq / .code）。升級而家會
        // 自動換返新嗰份內置表，但係 user 自己揀過 sqlite 嗰啲就唔會踩親（見
        // `TTDb.ensureInstalled`）—— 攞唔到就跌返落速選字表（id 1000，即係以前
        // 嘅做法），總好過條 bar 一路吉住。想攞返新功能就撳「還原內置字碼表」。
        defaultPicks = runCatching {
            db?.keyInput(DEFAULT_PICK_ID)?.takeIf { it.isNotEmpty() }
                ?: db?.keyInput(LEGACY_PICK_ID).orEmpty()
        }.getOrDefault(emptyList()).filter { it.isNotEmpty() && it != "*" }
        clipboard()?.addPrimaryClipChangedListener(clipListener)
        UsageStats.get(this) // 背景 thread 偷偷載返之前記低嘅 bigram / 每字次數
    }

    override fun onDestroy() {
        stopStt()
        cancelAiStt()
        unmuteEarcons() // 收檔前一定要還原，唔可以留低部機靜咗
        clipboard()?.removePrimaryClipChangedListener(clipListener)
        db?.close()
        super.onDestroy()
    }

    private fun clipboard(): ClipboardManager? =
        getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

    override fun onCreateInputView(): View {
        theme = Theme.of(this)
        StrokeImages.configure(theme.dark)
        applyThemeToPads() // 啲 pad cache 住唔會跟住 recreate，要自己補返色
        Thread { StrokeImages.preload(this) }.start()

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(theme.background)
        }
        bars = OptionBarsView(this).apply {
            listener = this@TTInputMethodService
            applyTheme(theme)
        }
        padHolder = FrameLayout(this)
        // 條 bar 係新起嘅，一定係收埋咗嗰個樣 —— 唔清返個 flag，撳返粒 ▼ 就會
        // 以為「已經拉大咗」乜都唔做（見 [onExpandChanged]）
        candidatesExpanded = false

        root.addView(bars, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(padHolder, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // 包多層 FrameLayout：AI 處理緊嗰陣要喺呢層加返個 disable overlay 蓋晒成個鍵盤，
        // root 本身係 LinearLayout（bars 疊 padHolder），冇得喺度再疊多層
        outer = FrameLayout(this)
        // 底下俾導覽列（「收起鍵盤／轉鍵盤」嗰條）閃開嘅位係呢層嘅 padding，
        // 冇底色就會透見住下面個 app，一忽色唔同好突兀 —— 補返鍵盤自己個底色
        outer.setBackgroundColor(theme.background)
        outer.addView(root, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))

        // targetSdk 35+ 之後 IME window 一路去到螢幕最底，要自己閃開導覽列
        ViewCompat.setOnApplyWindowInsetsListener(outer) { v, insets ->
            val bottom = insets.getInsets(
                WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.captionBar()
            ).bottom
            v.setPadding(0, 0, 0, if (bottom > 0) bottom else fallbackNavBarPx())
            insets
        }

        switchMode(mode, force = true)
        return outer
    }

    /** 有啲機／模擬器唔會經 insets 報返嚟，唯有攞返系統嘅高度 */
    private fun fallbackNavBarPx(): Int {
        val id = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else 0
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    /** 就算插咗實體鍵盤都照出，唔好淨係得 candidate bar */
    override fun onEvaluateInputViewShown(): Boolean = true

    /**
     * 啲 pad（同側邊欄）而家套緊邊個主題。
     *
     * [outer]／[root]／[bars] 每次 [onCreateInputView] 都重新起過，跟得實 [theme]；
     * 但係啲 pad **cache 住一世**（起一次就一直留喺 [chinesePad] 嗰堆 field 度），
     * 淨係出世嗰陣 `applyTheme` 過一次。所以要另外記低套咗邊個色落佢哋度。
     */
    private var padsDark: Boolean? = null

    private fun applyThemeToPads() {
        if (padsDark == theme.dark) return
        padsDark = theme.dark
        chinesePad?.applyTheme(theme)
        latinPad?.applyTheme(theme)
        symbolPad?.applyTheme(theme)
        numberPad?.applyTheme(theme)
        emojiPad?.applyTheme(theme)
        sidePanel?.applyTheme(theme)
    }

    /**
     * 系統 dark/light 轉咗就喺度補返色。轉主題通常會連 input view 都重新 create
     * （[onCreateInputView] 度已經處理），但係唔可以靠得實佢一定會發生 ——
     * 每次彈鍵盤 check 多次，最多都係白行一句比較。
     */
    private fun refreshThemeIfChanged() {
        if (!::root.isInitialized) return
        val fresh = Theme.of(this)
        if (fresh.dark != theme.dark) {
            theme = fresh
            StrokeImages.configure(theme.dark)
            root.setBackgroundColor(theme.background)
            outer.setBackgroundColor(theme.background)
            bars.applyTheme(theme)
        }
        applyThemeToPads()
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        refreshThemeIfChanged()
        engine.scOutput = Prefs.scOutput(this)
        // 設定頁改完個開關唔會 restart 個 service，所以每次入欄都要重新讀
        engine.usageReorder = Prefs.usageReorder(this)
        InputLog.pref = Prefs.inputLog(this)
        barMode = Prefs.barMode(this)
        latinComposing.setLength(0)
        latinSuggestions = emptyList()
        latinWordDone = false
        shiftManual = false
        latinSwiped = false
        forceCandidates = false
        lastCommittedWord = ""
        endEmojiSearch()
        hideOverlay()
        engine.cancel()

        enterLabel = enterLabelFor(info)
        chinesePad?.onSettingsChanged()
        latinPad?.rebuild()

        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        val isText = cls == InputType.TYPE_CLASS_TEXT
        val isEmail = isText && (variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS)
        val isNumberPassword = cls == InputType.TYPE_CLASS_NUMBER &&
            variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
        val isUri = isText && variation == InputType.TYPE_TEXT_VARIATION_URI
        val isPassword = isText && (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
        val isFilter = isText && variation == InputType.TYPE_TEXT_VARIATION_FILTER
        val noSuggestions = (info.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0

        noAutoSpaceField = isUri || isEmail || isPassword || isNumberPassword || noSuggestions
        // 句首自動大階：淨係普通文字欄。URL／email／密碼打大階會直接打錯嘢，
        // 篩選欄（`textFilter`）打一兩個字就篩，自動大階淨係阻住（見 [updateAutoCaps]）
        autoCapsField = isText && !isEmail && !isUri && !isPassword && !isFilter
        latinField = when {
            isEmail -> LatinField.EMAIL
            isUri -> LatinField.URI
            isPassword -> LatinField.PASSWORD
            else -> LatinField.NORMAL
        }
        latinPad?.fieldKind = latinField
        numField = when {
            isNumberPassword -> NumField.PIN
            cls == InputType.TYPE_CLASS_PHONE -> NumField.PHONE
            cls == InputType.TYPE_CLASS_DATETIME -> when (variation) {
                InputType.TYPE_DATETIME_VARIATION_DATE -> NumField.DATE
                InputType.TYPE_DATETIME_VARIATION_TIME -> NumField.TIME
                else -> NumField.DATETIME
            }
            cls == InputType.TYPE_CLASS_NUMBER -> NumField.NUMBER
            // 唔係數字欄（由符號頁撳 `123` 入嚟）就照出計數嗰套
            else -> NumField.CALC
        }
        numSigned = (info.inputType and InputType.TYPE_NUMBER_FLAG_SIGNED) != 0
        numDecimal = (info.inputType and InputType.TYPE_NUMBER_FLAG_DECIMAL) != 0
        numberPad?.setField(numField, numSigned, numDecimal)
        hasSelection = currentInputConnection?.getSelectedText(0)?.isNotEmpty() == true
        refreshAiState()

        val want = when {
            cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE ||
                cls == InputType.TYPE_CLASS_DATETIME -> PadMode.NUMBER
            isEmail || isUri || isPassword -> PadMode.LATIN
            else -> PadMode.CHINESE
        }
        switchMode(want, force = true)
        updateAutoCaps()
        refreshBars()
        scheduleSizeRecheck()
    }

    /** 由**冇到有**出鍵盤嗰下個窗啱啱先定形，遲少少要再度一次（見 [scheduleSizeRecheck]） */
    override fun onWindowShown() {
        super.onWindowShown()
        scheduleSizeRecheck()
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        ui.removeCallbacks(sizeRecheck)
    }

    /** 轉橫直／摺機開合：`Prefs.profKey` 轉咗組，成塊鍵盤要重新度過 */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        scheduleSizeRecheck()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        ui.removeCallbacks(sizeRecheck)
        stopStt()
        // 個欄冇咗就冇地方入返段字，唔好嘥個 API call（亦都唔好留住支咪）
        cancelAiStt()
        finishLatinComposing()
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd,
            candidatesStart, candidatesEnd)
        val sel = newSelStart != newSelEnd
        // 揀唔揀咗字唔緊要，個欄有冇字都會影響 ✨ 撳唔撳得（冇揀就當改寫成個欄），
        // 所以每次都要重新計，唔可以好似以前咁「揀嘅狀態冇變就 return」
        hasSelection = sel
        // caret 唔喺最頭 = 前面實有字，慳返一次 IPC
        applyAiState(sel || newSelStart > 0 ||
            !currentInputConnection?.getTextAfterCursor(1, 0).isNullOrEmpty())
        // 候選欄係跟住**游標前面嗰隻字**行（見 [contextPicks]），所以游標一郁就要重出。
        // 打緊碼／揀緊字嗰陣個表同游標冇關，唔使嘥呢次 IPC。
        if (mode == PadMode.CHINESE && !engine.busy && !emojiSearch) refreshBars()
        // caret 郁咗（自己撳、揀字、app 自己改）都要重新計句首自動大階
        updateAutoCaps()
    }

    /**
     * `⏎` 跟欄位嘅 `imeOptions` 換樣（全部單色符號，見 [SEARCH_GLYPH] 嗰段）：
     * 完成 ✓、搜尋 ⌕、傳送 ➤、前往 →、下一個 ⇥、上一個 ⇤。
     *
     * `actionUnspecified` / `actionNone`／ multi-line（框架會加
     * [EditorInfo.IME_FLAG_NO_ENTER_ACTION]）就照出返 `⏎` —— 嗰啲欄撳落去
     * 係真係換行，唔好扮到似「撳咗就走」。呢度同 [enter] 嗰邊嘅條件要一模一樣。
     */
    private fun enterLabelFor(ei: EditorInfo?): String {
        val opts = ei?.imeOptions ?: 0
        if ((opts and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0) return "⏎"
        return when (opts and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_DONE -> DONE_GLYPH
            EditorInfo.IME_ACTION_SEARCH -> SEARCH_GLYPH
            EditorInfo.IME_ACTION_SEND -> SEND_GLYPH
            EditorInfo.IME_ACTION_GO -> GO_GLYPH
            EditorInfo.IME_ACTION_NEXT -> NEXT_GLYPH
            EditorInfo.IME_ACTION_PREVIOUS -> PREV_GLYPH
            else -> "⏎"
        }
    }

    // ---- view 切換 --------------------------------------------------------

    /**
     * 上一個 pad 有幾高：emoji 表同剪貼簿都跟住佢，唔好一開就成個窗跳高跳低。
     * 要喺 `removeAllViews()` 之前先記低，唔係就已經冇咗個 child 度高。
     */
    private var padHeightPx = 0

    private fun rememberPadHeight() {
        val h = if (::padHolder.isInitialized) padHolder.getChildAt(0)?.height ?: 0 else 0
        if (h > 0) padHeightPx = h
    }

    private fun switchMode(m: PadMode, force: Boolean = false) {
        if (!::padHolder.isInitialized) { mode = m; return }
        if (m == mode && !force && padHolder.childCount > 0) return
        // 搵 emoji 可以喺英文（打 cat）或者中文（打「貓」）鍵盤度做，
        // 去到第二啲 view 就當唔搵住
        if (m != PadMode.LATIN && m != PadMode.CHINESE) endEmojiSearch()
        hideOverlay()
        bars.forceCollapse() // 唔係就下面 removeAllViews() 會靜靜雞清埋拉大咗嘅關聯字 view
        rememberPadHeight()
        mode = m
        finishLatinComposing()
        padHolder.removeAllViews()
        val v: View = when (m) {
            PadMode.CHINESE -> chinesePad ?: ChinesePadView(this, engine).also {
                it.host = this; it.chineseHost = this; it.applyTheme(theme); chinesePad = it
            }
            PadMode.LATIN -> {
                // 20 萬字嘅詞庫，見到英文 view 先至喺背景偷偷載，唔會阻住開鍵盤
                EnDict.preloadAsync(this)
                NextWordModel.preloadAsync(this)
                preloadGestureDecoder()
                (latinPad ?: LatinPadView(this).also {
                    it.host = this; it.latinHost = this; it.applyTheme(theme); latinPad = it
                }).also { it.fieldKind = latinField }
            }
            PadMode.SYMBOL -> symbolPad ?: SymbolPadView(this).also {
                it.host = this; it.applyTheme(theme); symbolPad = it
            }
            PadMode.NUMBER -> (numberPad ?: NumberPadView(this).also {
                it.host = this; it.applyTheme(theme); numberPad = it
            }).also { it.setField(numField, numSigned, numDecimal) }
            PadMode.EMOJI -> (emojiPad ?: EmojiPadView(this).also {
                it.emojiHost = this; it.applyTheme(theme); emojiPad = it
            }).also {
                it.forcedHeightPx = padHeightPx
                it.rebuild()
            }
        }
        (v.parent as? ViewGroup)?.removeView(v)
        // 鍵盤本體自己喺 `PadMetrics.offsetX` 度排位（仲要留位俾側邊欄），所以鋪滿成行；
        // emoji 表嗰類「功能表」就跟住顯示方式擺位（見 [panelLayoutParams]）
        padHolder.addView(v, if (v is KeyboardBaseView)
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        else panelLayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT))
        (v as? KeyboardBaseView)?.enterLabel = enterLabel
        (v as? RowsPadView)?.rebuild()
        (v as? ChinesePadView)?.onSettingsChanged()
        // 啱啱轉去英文（例如中文頁撳 `Eng`）：句首就要即刻著返大階
        updateAutoCaps()
        refreshBars()
    }

    /**
     * 而家見緊嗰個 pad 攞邊套大細設定。英文同符號自成一組，其餘（中文九宮格、
     * 純數字、emoji 表）都跟返中文嗰組 —— 拉大細嗰陣淨係應該郁到眼前嗰組。
     */
    private val padGroup: PadGroup
        get() = if (mode == PadMode.LATIN || mode == PadMode.SYMBOL) PadGroup.LATIN else PadGroup.CJK

    /** 拉高拉低之後，所有已經砌咗嘅 pad 都要重新排位 */
    private fun relayoutPads() {
        chinesePad?.onSettingsChanged()
        latinPad?.rebuild()
        symbolPad?.rebuild()
        numberPad?.rebuild()
        padHeightPx = 0
        emojiPad?.forcedHeightPx = 0
        overlay?.requestLayout()
        // 改咗顯示方式／拉過闊窄：攤開住嗰張功能表要即刻跟住搬位（闊度喺
        // LayoutParams 度，齋 requestLayout 係唔會變嘅）
        refreshPanelLayout(overlay)
        refreshPanelLayout(emojiPad)
        refreshExpandedLayout()
    }

    // ---- 開鍵盤嗰下再度多次尺寸 --------------------------------------------

    /** 仲有幾多次補度（見 [scheduleSizeRecheck]），0 = 呢一輪度完 */
    private var sizeRechecksLeft = 0

    /**
     * 呢一輪仲准重排幾多次（見 [recheckPadSize]）。改完個窗要下一個 layout pass
     * 先跟得上，所以改完會再補度多一輪；有上限就唔會度極都唔啱一路重排落去。
     */
    private var sizeFixesLeft = 0

    /**
     * 上一次重排之前度到嘅尺寸（見 [fixPadSizeIfOff]）。重排完一模一樣就唔好再試 ——
     * 嗰陣係真係頂到盡（鍵盤本身高過個螢幕），唔係量錯。
     * 一見到正常尺寸就清返做空，所以淨係擋住「執極都一樣」嗰段，
     * 之後再撞到同一個壞尺寸一樣會照執。
     */
    private var lastFixState = ""

    private val sizeRecheck = Runnable { recheckPadSize() }

    /**
     * 由**冇到有**開鍵盤嗰下，個窗未必即刻報得返啱嘅闊度／導覽列高度：量出嚟
     * 成塊鍵盤高過個窗，最底嗰行就俾導覽列冚咗，要拉一拉高度或者轉一次橫直
     * 先返到正常（user 2026-08-28 踩到）。
     *
     * 所以出咗嚟之後每隔 [SIZE_RECHECK_MS] 補度 [SIZE_RECHECK_TRIES] 次
     * ——**度到唔啱先至重排**，啱就乜都唔做，平時開鍵盤唔會見到跳一跳。
     */
    private fun scheduleSizeRecheck() {
        if (!::padHolder.isInitialized) return
        sizeRechecksLeft = SIZE_RECHECK_TRIES
        sizeFixesLeft = SIZE_MAX_FIXES
        ui.removeCallbacks(sizeRecheck)
        ui.postDelayed(sizeRecheck, SIZE_RECHECK_MS)
    }

    private fun recheckPadSize() {
        if (!::padHolder.isInitialized) return
        // 有啲機第一次唔會派 insets 落嚟，底下就唔會閃開導覽列
        // （見 AGENTS.md「底下閃開導覽列嗰條要有底色」）
        ViewCompat.requestApplyInsets(outer)
        // 真係重排咗就再補度多一輪：個窗要下一個 layout pass 先跟得上，
        // 一次未必夠（鎖住屏幕轉橫直嗰個 case 就係）
        if (fixPadSizeIfOff() && sizeFixesLeft > 0) {
            sizeFixesLeft--
            sizeRechecksLeft = SIZE_RECHECK_TRIES
        }
        if (sizeRechecksLeft > 0) {
            sizeRechecksLeft--
            ui.postDelayed(sizeRecheck, SIZE_RECHECK_MS)
        }
    }

    /**
     * 而家排住嗰塊鍵盤，同用**而家**個闊度重新計出嚟嘅高度唔同 → 開頭嗰次量錯咗，
     * 重排一次，回傳有冇真係重排過。連 `refreshBars()` 都要行返：側邊欄個高度係寫死
     * `PadMetrics.totalHeight` 嘅（見「中文拉窄就唔要上面條 bar」），唔重新加返就會跟住錯埋。
     *
     * **塊 pad 自己夠唔夠位擺都要比。** 打橫改完高度、熄咗屏幕轉返直、再解鎖
     * 嗰下（2026-08-28 user 踩到）：塊 pad 自己係量返啱嘅（直度嗰套 630），
     * 但係個窗仲係停留喺打橫嗰個高度，`padHolder` 得 504 咁高，最底成行俾裁走 ——
     * 淨係比塊 pad 就當一切正常，要拉一拉高度或者再轉多次橫直先返到正常。
     *
     * 但係「`padHolder` 矮過塊 pad」唔一定係出事：鍵盤本身拉到高過個螢幕
     * （打橫好易），個窗頂到盡都一定裁到。所以重排之前記低度到嘅尺寸
     * （[lastFixState]），**重排完一模一樣就唔再試**，唔係就會一路重排落去。
     */
    private fun fixPadSizeIfOff(): Boolean {
        // emoji 表／剪貼簿係跟 `forcedHeightPx`，唔喺度計
        val pad = padHolder.getChildAt(0) as? KeyboardBaseView ?: return false
        val w = padHolder.width
        if (w <= 0 || pad.height <= 0) return false
        val want = PadMetrics.padHeightPx(this, w, padGroup).roundToInt()
        // 量啱咗（塊 pad 高度啱），而且真係擺得落（個 holder 冇裁到佢）
        if (abs(pad.height - want) <= 1 && padHolder.height >= pad.height - 1) {
            lastFixState = "" // 而家正常，下次再撞到同一個壞尺寸都要照執
            return false
        }
        val state = "${padHolder.width}x${padHolder.height}/${pad.height}/${outer.height}/$want"
        if (state == lastFixState) return false
        lastFixState = state
        relayoutPads()
        root.requestLayout()
        refreshBars()
        return true
    }

    // ---- TTEngine.Host ----------------------------------------------------

    override fun commitText(text: String) {
        // 搵 emoji 嗰陣，九宮格打出嚟嘅中文都係入條 query，唔會入落個欄
        if (emojiSearch) { emojiQuery.append(text); syncEmojiComposing(); refreshEmojiResults(); return }
        currentInputConnection?.commitText(text, 1)
    }

    override fun bumpChar(ch: String) = UsageStats.get(this).bumpChar(ch)
    override fun bumpBigram(pair: String) = UsageStats.get(this).bumpBigram(pair)
    override fun bigramCount(pair: String): Int = UsageStats.get(this).bigramCount(pair)

    /**
     * 開關標點（長撳 `0`）。兩種情況唔同做法：
     *
     *  - **揀住咗一段字** → 「」係**包住**佢，唔係取代佢：`揀咗嘅字` → `「揀咗嘅字」`。
     *    （`commitText` 本身係取代揀咗嗰段，所以要自己接返段字入中間。）
     *  - **冇揀字** → 出一對「」，再將 caret 移返兩個標點**中間**，等打得落去。
     */
    override fun commitPair(pair: String) {
        val ic = currentInputConnection ?: return
        val parts = TTDb.splitGraphemes(pair)
        val open = parts.firstOrNull() ?: return
        val close = parts.drop(1).joinToString("")
        ic.beginBatchEdit()

        val selected = ic.getSelectedText(0)?.toString().orEmpty()
        if (selected.isNotEmpty()) {
            // 包住揀咗嗰段，caret 擺喺收嗰個標點後面（成段嘢已經圈好，唔使再打）
            ic.commitText(open + selected + close, 1)
            ic.endBatchEdit()
            return
        }

        ic.commitText(open + close, 1)
        val et = ic.getExtractedText(ExtractedTextRequest(), 0)
        if (et != null && et.selectionStart >= close.length) {
            val p = et.selectionStart - close.length
            ic.setSelection(p, p)
        } else {
            repeat(close.length) {
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT))
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_LEFT))
            }
        }
        ic.endBatchEdit()
    }

    override fun onStateChanged() {
        // 唔淨係重畫：入／出「夠兩頁嘅選字模式」底行要換做「下頁／上頁」兩粒
        chinesePad?.onEngineState()
        refreshBars()
    }

    // ---- ChinesePadView.ChineseHost ---------------------------------------

    override fun pressDigit(digit: Int) {
        engine.press(digit)
    }

    // ---- 按鍵 -------------------------------------------------------------

    override fun onKey(key: Key) {
        when (key.action) {
            KeyAction.DIGIT -> engine.press(key.digit)
            KeyAction.CANCEL -> engine.cmd(TTCmd.CANCEL)
            KeyAction.SHORTCUT -> engine.cmd(TTCmd.SHORTCUT)
            KeyAction.SC_TOGGLE -> toggleSc()
            KeyAction.HOMO -> engine.cmd(TTCmd.HOMO)
            KeyAction.RELATE -> engine.cmd(TTCmd.RELATE)
            KeyAction.PREV_PAGE -> engine.cmd(TTCmd.PREV)
            KeyAction.NEXT_PAGE -> engine.cmd(TTCmd.NEXT)
            KeyAction.TO_CHINESE -> switchMode(PadMode.CHINESE)
            KeyAction.TO_LATIN -> switchMode(PadMode.LATIN)
            KeyAction.TO_SYMBOL -> { switchMode(PadMode.SYMBOL); symbolPad?.page = 0 }
            KeyAction.TO_NUMBER -> switchMode(PadMode.NUMBER)
            KeyAction.TO_EMOJI -> openEmoji()
            KeyAction.PASTE -> paste()
            KeyAction.COPY -> copy()
            KeyAction.SELECT_ALL -> selectAll()
            KeyAction.UNDO -> undo(redo = false)
            KeyAction.REDO -> undo(redo = true)
            KeyAction.AI -> runAi()
            KeyAction.SYM_PAGE -> symbolPad?.let { it.page = 1 - it.page }
            KeyAction.IME_SWITCH -> switchIme()
            KeyAction.IME_PICKER -> showImePicker()
            KeyAction.STT -> toggleStt()
            KeyAction.OPTION -> toggleBar()
            KeyAction.BAR_HIDE -> cycleBarWithHide()
            KeyAction.BACKSPACE -> backspace()
            KeyAction.SPACE -> space()
            KeyAction.ENTER -> enter()
            KeyAction.SHIFT -> tapShift()
            KeyAction.CHAR -> typeChar(key.text, key.literal)
            KeyAction.NOOP -> {}
        }
    }

    override fun onLongPress(key: Key): Boolean {
        // 左上角揀咗做 🎤 嗰粒鍵，長撳一樣係「撳實一路錄」（同工具列嗰粒一致）——
        // 但淨係喺個位本身冇長撳功能嗰陣，唔可以食咗 user 特登揀咗嘅長撳動作
        if (key.action == KeyAction.STT && key.longAction == KeyAction.NOOP &&
            onSttHoldStart()) return true
        // 設定頁換得嘅鍵（左上角嗰粒）自己帶住長撳做乜
        if (key.longAction != KeyAction.NOOP) {
            onKey(key.copy(action = key.longAction, longAction = KeyAction.NOOP))
            return true
        }
        when (key.action) {
            KeyAction.DIGIT -> {
                // 本身 "/" 鍵嘅開關標點，改成長撳 0
                if (key.digit == 0) {
                    // 大格「下頁」模式（[PagerLayout.WIDE_NEXT]）選字揭緊頁嗰陣，
                    // 長撳讓咗俾「上頁」——「」冇位，粒鍵左上角亦都寫住「上頁」
                    if (chinesePad?.wideNextPage() == true) { engine.cmd(TTCmd.PREV); return true }
                    engine.cmd(TTCmd.OPENCLOSE)
                    return true
                }
                // 選字模式長撳一格 = 開嗰個字嘅同音字表（唔使先撳「同音」）。
                // 就算查唔到同音字都照食咗呢下長撳 —— 唔好跌返落「長撳 = 連撳」，
                // 唔係就會即刻揀咗個字，跟住放手嗰下再攞個數字起新碼。
                if (engine.selectMode) { engine.homoAt(key.digit); return true }
                // 未打過碼長撳 1~9 = 直接開嗰格嘅速選字表（打緊碼就照舊「長撳 = 連撳」）。
                // 預設熄咗：呢下會食咗「長撳 = 連撳」嘅頭一下，打 77x 呢啲碼嘅人會
                // 覺得撳極都唔出，所以要 user 自己喺設定頁開（[Prefs.longPressShortcut]）
                if (Prefs.longPressShortcut(this) && engine.shortcutDigit(key.digit)) return true
            }
            KeyAction.PASTE -> { onPasteHistory(); return true }
            KeyAction.AI -> { openAiPrompts(); return true }
            KeyAction.IME_SWITCH -> { showImePicker(); return true }
            KeyAction.SHIFT -> {
                latinPad?.let { it.shift = ShiftState.LOCK; it.rebuild() }
                shiftManual = true
                return true
            }
            KeyAction.CHAR -> if (key.hint.isNotEmpty()) { typeChar(key.hint); return true }
            else -> {}
        }
        return false
    }

    /** 撳實鍵盤上嗰粒 🎤 錄完放手（見 [onSttHoldStart]） */
    override fun onLongPressEnd(key: Key) {
        if (key.action == KeyAction.STT) onSttHoldEnd()
    }

    override fun feedback(key: Key) {
        val level = Prefs.vibrateLevel(this)
        if (level > 0) {
            vibrator()?.let { v ->
                // 部分機款（如部分 Sony Xperia）唔支援自訂震幅，
                // 硬傳 amplitude 會令震動完全無反應，要用 DEFAULT_AMPLITUDE 做後備。
                // 嗰啲機就淨係靠時間長短分開三級。
                val amplitude = if (v.hasAmplitudeControl()) Prefs.vibrateAmplitude(level)
                                else VibrationEffect.DEFAULT_AMPLITUDE
                v.vibrate(VibrationEffect.createOneShot(Prefs.vibrateDurationMs(level), amplitude))
            }
        }
        if (Prefs.sound(this)) {
            (getSystemService(Context.AUDIO_SERVICE) as? AudioManager)
                ?.playSoundEffect(AudioManager.FX_KEYPRESS_STANDARD, 0.4f)
        }
    }

    /** 長撳 ␣ 之後拖手指郁 caret */
    override fun moveCursor(dx: Int, dy: Int) {
        val ic = currentInputConnection ?: return
        finishLatinComposing()
        latinWordDone = false
        lastCommittedWord = ""
        ic.beginBatchEdit()
        repeat(abs(dx)) {
            sendDpad(if (dx > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT)
        }
        repeat(abs(dy)) {
            sendDpad(if (dy > 0) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP)
        }
        ic.endBatchEdit()
    }

    private fun sendDpad(code: Int) {
        val ic = currentInputConnection ?: return
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    private fun vibrator(): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    // ---- 編輯動作 ---------------------------------------------------------

    private fun typeChar(raw: String, literal: Boolean = false) {
        var s = raw
        val pad = latinPad
        if (emojiSearch) {
            emojiQuery.append(s.filter { it != CARET })
            syncEmojiComposing()
            refreshEmojiResults()
            return
        }
        // 大階字母都要行呢條路：長撳變體 popup 揀到大階（"a" 撳實可以揀 "A"），
        // 唔當佢係英文字母就會 finishComposing，打字提示同 backspace 全部散晒
        if (mode == PadMode.LATIN && pad != null && s.length == 1 &&
            (s[0] in 'a'..'z' || s[0] in 'A'..'Z')) {
            if (!literal && s[0] in 'a'..'z' && pad.shift != ShiftState.OFF) s = s.uppercase()
            if (pad.shift == ShiftState.ON) { pad.shift = ShiftState.OFF; pad.rebuild() }
            latinWordDone = false
            forceCandidates = false
            // 新字開頭：記低上一個字做 next-word context，再睇下要唔要補返個 space
            if (latinComposing.isEmpty()) {
                lastCommittedWord = wordCharsBefore()
                autoSpaceAfterPunct()
            }
            if (latinSwiped) {
                // 啱啱滑出嚟、仲未打過字母嗰個字，而家打緊字 → 唔係 swipe，
                // 即刻取消 underline（composing）狀態，個字變返做普通已入嘅字
                currentInputConnection?.finishComposingText()
                latinSwiped = false
            }
            latinComposing.append(s)
            currentInputConnection?.commitText(s, 1)
            latinSuggestions = latinTypingSuggestions()
            clearShiftManual()
            updateAutoCaps()
            refreshBars()
            return
        }
        finishLatinComposing()
        latinWordDone = false
        // 成對符號（長撳 `,` 頂行嗰啲）中間夾住個 [CARET]：斬開兩橛分開打，
        // 第二橛用 `newCursorPosition = 0` commit —— 0 = caret 擺喺呢橛**前面**，
        // 即係啱啱好停返兩個符號中間，跟住打嘅字自然落咗入去對括號／引號入面。
        val caret = s.indexOf(CARET)
        currentInputConnection?.let { ic ->
            if (caret < 0) ic.commitText(s, 1)
            else {
                ic.beginBatchEdit()
                ic.commitText(s.substring(0, caret), 1)
                ic.commitText(s.substring(caret + 1), 0)
                ic.endBatchEdit()
            }
        }
        if (mode == PadMode.CHINESE) engine.cancel().also { onStateChanged() }
        // 啱啱打咗個標點／符號：`. ` `!` 之後嗰個字母要自動大階
        clearShiftManual()
        updateAutoCaps()
    }

    /**
     * 前面貼住 `, ? !` 又冇隔空格嘅話，打新字之前自己補一個。
     *
     * **句號（`.`）故意唔喺呢個表入面。** 打網址（`google.com`）、小數、檔名、縮寫
     * 全部都係「字母 + `.` + 字母」，同「句尾 + 新句」喺打嗰一刻**分唔開**
     * （`google.` 同 `Hello.` 前面嗰橛一模一樣咁普通），試過用 token 內容去估都靠唔住。
     * 補錯個空格會直接搞到網址打唔到，所以情願唔補 —— 想斷句就自己撳 ␣。
     *
     * URL／email／密碼欄再加多重保險，成個 auto-space 都熄埋（見 [noAutoSpaceField]）。
     */
    private fun autoSpaceAfterPunct() {
        if (noAutoSpaceField) return
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(1, 0)?.toString().orEmpty()
        if (before.isNotEmpty() && before[0] in ",?!") ic.commitText(" ", 1)
    }

    /**
     * 連撳兩下 shift = capslock（粒掣會變藍，打乜都大階）。
     * 撳一下就係淨係下一個字母大階，再撳一下就熄。長撳一樣係 capslock。
     */
    private fun tapShift() {
        val pad = latinPad ?: return
        val now = android.os.SystemClock.uptimeMillis()
        val double = now - lastShiftTapAt <= DOUBLE_TAP_MS
        lastShiftTapAt = now
        pad.shift = when {
            double -> ShiftState.LOCK
            pad.shift == ShiftState.OFF -> ShiftState.ON
            else -> ShiftState.OFF
        }
        // 自己撳過就當佢話事，唔好俾自動大階即刻改返（見 [updateAutoCaps]）
        shiftManual = true
        pad.rebuild()
    }

    // ---- 句首自動大階 -----------------------------------------------------

    /**
     * 英文鍵盤句首自動 shift。判斷條件全部喺 [AutoCaps]（純 Kotlin，有 unit test）：
     * 欄位開頭、換行、`! ?`（可以隔空格）、`.` + 空格。
     *
     * **一定要撳得熄。** user 自己撳過粒 ⇧ 就 [shiftManual] = true，
     * 之後再郁游標／收到 `onUpdateSelection` 都唔會夾硬校返大階；
     * 一打到落字（打字、space、backspace、⏎）就當呢個「手動決定」用完，
     * 下一句照舊自動大階。Capslock 亦都唔會被呢度郁到。
     *
     * URL／email／密碼／篩選欄唔會行呢度（見 [autoCapsField]）。
     */
    private fun updateAutoCaps() {
        val pad = latinPad ?: return
        if (mode != PadMode.LATIN) return
        if (pad.shift == ShiftState.LOCK || shiftManual) return
        val want = if (autoCapsWanted()) ShiftState.ON else ShiftState.OFF
        if (pad.shift == want) return
        pad.shift = want
        pad.rebuild()
    }

    private fun autoCapsWanted(): Boolean {
        if (!autoCapsField || emojiSearch) return false
        // 打緊一個字嘅中間（`hel|`）梗係唔會係句首，慳返一次 IPC
        if (latinComposing.isNotEmpty()) return false
        val ic = currentInputConnection ?: return false
        val before = ic.getTextBeforeCursor(AutoCaps.LOOKBACK, 0) ?: return false
        return AutoCaps.atSentenceStart(before)
    }

    /** 打到落字就當「手動 shift」用完（見 [updateAutoCaps]） */
    private fun clearShiftManual() { shiftManual = false }

    private fun backspace() {
        // 中文打緊碼就照剷碼先，剷完先至輪到條 emoji query
        if (mode == PadMode.CHINESE && engine.backspace()) return
        if (emojiSearch) {
            if (emojiQuery.isEmpty()) { endEmojiSearch(); openEmoji() }
            else { emojiQuery.setLength(emojiQuery.length - 1); syncEmojiComposing(); refreshEmojiResults() }
            return
        }
        latinWordDone = false
        if (latinComposing.isNotEmpty()) {
            if (latinSwiped) {
                // 啱啱滑出嚟、仲未打過字母 → 呢下 backspace 代表個字啱啱滑錯咗，成個字一次過剷
                latinComposing.setLength(0)
                currentInputConnection?.commitText("", 1)
                latinSuggestions = emptyList()
                latinSwiped = false
                refreshBars()
                return
            }
            // 已經冇 underline（唔係啱啱滑出嚟未打過字），啲字已經直接 commit 咗入個欄，
            // 剷返上一個字母淨係要刪返個字元，唔使再郁 composing
            latinComposing.setLength(latinComposing.length - 1)
            currentInputConnection?.deleteSurroundingText(1, 0)
            latinSuggestions = if (latinComposing.isEmpty()) emptyList() else latinTypingSuggestions()
            clearShiftManual()
            updateAutoCaps()
            refreshBars()
            return
        }
        val ic = currentInputConnection ?: return
        val sel = ic.getSelectedText(0)
        if (sel != null && sel.isNotEmpty()) ic.commitText("", 1)
        else deleteOneElement(ic)
        // 剷返到句尾／欄位開頭就要即刻著返大階
        clearShiftManual()
        updateAutoCaps()
    }

    /**
     * 剷走游標前面**一個文字元素**（唔係一個 `char`）。
     *
     * `deleteSurroundingText` 數嘅係 UTF-16 char，一個 emoji 通常佔兩個 ——
     * 寫死 `1` 就要撳兩下 ⌫ 先剷得走，中間嗰下仲會喺個欄度留低半隻（豆腐字）。
     * 旗（`🇭🇰`）四個、一家人（`👨‍👩‍👧`）成八個，情況一樣。所以要問個欄攞返前面
     * 嗰段字，用 [TextEdit.lastClusterLength] 計啱條數先剷（`assets/emoji.txt`
     * 入面 1416 個 emoji 有 1248 個中招，唔止 `👾` 一個）。
     */
    private fun deleteOneElement(ic: InputConnection) {
        val before = ic.getTextBeforeCursor(TextEdit.LOOKBEHIND, 0)
        val n = TextEdit.lastClusterLength(before ?: "")
        ic.deleteSurroundingText(max(1, n), 0)
    }

    private fun space() {
        if (mode == PadMode.CHINESE && engine.selectMode) {
            engine.pickCandidateAt(engine.currPage * 9)
            return
        }
        if (emojiSearch) { emojiQuery.append(' '); syncEmojiComposing(); refreshEmojiResults(); return }
        // 打緊嗰個字未 commit 就已經係「上一個字」；未打緊字就攞返個欄度貼住 caret 嗰個字
        val prevWord = if (mode == PadMode.LATIN) {
            (if (latinComposing.isNotEmpty()) latinComposing.toString() else wordCharsBefore())
        } else ""
        finishLatinComposing()
        latinWordDone = false
        currentInputConnection?.commitText(" ", 1)
        clearShiftManual()
        updateAutoCaps()
        if (mode == PadMode.LATIN && prevWord.isNotEmpty()) {
            lastCommittedWord = prevWord
            latinSuggestions = nextWordSuggestions(prevWord)
            refreshBars()
        }
    }

    private fun enter() {
        if (emojiSearch) { endEmojiSearch(); openEmoji(); return }
        if (mode == PadMode.CHINESE && engine.busy) { engine.cmd(TTCmd.CANCEL); return }
        finishLatinComposing()
        engine.onLineBreak() // 換咗行，中文 bigram 統計唔可以跨行接落去
        val ic = currentInputConnection ?: return
        val ei = currentInputEditorInfo
        val action = ei?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE
        val noEnter = (ei?.imeOptions?.and(EditorInfo.IME_FLAG_NO_ENTER_ACTION) ?: 0) != 0
        if (!noEnter && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            ic.performEditorAction(action)
        } else {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        }
        // 換咗行 = 新一段，下一個字母要自動大階
        clearShiftManual()
        updateAutoCaps()
    }

    /** emoji query 打緊嘅字：即時 set 做 composing text，等 user 見到打緊乜（唔會真係入落個欄） */
    private fun syncEmojiComposing() {
        currentInputConnection?.setComposingText(emojiQuery.toString(), 1)
    }

    private fun finishLatinComposing() {
        if (latinComposing.isNotEmpty()) {
            currentInputConnection?.finishComposingText()
            latinComposing.setLength(0)
        }
        latinSuggestions = emptyList()
        forceCandidates = false
        latinSwiped = false
    }

    /** 打完一個字之後估下一個字。密碼欄一律唔出（同 [latinTypingSuggestions]） */
    private fun nextWordSuggestions(prev: String): List<String> =
        if (passwordField) emptyList()
        else NextWordModel.get()?.predictNext(prev) ?: emptyList()

    /**
     * 冇 context 嗰陣嘅**預設英文提示** —— 即係 `prev` 吉嗰陣
     * [NextWordModel.predictNext] 跌落去嗰批全域最常用字。
     *
     * 幾時用：英文鍵盤，冇字打緊，而上一個字又唔係英文（啱啱打完中文、標點、
     * 或者成個欄都係空）。2026-09-13 user 報「上一個字係中文嗰陣條 bar 吉咗」。
     *
     * 個 list 成世都係同一批字，所以**計一次就 cache 住** ——
     * [refreshBars] 逐粒鍵行一次，唔想每次都行一轉個 trie。
     * 詞庫係背景載嘅，未載好嗰陣回吉，所以吉就唔 cache，下次再試過。
     */
    private fun latinDefaultSuggestions(): List<String> {
        if (passwordField) return emptyList()
        if (defaultLatinSuggestions.isEmpty()) {
            defaultLatinSuggestions = NextWordModel.get()?.predictNext("").orEmpty()
        }
        return defaultLatinSuggestions
    }

    /**
     * 打緊字嗰陣（[latinComposing] 唔係空）出嘅提示：先用 [lastCommittedWord] 做 context
     * 揾 bigram 夾 prefix 嘅字（AOSP 標準嘅 N-gram 做法），唔夠先用 [EnDict] 補齊。
     */
    private fun latinTypingSuggestions(): List<String> {
        // 密碼欄唔出提示：打緊嘅密碼唔應該喺候選欄逐個字現形
        if (passwordField) return emptyList()
        val prefix = latinComposing.toString().lowercase()
        val model = NextWordModel.get()
        if (model != null) return model.suggestWithPrefix(lastCommittedWord, prefix)
        return EnDict.get()?.fromPrefix(prefix) ?: emptyList()
    }

    private fun toggleSc() {
        engine.scOutput = !engine.scOutput
        Prefs.setScOutput(this, engine.scOutput)
        onStateChanged()
    }

    private fun switchIme() {
        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            switchToNextInputMethod(false)
        } else {
            @Suppress("DEPRECATION")
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .switchToNextInputMethod(window?.window?.attributes?.token, false)
        }
        if (!ok) showImePicker()
    }

    /** 彈系統嗰個輸入法選單（長撳 `Eng` 揀得，見 [tt.ime.riverine.core.EngLongPress]） */
    private fun showImePicker() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .showInputMethodPicker()
    }

    // ---- 貼上 / clipboard 歷史 --------------------------------------------

    private fun paste() {
        val text = runCatching { ClipHistory.current(this) }.getOrDefault("")
        if (text.isEmpty()) { toast("剪貼簿是空的"); return }
        commitPlain(text)
    }

    /**
     * 複製：有揀字就叫個欄自己複製揀住嗰段（`android.R.id.copy`，同 [selectAll]
     * 一樣行 `performContextMenuAction`，保住個欄原本嗰種複製方式）；冇揀就攞
     * 成個輸入框嘅字直接寫落 clipboard —— 唔夾硬全選，唔想搞郁 user 個 caret／
     * selection 顯示。個欄一隻字都冇（[fieldHasText] 假）粒鍵已經撳唔到，
     * 呢度嘅 empty 分支純粹保底。
     */
    private fun copy() {
        val ic = currentInputConnection ?: return
        val selected = ic.getSelectedText(0)?.toString().orEmpty()
        if (selected.isNotEmpty()) {
            ic.performContextMenuAction(android.R.id.copy)
            return
        }
        val all = extractedAll()
        if (all.isEmpty()) { toast("輸入框沒有文字，無法複製"); return }
        clipboard()?.setPrimaryClip(ClipData.newPlainText(null, all))
        toast("已複製整個輸入框")
    }

    /**
     * 全選：**唔係我哋自己數個欄有幾多隻字**（`getExtractedText` 攞到嗰段唔一定係
     * 全部，長文會截），而係叫個欄自己做 —— `android.R.id.selectAll` 就係
     * 揀字選單「全選」嗰一項，`TextView` 收到就自己揀晒。
     *
     * 打緊嘅碼要先清（中文 composing 段仲喺個欄度，唔清就會連埋佢一齊揀），
     * 揀完再 [refreshBars]：AI 改寫嗰粒鍵係「有揀字先撳得」，全選之後就要著返。
     */
    private fun selectAll() {
        val ic = currentInputConnection ?: return
        finishLatinComposing()
        if (mode == PadMode.CHINESE) engine.cancel()
        ic.performContextMenuAction(android.R.id.selectAll)
        onStateChanged()
    }

    /**
     * 復原／重做：向個欄發 **Ctrl+Z**（[redo] = Ctrl+Shift+Z）。
     *
     * `InputConnection` 冇「undo」呢個 API，`android.R.id.undo` 亦都唔係公開嘅 id，
     * 所以行硬件鍵盤嗰條路 —— `TextView` 自己就係咁認復原／重做
     * （`Editor.UndoManager`），支援嘅欄（包括大部分 `EditText`）都食呢兩下。
     *
     * 個欄唔支援就乜都唔會發生（發咗個 key event 出去冇人理），呢個係冇辦法
     * 事先問到嘅，所以唔出 toast 亂咁講「復原咗」。
     */
    private fun undo(redo: Boolean) {
        val ic = currentInputConnection ?: return
        finishLatinComposing()
        if (mode == PadMode.CHINESE) engine.cancel()
        var meta = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        if (redo) meta = meta or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        ic.beginBatchEdit()
        // 有啲欄睇住真實嘅 Ctrl 撳咗未（唔止睇 event 個 metaState），
        // 所以照住硬件鍵盤嗰個次序：Ctrl 落 → Z 落放 → Ctrl 起
        sendMetaKey(KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.ACTION_DOWN, KeyEvent.META_CTRL_ON)
        if (redo) sendMetaKey(KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.ACTION_DOWN, meta)
        sendMetaKey(KeyEvent.KEYCODE_Z, KeyEvent.ACTION_DOWN, meta)
        sendMetaKey(KeyEvent.KEYCODE_Z, KeyEvent.ACTION_UP, meta)
        if (redo) sendMetaKey(KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.ACTION_UP, meta)
        sendMetaKey(KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.ACTION_UP, 0)
        ic.endBatchEdit()
        // 復原咗之後個欄頭尾都可能變咗樣（例如剷返到句頭），大階要重算
        clearShiftManual()
        updateAutoCaps()
        onStateChanged()
    }

    /** 一個帶 `metaState` 嘅 key event（[sendDpad] 嗰個唔帶 meta，行唔到 Ctrl 組合） */
    private fun sendMetaKey(code: Int, action: Int, meta: Int) {
        val ic = currentInputConnection ?: return
        val now = android.os.SystemClock.uptimeMillis()
        ic.sendKeyEvent(KeyEvent(now, now, action, code, 0, meta))
    }

    private fun commitPlain(text: String) {
        finishLatinComposing()
        if (mode == PadMode.CHINESE) engine.cancel()
        currentInputConnection?.commitText(text, 1)
        onStateChanged()
    }

    /**
     * 長撳工具列／側邊欄嗰粒「表情」，喺彈出嗰行速選揀咗個（見 [QuickEmoji]）。
     * 同喺 emoji 表揀一模一樣：記低「最近用過」，再直接打出嚟。
     */
    override fun onQuickEmoji(emoji: String) {
        EmojiDict.addRecent(this, emoji)
        commitPlain(emoji)
    }

    override fun onPasteHistory() {
        rememberPadHeight()
        runCatching { ClipHistory.current(this) }
        showOverlay(ClipboardListView(this).apply {
            applyTheme(theme)
            forcedHeightPx = padHeightPx
            clipHost = ClipboardListView.ClipHost { text -> hideOverlay(); commitPlain(text) }
        })
    }

    /**
     * 攤開喺鍵盤位置嗰啲「功能表」（emoji 表、剪貼簿歷史、AI prompt 名單、
     * 拉大咗嘅關聯字）**擺位要跟鍵盤本體**：靠左貼左、靠右貼右、置中居中，
     * 闊度亦都跟 [PadMetrics.contentW]（2026-09-11 user 要求）。
     *
     * 冇呢樣嘢就會：鍵盤縮窄靠住一邊單手打，一長撳「貼上」彈出嚟嗰張表
     * 又鋪滿成行，隻手夠唔到另一邊。
     *
     * 「拉闊」同「左右拆開」本來就用盡成行，照用 `MATCH_PARENT`。
     * **鍵盤本體唔行呢條路**：佢哋自己喺 `PadMetrics.offsetX` 度排位，
     * 而且空出嚟嗰邊要留返俾側邊欄（見 [refreshSidePanel]）。
     */
    private fun panelLayoutParams(height: Int): FrameLayout.LayoutParams {
        val w = if (padHolder.width > 0) padHolder.width else resources.displayMetrics.widthPixels
        val m = if (w > 0) PadMetrics(this, w, group = padGroup) else null
        val gravity = when (m?.align) {
            PadAlign.RIGHT_GAP -> Gravity.START   // 右邊留白 → 貼左
            PadAlign.LEFT_GAP -> Gravity.END      // 左邊留白 → 貼右
            PadAlign.CENTER -> Gravity.CENTER_HORIZONTAL
            else -> return FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, height)
        }
        return FrameLayout.LayoutParams(m.contentW.roundToInt(), height).also { it.gravity = gravity }
    }

    /**
     * 鍵盤本體左右兩邊各留幾多白（px）。上面條 bar 入面啲嘢跟呢兩個數縮返入嚟
     * （見 [OptionBarsView.setContentInsets]），咁啲掣就企喺鍵盤上面。
     *
     * 「拉闊」同「左右拆開」用盡成行，兩邊都係 0。
     */
    private fun padInsets(): Pair<Int, Int> {
        if (!::padHolder.isInitialized) return 0 to 0
        val w = if (padHolder.width > 0) padHolder.width else resources.displayMetrics.widthPixels
        if (w <= 0) return 0 to 0
        val m = PadMetrics(this, w, group = padGroup)
        val slack = (w - m.contentW).roundToInt().coerceAtLeast(0)
        return when (m.align) {
            PadAlign.RIGHT_GAP -> 0 to slack              // 右邊留白
            PadAlign.LEFT_GAP -> slack to 0               // 左邊留白
            PadAlign.CENTER -> slack / 2 to slack - slack / 2
            else -> 0 to 0
        }
    }

    /** 功能表已經攤開住，而家改咗顯示方式／拉過闊窄：重新擺位（高度唔變） */
    private fun refreshPanelLayout(v: View?) {
        if (v == null || v.parent !== padHolder) return
        val h = (v.layoutParams as? FrameLayout.LayoutParams)?.height
            ?: FrameLayout.LayoutParams.WRAP_CONTENT
        v.layoutParams = panelLayoutParams(h)
    }

    private fun showOverlay(v: View) {
        if (!::padHolder.isInitialized) return
        hideOverlay()
        overlay = v
        padHolder.addView(v, panelLayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT))
        refreshBars()
    }

    private fun hideOverlay() {
        val v = overlay ?: return
        overlay = null
        (v.parent as? ViewGroup)?.removeView(v)
        refreshBars()
    }

    // ---- emoji ------------------------------------------------------------

    private fun openEmoji() {
        EmojiDict.preloadAsync(this)
        if (emojiSearch) endEmojiSearch()
        if (mode != PadMode.EMOJI) {
            emojiReturnMode = if (mode == PadMode.CHINESE) PadMode.CHINESE else PadMode.LATIN
        }
        switchMode(PadMode.EMOJI, force = true)
    }

    override fun onEmojiPicked(emoji: String) {
        commitPlain(emoji)
        emojiPad?.rebuild()
    }

    private fun closeEmoji() {
        endEmojiSearch()
        switchMode(emojiReturnMode, force = true)
    }

    override fun onEmojiBackspace() = backspace()

    /** emoji 表自己個 header 有粒 ✖（搵字掣左邊），同條 bar 嗰粒做同一件事 */
    override fun onEmojiClose() = closeEmoji()

    /**
     * 撳 emoji 表嗰粒搵字掣：轉去英文鍵盤打字，但啲字唔會入落個欄，
     * 淨係即時篩 emoji，夾到嗰啲出喺上面條 bar 度撳。
     * 底行嗰陣淨係得「退出表情搜尋」同 ␣（見 [LatinPadView.emojiSearchMode]）。
     */
    override fun onEmojiSearch() {
        emojiSearch = true
        emojiQuery.setLength(0)
        emojiResults = emptyList()
        latinPad?.emojiSearchMode = true
        switchMode(PadMode.LATIN, force = true)
        latinPad?.emojiSearchMode = true
        refreshBars()
    }

    private fun endEmojiSearch() {
        if (!emojiSearch) return
        emojiSearch = false
        // 未揀就走人：composing 緊嗰段 query 唔可以留喺人哋個輸入框度
        if (emojiQuery.isNotEmpty()) currentInputConnection?.commitText("", 1)
        emojiQuery.setLength(0)
        emojiResults = emptyList()
        latinPad?.emojiSearchMode = false
    }

    private fun refreshEmojiResults() {
        emojiResults = EmojiDict.search(this, emojiQuery.toString())
        refreshBars()
    }

    // ---- 上面條 bar -------------------------------------------------------

    /**
     * 九宮格右上角嗰粒。平時 `☰` = 開／關成條 bar，一開返永遠先入關聯字 view。
     *
     * **條 bar 常駐（[Prefs.barPinned]）嗰陣冇嘢好開關**，粒鍵改咗做 `⇄`：
     * 喺關聯字同工具之間切，同條 bar 最左本來嗰粒一模一樣（嗰粒亦都因此收埋咗，
     * 見 [refreshBars] 嗰句 `setSwitchVisible`）。
     */
    private fun toggleBar() {
        if (Prefs.barPinned(this)) { onSwitchView(); chinesePad?.invalidate(); return }
        barMode = if (barMode == BarMode.OFF) BarMode.CANDIDATES else BarMode.OFF
        Prefs.setBarMode(this, barMode)
        refreshBars()
        chinesePad?.invalidate()
    }

    /**
     * 英文底行嗰粒（淨係闊 keyboard 先有，見 [Prefs.barToggleAllowed]）：
     * **四段循環**（2026-09-11 user 要求）——
     * 關聯字 → 工具 → 兩行一齊 → **收起**，跟住由頭嚟過。
     *
     * 即係話佢同切換掣（[onSwitchView]）行同一個圈，淨係多咗「收起」嗰段。
     * **收起淨係呢粒做得到** —— 窄機根本冇呢粒鍵，收起咗就等於打盲舖。
     *
     * 粒掣自己個字面每段都唔同（見 `LatinPadView.barCycleGlyph`），所以要重砌塊英文 pad。
     */
    private fun cycleBarWithHide() {
        when {
            // 收起咗 → 出返嚟，由第一段（關聯字）開始
            Prefs.barHidden(this) -> {
                Prefs.setBarHidden(this, false)
                barMode = BarMode.CANDIDATES
                Prefs.setBarMode(this, barMode)
            }
            // 行完三段 → 收起
            barMode == BarMode.BOTH -> Prefs.setBarHidden(this, true)
            else -> {
                barMode = barMode.nextVisible()
                Prefs.setBarMode(this, barMode)
            }
        }
        latinPad?.rebuild()
        refreshBars()
        chinesePad?.invalidate()
    }

    /**
     * 切換掣（條 bar 最左嗰粒 `⇄`、中文九宮格右上角嗰粒）：
     * 關聯字 → 工具 → 兩行一齊 → 關聯字，一路撳落去。
     *
     * **永遠行去有嘢見嘅一段** —— 條 bar 收起咗（闊 screen 嗰粒 `▾` 做嘅）就
     * 即刻出返嚟，唔係中文頁嗰粒 `⇄` 撳極都冇反應（中文頁冇「收起」嗰粒鍵）。
     */
    override fun onSwitchView() {
        if (Prefs.barHidden(this)) {
            Prefs.setBarHidden(this, false)
            barMode = BarMode.CANDIDATES
        } else {
            barMode = barMode.nextVisible()
        }
        Prefs.setBarMode(this, barMode)
        latinPad?.rebuild() // 英文底行嗰粒個字面跟住而家喺邊段行
        refreshBars()
        chinesePad?.invalidate() // 常駐模式：右上角嗰粒著燈與否跟住呢個狀態行
    }

    /**
     * emoji 表／剪貼簿開住：條 bar 一定要出返（唔可以俾 [Prefs.barHidden] 收埋），
     * 唔係剪貼簿就返唔到去普通鍵盤 —— 佢粒 ✖ 就喺條 bar 度。emoji 表自己個
     * header 已經有粒 ✖（2026-09-13，見 [EmojiPadView]），條 bar 留返係為咗
     * 嗰行工具掣，唔再係為咗有得返去。
     */
    private val specialPad: Boolean get() = mode == PadMode.EMOJI || overlay != null

    private fun refreshBars() {
        if (!::bars.isInitialized) return
        val pinned = Prefs.barPinned(this)
        // 常駐：關唔熄得。舊設定裡面存住 OFF 就當場升做關聯字（設定頁開個掣嗰陣
        // 唔會 restart 個 service，所以要喺呢度補）
        if (pinned && barMode == BarMode.OFF) {
            barMode = BarMode.CANDIDATES
            Prefs.setBarMode(this, barMode)
        }
        // 搵 emoji 嗰陣一定要見到啲結果，就算條 bar 本身係關住。
        // 滑出咗個字都一樣 —— 唔見到啲候選就揀唔到第二個字。
        val mustShow = emojiSearch || (forceCandidates && latinSuggestions.isNotEmpty())
        // 本來就有關聯字嗰行（[BarMode.BOTH]）就唔使郁，工具嗰行照留返
        var effective = if (mustShow && !barMode.hasCands) BarMode.CANDIDATES else barMode
        // 英文頁夾硬開返條 bar：佢靠條 bar 出打字提示同滑動出嚟嘅字，
        // 冇咗就等於打盲舖。**唔會改到 [barMode] 本身** —— 返到中文頁
        // 照樣跟返 user 設定嘅開關。
        if (mode == PadMode.LATIN && effective == BarMode.OFF) effective = BarMode.CANDIDATES
        // 符號／純數字頁**一律唔出關聯字嗰行**（2026-09-13 user 要求）：嗰兩頁
        // 根本冇字可以提示，設定成點都好，出嚟都係一行吉位。條 bar 本身照留做
        // 工具嗰行 —— 嗰兩頁冇 `⇄` 嗰粒鍵，成條 bar 收埋咗就冇入口開返。
        if (mode == PadMode.SYMBOL || mode == PadMode.NUMBER) effective = BarMode.TOOLS
        // emoji 表／剪貼簿嗰陣冇關聯字可以出，索性成行出工具，唔好淨係得粒 ✖ 吉住
        if (specialPad) effective = BarMode.TOOLS
        // 闊 keyboard 收起咗成條 bar（英文底行嗰粒，見 [cycleBarWithHide]；
        // 打橫仲要係預設收起，見 [Prefs.barHidden]）。**四款鍵盤都跟**：
        // 出返嚟嘅入口除咗嗰粒鍵，仲有任何一粒切換掣（見 [onSwitchView]）。
        // 擺喺最後 —— 上面三條（搵 emoji／滑完揀字／emoji 表同剪貼簿）都要越過佢。
        if (!mustShow && !specialPad && Prefs.barHidden(this)) effective = BarMode.OFF

        // 中文本體窄到夠位喺隔籬擺嘢 → 條 bar 收埋，功能掣同關聯字全部搬去側邊欄。
        // 一早計定：側邊欄兩樣（關聯字＋工具）一次過見晒，所以佢出咗嚟就一定要有關聯字
        val geom = if (effective == BarMode.OFF || overlay != null) null else sideGeom()
        val wantCands = geom != null || effective.hasCands

        showingContextPicks = false
        contextBarPicks = emptyList()
        val cands = when {
            emojiSearch -> emojiResults
            mode == PadMode.CHINESE -> when {
                engine.selectMode -> engine.selectWords
                // 根本冇出緊關聯字（條 bar 關咗／而家喺工具嗰邊）就唔使查
                // ——[contextPicks] 要問個輸入框攞字（IPC），逐粒鍵行一次好唔抵
                !wantCands -> emptyList()
                // 打咗一兩個碼（未夠碼出字）：出「呢個碼開頭最常用嗰九隻字」
                engine.currCode.isNotEmpty() -> contextBar(codePreview(engine.currCode))
                // 乜都未打：跟游標前面嗰隻字（唔係「啱啱打完嗰隻」）
                else -> contextBar(contextPicks())
            }
            // 冇字打緊、又冇提示（上一個字唔係英文、或者成個欄係空）：出返全域
            // 最常用嗰幾個字（2026-09-13 user 要求，本來係留一行吉位）。**要寫
            // 返落 [latinSuggestions]** —— 撳落去揀邊個字係查返佢嘅（見
            // [onPickCandidate]），淨係畫出嚟就會變咗撳極都冇反應
            mode == PadMode.LATIN -> {
                if (latinSuggestions.isEmpty() && latinComposing.isEmpty()) {
                    latinSuggestions = latinDefaultSuggestions()
                }
                latinSuggestions
            }
            else -> emptyList()
        }

        if (refreshSidePanel(geom, cands)) {
            bars.visibility = View.GONE
            return
        }

        // 大細／貼邊／關聯字字體全部跟而家見緊嗰組（見 [padGroup]）。要喺
        // setCandidates 之前做 —— 條 bar 幾高、啲 chip 幾大都係跟呢個組行
        bars.padGroup = padGroup
        // 條 bar 入面啲嘢（工具掣、✖／⇄、關聯字、▼）要企喺鍵盤本體上面，
        // 唔好鋪滿成行 —— 鍵盤靠邊／置中，上面啲掣一齊跟住郁
        padInsets().let { (l, r) -> bars.setContentInsets(l, r) }
        // 條 bar 唔可以粗過下面一行鍵（見 [OptionBarsView.keyRowHeightPx]）——
        // 一樣要喺 refreshFontScale 之前擺低，條 bar 幾高就係喺嗰度計
        bars.keyRowHeightPx = keyRowHeightPx()
        bars.refreshFontScale()
        // 設定頁改完「按鍵排位」返嚟：工具列有邊幾粒可能已經唔同咗
        bars.refreshTools()
        bars.setMode(effective)
        bars.setCandidates(if (effective.hasCands) cands else emptyList())
        // ✖ 淨係俾剪貼簿／AI prompt 嗰啲 overlay 用 —— emoji 表 2026-09-13 改咗
        // 自己個 header 出粒 ✖（見 [EmojiPadView]），條 bar 唔使再出多粒
        bars.setCloseVisible(overlay != null)
        // 常駐 + 中文九宮格：切換掣已經搬咗去右上角嗰粒鍵，條 bar 唔使再擺多粒。
        // 英文頁冇嗰粒鍵，所以一定要留返，唔係就入唔到工具列。
        // 符號／純數字頁就連粒 `⇄` 都唔出 —— 嗰兩頁夾硬淨係得工具嗰行（見上面），
        // 撳極都唔會見到有嘢變，出粒似壞咗嘅掣不如唔出
        // emoji 表冇關聯字可以切換過去（條 bar 夾硬係 TOOLS），出粒撳極都冇反應嘅 ⇄
        // 不如唔出 —— 以前呢個位係俾 ✖ 霸住嘅，而家 ✖ 搬咗入 emoji 表個 header
        val fixedTools = mode == PadMode.SYMBOL || mode == PadMode.NUMBER ||
            mode == PadMode.EMOJI
        bars.setSwitchVisible(!(pinned && mode == PadMode.CHINESE) && !fixedTools)
        bars.setAiReady(aiUsable)
        bars.setAiVisible(aiKeySet)
        bars.setCopyReady(fieldHasText)
        // 大細／貼邊分咗兩組存，粒「靠左／靠右」掣要拉、要著返邊個樣，
        // 都係跟而家見緊嗰組（見 [padGroup]）
        bars.refreshAlignLabel()
        // 條 bar 高度淨係跟關聯字嘅字體行（見 [OptionBarsView.barHeightFor]），
        // 唔會因為有冇關聯字、而家喺邊一段而跳高跳低
        bars.visibility = if (effective == BarMode.OFF) View.GONE else View.VISIBLE
    }

    /**
     * 下面鍵盤**一行鍵**幾高（px）。上面條 bar 跟呢個數封頂 ——
     * 打橫縮到最細嗰陣，一行鍵得三十幾 dp，條 bar 唔跟住縮就會變咗最粗嗰橛。
     *
     * 唔量真 view（開鍵盤第一下、轉頁嗰陣佢仲未排好），用返砌鍵盤嗰條式
     * （[PadMetrics.padHeightPx] ÷ 行數）—— 同鍵盤本身一定夾得返。
     * 行數要問返而家嗰塊 pad：英文開咗數字行 5 行、中文九宮格 4 行。
     */
    private fun keyRowHeightPx(): Int {
        if (!::padHolder.isInitialized) return 0
        val w = if (padHolder.width > 0) padHolder.width else resources.displayMetrics.widthPixels
        if (w <= 0) return 0
        val rows = (padHolder.getChildAt(0) as? RowsPadView)?.rowCount ?: CJK_ROWS
        return (PadMetrics.padHeightPx(this, w, padGroup) / max(1, rows)).roundToInt()
    }

    /** 記住而家出緊嗰個 list，[onPickCandidate] 就知撳咗邊隻字（未入過選字模式） */
    private fun contextBar(list: List<String>): List<String> {
        showingContextPicks = true
        contextBarPicks = list
        return list
    }

    /**
     * 未打過碼嗰陣候選欄出乜：**讀游標前面嗰隻字**，出佢嘅關聯字。
     *
     * 特登唔用 `TTEngine.relateHints`（＝「啱啱打完嗰隻字」）—— user 撳過個輸入框
     * 郁咗游標、或者啱啱開個鍵盤，嗰個狀態就已經係舊嘅。前面吉住、或者唔係中文
     * （英文、標點、數字…）就出 [DEFAULT_PICK_ID] 嗰行最常用字。
     *
     * 開咗「輸出簡體」嗰陣個欄入面係簡體，但 `related_candidates_table` 淨係有
     * 正體，所以查唔到就轉返正體再查一次（[TTDb.sctc] 淨係攞嚟查表，唔會輸出）。
     */
    private fun contextPicks(): List<String> {
        val d = db ?: return defaultPicks
        // 攞兩個 char：一個增補字符（surrogate pair）都要攞得齊
        val before = runCatching { currentInputConnection?.getTextBeforeCursor(2, 0)?.toString() }
            .getOrNull().orEmpty()
        val ch = TTDb.splitGraphemes(before).lastOrNull().orEmpty()
        if (ch.isNotEmpty() && isHanChar(ch)) {
            var r = d.getRelate(ch)
            if (r.isEmpty() && engine.scOutput) r = d.getRelate(d.sctc(ch))
            val f = r.filter { it.isNotEmpty() && it != "*" }
            if (f.isNotEmpty()) return f
        }
        return defaultPicks
    }

    /**
     * 打咗 1~2 個碼（未夠碼出關聯字）嗰陣，條 bar 出「以呢個碼開頭最常用嗰九隻字」，
     * 撳落去即刻出字，唔使打齊三個碼（見 [TTDb.topByCodePrefix]）。
     * 同一個碼查一次就夠 —— 每撳一下鍵 [refreshBars] 都會行到呢度。
     */
    private fun codePreview(code: String): List<String> {
        if (code == codePreviewFor) return codePreviewList
        codePreviewFor = code
        // 舊版 dataset.db 冇 `word_meta.freq` / `.code`，查唔到就跌返落預設嗰行字
        // （條 bar 吉住反而似壞咗）。`topByCodePrefix` 自己會食晒個 SQL exception。
        codePreviewList = runCatching { db?.topByCodePrefix(code, BAR_PREVIEW_COUNT).orEmpty() }
            .getOrDefault(emptyList()).ifEmpty { defaultPicks }
        return codePreviewList
    }

    private fun isHanChar(s: String): Boolean =
        s.isNotEmpty() && s.codePointCount(0, s.length) == 1 &&
            Character.UnicodeScript.of(s.codePointAt(0)) == Character.UnicodeScript.HAN

    // ---- 側邊欄（中文拉窄嗰陣）----------------------------------------------

    /**
     * 中文本體靠咗一邊、又窄過螢幕嘅 [Prefs.SIDE_PANEL_MAX_RATIO]（六成）嗰陣，
     * 空出嚟嗰四成幾位夠曬擺功能掣同一大版關聯字，冇理由再喺上面霸多條 bar。
     *
     * 回傳 true = 而家用緊側邊欄（call 嗰邊要自己收埋 [bars]）。
     * [geom] 由 [refreshBars] 計（null = 唔夠窄／條 bar 關咗／有 overlay 蓋住）——
     * 佢自己都要用「而家出唔出側邊欄」呢個答案去決定使唔使查關聯字，所以計一次就夠。
     *
     * 只限中文九宮格：英文／符號／純數字係一行行鋪滿成行嘅，冇位空出嚟；
     * 剪貼簿嗰個 overlay 又會蓋住成個 padHolder（連側邊欄都遮埋，就撳唔返粒 ✖）。
     */
    private fun refreshSidePanel(geom: SideGeom?, cands: List<String>): Boolean {
        if (geom == null) {
            removeSidePanel()
            return false
        }
        val panel = sidePanel ?: SidePanelView(this).also {
            it.listener = this
            it.applyTheme(theme)
            sidePanel = it
        }
        // 高度**寫死做中文九宮格嗰個高度**，唔可以用 MATCH_PARENT。
        // padHolder 係 wrap_content 嘅 FrameLayout：MATCH_PARENT 嘅仔會攞到
        // AT_MOST(成個可用高度)，入面又有個食 weight 嘅關聯字 ScrollView，
        // 結果關聯字一多就撐大咗 padHolder，成個鍵盤跟住拉高（打橫尤其明顯）。
        val lp = FrameLayout.LayoutParams(geom.slackPx, geom.heightPx).apply {
            // 「靠右」= 內容貼右、左邊留白 → 側邊欄擺左邊，反之亦然
            gravity = if (geom.atStart) Gravity.START else Gravity.END
        }
        val old = panel.layoutParams as? FrameLayout.LayoutParams
        if (panel.parent !== padHolder) {
            (panel.parent as? ViewGroup)?.removeView(panel)
            padHolder.addView(panel, lp)
        } else if (old == null || old.width != lp.width || old.height != lp.height ||
            old.gravity != lp.gravity) {
            panel.layoutParams = lp
        }
        panel.refreshFontScale()
        panel.refreshTools()
        panel.setCandidates(cands)
        panel.setAiReady(aiUsable)
        panel.setAiVisible(aiKeySet)
        panel.setCopyReady(fieldHasText)
        panel.setCloseVisible(false)
        panel.refreshAlignLabel()
        return true
    }

    /** 側邊欄擺喺邊、幾大 */
    private class SideGeom(val slackPx: Int, val heightPx: Int, val atStart: Boolean)

    /** null = 唔夠窄／唔啱模式，照用返上面條 bar */
    private fun sideGeom(): SideGeom? {
        if (mode != PadMode.CHINESE || !::padHolder.isInitialized) return null
        val align = Prefs.align(this)
        // 「拉闊」冇位空出嚟；「置中」空出嚟嗰啲位一開二，兩邊都窄過擺得落工具掣，
        // 所以兩個都照用返上面條 bar
        if (align == PadAlign.STRETCH || align == PadAlign.CENTER) return null
        val w = padHolder.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        if (w <= 0) return null
        val m = PadMetrics(this, w)
        if (m.contentW > w * Prefs.SIDE_PANEL_MAX_RATIO) return null
        val slack = (w - m.contentW).roundToInt()
        if (slack <= 0) return null
        // 「靠右」（LEFT_GAP）= 內容貼右、左邊留白 → 側邊欄擺喺最左
        return SideGeom(slack, m.totalHeight.roundToInt(), align == PadAlign.LEFT_GAP)
    }

    private fun removeSidePanel() {
        val p = sidePanel ?: return
        (p.parent as? ViewGroup)?.removeView(p)
    }

    // ---- AI 撳唔撳得 --------------------------------------------------------

    /** 開頭／轉欄嗰陣：問清楚個欄到底有冇字（三次 IPC，唔係逐粒鍵行嘅路） */
    private fun refreshAiState() {
        val ic = currentInputConnection
        applyAiState(
            !ic?.getSelectedText(0).isNullOrEmpty() ||
                !ic?.getTextBeforeCursor(1, 0).isNullOrEmpty() ||
                !ic?.getTextAfterCursor(1, 0).isNullOrEmpty()
        )
    }

    /**
     * ✨ 撳唔撳得：**唔使揀住字都用得** —— 個欄有字就當「改寫成個欄」（見 [runAi]）。
     * 完全冇入 API key、或者設定頁熄咗「AI 改寫」，就連粒掣都唔出
     * （[OptionBarsView.setAiVisible]）。
     */
    private fun applyAiState(hasText: Boolean) {
        // 設定頁熄咗「AI 改寫」就當冇入過 key 咁處理 —— 成粒 ✨ 唔出
        val keySet = Prefs.aiApiKey(this).isNotBlank() && Prefs.aiRewriteOn(this)
        val usable = keySet && hasText
        val textChanged = hasText != fieldHasText
        if (keySet == aiKeySet && usable == aiUsable && !textChanged) return
        aiKeySet = keySet
        aiUsable = usable
        fieldHasText = hasText
        if (::bars.isInitialized) { bars.setAiVisible(keySet); bars.setAiReady(usable) }
        sidePanel?.let { it.setAiVisible(keySet); it.setAiReady(usable) }
        if (textChanged) {
            if (::bars.isInitialized) bars.setCopyReady(hasText)
            sidePanel?.setCopyReady(hasText)
        }
        chinesePad?.invalidate()
    }

    override fun onCloseSpecialPad() {
        if (overlay != null) { hideOverlay(); return }
        if (mode == PadMode.EMOJI) closeEmoji()
    }

    override fun onPickCandidate(index: Int) {
        if (showingContextPicks && !emojiSearch) {
            // 呢個 list 唔係 engine 出嘅選字表（根本未入過選字模式），
            // 所以要行 pickQuick —— 佢入面會補返簡繁／同音／關聯字嗰套
            engine.pickQuick(contextBarPicks.getOrNull(index) ?: return)
            return
        }
        if (emojiSearch) {
            val e = emojiResults.getOrNull(index) ?: return
            EmojiDict.addRecent(this, e)
            commitPlain(e) // commitText 會取代咗仲顯示緊嘅 composing query
            // 揀完之後個 query 要清返，唔係跟住打嘅字會屈埋落舊嗰段 composing 度
            emojiQuery.setLength(0)
            emojiResults = emptyList()
            refreshBars()
            return
        }
        when (mode) {
            PadMode.CHINESE -> engine.pickCandidateAt(index)
            else -> {
                val w = latinSuggestions.getOrNull(index) ?: return
                val ic = currentInputConnection ?: return
                // 未 swipe 過（冇 composing region）嘅字係逐個字母直接 commit 落個欄嘅，
                // 揀候選要自己剷返成個字先夾得返（swipe 出嚟嗰陣仲有 composing，commitText 會自動取代）
                val wasTypedPrefix = !latinSwiped && latinComposing.isNotEmpty()
                // composing 係空、又未 swiped → 呢個係「下一個字」預測嘅提示，唔係補完緊打嘅字
                val wasNextWordPick = mode == PadMode.LATIN && !latinSwiped && latinComposing.isEmpty()
                if (wasTypedPrefix) ic.deleteSurroundingText(latinComposing.length, 0)
                // 「下一個字」係揀嚟接喺前面嗰個字後面嘅，兩個字之間一定要有個空格
                // （`hello` 揀 `there` 要出 `hello there`，唔係 `hellothere`）。
                // 前面唔係英文字（空格、標點、換行、中文、乜都冇）就唔使補 ——
                // 嗰啲位置本來就係一個字嘅開頭。
                if (wasNextWordPick && needSpaceBeforeWord()) ic.commitText(" ", 1)
                ic.commitText(w, 1)
                if (wasNextWordPick) ic.commitText(" ", 1)
                latinComposing.setLength(0)
                latinSuggestions = emptyList()
                // 揀咗個完整嘅字 → 下次滑就係下一個字（會自動加空格）
                latinWordDone = true
                latinSwiped = false
                forceCandidates = false
                if (mode == PadMode.LATIN) {
                    lastCommittedWord = w
                    latinSuggestions = nextWordSuggestions(w)
                    clearShiftManual()
                    updateAutoCaps()
                }
                refreshBars()
            }
        }
    }

    override fun onCycleAlign() {
        val g = padGroup
        // 揀得邊幾個要問 [Prefs.alignOptions]：闊 screen 嘅英數鍵盤淨係得
        // 「拉闊」同「左右拆開」，靠左／靠右嗰兩個嗰陣會收起
        Prefs.setAlign(this, Prefs.nextAlign(this, g), g)
        relayoutPads()
        bars.refreshAlignLabel()
        // 轉咗顯示方式可能就啱啱夠窄／唔再夠窄，側邊欄要跟住出現或者消失
        refreshBars()
    }

    /**
     * 左右拖 = 拉闊拉窄鍵盤本體（淨係郁到而家見緊嗰組，見 [padGroup]）。方向要跟返
     * 顯示方式：內容貼右（左邊留白）嗰陣向左拖先係拉闊，貼左就啱啱相反 ——
     * 永遠都係「拖向留白嗰邊 = 拉闊」。[PadAlign.SPLIT] 條罅喺中間，
     * 所以向右（＝向住條罅）拖就係兩橛一齊拉闊。
     *
     * [PadAlign.CENTER] 兩邊都有留白，冇「留白嗰邊」可言，所以跟返最順手嗰個：
     * **向右拖 = 拉闊**（兩邊一齊向外撐）。
     */
    override fun onWidthDrag(dxDp: Int) {
        if (dxDp == 0) return
        val g = padGroup
        val align = Prefs.align(this, g)
        if (align == PadAlign.STRETCH) return // 本來就用盡成行，冇位可以拉
        val sign = if (align == PadAlign.LEFT_GAP) -1 else 1
        val cur = Prefs.widthScale(this, g)
        val next = (cur + sign * dxDp / 250f)
            .coerceIn(Prefs.MIN_WIDTH_SCALE, Prefs.MAX_WIDTH_SCALE)
        if (next == cur) return
        Prefs.setWidthScale(this, next, g)
        relayoutPads()
        refreshBars()
    }

    /**
     * 長撳「靠左／靠右」嗰粒（撳實唔拉）：鍵盤本體一下子拉到最闊 ——
     * [Prefs.MAX_WIDTH_SCALE] 之下 `PadMetrics` 個 `cellW` 一定會頂到 `availW / cols`，
     * 即係成個螢幕咁闊，同「拉闊」睇落一樣。拉窄咗之後想還原唔使一路拖返出去。
     */
    override fun onMaxWidth() {
        val g = padGroup
        if (Prefs.widthScale(this, g) >= Prefs.MAX_WIDTH_SCALE) { toast("鍵盤闊度已是最大"); return }
        Prefs.setWidthScale(this, Prefs.MAX_WIDTH_SCALE, g)
        relayoutPads()
        refreshBars()
        toast("鍵盤闊度已設為最大")
    }

    /**
     * 上下拖 = 拉高／拉低而家見緊嗰組鍵盤（鍵盤永遠貼實底，唔會提起留個窿）。
     * 中文＋純數字一組、英文＋符號另一組，兩組各拉各（見 [padGroup]）。
     */
    override fun onSizeDrag(dyDp: Int) {
        if (dyDp == 0) return
        val g = padGroup
        // 由**而家實際嗰個**倍數開始加減，唔係 pref 嗰個 —— 未校過高度嘅闊 screen
        // 俾「最多半個螢幕」封咗頂（見 [PadMetrics]），由 pref 嗰個 100% 起計
        // 就會一拖落去反而彈高咗
        val w = if (padHolder.width > 0) padHolder.width else resources.displayMetrics.widthPixels
        val cur = PadMetrics(this, w, group = g).heightScale
        val next = (cur + dyDp / 250f).coerceIn(Prefs.MIN_HEIGHT_SCALE, Prefs.MAX_HEIGHT_SCALE)
        if (next != cur) { Prefs.setHeightScale(this, next, g); relayoutPads(); refreshBars() }
    }

    /**
     * 關聯字 bar 拉大／縮返：拉大嗰陣攞 `bars.expandedView` 蓋喺 padHolder 度（向下遮），
     * **唔會**加高成個 root，同 emoji／clipboard 個 overlay 一樣攞 `padHeightPx` 做高度。
     */
    override fun onExpandChanged(expanded: Boolean) {
        if (!::padHolder.isInitialized || candidatesExpanded == expanded) return
        candidatesExpanded = expanded
        val v = bars.expandedView
        (v.parent as? ViewGroup)?.removeView(v)
        // 蓋喺 [outer]（唔係 padHolder）—— 連上面條 bar 都要遮埋，見 [expandedLayoutParams]
        if (expanded) outer.addView(v, expandedLayoutParams())
    }

    /**
     * 拉大咗嘅關聯字幾大、擺喺邊：**成個鍵盤咁高**（連上面條 bar 嗰一兩行都食埋，
     * 2026-09-13 user 要求）。
     *
     * 以前淨係蓋住 `padHolder`，條 bar 照留喺上面 —— 拉大嗰下工具嗰行仲霸住成行，
     * 得返下面嗰橛出字，字一多就要捲好耐。而家由最頂起計，所以粒 ▲ 亦都要搬入
     * 塊嘢自己度（見 [OptionBarsView.expandedView]），唔係就連佢都俾自己遮住。
     *
     * 打橫嘅擺位照跟鍵盤本體（[panelLayoutParams]）。高度寫死做「條 bar ＋ 鍵盤」，
     * 唔用 MATCH_PARENT —— `outer` 係 wrap_content，MATCH_PARENT 會撐大個 IME window。
     */
    private fun expandedLayoutParams(): FrameLayout.LayoutParams {
        rememberPadHeight()
        val padH =
            if (padHeightPx > 0) padHeightPx else PadMetrics.defaultPadHeightPx(this).roundToInt()
        val barsH = if (bars.visibility == View.VISIBLE) bars.height else 0
        return panelLayoutParams(padH + barsH).also { it.gravity = it.gravity or Gravity.TOP }
    }

    /** 攤開住嘅關聯字：改咗顯示方式／拉過闊窄就要重新擺位 */
    private fun refreshExpandedLayout() {
        if (!candidatesExpanded || !::outer.isInitialized) return
        val v = bars.expandedView
        if (v.parent !== outer) return
        v.layoutParams = expandedLayoutParams()
    }

    override fun onTool(action: KeyAction) = onKey(Key(action))

    /**
     * 工具列粒掣長撳 —— 直接掉返俾 [onLongPress]，同鍵盤上面啲鍵行同一套。
     * 工具列冇得自己配長撳（[KeyLayout.TOOLS_HAVE_LONG]），所以一定係跌落
     * 粒掣自己嗰個內置動作（「貼上」＝剪貼簿歷史、🎤 ＝撳實一路錄）。
     */
    override fun onToolLong(key: Key): Boolean = onLongPress(key)

    /**
     * 撳實 🎤 一路錄。淨係 AI 語音輸入做得到（系統嗰個 recognizer 冇呢個模式），
     * 所以其餘情況回 false，粒掣照跌返落短撳。
     */
    override fun onSttHoldStart(): Boolean {
        if (!aiSttOn() || sttBusy) return false
        startAiStt(hold = true)
        // 就算開唔到咪（要去問權限、俾第二個 app 霸咗）都照當收咗呢下長撳：
        // 唔收嘅話放手嗰下會補返一下短撳，即刻再試多次，得個彈多次權限視窗
        return true
    }

    override fun onSttHoldEnd() {
        if (sttHold && sttRecorder != null) stopAiStt(commit = true)
    }

    // ---- AI 改寫 -----------------------------------------------------------

    /**
     * 長撳「AI改」：成個鍵盤位置攤開 prompt 名單（同長撳「貼上」開剪貼簿歷史同一招），
     * 撳邊個就用邊個 prompt 改寫。短撳就唔經呢度，一律用名單第一個。
     *
     * **揀之前唔可以郁個輸入框**：`showOverlay` 淨係喺 `padHolder` 加塊 view，
     * 冇搶 focus 亦都冇掂過個 selection，所以揀完落到 [runAi] 嗰陣，
     * user 原本揀住嗰段字仲喺度。
     */
    private fun openAiPrompts() {
        if (Prefs.aiApiKey(this).isBlank()) { toast("請先在設定頁輸入 Gemini API key"); return }
        if (!Prefs.aiRewriteOn(this)) { toast("AI 改寫已在設定頁關閉"); return }
        rememberPadHeight()
        showOverlay(AiPromptListView(this).apply {
            applyTheme(theme)
            forcedHeightPx = padHeightPx
            promptHost = AiPromptListView.PromptHost { p -> hideOverlay(); runAi(p.text) }
        })
    }

    /**
     * ✨：**冇揀住字都用得**。揀咗就淨係改揀咗嗰段，冇揀就當「改寫成個輸入框」——
     * 夾硬全選再交出去，返到嚟嗰段字直接取代成個欄嘅內容。
     *
     * 全選要喺出返嚟嗰陣**再做多次**：等緊 Gemini 嗰幾秒 user 隨時撳過個欄，
     * 一撳 caret 就散咗個 selection，`commitText` 就會變成插埋落去而唔係取代。
     */
    private fun runAi(template: String = Prefs.aiPrompt(this)) {
        val ic = currentInputConnection ?: return
        if (Prefs.aiApiKey(this).isBlank()) { toast("請先在設定頁輸入 Gemini API key"); return }
        if (!Prefs.aiRewriteOn(this)) { toast("AI 改寫已在設定頁關閉"); return }
        var selected = ic.getSelectedText(0)?.toString().orEmpty()
        val wholeField = selected.isBlank()
        if (wholeField) {
            val all = extractedAll()
            if (all.isBlank()) { toast("輸入框沒有文字，無法改寫"); return }
            ic.setSelection(0, all.length)
            selected = all
        }

        val myGen = ++aiGeneration
        showAiLoading()

        val timeout = Runnable {
            if (myGen != aiGeneration) return@Runnable
            aiGeneration++ // 令跟住嚟遲到嘅 callback 當第晒
            hideAiLoading()
            playErrorTone()
            toast("AI 逾時（10 秒沒有回應）")
        }
        ui.postDelayed(timeout, AI_TIMEOUT_MS)

        AiRewrite.rewrite(this, selected, template) { r ->
            if (myGen != aiGeneration) return@rewrite // 已經逾時處理咗
            ui.removeCallbacks(timeout)
            aiGeneration++
            hideAiLoading()
            r.onSuccess { out ->
                val c = currentInputConnection
                // 全欄改寫：等緊嗰陣個 selection 可能已經冇咗，出返嚟之前再全選一次
                if (wholeField) c?.setSelection(0, extractedAll().length)
                // commitText 會取代咗揀住嗰段
                c?.commitText(out, 1)
            }.onFailure {
                playErrorTone()
                toast("AI 失敗：" + (it.message ?: "未知錯誤"))
            }
        }
    }

    /** 成個輸入框而家有幾多字（攞唔到就當空） */
    private fun extractedAll(): String {
        val req = ExtractedTextRequest().apply { hintMaxChars = 100_000; hintMaxLines = 10_000 }
        return currentInputConnection?.getExtractedText(req, 0)?.text?.toString().orEmpty()
    }

    /** AI 處理緊嗰陣：成個 UI disable，中間出個轉緊嘅圈 */
    private fun showAiLoading() {
        showBlockingOverlay(ProgressBar(this))
    }

    /**
     * 喺成個鍵盤上面冚一塊半透明嘅嘢：底下啲鍵變灰，亦都撳唔到
     * （`isClickable` 食晒啲掂觸）。AI 改寫、AI 錄音、等緊辨識結果三樣都用呢個。
     *
     * 高度**寫死做 `root` 而家嘅高度**（度唔到就用預設鍵盤高度）：`outer` 係
     * wrap_content，用 MATCH_PARENT 會撐大咗成個 IME window。
     */
    private fun showBlockingOverlay(content: View): FrameLayout? {
        if (!::outer.isInitialized) return null
        hideAiLoading()
        val h = root.height.takeIf { it > 0 } ?: PadMetrics.defaultPadHeightPx(this).roundToInt()
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(170, 0, 0, 0))
            isClickable = true
            isFocusable = true
            addView(content, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER })
        }
        aiOverlay = overlay
        outer.addView(overlay, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, h))
        return overlay
    }

    private fun hideAiLoading() {
        val v = aiOverlay ?: return
        aiOverlay = null
        (v.parent as? ViewGroup)?.removeView(v)
    }

    /** load fail 嗰下嘟一聲，唔靠 [Prefs.sound]（嗰個係按鍵聲，呢個係錯誤提示） */
    private fun playErrorTone() = playTone(ToneGenerator.TONE_PROP_NACK, 300)

    /**
     * 提示音。每次開一個新 [ToneGenerator] 再 release —— 留住一個唔用就霸住個
     * audio session，IME 好多時喺背景瞓覺，霸住會累到人哋部機播歌都細聲咗。
     *
     * 音量跟 [Prefs.toneLevel]（設定頁「其他」）；0 級就連個 `ToneGenerator`
     * 都唔開，唔係播一段「音量 0」嘅聲一樣會搶咗人哋部機個 audio focus。
     */
    private fun playTone(tone: Int, ms: Int) {
        val vol = Prefs.toneVolume(Prefs.toneLevel(this))
        if (vol <= 0) return
        runCatching {
            val tg = ToneGenerator(AudioManager.STREAM_NOTIFICATION, vol)
            tg.startTone(tone, ms)
            ui.postDelayed({ runCatching { tg.release() } }, (ms + 100).toLong())
        }
    }

    private fun dpPx(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // ---- LatinPadView.LatinHost -------------------------------------------

    /**
     * 滑完一次。呢度做四件事：
     *
     *  1. 上一個字係滑出嚟（或者喺候選欄揀咗）嘅話，今次就當**下一個字** ——
     *     自動加返個空格，唔會好似以前咁 `setComposingText` 蓋咗上一個字。
     *  2. 冇（1）嘅話就攞 caret **前後**已經打咗嘅字母做 context：
     *     個欄係 `dis|y`，滑 `p→l→a` 就搵到 `display`（唔使成個字滑晒）。
     *  3. 用 [GestureDecoder] 將條原始軌跡同字典做形狀比對，揀最夾嘅幾個字。
     *  4. 出候選欄畀 user 揀第二個字，就算條 bar 本身係關住。
     */
    override fun onSwipePath(
        path: List<Float>,
        times: List<Long>,
        keyCenter: (Char) -> Pair<Float, Float>?,
        keyWidth: Float
    ) {
        val decoder = gestureDecoder() ?: return
        if (emojiSearch) {
            val word = decoder.decode(path, times, keyCenter, keyWidth).firstOrNull() ?: return
            emojiQuery.append(word)
            syncEmojiComposing()
            refreshEmojiResults()
            return
        }
        val ic = currentInputConnection ?: return
        ic.beginBatchEdit()
        finishLatinComposing()

        var pre = ""
        var suf = ""
        if (latinWordDone) {
            // 上一個字已經完成 → 今次係新一個字，兩個字之間補返個空格
            if (!endsWithSpace()) ic.commitText(" ", 1)
        } else {
            pre = wordCharsBefore()
            suf = wordCharsAfter()
            // 冇字母 context，即係新字開頭：前面貼住標點又冇隔空格就補一個
            if (pre.isEmpty()) autoSpaceAfterPunct()
        }

        // context 夾唔到就一步步放寬，唔好因為前後有嘢就一個字都出唔到
        var words = decoder.decode(path, times, keyCenter, keyWidth, pre.lowercase(), suf.lowercase())
        if (words.isEmpty() && suf.isNotEmpty()) {
            suf = ""; words = decoder.decode(path, times, keyCenter, keyWidth, pre.lowercase(), "")
        }
        if (words.isEmpty() && pre.isNotEmpty()) {
            pre = ""; words = decoder.decode(path, times, keyCenter, keyWidth, "", "")
        }
        if (words.isEmpty()) {
            // 乜都揾唔到，唔好屈硬出啲嘢 —— 當呢次滑冇發生過
            ic.endBatchEdit()
            return
        }

        // 攞咗前後嗰啲字母入個字度，就要喺個欄度剷返佢哋走
        if (pre.isNotEmpty() || suf.isNotEmpty()) ic.deleteSurroundingText(pre.length, suf.length)

        val shown = words.map { applyShiftToWord(it) }
        val first = shown.first()
        latinComposing.setLength(0)
        latinComposing.append(first)
        ic.setComposingText(first, 1)
        ic.endBatchEdit()

        latinSuggestions = shown
        lastCommittedWord = first
        latinWordDone = true
        latinSwiped = true
        forceCandidates = true
        latinPad?.let { if (it.shift == ShiftState.ON) { it.shift = ShiftState.OFF; it.rebuild() } }
        refreshBars()
    }

    @Volatile private var gestureDecoderCache: GestureDecoder? = null

    private fun gestureDecoder(): GestureDecoder? {
        gestureDecoderCache?.let { return it }
        val dict = EnDict.get() ?: return null
        return GestureDecoder(dict).also { gestureDecoderCache = it }
    }

    /** 見到英文 view 就喺背景砌埋（bucket index 要行成個詞庫），唔使等第一次滑先起 */
    private fun preloadGestureDecoder() {
        if (gestureDecoderCache != null) return
        Thread({
            var dict = EnDict.get()
            var waited = 0L
            while (dict == null && waited < 5000L) {
                Thread.sleep(50); waited += 50
                dict = EnDict.get()
            }
            dict?.let { gestureDecoderCache = GestureDecoder(it) }
        }, "tt-gesture-decoder").apply { priority = Thread.MIN_PRIORITY }.start()
    }

    /** caret 前面貼住嘅英文字母（`dis|y` → `dis`） */
    private fun wordCharsBefore(): String {
        val s = currentInputConnection?.getTextBeforeCursor(24, 0)?.toString().orEmpty()
        return s.takeLastWhile { it in 'a'..'z' || it in 'A'..'Z' }
    }

    /** caret 後面貼住嘅英文字母（`dis|y` → `y`） */
    private fun wordCharsAfter(): String {
        val s = currentInputConnection?.getTextAfterCursor(24, 0)?.toString().orEmpty()
        return s.takeWhile { it in 'a'..'z' || it in 'A'..'Z' }
    }

    /**
     * 游標前面**貼實**住個英文字（字母／數字／`'`）—— 即係喺呢度直接打落去會黐埋
     * 上一個字，要自己補返個空格。空格、標點、換行、中文字、或者成個欄都係吉嗰陣
     * 就 false（嗰啲位置本來就係一個字嘅開頭）。
     */
    private fun needSpaceBeforeWord(): Boolean {
        val c = currentInputConnection?.getTextBeforeCursor(1, 0)?.toString().orEmpty()
        if (c.isEmpty()) return false
        val ch = c[0]
        return ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch == '\''
    }

    private fun endsWithSpace(): Boolean {
        val s = currentInputConnection?.getTextBeforeCursor(1, 0)?.toString().orEmpty()
        return s.isEmpty() || s[0].isWhitespace()
    }

    private fun applyShiftToWord(w: String): String {
        val pad = latinPad ?: return w
        return when (pad.shift) {
            ShiftState.OFF -> w
            ShiftState.ON -> w.replaceFirstChar { it.uppercase() }
            ShiftState.LOCK -> w.uppercase()
        }
    }

    // ---- 語音輸入 (廣東話) -------------------------------------------------

    private fun toggleStt() {
        // AI 語音輸入開咗就完全頂走系統嗰個 recognizer（撳一下開始，再撳一下停）
        if (aiSttOn()) {
            if (sttRecorder != null) stopAiStt(commit = true) else startAiStt(hold = false)
            return
        }
        if (listening) { stopStt(); return }
        // 粒 🎤 而家喺工具 bar 度（貼上隔籬），聽緊嘢就著燈
        if (!ensureMicPermission()) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            toast("此裝置沒有語音輸入服務")
            return
        }
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { listening = true }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) { listening = false; setSttLight(false); releaseRecognizer() }
            override fun onResults(results: Bundle?) {
                sttBest(results)?.let { commitSttText(it) }
                listening = false
                setSttLight(false)
                releaseRecognizer()
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val locale = Prefs.sttLocale(this)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, locale)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        }
        listening = true
        setSttLight(true)
        toast("🎤 聆聽中…")
        runCatching { r.startListening(intent) }.onFailure {
            listening = false; releaseRecognizer()
        }
    }

    private fun stopStt() {
        if (listening) { runCatching { recognizer?.stopListening() } }
        listening = false
        setSttLight(false)
        releaseRecognizer()
    }

    private fun setSttLight(on: Boolean) {
        ui.post {
            if (::bars.isInitialized) bars.setSttActive(on)
            sidePanel?.setSttActive(on)
        }
    }

    private fun releaseRecognizer() {
        ui.post {
            runCatching { recognizer?.destroy() }
            recognizer = null
        }
    }

    /** 冇錄音權限就彈個透明 activity 去問，回 false = 而家未用得 */
    private fun ensureMicPermission(): Boolean {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) return true
        startActivity(Intent(this, MicPermissionActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return false
    }

    // ---- AI 語音輸入 -------------------------------------------------------

    /**
     * 用 AI 做語音輸入（`AiStt`）而唔係系統嗰個 `SpeechRecognizer`。
     * 要設定頁開咗、有 API key、而且**用緊 Gemini**（自訂 API 送唔到錄音上去，
     * 見 [Prefs.aiSttOn]）。差一樣就照跌返落 [toggleStt] 原本嗰條路。
     */
    private fun aiSttOn() = Prefs.aiSttOn(this) && Prefs.aiApiKey(this).isNotBlank()

    /**
     * 開始錄音。[hold] = 撳實錄嗰種（放手就收工），false = 撳一下開始、再撳一下停。
     *
     * 錄緊同埋等緊結果嗰陣成個鍵盤俾 [showBlockingOverlay] 蓋住（變灰兼撳唔到），
     * 所以「再撳一下停」係撳嗰塊 overlay，唔係撳返粒 🎤。
     */
    private fun startAiStt(hold: Boolean) {
        if (sttBusy || sttRecorder != null) return
        if (!ensureMicPermission()) return
        val rec = VoiceRecorder()
        if (!rec.start()) {
            playSttTone(SttTone.FAIL)
            toast("開唔到麥克風，請檢查權限或其他正在錄音的程式")
            return
        }
        sttRecorder = rec
        sttHold = hold
        sttBusy = true
        setSttLight(true)
        playSttTone(SttTone.START)
        // 兩邊一齊開：未夠 [Prefs.aiSttSysSec] 秒就放手，攞系統嗰個嘅結果
        // （快、免費、唔使等 upload）；過咗界就 cancel 咗系統嗰個，淨返 AI
        val sysMs = Prefs.aiSttSysSec(this) * 1000L
        sysSttDeadlineMs = if (sysMs > 0 && startSysStt()) sysMs else 0L
        showSttRecording(hold)
    }

    /**
     * 收工。[commit] = false 就淨係丟咗段錄音（收返啲資源，唔叫 API）。
     *
     * `VoiceRecorder.stop()` 自己會篩：太短（撳錯／彈手）同埋由頭到尾冇人講過嘢
     * （[VoiceClip.Silent]）兩種都**唔會**叫 API —— 一個 request 掟幾百 KB 上去
     * 等足幾秒，出返一句「（沒有聲音）」係好嘥。（[VoiceClip.Silent] 嗰個篩喺
     * 有系統 STT 陪住嗰陣會鬆返，原因見下面。）
     */
    private fun stopAiStt(commit: Boolean) {
        val rec = sttRecorder ?: return
        val recMs = rec.elapsedMs // 要喺 stop() 之前攞，佢一收工個計時器就清零
        sttRecorder = null
        sttHold = false
        stopSttTimer()
        setSttLight(false)
        val clip = if (commit) rec.stop() else { rec.cancel(); null }
        // 仲未過界（[sysSttDeadlineMs]）就由系統嗰個 recognizer 交貨。
        // 但係佢**早咗好耐**就自己收咗工（靜咗一陣當你講完）嘅話唔好信 ——
        // 佢嗰句實係斬到一半，餘下嗰段淨係我哋自己條錄音先有，照送去 AI。
        val sysTailLost = sysSttFinished && sysSttEndedAtMs >= 0 &&
            recMs - sysSttEndedAtMs > SYS_STT_TAIL_MS
        val useSys = commit && sysStt != null && !sysTailLost
        // 唔靠系統嗰邊就即刻收咗佢，唔好留住個 recognizer 聽落去（下一次錄音
        // 會見到 `sysStt` 仲喺度，當咗係今次開嘅）
        if (!useSys) stopSysSttWait()
        // 太短（撳錯／彈手）兩邊都唔使問。但係「聽唔到聲」淨係喺冇系統 STT
        // 嗰陣先當冇嘢錄到 —— 個 VAD 睇嘅係我哋自己錄嗰條 PCM，而兩個 client
        // 同時開咪係部機話事，靜咗嘅可能係我哋呢邊，系統嗰邊照聽到。
        if (clip == null || clip is VoiceClip.TooShort || (clip !is VoiceClip.Ready && !useSys)) {
            sttBusy = false
            hideAiLoading()
            if (commit) {
                playSttTone(SttTone.FAIL)
                toast(if (clip is VoiceClip.Silent) "沒有聽到說話，已取消" else "錄音太短")
            }
            return
        }
        playSttTone(SttTone.STOP)
        showSttWaiting()
        val ready = clip as? VoiceClip.Ready
        if (useSys) waitSysStt(ready) else if (ready != null) startAiTranscribe(ready)
    }

    /** 段錄音送上 Gemini。呢步之前一定已經出咗 [showSttWaiting] */
    private fun startAiTranscribe(clip: VoiceClip.Ready) {
        val myGen = ++sttGeneration
        val timeout = Runnable {
            if (myGen != sttGeneration) return@Runnable
            sttGeneration++
            sttBusy = false
            hideAiLoading()
            playSttTone(SttTone.FAIL)
            toast("語音輸入逾時")
        }
        ui.postDelayed(timeout, STT_TIMEOUT_MS)

        AiStt.transcribe(this, clip, sttContext()) { r ->
            if (myGen != sttGeneration) return@transcribe // 已經逾時處理咗
            ui.removeCallbacks(timeout)
            sttGeneration++
            sttBusy = false
            hideAiLoading()
            r.onSuccess { out ->
                val text = out.trim()
                if (text.isEmpty()) {
                    playSttTone(SttTone.FAIL)
                    toast("聽唔到內容")
                    return@onSuccess
                }
                playSttTone(SttTone.OK)
                commitSttText(text)
            }.onFailure {
                playSttTone(SttTone.FAIL)
                toast("語音輸入失敗：" + (it.message ?: "未知錯誤"))
            }
        }
    }

    /**
     * 送埋落 prompt 嘅上下文：輸入框而家嘅內容（節錄）。
     * 前面攞多啲（剛講完嘅嘢通常喺 caret 前面），後面攞少少夠知個句點喺邊就得。
     */
    private fun sttContext(): String {
        val ic = currentInputConnection ?: return ""
        val before = ic.getTextBeforeCursor(STT_CONTEXT_BEFORE, 0)?.toString().orEmpty()
        val after = ic.getTextAfterCursor(STT_CONTEXT_AFTER, 0)?.toString().orEmpty()
        return (before + after).trim()
    }

    /** 錄緊嘢：成個鍵盤蓋住，中間出個計時器 */
    private fun showSttRecording(hold: Boolean) {
        val timer = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 30f
            gravity = Gravity.CENTER
        }
        sttTimerLabel = timer
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(timer)
            addView(TextView(this@TTInputMethodService).apply {
                text = if (hold) "🎤 放開即停" else "🎤 輕觸任何位置停止"
                setTextColor(Color.argb(210, 255, 255, 255))
                textSize = 14f
                gravity = Gravity.CENTER
            })
        }
        // 撳實錄嗰種唔使理呢下撳（放手自然會停），但擺住都冇壞：
        // 手指仲撳實住粒 🎤，成串 event 都會繼續派返俾佢，唔會落到呢度
        showBlockingOverlay(col)?.setOnClickListener { stopAiStt(commit = true) }
        updateSttTimer()
        ui.post(sttTimerTick)
    }

    /** 等緊 Gemini：一樣蓋住，中間換做個轉緊嘅圈 */
    private fun showSttWaiting() {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(ProgressBar(this@TTInputMethodService))
            addView(TextView(this@TTInputMethodService).apply {
                text = "辨識中…"
                setTextColor(Color.argb(210, 255, 255, 255))
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(0, dpPx(10), 0, 0)
            })
        }
        showBlockingOverlay(col)
    }

    private val sttTimerTick = object : Runnable {
        override fun run() {
            val rec = sttRecorder ?: return
            updateSttTimer()
            // 過咗界就唔再靠系統 STT：cancel 咗佢，段錄音照錄落去，最後送上 AI
            if (sysStt != null && sysSttDeadlineMs > 0 && rec.elapsedMs >= sysSttDeadlineMs) {
                releaseSysStt()
            }
            // 封頂：一 request 掟幾十 MB 上去實 timeout，夠鐘就當 user 撳咗停
            if (rec.elapsedMs >= AiStt.MAX_RECORD_MS) { stopAiStt(commit = true); return }
            ui.postDelayed(this, 100)
        }
    }

    private fun updateSttTimer() {
        val ms = sttRecorder?.elapsedMs ?: return
        val tenths = ms / 100
        sttTimerLabel?.text = "● %d:%02d.%d".format(tenths / 600, (tenths / 10) % 60, tenths % 10)
    }

    private fun stopSttTimer() {
        ui.removeCallbacks(sttTimerTick)
        sttTimerLabel = null
    }

    /** 唔要而家錄緊／等緊嗰次（離開個欄、service 收工）：唔叫 API，亦都唔出聲 */
    private fun cancelAiStt() {
        sttGeneration++ // 遲到嘅回覆當第
        if (sttRecorder != null) stopAiStt(commit = false)
        stopSysSttWait() // 錄完、等緊系統嗰邊交貨嗰段都要收
        sttBusy = false
        stopSttTimer()
        hideAiLoading()
    }

    // ---- 短錄音改用系統 STT -------------------------------------------------

    /**
     * 同 [VoiceRecorder] 一齊開埋系統嗰個 recognizer。回 false = 開唔到
     * （部機冇語音服務、`createSpeechRecognizer` 失敗），今次就照舊淨係得 AI。
     *
     * **兩個 client 同時開咪係部機話事嘅**：Android 10 之後嗰套 audio policy
     * 隨時會靜咗其中一邊（嗰邊讀到嘅係一條全零嘅 PCM，唔會報錯）。所以兩邊都
     * 有後路 —— 系統嗰邊空手回就跌返落 AI（見 [finishSysStt]），我哋自己錄嗰條
     * 錄音就算 VAD 判咗冇聲都唔會即刻當失敗（見 [stopAiStt]）。
     */
    private fun startSysStt(): Boolean {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return false
        val r = runCatching { SpeechRecognizer.createSpeechRecognizer(this) }.getOrNull()
            ?: return false
        sysStt = r
        sysSttText = null
        sysSttFinished = false
        sysSttEndedAtMs = -1L
        sysSttPending = null
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) { onSysSttFinal(null) }
            override fun onResults(results: Bundle?) { onSysSttFinal(sttBest(results)) }
            override fun onPartialResults(partialResults: Bundle?) {
                // 有啲 recognizer 收工淨係派 partial，final 嗰個 bundle 係吉嘅，
                // 所以逐段記低，攞唔到 final 就用返最後聽到嗰句
                sttBest(partialResults)?.let { sysSttText = it }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val locale = Prefs.sttLocale(this)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, locale)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            // 幾時收工由我哋話事（放手／過界），唔好靜咗一陣就自己埋單 ——
            // 唔係講到一半唞啖氣，出返嚟就淨係得半句。呢兩個 extra 唔係每個
            // recognizer 都認，所以認唔認都要有後路（見上面）。
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                SYS_STT_SILENCE_MS)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                SYS_STT_SILENCE_MS)
        }
        // 開之前先靜咗部機：佢自己嗰兩下「開始／完結」提示聲喺佢個 process 度播，
        // 我哋條 playTone 管唔到（見 [muteEarcons]）
        if (Prefs.sttMuteEarcon(this)) muteEarcons()
        runCatching { r.startListening(intent) }.onFailure {
            releaseSysStt()
            return false
        }
        return true
    }

    /**
     * 夾硬靜咗 [EARCON_STREAMS] 嗰幾條 stream，遮住語音辨識服務自己播嗰兩下
     * 提示聲。**冇公開 API 叫佢唔好播**，靜音係業界通用嗰個 workaround。
     *
     * 三個位要小心：
     *
     *  - **逐條 stream 分開試**。靜 `STREAM_SYSTEM` 喺唔少機上面等同郁鈴聲模式，
     *    冇 notification policy access 就會掟 `SecurityException` —— 掟就跳過嗰條，
     *    唔好連 `STREAM_MUSIC` 都一齊唔做。靜到邊幾條就記低邊幾條，還原淨係還原嗰啲。
     *  - **唔掂 `STREAM_NOTIFICATION`**：我哋自己嗰四下提示音就係喺嗰條
     *    （見 [playTone]），一齊靜埋就連自己嗰啲都聽唔到。
     *  - **點都要還原**。除咗正路收工嗰下（[releaseSysStt]），仲有條
     *    [EARCON_MUTE_MAX_MS] 安全網 —— 有咩意外都唔可以留低部機靜咗。
     */
    private fun muteEarcons() {
        if (mutedStreams.isNotEmpty()) return
        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        mutedStreams = EARCON_STREAMS.filter { stream ->
            runCatching { am.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, 0) }.isSuccess
        }
        if (mutedStreams.isEmpty()) return
        scheduleUnmute(EARCON_MUTE_MAX_MS)
    }

    /** [delay] 之後還原音量。收工嗰下拖多陣先還原，等埋佢嗰下「完結」聲播完 */
    private fun scheduleUnmute(delay: Long) {
        if (mutedStreams.isEmpty()) return
        unmuteRun?.let { ui.removeCallbacks(it) }
        val run = Runnable { unmuteEarcons() }
        unmuteRun = run
        ui.postDelayed(run, delay)
    }

    private fun unmuteEarcons() {
        unmuteRun?.let { ui.removeCallbacks(it) }
        unmuteRun = null
        val streams = mutedStreams
        if (streams.isEmpty()) return
        mutedStreams = emptyList()
        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        streams.forEach { runCatching { am.adjustStreamVolume(it, AudioManager.ADJUST_UNMUTE, 0) } }
    }

    /** `onResults`／`onError` 都行呢度：記低最好嗰句，等緊嘅話就即刻交貨 */
    private fun onSysSttFinal(text: String?) {
        if (!text.isNullOrBlank()) sysSttText = text
        sysSttFinished = true
        sysSttEndedAtMs = sttRecorder?.elapsedMs ?: -1L
        sysSttPending?.invoke(sysSttText)
    }

    /**
     * 放手嗰陣仲未過界：等系統 recognizer 交貨。佢空手回（聽唔到、出錯、
     * 等到 [SYS_STT_WAIT_MS] 都唔應）就跌返落 AI —— 呢招本來就係為咗慳，
     * 慳唔到都唔可以當今次語音輸入失敗。
     *
     * [clip] = null 即係我哋自己錄嗰條 VAD 判咗冇聲，冇得跌。
     */
    private fun waitSysStt(clip: VoiceClip.Ready?) {
        val myGen = ++sttGeneration
        val timeout = Runnable { finishSysStt(myGen, clip, sysSttText) }
        sysSttTimeout = timeout
        ui.postDelayed(timeout, SYS_STT_WAIT_MS)
        // 講完一句停一停，有啲 recognizer 未放手就已經自己埋咗單
        if (sysSttFinished) { finishSysStt(myGen, clip, sysSttText); return }
        sysSttPending = { finishSysStt(myGen, clip, it) }
        runCatching { sysStt?.stopListening() }
    }

    /** 系統嗰邊嘅結局：出到嘢就直接入框，空手就跌返落 [startAiTranscribe] */
    private fun finishSysStt(gen: Int, clip: VoiceClip.Ready?, text: String?) {
        if (gen != sttGeneration) return
        sttGeneration++
        stopSysSttWait()
        val out = text?.trim().orEmpty()
        if (out.isNotEmpty()) {
            sttBusy = false
            hideAiLoading()
            playSttTone(SttTone.OK)
            commitSttText(out)
            return
        }
        if (clip != null) { startAiTranscribe(clip); return }
        sttBusy = false
        hideAiLoading()
        playSttTone(SttTone.FAIL)
        toast("沒有聽到說話，已取消")
    }

    /** 唔再等系統嗰邊，順手收返個 recognizer */
    private fun stopSysSttWait() {
        sysSttTimeout?.let { ui.removeCallbacks(it) }
        sysSttTimeout = null
        releaseSysStt()
    }

    /** cancel 咗就唔會再派結果過嚟，之後 [sysStt] 係 null 就代表呢招今次收咗檔 */
    private fun releaseSysStt() {
        val r = sysStt ?: return
        sysStt = null
        sysSttPending = null
        // 收咗工先還原音量，但要拖多陣 —— 佢嗰下「完結」聲係停止聆聽之後先播
        scheduleUnmute(EARCON_TAIL_MS)
        // 同 [releaseRecognizer] 一樣 post 出去：呢度好多時係喺佢自己個
        // listener callback 入面叫，即場 destroy 有啲實作會炸
        ui.post {
            runCatching { r.cancel() }
            runCatching { r.destroy() }
        }
    }

    /** 一個 recognizer bundle 入面第一句有料嘅 */
    private fun sttBest(b: Bundle?): String? =
        b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull { !it.isNullOrBlank() }

    /** 語音辨識出嚟嗰段字入框（簡體輸出開咗就順手轉埋） */
    private fun commitSttText(text: String) {
        currentInputConnection?.commitText(
            if (engine.scOutput) db?.tcsc(text) ?: text else text, 1)
    }

    /** 四個階段四把唔同嘅聲：開始錄、錄完、成功、失敗 */
    private enum class SttTone { START, STOP, OK, FAIL }

    private fun playSttTone(t: SttTone) = when (t) {
        SttTone.START -> playTone(ToneGenerator.TONE_PROP_BEEP, 120)
        SttTone.STOP -> playTone(ToneGenerator.TONE_PROP_BEEP2, 200)
        SttTone.OK -> playTone(ToneGenerator.TONE_PROP_ACK, 200)
        SttTone.FAIL -> playTone(ToneGenerator.TONE_PROP_NACK, 300)
    }

    companion object {
        private const val TAG = "TT"
        /** 連撳兩下 shift 幾快先當 capslock */
        private const val DOUBLE_TAP_MS = 400L
        /** AI 攞 10 秒都未有回應就當 error */
        private const val AI_TIMEOUT_MS = 10_000L
        /** 語音辨識要成段錄音上傳，比改寫慢好多，所以放鬆到 100 秒 */
        private const val STT_TIMEOUT_MS = 100_000L
        /** 放手之後等系統 recognizer 幾耐；等唔到就跌返落 AI */
        private const val SYS_STT_WAIT_MS = 8_000L
        /**
         * 叫系統 recognizer 唔好因為靜咗一陣就自己收工（幾時收工由我哋話事）。
         * 揀到咁大係因為呢招最多都係頂到 [Prefs.MAX_AI_STT_SYS_SEC] 秒。
         */
        private const val SYS_STT_SILENCE_MS = 30_000
        /**
         * 系統 recognizer 早過放手幾多先當佢斬咗尾（見 [stopAiStt]）。
         * 正路講完一句就放手，兩者差極都係幾百毫秒。
         */
        private const val SYS_STT_TAIL_MS = 1_500L
        /**
         * 開系統 recognizer 嗰陣靜邊幾條 stream（見 [muteEarcons]）。
         * **唔可以有 `STREAM_NOTIFICATION`** —— 我哋自己嗰四下提示音喺嗰條。
         */
        private val EARCON_STREAMS = listOf(AudioManager.STREAM_MUSIC, AudioManager.STREAM_SYSTEM)
        /** 停止聆聽之後仲要靜幾耐，等埋佢嗰下「完結」聲播完 */
        private const val EARCON_TAIL_MS = 700L
        /** 安全網：靜咗最多咁耐，之後點都還原（見 [muteEarcons]） */
        private const val EARCON_MUTE_MAX_MS = 20_000L
        /** 送去 AI 做上下文嘅字數：caret 前面／後面各攞幾多 */
        private const val STT_CONTEXT_BEFORE = 400
        private const val STT_CONTEXT_AFTER = 100
        /**
         * 游標前面吉住／唔係中文嗰陣，候選欄出 `mapped_table` 呢個 id
         * （最常用嗰行字）。**唔係速選字表 1000** —— 嗰行係符號同口語字。
         */
        private const val DEFAULT_PICK_ID = 1010
        /** 舊版 dataset.db 冇 [DEFAULT_PICK_ID]，跌返落呢個（速選字表，以前嘅做法） */
        private const val LEGACY_PICK_ID = 1000
        /** 打咗一兩個碼嗰陣，條 bar 出幾多隻「呢個碼最常用」嘅字 */
        private const val BAR_PREVIEW_COUNT = 9

        /** 中文九宮格永遠 4 行（[keyRowHeightPx] 度唔到行數嗰陣嘅預設） */
        private const val CJK_ROWS = 4
        /** 開完鍵盤幾耐補度一次尺寸（見 [scheduleSizeRecheck]） */
        private const val SIZE_RECHECK_MS = 100L
        /** 補度幾多次 —— 有啲機要等埋 insets 落嚟先報得到啱嘅高度 */
        private const val SIZE_RECHECK_TRIES = 3
        /** 一輪入面最多重排幾多次（見 [recheckPadSize]），唔係就可能一路重排落去 */
        private const val SIZE_MAX_FIXES = 2
    }
}
