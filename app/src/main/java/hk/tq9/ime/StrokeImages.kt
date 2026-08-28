package hk.tq9.ime

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import java.io.File
import kotlin.math.roundToInt

/**
 * 九方筆形提示圖。
 *
 * 以前係 `assets/img/` 入面 90 個獨立 png（`0_1` ～ `9_9`），開機要逐個
 * decode。而家改成**一幅** sprite sheet（[ASSET]）：橫 9 直 10，
 * 左上角 = `0_1`、右上 = `0_9`、左下 = `9_1`、右下 = `9_9`
 * ——即係**第一個數字 = 第幾行（0 起）**、**第二個數字 = 第幾列（1 起）**。
 * 畫嗰陣直接由 sheet 度攞個 source rect 出嚟 [Canvas.drawBitmap]，
 * 唔會真係 crop 90 個 Bitmap 出嚟（慳一份 memory，亦都冇 90 次 decode）。
 *
 * User 喺設定頁換得呢幅圖（同換 `dataset.db` 一樣）：換咗嗰幅擺喺
 * `filesDir/`[FILE]，個檔案喺度就用佢，撳「還原內置圖片」就係刪咗佢。
 * 所以升級換咗新嘅內置圖亦都即刻生效，唔使似 db 咁記 versionCode。
 */
object StrokeImages {

    /** sheet 橫向幾多格（第二個數字 `1`～`9`） */
    const val COLS = 9
    /** sheet 直向幾多格（第一個數字 `0`～`9`） */
    const val ROWS = 10

    /** 內置嗰幅（assets） */
    const val ASSET = "default90.png"
    /** User 自己換嗰幅擺喺 `filesDir` 邊個檔名 */
    const val FILE = "strokes.png"

    /**
     * 太大幅嘅圖 decode 落嚟會食爆 memory（IME process 個 heap 好細），
     * 所以長闊夾硬壓落 2400 以內先 —— 一格都仲有 250px 咁大，夠晒用。
     */
    private const val MAX_SIDE = 2400

    private var sheet: Bitmap? = null
    private var loaded = false
    private var inverted = false

    private val src = Rect()

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

    /** User 換咗嗰幅擺喺邊（檔案唔存在 = 而家用緊內置嗰幅） */
    fun file(ctx: Context): File = File(ctx.applicationContext.filesDir, FILE)

    fun isCustom(ctx: Context): Boolean = file(ctx).let { it.exists() && it.length() > 0 }

    /**
     * 攞成幅 sheet（load 一次就 cache 住）。
     * 自訂嗰幅爛咗／唔見咗都唔可以死，跌返落內置嗰幅。
     */
    @Synchronized
    fun sheet(ctx: Context): Bitmap? {
        if (loaded) return sheet
        loaded = true
        val app = ctx.applicationContext
        val f = file(app)
        if (f.exists() && f.length() > 0) {
            sheet = runCatching { decodeFile(f) }.getOrNull()
        }
        if (sheet == null) {
            sheet = runCatching {
                app.assets.open(ASSET).use { BitmapFactory.decodeStream(it) }
            }.getOrNull()
        }
        return sheet
    }

    /**
     * 換咗圖之後要行呢句先見到新嘅。
     *
     * **唔 recycle 舊嗰幅** —— 鍵盤可能同一時間畫緊佢，recycle 咗就即刻閃退，
     * 放手交返俾 GC 執手尾就夠。
     */
    @Synchronized
    fun reload() {
        sheet = null
        loaded = false
    }

    /** 開機嗰陣喺後台 decode 定，唔使等第一次畫先卡一卡 */
    fun preload(ctx: Context) { sheet(ctx) }

    /**
     * 由 sheet 度攞 [name]（`"3_7"` 咁嘅樣）嗰格出嚟畫。
     *
     * 個 source rect 用**乘完先除**嘅方法計邊界（`c * w / COLS`），
     * 咁樣就算 user 換咗幅唔係啱啱好 9 嘅倍數闊嘅圖，格與格之間都唔會
     * 漏一兩條 pixel 出嚟。
     */
    fun draw(canvas: Canvas, ctx: Context, name: String, dst: RectF, dim: Boolean) {
        val bm = sheet(ctx) ?: return
        val sep = name.indexOf('_')
        if (sep <= 0) return
        val row = name.substring(0, sep).toIntOrNull() ?: return
        val col = name.substring(sep + 1).toIntOrNull() ?: return
        if (row !in 0 until ROWS || col !in 1..COLS) return
        val w = bm.width
        val h = bm.height
        src.set(
            ((col - 1).toLong() * w / COLS).toInt(),
            (row.toLong() * h / ROWS).toInt(),
            (col.toLong() * w / COLS).toInt(),
            ((row + 1).toLong() * h / ROWS).toInt()
        )
        if (src.isEmpty) return
        canvas.drawBitmap(bm, src, dst, if (dim) dimPaint else paint)
    }

    // ---- 設定頁換圖 --------------------------------------------------------

    /**
     * 設定頁揀咗新圖：驗完就抄落 `filesDir`，舊嗰幅唔留。
     * 驗唔過（decode 唔到、細過 9×10）就唔會郁到而家用緊嗰幅。
     */
    fun replaceFrom(ctx: Context, uri: Uri): Result<Unit> = runCatching {
        val tmp = File(ctx.cacheDir, "incoming_strokes.png")
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            tmp.outputStream().use { out -> input.copyTo(out) }
        } ?: error("無法開啟所選檔案")
        val bm = runCatching { decodeFile(tmp) }.getOrNull()
        require(bm != null) { "不是有效的圖片檔案" }
        require(bm.width >= COLS && bm.height >= ROWS) {
            "圖片太細，最少要 ${COLS}×${ROWS} 像素（會切成橫 $COLS 直 $ROWS 共 90 格）"
        }
        val target = file(ctx)
        target.parentFile?.mkdirs()
        tmp.copyTo(target, overwrite = true)
        tmp.delete()
        reload()
    }.onFailure { File(ctx.cacheDir, "incoming_strokes.png").delete() }

    /** 還原內置嗰幅：刪咗自訂檔就得 */
    fun restoreBuiltin(ctx: Context) {
        file(ctx).delete()
        reload()
    }

    /**
     * Decode 之前先問長闊，太大就 [BitmapFactory.Options.inSampleSize] 縮細 ——
     * user 隨手揀張相機影嘅相都唔會爆 memory。
     */
    private fun decodeFile(f: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_SIDE) sample *= 2
        val o = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeFile(f.absolutePath, o)
    }

    /** 每格幾多 pixel（設定頁攞嚟顯示，`null` = 而家幅圖 load 唔到） */
    fun tileSize(ctx: Context): Pair<Int, Int>? {
        val bm = sheet(ctx) ?: return null
        return (bm.width.toFloat() / COLS).roundToInt() to (bm.height.toFloat() / ROWS).roundToInt()
    }
}
