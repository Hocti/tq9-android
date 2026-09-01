package tt.ime.riverine.ime

import android.content.Context
import tt.ime.riverine.core.Prefs
import tt.ime.riverine.swipe.GestureKeyTracker
import kotlin.math.max

enum class ShiftState { OFF, ON, LOCK }

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
 * 標點鍵長撳彈出嘅嘢。三粒都會喺左上角寫返個細字提示（見 [LatinPadView.punct]），
 * 唔係冇人知撳實佢仲有嘢揀。
 *
 * 三粒都**唔跟**「第一個 = 自己本身」嗰個規矩 —— 每粒排頭嗰個係長撳一彈出嚟
 * 就已經停咗喺度嗰個（唔郁手指放開就出佢），粒鍵自己短撳就攞得返：
 *
 *  - `,` → **Tab**（`\t`，畫成 `⇥`）
 *  - `.` → `;`
 *  - `/` → `?`（`?` 打得多過 `/` 好多）
 */
private val PUNCT_VARIANTS: Map<String, List<String>> = mapOf(
    "," to listOf("\t", ",", "<", ">", "[", "]", "{", "}"),
    "." to listOf(";", ".", "'", "\"", ":", "`", "~"),
    "/" to listOf("?", "/", "\\", "|", "=", "_", "+", "-")
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
 * 長撳彈出嘅 list 第一個要係自己本身 —— 好似一般 keyboard 咁，
 * 唔郁手指直接放手就係打返個數字，唔係跳咗去第一個符號。
 */
fun digitKey(d: String, weight: Float = 1f, bigLabel: Boolean = false): Key {
    val syms = DIGIT_SYMBOLS[d].orEmpty()
    return Key(
        KeyAction.CHAR, label = d, text = d, weight = weight, bigLabel = bigLabel,
        hintRight = syms.firstOrNull().orEmpty(), variants = listOf(d) + syms
    )
}

/** 英文 QWERTY，支援 Swipe 畫線 */
class LatinPadView(context: Context) : RowsPadView(context) {

    interface LatinHost {
        /**
         * 滑完一次嘅原始軌跡（x,y 交替）連埋「邊個字母個鍵中心喺邊」一齊拋畀 host。
         * **唔係**喺呢度查詞庫 —— host 要用 [tt.ime.riverine.swipe.GestureDecoder] 做形狀比對，
         * 仲要連 caret 前後已經打咗嘅字母一齊計（`dis|y` 滑 `pla` = `display`）。
         */
        fun onSwipePath(path: List<Float>, keyCenter: (Char) -> Pair<Float, Float>?, keyWidth: Float)
    }

    var latinHost: LatinHost? = null

    var shift: ShiftState = ShiftState.OFF
        set(v) { field = v; invalidate() }

    /** email 欄位先出 @ 同 .com */
    var emailMode: Boolean = false
        set(v) { if (field != v) { field = v; rebuild() } }

    /** 搵 emoji 嗰陣：打嘅字唔入去，而係篩上面條 bar 嘅 emoji */
    var emojiSearchMode: Boolean = false
        set(v) { if (field != v) { field = v; rebuild() } }

    /**
     * 長撳彈出嘅變體 list，第一個一定係 [c] 自己本身 —— 撳實唔郁直接放手
     * 就係打返個字，跟一般 keyboard 嘅習慣（唔係跳咗去第一個口音字）。
     */
    private fun ch(
        c: String, hint: String = "", hintRight: String = "", extra: List<String> = emptyList(),
        weight: Float = 1f
    ): Key {
        val letter = c.length == 1 && c[0] in 'a'..'z'
        val upper = letter && shift != ShiftState.OFF
        // 大細階兩樣都要揀得到：排頭嗰個係而家粒鍵寫住嗰個（撳實唔郁放手 = 打返佢），
        // 第二個就係另一個大細階，跟住先至係口音字（一樣跟返而家嘅大細階）
        val base = if (upper) c.uppercase() else c
        val head = if (!letter) listOf(c)
                   else listOf(base, if (upper) c.lowercase() else c.uppercase())
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
            hint = tip?.let(::variantDisplay).orEmpty(), variants = v
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
        // `,` 搬咗落底行（頂咗本來個 `?`），呢行讓返出嚟嘅位就俾 ⇧ 同 ⌫ 拉長
        val r2 = listOf(
            Key(KeyAction.SHIFT, label = shiftLabel(), weight = 1.75f,
                accent = shift == ShiftState.LOCK)
        ) + "zxcvbnm".map { ch(it.toString()) } +
            listOf(Key(KeyAction.BACKSPACE, label = "⌫", weight = 1.75f, repeatable = true))
        val r3 = ArrayList<Key>()
        // 搵 emoji 嗰陣底行淨係要「退出」同 ␣ 兩粒：`?123`、`中`、`⏎`、標點喺呢頁
        // 一粒都用唔著（打嘅字淨係用嚟篩 emoji，唔會入落個欄）。粒退出掣**寫明幾隻字**
        // —— 以前淨係得個 😀，冇人知撳落去係唔搵住定係入咗個 emoji
        if (emojiSearchMode) {
            r3.add(Key(KeyAction.TO_EMOJI, label = "退出表情搜尋", weight = 3f))
            r3.add(Key(KeyAction.SPACE, label = "␣", weight = 4f))
            return if (numRow) listOf(digits, r0, r1, r2, r3) else listOf(r0, r1, r2, r3)
        }
        r3.add(Key(KeyAction.TO_CHINESE, label = "中", weight = 1.3f, bigLabel = true))
        // 長撳 ?123 唔使經符號頁，直接跳去純數字 keypad。
        // **冇左上角提示字**（2026-08-29 user 要求）—— 呢粒鍵面本身已經四個字符，
        // 英文底行粒粒都窄，再喺左上角迫多個「123」就撞埋一舊。
        // 中文九宮格嗰粒地方鬆啲，個 hint 照留。
        r3.add(Key(KeyAction.TO_SYMBOL, label = "?123", weight = 1.3f,
            longAction = KeyAction.TO_NUMBER))
        if (emailMode) {
            // 長撳 @：可以揀常用信箱域名，第一個照舊係 @ 本身
            r3.add(Key(
                KeyAction.CHAR, label = "@", text = "@", weight = 1f,
                variants = listOf("@", "@gmail.com", "@hotmail.com")
            ))
            r3.add(Key(KeyAction.SPACE, label = "␣", weight = 2.2f))
            // 長撳 .com：.com.hk、.net 呢啲常用尾巴，第一個照舊係 .com 本身
            r3.add(Key(
                KeyAction.CHAR, label = ".com", text = ".com", weight = 1.5f,
                variants = listOf(".com", ".com.hk", ".net", ".org", ".edu", ".gov")
            ))
            r3.add(punct("/"))
        } else {
            r3.add(Key(KeyAction.SPACE, label = "␣", weight = 3.4f))
            // space 右邊順住排 `, . /` 三粒，三粒都有長撳 popup
            r3.add(punct(","))
            r3.add(punct("."))
            r3.add(punct("/"))
        }
        r3.add(Key(KeyAction.ENTER, label = "⏎", weight = 1.7f, accent = true))
        return if (numRow) listOf(digits, r0, r1, r2, r3) else listOf(r0, r1, r2, r3)
    }

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

    override fun canSwipe(key: Key) = key.swipeable && Prefs.swipeEnabled(context)

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
        latinHost?.onSwipePath(ArrayList(tracker.points), ::keyCenter, avgLetterKeyWidth())
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
