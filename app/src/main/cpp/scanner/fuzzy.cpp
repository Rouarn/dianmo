#include "fuzzy.h"

#include <algorithm>
#include <cstdio>
#include <cstring>

namespace {

struct SnapshotHeader {
    char magic[4];       // "MTSF"
    uint32_t type;       // ScanType
    uint32_t value_size; // 4 or 8
    uint64_t count;
};

constexpr size_t kChunk = 4 * 1024 * 1024;

int compare_values(ScanType type, const uint8_t* a, const uint8_t* b) {
    switch (type) {
        case ScanType::I32: {
            int32_t x = 0, y = 0;
            memcpy(&x, a, 4);
            memcpy(&y, b, 4);
            return (x > y) - (x < y);
        }
        case ScanType::I64: {
            int64_t x = 0, y = 0;
            memcpy(&x, a, 8);
            memcpy(&y, b, 8);
            return (x > y) - (x < y);
        }
        case ScanType::F32: {
            float x = 0, y = 0;
            memcpy(&x, a, 4);
            memcpy(&y, b, 4);
            return (x > y) - (x < y);
        }
        case ScanType::F64: {
            double x = 0, y = 0;
            memcpy(&x, a, 8);
            memcpy(&y, b, 8);
            return (x > y) - (x < y);
        }
    }
    return 0;
}

bool match(FuzzyMode mode, int cmp) {
    switch (mode) {
        case FuzzyMode::Bigger: return cmp > 0;
        case FuzzyMode::Smaller: return cmp < 0;
        case FuzzyMode::Unchanged: return cmp == 0;
        case FuzzyMode::Changed: return cmp != 0;
    }
    return false;
}

}  // namespace

bool parse_fuzzy_mode(const char* s, FuzzyMode& out) {
    if (s == nullptr) return false;
    if (strcmp(s, "bigger") == 0) {
        out = FuzzyMode::Bigger;
        return true;
    }
    if (strcmp(s, "smaller") == 0) {
        out = FuzzyMode::Smaller;
        return true;
    }
    if (strcmp(s, "unchanged") == 0) {
        out = FuzzyMode::Unchanged;
        return true;
    }
    if (strcmp(s, "changed") == 0) {
        out = FuzzyMode::Changed;
        return true;
    }
    return false;
}

long long fuzzy_init(const MemoryAccess& mem,
                     const std::vector<MapRegion>& regions,
                     ScanType type,
                     size_t max_bytes,
                     const char* path) {
    const size_t value_size = scan_type_size(type);
    if (value_size == 0 || value_size > 8) return -1;

    FILE* fp = fopen(path, "wb");
    if (fp == nullptr) return -1;

    SnapshotHeader header{};
    memcpy(header.magic, "MTSF", 4);
    header.type = static_cast<uint32_t>(type);
    header.value_size = static_cast<uint32_t>(value_size);
    header.count = 0;
    fwrite(&header, sizeof(header), 1, fp);

    std::vector<uint8_t> buffer(kChunk + 16);
    size_t used = 0;
    uint64_t count = 0;

    for (const auto& region : regions) {
        if (!region.scannable()) continue;
        if (used >= max_bytes) break;

        uintptr_t addr = region.start;
        while (addr < region.end && used < max_bytes) {
            size_t chunk = std::min(kChunk, static_cast<size_t>(region.end - addr));
            chunk = std::min(chunk, max_bytes - used);
            if (chunk == 0) break;

            const ssize_t got = mem.read(addr, buffer.data(), chunk);
            if (got > 0) {
                const size_t step = value_size;
                for (size_t off = 0; off + value_size <= static_cast<size_t>(got); off += step) {
                    const uintptr_t a = addr + off;
                    fwrite(&a, sizeof(a), 1, fp);
                    fwrite(buffer.data() + off, value_size, 1, fp);
                    count++;
                }
            }
            addr += chunk;
            used += chunk;
        }
    }

    header.count = count;
    fseek(fp, 0, SEEK_SET);
    fwrite(&header, sizeof(header), 1, fp);
    fclose(fp);
    return static_cast<long long>(count);
}

std::vector<uintptr_t> fuzzy_filter(const MemoryAccess& mem,
                                    ScanType type,
                                    FuzzyMode mode,
                                    const char* path,
                                    size_t max_results,
                                    const char* out_path) {
    std::vector<uintptr_t> out;
    FILE* out_fp = (out_path != nullptr && out_path[0] != '\0') ? fopen(out_path, "wb") : nullptr;
    uint64_t kept = 0;
    FILE* fp = fopen(path, "rb");
    if (fp == nullptr) return out;

    SnapshotHeader header{};
    if (fread(&header, sizeof(header), 1, fp) != 1) {
        fclose(fp);
        return out;
    }
    if (memcmp(header.magic, "MTSF", 4) != 0 || header.value_size == 0 ||
        header.value_size > 8) {
        fclose(fp);
        return out;
    }

    const size_t value_size = header.value_size;
    uint8_t old_v[8] = {};
    uint8_t new_v[8] = {};

    if (out_fp != nullptr) {
        SnapshotHeader nh{};
        memcpy(nh.magic, "MTSF", 4);
        nh.type = header.type;
        nh.value_size = header.value_size;
        nh.count = 0;
        fwrite(&nh, sizeof(nh), 1, out_fp);
    }

    for (uint64_t i = 0; i < header.count && out.size() < max_results; i++) {
        uintptr_t a = 0;
        if (fread(&a, sizeof(a), 1, fp) != 1) break;
        if (fread(old_v, value_size, 1, fp) != 1) break;
        if (mem.read(a, new_v, value_size) != static_cast<ssize_t>(value_size)) continue;
        if (match(mode, compare_values(type, old_v, new_v))) {
            out.push_back(a);
            if (out_fp != nullptr) {
                fwrite(&a, sizeof(a), 1, out_fp);
                fwrite(new_v, value_size, 1, out_fp);
                kept++;
            }
        }
    }
    fclose(fp);

    if (out_fp != nullptr) {
        SnapshotHeader nh{};
        memcpy(nh.magic, "MTSF", 4);
        nh.type = header.type;
        nh.value_size = header.value_size;
        nh.count = kept;
        fseek(out_fp, 0, SEEK_SET);
        fwrite(&nh, sizeof(nh), 1, out_fp);
        fclose(out_fp);
    }
    return out;
}
