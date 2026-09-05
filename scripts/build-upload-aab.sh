#!/usr/bin/env bash
#
# 三三輸入法（ThreeThree）— 建立可上載 Google Play 的新 AAB。
#
#   ./scripts/build-upload-aab.sh            # patch +1、跑 test、build、複製 AAB
#   ./scripts/build-upload-aab.sh --no-bump  # 沿用現有版本重新 build
#   ./scripts/build-upload-aab.sh --help
#
# 這條只負責本機產物，絕不 commit、push 或上載 Play Console。
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."
ROOT="$PWD"
GRADLE_FILE="app/build.gradle.kts"
OUTPUT_DIR="$ROOT/build/release"
NO_BUMP=0

die()  { printf '\033[31m✗ %s\033[0m\n' "$*" >&2; exit 1; }
info() { printf '\033[36m▸ %s\033[0m\n' "$*"; }
ok()   { printf '\033[32m✓ %s\033[0m\n' "$*"; }

usage() {
  sed -n '2,8p' "$0" | sed 's/^# \?//'
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --no-bump) NO_BUMP=1 ;;
    -h|--help) usage; exit 0 ;;
    *) die "不認識這個選項：$1（用 --help 看用法）" ;;
  esac
  shift
done

[[ -f "$GRADLE_FILE" ]] || die "找不到 $GRADLE_FILE；請從 repo 內執行。"
[[ -f "$HOME/.android/tt-release.keystore" ]] || die "找不到 ~/.android/tt-release.keystore。"
[[ -f "$HOME/.android/tt-release.properties" ]] || die "找不到 ~/.android/tt-release.properties。"

# AGENTS.md 指定 JDK；若呼叫者沒有設定，採用 repo 的標準位置。
export JAVA_HOME="${JAVA_HOME:-/opt/android-studio/jbr}"
[[ -x "$JAVA_HOME/bin/java" ]] || die "JAVA_HOME 無效：$JAVA_HOME"
export PATH="$JAVA_HOME/bin:$PATH"
command -v jarsigner >/dev/null || die "找不到 jarsigner；請設定 JAVA_HOME 到 Android Studio 的 JBR。"

read_ver()  { sed -n 's/.*versionName *= *"\([^"]*\)".*/\1/p' "$GRADLE_FILE" | head -1; }
read_code() { sed -n 's/.*versionCode *= *\([0-9][0-9]*\).*/\1/p' "$GRADLE_FILE" | head -1; }

if (( ! NO_BUMP )); then
  CURRENT_VER="$(read_ver)"
  CURRENT_CODE="$(read_code)"
  [[ "$CURRENT_VER" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ && "$CURRENT_CODE" =~ ^[0-9]+$ ]] \
    || die "讀不到 x.y.z 版本號或 versionCode。"
  IFS=. read -r MAJOR MINOR PATCH <<<"$CURRENT_VER"
  NEXT_VER="$MAJOR.$MINOR.$((PATCH + 1))"
  NEXT_CODE=$((CURRENT_CODE + 1))
  perl -0pi -e \
    "s/versionCode = $CURRENT_CODE\\n        versionName = \\\"\\Q$CURRENT_VER\\E\\\"/versionCode = $NEXT_CODE\\n        versionName = \\\"$NEXT_VER\\\"/" \
    "$GRADLE_FILE"
  [[ "$(read_ver)" == "$NEXT_VER" && "$(read_code)" == "$NEXT_CODE" ]] \
    || die "無法更新 $GRADLE_FILE 的版本號。"
  ok "版本 $CURRENT_VER ($CURRENT_CODE) → $NEXT_VER ($NEXT_CODE)"
fi

VERSION="$(read_ver)"
VERSION_CODE="$(read_code)"
[[ -n "$VERSION" && -n "$VERSION_CODE" ]] || die "讀不到版本號。"

info "執行 JVM unit tests …"
./gradlew --console=plain :app:testDebugUnitTest

info "建立 Play upload key 簽署的 AAB（$VERSION / $VERSION_CODE）…"
./gradlew --console=plain :app:bundleRelease -Ptt.upload

SOURCE_AAB="app/build/outputs/bundle/release/app-release.aab"
[[ -f "$SOURCE_AAB" ]] || die "build 完找不到 $SOURCE_AAB。"

mkdir -p "$OUTPUT_DIR"
OUTPUT_AAB="$OUTPUT_DIR/threethree-$VERSION-$VERSION_CODE-upload.aab"
cp -f "$SOURCE_AAB" "$OUTPUT_AAB"

# Gradle 已因 -Ptt.upload 強制使用 upload key；這裡再確認 AAB 的 JAR 簽名可驗證。
# upload key 是 self-signed，故不能加 -strict（它會將沒有 CA chain 視為失敗）。
jarsigner -verify "$OUTPUT_AAB" >/dev/null \
  || die "AAB 簽名驗證失敗。"

SHA256="$(sha256sum "$OUTPUT_AAB" | awk '{print $1}')"
ok "AAB 已建立：$OUTPUT_AAB"
ok "SHA-256：$SHA256"
printf '\n下一步：到 Play Console 的 Closed testing → Create new release，上載上面這個 .aab。\n'
