package hk.tq9.ime

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import hk.tq9.core.AiRewrite
import hk.tq9.core.BarMode
import hk.tq9.core.ClipHistory
import hk.tq9.core.EmojiDict
import hk.tq9.core.EnDict
import hk.tq9.core.NextWordModel
import hk.tq9.core.Prefs
import hk.tq9.core.Q9Db
import hk.tq9.core.Q9Cmd
import hk.tq9.core.Q9Engine
import hk.tq9.core.UsageStats
import hk.tq9.swipe.GestureDecoder
import hk.tq9.ui.MicPermissionActivity
import kotlin.math.abs
import kotlin.math.roundToInt

enum class PadMode { CHINESE, LATIN, SYMBOL, NUMBER, EMOJI }

/** 九万輸入法 (TQ9) */
class TQ9InputMethodService : android.inputmethodservice.InputMethodService(),
    Q9Engine.Host, KeyboardBaseView.Host, ChinesePadView.ChineseHost,
    LatinPadView.LatinHost, EmojiPadView.EmojiHost, OptionBarsView.Listener {

    private var db: Q9Db? = null
    private lateinit var engine: Q9Engine

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
    /** 啱啱完成嘅上一個英文字，畀 [hk.tq9.core.NextWordModel] 估下一個字用 */
    private var lastCommittedWord = ""
    private var lastShiftTapAt = 0L

    // 搵 emoji：打嘅字唔會入去個欄，淨係用嚟篩
    private var emojiSearch = false
    private val emojiQuery = StringBuilder()
    private var emojiResults: List<String> = emptyList()
    private var emojiReturnMode = PadMode.CHINESE

    // 候選欄冇嘢出嗰陣頂上嘅速選字（mapped_table id 1000）
    private var quickPicks: List<String> = emptyList()
    private var showingQuickPicks = false

    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private val ui = Handler(Looper.getMainLooper())

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        runCatching { ClipHistory.current(this) }
    }

    override val optionOn: Boolean get() = barMode != BarMode.OFF
    override val aiReady: Boolean get() = hasSelection

    // ---- lifecycle --------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        Q9Db.ensureInstalled(this)
        db = runCatching { Q9Db.open(this) }.onFailure { Log.e(TAG, "開唔到資料庫", it) }.getOrNull()
        engine = Q9Engine(db ?: run {
            // 資料庫壞咗就用返內置嗰個
            Q9Db.installFromAssets(this)
            Q9Db.open(this).also { db = it }
        })
        engine.host = this
        engine.scOutput = Prefs.scOutput(this)
        barMode = Prefs.barMode(this)
        quickPicks = runCatching { db?.keyInput(1000).orEmpty() }.getOrDefault(emptyList())
            .filter { it.isNotEmpty() && it != "*" }
        clipboard()?.addPrimaryClipChangedListener(clipListener)
        UsageStats.get(this) // 背景 thread 偷偷載返之前記低嘅 bigram / 每字次數
    }

    override fun onDestroy() {
        stopStt()
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
            listener = this@TQ9InputMethodService
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

        val want = when {
            cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE ||
                cls == InputType.TYPE_CLASS_DATETIME -> PadMode.NUMBER
            isEmail || isUri || isPassword -> PadMode.LATIN
            else -> PadMode.CHINESE
        }
        switchMode(want, force = true)
        refreshBars()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        stopStt()
        finishLatinComposing()
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd,
            candidatesStart, candidatesEnd)
        val sel = newSelStart != newSelEnd
        if (sel == hasSelection) return
        hasSelection = sel
        if (::bars.isInitialized) bars.setAiReady(sel)
        chinesePad?.invalidate()
    }

    /** 搜尋欄要出放大鏡，唔係就照出 ⏎ */
    private fun enterLabelFor(ei: EditorInfo?): String {
        val action = ei?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE
        val noEnter = (ei?.imeOptions?.and(EditorInfo.IME_FLAG_NO_ENTER_ACTION) ?: 0) != 0
        return if (!noEnter && action == EditorInfo.IME_ACTION_SEARCH) "🔍" else "⏎"
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

    // ---- Q9Engine.Host ----------------------------------------------------

    override fun commitText(text: String) {
        // 搵 emoji 嗰陣，九宮格打出嚟嘅中文都係入條 query，唔會入落個欄
        if (emojiSearch) { emojiQuery.append(text); syncEmojiComposing(); refreshEmojiResults(); return }
        currentInputConnection?.commitText(text, 1)
    }

    override fun bumpChar(ch: String) = UsageStats.get(this).bumpChar(ch)
    override fun bumpBigram(pair: String) = UsageStats.get(this).bumpBigram(pair)
    override fun charFreq(ch: String): Int = UsageStats.get(this).charFreq(ch)
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
        val parts = Q9Db.splitGraphemes(pair)
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
        chinesePad?.invalidate()
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
            KeyAction.CANCEL -> engine.cmd(Q9Cmd.CANCEL)
            KeyAction.SHORTCUT -> engine.cmd(Q9Cmd.SHORTCUT)
            KeyAction.SC_TOGGLE -> toggleSc()
            KeyAction.HOMO -> engine.cmd(Q9Cmd.HOMO)
            KeyAction.TO_CHINESE -> switchMode(PadMode.CHINESE)
            KeyAction.TO_LATIN -> switchMode(PadMode.LATIN)
            KeyAction.TO_SYMBOL -> { switchMode(PadMode.SYMBOL); symbolPad?.page = 0 }
            KeyAction.TO_NUMBER -> switchMode(PadMode.NUMBER)
            KeyAction.TO_EMOJI -> openEmoji()
            KeyAction.PASTE -> paste()
            KeyAction.AI -> runAi()
            KeyAction.SYM_PAGE -> symbolPad?.let { it.page = 1 - it.page }
            KeyAction.IME_SWITCH -> switchIme()
            KeyAction.STT -> toggleStt()
            KeyAction.OPTION -> toggleBar()
            KeyAction.BACKSPACE -> backspace()
            KeyAction.SPACE -> space()
            KeyAction.ENTER -> enter()
            KeyAction.SHIFT -> tapShift()
            KeyAction.CHAR -> typeChar(key.text)
            KeyAction.NOOP -> {}
        }
    }

    override fun onLongPress(key: Key): Boolean {
        // 設定頁換得嘅鍵（左上角嗰粒）自己帶住長撳做乜
        if (key.longAction != KeyAction.NOOP) {
            onKey(key.copy(action = key.longAction, longAction = KeyAction.NOOP))
            return true
        }
        when (key.action) {
            // 本身 "/" 鍵嘅開關標點，改成長撳 0
            KeyAction.DIGIT -> if (key.digit == 0) { engine.cmd(Q9Cmd.OPENCLOSE); return true }
            KeyAction.HOMO -> { engine.cmd(Q9Cmd.RELATE); return true }
            KeyAction.PASTE -> { onPasteHistory(); return true }
            KeyAction.IME_SWITCH -> {
                (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showInputMethodPicker()
                return true
            }
            KeyAction.SHIFT -> {
                latinPad?.let { it.shift = ShiftState.LOCK; it.rebuild() }
                return true
            }
            KeyAction.CHAR -> if (key.hint.isNotEmpty()) { typeChar(key.hint); return true }
            else -> {}
        }
        return false
    }

    override fun feedback(key: Key) {
        if (Prefs.vibrate(this)) {
            vibrator()?.vibrate(VibrationEffect.createOneShot(12, 40))
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

    private fun typeChar(raw: String) {
        var s = raw
        val pad = latinPad
        if (emojiSearch) {
            emojiQuery.append(s)
            syncEmojiComposing()
            refreshEmojiResults()
            return
        }
        if (mode == PadMode.LATIN && pad != null && s.length == 1 && s[0] in 'a'..'z') {
            if (pad.shift != ShiftState.OFF) s = s.uppercase()
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
        if (mode == PadMode.CHINESE && engine.busy) { engine.cmd(Q9Cmd.CANCEL); return }
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
        if (!ok) {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .showInputMethodPicker()
        }
    }

    // ---- 貼上 / clipboard 歷史 --------------------------------------------

    private fun paste() {
        val text = runCatching { ClipHistory.current(this) }.getOrDefault("")
        if (text.isEmpty()) { toast("剪貼簿係空嘅"); return }
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
     * 撳 🔍：轉去英文鍵盤打字，但啲字唔會入落個欄，
     * 淨係即時篩 emoji，夾到嗰啲出喺上面條 bar 度撳。
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

    /** 九宮格右上角 ☰：淨係開／關成條 bar，一開返永遠先入候選字 view */
    private fun toggleBar() {
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
    }

    /** emoji 表／剪貼簿開住：一定要有條 bar 出返粒 ✖，唔係就返唔到去普通鍵盤 */
    private val specialPad: Boolean get() = mode == PadMode.EMOJI || overlay != null

    private fun refreshBars() {
        if (!::bars.isInitialized) return
        // 搵 emoji 嗰陣一定要見到啲結果，就算條 bar 本身係關住
        var effective = if (emojiSearch || (forceCandidates && latinSuggestions.isNotEmpty()))
            BarMode.CANDIDATES else barMode
        // emoji 表／剪貼簿嗰陣冇候選字可以出，索性成行出工具，唔好淨係得粒 ✖ 吉住
        if (specialPad) effective = BarMode.TOOLS

        showingQuickPicks = false
        val cands = when {
            emojiSearch -> emojiResults
            mode == PadMode.CHINESE -> {
                val c = if (engine.selectMode) engine.selectWords else engine.relateHints
                // 冇字碼又冇關聯字嗰陣，條欄唔好吉住 —— 出速選字頂住
                if (c.isEmpty()) { showingQuickPicks = true; quickPicks } else c
            }
            mode == PadMode.LATIN || mode == PadMode.SYMBOL -> latinSuggestions
            else -> emptyList()
        }
        bars.setMode(effective)
        bars.setCandidates(if (effective == BarMode.CANDIDATES) cands else emptyList())
        bars.setCloseVisible(specialPad)
        bars.setAiReady(hasSelection)
        bars.refreshAlignLabel()
        // 條 bar 高度定死，唔會因為有冇候選字而跳高跳低
        bars.visibility = if (effective == BarMode.OFF) View.GONE else View.VISIBLE
    }

    override fun onCloseSpecialPad() {
        if (overlay != null) { hideOverlay(); return }
        if (mode == PadMode.EMOJI) closeEmoji()
    }

    override fun onPickCandidate(index: Int) {
        if (showingQuickPicks && !emojiSearch) {
            engine.pickQuick(quickPicks.getOrNull(index) ?: return)
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
            PadMode.CHINESE -> {
                if (engine.selectMode) engine.pickCandidateAt(index) else engine.pickRelateAt(index)
            }
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
        val next = Prefs.align(this).next()
        Prefs.setAlign(this, next)
        chinesePad?.onSettingsChanged()
        bars.refreshAlignLabel()
    }

    /** 上下拖 = 拉高／拉低成個鍵盤（鍵盤永遠貼實底，唔會提起留個窿）。自由移動已經冇咗。 */
    override fun onSizeDrag(dyDp: Int) {
        if (dyDp == 0) return
        val cur = Prefs.heightScale(this)
        val next = (cur + dyDp / 250f).coerceIn(Prefs.MIN_HEIGHT_SCALE, Prefs.MAX_HEIGHT_SCALE)
        if (next != cur) { Prefs.setHeightScale(this, next); relayoutPads() }
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

    // ---- AI 改寫 -----------------------------------------------------------

    private fun runAi() {
        val ic = currentInputConnection
        val selected = ic?.getSelectedText(0)?.toString().orEmpty()
        if (selected.isBlank()) { toast("要先揀住一段字先用得 AI"); return }
        if (Prefs.aiApiKey(this).isBlank()) { toast("請喺設定頁入 Gemini API key"); return }

        val myGen = ++aiGeneration
        showAiLoading()

        val timeout = Runnable {
            if (myGen != aiGeneration) return@Runnable
            aiGeneration++ // 令跟住嚟遲到嘅 callback 當第晒
            hideAiLoading()
            playErrorTone()
            toast("AI 逾時（10 秒冇回應）")
        }
        ui.postDelayed(timeout, AI_TIMEOUT_MS)

        AiRewrite.rewrite(this, selected) { r ->
            if (myGen != aiGeneration) return@rewrite // 已經逾時處理咗
            ui.removeCallbacks(timeout)
            aiGeneration++
            hideAiLoading()
            r.onSuccess { out ->
                // commitText 會取代咗揀住嗰段
                currentInputConnection?.commitText(out, 1)
            }.onFailure {
                playErrorTone()
                toast("AI 失敗：" + (it.message ?: "未知錯誤"))
            }
        }
    }

    /** AI 處理緊嗰陣：成個 UI disable，中間出個轉緊嘅圈 */
    private fun showAiLoading() {
        if (!::outer.isInitialized) return
        hideAiLoading()
        val h = root.height.takeIf { it > 0 } ?: PadMetrics.defaultPadHeightPx(this).roundToInt()
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(170, 0, 0, 0))
            isClickable = true
            isFocusable = true
            addView(ProgressBar(this@TQ9InputMethodService), FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER })
        }
        aiOverlay = overlay
        outer.addView(overlay, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, h))
    }

    private fun hideAiLoading() {
        val v = aiOverlay ?: return
        aiOverlay = null
        (v.parent as? ViewGroup)?.removeView(v)
    }

    /** load fail 嗰下嘟一聲，唔靠 [Prefs.sound]（嗰個係按鍵聲，呢個係錯誤提示） */
    private fun playErrorTone() {
        runCatching {
            val tg = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
            tg.startTone(ToneGenerator.TONE_PROP_NACK, 300)
            ui.postDelayed({ runCatching { tg.release() } }, 400)
        }
    }

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
        }, "tq9-gesture-decoder").apply { priority = Thread.MIN_PRIORITY }.start()
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
        if (listening) { stopStt(); return }
        // 粒 🎤 而家喺工具 bar 度（貼上隔籬），聽緊嘢就著燈
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            startActivity(Intent(this, MicPermissionActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            toast("部機冇語音輸入服務")
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
        toast("🎤 聽緊…")
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
        if (::bars.isInitialized) ui.post { bars.setSttActive(on) }
    }

    private fun releaseRecognizer() {
        ui.post {
            runCatching { recognizer?.destroy() }
            recognizer = null
        }
    }

    companion object {
        private const val TAG = "TQ9"
        /** 連撳兩下 shift 幾快先當 capslock */
        private const val DOUBLE_TAP_MS = 400L
        /** AI 攞 10 秒都未有回應就當 error */
        private const val AI_TIMEOUT_MS = 10_000L
    }
}
