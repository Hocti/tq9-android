# AGENTS.md — 三三正體中文輸入法 ThreeThree (Android)

改內容之前請先讀完本文件。這個 repo 是 Windows 版（C# WinForms）
移植過來的 Android system keyboard，行為必須與原版一致。

---

## 概要

使用已過期專利 HK1035043 的 numpad 中文輸入法。按 2~3 個碼查 `mapped_table` 出關聯字，
字碼表／關聯字／同音字／繁簡表全部在一個 sqlite 檔案內，使用者可以在設定頁更換。

## 環境

| | |
| --- | --- |
| JDK | `/opt/android-studio/jbr`（要 `JAVA_HOME=/opt/android-studio/jbr ./gradlew …`） |
| SDK | `~/Android/Sdk`（`local.properties` 已固定） |
| 版本 | minSdk 26 / targetSdk 36 / Kotlin 2.1 / AGP 8.13 / Gradle 8.14 |
| 模擬器 | AVD `Medium_Phone_API_36.1` |

```bash
JAVA_HOME=/opt/android-studio/jbr ./gradlew :app:assembleDebug :app:testDebugUnitTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell ime enable tt.ime.riverine/.ime.TTInputMethodService
adb shell ime set    tt.ime.riverine/.ime.TTInputMethodService
```

### 在模擬器上測試鍵盤，有四個陷阱

1. **一定要 `adb shell settings put secure show_ime_with_hard_keyboard 1`**。
   模擬器會將自己視為已連接實體鍵盤，不開這個 setting 就不會彈輸入法出來。
2. **不要 `adb shell am force-stop tt.ime.riverine`**。IME service 與 app 同一個 package，
   force-stop 會一併終止 IME，系統就會回退回 Gboard。用 `am start` 就夠。
3. **每次 `adb install -r` 之後都要再 `ime enable` + `ime set` 一次**，
   系統會當它 reinstall 而 reset。
4. **`screencap` 影出來成塊黑／白（WSL + swiftshader）**，看不到鍵盤實際狀態。
   改為用數字驗證：

```bash
# 鍵盤（IME window）實際寬度幾高
adb shell dumpsys window windows | sed -n '/InputMethod}/,/Frames/p' | grep -E "Requested|Frames"
# 可見的 view（app 那邊）找輸入框坐標 —— 鍵盤本身是自己畫的，dump 無法顯示按鍵
adb shell uiautomator dump /sdcard/u.xml && adb pull /sdcard/u.xml
# 改設定／看 pref（app debuggable，不用 root）
adb shell run-as tt.ime.riverine cat shared_prefs/tq9_settings.xml
```

`shared_prefs` 檔案名**故意仍是舊名** `tq9_settings.xml`（見 `Prefs.FILE`）——
2.0.0 改名時沒有更改它；否則舊裝置的設定會全部重置。純內部檔名，使用者看不到。

   要開英文鍵盤就按 Chrome 個網址欄（URI 欄 → `PadMode.LATIN`），中文就找個
   普通文字欄（例如 `am start -a android.intent.action.INSERT -t vnd.android.cursor.dir/contact`）。
   按工具列 上面顆大小按鍵要自己計坐標：工具列 高 42dp 在 IME window 最頂，
   英文／符號頁最左還有顆 42dp 的 `⇄`（`setSwitchVisible`），然後才是
   工具列五個（padding 4dp、每顆 margin 3dp）—— 而且要工具列 在「工具」那段
   （`bar_mode`）先按得到。按一下 = 轉顯示方式，拖 = 拉大小。

設定頁最底本來有「試打」四欄位（普通／email／PIN／搜尋）以及實時預覽，
**目前已隱藏**（`SettingsActivity.SHOW_DEBUG_SECTIONS = false`，使用者不想見到）。
`buildTryBox()` / `buildPreview()` 一行都沒有刪 —— 想 debug 排位就改回 `true`，
無須開啟第三方 app。搜尋那欄是用來看 `⏎` 有沒有變 `🔍`
（`enterLabelFor()` 看 `IME_ACTION_SEARCH`）。

### 查「明明按了 793，為何出現了第二字」：`scripts/debug-input.sh`

```bash
./scripts/debug-input.sh            # 接住裝置就開始收 log
./scripts/debug-input.sh --install  # 同時建置 + 裝 debug APK（會自動 ime enable/set）
./scripts/debug-input.sh --off      # 關閉（條 log 顯示輸入中的甚麼，查完要關閉）
```

條 script 行 `adb logcat -s TTInput`，再按內容上色（青＝手指、紫＝滑動判定、
綠＝出現了字兼空一行、紅＝沒有效果），迅速看出**手指做的內容**與**engine 收到的內容**
差異所在。`--raw` 就不上色，方便 `tee` 落檔。

log 由 `core/InputLog` 出，**兩個開關任選其一**（預設兩個都關閉）：

- 設定頁「其他 → 記錄輸入過程 (logcat)」（`Prefs.KEY_INPUT_LOG`）——
  設定頁與 IME service 同一個 process，按下立即生效；關機都仍在此。
- `adb shell setprop log.tag.TTInput DEBUG`（`Log.isLoggable` 逐次重新讀取
  property，不用重開個 IME）—— 條 script 用這個，不會移動 使用者個設定；關機就沒有。

寫 log 的位（加新 log 就依照這些，不要散落周圍）：

| 哪裡 | 寫甚麼 |
| --- | --- |
| `KeyboardBaseView` | 按下哪個鍵＋坐標＋有沒有貼邊補正（`boxNear` 按中隙位）、按下即出、放手是否輸出、長按、連按 |
| `GestureKeyTracker` | 每格的停留、有沒有「減速再加速」、轉角度數／門檻、幾何分、字碼表加減分、總分／門檻、算不算按了 |
| `TTEngine` | 碼持續如何變化、資料表開啟後有多少個字、選了哪一格（第幾頁排第幾）、出現了哪個字 |

`InputLog.log { … }` 收 lambda —— 關閉時**連日誌字串也不會建立**，每個按鍵都會執行，不可造成不必要的開銷。

⚠️ `InputLog` 接觸 `android.util.Log`，而 JVM unit test 內 `android.*` 是沒有實作
的 stub，所以 `app/build.gradle.kts` 開啟後
`testOptions.unitTests.isReturnDefaultValues = true`（不開 `GestureKeyTrackerTest`
成批會掟「not mocked」）。

---

## 不可混淆的規則

### 九宮格排位是 numpad，不是電話

`7 8 9` 在最上、`1 2 3` 在最下方，依照 Windows 版的 `ResizeAllButton()`。
底行是 `[0 佔兩格][取消]`；選字達兩頁時兩格寬那顆 `0` 如何變化由設定決定
（見下面「選字揭頁」）。改為電話排列就與原版打法完全不同。

**右欄由上而下是 `☰／⇄`、`␣`、`⌫`、`⏎`**（2026-08-27 使用者要求，`␣` 與 `⌫`
對調了）—— 這樣中文都依照下面那條「`⏎` 上面那顆一定是 `⌫`」的規矩，
四款鍵盤一致。最上那顆平時是 `☰`，工具列常駐時變 `⇄`（見「工具列常駐」）。
左下角只保留 `🌐`（整格寬）—— 錄音已移至上工具列，在「貼上」旁邊，
也是左上角那按鍵可選擇的其中一個 `PadFunc`。

### 底行的規矩（英文／符號／純數字）

- **左下兩個一定是「返回英文／中文」**（`Eng` 優先，然後才 `中`）—— 有兩個例外：
  中文九宮格自己就是中文，左下角只保留 `Eng`；**純數字頁**兩個已移至**右上角**
  （使用者要求，左下角讓了讓 `0` `.` `-`）。
  英文那顆**寫 `Eng` 不寫 `ABC`**（2026-08-25 使用者要求，全部頁一致）。
  中文那顆**長按 = 換輸入法**，執行哪項功能由設定頁選（`Prefs.EngLongPress`）：
  `NEXT_IME` → `KeyAction.IME_SWITCH`（跳去下一個，無法切換就轉到選單），
  `PICKER` → `KeyAction.IME_PICKER`（直接彈系統選單）。🌐 已隱藏，
  這按鍵是唯一入口，所以兩種做法都要有。
- **`⏎` 上面那顆一定是 `⌫`**。所以符號頁的分頁按鍵（`€£¥`／`?123`）與 `⌫`
  都在倒數第二行的最左與最右，純數字頁的 `⌫` 也由右上角已移至 `⏎` 上面。
  第一頁那顆分頁按鍵**寫三個貨幣符號 `€£¥`**（第二頁頭一行就是貨幣符號），
  以前寫 `=\<`，沒有人知是甚麼。
- 騰出的位：符號第一頁底行 space 右邊依序排列 `, . ? ; /` 五個（本來散在
  上面兩行）；第二頁不要標點，space 與 `⏎` 拉長，`numpad` 按鍵再上移一行。
- **純數字頁最左有一欄 `+ - * /`**（2026-08-25 加）：`-` 由底行已移至上去，
  騰出那位置（`0` 右邊）放了顆 **`000`**（一次輸入三個 0）。
  即目前成頁是 5 欄，與中文九宮格一樣。
- **英文底行 space 右邊是 `, . /` 三個**（原本是 `/` 在 space 左、`?` 與 `.` 在右）。
  `?` 已經沒有獨立一個 —— 它是長按 `/` 的第一個選擇（見下面）。

### 英文鍵盤排位（2026-08-24 大修正過）

- **永遠有數字行**（`Prefs.FORCE_LATIN_NUM_ROW = true`）。設定頁那個開關已隱藏，
  但 `KEY_LATIN_NUM_ROW` 與「沒有數字行會在字母角落顯示小字」那段 code 都沒有刪。
- `asdfghjkl` **不再靠拉長 `a` / `l` 填滿邊緣**：九顆一樣寬，兩頭各讓半格空位
  （`spacerKey(0.5f)`）。空位**不會**入 `boxes`，所以按下去會由 `boxNear()`
  snap 至旁邊實際按鍵，不會變死位。
- `,` 由 `zxcvbnm` 行已移至底行（頂了本來個 `?`），騰出的位讓 `⇧` 與 `⌫` 拉長。
- **長按字母大小寫兩樣都可選**：`ch()` 會按目前個 `ShiftState` 建立 variants ——
  排頭那個是按鍵目前顯示那個（按住不移動後放手 = 輸入它），第二個是另一個大小寫。
  popup 選回來那按鍵帶 `Key.literal = true`，`typeChar()` 見到就**不會**再套 shift
  （不是特意選個小寫 `a` 會讓 shift 強行恢復 `A`）。
- 標點三個（`, . /`）長按有 `PUNCT_VARIANTS`，左上角顯示小字提示。
  **三個都不跟「第一個 = 自己」規矩** —— 排頭那個是長按彈出時就已經停了
  在此那個（手指不移動放開就出它），按鍵自己短按取得：
  `,` → **Tab**（`\t`）、`.` → `;`、`/` → `?`。
- **Tab 沒有字形**，畫出來一片空白，所以 `variantDisplay()`（`KeyDef.kt`）會替換為
  `⇥` —— popup 與角落提示都要執行這個 helper，但 `Key.variants` 內存的、
  以及最後 commit 出去那個一定要是真正的 `\t`。
- **變體多於螢幕容納就一起壓縮**（`openVariantPopup`：
  `if (popupItemW * items.size > width) popupItemW = width / items.size`）。
  寧可每顆細些都好過有多個推了出螢幕外面永遠無法選擇 —— `/` 有八個，
  使用 `max(鍵寬 × 1.1, 50dp)` 就一定超出。字太大 `KeyPopup` 自己會縮回。
- `?123` 長按 = 直接跳純數字頁（`longAction = TO_NUMBER`），中文九宮格那顆一樣。
  **英文那顆沒有左上角提示字**（2026-08-29 使用者要求）：鍵面本身已經四文字符，
  底行每顆都窄，再擠壓個 `123` 落左上角就擠在一起。中文九宮格那顆空間較寬鬆，
  個 `hint` 照留 —— 兩處不一致是特意的。

### 純數字頁：成頁不得長按

`NumberPadView.allowLongPress()` 一律回 `false`（`KeyboardBaseView` 那個 hook）。
打電話號碼／金額按住稍久就彈個符號 popup 會很干擾，所以數字鍵**用 `num()`
不用 `digitKey()`**（後者會帶 `variants`）。

寬度與貼邊**不可以再自己計**：`RowsPadView.contentBounds()` 已經直接開一個
`PadMetrics`（一樣 5 欄 4 行）取 `offsetX` / `contentW`，這頁只要 override
`padGroup = PadGroup.CJK`，即大小完全跟中文九宮格 —— 連工具列 左右拖出來
那個寬度倍數都跟。以前這頁自己置中而且封頂 360dp，中英鍵盤切換時，按鍵會左右彈。

### `dataset.db` 不會自動更新，舊機仍在使用裝機時那份

`TTDb.ensureInstalled()` **只在 `filesDir/dataset.db` 不存在時先由 assets 抄**——
使用者可以在設定頁更換文字碼表，強行覆蓋就會已刪除他人自己選那份。代價：
**由舊版升級上來的機，資料庫 仍是當初裝機那份**。實測（2026-08-27，模擬器）
2026-08-21 那份 1.7MB 舊 db：

- `word_meta` **沒有 `freq` / `code` 兩欄** → `topByCodePrefix` 無法查詢（SQL 直接
  throw，它自己 `runCatching` 攔截了）
- `mapped_table` **沒有 id `1010`** → 候選欄的預設字無法取得

所以兩處都要有 fallback（`TTInputMethodService`）：`defaultPicks` 無法取得 1010
就回退至 1000（速選字表，即以前的做法），`codePreview()` 空就回退至
`defaultPicks`。**工具列 留空看起來似壞了，寧可出舊那套。** 想取回新功能應提示使用者
在設定頁按「還原內置字碼表」（會覆蓋它自訂過的 db，所以不可以無聲地自動做）。

加任何要新 schema 的內容之前，記住考慮：舊 db 會點？

### mapped_table 的 id 有特別意思

| id | 是甚麼 |
| --- | --- |
| `0` | 標點（首頁按 0） |
| `1` | 開關標點成對（長按 0） |
| `10`, `20`, … `90` | 姓氏表（按了第一碼之後再按 0） |
| `10`~`999` | 一般字碼表，`weight` = 常用度 |
| `1000`~`1009` | 速選字表（⭐；首頁 = 1000，按了 1~9 之後 = 1001~1009） |
| `1010` | 候選欄的預設字（游標前方為空／不是中文時顯示，見「候選欄出甚麼」） |

字碼輸入邏輯（`TTEngine.press`）：輸入滿三碼、或者中途按 0 收尾，就查表出關聯字。

### `characters` 內的 `*` 是佔位符，不是一個字

`mapped_table.characters` 一格內用 `*` 佔位，等後面些字保持在正確格位 ——
例如 id `169` = `********教`，「教」一定要坐第 9 格（＝碼 `1699`）先符合
「第一頁永遠 `1`~`9`」條規矩。所以 **`*` 不可以刪除**（一刪除後面全部偏移），
但也**不得輸出**。

2026-08-30 之前只 `showPage()` 繪製時當它吉：按鍵格看起來是空白，但
`selectWord()` 照取 `selectWords[key]` 出來 commit，按下去確實會打隻 `*`
至輸入框。目前改成**在入口統一清理** —— `TTEngine.startSelectWord()`
見到 `TTEngine.PLACEHOLDER` 就替換為 `""`，跟寫入所有路（九宮格、上面工具列、
側邊欄、`pickCandidateAt`、`plausibility`）都當「這位置沒有字」，不用逐位置補
`it != "*"`。關聯字表（`related_candidates_table`）一樣有 `*`，行同一條路。

### 字要用 grapheme cluster 拆

`TTDb.splitGraphemes()` 用 `BreakIterator`，等與 C# 的 `StringInfo`。
用 `String.length` / `toCharArray` 會拆分損壞 emoji 與香港增補字符集。
判斷「是否單一個字」要用 `codePointCount`，不是 `length`。

### 按鍵之間不可以有死位

畫面上可見的隙是 `drawFace()` 縮了 `gapPx` 畫出來的，`KeyBox` 本身要貼近
（`RowsPadView` 最後一格／最後一行會強行去到最右最底）。
ACTION_DOWN 用 `boxNear()`（無法找到就取 14dp 內最近那顆），
**不要**改用 `boxAt()` —— 但滑動判定（`swipeKeyAt`）就一定要用 `boxAt()`，
不是格外面都會當按了邊緣那顆。

### 圖檔

90 格筆形提示圖**不再是 90 個 png**，是一幅 sprite sheet：`assets/default90.png`，
橫切 9 份、直切 10 份 —— **第一個數字 = 第幾行（0 起）、第二個 = 第幾列（1 起）**，
所以左上角 `0_1`、右上 `0_9`、左下 `9_1`、右下 `9_9`。`0_1`~`0_9` 是首頁筆形，
`1_1`~`9_9` 是第二碼提示。**Android 版沒有 `10_x`** —— 關聯字已改在上面工具列 選，
不會再塞進九宮格。

`StrokeImages` 只有一個 `Bitmap` cache 住成幅 sheet，`draw()` 每次計來源區域
出來 `drawBitmap` —— **不要**改回逐格 crop 出 90 個 Bitmap（多一份 memory，
IME process 個 heap 很小）。來源區域 用「先乘後除」（`c * w / COLS`）計邊界，
使用者使用者更換的不是 9 的倍數寬的圖都不會漏 pixel。

換圖：選那幅抄落 `filesDir/strokes.png`，**檔案是否存在就是「有沒有自訂」**
（沒有另開 pref，不會出現「pref 話自訂但檔案消失」）。所以升級已更換新的內置圖
立即生效，不用似 `dataset.db` 這樣記 versionCode。換完要叫 `StrokeImages.reload()`，
但**不要 recycle 舊那幅** —— 鍵盤可能同一時間繪製該圖片。

**「檢視目前 sqlite／圖片」＝ save as，不是在 app 內開**（2026-08-29 加）：
設定頁兩節各新增一個按鍵，行 `ActivityResultContracts.CreateDocument` 匯出一份，
使用者想用哪一個 sqlite viewer／看圖 app 皆可。App 自己無法開啟這兩種檔，
猜它裝了甚麼、又或者為了 `ACTION_VIEW` 開多個 `FileProvider` 出來都是多餘。
兩點要記住：

- **一定要抄 `filesDir` 那份**（`TTDb.file` / `StrokeImages.file`），不要因方便而
  由 assets 取 —— 不是就變了「檢視內置」，看不到 使用者自己已更換入去那份。
- 筆形圖**沒有自訂過就 `filesDir` 內根本沒有檔案**（見上面「檔案是否存在就是
  有沒有自訂」），所以 `exportImg()` 時才需要 `isCustom()` 分流，
  沒有自訂就實際從 assets 複製。字碼庫沒有這個問題（`ensureInstalled` 保證有）。

App icon 由 `../logo.jpg`（2048×2048）縮出來，五個 density 一次產生：
`ic_launcher_foreground` 透明底、圖佔 canvas **50%**（大於此比例，圓形 mask 就會
裁切隻箭嘴尖，試過 56% 已逸出），legacy 的 `ic_launcher` / `ic_launcher_round`
分別是 70% / 68%。底色 `@color/ic_launcher_background` = `#F4F8F9`。

### 系統輸入法選擇視窗只出一行「三三輸入法」

`res/xml/method.xml` **一個 `<subtype>` 都沒有**，是特意的（2.0.1 使用者要求）。
中英數符號是在鍵盤內自己切，不用讓系統知道語言：

- 加 subtype = 系統清單／選擇視窗在個名下面多一行「語言」。顆 subtype 有
  `android:label` 就出標籤，沒有 label 就自動出 locale 名（`zh_HK` → 「中文 (香港)」）
  —— 兩樣都不要，使用者只要一行 `ime_name`。空字串 label 都無效，
  `InputMethodSubtype.getDisplayName()` 見到空就會回退回出 locale 名。
- 加多於一個 subtype 仍衰，系統那度會變兩個輸入法。

所以此處**不要補回** subtype、`imeSubtypeLocale`、`languageTag`、`isAsciiCapable`。
Kotlin 那邊任何 subtype API 都無效（`grep -ri subtype app/src --include=*.kt` 是空），
拆走沒有內容會斷。

---

## 架構

```
core/   TTDb       sqlite 存取、assets 安裝、換 db、weight prefix 統計
        TTEngine   輸入狀態機（Windows 版移植），不接觸 Android UI
        EnDict     5 萬字英文詞庫（blob + starts + weight，慳記憶體），只
                   `fromPrefix` 打字提示 + `word`/`charAt`/`weightAt` 這些
                   public accessor 讓 `GestureDecoder`／`EnTrie` 用
        EnTrie     英文 unigram trie，每個節點快取住自己那個 prefix 之下
                   常用度最高的多個完整字（AOSP 標準做法）
        NextWordModel 選完一個字之後估下一個字：bigram（assets/en_bigram.txt）
                   做主，沒有 context／無法匹配 prefix 就轉到 EnTrie 的全域常用字
        EmojiDict  assets/emoji.txt，分類 + 用英文／中文關鍵字找
        ClipHistory clipboard 歷史（JSON 存在 Prefs）
        AiRewrite  Gemini generateContent，改寫選了那段字
        AiStt      VoiceRecorder 錄 PCM、VoiceActivity 判斷有沒有人聲、
                   SttAudio 壓縮（AAC-LC / ADTS，無法進行壓縮就回退 WAV）
        UsageStats 另一個 sqlite（usage_stats.db，與 dataset.db 分開）：
                   連續兩個中文字的 bigram 次數、每個字輸入了多少次
        Prefs      全部設定
swipe/  GestureKeyTracker   中文九宮格滑動中間鍵判定（純 Kotlin，有 unit test）
        GestureDecoder      英文 swipe 認字：AOSP 手勢輸入那套概念的 Kotlin 版
                   （軌跡 vs 關聯字理想路徑做形狀比對，不是逐格判斷按了哪個鍵）
ime/    TTInputMethodService   IME 主體，所有 view 的 host
        KeyboardBaseView        排版／畫鍵／接觸觸／畫線／長按 popup／長按 ␣ 移動 caret
        KeyPopup                浮在鍵盤外面那些窗（長按變體行、滑動 hover 提示）
        ChinesePadView          九宮格（KeyboardBaseView）
        RowsPadView             一行行按 weight 分寬度的底
        LatinPadView / SymbolPadView / NumberPadView（RowsPadView）
        EmojiPadView            emoji grid（ViewGroup，不是 KeyboardBaseView）
        ClipboardListView       長按「貼上」之後蓋在 padHolder 上面的 overlay
        PadMetrics              尺寸與顯示方式計算
        OptionBarsView          上面工具列（三段：關／關聯字／工具）
ui/     SettingsActivity / MicPermissionActivity
```

`TTEngine` 不應該 import 任何 `android.view.*`；它只吐狀態，由 `ChinesePadView` 畫。

---

## UI 高度：不可以無啦啦跳

### 同一組鍵盤總高度一致

`RowsPadView.onMeasure` **不是**逐行乘行高，是直接取
`PadMetrics.padHeightPx(ctx, w, padGroup)`（＝那組 4 行的總高）。英文開啟後數字行有
5 行、符號頁有 5 行、中文永遠 4 行，同一組內總高度一樣，行數較多時，每行會自然較矮。
加行減行**不會**令視窗跳高跳低，所以不要在子類中補回 `rowHeightDp` 這類逐行計的內容。

`padGroup`（`PadGroup.CJK` / `LATIN`）話讓它知取哪套大小 —— 見「大小設定分組存」。
`RowsPadView` 預設 `LATIN`，`NumberPadView` override 回做 `CJK`（跟中文九宮格）。

### 上面工具列 三段都是一行

`OptionBarsView` 三段（`BarMode`）每段都是只有**一行**。以前有條「狀態」小字
（字碼、`[同音]`、頁數）放在最上面，一出現就整個鍵盤高了一截，已經**移除**——
`TTEngine.status` 仍在計算，但沒有人畫。要出 message 就用 `toast()`，
不要再在工具列 上面加行。

高度**不再是固定為 42dp**（2026-08-29 使用者要求）：根據 `CandChip` 中關聯字實際
要幾高，再加上下 3dp margin，最矮 42dp。100% 之下實測 47dp。
**三段共用同一高度**，轉段一樣不會跳。

改完字體要行 `bars.refreshFontScale()`（`refreshBars()` 內，在
`setCandidates` 之前）—— 它見到 sp 沒有變就立即返回，所以即使逐個按鍵呼叫也不會有額外開銷。
側邊欄那邊是 `SidePanelView.refreshFontScale()`：**不可以靠 `setCandidates`**，
它見個 list 沒有變就不會重建些 chip，改完字體回來仍是舊 size。

### 關聯字 chip 的高度／padding 一定要行 `CandChip`

大小全部在 `CandChip` 中計，`OptionBarsView` 與 `SidePanelView` 兩份 `makeChip()`
共用。裡面有兩個曾遇到的坑（2026-08-29 使用者影實機照片遇到「上面 padding 多於下面」），
改之前一定要看：

1. **不可以使用 `Paint` 取得 metrics 來決定高度。** `Paint.getFontMetrics()` 回的是
   **primary typeface（拉丁）**那套；中文字是轉到 CJK fallback 字型畫。實測
   20sp / density 2.625：`Paint` 話 `descent - ascent` = 61.5px，但真正
   `layout.height` = 75px。用細那數值，chip（要 105px）就會讓 `AT_MOST`
   壓縮到 96px，上下 padding 寫到幾對稱都無效。所以要**建立真實的 `TextView`**。
2. **`gravity = CENTER` 置中的是 line box，不是文字的墨。** CJK 字形實際畫素位置較高：
   baseline 上面 box 有 60px 但字形實際畫素只去到 44，下方 box 有 15px，但字形實際畫素只佔 5px ——
   上方空隙為 16、下方空隙為 10，置中後文字實際偏低。所以 `padTop` / `padBottom`
   **特意不對稱**（實測 13 / 18），差額剛好抵消。

測量時用**固定的參考字**（中文 `字`、英文 `Ag`），**不可以**用 chip 自己
該字 —— 不同字字形實際畫素不同高（`一` 只一橫、`我` 佔整格），逐個各自置中就會
每顆 baseline 不同，一行看起來高高低低。實測結果（gapTop/gapBot）：
`我` 29/28、`的` 28/29、`一` 50/51、`是` 30/28、`不` 32/28。

### 字體大小：條 slider 只能調整「字」，不能調整功能鍵

設定頁兩條字體 slider（`Prefs.KEY_FONT_SCALE` / `..._LATIN`）**只**放大
實際可輸入的字元：九宮格的關聯字與筆形提示、英文字母、符號、數字，
以及上面工具列 與側邊欄的關聯字（`Prefs.candTextSp`）。

**功能鍵（同音、取消、Eng、中、⌫、⏎、␣、?123、€£¥…）不跟**
（2026-08-29 使用者要求）：行 `Prefs.funcFontScale()`，永遠當 100%
（英文組仍然乘 `LATIN_FONT_BOOST`，所以 slider 設為 100% 時的外觀與以前相同）。
按鍵執行哪項功能早就記熟了，不用看得那麼清楚，若與字元同時放大，就會擠滿按鍵。

繪製時如何區分：`RowsPadView` 看 `isFunctionKey(k)`（`action != CHAR`），
`ChinesePadView` 就是 `drawFunction()` 那條路（`DIGIT` 以外全部）。
**整個按鍵統一使用同一倍數** —— 鍵面標籤、左上／左下／右上角小字、
`Eng` 個 🌐，全部都要傳 `scale =` 落 `KeyboardBaseView` 那`draw*` helper，
不要只改標籤。工具列上的圖案（`ICON_DP`）同一個道理，一樣不跟。

### 顆 `▼`（拉大關聯字）只在確實可捲動時顯示

`OptionBarsView.wantExpandBtn()`：**比 `strip.width`（字元的實際寬度）與 `swap.width`
（成行的寬度）**，不夠位容納時才 `VISIBLE`，否則 `GONE`（不是 `INVISIBLE` ——
以前是 `INVISIBLE`，顆按鍵沒有個 `▼` 但位置仍被佔用，看起來似壞了）。
**不可以取 `scroller` 的寬度來比**：顆按鍵一出現就佔用 38dp，然後又恢復「要捲」，
反覆切換。展開後（`expanded`）就一定要出，不是就無法收合。

判斷要等排完版先做得，所以在 `onLayout()` 中做，而且**改 visibility 要 `post`**
（佈局期間修改就會立即再 `requestLayout` 多次）。

### 中文拉窄就不要上面工具列，改用側邊欄

`PadAlign.LEFT_GAP` / `RIGHT_GAP` 之下，中文本體寬過螢幕的
`Prefs.SIDE_PANEL_MAX_RATIO`（六成）就仍然用上方的 `OptionBarsView`；
**窄過六成**就 `bars.visibility = GONE`，成工具列 的內容搬去 `SidePanelView`
（加入 `padHolder` 度，`FrameLayout.LayoutParams` 寬度 = 空出來那邊，
gravity 跟 `PadAlign` 反過來放置）：上面一（兩）行功能按鍵，下面成塊可 scroll 的關聯字。

入口是 `refreshBars()` 開頭那句 `if (refreshSidePanel(cands)) { … return }`。

**高度一定要固定為 `PadMetrics.totalHeight`（＝中文九宮格幾高），
不可以用 `MATCH_PARENT`。** `padHolder` 是 `wrap_content` 的 `FrameLayout`：
`MATCH_PARENT` 的子視圖會取到 `AT_MOST(全部可用高度)`，而 `SidePanelView` 內
個關聯字 `ScrollView` 又佔用 `weight = 1`，結果關聯字一多就擴大了 `padHolder`，
整個鍵盤然後拉高（**橫向特別明顯**，因為橫向一定入側邊欄模式）。
只限**中文九宮格** —— 英文／符號／純數字是鋪滿成行，沒有位空出來；
剪貼簿個 overlay 又會蓋住整個 `padHolder`（連側邊欄都一併遮蓋就無法按下 ✖），
所以 `overlay != null` 時一定要退回用上面工具列。

側邊欄沒有 `⇄`（關聯字與工具同時顯示，不用切）。`switchMode()` 個
`padHolder.removeAllViews()` 會同時 detach 了它，最後那句 `refreshBars()` 會補回。

### 鍵盤永遠貼近底

`PadMetrics` 沒有 `extraBottom`／`Prefs.floatY`。`PadAlign.FLOATING` 已刪除
（自由移動那顆無效，`Prefs.floatX`／`ChinesePadView.nudgeFloat` 一起清了）——
目前 `PadAlign` 只有 `STRETCH`／`LEFT_GAP`／`RIGHT_GAP` 三個，
`OptionBarsView` 個 sizeBtn 只轉這三個。拖動**兩個方向都都有對應功能**
（拖動超過 8dp 就鎖定方向，不會輕微斜向移動就兩樣一起改）：

- **上下** = `Prefs.heightScale`（0.6~1.8）。`PadMetrics.cellH` 與
  `PadMetrics.rowHeightPx()` 兩邊都要乘回它，不是英文鍵盤就不會隨之變。
  拉哪組要看 `TTInputMethodService.padGroup`（見「大小設定分組存」）。
- **左右** = `Prefs.widthScale`（0.45~1.6），只 `LEFT_GAP` / `RIGHT_GAP` 有用。
  **只入 `cellW`，不可以入 `cellH`** —— 兩者本來都由同一個 `unit` 出，
  一不小心就會變成「左右拉埋高度都隨之變」。
  方向要跟顯示方式反（見 `onWidthDrag`）：永遠都是「拖向留白那邊 = 拉寬」。
- **長按（按住不拉）= 一下子拉到最寬**（`Listener.onMaxWidth`，2026-08-25 加）：
  `widthScale` 直接寫 `MAX_WIDTH_SCALE`。手機直向的「最寬」＝螢幕寬度
  （`cellW` 會限制為 `availW / cols`）。

  **長按操作不能立即執行操作，必須等到放手後才確認**（2026-08-28 修正，使用者遇到：
  「左右拆開拖動期間兩半突然合併」）。`View` 個長按大約半秒就 fire，而
  **只手指移出按鍵範圍先會自動取消** —— 顆按鍵很寬（橫向成 170dp），
  慢慢拖、或者一般這樣「按下 → 稍作停留 → 才拖」，長按都會在途中 fire，
  立即執行就會拖動進行至一半時跳至最寬（在 `SPLIT` 之下就是兩半合併）。
  目前 `setOnLongClickListener` 只 `longPressArmed = true`，`handleSizeDrag`
  收到 `ACTION_UP` 先看：**有拖過（`dragging`）就當拖，沒有拖過才叫
  `onMaxWidth()`**；一鎖定拖動方向也同時 `cancelLongPress()`。
  `OptionBarsView` 與 `SidePanelView` 兩邊完全一致，改就兩邊一起改。

**中文本體最少 `PadMetrics.MIN_CONTENT_DP`（320dp）寬**（螢幕本身窄過 320dp
就用盡螢幕）—— 拉窄與 `widthScale` 都無法縮小至低於這條線。順帶影響：直向手機
（400dp 左右）目前永遠無法達到 `SIDE_PANEL_MAX_RATIO`（六成）那個側邊欄條件，
側邊欄實際上只會在橫向或平板上顯示。

設定頁那幾條尺寸 slider（按鍵大小／最大寬度／最大高度／按鍵高度／鍵盤高度）
全部已隱藏（`SettingsActivity.SHOW_HIDDEN_OPTIONS = false`，一行 code 都沒有刪），
只保留「字體大小」（中文一條、英文一條）與「邊框粗細」—— 長寬目前一律在鍵盤上直接拖。

**滑動輸入的「停留」與「轉角」**兩條 2026-08-29 隱藏過
（一般使用者無須進行這麼細緻的調整），2026-08-31 應 使用者要求**重新顯示做 debug 測試用**——
目前兩條 slider 不再受 `SHOW_HIDDEN_OPTIONS` 管，直接出在「滑動輸入」那段。
兩個 pref（`KEY_SWIPE_DWELL` / `KEY_SWIPE_ANGLE`）與 `GestureKeyTracker`
始終都照讀，隱藏那排都沒有停過。

**「顯示方式」那顆按鍵 2026-08-29 由設定頁移走了**（使用者要求）：條工具列最左顆按鍵
按一下就轉下一個，設定頁再加入一個入口做同一件事是多餘。`Prefs.align` /
`setAlign` / `nextAlign` 程式碼均未刪除，`TTInputMethodService.onCycleAlign` 仍在使用。

### 字體大小都是分兩組（2026-08-28 使用者要求）

`Prefs.fontScalePref(ctx, group)`：`CJK` 行 `KEY_FONT_SCALE`、`LATIN` 行
`KEY_FONT_SCALE_LATIN`（未校過就依照 `CJK` 那值，升級之後外觀不會變）。
中文字要夠大先看得清，英文字母與數字用同一個倍數就會擠滿按鍵。

**繪製時要叫 `Prefs.fontScale`，不是 `fontScalePref`**（2026-08-29 分開啟後）：
英文組的實際倍數 = pref × `Prefs.LATIN_FONT_BOOST`（1.2），而且條 slider
可調整至 `MAX_FONT_SCALE_LATIN_PCT`（200%，中文那條仍然 140%）—— 使用者表示英文
140% 都不算大。**不要因方便而將個 boost 直接寫入 pref**（例如選 100 就存 120）：
儲存的數值就是設定頁見到那個百分比，反推回來一定有 rounding 誤差，
拖幾次就會偏移。設定頁讀 `fontScalePref`，鍵盤讀 `fontScale`。

分組依照 `PadGroup`（**不是**「中文 pad vs 其餘」）：純數字 keypad 排位與大小
本來就跟九宮格，所以字體都跟 `CJK`。`KeyboardBaseView.padGroup` 就是取哪套的入口
（預設 `CJK`，`RowsPadView` override 做 `LATIN`，`NumberPadView` 再 override 回 `CJK`），
`fontScale` 與 `PadMetrics` 兩邊都靠它。

### 大小設定分組存（2026-08-28 使用者要求）

`heightScale` / `widthScale` / `align` 三項**不是只有一套**，而是
「螢幕尺寸 × `PadGroup`」各有各存（`Prefs.profKey()` 砌個
`<base>_<寬dp>x<高dp>_<組>` 的 key）：

- **`PadGroup.CJK`** = 中文九宮格 + 純數字 keypad（本來就是同一個 5 欄排位）
- **`PadGroup.LATIN`** = 英文 + 符號（`RowsPadView` 預設）

螢幕尺寸用 dp 寬高做名，一次分開所有摺機的外／內屏（尺寸不同）與直向橫向
（寬高調轉）—— 摺機正確會有 2 屏 × 2 方向 × 2 組 ＝ 8 套。**舊那個沒有螢幕名的
key 照留回做預設值**（`sp.getFloat(profKey(...), sp.getFloat(舊 key, 1f))`），
升級之後大小不會偏移，兩組都由舊那值起步。

`TTInputMethodService.padGroup` 看住 `mode` 回傳目前移動緊哪組（`LATIN`／`SYMBOL`
是 `LATIN`，其餘全部 `CJK`），`onSizeDrag` / `onWidthDrag` / `onMaxWidth` /
`onCycleAlign` 四個都要取它，然後一律 `relayoutPads()`（所有已經砌了的 pad 一起重排，
不用逐個 `?.rebuild()` 撩漏）。`OptionBarsView.padGroup` 也要在 `refreshBars()`
度然後 set，不是顆「靠左／靠右」按鍵個圖案會畫回另一組那個狀態。

英文／符號頁本來永遠鋪滿成行，目前 `RowsPadView.buildLayout()` 一律重新開啟個
`PadMetrics(w, group = padGroup)` 取 `offsetX` / `contentW`，所以它哋一樣拉得寬窄、
分別貼齊左右兩邊。`STRETCH` 時 `contentW == availW`，與以前完全一致。
側邊欄（`sideGeom()`）就仍然只中文先出，用 `CJK` 那套。

### 兩組的寬度**計法不同**（2026-08-28 修正過）

`PadMetrics` 內 `contentW` 分兩條路：

| 組 | 點計 | 為何 |
| --- | --- | --- |
| `CJK` | `unit × widthScale × cols`（封頂 `availW`、封底 `MIN_CONTENT_DP`） | 九宮格要保持格仔的高寬比，寬度與 `unit`（＝高度那個 unit）綁埋 |
| `LATIN` | `MIN_CONTENT_DP` → `availW` **線性**（`widthScale` 由 `MIN_` 到 `MAX_WIDTH_SCALE` 對應 0→1） | 一行行排，按鍵格的寬度與高度沒有關係 |

英數那邊**不可以**跟九宮格條式：`unit` 讓 `maxHeightDp / rows`（預設 300/4 = 75dp）
封住頂，寬 screen 拉極都無法達到整個螢幕寬；窄 screen 又成段撞住 `MIN_CONTENT_DP`，
拉大拉細都是同一個寬度（使用者2026-08-28 遇到）。目前最窄一定是
`min(320dp, 螢幕寬)`、最寬一定是**整個螢幕**，中間平均拉。

### `PadAlign.SPLIT`：英數鍵盤在寬 screen 拆做兩半

`Prefs.alignOptions(ctx, group)` 提示知**目前可選邊多個**顯示方式：

- `LATIN` + 螢幕寬過 `Prefs.SPLIT_MIN_WIDTH_DP`（500dp）→ 只有 `STRETCH` 與 `SPLIT`
  （靠左／靠右時收合 —— 這麼寬的螢幕靠近一邊，另一邊那部分空間就是浪費了）
- 其餘（`CJK`、或者窄螢幕）→ 原本三個，沒有 `SPLIT`

三樣內容然後這資料表行，加新 mode 記得三樣一起改：

1. **`Prefs.align()` 會過濾**：儲存的值不在 `alignOptions` 內就當 `STRETCH`
   （摺機開合／橫向之後可選擇的內容會變，舊 profile 不可以強行用寫入）。
2. **`Prefs.nextAlign()`** 才是「按一下轉下一個」，`PadAlign.next()` 已刪除 ——
   自己 `ordinal + 1` 就會轉到不得選那個。
3. `OptionBarsView` / `SidePanelView` 兩個 `refreshAlignLabel()` 個 `when` 都要寫齊
   （側邊欄是中文專用，不會進入 `SPLIT`，但一樣要有那個 branch）。

排位在 `RowsPadView.buildLayout()`：每行用 `splitRow()` 由左邊夾達一半 weight
斬開（`asdfg` | `hjkl`、`⇧zxcv` | `bnm⌫`），兩半各 `PadMetrics.halfW` 這麼寬，
一橛貼 `0`、一橛貼 `w - halfW`。

**`SPLIT` 之下最寬 = 螢幕寬度減 `PadMetrics.MIN_SPLIT_GAP_DP`（80dp）**
（2026-08-28 使用者遇到）：不封住個頂，`widthScale` 拉到盡（或者長按顆大小按鍵
「一下子拉到最寬」）就會兩半合併鋪滿成行，中間條罅變 0，看起來與「拉寬」
完全一致 —— 使用者以為分割壞了。`contentW` 條線性式最寬那端然後收窄，
所以由最窄拉到最寬成段都有用。**斬到一半那顆剛好是 `␣` 就拆它做兩個**
（`k.copy(weight = k.weight / 2f)`），不是得左邊有 space，右手姆指無法操作。

`EmojiPadView` 與 `ClipboardListView` 不是 `KeyboardBaseView`，
高度靠 `forcedHeightPx`（開之前在 `rememberPadHeight()` 記低上一個 pad 幾高），
所以**一定要在 `padHolder.removeAllViews()` 之前記**，不是就取到 0。

### 底下閃開導覽列那條要有底色

targetSdk 35+ 之後 IME window 持續去到螢幕最底，`outer` 個
`setOnApplyWindowInsetsListener` 加 bottom padding 閃開導覽列（系統的
「收起鍵盤／轉鍵盤」就在那度）。**那部分 padding 是 `outer` 自己的底色**——
不 set 就透透出下方應用程式的畫面，那塊區域的顏色不同，外觀會很突兀，所以 `outer.setBackgroundColor(theme.background)`
（`root` 那個 background 無法覆蓋 padding 區）。

### 開鍵盤那次要補度尺寸（2026-08-28 加）

由**沒有到有**彈鍵盤出來那次，視窗未必立即報得回正確的寬度／導覽列高度，量出來成塊
鍵盤高過視窗，最底那行就讓導覽列遮蓋 —— 要拉一拉高度或者轉一次橫直先回到正常。

`TTInputMethodService.scheduleSizeRecheck()` 在 `onWindowShown()`／
`onStartInputView()`／`onConfigurationChanged()` 三位置排隊，之後每
`SIZE_RECHECK_MS`（100ms）補度一次，總共 `SIZE_RECHECK_TRIES + 1` 次
（＝ 100…400ms）。每次做兩件事：

1. `ViewCompat.requestApplyInsets(outer)` —— 有些機第一次不會派 insets 落來，
   底下就不會閃開導覽列。
2. `fixPadSizeIfOff()`：取**目前**個 `padHolder.width` 重新計 `PadMetrics.padHeightPx`，
   與當前鍵盤的實際高度比。**不同才** `relayoutPads()` + `root.requestLayout()`
   + `refreshBars()`。確實重排後，再額外執行一輪（`SIZE_MAX_FIXES`，視窗需要在下一個
   layout pass 先跟得上）。

**除了塊 pad 自己幾高，仍要比「放不放得落」**（`padHolder.height >= pad.height`，
2026-08-28 使用者遇到）：橫向改完高度 → 關閉屏 → 轉直 → 解鎖，塊 pad 自己是量回
直向那套（正確），但視窗仍是停留在橫向那高度，`padHolder` 讓裁短了，最底成行
消失。只比較 pad 就當一切正常，就永遠不會重新執行。

但「`padHolder` 矮過塊 pad」不一定是出事：鍵盤本身拉到高過個螢幕（橫向時很容易），
視窗限制為盡都一定裁到。所以重排之前記低度到的尺寸（`lastFixState`），
**重排完完全一致就不再試**；一旦恢復正常尺寸就清除，下次再遇到同一個錯誤尺寸
仍然執行。

**必須「測量結果不正確時才重排」**，不可以強制執行一次 —— 否則每次開啟鍵盤都會看到畫面跳動。
最後那句 `refreshBars()` 不可以慳：側邊欄高度固定為 `PadMetrics.totalHeight` 的
（見上面「中文拉窄就不要上面工具列」），不重新補回就會然後錯埋。
emoji 表／剪貼簿跟 `forcedHeightPx`，不在此處計（`as? KeyboardBaseView` 濾走）。

---

## 工具列 不可以出不回來

`EmojiPadView` 與 `ClipboardListView` 沒有自己的「關閉」按鍵 —— 顆 `✖` 統一在
`OptionBarsView` 最左。所以 `refreshBars()` 見到 `specialPad`
（`mode == EMOJI || overlay != null`）就一定要 **force `BarMode.TOOLS` + 出顆 ✖ + 不得 GONE**，
不是 使用者關閉工具列 之後開 emoji 就無法返回去普通鍵盤。

`showOverlay()` / `hideOverlay()` 兩邊都會叫 `refreshBars()`。

### 工具列常駐（`Prefs.barPinned`，2026-08-27 加，2026-08-28 起**預設開**）

開啟後之後工具列 關不關閉得，而**九宮格右上角那按鍵已更換個意思**：

| | 按鍵 | 工具列 最左 |
| --- | --- | --- |
| 關閉 | `☰`＝開／關成工具列 | `⇄`＝關聯字 ⇄ 工具 |
| 常駐（預設） | `⇄`＝關聯字 ⇄ 工具 | **沒有**（`setSwitchVisible(false)`） |

三處要一起夾：

- `TTInputMethodService.toggleBar()` 第一句就分流去 `onSwitchView()`。
- `refreshBars()` 開頭見到 `pinned && barMode == OFF` 就當場升做 `CANDIDATES`
  **而且寫回落 pref**（設定頁㩒按鍵不會 restart 個 service）。
- `ChinesePadView.optionKey()` 換鍵面；`optionOn`（是否亮起）常駐時代表
  「目前在工具那邊」，不是「工具列 保持開啟」—— 持續著住藍燈沒有資訊可言。

**`setSwitchVisible` 只在中文九宮格中隱藏顆 `⇄`**：英文／符號頁根本沒有右上角
那按鍵，已隱藏就永遠無法進入工具列。`✖`（emoji 表／剪貼簿）永遠優先，
兩個共用一個位置（`refreshLeftBtn()`）。

**英文／符號頁也強行重新開啟工具列**：這兩頁靠它出打字提示與滑出來的字，
沒有就等於打盲舖。`refreshBars()` 見到 `mode` 是 `LATIN`／`SYMBOL` 而
`effective == BarMode.OFF` 就升做 `CANDIDATES`。**不會改到 `barMode` 本身** ——
回到中文頁仍然依照 使用者設定那個開關。

## 工具按鍵的圖案：自己畫，不用 emoji

工具列（`OptionBarsView`）與側邊欄（`SidePanelView`）那幾顆按鍵本來直接寫
`📋` `🎤` `😀` `✨` 落 `TextView` 度，2026-08-25 全部換成 `ime/ToolIcons.kt`
內自己畫的**單色** `ToolIconDrawable`。三個不用 emoji 的理由，改之前記住：

1. emoji 一律由系統的彩色 emoji 字型畫 —— 鍵盤其餘全部單色，夾埋一起好突兀；
2. 每裝置每個 Android 版本的 emoji 字型都不同，畫出來的大小與顏色都不受控；
3. `setTextColor(Theme.text)` **無法套用於彩色 emoji**，深色主題一樣是那個彩色樣。

畫法：一律在一個 **24×24 的座標**度砌，`draw()` 先透過 `canvas.scale()` 縮放至顆按鍵實際
這樣大，所以任何 dp 都不會起格。**顏色在 constructor 傳死**（跟 `Theme.text`），
沒有實作 `setTintList` —— 轉主題是 `styleTool()` 新的完整 drawable。
兩個 view 都有一個 `icons: LinkedHashMap<TextView, Pair<ToolIcon, String>>`
記住邊顆按鍵用哪一個圖案，`applyTheme()` 就是照住它重畫一次。

**不可以用 compound drawable** —— `TextView` 個 `gravity` 只管些字：左格那個
drawable 永遠貼死 `paddingLeft`（只上下置中），上格那個就永遠貼死 `paddingTop`
（只能左右置中），兩樣都不會確實放正中間（實測過，些圖案全部黐全顆按鍵左邊）。
所以 `iconChip()` 用 `LayerDrawable` 疊在圓角底色上面，
`setLayerSize()` + `setLayerGravity(CENTER)`，甚麼情況都正確。
順帶：按鍵的底色與圖案目前是同一件 drawable，所以 `refreshSttLook()`
（錄音時亮起）不可以再只 `background = chipBg(...)`，必須繼續呼叫 `styleTool()`。

**顯示方式那顆（`refreshAlignLabel`）不是單獨的左／右箭咀** —— 單獨箭頭看起來似
「向左移／向右移」，但實際上是「貼近左邊／貼近右邊」，所以畫成
**一條牆 + 一支箭嘴指住埋去**（`ALIGN_LEFT` / `ALIGN_RIGHT`）；
「拉寬」（`STRETCH`）就兩邊都有牆、箭嘴向外撐開（`ALIGN_WIDE`）。
留意 `PadAlign.LEFT_GAP` 是「**左**邊留白」＝ 內容貼**右**，所以它配 `ALIGN_RIGHT`，
兩個名是對調的，改時看清楚。

`✖`（關閉）、`⇄`（切換）、`▼`（拉大候選）三個**沒有換** —— 它哋本身就是單色
文字符號，不是彩色 emoji。`PadFunc.EMOJI` 按鍵面也由 `😀` 已改寫「表情」，
目前整個 `PadFunc` 一個 icon 都沒有，全部寫中文。

## `Spinner` 不可以用「跳過第一下 callback」那招

`SettingsActivity.FuncPicker`（左上角鍵的短按／長按）試過用一個 `ready` flag
擋開頭那次 programmatic `onItemSelected`，**中過伏**：第一下何時 fire（甚至
fire 不 fire）是看 layout 時序，擋錯了就會攔截了 使用者真正那次 —— 按鍵看起來移動了，
但 pref 沒有改過、上方標籤也仍是舊那個，然後去另一個 spinner 選回同一樣
內容就會冤枉人「功能重覆」。

目前改成**與 pref 目前真正儲存的值比**：一樣就當開場／回位任何事都不做，
不一樣才算 使用者選過內容。標籤 每次都由 getter 重新讀，就算 `onPick`
拒絕了都不會與 pref 不夾。

順帶一提 `Prefs.topLeftLong()` 選取重複功能後短按時**只計出** `NONE`，個 pref 內
仍是舊那個 —— 改完短按要自己 `setFunc(KEY_TL_LONG, NONE)` 明確寫入，
不是個 spinner 與 pref 就會各講各話。

## 震動分級：舊個 boolean 沒有刪

`Prefs.KEY_VIBRATE`（boolean）已改做 `KEY_VIBRATE_LEVEL`（0～3，預設 1）。
舊 key **沒有刪**，仍要持續顯示：

- `vibrateLevel()` 見到未寫過新 key，就由舊個 boolean 轉回過來（開 = 1、閂 = 0）——
  update 上來的人不會無啦啦震回全。
- `setVibrateLevel()` 同時 `putBoolean(KEY_VIBRATE, v > 0)`，萬一有哪裡仍讀取中的
  舊那個都不會與新設定不夾。

級數對應的時間／震幅在 `Prefs.vibrateDurationMs()` / `vibrateAmplitude()`，
**level 1 一定要是舊那個力度**（12ms / 40）—— 那個是以前唯一的設定。
部分機款（例如部分 Sony Xperia）沒有 `hasAmplitudeControl()`，硬傳 amplitude
會完全不震，所以那些機回退 `DEFAULT_AMPLITUDE`，只靠時間長短分三級。

2026-08-25 使用者表示 level 3 仍是不夠明顯，**2／3 兩級由 18／26ms 拉長到 34／60ms**
（震幅也由 110／200 加到 170／255）。要再調就繼續加時間 —— 好多機的震幅
是封了頂的，真正感覺到「大力了」的是震耐了。**0 與 1 不得移動。**

## 設定頁的 `slider()` 有 step 與 format

`SettingsActivity.slider()` 收多兩個 optional 參數：`step`（拖一格跳多少，
例如長按時間逐 10ms 一格）與 `format`（值點寫，例如震動級數寫「1（最輕）」）。
`SeekBar` 只認整數 progress，所以 progress 是**第幾格**，值 = `min + 格數 × step`。
`onChange` 一直是**放手先叫**（`onStopTrackingTouch`），拖動期間只改上面文字。

## 讓人看的字：全部正體中文書面語

app 內所有 使用者見到的字（設定頁、toast、鍵面、空狀態提示）一律用
**正體中文書面語**，不用廣東話口語（「沒有」→「沒有」、「按」→「按」、
「目前」→「目前」…）。**註解與 commit message 無需遵循**，仍然用口語。

### 而且要短（2026-08-29 使用者遇到「描述過長，很多廢話」）

設定頁每個 `note()` **最多兩句**，講「開啟後會點／要注意甚麼」就夠。不要寫：

- 設計理由（「否則等於浪費一格」、「按錯一下不應該影響往後的選字」）——
  該些內容應寫入程式碼註解與本 AGENTS.md，而不是顯示給使用者。
- 內部細節（震動多少毫秒、`?123` 個 hint 點來、哪一個版本改過甚麼）。
- 鍵面上按一下就見到的內容（「⌫ 就在 ⏎ 上面」）。

一般 使用者不會逐段看，寫長了等於沒有寫。

全名叫「**三三正體中文輸入法**」，通常叫「**三三輸入法**」，簡稱「**三三**」；
英文「**ThreeThree**」，簡稱「**TT**」。系統輸入法選擇視窗那行位窄，出簡稱
（`ime_name`）；launcher 與應用程式清單出全名（`app_name`）。

**不可以在 app、文件、commit message 內點名任何輸入法品牌**——
講個專利就只寫「使用已過期專利 HK1035043」，不要帶埋邊間公司。
（2.0.0 商標避嫌改的，見 CHANGELOG。）

## AI 設定頁分三大類

「AI」分頁分為 **語音輸入 (STT)** / **AI 改寫** / **AI 設定** 三段，每段均可收合
（`SettingsActivity.collapsible()`）。頭兩段各自有個總開關，第三段是
**兩邊共用的 provider 設定**（API key、模型名稱、自訂 API、profile）——
不要分開兩份 key 或者兩個 model 出來。

已隱藏未是 activity 的 instance state（`aiOpenStt` / `aiOpenRewrite` / `aiOpenSetup`），
不入 pref：重新開應用程式就當三段都展開。改任何一個開關都是整個
`rebuildAiSection()` 重畫，所以 `collapsible()` 收合時不建立任何 view。

## AI 改寫（✨）

- **不用選取字都可使用**：選了就只改選了那段，沒有選就當「改寫整個輸入框」——
  `runAi()` 會 `setSelection(0, 全長)` 再交出去。**出回來之前要再全選一次**：
  等待 Gemini 回應的數秒間 使用者隨時按過欄位，一按 selection 就會消失，
  `commitText` 就會變成插埋寫入而不是取代。
- **沒有入 API key、或者設定頁關閉個總開關（`Prefs.aiRewriteOn`），
  整個按鍵都會隱藏**（`setAiVisible`，不是只灰了）。
  灰了那個狀態留回讓「有 key 但欄位空了」。
- 按不按得由 `applyAiState()` 決定，`onUpdateSelection` 每次都會重新計
  （不可以好似以前這樣「選的狀態沒有變就 return」—— 目前欄位有沒有字都影響到）。

## AI 語音輸入：頂走系統那個 `SpeechRecognizer`

`Prefs.aiSttOn` 開啟且已設定 key 時，顆 🎤 始終不再接觸
`SpeechRecognizer` —— `toggleStt()` 第一句就分流去 `startAiStt()` / `stopAiStt()`。
兩條路**完全分開**，`listening` / `recognizer` 那套內容一個都不會 set。

- **只 Gemini 做得**：段錄音要用 `inline_data` 這個 Gemini 專用格式送上去，
  設定頁那套自訂 API 範本（URL／headers／body）無法表達。所以
  `Prefs.aiSttOn()` 見到 `aiUseCustom` 就**一律回 false**（不理個 pref 之前開過），
  設定頁那個開關也會鎖住。加新 provider 之前請先諗清楚點送段錄音。
- **錄音**用 `VoiceRecorder`（`core/AiStt.kt`）：`AudioRecord` 收 16kHz mono PCM。
  特意**不用 `MediaRecorder`** —— 它一定要寫落檔案，而且各家機出來的容器
  不一定正確 Gemini 收。段 PCM 點包由 `SttAudio` 決定（見下面）。
- **兩種操作**：按一下開始、再按一下停（`hold = false`）；按住持續錄、放手即停
  （`hold = true`）。後者要 `OptionBarsView.Listener.onSttHoldStart()` /
  `onSttHoldEnd()` 兩個 callback，以及 `KeyboardBaseView.Host.onLongPressEnd()`
  （左上角選了做 🎤 那按鍵用）—— 平時沒有人要知「長按何時放手」，所以那個
  interface method 有 default 空 body。
- **顆按鍵個 `OnTouchListener` 一定要回 `false`**：`ACTION_UP` 在 `performClick`
  之前到，回 `false` 才保得住「按一下 = 短按」。回 `true` 就再沒有短按。
- **左上角那顆 🎤 只在長按位留空才按住錄**（`key.longAction == NOOP`），
  不可以攔截了 使用者特意在設定頁選的長按動作。
- **錄音及等待結果期間，整個鍵盤變灰兼無法操作**：`showBlockingOverlay()`（AI 改寫
  那個 loading 都是使用它）。所以「再按一下停」是按那塊 overlay，不是按回顆 🎤。
  高度**固定為 `root.height`**，用 MATCH_PARENT 會擴大了整個 IME window。
- **四個階段四種不同的提示音**（`SttTone`）：開始錄 `TONE_PROP_BEEP`、錄音結束
  `TONE_PROP_BEEP2`、成功 `TONE_PROP_ACK`、失敗 `TONE_PROP_NACK`。
  這些與 `Prefs.sound`（按鍵聲）**沒有關係**，不跟那個開關。
- **逾時／離開欄位要記得清**：`sttGeneration` 與 `aiGeneration` 一樣是用來
  當第遲到的 callback；`onFinishInputView` / `onDestroy` 行 `cancelAiStt()`。
- **放手之後先篩一篩，不要任何事都掟上去**（2026-08-25 加）。`VoiceRecorder.stop()`
  回一個 `VoiceClip`，三種：

  | | 何時 | 點處理 |
  | --- | --- | --- |
  | `TooShort` | 短過 `VoiceRecorder.MIN_CLIP_MS`（400ms） | 當按錯，不叫 API |
  | `Silent` | 夠長但 `VoiceActivity.hasSpeech()` 話沒有人聲 | 當按錯，不叫 API |
  | `Ready` | 其餘 | 送上去 |

  `VoiceActivity` 是能量式 VAD：逐 20ms 一格計 RMS，最響那些都細過 `ABS_PEAK`
  就當死靜；再用「噪音底（第 20 百分位）× `SNR_RATIO`」做動態門檻，夠
  `MIN_VOICED_FRAMES`（8 格 = 160ms）響過門檻先當有人講內容。**寧鬆莫緊** ——
  遺漏一次最多浪費個 API call，但錯手擋了他人低聲講那句，使用者就會覺得顆按鍵壞了。
  改門檻一定要跑 `VoiceActivityTest`（純 JVM，內有「嘈但沒有人講內容」與
  「低聲講都不可以擋」兩個對照 case）。
- **壓縮不可以在 `stop()` 度做**：`stop()` 是在 main thread 叫的（放手那次），
  一分鐘錄音 encode 落 AAC 要成幾百毫秒，放在那度就會停頓。所以 `VoiceClip.Ready`
  內只原始 PCM，`AiStt.transcribe` 在自己條背景 thread 度才叫 `SttAudio.encode`。
  VAD 就相反 —— 處理完整段落只需數毫秒，而且必須立即取得結果，才能決定是否呼叫 API，
  所以仍然在 `stop()` 度行。
- **`SttAudio` 首選 AAC-LC，回退至 WAV**：16kHz mono 24kbps，一分鐘 ~180KB
  （WAV 要 ~1.9MB）。容器是自己逐 frame 加 7 byte **ADTS header** 出來的裸
  AAC stream，**不經 `MediaMuxer`** —— 當初不敢用 `MediaRecorder` 就是因為
  各家機出來的容器不一定正確 Gemini 收，ADTS 自己砌就每個 byte 都持有得住。
  `BUFFER_FLAG_CODEC_CONFIG` 那段（AudioSpecificConfig）**不可以**寫至 stream 度，
  ADTS header 本身已包含同樣的資料。裝置沒有 AAC encoder、或者中途 fail
  （包括 `ENCODE_DEADLINE_MS` 逾時）就回退至 WAV —— **不可以**因為 encode 失敗
  就當今次語音輸入失敗。
- prompt（`Prefs.DEFAULT_AI_STT_PROMPT`）逐條明確列出禁止模型進行的操作 —— Gemini 很容易
  加句「以下是錄音的轉錄內容：」，也容易擅自潤飾句子。改 prompt 時
  不要已刪除「只輸出結果」與「逐字轉錄不要潤飾」這兩條。

## 候選欄出甚麼（中文，2026-08-27 重寫）

`refreshBars()` 在中文模式分三種情況，**兩種是「不關 engine 事」的**
（`showingContextPicks = true`，按下去要行 `TTEngine.pickQuick()`，
**不是** `pickCandidateAt()` —— 因為此時從未進入 selectMode。`pickQuick()` 內部是
`startSelectWord(listOf(word))` + `selectWord(1)`，所以簡繁輸出、同音、關聯字全部照行）：

| 狀態 | 出甚麼 |
| --- | --- |
| `engine.selectMode` | `engine.selectWords`（仍然，按 = `pickCandidateAt`） |
| `currCode` 有 1~2 個碼 | `codePreview()` → `TTDb.topByCodePrefix`，最常用那 9 字 |
| 任何事都未打 | `contextPicks()` → **游標前面那字**的關聯字，沒有就 `mapped_table` id `1010` |

- **速選字表（id 1000）不再在工具列 出現**（以前留空就出它）。`quickPicks` 個 field
  已刪除，換成 `defaultPicks`（id `1010`）。速選字表仍然由左上角按鍵／長按 1~9 開得到。
- **`contextPicks()` 特意不用 `engine.relateHints`**（＝「剛好打完那字」）——
  使用者按過輸入框移動了游標、或者剛好開鍵盤，`relateHints` 已經是舊內容。
  目前逐次 `getTextBeforeCursor(2, 0)` 取回游標前面那個 grapheme 去查。
  所以 `onUpdateSelection` 在中文、`!engine.busy`、且不在 emoji 搜尋模式時要 `refreshBars()`
  —— 游標一移動，工具列 就要換。（`TTEngine.pickRelateAt` 沒有人叫，但沒有刪。）
- **開啟後「輸出簡體」欄位內是簡體**，但 `related_candidates_table` 只有正體，
  所以無法查詢就 `TTDb.sctc()` 轉回正體再查一次。`sctc` 是由 `ts_chinese_table` 反轉出來的
  （多對一，第一個當代表）—— **只可以取來查表，不可以取來做輸出**，出街的字一律行 `tcsc`。
- **`topByCodePrefix` 個 LIKE 一定要連與顆逗號**：`word_meta.code` 每個打法都以 `,`
  開頭（「為」＝ `,470,480,970`），所以 pattern 是 `%,<prefix>%`。打 `4`／`47`／`48`
  都找得回「為」，而 `70` **不可以**能正確匹配 `,970`。同一個字有多個打法（幾行記錄），
  要 `GROUP BY char ORDER BY MAX(freq)`。同一個碼查一次就 cache 住（`codePreviewFor`），
  不是每按一下鍵都查次 sqlite。

## 選字放入九宮格：**第一頁永遠 `1`~`9`**

只有一項規則，寫在 `TTEngine.slotOrder(page)`：

| 頁 | 排法 | 為何 |
| --- | --- | --- |
| **第一頁**（`page == 0`） | `1` 排到 `9`，與字碼表次序完全一致 | 那個格號**就是字碼的最後一個數字**（狀態列「碼:」寫的內容、已習慣的手勢全部靠它）。一調位就立即全部作廢 |
| **第二頁開始**（`page > 0`） | `SLOT_ORDER`＝`5 4 6 2 8 1 3 7 9` | 那些字本來就沒有碼可以記，一定要望住選，所以哪一格容易按放哪一格：`5` 在正中最容易按，然後四邊（`4 6 2 8`），四角（`1 3 7 9`）最後。該頁得三字就只佔 `5 4 6`，四角留空 |

**第一頁沒有任何例外。** 2026-08-28 一日之內試過三個版本，最後定了上面這個：

1. 整資料表（連第一頁）都用 `SLOT_ORDER` —— 使用者叫「嚴重錯誤」，即日收回
2. 「整張資料表只有一頁就整頁使用 `SLOT_ORDER`」—— 收回
3. 「只有一頁時，最後按那個碼排最前（`159` → `9 5 4 6…`）」—— 收回

所以下次見到「一版可選全／剛好按完哪一個碼」這類特例，**不要自己補回至第一頁**。

「常用字排前」不會調整第一頁（頭 9 個永遠無法移動），推得最前都是第 10 位 ——
`reorderByUsage()` 只換 `selectWords` 個次序，格號永遠由 `slotOrder()` 決定。

引擎內兩個私家 helper `slotAt(rank, page)` / `rankAt(slot)` 幫你填回 `currPage`，
一定要**成對這樣用**。凡是「格號 ↔ `selectWords` 內第幾個」的換算全部要行它哋，四處：

- `showPage(page)`：`rank` → `slotAt(rank, page)` 寫入 `keys[]`（這個要傳 `page`，
  因為 `currPage` 未 set 好）
- `selectWord(slot)` / `homoAt(slot)`：`slot` → `rankAt(slot)` 取回 index
- `plausibility(digit)`（滑動評估，選字模式那段）：一樣要換
- `pickCandidateAt(index)`（工具列 按下來的絕對位置）：**先** `currPage = index / 9`，
  然後 `selectWord(slotAt(index % 9))`

遺漏其中一處就會「見到的字」與「按下去出的字」對不上，而且無法測試——
`ChinesePadView` 只照 `engine.keys[d]` 畫，它自己不知個次序。
`SlotOrderTest` 盯住個次序表本身，`UsageReorderTest` 盯住「常用字排前」無法移動頭九位。

## 同音字就是一個 flag，不要再加內容

`TTEngine.pressHomo()` **只** `homo = !homo`，依照 Windows 原版：
打字後不出字，要輸入字碼並選字才彈同音字表出來。試過改成「一按就立即開表」
（有 `lastWord` 就開它的同音字，沒有就開速選字表），使用者表示這會打斷打字流程，**收回了**。
按一下只著／關閉顆按鍵（會變藍），不可以更換目前文字表。

### 同音字表尾會補一橛「近音字」（2026-08-30 加）

`TTDb.getHomo()` = `exactHomo()`（`ping` 完全一致，聲調都夾那些優先）
**＋ `nearHomo()`（近音，一律排在最後）**。近音只為了補足遺漏 —— 不會擠走本來
選開那幾字的位。

如何區分近音：**只看 `word_meta.ping` 這一欄**，沒有任何「哪個字似哪個字」的
硬寫對應表（2026-08-30 使用者明確明確說明要這樣做）。兩個 `ping` 行過
`TTDb.fuzzyPing()` 歸一化為同一個結果就當近音，即「正在選擇 `ngo`，同時連 `o`
那堆字一起找」。

`fuzzyPing()` 分兩截，看該函式個 doc 已包含例：

1. **先夾回同一套拼音。** `word_meta.ping` 主要是耶魯（`ji`＝之、`yi`＝二、
   `cheui`＝取），但夾雜少量粵拼串法的冷字（`zi`＝衹、`ceoi`＝綷、
   `coek`＝焯）。不統一的話這些字連「同音」都不會進入。
2. **然後才是懶音／近音**：`ng-` 移除聲母、`n-`/`l-` 不分、`gw-`/`g-`、
   `kw-`/`k-`、`aa`/`a`、`-n`/`-ng`、`-k`/`-t`。

規矩無法匹配但想擠在一起那些，寫在 `EXTRA_GROUP`（一樣是**拼音對拼音**）：
目前得兩行 —— `o`→`a`（「我」要找得回「啊」，使用者指定）與 `n`→`m`
（「五」與「不」兩個純鼻音字）。加減規矩修改後請執行 `FuzzyPingTest`。

規則放寬多少必須適度：實測全表 682 個 `ping` 揉成 380 組，最大一組
82 文字（`ji`＋`zi`）。再放寬（例如連 `-m`/`-n`、`-p`/`-t` 都當一樣）
就會多到揭幾頁都選不完，所以沒有加。

### 左上角 = 長按執行哪項功能，不要在此顯示即時狀態

整個應用程式遵循一項規則：**按鍵左上角小字一律表示「長按會執行的操作」**（`drawCornerHint`）。
同音鍵以前破了這條規矩（左上角顯示即時提示），2026-08-28 改回：

| 位 | 放甚麼 | 對應程式碼 |
|---|---|---|
| 左上 | `Key.hint` ＝長按那個 `PadFunc.icon`（預設「關聯字」）| `ChinesePadView.funcLongKey` |
| 左下 | 即時提示（`homoWord` / `homoCodeHint`）| `drawCornerHintBottom` |

同一條規矩之下同時補回：`?123` 左上角寫 `123`（長按直入 numpad；2026-08-29 起
**只中文九宮格那顆有**，英文那顆太窄，見「英文鍵盤排位」），
`Eng` 左上角畫個地球 `ToolIcon.GLOBE`（長按轉輸入法 ——
顆獨立 🌐 按鍵已隱藏之後，沒有這個 icon 就沒有人知按得長按）。個地球是
`drawCornerIcon` 畫的**單色** vector，不要改回寫 emoji 🌐（鍵面其餘全單色）。

### 四個位置選功能，不得重複

`Prefs.FUNC_SLOTS` = 左上短按 → 左上長按 → 同音長按 → 右上長按，**列出的
次序就是優先次序**。四個位置不得做同一件事（`PadFunc.NONE` 例外，可以全部停用），
左上短按仍不得 `NONE`。

- 讀：一律行 `Prefs.funcSlot(ctx, key)`（`topLeftTap` / `topLeftLong` /
  `homoLong` / `topRightLong` 都是它的 wrapper）。與**排在前面**那位置就回
  `NONE`。這段是處理舊 pref，正常情況下不會進入。
- 選：**不得重複不是選完再提示不允許**，而是 `SettingsActivity.availableFuncs()`
  一開始就不會將已經有人用那些放入個 spinner。試過「選選取重複功能後就 toast 重新顯示回」
  以及「選選取重複功能後就無聲地關閉第二位置」，兩樣都差 —— 前者要嘗試後才能得知，
  後者移動了 使用者沒有叫你移動的設定。所以 `FuncPicker` 個 options 是個 lambda，
  每次 `sync()` 都重新問過。
- 更換 adapter 時 `Spinner` 會 select 回第 0 個兼有機會立即 `onItemSelected`，
  所以 `FuncPicker.fill()` **一定要**拆走個 listener 先，放回真正 selection 之後
  先駁回，不是就會當了 使用者選了第 0 個。

同音鍵與右上角那顆**短按功能不可更改**（開關同音／開關上面工具列），只長按可選；
兩個都是 `ChinesePadView.funcLongKey()` 幫按鍵補回 `hint` ＋ `longAction`。
長按住際如何運作完全靠 `TTInputMethodService.onLongPress` 開頭那句
「`key.longAction != NOOP` 即呼叫一次 `onKey`」—— **不要**再在下方的 `when`
為某個按鍵固定執行哪項功能（同音鍵以前固定為 `TTCmd.RELATE`，設定選「停用」仍會執行）。

同音鍵**左下角**一個位置顯示三種內容，顯示的次序就是優先次序：

- `TTEngine.homoWord` = 目前正在查詢哪個字的同音（兩條入口都會 set）。成頁都是
  同音字，如不顯示該字，就無法知道是哪個字的音。`cancel()` 會清走它。
- `TTEngine.currCode` = **目前打了的碼**（`1` → `12` → `123`，2026-08-31 加）。
  設定頁「顯示目前已輸入碼」（`Prefs.KEY_SHOW_CURR_CODE`，預設開）關閉得。
- `homoCodeHint` = 用同音字輸入完成後，該字**正確如何輸入**（`db.getCode()`），
  提示使用者正確按哪幾個按鍵。再輸入一個普通字就在 `selectWord()` 中清走。

頭兩個不會撞（同音字表那條路不留碼，見下面 `keepCode`）；第二與第三就會 ——
「用同音字打完，然後輸入中的下一個字」那次讓了位置讓輸入中的那個碼，即時狀態優先於
回應提示。

三樣都是 `ChinesePadView.drawFunction` 即時問 engine 取，不是 `Key.hint`，
因為 `boxes` 不會逐次重建。

### 入了選字模式**不會**清走 `currCode`（`startSelectWord(keepCode = true)`）

打第三個碼那次一按下就入了選字模式，所以以前左下角個碼永遠只見到頭兩個 ——
第三個一按就沒有，看不到自己到底按了甚麼。2026-08-31 已加入個 `keepCode`：
**只 `processResult()` 那條路**（資料表確實由當前字碼查出來）保留字碼，
其餘入口（速選字、關聯字、同音字表、成對標點）仍然清。

保留字碼**只影響顯示**：選字模式下每個讀 `currCode` 的位（`press`、`cmd`、
`backspace`、`plausibility`、`shortcutDigit`、`ChinesePadView.instantKey`、
`TTInputMethodService` 工具列）都是**先看 `selectMode`**，不會繼續執行；
`engine.busy` 也本來就已經 `selectMode || …`。選完字／取消由 `cancel()` 清。
**注意有些字（例如「的」「在」）根本沒有 `word_meta` 記錄**，`getHomo()` 回空，
時 `selectWord()` 會直接 `cancel()`。

除了個 flag，還有第二條路入同音字表：**選字模式長按那格**
（`TTEngine.homoAt()`，2026-08-25 加）。兩條路出來的表完全一致（同一句
`db.getHomo()`），也一樣會 set `afterHomo`，所以選完仍然在同音鍵左下角
寫回文字正確如何輸入。`homoAt()` 不會接觸 `homo` 個 flag 以外的內容，
開關標點模式（`openclose`）就直接不做 —— 時資料表是「」這些一對對的標點。

## 搜尋 emoji 不會實際將文字寫入欄位，但會 set 做 composing text

`emojiSearch` 保持開啟時，`typeChar()` 與 `TTEngine.Host.commitText()` 兩邊
都會攔住些字入條 `emojiQuery`，結果出在關聯字工具列，**不會** `commitText`。
但為了等 使用者見到自己輸入中的甚麼（不是就只有關聯字 bar，看不到個 input），
每次 `emojiQuery` 一變就會 `syncEmojiComposing()` set 做 composing text，
選了 emoji 或者打多文字都會自然取代／清走（`commitText` 蓋了 composing 區），
`endEmojiSearch()` 見到還有殘留就 `commitText("", 1)` 清走。
`switchMode()` 去到 LATIN／CHINESE 以外就會自動關閉它。

**搜尋時，英文鍵盤底行只有兩個按鍵：「退出表情搜尋」＋ `␣`**（2026-08-25 使用者要求）。
`?123`、`中`、`⏎`、標點在這頁一個都用不著（些字只用來篩，不會入至欄位），
顆退出按鍵也**以文字明確標示**，不再只顯示 😀 沒有人知按下去執行哪項功能。
代價：搜尋期間無法達到中文九宮格打中文關鍵字（要先退出再由中文鍵盤開 emoji）。

搜尋個「放大鏡」一律用**單色** `⌕`（`KeyDef.kt` 的 `SEARCH_GLYPH`），
不用彩色 emoji 🔍 —— 搜尋欄的 `⏎`（`enterLabelFor`）與 emoji 表那顆找字按鍵兩處都是。
字型沒有 `⌕`（`Paint.hasGlyph`）就寫回「搜尋」兩字，不可以出一格豆腐。

## 中文使用習慣統計：bigram 與每字次數

`UsageStats`（`usage_stats.db`，與 `dataset.db` 分開存）記兩樣內容：連續打的
兩個中文字（`TTEngine.bigramPrev`）、與每個字輸入了多少次。只計**單字**
（`isHanChar()` 篩走標點與多字詞），選了多字詞、標點、或者已更換行
（`onLineBreak()`）都會中斷 bigram 鏈，不會強行合併不正確的組合。

設定頁「使用習慣統計」那段可以**匯出／匯入／清除**整個 `usage_stats.db`
（`UsageStats.exportTo` / `importFrom` / `clear`）。三樣內容一開頭都要行
`closeSync()`：背景 io thread 中尚未完成的寫入必須等待完成、WAL 要 checkpoint，
不是抄出去那份會少了最後幾下；`instance` 清走之後下次 `get()` 會重新重新開啟檔案。
匯入要驗到已包含 `bigram` / `char_freq` 兩張表才覆蓋，覆蓋之前連
`-wal` / `-shm` / `-journal` 都要一併刪除（不清就會取住舊 WAL 蓋回到新 db 度）。

同一段還有「**常用字排前**」開關（`Prefs.KEY_USAGE_REORDER`，預設開）：
關閉只 `TTEngine.usageReorder = false`（`reorderByUsage()` 立即 return），
**仍然繼續記數**。設定頁改完不會 restart 個 service，所以 `onStartInputView`
每次都要重新讀一次。

`TTEngine.reorderByUsage()` 只在 `processResult()`（字碼輸入與選字的主要路徑）用，
而且**頭 9 個（第一頁）一律保持不變**，保留字碼表原本的位置 —— 第一頁的格號就是
字碼的最後一個數字，調過位就立即影響已習慣的手勢。**第 10 位起（即第二頁
開始）才看 bigram 排**：常用但不在頭九位的字最多推到第 10 位，反正那些字本來
就沒有碼可以記，一定要望住選，推前了只少揭幾版。

⚠️ 這條規矩 2026-08-30 反轉過：**2026-08-28 至 08-30 期間剛好相反**（只移動
第一頁、第二頁起保持不變），使用者指出這是錯誤，因此已還原。下次見到「頭九位排列得更美觀」這類想法，
**不要移動第一頁**。實際排法已抽離為 companion 的 pure function
`TTEngine.reorderByUsage(words, count)`（instance 那個只包住開關與 `bigramPrev`），
`UsageReorderTest` 盯死它。
（順帶 `Host.charFreq` 沒有人用，一起已刪除，但 `UsageStats` 仍然繼續記單字次數。）**要打過至少 `TTEngine.MIN_USAGE_COUNT`
（＝ 2）次才移動個次序**（2026-08-27 使用者要求，以前 bigram 是 3 次）——
按錯一下不應該影響到之後的選字。用**穩定排序**（`sortedByDescending`），
沒有資格的內容（`qualified()` 回 -1）不會亂了原本次序。
讀寫都在 `UsageStats` 內：讀是 in-memory cache（`ensureLoaded()`
第一次先存取 sqlite），寫就立即更新 cache、sqlite 那邊放去背景 thread，
不會拖慢緊接著的輸入操作的 UI。

---

## 兩個容易意外覆蓋的位

### 1. 已改顯示方式但高度沒有變 → `onSizeChanged` 不會 fire

`ChinesePadView.buildLayout()` **每次都要重新 `PadMetrics(context, w)`**，
不可以 cache `onMeasure` 那個。拉長 ↔ 左留白 高度一樣，
只靠 `onSizeChanged` 就永遠不會重新排位。

### 2. 轉角要用即時方向，不可以用弦線

`GestureKeyTracker` 判「有沒有在這格轉彎」是比較**入格前 60ms** 與**出格前 60ms**
的移動方向（`dirBack()`）。如果用「入口點→出口點」的弦線，
一個真正 90° 的轉角只會計到 45°，會遺漏按鍵。這個是實測曾遇到的坑。

---

### 英文滑動要行達一整格先算

`swipeStartDistPx(box)` 明確說明拖多遠先當「確實在此滑」（開始畫線、放手會查詞庫）。
預設是一個 touch slop（中文九宮格滑去隔離格就是下一碼，要立即收），
`LatinPadView` override 成 `max(box.w, box.h) * 1.2`：單按時手指很容易出現輕微位移，
一帶就變了很短的 swipe，任何輸入都出錯字。qwerty 上面又沒有兩個字母貼住的英文詞，
所以**拉到隔離格這樣遠就放手，一律當誤觸**，照重新顯示按鍵本身。

行達一個 slop 就會 `cancelPending()`（不要再彈長按那些內容出來），但
`swiping` 要移動達 `swipeStartDistPx` 才 true。`GestureKeyTracker` 由 DOWN
那次就持續保留完整軌跡，所以夠距離之後條線／認字是由**起點**計起，沒有遺漏起點。

## 滑動判定（三個線索）

```
分數 = 幾何信心(0~1) + 0.35 × weight信心(-1~+1)  ≥ 0.62 就當按了
```

- 幾何：**明顯減速再加速**（V 形）、入格出格方向轉得夠多
- weight：`mapped_table.weight` 砌成 prefix 權重表（`TTDb.prefixPlausibility`），
  已加入這一碼後完全沒有字就 -1 直接排除，常見字碼就加分
- **起點與終點永遠計**，不會被 weight 否決

#### 中間格**不可以**只計「留了幾耐」

以前是「在格內留夠 `dwellMs` 就讓滿分」。這個是錯的，使用者報過：慢手由 `7`
一條直線拉去 `9`，在 `8` 度留的時間一樣過到 `dwellMs`，就白白多了個 `8`，
`790` 變了 `789`。**留得耐 ≠ 按了** —— 慢慢經過都會留得耐。

目前要見到速度確實「轉到去、再重新顯示上來」先算數（`decideAndEmit`）：

```
dipped  = 格內最慢的即時速度 < STOP_RATIO(0.35) × 整個 gesture 的平均速度
reaccel = 出格速度 > 最慢速度 × REACCEL_RATIO(2)
兩個都成立先有分（停夠 dwellMs = 1.0，不夠 = 0.8）
```

即時速度用 `speedBack()`（回望 50ms 的**位移**，不是路程 —— 在一個按鍵格內繞圈／
震手位移細，一樣當停低了）。`Visit.minSpeed` 在入格夠 50ms 之後先開始取樣，
不是個 window 會望回到一格那段快速移動。改此處一定要跑
`GestureKeyTrackerTest`，內有「慢手直線拉不可以出中間格」與「慢手但確實
稍作停留就要出」兩個對照 case。

#### 滑動只在輸入字碼階段執行

入了選字模式些數字鍵已經不再是碼，而是「選擇第幾個字」與 `0` = 揭下一頁 ——
使用者報過滑 `7→9→0` 出到字之後，最後的 `0` 誤為揭第二頁。兩邊一起擋：

- **未起手**：`ChinesePadView.canSwipe()` 要求 `!engine.selectMode`，
  tracker 根本不會 start，所以連條線都畫不出。
- **滑到一半先入選字模式**：`onGestureKey()` 見到 `engine.selectMode` 就叫
  `abortSwipe()`。`KeyboardBaseView` 收到之後：`swipeDelegate` 不再派鍵出去、
  `drawTrail()` 立即不畫、ACTION_UP 也**不會**行 `tracker.finish()`
  （不是最後一格會多輸出一次，一樣誤為選字／揭頁）。

畫線與出鍵要一起停 —— 只有軌跡線繼續移動卻沒有反應，使用者會以為功能故障。

#### 選字揭頁：三種排法，不要再做 flick

`0` 按一下是「下頁」。返回上一頁原本是**按住「下頁」向左掃**（`KeyboardBaseView`
一套 `canFlick` / `onFlick`），2026-08-25 使用者表示「swipe 左變了下頁，很難用」，
整套 flick 機制 連 `ChinesePadView` 那個 override 一起**已刪除**，不要再補回。

目前改成排列方式上解決，而且**三種排法由設定頁選**（`Prefs.PagerLayout`，
設定頁「一般 → 選字翻頁」）。三種都只在 `selectMode && totalPage > 1`
（`ChinesePadView.paging()`）時先生效：

| `PagerLayout` | 底行 |
| --- | --- |
| `PREV_NEXT` | 拆兩個正常寬：左「上頁」、右 `0`（＝「下頁」） |
| `NEXT_PREV` | 拆兩個正常寬：左 `0`（＝「下頁」）、右「上頁」 |
| `WIDE_NEXT`（**預設**） | 不拆，成兩格寬那顆 `0` 就是「下頁」，**長按 = 上頁** |

「上頁」是 `KeyAction.PREV_PAGE` → `TTCmd.PREV`。

顆「下頁」**顯示 `1/10`**（`TTEngine.pageHint`，由 1 起計，不是 0）。
與同音鍵一樣是在 `drawDigit` 中即時問 engine 取，不是 `Key.hint`（`boxes`
不會逐次重建）。拆兩個那兩個排法放在**左上角**，沒有分頁時 `pageHint` 是空，
位置就留給讓長按提示 `「」`。

`WIDE_NEXT` 有兩件事與其餘兩個不同，改時兩邊都要一起改：

- **排位始終保持不變**（`wantSplitPager()` 見到 `WIDE_NEXT` 一律回 false），
  所以按鍵的 `Key` object 不會重建 —— 「目前是否揭緊頁」一定要即時查詢
  `ChinesePadView.wideNextPage()`，不可以入 `Key` 度。
- **長按的成對標點功能暫時讓位給「上頁」**：`TTInputMethodService.onLongPress` 的
  `digit == 0` 那路要先問 `chinesePad?.wideNextPage()`，是就 `TTCmd.PREV`，
  否則才繼續執行 `TTCmd.OPENCLOSE`。畫面上左上角寫「上頁」（＝長按執行哪項功能，
  與其他鍵一致），頁數則改至**右上角**（`drawCornerHintRight`）。

排位然後 engine 狀態變，所以 `TTEngine.Host.onStateChanged()` 不可以只
`invalidate()`：要行 `ChinesePadView.onEngineState()`，它見到 `splitPager`
與目前想要的不一樣才 `relayout()`（每次 `onStateChanged` 都重排就浪費）。

中文是**即時出碼**：離開一格就立即 `engine.press()`，九宮格內容立即變。
滑 `7→9→3` 畫直角 = 順序按了三下（`GestureKeyTracker` 逐格判斷）。**英文不用這套** —— `LatinPadView.onSwipeEnd()` 只將
`tracker.points`（原始軌跡，`GestureKeyTracker` 一樣有 buffer，只不理它個
per-key 判斷）連與 `keyCenter` 傳送給 IME service，放手之後**由 IME service**
（不是 `LatinPadView`）用 `GestureDecoder` 查詞庫 —— 因為要連 caret 前後些
字母一起計，亦要將完整軌跡統一與關聯字比對，不是逐格判斷。

### 長按 = 連按（九宮格）

兩個不同的位一起做，不要只改一邊：

- **起手長按**：`KeyboardBaseView.longPressRunnable` 見到 `Key.holdRepeat`
  就立即 `onKey()` 一次，而且**不會**設 `longFired`，放手那次照計 → `77`。
  然後拖走就變成 tracker 那條路（tracker 起點永遠 emit）→ `770`。
- **收手前稍作停留**：`GestureKeyTracker.finish()` 見到最後一格停夠
  `holdRepeatMs`（＝`Prefs.longPressMs`）就 emit 多一次 → `811`。
  英文不用這樣，所以 `holdRepeatMs` 預設 0（關閉），只 `ChinesePadView` 開。

`0` 沒有 `holdRepeat`，因為它長按是開關標點。

### 按下即出（2026-08-27 加，2026-08-28 起沒有得關閉）

使用者要求永遠保持開啟，所以 `Prefs.KEY_INSTANT_KEY` 與設定頁那個開關都已刪除，
只剩說明文字。`KeyboardBaseView` ACTION_DOWN 見到 `instantKey(key)` 就立即 `host.onKey()`，
放手那次就不再出（`instantFired`）。**只中文九宮格 `1`~`9` 先做**
（`ChinesePadView.instantKey`），而且只在「長按 = 連按」那個狀態：

- 選字模式（長按 = 同音字表）不做
- 開啟後 `longPressShortcut` 而又未打過碼（長按 = 速選字表）不做
- `0` 不做（長按 = 開關標點／上頁）

三樣一起夾住先正確：

1. **DOWN 出鍵一定要在 `tracker.start()` 之後**——`onKey()` 會改 engine 狀態，
   隨時觸發 `relayout()`（`boxes` 重建），那顆 `KeyBox` 就會變了舊內容。
2. **`tracker.start(x, y, t, startEmitted = true)`**：`GestureKeyTracker` 起點
   **永遠 emit**，不話它知就會變了打兩下。它兩處要跳過：`decideAndEmit` 的
   `isFirst` 分支，以及 `finish()` 的**基本**那次（「停夠耐再補一下」照出，
   不是在起點格停住放手就取不回到連按）。
3. **長按那次照計**：`longPressRunnable` 的 `holdRepeat` 分支不設 `longFired`，
   所以「按下一下 + 長按一下」＝ 連按兩下，與以前完全一致。

改此處一定要跑 `GestureKeyTrackerTest`，內有四個 `startEmitted` 的 case
（起點不可以補、沒有離開過起點放手都不補、停夠耐照補、拉去第二格照計）。

**「長按 = 連按」是最後一條路**：`longPressRunnable` 要 `Host.onLongPress()`
回 `false` 才執行到它。九宮格 `1`~`9` 有兩個情況會支配此操作（2026-08-25 加）：

- **一個碼都未打**（`!selectMode && currCode.isEmpty()`）→
  `TTEngine.shortcutDigit(d)`：直接開那格的速選字表（`mapped_table` id
  `1000 + d`），不用再「按個碼再按速選按鍵」。輸入中的碼（`currCode` 有內容）就不截，
  照行回連按，`77x` 這些碼按得回。
  **這持續預設關閉**（`Prefs.longPressShortcut`，設定 →「其他」，2026-08-25 加）：
  就算未打碼，它一樣攔截了「長按 = 連按」的第一次，`77`／`88` 無論如何長按都無法輸入，
  所以要 使用者自己開。
- **選字模式**→ `TTEngine.homoAt(slot)`：開啟該格字元的同音字表，
  **不用先按「同音」按鍵**。就算無法查詢同音字（多字詞、標點、`word_meta` 沒有記錄）
  `onLongPress` 都要回 `true` 已處理此操作 —— 回退至連按就會立即選取一個字，
  然後放手那次又將數字作為新字碼，一次按鍵產生兩項結果。

## 開關標點（長按 `0`）：包住 vs 移 caret

`TTEngine` 選完一對標點是行 `host?.commitPair()`（**不是** `commitText`），
`TTInputMethodService.commitPair()` 分兩種情況：

- **選取了一段字** → 「」**包住**它：`選了的字` 變 `「選了的字」`。
  注意 `commitText` 本身是**取代**選了那段，所以一定要自己
  `getSelectedText()` 取回選取文字並接至中間，不是就會覆蓋使用者選取的文字。
- **沒有選字** → 出一對「」，再將 caret 移回兩個標點**中間**（`setSelection`，
  attach 不支援 `getExtractedText` 就轉到去發 DPAD_LEFT）。

長按 `0` 那次（`onLongPress` → `TTCmd.OPENCLOSE`）只改 engine 狀態，
不會 commit 任何內容，所以 app 那邊選取的字持續留到選完標點先有用。

### 長按變體 popup：PopupWindow，永遠向上彈 + 絕對位置選

**用 `KeyPopup`（`PopupWindow`）畫，不是在 `KeyboardBaseView.onDraw` 度畫。**
在 view 內畫一定讓 view 邊界剪走，最頂那行就永遠彈不出鍵盤外面。`KeyPopup`
開啟後 `isClippingEnabled = false` + `isAttachedInDecor = false`，視窗出得 IME
window 範圍，彈上 app 那邊；`isTouchable = false`，所以 touch 一直是
`KeyboardBaseView` 收，長按完仍然拉得去選。

多項曾遇到坑，不要改回：

- **永遠向上彈**（`popupTop = box.top - popupItemH - 8dp`，負數都照）。以前是
  「頂行沒有位就向下彈」，結果英文數字行（`numRow` 開啟後時 digits 是第 0 行）
  長按 `0` 會向下彈至過低的位置蓋住第二行鍵，按都無法操作。之後改成貼著鍵盤頂部繪製，
  又變成與按鍵重疊一舊讓手指遮住 —— 目前有了 `KeyPopup` 就確實彈到鍵盤上面。
- **字大 30%**（`KeyPopup.TEXT_RATIO = 0.53`，本來 0.40×格高），格本身也
  稍寬（`box.w * 1.1`，最少 50dp）。手指遮住一半時要看得清楚。
- **選哪一個 = 手指目前在哪一個格上面（絕對位置）**，不是「行了多少步」。用相對
  步數時，貼邊的鍵（`p`、`0`）成行變體會讓 `popupLeft` 的 clamp 擠壓住向左推，
  看到的高亮與手指位置完全對不上，變成無論如何拖動都無法選擇。
- 但「長按後不移動而直接放手 = 輸入按鍵本身」必須保留：`popupMoved` 尚未移動超過一個
  `slop` 之前一律當第一個（`variants` 第一個永遠是按鍵自己）。

### 滑動 hover 提示

滑動時手指下方那按鍵一定會被手指遮擋，所以 `updateHoverPopup()` 用同一個
`KeyPopup` 在**按鍵上面**浮個大字出來（`hoverLabel(box)`，只 `LatinPadView`
有實作，a~z 才顯示）。移至另一個鍵先移動視窗（`hoverBox` 擋住）—— 每次
`PopupWindow.update()` 都是一次 window relayout，逐個 MOVE event 移動就會 lag。

### 英文滑動：空格、context、候選欄

`TTInputMethodService.onSwipePath()` 統一處理四項內容，改其中一樣之前看清楚
另外多項：

- `latinWordDone`（剛好滑完／在候選欄選完一個字）→ 今次是**下一個字**，
  補個空格，不取前面該字做 context。沒有它就會恢復以前那個 bug：
  `setComposingText` 蓋了上一個字。
- 不是的話就取 caret 前後貼住的字母做 `prefix` / `suffix`
  （`GestureDecoder.decode(path, keyCenter, keyWidth, prefix, suffix)`），
  出到字之後要 `deleteSurroundingText(pre.length, suf.length)` 刪除些舊字母。
  無法匹配就一步步放寬（先移除 suffix、再移除 prefix），仍無法找到任何結果就當這次滑
  沒有發生過（不會屈硬出些垃圾字）。
- `forceCandidates` 令 `refreshBars()` 強行出候選段，就算 `barMode` 是 OFF／TOOLS。
- 滑出來該字會立即 `setComposingText`（underline），但**只這個狀態**先有
  underline —— 一打字（不是 swipe）就立即 `finishComposingText()` 取消，恢復
  已以普通方式輸入的字（`typeChar()` 見到 `latinSwiped` 就立即處理）。

### 自動補空格：句號（`.`）**不可以**補回寫入

`autoSpaceAfterPunct()` 只在 `, ? !` 後面補空格。**句號故意不在資料表內**：
打網址（`google.com`）、小數、檔名、縮寫全部都是「字母 + `.` + 字母」，
與「句尾 + 開新句」在打那一刻**分不開** —— `google.` 與 `Hello.` 前面那部分
狀態完全相同，試過用 token 內容去估都靠不住。補錯個空格會直接導致網址
無法輸入（使用者報過：「簡直打不了」）。想斷句就自己按 ␣。

URL／email／密碼／`TYPE_TEXT_FLAG_NO_SUGGESTIONS` 的欄再加多重保險：
`noAutoSpaceField` 會令整個 auto-space 完全關閉。

## 英文詞庫

`assets/en_freq.txt` = `/mnt/d/sync_dev/eng/data/en-most.txt` 篩走非 a-z 之後、
再只保留最常用那 5 萬個的版本（`字 頻率`，已經由高到低排好）。原始開發版有 20 萬個，
但正式版無需如此多，只保留頭 5 萬個就夠（`head -n 50000`）——記住之後如果要再取新資料，
一樣要裁到 5 萬個先入 assets，不是 apk 體積會大幅增加。

- **不要在 `onCreate` 載**。`EnDict.preloadAsync()` 只在 `switchMode(LATIN)` 時叫，
  背景 thread 低優先次序，未載完 `EnDict.get()` 回 null，當沒有提示就算，UI 不會 lag。
- 內部用一條 `blob: String` + `starts: IntArray` + `weight: FloatArray`，
  不是一堆 String object。比對直接在 blob 上面行，不要加 substring。
- `EnDict` 本身**沒有** swipe 認字邏輯，只 `word`/`charAt`/`weightAt`/`wordLength`
  這些 public accessor 讓 `GestureDecoder` 用。

### 英文 swipe 認字：`GestureDecoder`（AOSP 手勢輸入那套概念）

不再逐格判斷「按了哪個鍵」（`GestureKeyTracker` 那套 dwell/轉角 heuristic 只有
中文九宮格仍在使用）。改成完整手指軌跡（`tracker.points`）統一與候選字的
「理想路徑」（逐文字母的鍵中心連成線，連續重複字母隱藏做一格）比對形狀＋位置：

1. 首尾字母分桶粗篩候選（`byFirstLast`），無法匹配就放寬做只信第一個字母
   （`byFirst`，file 頭 2 萬個常用字）。
2. 兩條軌跡都用弧長重新取樣做固定 32 點（`resample`，$1 recognizer 那套做法），
   逐點計距離取平均（`pathCost`），再以及首尾點距離的額外罰分（`ENDPOINT_WEIGHT`）。
3. 距離用 `keyWidth` 正規化（不同螢幕、不同鍵盤大小都能正確匹配），再與
   `ln(頻率) × LM_WEIGHT` 夾埋做總分。
4. 改這個評分公式之前，看回 `EnDictRealDataTest`（真字典 + 手震雜訊都要能正確匹配）
   與 `EnDictTest`（fake 座標，形狀＋常用度都要試到）。

### 選完一個字，估下一個字：`NextWordModel`

`EnTrie`（unigram trie，bottom-up 快取每個節點的 top-K 常用字）+
`assets/en_bigram.txt`（`word1 word2 頻率`，依家是人手選的常見詞組種子數據，
不是真語料統計出來，之後要換成真 corpus 就跟同一個格式重新產生這檔案即可）。
`space()` / `onPickCandidate()` 選完字就取 `NextWordModel.predictNext(prevWord)`
入 `latinSuggestions`；輸入中的下一個字就用 `suggestWithPrefix(prevWord, prefix)`
—— bigram 夾 prefix 的放前面，不夠先用 `EnTrie.completions()` 補位。

---

## 改完之後

```bash
JAVA_HOME=/opt/android-studio/jbr ./gradlew :app:assembleDebug :app:testDebugUnitTest
```

要保持**零 warning**。純 JVM unit test 有四份：`GestureKeyTracker`、`EnDict`、
`VoiceActivity`（語音 VAD 門檻）、`SttAudio`（ADTS header 逐個 byte）——
改判定邏輯、評分公式、VAD 門檻或者 header bit packing 一定要跑。
UI 就上模擬器影相看。

## 簽名：兩條 key，不可混淆

| 用途 | key | 如何運作 |
| --- | --- | --- |
| side-load（dl 的 `threethree-v<N>.apk`、GitHub release） | `~/.android/debug.keystore` | `./gradlew assembleRelease`（預設） |
| 上架 Google Play | `~/.android/tt-release.keystore` | `./gradlew bundleRelease -Ptt.upload` |

正式那條 key（2026-08-25 使用者自己 `keytool -genkeypair` 出來）**不在 repo 內**，
密碼也不會入 git：

```
~/.android/tt-release.keystore
~/.android/tt-release.properties   # storePassword / keyAlias / keyPassword
```

`app/build.gradle.kts` 見到 `-Ptt.upload` 才砌 `upload` 這個 `signingConfig`，
兩個檔案有一個不見就立即 fail（不會無聲地回退至 debug key）。**沒有加 `-Ptt.upload`
就一定是 debug key** —— side-load 那條線一直是那條 key 簽，無聲地已更換，
些人就要 uninstall 了先裝到新版。`.gitignore` 已經封全 `*.keystore` / `*.jks` /
`*keystore.properties`，keystore 消失就永遠再 無法再更新應用程式，請務必備份。

Play 收 `.aab` 不收 `.apk`（新 app），所以上架那個是 `bundleRelease`；
想自己裝來試就 `assembleRelease -Ptt.upload`（與 debug key 那個無法與 debug key 版本同時安裝在同一裝置上）。

## 版本號：每次改完自動加一

`app/build.gradle.kts` 的 `versionName`（`x.y.z`）與 `versionCode`：
**使用者叫改內容（無論是新增功能或修正錯誤），一改完就自動兩個一起 +1**——
`versionName` 補回 patch（`1.0.0` → `1.0.1`），`versionCode` 都 +1，
不用 使用者特別提及先做。第一個正式版由 `1.0.0` 開始（2026-08-21 定的）。
這個與 `/mnt/nas4/web/subdomains/dl/` 中的 `threethree-v<N>.apk` 個 `N`（每次 build release 版都加一）
是兩件不同的內容，不可混為一談。
