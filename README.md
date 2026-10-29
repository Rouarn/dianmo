# 点墨（DianMo）

Android 单机游戏内存修改器（学习项目）。仅用于**本人拥有的离线单机游戏**，请勿用于联机游戏、内购绕过或任何商业作弊场景。

## 它做什么

- 以 root 权限读取目标游戏进程的内存
- 按数值类型（Int32 / Int64 / Float / Double）扫描
- 二次扫描逐步收敛到唯一地址
- 改写数值（金币、弹药、血量等）

## 架构

```
┌────────────────────────────────────────────┐
│  UI (Kotlin + Material3)                    │
│   进程选择 / 类型选择 / 扫描 / 结果列表      │
├────────────────────────────────────────────┤
│  ScannerCli (Kotlin)                        │
│   把 native 扫描器部署到 /data/local/tmp    │
│   通过 libsu 的 `su -c` 调用它              │
├────────────────────────────────────────────┤
│  memtool-scanner (C++ 独立可执行文件)        │
│   process_vm_readv/writev → /proc/pid/mem   │
│   maps 解析 / 首扫 / 过滤 / 读写            │
└────────────────────────────────────────────┘
```

关键点：**App 进程本身没有 root**。所有内存访问都由 `su` 拉起的
`memtool-scanner` 子进程完成，App 只负责参数和结果显示。

## 目录结构

```
DianMo/
├── app/src/main/
│   ├── java/com/woyuxiaji/memtool/
│   │   ├── MainActivity.kt          # 主界面与扫描流程
│   │   ├── core/RootShell.kt        # libsu 封装
│   │   ├── core/ScannerCli.kt       # 与 native 扫描器通信
│   │   ├── model/                   # ScanType / ScanHit / ProcessInfo
│   │   ├── ui/                      # RecyclerView 适配器
│   │   └── util/ProcUtils.kt        # 进程列表
│   ├── cpp/scanner/                 # native 扫描器源码
│   │   ├── main.cpp                 # CLI 入口
│   │   ├── mem_access.cpp           # 进程内存读写
│   │   ├── maps_parser.cpp          # /proc/pid/maps 解析
│   │   ├── scan_engine.cpp          # 首扫 / 过滤
│   │   └── common.cpp               # 数值解析与格式化
│   ├── assets/scanner/<abi>/        # 构建产物（由脚本生成）
│   └── res/
├── scripts/build-scanner.sh         # 编译 native 扫描器
└── README.md
```

## 构建步骤

### 0. 环境要求

- JDK 17
- Android SDK（compileSdk 35、build-tools 35）
- Android NDK 26.3.11579264（`$HOME/Library/Android/sdk/ndk/26.3.11579264`）

### 1. 编译 native 扫描器

```bash
./scripts/build-scanner.sh
```

产物会写入 `app/src/main/assets/scanner/<abi>/memtool-scanner`。

### 2. 构建 APK

```bash
./gradlew :app:assembleDebug
```

输出：`app/build/outputs/apk/debug/app-debug.apk`

### 3. 安装

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 使用流程

1. 打开 App，等待右上角显示「Root: 已授权」
2. 点「选择目标进程」，选游戏进程
3. 选择数据类型（金币一般是 Int32，血量可能是 Float）
4. 输入游戏里当前显示的数值，点「首次扫描」
5. 在游戏里让数值变化（花掉金币 / 受一次伤害）
6. 输入新数值，点「再次扫描」，重复直到只剩少数地址
7. 点结果里的「改写」，输入目标值

## 常见问题

- **扫描器部署失败**：确认 root 已授权，且 `/data/local/tmp` 可写
- **扫描结果为空**：换个类型（int/float）再试，或者数值在 Java 堆里（会被 GC 移动，外部扫描不稳定）
- **改写后游戏崩溃**：可能改到了引擎关键数据，重启游戏即可，只改明确的数值字段

## 协议（native scanner CLI）

```
memtool-scanner version
memtool-scanner maps   <pid>
memtool-scanner scan   <pid> <type> <value> [max]     -> 每行 "0xADDR VALUE"
memtool-scanner filter <pid> <type> <value> <file>    -> 每行 "0xADDR VALUE"
memtool-scanner read   <pid> <type> <addr>
memtool-scanner write  <pid> <type> <addr> <value>    -> "OK"
```

type: `i32` / `i64` / `f32` / `f64`

## 合规声明

本项目仅用于学习 Android 内存模型与逆向工程基础。请只在你拥有的、离线的单机游戏上使用；不要用于联机游戏、绕过付费或破坏他人服务。
