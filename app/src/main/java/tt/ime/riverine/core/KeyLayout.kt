package tt.ime.riverine.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 中文鍵盤**可以自由擺位嗰啲鍵**而家點排（設定頁「按鍵排位」個拖放介面砌出嚟嘅嘢）。
 *
 * ```
 *  左欄            九宮格           右欄
 *  [left 0]      7  8  9        [right 0]
 *  [left 1]      4  5  6        [right 1]
 *  [left 2]      1  2  3        [right 2]
 *  [left 3]   [ 0  0 ] 取消      [right 3]
 *
 *  工具列（鍵盤上面條 bar 嘅「工具」嗰段）：[tools 0][tools 1]…
 * ```
 *
 * 九宮格 `1`~`9`、兩格闊嗰粒 `0`、同「取消」**唔喺呢度** —— 嗰幾粒就係三三本身
 * 嘅打法，郁咗就唔係三三（見 `AGENTS.md`「九宮格排位是 numpad」）。
 *
 * 每個位分**短撳**同**長撳**兩格（[Slot]），工具列每粒掣一樣有埋長撳。
 * 個排位一定係**有效**嘅（見 [checkDrop]）：拖放介面每一下都會先問過先至擺得落，
 * 擺唔到嗰啲根本 drop 唔到，所以讀出嚟之後唔使再驗一次。真係讀到爛嘢
 * （手改過個 xml、將來降返舊版…）就整段跌返 [DEFAULT]，唔會出半個殘廢鍵盤。
 */
object KeyLayout {

    /** 左欄／右欄各四個位（跟足九宮格四行） */
    const val SIDE_ROWS = 4

    /**
     * 工具列最多擺幾多粒。
     *
     * 夠八粒之後就算條 bar 捲得，都已經冇人記得住邊粒喺邊 —— 而且工具列
     * 本來就係「唔常用先入嚟撳」嗰啲嘢，唔應該做到似第二個鍵盤。
     */
    const val MAX_TOOLS = 8

    /**
     * **工具列冇長撳，淨係短撳**（2026-09-09 user 要求）。
     *
     * 條 bar 嗰幾粒本身就有自己嘅撳實動作 —— 「貼上」撳實開剪貼簿歷史、
     * 🎤 撳實一路錄、「改變大小」撳實一下子拉到最闊 —— 再畀人配多個長撳落去
     * 就一定撞。所以 [Layout.tools] 嗰啲 [Slot] 個 `long` 永遠係
     * [PadFunc.NONE]（[normalise] 夾硬清），設定頁亦都冇長撳嗰行。
     */
    const val TOOLS_HAVE_LONG = false

    /**
     * 一個位：短撳做乜、長撳做乜。
     *
     * [long] 存住嘅係 user 揀過乜，**但唔一定作得準** —— [tap] 係 `␣`／`⌫`／`⏎`
     * 嗰類（[PadFunc.tapOnly]）嗰陣長撳一律熄咗，睇 [effectiveLong]。
     */
    data class Slot(val tap: PadFunc = PadFunc.NONE, val long: PadFunc = PadFunc.NONE) {
        /** 真正生效嘅長撳（[PadFunc.tapOnly] 嗰幾粒一律冇長撳，見 [PadFunc.tapOnly]） */
        val effectiveLong: PadFunc get() = if (tap.tapOnly) PadFunc.NONE else long

        /** 呢個位而家用咗邊幾個功能（數重複、數「有冇齊必用鍵」都靠佢） */
        fun funcs(): List<PadFunc> =
            listOf(tap, effectiveLong).filter { it != PadFunc.NONE }
    }

    data class Layout(
        val left: List<Slot>,
        val right: List<Slot>,
        val tools: List<Slot>,
    ) {
        /** 左欄＋右欄嘅八個位（拖放介面同重複檢查都當佢哋係同一個範圍） */
        val side: List<Slot> get() = left + right

        fun sideFuncs(): List<PadFunc> = side.flatMap { it.funcs() }
        fun toolFuncs(): List<PadFunc> = tools.flatMap { it.funcs() }
    }

    /** 拖放介面入面一格嘅地址 */
    enum class Area { LEFT, RIGHT, TOOLS }

    data class Cell(val area: Area, val index: Int, val long: Boolean)

    /**
     * 預設排位 = **2026-09-09 之前寫死嗰個**，一粒都冇郁位。
     *
     * 即係話裝完個 app 唔去掂設定頁，個鍵盤同以前一模一樣；設定頁嗰粒
     * 「還原預設排位」撳落去亦都係返呢個樣。四個舊「可揀功能」位嘅預設值
     * 亦都一模一樣（左上短撳＝關聯字、左上長撳＝貼上、同音長撳＝速選字、
     * 右上長撳＝錄音），長撳 `Eng` 就照 [EngLongPress.NEXT_IME] 做「下一個輸入法」。
     */
    val DEFAULT = Layout(
        left = listOf(
            Slot(PadFunc.RELATE, PadFunc.PASTE),
            Slot(PadFunc.HOMO, PadFunc.SHORTCUT),
            Slot(PadFunc.TO_SYMBOL, PadFunc.TO_NUMBER),
            Slot(PadFunc.TO_LATIN, PadFunc.IME_NEXT),
        ),
        right = listOf(
            Slot(PadFunc.BAR_SWITCH, PadFunc.STT),
            Slot(PadFunc.SPACE),
            Slot(PadFunc.BACKSPACE),
            Slot(PadFunc.ENTER),
        ),
        tools = listOf(
            Slot(PadFunc.ALIGN),
            Slot(PadFunc.PASTE),
            Slot(PadFunc.STT),
            Slot(PadFunc.EMOJI),
            Slot(PadFunc.AI),
        ),
    )

    /**
     * Pool（拖放介面下面成堆掣）有邊啲。
     *
     * [PadFunc.NONE] 唔喺度 —— 想清走一個位就將佢**拖返落 pool**，
     * 唔使再喺一堆掣入面搵粒「停用」。
     */
    val POOL: List<PadFunc> = PadFunc.entries.filter { it != PadFunc.NONE }

    // ---- 讀／寫 -----------------------------------------------------------

    fun load(ctx: Context): Layout {
        val raw = Prefs.sp(ctx).getString(Prefs.KEY_LAYOUT, null)
        // 未存過（舊裝機第一次行新版）：由五個舊 pref 砌返個一模一樣嘅排位
        if (raw.isNullOrBlank()) return fromLegacyPrefs(ctx)
        return runCatching { parse(JSONObject(raw)) }.getOrDefault(DEFAULT)
    }

    fun save(ctx: Context, l: Layout) {
        Prefs.sp(ctx).edit().putString(Prefs.KEY_LAYOUT, toJson(l).toString()).apply()
    }

    /** 撳「還原預設排位」：索性剷走個 key，之後就一路讀 [DEFAULT] */
    fun reset(ctx: Context) {
        Prefs.sp(ctx).edit().putString(Prefs.KEY_LAYOUT, toJson(DEFAULT).toString()).apply()
    }

    /**
     * 舊版五個 pref → 新排位。
     *
     * 舊版得四個位揀得（左上短／長撳、同音長撳、右上長撳）＋長撳 `Eng`，
     * 其餘全部寫死，所以由 [DEFAULT] 起，將嗰五格換返做 user 揀過嘅嘢就啱晒。
     * 換完可能會撞（例如四個位都揀咗「貼上」—— 舊版特登准），所以最後行一次
     * [dedupSide] 清走重複，唔係個新排位一開就係無效狀態。
     */
    private fun fromLegacyPrefs(ctx: Context): Layout {
        fun legacy(key: String): PadFunc = Prefs.funcSlot(ctx, key)
        val engLong = when (Prefs.engLongPress(ctx)) {
            EngLongPress.NEXT_IME -> PadFunc.IME_NEXT
            EngLongPress.PICKER -> PadFunc.IME_PICKER
        }
        val left = listOf(
            Slot(legacy(Prefs.KEY_TL_TAP), legacy(Prefs.KEY_TL_LONG)),
            Slot(PadFunc.HOMO, legacy(Prefs.KEY_HOMO_LONG)),
            DEFAULT.left[2],
            Slot(PadFunc.TO_LATIN, engLong),
        )
        val right = DEFAULT.right.toMutableList()
        right[0] = Slot(PadFunc.BAR_SWITCH, legacy(Prefs.KEY_TR_LONG))
        return dedupSide(Layout(left, right, DEFAULT.tools))
    }

    /**
     * 左右欄唔准有重複功能（舊版准，見 [fromLegacyPrefs]）。由頭行到尾，
     * **第一次見到嗰個位留低**，之後撞返同一個功能嘅就吉咗佢。
     */
    private fun dedupSide(l: Layout): Layout {
        val seen = HashSet<PadFunc>()
        fun fix(slots: List<Slot>) = slots.map { s ->
            val tap = if (s.tap == PadFunc.NONE || seen.add(s.tap)) s.tap else PadFunc.NONE
            val long =
                if (s.long == PadFunc.NONE || seen.add(s.long)) s.long else PadFunc.NONE
            Slot(tap, long)
        }
        return l.copy(left = fix(l.left), right = fix(l.right))
    }

    // ---- JSON -------------------------------------------------------------

    private fun toJson(l: Layout) = JSONObject().apply {
        put("left", slotsJson(l.left))
        put("right", slotsJson(l.right))
        put("tools", slotsJson(l.tools))
    }

    private fun slotsJson(slots: List<Slot>) = JSONArray().apply {
        for (s in slots) put(JSONObject().apply {
            put("tap", s.tap.name)
            put("long", s.long.name)
        })
    }

    /**
     * 讀返個 JSON。任何一格認唔到就當 [PadFunc.NONE]（例如將來刪咗個功能，
     * 或者由新版降返舊版），**唔會掟 exception** —— 除非連個 array 長度都
     * 對唔上，嗰陣 [load] 個 `runCatching` 會接住，成段跌返 [DEFAULT]。
     */
    private fun parse(o: JSONObject): Layout {
        val left = parseSlots(o.getJSONArray("left"))
        val right = parseSlots(o.getJSONArray("right"))
        val tools = parseSlots(o.optJSONArray("tools") ?: JSONArray())
        require(left.size == SIDE_ROWS && right.size == SIDE_ROWS)
        return Layout(left, right, tools.take(MAX_TOOLS))
    }

    private fun parseSlots(a: JSONArray): List<Slot> = (0 until a.length()).map { i ->
        val o = a.getJSONObject(i)
        Slot(func(o.optString("tap")), func(o.optString("long")))
    }

    private fun func(name: String?): PadFunc =
        PadFunc.entries.firstOrNull { it.name == name } ?: PadFunc.NONE

    // ---- 規矩 -------------------------------------------------------------

    /**
     * 將 [f] 擺落 [cell] 得唔得？得就回 `null`，唔得就回**解釋緊點解**嗰句字
     * （拖放介面會照原句 toast 出嚟）。
     *
     * [from] = 由邊格拖過嚟（由 pool 拖就係 `null`）。同一格拖返自己度、或者
     * 兩格對調呢啲情況，重複檢查要**當嗰格已經吐返個功能出嚟**，唔係就會
     * 自己撞自己。
     *
     * 五條規矩，同 `PadFunc` 嗰邊嘅 doc 一一對應：
     *
     *  0. 工具列冇長撳（[TOOLS_HAVE_LONG]）
     *  1. 擺得邊（[PadFunc.place]）—— `⏎` 唔可以入工具列、「改變大小」唔可以出鍵盤
     *  2. `␣`／`⌫`／`⏎`（[PadFunc.tapOnly]）淨係擺得落短撳嗰行。擺落一個
     *     本來有長撳嘅位係**准嘅**，嗰個長撳會俾 [normalise] 清走 ——
     *     「擺咗之後個長撳熄咗」本來就係呢類鍵嘅規矩，唔使叫人手動清一次先
     *  3. 左右欄八個位入面唔准有重複；工具列自己都唔准 —— 但兩邊各自計，
     *     所以「貼上」同時擺喺工具列同左欄係得嘅
     *  4. 必用鍵（[PadFunc.required]）唔准喺左右欄消失
     *  5. 短撳嗰格吉咗，長撳嗰格就唔可以有嘢（粒鍵本身都冇，長撳邊個？）
     */
    fun checkDrop(l: Layout, cell: Cell, f: PadFunc, from: Cell? = null): String? {
        val toTools = cell.area == Area.TOOLS
        if (toTools && cell.long) return "工具列沒有長按。"
        if (f != PadFunc.NONE) {
            if (toTools && !f.toolOk) return "「${f.label}」不能放在工具列，只能放在鍵盤左右兩欄。"
            if (!toTools && !f.sideOk) return "「${f.label}」只能放在工具列。"
            if (cell.long && f.tapOnly) return "「${f.label}」只能放在短按那一行。"
            val dup = duplicate(l, cell, f, from)
            if (dup) return "「${f.label}」已經在${if (toTools) "工具列" else "左右兩欄"}用了，不能重複。"
            // 規矩 5：粒鍵本身都吉住，長撳邊個？
            if (cell.long && at(l, cell.copy(long = false)) == PadFunc.NONE)
                return "這個位置的短按還是空的，要先放一個按鍵，才能設定它的長按。"
        }
        // 拖走／覆蓋之後仲有冇齊必用鍵
        val after = apply(l, cell, f, from)
        missingRequired(after)?.let {
            return "「${it.label}」是必用按鍵，一定要留在鍵盤左右兩欄。"
        }
        return null
    }

    private fun duplicate(l: Layout, cell: Cell, f: PadFunc, from: Cell?): Boolean {
        val used = ArrayList<PadFunc>()
        val scope = if (cell.area == Area.TOOLS) listOf(Area.TOOLS)
                    else listOf(Area.LEFT, Area.RIGHT)
        for (a in scope) for ((i, s) in slots(l, a).withIndex()) {
            for (isLong in listOf(false, true)) {
                val here = Cell(a, i, isLong)
                // 目標格自己、同埋「拖走咗」嗰格，都當佢哋而家係吉嘅
                if (here == cell || here == from) continue
                val v = if (isLong) s.effectiveLong else s.tap
                if (v != PadFunc.NONE) used.add(v)
            }
        }
        return f in used
    }

    /**
     * 左右欄入面擺住 [tap] 嗰粒鍵，佢個長撳係乜（搵唔到嗰粒鍵就回 [PadFunc.NONE]）。
     *
     * 純數字鍵盤粒 `Eng` 攞嚟**跟返中文九宮格嗰粒**（見 `NumberPadView.toLatin`）——
     * user 喺「按鍵排位」度將 `Eng` 個長撳改做「彈輸入法選擇表」，
     * 兩頁應該一齊變，唔會一頁一個樣。
     */
    fun longFor(l: Layout, tap: PadFunc): PadFunc =
        l.side.firstOrNull { it.tap == tap }?.effectiveLong ?: PadFunc.NONE

    /** 邊個必用鍵而家唔喺左右欄（冇就回 `null`） */
    fun missingRequired(l: Layout): PadFunc? {
        val have = l.sideFuncs().toSet()
        return PadFunc.entries.firstOrNull { it.required && it !in have }
    }

    /**
     * 真係擺落去（**唔會驗**，驗係 [checkDrop] 嘅事）。
     *
     * [from] 有值就係「格對格」：兩格**對調**，唔係複製 ——
     * 由 pool 拖上去先至係複製（pool 嗰堆掣拖極都唔會少，見設定頁）。
     * 對調之後如果邊一格違返規矩（例如將 `⏎` 掉咗去長撳行），
     * 嗰格就吉咗，唔會夾硬擺個擺唔得嘅嘢落去。
     */
    fun apply(l: Layout, cell: Cell, f: PadFunc, from: Cell? = null): Layout {
        var out = set(l, cell, f)
        if (from != null && from != cell) out = set(out, from, legal(at(l, cell), from))
        return normalise(out)
    }

    /** 對調返轉頭嗰半格：擺唔落就吉咗（見 [apply]） */
    private fun legal(f: PadFunc, cell: Cell): PadFunc = when {
        f == PadFunc.NONE -> f
        cell.area == Area.TOOLS && !f.toolOk -> PadFunc.NONE
        cell.area != Area.TOOLS && !f.sideOk -> PadFunc.NONE
        cell.long && f.tapOnly -> PadFunc.NONE
        else -> f
    }

    fun at(l: Layout, c: Cell): PadFunc {
        val s = slots(l, c.area).getOrNull(c.index) ?: return PadFunc.NONE
        return if (c.long) s.long else s.tap
    }

    private fun slots(l: Layout, a: Area): List<Slot> = when (a) {
        Area.LEFT -> l.left
        Area.RIGHT -> l.right
        Area.TOOLS -> l.tools
    }

    private fun set(l: Layout, c: Cell, f: PadFunc): Layout {
        val cur = slots(l, c.area)
        if (c.index !in cur.indices) return l
        val out = cur.toMutableList()
        val s = out[c.index]
        out[c.index] = if (c.long) s.copy(long = f) else s.copy(tap = f)
        return when (c.area) {
            Area.LEFT -> l.copy(left = out)
            Area.RIGHT -> l.copy(right = out)
            Area.TOOLS -> l.copy(tools = out)
        }
    }

    /**
     * 執手尾：短撳吉咗嘅位唔可以淨係得個長撳（規矩 5），工具列亦都唔留吉格
     * （條 bar 出個窿好核突，其餘幾粒自動攤開就得）。
     */
    private fun normalise(l: Layout): Layout {
        fun fix(s: Slot) = when {
            s.tap == PadFunc.NONE -> Slot()
            // `␣`／`⌫`／`⏎` 擺咗落嚟就冇長撳，順手連**存住嗰個**都清埋，
            // 唔好留住個作唔到準嘅值喺個 JSON 度誤導人
            s.tap.tapOnly -> Slot(s.tap)
            else -> s
        }
        return l.copy(
            left = l.left.map(::fix),
            right = l.right.map(::fix),
            // 工具列冇長撳（[TOOLS_HAVE_LONG]），順手連舊排位存落嘅都清走
            tools = l.tools.map(::fix).filter { it.tap != PadFunc.NONE }.map { Slot(it.tap) },
        )
    }

    /**
     * 工具列尾加一粒（拖去最後嗰個「＋」格）。滿咗就唔加，回返原本個排位。
     *
     * [from] 有值（由第二格拖過嚟，唔係由 pool）就順手清走嗰格 ——
     * 尾格冇嘢可以同佢對調，所以係「搬過去」唔係「調位」。
     */
    fun appendTool(l: Layout, f: PadFunc, from: Cell? = null): Layout {
        if (l.tools.size >= MAX_TOOLS || !f.toolOk) return l
        val base = if (from == null) l else normalise(set(l, from, PadFunc.NONE))
        return normalise(base.copy(tools = base.tools + Slot(f)))
    }

    /** 拖返落 pool = 清走嗰格（工具列嗰粒就成粒刪咗，見 [normalise]） */
    fun clear(l: Layout, c: Cell): Layout = normalise(set(l, c, PadFunc.NONE))
}
