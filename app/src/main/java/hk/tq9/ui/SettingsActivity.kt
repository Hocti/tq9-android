package hk.tq9.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
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
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
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
 * 揀一個 sqlite 檔案就會直接覆蓋而家嘅字碼庫（舊嗰個唔會保留），
 * 同埋喺呢度校鍵盤大細，下面有實時預覽。
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var content: LinearLayout
    private var preview: ChinesePadView? = null
    private var previewHolder: FrameLayout? = null
    private var dbLabelView: TextView? = null
    private var alignBtn: Button? = null
    private var imeStatus: TextView? = null
    private var barModeBtn: Button? = null
    private var tlTapBtn: Button? = null
    private var tlLongBtn: Button? = null

    private val pickDb = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) replaceDb(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Q9Db.ensureInstalled(this)

        val scroll = ScrollView(this)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }
        scroll.addView(content)
        setContentView(scroll)
        title = "九万輸入法 TQ9"

        buildImeSection()
        buildDbSection()
        buildSizeSection()
        buildKeysSection()
        buildSwipeSection()
        buildAiSection()
        buildBehaviourSection()
        buildTryBox()
        buildPreview()
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
            button("開啟 / 停用輸入法") {
                startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            },
            button("轉用九万") {
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
            active -> "✅ 而家用緊九万"
            enabled -> "☑️ 已經開啟，但未揀做預設輸入法"
            else -> "⚠️ 未喺系統開啟九万"
        }
    }

    private fun buildDbSection() {
        header("字碼資料庫")
        dbLabelView = note("而家用緊：" + Prefs.dbLabel(this))
        note("揀一個 sqlite 檔案就會即刻覆蓋（舊版唔會保留）。" +
            "要有 mapped_table / related_candidates_table / ts_chinese_table / word_meta 四張表。")
        row(
            button("揀 sqlite 檔案…") {
                pickDb.launch(arrayOf("*/*"))
            },
            button("還原內置字碼表") {
                Q9Db.installFromAssets(this)
                dbLabelView?.text = "而家用緊：" + Prefs.dbLabel(this)
                toast("已經還原內置 dataset.db")
                rebuildPreview()
            }
        )
    }

    private fun replaceDb(uri: Uri) {
        val name = displayName(uri)
        Q9Db.replaceFrom(this, uri)
            .onSuccess {
                Prefs.setDbLabel(this, name)
                dbLabelView?.text = "而家用緊：$name"
                toast("字碼庫換咗做 $name")
                rebuildPreview()
            }
            .onFailure { toast("換唔到：" + (it.message ?: "未知錯誤")) }
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

    private fun buildSizeSection() {
        header("鍵盤大細")
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
        slider("按鍵之間空隙", 0, 8, Prefs.gapDp(this), "dp") { v ->
            Prefs.sp(this).edit().putInt(Prefs.KEY_GAP_DP, v).apply(); rebuildPreview()
        }
        slider("字體大細", 70, 140, (Prefs.fontScale(this) * 100).toInt(), "%") { v ->
            Prefs.sp(this).edit().putFloat(Prefs.KEY_FONT_SCALE, v / 100f).apply(); rebuildPreview()
        }
        note("闊 screen（摺機、平板、打橫）超過最大闊度之後，九宮格唔會再拉長，" +
            "剩低嘅位由下面呢個掣決定點擺。喺鍵盤入面 option bar 最左嗰粒都可以即時轉。")
        alignBtn = button(alignLabel()) {
            Prefs.setAlign(this, Prefs.align(this).next())
            alignBtn?.text = alignLabel()
            rebuildPreview()
        }
        content.addView(alignBtn)
        note("鍵盤永遠貼實螢幕底，唔會整個提高留個窿。想高啲矮啲就拉下面條，" +
            "或者喺工具 bar 最左嗰粒掣度直接上下拖。")
        slider("鍵盤高度", (Prefs.MIN_HEIGHT_SCALE * 100).toInt(), (Prefs.MAX_HEIGHT_SCALE * 100).toInt(),
            (Prefs.heightScale(this) * 100).toInt(), "%") { v ->
            Prefs.setHeightScale(this, v / 100f); rebuildPreview()
        }
    }

    private fun alignLabel() = "顯示方式：" + Prefs.align(this).label

    private fun barModeLabel() = "上面條 bar 預設：" + Prefs.barMode(this).label

    private fun buildKeysSection() {
        header("按鍵功能")
        note("左上角嗰粒鍵，短撳同長撳分別做乜都揀得。預設係短撳速選字、長撳簡體開關。" +
            "短撳唔可以揀「無效」，短撳同長撳亦都唔可以做同一件事（咁樣就嘥咗一格）。")
        tlTapBtn = button(tlLabel("短撳", Prefs.topLeftTap(this))) {
            val next = PadFunc.nextFor(Prefs.topLeftTap(this), Prefs.topLeftLong(this), allowNone = false)
            Prefs.setFunc(this, Prefs.KEY_TL_TAP, next)
            tlTapBtn?.text = tlLabel("短撳", next)
            tlLongBtn?.text = tlLabel("長撳", Prefs.topLeftLong(this))
            rebuildPreview()
        }
        content.addView(tlTapBtn)
        tlLongBtn = button(tlLabel("長撳", Prefs.topLeftLong(this))) {
            val next = PadFunc.nextFor(Prefs.topLeftLong(this), Prefs.topLeftTap(this), allowNone = true)
            Prefs.setFunc(this, Prefs.KEY_TL_LONG, next)
            tlLongBtn?.text = tlLabel("長撳", next)
            rebuildPreview()
        }
        content.addView(tlLongBtn)

        switch("英文鍵盤上面加一行數字", Prefs.KEY_LATIN_NUM_ROW, false)
        note("冇呢行數字嗰陣，qwertyuiop 左上角會寫細細個數字、右上角寫符號，長撳兩樣都揀得；" +
            "開咗之後就淨係喺數字鍵右上角寫符號，長撳字母唔會再出數字。" +
            "數字鍵長撳出嘅符號同實體鍵盤撳 shift 一樣（1 → !），4 仲有各國銀紙揀。")
        note("長撳 ␣ 之後唔好放手，上下左右拖就可以郁 caret（本來嗰個輸入法揀選視窗，" +
            "改咗去長撳 🌐）。")
    }

    private fun tlLabel(prefix: String, f: PadFunc) =
        "左上角鍵 $prefix：" + (if (f.icon.isEmpty()) "" else f.icon + " ") + f.label

    private fun buildAiSection() {
        header("AI 改寫")
        note("喺任何 app 揀住一段字，撳工具 bar 嘅 ✨ 就會用 Gemini 改寫，" +
            "改完直接取代揀咗嗰段。未揀字嗰陣粒掣係灰色。")
        textField("Gemini API key", Prefs.KEY_AI_KEY, "", password = true)
        textField("Model", Prefs.KEY_AI_MODEL, Prefs.DEFAULT_AI_MODEL)
        textField("Prompt（%text% = 揀咗嗰段字）", Prefs.KEY_AI_PROMPT, Prefs.DEFAULT_AI_PROMPT,
            multiline = true)
    }

    private fun buildSwipeSection() {
        header("滑動輸入 (Swype)")
        switch("開啟滑動輸入", Prefs.KEY_SWIPE, true)
        note("中文滑動係即時出碼：滑過 7→9→3 就等於順序撳咗三下，" +
            "每出一碼九宮格會即刻變。中間格靠停留時間、轉角度，" +
            "再加字碼表 weight（常唔常用）一齊判斷。")
        slider("停留幾耐先當撳咗", 60, 400, Prefs.swipeDwellMs(this).toInt(), "ms") { v ->
            Prefs.sp(this).edit().putInt(Prefs.KEY_SWIPE_DWELL, v).apply()
        }
        slider("轉幾多度先當轉角", 25, 110, Prefs.swipeAngleDeg(this).toInt(), "°") { v ->
            Prefs.sp(this).edit().putInt(Prefs.KEY_SWIPE_ANGLE, v).apply()
        }
    }

    private fun buildBehaviourSection() {
        header("其他")
        switch("輸出簡體字", Prefs.KEY_SC_OUTPUT, false)
        note("九宮格右上角 ☰ 淨係開／關成條 bar，一開返先入候選字。" +
            "開住之後靠條 bar 最左嗰粒（⇄）喺候選字／工具（大細位置、貼上、emoji、AI）之間切。" +
            "下面揀嘅係開機預設嗰段。")
        barModeBtn = button(barModeLabel()) {
            val next = Prefs.barMode(this).next()
            Prefs.setBarMode(this, next)
            barModeBtn?.text = barModeLabel()
        }
        content.addView(barModeBtn)
        switch("按鍵震動", Prefs.KEY_VIBRATE, true)
        switch("按鍵聲", Prefs.KEY_SOUND, false)
        slider("長撳時間", 200, 700, Prefs.longPressMs(this).toInt(), "ms") { v ->
            Prefs.sp(this).edit().putInt(Prefs.KEY_LONG_PRESS_MS, v).apply()
        }
        note("長撳 0 = 開關標點（「」之類）；長撳 🔈 = 關聯字；長撳 📋 = 剪貼簿歷史。" +
            "九宮格 1~9 長撳 = 連撳兩下（長撳 7 再拉去 0 = 770），" +
            "滑到最後一格停夠呢個時間先放手都一樣算兩下。")
    }

    private fun buildTryBox() {
        header("試打")
        note("email 欄會自動出 @ 同 .com；密碼數字欄會自動變純數字鍵盤；搜尋欄嘅 ⏎ 會變放大鏡。")
        tryField("喺呢度試下打字", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE)
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
    private fun switch(label: String, key: String, def: Boolean) {
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
            }
        }
        content.addView(s, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    /** 一格文字設定，打完自動存（唔使再撳「儲存」） */
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
            if (multiline) { minLines = 3; gravity = Gravity.TOP or Gravity.START }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    Prefs.sp(this@SettingsActivity).edit()
                        .putString(key, s?.toString().orEmpty()).apply()
                }
            })
        }
        content.addView(edit, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
