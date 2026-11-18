#!/bin/bash
# 构建 点墨 release APK → 上传并发布到 VaKao 软件中心
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CLI="${HUB_CLI:-/Users/woyuxiaji/Rouarn/AliyunCloud/vakao-hub/tools/hub-cli.mjs}"
VERSION=$(grep -oE 'versionName = "[^"]+"' "$ROOT/app/build.gradle.kts" | head -1 | sed 's/.*"\(.*\)"/\1/')
CODE=$(grep -oE 'versionCode = [0-9]+' "$ROOT/app/build.gradle.kts" | head -1 | grep -oE '[0-9]+')
APK="$ROOT/app/build/outputs/apk/release/app-release.apk"
if [ "${1:-}" != "--no-build" ]; then
  export JAVA_HOME="$(/usr/libexec/java_home -v 17 2>/dev/null || /usr/libexec/java_home)"
  export ANDROID_HOME="$HOME/Library/Android/sdk"
  ( cd "$ROOT" && ./gradlew :app:assembleRelease --no-daemon )
fi
[ -f "$APK" ] || { echo "找不到产物 $APK" >&2; exit 1; }
exec node "$CLI" release --app dianmo --version "$VERSION" --code "$CODE" \
  --file "$APK" --platform android --arch universal --format apk \
  --notes "点墨 $VERSION" "$@"
