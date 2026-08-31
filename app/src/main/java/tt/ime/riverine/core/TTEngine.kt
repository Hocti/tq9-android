package tt.ime.riverine.core

import kotlin.math.ceil

enum class TTCmd { CANCEL, PREV, NEXT, HOMO, OPENCLOSE, RELATE, SHORTCUT }

/** 九宮格入面一格嘅內容 */
data class PadKey(
    var img: String? = null,     // assets/img 入面嘅名，例如 "0_1"
    var dim: Boolean = false,    // 半透明（第二碼之後嘅提示圖）
    var text: String = "",       // 置中大字（選字時）
    var hint: String = "",       // 左上角灰色細字（關聯字提示）
    var enabled: Boolean = true
) {
    fun clear() { img = null; dim = false; text = ""; hint = ""; enabled = true }
}

/**
 * 三三輸入法核心狀態機，由 Windows 版移植。
 *
 * 打字流程：
 *  - 首頁按 1~9 → 顯示第二碼提示圖；再按一個 → 顯示「選字」提示圖
 *  - 夠三碼、或者中途按 0 收尾 → 查 mapped_table 出關聯字
 *  - 首頁直接按 0 → 標點；按 1~9 之後按 0 → 姓氏表
 */
class TTEngine(val db: TTDb) {

    interface Host {
        fun commitText(text: String)
        /** 開關標點：一次過輸入「」再把游標移返中間 */
        fun commitPair(pair: String)
        /** 狀態變咗，重畫 */
        fun onStateChanged()

        // ---- 使用習慣統計（bigram / 每字次數，跟住呢部機、唔跟 dataset.db） ----
        /** 打咗一個單字（bigram 嘅其中一個字） */
        fun bumpChar(ch: String)
        /** 連續打咗兩個中文字（`a+b`） */
        fun bumpBigram(pair: String)
        fun bigramCount(pair: String): Int
    }

    var host: Host? = null

    /** 1..9 有效；index 0 未用 */
    val keys = Array(10) { PadKey() }

    var key0Label: String = "標點"; private set
    var cancelLabel: String = "取消"; private set

    var currCode: String = ""; private set
    var homo: Boolean = false; private set
    var scOutput: Boolean = false

    /**
     * 要唔要將打得多嘅字推前（設定頁嗰個開關，見 `Prefs.KEY_USAGE_REORDER`）。
     * 熄咗就完全跟返字碼表原本嘅次序，[Host] 嗰邊照計次數，淨係唔攞嚟排位。
     */
    var usageReorder: Boolean = true

    private var afterHomo = false
    private var openclose = false
    private var lastWord = ""

    var selectMode: Boolean = false; private set
    var selectWords: List<String> = emptyList(); private set
    var currPage: Int = 0; private set
    var totalPage: Int = 0; private set

    /** 送去 option bar 顯示嘅關聯字（未入選字模式嗰陣） */
    var relateHints: List<String> = emptyList(); private set

    var statusPrefix: String = ""; private set
    var statusText: String = ""; private set

    /**
     * 上一個計落 bigram 嘅單字（連續兩個中文字嗰個組合）。
     * 遇到標點、換行、或者揀咗一個多字詞就清走，唔會跨過去。
     */
    private var bigramPrev: String = ""

    /**
     * 啱啱用同音字打完一個字，嗰個字**正路應該點打**（字碼）。
     * 畫喺同音鍵左上角提你返轉頭應該撳邊幾個掣，打多一個普通字就會清走。
     */
    var homoCodeHint: String = ""; private set

    /**
     * 而家攤開緊邊隻字嘅同音字表（撳「同音」掣、或者選字模式長撳一格都會 set）。
     * 一樣畫喺同音鍵左上角 —— 揀緊嗰版全部都係同音字，冇個字擺喺度就唔知搵緊邊隻字嘅音。
     * 揀完（`cancel()`）就清走，之後個位就讓返俾 [homoCodeHint]。
     */
    var homoWord: String = ""; private set

    /** 選字夠兩頁嗰陣「下頁」鍵左上角寫嘅頁數（由 1 起計，例：`2/10`） */
    val pageHint: String
        get() = if (selectMode && totalPage > 1) "${currPage + 1}/$totalPage" else ""

    val status: String
        get() = buildString {
            if (homo) append("[同音] ")
            if (scOutput) append("[简] ")
            append(statusPrefix)
            if (statusText.isNotEmpty()) { append(' '); append(statusText) }
        }.trim()

    init { reset() }

    fun reset() {
        selectMode = false
        homo = false
        afterHomo = false
        openclose = false
        currCode = ""
        currPage = 0
        totalPage = 0
        selectWords = emptyList()
        relateHints = emptyList()
        lastWord = ""
        statusPrefix = ""
        statusText = ""
        homoCodeHint = ""
        homoWord = ""
        bigramPrev = ""
        setPadImages(0)
    }

    /** 換咗行：中文 bigram 統計唔可以由上一行接落嚟 */
    fun onLineBreak() { bigramPrev = "" }

    /** 有冇緊要狀態未清（決定 backspace 要唔要食咗佢） */
    val busy: Boolean get() = selectMode || currCode.isNotEmpty()

    // ---- 按鍵 -------------------------------------------------------------

    fun press(digit: Int) {
        if (selectMode) {
            if (digit == 0) cmd(TTCmd.NEXT) else selectWord(digit)
        } else {
            currCode += digit
            statusPrefix = currCode
            statusText = ""
            when {
                digit == 0 -> processResult(db.keyInput(currCode.toInt()))
                currCode.length == 3 -> processResult(db.keyInput(currCode.toInt()))
                currCode.length == 1 -> setPadImages(digit)
                else -> setPadImages(10)
            }
            changed()
        }
    }

    fun cmd(command: TTCmd) {
        when (command) {
            TTCmd.CANCEL -> cancel()

            TTCmd.OPENCLOSE -> {
                homo = false
                afterHomo = false
                openclose = true
                val pieces = db.keyInput(1)
                val pairs = ArrayList<String>(pieces.size / 2)
                var i = 0
                while (i + 1 < pieces.size) { pairs.add(pieces[i] + pieces[i + 1]); i += 2 }
                statusPrefix = "「」"
                startSelectWord(pairs)
            }

            TTCmd.HOMO -> pressHomo()

            TTCmd.SHORTCUT -> {
                if (selectMode) {
                    addPage(-1)
                } else if (currCode.isEmpty()) {
                    statusPrefix = "速選"
                    startSelectWord(db.keyInput(1000))
                } else if (currCode.length == 1) {
                    statusPrefix = "速選" + currCode
                    startSelectWord(db.keyInput(1000 + currCode.toInt()))
                }
            }

            TTCmd.RELATE -> {
                if (lastWord.codePointCount(0, lastWord.length) == 1) {
                    homo = false
                    statusPrefix = "[$lastWord]關聯"
                    startSelectWord(db.getRelate(lastWord))
                }
            }

            TTCmd.PREV -> if (selectMode) addPage(-1)
            TTCmd.NEXT -> if (selectMode) addPage(1)
        }
        changed()
    }

    /**
     * 撳「同音」。同 Windows 原版一樣，**淨係 toggle 個 flag**：
     * 開咗之後照樣打碼揀字，揀嗰下先至彈返個字嘅同音字表出嚟。
     * 唔會即刻換走而家個字表 —— 試過一撳就開表，打斷咗打字流程，收返。
     */
    private fun pressHomo() {
        homo = !homo
    }

    /**
     * **未打過任何碼**嗰陣長撳 1~9：唔使先撳個碼再撳「速選」，
     * 直接開嗰格嘅速選字表（`mapped_table` 嘅 `1000 + digit`）。
     *
     * 回 false = 冇嘢開得（唔喺首頁、或者嗰格根本冇速選字），
     * 粒鍵就行返平時嘅長撳（九宮格係「長撳 = 連撳」）。
     */
    fun shortcutDigit(digit: Int): Boolean {
        if (digit !in 1..9 || selectMode || currCode.isNotEmpty()) return false
        val words = db.keyInput(1000 + digit)
        if (words.isEmpty()) return false
        statusPrefix = "速選$digit"
        startSelectWord(words)
        changed()
        return true
    }

    /**
     * 選字模式長撳其中一格：直接開嗰個字嘅同音字表 ——
     * **唔使先撳「同音」掣**，兩條路出嚟嘅表一模一樣（見 [selectWord] 嘅 `homo` 段）。
     *
     * 回 false = 嗰格冇字、係多字詞（同音字淨係單字先有）、或者查唔到同音字。
     */
    fun homoAt(slot: Int): Boolean {
        if (!selectMode || openclose || slot !in 1..9) return false
        val idx = currPage * 9 + rankAt(slot)
        if (idx >= selectWords.size) return false
        val word = selectWords[idx]
        if (word.isEmpty()) return false
        val list = db.getHomo(word)
        if (list.isEmpty()) return false
        homo = false
        // 揀完之後照樣喺狀態列寫返個字正路點打（同撳「同音」掣嗰條路一樣）
        afterHomo = true
        statusPrefix = "同音[$word]"
        homoWord = word   // 同音鍵左上角要寫住而家搵緊邊隻字嘅同音
        startSelectWord(list)
        changed()
        return true
    }

    /** 上面條 bar 冇關聯字嗰陣會出速選字，撳咗就當揀咗佢（會行返同音／關聯字嗰套） */
    fun pickQuick(word: String) {
        if (word.isEmpty() || word == PLACEHOLDER) return
        startSelectWord(listOf(word))
        // 得一個字，即係第一頁排第一 —— 坐邊格要問 [slotOrder]
        selectWord(slotAt(0))
        changed()
    }

    /** option bar 直接揀（index 係整個 selectWords 嘅絕對位置） */
    fun pickCandidateAt(index: Int) {
        if (!selectMode || index < 0 || index >= selectWords.size) return
        // 佔位嘅吉格：唔好連頁都揭埋（[selectWord] 嗰邊照樣會擋，但頁已經郁咗）
        if (selectWords[index].isEmpty()) return
        currPage = index / 9
        selectWord(slotAt(index % 9))
        changed()
    }

    /** option bar 揀關聯字（未入選字模式嗰陣） */
    fun pickRelateAt(index: Int) {
        val list = relateHints
        if (index < 0 || index >= list.size) return
        cmd(TTCmd.RELATE)
        if (selectMode) pickCandidateAt(index)
    }

    /** backspace：先食咗未完成嘅碼，冇先至真係刪字 */
    fun backspace(): Boolean {
        if (selectMode) { cancel(); changed(); return true }
        if (currCode.isNotEmpty()) {
            currCode = currCode.dropLast(1)
            statusPrefix = currCode
            when (currCode.length) {
                0 -> setPadImages(0)
                1 -> setPadImages(currCode[0] - '0')
                else -> setPadImages(10)
            }
            changed()
            return true
        }
        return false
    }

    // ---- swipe 用嘅可能性評估 ---------------------------------------------

    /**
     * 滑動途中「呢一格到底有冇撳過」唔肯定嗰陣，用字碼表 weight 幫手判斷。
     * 回傳 -1f（多數係誤觸）~ +1f（好合理）。
     */
    fun plausibility(digit: Int): Float {
        if (selectMode) {
            if (digit == 0) return if (totalPage > 1) 0f else -0.8f
            // 選字途中滑過某格就揀錯字代價好大，所以中性；冇字嘅格就直接否決
            val idx = currPage * 9 + rankAt(digit)
            return if (idx < selectWords.size && selectWords[idx].isNotEmpty()) 0f else -1f
        }
        val next = currCode + digit
        if (next.length >= 4) return -1f
        if (digit == 0) {
            // 首頁按 0 = 標點，一定有；其他情況即係收碼，要真係查到嘢先算
            if (next.length == 1) return 0f
            return if (db.hasId(next.toInt())) 0.5f else -1f
        }
        return db.prefixPlausibility(next)
    }

    // ---- 內部 -------------------------------------------------------------

    private fun processResult(words: List<String>) {
        if (words.isEmpty()) { cancel(); return }
        startSelectWord(reorderByUsage(words))
    }

    /**
     * 「常用字排前」：點排見 companion 嗰個 [TTEngine.reorderByUsage]。
     *
     * 熄咗開關、或者根本冇上一個字（啱啱開始打／打完標點換行）就原封不動 ——
     * 冇 bigram 睇就冇嘢排得。
     */
    private fun reorderByUsage(words: List<String>): List<String> {
        val h = host ?: return words
        val prev = bigramPrev
        if (!usageReorder || prev.isEmpty()) return words
        return reorderByUsage(words) { w -> h.bigramCount(prev + w) }
    }

    private fun startSelectWord(words: List<String>) {
        if (words.isEmpty()) return
        // 字碼表入面嘅 `*` 淨係**佔住個位**（後面嗰啲字先至坐得返啱格，例如
        // `mapped_table` 169 = `********教`，「教」一定要坐第 9 格），本身唔係
        // 隻打得嘅字。喺呢度一次過變吉 —— 揀字（[selectWord]）／條 bar／
        // 側邊欄跟住就同「呢一格冇字」一樣咁處理，撳落去乜都唔會出。
        selectWords = words.map { if (it == PLACEHOLDER) "" else it }
        totalPage = ceil(words.size / 9.0).toInt()
        selectMode = true
        currCode = ""
        showPage(0)
        cancelLabel = "取消"
        key0Label = if (totalPage > 1) "下頁" else ""
    }

    /** 而家攤開緊嗰個表：第 [page] 頁排第 [rank] 嗰個字坐邊格 */
    private fun slotAt(rank: Int, page: Int = currPage): Int = slotOrder(page)[rank]

    /** 而家攤開緊嗰個表：[slot] 格坐住嘅係排第幾（由 0 起，唔喺表入面就 -1） */
    private fun rankAt(slot: Int, page: Int = currPage): Int =
        if (slot !in 1..9) -1 else slotOrder(page).indexOf(slot)

    private fun addPage(delta: Int) {
        val p = currPage + delta
        showPage(if (p < 0) totalPage - 1 else if (p >= totalPage) 0 else p)
    }

    private fun showPage(page: Int) {
        currPage = page
        for (rank in 0..8) {
            val p = page * 9 + rank
            val w = if (p >= selectWords.size) "" else selectWords[p]
            val i = slotAt(rank, page)
            keys[i].clear()
            keys[i].text = w
            keys[i].enabled = w.isNotEmpty()
        }
        statusText = if (totalPage > 1) "${currPage + 1}/${totalPage}頁" else ""
        key0Label = if (totalPage > 1) "下頁" else ""
    }

    private fun selectWord(slot: Int) {
        val rank = rankAt(slot)
        if (rank < 0) return
        val key = currPage * 9 + rank
        if (key >= selectWords.size) return
        val typeWord = selectWords[key]
        // 吉位（表尾未排滿、或者字碼表嗰個 `*` 佔位符）撳極都唔會出字
        if (typeWord.isEmpty()) return

        if (homo) {
            homo = false
            afterHomo = true
            statusPrefix = "同音[$typeWord]"
            homoWord = typeWord   // 同音鍵左上角要寫住而家搵緊邊隻字嘅同音
            val h = db.getHomo(typeWord)
            if (h.isEmpty()) { cancel() } else startSelectWord(h)
            changed()
            return
        }
        if (openclose) {
            openclose = false
            host?.commitPair(out(typeWord))
            bigramPrev = "" // 標點打斷咗連續兩個中文字嘅組合
            cancel()
            changed()
            return
        }

        host?.commitText(out(typeWord))

        // 連續兩個中文字（唔計標點、唔計揀咗嘅多字詞）就算一個 bigram，記落使用次數
        if (isHanChar(typeWord)) {
            val prev = bigramPrev
            if (prev.isNotEmpty()) host?.bumpBigram(prev + typeWord)
            host?.bumpChar(typeWord)
            bigramPrev = typeWord
        } else {
            bigramPrev = ""
        }

        val single = typeWord.codePointCount(0, typeWord.length) == 1
        val relates = if (single) { lastWord = typeWord; db.getRelate(typeWord) }
                      else { lastWord = ""; emptyList() }

        val lastAfterHomo = afterHomo
        if (relates.isNotEmpty()) {
            cancel(clearPad = false)
            setRelateHints(relates)
        } else {
            cancel()
        }

        if (lastAfterHomo) {
            afterHomo = false
            val nums = db.getCode(typeWord).joinToString(",")
            statusPrefix = "$typeWord 碼:$nums"
            homoCodeHint = nums
        } else {
            // 打返個普通字，同音鍵嗰段細字就唔關事，清走
            homoCodeHint = ""
        }
        changed()
    }

    private fun out(s: String): String = if (scOutput) db.tcsc(s) else s

    fun cancel(clearPad: Boolean = true) {
        selectMode = false
        homo = false
        afterHomo = false
        openclose = false
        currCode = ""
        currPage = 0
        totalPage = 0
        selectWords = emptyList()
        statusPrefix = ""
        statusText = ""
        homoWord = ""
        if (clearPad) { relateHints = emptyList(); setPadImages(0) }
    }

    /** type: 0 = 首頁, 1~9 = 第二碼提示, 10 = 第三碼（淡色） */
    private fun setPadImages(type: Int) {
        relateHints = emptyList()
        for (i in 1..9) {
            keys[i].clear()
            keys[i].img = if (type == 10) "0_$i" else "${type}_$i"
            keys[i].dim = type == 10
        }
        key0Label = when {
            type == 0 -> "標點"
            type <= 9 -> "姓氏"
            else -> "選字"
        }
        cancelLabel = "取消"
    }

    /**
     * 打完一個字：九宮格照返首頁（可以直接打下一個字），
     * 關聯字唔會擠喺格仔上面，改為交俾上面條 bar 揀。
     */
    private fun setRelateHints(words: List<String>) {
        setPadImages(0)
        relateHints = words.filter { it.isNotEmpty() && it != PLACEHOLDER }
    }

    private fun changed() { host?.onStateChanged() }

    companion object {
        /**
         * 字碼表（同關聯字表）入面嘅**佔位符**：淨係擺喺度令後面啲字唔會走位，
         * 唔係一隻打得嘅字。見 [startSelectWord]。
         */
        const val PLACEHOLDER = "*"

        /**
         * 「常用字排前」要打過幾多次先至肯郁個次序（bigram 同單字次數都用呢個）。
         * 少過呢個數就當冇打過 —— 撳錯一次唔應該就影響到之後嘅選字次序。
         */
        const val MIN_USAGE_COUNT = 2

        /**
         * 「常用字排前」點排（[count] = 呢隻字同上一個字組成過幾多次 bigram）。
         *
         * **頭 9 個（第一頁）永遠唔郁**：第一頁個格號就係隻字個碼最後嗰個數字
         * （見 [slotOrder]），次序一調就即刻累到所有打熟咗嘅手勢。所以打得幾多
         * 都好，頭九位原封不動。
         *
         * **第 10 位起（第二頁開始）先至排**：打夠 [MIN_USAGE_COUNT] 次嘅推去
         * 前面，次數越大越前。即係話「常用但唔喺頭九位」嗰啲字，最多推到第 10 位
         * （第二頁排第一），撩唔到第一頁 —— 反正第二頁開始本來就冇碼可以記，
         * 一定要望住揀，推前咗淨係少揭幾版。
         *
         * 要打夠 [MIN_USAGE_COUNT] 次先算數：打過一次就當「常用」太急，
         * 撳錯一下就會累住之後個次序都唔同咗，user 會覺得個字表自己識郁。
         */
        fun reorderByUsage(words: List<String>, count: (String) -> Int): List<String> {
            // 尾巴得一個（或者根本得一頁）點排都係同一個樣
            if (words.size <= 10) return words
            val tail = words.drop(9).sortedByDescending { w ->
                if (isHanChar(w)) qualified(count(w)) else -1
            }
            return words.take(9) + tail
        }

        /** 未打夠 [MIN_USAGE_COUNT] 次就當冇打過（-1 = 排返原本個位，穩定排序唔會亂） */
        private fun qualified(count: Int): Int = if (count >= MIN_USAGE_COUNT) count else -1

        /** 得一隻漢字先當得（`*` 佔位符、英文、標點、多字詞一律唔算） */
        private fun isHanChar(s: String): Boolean =
            s.isNotEmpty() && s != PLACEHOLDER && s.codePointCount(0, s.length) == 1 &&
                Character.UnicodeScript.of(s.codePointAt(0)) == Character.UnicodeScript.HAN

        /**
         * **第二頁開始**先至用嘅格仔先後次序：嗰頁排第一嗰個字擺 `5`，跟住 `4`、`6`…
         *
         * 九宮格排位係 numpad（`7 8 9` 喺最上），所以 `5` 喺正中間，最易撳到；
         * `4 6 2 8` 四邊次之；`1 3 7 9` 四角最難撳，排最後。
         * 即係話第二頁得三個字嘅時候，佢哋會坐 `5 4 6`，唔會由 `1` 開始排。
         */
        private val SLOT_ORDER = listOf(5, 4, 6, 2, 8, 1, 3, 7, 9)

        /** 第一頁嘅次序：照字碼表順住排 */
        private val FIRST_PAGE_ORDER = (1..9).toList()

        /**
         * 第 [page] 頁（由 0 起）啲字**由邊格排起**：
         * 頭一個格號擺排第一嗰個字，如此類推。
         *
         * **第一頁永遠 `1`~`9`，冇任何例外**：嗰個格號就係隻字個碼最後嗰個數字
         * （狀態列「碼:」寫嘅嘢、打熟咗嘅手勢全部靠佢），一調位就即刻全部作廢。
         * 一版揀得晒都好、啱啱撳完邊個碼都好，通通唔關事。
         *
         * **第二頁開始**先至行 [SLOT_ORDER]：嗰啲字本來就冇碼可以記，
         * 一定要望住揀，所以邊格易撳就擺邊格。
         */
        fun slotOrder(page: Int): List<Int> =
            if (page == 0) FIRST_PAGE_ORDER else SLOT_ORDER
    }
}
