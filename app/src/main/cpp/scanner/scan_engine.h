#pragma once

#include <cstdint>
#include <vector>

#include "common.h"
#include "maps_parser.h"
#include "mem_access.h"

/// 首次扫描：遍历所有可扫描区域，按目标字节序列查找。
std::vector<uintptr_t> first_scan(
    const MemoryAccess& mem,
    const std::vector<MapRegion>& regions,
    ScanType type,
    const std::vector<uint8_t>& target,
    size_t max_results
);

/// 再次扫描：只在给定候选地址上重新比较。
std::vector<uintptr_t> filter_scan(
    const MemoryAccess& mem,
    ScanType type,
    const std::vector<uint8_t>& target,
    const std::vector<uintptr_t>& candidates
);
