package com.woyuxiaji.dianmo.util

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import com.woyuxiaji.dianmo.core.RootShell
import com.woyuxiaji.dianmo.model.ProcessInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ProcUtils {

    private data class AppMeta(
        val label: String?,
        val isUserApp: Boolean,
        val icon: Drawable?,
    )

    /**
     * 读取进程列表，并补上应用中文名、图标、是否系统应用。
     * 通过 PackageManager 查询，调用方需有 QUERY_ALL_PACKAGES 权限。
     */
    suspend fun listProcesses(pm: PackageManager): List<ProcessInfo> =
        withContext(Dispatchers.IO) {
            val result = RootShell.exec("ps -A -o PID,NAME")

            // 先解析出 (pid, rawName)，再收集唯一包名，批量查询元信息
            val raw = ArrayList<Pair<Int, String>>()
            result.out.forEachIndexed { index, line ->
                if (index == 0) return@forEachIndexed
                val t = line.trim()
                if (t.isEmpty()) return@forEachIndexed
                val parts = t.split(Regex("\\s+"), limit = 2)
                if (parts.size < 2) return@forEachIndexed
                val pid = parts[0].toIntOrNull() ?: return@forEachIndexed
                raw.add(pid to parts[1])
            }

            val packageNames = raw
                .mapNotNull { (_, name) -> toPackageName(name) }
                .toSet()

            val metaMap = packageNames.associateWith { pkg -> resolveApp(pm, pkg) }

            val list = ArrayList<ProcessInfo>(raw.size)
            for ((pid, name) in raw) {
                val pkg = toPackageName(name)
                val meta = pkg?.let { metaMap[it] }
                list.add(
                    ProcessInfo(
                        rawName = name,
                        pid = pid,
                        packageName = pkg,
                        label = meta?.label,
                        isUserApp = meta?.isUserApp ?: false,
                        icon = meta?.icon,
                    )
                )
            }

            list.sortedWith(
                compareByDescending<ProcessInfo> { it.isUserApp }
                    .thenByDescending { it.label != null }
                    .thenBy { it.displayName.lowercase() }
                    .thenBy { it.rawName }
            )
        }

    /** 读取当前前台应用包名，用于在列表里置顶 */
    suspend fun foregroundPackage(): String? {
        val r = RootShell.exec(
            "dumpsys activity activities | grep -E 'topResumedActivity|mResumedActivity' | head -1"
        )
        val line = r.out.firstOrNull() ?: return null
        val m = Regex("([a-zA-Z0-9_.]+)/").find(line)
        return m?.groupValues?.get(1)
    }

    private fun toPackageName(rawName: String): String? {
        val base = rawName.substringBefore(':')
        if (!base.contains('.')) return null
        if (base.startsWith("[")) return null
        return base
    }

    private fun resolveApp(pm: PackageManager, pkg: String): AppMeta {
        return try {
            val info = pm.getApplicationInfo(pkg, 0)
            AppMeta(
                label = info.loadLabel(pm)?.toString(),
                isUserApp = (info.flags and ApplicationInfo.FLAG_SYSTEM) == 0,
                icon = info.loadIcon(pm),
            )
        } catch (e: Exception) {
            AppMeta(label = null, isUserApp = false, icon = null)
        }
    }
}
