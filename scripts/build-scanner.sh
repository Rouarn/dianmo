#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
NDK="${ANDROID_NDK_HOME:-$HOME/Library/Android/sdk/ndk/26.3.11579264}"

if [ ! -d "$NDK" ]; then
  echo "未找到 NDK: $NDK" >&2
  echo "请设置 ANDROID_NDK_HOME，或在 Android SDK 中安装 NDK 26.3.11579264" >&2
  exit 1
fi

TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin"
SRC="$ROOT/app/src/main/cpp/scanner"
OUT="$ROOT/app/src/main/assets/scanner"

SOURCES=(
  "$SRC/main.cpp"
  "$SRC/common.cpp"
  "$SRC/maps_parser.cpp"
  "$SRC/mem_access.cpp"
  "$SRC/scan_engine.cpp"
  "$SRC/fuzzy.cpp"
  "$SRC/pointer.cpp"
)

build() {
  local abi="$1" api="$2" triple="$3"
  local dest="$OUT/$abi"
  mkdir -p "$dest"
  echo "==> $abi"
  "$TOOLCHAIN/${triple}${api}-clang++" \
    -std=c++17 -O2 -Wall -Wextra \
    -fPIE -pie \
    -o "$dest/memtool-scanner" \
    "${SOURCES[@]}"
  if [ -x "$TOOLCHAIN/llvm-strip" ]; then
    "$TOOLCHAIN/llvm-strip" --strip-all "$dest/memtool-scanner" || true
  fi
  ls -lh "$dest/memtool-scanner"
}

build arm64-v8a 26 aarch64-linux-android
build armeabi-v7a 26 armv7a-linux-androideabi

echo "扫描器构建完成: $OUT"
