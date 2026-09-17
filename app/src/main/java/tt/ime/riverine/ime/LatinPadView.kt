package tt.ime.riverine.ime

import android.content.Context
import tt.ime.riverine.core.Prefs
import tt.ime.riverine.swipe.GestureKeyTracker
import kotlin.math.max

enum class ShiftState { OFF, ON, LOCK }

/**
 * 輸入欄的性質，決定底行怎樣排（見 [LatinPadView.rows]）。
 * 由 `TTInputMethodService.onStartInputView` 看 `inputType` 決定。
 *
 * 四款都保留 `中`：URL 欄／密碼欄一樣可能要打中文（Chrome 的網址欄就是
 * `textUri`，在那裏搜尋中文非常普遍），收起了就整個欄位都打不到中文。
 */
enum class LatinField { NORMAL, EMAIL, URI, PASSWORD }

/**
 * 英文底行嗰粒 [KeyAction.BAR_HIDE] 而家寫乜。只負責 show／hide 上面條 bar。
 */
private fun LatinPadView.barToggleGlyph(): String =
    if (Prefs.barHidden(context)) BAR_SHOW_GLYPH else BAR_HIDE_GLYPH

/**
 * 長撳字母彈出嘅變體：淨係各國重音寫法。
 *
 * 數字同符號**唔會**擺喺呢度 —— 開咗數字行嗰陣長撳字母唔應該再出數字（會撞），
 * 冇數字行嗰陣就由 [LatinPadView.rows] 自己喺 `qwertyuiop` 前面插返數字同符號。
 */
private val ACCENTS: Map<String, List<String>> = mapOf(
    "w" to listOf("ŵ"),
    "e" to listOf("é", "è", "ê", "ë", "ē", "ę"),
    "r" to listOf("ř"),
    "t" to listOf("þ", "ť"),
    "y" to listOf("ý", "ÿ"),
    "u" to listOf("ú", "ù", "û", "ü", "ū"),
    "i" to listOf("í", "ì", "î", "ï", "ī"),
    "o" to listOf("ó", "ò", "ô", "ö", "õ", "ø", "œ"),
    "a" to listOf("á", "à", "â", "ä", "ã", "å", "ā", "æ"),
    "s" to listOf("ß", "ś", "š"),
    "d" to listOf("ð", "ď"),
    "f" to listOf("ƒ"),
    "g" to listOf("ğ", "ģ"),
    "h" to listOf("ĥ"),
    "j" to listOf("ĵ"),
    "k" to listOf("ķ"),
    "l" to listOf("ł", "ĺ", "ľ"),
    "z" to listOf("ž", "ź", "ż"),
    "x" to listOf("×"),
    "c" to listOf("ç", "ć", "č", "©"),
    "v" to listOf("ν"),
    "b" to listOf("ḃ"),
    "n" to listOf("ñ", "ń", "ň"),
    "m" to listOf("µ")
)

/**
 * 標點鍵長撳彈出嘅嘢。粒粒都會喺左上角寫返個細字提示（見 [LatinPadView.punct]），
 * 唔係冇人知撳實佢仲有嘢揀。
 *
 * 全部都**唔跟**「第一個 = 自己本身」嗰個規矩 —— 每粒排頭嗰個係長撳一彈出嚟
 * 就已經停咗喺度嗰個（唔郁手指放開就出佢），粒鍵自己短撳就攞得返：
 *
 *  - `,` → **Tab**（`\t`，畫成 `⇥`）
 *  - `.` → `/`（粒 `/` 冇咗，見下面）
 *  - `/` → `?`（`?` 打得多過 `/` 好多）
 *
 * 一般英文鍵盤（[LatinField.NORMAL]）已經冇咗粒 `/` —— 佢成條 list 併咗入
 * `.` 度（見 [SLASH_VARIANTS] 同 [PUNCT_VARIANTS]），讓返個位出嚟俾 space bar
 * 拉長。粒 `/` 淨係喺網址／電郵欄先仲喺度（嗰兩度真係逐個字都要撳到）。
 */
private val SLASH_VARIANTS = listOf("?", "/", "\\", "|", "=", "_", "+", "-")

private val PUNCT_VARIANTS: Map<String, List<String>> = mapOf(
    "," to listOf(",","'","\"","`", "<", ">", "[", "]", "{", "}"),
    // 排頭係 `/` 唔係 `;`：粒 `/` 掣冇咗，長撳 `.` 一彈出就停喺佢度（放手即出），
    // 左上角個提示亦都寫住 `/`，睇一眼就知粒鍵搬咗去邊。
    // `.drop(2)` = 剷走 `?` `/`，佢哋已經喺前面
    "." to listOf("?","!", ";", ":", ",",".","/", "\\", "|", "=", "+", "-", "_",   "~","\t" ),
    "/" to SLASH_VARIANTS
)

/**
 * 長撳 `,` 最頂再加嘅一行：**開同收一次過打埋**，caret 停返兩者中間
 * （`''` 打完個游標就喺兩粒引號之間，直接打得落內容）。
 *
 * 逐個都係下面 `,` 嗰行本身有嘅符號 —— 下面一行係「淨係打一個」，
 * 上面一行就係「成對」，同一粒符號兩種打法排上下兩行對得返。
 * 中間嗰個 [CARET] 唔會出街（見 [variantDisplay] 同 `TTInputMethodService.typeChar`）。
 */
private val PUNCT_PAIRS: Map<String, List<String>> = mapOf(
    "," to listOf("'$CARET'", "\"$CARET\"", "`$CARET`", "<$CARET>", "[$CARET]", "{$CARET}")
)

/**
 * 數字鍵長撳彈出嘅符號，好似實體鍵盤撳住 shift 咁（`1` → `!`）。
 * `4` 唔止有 `$`，各國銀紙都揀得（英磅、歐羅、日圓…）。
 * 每個 list 排頭嗰個就係畫喺右上角嘅提示。
 */
val DIGIT_SYMBOLS: Map<String, List<String>> = mapOf(
    "1" to listOf("!", "¡", "¹", "½"),
    "2" to listOf("@", "²"),
    "3" to listOf("#", "³"),
    "4" to listOf("$", "£", "€", "¥", "¢", "₩", "₹", "₱"),
    "5" to listOf("%", "‰"),
    "6" to listOf("^", "°"),
    "7" to listOf("&", "§"),
    "8" to listOf("*", "•", "×"),
    "9" to listOf("(", "[", "{", "<"),
    "0" to listOf(")", "]", "}", ">")
)

/**
 * 一粒數字鍵：中間大字、右上角細字寫住長撳會出咩符號。
 *
 * 長撳彈出嘅 list **排頭係符號**（`1` → `!`），數字自己排第二（2026-09-11 user 要求）
 * —— 短撳已經打得到個數字，長撳嗰下十之八九係想要粒符號，所以長撳一彈出就
 * 停咗喺符號度（唔郁手指放開即出 `!`），要個數字就向右拉一格。
 */
fun digitKey(d: String, weight: Float = 1f, bigLabel: Boolean = false): Key {
    val syms = DIGIT_SYMBOLS[d].orEmpty()
    return Key(
        KeyAction.CHAR, label = d, text = d, weight = weight, bigLabel = bigLabel,
        hintRight = syms.firstOrNull().orEmpty(),
        variants = syms.take(1) + listOf(d) + syms.drop(1)
    )
}

/** 英文 QWERTY，支援 Swipe 畫線 */
class LatinPadView(context: Context) : RowsPadView(context) {

    interface LatinHost {
        /**
         * 滑完一次嘅原始軌跡（x,y 交替）連埋每點嘅時間、同埋「邊個字母個鍵中心喺邊」
         * 一齊拋畀 host。
         * **唔係**喺呢度查詞庫 —— host 要用 [tt.ime.riverine.swipe.GestureDecoder] 做形狀比對，
         * 仲要連 caret 前後已經打咗嘅字母一齊計（`dis|y` 滑 `pla` = `display`）。
         *
         * [times] 唔可以慳 —— 詞庫夾唔到嗰陣要靠佢搵返「特登停低／特登拗彎」嗰幾點，
         * 砌返個唔喺詞庫嘅字出嚟（見 [tt.ime.riverine.swipe.GesturePivots]）。
         */
        fun onSwipePath(
            path: List<Float>,
            times: List<Long>,
            keyCenter: (Char) -> Pair<Float, Float>?,
            keyWidth: Float
        )
    }

    var latinHost: LatinHost? = null

    var shift: ShiftState = ShiftState.OFF
        set(v) { field = v; invalidate() }

    /** 輸入欄的性質（email／URL／密碼），底行跟着換（見 [LatinField]） */
    var fieldKind: LatinField = LatinField.NORMAL
        set(v) { if (field != v) { field = v; rebuild() } }

    /** 搵 emoji 嗰陣：打嘅字唔入去，而係篩上面條 bar 嘅 emoji */
    var emojiSearchMode: Boolean = false
        set(v) { if (field != v) { field = v; rebuild() } }

    /**
     * 長撳彈出嘅變體 list，**排頭係另一個大細階**（2026-09-11 user 要求）——
     * 而家寫住細階就排頭出大階、寫住大階就排頭出細階，撳實唔郁放手即刻攞到
     * （打一個大階字母唔使再撳 ⇧）。粒鍵自己嗰個排第二，跟住先至係口音字。
     */
    private fun ch(
        c: String, hint: String = "", hintRight: String = "", extra: List<String> = emptyList(),
        weight: Float = 1f
    ): Key {
        val letter = c.length == 1 && c[0] in 'a'..'z'
        val upper = letter && shift != ShiftState.OFF
        // 大細階兩樣都要揀得到：排頭嗰個係**另一個**大細階（撳實唔郁放手 = 打佢），
        // 第二個先至係粒鍵而家寫住嗰個，跟住係口音字（一樣跟返而家嘅大細階）
        val base = if (upper) c.uppercase() else c
        val other = if (upper) c.lowercase() else c.uppercase()
        val head = if (!letter) listOf(c) else listOf(other, base)
        // "ß".uppercase() 會變兩個字母 "SS" —— 變咗長度就唔換，照出返細階嗰個
        val accents = ACCENTS[c].orEmpty().let { list ->
            if (!upper) list
            else list.map { a -> a.uppercase().takeIf { it.length == a.length } ?: a }
        }
        return Key(
            KeyAction.CHAR, label = c, text = c, hint = hint, hintRight = hintRight, weight = weight,
            variants = head + extra + accents,
            swipeable = letter
        )
    }

    /** 標點鍵（`,` `.` `/`）：長撳有嘢揀，所以左上角要寫返個細字提示 */
    private fun punct(c: String, weight: Float = 1f): Key {
        val v = PUNCT_VARIANTS[c].orEmpty()
        // 提示寫「長撳會停喺邊個」嗰個 —— `/` 就係 `?`，其餘就係第二個（第一個係自己）
        val tip = if (v.firstOrNull() != c) v.firstOrNull() else v.getOrNull(1)
        return Key(
            KeyAction.CHAR, label = c, text = c, weight = weight,
            hint = tip?.let(::variantDisplay).orEmpty(), variants = v,
            variantsTop = PUNCT_PAIRS[c].orEmpty()
        )
    }

    override fun rows(): List<List<Key>> {
        // 開咗數字行就真係多一行數字喺上面，字母角落亦都唔會再寫細字
        val numRow = Prefs.latinNumberRow(context)
        val digits = "1234567890".map { digitKey(it.toString()) }
        val r0 = "qwertyuiop".mapIndexed { i, c ->
            val d = "1234567890"[i].toString()
            val sym = DIGIT_SYMBOLS[d]?.firstOrNull().orEmpty()
            // 冇數字行：左上角寫細細個數字、右上角寫符號，長撳兩樣都揀得
            if (numRow) ch(c.toString())
            else ch(c.toString(), hint = d, hintRight = sym, extra = listOf(d, sym))
        }
        // a、l 同其他字母一樣闊，兩頭讓返半格出嚟（跟返一般 qwerty 個樣，
        // 唔再將收邊嗰兩粒拉長）。空格唔會食掉掂觸 —— 撳落去會 snap 去隔籬粒鍵。
        val r1 = listOf(spacerKey(0.5f)) + "asdfghjkl".map { ch(it.toString()) } +
            listOf(spacerKey(0.5f))
        // 冇咗粒 `/`（併咗入 `.`，見 [PUNCT_VARIANTS]），讓返嗰格出嚟俾 ⇧ 同 ⌫ 分。
        // 1.5 + 7 + 1.5 = 10，啱啱同上面兩行嘅十個字母對得齊。
        val r2 = listOf(
            Key(KeyAction.SHIFT, label = shiftLabel(), weight = 1.5f,
                accent = shift == ShiftState.LOCK)
        ) + "zxcvbnm".map { ch(it.toString()) } +
            listOf(Key(KeyAction.BACKSPACE, label = "⌫", weight = 1.5f, repeatable = true))
        val r3 = ArrayList<Key>()
        // 搵 emoji 嗰陣底行淨係要「退出」同 ␣ 兩粒：`?123`、`中`、`⏎`、標點喺呢頁
        // 一粒都用唔著（打嘅字淨係用嚟篩 emoji，唔會入落個欄）。粒退出掣**寫明幾隻字**
        // —— 以前淨係得個 😀，冇人知撳落去係唔搵住定係入咗個 emoji
        if (emojiSearchMode) {
            r3.add(Key(KeyAction.TO_EMOJI, label = "退出表情搜尋", weight = 3f))
            r3.add(Key(KeyAction.SPACE, label = "␣", weight = 4f))
            return if (numRow) listOf(digits, r0, r1, r2, r3) else listOf(r0, r1, r2, r3)
        }
        // `中` 同 `?123` 呢兩粒字面本身短，唔使 bigLabel 都夠睇清楚 ——
        // 用返正常字size，讓返嗰啖位出嚟俾 space bar 擺得更中、闊少少。
        // `中` 淨係一隻字，仲窄得過 `?123`（2026-09-11 user 要求：再縮，益返 space）
        r3.add(Key(KeyAction.TO_CHINESE, label = "中", weight = 1f))
        // 長撳 ?123 唔使經符號頁，直接跳去純數字 keypad。
        // **冇左上角提示字**（2026-08-29 user 要求）—— 呢粒鍵面本身已經四個字符，
        // 英文底行粒粒都窄，再喺左上角迫多個「123」就撞埋一舊。
        // 中文九宮格嗰粒地方鬆啲，個 hint 照留。
        r3.add(Key(KeyAction.TO_SYMBOL, label = "?123", weight = 1.3f,
            longAction = KeyAction.TO_NUMBER))
        // 闊 keyboard（打橫／摺機內屏／平板）先有：上面條 bar 嘅收起／打開
        // （見 [barToggleGlyph] 同 `TTInputMethodService.toggleBarHidden`）。
        // 打橫本來就矮，條 bar 佔嗰橛位好肉赤，但係窄機收起咗就等於打盲舖
        // （打字提示同滑出嚟嗰個字全部喺條 bar 度），所以窄嗰陣粒掣唔會出現
        // ——窄機得返條 bar 自己嗰粒 `⇄`，三段循環，收唔起。
        if (Prefs.barToggleAllowed(context)) {
            r3.add(Key(KeyAction.BAR_HIDE, label = barToggleGlyph(), weight = 1f))
        }
        when (fieldKind) {
            LatinField.EMAIL -> {
                // `@` 同 `.` 係電郵必用；`.com` 係常用尾巴。`/` 喺電郵極少用，
                // 長撳 `.` 仍然揀得到（見 [PUNCT_VARIANTS]）。
                r3.add(Key(
                    KeyAction.CHAR, label = "@", text = "@", weight = 1f,
                    variants = listOf("@", "@gmail.com", "@hotmail.com")
                ))
                r3.add(Key(KeyAction.SPACE, label = "␣", weight = 2.2f))
                r3.add(punct("."))
                r3.add(domainKey())
            }
            // URL 欄：`,` 在網址中幾乎用不着，收起了讓 `/` 與 `.com` 上來。
            // `.` 與 `/` 仍是 [punct]，長撳有 `: - _ ~ = ?` 這些網址常用符號。
            LatinField.URI -> {
                r3.add(punct("/"))
                r3.add(Key(KeyAction.SPACE, label = "␣", weight = 2.2f))
                r3.add(punct("."))
                r3.add(domainKey())
            }
            // 密碼欄：`,` `/` 收起（密碼很少用），換上 `-` `_` 兩粒最常見的符號。
            // 長撳 `.` 仍有 `; ' " : ~` 一堆。這一頁亦不准滑動、不出提示（見
            // [canSwipe] 與 `TTInputMethodService.latinTypingSuggestions`）。
            LatinField.PASSWORD -> {
                r3.add(Key(KeyAction.SPACE, label = "␣", weight = 2.6f))
                r3.add(punct("."))
                r3.add(Key(KeyAction.CHAR, label = "-", text = "-"))
                r3.add(Key(KeyAction.CHAR, label = "_", text = "_"))
            }
            LatinField.NORMAL -> {
                // `,` 喺 space 左面、`.` 喺右面 —— `.` 貼實粒 ⏎，打完句號緊接住換行
                // 嗰下手指唔使行過成條 space。粒 `/` 唔喺度，長撳 `.` 攞。
                r3.add(punct(","))
                r3.add(Key(KeyAction.SPACE, label = "␣", weight = 4f))
                r3.add(punct("."))
            }
        }
        // ⏎ 減咗兩成闊（2026-09-11 user 要求）：慳落嘅位全部益咗 space bar
        r3.add(Key(KeyAction.ENTER, label = "⏎", weight = 1.36f, accent = true))
        return if (numRow) listOf(digits, r0, r1, r2, r3) else listOf(r0, r1, r2, r3)
    }

    /** 長撳 .com：`.com.hk`、`.net` 這些常用尾巴，第一個照舊是 `.com` 本身 */
    private fun domainKey() = Key(
        KeyAction.CHAR, label = ".com", text = ".com", weight = 1.5f,
        variants = listOf(".com", ".com.hk", ".hk", ".net", ".org", ".edu", ".gov")
    )

    private fun shiftLabel() = when (shift) {
        ShiftState.OFF -> "⇧"
        ShiftState.ON -> "⬆"
        ShiftState.LOCK -> "⇪"
    }

    override fun displayLabel(k: Key): String =
        if (k.action == KeyAction.CHAR && k.text.length == 1 && k.text[0] in 'a'..'z' && shift != ShiftState.OFF)
            k.text.uppercase() else labelOf(k)

    override fun isFunctionKey(k: Key) = k.action != KeyAction.CHAR

    // ---- 滑動 -------------------------------------------------------------

    /** 密碼欄不准滑動：滑出來的一定是詞庫中的字，對密碼沒有用，還會經候選欄現形 */
    override fun canSwipe(key: Key) =
        key.swipeable && fieldKind != LatinField.PASSWORD && Prefs.swipeEnabled(context)

    /**
     * 英文要**行過成粒鍵**先當滑動，唔係一過 touch slop 就算。
     *
     * 單撳嗰陣手指好易帶少少，一帶就變咗條好短嘅 swipe，打乜都出錯字。
     * qwerty 上面又冇兩個字母貼住嘅英文詞，所以「拉到隔離格咁遠就放手」
     * 一律當誤觸 —— 唔畫線、唔查詞庫，照出返粒鍵本身。
     */
    override fun swipeStartDistPx(box: KeyBox?): Float {
        val b = box ?: return super.swipeStartDistPx(null)
        return max(b.w, b.h) * 1.2f
    }

    /** 撳落／滑動嗰陣手指遮住咗粒鍵，喺上面浮返個大字出嚟（字母、數字都要） */
    override fun hoverLabel(box: KeyBox): String? {
        val k = box.key
        if (k.action != KeyAction.CHAR || k.text.length != 1) return null
        val c = k.text[0]
        if (c !in 'a'..'z' && c !in '0'..'9') return null
        return displayLabel(k)
    }

    override fun swipeKeyAt(x: Float, y: Float): Int {
        val b = boxAt(x, y) ?: return GestureKeyTracker.NO_KEY
        val k = b.key
        if (k.action != KeyAction.CHAR || k.text.length != 1) return GestureKeyTracker.NO_KEY
        val c = k.text[0]
        return if (c in 'a'..'z') c - 'a' else GestureKeyTracker.NO_KEY
    }

    override fun onSwipeEnd() {
        if (tracker.points.size < 4) return // 至少要有兩個點先夾到條軌跡
        latinHost?.onSwipePath(
            ArrayList(tracker.points), ArrayList(tracker.times), ::keyCenter, avgLetterKeyWidth()
        )
    }

    /** 邊個字母個鍵中心喺邊，畀 [tt.ime.riverine.swipe.GestureDecoder] 砌「理想路徑」用 */
    private fun keyCenter(c: Char): Pair<Float, Float>? {
        for (b in boxes) {
            val k = b.key
            if (k.action == KeyAction.CHAR && k.text.length == 1 && k.text[0] == c) return b.cx to b.cy
        }
        return null
    }

    /** 用嚟將軌跡距離正規化，唔同螢幕、唔同鍵盤大細都夾得返 */
    private fun avgLetterKeyWidth(): Float {
        var total = 0f
        var n = 0
        for (b in boxes) {
            val k = b.key
            if (k.action == KeyAction.CHAR && k.text.length == 1 && k.text[0] in 'a'..'z') {
                total += b.w; n++
            }
        }
        return if (n > 0) total / n else dp(40f)
    }
}
