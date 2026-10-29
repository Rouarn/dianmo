package com.woyuxiaji.dianmo.model

import android.graphics.drawable.Drawable

/** 目标进程信息 */
data class ProcessInfo(
    /** ps 里的原始进程名，例如 com.xxx.game 或 com.xxx.game:remote */
    val rawName: String,
    val pid: Int,
    /** 去掉 :suffix 之后的包名，查不到时为 null */
    val packageName: String?,
    /** 应用显示名（系统语言下的中文名），查不到时为 null */
    val label: String?,
    /** 是否第三方应用（非系统应用） */
    val isUserApp: Boolean,
    /** 应用图标 */
    val icon: Drawable?,
) {
    /** 界面展示用名称 */
    val displayName: String
        get() = label ?: packageName ?: rawName

    /** 是否是应用的子进程（带 :remote 之类后缀） */
    val isSubProcess: Boolean
        get() = rawName.contains(':')
}
