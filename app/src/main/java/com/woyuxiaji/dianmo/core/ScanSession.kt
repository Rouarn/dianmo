package com.woyuxiaji.dianmo.core

import android.content.Context
import com.woyuxiaji.dianmo.model.ProcessInfo
import com.woyuxiaji.dianmo.model.ScanHit
import com.woyuxiaji.dianmo.model.ScanType

/**
 * 主界面与悬浮窗共享的扫描会话。
 * 这样在悬浮窗里操作的结果，切回 App 也能看到，反之亦然。
 */
object ScanSession {

    private var scanner: ScannerCli? = null

    var target: ProcessInfo? = null
    var type: ScanType = ScanType.I32
    var hits: List<ScanHit> = emptyList()
    var lastValue: String = ""

    fun ensure(context: Context): ScannerCli {
        scanner?.let { return it }
        return ScannerCli(context.applicationContext).also { scanner = it }
    }

    fun clearHits() {
        hits = emptyList()
    }
}
