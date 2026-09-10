#!/usr/bin/env bash
#
# 三三輸入法 (ThreeThree) — 重新產生 app/src/main/assets/en_freq.txt
#
# 兩份原始數據夾埋一齊（都喺 /mnt/d/sync_dev/eng/data/）：
#
#   en-most.txt  維基百科語料嘅 `字 頻率`，20 萬個，由高到低。
#                覆蓋面夠闊，但**係百科全書嘅語料**：姓氏、地名多到不得了，
#                而日常口語詞就排得好後。`swipe` 排 80494（成日打嘅字，
#                入唔到頭 5 萬）；`swope`（一個姓）反而 265 次 > `swipe` 127 次。
#                滑動認字靠頻率拆歧義，咁樣就會滑啱晒都出個姓出嚟。
#
#   30k.txt      日常英文常用字排名（冇頻率，行數就係名次），3 萬個。
#                `sorry` 169、`story` 309、`swipe` 5698，冇 `swope` ——
#                呢個先似人喺手機打嘅嘢。
#
# 做法：喺 30k 入面嘅字，用 Zipf（頻率 ∝ 1/名次）砌返個合成頻率，
# 同維基頻率取大嗰個；唔喺 30k 就照用維基頻率。咁樣日常詞行返前面，
# 維基語料淨係負責補長尾（專有名詞、生僻字仍然查得到，但拆歧義嗰陣唔會贏）。
#
# 出嚟一樣係 `字 頻率`、由高到低、**淨係頭 5 萬個**（見 AGENTS.md：
# 入 assets 之前一定要裁到 5 萬，唔係 apk 會大好多）。

set -euo pipefail

SRC_DIR="${SRC_DIR:-/mnt/d/sync_dev/eng/data}"
OUT="$(dirname "$0")/../app/src/main/assets/en_freq.txt"
KEEP=50000

WIKI="$SRC_DIR/en-most.txt"
COMMON="$SRC_DIR/30k.txt"

for f in "$WIKI" "$COMMON"; do
  [ -f "$f" ] || { echo "搵唔到原始數據：$f" >&2; exit 1; }
done

awk -v keep="$KEEP" '
  # 30k.txt：行數 = 名次。只收純細楷 a-z（`I`、`American`、`y'"'"'all` 唔要）
  FNR == NR {
    w = tolower($1)
    if (w ~ /^[a-z][a-z]+$/ && !(w in rank)) rank[w] = FNR
    next
  }
  # en-most.txt：`字 頻率`
  {
    w = tolower($1)
    if (w !~ /^[a-z][a-z]+$/) next
    if (w in seen) next
    seen[w] = 1
    freq[w] = $2 + 0
  }
  END {
    # Zipf：名次 1 嘅合成頻率 = 維基最高嗰個，之後 ∝ 1/名次
    top = 0
    for (w in freq) if (freq[w] > top) top = freq[w]
    for (w in rank) {
      z = int(top / rank[w])
      if (!(w in freq) || z > freq[w]) freq[w] = z
    }
    for (w in freq) printf "%d %s\n", freq[w], w
  }
' "$COMMON" "$WIKI" |
  sort -k1,1nr -k2,2 |
  head -n "$KEEP" |
  awk '{ print $2, $1 }' > "$OUT"

echo "寫咗 $(wc -l < "$OUT") 個字入 $OUT"
