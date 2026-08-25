package hk.tq9.core

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 用戶自己嘅打字習慣：連續兩個中文字（bigram）用咗幾多次、同每隻字打咗幾多次。
 *
 * 同 `dataset.db` 分開存喺另一個 sqlite 檔（`usage_stats.db`）——
 * 嗰個係字碼表，user 換走佢唔應該累埋呢啲統計；呢個先係真係跟住呢部機、
 * 跟住呢個 user 嘅嘢，換字碼表都唔會冇咗。
 *
 * 讀：一開始整個表載晒入 memory（HashMap），之後淨係查 memory，唔會逐次揀 sqlite。
 * 寫：memory 即刻更新（下一次查就用得到），sqlite 嗰邊擺去背景 thread 慢慢寫，
 * 唔會拖慢緊住打緊字嘅 UI thread。
 */
class UsageStats private constructor(ctx: Context) :
    SQLiteOpenHelper(ctx.applicationContext, DB_NAME, null, VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE bigram (pair TEXT PRIMARY KEY, count INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE char_freq (ch TEXT PRIMARY KEY, count INTEGER NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    private val bigramCache = HashMap<String, Int>()
    private val charCache = HashMap<String, Int>()
    @Volatile private var loaded = false

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "tq9-usage").apply { priority = Thread.MIN_PRIORITY }
    }

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            runCatching {
                readableDatabase.rawQuery("SELECT pair, count FROM bigram", null).use { c ->
                    while (c.moveToNext()) bigramCache[c.getString(0)] = c.getInt(1)
                }
                readableDatabase.rawQuery("SELECT ch, count FROM char_freq", null).use { c ->
                    while (c.moveToNext()) charCache[c.getString(0)] = c.getInt(1)
                }
            }
            loaded = true
        }
    }

    /** 打過幾多次某個 bigram（連續兩個中文字嘅組合） */
    fun bigramCount(pair: String): Int { ensureLoaded(); return bigramCache[pair] ?: 0 }

    /** 打過幾多次某隻字 */
    fun charFreq(ch: String): Int { ensureLoaded(); return charCache[ch] ?: 0 }

    fun bumpBigram(pair: String) = bump(pair, bigramCache) { p, n ->
        writableDatabase.execSQL("INSERT OR REPLACE INTO bigram(pair, count) VALUES(?, ?)", arrayOf(p, n))
    }

    fun bumpChar(ch: String) = bump(ch, charCache) { c, n ->
        writableDatabase.execSQL("INSERT OR REPLACE INTO char_freq(ch, count) VALUES(?, ?)", arrayOf(c, n))
    }

    private fun bump(key: String, cache: HashMap<String, Int>, persist: (String, Any) -> Unit) {
        ensureLoaded()
        val n = (cache[key] ?: 0) + 1
        cache[key] = n
        io.execute { runCatching { persist(key, n) } }
    }

    companion object {
        private const val DB_NAME = "usage_stats.db"
        private const val VERSION = 1

        /** 匯入嗰陣要有齊呢兩張表先當係有效嘅使用記錄檔 */
        private val TABLES = listOf("bigram", "char_freq")

        @Volatile private var instance: UsageStats? = null

        fun get(ctx: Context): UsageStats {
            instance?.let { return it }
            synchronized(this) {
                instance?.let { return it }
                val created = UsageStats(ctx.applicationContext)
                instance = created
                created.io.execute { created.ensureLoaded() } // 偷偷背景載，唔阻住第一次真正查詢
                return created
            }
        }

        fun file(ctx: Context): File = ctx.applicationContext.getDatabasePath(DB_NAME)

        /** 設定頁攞嚟寫「目前記錄：X 個字、Y 組前後組合」 */
        fun counts(ctx: Context): Pair<Int, Int> {
            val u = get(ctx)
            u.ensureLoaded()
            return u.charCache.size to u.bigramCache.size
        }

        /**
         * 匯出而家個 `usage_stats.db`（設定頁用 SAF 揀檔案）。
         *
         * 一定要先 [closeSync] —— 背景 thread 排緊嘅寫未落到 sqlite、WAL 又未
         * checkpoint 嘅話，抄出去嗰份會少咗最後嗰幾下。
         */
        fun exportTo(ctx: Context, out: OutputStream): Result<Unit> = runCatching {
            closeSync()
            val f = file(ctx)
            require(f.exists() && f.length() > 0) { "尚未有任何使用記錄" }
            f.inputStream().use { it.copyTo(out) }
        }

        /** 匯入：驗到有齊 [TABLES] 先至覆蓋（舊嗰份唔會保留，同換字碼庫一樣） */
        fun importFrom(ctx: Context, input: InputStream): Result<Unit> = runCatching {
            val tmp = File(ctx.cacheDir, "incoming_usage.db")
            tmp.outputStream().use { input.copyTo(it) }
            try {
                validate(tmp)
                closeSync()
                val target = file(ctx)
                target.parentFile?.mkdirs()
                // 舊 db 嘅 -wal / -shm 唔清，下次開返會攞住舊 WAL 蓋返落新 db 度
                deleteDbFiles(target)
                tmp.copyTo(target, overwrite = true)
            } finally {
                tmp.delete()
            }
        }

        /** 清走所有記錄（等於換返一個新表：下次 [get] 會由 [onCreate] 重新起） */
        fun clear(ctx: Context): Result<Unit> = runCatching {
            closeSync()
            deleteDbFiles(file(ctx))
        }

        /**
         * 關咗個 helper，順手等埋背景 thread 排緊嗰啲寫。
         * `instance` 清走之後，下次 [get] 會重新開返個檔（匯入／清除完照用得）。
         */
        private fun closeSync() {
            val inst = synchronized(this) { instance.also { instance = null } } ?: return
            val done = CountDownLatch(1)
            // 排喺 io queue 最後，即係前面嗰啲 bump 一定寫完先 close
            runCatching { inst.io.execute { runCatching { inst.close() }; done.countDown() } }
                .onFailure { runCatching { inst.close() }; return }
            runCatching { done.await(2, TimeUnit.SECONDS) }
        }

        private fun deleteDbFiles(f: File) {
            f.delete()
            File(f.path + "-wal").delete()
            File(f.path + "-shm").delete()
            File(f.path + "-journal").delete()
        }

        private fun validate(f: File) {
            val d = SQLiteDatabase.openDatabase(f.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            d.use {
                val have = HashSet<String>()
                it.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null).use { c ->
                    while (c.moveToNext()) have.add(c.getString(0))
                }
                val missing = TABLES.filter { t -> t !in have }
                require(missing.isEmpty()) {
                    "不是有效的使用記錄檔，缺少：" + missing.joinToString(", ")
                }
            }
        }
    }
}
