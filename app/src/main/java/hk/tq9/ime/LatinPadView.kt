package hk.tq9.ime

import android.content.Context
import hk.tq9.core.Prefs
import hk.tq9.swipe.GestureKeyTracker

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
    "m" to listOf("µ"),
    "." to listOf(",", "?", "!", "'", "\"", ":", ";", "-")
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

/** 英文 QWERTY，支援 Swype 畫線 */
class LatinPadView(context: Context) : RowsPadView(context) {

    interface LatinHost {
        /**
         * 滑完一次滑過嘅字母。**唔係**喺呢度查詞庫 —— host 要連 caret 前後
         * 已經打咗嘅字母一齊計（`dis|y` 滑 `pla` = `display`），所以淨係交返啲字母出去。
         */
        fun onSwipeLetters(letters: List<Char>)
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

    private val gestureLetters = ArrayList<Char>(24)

    /**
     * 長撳彈出嘅變體 list，第一個一定係 [c] 自己本身 —— 撳實唔郁直接放手
     * 就係打返個字，跟一般 keyboard 嘅習慣（唔係跳咗去第一個口音字）。
     */
    private fun ch(
        c: String, hint: String = "", hintRight: String = "", extra: List<String> = emptyList(),
        weight: Float = 1f
    ) = Key(
        KeyAction.CHAR, label = c, text = c, hint = hint, hintRight = hintRight, weight = weight,
        variants = listOf(c) + extra + ACCENTS[c].orEmpty(),
        swipeable = c.length == 1 && c[0] in 'a'..'z'
    )

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
        // a、l 兩隻收邊鍵較長，s~k 就同其他行嘅每粒鍵一樣闊
        val r1 = "asdfghjkl".map { ch(it.toString(), weight = if (it == 'a' || it == 'l') 1.5f else 1f) }
        val r2 = listOf(
            Key(KeyAction.SHIFT, label = shiftLabel(), weight = 1.2f,
                accent = shift == ShiftState.LOCK)
        ) + "zxcvbnm".map { ch(it.toString()) } + listOf(ch(",")) +
            listOf(Key(KeyAction.BACKSPACE, label = "⌫", weight = 1.2f, repeatable = true))
        val r3 = ArrayList<Key>()
        if (emojiSearchMode) {
            r3.add(Key(KeyAction.TO_EMOJI, label = "😀", weight = 1.3f, bigLabel = true))
        } else {
            r3.add(Key(KeyAction.TO_CHINESE, label = "中", weight = 1.3f, bigLabel = true))
        }
        r3.add(Key(KeyAction.TO_SYMBOL, label = "?123", weight = 1.3f))
        // 搵 emoji 嗰陣粒位讓返俾「中」（打中文關鍵字，例如「貓」），
        // 平時就係 "/"（轉輸入法喺中文 view 嘅 🌐 度做）
        if (emojiSearchMode) r3.add(Key(KeyAction.TO_CHINESE, label = "中", weight = 1f))
        else r3.add(ch("/"))
        if (emailMode) {
            r3.add(Key(KeyAction.CHAR, label = "@", text = "@", weight = 1f))
            r3.add(Key(KeyAction.SPACE, label = "␣", weight = 2.4f))
            // 長撳 .com：.com.hk、.net 呢啲常用尾巴，第一個照舊係 .com 本身
            r3.add(Key(
                KeyAction.CHAR, label = ".com", text = ".com", weight = 1.6f,
                variants = listOf(".com", ".com.hk", ".net", ".org", ".edu", ".gov")
            ))
        } else {
            r3.add(Key(KeyAction.SPACE, label = "␣", weight = 4.6f))
            r3.add(ch("."))
        }
        r3.add(Key(KeyAction.ENTER, label = "⏎", weight = 1.8f, accent = true))
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

    override fun swipeKeyAt(x: Float, y: Float): Int {
        val b = boxAt(x, y) ?: return GestureKeyTracker.NO_KEY
        val k = b.key
        if (k.action != KeyAction.CHAR || k.text.length != 1) return GestureKeyTracker.NO_KEY
        val c = k.text[0]
        return if (c in 'a'..'z') c - 'a' else GestureKeyTracker.NO_KEY
    }

    override fun onSwipeStart() {
        gestureLetters.clear()
        tracker.minSegPx = dp(10f)
    }

    /** 英文係放手先出字，所以呢度淨係 buffer */
    override fun onGestureKey(index: Int) {
        val c = ('a' + index)
        if (gestureLetters.isEmpty() || gestureLetters.last() != c) gestureLetters.add(c)
    }

    override fun onSwipeEnd() {
        if (gestureLetters.size < 2) { gestureLetters.clear(); return }
        latinHost?.onSwipeLetters(ArrayList(gestureLetters))
        gestureLetters.clear()
    }
}
