#!/usr/bin/env bash
#
# 三三輸入法 (ThreeThree) — 喺 terminal 睇實機打字過程
#
# 查「我明明撳咗 793，點解出咗第二隻字」呢類問題：逐行寫住手指撳落邊粒鍵
# （連坐標同貼邊補正）、滑動途中每格點計分、engine 收到嘅碼一路變成點、
# 最後揀咗邊格出咗邊隻字。對住條 log 就見到自己撳嘅同輸入法收到嘅差喺邊。
#
#   ./scripts/debug-input.sh                # 接住部機開始睇
#   ./scripts/debug-input.sh --install      # 先 build + 裝 debug APK 再睇
#   ./scripts/debug-input.sh -s <serial>    # 插住幾部機嗰陣指定邊部
#   ./scripts/debug-input.sh --raw          # 唔上色、唔加空行（方便 tee 落檔）
#   ./scripts/debug-input.sh --off          # 熄返個記錄開關，唔睇 log
#
# 睇完記得熄返（`--off`，或者設定頁「其他 → 記錄輸入過程 (logcat)」）——
# 條 log 寫住你打緊乜。
#
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

PKG="tt.ime.riverine"
TAG="TTInput"
SERIAL="" INSTALL=0 RAW=0 OFF=0

die()  { printf '\033[31m✗ %s\033[0m\n' "$*" >&2; exit 1; }
info() { printf '\033[36m▸ %s\033[0m\n' "$*" >&2; }
ok()   { printf '\033[32m✓ %s\033[0m\n' "$*" >&2; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    --install)  INSTALL=1 ;;
    --raw)      RAW=1 ;;
    --off)      OFF=1 ;;
    -s)         SERIAL="${2:-}"; shift ;;
    -h|--help)  sed -n '3,16p' "$0" | sed 's/^# \?//'; exit 0 ;;
    *)          die "唔識呢個 flag：$1（--help 睇用法）" ;;
  esac
  shift
done

command -v adb >/dev/null || die "搵唔到 adb（裝 Android platform-tools）"
ADB=(adb)
[[ -n "$SERIAL" ]] && ADB=(adb -s "$SERIAL")

# 冇機／幾部機都要即刻講，唔好行到一半先死
DEVICES=$("${ADB[@]}" devices | awk 'NR>1 && $2=="device" {print $1}')
[[ -z "$DEVICES" ]] && die "冇接到機（adb devices 睇下；模擬器要開咗先）"
if [[ -z "$SERIAL" && $(wc -l <<<"$DEVICES") -gt 1 ]]; then
  die "接住多過一部機，要用 -s 指定："$'\n'"$DEVICES"
fi

if [[ $INSTALL -eq 1 ]]; then
  info "build debug APK…"
  JAVA_HOME="${JAVA_HOME:-/opt/android-studio/jbr}" ./gradlew :app:assembleDebug -q
  "${ADB[@]}" install -r app/build/outputs/apk/debug/app-debug.apk >/dev/null
  # 每次 install -r 系統都會當佢 reinstall 而 reset，要再 enable + set 一次
  "${ADB[@]}" shell ime enable "$PKG/.ime.TTInputMethodService" >/dev/null
  "${ADB[@]}" shell ime set    "$PKG/.ime.TTInputMethodService" >/dev/null
  ok "裝好，輸入法已經揀返"
fi

"${ADB[@]}" shell pm path "$PKG" >/dev/null 2>&1 || die "部機未裝過 $PKG（行 --install）"

# 兩個開關任開一個都會出 log（見 core/InputLog.kt）：
#   1. setprop —— 唔使入設定頁，但係熄機就冇咗
#   2. 設定頁「其他 → 記錄輸入過程 (logcat)」—— 熄機都仲喺度
# 條 script 用 setprop（唔郁 user 個設定），所以 IME 要重新起過先讀到。
if [[ $OFF -eq 1 ]]; then
  "${ADB[@]}" shell setprop log.tag."$TAG" INFO || true
  ok "已經熄咗 setprop 嗰個開關。設定頁嗰個開關（如果開過）要自己入去熄。"
  exit 0
fi

"${ADB[@]}" shell setprop log.tag."$TAG" DEBUG ||
  die "setprop 唔畀行 —— 改用設定頁「其他 → 記錄輸入過程 (logcat)」開"

# IME 係一個長命 service，`Log.isLoggable` 逐次讀返 property，所以唔使重開；
# 但係之前 crash 過／未起過就要撳一撳輸入框佢先起身。
"${ADB[@]}" logcat -c >/dev/null 2>&1 || true

ok "開始收 log —— 而家去部機打字，Ctrl-C 收工"
info "撳落／放手 = 手指；滑動 = 逐格判定；撳…碼 = engine 收到嘅碼；揀格／出字 = 結果"
echo >&2

FILTER=(logcat -v time -s "$TAG:D")

if [[ $RAW -eq 1 ]]; then
  exec "${ADB[@]}" "${FILTER[@]}"
fi

# 上色：手指做嘅嘢一種色、engine 收到乜另一種色，一眼掃到邊行係邊樣。
# 每次「出字」之後空一行，一個字一段咁睇。
"${ADB[@]}" "${FILTER[@]}" | awk '
  /撳落|放手|連撳|長撳/     { printf "\033[36m%s\033[0m\n", $0; next }   # 青 = 手指
  /滑動|滑過/               { printf "\033[35m%s\033[0m\n", $0; next }   # 紫 = 滑動判定
  /出字/                    { printf "\033[32m%s\033[0m\n\n", $0; next } # 綠 = 出咗字，跟住空行
  /查唔到|唔算|唔再出|吉位/ { printf "\033[31m%s\033[0m\n", $0; next }   # 紅 = 冇效果
                            { print }
'
