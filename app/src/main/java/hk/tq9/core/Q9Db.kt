package hk.tq9.core

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.util.Log
import java.io.File
import java.text.BreakIterator
import kotlin.math.ln

/**
 * 字碼資料庫。對應 Windows 版的 Q9Core.cs。
 *
 * 資料庫檔案放喺 filesDir/dataset.db，第一次開機由 assets 複製過去，
 * setting page 可以揀另一個 sqlite file 直接覆蓋（舊版唔會保留）。
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

    /** 同音字：先出聲調都一樣嘅 */
    fun getHomo(word: String): List<String> {
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

        fun file(ctx: Context): File = File(ctx.applicationContext.filesDir, DB_NAME)

        /** 第一次執行：由 assets 複製內置字碼表 */
        fun ensureInstalled(ctx: Context) {
            val f = file(ctx)
            if (f.exists() && f.length() > 0) return
            installFromAssets(ctx)
        }

        fun installFromAssets(ctx: Context) {
            val f = file(ctx)
            f.parentFile?.mkdirs()
            ctx.assets.open(DB_NAME).use { input ->
                f.outputStream().use { out -> input.copyTo(out) }
            }
            Prefs.setDbLabel(ctx, "內置 dataset.db")
        }

        /** setting page 揀咗新 sqlite：直接覆蓋，舊版唔留 */
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
