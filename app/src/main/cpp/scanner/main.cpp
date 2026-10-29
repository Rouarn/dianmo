#include <algorithm>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#include "common.h"
#include "fuzzy.h"
#include "maps_parser.h"
#include "pointer.h"

int detect_ptr_size(pid_t pid);
#include "mem_access.h"
#include "scan_engine.h"

namespace {

constexpr size_t kDefaultMax = 20000;

int usage() {
    fprintf(stderr,
            "usage:\n"
            "  memtool-scanner version\n"
            "  memtool-scanner maps <pid>\n"
            "  memtool-scanner scan <pid> <type> <value> [max]\n"
            "  memtool-scanner filter <pid> <type> <value> <addr-file>\n"
            "  memtool-scanner read <pid> <type> <addr>\n"
            "  memtool-scanner write <pid> <type> <addr> <value>\n"
            "type: i32 | i64 | f32 | f64\n");
    return 1;
}

bool require_scan_type(int argc, char** argv, ScanType& type) {
    if (argc < 4) return false;
    return parse_scan_type(argv[3], type);
}

std::vector<uintptr_t> read_address_file(const char* path) {
    std::vector<uintptr_t> out;
    FILE* fp = fopen(path, "r");
    if (fp == nullptr) return out;

    char line[128];
    while (fgets(line, sizeof(line), fp) != nullptr) {
        char* end = nullptr;
        unsigned long long v = strtoull(line, &end, 10);
        if (end != line && v != 0) {
            out.push_back(static_cast<uintptr_t>(v));
        }
    }
    fclose(fp);
    return out;
}

void print_hits(const MemoryAccess& mem, ScanType type,
                const std::vector<uintptr_t>& hits) {
    const size_t size = scan_type_size(type);
    uint8_t data[8];
    for (uintptr_t addr : hits) {
        if (mem.read(addr, data, size) == static_cast<ssize_t>(size)) {
            printf("0x%zx %s\n", static_cast<size_t>(addr),
                   format_value(type, data).c_str());
        }
    }
}

}  // namespace

int main(int argc, char** argv) {
    if (argc < 2) return usage();

    const std::string cmd = argv[1];

    if (cmd == "version") {
        printf("memtool-scanner 0.1.0\n");
        return 0;
    }

    if (cmd == "maps") {
        if (argc < 3) return usage();
        pid_t pid = static_cast<pid_t>(atoi(argv[2]));
        auto regions = parse_maps(pid);
        for (const auto& r : regions) {
            printf("%lx-%lx %c%c%c%c %s\n",
                   static_cast<unsigned long>(r.start),
                   static_cast<unsigned long>(r.end),
                   r.perms[0], r.perms[1], r.perms[2], r.perms[3],
                   r.path.c_str());
        }
        return 0;
    }

    if (cmd == "scan") {
        if (argc < 5) return usage();
        pid_t pid = static_cast<pid_t>(atoi(argv[2]));
        ScanType type;
        if (!require_scan_type(argc, argv, type)) {
            fprintf(stderr, "ERR bad type\n");
            return 2;
        }

        std::vector<uint8_t> target;
        if (!parse_value(type, argv[4], target)) {
            fprintf(stderr, "ERR bad value\n");
            return 2;
        }

        size_t max_results = kDefaultMax;
        if (argc > 5) {
            max_results = static_cast<size_t>(strtoul(argv[5], nullptr, 10));
            if (max_results == 0) max_results = kDefaultMax;
        }

        MemoryAccess mem;
        if (!mem.open(pid)) {
            fprintf(stderr, "ERR open process %d failed\n", static_cast<int>(pid));
            return 3;
        }

        auto regions = parse_maps(pid);
        auto hits = first_scan(mem, regions, type, target, max_results);
        print_hits(mem, type, hits);
        return 0;
    }

    if (cmd == "filter") {
        if (argc < 6) return usage();
        pid_t pid = static_cast<pid_t>(atoi(argv[2]));
        ScanType type;
        if (!require_scan_type(argc, argv, type)) {
            fprintf(stderr, "ERR bad type\n");
            return 2;
        }

        std::vector<uint8_t> target;
        if (!parse_value(type, argv[4], target)) {
            fprintf(stderr, "ERR bad value\n");
            return 2;
        }

        auto candidates = read_address_file(argv[5]);
        if (candidates.empty()) {
            fprintf(stderr, "ERR empty address file\n");
            return 2;
        }

        MemoryAccess mem;
        if (!mem.open(pid)) {
            fprintf(stderr, "ERR open process %d failed\n", static_cast<int>(pid));
            return 3;
        }

        auto hits = filter_scan(mem, type, target, candidates);
        print_hits(mem, type, hits);
        return 0;
    }

    if (cmd == "fuzzy-init") {
        if (argc < 6) return usage();
        pid_t pid = static_cast<pid_t>(atoi(argv[2]));
        ScanType type;
        if (!parse_scan_type(argv[3], type)) {
            fprintf(stderr, "ERR bad type\n");
            return 2;
        }
        size_t max_bytes = static_cast<size_t>(strtoull(argv[4], nullptr, 10));
        if (max_bytes == 0) max_bytes = 32u * 1024u * 1024u;
        const char* snapshot = argv[5];

        MemoryAccess mem;
        if (!mem.open(pid)) {
            fprintf(stderr, "ERR open process %d failed\n", static_cast<int>(pid));
            return 3;
        }
        auto regions = parse_maps(pid);
        const long long n = fuzzy_init(mem, regions, type, max_bytes, snapshot);
        if (n < 0) {
            fprintf(stderr, "ERR fuzzy init failed\n");
            return 4;
        }
        printf("OK %lld\n", n);
        return 0;
    }

    if (cmd == "fuzzy-filter") {
        if (argc < 6) return usage();
        pid_t pid = static_cast<pid_t>(atoi(argv[2]));
        ScanType type;
        if (!parse_scan_type(argv[3], type)) {
            fprintf(stderr, "ERR bad type\n");
            return 2;
        }
        FuzzyMode mode;
        if (!parse_fuzzy_mode(argv[4], mode)) {
            fprintf(stderr, "ERR bad mode\n");
            return 2;
        }
        const char* snapshot = argv[5];
        size_t max_results = kDefaultMax;
        if (argc > 6) {
            max_results = static_cast<size_t>(strtoul(argv[6], nullptr, 10));
            if (max_results == 0) max_results = kDefaultMax;
        }

        MemoryAccess mem;
        if (!mem.open(pid)) {
            fprintf(stderr, "ERR open process %d failed\n", static_cast<int>(pid));
            return 3;
        }
        const char* out_snapshot = (argc > 7) ? argv[7] : nullptr;
        auto hits = fuzzy_filter(mem, type, mode, snapshot, max_results, out_snapshot);
        print_hits(mem, type, hits);
        return 0;
    }

    if (cmd == "findptr") {
        if (argc < 4) return usage();
        pid_t pid = static_cast<pid_t>(atoi(argv[2]));
        const uintptr_t target = static_cast<uintptr_t>(strtoull(argv[3], nullptr, 16));
        size_t max_results = kDefaultMax;
        if (argc > 4) {
            max_results = static_cast<size_t>(strtoul(argv[4], nullptr, 10));
            if (max_results == 0) max_results = kDefaultMax;
        }

        MemoryAccess mem;
        if (!mem.open(pid)) {
            fprintf(stderr, "ERR open process %d failed\n", static_cast<int>(pid));
            return 3;
        }
        auto regions = parse_maps(pid);
        uintptr_t range = 0x2000;
        if (argc > 5) {
            const unsigned long long r = strtoull(argv[5], nullptr, 16);
            if (r != 0) range = static_cast<uintptr_t>(r);
        }
        const int ptr_size = detect_ptr_size(pid);
        auto ptrs = find_pointers(mem, regions, target, range, max_results,
                                  static_cast<size_t>(ptr_size));
        for (const auto& p : ptrs) {
            printf("0x%zx %zx\n", static_cast<size_t>(p.ptr_addr),
                   static_cast<size_t>(p.offset));
        }
        return 0;
    }

    if (cmd == "chain") {
        if (argc < 4) return usage();
        pid_t pid = static_cast<pid_t>(atoi(argv[2]));
        const uintptr_t target = static_cast<uintptr_t>(strtoull(argv[3], nullptr, 16));
        size_t max_depth = 8;
        size_t max_nodes = 80;
        if (argc > 4) {
            const unsigned long d = strtoul(argv[4], nullptr, 10);
            if (d != 0) max_depth = static_cast<size_t>(d);
        }
        if (argc > 5) {
            const unsigned long n = strtoul(argv[5], nullptr, 10);
            if (n != 0) max_nodes = static_cast<size_t>(n);
        }

        MemoryAccess mem;
        if (!mem.open(pid)) {
            fprintf(stderr, "ERR open process %d failed\n", static_cast<int>(pid));
            return 3;
        }
        auto regions = parse_maps(pid);
        const int ptr_size = detect_ptr_size(pid);
        const auto r = find_chain(mem, regions, target, max_depth, max_nodes,
                                  static_cast<size_t>(ptr_size));
        if (!r.found) {
            printf("NOFOUND\n");
            return 0;
        }
        printf("FOUND %s\n", r.base_module.c_str());
        for (const auto& p : r.chain) {
            printf("0x%zx %zx\n", static_cast<size_t>(p.ptr_addr),
                   static_cast<size_t>(p.offset));
        }
        return 0;
    }

    if (cmd == "read") {
        if (argc < 5) return usage();
        pid_t pid = static_cast<pid_t>(atoi(argv[2]));
        ScanType type;
        if (!require_scan_type(argc, argv, type)) {
            fprintf(stderr, "ERR bad type\n");
            return 2;
        }

        uintptr_t addr = static_cast<uintptr_t>(strtoull(argv[4], nullptr, 16));

        MemoryAccess mem;
        if (!mem.open(pid)) {
            fprintf(stderr, "ERR open process %d failed\n", static_cast<int>(pid));
            return 3;
        }

        uint8_t data[8];
        const size_t size = scan_type_size(type);
        if (mem.read(addr, data, size) != static_cast<ssize_t>(size)) {
            fprintf(stderr, "ERR read failed\n");
            return 4;
        }
        printf("0x%zx %s\n", static_cast<size_t>(addr),
               format_value(type, data).c_str());
        return 0;
    }

    if (cmd == "write") {
        if (argc < 6) return usage();
        pid_t pid = static_cast<pid_t>(atoi(argv[2]));
        ScanType type;
        if (!require_scan_type(argc, argv, type)) {
            fprintf(stderr, "ERR bad type\n");
            return 2;
        }

        uintptr_t addr = static_cast<uintptr_t>(strtoull(argv[4], nullptr, 16));
        std::vector<uint8_t> value;
        if (!parse_value(type, argv[5], value)) {
            fprintf(stderr, "ERR bad value\n");
            return 2;
        }

        MemoryAccess mem;
        if (!mem.open(pid)) {
            fprintf(stderr, "ERR open process %d failed\n", static_cast<int>(pid));
            return 3;
        }

        if (mem.write(addr, value.data(), value.size()) !=
            static_cast<ssize_t>(value.size())) {
            fprintf(stderr, "ERR write failed\n");
            return 4;
        }
        printf("OK\n");
        return 0;
    }

    if (cmd == "writeall") {
        if (argc < 6) return usage();
        pid_t pid = static_cast<pid_t>(atoi(argv[2]));
        ScanType type;
        if (!require_scan_type(argc, argv, type)) {
            fprintf(stderr, "ERR bad type\n");
            return 2;
        }

        std::vector<uint8_t> value;
        if (!parse_value(type, argv[4], value)) {
            fprintf(stderr, "ERR bad value\n");
            return 2;
        }

        auto candidates = read_address_file(argv[5]);
        if (candidates.empty()) {
            fprintf(stderr, "ERR empty address file\n");
            return 2;
        }

        MemoryAccess mem;
        if (!mem.open(pid)) {
            fprintf(stderr, "ERR open process %d failed\n", static_cast<int>(pid));
            return 3;
        }

        size_t ok_count = 0;
        for (uintptr_t addr : candidates) {
            if (mem.write(addr, value.data(), value.size()) ==
                static_cast<ssize_t>(value.size())) {
                ok_count++;
            }
        }
        printf("OK %zu/%zu\n", ok_count, candidates.size());
        return 0;
    }

    if (cmd == "readall") {
        if (argc < 5) return usage();
        pid_t pid = static_cast<pid_t>(atoi(argv[2]));
        ScanType type;
        if (!require_scan_type(argc, argv, type)) {
            fprintf(stderr, "ERR bad type\n");
            return 2;
        }

        auto candidates = read_address_file(argv[4]);
        if (candidates.empty()) {
            fprintf(stderr, "ERR empty address file\n");
            return 2;
        }

        MemoryAccess mem;
        if (!mem.open(pid)) {
            fprintf(stderr, "ERR open process %d failed\n", static_cast<int>(pid));
            return 3;
        }

        // 按 4KB 页分组批量读，避免逐个地址一次 syscall。
        // Java 堆地址密集时，能把几万次读取压到几百次。
        std::sort(candidates.begin(), candidates.end());

        const size_t kPage = 4096;
        std::vector<uint8_t> buffer(kPage + 16);
        const size_t size = scan_type_size(type);

        size_t i = 0;
        while (i < candidates.size()) {
            const uintptr_t page_start = candidates[i] & ~static_cast<uintptr_t>(kPage - 1);
            size_t j = i;
            while (j < candidates.size() && candidates[j] < page_start + kPage) {
                j++;
            }

            ssize_t got = mem.read(page_start, buffer.data(), kPage + 16);
            if (got > 0) {
                for (size_t k = i; k < j; k++) {
                    const size_t off = static_cast<size_t>(candidates[k] - page_start);
                    if (off + size <= static_cast<size_t>(got)) {
                        printf("0x%zx %s\n", static_cast<size_t>(candidates[k]),
                               format_value(type, buffer.data() + off).c_str());
                    }
                }
            }
            i = j;
        }
        return 0;
    }

    return usage();
}
