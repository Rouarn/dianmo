#pragma once

#include <cstdint>
#include <vector>

#include "common.h"
#include "maps_parser.h"
#include "mem_access.h"

enum class FuzzyMode { Bigger, Smaller, Unchanged, Changed };

bool parse_fuzzy_mode(const char* s, FuzzyMode& out);

/// 扫描可写区域并保存快照；返回记录数，失败返回 -1。
long long fuzzy_init(const MemoryAccess& mem,
                     const std::vector<MapRegion>& regions,
                     ScanType type,
                     size_t max_bytes,
                     const char* path);

/// 读取快照并与当前内存比较，返回命中地址。
/// out_path 非空时，把命中项（地址 + 当前值）写成新快照，供下一轮继续收敛。
std::vector<uintptr_t> fuzzy_filter(const MemoryAccess& mem,
                                    ScanType type,
                                    FuzzyMode mode,
                                    const char* path,
                                    size_t max_results,
                                    const char* out_path);
