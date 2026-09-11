package tt.ime.riverine.core

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

    /**
     * 長撳「表情」彈出嚟嗰行速選（見 `ime/QuickEmoji.kt`）：**最近用過嗰啲排先**，
     * 唔夠 [limit] 個就由 [COMMON] 補返尾。
     *
     * 特登唔查 `emoji.txt`（[require]）—— 長撳要即刻彈得出，唔可以喺嗰一刻先載成個表；
     * [COMMON] 得十個，逐個問 [Paint.hasGlyph] 都係一眨眼嘅事，而且問完就記住。
     */
    fun quick(ctx: Context, limit: Int): List<String> {
        val out = ArrayList<String>(limit)
        for (e in recents(ctx)) {
            if (out.size >= limit) return out
            out.add(e)
        }
        for (e in common()) {
            if (out.size >= limit) break
            if (e !in out) out.add(e)
        }
        return out
    }

    /**
     * 一部新機未用過 emoji 嗰陣，[quick] 攞嚟頂住嘅一批（次序 = 全世界用得最多嗰幾個）。
     * 用過幾個之後就會俾「最近用過」逐個擠走。
     */
    private val COMMON = listOf("\uD83D\uDE02", "\u2764\uFE0F", "\uD83D\uDC4D", "\uD83D\uDE0D",
        "\uD83D\uDE2D", "\uD83D\uDE4F", "\uD83D\uDE0A", "\uD83D\uDD25", "\uD83C\uDF89", "\uD83D\uDE05")

    @Volatile private var commonOk: List<String>? = null

    /** [COMMON] 篩走部機冇字型嗰啲（同 [load] 一樣唔好出豆腐字），篩完記住 */
    private fun common(): List<String> = commonOk ?: run {
        val paint = Paint()
        val l = COMMON.filter { paint.hasGlyph(it) }
        commonOk = l
        l
    }

    fun addRecent(ctx: Context, emoji: String) {
        val list = ArrayList<String>(recents(ctx))
        list.remove(emoji)
        list.add(0, emoji)
        while (list.size > 40) list.removeAt(list.size - 1)
        Prefs.sp(ctx).edit().putString(Prefs.KEY_EMOJI_RECENT, list.joinToString(SEP)).apply()
    }
}
