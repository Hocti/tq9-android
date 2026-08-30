# AGENTS.md — 三三正體中文輸入法 ThreeThree (Android)

改嘢之前請先讀晒呢版。呢個 repo 係 Windows 版（C# WinForms）
移植過嚟嘅 Android system keyboard，行為要同原版夾得住。

---

## 一句講晒

使用已過期專利 HK1035043 嘅 numpad 中文輸入法。撳 2~3 個碼查 `mapped_table` 出候選字，
字碼表／關聯字／同音字／繁簡表全部喺一個 sqlite 檔案入面，user 可以喺設定頁換走。

## 環境

| | |
| --- | --- |
| JDK | `/opt/android-studio/jbr`（要 `JAVA_HOME=/opt/android-studio/jbr ./gradlew …`） |
| SDK | `~/Android/Sdk`（`local.properties` 已寫死） |
| 版本 | minSdk 26 / targetSdk 36 / Kotlin 2.1 / AGP 8.13 / Gradle 8.14 |
| 模擬器 | AVD `Medium_Phone_API_36.1` |

```bash
JAVA_HOME=/opt/android-studio/jbr ./gradlew :app:assembleDebug :app:testDebugUnitTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell ime enable tt.ime.riverine/.ime.TTInputMethodService
adb shell ime set    tt.ime.riverine/.ime.TTInputMethodService
```

### 喺模擬器度試鍵盤，有四個陷阱

1. **一定要 `adb shell settings put secure show_ime_with_hard_keyboard 1`**。
   模擬器當自己有實體鍵盤，唔開呢個 setting 就唔會彈輸入法出嚟。
2. **唔好 `adb shell am force-stop tt.ime.riverine`**。IME service 同 app 同一個 package，
   force-stop 會殺埋 IME，系統就會跌返去 Gboard。用 `am start` 就夠。
3. **每次 `adb install -r` 之後都要再 `ime enable` + `ime set` 一次**，
   系統會當佢 reinstall 而 reset。
4. **`screencap` 影出嚟成塊黑／白（WSL + swiftshader）**，睇唔到個鍵盤實際點。
   改為用數字驗證：

```bash
# 鍵盤（IME window）實際幾闊幾高
adb shell dumpsys window windows | sed -n '/InputMethod}/,/Frames/p' | grep -E "Requested|Frames"
# 睇得到嘅 view（app 嗰邊）搵輸入框坐標 —— 鍵盤本身係自己畫嘅，dump 唔到粒鍵
adb shell uiautomator dump /sdcard/u.xml && adb pull /sdcard/u.xml
# 改設定／睇 pref（app debuggable，唔使 root）
adb shell run-as tt.ime.riverine cat shared_prefs/tq9_settings.xml
```

`shared_prefs` 個檔名**故意仲係舊名** `tq9_settings.xml`（見 `Prefs.FILE`）——
2.0.0 改名嗰陣冇郁佢，改咗舊裝機啲設定就會一次過 reset。純內部檔名，user 見唔到。

   要開英文鍵盤就撳 Chrome 個網址欄（URI 欄 → `PadMode.LATIN`），中文就搵個
   普通文字欄（例如 `am start -a android.intent.action.INSERT -t vnd.android.cursor.dir/contact`）。
   撳條 bar 上面粒大細掣要自己計坐標：條 bar 高 42dp 喺 IME window 最頂，
   英文／符號頁最左仲有粒 42dp 嘅 `⇄`（`setSwitchVisible`），跟住先至係
   工具列五粒（padding 4dp、每粒 margin 3dp）—— 而且要條 bar 喺「工具」嗰段
   （`bar_mode`）先撳得到。撳一下 = 轉顯示方式，拖 = 拉大細。

設定頁最底本來有「試打」四個欄（普通／email／PIN／搜尋）同埋實時預覽，
**而家收埋咗**（`SettingsActivity.SHOW_DEBUG_SECTIONS = false`，user 唔想見到）。
`buildTryBox()` / `buildPreview()` 一行都冇刪 —— 想 debug 排位就改返做 `true`，
唔使開第三方 app。搜尋嗰欄係用嚟睇 `⏎` 有冇變 `🔍`
（`enterLabelFor()` 睇 `IME_ACTION_SEARCH`）。

---

## 唔好搞亂嘅嘢

### 九宮格排位係 numpad，唔係電話

`7 8 9` 喺最上、`1 2 3` 喺最落，跟返 Windows 版嘅 `ResizeAllButton()`。
底行係 `[0 佔兩格][取消]`；選字夠兩頁嗰陣兩格闊嗰粒 `0` 點變由設定話事
（見下面「選字揭頁」）。改過嚟電話排法就同原版打法唔同曬。

**右欄由上而下係 `☰／⇄`、`␣`、`⌫`、`⏎`**（2026-08-27 user 要求，`␣` 同 `⌫`
對調咗）—— 咁樣中文都跟返下面嗰條「`⏎` 上面嗰粒一定係 `⌫`」嘅規矩，
四款鍵盤一致。最上嗰粒平時係 `☰`，條 bar 常駐嗰陣變 `⇄`（見「工具列常駐」）。
左下角淨返粒 `🌐`（成格闊）—— 錄音搬咗上工具 bar，喺「貼上」隔籬，
亦都係左上角嗰粒鍵揀得嘅其中一個 `PadFunc`。

### 底行嘅規矩（英文／符號／純數字）

- **左下兩粒一定係「返去英文／中文」**（`Eng` 行先，跟住先至 `中`）—— 有兩個例外：
  中文九宮格自己就係中文，左下角淨係 `Eng`；**純數字頁**兩粒搬咗去**右上角**
  （user 要求，左下角讓咗俾 `0` `.` `-`）。
  英文嗰粒**寫 `Eng` 唔寫 `ABC`**（2026-08-25 user 要求，全部頁一致）。
  中文嗰粒**長撳 = 換輸入法**，做乜由設定頁揀（`Prefs.EngLongPress`）：
  `NEXT_IME` → `KeyAction.IME_SWITCH`（跳去下一個，跳唔到就跌落選單），
  `PICKER` → `KeyAction.IME_PICKER`（直接彈系統選單）。🌐 收埋咗，
  呢粒鍵係唯一入口，所以兩種做法都要有。
- **`⏎` 上面嗰粒一定係 `⌫`**。所以符號頁嘅分頁掣（`€£¥`／`?123`）同 `⌫`
  都喺倒數第二行嘅最左同最右，純數字頁嘅 `⌫` 亦都由右上角搬咗去 `⏎` 上面。
  第一頁嗰粒分頁掣**寫三個銀紙符號 `€£¥`**（第二頁頭一行就係啲銀紙），
  以前寫 `=\<`，冇人知係乜。
- 讓返出嚟嘅位：符號第一頁底行 space 右邊順住排 `, . ? ; /` 五粒（本來散喺
  上面兩行）；第二頁唔要標點，space 同 `⏎` 拉長，`numpad` 掣再升多一行。
- **純數字頁最左有一欄 `+ - * /`**（2026-08-25 加）：`-` 由底行搬咗上去，
  讓返出嚟嗰個位（`0` 右邊）擺咗粒 **`000`**（一次過打三個 0）。
  即係而家成頁係 5 欄，同中文九宮格一樣。
- **英文底行 space 右邊係 `, . /` 三粒**（本來係 `/` 喺 space 左、`?` 同 `.` 喺右）。
  `?` 已經冇咗獨立一粒 —— 佢係長撳 `/` 嘅第一個選擇（見下面）。

### 英文鍵盤排位（2026-08-24 大執過）

- **永遠有數字行**（`Prefs.FORCE_LATIN_NUM_ROW = true`）。設定頁嗰個開關收埋咗，
  但 `KEY_LATIN_NUM_ROW` 同「冇數字行就喺字母角落寫細字」嗰段 code 都冇刪。
- `asdfghjkl` **唔再靠拉長 `a` / `l` 收邊**：九粒一樣闊，兩頭各讓半格空位
  （`spacerKey(0.5f)`）。空位**唔會**入 `boxes`，所以撳落去會由 `boxNear()`
  snap 去隔籬真嗰粒鍵，唔會變死位。
- `,` 由 `zxcvbnm` 行搬咗落底行（頂咗本來個 `?`），讓返出嚟嘅位俾 `⇧` 同 `⌫` 拉長。
- **長撳字母大細階兩樣都揀得**：`ch()` 會按而家個 `ShiftState` 砌 variants ——
  排頭嗰個係粒鍵而家寫住嗰個（撳實唔郁放手 = 打返佢），第二個係另一個大細階。
  popup 揀返嚟嗰粒鍵帶 `Key.literal = true`，`typeChar()` 見到就**唔會**再套 shift
  （唔係特登揀個細階 `a` 會俾 shift 夾硬變返 `A`）。
- 標點三粒（`, . /`）長撳有 `PUNCT_VARIANTS`，左上角寫住細字提示。
  **三粒都唔跟「第一個 = 自己」規矩** —— 排頭嗰個係長撳一彈出嚟就已經停咗
  喺度嗰個（唔郁手指放開就出佢），粒鍵自己短撳攞得返：
  `,` → **Tab**（`\t`）、`.` → `;`、`/` → `?`。
- **Tab 冇字形**，畫出嚟一片空白，所以 `variantDisplay()`（`KeyDef.kt`）會換做
  `⇥` —— popup 同角落提示都要行呢個 helper，但係 `Key.variants` 入面存嘅、
  同埋最後 commit 出去嗰個一定要係真正嘅 `\t`。
- **變體多過螢幕裝得落就一齊迫窄**（`openVariantPopup`：
  `if (popupItemW * items.size > width) popupItemW = width / items.size`）。
  情願粒粒細啲都好過有幾個推咗出螢幕外面永遠揀唔到 —— `/` 有八個，
  用返 `max(鍵闊 × 1.1, 50dp)` 就一定爆。字太大 `KeyPopup` 自己會縮返。
- `?123` 長撳 = 直接跳純數字頁（`longAction = TO_NUMBER`），中文九宮格嗰粒一樣。
  **英文嗰粒冇左上角提示字**（2026-08-29 user 要求）：鍵面本身已經四個字符，
  底行粒粒都窄，再迫個 `123` 落左上角就撞埋一舊。中文九宮格嗰粒地方鬆啲，
  個 `hint` 照留 —— 兩處唔一致係特登嘅。

### 純數字頁：成頁唔准長撳

`NumberPadView.allowLongPress()` 一律回 `false`（`KeyboardBaseView` 嗰個 hook）。
打電話號碼／金額撳耐咗少少就彈個符號 popup 出嚟好煩，所以數字鍵**用 `num()`
唔用 `digitKey()`**（後者會帶 `variants`）。

闊度同貼邊**唔可以再自己計**：`RowsPadView.contentBounds()` 已經直接開一個
`PadMetrics`（一樣 5 欄 4 行）攞 `offsetX` / `contentW`，呢頁淨係要 override
`padGroup = PadGroup.CJK`，即係大細完全跟中文九宮格 —— 連工具 bar 左右拖出嚟
嗰個闊度倍數都跟。以前呢頁自己置中兼且封頂 360dp，中英切換嗰陣啲鍵會左右彈。

### `dataset.db` 唔會自動更新，舊機仲用緊裝機嗰陣嗰份

`TTDb.ensureInstalled()` **淨係喺 `filesDir/dataset.db` 唔見咗嗰陣先由 assets 抄**——
user 可以喺設定頁換走個字碼表，夾硬覆蓋就會刪咗人哋自己揀嗰份。代價：
**由舊版升級上嚟嘅機，個 db 仲係當初裝機嗰份**。實測（2026-08-27，模擬器）
2026-08-21 嗰份 1.7MB 舊 db：

- `word_meta` **冇 `freq` / `code` 兩欄** → `topByCodePrefix` 查唔到（SQL 直接
  throw，佢自己 `runCatching` 食咗）
- `mapped_table` **冇 id `1010`** → 候選欄嘅預設字攞唔到

所以兩處都要有 fallback（`TTInputMethodService`）：`defaultPicks` 攞唔到 1010
就跌返落 1000（速選字表，即係以前嘅做法），`codePreview()` 空就跌返落
`defaultPicks`。**條 bar 吉住睇落似壞咗，情願出舊嗰套。** 想攞返新功能就叫 user
喺設定頁撳「還原內置字碼表」（會覆蓋佢自訂過嘅 db，所以唔可以靜靜雞自動做）。

加任何要新 schema 嘅嘢之前，記住問返自己：舊 db 會點？

### mapped_table 嘅 id 有特別意思

| id | 係乜 |
| --- | --- |
| `0` | 標點（首頁撳 0） |
| `1` | 開關標點成對（長撳 0） |
| `10`, `20`, … `90` | 姓氏表（撳咗第一碼之後再撳 0） |
| `10`~`999` | 正常字碼表，`weight` = 常用度 |
| `1000`~`1009` | 速選字表（⭐；首頁 = 1000，撳咗 1~9 之後 = 1001~1009） |
| `1010` | 候選欄嘅預設字（游標前面吉住／唔係中文嗰陣出，見「候選欄出乜」） |

打碼邏輯（`TTEngine.press`）：夠三碼、或者中途撳 0 收尾，就查表出候選字。

### `characters` 入面嘅 `*` 係佔位符，唔係一隻字

`mapped_table.characters` 一格入面用 `*` 霸位，等後面啲字坐得返啱嗰格 ——
例如 id `169` = `********教`，「教」一定要坐第 9 格（＝碼 `1699`）先夾得返
「第一頁永遠 `1`~`9`」條規矩。所以 **`*` 唔可以剷走**（一剷後面全部走位），
但係亦都**唔准打得出**。

2026-08-30 之前淨係 `showPage()` 畫嗰陣當佢吉：格仔望落係空白，但係
`selectWord()` 照攞 `selectWords[key]` 出嚟 commit，撳落去真係會打隻 `*`
落個輸入框。而家改成**入口一次過洗乾淨** —— `TTEngine.startSelectWord()`
見到 `TTEngine.PLACEHOLDER` 就換做 `""`，跟落去所有路（九宮格、上面條 bar、
側邊欄、`pickCandidateAt`、`plausibility`）都當「呢個位冇字」，唔使逐個位補
`it != "*"`。關聯字表（`related_candidates_table`）一樣有 `*`，行同一條路。

### 字要用 grapheme cluster 拆

`TTDb.splitGraphemes()` 用 `BreakIterator`，等同 C# 嘅 `StringInfo`。
用 `String.length` / `toCharArray` 會拆爛 emoji 同香港增補字符集。
判斷「係咪單一個字」要用 `codePointCount`，唔係 `length`。

### 撳鍵之間唔可以有死位

畫面見到嘅隙係 `drawFace()` 縮咗 `gapPx` 畫出嚟嘅，`KeyBox` 本身要貼實
（`RowsPadView` 最後一格／最後一行會夾硬去到最右最底）。
ACTION_DOWN 用 `boxNear()`（搵唔到就攞 14dp 內最近嗰粒），
**唔好**改用 `boxAt()` —— 但係滑動判定（`swipeKeyAt`）就一定要用 `boxAt()`，
唔係格外面都會當撳咗邊上嗰粒。

### 圖檔

90 格筆形提示圖**唔再係 90 個 png**，係一幅 sprite sheet：`assets/default90.png`，
橫切 9 份、直切 10 份 —— **第一個數字 = 第幾行（0 起）、第二個 = 第幾列（1 起）**，
所以左上角 `0_1`、右上 `0_9`、左下 `9_1`、右下 `9_9`。`0_1`~`0_9` 係首頁筆形，
`1_1`~`9_9` 係第二碼提示。**Android 版冇 `10_x`** —— 關聯字改咗喺上面條 bar 揀，
唔會再迫落九宮格。

`StrokeImages` 得一個 `Bitmap` cache 住成幅 sheet，`draw()` 每次計個 source rect
出嚟 `drawBitmap` —— **唔好**改返做逐格 crop 出 90 個 Bitmap（多一份 memory，
IME process 個 heap 好細）。個 source rect 用「乘完先除」（`c * w / COLS`）計邊界，
user 換咗幅唔係 9 嘅倍數闊嘅圖都唔會漏 pixel。

換圖：揀嗰幅抄落 `filesDir/strokes.png`，**檔案喺唔喺度就係「有冇自訂」**
（冇另開 pref，唔會出現「pref 話自訂但個檔唔見咗」）。所以升級換咗新嘅內置圖
即刻生效，唔使似 `dataset.db` 咁記 versionCode。換完要叫 `StrokeImages.reload()`，
但係**唔好 recycle 舊嗰幅** —— 鍵盤可能同一時間畫緊佢。

**「檢視目前 sqlite／圖片」＝ save as，唔係喺 app 入面開**（2026-08-29 加）：
設定頁兩節各加咗一粒掣，行 `ActivityResultContracts.CreateDocument` 抄一份出去，
user 想用邊個 sqlite viewer／睇圖 app 都得。App 自己唔識開呢兩種檔，
猜佢裝咗乜、又或者為咗 `ACTION_VIEW` 開多個 `FileProvider` 出嚟都係多餘。
兩點要記住：

- **一定要抄 `filesDir` 嗰份**（`TTDb.file` / `StrokeImages.file`），唔好貪方便
  由 assets 攞 —— 唔係就變咗「檢視內置」，睇唔到 user 自己換咗入去嗰份。
- 筆形圖**冇自訂過就 `filesDir` 度根本冇個檔**（見上面「檔案喺唔喺度就係
  有冇自訂」），所以 `exportImg()` 嗰陣先要 `isCustom()` 分流，
  冇自訂就真係由 assets 抄。字碼庫冇呢個問題（`ensureInstalled` 保證有）。

App icon 由 `../logo.jpg`（2048×2048）縮出嚟，五個 density 一次過出：
`ic_launcher_foreground` 透明底、圖佔 canvas **50%**（大過呢個數，圓形 mask 就會
切到隻箭嘴尖，試過 56% 已經穿），legacy 嘅 `ic_launcher` / `ic_launcher_round`
分別係 70% / 68%。底色 `@color/ic_launcher_background` = `#F4F8F9`。

### 系統輸入法揀選視窗只出一行「三三輸入法」

`res/xml/method.xml` **一粒 `<subtype>` 都冇**，係特登嘅（2.0.1 user 要求）。
中英數符號係喺鍵盤入面自己切，唔使畀系統知道語言：

- 加 subtype = 系統清單／揀選視窗喺個名下面多一行「語言」。粒 subtype 有
  `android:label` 就出個 label，冇 label 就自動出 locale 名（`zh_HK` → 「中文 (香港)」）
  —— 兩樣都唔要，user 淨係要一行 `ime_name`。空字串 label 都冇用，
  `InputMethodSubtype.getDisplayName()` 見到空就會跌返去出 locale 名。
- 加多過一粒 subtype 仲衰，系統嗰度會變兩個輸入法。

所以呢度**唔好加返** subtype、`imeSubtypeLocale`、`languageTag`、`isAsciiCapable`。
Kotlin 嗰邊一句 subtype API 都冇用（`grep -ri subtype app/src --include=*.kt` 係空），
拆走冇嘢會斷。

---

## 架構

```
core/   TTDb       sqlite 存取、assets 安裝、換 db、weight prefix 統計
        TTEngine   輸入狀態機（Windows 版移植），唔掂 Android UI
        EnDict     5 萬字英文詞庫（blob + starts + weight，慳記憶體），淨係
                   `fromPrefix` 打字提示 + `word`/`charAt`/`weightAt` 呢幾個
                   public accessor 畀 `GestureDecoder`／`EnTrie` 用
        EnTrie     英文 unigram trie，每個節點快取住自己嗰個 prefix 之下
                   常用度最高嘅幾個完整字（AOSP 標準做法）
        NextWordModel 揀完一個字之後估下一個字：bigram（assets/en_bigram.txt）
                   做主，冇 context／夾唔到 prefix 就跌落 EnTrie 嘅全域常用字
        EmojiDict  assets/emoji.txt，分類 + 用英文／中文關鍵字搵
        ClipHistory clipboard 歷史（JSON 存喺 Prefs）
        AiRewrite  Gemini generateContent，改寫揀咗嗰段字
        AiStt      VoiceRecorder 錄 PCM、VoiceActivity 判斷有冇人聲、
                   SttAudio 壓縮（AAC-LC / ADTS，做唔到就跌返 WAV）
        UsageStats 另一個 sqlite（usage_stats.db，同 dataset.db 分開）：
                   連續兩個中文字嘅 bigram 次數、每隻字打咗幾多次
        Prefs      全部設定
swipe/  GestureKeyTracker   中文九宮格滑動中間鍵判定（純 Kotlin，有 unit test）
        GestureDecoder      英文 swipe 認字：AOSP 手勢輸入嗰套概念嘅 Kotlin 版
                   （軌跡 vs 候選字理想路徑做形狀比對，唔係逐格判斷撳咗邊粒鍵）
ime/    TTInputMethodService   IME 主體，所有 view 嘅 host
        KeyboardBaseView        排版／畫鍵／掂觸／畫線／長撳 popup／長撳 ␣ 郁 caret
        KeyPopup                浮喺鍵盤外面嗰啲窗（長撳變體行、滑動 hover 提示）
        ChinesePadView          九宮格（KeyboardBaseView）
        RowsPadView             一行行按 weight 分闊度嘅底
        LatinPadView / SymbolPadView / NumberPadView（RowsPadView）
        EmojiPadView            emoji grid（ViewGroup，唔係 KeyboardBaseView）
        ClipboardListView       長撳「貼上」之後蓋喺 padHolder 上面嘅 overlay
        PadMetrics              尺寸同顯示方式計算
        OptionBarsView          上面條 bar（三段：關／候選字／工具）
ui/     SettingsActivity / MicPermissionActivity
```

`TTEngine` 唔應該 import 任何 `android.view.*`；佢淨係吐狀態，由 `ChinesePadView` 畫。

---

## UI 高度：唔可以無啦啦跳

### 同一組鍵盤一樣咁高

`RowsPadView.onMeasure` **唔係**逐行乘行高，係直接攞
`PadMetrics.padHeightPx(ctx, w, padGroup)`（＝嗰組 4 行嘅總高）。英文開咗數字行有
5 行、符號頁有 5 行、中文永遠 4 行，同一組入面總高度一樣，行數多嗰啲每行自然矮啲。
加行減行**唔會**令個窗跳高跳低，所以唔好喺子類度加返 `rowHeightDp` 呢類逐行計嘅嘢。

`padGroup`（`PadGroup.CJK` / `LATIN`）話畀佢知攞邊套大細 —— 見「大細設定分組存」。
`RowsPadView` 預設 `LATIN`，`NumberPadView` override 返做 `CJK`（跟中文九宮格）。

### 上面條 bar 三段都係一行

`OptionBarsView` 三段（`BarMode`）每段都係得**一行**。以前有條「狀態」細字
（字碼、`[同音]`、頁數）擺喺最上面，一出現就成個鍵盤高咗一截，已經**拆咗**——
`TTEngine.status` 仲計緊，但係冇人畫。要出 message 就用 `toast()`，
唔好再喺條 bar 上面加行。

高度**唔再係寫死 42dp**（2026-08-29 user 要求）：由 `CandChip` 度候選字實際
要幾高，再加上下 3dp margin，最矮 42dp。100% 之下實測 47dp。
**三段共用同一個高度**，轉段一樣唔會跳。

改完字體要行 `bars.refreshFontScale()`（`refreshBars()` 入面，喺
`setCandidates` 之前）—— 佢見到 sp 冇變就即刻返轉頭，所以逐粒鍵行都唔怕。
側邊欄嗰邊係 `SidePanelView.refreshFontScale()`：**唔可以靠 `setCandidates`**，
佢見個 list 冇變就唔會重砌啲 chip，改完字體返嚟仲係舊 size。

### 候選字 chip 嘅高度／padding 一定要行 `CandChip`

大細全部喺 `CandChip` 度計，`OptionBarsView` 同 `SidePanelView` 兩份 `makeChip()`
共用。裡面有兩個踩過嘅坑（2026-08-29 user 影實機相踩到「上面 padding 多過下面」），
改之前一定要睇：

1. **唔可以問 `Paint` 攞 metrics 去定高度。** `Paint.getFontMetrics()` 回嘅係
   **primary typeface（拉丁）**嗰套；中文字係跌落 CJK fallback 字型畫。實測
   20sp / density 2.625：`Paint` 話 `descent - ascent` = 61.5px，但真正
   `layout.height` = 75px。用細嗰個數，chip（要 105px）就會俾 `AT_MOST`
   迫窄到 96px，上下 padding 寫到幾對稱都冇用。所以要**開個真 `TextView` 度**。
2. **`gravity = CENTER` 置中嘅係 line box，唔係個字嘅墨。** CJK 個墨企得高：
   baseline 上面 box 有 60px 但個墨淨係去到 44，下面 box 15 個墨得 5 ——
   上面鬆 16、下面鬆 10，置中完個字實偏低。所以 `padTop` / `padBottom`
   **特登唔對稱**（實測 13 / 18），差額啱啱抵消。

度嘅時候用**固定嘅參考字**（中文 `字`、英文 `Ag`），**唔可以**用 chip 自己
嗰個字 —— 唔同字個墨唔同高（`一` 淨係一橫、`我` 佔成格），逐個各自置中就會
粒粒 baseline 唔同，一行睇落高高低低。實測結果（gapTop/gapBot）：
`我` 29/28、`的` 28/29、`一` 50/51、`是` 30/28、`不` 32/28。

### 字體大細：條 slider 淨係郁得到「字」，郁唔到功能鍵

設定頁兩條字體 slider（`Prefs.KEY_FONT_SCALE` / `..._LATIN`）**淨係**放大
真係打得出嚟嗰啲字符：九宮格嘅候選字同筆形提示、英文字母、符號、數字，
加埋上面條 bar 同側邊欄嘅候選字（`Prefs.candTextSp`）。

**功能鍵（同音、取消、Eng、中、⌫、⏎、␣、?123、€£¥…）唔跟**
（2026-08-29 user 要求）：行 `Prefs.funcFontScale()`，永遠當 100%
（英文組照樣乘 `LATIN_FONT_BOOST`，所以 slider 停喺 100% 個樣同以前一樣）。
粒鍵做乜早就記熟咗，唔使睇得咁清，跟住一齊大就逼爆粒鍵。

畫嗰陣點分：`RowsPadView` 睇 `isFunctionKey(k)`（`action != CHAR`），
`ChinesePadView` 就係 `drawFunction()` 嗰條路（`DIGIT` 以外全部）。
**成粒鍵一齊行同一個倍數** —— 鍵面個 label、左上／左下／右上角細字、
`Eng` 個 🌐，全部都要傳 `scale =` 落 `KeyboardBaseView` 嗰幾個 `draw*` helper，
唔好淨係改個 label。工具 bar 嗰啲圖案（`ICON_DP`）同一個道理，一樣唔跟。

### 粒 `▼`（拉大候選字）淨係喺真係捲得到嗰陣先出

`OptionBarsView.wantExpandBtn()`：**比 `strip.width`（啲字實際幾闊）同 `swap.width`
（成行嘅闊度）**，唔夠位擺先至 `VISIBLE`，否則 `GONE`（唔係 `INVISIBLE` ——
以前係 `INVISIBLE`，粒掣冇咗個 `▼` 但個位仲霸住，睇落似壞咗）。
**唔可以攞 `scroller` 嘅闊度嚟比**：粒掣一出現就食咗 38dp，跟住又變返「要捲」，
出出入入。攤開咗（`expanded`）就一定要出，唔係收唔返。

判斷要等排完版先做得，所以喺 `onLayout()` 度做，而且**改 visibility 要 `post`**
（排緊版嗰陣改就會即刻再 `requestLayout` 多次）。

### 中文拉窄就唔要上面條 bar，改用側邊欄

`PadAlign.LEFT_GAP` / `RIGHT_GAP` 之下，中文本體闊過螢幕嘅
`Prefs.SIDE_PANEL_MAX_RATIO`（六成）就照舊用上面條 `OptionBarsView`；
**窄過六成**就 `bars.visibility = GONE`，成條 bar 嘅內容搬去 `SidePanelView`
（加落 `padHolder` 度，`FrameLayout.LayoutParams` 闊度 = 空出嚟嗰邊，
gravity 跟 `PadAlign` 反過嚟擺）：上面一（兩）行功能掣，下面成塊可 scroll 嘅候選字。

入口係 `refreshBars()` 開頭嗰句 `if (refreshSidePanel(cands)) { … return }`。

**個高度一定要寫死做 `PadMetrics.totalHeight`（＝中文九宮格幾高），
唔可以用 `MATCH_PARENT`。** `padHolder` 係 `wrap_content` 嘅 `FrameLayout`：
`MATCH_PARENT` 嘅仔會攞到 `AT_MOST(成個可用高度)`，而 `SidePanelView` 入面
個候選字 `ScrollView` 又食住 `weight = 1`，結果候選字一多就撐大咗 `padHolder`，
成個鍵盤跟住拉高（**打橫特別明顯**，因為打橫一定入側邊欄模式）。
只限**中文九宮格** —— 英文／符號／純數字係鋪滿成行，冇位空出嚟；
剪貼簿個 overlay 又會蓋住成個 `padHolder`（連側邊欄都遮埋就撳唔返粒 ✖），
所以 `overlay != null` 嗰陣一定要退返去用上面條 bar。

側邊欄冇 `⇄`（候選字同工具一次過見晒，唔使切）。`switchMode()` 個
`padHolder.removeAllViews()` 會順手 detach 咗佢，最後嗰句 `refreshBars()` 會加返。

### 鍵盤永遠貼實底

`PadMetrics` 冇咗 `extraBottom`／`Prefs.floatY`。`PadAlign.FLOATING` 已經刪咗
（自由移動嗰粒冇用，`Prefs.floatX`／`ChinesePadView.nudgeFloat` 一齊清咗）——
而家 `PadAlign` 得 `STRETCH`／`LEFT_GAP`／`RIGHT_GAP` 三個，
`OptionBarsView` 個 sizeBtn 淨係轉呢三個。拖動**兩個方向都有嘢做**
（一拖夠 8dp 就鎖死方向，唔會斜少少就兩樣一齊改）：

- **上下** = `Prefs.heightScale`（0.6~1.8）。`PadMetrics.cellH` 同
  `PadMetrics.rowHeightPx()` 兩邊都要乘返佢，唔係英文鍵盤就唔會跟住變。
  拉邊組要睇 `TTInputMethodService.padGroup`（見「大細設定分組存」）。
- **左右** = `Prefs.widthScale`（0.45~1.6），淨係 `LEFT_GAP` / `RIGHT_GAP` 有用。
  **淨係入 `cellW`，唔可以入 `cellH`** —— 兩者本來都由同一個 `unit` 出，
  一唔小心就會變成「左右拉埋高度都跟住變」。
  方向要跟顯示方式反（見 `onWidthDrag`）：永遠都係「拖向留白嗰邊 = 拉闊」。
- **長撳（撳實唔拉）= 一下子拉到最闊**（`Listener.onMaxWidth`，2026-08-25 加）：
  `widthScale` 直接寫 `MAX_WIDTH_SCALE`。手機直度嘅「最闊」＝螢幕闊度
  （`cellW` 會頂到 `availW / cols`）。

  **粒長撳唔可以即刻做嘢，要等放手先算**（2026-08-28 執，user 踩到：
  「左右拆開拖拖下兩橛突然併埋」）。`View` 個長撳大約半秒就 fire，而
  **淨係手指行出咗粒掣範圍先會自動取消** —— 粒掣好闊（打橫成 170dp），
  慢慢拖、或者好正常噉「撳落 → 停一停 → 先至拖」，長撳都會喺途中 fire，
  即刻做就會拖到一半彈晒去最闊（喺 `SPLIT` 之下就係兩橛併埋）。
  而家 `setOnLongClickListener` 淨係 `longPressArmed = true`，`handleSizeDrag`
  收到 `ACTION_UP` 先睇：**有拖過（`dragging`）就當拖，冇拖過先至叫
  `onMaxWidth()`**；一鎖實拖動方向亦都順手 `cancelLongPress()`。
  `OptionBarsView` 同 `SidePanelView` 兩邊一模一樣，改就兩邊一齊改。

**中文本體最少 `PadMetrics.MIN_CONTENT_DP`（320dp）闊**（螢幕本身窄過 320dp
就用盡螢幕）—— 拉窄同 `widthScale` 都收唔過呢條線。順帶影響：直度手機
（400dp 左右）而家永遠達唔到 `SIDE_PANEL_MAX_RATIO`（六成）嗰個側邊欄條件，
側邊欄實際上淨係打橫／平板先會出。

設定頁嗰幾條尺寸 slider（按鍵大細／最大闊度／最大高度／按鍵高度／鍵盤高度）
全部收埋咗（`SettingsActivity.SHOW_HIDDEN_OPTIONS = false`，一行 code 都冇刪），
剩返「字體大細」（中文一條、英文一條）同「邊框粗幼」—— 長闊而家一律喺鍵盤度直接拖。

`SHOW_HIDDEN_OPTIONS` 之下仲有**滑動輸入嘅「停留」同「轉角」**兩條
（2026-08-29 收埋，user：一般人唔需要調到咁細）—— 兩個 pref
（`KEY_SWIPE_DWELL` / `KEY_SWIPE_ANGLE`）同 `GestureKeyTracker` 照讀，
淨係設定頁見唔到。

**「顯示方式」嗰粒掣 2026-08-29 由設定頁移走咗**（user 要求）：條工具列最左粒掣
撳一下就轉下一個，設定頁再擺多個入口做同一件事係多舊雲。`Prefs.align` /
`setAlign` / `nextAlign` 一個字都冇刪，`TTInputMethodService.onCycleAlign` 仲用緊。

### 字體大細都係分兩組（2026-08-28 user 要求）

`Prefs.fontScalePref(ctx, group)`：`CJK` 行 `KEY_FONT_SCALE`、`LATIN` 行
`KEY_FONT_SCALE_LATIN`（未校過就跟返 `CJK` 嗰個值，升級之後個樣唔會變）。
中文字要夠大先睇得清，英文字母同數字用同一個倍數就會逼爆粒鍵。

**畫嗰陣要叫 `Prefs.fontScale`，唔係 `fontScalePref`**（2026-08-29 分開咗）：
英文嗰組實際倍數 = pref × `Prefs.LATIN_FONT_BOOST`（1.2），而且條 slider
拉得到 `MAX_FONT_SCALE_LATIN_PCT`（200%，中文嗰條照舊 140%）—— user 話英文
140% 都唔算大。**唔好貪方便將個 boost 寫死落 pref 度**（例如揀 100 就存 120）：
存住嗰個數字就係設定頁見到嗰個百分比，反推返嚟一定有 rounding 誤差，
拖幾次就會走位。設定頁讀 `fontScalePref`，鍵盤讀 `fontScale`。

分組跟返 `PadGroup`（**唔係**「中文 pad vs 其餘」）：純數字 keypad 排位同大細
本來就跟九宮格，所以字體都跟 `CJK`。`KeyboardBaseView.padGroup` 就係攞邊套嘅入口
（預設 `CJK`，`RowsPadView` override 做 `LATIN`，`NumberPadView` 再 override 返 `CJK`），
`fontScale` 同 `PadMetrics` 兩邊都靠佢。

### 大細設定分組存（2026-08-28 user 要求）

`heightScale` / `widthScale` / `align` 三樣**唔係得一套**，而係
「螢幕尺寸 × `PadGroup`」各有各存（`Prefs.profKey()` 砌個
`<base>_<闊dp>x<高dp>_<組>` 嘅 key）：

- **`PadGroup.CJK`** = 中文九宮格 + 純數字 keypad（本來就係同一個 5 欄排位）
- **`PadGroup.LATIN`** = 英文 + 符號（`RowsPadView` 預設）

螢幕尺寸用 dp 闊高做名，一次過分開晒摺機嘅外／內屏（尺寸唔同）同打直打橫
（闊高調轉）—— 摺機正路會有 2 屏 × 2 方向 × 2 組 ＝ 8 套。**舊嗰個冇螢幕名嘅
key 照留返做預設值**（`sp.getFloat(profKey(...), sp.getFloat(舊 key, 1f))`），
升級之後大細唔會走位，兩組都由舊嗰個值起步。

`TTInputMethodService.padGroup` 睇住 `mode` 答而家郁緊邊組（`LATIN`／`SYMBOL`
係 `LATIN`，其餘全部 `CJK`），`onSizeDrag` / `onWidthDrag` / `onMaxWidth` /
`onCycleAlign` 四個都要攞佢，跟住一律 `relayoutPads()`（所有已經砌咗嘅 pad 一齊重排，
唔使逐個 `?.rebuild()` 撩漏）。`OptionBarsView.padGroup` 亦都要喺 `refreshBars()`
度跟住 set，唔係粒「靠左／靠右」掣個圖案會畫返另一組嗰個狀態。

英文／符號頁本來永遠鋪滿成行，而家 `RowsPadView.buildLayout()` 一律開返個
`PadMetrics(w, group = padGroup)` 攞 `offsetX` / `contentW`，所以佢哋一樣拉得闊窄、
貼得左右。`STRETCH` 嗰陣 `contentW == availW`，同以前一模一樣。
側邊欄（`sideGeom()`）就照舊淨係中文先出，用 `CJK` 嗰套。

### 兩組嘅闊度**計法唔同**（2026-08-28 執過）

`PadMetrics` 入面 `contentW` 分兩條路：

| 組 | 點計 | 點解 |
| --- | --- | --- |
| `CJK` | `unit × widthScale × cols`（封頂 `availW`、封底 `MIN_CONTENT_DP`） | 九宮格要保持格仔嘅高闊比，闊度同 `unit`（＝高度嗰個 unit）綁埋 |
| `LATIN` | `MIN_CONTENT_DP` → `availW` **線性**（`widthScale` 由 `MIN_` 到 `MAX_WIDTH_SCALE` 對應 0→1） | 一行行排，格仔闊度同高度冇關係 |

英數嗰邊**唔可以**跟九宮格條式：`unit` 俾 `maxHeightDp / rows`（預設 300/4 = 75dp）
封住頂，闊 screen 拉極都去唔到成個螢幕闊；窄 screen 又成段撞住 `MIN_CONTENT_DP`，
拉大拉細都係同一個闊度（user 2026-08-28 踩到）。而家最窄一定係
`min(320dp, 螢幕闊)`、最闊一定係**成個螢幕**，中間平均拉。

### `PadAlign.SPLIT`：英數鍵盤喺闊 screen 拆做兩橛

`Prefs.alignOptions(ctx, group)` 話你知**而家揀得邊幾個**顯示方式：

- `LATIN` + 螢幕闊過 `Prefs.SPLIT_MIN_WIDTH_DP`（500dp）→ 得 `STRETCH` 同 `SPLIT`
  （靠左／靠右嗰陣收起 —— 咁闊嘅螢幕靠實一邊，另一邊嗰橛位就係嘥咗）
- 其餘（`CJK`、或者窄螢幕）→ 原本三個，冇 `SPLIT`

三樣嘢跟住呢個表行，加新 mode 記得三樣一齊改：

1. **`Prefs.align()` 會過濾**：存住嘅值唔喺 `alignOptions` 入面就當 `STRETCH`
   （摺機開合／打橫之後可揀嘅嘢會變，舊 profile 唔可以夾硬用落去）。
2. **`Prefs.nextAlign()`** 先至係「撳一下轉下一個」，`PadAlign.next()` 已經刪咗 ——
   自己 `ordinal + 1` 就會轉到唔准揀嗰個。
3. `OptionBarsView` / `SidePanelView` 兩個 `refreshAlignLabel()` 個 `when` 都要寫齊
   （側邊欄係中文專用，撞唔到 `SPLIT`，但一樣要有嗰個 branch）。

排位喺 `RowsPadView.buildLayout()`：每行用 `splitRow()` 由左邊夾夠一半 weight
斬開（`asdfg` | `hjkl`、`⇧zxcv` | `bnm⌫`），兩橛各 `PadMetrics.halfW` 咁闊，
一橛貼 `0`、一橛貼 `w - halfW`。

**`SPLIT` 之下最闊 = 螢幕闊度減 `PadMetrics.MIN_SPLIT_GAP_DP`（80dp）**
（2026-08-28 user 踩到）：唔封住個頂，`widthScale` 拉到盡（或者長撳粒大細掣
「一下子拉到最闊」）就會兩橛併埋鋪滿成行，中間條罅變 0，睇落同「拉闊」
一模一樣 —— 用家以為分割壞咗。`contentW` 條線性式最闊嗰端跟住收窄，
所以由最窄拉到最闊成段都用得着。**斬到一半嗰粒啱啱係 `␣` 就拆佢做兩粒**
（`k.copy(weight = k.weight / 2f)`），唔係得左邊有 space，右手姆指撳唔到。

`EmojiPadView` 同 `ClipboardListView` 唔係 `KeyboardBaseView`，
高度靠 `forcedHeightPx`（開之前喺 `rememberPadHeight()` 記低上一個 pad 幾高），
所以**一定要喺 `padHolder.removeAllViews()` 之前記**，唔係就攞到 0。

### 底下閃開導覽列嗰條要有底色

targetSdk 35+ 之後 IME window 一路去到螢幕最底，`outer` 個
`setOnApplyWindowInsetsListener` 加 bottom padding 閃開導覽列（系統嘅
「收起鍵盤／轉鍵盤」就喺嗰度）。**嗰忽 padding 係 `outer` 自己嘅底色**——
唔 set 就透見住下面個 app，一忽色唔同好突兀，所以 `outer.setBackgroundColor(theme.background)`
（`root` 嗰個 background 蓋唔到 padding 區）。

### 開鍵盤嗰下要補度尺寸（2026-08-28 加）

由**冇到有**彈鍵盤出嚟嗰下，個窗未必即刻報得返啱嘅闊度／導覽列高度，量出嚟成塊
鍵盤高過個窗，最底嗰行就俾導覽列冚咗 —— 要拉一拉高度或者轉一次橫直先返到正常。

`TTInputMethodService.scheduleSizeRecheck()` 喺 `onWindowShown()`／
`onStartInputView()`／`onConfigurationChanged()` 三個位排隊，之後每
`SIZE_RECHECK_MS`（100ms）補度一次，總共 `SIZE_RECHECK_TRIES + 1` 次
（＝ 100…400ms）。每次做兩件事：

1. `ViewCompat.requestApplyInsets(outer)` —— 有啲機第一次唔會派 insets 落嚟，
   底下就唔會閃開導覽列。
2. `fixPadSizeIfOff()`：攞**而家**個 `padHolder.width` 重新計 `PadMetrics.padHeightPx`，
   同排住嗰塊鍵盤實際高度比。**唔同先至** `relayoutPads()` + `root.requestLayout()`
   + `refreshBars()`。真係重排過就再補多一輪（`SIZE_MAX_FIXES`，個窗要下一個
   layout pass 先跟得上）。

**除咗塊 pad 自己幾高，仲要比「擺唔擺得落」**（`padHolder.height >= pad.height`，
2026-08-28 user 踩到）：打橫改完高度 → 熄屏 → 轉直 → 解鎖，塊 pad 自己係量返
直度嗰套（啱），但係個窗仲係停留喺打橫嗰個高度，`padHolder` 俾裁短咗，最底成行
唔見咗。淨係比塊 pad 就當一切正常，一路都唔會執返。

但係「`padHolder` 矮過塊 pad」唔一定係出事：鍵盤本身拉到高過個螢幕（打橫好易），
個窗頂到盡都一定裁到。所以重排之前記低度到嘅尺寸（`lastFixState`），
**重排完一模一樣就唔再試**；一見返正常尺寸就清返做空，下次再撞到同一個壞尺寸
照樣執。

**一定要「度到唔啱先至重排」**，唔可以硬行一次 —— 唔係每次開鍵盤都會見到跳一跳。
最後嗰句 `refreshBars()` 唔可以慳：側邊欄個高度係寫死 `PadMetrics.totalHeight` 嘅
（見上面「中文拉窄就唔要上面條 bar」），唔重新加返就會跟住錯埋。
emoji 表／剪貼簿跟 `forcedHeightPx`，唔喺呢度計（`as? KeyboardBaseView` 濾走）。

---

## 條 bar 唔可以出唔返嚟

`EmojiPadView` 同 `ClipboardListView` 冇自己嘅「關閉」掣 —— 粒 `✖` 統一喺
`OptionBarsView` 最左。所以 `refreshBars()` 見到 `specialPad`
（`mode == EMOJI || overlay != null`）就一定要 **force `BarMode.TOOLS` + 出粒 ✖ + 唔准 GONE**，
唔係 user 熄咗條 bar 之後開 emoji 就返唔到去普通鍵盤。

`showOverlay()` / `hideOverlay()` 兩邊都會叫 `refreshBars()`。

### 工具列常駐（`Prefs.barPinned`，2026-08-27 加，2026-08-28 起**預設開**）

開咗之後條 bar 關唔熄得，而**九宮格右上角嗰粒鍵換咗個意思**：

| | 粒鍵 | 條 bar 最左 |
| --- | --- | --- |
| 熄咗 | `☰`＝開／關成條 bar | `⇄`＝候選字 ⇄ 工具 |
| 常駐（預設） | `⇄`＝候選字 ⇄ 工具 | **冇咗**（`setSwitchVisible(false)`） |

三處要一齊夾：

- `TTInputMethodService.toggleBar()` 第一句就分流去 `onSwitchView()`。
- `refreshBars()` 開頭見到 `pinned && barMode == OFF` 就當場升做 `CANDIDATES`
  **兼且寫返落 pref**（設定頁㩒個掣唔會 restart 個 service）。
- `ChinesePadView.optionKey()` 換鍵面；`optionOn`（著唔著燈）常駐嗰陣代表
  「而家喺工具嗰邊」，唔係「條 bar 開住」—— 一路著住藍燈冇資訊可言。

**`setSwitchVisible` 淨係喺中文九宮格先收埋粒 `⇄`**：英文／符號頁根本冇右上角
嗰粒鍵，收埋咗就永遠入唔到工具列。`✖`（emoji 表／剪貼簿）永遠行先，
兩粒共用同一個位（`refreshLeftBtn()`）。

**英文／符號頁亦都夾硬開返條 bar**：呢兩頁靠佢出打字提示同滑出嚟嘅字，
冇咗就等於打盲舖。`refreshBars()` 見到 `mode` 係 `LATIN`／`SYMBOL` 而
`effective == BarMode.OFF` 就升做 `CANDIDATES`。**唔會改到 `barMode` 本身** ——
返到中文頁照樣跟返 user 設定嗰個開關。

## 工具掣嘅圖案：自己畫，唔用 emoji

工具 bar（`OptionBarsView`）同側邊欄（`SidePanelView`）嗰幾粒掣本來直接寫
`📋` `🎤` `😀` `✨` 落 `TextView` 度，2026-08-25 全部換成 `ime/ToolIcons.kt`
入面自己畫嘅**單色** `ToolIconDrawable`。三個唔用 emoji 嘅理由，改之前記住：

1. emoji 一律由系統嘅彩色 emoji 字型畫 —— 鍵盤其餘全部單色，夾埋一齊好突兀；
2. 每部機每個 Android 版本嘅 emoji 字型都唔同樣，畫出嚟嘅大細同顏色都唔受控；
3. `setTextColor(Theme.text)` **套唔到落彩色 emoji 度**，深色主題一樣係嗰個彩色樣。

畫法：一律喺一個 **24×24 嘅座標**度砌，`draw()` 先 `canvas.scale()` 去粒掣實際
咁大，所以任何 dp 都唔會起格。**顏色喺 constructor 傳死**（跟 `Theme.text`），
冇實作 `setTintList` —— 轉主題係 `styleTool()` 整粒新嘅出嚟。
兩個 view 都有一個 `icons: LinkedHashMap<TextView, Pair<ToolIcon, String>>`
記住邊粒掣用邊個圖案，`applyTheme()` 就係照住佢重畫一次。

**唔可以用 compound drawable** —— `TextView` 個 `gravity` 淨係管啲字：左格嗰個
drawable 永遠貼死 `paddingLeft`（淨係上下置中），上格嗰個就永遠貼死 `paddingTop`
（淨係左右置中），兩樣都唔會真係擺正中間（實測過，啲圖案全部黐晒粒掣左邊）。
所以 `iconChip()` 用 `LayerDrawable` 疊喺圓角底色上面，
`setLayerSize()` + `setLayerGravity(CENTER)`，乜情況都啱。
順帶：粒掣個底色同圖案而家係同一件 drawable，所以 `refreshSttLook()`
（錄緊音著燈）唔可以再淨係 `background = chipBg(...)`，要行返 `styleTool()`。

**顯示方式嗰粒（`refreshAlignLabel`）唔係一支淨嘅左／右箭咀** —— 淨箭咀睇落似
「向左移／向右移」，但實際上係「貼實左邊／貼實右邊」，所以畫成
**一條牆 + 一支箭嘴指住埋去**（`ALIGN_LEFT` / `ALIGN_RIGHT`）；
「拉闊」（`STRETCH`）就兩邊都有牆、箭嘴向外撐開（`ALIGN_WIDE`）。
留意 `PadAlign.LEFT_GAP` 係「**左**邊留白」＝ 內容貼**右**，所以佢配 `ALIGN_RIGHT`，
兩個名係對調嘅，改嗰陣睇清楚。

`✖`（關閉）、`⇄`（切換）、`▼`（拉大候選）三粒**冇換** —— 佢哋本身就係單色
文字符號，唔係彩色 emoji。`PadFunc.EMOJI` 個鍵面亦都由 `😀` 改咗寫「表情」，
而家成個 `PadFunc` 一個 icon 都冇，全部寫中文。

## `Spinner` 唔可以用「跳過第一下 callback」嗰招

`SettingsActivity.FuncPicker`（左上角鍵嘅短撳／長撳）試過用一個 `ready` flag
擋開頭嗰下 programmatic `onItemSelected`，**中過伏**：第一下幾時 fire（甚至
fire 唔 fire）係睇 layout 時序，擋錯咗就會食咗 user 真正嗰下 —— 個掣睇落郁咗，
但係 pref 冇改過、上面個 label 亦都仲係舊嗰個，跟住去另一個 spinner 揀返同一樣
嘢就會冤枉人「功能重覆」。

而家改成**同 pref 而家真正存住嗰個值比**：一樣就當開場／回位乜都唔做，
唔一樣先至算 user 揀過嘢。個 label 每次都由 getter 重新讀，就算 `onPick`
拒絕咗都唔會同 pref 唔夾。

順帶一提 `Prefs.topLeftLong()` 撞咗短撳嗰陣**淨係計出** `NONE`，個 pref 入面
仲係舊嗰個 —— 改完短撳要自己 `setFunc(KEY_TL_LONG, NONE)` 寫實落去，
唔係個 spinner 同 pref 就會各講各話。

## 震動分級：舊個 boolean 冇刪

`Prefs.KEY_VIBRATE`（boolean）改咗做 `KEY_VIBRATE_LEVEL`（0～3，預設 1）。
舊 key **冇刪**，仲要一路寫住：

- `vibrateLevel()` 見到未寫過新 key，就由舊個 boolean 轉返過嚟（開 = 1、閂 = 0）——
  update 上嚟嘅人唔會無啦啦震返晒。
- `setVibrateLevel()` 順手 `putBoolean(KEY_VIBRATE, v > 0)`，萬一有邊度仲讀緊
  舊嗰個都唔會同新設定唔夾。

級數對應嘅時間／震幅喺 `Prefs.vibrateDurationMs()` / `vibrateAmplitude()`，
**level 1 一定要係舊嗰個力度**（12ms / 40）—— 嗰個係以前唯一嘅設定。
部分機款（例如部分 Sony Xperia）冇 `hasAmplitudeControl()`，硬傳 amplitude
會完全唔震，所以嗰啲機跌返 `DEFAULT_AMPLITUDE`，淨係靠時間長短分三級。

2026-08-25 user 話 level 3 仲係唔夠明顯，**2／3 兩級由 18／26ms 拉長到 34／60ms**
（震幅亦都由 110／200 加到 170／255）。要再調就繼續加時間 —— 好多機嘅震幅
係封咗頂嘅，真正感覺到「大力咗」嘅係震耐咗。**0 同 1 唔准郁。**

## 設定頁嘅 `slider()` 有 step 同 format

`SettingsActivity.slider()` 收多兩個 optional 參數：`step`（拖一格跳幾多，
例如長按時間逐 10ms 一格）同 `format`（個值點寫，例如震動級數寫「1（最輕）」）。
`SeekBar` 只認整數 progress，所以 progress 係**第幾格**，值 = `min + 格數 × step`。
`onChange` 一路都係**放手先叫**（`onStopTrackingTouch`），拖緊嗰陣淨係改上面個字。

## 畀人睇嘅字：全部正體中文書面語

app 入面所有 user 見到嘅字（設定頁、toast、鍵面、空狀態提示）一律用
**正體中文書面語**，唔用廣東話口語（「冇」→「沒有」、「撳」→「按」、
「而家」→「目前」…）。**注釋同 commit message 唔使跟**，照舊用口語。

### 而且要短（2026-08-29 user 踩到「描述過長，很多廢話」）

設定頁每個 `note()` **最多兩句**，講「開咗會點／要注意乜」就夠。唔好寫：

- 設計理由（「否則等於浪費一格」、「按錯一下不應該影響往後的選字」）——
  嗰啲寫落 code 注釋同呢份 AGENTS.md，唔係寫俾 user 睇。
- 內部細節（震動幾多毫秒、`?123` 個 hint 點嚟、邊個版本改過乜）。
- 鍵面上撳一下就見到嘅嘢（「⌫ 就在 ⏎ 上面」）。

一般 user 唔會逐段睇，寫長咗等於冇寫。

全名叫「**三三正體中文輸入法**」，通常叫「**三三輸入法**」，簡稱「**三三**」；
英文「**ThreeThree**」，簡稱「**TT**」。系統輸入法揀選視窗嗰行位窄，出簡稱
（`ime_name`）；launcher 同應用程式清單出全名（`app_name`）。

**唔可以喺 app、文件、commit message 入面點名任何輸入法品牌**——
講個專利就淨係寫「使用已過期專利 HK1035043」，唔好帶埋邊間公司。
（2.0.0 商標避嫌改嘅，見 CHANGELOG。）

## AI 設定頁分三大類

「AI」個 tab 分咗 **語音輸入 (STT)** / **AI 改寫** / **AI 設定** 三段，每段都收埋得
（`SettingsActivity.collapsible()`）。頭兩段各自有個總開關，第三段係
**兩邊共用嘅 provider 設定**（API key、模型名稱、自訂 API、profile）——
唔好分開兩份 key 或者兩個 model 出嚟。

收埋咗未係 activity 嘅 instance state（`aiOpenStt` / `aiOpenRewrite` / `aiOpenSetup`），
唔入 pref：重新開個 app 就當三段都攤開。改任何一個開關都係成個
`rebuildAiSection()` 重畫，所以 `collapsible()` 收埋嗰陣索性一件 view 都唔砌。

## AI 改寫（✨）

- **唔使揀住字都用得**：揀咗就淨係改揀咗嗰段，冇揀就當「改寫成個輸入框」——
  `runAi()` 會 `setSelection(0, 全長)` 再交出去。**出返嚟之前要再全選一次**：
  等緊 Gemini 嗰幾秒 user 隨時撳過個欄，一撳 caret 就散咗個 selection，
  `commitText` 就會變成插埋落去而唔係取代。
- **冇入 API key、或者設定頁熄咗個總開關（`Prefs.aiRewriteOn`），
  就成粒掣唔見咗**（`setAiVisible`，唔係淨係灰咗）。
  灰咗嗰個狀態留返俾「有 key 但個欄空咗」。
- 撳唔撳得由 `applyAiState()` 話事，`onUpdateSelection` 每次都會重新計
  （唔可以好似以前咁「揀嘅狀態冇變就 return」—— 而家個欄有冇字都影響到）。

## AI 語音輸入：頂走系統嗰個 `SpeechRecognizer`

`Prefs.aiSttOn` 開咗（而且有 key）嗰陣，粒 🎤 由頭到尾唔再掂
`SpeechRecognizer` —— `toggleStt()` 第一句就分流去 `startAiStt()` / `stopAiStt()`。
兩條路**完全分開**，`listening` / `recognizer` 嗰套嘢一個都唔會 set。

- **淨係 Gemini 做得**：段錄音要用 `inline_data` 呢個 Gemini 專用格式送上去，
  設定頁嗰套自訂 API 範本（URL／headers／body）表達唔到。所以
  `Prefs.aiSttOn()` 見到 `aiUseCustom` 就**一律回 false**（唔理個 pref 之前開過），
  設定頁嗰個開關亦都會鎖住。加新 provider 之前請先諗清楚點送段錄音。
- **錄音**用 `VoiceRecorder`（`core/AiStt.kt`）：`AudioRecord` 收 16kHz mono PCM。
  特登**唔用 `MediaRecorder`** —— 佢一定要寫落檔案，而且各家機出嚟嘅容器
  唔一定啱 Gemini 收。段 PCM 點包由 `SttAudio` 話事（見下面）。
- **兩種操作**：撳一下開始、再撳一下停（`hold = false`）；撳實一路錄、放手即停
  （`hold = true`）。後者要 `OptionBarsView.Listener.onSttHoldStart()` /
  `onSttHoldEnd()` 兩個 callback，同埋 `KeyboardBaseView.Host.onLongPressEnd()`
  （左上角揀咗做 🎤 嗰粒鍵用）—— 平時冇人要知「長撳幾時放手」，所以嗰個
  interface method 有 default 空 body。
- **粒掣個 `OnTouchListener` 一定要回 `false`**：`ACTION_UP` 喺 `performClick`
  之前到，回 `false` 先至保得住「撳一下 = 短撳」。回 `true` 就再冇短撳。
- **左上角嗰粒 🎤 淨係喺長撳位吉住先至撳實錄**（`key.longAction == NOOP`），
  唔可以食咗 user 特登喺設定頁揀嘅長撳動作。
- **錄緊同等緊結果，成個鍵盤變灰兼撳唔到**：`showBlockingOverlay()`（AI 改寫
  嗰個 loading 都係用返佢）。所以「再撳一下停」係撳嗰塊 overlay，唔係撳返粒 🎤。
  高度**寫死做 `root.height`**，用 MATCH_PARENT 會撐大咗成個 IME window。
- **四個階段四把唔同嘅聲**（`SttTone`）：開始錄 `TONE_PROP_BEEP`、錄完
  `TONE_PROP_BEEP2`、成功 `TONE_PROP_ACK`、失敗 `TONE_PROP_NACK`。
  呢啲同 `Prefs.sound`（按鍵聲）**冇關係**，唔跟嗰個開關。
- **逾時／離開個欄要記得清**：`sttGeneration` 同 `aiGeneration` 一樣係用嚟
  當第遲到嘅 callback；`onFinishInputView` / `onDestroy` 行 `cancelAiStt()`。
- **放手之後先篩一篩，唔好乜都掟上去**（2026-08-25 加）。`VoiceRecorder.stop()`
  回一個 `VoiceClip`，三種：

  | | 幾時 | 點處理 |
  | --- | --- | --- |
  | `TooShort` | 短過 `VoiceRecorder.MIN_CLIP_MS`（400ms） | 當撳錯，唔叫 API |
  | `Silent` | 夠長但係 `VoiceActivity.hasSpeech()` 話冇人聲 | 當撳錯，唔叫 API |
  | `Ready` | 其餘 | 送上去 |

  `VoiceActivity` 係能量式 VAD：逐 20ms 一格計 RMS，最響嗰啲都細過 `ABS_PEAK`
  就當死靜；再用「噪音底（第 20 百分位）× `SNR_RATIO`」做動態門檻，夠
  `MIN_VOICED_FRAMES`（8 格 = 160ms）響過門檻先當有人講嘢。**寧鬆莫緊** ——
  漏咗一次最多嘥個 API call，但係錯手擋咗人哋細聲講嗰句，user 就會覺得粒掣壞咗。
  改門檻一定要跑 `VoiceActivityTest`（純 JVM，入面有「嘈但係冇人講嘢」同
  「細聲講都唔可以擋」兩個對照 case）。
- **壓縮唔可以喺 `stop()` 度做**：`stop()` 係喺 main thread 叫嘅（放手嗰下），
  一分鐘錄音 encode 落 AAC 要成幾百毫秒，擺喺嗰度就會窒。所以 `VoiceClip.Ready`
  入面淨係原始 PCM，`AiStt.transcribe` 喺自己條背景 thread 度先至叫 `SttAudio.encode`。
  VAD 就相反 —— 行一次成段嘢係幾毫秒嘅事，而且要即刻知結果先決定叫唔叫 API，
  所以照樣喺 `stop()` 度行。
- **`SttAudio` 首選 AAC-LC，跌返落 WAV**：16kHz mono 24kbps，一分鐘 ~180KB
  （WAV 要 ~1.9MB）。容器係自己逐 frame 加 7 byte **ADTS header** 出嚟嘅裸
  AAC stream，**唔經 `MediaMuxer`** —— 當初唔敢用 `MediaRecorder` 就係因為
  各家機出嚟嘅容器唔一定啱 Gemini 收，ADTS 自己砌就每個 byte 都揸得住。
  `BUFFER_FLAG_CODEC_CONFIG` 嗰嚿（AudioSpecificConfig）**唔可以**寫落個 stream 度，
  ADTS header 本身已經有齊同樣嘅資料。部機冇 AAC encoder、或者中途 fail
  （包括 `ENCODE_DEADLINE_MS` 逾時）就跌返落 WAV —— **唔可以**因為 encode 唔到
  就當今次語音輸入失敗。
- prompt（`Prefs.DEFAULT_AI_STT_PROMPT`）逐條寫死唔准做乜 —— Gemini 好鍾意
  加句「以下是錄音的轉錄內容：」，又鍾意順手幫你執靚啲句子。改 prompt 嗰陣
  唔好刪咗「只輸出結果」同「逐字轉錄唔好潤飾」呢兩條。

## 候選欄出乜（中文，2026-08-27 重寫）

`refreshBars()` 喺中文模式分三種情況，**兩種係「唔關 engine 事」嘅**
（`showingContextPicks = true`，撳落去要行 `TTEngine.pickQuick()`，
**唔係** `pickCandidateAt()` —— 嗰陣根本冇入過 selectMode。`pickQuick()` 內部係
`startSelectWord(listOf(word))` + `selectWord(1)`，所以簡繁輸出、同音、關聯字全部照行）：

| 狀態 | 出乜 |
| --- | --- |
| `engine.selectMode` | `engine.selectWords`（照舊，撳 = `pickCandidateAt`） |
| `currCode` 有 1~2 個碼 | `codePreview()` → `TTDb.topByCodePrefix`，最常用嗰 9 隻字 |
| 乜都未打 | `contextPicks()` → **游標前面嗰隻字**嘅關聯字，冇就 `mapped_table` id `1010` |

- **速選字表（id 1000）唔再喺條 bar 出現**（以前吉住就出佢）。`quickPicks` 個 field
  刪咗，換成 `defaultPicks`（id `1010`）。速選字表照樣由左上角粒鍵／長撳 1~9 開得到。
- **`contextPicks()` 特登唔用 `engine.relateHints`**（＝「啱啱打完嗰隻字」）——
  user 撳過個輸入框郁咗游標、或者啱啱開個鍵盤，`relateHints` 已經係舊嘢。
  而家逐次 `getTextBeforeCursor(2, 0)` 攞返游標前面嗰個 grapheme 去查。
  所以 `onUpdateSelection` 喺中文、`!engine.busy`、唔喺 emoji 搜尋嗰陣要 `refreshBars()`
  —— 游標一郁，條 bar 就要換。（`TTEngine.pickRelateAt` 冇人叫，但係冇刪。）
- **開咗「輸出簡體」個欄入面係簡體**，但 `related_candidates_table` 淨係有正體，
  所以查唔到就 `TTDb.sctc()` 轉返正體再查一次。`sctc` 係由 `ts_chinese_table` 反轉出嚟嘅
  （多對一，第一個當代表）—— **淨係可以攞嚟查表，唔可以攞嚟做輸出**，出街嘅字一律行 `tcsc`。
- **`topByCodePrefix` 個 LIKE 一定要連埋粒逗號**：`word_meta.code` 每個打法都以 `,`
  開頭（「為」＝ `,470,480,970`），所以 pattern 係 `%,<prefix>%`。打 `4`／`47`／`48`
  都搵得返「為」，而 `70` **唔可以**夾到 `,970`。同一隻字有幾個打法（幾行記錄），
  要 `GROUP BY char ORDER BY MAX(freq)`。同一個碼查一次就 cache 住（`codePreviewFor`），
  唔係每撳一下鍵都查次 sqlite。

## 選字擺入九宮格：**第一頁永遠 `1`~`9`**

規矩得一條，寫喺 `TTEngine.slotOrder(page)`：

| 頁 | 排法 | 點解 |
| --- | --- | --- |
| **第一頁**（`page == 0`） | `1` 排到 `9`，同字碼表次序一模一樣 | 嗰個格號**就係隻字個碼最後嗰個數字**（狀態列「碼:」寫嘅嘢、打熟咗嘅手勢全部靠佢）。一調位就即刻全部作廢 |
| **第二頁開始**（`page > 0`） | `SLOT_ORDER`＝`5 4 6 2 8 1 3 7 9` | 嗰啲字本來就冇碼可以記，一定要望住揀，所以邊格易撳擺邊格：`5` 喺正中最易撳，跟住四邊（`4 6 2 8`），四角（`1 3 7 9`）最後。嗰頁得三隻字就淨係佔 `5 4 6`，四角吉住 |

**第一頁冇任何例外。** 2026-08-28 一日之內試過三個版本，最後定咗上面呢個：

1. 成個表（連第一頁）都用 `SLOT_ORDER` —— user 叫「嚴重錯誤」，即日收返
2. 「成個表得一頁就成頁用 `SLOT_ORDER`」—— 收返
3. 「得一頁嗰陣，最後撳嗰個碼排最前（`159` → `9 5 4 6…`）」—— 收返

所以下次見到「一版揀得晒／啱啱撳完邊個碼」呢類特例，**唔好自己加返落第一頁**。

「常用字排前」撩唔到第一頁（頭 9 個永遠唔郁），推得最前都係第 10 位 ——
`reorderByUsage()` 淨係換 `selectWords` 個次序，格號永遠由 `slotOrder()` 話事。

引擎入面兩個私家 helper `slotAt(rank, page)` / `rankAt(slot)` 幫你填返 `currPage`，
一定要**成對咁用**。凡係「格號 ↔ `selectWords` 入面第幾個」嘅換算全部要行佢哋，四處：

- `showPage(page)`：`rank` → `slotAt(rank, page)` 擺落 `keys[]`（呢個要傳 `page`，
  因為 `currPage` 未 set 好）
- `selectWord(slot)` / `homoAt(slot)`：`slot` → `rankAt(slot)` 攞返 index
- `plausibility(digit)`（滑動評估，選字模式嗰段）：一樣要換
- `pickCandidateAt(index)`（條 bar 撳落嚟嘅絕對位置）：**先** `currPage = index / 9`，
  跟住 `selectWord(slotAt(index % 9))`

漏咗其中一處就會「見到嘅字」同「撳落去出嘅字」對唔上，而且測唔到——
`ChinesePadView` 淨係照 `engine.keys[d]` 畫，佢自己唔知個次序。
`SlotOrderTest` 盯住個次序表本身，`UsageReorderTest` 盯住「常用字排前」唔郁頭九位。

## 同音字就係一個 flag，唔好再加嘢

`TTEngine.pressHomo()` **淨係** `homo = !homo`，跟返 Windows 原版：
打字後不出字，要打碼揀個字先至彈同音字表出嚟。試過改成「一撳就即刻開表」
（有 `lastWord` 就開佢嘅同音字，冇就開速選字表），user 話打斷咗打字流程，**收返咗**。
撳一下淨係著／熄粒掣（會變藍），唔可以換走而家個字表。

### 同音字表尾會補一橛「近音字」（2026-08-30 加）

`TTDb.getHomo()` = `exactHomo()`（`ping` 一模一樣，聲調都夾嗰啲行先）
**＋ `nearHomo()`（近音，一律排最尾）**。近音淨係為咗補漏 —— 唔會擠走本來
揀開嗰幾隻字嘅位。

點分近音：**淨係睇 `word_meta.ping` 呢一欄**，冇任何「邊隻字似邊隻字」嘅
硬寫對應表（2026-08-30 user 明確講明要咁做）。兩個 `ping` 行過
`TTDb.fuzzyPing()` 揉到同一個結果就當近音，即係「揀緊 `ngo`，順手連 `o`
嗰堆字一齊搵」。

`fuzzyPing()` 分兩截，睇該函式個 doc 有齊例：

1. **先夾返同一套拼音。** `word_meta.ping` 主要係耶魯（`ji`＝之、`yi`＝二、
   `cheui`＝取），但係夾雜咗少少粵拼串法嘅冷字（`zi`＝衹、`ceoi`＝綷、
   `coek`＝焯）。唔統一嘅話呢啲字連「同音」都撞唔到。
2. **跟住先至係懶音／近音**：`ng-` 甩聲母、`n-`/`l-` 不分、`gw-`/`g-`、
   `kw-`/`k-`、`aa`/`a`、`-n`/`-ng`、`-k`/`-t`。

規矩夾唔到但係想撞埋一齊嗰啲，寫喺 `EXTRA_GROUP`（一樣係**拼音對拼音**）：
而家得兩行 —— `o`→`a`（「我」要搵得返「啊」，user 指定）同 `n`→`m`
（「五」同「唔」兩隻淨鼻音字）。加減規矩改完記得跑 `FuzzyPingTest`。

規則放寬幾多要有分寸：實測全表 682 個 `ping` 揉成 380 組，最大一組
82 個字（`ji`＋`zi`）。再放寬（例如連 `-m`/`-n`、`-p`/`-t` 都當一樣）
就會多到揭幾頁都揀唔完，所以冇加。

### 左上角 = 長撳做乜，唔好擺即時狀態落去

成個 app 一條規矩：**粒鍵左上角細字一律講「長撳會發生咩事」**（`drawCornerHint`）。
同音鍵以前破咗呢條規矩（左上角擺住即時提示），2026-08-28 改返：

| 位 | 擺乜 | 邊句 code |
|---|---|---|
| 左上 | `Key.hint` ＝長撳嗰個 `PadFunc.icon`（預設「候選字」）| `ChinesePadView.funcLongKey` |
| 左下 | 即時提示（`homoWord` / `homoCodeHint`）| `drawCornerHintBottom` |

同一條規矩之下順手補返：`?123` 左上角寫 `123`（長撳直入 numpad；2026-08-29 起
**淨係中文九宮格嗰粒有**，英文嗰粒太窄，見「英文鍵盤排位」），
`Eng` 左上角畫個地球 `ToolIcon.GLOBE`（長撳轉輸入法 ——
粒獨立 🌐 掣收埋咗之後，冇呢個 icon 就冇人知撳得長撳）。個地球係
`drawCornerIcon` 畫嘅**單色** vector，唔好改返寫 emoji 🌐（鍵面其餘全單色）。

### 四個位揀功能，唔准重複

`Prefs.FUNC_SLOTS` = 左上短撳 → 左上長撳 → 同音長撳 → 右上長撳，**排住嗰個
次序就係優先次序**。四個位唔准做同一件事（`PadFunc.NONE` 例外，可以齊齊停用），
左上短撳仲唔准 `NONE`。

- 讀：一律行 `Prefs.funcSlot(ctx, key)`（`topLeftTap` / `topLeftLong` /
  `homoLong` / `topRightLong` 都係佢嘅 wrapper）。撞到**排喺前面**嗰個位就回
  `NONE`。呢段係防住舊 pref，正常情況下唔會行到。
- 揀：**唔准重複唔係揀完先話你唔得**，而係 `SettingsActivity.availableFuncs()`
  一開始就唔會將已經有人用嗰啲放入個 spinner。試過「揀撞咗就 toast 彈返轉頭」
  同埋「揀撞咗就靜靜雞熄咗第二個位」，兩樣都差 —— 前者要撞完釘先知，
  後者郁咗 user 冇叫你郁嘅設定。所以 `FuncPicker` 個 options 係個 lambda，
  每次 `sync()` 都重新問過。
- 換 adapter 嗰陣 `Spinner` 會 select 返第 0 個兼有機會即場 `onItemSelected`，
  所以 `FuncPicker.fill()` **一定要**拆走個 listener 先，擺返真正 selection 之後
  先駁返，唔係就會當咗 user 揀咗第 0 個。

同音鍵同右上角嗰粒**短撳換唔到**（開關同音／開關上面條 bar），淨係長撳揀得；
兩粒都係 `ChinesePadView.funcLongKey()` 幫粒鍵補返 `hint` ＋ `longAction`。
長撳實際點行完全靠 `TTInputMethodService.onLongPress` 開頭嗰句
「`key.longAction != NOOP` 就照 `onKey` 行一次」—— **唔好**再喺下面個 `when`
度為某粒鍵寫死長撳做乜（同音鍵以前寫死 `TTCmd.RELATE`，設定揀「停用」都照行）。

同音鍵**左下角**一個位、兩樣嘢，**攤開緊個表嗰陣行 `homoWord`，揀完先至行
`homoCodeHint`**：

- `TTEngine.homoWord` = 而家搵緊邊隻字嘅同音（兩條入口都會 set）。成頁都係
  同音字，唔擺個字出嚟就唔知係邊隻字嘅音。`cancel()` 會清走佢。
- `homoCodeHint` = 用同音字打完之後，嗰個字**正路點打**（`db.getCode()`），
  提你返轉頭應該撳邊幾個掣。打多一個普通字就喺 `selectWord()` 度清走。

兩樣都係 `ChinesePadView.drawFunction` 即時問 engine 攞，唔係 `Key.hint`，
因為 `boxes` 唔會逐次重砌。
**注意有啲字（例如「嘅」「喺」）根本冇 `word_meta` 記錄**，`getHomo()` 回空，
嗰陣 `selectWord()` 會直接 `cancel()`。

除咗個 flag，仲有第二條路入同音字表：**選字模式長撳嗰格**
（`TTEngine.homoAt()`，2026-08-25 加）。兩條路出嚟嘅表一模一樣（同一句
`db.getHomo()`），亦都一樣會 set `afterHomo`，所以揀完照樣喺同音鍵左下角
寫返個字正路點打。`homoAt()` 唔會掂 `homo` 個 flag 以外嘅嘢，
開關標點模式（`openclose`）就直接唔做 —— 嗰陣個表係「」呢啲一對對嘅標點。

## 搵 emoji 唔會真係入字落個欄，但會 set 做 composing text

`emojiSearch` 開住嗰陣，`typeChar()` 同 `TTEngine.Host.commitText()` 兩邊
都會攔住啲字入條 `emojiQuery`，結果出喺候選字條 bar，**唔會** `commitText`。
但為咗等 user 見到自己打緊乜（唔係就淨係得個候選字 bar，睇唔到個 input），
每次 `emojiQuery` 一變就會 `syncEmojiComposing()` set 做 composing text，
揀咗 emoji 或者打多個字都會自然取代／清走（`commitText` 蓋咗 composing 區），
`endEmojiSearch()` 見到仲有殘留就 `commitText("", 1)` 清走。
`switchMode()` 去到 LATIN／CHINESE 以外就會自動熄咗佢。

**搜尋嗰陣英文鍵盤底行淨係得兩粒：「退出表情搜尋」＋ `␣`**（2026-08-25 user 要求）。
`?123`、`中`、`⏎`、標點喺呢頁一粒都用唔著（啲字淨係用嚟篩，唔會入落個欄），
粒退出掣亦都**寫明幾隻字**，唔再係得個 😀 冇人知撳落去做乜。
代價：搜尋期間去唔到中文九宮格打中文關鍵字（要先退出再由中文鍵盤開 emoji）。

搜尋個「放大鏡」一律用**單色** `⌕`（`KeyDef.kt` 嘅 `SEARCH_GLYPH`），
唔用彩色 emoji 🔍 —— 搜尋欄嘅 `⏎`（`enterLabelFor`）同 emoji 表嗰粒搵字掣兩處都係。
字型冇 `⌕`（`Paint.hasGlyph`）就寫返「搜尋」兩隻字，唔可以出一格豆腐。

## 中文使用習慣統計：bigram 同每字次數

`UsageStats`（`usage_stats.db`，同 `dataset.db` 分開存）記兩樣嘢：連續打嘅
兩個中文字（`TTEngine.bigramPrev`）、同每隻字打咗幾多次。淨係計**單字**
（`isHanChar()` 篩走標點同多字詞），揀咗多字詞、標點、或者換咗行
（`onLineBreak()`）都會斷咗個 bigram 鏈，唔會屈埋唔啱嘅組合。

設定頁「使用習慣統計」嗰段可以**匯出／匯入／清除**成個 `usage_stats.db`
（`UsageStats.exportTo` / `importFrom` / `clear`）。三樣嘢一開頭都要行
`closeSync()`：背景 io thread 排緊嘅寫要等佢寫完、WAL 要 checkpoint，
唔係抄出去嗰份會少咗最後幾下；`instance` 清走之後下次 `get()` 會重新開返個檔。
匯入要驗到有齊 `bigram` / `char_freq` 兩張表先至覆蓋，覆蓋之前連
`-wal` / `-shm` / `-journal` 都要刪埋（唔清就會攞住舊 WAL 蓋返落新 db 度）。

同一段仲有「**常用字排前**」開關（`Prefs.KEY_USAGE_REORDER`，預設開）：
熄咗淨係 `TTEngine.usageReorder = false`（`reorderByUsage()` 即刻 return），
**照樣繼續記數**。設定頁改完唔會 restart 個 service，所以 `onStartInputView`
每次都要重新讀一次。

`TTEngine.reorderByUsage()` 淨係喺 `processResult()`（打碼揀字嗰條主線）用，
而且**頭 9 個（第一頁）一律唔郁**，保留返字碼表原本嘅位置 —— 第一頁個格號就係
隻字個碼最後嗰個數字，調過位就即刻累到打熟咗嘅手勢。**第 10 位起（即係第二頁
開始）先至睇 bigram 排**：常用但唔喺頭九位嘅字最多推到第 10 位，反正嗰啲字本來
就冇碼可以記，一定要望住揀，推前咗淨係少揭幾版。

⚠️ 呢條規矩 2026-08-30 反轉過：**2026-08-28 至 08-30 期間啱啱相反**（淨係郁
第一頁、第二頁起唔郁），user 話係 bug 收返咗。下次見到「頭九位排靚啲」呢類諗頭，
**唔好郁第一頁**。實際個排法抽咗做 companion 嘅 pure function
`TTEngine.reorderByUsage(words, count)`（instance 嗰個淨係包住開關同 `bigramPrev`），
`UsageReorderTest` 盯死佢。
（順帶 `Host.charFreq` 冇人用，一齊刪咗，但 `UsageStats` 照樣繼續記單字次數。）**要打過至少 `TTEngine.MIN_USAGE_COUNT`
（＝ 2）次先至郁個次序**（2026-08-27 user 要求，以前 bigram 係 3 次）——
撳錯一下唔應該影響到之後嘅選字。用**穩定排序**（`sortedByDescending`），
冇資格嘅嘢（`qualified()` 回 -1）唔會亂咗原本次序。
讀寫都喺 `UsageStats` 入面：讀係 in-memory cache（`ensureLoaded()`
第一次先揸 sqlite），寫就即刻更新 cache、sqlite 嗰邊擺去背景 thread，
唔會拖慢緊住打緊字嘅 UI。

---

## 兩個容易踩親嘅位

### 1. 改咗顯示方式但高度冇變 → `onSizeChanged` 唔會 fire

`ChinesePadView.buildLayout()` **每次都要重新 `PadMetrics(context, w)`**，
唔可以 cache `onMeasure` 嗰個。拉長 ↔ 左留白 高度一樣，
只靠 `onSizeChanged` 就永遠唔會重新排位。

### 2. 轉角要用即時方向，唔可以用弦線

`GestureKeyTracker` 判「有冇喺呢格轉彎」係比較**入格前 60ms** 同**出格前 60ms**
嘅移動方向（`dirBack()`）。如果用「入口點→出口點」嘅弦線，
一個真正 90° 嘅轉角只會計到 45°，會漏鍵。呢個係實測踩過嘅坑。

---

### 英文滑動要行夠一整格先算

`swipeStartDistPx(box)` 講明拖幾遠先當「真係喺度滑」（開始畫線、放手會查詞庫）。
預設係一個 touch slop（中文九宮格滑去隔離格就係下一碼，要即刻收），
`LatinPadView` override 成 `max(box.w, box.h) * 1.2`：單撳嗰陣手指好易帶少少，
一帶就變咗條好短嘅 swipe，打乜都出錯字。qwerty 上面又冇兩個字母貼住嘅英文詞，
所以**拉到隔離格咁遠就放手，一律當誤觸**，照出返粒鍵本身。

行夠一個 slop 就會 `cancelPending()`（唔好再彈長撳嗰啲嘢出嚟），但係
`swiping` 要行夠 `swipeStartDistPx` 先至 true。`GestureKeyTracker` 由 DOWN
嗰下就一路收埋成條軌跡，所以夠距離之後條線／認字係由**起點**計起，冇甩頭。

## 滑動判定（三個線索）

```
分數 = 幾何信心(0~1) + 0.35 × weight信心(-1~+1)  ≥ 0.62 就當撳咗
```

- 幾何：**明顯減速再加速**（V 形）、入格出格方向轉得夠多
- weight：`mapped_table.weight` 砌成 prefix 權重表（`TTDb.prefixPlausibility`），
  加咗呢一碼之後完全冇字就 -1 直接剔走，大路字碼就加分
- **起點同終點永遠計**，唔會被 weight 否決

#### 中間格**唔可以**淨係計「留咗幾耐」

以前係「喺格入面留夠 `dwellMs` 就俾滿分」。呢個係錯嘅，user 報過：慢手由 `7`
一條直線拉去 `9`，喺 `8` 度留嘅時間一樣過到 `dwellMs`，就白白多咗個 `8`，
`790` 變咗 `789`。**留得耐 ≠ 撳咗** —— 慢慢經過都會留得耐。

而家要見到速度真係「跌落去、再彈返上嚟」先算數（`decideAndEmit`）：

```
dipped  = 格入面最慢嘅即時速度 < STOP_RATIO(0.35) × 成個 gesture 嘅平均速度
reaccel = 出格速度 > 最慢速度 × REACCEL_RATIO(2)
兩個都成立先有分（停夠 dwellMs = 1.0，唔夠 = 0.8）
```

即時速度用 `speedBack()`（回望 50ms 嘅**位移**，唔係路程 —— 喺一格度打圈／
震手位移細，一樣當停低咗）。`Visit.minSpeed` 喺入格夠 50ms 之後先開始取樣，
唔係個 window 會望返上一格嗰段快速移動。改呢度一定要跑
`GestureKeyTrackerTest`，入面有「慢手直線拉唔可以出中間格」同「慢手但真係
停一停就要出」兩個對照 case。

#### 滑動淨係喺打碼階段行

入咗選字模式啲數字鍵已經唔再係碼，而係「揀第幾個字」同 `0` = 揭下一頁 ——
user 報過滑 `7→9→0` 出到字之後，最尾嗰個 `0` 走咗去揭第二頁。兩邊一齊擋：

- **未起手**：`ChinesePadView.canSwipe()` 要求 `!engine.selectMode`，
  tracker 根本唔會 start，所以連條線都畫唔出。
- **滑到一半先入選字模式**：`onGestureKey()` 見到 `engine.selectMode` 就叫
  `abortSwipe()`。`KeyboardBaseView` 收到之後：`swipeDelegate` 唔再派鍵出去、
  `drawTrail()` 即刻唔畫、ACTION_UP 亦都**唔會**行 `tracker.finish()`
  （唔係最尾嗰格會補多下，一樣走咗去揀字／揭頁）。

畫線同出鍵要一齊停 —— 得個線繼續行但係冇反應，user 會以為部嘢壞咗。

#### 選字揭頁：三種排法，唔好再做 flick

`0` 撳一下係「下頁」。返上一頁本來係**撳住「下頁」向左掃**（`KeyboardBaseView`
一套 `canFlick` / `onFlick`），2026-08-25 user 話「swipe 左變咗下頁，好難用」，
成套 flick 連 `ChinesePadView` 嗰個 override 一齊**刪咗**，唔好再加返。

而家改成排位度解決，而且**三種排法由設定頁揀**（`Prefs.PagerLayout`，
設定頁「一般 → 選字翻頁」）。三種都淨係喺 `selectMode && totalPage > 1`
（`ChinesePadView.paging()`）嗰陣先生效：

| `PagerLayout` | 底行 |
| --- | --- |
| `PREV_NEXT` | 拆兩粒正常闊：左「上頁」、右 `0`（＝「下頁」） |
| `NEXT_PREV` | 拆兩粒正常闊：左 `0`（＝「下頁」）、右「上頁」 |
| `WIDE_NEXT`（**預設**） | 唔拆，成兩格闊嗰粒 `0` 就係「下頁」，**長撳 = 上頁** |

「上頁」係 `KeyAction.PREV_PAGE` → `TTCmd.PREV`。

粒「下頁」**寫住 `1/10`**（`TTEngine.pageHint`，由 1 起計，唔係 0）。
同同音鍵一樣係喺 `drawDigit` 度即時問 engine 攞，唔係 `Key.hint`（`boxes`
唔會逐次重砌）。拆兩粒嗰兩個排法擺喺**左上角**，冇分頁嗰陣 `pageHint` 係空，
個位就讓返俾長撳提示 `「」`。

`WIDE_NEXT` 有兩件事同其餘兩個唔同，改嗰陣兩邊都要一齊改：

- **排位由頭到尾唔郁**（`wantSplitPager()` 見到 `WIDE_NEXT` 一律回 false），
  所以粒鍵嘅 `Key` object 唔會重砌 —— 「而家係咪揭緊頁」一定要即場問
  `ChinesePadView.wideNextPage()`，唔可以入 `Key` 度。
- **長撳嗰個「」讓咗俾「上頁」**：`TTInputMethodService.onLongPress` 嘅
  `digit == 0` 嗰路要先問 `chinesePad?.wideNextPage()`，係就 `TTCmd.PREV`，
  唔係先至照舊 `TTCmd.OPENCLOSE`。畫面上左上角寫「上頁」（＝長撳做乜，
  同其他鍵一致），頁數讓咗去**右上角**（`drawCornerHintRight`）。

排位跟住 engine 狀態變，所以 `TTEngine.Host.onStateChanged()` 唔可以淨係
`invalidate()`：要行 `ChinesePadView.onEngineState()`，佢見到 `splitPager`
同而家想要嘅唔一樣先至 `relayout()`（每次 `onStateChanged` 都重排就嘥）。

中文係**即時出碼**：離開一格就即刻 `engine.press()`，九宮格內容即刻變。
滑 `7→9→3` 畫直角 = 順序撳咗三下（`GestureKeyTracker` 逐格判斷）。**英文唔用呢套** —— `LatinPadView.onSwipeEnd()` 淨係將
`tracker.points`（原始軌跡，`GestureKeyTracker` 一樣有 buffer，淨係唔理佢個
per-key 判斷）連埋 `keyCenter` 拋畀 IME service，放手之後**由 IME service**
（唔係 `LatinPadView`）用 `GestureDecoder` 查詞庫 —— 因為要連 caret 前後啲
字母一齊計，亦要成條軌跡一次過同候選字比對，唔係逐格判斷。

### 長撳 = 連撳（九宮格）

兩個唔同嘅位一齊做，唔好淨係改一邊：

- **起手長撳**：`KeyboardBaseView.longPressRunnable` 見到 `Key.holdRepeat`
  就即刻 `onKey()` 一次，而且**唔會**設 `longFired`，放手嗰下照計 → `77`。
  跟住拖走就變成 tracker 嗰條路（tracker 起點永遠 emit）→ `770`。
- **收手前停一停**：`GestureKeyTracker.finish()` 見到最後一格停夠
  `holdRepeatMs`（＝`Prefs.longPressMs`）就 emit 多一次 → `811`。
  英文唔使呢樣，所以 `holdRepeatMs` 預設 0（熄），淨係 `ChinesePadView` 開。

`0` 冇 `holdRepeat`，因為佢長撳係開關標點。

### 撳落即出（2026-08-27 加，2026-08-28 起冇得熄）

user 要求永遠開住，所以 `Prefs.KEY_INSTANT_KEY` 同設定頁嗰個開關都刪咗，
淨返段說明。`KeyboardBaseView` ACTION_DOWN 見到 `instantKey(key)` 就即刻 `host.onKey()`，
放手嗰下就唔再出（`instantFired`）。**淨係中文九宮格 `1`~`9` 先做**
（`ChinesePadView.instantKey`），而且淨係喺「長撳 = 連撳」嗰個狀態：

- 選字模式（長撳 = 同音字表）唔做
- 開咗 `longPressShortcut` 而又未打過碼（長撳 = 速選字表）唔做
- `0` 唔做（長撳 = 開關標點／上頁）

三樣一齊夾住先啱：

1. **DOWN 出鍵一定要喺 `tracker.start()` 之後**——`onKey()` 會改 engine 狀態，
   隨時觸發 `relayout()`（`boxes` 重砌），嗰粒 `KeyBox` 就會變咗舊嘢。
2. **`tracker.start(x, y, t, startEmitted = true)`**：`GestureKeyTracker` 起點
   **永遠 emit**，唔話佢知就會變咗打兩下。佢兩處要跳過：`decideAndEmit` 嘅
   `isFirst` 分支，同埋 `finish()` 嘅**基本**嗰下（「停夠耐再補一下」照出，
   唔係喺起點格停住放手就攞唔返個連撳）。
3. **長撳嗰下照計**：`longPressRunnable` 嘅 `holdRepeat` 分支唔設 `longFired`，
   所以「撳落一下 + 長撳一下」＝ 連撳兩下，同以前一模一樣。

改呢度一定要跑 `GestureKeyTrackerTest`，入面有四個 `startEmitted` 嘅 case
（起點唔可以補、冇離開過起點放手都唔補、停夠耐照補、拉去第二格照計）。

**「長撳 = 連撳」係最後一條路**：`longPressRunnable` 要 `Host.onLongPress()`
回 `false` 先至行到佢。九宮格 `1`~`9` 有兩個情況會截走（2026-08-25 加）：

- **一個碼都未打**（`!selectMode && currCode.isEmpty()`）→
  `TTEngine.shortcutDigit(d)`：直接開嗰格嘅速選字表（`mapped_table` id
  `1000 + d`），唔使再「撳個碼再撳速選掣」。打緊碼（`currCode` 有嘢）就唔截，
  照行返連撳，`77x` 呢啲碼撳得返。
  **呢一路預設熄咗**（`Prefs.longPressShortcut`，設定 →「其他」，2026-08-25 加）：
  就算未打碼，佢一樣食咗「長撳 = 連撳」嘅頭一下，`77`／`88` 撳極都唔出，
  所以要 user 自己開。
- **選字模式**→ `TTEngine.homoAt(slot)`：開嗰格嗰個字嘅同音字表，
  **唔使先撳「同音」掣**。就算查唔到同音字（多字詞、標點、`word_meta` 冇記錄）
  `onLongPress` 都要回 `true` 食咗佢 —— 跌返落連撳就會即刻揀咗個字，
  跟住放手嗰下再攞個數字起新碼，一撳出兩樣嘢。

## 開關標點（長撳 `0`）：包住 vs 移 caret

`TTEngine` 揀完一對標點係行 `host?.commitPair()`（**唔係** `commitText`），
`TTInputMethodService.commitPair()` 分兩種情況：

- **揀住咗一段字** → 「」**包住**佢：`揀咗嘅字` 變 `「揀咗嘅字」`。
  注意 `commitText` 本身係**取代**揀咗嗰段，所以一定要自己
  `getSelectedText()` 攞返段字接埋落中間，唔係就會蓋咗人哋段字。
- **冇揀字** → 出一對「」，再將 caret 移返兩個標點**中間**（`setSelection`，
  attach 唔到 `getExtractedText` 就跌落去發 DPAD_LEFT）。

長撳 `0` 嗰下（`onLongPress` → `TTCmd.OPENCLOSE`）淨係改 engine 狀態，
唔會 commit 任何嘢，所以 app 嗰邊揀住嘅字一路留到揀完標點先用得着。

### 長撳變體 popup：PopupWindow，永遠向上彈 + 絕對位置揀

**用 `KeyPopup`（`PopupWindow`）畫，唔係喺 `KeyboardBaseView.onDraw` 度畫。**
喺 view 入面畫一定俾 view 邊界剪走，最頂嗰行就永遠彈唔出鍵盤外面。`KeyPopup`
開咗 `isClippingEnabled = false` + `isAttachedInDecor = false`，個窗出得 IME
window 範圍，彈上 app 嗰邊；`isTouchable = false`，所以 touch 一路都係
`KeyboardBaseView` 收，長撳完照樣拉得去揀。

幾樣踩過坑，唔好改返轉頭：

- **永遠向上彈**（`popupTop = box.top - popupItemH - 8dp`，負數都照）。以前係
  「頂行冇位就向下彈」，結果英文數字行（`numRow` 開咗嗰陣 digits 係第 0 行）
  長撳 `0` 會向下彈到老遠蓋住第二行鍵，撳都撳唔到。之後改成頂住鍵盤個頂畫，
  又變成同粒鍵疊埋一舊俾手指遮住 —— 而家有咗 `KeyPopup` 就真係彈到鍵盤上面。
- **字大 30%**（`KeyPopup.TEXT_RATIO = 0.53`，本來 0.40×格高），格本身亦都
  闊咗少少（`box.w * 1.1`，最少 50dp）。手指遮住一半嗰陣要睇得清楚。
- **揀邊個 = 手指而家喺邊個格上面（絕對位置）**，唔係「行咗幾多步」。用相對
  步數嗰陣，貼邊嘅鍵（`p`、`0`）成行變體會俾 `popupLeft` 嘅 clamp 迫住向左推，
  睇到嘅高亮同手指位置完全對唔上，變成點拉都揀唔到。
- 但係「長撳完唔郁直接放手 = 打返粒鍵本身」要保住：`popupMoved` 未行夠一個
  `slop` 之前一律當第一個（`variants` 第一個永遠係粒鍵自己）。

### 滑動 hover 提示

滑動途中手指底下嗰粒鍵一定俾自己隻手遮住，所以 `updateHoverPopup()` 用同一個
`KeyPopup` 喺**粒鍵上面**浮個大字出嚟（`hoverLabel(box)`，淨係 `LatinPadView`
有實作，a~z 先出）。跨咗去另一粒鍵先郁個窗（`hoverBox` 擋住）—— 每次
`PopupWindow.update()` 都係一次 window relayout，逐個 MOVE event 郁就會 lag。

### 英文滑動：空格、context、候選欄

`TTInputMethodService.onSwipePath()` 一次過處理四樣嘢，改其中一樣之前睇清楚
另外幾樣：

- `latinWordDone`（啱啱滑完／喺候選欄揀完一個字）→ 今次係**下一個字**，
  補個空格，唔攞前面嗰個字做 context。冇咗佢就會變返以前嗰個 bug：
  `setComposingText` 蓋咗上一個字。
- 唔係嘅話就攞 caret 前後貼住嘅字母做 `prefix` / `suffix`
  （`GestureDecoder.decode(path, keyCenter, keyWidth, prefix, suffix)`），
  出到字之後要 `deleteSurroundingText(pre.length, suf.length)` 剷返啲舊字母。
  夾唔到就一步步放寬（先甩 suffix、再甩 prefix），乜都揾唔到就當呢次滑
  冇發生過（唔會屈硬出啲垃圾字）。
- `forceCandidates` 令 `refreshBars()` 夾硬出候選段，就算 `barMode` 係 OFF／TOOLS。
- 滑出嚟嗰個字會即刻 `setComposingText`（underline），但**淨係呢個狀態**先有
  underline —— 一打字（唔係 swipe）就即刻 `finishComposingText()` 取消，變返
  普通已入嘅字（`typeChar()` 見到 `latinSwiped` 就即刻做）。

### 自動補空格：句號（`.`）**唔可以**加返落去

`autoSpaceAfterPunct()` 淨係喺 `, ? !` 後面補空格。**句號故意唔喺個表入面**：
打網址（`google.com`）、小數、檔名、縮寫全部都係「字母 + `.` + 字母」，
同「句尾 + 開新句」喺打嗰一刻**分唔開** —— `google.` 同 `Hello.` 前面嗰橛
一模一樣咁普通，試過用 token 內容去估都靠唔住。補錯個空格會直接搞到網址
打唔到（user 報過：「簡直打不了」）。想斷句就自己撳 ␣。

URL／email／密碼／`TYPE_TEXT_FLAG_NO_SUGGESTIONS` 嘅欄再加多重保險：
`noAutoSpaceField` 會令成個 auto-space 熄晒。

## 英文詞庫

`assets/en_freq.txt` = `/mnt/d/sync_dev/eng/data/en-most.txt` 篩走非 a-z 之後、
再淨低最常用嗰 5 萬個嘅版本（`字 頻率`，已經由高到低排好）。原始開發版有 20 萬個，
但正式版唔使咁多，淨低頭 5 萬個就夠（`head -n 50000`）——記住之後如果要再攞新資料，
一樣要裁到 5 萬個先入 assets，唔係 apk 會肥好多。

- **唔好喺 `onCreate` 載**。`EnDict.preloadAsync()` 淨係喺 `switchMode(LATIN)` 嗰陣叫，
  背景 thread 低優先次序，未載完 `EnDict.get()` 回 null，當冇提示就算，UI 唔會 lag。
- 內部用一條 `blob: String` + `starts: IntArray` + `weight: FloatArray`，
  唔係一堆 String object。比對直接喺 blob 上面行，唔好加 substring。
- `EnDict` 本身**冇** swipe 認字邏輯，淨係 `word`/`charAt`/`weightAt`/`wordLength`
  呢幾個 public accessor 畀 `GestureDecoder` 用。

### 英文 swipe 認字：`GestureDecoder`（AOSP 手勢輸入嗰套概念）

唔再逐格判斷「撳咗邊粒鍵」（`GestureKeyTracker` 嗰套 dwell/轉角 heuristic 只有
中文九宮格仲用緊）。改成成條手指軌跡（`tracker.points`）一次過同候選字嘅
「理想路徑」（逐個字母嘅鍵中心連成線，連續重複字母收埋做一格）比對形狀＋位置：

1. 首尾字母分桶粗篩候選（`byFirstLast`），夾唔到就放寬做淨係信第一個字母
   （`byFirst`，file 頭 2 萬個常用字）。
2. 兩條軌跡都用弧長重新取樣做固定 32 點（`resample`，$1 recognizer 嗰套做法），
   逐點計距離攞平均（`pathCost`），再加埋首尾點距離嘅額外罰分（`ENDPOINT_WEIGHT`）。
3. 距離用 `keyWidth` 正規化（唔同螢幕、唔同鍵盤大細都夾到），再同
   `ln(頻率) × LM_WEIGHT` 夾埋做總分。
4. 改呢個評分公式之前，睇返 `EnDictRealDataTest`（真字典 + 手震雜訊都要夾到）
   同 `EnDictTest`（fake 座標，形狀＋常用度都要試到）。

### 揀完一個字，估下一個字：`NextWordModel`

`EnTrie`（unigram trie，bottom-up 快取每個節點嘅 top-K 常用字）+
`assets/en_bigram.txt`（`word1 word2 頻率`，依家係人手揀嘅常見詞組種子數據，
唔係真語料統計出嚟，之後要換成真 corpus 就跟同一個格式重新產生呢個檔就得）。
`space()` / `onPickCandidate()` 揀完字就攞 `NextWordModel.predictNext(prevWord)`
入 `latinSuggestions`；打緊下一個字就用 `suggestWithPrefix(prevWord, prefix)`
—— bigram 夾 prefix 嘅擺前面，唔夠先用 `EnTrie.completions()` 補位。

---

## 改完之後

```bash
JAVA_HOME=/opt/android-studio/jbr ./gradlew :app:assembleDebug :app:testDebugUnitTest
```

要保持**零 warning**。純 JVM unit test 有四份：`GestureKeyTracker`、`EnDict`、
`VoiceActivity`（語音 VAD 門檻）、`SttAudio`（ADTS header 逐個 byte）——
改判定邏輯、評分公式、VAD 門檻或者 header bit packing 一定要跑。
UI 就上模擬器影相睇。

## 簽名：兩條 key，唔好撈亂

| 用途 | key | 點行 |
| --- | --- | --- |
| side-load（dl 嘅 `threethree-v<N>.apk`、GitHub release） | `~/.android/debug.keystore` | `./gradlew assembleRelease`（預設） |
| 上架 Google Play | `~/.android/tt-release.keystore` | `./gradlew bundleRelease -Ptt.upload` |

正式嗰條 key（2026-08-25 user 自己 `keytool -genkeypair` 出嚟）**唔喺 repo 入面**，
密碼亦都唔會入 git：

```
~/.android/tt-release.keystore
~/.android/tt-release.properties   # storePassword / keyAlias / keyPassword
```

`app/build.gradle.kts` 見到 `-Ptt.upload` 先至砌 `upload` 呢個 `signingConfig`，
兩個檔案有一個唔見就即刻 fail（唔會靜靜雞跌返落 debug key）。**冇加 `-Ptt.upload`
就一定係 debug key** —— side-load 果條線一路都係嗰條 key 簽，靜靜雞換咗，
啲人就要 uninstall 咗先裝到新版。`.gitignore` 已經封晒 `*.keystore` / `*.jks` /
`*keystore.properties`，keystore 唔見咗就永遠再 update 唔到個 app，記住備份。

Play 收 `.aab` 唔收 `.apk`（新 app），所以上架果個係 `bundleRelease`；
想自己裝嚟試就 `assembleRelease -Ptt.upload`（同 debug key 嗰個裝唔埋同一部機）。

## 版本號：每次改完自動加一

`app/build.gradle.kts` 嘅 `versionName`（`x.y.z`）同 `versionCode`：
**user 叫改嘢（唔理係加功能定係 fix bug），一改完就自動兩個一齊 +1**——
`versionName` 加返 patch（`1.0.0` → `1.0.1`），`versionCode` 都 +1，
唔使 user 特登講先做。第一個正式版由 `1.0.0` 開始（2026-08-21 定嘅）。
呢個同 `/mnt/nas4/web/subdomains/dl/` 度 `threethree-v<N>.apk` 個 `N`（每次 build release 版都加一）
係兩件唔同嘅嘢，唔好搞埋一齊。
