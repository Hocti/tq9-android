package hk.tq9.core

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.concurrent.Executors

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
    }
}
