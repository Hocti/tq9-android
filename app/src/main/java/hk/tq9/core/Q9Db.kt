package hk.tq9.core

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Build
import android.util.Log
import java.io.File
import java.text.BreakIterator
import kotlin.math.ln

/**
 * 字碼資料庫。對應 Windows 版的 Q9Core.cs。
 *
 * 資料庫檔案放喺 filesDir/dataset.db，第一次開機由 assets 複製過去，
 * **裝咗新版 apk 亦都會抄多次**（見 [Q9Db.ensureInstalled]）。
 * setting page 可以揀另一個 sqlite file 直接覆蓋（舊版唔會保留），
 * 換過之後就當係 user 自己嘅嘢，升級唔會再踩親。
 */
class Q9Db private constructor(private val db: SQLiteDatabase) {

    // ---- 基本查詢 --------------------------------------------------------

    /** mapped_table 的一格字碼表，拆成一個個「文字元素」(emoji / 增補字符會計成一個) */
    fun keyInput(id: Int): List<String> {
        val s = scalar("SELECT characters FROM mapped_table WHERE id=?", arrayOf(id.toString()))
            ?: return emptyList()
        return splitGraphemes(s)
    }

    fun hasId(id: Int): Boolean =
        scalar("SELECT 1 FROM mapped_table WHERE id=? AND characters<>''", arrayOf(id.toString())) != null

    /** 關聯字，資料入面用空格分隔 */
    fun getRelate(word: String): List<String> {
        val s = scalar("SELECT candidates FROM related_candidates_table WHERE character=?", arrayOf(word))
            ?: return emptyList()
        return s.split(' ').filter { it.isNotEmpty() }
    }

    /**
     * 同音字表：**先同音、尾近音**。
     *
     *  - 同音（[exactHomo]）＝ `word_meta.ping` 一模一樣，聲調都夾嗰啲行先；
     *  - 近音（[nearHomo]）＝ 拼音「懶音化」之後先至夾（`ngo` ↔ `o`、`naa` ↔ `laa`…），
     *    一律排喺同音之後，唔會擠走本來揀開嗰幾隻字。
     */
    fun getHomo(word: String): List<String> {
        val exact = exactHomo(word)
        return exact + nearHomo(word, exact)
    }

    /** 同音字：先出聲調都一樣嘅 */
    fun exactHomo(word: String): List<String> {
        if (word.codePointCount(0, word.length) != 1) return emptyList()
        val out = ArrayList<String>()
        db.rawQuery(
            "SELECT w1.char FROM word_meta w1 INNER JOIN word_meta w2 ON w1.ping = w2.ping " +
                "WHERE w2.char = ? ORDER BY CASE WHEN w1.ping2 = w2.ping2 THEN 0 ELSE 1 END ASC",
            arrayOf(word)
        ).use { c ->
            while (c.moveToNext()) {
                val s = c.getString(0) ?: continue
                if (s !in out) out.add(s)
            }
        }
        return out
    }

    /**
     * 近音字：拼音行過 [fuzzyPing] 之後撞埋同一組嗰啲 `ping`，**淨係睇 `word_meta.ping`**，
     * 冇任何逐隻字硬寫嘅對應表。例如揀緊「我」（`ngo`）就連 `o` 嗰堆字一齊搵。
     *
     * [skip] 入面嗰啲（＝已經出咗嘅同音字）唔會再出一次。表入面冇 `ping`／舊版
     * dataset.db 查唔到就當冇近音字，唔會累到同音字本身。
     */
    fun nearHomo(word: String, skip: Collection<String> = emptyList()): List<String> {
        if (word.codePointCount(0, word.length) != 1) return emptyList()
        val out = ArrayList<String>()
        runCatching {
            val own = pingsOf(word)
            if (own.isEmpty()) return emptyList()
            // 自己嗰幾個 ping 唔使再搵一次 —— 嗰啲字 [exactHomo] 已經出晒
            val want = LinkedHashSet<String>()
            for (p in own) want.addAll(fuzzyGroups()[fuzzyPing(p)].orEmpty())
            want.removeAll(own)
            if (want.isEmpty()) return emptyList()
            val seen = HashSet(skip)
            seen.add(word)
            db.rawQuery(
                "SELECT char FROM word_meta WHERE ping IN (" + want.joinToString(",") { "?" } + ") " +
                    "AND char <> '' GROUP BY char ORDER BY MAX(freq) DESC",
                want.toTypedArray()
            ).use { c ->
                while (c.moveToNext()) {
                    val s = c.getString(0) ?: continue
                    if (seen.add(s)) out.add(s)
                }
            }
        }.onFailure { Log.w(TAG, "近音字查唔到", it) }
        return out
    }

    private fun pingsOf(word: String): List<String> {
        val out = ArrayList<String>(2)
        db.rawQuery(
            "SELECT DISTINCT ping FROM word_meta WHERE char = ? AND ping IS NOT NULL AND ping <> ''",
            arrayOf(word)
        ).use { c -> while (c.moveToNext()) c.getString(0)?.let { out.add(it) } }
        return out
    }

    /**
     * 模糊音 -> 屬於嗰組嘅 `ping`。成張表得六百幾個 `ping`，開機後查一次就夠。
     */
    private var fuzzy: Map<String, List<String>>? = null

    private fun fuzzyGroups(): Map<String, List<String>> {
        fuzzy?.let { return it }
        val m = HashMap<String, MutableList<String>>(512)
        runCatching {
            db.rawQuery(
                "SELECT DISTINCT ping FROM word_meta WHERE ping IS NOT NULL AND ping <> ''", null
            ).use { c ->
                while (c.moveToNext()) {
                    val p = c.getString(0) ?: continue
                    m.getOrPut(fuzzyPing(p)) { ArrayList(4) }.add(p)
                }
            }
        }.onFailure { Log.w(TAG, "ping 表讀唔到", it) }
        return m.also { fuzzy = it }
    }


    /** 反查一個字嘅字碼（打完同音字之後顯示） */
    fun getCode(word: String): List<Int> {
        val out = ArrayList<Int>()
        db.rawQuery(
            "SELECT id FROM mapped_table WHERE id BETWEEN 10 AND 999 AND INSTR(characters, ?) > 0",
            arrayOf(word)
        ).use { c -> while (c.moveToNext()) out.add(c.getInt(0)) }
        return out
    }

    /**
     * 打咗一兩個碼、仲未夠碼出候選字嗰陣，上面條 bar 出「呢個碼開頭最常用嗰幾隻字」。
     *
     * `word_meta.code` 入面每一個打法都以 `,` 開頭（例如「為」＝ `,470,480,970`），
     * 所以「碼嘅頭幾個數字」＝ 搵 `,` 加住個 prefix 呢段字。打 `4`、`47`、`48`
     * 三個 prefix 都要搵得返「為」，但係 `70` **唔可以**夾到 `,970`（嗰個 `70`
     * 唔係由 `,` 開始），所以個 pattern 一定要連埋粒逗號一齊 LIKE。
     *
     * 同一隻字可能有幾個打法（幾行記錄），所以要 `GROUP BY` 收埋做一個。
     */
    fun topByCodePrefix(prefix: String, limit: Int = 9): List<String> {
        if (prefix.isEmpty() || prefix.any { it !in '0'..'9' }) return emptyList()
        val out = ArrayList<String>(limit)
        runCatching {
            db.rawQuery(
                "SELECT char FROM word_meta WHERE code LIKE ? AND char <> '' " +
                    "GROUP BY char ORDER BY MAX(freq) DESC LIMIT ?",
                arrayOf("%,$prefix%", limit.toString())
            ).use { c ->
                while (c.moveToNext()) c.getString(0)?.let { if (it.isNotEmpty()) out.add(it) }
            }
        }.onFailure { Log.w(TAG, "topByCodePrefix 查唔到", it) }
        return out
    }

    /** 繁轉簡 */
    fun tcsc(input: String): String {
        if (tsMap == null) loadTsMap()
        val m = tsMap ?: return input
        val sb = StringBuilder(input.length)
        var i = 0
        while (i < input.length) {
            val cp = input.codePointAt(i)
            val n = Character.charCount(cp)
            val src = input.substring(i, i + n)
            sb.append(m[src] ?: src)
            i += n
        }
        return sb.toString()
    }

    /**
     * 簡轉繁，**淨係攞嚟查表**（例如攞游標前面隻字去查關聯字）。
     *
     * 簡體字係多對一嘅，反查一定有機會揀錯字，所以出街嘅字一律行 [tcsc]，
     * 呢個反向表唔可以攞去做輸出。開咗「輸出簡體」嗰陣輸入框入面係簡體，
     * 但係 `related_candidates_table` 淨係有正體，唔轉返就成個關聯字功能廢咗。
     */
    fun sctc(input: String): String {
        if (stMap == null) loadTsMap()
        val m = stMap ?: return input
        val sb = StringBuilder(input.length)
        var i = 0
        while (i < input.length) {
            val cp = input.codePointAt(i)
            val n = Character.charCount(cp)
            val src = input.substring(i, i + n)
            sb.append(m[src] ?: src)
            i += n
        }
        return sb.toString()
    }

    private var tsMap: HashMap<String, String>? = null
    private var stMap: HashMap<String, String>? = null

    private fun loadTsMap() {
        val m = HashMap<String, String>(4500)
        val r = HashMap<String, String>(4500)
        runCatching {
            db.rawQuery("SELECT traditional, simplified FROM ts_chinese_table", null).use { c ->
                while (c.moveToNext()) {
                    val t = c.getString(0)
                    val sc = c.getString(1)
                    m[t] = sc
                    // 多對一：頭一個（表入面排先嗰個）當代表，後面嗰啲唔覆蓋
                    if (sc !in r) r[sc] = t
                }
            }
        }.onFailure { Log.w(TAG, "ts_chinese_table 讀唔到", it) }
        tsMap = m
        stMap = r
    }

    // ---- weight：swipe 歧義時用嚟加減可能性 --------------------------------

    /** prefix ("1" / "12" / "123") -> 該 prefix 之下所有字碼 weight 總和 */
    private val prefixWeight = HashMap<String, Double>(1024)
    /** 每個 prefix 長度嘅最大值，用嚟做 normalize */
    private val maxPrefixWeight = DoubleArray(4)

    private fun loadWeights() {
        prefixWeight.clear()
        maxPrefixWeight.fill(0.0)
        db.rawQuery("SELECT id, weight FROM mapped_table WHERE id BETWEEN 10 AND 999", null).use { c ->
            while (c.moveToNext()) {
                val id = c.getInt(0).toString()
                val w = if (c.isNull(1)) 0.0 else c.getDouble(1)
                for (len in 1..id.length) {
                    val p = id.substring(0, len)
                    prefixWeight[p] = (prefixWeight[p] ?: 0.0) + w
                }
            }
        }
        for ((p, w) in prefixWeight) {
            if (w > maxPrefixWeight[p.length]) maxPrefixWeight[p.length] = w
        }
    }

    /**
     * 一個字碼 prefix 有幾「值得信」，回傳 -1f .. +1f。
     * -1 = 完全冇字（多數係誤觸），+1 = 呢個 prefix 下最常用嘅一堆字。
     */
    fun prefixPlausibility(prefix: String): Float {
        if (prefix.isEmpty() || prefix.length > 3) return 0f
        val w = prefixWeight[prefix] ?: return -1f
        val max = maxPrefixWeight[prefix.length]
        if (max <= 0.0) return 0f
        val score = (ln(1.0 + w) / ln(1.0 + max)).coerceIn(0.0, 1.0)
        return (score * 2.0 - 1.0).toFloat()
    }

    fun close() = runCatching { db.close() }

    companion object {
        private const val TAG = "Q9Db"
        const val DB_NAME = "dataset.db"

        /**
         * 一個 `ping` 嘅「模糊音」—— 撞到同一個結果嘅就當近音（見 [nearHomo]）。
         *
         * 呢度**淨係搞拼音串**，同係邊隻字冇關，所以加減規則唔使掂到字表。
         *
         * ## 1. 先夾返同一套拼音
         *
         * `word_meta.ping` 主要係耶魯拼音（`ji`＝之、`yi`＝二、`cheui`＝取），
         * 但係夾雜咗少少粵拼串法嘅冷字（`zi`＝衹、`ci`＝黐、`ceoi`＝綷、`coek`＝焯）。
         * 唔統一嘅話呢啲字連「同音」都撞唔到，所以先將粵拼嗰套搬返做耶魯：
         * `z-`→`j-`、`c-`→`ch-`、`eoi/eon/eot`→`eui/eun/eut`、`oe`→`eu`。
         *
         * ## 2. 跟住先至係懶音／近音
         *
         * | 規則 | 例 |
         * | --- | --- |
         * | `ng-` 聲母脫落 | `ngo` ↔ `o`、`ngai` ↔ `ai` |
         * | `n-` / `l-` 不分 | `naa` ↔ `laa` |
         * | `gw-` / `g-`、`kw-` / `k-` | `gwong` ↔ `gong`、`kwok` ↔ `kok` |
         * | 長短元音 `aa` ↔ `a` | `naa` ↔ `na` |
         * | 鼻音韻尾 `-ng` ↔ `-n` | `sang` ↔ `san` |
         * | 入聲韻尾 `-k` ↔ `-t` | `baak` ↔ `baat` |
         *
         * 淨返個 `ng`／`m`（五、唔嗰啲單鼻音字）唔當佢有聲母，唔會剝到剩返吉。
         */
        fun fuzzyPing(raw: String): String {
            var s = raw.trim().lowercase()
            if (s.isEmpty()) return s

            // ---- 1. 拼音方案統一（粵拼 -> 耶魯）----
            if (s.startsWith("z")) s = "j" + s.substring(1)
            else if (s.startsWith("c") && !s.startsWith("ch")) s = "ch" + s.substring(1)
            s = s.replace("eoi", "eui").replace("eon", "eun").replace("eot", "eut")
                .replace("oe", "eu")

            // ---- 2. 聲母懶音 ----
            if (s.startsWith("ng") && s.length > 2) s = s.substring(2)   // ngo -> o
            else if (s != "ng" && s.startsWith("n") && s.length > 1) s = "l" + s.substring(1)
            if (s.startsWith("gw")) s = "g" + s.substring(2)
            else if (s.startsWith("kw")) s = "k" + s.substring(2)

            // ---- 3. 韻母／韻尾 ----
            s = s.replace("aa", "a")
            if (s.endsWith("ng")) s = s.dropLast(2) + "n"
            if (s.endsWith("k")) s = s.dropLast(1) + "t"

            return EXTRA_GROUP[s] ?: s
        }

        /**
         * 上面啲規則夾唔到、但係 user 想撞埋一齊嗰幾組（一樣係**淨係睇拼音**）：
         * 寫低邊個模糊音併埋落邊個。加多兩組就喺呢度加一行。
         *
         *  - `o` ↔ `a`：「我」（`ngo`→`o`）要搵得返「啊」（`a`）。
         *  - `n` ↔ `m`：淨鼻音字，「五」（`ng`→`n`）同「唔」（`m`）。
         *
         * 併埋去嗰個（value）本身**唔可以再係另一行嘅 key** —— 呢度淨係查一次，
         * 唔會一路跟住條鏈行落去。
         */
        private val EXTRA_GROUP = mapOf(
            "o" to "a",
            "n" to "m",
        )

        fun file(ctx: Context): File = File(ctx.applicationContext.filesDir, DB_NAME)

        /**
         * 第一次執行：由 assets 複製內置字碼表。
         *
         * **裝咗新版 apk 亦都會抄多次**（[Prefs.dbAssetVersion] 對唔上而家個
         * versionCode）—— 以前淨係「檔案存在就唔理」，所以由舊版升上嚟嗰啲人
         * 一世都用緊當初裝機嗰份，新版點改字碼表都冇效。
         *
         * **除非 user 自己揀過 sqlite 換走佢**（[isCustom]）：嗰份係佢自己嘅嘢，
         * 升幾多次版都唔准踩親，要撳設定頁「還原內置字碼表」先返得轉頭。
         */
        fun ensureInstalled(ctx: Context) {
            val f = file(ctx)
            if (!f.exists() || f.length() <= 0) { installFromAssets(ctx); return }
            if (isCustom(ctx)) return
            val v = appVersion(ctx)
            if (v <= 0 || Prefs.dbAssetVersion(ctx) == v) return
            installFromAssets(ctx)
        }

        fun installFromAssets(ctx: Context) {
            val f = file(ctx)
            f.parentFile?.mkdirs()
            ctx.assets.open(DB_NAME).use { input ->
                f.outputStream().use { out -> input.copyTo(out) }
            }
            Prefs.setDbLabel(ctx, Prefs.BUILTIN_DB_LABEL)
            Prefs.setDbCustom(ctx, false)
            Prefs.setDbAssetVersion(ctx, appVersion(ctx))
        }

        /**
         * User 係咪自己換過字碼表。舊版冇記過 [Prefs.dbCustom]，所以連個 label
         * 一齊睇 —— 換過就會寫住嗰個檔案名，唔會係 [Prefs.BUILTIN_DB_LABEL]。
         */
        private fun isCustom(ctx: Context): Boolean =
            Prefs.dbCustom(ctx) || Prefs.dbLabel(ctx) != Prefs.BUILTIN_DB_LABEL

        /** 而家個 apk 嘅 versionCode（問唔到就 `0`，嗰陣當冇升過級，唔會亂咁覆蓋） */
        private fun appVersion(ctx: Context): Long {
            val pi = runCatching {
                ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            }.getOrNull() ?: return 0L
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pi.longVersionCode
                   else @Suppress("DEPRECATION") pi.versionCode.toLong()
        }

        /**
         * setting page 揀咗新 sqlite：直接覆蓋，舊版唔留。
         * 順手記低「呢部機用緊自己嘅字碼表」，之後升級唔會由 assets 抄返過去。
         */
        fun replaceFrom(ctx: Context, uri: Uri): Result<Unit> = runCatching {
            val tmp = File(ctx.cacheDir, "incoming.db")
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { out -> input.copyTo(out) }
            } ?: error("無法開啟所選檔案")
            validate(tmp)
            val target = file(ctx)
            if (target.exists()) target.delete()
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
            Prefs.setDbCustom(ctx, true)
        }

        private fun validate(f: File) {
            val d = SQLiteDatabase.openDatabase(f.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            d.use {
                val need = listOf("mapped_table", "related_candidates_table", "ts_chinese_table", "word_meta")
                val have = HashSet<String>()
                it.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null).use { c ->
                    while (c.moveToNext()) have.add(c.getString(0))
                }
                val missing = need.filter { t -> t !in have }
                require(missing.isEmpty()) { "不是有效的九万資料庫，缺少：" + missing.joinToString(", ") }
            }
        }

        fun open(ctx: Context): Q9Db {
            ensureInstalled(ctx)
            val d = SQLiteDatabase.openDatabase(
                file(ctx).absolutePath, null,
                SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
            )
            return Q9Db(d).also { it.loadWeights() }
        }

        /** 把字串拆成 grapheme cluster（等同 C# 的 StringInfo） */
        fun splitGraphemes(s: String): List<String> {
            val it = BreakIterator.getCharacterInstance()
            it.setText(s)
            val out = ArrayList<String>(s.length)
            var start = it.first()
            var end = it.next()
            while (end != BreakIterator.DONE) {
                val piece = s.substring(start, end)
                if (piece.isNotEmpty()) out.add(piece)
                start = end
                end = it.next()
            }
            return out
        }
    }

    private fun scalar(sql: String, args: Array<String>): String? {
        db.rawQuery(sql, args).use { c ->
            if (!c.moveToFirst()) return null
            if (c.isNull(0)) return null
            return c.getString(0)
        }
    }
}
