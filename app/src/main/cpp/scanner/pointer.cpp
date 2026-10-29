#include "pointer.h"

#include <fcntl.h>
#include <unistd.h>

#include <algorithm>
#include <cstdio>
#include <cstring>
#include <deque>

namespace {
constexpr size_t kChunk = 4 * 1024 * 1024;

bool in_module(uintptr_t addr, const std::vector<MapRegion>& regions, std::string* path) {
    for (const auto& r : regions) {
        if (addr < r.start || addr >= r.end) continue;
        if (!r.path.empty() && r.path[0] == '/') {
            if (path != nullptr) *path = r.path;
            return true;
        }
        return false;
    }
    return false;
}
}  // namespace

int detect_ptr_size(pid_t pid) {
    char path[64];
    snprintf(path, sizeof(path), "/proc/%d/exe", static_cast<int>(pid));
    const int fd = open(path, O_RDONLY);
    if (fd < 0) return 8;
    unsigned char ident[5] = {};
    const ssize_t n = read(fd, ident, 5);
    close(fd);
    if (n == 5 && ident[0] == 0x7f && ident[1] == 'E' && ident[2] == 'L' &&
        ident[3] == 'F') {
        return (ident[4] == 1) ? 4 : 8;
    }
    return 8;
}

std::vector<PointerHit> find_pointers(const MemoryAccess& mem,
                                      const std::vector<MapRegion>& regions,
                                      uintptr_t target,
                                      uintptr_t range,
                                      size_t max_results,
                                      size_t ptr_size) {
    std::vector<PointerHit> out;
    if (target == 0) return out;

    const uintptr_t low = (target > range) ? (target - range) : 0;
    const size_t step = (ptr_size == 4) ? 4 : 8;
    std::vector<uint8_t> buffer(kChunk + 16);

    for (const auto& region : regions) {
        if (!region.scannable()) continue;
        if (out.size() >= max_results) break;

        uintptr_t addr = region.start;
        while (addr < region.end && out.size() < max_results) {
            size_t chunk = std::min(kChunk, static_cast<size_t>(region.end - addr));
            const ssize_t got = mem.read(addr, buffer.data(), chunk);
            if (got > 0) {
                const size_t limit = static_cast<size_t>(got);
                for (size_t off = 0; off + step <= limit; off += step) {
                    uint64_t v = 0;
                    if (step == 4) {
                        uint32_t v32 = 0;
                        memcpy(&v32, buffer.data() + off, 4);
                        v = v32;
                    } else {
                        memcpy(&v, buffer.data() + off, 8);
                    }
                    if (v >= low && v <= target && v != 0) {
                        PointerHit hit;
                        hit.ptr_addr = addr + off;
                        hit.offset = static_cast<uintptr_t>(target - v);
                        out.push_back(hit);
                        if (out.size() >= max_results) break;
                    }
                }
            }
            addr += chunk;
        }
    }
    return out;
}

ChainResult find_chain(const MemoryAccess& mem,
                       const std::vector<MapRegion>& regions,
                       uintptr_t target,
                       size_t max_depth,
                       size_t max_nodes,
                       size_t ptr_size) {
    ChainResult result;

    struct Node {
        uintptr_t addr;
        std::vector<PointerHit> path;
    };

    std::deque<Node> queue;
    Node start;
    start.addr = target;
    queue.push_back(start);

    size_t visited = 0;
    while (!queue.empty() && visited < max_nodes) {
        Node cur = queue.front();
        queue.pop_front();
        visited++;

        if (cur.path.size() >= max_depth) continue;

        const auto ptrs = find_pointers(mem, regions, cur.addr, 0x2000, 3, ptr_size);
        for (const auto& p : ptrs) {
            std::vector<PointerHit> new_path = cur.path;
            new_path.push_back(p);

            std::string module;
            if (in_module(p.ptr_addr, regions, &module)) {
                result.found = true;
                result.chain = new_path;
                result.base_module = module;
                return result;
            }
            Node next;
            next.addr = p.ptr_addr;
            next.path = std::move(new_path);
            queue.push_back(std::move(next));
        }
    }
    return result;
}
