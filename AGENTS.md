# AGENTS.md — 九万輸入法 TQ9 (Android)

改嘢之前請先讀晒呢版。呢個 repo 係 Windows 版 `/mnt/d/dev/Q9/TQ9/`（C# WinForms）
移植過嚟嘅 Android system keyboard，行為要同原版夾得住。

---

## 一句講晒

九方過期專利 HK1035043 嘅 numpad 中文輸入法。撳 2~3 個碼查 `mapped_table` 出候選字，
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
adb shell ime enable hk.tq9/.ime.TQ9InputMethodService
adb shell ime set    hk.tq9/.ime.TQ9InputMethodService
```

### 喺模擬器度試鍵盤，有三個陷阱

1. **一定要 `adb shell settings put secure show_ime_with_hard_keyboard 1`**。
   模擬器當自己有實體鍵盤，唔開呢個 setting 就唔會彈輸入法出嚟。
2. **唔好 `adb shell am force-stop hk.tq9`**。IME service 同 app 同一個 package，
   force-stop 會殺埋 IME，系統就會跌返去 Gboard。用 `am start` 就夠。
3. **每次 `adb install -r` 之後都要再 `ime enable` + `ime set` 一次**，
   系統會當佢 reinstall 而 reset。

設定頁最底有「試打」四個欄（普通／email／PIN／搜尋）同埋實時預覽，慳返開第三方 app 去試。
搜尋嗰欄係用嚟睇 `⏎` 有冇變 `🔍`（`enterLabelFor()` 睇 `IME_ACTION_SEARCH`）。

---

## 唔好搞亂嘅嘢

### 九宮格排位係 numpad，唔係電話

`7 8 9` 喺最上、`1 2 3` 喺最落，跟返 `Q9Form.cs` 嘅 `ResizeAllButton()`。
底行係 `[0 佔兩格][取消]`。改過嚟電話排法就同原版打法唔同曬。
左下角淨返粒 `🌐`（成格闊）—— `🎤` 搬咗上工具 bar，喺 `📋` 隔籬，
亦都係左上角嗰粒鍵揀得嘅其中一個 `PadFunc`。

### mapped_table 嘅 id 有特別意思

| id | 係乜 |
| --- | --- |
| `0` | 標點（首頁撳 0） |
| `1` | 開關標點成對（長撳 0） |
| `10`, `20`, … `90` | 姓氏表（撳咗第一碼之後再撳 0） |
| `10`~`999` | 正常字碼表，`weight` = 常用度 |
| `1000`~`1009` | 速選字表（⭐；首頁 = 1000，撳咗 1~9 之後 = 1001~1009） |

打碼邏輯（`Q9Engine.press`）：夠三碼、或者中途撳 0 收尾，就查表出候選字。

### 字要用 grapheme cluster 拆

`Q9Db.splitGraphemes()` 用 `BreakIterator`，等同 C# 嘅 `StringInfo`。
用 `String.length` / `toCharArray` 會拆爛 emoji 同香港增補字符集。
判斷「係咪單一個字」要用 `codePointCount`，唔係 `length`。

### 撳鍵之間唔可以有死位

畫面見到嘅隙係 `drawFace()` 縮咗 `gapPx` 畫出嚟嘅，`KeyBox` 本身要貼實
（`RowsPadView` 最後一格／最後一行會夾硬去到最右最底）。
ACTION_DOWN 用 `boxNear()`（搵唔到就攞 14dp 內最近嗰粒），
**唔好**改用 `boxAt()` —— 但係滑動判定（`swipeKeyAt`）就一定要用 `boxAt()`，
唔係格外面都會當撳咗邊上嗰粒。

### 圖檔

`assets/img/` 直接照抄 Windows 版 `files/img/`，`0_1`~`0_9` 係首頁筆形，
`1_1`~`9_9` 係第二碼提示。**Android 版冇 `10_x`** —— 關聯字改咗喺上面條 bar 揀，
唔會再迫落九宮格。App icon 直接用 `TQ9/logo.png`。

### 系統輸入法揀選視窗只可以有一個「九万」

`res/xml/method.xml` 得一個 subtype。中英數符號係喺鍵盤入面自己切，
加多個 subtype 就會喺系統嗰度變兩個輸入法。

---

## 架構

```
core/   Q9Db       sqlite 存取、assets 安裝、換 db、weight prefix 統計
        Q9Engine   九万狀態機（Q9Form.cs 移植），唔掂 Android UI
        EnDict     5 萬字英文詞庫（blob + starts + weight，慳記憶體）
        EmojiDict  assets/emoji.txt，分類 + 用英文／中文關鍵字搵
        ClipHistory clipboard 歷史（JSON 存喺 Prefs）
        AiRewrite  Gemini generateContent，改寫揀咗嗰段字
        UsageStats 另一個 sqlite（usage_stats.db，同 dataset.db 分開）：
                   連續兩個中文字嘅 bigram 次數、每隻字打咗幾多次
        Prefs      全部設定
swipe/  GestureKeyTracker   滑動中間鍵判定（純 Kotlin，有 unit test）
ime/    TQ9InputMethodService   IME 主體，所有 view 嘅 host
        KeyboardBaseView        排版／畫鍵／掂觸／畫線／長撳 popup／長撳 ␣ 郁 caret
        ChinesePadView          九宮格（KeyboardBaseView）
        RowsPadView             一行行按 weight 分闊度嘅底
        LatinPadView / SymbolPadView / NumberPadView（RowsPadView）
        EmojiPadView            emoji grid（ViewGroup，唔係 KeyboardBaseView）
        ClipboardListView       長撳「貼上」之後蓋喺 padHolder 上面嘅 overlay
        PadMetrics              尺寸同顯示方式計算
        OptionBarsView          上面條 bar（三段：關／候選字／工具）
ui/     SettingsActivity / MicPermissionActivity
```

`Q9Engine` 唔應該 import 任何 `android.view.*`；佢淨係吐狀態，由 `ChinesePadView` 畫。

---

## UI 高度：唔可以無啦啦跳

### 所有鍵盤一樣咁高

`RowsPadView.onMeasure` **唔係**逐行乘行高，係直接攞 `PadMetrics.padHeightPx()`
（＝中文九宮格四行嘅總高）。英文開咗數字行有 5 行、符號頁有 5 行、中文永遠 4 行，
總高度一樣，行數多嗰啲每行自然矮啲。加行減行**唔會**令個窗跳高跳低，
所以唔好喺子類度加返 `rowHeightDp` 呢類逐行計嘅嘢。

### 上面條 bar 三段都係一行

`OptionBarsView` 三段（`BarMode`）每段都係得**一行 42dp**。以前有條「狀態」細字
（字碼、`[同音]`、頁數）擺喺最上面，一出現就成個鍵盤高咗一截，已經**拆咗**——
`Q9Engine.status` 仲計緊，但係冇人畫。要出 message 就用 `toast()`，
唔好再喺條 bar 上面加行。

### 鍵盤永遠貼實底

`PadMetrics` 冇咗 `extraBottom`／`Prefs.floatY`。`PadAlign.FLOATING` 已經刪咗
（自由移動嗰粒冇用，`Prefs.floatX`／`ChinesePadView.nudgeFloat` 一齊清咗）——
而家 `PadAlign` 得 `STRETCH`／`LEFT_GAP`／`RIGHT_GAP` 三個，
`OptionBarsView` 個 sizeBtn 淨係轉呢三個，拖動就淨係得上下（拉高拉低）。
「拉高／拉低」係 `Prefs.heightScale`（0.6~1.8），
`PadMetrics.cellH` 同 `PadMetrics.rowHeightPx()` 兩邊都要乘返佢，
唔係英文鍵盤就唔會跟住變。

`EmojiPadView` 同 `ClipboardListView` 唔係 `KeyboardBaseView`，
高度靠 `forcedHeightPx`（開之前喺 `rememberPadHeight()` 記低上一個 pad 幾高），
所以**一定要喺 `padHolder.removeAllViews()` 之前記**，唔係就攞到 0。

---

## 條 bar 唔可以出唔返嚟

`EmojiPadView` 同 `ClipboardListView` 冇自己嘅「關閉」掣 —— 粒 `✖` 統一喺
`OptionBarsView` 最左。所以 `refreshBars()` 見到 `specialPad`
（`mode == EMOJI || overlay != null`）就一定要 **force `BarMode.TOOLS` + 出粒 ✖ + 唔准 GONE**，
唔係 user 熄咗條 bar 之後開 emoji 就返唔到去普通鍵盤。

`showOverlay()` / `hideOverlay()` 兩邊都會叫 `refreshBars()`。

## 候選欄吉住就出速選字

`refreshBars()` 喺中文模式見到 `selectWords` 同 `relateHints` 都空，
就會出 `quickPicks`（`mapped_table` id 1000，`onCreate` 讀一次）同時
`showingQuickPicks = true`。撳落去要行 `Q9Engine.pickQuick()`，
**唔係** `pickCandidateAt()` —— 嗰陣根本冇入過 selectMode。
`pickQuick()` 內部係 `startSelectWord(listOf(word))` + `selectWord(1)`，
所以簡繁輸出、同音、關聯字全部照行。

## 同音字就係一個 flag，唔好再加嘢

`Q9Engine.pressHomo()` **淨係** `homo = !homo`，跟返原版（`Q9Form.cs:316`）：
打字後不出字，要打碼揀個字先至彈同音字表出嚟。試過改成「一撳就即刻開表」
（有 `lastWord` 就開佢嘅同音字，冇就開速選字表），user 話打斷咗打字流程，**收返咗**。
撳一下淨係著／熄粒掣（會變藍），唔可以換走而家個字表。

用同音字打完之後，`homoCodeHint` 會記住嗰個字**正路點打**（`db.getCode()`），
畫喺同音鍵左上角（`ChinesePadView.drawFunction` 即時攞，唔係 `Key.hint`，
因為 `boxes` 唔會逐次重砌）。打多一個普通字就喺 `selectWord()` 度清走。
**注意有啲字（例如「嘅」「喺」）根本冇 `word_meta` 記錄**，`getHomo()` 回空，
嗰陣 `selectWord()` 會直接 `cancel()`。

## 搵 emoji 唔會真係入字落個欄，但會 set 做 composing text

`emojiSearch` 開住嗰陣，`typeChar()` 同 `Q9Engine.Host.commitText()` 兩邊
都會攔住啲字入條 `emojiQuery`，結果出喺候選字條 bar，**唔會** `commitText`。
但為咗等 user 見到自己打緊乜（唔係就淨係得個候選字 bar，睇唔到個 input），
每次 `emojiQuery` 一變就會 `syncEmojiComposing()` set 做 composing text，
揀咗 emoji 或者打多個字都會自然取代／清走（`commitText` 蓋咗 composing 區），
`endEmojiSearch()` 見到仲有殘留就 `commitText("", 1)` 清走。
所以英文（打 `cat`）同中文九宮格（打「貓」）都搵得到。
`switchMode()` 去到 LATIN／CHINESE 以外就會自動熄咗佢。

## 中文使用習慣統計：bigram 同每字次數

`UsageStats`（`usage_stats.db`，同 `dataset.db` 分開存）記兩樣嘢：連續打嘅
兩個中文字（`Q9Engine.bigramPrev`）、同每隻字打咗幾多次。淨係計**單字**
（`isHanChar()` 篩走標點同多字詞），揀咗多字詞、標點、或者換咗行
（`onLineBreak()`）都會斷咗個 bigram 鏈，唔會屈埋唔啱嘅組合。

`Q9Engine.reorderByUsage()` 淨係喺 `processResult()`（打碼揀字嗰條主線）用：
第一頁（頭 9 個）睇 bigram（夠 3 次先郁，次數越大越前），第九個之後睇單字
次數；兩邊都用**穩定排序**（`sortedByDescending`），冇資格嘅嘢唔會亂咗原本
次序。讀寫都喺 `UsageStats` 入面：讀係 in-memory cache（`ensureLoaded()`
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

## 滑動判定（三個線索）

```
分數 = 幾何信心(0~1) + 0.35 × weight信心(-1~+1)  ≥ 0.62 就當撳咗
```

- 幾何：停留耐 / 減速再加速、入格出格方向轉得夠多
- weight：`mapped_table.weight` 砌成 prefix 權重表（`Q9Db.prefixPlausibility`），
  加咗呢一碼之後完全冇字就 -1 直接剔走，大路字碼就加分
- **起點同終點永遠計**，唔會被 weight 否決

中文係**即時出碼**：離開一格就即刻 `engine.press()`，九宮格內容即刻變。
滑 `7→9→3` 畫直角 = 順序撳咗三下。英文就相反，`onGestureKey` 只係 buffer，
放手之後**由 IME service**（唔係 `LatinPadView`）查詞庫 —— 因為要連 caret
前後啲字母一齊計。

### 長撳 = 連撳（九宮格）

兩個唔同嘅位一齊做，唔好淨係改一邊：

- **起手長撳**：`KeyboardBaseView.longPressRunnable` 見到 `Key.holdRepeat`
  就即刻 `onKey()` 一次，而且**唔會**設 `longFired`，放手嗰下照計 → `77`。
  跟住拖走就變成 tracker 嗰條路（tracker 起點永遠 emit）→ `770`。
- **收手前停一停**：`GestureKeyTracker.finish()` 見到最後一格停夠
  `holdRepeatMs`（＝`Prefs.longPressMs`）就 emit 多一次 → `811`。
  英文唔使呢樣，所以 `holdRepeatMs` 預設 0（熄），淨係 `ChinesePadView` 開。

`0` 冇 `holdRepeat`，因為佢長撳係開關標點。

### 英文滑動：空格、context、候選欄

`onSwipeLetters()` 一次過處理三樣嘢，改其中一樣之前睇清楚另外兩樣：

- `latinWordDone`（啱啱滑完／喺候選欄揀完一個字）→ 今次係**下一個字**，
  補個空格，唔攞前面嗰個字做 context。冇咗佢就會變返以前嗰個 bug：
  `setComposingText` 蓋咗上一個字。
- 唔係嘅話就攞 caret 前後貼住嘅字母做 `prefix` / `suffix`
  （`EnDict.fromGesture(seq, prefix, suffix)`），出到字之後要
  `deleteSurroundingText(pre.length, suf.length)` 剷返啲舊字母。
  夾唔到就一步步放寬（先甩 suffix、再甩 prefix），唔好一次都出唔到。
- `forceCandidates` 令 `refreshBars()` 夾硬出候選段，就算 `barMode` 係 OFF／TOOLS。

## 英文詞庫

`assets/en_freq.txt` = `/mnt/d/sync_dev/eng/data/en-most.txt` 篩走非 a-z 之後、
再淨低最常用嗰 5 萬個嘅版本（`字 頻率`，已經由高到低排好）。原始開發版有 20 萬個，
但正式版唔使咁多，淨低頭 5 萬個就夠（`head -n 50000`）——記住之後如果要再攞新資料，
一樣要裁到 5 萬個先入 assets，唔係 apk 會肥好多。

- **唔好喺 `onCreate` 載**。`EnDict.preloadAsync()` 淨係喺 `switchMode(LATIN)` 嗰陣叫，
  背景 thread 低優先次序，未載完 `EnDict.get()` 回 null，當冇提示就算，UI 唔會 lag。
- 內部用一條 `blob: String` + `starts: IntArray` + `weight: FloatArray`，
  唔係一堆 String object。比對直接喺 blob 上面行，唔好加 substring。
- `EnDict.fromGesture` 兩條路：
  - **精準路徑**（`strictFromGesture`）：頭尾字母一定要啱、成串字母要係目標字嘅
    subsequence，揀字 = `使用頻率(ln) - 長度差罰分`。滑得靚嗰陣好準。
  - **鬆路徑**（`looseFromGesture`）：精準路徑乜都揾唔到（多數係中間掃錯／
    多咗粒鍵，例如 `v` 畫咗喺 `b` 度）先至用——淨係信第一個字母，喺
    `bucketsByFirst`（file 頭 2 萬個最常用字，按第一個字母分桶）度用 LCS
    （`shapeScore`）計形狀夾唔夾，但**常用度為主**：weight 差幾多就要 shape
    差成倍先追得返，情願估一個形狀差好遠但成日見嘅字，都好過估一個形狀啱晒
    但未見過嘅怪字。改呢兩條路嘅分數公式之前，睇返 `EnDictRealDataTest` 用真
    字典夾唔夾得到常見字。

---

## 改完之後

```bash
JAVA_HOME=/opt/android-studio/jbr ./gradlew :app:assembleDebug :app:testDebugUnitTest
```

要保持**零 warning**。`GestureKeyTracker` 同 `EnDict` 有 unit test（純 JVM），
改判定邏輯或者評分公式一定要跑。UI 就上模擬器影相睇。

## 版本號：每次改完自動加一

`app/build.gradle.kts` 嘅 `versionName`（`x.y.z`）同 `versionCode`：
**user 叫改嘢（唔理係加功能定係 fix bug），一改完就自動兩個一齊 +1**——
`versionName` 加返 patch（`1.0.0` → `1.0.1`），`versionCode` 都 +1，
唔使 user 特登講先做。第一個正式版由 `1.0.0` 開始（2026-08-21 定嘅）。
呢個同 `/mnt/nas4/web/subdomains/dl/` 度 `tq9-v<N>.apk` 個 `N`（每次 build release 版都加一）
係兩件唔同嘅嘢，唔好搞埋一齊。
