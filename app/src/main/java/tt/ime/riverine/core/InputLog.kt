package tt.ime.riverine.core

import android.util.Log

/**
 * 打字過程嘅 logcat 記錄（tag `TTInput`）—— 專門查「明明撳咗 793，點解出咗第二隻字」
 * 呢種問題：**手指撳落邊粒鍵**（連坐標同貼邊補正）、**滑動途中判定咗撳過邊幾格**、
 * **engine 收到嘅碼一路變成點**、最後**揀咗邊格出咗邊隻字**，逐行寫晒出嚟，
 * 對住 `scripts/debug-input.sh` 條 terminal 就見到自己撳嘅同 app 收到嘅差喺邊。
 *
 * 兩個開關，開任何一個都會出 log：
 *
 *  - 設定頁「其他 → 記錄輸入過程 (logcat)」（[Prefs.KEY_INPUT_LOG]）——
 *    平時裝住嘅 release 版都開得，改完即刻生效（`TTInputMethodService` 每次
 *    入輸入框都會餵返 [pref]）。
 *  - `adb shell setprop log.tag.TTInput DEBUG` —— 唔使入設定頁，但係熄機就冇咗。
 *
 * **預設兩個都熄**：呢啲 log 逐粒鍵一行，寫住打緊乜（包括密碼欄打嘅嘢），
 * 唔應該喺正常用嗰陣一路出。
 */
object InputLog {

    const val TAG = "TTInput"

    /** 設定頁嗰個開關（`TTInputMethodService` set 返落嚟，core 呢邊冇 Context） */
    @Volatile
    var pref = false

    val on: Boolean get() = pref || Log.isLoggable(TAG, Log.DEBUG)

    /**
     * 熄咗嗰陣**連句字都唔會砌**（[msg] 係 inline lambda）——
     * 逐粒鍵行一次，唔好為咗冇人睇嘅 log 而喺打字途中起 string。
     */
    inline fun log(msg: () -> String) {
        if (on) Log.d(TAG, msg())
    }
}
