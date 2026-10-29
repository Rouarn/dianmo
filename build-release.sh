#!/usr/bin/env bash
# 点墨（DianMo）一键打包正式版
# 用法：./build-release.sh [--skip-scanner] [--aab]
set -euo pipefail

ROOT=$(cd "$(dirname "$0")" && pwd)
cd "$ROOT"

SKIP_SCANNER=0
BUILD_AAB=0
for arg in "$@"; do
  case "$arg" in
    --skip-scanner) SKIP_SCANNER=1 ;;
    --aab) BUILD_AAB=1 ;;
    *) echo "未知参数: $arg"; exit 1 ;;
  esac
done

echo "==> 检查环境"
if [ -z "${JAVA_HOME:-}" ]; then
  if [ -d "$HOME/.sdkman/candidates/java/17.0.19-zulu" ]; then
    export JAVA_HOME="$HOME/.sdkman/candidates/java/17.0.19-zulu"
  else
    echo "未找到 JAVA_HOME（需要 JDK 17）" >&2
    exit 1
  fi
fi
echo "    JAVA_HOME=$JAVA_HOME"

GRADLE=$(ls -d "$HOME"/.gradle/wrapper/dists/gradle-8.13-bin/*/gradle-8.13/bin/gradle 2>/dev/null | head -1)
if [ -z "$GRADLE" ]; then
  echo "未找到 gradle-8.13，请先运行过一次 ./gradlew" >&2
  exit 1
fi
echo "    GRADLE=$GRADLE"

echo "==> 检查签名配置"
if [ ! -f "keystore.properties" ]; then
  echo "缺少 keystore.properties（签名密码文件），无法打正式版" >&2
  echo "参考格式：" >&2
  echo "  storeFile=../app/dianmo-release.jks" >&2
  echo "  storePassword=你的密码" >&2
  echo "  keyAlias=dianmo" >&2
  echo "  keyPassword=你的密码" >&2
  exit 1
fi
if [ ! -f "app/dianmo-release.jks" ]; then
  echo "缺少签名文件 app/dianmo-release.jks" >&2
  exit 1
fi
echo "    签名配置就绪"

if [ "$SKIP_SCANNER" -eq 0 ]; then
  echo "==> 编译 native 扫描器（arm64-v8a + armeabi-v7a）"
  bash scripts/build-scanner.sh
else
  echo "==> 跳过 native 扫描器编译"
fi

echo "==> 读取版本号"
VERSION_NAME=$(grep -E 'versionName\s*=' app/build.gradle.kts | head -1 | sed -E 's/.*"(.*)".*/\1/')
VERSION_CODE=$(grep -E 'versionCode\s*=' app/build.gradle.kts | head -1 | sed -E 's/[^0-9]*([0-9]+).*/\1/')
echo "    versionName=$VERSION_NAME  versionCode=$VERSION_CODE"

if [ "$BUILD_AAB" -eq 1 ]; then
  echo "==> 构建 release AAB"
  "$GRADLE" :app:bundleRelease --no-daemon --console=plain
  OUT="app/build/outputs/bundle/release/app-release.aab"
else
  echo "==> 构建 release APK"
  "$GRADLE" :app:assembleRelease --no-daemon --console=plain
  OUT="app/build/outputs/apk/release/app-release.apk"
fi

if [ ! -f "$OUT" ]; then
  echo "构建产物不存在：$OUT" >&2
  exit 1
fi

echo "==> 校验签名"
BT=$(ls -d "$HOME"/Library/Android/sdk/build-tools/*/ 2>/dev/null | tail -1)
if [ -n "$BT" ] && [ -x "${BT}apksigner" ] && [ "$BUILD_AAB" -eq 0 ]; then
  "${BT}apksigner" verify --print-certs "$OUT" | head -4
fi

SIZE=$(du -h "$OUT" | cut -f1)
echo ""
echo "=========================================="
echo " 构建完成"
echo " 版本：$VERSION_NAME ($VERSION_CODE)"
echo " 产物：$OUT"
echo " 大小：$SIZE"
echo "=========================================="
