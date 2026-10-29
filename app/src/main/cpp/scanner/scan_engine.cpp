#include "scan_engine.h"

#include <algorithm>
#include <cstring>

std::vector<uintptr_t> first_scan(
    const MemoryAccess& mem,
    const std::vector<MapRegion>& regions,
    ScanType type,
    const std::vector<uint8_t>& target,
    size_t max_results
) {
    std::vector<uintptr_t> hits;
    if (target.empty()) return hits;

    constexpr size_t kChunk = 4 * 1024 * 1024;
    const size_t size = target.size();
    const size_t align = scan_type_align(type);

    std::vector<uint8_t> buffer(kChunk);

    for (const auto& region : regions) {
        if (!region.scannable()) continue;

        uintptr_t addr = region.start;
        while (addr < region.end && hits.size() < max_results) {
            size_t want = std::min<size_t>(kChunk, region.end - addr);
            ssize_t got = mem.read(addr, buffer.data(), want);
            if (got <= 0) {
                addr += want;
                continue;
            }

            const size_t n = static_cast<size_t>(got);
            for (size_t i = 0; i + size <= n; i += align) {
                if (memcmp(buffer.data() + i, target.data(), size) == 0) {
                    hits.push_back(addr + i);
                    if (hits.size() >= max_results) break;
                }
            }
            addr += n;
        }

        if (hits.size() >= max_results) break;
    }

    return hits;
}

std::vector<uintptr_t> filter_scan(
    const MemoryAccess& mem,
    ScanType type,
    const std::vector<uint8_t>& target,
    const std::vector<uintptr_t>& candidates
) {
    std::vector<uintptr_t> hits;
    (void)type;
    const size_t size = target.size();
    if (size == 0 || size > 8) return hits;

    uint8_t data[8];
    for (uintptr_t addr : candidates) {
        if (mem.read(addr, data, size) == static_cast<ssize_t>(size)) {
            if (memcmp(data, target.data(), size) == 0) {
                hits.push_back(addr);
            }
        }
    }
    return hits;
}
