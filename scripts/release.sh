#!/usr/bin/env bash
#
# 三三輸入法 (ThreeThree) — build release APK 然後出 GitHub Release
#
#   ./scripts/release.sh              # 用 build.gradle.kts 現時嘅版本
#   ./scripts/release.sh --bump       # 先 patch +1（versionName / versionCode）
#   ./scripts/release.sh --draft      # 出 draft，唔即刻公開
#   ./scripts/release.sh --dry-run    # 淨係 build，唔掂 git / GitHub
#
# 其他 flag：--title "..."、--notes-file FILE、--allow-dirty、--prerelease
#
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."
ROOT="$PWD"
GRADLE_FILE="app/build.gradle.kts"
CHANGELOG="CHANGELOG.md"

BUMP=0 DRAFT=0 PRERELEASE=0 DRYRUN=0 ALLOW_DIRTY=0
TITLE="" NOTES_FILE=""

die()  { printf '\033[31m✗ %s\033[0m\n' "$*" >&2; exit 1; }
info() { printf '\033[36m▸ %s\033[0m\n' "$*"; }
ok()   { printf '\033[32m✓ %s\033[0m\n' "$*"; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    --bump)        BUMP=1 ;;
    --draft)       DRAFT=1 ;;
    --prerelease)  PRERELEASE=1 ;;
    --dry-run)     DRYRUN=1 ;;
    --allow-dirty) ALLOW_DIRTY=1 ;;
    --title)       TITLE="${2:-}"; shift ;;
    --notes-file)  NOTES_FILE="${2:-}"; shift ;;
    -h|--help)     sed -n '2,10p' "$0" | sed 's/^# \?//'; exit 0 ;;
    *)             die "唔識呢個 flag：$1（--help 睇用法）" ;;
  esac
  shift
done

# ---------- 前置檢查 ----------
command -v gh >/dev/null || die "搵唔到 gh CLI（https://cli.github.com/）"
[[ -f "$GRADLE_FILE" ]] || die "搵唔到 $GRADLE_FILE，唔喺 repo 根度？"
if (( ! DRYRUN )); then
  gh auth status >/dev/null 2>&1 || die "gh 未 login，行 'gh auth login' 先"
fi

KEYSTORE="$HOME/.android/debug.keystore"
[[ -f "$KEYSTORE" ]] || die "搵唔到 $KEYSTORE —— release 要用返同一個 key 簽，唔係人哋要 uninstall 先裝到"

if (( ! ALLOW_DIRTY && ! DRYRUN )) && [[ -n "$(git status --porcelain)" ]]; then
  git status --short
  die "working tree 未 clean，commit 咗先（或者 --allow-dirty）"
fi

# ---------- 版本號 ----------
read_ver()  { sed -n 's/.*versionName *= *"\([^"]*\)".*/\1/p' "$GRADLE_FILE" | head -1; }
read_code() { sed -n 's/.*versionCode *= *\([0-9]*\).*/\1/p'  "$GRADLE_FILE" | head -1; }

if (( BUMP )); then
  cur="$(read_ver)"; code="$(read_code)"
  [[ -n "$cur" && -n "$code" ]] || die "讀唔到 versionName / versionCode"
  IFS=. read -r MA MI PA <<<"$cur"
  new="$MA.$MI.$((PA + 1))"; newcode=$((code + 1))
  sed -i "s/versionName *= *\"$cur\"/versionName = \"$new\"/; s/versionCode *= *$code\b/versionCode = $newcode/" "$GRADLE_FILE"
  ok "版本 $cur ($code) → $new ($newcode)"
  if (( ! DRYRUN )); then
    git add "$GRADLE_FILE"
    git commit -q -m "chore: 版本 $new (versionCode $newcode)"
    ok "commit 咗版本號改動（$(git rev-parse --short HEAD)）"
  fi
fi

VER="$(read_ver)"; CODE="$(read_code)"
[[ -n "$VER" ]] || die "讀唔到 versionName"
TAG="v$VER"
APK_NAME="tt-$VER.apk"
info "準備出 $TAG（versionCode $CODE）"

# ---------- build ----------
info "gradlew assembleRelease …"
./gradlew --console=plain assembleRelease

SRC_APK="app/build/outputs/apk/release/app-release.apk"
[[ -f "$SRC_APK" ]] || die "build 完搵唔到 $SRC_APK"

OUT_DIR="$ROOT/build/release"
mkdir -p "$OUT_DIR"
APK="$OUT_DIR/$APK_NAME"
cp -f "$SRC_APK" "$APK"
ok "APK：$APK（$(du -h "$APK" | cut -f1)）"

# 簽名核對（有 apksigner 先做）
APKSIGNER="$(command -v apksigner || ls "$HOME"/Android/Sdk/build-tools/*/apksigner 2>/dev/null | sort -V | tail -1 || true)"
if [[ -n "$APKSIGNER" ]]; then
  "$APKSIGNER" verify --print-certs "$APK" >/dev/null 2>&1 \
    && ok "簽名 OK" || die "APK 簽名核對唔過"
fi

(( DRYRUN )) && { ok "--dry-run，到此為止。APK 喺 $APK"; exit 0; }

# ---------- release notes ----------
NOTES="$(mktemp)"; trap 'rm -f "$NOTES"' EXIT
if [[ -n "$NOTES_FILE" ]]; then
  [[ -f "$NOTES_FILE" ]] || die "搵唔到 $NOTES_FILE"
  cat "$NOTES_FILE" > "$NOTES"
elif [[ -f "$CHANGELOG" ]] && grep -q "^## \[$VER\]" "$CHANGELOG"; then
  awk -v v="## [$VER]" '
    $0 ~ "^## \\[" { if (inblk) exit; if (index($0, v) == 1) { inblk = 1; next } }
    inblk { print }
  ' "$CHANGELOG" | sed '/./,$!d' > "$NOTES"
  ok "release notes 由 CHANGELOG.md [$VER] 抽出嚟"
else
  printf '完整改動記錄見 [CHANGELOG.md](CHANGELOG.md)。\n' > "$NOTES"
  info "CHANGELOG.md 冇 [$VER] 一節，用返預設 notes"
fi
printf '\n---\n\n直接裝 `%s`（versionCode %s）。舊版可以照 update，唔使 uninstall\n（**2.0.0 除外**：applicationId 改咗做 `tt.ime.riverine`，舊裝機 update 唔到，要另外再裝多次）。\n' "$APK_NAME" "$CODE" >> "$NOTES"

[[ -n "$TITLE" ]] || TITLE="$TAG"

# ---------- push ----------
BRANCH="$(git rev-parse --abbrev-ref HEAD)"
info "push $BRANCH 去 origin …"
git push origin "$BRANCH"

# ---------- tag ----------
# 目前個 commit 冇 tag 就補返 v<ver> 上去，有就照用返
HEAD_SHA="$(git rev-parse HEAD)"
HEAD_TAGS="$(git tag --points-at HEAD | tr '\n' ' ' | sed 's/ *$//')"

if git rev-parse -q --verify "refs/tags/$TAG" >/dev/null; then
  TAG_SHA="$(git rev-list -n 1 "$TAG")"
  [[ "$TAG_SHA" == "$HEAD_SHA" ]] || die "$TAG 已經指住 $(git rev-parse --short "$TAG_SHA")，唔係 HEAD。行 --bump 出新版本，或者 'git tag -d $TAG' 先"
  ok "HEAD 已經有 tag $TAG"
else
  [[ -z "$HEAD_TAGS" ]] || info "HEAD 已經有 tag（$HEAD_TAGS），但係今次出 $TAG，照加多個"
  git tag -a "$TAG" -m "ThreeThree $TAG"
  ok "加咗 tag $TAG 喺 $(git rev-parse --short HEAD)"
fi
git push origin "refs/tags/$TAG"

# ---------- 出 release ----------
FLAGS=()
(( DRAFT ))      && FLAGS+=(--draft)
(( PRERELEASE )) && FLAGS+=(--prerelease)

if gh release view "$TAG" >/dev/null 2>&1; then
  info "$TAG 已經有 release，覆返個 asset 同 notes"
  gh release upload "$TAG" "$APK#三三輸入法 $TAG (Android APK)" --clobber
  gh release edit "$TAG" --title "$TITLE" --notes-file "$NOTES" "${FLAGS[@]}"
else
  gh release create "$TAG" "$APK#三三輸入法 $TAG (Android APK)" \
    --title "$TITLE" --notes-file "$NOTES" "${FLAGS[@]}"
fi

URL="$(gh release view "$TAG" --json url -q .url)"
ok "出咗貨：$URL"
ok "APK 直接下載：$(gh release view "$TAG" --json assets -q '.assets[0].url')"
