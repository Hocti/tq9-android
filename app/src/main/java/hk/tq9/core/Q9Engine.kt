package hk.tq9.core

import kotlin.math.ceil

enum class Q9Cmd { CANCEL, PREV, NEXT, HOMO, OPENCLOSE, RELATE, SHORTCUT }

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
 * 九万輸入法核心狀態機，由 Q9Form.cs 移植。
 *
 * 打字流程：
 *  - 首頁按 1~9 → 顯示第二碼提示圖；再按一個 → 顯示「選字」提示圖
 *  - 夠三碼、或者中途按 0 收尾 → 查 mapped_table 出候選字
 *  - 首頁直接按 0 → 標點；按 1~9 之後按 0 → 姓氏表
 */
class Q9Engine(val db: Q9Db) {

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
        fun charFreq(ch: String): Int
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
            if (digit == 0) cmd(Q9Cmd.NEXT) else selectWord(digit)
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

    fun cmd(command: Q9Cmd) {
        when (command) {
            Q9Cmd.CANCEL -> cancel()

            Q9Cmd.OPENCLOSE -> {
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

            Q9Cmd.HOMO -> pressHomo()

            Q9Cmd.SHORTCUT -> {
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

            Q9Cmd.RELATE -> {
                if (lastWord.codePointCount(0, lastWord.length) == 1) {
                    homo = false
                    statusPrefix = "[$lastWord]關聯"
                    startSelectWord(db.getRelate(lastWord))
                }
            }

            Q9Cmd.PREV -> if (selectMode) addPage(-1)
            Q9Cmd.NEXT -> if (selectMode) addPage(1)
        }
        changed()
    }

    /**
     * 撳「同音」。同原版（`Q9Form.cs`）一樣，**淨係 toggle 個 flag**：
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
        val idx = currPage * 9 + slot - 1
        if (idx >= selectWords.size) return false
        val word = selectWords[idx]
        if (word.isEmpty() || word == "*") return false
        val list = db.getHomo(word)
        if (list.isEmpty()) return false
        homo = false
        // 揀完之後照樣喺狀態列寫返個字正路點打（同撳「同音」掣嗰條路一樣）
        afterHomo = true
        statusPrefix = "同音[$word]"
        startSelectWord(list)
        changed()
        return true
    }

    /** 上面條 bar 冇候選字嗰陣會出速選字，撳咗就當揀咗佢（會行返同音／關聯字嗰套） */
    fun pickQuick(word: String) {
        if (word.isEmpty() || word == "*") return
        startSelectWord(listOf(word))
        selectWord(1)
        changed()
    }

    /** option bar 直接揀（index 係整個 selectWords 嘅絕對位置） */
    fun pickCandidateAt(index: Int) {
        if (!selectMode || index < 0 || index >= selectWords.size) return
        currPage = index / 9
        selectWord(index % 9 + 1)
        changed()
    }

    /** option bar 揀關聯字（未入選字模式嗰陣） */
    fun pickRelateAt(index: Int) {
        val list = relateHints
        if (index < 0 || index >= list.size) return
        cmd(Q9Cmd.RELATE)
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
            val idx = currPage * 9 + digit - 1
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
     * 頭九個（第一頁）：同上一個字組成夠 3 次嘅 bigram 推去前面，次數越大越前。
     * 第九個之後：跟住打過幾多次（[Host.charFreq]）推前，唔郁第一頁。
     */
    private fun reorderByUsage(words: List<String>): List<String> {
        val h = host ?: return words
        if (words.size <= 1) return words
        val head = words.take(9)
        val tail = words.drop(9)
        val prev = bigramPrev
        val newHead = if (prev.isEmpty()) head else head.sortedByDescending { w ->
            if (isHanChar(w)) h.bigramCount(prev + w).let { if (it >= 3) it else -1 } else -1
        }
        val newTail = if (tail.isEmpty()) tail else tail.sortedByDescending { w ->
            if (isHanChar(w)) h.charFreq(w) else -1
        }
        return newHead + newTail
    }

    private fun isHanChar(s: String): Boolean =
        s.isNotEmpty() && s != "*" && s.codePointCount(0, s.length) == 1 &&
            Character.UnicodeScript.of(s.codePointAt(0)) == Character.UnicodeScript.HAN

    private fun startSelectWord(words: List<String>) {
        if (words.isEmpty()) return
        selectWords = words
        totalPage = ceil(words.size / 9.0).toInt()
        selectMode = true
        currCode = ""
        showPage(0)
        cancelLabel = "取消"
        key0Label = if (totalPage > 1) "下頁" else ""
    }

    private fun addPage(delta: Int) {
        val p = currPage + delta
        showPage(if (p < 0) totalPage - 1 else if (p >= totalPage) 0 else p)
    }

    private fun showPage(page: Int) {
        currPage = page
        for (i in 1..9) {
            val p = page * 9 + i - 1
            val w = if (p >= selectWords.size || selectWords[p] == "*") "" else selectWords[p]
            keys[i].clear()
            keys[i].text = w
            keys[i].enabled = w.isNotEmpty()
        }
        statusText = if (totalPage > 1) "${currPage + 1}/${totalPage}頁" else ""
        key0Label = if (totalPage > 1) "下頁" else ""
    }

    private fun selectWord(slot: Int) {
        val key = currPage * 9 + slot - 1
        if (key >= selectWords.size) return
        val typeWord = selectWords[key]
        if (typeWord.isEmpty()) return

        if (homo) {
            homo = false
            afterHomo = true
            statusPrefix = "同音[$typeWord]"
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
        relateHints = words.filter { it.isNotEmpty() && it != "*" }
    }

    private fun changed() { host?.onStateChanged() }
}
