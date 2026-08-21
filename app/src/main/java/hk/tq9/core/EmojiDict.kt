package hk.tq9.core

import android.content.Context
import android.graphics.Paint
import java.io.BufferedReader

/**
 * `assets/emoji.txt` 嘅 emoji 表，每行 `emoji <TAB> 分類 <TAB> 關鍵字`。
 *
 * 關鍵字係 Unicode 個名（英文）加返常用嗰啲嘅中文，所以打 "cat" 同「貓」都搵得到。
 * 載入嗰陣會用 [Paint.hasGlyph] 篩走部機無字型嘅，唔會出豆腐字。
 */
object EmojiDict {

    class Entry(val emoji: String, val cat: String, val keywords: String)

    val CATEGORIES = listOf(
        "recent" to "最近",
        "face" to "表情",
        "hand" to "手勢",
        "people" to "人物",
        "animal" to "動植物",
        "food" to "食物",
        "activity" to "活動",
        "travel" to "旅遊",
        "object" to "物件",
        "symbol" to "符號",
        "flag" to "旗幟"
    )

    @Volatile private var entries: List<Entry>? = null
    @Volatile private var loading = false

    /** 已經載咗就即刻拎到，未載就 null */
    fun peek(): List<Entry>? = entries

    fun preloadAsync(ctx: Context) {
        if (entries != null || loading) return
        loading = true
        val app = ctx.applicationContext
        Thread {
            runCatching { load(app) }.onSuccess { entries = it }
            loading = false
        }.apply { priority = Thread.MIN_PRIORITY }.start()
    }

    /** 一定要有嗰陣用（會喺呼叫嘅 thread 度載，個 file 得幾十 KB） */
    fun require(ctx: Context): List<Entry> {
        entries?.let { return it }
        val l = runCatching { load(ctx.applicationContext) }.getOrDefault(emptyList())
        entries = l
        return l
    }

    private fun load(ctx: Context): List<Entry> {
        val paint = Paint()
        val out = ArrayList<Entry>(1500)
        ctx.assets.open("emoji.txt").bufferedReader().use { r: BufferedReader ->
            r.forEachLine { line ->
                val a = line.indexOf('\t')
                if (a <= 0) return@forEachLine
                val b = line.indexOf('\t', a + 1)
                if (b <= a) return@forEachLine
                val e = line.substring(0, a)
                if (!paint.hasGlyph(e)) return@forEachLine
                out.add(Entry(e, line.substring(a + 1, b), line.substring(b + 1)))
            }
        }
        return out
    }

    fun byCategory(ctx: Context, cat: String): List<String> {
        if (cat == "recent") return recents(ctx)
        return require(ctx).filter { it.cat == cat }.map { it.emoji }
    }

    /** 搵字：由字頭夾到嗰啲排先，之後先至係中間夾到嘅 */
    fun search(ctx: Context, query: String, limit: Int = 80): List<String> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val head = ArrayList<String>(limit)
        val tail = ArrayList<String>(limit)
        for (e in require(ctx)) {
            val i = e.keywords.indexOf(q)
            if (i < 0) continue
            if (i == 0 || e.keywords[i - 1] == ' ') head.add(e.emoji) else tail.add(e.emoji)
            if (head.size >= limit) break
        }
        return (head + tail).take(limit)
    }

    // ---- 最近用過 ----------------------------------------------------------

    private const val SEP = "\u001F"

    fun recents(ctx: Context): List<String> =
        Prefs.sp(ctx).getString(Prefs.KEY_EMOJI_RECENT, "")!!
            .split(SEP).filter { it.isNotEmpty() }

    fun addRecent(ctx: Context, emoji: String) {
        val list = ArrayList<String>(recents(ctx))
        list.remove(emoji)
        list.add(0, emoji)
        while (list.size > 40) list.removeAt(list.size - 1)
        Prefs.sp(ctx).edit().putString(Prefs.KEY_EMOJI_RECENT, list.joinToString(SEP)).apply()
    }
}
