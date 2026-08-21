package hk.tq9.ime

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint

/** assets/img 入面嘅九方筆形提示圖，載入之後 cache 住 */
object StrokeImages {

    private val cache = HashMap<String, Bitmap?>()
    private var inverted = false

    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { alpha = 90 }

    /** 深色主題要反白，唔係黑色筆畫會睇唔到 */
    fun configure(dark: Boolean) {
        if (inverted == dark) return
        inverted = dark
        val f = if (dark) ColorMatrixColorFilter(
            ColorMatrix(floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f
            ))
        ) else null
        paint.colorFilter = f
        dimPaint.colorFilter = f
    }

    @Synchronized
    fun get(ctx: Context, name: String): Bitmap? {
        cache[name]?.let { return it }
        if (cache.containsKey(name)) return null
        val bm = runCatching {
            ctx.applicationContext.assets.open("img/$name.png").use { BitmapFactory.decodeStream(it) }
        }.getOrNull()
        cache[name] = bm
        return bm
    }

    fun preload(ctx: Context) {
        // 只需要 0_x（首頁）同 1_x~9_x（第二碼提示）
        for (i in 0..9) for (j in 1..9) get(ctx, "${i}_$j")
    }
}
