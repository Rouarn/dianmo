#pragma once

#include <cstdint>
#include <string>
#include <utility>
#include <vector>

#include "maps_parser.h"
#include "mem_access.h"

struct PointerHit {
    uintptr_t ptr_addr;  // 指针本身所在的位置
    uintptr_t offset;    // target - 指针值（目标在对象内的偏移）
};

struct ChainResult {
    bool found = false;
    std::vector<PointerHit> chain;  // 从目标往上，最后一层是模块内基址
    std::string base_module;        // 基址所在模块路径
};

/// 搜索内存中的指针（8/4 字节），允许指针值落在 [target - range, target]。
std::vector<PointerHit> find_pointers(const MemoryAccess& mem,
                                      const std::vector<MapRegion>& regions,
                                      uintptr_t target,
                                      uintptr_t range,
                                      size_t max_results,
                                      size_t ptr_size);

/// 广度优先搜索，从 target 往上找一条能落到模块内（文件映射区）的指针链。
ChainResult find_chain(const MemoryAccess& mem,
                       const std::vector<MapRegion>& regions,
                       uintptr_t target,
                       size_t max_depth,
                       size_t max_nodes,
                       size_t ptr_size);

/// 自动检测进程是 32 位还是 64 位（返回 4 或 8）。
int detect_ptr_size(pid_t pid);
