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

設定頁最底本來有「試打」欄位以及實時預覽，
**目前已隱藏**（`SettingsActivity.SHOW_DEBUG_SECTIONS = false`，使用者不想見到）。
`buildTryBox()` / `buildPreview()` 一行都沒有刪 —— 想 debug 排位就改回 `true`，
無須開啟第三方 app。裏面每種特別欄位各有一欄（email／網址／密碼／PIN／電話／
數字／金額／日期／時間），加上六款 `imeOptions`，就是用來一次過看勻
「跟輸入欄類型換排位」與「`⏎` 跟 `imeOptions` 換樣」兩套邏輯。

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

**左欄四顆、右欄四顆由使用者自己排**（2026-09-09 起，見下面「按鍵排位」）。
`ChinesePadView.buildLayout()` 只砌中間三欄，兩側跟 `KeyLayout.load()` 出來的
排位走 —— 想知道預設是甚麼樣，看 `KeyLayout.DEFAULT`，那就是 2026-09-09
之前寫死的那個：左欄「關聯字／同音／`?123`／`Eng`」，
**右欄由上而下 `⇄`、`␣`、`⌫`、`⏎`**（2026-08-27 使用者要求，`␣` 與 `⌫`
對調了）—— 這樣中文都依照下面那條「`⏎` 上面那顆一定是 `⌫`」的規矩，
四款鍵盤一致。錄音已移至上工具列，在「貼上」旁邊。

**不要再在 `buildLayout()` 寫死兩側任何一顆**。要改預設就改 `KeyLayout.DEFAULT`，
要加一種可擺的功能就加落 `PadFunc`（順手在 `PadFuncKeys.kt` 補回它的
`KeyAction` 與圖案）—— 兩處都改完，設定頁那個拖放介面自動就有得揀。

### 底行的規矩（英文／符號／純數字）

- **左下兩個一定是「返回英文／中文」**（`Eng` 在最下，`中` 在它上面）—— 一個例外：
  中文九宮格自己就是中文，左下角只保留 `Eng`。
  **純數字頁 2026-09-09 由右上角搬回左下角**（使用者要求）：這樣四款鍵盤一致，
  而且 `Eng` 落在最左下，跟中文九宮格那顆站在同一個位。最左那欄的符號向上推
  （`calc` 的 `* /` 上到頂），讓走那對（`+ -`）搬去右上角 —— 每種
  `NumField` 都跟同一條規矩：**下面那對符號留在左邊往上推，上面那對搬去右上**。
  英文那顆**寫 `Eng` 不寫 `ABC`**（2026-08-25 使用者要求，全部頁一致）。
  中文那顆預設**長按 = 下一個輸入法**，但 2026-09-09 起這不再是 `Eng` 專屬：
  「下一個輸入法」（`PadFunc.IME_NEXT` → `KeyAction.IME_SWITCH`）與
  「彈出輸入法選擇表」（`PadFunc.IME_PICKER` → `KeyAction.IME_PICKER`）
  都是**可以自由擺位的按鍵**，兩顆的預設就是前者擺在左下角 `Eng` 的長按。
  舊那個 `Prefs.EngLongPress` 只剩下升級時讀一次（`KeyLayout.fromLegacyPrefs`），
  設定頁那個選單已隱藏（`SettingsActivity.SHOW_LEGACY_KEY_OPTIONS`）。
  **純數字頁那顆 `Eng` 跟回中文九宮格那顆**（`KeyLayout.longFor`）——
  排位改了兩頁一起變，不會一頁一個樣。那頁本來 `allowLongPress` 全部回 false
  （打號碼按久一點就彈 popup 很煩），`Eng` 是唯一例外。
- **`⏎` 上面那顆一定是 `⌫`**。所以符號頁的分頁按鍵（`€£¥`／`?123`）與 `⌫`
  都在倒數第二行的最左與最右，純數字頁的 `⌫` 也由右上角已移至 `⏎` 上面。
  第一頁那顆分頁按鍵**寫三個貨幣符號 `€£¥`**（第二頁頭一行就是貨幣符號），
  以前寫 `=\<`，沒有人知是甚麼。
- 騰出的位：符號第一頁底行 space 右邊依序排列 `, . ? ; /` 五個（本來散在
  上面兩行）；第二頁不要標點，space 與 `⏎` 拉長，`numpad` 按鍵再上移一行。
- **純數字頁最左有一欄 `+ - * /`**（2026-08-25 加）：`-` 由底行已移至上去，
  騰出那位置（`0` 右邊）放了顆 **`000`**（一次輸入三個 0）。
  即目前成頁是 5 欄，與中文九宮格一樣。
- **英文底行 space 左邊 `,`、右邊 `.`**（2026-09-11 改；期間曾經是 `.` 在左、
  `,` 在右）。`?` 與 `/` 都沒有獨立一個 —— 兩個都在長按 `.` 那條 list 內（見下面）。

### 英文鍵盤排位（2026-08-24 大修正過）

- **永遠有數字行**（`Prefs.FORCE_LATIN_NUM_ROW = true`）。設定頁那個開關已隱藏，
  但 `KEY_LATIN_NUM_ROW` 與「沒有數字行會在字母角落顯示小字」那段 code 都沒有刪。
- `asdfghjkl` **不再靠拉長 `a` / `l` 填滿邊緣**：九顆一樣寬，兩頭各讓半格空位
  （`spacerKey(0.5f)`）。空位**不會**入 `boxes`，所以按下去會由 `boxNear()`
  snap 至旁邊實際按鍵，不會變死位。
- `,` 由 `zxcvbnm` 行已移至底行（頂了本來個 `?`），騰出的位讓 `⇧` 與 `⌫` 拉長。
- **`/` 2026-09-11 整個取消**（一般輸入欄）：`zxcvbnm` 行回復七顆字母，`⇧` 與 `⌫`
  各自 1.5（`1.5 + 7 + 1.5 = 10`，剛好與上面兩行十顆字母對齊）。底行同時
  `中` 由 1.3 收到 1、`⏎` 減兩成（1.7 → 1.36），慳下的位全數給 space
  （3.4 → 4，佔整行由 35% 變 41%）—— 全部都是為了 space bar 更長。
  網址欄（`textUri`）與電郵欄那顆 `/` 照留，那兩處逐個字元都要按得到。
- **長按字母大小寫兩樣都可選**：`ch()` 會按目前個 `ShiftState` 建立 variants。
  **2026-09-11 起排頭那個是「另一個」大小寫**（使用者要求）—— 按鍵寫住細階就排頭
  出大階、寫住大階就排頭出細階（按住不移動放手 = 輸入它），按鍵自己那個排第二，
  之後才是重音字母。短按本來就取得到按鍵自己那個，長按那下想要的一定是另一個。
  popup 選回來那按鍵帶 `Key.literal = true`，`typeChar()` 見到就**不會**再套 shift
  （不是特意選個小寫 `a` 會讓 shift 強行恢復 `A`）。
- **長按數字排頭是符號**（`digitKey()`，2026-09-11 使用者要求）：`1` → `!` 排頭、
  `1` 自己排第二，之後才是 `¡` `¹` `½`。同樣道理 —— 數字短按已經打得到。
  `DIGIT_SYMBOLS` 本身的次序沒有改，右上角那個小字提示照舊是 `syms[0]`。
  `SymbolPadView` 那行數字同樣用 `digitKey()`，所以兩處一致。
- 標點（`,` `.`，網址／電郵欄再加 `/`）長按有 `PUNCT_VARIANTS`，左上角顯示小字提示。
  **全部都不跟「第一個 = 自己」規矩** —— 排頭那個是長按彈出時就已經停了
  在此那個（手指不移動放開就出它），按鍵自己短按取得：
  `,` → **Tab**（`\t`）、`.` → `/`、`/` → `?`。
  `.` 排頭是 `/` 不是 `;`（2026-09-11）：那顆 `/` 已經取消，整條 `SLASH_VARIANTS`
  併入 `.`（共 15 個），角落提示同時由 `;` 變 `/`，看一眼就知按鍵搬了去哪。
- **Tab 沒有字形**，畫出來一片空白，所以 `variantDisplay()`（`KeyDef.kt`）會替換為
  `⇥` —— popup 與角落提示都要執行這個 helper，但 `Key.variants` 內存的、
  以及最後 commit 出去那個一定要是真正的 `\t`。
- **變體多於螢幕容納就一起壓縮**（`openVariantPopup`：
  `if (popupItemW * cols > width) popupItemW = width / cols`）。
  寧可每顆細些都好過有多個推了出螢幕外面永遠無法選擇 —— `/` 有八個，
  使用 `max(鍵寬 × 1.1, 50dp)` 就一定超出。字太大 `KeyPopup` 自己會縮回。
- **多於 `MAX_POPUP_COLS`（10）個就拆上下兩行**（2026-09-11 加，因為長按 `.`
  變成 15 個，擠成一行每格只剩幾 mm）。`KeyPopup.showGrid` 收一個
  `List<List<String>>`（由上至下），`index` 是拉直之後那個位置。
  **排頭那批放在下面那行**：popup 彈在按鍵上方，手指本身就在最底那行的高度，
  常用那幾個不用抬手就拉得到。所以「手指不移動放開 = 第一個」那個預設
  `popupIndex` 是 `rows[0].size`（不是 0）。奇數個時短的那行置中，
  `updateVariantPopup` 要計回相同的 offset 才對得上高亮。
  兩行時 `slop` 連上下一起計（`hypot`），一行時仍然只計左右 —— 否則只抬高手指
  （左右沒動）永遠選不到上面那行。
- `?123` 長按 = 直接跳純數字頁（`longAction = TO_NUMBER`），中文九宮格那顆一樣。
  **英文那顆沒有左上角提示字**（2026-08-29 使用者要求）：鍵面本身已經四文字符，
  底行每顆都窄，再擠壓個 `123` 落左上角就擠在一起。中文九宮格那顆空間較寬鬆，
  個 `hint` 照留 —— 兩處不一致是特意的。
- **`?123` 右邊那顆只在寬 keyboard 出現**（2026-09-11 使用者要求）：它是上面那條
  bar 的**四段循環**（`KeyAction.BAR_HIDE` → `TTInputMethodService.cycleBarWithHide`）——
  關聯字 → 工具 → 兩行一齊 → **收起**，然後從頭再來。即是與切換掣（`⇄`）走同一個
  圈，只是多了「收起」那一段，而**收起只有這顆做得到**。

  個字面講的一律是「按完會怎樣」（`LatinPadView.barCycleGlyph()`）：
  `⇄` 轉去工具、`⇅` 兩行一齊、`▴` 收上去、`▾` 拉回來。每按一下都要
  `latinPad?.rebuild()`，否則字面停在上一段。

  「寬」＝ `Prefs.barToggleAllowed()`，與「左右拆開」揀不揀得到是同一條線
  （`SPLIT_MIN_WIDTH_DP`，500dp）。窄機**不會**出現這顆，條 bar 收不起 ——
  打字提示、滑出來那個字、關聯字全部在那條 bar 上，窄機收起了就等於打盲舖。

  **收起了是四款鍵盤一起收**（2026-09-11 起，以前只有英文頁理會）：
  `Prefs.barHidden()` 每個螢幕尺寸各自存（`Prefs.screenKey()`），所以打橫收起了
  轉回打直不會一起收埋。**打橫未按過就預設收起** —— 打橫本來就矮，首次開鍵盤
  連條 bar 遮住整個螢幕（使用者報）。叫得回來的入口有兩個：這顆鍵，以及**任何一顆
  切換掣**（`onSwitchView()` 見到收起了就先放回來再走第一段，否則中文頁那顆 `⇄`
  按極都沒有反應——中文頁沒有「收起」那顆鍵）。

  搜尋 emoji、滑完等揀字、emoji 表／剪貼簿三種情況一律越過它（見
  `refreshBars()` 中的 `mustShow`）—— 那幾下不見到條 bar 就選不到東西。
  符號頁在那裏收不起也開不回（沒有那兩顆鍵），要轉回英文／中文頁才叫得回來。

### 英文句首自動大階（2026-09-05 加）

英文鍵盤在句首會自動開 `ShiftState.ON`（`TTInputMethodService.updateAutoCaps`），
**不是** 看欄位有沒有寫 `textCapSentences` —— 一律自己判斷。判斷條件全部在
`core/AutoCaps.atSentenceStart()`（純 Kotlin，有 `AutoCapsTest`），只收游標前面
12 個字元：欄位開頭、換行、`! ?`（連全形 `！？。`）、`.` 之後。

- **`.` 一定要跟着至少一個空格才算句尾**，`! ?` 就不用。因為「句號 + 新句」與
  網址／小數／檔名／縮寫在打字的一刻分不開（`google.` 與 `Hello.` 前面那截
  一模一樣），不跟空格就會打出 `google.Com`。這與 `autoSpaceAfterPunct` 特意
  不理會 `.` 是同一個道理，AOSP 的 `TextUtils.getCapsMode` 亦是這樣。
  全形 `。！？` 不會在網址中出現，所以不用跟空格。
- 前面隔着收結引號／括號（`他說「好。」`）照樣算句首。

**一定要按得熄。** 使用者自己按過 ⇧（或長按 capslock）就 `shiftManual = true`，
之後 `onUpdateSelection` 再來都不會強行改回大階；一打到落字（打字、`␣`、
`⌫`、`⏎`）就當這個手動決定用完，下一句照舊自動大階。`ShiftState.LOCK`
永遠不會被這裏動到。

呼叫點：`onStartInputView`、`switchMode`、`typeChar`、`space`、`backspace`、
`enter`、`onPickCandidate`、`onUpdateSelection`。每次會問輸入框拿一次
`getTextBeforeCursor`（IPC），所以 `mode != LATIN`、正在打一個字的中間
（`latinComposing` 不是空）、capslock、`shiftManual` 都會先擋住不查。

URL／email／密碼／篩選欄（`textFilter`）不會自動大階（`autoCapsField`）——
在那些欄位打大階等於直接打錯東西。**沒有另開設定開關**：使用者要熄就按 ⇧。

### 跟輸入欄類型（`inputType`）換排位（2026-09-05 加）

`onStartInputView` 看 `inputType` 之後，英文鍵盤走 `LatinField`、純數字鍵盤走
`NumField`。原則：**該欄位收不到的字元不要留在鍵面上** —— 那些鍵會被輸入框
自己的 `KeyListener` 濾走，按極都沒有反應，比沒有還差；收得的分隔符就補回去。

| 欄位 | 頁 | 底行／最左一欄 |
| --- | --- | --- |
| 普通文字 | 中文九宮格 | （不變） |
| `textEmailAddress` | 英文 | `@`、`.`、`.com`（長按有 `.com.hk` 等；`/` 在長按 `.`） |
| `textUri` | 英文 | 收起 `,`，改為 `/` `.` `.com` |
| `textPassword`（連 `webPassword` / `visiblePassword`） | 英文 | 收起 `,` `/`，改為 `.` `-` `_` |
| `phone` | 純數字 | `( ) - +` 與 `*` `#`（撥號串常用） |
| `numberPassword` | 純數字 PIN（三欄） | 本來那顆用不着的 `-` 改為 `⏎` |
| `number` | 純數字 | `+ * /` 全部收起；`-` 只在 `numberSigned`、`.` 只在 `numberDecimal` 才出 |
| `date` / `time` / `datetime` | 純數字 | `date` 出 `. - /`、`time` 出 `:`、沒寫變體就四顆都出 |
| 由符號頁按 `123` 入來 | 純數字 | `+ - * /`（`NumField.CALC`，即以前那套） |

**四款英文排位都保留 `中`。** URL 欄（Chrome 的網址欄就是 `textUri`）與密碼欄
一樣可能要打中文，收起了就整個欄位都打不到中文，只能去換輸入法。

**純數字頁不夠鍵就留空位，不可以縮成四欄。** 用不着的位置放 `spacerKey(1f)`，
數字永遠坐同一格，中英切換時不會左右彈（見上面「寬度與貼邊不可以再自己計」）。
空位不會入 `boxes`，按下去會 `boxNear()` snap 去旁邊那顆，不是死位。

密碼欄還有兩件事**不是排位**：不准滑動輸入（`LatinPadView.canSwipe`）、
不出打字提示與下一個字預測（`latinTypingSuggestions` / `nextWordSuggestions`）
—— 正在打的密碼不應該在候選欄逐個字現形，而且滑出來的一定是詞庫中的字，
對密碼根本沒有用。

### `⏎` 跟 `imeOptions` 換樣（2026-09-05 起七款）

`TTInputMethodService.enterLabelFor()` 按 `IME_MASK_ACTION` 出不同符號，
全部**單色**（見 `KeyDef.kt` 的 `glyphOr()`，字型沒有該字就寫回中文字）：

| `imeOptions` | 鍵面 | 沒有字型時 |
| --- | --- | --- |
| `actionDone` | `✓` | 完成 |
| `actionSearch` | `⌕` | 搜尋 |
| `actionSend` | `➤` | 傳送 |
| `actionGo` | `→` | 前往 |
| `actionNext` | `⇥` | 下一 |
| `actionPrevious` | `⇤` | 上一 |
| `actionUnspecified` / `actionNone` | `⏎` | — |

`IME_FLAG_NO_ENTER_ACTION` 一律出 `⏎`。**有明確動作（傳送／搜尋／完成）就執行動作**，
不要因為欄位順便標了 `TYPE_TEXT_FLAG_MULTI_LINE`（文字可折行）就改成隔行
（2026-09-16：先前一律換行，連真正要送出的欄都壞了）。真的要 Enter 隔行的聊天欄
應設 `IME_FLAG_NO_ENTER_ACTION`。換行用 `commitText("\n")`，不要送
`KEYCODE_ENTER`（有些 app 會攔截成送出）。`TYPE_NULL` 才送原生 Enter。條件全部
在 `EnterKey.behavior()`（有 `EnterKeyTest`），**`enterLabelFor()` 與 `enter()`
都要問它**，不然會出現「鍵面寫住 ➤、按下去卻換行」。

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

**剷字（⌫）也是同一條規矩**（2026-09-11 使用者報「`👾` 要按兩次 backspace」）。
`InputConnection.deleteSurroundingText` 收的單位是 **UTF-16 char**，收那邊
（`BaseInputConnection`，即一般 `EditText`）**不會**自己補回個 surrogate pair，
所以寫死 `1` 就會每次只剷半隻字：

| 打的內容 | 佔幾多 char | 寫死 `1` 要按幾多次 ⌫ |
| --- | --- | --- |
| `字` / `a` | 1 | 1 |
| `👾`（`assets/emoji.txt` 1416 個中有 1248 個是這樣） | 2 | 2 |
| `🇭🇰`（兩個 regional indicator） | 4 | 4 |
| `👨‍👩‍👧`（ZWJ 串起一家人） | 8 | 8 |

`TTInputMethodService.deleteOneElement()` 先取回游標前面那段字，行
`TextEdit.lastClusterLength()`（`BreakIterator`，有 `TextEditTest`）計出實際
要剷幾多個 char。加新的剷字路徑一定要行它，不可以再寫 `deleteSurroundingText(1, 0)`
—— 只有「剷一個英文字母」（`latinComposing`）那條路確定是 1 才可以寫死。

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
        EnterKey   `⏎` 換行 vs 執行欄位動作 vs 原生 Enter（有 `EnterKeyTest`）
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
        GeminiLive Gemini Live WebSocket 即時轉錄（`gemini-3.8-live-extended-thinking`）；
                   setup 一定要帶 thinkingLevel（預設 LOW）；設定頁開 Live 就頂走上傳那條路
        UsageStats 另一個 sqlite（usage_stats.db，與 dataset.db 分開）：
                   連續兩個中文字的 bigram 次數、每個字輸入了多少次
        Prefs      全部設定
swipe/  GestureKeyTracker   中文九宮格滑動中間鍵判定（純 Kotlin，有 unit test）
        GestureDecoder      英文 swipe 認字：AOSP 手勢輸入那套概念的 Kotlin 版
                   （軌跡 vs 關聯字理想路徑做形狀比對，不是逐格判斷按了哪個鍵）
        GesturePivots       由軌跡＋時間抽出「明確按過哪幾個字母」（停留／拗彎），
                   **不查詞庫** —— 滑一個詞庫沒有的字（人名、代號）時，
                   靠它砌回那串字母去跟詞庫那些鬥（見下面「詞庫沒有那個字」）
ime/    TTInputMethodService   IME 主體，所有 view 的 host
        KeyboardBaseView        排版／畫鍵／接觸觸／畫線／長按 popup／長按 ␣ 移動 caret
        KeyPopup                浮在鍵盤外面那些窗（長按變體行、滑動 hover 提示）
        ChinesePadView          九宮格（KeyboardBaseView）
        RowsPadView             一行行按 weight 分寬度的底
        LatinPadView / SymbolPadView / NumberPadView（RowsPadView）
        EmojiPadView            emoji grid（ViewGroup，不是 KeyboardBaseView）
        QuickEmoji              長按「表情」彈出的速選那行（見下面那節）：
                                條件 `appliesTo()` 加 工具列那顆的 `QuickEmojiPopup`
        ClipboardListView       長按「貼上」之後蓋在 padHolder 上面的 overlay
        PadMetrics              尺寸與顯示方式計算
        OptionBarsView          上面工具列（三段：關／關聯字／工具）
        FloatHandleView         浮動窗底條 handle（輸入法選單／調整大小／拖／收起）
        FloatResizeOverlay      浮動調整大小遮罩（蓋住鍵盤；X 橫排、Y 直排）
        FloatGeom               浮動位置分數 ↔ pixel（純函數，有 unit test）
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

### 上面工具列：每段一行，`BOTH` 就兩行

`OptionBarsView` 是**兩個 view 疊起**：`toolLine`（工具）在上、`candLine`（關聯字）
在下，`BarMode` 四段就是這兩個 `visibility` 的組合（`CANDIDATES` / `TOOLS` /
`BOTH` 兩行一起 / `OFF` 整條 `GONE`，見 `setMode()`）。`BOTH` 是 2026-09-11
使用者要求加的，那陣條 bar **真的高一倍**，不是把兩樣塞進同一行。

**上下次序是 2026-09-13 調轉的**（本來關聯字在上）：窄螢幕那個側邊欄
（`SidePanelView`）一直都是工具在上、關聯字在下，兩個排法不一樣，橫直換來換去
就要重新找過哪顆掣在哪。現在兩邊一致 —— 關聯字永遠是最貼近鍵盤那行
（選字時手指走得最短），工具釘在最上。改其中一邊就要兩邊一起改。

那顆 `⇄`（與 `✖`）**跟著工具那行走**：有工具那行就在工具那行，只得關聯字
那段才搬去關聯字那行（`refreshLeftBtn()` 靠 `mode.hasTools` 分）。兩行一起那陣
關聯字那行就沒有那顆掣，整行讓給那些字。兩行的高度、`setContentInsets()` 的
padding **一定要一起改**（`lines`），否則兩行一起出的時候上下不對稱。

**符號／純數字頁不出關聯字那行**（2026-09-13 使用者要求）：那兩頁根本沒有字
可以提示，設定成怎樣都好，出來都是一行空位 —— `refreshBars()` 直接把
`effective` 定成 `BarMode.TOOLS`，連那顆 `⇄` 都不出（按了也不會見到有東西變，
`setSwitchVisible(false)`）。條 bar 本身一定要留：那兩頁沒有 `⇄` 那顆鍵，
整條收起了就沒有入口開回來。

每段本身仍然只有**一行**。以前有條「狀態」小字
（字碼、`[同音]`、頁數）放在最上面，一出現就整個鍵盤高了一截，已經**移除**——
`TTEngine.status` 仍在計算，但沒有人畫。要出 message 就用 `toast()`，
不要再在工具列 上面加行。

高度**不再是固定為 42dp**（2026-08-29 使用者要求）：根據 `CandChip` 中關聯字實際
要幾高，再加上下 3dp margin，最矮 42dp。100% 之下實測 47dp。
**兩行共用同一高度**，在 `CANDIDATES` 與 `TOOLS` 之間轉一樣不會跳
（`BOTH` 當然會高一倍，那是使用者自己按出來的）。

**但再粗都不可以粗過而家呢組一行鍵的 80%**（2026-09-17：打橫時 42dp 的 bar
會高過下面的鍵）。中英各用各的 `PadMetrics` 行高（英文 5 行、中文 4 行）。
`CandChip.barHeightPx` 封頂在 `rowH × BAR_TO_KEY_RATIO(0.8)`。浮動底列 handle
跟功能表一行的實際高度。轉鍵盤（中↔英）要 **立刻** 清 cache 重算，不要承繼
上一組的高度等關聯字更新才跳。

封了頂那陣**關聯字要跟着縮細**（`OptionBarsView.applyBarSize()`，最細 60%）。
縮完仍高過條 bar 就**加高條 bar**（下限 = chip + 上下 1dp margin），不要裁走
字的下半。chip 上下 padding 是 2dp（另加墨水偏移），Y 方向 margin 1dp。

一組之內 `rowCount` 是固定的（中文與純數字都是 4 行、英文與符號都是 5 行），
所以轉頁一樣不會跳高跳低。加新版面時要留意這點。

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

### 拉大了的關聯字：蓋住**整個鍵盤**，連條 bar 都食埋

2026-09-13 使用者要求。`onExpandChanged(true)` 把 `bars.expandedView` 加入
**`outer`**（不是 `padHolder`），高度 = `padHeightPx` + `bars.height`
（`expandedLayoutParams()`），所以由最頂起計，工具那行也讓給那些字。
仍然**不可以用 `MATCH_PARENT`**：`outer` 是 `wrap_content`，會撐大整個 IME window。

連帶兩件事：

- 條 bar 那顆 `▼` 被自己遮住了，所以收合那顆 `▲`（`OptionBarsView.collapseBtn`）
  搬進了 `expandedView` 自己裡面 —— 它是 `FrameLayout`（`expandedBox`）：
  `ScrollView` 鋪滿，`▲` **浮在上面右上角不跟著捲**，捲到哪裡都收得回。
- 那顆 `▲` 會壓著第一行最右那隻字，所以 `CandFlowView.firstRowInsetRight`
  讓**第一行**右邊留回 38dp + 3dp（其餘行照用盡）。側邊欄沒有那顆掣，一直是 0。

`relayoutPads()` 尾那句 `refreshExpandedLayout()` 負責改了顯示方式／拉過寬窄
之後重新擺位（同 `refreshPanelLayout()` 一樣的道理，只是 parent 是 `outer`）。

### 中文拉窄就不要上面工具列，改用側邊欄

`PadAlign.LEFT_GAP` / `RIGHT_GAP` 之下，中文本體寬過螢幕的
`Prefs.SIDE_PANEL_MAX_RATIO`（六成）就仍然用上方的 `OptionBarsView`；
**窄過六成**就 `bars.visibility = GONE`，成工具列 的內容搬去 `SidePanelView`
（加入 `padHolder` 度，`FrameLayout.LayoutParams` 寬度 = 空出來那邊，
gravity 跟 `PadAlign` 反過來放置）：上面一（兩）行功能按鍵，下面成塊可 scroll 的關聯字。

入口是 `refreshBars()` 開頭那句 `if (refreshSidePanel(cands)) { … return }`。

**`PadAlign.CENTER`（置中）沒有側邊欄**（`sideGeom()` 與 `STRETCH` 一起回 `null`）：
空出來那些位置一開二，兩邊各一半，哪邊都窄過放得下工具按鍵，所以照用回上面工具列。

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

### 鍵盤永遠貼近底（浮動除外）

貼底嗰幾個顯示方式，`PadMetrics` 沒有 `extraBottom`。目前 `PadAlign` 有
`STRETCH`／`LEFT_GAP`／`RIGHT_GAP`／`CENTER`／`SPLIT`／**`FLOATING`**。
`OptionBarsView` 個 sizeBtn 按一下就轉下一個（可選哪幾個見 `Prefs.alignOptions`）。

**`PadAlign.FLOATING` 只在闊 screen（`> SPLIT_MIN_WIDTH_DP`）出現**，中英兩組都有。
窄了（轉直）就不在 `alignOptions` 內，`Prefs.align()` 當「拉闊」，自動停用。
IME window 鋪滿螢幕但背景透明，`onComputeInsets` 把 content insets 拉到窗底
（下面的 app 不被夾高），只張卡那個矩形吃觸摸。拖動範圍用螢幕像素，**不要**用
當時 IME window 幾高 —— wrap_content 時 Y 會夾死在 0，位置亦會忽高忽低。

最底一條 handle：**左**彈系統輸入法選單、**左中**調整大小、**中**拖去移動（X／Y
都得）、**右**收起鍵盤。工具列那顆顯示方式**照轉下一個**（浮動時仍可轉返置左／
右／置中）；入浮動時中英兩組一齊 `FLOATING`，闊／高各用各的
`floatWidthScale`／`floatHeightScale`（英文可以較闊、中文較窄）。

調整大小是蓋住張卡的 overlay（確定／取消疊在鍵盤底，**不要**加高張卡，否則
預覽不到拉高之後實際幾高）。X 橫排（－ X ＋）、Y 直排（＋ 在上、－ 在下）。

拖動**兩個方向都都有對應功能**
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
英文組的實際倍數 = pref × `Prefs.LATIN_FONT_BOOST`（**1.3**，2026-09-11 由 1.2 加大：
slider 停在 100% 時就是舊版 130% 那個大小；上面那條候選字 bar 讀 `candTextSp`，
**不乘**這個 boost，所以字大小完全沒變），而且條 slider
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

- `LATIN` + 螢幕寬過 `Prefs.SPLIT_MIN_WIDTH_DP`（500dp）→ `STRETCH`、`SPLIT`、`CENTER`、`FLOATING`
  （靠左／靠右時收合 —— 這麼寬的螢幕靠近一邊，另一邊那部分空間就是浪費了）
- 其餘闊螢幕（中文那組）→ `STRETCH`、`LEFT_GAP`、`RIGHT_GAP`、`CENTER`、`FLOATING`
- 窄螢幕 → `STRETCH`、`LEFT_GAP`、`RIGHT_GAP`、`CENTER`，沒有 `SPLIT`／`FLOATING`

`CENTER`（置中，2026-09-11 使用者要求）**兩邊都可以選**：它不佔一邊，純粹是
「拉窄之後站中間」，寬螢幕與窄螢幕一樣用得著。

三樣內容然後這資料表行，加新 mode 記得三樣一起改：

1. **`Prefs.align()` 會過濾**：儲存的值不在 `alignOptions` 內就當 `STRETCH`
   （摺機開合／橫向之後可選擇的內容會變，舊 profile 不可以強行用寫入）。
2. **`Prefs.nextAlign()`** 才是「按一下轉下一個」，`PadAlign.next()` 已刪除 ——
   自己 `ordinal + 1` 就會轉到不得選那個。
3. `OptionBarsView` / `SidePanelView` 兩個 `refreshAlignLabel()` 個 `when` 都要寫齊
   （側邊欄是中文專用，不會進入 `SPLIT`／`FLOATING`，但一樣要有那個 branch）。
   `FLOATING` 的圖案是 `ALIGN_FLOAT`（一條底線 + 浮起的方塊），按一下仍轉下一個。

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

`ClipboardListView` 那類 overlay 沒有自己的「關閉」按鍵 —— 顆 `✖` 在
`OptionBarsView` 最左。所以 `refreshBars()` 見到 `specialPad`
（`mode == EMOJI || overlay != null`）就一定要 **force `BarMode.TOOLS` + 不得 GONE**，
不是 使用者關閉工具列 之後開剪貼簿就無法返回去普通鍵盤。

`EmojiPadView` 是例外（2026-09-13 使用者要求）：它自己個 header 最左有顆 `✖`
（擺在搵字掣 `⌕` 左邊，`EmojiHost.onEmojiClose()` → `closeEmoji()`），
所以 `bars.setCloseVisible()` 收到的是 `overlay != null`，**不是** `specialPad`。
emoji 那陣條 bar 仍然 force `TOOLS` + 不得 GONE，但理由只剩「那行工具掣」，
不再是「沒有它就返不去」。順帶：emoji 那陣 `⇄` 也不出（`fixedTools` 包了
`PadMode.EMOJI`）—— 條 bar 夾硬是 `TOOLS`，那顆掣按極都沒有反應。

`showOverlay()` / `hideOverlay()` 兩邊都會叫 `refreshBars()`。

### 功能表（emoji 表／剪貼簿／AI prompt 名單／拉大了的候選字）跟顯示方式放位

2026-09-11 使用者要求：鍵盤本體靠左／靠右／置中，**攤開在鍵盤位置那些表也要跟著放**。
不是就會「鍵盤縮窄靠著一邊單手打字，長按『貼上』彈出來那張表卻鋪滿成行」，
手指夠不到另一邊。

`TTInputMethodService.panelLayoutParams(height)` 一個地方計完：寬度 =
`PadMetrics.contentW`，`gravity` 跟 `PadAlign`（`RIGHT_GAP` → `START`、
`LEFT_GAP` → `END`、`CENTER` → `CENTER_HORIZONTAL`）。`STRETCH` 與 `SPLIT`
本來就用盡成行，回 `MATCH_PARENT`。四個入口：`showOverlay()`（剪貼簿、
AI prompt 名單）、`switchMode()` 加 view 那句（emoji 表）、`expandedLayoutParams()`
（拉大了的候選字 —— 它加在 `outer`，高度另計，見上面那節），以及
`relayoutPads()` 尾那幾句 `refreshPanelLayout()`。

兩個陷阱：

- **鍵盤本體不可以走這條路**（`switchMode()` 那句 `if (v is KeyboardBaseView)`）：
  它自己在 `PadMetrics.offsetX` 排位，而且空出來那邊要留給側邊欄（`SidePanelView`
  是 `padHolder` 的另一個 child），本體一縮窄側邊欄就沒有位置放。
- **改了顯示方式／拉過寬窄，要重新 set `layoutParams`**，`requestLayout()` 沒有用 ——
  寬度記在 `LayoutParams` 裡面，不重新 set 就會維持上一次那個寬度。
  `relayoutPads()` 因此同時 `refreshPanelLayout(overlay)` / `emojiPad`，
  拉大了的候選字就行 `refreshExpandedLayout()`（只在 `candidatesExpanded` 時）。

### 上面那條 bar 裡面的內容也跟著縮進來

同一個要求（2026-09-11）：鍵盤站一邊，上面那條 bar 的**按鍵**不可以仍然鋪滿成行。
`TTInputMethodService.padInsets()` 算出左右各留多少白（`STRETCH` / `SPLIT` 回
`0 to 0`），`refreshBars()` 交給 `OptionBarsView.setContentInsets()`，它**只加
`barRow` 的 padding**：工具按鍵、`⇄`／`✖`、候選字與顆 `▼` 一起縮進來站在鍵盤上面，
而條 bar 自己的底色照舊鋪滿成行，看起來仍然是一條完整的 bar，高度也不受影響。

側邊欄（`SidePanelView`）不用理它 —— 它本來就住在空出來那邊。

### 工具列常駐（`Prefs.barPinned`，2026-08-27 加，2026-09-09 起**一律開**）

2026-09-09 起 `Prefs.FORCE_BAR_PINNED = true`，設定頁那個開關已隱藏
（`SettingsActivity.SHOW_LEGACY_KEY_OPTIONS`）—— **pref 與整條「關閉」那路
一行都沒有刪**，把那個 const 改回 false 就全部回來。

常駐即是「開／關整條工具列」沒有事可做，所以那顆按鍵一律是
`PadFunc.BAR_SWITCH`（`⇄`＝關聯字 ⇄ 工具）。它是**必用鍵**
（`PadFunc.required`），拖放介面不准把它拖走，否則就永遠進不了工具列。

| | 按鍵 | 工具列 最左 |
| --- | --- | --- |
| 關閉（已隱藏） | `☰`＝開／關成工具列 | `⇄`＝三段循環 |
| 常駐（現在一律） | `⇄`＝三段循環 | **沒有**（`setSwitchVisible(false)`） |

切換掣走的圈是 `BarMode.nextVisible()`：關聯字 → 工具 → 兩行一齊 → 關聯字，
**永遠不會走到 `OFF`** —— 收起只有寬螢幕英文底行那顆做得到（見上面）。

三處要一起夾：

- `TTInputMethodService.toggleBar()` 第一句就分流去 `onSwitchView()`。
- `refreshBars()` 開頭見到 `pinned && barMode == OFF` 就當場升做 `CANDIDATES`
  **而且寫回落 pref**（設定頁㩒按鍵不會 restart 個 service）。
- 那顆鍵面來自 `PadFunc.BAR_SWITCH.face`；`optionOn`（是否亮起）常駐時代表
  「目前在工具那邊」，不是「工具列 保持開啟」—— 持續著住藍燈沒有資訊可言。

### 寬螢幕未調過高度：鍵盤最多佔螢幕一半

2026-09-11 使用者報「首次打橫時經常高到遮住整個screen」。`PadMetrics.autoCapped()`
因此在寬螢幕（`> SPLIT_MIN_WIDTH_DP`）而使用者**未自己調過高度**
（`Prefs.heightScaleSet()`）時，把每行封頂在 `螢幕高 × AUTO_HEIGHT_RATIO(0.5) ÷ rows`。

為甚麼不直接改細預設倍數：倍數是乘在 `unit` 上，而 `unit` 跟「最大寬度／最大高度」
兩條 slider 走，與螢幕幾高完全無關 —— 打橫那陣 300dp 的 `maxH` 夾 4 行，
出來就已經超過半個螢幕。要**量回真螢幕幾高**才封得到頂。

一調過（設定頁條 slider、或工具列那顆掣上下拖）就完全聽使用者那個數，不再封頂。
所以 `onSizeDrag()` 的起點是 `PadMetrics(...).heightScale`（**實際用了那個**），
不是 `Prefs.heightScale()` —— 由封了頂的 0.8 拖，不會第一下就彈回 100%。

### 工具列那行掣的闊度：`ToolStrip`

**不用 `weight` 平分**（2026-09-09 使用者要求）：兩三顆的時候平分會闊到像個
banner、按邊都按到嘢；八顆的時候窄機平分下來每顆三十幾 dp，細過隻手指。
所以每顆鎖死在 `minW`（44dp）～`maxW` 之間，擺不下就交給外面那個
`HorizontalScrollView` 打橫捲。

`maxW` **不是寫死的數**（2026-09-11 使用者要求）：由 `OptionBarsView.refreshToolWidth()`
跟住**中文九宮格一顆鍵的闊度**放下來，一至五顆的時候條 bar 那幾顆掣就與下面那些鍵
一樣闊、對得正。那個數 = `PadMetrics(…, group = CJK).cellW` 減兩浸 `gap()` ——
`cellW` 是**連埋兩邊那浸罅**的格子闊度（顆鍵畫的時候自己縮了 `gapPx`），
而 `maxW` 不計 margin。鍵的闊度會跟住拉大細（`Prefs.widthScale` 那些）走，
所以每次 `refreshTools()`（即每按一顆鍵）都量多次；`ToolStrip.maxW` 的 setter
見到沒有變就不 `requestLayout`。

⚠️ **加了那個 scroll view 就一定要處理「改變大小」那顆**：它左右拖 = 拉闊拉窄
鍵盤，正正是 `HorizontalScrollView` 想搶的方向。`handleSizeDrag` 的 `ACTION_DOWN`
一定要叫 `requestDisallowInterceptTouchEvent(true)`，否則闊度怎樣拖都不動，
只會捲那條 bar（2026-09-09 使用者報）。**拉大小行先，捲讓路。**

做法是**借 `fillViewport` 度兩次**，`ToolStrip.onMeasure` 分兩種 spec：

| spec | 回甚麼 | 為甚麼 |
| --- | --- | --- |
| `UNSPECIFIED`（第一次） | 每顆 `minW`（**最窄**那個樣） | 「縮到最細都擺不下」才要捲 |
| `EXACTLY`（第二次，只在上面那次比 viewport 窄時才有） | `clamp(avail / n, min, max)` | 有位就攤開，但封頂 |

**次序不可以調轉**（`UNSPECIFIED` 回 `maxW`）—— 那樣八顆會用最闊的樣去捲，
明明縮細一點就擺得下。外面那個 `HorizontalScrollView` 一定要開
`isFillViewport = true`，否則第二次量度根本不會發生，那行掣永遠是最窄那個樣。

**`setSwitchVisible` 只在中文九宮格中隱藏顆 `⇄`**：英文／符號頁根本沒有右上角
那按鍵，已隱藏就永遠無法進入工具列（符號／純數字／emoji 三頁夾硬是 `TOOLS`，
`⇄` 也不出）。`✖`（剪貼簿那類 overlay）永遠優先，
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
兩個名是對調的，改時看清楚。`CENTER`（置中）與 `SPLIT`（左右拆開）兩個都是
**兩邊牆**加實心方塊：置中一塊站中間（`ALIGN_CENTER`），拆開兩塊各貼一邊
（`ALIGN_SPLIT`），所以一眼分得開。

`✖`（關閉）、`⇄`（切換）、`▼`（拉大候選）三個**沒有換** —— 它哋本身就是單色
文字符號，不是彩色 emoji。`PadFunc.EMOJI` 按鍵面也由 `😀` 已改寫「表情」。
`PadFunc.face` 全部寫中文，**只有三個例外是空的**：換輸入法那兩顆與
「改變大小」—— 它們沒有字可寫，改為畫單色圖案（見上面「左上角 = 長按」）。

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

## 按鍵按下效果

設定頁「一般 → 其他 → 按鍵按下時的效果」有四種：無效果、變光、變暗、
略為放大（`KeyPressEffect`）。**預設是變光**，並且直接使用既有的
`Theme.keyFaceDown`，所以舊裝置升級後的外觀不變。

這項套用於所有 `KeyboardBaseView`（中文、英文、符號、純數字）。「略為放大」
是將底色、字與角落提示整顆一起放大，並在相鄰按鍵後繪製，否則擴大的部分會被蓋住。

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

## AI 設定頁分兩大類

「AI」分頁分為 **語音輸入 (STT)** / **AI 改寫** 兩段，每段均可收合
（`SettingsActivity.collapsible()`），各自有個總開關，**也各自有一組 provider 設定**
（2026-09-14 改）：`buildAiProviderBlock(slot)` 是可重用的一整組
「profile＋API key＋模型＋自訂 API」，`AiSlot.REWRITE` / `AiSlot.STT` 各放一個。

- `AiSlot.REWRITE` 沿用舊 key（`ai_api_key`…），`AiSlot.STT` 用 `slot.key()` 加 `stt_` 前綴。
  讀一律用 `Prefs.aiProvider(ctx, slot)`（已填好預設值的 `AiProvider`）。
- 語音輸入有個 **「和 AI 改寫共用 Profile」**（`KEY_AI_STT_SHARE`，預設開）：
  開著就用 REWRITE 那組、設定頁不顯示 STT 那組。**永遠經 `Prefs.aiSttSlot()` 決定用哪組**，
  不要直接寫死 `AiSlot.STT`。
- **Profile 只存 provider 那幾欄**（key／model／useCustom／url／headers／body／
  multipart／responsePath），名單兩組共用，載入時只寫入按鍵所在那組。舊版 profile
  存有 prompt／STT 開關，現在載入時不理。

已隱藏未是 activity 的 instance state（`aiOpenStt` / `aiOpenRewrite`），
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

### Prompt 是一張**有名的清單**，不是一段字

`Prefs.KEY_AI_PROMPTS` 存一個 JSON array（`[{"name":…,"text":…}]`，
讀回來就是 `List<AiPrompt>`）。**次序有意思**：

- **短按** `✨` → 用第一個（`Prefs.aiPrompt()` 就是 `aiPrompts().first().text`）
- **長按** `✨` → `openAiPrompts()` 攤開 `AiPromptListView`（與長按「貼上」開剪貼簿
  歷史同一招：`showOverlay()` 在 `padHolder` 加塊 view），選了哪個就
  `runAi(它的 text)`。**那塊 overlay 不搶 focus 也不碰 selection**，所以選完
  使用者本來選取那段字仍在。

內置三個是 `Prefs.defaultAiPrompts()`：`英譯`（＝以前那個 `DEFAULT_AI_PROMPT`）、
`回答`、`修飾`。**名稱必須唯一** —— 長按那張清單只靠名字認人，所以
`parseAiPrompts()` 讀的時候撞名只留頭一個，設定頁儲存時也擋住。
`aiPrompts()` **保證不會回空 list**（壞 JSON／空 array 一律跌回預設那批），
所以 `first()` 安全。

⚠️ **`KEY_AI_PROMPT`（單數，舊 key）不要拿來讀**：它只剩下兩個用途 ——
未寫過 `KEY_AI_PROMPTS` 的裝置從它身上砌回清單（升級上來自訂過的 prompt
會變成清單第一個，不會不見了），以及 profile 仍然存一份給舊版讀。

## AI 語音輸入：頂走系統那個 `SpeechRecognizer`

`Prefs.aiSttOn` 開啟且已設定 key 時，顆 🎤 不再行 `toggleStt()` 那條系統路 ——
第一句就分流去 `startAiStt()` / `stopAiStt()`，`listening` / `recognizer`
那套內容一個都不會 set。（`Prefs.aiSttSysSec` 開著時 AI 那條路仍然會自己另外開
一個 `SpeechRecognizer`，但用的是 `sysStt` 那組獨立欄位，見下面一節。）

### Gemini Live 即時辨識（`Prefs.KEY_AI_STT_LIVE`，預設關）

而家嗰套係整段 PCM 壓完先 `generateContent` 上傳，**套唔到** Live API
（`stt_demo.ts` 嗰條 `ai.live.connect` WebSocket）。設定頁「使用 Gemini Live
即時辨識」開了就**完全頂走**呢條上傳路，亦唔會開系統 STT 陪跑：

- WebSocket `BidiGenerateContent`（OkHttp），16 kHz PCM 邊錄邊送
- 預設模型 `gemini-3.8-live-extended-thinking`（同改寫嗰個 flash **分開**，flash 唔係 live 模型）
- **跟 `stt_demo.ts`**：`responseModalities` 一定係 **AUDIO**（native audio 模型唔收 TEXT；
  TEXT + 轉錄會變成 `AUDIO, TEXT` 被拒）。`thinkingConfig`／`speechConfig`（Zephyr）／
  `mediaResolution` 一樣放 `generationConfig`。字靠 `inputAudioTranscription`，唔係 TEXT
- **`thinkingConfig.thinkingLevel` 一定要帶**（唔帶 setup 就報錯）。預設 `LOW`，
  設定頁「思考層級」揀 LOW／MEDIUM／HIGH（此模型唔支援 MINIMAL）
- `inputAudioTranscription` + SMART；push-to-talk（`automaticActivityDetection.disabled`，
  放手先 `activityEnd`）—— 咪掣幾時停由使用者話事
- 語言：`%lang%` 填咗 BCP-47（`yue-Hant-HK` / `en-US`）就直接用，否則中文鍵盤粵語、其他英語
- **只用 Gemini**。自訂 API／Whisper／prompt／短錄音改用系統辨識全部唔適用
- `VoiceRecorder` 仍然錄埋成段做 VAD（太短／冇人聲照取消，唔等 server）
- 計時 overlay **跟錄音走**：WebSocket 途中報錯只寫喺遮罩上，**唔好收 overlay**
  （收咗使用者會以為錄了半秒就完，咪其實仲開住）。`onDone` 淨係放手／再撳停之後先至叫
- 協議砌／拆喺 `GeminiLive`（有 `GeminiLiveTest`），session 喺 `GeminiLiveSession`

- **Gemini 或自訂 API 都得**（Live 關咗嗰陣）：Gemini 用 `inline_data` 送 ADTS AAC；自訂 API 靠範本中的
  **`%audio%`** 放錄音 —— multipart 模式（`AiProvider.multipart`）值剛好是 `%audio%`
  那行變成檔案 `audio.m4a`，JSON 模式 `%audio%` = base64、`%audio_mime%` = MIME。
  STT 那組的預設範本是 OpenAI Whisper（`Prefs.DEFAULT_AI_STT_BODY`）。
  用緊的那組是自訂 API 但 body 沒有 `%audio%`（例如共用了改寫那組 chat 範本），
  `Prefs.aiSttOn()` 就**一律回 false**，跌回系統 STT（`Prefs.aiAudioCapable`）。
  開了 Live 就唔再問 `%audio%`（PCM 經 WebSocket 送）。
- 自訂 API 範本的 placeholder 用 `AiTemplate.fill()` **一次過掃一轉**，換入去的
  prompt 內容不會再被換第二次；multipart body 要**先拆行再換**（prompt 本身可以有換行）。
  兩個都有 `AiTemplateTest`。
- **`%lang%`**：STT prompt 中換成目標語言。中文九宮格（`PadMode.CHINESE`）按語音用
  `Prefs.aiSttLang(chinese = true)`（預設「廣東話(有機會中英夾雜)」），其他鍵盤一律
  預設 `English`，兩個字串都可以在設定頁改。
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
  **其間電話不會休眠**：overlay／IME window `keepScreenOn` + `PARTIAL_WAKE_LOCK`
  （`setKeepAwake`；`WAKE_LOCK` 係 normal permission）。收 overlay／停系統 STT／
  `onDestroy` 一定要放。
- **四個階段四種不同的提示音**（`SttTone`）：開始錄 `TONE_PROP_BEEP`、錄音結束
  `TONE_PROP_BEEP2`、成功 `TONE_PROP_ACK`、失敗 `TONE_PROP_NACK`。
  這些與 `Prefs.sound`（按鍵聲）**沒有關係**，不跟那個開關 —— 音量另有
  `Prefs.toneLevel`（0～4，設定頁「其他 → 提示音音量」，`playTone` 一處管晒，
  連 `playErrorTone` 都跟）。**level 3 = 80，是以前寫死那個數，不可以郁**，
  否則現有使用者一升級就覺得提示音無端端變了。0 級**不會開 `ToneGenerator`**
  （播一段「音量 0」的聲一樣會搶了人家部機的 audio focus）。
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
  自訂 API 則用 `SttAudio.encodeM4a()`：同一批 AAC frame（`aacFrames()`）經
  `MediaMuxer` 包成 m4a（Whisper 不收裸 ADTS）。`MediaMuxer` 只寫得落檔案，所以借
  `cacheDir` 寫臨時檔再讀回；m4a 需要的 csd-0 來自 `INFO_OUTPUT_FORMAT_CHANGED` 那個
  `outputFormat`，同樣不可以把 `CODEC_CONFIG` 那段當 frame 寫。
- prompt（`Prefs.DEFAULT_AI_STT_PROMPT`）逐條明確列出禁止模型進行的操作 —— Gemini 很容易
  加句「以下是錄音的轉錄內容：」，也容易擅自潤飾句子。改 prompt 時
  不要已刪除「只輸出結果」與「逐字轉錄不要潤飾」這兩條。

## 短錄音改用系統 STT（`Prefs.aiSttSysSec`，2026-09-09 加）

AI 語音輸入開著時，講一兩句都要等 upload + Gemini 回覆，而系統內置那個
`SpeechRecognizer` 一秒就出到。所以**兩邊一齊開**：`startAiStt()` 開
`VoiceRecorder` 的同時叫 `startSysStt()`，未夠 `Prefs.aiSttSysSec` 秒
（預設 8，設定頁那條 slider 拉到 0 就是關掉，行回以前一律用 AI 的做法）
就攞系統那句，過了界就 `releaseSysStt()` cancel 掉系統那邊、段錄音照送上 Gemini。

- **`SpeechRecognizer` cancel 得**：`cancel()` + `destroy()`，之後不會再派
  callback 過來。`sysStt == null` 就是「這招今次收了檔」的旗號，`stopAiStt()`
  靠它決定行邊條路。過界那下由 `sttTimerTick`（每 100ms）叫。
- **`releaseSysStt()` 一定要 `ui.post` 出去**：好多時是在它自己個 listener
  callback 入面叫的（`onResults` → `finishSysStt`），即場 `destroy()` 有些
  實作會炸。與舊有那個 `releaseRecognizer()` 同一個寫法。
- **兩個 client 同時開咪是部機話事的**：Android 10 之後那套 audio policy
  隨時靜了其中一邊 —— 那邊讀到的是一條全零的 PCM，**不會報錯**。所以三邊都有後路：

  | 出事的一邊 | 後路 |
  | --- | --- |
  | 系統那邊聽不到／出錯／等足 `SYS_STT_WAIT_MS`（8 秒）都不應 | 段錄音仍在手，`finishSysStt()` 跌回 `startAiTranscribe()` |
  | 我們自己那條 PCM 被靜（VAD 判 `Silent`） | 有系統 STT 陪住時**不當按錯**，照等系統那句（`stopAiStt()` 那句條件） |
  | 系統那邊早了 `SYS_STT_TAIL_MS`（1.5 秒）以上自己收工 | 那句一定斬到一半，`sysTailLost` 判掉，照送去 AI |

  只有 `TooShort`（400ms 以下）是兩邊都不問 —— 那個看的是時長，不受靜音影響。
- **不要讓系統那邊靜了一陣就自己埋單**：`startSysStt()` 落
  `EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS` /
  `..._POSSIBLY_COMPLETE_...`（`SYS_STT_SILENCE_MS` = 30 秒），幾時收工由我們
  `stopListening()` 話事。這兩個 extra **不是每個 recognizer 都認**，所以上面
  那條 `sysTailLost` 後路一定要留。
- **系統那邊自己那兩下「開始／完結」提示聲，只可以靠靜音遮**（`muteEarcons`）。
  那兩下在辨識服務（多數是 Google app）自己個 process 度播，`playTone` /
  `Prefs.toneLevel` 完全管不到，也沒有公開 API 叫它不要播。所以
  `Prefs.sttMuteEarcon`（設定頁那個掣，預設開）開著時，`startListening()`
  之前把 `EARCON_STREAMS` 靜掉，`releaseSysStt()` 之後 `EARCON_TAIL_MS`（0.7 秒，
  等埋那下「完結」聲）才還原。三個位不可以改壞：
  **逐條 stream 分開 `runCatching`**（靜 `STREAM_SYSTEM` 在不少機上等同郁鈴聲模式，
  沒有 notification policy access 會掟 `SecurityException`，掟就跳過那條，
  不可以連 `STREAM_MUSIC` 都一起不做）、**不可以加 `STREAM_NOTIFICATION`**
  （我們自己那四下提示音就在那條）、**還原一定要有安全網**
  （`EARCON_MUTE_MAX_MS` 20 秒，加上 `onDestroy` 直接 `unmuteEarcons()`）——
  留低部機靜了是不可以接受的。
- **`onPartialResults` 那句要記低**：有些 recognizer 收工只派 partial，final
  那個 bundle 是空的，攞不到 final 就用回最後聽到那句（`sysSttText`）。
- **`recMs` 要在 `rec.stop()` 之前攞**：`VoiceRecorder.finish()` 會把
  `startedAt` 清零，`elapsedMs` 之後回 0，`sysTailLost` 就會永遠計錯。

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

## 九宮格預覽關聯字（`Prefs.KEY_RELATE_PREVIEW`，預設開）

打完一個字、還沒輸入下一個字碼，而且該字在 `related_candidates_table` 有關聯字時，
九宮格 **1～9 左上角**預覽「此時按『關聯字』會坐那格的字」（第一頁，格號 = 次序，
與 `TTEngine.slotOrder(0)`／`relatePadSlots` 相同；`*` 佔位不畫）。
筆形圖縮至原本的 **55%**，放在右下角。字體是選字字體的 50%；超過一個字再除以
grapheme 數，仍太寬再夾入格內，**靠左**。

資料跟打完嗰隻字（`TTCmd.RELATE` 用嘅 `lastWord`），不是候選欄的
`contextPicks()`（游標前面那字）。按一次「取消」只收起預覽
（`TTInputMethodService` 見到 `relatePadPreviewing` 就叫 `dismissRelatePreview`，
不清 `lastWord`）——之後按「關聯字」仍然開得到那個表。
打完下一個有關聯字的字會重新打開。設定頁「其他 → 預覽關聯字」可關。

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

### 工具列那幾顆掣的樣，跟鍵盤那些鍵一模一樣

`chipBg()` 是**圓角 6dp、沒有邊框**，跟 `KeyboardBaseView.drawFace` 一樣；
每顆四邊留 `Prefs.gapDp`（設定頁那條「邊框粗細」，跟鍵那個 `gapPx` 同一個數），
不再是寫死的 3dp margin ＋ 一條 1px 灰邊。2026-09-09 之前兩截東西各有各的樣，
拉大「邊框粗細」時只有鍵盤在變，工具列不動，看上去不像同一套。

`OptionBarsView` 與 `SidePanelView` 兩邊都要一起改 —— 它們是同一批掣的兩個排法。

### 左上角 = 長按執行哪項功能，不要在此顯示即時狀態

整個應用程式遵循一項規則：**按鍵左上角小字一律表示「長按會執行的操作」**（`drawCornerHint`）。
同音鍵以前破了這條規矩（左上角顯示即時提示），2026-08-28 改回：

| 位 | 放甚麼 | 對應程式碼 |
|---|---|---|
| 左上 | `Key.hint` ＝長按那個 `PadFunc.face`（預設「關聯字」）| `PadFunc.toKey()` |
| 左下 | 即時提示（`homoWord` / `homoCodeHint`）| `drawCornerHintBottom` |

同一條規矩之下同時補回：`?123` 左上角寫 `123`（長按直入 numpad；2026-08-29 起
**只中文九宮格那顆有**，英文那顆太窄，見「英文鍵盤排位」），
`Eng` 左上角畫個地球 `ToolIcon.GLOBE`（長按轉輸入法 ——
顆獨立 🌐 按鍵已隱藏之後，沒有這個 icon 就沒有人知按得長按）。個地球是
`drawCornerIcon` 畫的**單色** vector，不要改回寫 emoji 🌐（鍵面其餘全單色）。

2026-09-09 這個地球**不再綁死在 `Eng` 那顆**：規矩改成「長按那個 `PadFunc`
沒有字可寫（`face` 是空）就畫圖案」，見 `ChinesePadView.faceIconOf`。
現在只有換輸入法那兩顆是這樣 —— 「下一個輸入法」是 `GLOBE`，
「彈出輸入法選擇表」是 `GLOBE_LIST`（地球旁邊三條橫線）。兩顆可以同時擺在
鍵盤上，單用個地球就分不出誰是誰，所以特意畫成兩個圖案。
它們擺在正中（不是角落）時走 `drawCenterIcon`。

### 按鍵排位：拖放砌左右欄與工具列（2026-09-09）

以前只有四個位置可以選功能（左上短／長按、同音長按、右上長按），
其餘全部寫死。現在**左欄四顆、右欄四顆、工具列整條**，每顆的短按與長按
都由使用者自己排：設定頁「一般 → 按鍵排位」（`ui/KeyLayoutEditor`）。

三個檔各管一件事，不要混在一起：

| 檔 | 管甚麼 |
| --- | --- |
| `core/PadFunc.kt` | 有哪些功能、每個擺得去哪（`FuncPlace`）、是否必用、是否只能短按 |
| `core/KeyLayout.kt` | 目前的排位、存／讀、**全部規矩**（`checkDrop` / `apply`） |
| `ime/PadFuncKeys.kt` | 一個 `PadFunc` 對應哪個 `KeyAction`、畫甚麼圖案、砌成 `Key` |

**規矩只有一個出處**：`KeyLayout.checkDrop()`。拖放與「按一下彈選單」兩條路
都問它，所以擺不下的東西根本擺不到 —— 不會存了個殘廢排位落去，回到鍵盤才
發現打不到字。加新規矩就加在那裡，不要在 UI 那邊補一層。五條規矩：

0. **工具列沒有長按**（`KeyLayout.TOOLS_HAVE_LONG` = false）—— 那幾顆本來就有
   自己的按住動作（「貼上」開剪貼簿歷史、🎤 一路錄、「改變大小」拉到最闊），
   再讓人配一個上去一定撞。`normalise()` 會強行把 tools 的 `long` 清成 `NONE`
1. **擺得去哪**：`␣`／`⌫`／`⏎`／`⇄` 只能在左右欄（條工具列會在窄螢幕
   變側邊欄，那時找不到它們）；「改變大小」與「中文鍵盤」相反，只能在工具列
   （「改變大小」不是按一下就算，要**在那顆按鍵上直接拖**才拉得動鍵盤大小，
   九宮格那套沒有這種拖法）。
   **四顆轉鍵盤的（`Eng`／`?123`／`123`／`中`）兩邊都擺得**（2026-09-13
   使用者要求）：它們做的都是「轉去另一個鍵盤」，與工具列本來就擺得的
   「中文鍵盤」同一件事。`Eng` 照舊是必用鍵（規矩 4），所以左右欄一定仍有一顆，
   條 bar 收起了也切換得回英文
2. **`␣`／`⌫`／`⏎` 只能放短按**，放了之後同一格的長按強制停用
   （`Slot.effectiveLong`）—— 它們自己的長按早有意思（拖游標、連續刪）
3. **左右欄八個位置之間不准重複；工具列自己也不准** —— 但**兩邊各自計**，
   所以「貼上」同時在工具列與左欄是可以的（預設就是這樣）
4. **必用鍵不准在左右欄消失**（`PadFunc.required`）
5. 短按那格空了，同一格的長按也一定是空（按鍵本身都沒有，長按誰？）

由按鍵池拖上去 = **複製**（池永遠不會少）；格對格拖 = **兩格對調**；
拖回池裡 = 清走那一格。三種都經 `checkDrop`，擋住時一定 toast 講回為甚麼
（原句由 `KeyLayout` 出，UI 不要自己寫一套講法）。

#### 編輯那個欄的三顆：全選／復原／重做（2026-09-11）

`PadFunc.SELECT_ALL` / `UNDO` / `REDO`（使用者要求）—— 兩邊都擺得
（`FuncPlace.BOTH`），鍵面就寫「全選」「復原」「重做」三個中文，沒有圖案。
三顆都**不關輸入法的事**，一律叫那個欄自己做：

| 功能 | 做法 | 為甚麼 |
| --- | --- | --- |
| 全選 | `InputConnection.performContextMenuAction(android.R.id.selectAll)` | 就是揀字選單那項「全選」。**不要**自己 `getExtractedText` 數字數再 `setSelection` —— 長文那段是截了的，數出來的長度不是全部 |
| 復原 | 發 **Ctrl+Z** 的 key event | `InputConnection` 根本沒有 undo 這個 API，`android.R.id.undo` 又不是公開的 id。`TextView` 自己認 Ctrl+Z／Ctrl+Shift+Z（`Editor.UndoManager`），走硬件鍵盤那條路反而通用 |
| 重做 | 發 **Ctrl+Shift+Z** | 同上 |

發 key event 那下照住真實鍵盤的次序：**Ctrl 按下 → Z 按下放開 → Ctrl 放開**
（`TTInputMethodService.sendMetaKey`），有些欄看住 Ctrl 到底按了沒有，不是單看
event 那個 `metaState`。`sendDpad` 那個不帶 meta，行不到 Ctrl 組合。

三顆都先 `finishLatinComposing()` ＋ 中文 `engine.cancel()`：composing 那段還在
那個欄裡面，不清就會連它一起揀／一起復原。做完叫 `onStateChanged()` ——
AI 改寫那顆是「有揀字才按得」，全選之後要亮回。

**支援不了的欄不會有任何反應**（那個 key event 發了出去沒有人理），這件事無法
事先問得到，所以不出 toast 亂說「已復原」。

#### 拖不郁的那個坑：`requestDisallowInterceptTouchEvent`

`KeyLayoutEditor.dragSource()` 在 `ACTION_DOWN` 那下**一定要**叫
`requestDisallowInterceptTouchEvent(true)`。設定頁整版在一個 `ScrollView` 裡面，
手指一向上／向下移夠 slop，`ScrollView.onInterceptTouchEvent` 就會搶走整串
event 去捲版 —— 我們連 `ACTION_MOVE` 都收不到，`startDragAndDrop` 永遠不會叫。
由按鍵池拖去上面工具列必定要向上走一大截，所以每次都撞正
（2026-09-09 使用者報「拖時有時無效，變了捲整版」）。

拖的影是自己那個 `ChipShadow`（放大 1.15 倍、半透明），不是 `DragShadowBuilder`
的預設 —— 預設是把那顆掣原封不動再畫一次，跟底下那格一模一樣，看上去像沒有動過。

#### 工具列在設定頁是 4×2，不捲

一般手機的闊度，一行擺五顆已經嫌逼，八顆一行就一定要捲 —— 捲起來後面那幾顆
根本沒有人見到，「最多八顆」變成講了等於沒講。所以設定頁那格是
**兩行、一行四個**（`KeyLayoutEditor.TOOLS_PER_ROW`），一屏見晒，不捲。
（鍵盤本身那條工具列是另一回事，見上面「工具列那行掣的闊度」。）

空格自己就是「加一顆」的 drop target，加極都是加在最後
（`KeyLayout.normalise` 本來就不准工具列中間有空格）。

#### 排位跟足鍵盤的樣

編輯器的排列是**上（工具列）、左、右**，跟鍵盤本身對得回。兩條側欄**左右鏡像**：
左欄的短按貼實最左、右欄的短按貼實最右。長按那格**矮一截、貼實短按、底對底**
（所以頂低過隔籬那顆），一眼看得出它是附屬，不是另一顆鍵。

`␣`／`⌫`／`⏎`（`PadFunc.tapOnly`）與空的短按位**索性沒有長按格** ——
畫個灰格在那裡只會引人去按。

工具列**沒有 `＋` 掣**（2026-09-09 使用者要求）：加一顆就是由按鍵池拖上去，
整條 strip 自己就是 drop target，跌在右邊空位 = 在尾加一顆（`onStripDrag`）。

**升級不會走位**：舊裝置沒有 `Prefs.KEY_LAYOUT`，`KeyLayout.load()` 會讀回
那四個舊 pref ＋ `KEY_ENG_LONG` 砌一個一模一樣的排位出來
（`fromLegacyPrefs`）。舊版特意准四個位置選同一件事，新規矩不准，
所以最後行一次 `dedupSide()` 清走重複 —— 不清的話排位一開始就是無效狀態。

`KeyLayoutTest` 盯死上面五條。改規矩就同時改測試，不要只改一邊。

每顆按鍵的 `hint` ＋ `longAction` 由 `PadFunc.toKey()` 一次過補上。
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
那個模式下長按那格有另一個意思（彈出「左／右」選單只輸入單邊），
見「開關標點」一節，而且它比 `onLongPress` 更早截住（`variantsOf` 先行）。

## 長按「表情」＝彈出最近用過那十個速選

按一下「表情」照舊開整個 emoji 表；**按住**就在按鍵上面彈出一行最多十個
（`QuickEmoji.MAX`，2026-09-11 使用者要求）—— 不要放手，拉去揀，放手才輸入，
與英文鍵盤長按彈變體完全同一套手勢。按住了不動就放手 ＝ 最近用過那個
（排頭那個），等於「再打多一次上次那個 emoji」。

那行的內容是 `EmojiDict.quick()`：**最近用過**（`KEY_EMOJI_RECENT`，與 emoji 表
第一個分類同一條 list）排先，不夠十個就由 `EmojiDict.COMMON` 那十個補回尾，
所以新裝機未用過 emoji 也彈得出東西。故意不查 `emoji.txt` —— 長按那一刻要即刻
彈出，不可以在那時才載入整個表。速選揀了也會 `addRecent()`，與在 emoji 表揀一樣。

**只在那顆按鍵本身的長按空著時才生效**（`QuickEmoji.appliesTo()`：
`action == TO_EMOJI && longAction == NOOP`）。使用者特意配了長按就一定不可以吃掉它：

- 九宮格左右欄那個位配了長按功能（`KeyLayout.Slot.long`，例如短按表情、長按速選字）：
  長按照做那個功能，`variantsOf()` 回空，跌回 `Host.onLongPress()`。
- 「表情」擺在**另一顆按鍵的長按格**（短按關聯字、長按才開表情表）：那顆的
  `action` 根本不是 `TO_EMOJI`，長按照樣開整個表。
- 工具列／側邊欄那顆沒有得配長按（`KeyLayout.TOOLS_HAVE_LONG = false`），所以一定有。

兩個地方走兩條路，改一邊記住另一邊：

| 在哪 | 怎樣做 |
| --- | --- |
| 九宮格左右欄 | 借鍵盤本體那套長按變體 popup：`ChinesePadView.variantsOf()` 餵一行 emoji 進去，`commitVariant()` 只負責 `addRecent()`（回 `false`，出字照走 `KeyAction.CHAR`） |
| 工具列／側邊欄 | 那些是 `TextView`，沒有那套，所以 `QuickEmojiPopup` 用同一個 `KeyPopup` 砌多次：`setOnLongClickListener` 彈出、`setOnTouchListener`（**永遠回 `false`**，不吃掉按鍵本身的短按／長按）跟手指移動，放手 `Listener.onQuickEmoji()` 輸出 |

`QuickEmojiPopup.open()` 有兩處易漏：那行比按鍵闊很多，座標要夾回螢幕之內
（用 anchor 那套座標，負數 ＝ 出了按鍵左邊）；而且手指一橫拉就離開了按鍵，
要 `requestDisallowInterceptTouchEvent(true)` 頂住外面那個 `HorizontalScrollView`
（工具列擺滿按鍵就捲得動），不是拉一下就被它搶走變 `ACTION_CANCEL`。

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
字型沒有 `⌕`（`Paint.hasGlyph`）就寫回「搜尋」兩字，不可以出一格豆腐 ——
`⏎` 其餘六款 `imeOptions` 的符號走同一個 `glyphOr()`（見上面「`⏎` 跟
`imeOptions` 換樣」）。

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

目前改成排列方式上解決，而且**四種排法由設定頁選**（`Prefs.PagerLayout`，
設定頁「一般 → 選字翻頁」，排在「按鍵排位」下面）。頭三種都只在
`selectMode && totalPage > 1`（`ChinesePadView.paging()`）時先生效：

| `PagerLayout` | 底行 |
| --- | --- |
| `PREV_NEXT` | 拆兩個正常寬：左「上頁」、右 `0`（＝「下頁」） |
| `NEXT_PREV` | 拆兩個正常寬：左 `0`（＝「下頁」）、右「上頁」 |
| `WIDE_NEXT`（**預設**） | 不拆，成兩格寬那顆 `0` 就是「下頁」，**長按 = 上頁** |
| `NO_CHANGE` | 完全不變樣：兩格寬照舊，長按照舊是成對標點 |

`NO_CHANGE`（2026-09-09 加）**不是「翻不到頁」**：`TTEngine.press(0)` 在選字
模式收到 `0` 一律 `TTCmd.NEXT`，那是三三本身的打法，不關排位事。它只是不
為了翻頁而改動排位 —— 想要一顆明確的翻頁鍵，就在「按鍵排位」把
`PadFunc.NEXT_PAGE` / `PadFunc.PREV_PAGE` 拖去左右欄。

「上頁」是 `KeyAction.PREV_PAGE` → `TTCmd.PREV`，「下頁」是
`KeyAction.NEXT_PAGE` → `TTCmd.NEXT`。

顆「下頁」**顯示 `1/10`**（`TTEngine.pageHint`，由 1 起計，不是 0）。
與同音鍵一樣是在 `drawDigit` 中即時問 engine 取，不是 `Key.hint`（`boxes`
不會逐次重建）。拆兩個那兩個排法放在**左上角**，沒有分頁時 `pageHint` 是空，
位置就留給讓長按提示 `「」`。

`WIDE_NEXT` 有兩件事與其餘兩個不同，改時兩邊都要一起改：

- **排位始終保持不變**（`wantSplitPager()` 只有拆兩個那兩種先回 true），
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
  **開關標點那個表例外**：`onLongPress` 根本不會收到那次長按，`ChinesePadView`
  已經用 `variantsOf()` 彈了「左／右」選單出來（見「開關標點」一節）。

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

### 在那個表長按一格 = 只輸入單邊（`「` 或者 `」`）

成對是常態，但有時就只要一邊（例如「補返個收的引號」）。2026-09-11 使用者要求：
**開關標點那個表攤開後，長按任何一格都會彈出一個只有兩格的 popup ——
左邊那隻、右邊那隻，選哪個就只輸入那一隻**，不用轉去符號頁找。

走的是既有的長按變體 popup（`KeyPopup`，見下一節），只是兩個位置改成問實時狀態：

| 位置 | 做甚麼 |
| --- | --- |
| `KeyboardBaseView.variantsOf(k)` | 哪幾個變體。預設 `Key.variants`（英文／符號鍵盤寫死在按鍵上），`ChinesePadView` override 成 `TTEngine.pairSidesAt(digit)` |
| `KeyboardBaseView.commitVariant(key, v)` | 選完那次。回 `false` 就照行 `Host.onKey(CHAR)`；`ChinesePadView` 回 `TTEngine.pickPairSide()` |

**不可以讓它跌回 `typeChar()`**：那條路只 commit 文字，不會清走選字狀態，
個表會留在畫面上，下一個數字鍵又變了選標點。`pickPairSide()` 是
`host.commitText()` ＋ 清 `bigramPrev` ＋ `cancel()`，與選一對標點
（`selectWord()` 那個 `openclose` 分支）完全同一套，只差不行 `commitPair()`
那個「移 caret 回中間」。

`pairSidesAt()` 只在 `pairMode`（＝ `selectMode && openclose`）回東西，
所以其餘字表（速選字、同音字、關聯字）長按一格照舊是開同音字表，
`0` 也照舊（`pairSidesAt(0)` 回空，`slot` 只收 `1..9`）。
次序跟資料表那一對本身，左邊那隻排頭 —— 所以「長按完不移動直接放手」＝ 輸入左邊那隻。

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
- **哪幾個變體一律問 `variantsOf(k)`，不要直接讀 `Key.variants`**：`boxes` 不會逐次
  重建，跟實時狀態變那些（開關標點表的「左／右」）只能在長按那一刻問 engine。

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

### 選「下一個字」要補回前面那個空格（2026-09-11 加）

候選欄那些「下一個字」預測（`latinComposing` 是空、又未 swipe 過）是接在前面
那個字後面的，所以 `onPickCandidate()` 在 `commitText(w)` **之前**要補個空格 ——
在候選欄選完一個完整的字（`wasTypedPrefix`）那條路不會補尾隨空格，跟着再選一個
預測字就會變成 `helpthere`（使用者 2026-09-11 報）。

補不補看 `needSpaceBeforeWord()`：游標前面**貼住**英文字（字母／數字／`'`）才補。
空格、標點、換行、中文字、或者整個欄都是空的就不補 —— 那些位置本來就是一個字的
開頭。與 `autoSpaceAfterPunct()` 是兩件事：那個是「標點後面」，這個是「字與字之間」。

## 英文詞庫

`assets/en_freq.txt`（`字 頻率`，已經由高到低排好，**5 萬行**）由
`scripts/build-en-freq.sh` 產生，兩份原始數據夾埋一齊：

| 原始檔（`/mnt/d/sync_dev/eng/data/`） | 是甚麼 |
| --- | --- |
| `en-most.txt` | 維基百科語料 `字 頻率`，20 萬個，覆蓋面闊 |
| `30k.txt` | 日常英文常用字**排名**（無頻率，行數就是名次），3 萬個 |

**不可以只用維基那份**（2026-09-10 修正，使用者報「滑 `swipe` 永遠出 `style`」）——
那是百科全書語料，姓氏地名多到不得了，日常口語詞排得很後：`swipe` 排 80494，
裁到 5 萬就整個字消失；而形狀一模一樣的 `swope`（一個姓）反而 265 次 > `swipe` 127 次。
滑動認字靠頻率拆歧義（見下面 `GestureDecoder`），這樣就會滑得完全準確都出個姓出來。
同類還有 `anwar` 壓住 `answer`、`story` 壓住 `sorry`。

所以在 `30k.txt` 內那些字會用 **Zipf（頻率 ∝ 1/名次）**砌回一個合成頻率，
與維基頻率取大那個；不在 `30k.txt` 就照用維基頻率 —— 日常詞行回前面，
維基語料只負責補長尾（專有名詞、生僻字仍然查得到，但拆歧義時不會贏）。

**入 assets 之前一定要裁到 5 萬個**（script 內 `KEEP=50000`），不是 apk 體積會大幅增加。
要換新原始數據就改 script 再跑一次，不要手改 `en_freq.txt`。

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
3. 距離用 `keyWidth` 正規化（不同螢幕、不同鍵盤大小都能正確匹配），乘
   `SPATIAL_WEIGHT` 之後再與 `ln(頻率) × LM_WEIGHT` 夾埋做總分。
4. 改這個評分公式之前，看回 `EnDictRealDataTest`（真字典 + 手震雜訊都要能正確匹配）
   與 `EnDictTest`（fake 座標，形狀＋常用度都要試到）。

**`SPATIAL_WEIGHT` 是 3f 不是 1f**（2026-09-10 修正）。`ln(頻率)` 的範圍是
5.5（罕見字）到 17.3（`the`），乘 `LM_WEIGHT` 之後有 1.9 分落差；但形狀夾不夾，
好極與差極之間只有 0.7 分左右 —— 即是**詞頻的影響力是形狀的兩倍多**，
結果滑得幾準都好，都會輸給一個順路而又更常用的字。實測（105 個常用字、
每點加 ±10% 鍵闊雜訊）當時 top1 只有 96/105，錯的全部是這一類：
滑 `there` 出 `the`、滑 `forget` 出 `first`、滑 `sorry` 出 `story`、
滑 `suppose` 出 `some`。改成 3f 之後詞頻只在形狀夾到差不多時才拆得動
（`swipe` / `swope` 那種），連同上面詞庫那個修正，同一個測試回到 106/106。

再拉高（6f、8f）在乾淨軌跡上會再好一點，但雜訊大時反而更差（過份相信形狀），
所以停在 3f。`EnDictRealDataTest.滑得準就唔可以輸畀更常用嗰個字` 就是這批個案。

### 詞庫沒有那個字：`GesturePivots`（2026-09-10 加）

形狀比對只會在詞庫內揀，所以滑一個詞庫沒有的字（人名、代號、新字）**一定**出錯字。
`GesturePivots` 補回另一條線索：**完全不查詞庫**，只看軌跡上哪幾點是
「特意停低／特意拗彎」，砌回那串字母，交給 `GestureDecoder` 做**多一個候選**
跟詞庫那些一齊評分。

- 三種算「明確」的證據：**起點終點**（永遠算）、**停低**（連續一段時間都沒有
  行出 0.33 個鍵那麼遠，而且維持夠 70ms）、**拗彎**（入角出角差 50° 以上）。
- **停低要用「行了多遠」不可以用「多快」。** 即時速度是向後望一個窗計出來的，
  手指停低之後那個窗還蓋着停低之前那段快速移動，要 50ms 之後才跌下去 ——
  量到的停留會短過真實停留一大截（實測停 80ms 只量到 20ms，`swipe` 抽出來變
  `swpe`）。直接看「有沒有行出過一個小圈」就沒有這個滯後，順帶還擋住
  「慢慢拉一條直線」（慢極都好，一路都在行，不會困在圈內）。
- 那個候選的詞頻當 `LITERAL_LM = 0.8f`，**特意低過詞庫最罕見那個（0.96）**：
  只要有詞庫字夾得差不多貼，都應該出詞庫那個。要贏就一定要形狀明顯好過所有
  詞庫字，即是「這串字母真的不屬任何字」那種情況。實測 114 個常用字，
  加了這個候選之後準確率**一個都沒有跌**。
- **抽不到就抽不到，不用另外定一條「夠不夠信」的界線**：滑得太順、沒有停過
  又沒有拗過，就只會剩回起點終點兩粒，砌出來那個「字」形狀一定夾不到軌跡，
  自然會在評分那裏輸給詞庫。
- **疊字抽不回兩個**（`hello` 只會抽到 `helo`）—— 手指在 `l` 只有一個停留，
  軌跡根本沒有「按兩次」這個資訊。還原疊字是詞庫的責任。

所以 `LatinHost.onSwipePath` **一定要連 `times` 一齊拋**（`tracker.times`，
與 `tracker.points` 一對一）。`GestureDecoder.decode` 收到的 `times` 空、
或者對不上數，就只查詞庫，行為跟以前一模一樣。

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

正式那條 key（2026-09-02 生成：PKCS12、alias `upload`、RSA 4096、有效期 10000 日，
密碼是隨機 32 字）**不在 repo 內**，密碼也不會入 git：

```
~/.android/tt-release.keystore
~/.android/tt-release.properties   # storePassword / keyAlias / keyPassword（權限 600）
```

（2026-08-25 那個 `~/.android/tq9-release.keystore` 是改名前的舊物，密碼沒有記下，
不再使用 —— `applicationId` 已改為 `tt.ime.riverine`，Play 那邊本來就是全新 listing。）

`app/build.gradle.kts` 見到 `-Ptt.upload` 才砌 `upload` 這個 `signingConfig`，
兩個檔案有一個不見就立即 fail（不會無聲地回退至 debug key）。**沒有加 `-Ptt.upload`
就一定是 debug key** —— side-load 那條線一直是那條 key 簽，無聲地已更換，
些人就要 uninstall 了先裝到新版。`.gitignore` 已經封全 `*.keystore` / `*.jks` /
`*keystore.properties`，keystore 消失就永遠再 無法再更新應用程式，請務必備份。

Play 收 `.aab` 不收 `.apk`（新 app），所以上架那個是 `bundleRelease`；
想自己裝來試就 `assembleRelease -Ptt.upload`（與 debug key 那個無法與 debug key 版本同時安裝在同一裝置上）。

個人開發者帳號要先做封閉測試（12 個 tester × 連續 14 日）才批得 production，
整個流程寫在 `docs/closed-testing-guide.html`（`docs/` 不入 git）。

## 版本號：每次改完自動加一

`app/build.gradle.kts` 的 `versionName`（`x.y.z`）與 `versionCode`：
**使用者叫改內容（無論是新增功能或修正錯誤），一改完就自動兩個一起 +1**——
`versionName` 補回 patch（`1.0.0` → `1.0.1`），`versionCode` 都 +1，
不用 使用者特別提及先做。第一個正式版由 `1.0.0` 開始（2026-08-21 定的）。
這個與 `/mnt/nas4/web/subdomains/dl/` 中的 `threethree-v<N>.apk` 個 `N`（每次 build release 版都加一）
是兩件不同的內容，不可混為一談。
