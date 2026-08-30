package tt.ime.riverine.ime

import android.Manifest
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
import tt.ime.riverine.core.BarMode
import tt.ime.riverine.core.ClipHistory
import tt.ime.riverine.core.EmojiDict
import tt.ime.riverine.core.EnDict
import tt.ime.riverine.core.NextWordModel
import tt.ime.riverine.core.PadAlign
import tt.ime.riverine.core.PadGroup
import tt.ime.riverine.core.PagerLayout
import tt.ime.riverine.core.Prefs
import tt.ime.riverine.core.TTDb
import tt.ime.riverine.core.TTCmd
import tt.ime.riverine.core.TTEngine
import tt.ime.riverine.core.UsageStats
import tt.ime.riverine.core.VoiceClip
import tt.ime.riverine.core.VoiceRecorder
import tt.ime.riverine.swipe.GestureDecoder
import tt.ime.riverine.ui.MicPermissionActivity
import kotlin.math.abs
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
    /** 候選字 bar 拉大咗：`bars.expandedView` 蓋喺 padHolder 度（見 [onExpandChanged]） */
    private var candidatesExpanded = false
    private var aiOverlay: View? = null
    private var aiGeneration = 0

    private var mode = PadMode.CHINESE
    private var theme = Theme(false)
    private var barMode = BarMode.CANDIDATES
    private var enterLabel = "⏎"

    private var emailField = false
    private var pinField = false
    /** URL／email／密碼／關咗提示嘅欄：唔好自作聰明補空格（見 [autoSpaceAfterPunct]） */
    private var noAutoSpaceField = false
    private var hasSelection = false
    /** 而家有冇嘢俾 AI 改（揀咗一段，或者成個輸入框有字） */
    private var aiUsable = false
    /** 設定頁有冇入 Gemini API key —— 冇就成粒 ✨ 唔見咗，唔係淨係灰咗 */
    private var aiKeySet = false
    private val latinComposing = StringBuilder()
    private var latinSuggestions: List<String> = emptyList()

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

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        runCatching { ClipHistory.current(this) }
    }

    /**
     * 九宮格右上角嗰粒要唔要著燈。平時 = 條 bar 開住；條 bar 常駐嗰陣粒鍵已經
     * 唔再係開關，而係候選字 ⇄ 工具嘅切換掣，所以改為代表「而家喺工具嗰邊」——
     * 一路著住藍燈冇資訊可言。
     */
    override val optionOn: Boolean
        get() = if (Prefs.barPinned(this)) barMode == BarMode.TOOLS else barMode != BarMode.OFF
    override val aiReady: Boolean get() = aiUsable

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
        clipboard()?.removePrimaryClipChangedListener(clipListener)
        db?.close()
        super.onDestroy()
    }

    private fun clipboard(): ClipboardManager? =
        getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

    override fun onCreateInputView(): View {
        theme = Theme.of(this)
        StrokeImages.configure(theme.dark)
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

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        engine.scOutput = Prefs.scOutput(this)
        // 設定頁改完個開關唔會 restart 個 service，所以每次入欄都要重新讀
        engine.usageReorder = Prefs.usageReorder(this)
        barMode = Prefs.barMode(this)
        latinComposing.setLength(0)
        latinSuggestions = emptyList()
        latinWordDone = false
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
        val isEmail = variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
        val isNumberPassword = cls == InputType.TYPE_CLASS_NUMBER &&
            variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
        val isUri = variation == InputType.TYPE_TEXT_VARIATION_URI
        val isPassword = variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        val noSuggestions = (info.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0

        emailField = isEmail
        pinField = isNumberPassword
        noAutoSpaceField = isUri || isEmail || isPassword || isNumberPassword || noSuggestions
        latinPad?.emailMode = isEmail
        numberPad?.pinMode = isNumberPassword
        hasSelection = currentInputConnection?.getSelectedText(0)?.isNotEmpty() == true
        refreshAiState()

        val want = when {
            cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE ||
                cls == InputType.TYPE_CLASS_DATETIME -> PadMode.NUMBER
            isEmail || isUri || isPassword -> PadMode.LATIN
            else -> PadMode.CHINESE
        }
        switchMode(want, force = true)
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
    }

    /** 搜尋欄要出放大鏡（單色，見 [SEARCH_GLYPH]），唔係就照出 ⏎ */
    private fun enterLabelFor(ei: EditorInfo?): String {
        val action = ei?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE
        val noEnter = (ei?.imeOptions?.and(EditorInfo.IME_FLAG_NO_ENTER_ACTION) ?: 0) != 0
        return if (!noEnter && action == EditorInfo.IME_ACTION_SEARCH) SEARCH_GLYPH else "⏎"
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
        bars.forceCollapse() // 唔係就下面 removeAllViews() 會靜靜雞清埋拉大咗嘅候選字 view
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
                }).also { it.emailMode = emailField }
            }
            PadMode.SYMBOL -> symbolPad ?: SymbolPadView(this).also {
                it.host = this; it.applyTheme(theme); symbolPad = it
            }
            PadMode.NUMBER -> (numberPad ?: NumberPadView(this).also {
                it.host = this; it.applyTheme(theme); numberPad = it
            }).also { it.pinMode = pinField }
            PadMode.EMOJI -> (emojiPad ?: EmojiPadView(this).also {
                it.emojiHost = this; it.applyTheme(theme); emojiPad = it
            }).also {
                it.forcedHeightPx = padHeightPx
                it.rebuild()
            }
        }
        (v.parent as? ViewGroup)?.removeView(v)
        padHolder.addView(v, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        (v as? KeyboardBaseView)?.enterLabel = enterLabel
        (v as? RowsPadView)?.rebuild()
        (v as? ChinesePadView)?.onSettingsChanged()
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
            KeyAction.TO_CHINESE -> switchMode(PadMode.CHINESE)
            KeyAction.TO_LATIN -> switchMode(PadMode.LATIN)
            KeyAction.TO_SYMBOL -> { switchMode(PadMode.SYMBOL); symbolPad?.page = 0 }
            KeyAction.TO_NUMBER -> switchMode(PadMode.NUMBER)
            KeyAction.TO_EMOJI -> openEmoji()
            KeyAction.PASTE -> paste()
            KeyAction.AI -> runAi()
            KeyAction.SYM_PAGE -> symbolPad?.let { it.page = 1 - it.page }
            KeyAction.IME_SWITCH -> switchIme()
            KeyAction.IME_PICKER -> showImePicker()
            KeyAction.STT -> toggleStt()
            KeyAction.OPTION -> toggleBar()
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
            KeyAction.IME_SWITCH -> { showImePicker(); return true }
            KeyAction.SHIFT -> {
                latinPad?.let { it.shift = ShiftState.LOCK; it.rebuild() }
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
            emojiQuery.append(s)
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
            refreshBars()
            return
        }
        finishLatinComposing()
        latinWordDone = false
        currentInputConnection?.commitText(s, 1)
        if (mode == PadMode.CHINESE) engine.cancel().also { onStateChanged() }
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
        pad.rebuild()
    }

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
            refreshBars()
            return
        }
        val ic = currentInputConnection ?: return
        val sel = ic.getSelectedText(0)
        if (sel != null && sel.isNotEmpty()) ic.commitText("", 1)
        else ic.deleteSurroundingText(1, 0)
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
        if (mode == PadMode.LATIN && prevWord.isNotEmpty()) {
            lastCommittedWord = prevWord
            latinSuggestions = NextWordModel.get()?.predictNext(prevWord) ?: emptyList()
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

    /**
     * 打緊字嗰陣（[latinComposing] 唔係空）出嘅提示：先用 [lastCommittedWord] 做 context
     * 揾 bigram 夾 prefix 嘅字（AOSP 標準嘅 N-gram 做法），唔夠先用 [EnDict] 補齊。
     */
    private fun latinTypingSuggestions(): List<String> {
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

    private fun commitPlain(text: String) {
        finishLatinComposing()
        if (mode == PadMode.CHINESE) engine.cancel()
        currentInputConnection?.commitText(text, 1)
        onStateChanged()
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

    private fun showOverlay(v: View) {
        if (!::padHolder.isInitialized) return
        hideOverlay()
        overlay = v
        padHolder.addView(v, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
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
     * 九宮格右上角嗰粒。平時 `☰` = 開／關成條 bar，一開返永遠先入候選字 view。
     *
     * **條 bar 常駐（[Prefs.barPinned]）嗰陣冇嘢好開關**，粒鍵改咗做 `⇄`：
     * 喺候選字同工具之間切，同條 bar 最左本來嗰粒一模一樣（嗰粒亦都因此收埋咗，
     * 見 [refreshBars] 嗰句 `setSwitchVisible`）。
     */
    private fun toggleBar() {
        if (Prefs.barPinned(this)) { onSwitchView(); chinesePad?.invalidate(); return }
        barMode = if (barMode == BarMode.OFF) BarMode.CANDIDATES else BarMode.OFF
        Prefs.setBarMode(this, barMode)
        refreshBars()
        chinesePad?.invalidate()
    }

    /** 條 bar 最左嗰粒：喺候選字／工具兩個 view 之間切 */
    override fun onSwitchView() {
        barMode = if (barMode == BarMode.TOOLS) BarMode.CANDIDATES else BarMode.TOOLS
        Prefs.setBarMode(this, barMode)
        refreshBars()
        chinesePad?.invalidate() // 常駐模式：右上角嗰粒著燈與否跟住呢個狀態行
    }

    /** emoji 表／剪貼簿開住：一定要有條 bar 出返粒 ✖，唔係就返唔到去普通鍵盤 */
    private val specialPad: Boolean get() = mode == PadMode.EMOJI || overlay != null

    private fun refreshBars() {
        if (!::bars.isInitialized) return
        val pinned = Prefs.barPinned(this)
        // 常駐：關唔熄得。舊設定裡面存住 OFF 就當場升做候選字（設定頁開個掣嗰陣
        // 唔會 restart 個 service，所以要喺呢度補）
        if (pinned && barMode == BarMode.OFF) {
            barMode = BarMode.CANDIDATES
            Prefs.setBarMode(this, barMode)
        }
        // 搵 emoji 嗰陣一定要見到啲結果，就算條 bar 本身係關住
        var effective = if (emojiSearch || (forceCandidates && latinSuggestions.isNotEmpty()))
            BarMode.CANDIDATES else barMode
        // 英文／符號頁夾硬開返條 bar：呢兩頁靠佢出打字提示同滑動出嚟嘅字，
        // 冇咗就等於打盲舖。**唔會改到 [barMode] 本身** —— 返到中文頁
        // 照樣跟返 user 設定嘅開關。
        if ((mode == PadMode.LATIN || mode == PadMode.SYMBOL) && effective == BarMode.OFF) {
            effective = BarMode.CANDIDATES
        }
        // emoji 表／剪貼簿嗰陣冇候選字可以出，索性成行出工具，唔好淨係得粒 ✖ 吉住
        if (specialPad) effective = BarMode.TOOLS

        // 中文本體窄到夠位喺隔籬擺嘢 → 條 bar 收埋，功能掣同候選字全部搬去側邊欄。
        // 一早計定：側邊欄兩樣（候選字＋工具）一次過見晒，所以佢出咗嚟就一定要有候選字
        val geom = if (barMode == BarMode.OFF || overlay != null) null else sideGeom()
        val wantCands = geom != null || effective == BarMode.CANDIDATES

        showingContextPicks = false
        contextBarPicks = emptyList()
        val cands = when {
            emojiSearch -> emojiResults
            mode == PadMode.CHINESE -> when {
                engine.selectMode -> engine.selectWords
                // 根本冇出緊候選字（條 bar 關咗／而家喺工具嗰邊）就唔使查
                // ——[contextPicks] 要問個輸入框攞字（IPC），逐粒鍵行一次好唔抵
                !wantCands -> emptyList()
                // 打咗一兩個碼（未夠碼出字）：出「呢個碼開頭最常用嗰九隻字」
                engine.currCode.isNotEmpty() -> contextBar(codePreview(engine.currCode))
                // 乜都未打：跟游標前面嗰隻字（唔係「啱啱打完嗰隻」）
                else -> contextBar(contextPicks())
            }
            mode == PadMode.LATIN || mode == PadMode.SYMBOL -> latinSuggestions
            else -> emptyList()
        }

        if (refreshSidePanel(geom, cands)) {
            bars.visibility = View.GONE
            return
        }

        // 大細／貼邊／候選字字體全部跟而家見緊嗰組（見 [padGroup]）。要喺
        // setCandidates 之前做 —— 條 bar 幾高、啲 chip 幾大都係跟呢個組行
        bars.padGroup = padGroup
        bars.refreshFontScale()
        bars.setMode(effective)
        bars.setCandidates(if (effective == BarMode.CANDIDATES) cands else emptyList())
        bars.setCloseVisible(specialPad)
        // 常駐 + 中文九宮格：切換掣已經搬咗去右上角嗰粒鍵，條 bar 唔使再擺多粒。
        // 英文／符號頁冇嗰粒鍵，所以一定要留返，唔係就入唔到工具列
        bars.setSwitchVisible(!(pinned && mode == PadMode.CHINESE))
        bars.setAiReady(aiUsable)
        bars.setAiVisible(aiKeySet)
        // 大細／貼邊分咗兩組存，粒「靠左／靠右」掣要拉、要著返邊個樣，
        // 都係跟而家見緊嗰組（見 [padGroup]）
        bars.refreshAlignLabel()
        // 條 bar 高度淨係跟候選字嘅字體行（見 [OptionBarsView.barHeightFor]），
        // 唔會因為有冇候選字、而家喺邊一段而跳高跳低
        bars.visibility = if (effective == BarMode.OFF) View.GONE else View.VISIBLE
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
     * 打咗 1~2 個碼（未夠碼出候選字）嗰陣，條 bar 出「以呢個碼開頭最常用嗰九隻字」，
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
     * 空出嚟嗰四成幾位夠曬擺功能掣同一大版候選字，冇理由再喺上面霸多條 bar。
     *
     * 回傳 true = 而家用緊側邊欄（call 嗰邊要自己收埋 [bars]）。
     * [geom] 由 [refreshBars] 計（null = 唔夠窄／條 bar 關咗／有 overlay 蓋住）——
     * 佢自己都要用「而家出唔出側邊欄」呢個答案去決定使唔使查候選字，所以計一次就夠。
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
        // AT_MOST(成個可用高度)，入面又有個食 weight 嘅候選字 ScrollView，
        // 結果候選字一多就撐大咗 padHolder，成個鍵盤跟住拉高（打橫尤其明顯）。
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
        panel.setCandidates(cands)
        panel.setAiReady(aiUsable)
        panel.setAiVisible(aiKeySet)
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
        if (align == PadAlign.STRETCH) return null
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
        if (keySet == aiKeySet && usable == aiUsable) return
        aiKeySet = keySet
        aiUsable = usable
        if (::bars.isInitialized) { bars.setAiVisible(keySet); bars.setAiReady(usable) }
        sidePanel?.let { it.setAiVisible(keySet); it.setAiReady(usable) }
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
                    latinSuggestions = NextWordModel.get()?.predictNext(w) ?: emptyList()
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
        val cur = Prefs.heightScale(this, g)
        val next = (cur + dyDp / 250f).coerceIn(Prefs.MIN_HEIGHT_SCALE, Prefs.MAX_HEIGHT_SCALE)
        if (next != cur) { Prefs.setHeightScale(this, next, g); relayoutPads(); refreshBars() }
    }

    /**
     * 候選字 bar 拉大／縮返：拉大嗰陣攞 `bars.expandedView` 蓋喺 padHolder 度（向下遮），
     * **唔會**加高成個 root，同 emoji／clipboard 個 overlay 一樣攞 `padHeightPx` 做高度。
     */
    override fun onExpandChanged(expanded: Boolean) {
        if (!::padHolder.isInitialized || candidatesExpanded == expanded) return
        candidatesExpanded = expanded
        val v = bars.expandedView
        if (expanded) {
            rememberPadHeight()
            (v.parent as? ViewGroup)?.removeView(v)
            val h = if (padHeightPx > 0) padHeightPx else PadMetrics.defaultPadHeightPx(this).roundToInt()
            padHolder.addView(v, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, h))
        } else {
            (v.parent as? ViewGroup)?.removeView(v)
        }
    }

    override fun onTool(action: KeyAction) = onKey(Key(action))

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
     * ✨：**冇揀住字都用得**。揀咗就淨係改揀咗嗰段，冇揀就當「改寫成個輸入框」——
     * 夾硬全選再交出去，返到嚟嗰段字直接取代成個欄嘅內容。
     *
     * 全選要喺出返嚟嗰陣**再做多次**：等緊 Gemini 嗰幾秒 user 隨時撳過個欄，
     * 一撳 caret 就散咗個 selection，`commitText` 就會變成插埋落去而唔係取代。
     */
    private fun runAi() {
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

        AiRewrite.rewrite(this, selected) { r ->
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
     */
    private fun playTone(tone: Int, ms: Int) {
        runCatching {
            val tg = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
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
    override fun onSwipePath(path: List<Float>, keyCenter: (Char) -> Pair<Float, Float>?, keyWidth: Float) {
        val decoder = gestureDecoder() ?: return
        if (emojiSearch) {
            val word = decoder.decode(path, keyCenter, keyWidth).firstOrNull() ?: return
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
        var words = decoder.decode(path, keyCenter, keyWidth, pre.lowercase(), suf.lowercase())
        if (words.isEmpty() && suf.isNotEmpty()) {
            suf = ""; words = decoder.decode(path, keyCenter, keyWidth, pre.lowercase(), "")
        }
        if (words.isEmpty() && pre.isNotEmpty()) {
            pre = ""; words = decoder.decode(path, keyCenter, keyWidth, "", "")
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
                val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = list?.firstOrNull()
                if (!text.isNullOrEmpty()) {
                    val outText = if (engine.scOutput) db?.tcsc(text) ?: text else text
                    currentInputConnection?.commitText(outText, 1)
                }
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
        showSttRecording(hold)
    }

    /**
     * 收工。[commit] = false 就淨係丟咗段錄音（收返啲資源，唔叫 API）。
     *
     * `VoiceRecorder.stop()` 自己會篩：太短（撳錯／彈手）同埋由頭到尾冇人講過嘢
     * （[VoiceClip.Silent]）兩種都**唔會**叫 API —— 一個 request 掟幾百 KB 上去
     * 等足幾秒，出返一句「（沒有聲音）」係好嘥。
     */
    private fun stopAiStt(commit: Boolean) {
        val rec = sttRecorder ?: return
        sttRecorder = null
        sttHold = false
        stopSttTimer()
        setSttLight(false)
        val clip = if (commit) rec.stop() else { rec.cancel(); null }
        if (clip !is VoiceClip.Ready) {
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
                currentInputConnection?.commitText(
                    if (engine.scOutput) db?.tcsc(text) ?: text else text, 1)
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
        sttBusy = false
        stopSttTimer()
        hideAiLoading()
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
        /** 開完鍵盤幾耐補度一次尺寸（見 [scheduleSizeRecheck]） */
        private const val SIZE_RECHECK_MS = 100L
        /** 補度幾多次 —— 有啲機要等埋 insets 落嚟先報得到啱嘅高度 */
        private const val SIZE_RECHECK_TRIES = 3
        /** 一輪入面最多重排幾多次（見 [recheckPadSize]），唔係就可能一路重排落去 */
        private const val SIZE_MAX_FIXES = 2
    }
}
