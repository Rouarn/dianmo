#include "maps_parser.h"

#include <cstdio>
#include <cstring>

std::vector<MapRegion> parse_maps(pid_t pid) {
    std::vector<MapRegion> regions;

    char path[64];
    snprintf(path, sizeof(path), "/proc/%d/maps", static_cast<int>(pid));

    FILE* fp = fopen(path, "r");
    if (fp == nullptr) return regions;

    char line[1024];
    while (fgets(line, sizeof(line), fp) != nullptr) {
        unsigned long start = 0;
        unsigned long end = 0;
        char perms[8] = {0};
        char tail[512] = {0};

        // start-end perms offset dev inode [path]
        // 前 5 个字段用 %*s 跳过，最后一段作为路径（可能不存在）
        int n = sscanf(
            line,
            "%lx-%lx %7s %*s %*s %*s %511[^\n]",
            &start, &end, perms, tail
        );
        if (n < 3) continue;

        MapRegion region;
        region.start = static_cast<uintptr_t>(start);
        region.end = static_cast<uintptr_t>(end);
        memcpy(region.perms, perms, 4);

        if (n == 4) {
            std::string p(tail);
            // 去掉尾部换行与首尾空白
            while (!p.empty() && (p.back() == '\n' || p.back() == '\r' || p.back() == ' ')) {
                p.pop_back();
            }
            size_t begin = p.find_first_not_of(" \t");
            if (begin != std::string::npos) {
                region.path = p.substr(begin);
            }
        }

        regions.push_back(std::move(region));
    }

    fclose(fp);
    return regions;
}
