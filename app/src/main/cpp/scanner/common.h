#pragma once

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

enum class ScanType {
    I32,
    I64,
    F32,
    F64,
};

bool parse_scan_type(const std::string& s, ScanType& out);
size_t scan_type_size(ScanType t);
size_t scan_type_align(ScanType t);
const char* scan_type_name(ScanType t);

/// 把命令行里的数值字符串解析为字节序列（按目标类型）。
bool parse_value(ScanType t, const std::string& s, std::vector<uint8_t>& out);

/// 把内存中的原始字节格式化为可读字符串。
std::string format_value(ScanType t, const uint8_t* data);
