package hk.tq9.ime

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color

/** 鍵盤配色 */
class Theme(val dark: Boolean) {
    val background = if (dark) Color.parseColor("#12151A") else Color.parseColor("#DCE0E6")
    val keyFace = if (dark) Color.parseColor("#242A33") else Color.WHITE
    val keyFaceAlt = if (dark) Color.parseColor("#1B2027") else Color.parseColor("#C9CFD8")
    val keyFaceDown = if (dark) Color.parseColor("#3A4552") else Color.parseColor("#B9C6DA")
    val keyDisabled = if (dark) Color.parseColor("#191D23") else Color.parseColor("#BFC4CC")
    val keyAccent = if (dark) Color.parseColor("#2B4A6F") else Color.parseColor("#BBD4F2")
    val text = if (dark) Color.parseColor("#ECEFF3") else Color.parseColor("#14171C")
    val textDim = if (dark) Color.parseColor("#8A93A0") else Color.parseColor("#6C7480")
    val trail = if (dark) Color.parseColor("#66C2FF") else Color.parseColor("#1E88E5")
    val onAccentText = if (dark) Color.parseColor("#EAF3FF") else Color.parseColor("#0B2A4A")

    companion object {
        fun of(ctx: Context): Theme {
            val night = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
            return Theme(night)
        }
    }
}
