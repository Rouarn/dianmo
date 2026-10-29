package com.woyuxiaji.dianmo.core

import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 对 libsu 的薄封装。
 * 所有命令都通过 root shell 执行——App 进程本身是普通 uid，
 * 只有 su 拉起的子进程才有权限访问目标进程内存。
 */
object RootShell {

    suspend fun isRoot(): Boolean = withContext(Dispatchers.IO) {
        runCatching { Shell.getShell().isRoot }.getOrDefault(false)
    }

    suspend fun exec(command: String): Shell.Result = withContext(Dispatchers.IO) {
        Shell.cmd(command).exec()
    }

    /** 返回 (exitCode, 输出行) */
    suspend fun execOut(command: String): Pair<Int, List<String>> = withContext(Dispatchers.IO) {
        val r = Shell.cmd(command).exec()
        r.code to r.out
    }
}
