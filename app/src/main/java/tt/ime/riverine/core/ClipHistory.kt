package tt.ime.riverine.core

import android.content.ClipboardManager
import android.content.Context
import org.json.JSONArray

/**
 * 剪貼簿歷史。Android 10 之後普通 app 冇 focus 就讀唔到 clipboard，
 * 但係「而家用緊嗰個輸入法」係例外，所以 IME 一路開住就收得到。
 */
object ClipHistory {

    const val MAX = 30

    fun list(ctx: Context): List<String> {
        val raw = Prefs.sp(ctx).getString(Prefs.KEY_CLIP_HISTORY, null) ?: return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optString(it, null) }
            .filter { it.isNotEmpty() }
    }

    /** 加喺最前，重複嗰個會提返上嚟 */
    fun add(ctx: Context, text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        val cur = ArrayList(list(ctx))
        cur.remove(t)
        cur.add(0, t)
        while (cur.size > MAX) cur.removeAt(cur.size - 1)
        save(ctx, cur)
    }

    fun remove(ctx: Context, text: String) {
        val cur = ArrayList(list(ctx))
        if (cur.remove(text)) save(ctx, cur)
    }

    fun clear(ctx: Context) = save(ctx, emptyList())

    private fun save(ctx: Context, items: List<String>) {
        val arr = JSONArray()
        for (s in items) arr.put(s)
        Prefs.sp(ctx).edit().putString(Prefs.KEY_CLIP_HISTORY, arr.toString()).apply()
    }

    /** 而家 clipboard 入面嗰段字（順手記入歷史） */
    fun current(ctx: Context): String {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return ""
        val clip = runCatching { cm.primaryClip }.getOrNull() ?: return ""
        if (clip.itemCount == 0) return ""
        val s = runCatching { clip.getItemAt(0).coerceToText(ctx).toString() }.getOrDefault("")
        if (s.isNotEmpty()) add(ctx, s)
        return s
    }
}
