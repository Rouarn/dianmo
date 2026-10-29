cmake_minimum_required(VERSION 3.22)
project(memtool-scanner CXX)

# 仅用于在 Android Studio 中查看/调试 scanner 源码。
# 实际构建由 scripts/build-scanner.sh 直接调用 NDK clang 完成。

add_executable(memtool-scanner
    scanner/main.cpp
    scanner/common.cpp
    scanner/maps_parser.cpp
    scanner/mem_access.cpp
    scanner/scan_engine.cpp
)

target_compile_features(memtool-scanner PRIVATE cxx_std_17)
target_compile_options(memtool-scanner PRIVATE -Wall -Wextra -O2 -fPIE -pie)
