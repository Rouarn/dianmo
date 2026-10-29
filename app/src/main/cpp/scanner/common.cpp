#include "common.h"

#include <cerrno>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <sstream>

bool parse_scan_type(const std::string& s, ScanType& out) {
    if (s == "i32" || s == "int32" || s == "int") {
        out = ScanType::I32;
        return true;
    }
    if (s == "i64" || s == "int64" || s == "long") {
        out = ScanType::I64;
        return true;
    }
    if (s == "f32" || s == "float") {
        out = ScanType::F32;
        return true;
    }
    if (s == "f64" || s == "double") {
        out = ScanType::F64;
        return true;
    }
    return false;
}

size_t scan_type_size(ScanType t) {
    switch (t) {
        case ScanType::I32:
        case ScanType::F32:
            return 4;
        case ScanType::I64:
        case ScanType::F64:
            return 8;
    }
    return 4;
}

size_t scan_type_align(ScanType t) {
    return scan_type_size(t);
}

const char* scan_type_name(ScanType t) {
    switch (t) {
        case ScanType::I32:
            return "i32";
        case ScanType::I64:
            return "i64";
        case ScanType::F32:
            return "f32";
        case ScanType::F64:
            return "f64";
    }
    return "i32";
}

bool parse_value(ScanType t, const std::string& s, std::vector<uint8_t>& out) {
    out.assign(scan_type_size(t), 0);

    if (t == ScanType::I32) {
        char* end = nullptr;
        errno = 0;
        long long v = strtoll(s.c_str(), &end, 0);
        if (errno != 0 || end == s.c_str()) return false;
        int32_t iv = static_cast<int32_t>(v);
        memcpy(out.data(), &iv, sizeof(iv));
        return true;
    }

    if (t == ScanType::I64) {
        char* end = nullptr;
        errno = 0;
        long long v = strtoll(s.c_str(), &end, 0);
        if (errno != 0 || end == s.c_str()) return false;
        memcpy(out.data(), &v, sizeof(v));
        return true;
    }

    if (t == ScanType::F32) {
        char* end = nullptr;
        errno = 0;
        float v = strtof(s.c_str(), &end);
        if (errno != 0 || end == s.c_str()) return false;
        memcpy(out.data(), &v, sizeof(v));
        return true;
    }

    if (t == ScanType::F64) {
        char* end = nullptr;
        errno = 0;
        double v = strtod(s.c_str(), &end);
        if (errno != 0 || end == s.c_str()) return false;
        memcpy(out.data(), &v, sizeof(v));
        return true;
    }

    return false;
}

std::string format_value(ScanType t, const uint8_t* data) {
    char buf[64];
    if (t == ScanType::I32) {
        int32_t v = 0;
        memcpy(&v, data, sizeof(v));
        snprintf(buf, sizeof(buf), "%d", v);
        return buf;
    }
    if (t == ScanType::I64) {
        int64_t v = 0;
        memcpy(&v, data, sizeof(v));
        snprintf(buf, sizeof(buf), "%lld", static_cast<long long>(v));
        return buf;
    }
    if (t == ScanType::F32) {
        float v = 0;
        memcpy(&v, data, sizeof(v));
        snprintf(buf, sizeof(buf), "%.9g", static_cast<double>(v));
        return buf;
    }
    double v = 0;
    memcpy(&v, data, sizeof(v));
    snprintf(buf, sizeof(buf), "%.17g", v);
    return buf;
}
