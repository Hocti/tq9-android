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

設定頁最底本來有「試打」四個欄（普通／email／PIN／搜尋）同埋實時預覽，
**而家收埋咗**（`SettingsActivity.SHOW_DEBUG_SECTIONS = false`，user 唔想見到）。
`buildTryBox()` / `buildPreview()` 一行都冇刪 —— 想 debug 排位就改返做 `true`，
唔使開第三方 app。搜尋嗰欄係用嚟睇 `⏎` 有冇變 `🔍`
（`enterLabelFor()` 睇 `IME_ACTION_SEARCH`）。

---

## 唔好搞亂嘅嘢

### 九宮格排位係 numpad，唔係電話

`7 8 9` 喺最上、`1 2 3` 喺最落，跟返 `Q9Form.cs` 嘅 `ResizeAllButton()`。
底行係 `[0 佔兩格][取消]`；選字夠兩頁嗰陣兩格闊嗰粒 `0` 會拆做
`[下頁][上頁]` 兩粒正常闊（見下面「選字揭頁」）。改過嚟電話排法就同原版打法唔同曬。
左下角淨返粒 `🌐`（成格闊）—— `🎤` 搬咗上工具 bar，喺 `📋` 隔籬，
亦都係左上角嗰粒鍵揀得嘅其中一個 `PadFunc`。

### 底行嘅規矩（英文／符號／純數字）

- **左下兩粒一定係「返去英文／中文」**（`Eng` 行先，跟住先至 `中`）—— 有兩個例外：
  中文九宮格自己就係中文，左下角淨係 `Eng`；**純數字頁**兩粒搬咗去**右上角**
  （user 要求，左下角讓咗俾 `0` `.` `-`）。
  英文嗰粒**寫 `Eng` 唔寫 `ABC`**（2026-08-25 user 要求，全部頁一致）。
- **`⏎` 上面嗰粒一定係 `⌫`**。所以符號頁嘅分頁掣（`€£¥`／`?123`）同 `⌫`
  都喺倒數第二行嘅最左同最右，純數字頁嘅 `⌫` 亦都由右上角搬咗去 `⏎` 上面。
  第一頁嗰粒分頁掣**寫三個銀紙符號 `€£¥`**（第二頁頭一行就係啲銀紙），
  以前寫 `=\<`，冇人知係乜。
- 讓返出嚟嘅位：符號第一頁底行 space 右邊順住排 `, . ? ; /` 五粒（本來散喺
  上面兩行）；第二頁唔要標點，space 同 `⏎` 拉長，`numpad` 掣再升多一行。
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

### 純數字頁：成頁唔准長撳

`NumberPadView.allowLongPress()` 一律回 `false`（`KeyboardBaseView` 嗰個 hook）。
打電話號碼／金額撳耐咗少少就彈個符號 popup 出嚟好煩，所以數字鍵**用 `num()`
唔用 `digitKey()`**（後者會帶 `variants`）。

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
        UsageStats 另一個 sqlite（usage_stats.db，同 dataset.db 分開）：
                   連續兩個中文字嘅 bigram 次數、每隻字打咗幾多次
        Prefs      全部設定
swipe/  GestureKeyTracker   中文九宮格滑動中間鍵判定（純 Kotlin，有 unit test）
        GestureDecoder      英文 swipe 認字：AOSP 手勢輸入嗰套概念嘅 Kotlin 版
                   （軌跡 vs 候選字理想路徑做形狀比對，唔係逐格判斷撳咗邊粒鍵）
ime/    TQ9InputMethodService   IME 主體，所有 view 嘅 host
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
- **左右** = `Prefs.widthScale`（0.45~1.6），淨係 `LEFT_GAP` / `RIGHT_GAP` 有用。
  **淨係入 `cellW`，唔可以入 `cellH`** —— 兩者本來都由同一個 `unit` 出，
  一唔小心就會變成「左右拉埋高度都跟住變」。
  方向要跟顯示方式反（見 `onWidthDrag`）：永遠都係「拖向留白嗰邊 = 拉闊」。

設定頁嗰幾條尺寸 slider（按鍵大細／最大闊度／最大高度／按鍵高度／鍵盤高度）
全部收埋咗（`SettingsActivity.SHOW_HIDDEN_OPTIONS = false`，一行 code 都冇刪），
剩返「字體大細」同「邊框粗幼」—— 長闊而家一律喺鍵盤度直接拖。

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

**英文／符號頁亦都夾硬開返條 bar**：呢兩頁靠佢出打字提示同滑出嚟嘅字，
冇咗就等於打盲舖。`refreshBars()` 見到 `mode` 係 `LATIN`／`SYMBOL` 而
`effective == BarMode.OFF` 就升做 `CANDIDATES`。**唔會改到 `barMode` 本身** ——
返到中文頁照樣跟返 user 設定嗰個開關。

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

## 畀人睇嘅字：全部正體中文書面語

app 入面所有 user 見到嘅字（設定頁、toast、鍵面、空狀態提示）一律用
**正體中文書面語**，唔用廣東話口語（「冇」→「沒有」、「撳」→「按」、
「而家」→「目前」…）。**注釋同 commit message 唔使跟**，照舊用口語。

個名一律叫「**九万輸入法**」，簡稱「**九万**」。「TQ9」係 project 個英文名，
**淨係可以喺 code／檔名／package 度出現**，唔可以出現喺畀人睇嘅字入面
（`strings.xml` 個 `subtype_en` 就係因為咁刪咗）。

## AI 改寫（✨）

- **唔使揀住字都用得**：揀咗就淨係改揀咗嗰段，冇揀就當「改寫成個輸入框」——
  `runAi()` 會 `setSelection(0, 全長)` 再交出去。**出返嚟之前要再全選一次**：
  等緊 Gemini 嗰幾秒 user 隨時撳過個欄，一撳 caret 就散咗個 selection，
  `commitText` 就會變成插埋落去而唔係取代。
- **完全冇入 API key 就成粒掣唔見咗**（`setAiVisible`，唔係淨係灰咗）。
  灰咗嗰個狀態留返俾「有 key 但個欄空咗」。
- 撳唔撳得由 `applyAiState()` 話事，`onUpdateSelection` 每次都會重新計
  （唔可以好似以前咁「揀嘅狀態冇變就 return」—— 而家個欄有冇字都影響到）。

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

除咗個 flag，仲有第二條路入同音字表：**選字模式長撳嗰格**
（`Q9Engine.homoAt()`，2026-08-25 加）。兩條路出嚟嘅表一模一樣（同一句
`db.getHomo()`），亦都一樣會 set `afterHomo`，所以揀完照樣喺同音鍵左上角
寫返個字正路點打。`homoAt()` 唔會掂 `homo` 個 flag 以外嘅嘢，
開關標點模式（`openclose`）就直接唔做 —— 嗰陣個表係「」呢啲一對對嘅標點。

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
- weight：`mapped_table.weight` 砌成 prefix 權重表（`Q9Db.prefixPlausibility`），
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

#### 選字揭頁：「下頁」拆兩粒，唔好再做 flick

`0` 撳一下係「下頁」。返上一頁本來係**撳住「下頁」向左掃**（`KeyboardBaseView`
一套 `canFlick` / `onFlick`），2026-08-25 user 話「swipe 左變咗下頁，好難用」，
成套 flick 連 `ChinesePadView` 嗰個 override 一齊**刪咗**，唔好再加返。

而家改成排位度解決：`ChinesePadView.wantSplitPager()`（＝`selectMode &&
totalPage > 1`）為真嗰陣，兩格闊嘅 `0` 拆做兩粒正常闊 ——
左邊仲係 `0`（＝「下頁」，撳開嗰個位唔變），右邊係新嘅
`KeyAction.PREV_PAGE`（「上頁」→ `Q9Cmd.PREV`）。

排位跟住 engine 狀態變，所以 `Q9Engine.Host.onStateChanged()` 唔可以淨係
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

**「長撳 = 連撳」係最後一條路**：`longPressRunnable` 要 `Host.onLongPress()`
回 `false` 先至行到佢。九宮格 `1`~`9` 有兩個情況會截走（2026-08-25 加）：

- **一個碼都未打**（`!selectMode && currCode.isEmpty()`）→
  `Q9Engine.shortcutDigit(d)`：直接開嗰格嘅速選字表（`mapped_table` id
  `1000 + d`），唔使再「撳個碼再撳速選掣」。打緊碼（`currCode` 有嘢）就唔截，
  照行返連撳，`77x` 呢啲碼撳得返。
- **選字模式**→ `Q9Engine.homoAt(slot)`：開嗰格嗰個字嘅同音字表，
  **唔使先撳「同音」掣**。就算查唔到同音字（多字詞、標點、`word_meta` 冇記錄）
  `onLongPress` 都要回 `true` 食咗佢 —— 跌返落連撳就會即刻揀咗個字，
  跟住放手嗰下再攞個數字起新碼，一撳出兩樣嘢。

## 開關標點（長撳 `0`）：包住 vs 移 caret

`Q9Engine` 揀完一對標點係行 `host?.commitPair()`（**唔係** `commitText`），
`TQ9InputMethodService.commitPair()` 分兩種情況：

- **揀住咗一段字** → 「」**包住**佢：`揀咗嘅字` 變 `「揀咗嘅字」`。
  注意 `commitText` 本身係**取代**揀咗嗰段，所以一定要自己
  `getSelectedText()` 攞返段字接埋落中間，唔係就會蓋咗人哋段字。
- **冇揀字** → 出一對「」，再將 caret 移返兩個標點**中間**（`setSelection`，
  attach 唔到 `getExtractedText` 就跌落去發 DPAD_LEFT）。

長撳 `0` 嗰下（`onLongPress` → `Q9Cmd.OPENCLOSE`）淨係改 engine 狀態，
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

`TQ9InputMethodService.onSwipePath()` 一次過處理四樣嘢，改其中一樣之前睇清楚
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

要保持**零 warning**。`GestureKeyTracker` 同 `EnDict` 有 unit test（純 JVM），
改判定邏輯或者評分公式一定要跑。UI 就上模擬器影相睇。

## 簽名：兩條 key，唔好撈亂

| 用途 | key | 點行 |
| --- | --- | --- |
| side-load（dl 嘅 `tq9-v<N>.apk`、GitHub release） | `~/.android/debug.keystore` | `./gradlew assembleRelease`（預設） |
| 上架 Google Play | `~/.android/tq9-release.keystore` | `./gradlew bundleRelease -Ptq9.upload` |

正式嗰條 key（2026-08-25 user 自己 `keytool -genkeypair` 出嚟）**唔喺 repo 入面**，
密碼亦都唔會入 git：

```
~/.android/tq9-release.keystore
~/.android/tq9-release.properties   # storePassword / keyAlias / keyPassword
```

`app/build.gradle.kts` 見到 `-Ptq9.upload` 先至砌 `upload` 呢個 `signingConfig`，
兩個檔案有一個唔見就即刻 fail（唔會靜靜雞跌返落 debug key）。**冇加 `-Ptq9.upload`
就一定係 debug key** —— side-load 果條線一路都係嗰條 key 簽，靜靜雞換咗，
啲人就要 uninstall 咗先裝到新版。`.gitignore` 已經封晒 `*.keystore` / `*.jks` /
`*keystore.properties`，keystore 唔見咗就永遠再 update 唔到個 app，記住備份。

Play 收 `.aab` 唔收 `.apk`（新 app），所以上架果個係 `bundleRelease`；
想自己裝嚟試就 `assembleRelease -Ptq9.upload`（同 debug key 嗰個裝唔埋同一部機）。

## 版本號：每次改完自動加一

`app/build.gradle.kts` 嘅 `versionName`（`x.y.z`）同 `versionCode`：
**user 叫改嘢（唔理係加功能定係 fix bug），一改完就自動兩個一齊 +1**——
`versionName` 加返 patch（`1.0.0` → `1.0.1`），`versionCode` 都 +1，
唔使 user 特登講先做。第一個正式版由 `1.0.0` 開始（2026-08-21 定嘅）。
呢個同 `/mnt/nas4/web/subdomains/dl/` 度 `tq9-v<N>.apk` 個 `N`（每次 build release 版都加一）
係兩件唔同嘅嘢，唔好搞埋一齊。
