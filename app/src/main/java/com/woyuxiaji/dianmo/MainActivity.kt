package com.woyuxiaji.dianmo

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.woyuxiaji.dianmo.core.RootShell
import com.woyuxiaji.dianmo.core.ScanSession
import com.woyuxiaji.dianmo.core.ScannerCli
import com.woyuxiaji.dianmo.databinding.ActivityMainBinding
import com.woyuxiaji.dianmo.service.OverlayService
import kotlinx.coroutines.launch

/**
 * 主程序只是启动器：检测 root、部署扫描器、唤起悬浮窗。
 * 所有扫描/改写操作都在悬浮窗内完成（类似烧饼修改器的形态）。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var scanner: ScannerCli
    private var scannerReady = false

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(this)) {
            startOverlayService()
        } else {
            toast("未授予悬浮窗权限")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        scanner = ScanSession.ensure(this)

        binding.btnStartOverlay.setOnClickListener { startOverlayFlow() }
        checkRoot()
    }

    private fun checkRoot() {
        lifecycleScope.launch {
            val ok = RootShell.isRoot()
            binding.tvRootStatus.text = getString(
                if (ok) R.string.root_status_ok else R.string.root_status_fail
            )
            binding.tvRootStatus.setTextColor(getColor(if (ok) R.color.ok else R.color.error))

            if (!ok) {
                binding.tvStatus.text = getString(R.string.root_required)
                return@launch
            }

            binding.tvStatus.text = getString(R.string.deploy_scanner)
            binding.btnStartOverlay.isEnabled = false
            try {
                scanner.deploy()
                scannerReady = true
                binding.tvStatus.text = getString(R.string.scanner_ready)
            } catch (e: Exception) {
                binding.tvStatus.text = "扫描器部署失败: ${e.message}"
            } finally {
                binding.btnStartOverlay.isEnabled = true
            }
        }
    }

    private fun startOverlayFlow() {
        if (!scannerReady) {
            toast("扫描器尚未就绪")
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            overlayPermissionLauncher.launch(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"),
                )
            )
            return
        }
        startOverlayService()
    }

    private fun startOverlayService() {
        startForegroundService(Intent(this, OverlayService::class.java))
        toast("悬浮窗已启动，可回到游戏")
        moveTaskToBack(true)
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }
}
