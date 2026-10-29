#pragma once

#include <sys/types.h>

#include <cstdint>
#include <string>
#include <vector>

struct MapRegion {
    uintptr_t start = 0;
    uintptr_t end = 0;
    char perms[4] = {'-', '-', '-', '-'};
    std::string path;

    bool readable() const { return perms[0] == 'r'; }
    bool writable() const { return perms[1] == 'w'; }

    /// 匿名段（无文件路径，或 [anon:...] / [stack] / [heap] 等）
    bool anonymous() const {
        if (path.empty()) return true;
        return path[0] == '[';
    }

    /// 扫描时只关心可读写且非文件映射的段，避免误命中和拖慢扫描。
    bool scannable() const {
        if (!readable() || !writable()) return false;
        if (!path.empty() && path[0] == '/') return false;
        if (path == "[vvar]" || path == "[vdso]" || path == "[vsyscall]") return false;
        return true;
    }

    size_t length() const { return static_cast<size_t>(end - start); }
};

/// 解析 /proc/<pid>/maps
std::vector<MapRegion> parse_maps(pid_t pid);
