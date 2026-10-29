package com.woyuxiaji.dianmo.core

import android.content.Context
import android.os.Build
import com.woyuxiaji.dianmo.model.ScanHit
import com.woyuxiaji.dianmo.model.ScanType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 与 native 扫描器可执行文件通信。
 *
 * scanner 是一个独立的 arm64/armv7 可执行文件，随 APK 的 assets 分发。
 * 首次使用时复制到 /data/local/tmp/memtool/memtool-scanner 并 chmod 755，
 * 之后通过 `su -c "<scanner> ..."` 调用，stdout 返回结果。
 *
 * 协议：
 *   scan   <pid> <type> <value> [max]        -> 每行 "0xADDR VALUE"
 *   filter <pid> <type> <value> <addr-file>  -> 每行 "0xADDR VALUE"
 *   read   <pid> <type> <addr>               -> "0xADDR VALUE"
 *   write  <pid> <type> <addr> <value>       -> "OK" / "ERR <msg>"
 *   maps   <pid>                             -> 每行 "START-END PERMS PATH"
 *   version                                  -> "memtool-scanner x.y"
 */
class ScannerCli(private val context: Context) {

    companion object {
        private const val REMOTE_DIR = "/data/local/tmp/memtool"
        private const val REMOTE_BIN = "$REMOTE_DIR/memtool-scanner"
        private const val REMOTE_ADDR_FILE = "$REMOTE_DIR/addrs.txt"
        private const val REMOTE_SNAPSHOT = "$REMOTE_DIR/snapshot.bin"

        /** 单次扫描最多返回的命中数，防止 UI 被超大结果集拖垮 */
        const val MAX_RESULTS = 5000
    }

    private fun abiDir(): String {
        return Build.SUPPORTED_ABIS.firstOrNull {
            it == "arm64-v8a" || it == "armeabi-v7a"
        } ?: "arm64-v8a"
    }

    /**
     * 把 assets 里的 scanner 部署到 /data/local/tmp。
     * 幂等：每次启动都会覆盖，避免版本不一致。
     */
    suspend fun deploy(): Unit = withContext(Dispatchers.IO) {
        val abi = abiDir()
        val local = File(context.cacheDir, "memtool-scanner-$abi")
        context.assets.open("scanner/$abi/memtool-scanner").use { input ->
            local.outputStream().use { input.copyTo(it) }
        }
        local.setExecutable(true)

        val result = RootShell.exec(
            "mkdir -p $REMOTE_DIR; " +
                "cp '${local.absolutePath}' '$REMOTE_BIN'; " +
                "chmod 755 '$REMOTE_BIN'; " +
                "'$REMOTE_BIN' version"
        )
        if (result.code != 0) {
            throw IllegalStateException(
                "部署扫描器失败: " + result.out.joinToString(" | ")
            )
        }
    }

    suspend fun scan(
        pid: Int,
        type: ScanType,
        value: String,
        max: Int = MAX_RESULTS,
    ): List<ScanHit> = withContext(Dispatchers.IO) {
        val r = RootShell.exec("'$REMOTE_BIN' scan $pid ${type.cli} $value $max")
        if (r.code != 0) {
            throw IllegalStateException(describeError(r.out))
        }
        parseHits(r.out)
    }

    suspend fun filter(
        pid: Int,
        type: ScanType,
        value: String,
        addresses: List<Long>,
    ): List<ScanHit> = withContext(Dispatchers.IO) {
        val local = File(context.cacheDir, "addrs.txt")
        local.writeText(addresses.joinToString("\n"))
        RootShell.exec("cp '${local.absolutePath}' '$REMOTE_ADDR_FILE'")

        val r = RootShell.exec("'$REMOTE_BIN' filter $pid ${type.cli} $value '$REMOTE_ADDR_FILE'")
        if (r.code != 0) {
            throw IllegalStateException(describeError(r.out))
        }
        parseHits(r.out)
    }

    suspend fun read(pid: Int, type: ScanType, addr: Long): String? = withContext(Dispatchers.IO) {
        val r = RootShell.exec("'$REMOTE_BIN' read $pid ${type.cli} ${addr.toString(16)}")
        if (r.code != 0) return@withContext null
        val line = r.out.firstOrNull { it.isNotBlank() } ?: return@withContext null
        line.trim().split(Regex("\\s+")).getOrNull(1)
    }

    suspend fun write(pid: Int, type: ScanType, addr: Long, value: String): Boolean =
        withContext(Dispatchers.IO) {
            val r = RootShell.exec("'$REMOTE_BIN' write $pid ${type.cli} ${addr.toString(16)} $value")
            r.code == 0 && r.out.any { it.startsWith("OK") }
        }

    /** 批量写入：把所有地址都改成同一个值。返回 (成功数, 总数) */
    suspend fun writeAll(
        pid: Int,
        type: ScanType,
        value: String,
        addresses: List<Long>,
    ): Pair<Int, Int> = withContext(Dispatchers.IO) {
        if (addresses.isEmpty()) return@withContext 0 to 0

        writeAddressFile(addresses)
        val r = RootShell.exec("'$REMOTE_BIN' writeall $pid ${type.cli} $value '$REMOTE_ADDR_FILE'")
        val line = r.out.firstOrNull { it.startsWith("OK") }
            ?: return@withContext 0 to addresses.size
        val body = line.removePrefix("OK").trim()
        val ok = body.substringBefore('/').trim().toIntOrNull() ?: 0
        val total = body.substringAfter('/', addresses.size.toString()).trim().toIntOrNull()
            ?: addresses.size
        ok to total
    }

    /** 重新读取一批地址的当前值（读失败的地址会被跳过） */
    suspend fun readAll(
        pid: Int,
        type: ScanType,
        addresses: List<Long>,
    ): List<ScanHit> = withContext(Dispatchers.IO) {
        if (addresses.isEmpty()) return@withContext emptyList()

        writeAddressFile(addresses)
        val r = RootShell.exec("'$REMOTE_BIN' readall $pid ${type.cli} '$REMOTE_ADDR_FILE'")
        parseHits(r.out)
    }

    private suspend fun writeAddressFile(addresses: List<Long>) {
        val local = File(context.cacheDir, "addrs.txt")
        local.writeText(addresses.joinToString("\n"))
        RootShell.exec("cp '${local.absolutePath}' '$REMOTE_ADDR_FILE'")
    }

    /** 模糊搜索：记录当前所有值（快照），返回记录数 */
    suspend fun fuzzyInit(
        pid: Int,
        type: ScanType,
        maxBytes: Long = 32L * 1024 * 1024,
    ): Long = withContext(Dispatchers.IO) {
        val r = RootShell.exec(
            "'$REMOTE_BIN' fuzzy-init $pid ${type.cli} $maxBytes '$REMOTE_SNAPSHOT'"
        )
        if (r.code != 0) throw IllegalStateException(describeError(r.out))
        val line = r.out.firstOrNull { it.startsWith("OK") } ?: return@withContext -1L
        line.removePrefix("OK").trim().toLongOrNull() ?: -1L
    }

    /** 模糊搜索：按 bigger/smaller/unchanged/changed 对比快照 */
    suspend fun fuzzyFilter(
        pid: Int,
        type: ScanType,
        mode: String,
        max: Int = MAX_RESULTS,
    ): List<ScanHit> = withContext(Dispatchers.IO) {
        val outSnapshot = "$REMOTE_SNAPSHOT.tmp"
        val r = RootShell.exec(
            "'$REMOTE_BIN' fuzzy-filter $pid ${type.cli} $mode '$REMOTE_SNAPSHOT' $max '$outSnapshot'"
        )
        if (r.code != 0) throw IllegalStateException(describeError(r.out))
        val hits = parseHits(r.out)
        // 收敛：把快照换成「本轮命中项」，下一轮只对比剩下的地址
        if (hits.isNotEmpty()) {
            RootShell.exec("mv '$outSnapshot' '$REMOTE_SNAPSHOT'")
        }
        hits
    }

    data class PtrHit(val addr: Long, val offset: Long)

    /** 查找指向 addr 附近对象的指针（允许对象内偏移） */
    suspend fun findPtr(
        pid: Int,
        addr: Long,
        max: Int = 200,
        range: Long = 0x2000,
    ): List<PtrHit> = withContext(Dispatchers.IO) {
        val r = RootShell.exec(
            "'$REMOTE_BIN' findptr $pid ${addr.toString(16)} $max ${range.toString(16)}"
        )
        r.out.mapNotNull { line ->
            val t = line.trim()
            if (!t.startsWith("0x")) {
                null
            } else {
                val parts = t.split(Regex("\\s+"))
                val ptr = parts.getOrNull(0)?.removePrefix("0x")?.toLongOrNull(16)
                val off = parts.getOrNull(1)?.toLongOrNull(16) ?: 0L
                if (ptr == null) null else PtrHit(ptr, off)
            }
        }
    }

    data class ChainLink(val addr: Long, val offset: Long)

    /** BFS 找指针链，返回 (基址模块, 链)；没找到时模块为 null */
    suspend fun findChain(
        pid: Int,
        addr: Long,
        maxDepth: Int = 8,
        maxNodes: Int = 40,
    ): Pair<String?, List<ChainLink>> = withContext(Dispatchers.IO) {
        val r = RootShell.exec(
            "'$REMOTE_BIN' chain $pid ${addr.toString(16)} $maxDepth $maxNodes"
        )
        val lines = r.out.filter { it.isNotBlank() }
        if (lines.isEmpty() || lines[0].contains("NOFOUND")) {
            return@withContext null to emptyList()
        }
        val module = lines[0].removePrefix("FOUND").trim()
        val links = lines.drop(1).mapNotNull { line ->
            val parts = line.trim().split(Regex("\\s+"))
            val a = parts.getOrNull(0)?.removePrefix("0x")?.toLongOrNull(16)
                ?: return@mapNotNull null
            val off = parts.getOrNull(1)?.toLongOrNull(16) ?: 0L
            ChainLink(a, off)
        }
        module to links
    }

    suspend fun dumpMaps(pid: Int): List<String> = withContext(Dispatchers.IO) {
        val r = RootShell.exec("'$REMOTE_BIN' maps $pid")
        if (r.code != 0) emptyList() else r.out
    }

    private fun describeError(out: List<String>): String {
        val err = out.firstOrNull { it.startsWith("ERR") }
        return err ?: out.lastOrNull() ?: "未知错误"
    }

    private fun parseHits(lines: List<String>): List<ScanHit> {
        val out = ArrayList<ScanHit>(lines.size)
        for (line in lines) {
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("ERR")) continue
            val parts = t.split(Regex("\\s+"))
            if (parts.size < 2) continue
            val addr = parts[0].removePrefix("0x").toLongOrNull(16) ?: continue
            out.add(ScanHit(addr, parts[1]))
        }
        return out
    }
}
