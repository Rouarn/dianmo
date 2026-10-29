package com.woyuxiaji.dianmo.model

/**
 * 支持扫描的数据类型。
 * cli 对应 native scanner 的类型参数。
 */
enum class ScanType(
    val cli: String,
    val size: Int,
    val label: String,
) {
    I32("i32", 4, "Int32"),
    I64("i64", 8, "Int64"),
    F32("f32", 4, "Float"),
    F64("f64", 8, "Double"),
}
