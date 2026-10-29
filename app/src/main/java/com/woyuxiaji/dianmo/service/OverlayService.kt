package com.woyuxiaji.dianmo.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.widget.doOnTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.woyuxiaji.dianmo.R
import com.woyuxiaji.dianmo.core.SavedStore
import com.woyuxiaji.dianmo.core.ScannerCli
import com.woyuxiaji.dianmo.core.ScanSession
import com.woyuxiaji.dianmo.model.ProcessInfo
import com.woyuxiaji.dianmo.model.ScanHit
import com.woyuxiaji.dianmo.model.ScanType
import com.woyuxiaji.dianmo.ui.HitAdapter
import com.woyuxiaji.dianmo.ui.ProcessAdapter
import com.woyuxiaji.dianmo.ui.SavedAdapter
import com.woyuxiaji.dianmo.ui.WashiDialog
import com.woyuxiaji.dianmo.ui.WashiMenuDialog
import com.woyuxiaji.dianmo.util.ProcUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 悬浮窗服务：烧饼修改器形态。
 *
 * - 收起时是左上角小悬浮球
 * - 展开时是全屏遮罩 + 居中大面板
 * - 面板内完成：切换进程、选类型、搜索、二次搜索、全部修改、刷新、单条改写
 */
class OverlayService : Service() {

    companion object {
        private const val CHANNEL_ID = "memtool_overlay"
        private const val NOTIF_ID = 1001
    }

    private lateinit var windowManager: WindowManager
    private lateinit var themedContext: Context

    private var ballView: View? = null
    private var panelView: View? = null
    private var ballParams: WindowManager.LayoutParams? = null
    private var panelParams: WindowManager.LayoutParams? = null

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private lateinit var tvTarget: TextView
    private lateinit var tvGameName: TextView
    private lateinit var targetRow: View
    private lateinit var btnNext: View
    private lateinit var cbSelectAll: android.widget.CheckBox
    private lateinit var etValue: EditText
    private lateinit var tvStatus: TextView
    private lateinit var tvResult: TextView
    private lateinit var resultBar: View
    private lateinit var rvHits: RecyclerView
    private lateinit var writeRow: View
    private lateinit var tvWriteAddr: TextView
    private lateinit var etWriteValue: EditText
    private lateinit var scanPanel: View
    private lateinit var processPicker: View
    private lateinit var etProcSearch: EditText
    private lateinit var rvProcesses: RecyclerView
    private lateinit var typeButtons: List<View>
    private lateinit var savedPanel: View
    private lateinit var rvSaved: RecyclerView
    private lateinit var cbSavedAll: android.widget.CheckBox
    private lateinit var tvSavedCount: TextView
    private lateinit var savedAdapter: SavedAdapter
    private var busy = false
    private val chainPath = ArrayList<ScannerCli.PtrHit>()
    private var chainMaps: List<String> = emptyList()

    private var hitAdapter: HitAdapter? = null
    private var processAdapter: ProcessAdapter? = null
    private var allProcesses: List<ProcessInfo> = emptyList()
    private var writeAddress: Long = 0L
    private var inputMode = false
    private var pickerVisible = false
    private lateinit var keyPanel: View
    private lateinit var tvEmptyHint: TextView
    private lateinit var btnChainView: TextView
    private lateinit var btnRefreshView: TextView
    private lateinit var tvProcLoading: TextView
    private var activeEdit: EditText? = null
    private var currentType: ScanType = ScanSession.type

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        themedContext = ContextThemeWrapper(this, R.style.Theme_MemTool)

        startForegroundCompat()
        setupBall()
        setupPanel()
        showBall()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        updateTargetLabel()
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        runCatching { ballView?.let { windowManager.removeView(it) } }
        runCatching { panelView?.let { windowManager.removeView(it) } }
        ballView = null
        panelView = null
        super.onDestroy()
    }

    // ------------------------------------------------------------ 通知

    private fun startForegroundCompat() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "点墨 悬浮窗", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("点墨 · 悬浮窗运行中")
            .setContentText("点击悬浮球展开面板")
            .setSmallIcon(R.drawable.ic_launcher)
            .setOngoing(true)
            .build()
        startForeground(NOTIF_ID, notification)
    }

    // ------------------------------------------------------------ 悬浮球

    private fun setupBall() {
        val view = LayoutInflater.from(themedContext).inflate(R.layout.overlay_ball, null)
        val ballSize = (52 * resources.displayMetrics.density).toInt()
        val params = WindowManager.LayoutParams(
            ballSize,
            ballSize,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 420
        }
        windowManager.addView(view, params)
        ballView = view
        ballParams = params
        makeDraggable(view, params, view)
        view.setOnClickListener { showPanel() }
    }

    // ------------------------------------------------------------ 面板

    private fun setupPanel() {
        val view = LayoutInflater.from(themedContext).inflate(R.layout.overlay_panel, null)
        val dm = resources.displayMetrics
        val marginPx = (8 * dm.density).toInt()
        val maxWidthPx = (420 * dm.density).toInt()
        val landscape = dm.widthPixels > dm.heightPixels
        // 横屏按高度比例限制，竖屏不超过 420dp
        val panelWidth = if (landscape) {
            minOf(
                dm.widthPixels - marginPx * 2,
                (dm.heightPixels * 1.15f).toInt(),
                maxWidthPx,
            )
        } else {
            minOf(dm.widthPixels - marginPx * 2, maxWidthPx)
        }
        val params = WindowManager.LayoutParams(
            panelWidth,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.CENTER
        }
        windowManager.addView(view, params)
        panelView = view
        panelParams = params

        tvTarget = view.findViewById(R.id.tvTarget)
        tvGameName = view.findViewById(R.id.tvGameName)
        targetRow = view.findViewById(R.id.targetRow)
        btnNext = view.findViewById(R.id.btnNext)
        cbSelectAll = view.findViewById(R.id.cbSelectAll)
        etValue = view.findViewById(R.id.etValue)
        tvStatus = view.findViewById(R.id.tvStatus)
        tvResult = view.findViewById(R.id.tvResult)
        resultBar = view.findViewById(R.id.resultBar)
        rvHits = view.findViewById(R.id.rvHits)
        writeRow = view.findViewById(R.id.writeRow)
        tvWriteAddr = view.findViewById(R.id.tvWriteAddr)
        etWriteValue = view.findViewById(R.id.etWriteValue)
        scanPanel = view.findViewById(R.id.scanPanel)
        processPicker = view.findViewById(R.id.processPicker)
        etProcSearch = view.findViewById(R.id.etProcSearch)
        rvProcesses = view.findViewById(R.id.rvProcesses)
        typeButtons = listOf(
            view.findViewById(R.id.type0),
            view.findViewById(R.id.type1),
            view.findViewById(R.id.type2),
            view.findViewById(R.id.type3),
        )

        hitAdapter = HitAdapter(
            onWrite = { hit -> showWriteRow(hit) },
            onCheckedChanged = { syncSelectAll() },
        )
        rvHits.layoutManager = LinearLayoutManager(themedContext)
        rvHits.adapter = hitAdapter

        cbSelectAll.setOnCheckedChangeListener { _, checked ->
            hitAdapter?.setAllChecked(checked)
        }

        processAdapter = ProcessAdapter { p -> selectProcess(p) }
        rvProcesses.layoutManager = LinearLayoutManager(themedContext)
        rvProcesses.adapter = processAdapter

        savedPanel = view.findViewById(R.id.savedPanel)
        rvSaved = view.findViewById(R.id.rvSaved)
        cbSavedAll = view.findViewById(R.id.cbSavedAll)
        tvSavedCount = view.findViewById(R.id.tvSavedCount)
        savedAdapter = SavedAdapter(
            onWrite = { item -> showSavedEdit(item) },
            onChecked = { updateSavedCount() },
        )
        rvSaved.layoutManager = LinearLayoutManager(themedContext)
        rvSaved.adapter = savedAdapter
        cbSavedAll.setOnCheckedChangeListener { _, c -> savedAdapter?.setAllChecked(c) }
        view.findViewById<View>(R.id.btnSavedDelete).setOnClickListener { deleteSavedSelected() }
        view.findViewById<View>(R.id.btnSavedRefresh).setOnClickListener { refreshSaved() }
        view.findViewById<View>(R.id.btnSavedEdit).setOnClickListener { batchEditSaved() }
        view.findViewById<View>(R.id.btnSavedBack).setOnClickListener {
            savedPanel.visibility = View.GONE
            scanPanel.visibility = View.VISIBLE
        }
        updateSavedCount()

        // 类型按钮
        typeButtons.forEachIndexed { index, btn ->
            btn.setOnClickListener { selectType(index) }
        }
        selectType(ScanType.entries.indexOf(ScanSession.type).coerceAtLeast(0))

        // 关闭/收起
        view.findViewById<View>(R.id.tvClose).setOnClickListener { showBall() }
        view.findViewById<View>(R.id.btnHide).setOnClickListener { showBall() }
        // 点遮罩空白处收起；点面板内部则消费掉，避免误触
        view.findViewById<android.widget.FrameLayout>(R.id.overlayMask)
            .setOnClickListener { showBall() }
        view.findViewById<View>(R.id.panelRoot).setOnClickListener { /* consume */ }

        // 操作按钮
        view.findViewById<View>(R.id.btnFirst).setOnClickListener {
            exitInputMode(); doScan(first = true)
        }
        view.findViewById<View>(R.id.btnNext).setOnClickListener {
            exitInputMode(); doScan(first = false)
        }
        view.findViewById<View>(R.id.btnWriteClose).setOnClickListener {
            exitInputMode()
            hideWriteRow()
            if (::keyPanel.isInitialized) keyPanel.visibility = View.GONE
        }
        view.findViewById<View>(R.id.btnWriteAll).setOnClickListener {
            exitInputMode(); showWriteAllDialog()
        }
        view.findViewById<View>(R.id.btnRefresh).setOnClickListener {
            exitInputMode(); refreshValues()
        }
        view.findViewById<View>(R.id.btnClear).setOnClickListener { clearResults() }
        view.findViewById<View>(R.id.btnFuzzy).setOnClickListener { showFuzzyMenu() }
        view.findViewById<View>(R.id.btnSpeed).setOnClickListener { showSpeedDialog() }
        view.findViewById<View>(R.id.btnChain).setOnClickListener { showPointerChain() }
        view.findViewById<View>(R.id.btnSave).setOnClickListener { saveAddresses() }
        view.findViewById<View>(R.id.btnSaved).setOnClickListener { showSavedList() }
        view.findViewById<View>(R.id.btnPickProcess).setOnClickListener { showProcessPicker() }
        view.findViewById<View>(R.id.btnClosePicker).setOnClickListener { hideProcessPicker() }

        // 输入框焦点切换
        // 数值输入用自绘键盘，彻底禁掉系统输入法
        etValue.showSoftInputOnFocus = false
        etWriteValue.showSoftInputOnFocus = false
        etValue.inputType = android.text.InputType.TYPE_NULL
        etWriteValue.inputType = android.text.InputType.TYPE_NULL
        val showKeyOnTouch = { edit: EditText ->
            edit.setOnTouchListener { _, e ->
                if (e.action == MotionEvent.ACTION_UP) showKeyPanel(edit)
                false
            }
        }
        showKeyOnTouch(etValue)
        showKeyOnTouch(etWriteValue)
        // 进程搜索仍用系统输入法（需要输入中文/字母）
        etProcSearch.setOnClickListener { enterInputMode(etProcSearch) }
        etProcSearch.setOnFocusChangeListener { _, has -> if (!has) scheduleExitInputMode() }

        etProcSearch.doOnTextChanged { _, _, _, _ -> filterProcesses() }

        keyPanel = view.findViewById(R.id.keyPanel)
        tvEmptyHint = view.findViewById(R.id.tvEmptyHint)
        btnChainView = view.findViewById(R.id.btnChain)
        btnRefreshView = view.findViewById(R.id.btnRefresh)
        tvProcLoading = view.findViewById(R.id.tvProcLoading)
        setupKeypad(view)

        makeDraggable(view.findViewById(R.id.dragBar), params, view)

        updateTargetLabel()
        renderHits(ScanSession.hits)
    }

    private fun showBall() {
        exitInputMode()
        if (::keyPanel.isInitialized) keyPanel.visibility = View.GONE
        hideProcessPicker()
        // 收起时把所有子页面归位，避免下次展开时重叠
        if (::savedPanel.isInitialized) savedPanel.visibility = View.GONE
        if (::scanPanel.isInitialized) scanPanel.visibility = View.VISIBLE
        if (::targetRow.isInitialized) targetRow.visibility = View.VISIBLE
        ballView?.visibility = View.VISIBLE
        panelView?.visibility = View.GONE
    }

    private fun showPanel() {
        ballView?.visibility = View.GONE
        panelView?.visibility = View.VISIBLE
        updateTargetLabel()
        renderHits(ScanSession.hits)
    }

    private fun updateTargetLabel() {
        if (!::tvTarget.isInitialized) return
        val p = ScanSession.target
        tvTarget.text = if (p == null) "未选择进程" else "${p.displayName} (${p.pid})"
        if (::tvGameName.isInitialized) {
            tvGameName.text = if (p == null) "点墨" else p.displayName
        }
    }

    private fun syncSelectAll() {
        if (!::cbSelectAll.isInitialized) return
        val all = hitAdapter?.isAllChecked() ?: false
        if (cbSelectAll.isChecked == all) return
        cbSelectAll.setOnCheckedChangeListener(null)
        cbSelectAll.isChecked = all
        cbSelectAll.setOnCheckedChangeListener { _, checked ->
            hitAdapter?.setAllChecked(checked)
        }
    }

    // ------------------------------------------------------------ 类型

    private fun selectType(index: Int) {
        val type = ScanType.entries[index]
        currentType = type
        ScanSession.type = type
        typeButtons.forEachIndexed { i, v ->
            v.setBackgroundResource(0)
            val tv = v as? TextView ?: return@forEachIndexed
            val on = i == index
            tv.setTextColor(getColor(if (on) R.color.vermilion else R.color.ink_secondary))
            tv.setTypeface(
                null,
                if (on) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL
            )
        }
    }

    // ------------------------------------------------------------ 进程页

    private fun showProcessPicker() {
        pickerVisible = true
        if (::scanPanel.isInitialized) scanPanel.visibility = View.GONE
        if (::targetRow.isInitialized) targetRow.visibility = View.GONE
        processPicker.visibility = View.VISIBLE
        loadProcesses()
    }

    private fun hideProcessPicker() {
        pickerVisible = false
        if (!::processPicker.isInitialized) return
        processPicker.visibility = View.GONE
        if (::scanPanel.isInitialized) scanPanel.visibility = View.VISIBLE
        if (::targetRow.isInitialized) targetRow.visibility = View.VISIBLE
    }

    private fun loadProcesses() {
        // 每次都重新读取：游戏可能在面板第一次打开之后才启动
        if (::tvProcLoading.isInitialized) {
            tvProcLoading.text = "正在读取进程列表…"
            tvProcLoading.visibility = View.VISIBLE
        }
        scope.launch {
            try {
                val list = ProcUtils.listProcesses(packageManager)
                android.util.Log.d(
                    "MemToolDebug",
                    "total=${list.size}, withPkg=${list.count { it.packageName != null }}, " +
                        "userApps=${list.count { it.isUserApp }}"
                )
                // 只显示第三方应用，避免系统进程淹没列表
                val userApps = list.filter { it.isUserApp }
                val base = if (userApps.isNotEmpty()) userApps else list.filter { it.packageName != null }
                // 当前前台游戏置顶，省得每次翻列表
                val fg = try { ProcUtils.foregroundPackage() } catch (e: Exception) { null }
                allProcesses = if (fg.isNullOrEmpty()) {
                    base
                } else {
                    base.sortedWith(
                        compareByDescending<ProcessInfo> { it.packageName == fg }
                            .thenBy { it.displayName.lowercase() }
                    )
                }
                android.util.Log.d("MemToolDebug", "filtered=${allProcesses.size}")
                filterProcesses()
                if (::tvProcLoading.isInitialized) {
                    if (allProcesses.isEmpty()) {
                        tvProcLoading.text = "没有找到可修改的应用"
                        tvProcLoading.visibility = View.VISIBLE
                    } else {
                        tvProcLoading.visibility = View.GONE
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("MemToolDebug", "load failed", e)
                if (::tvProcLoading.isInitialized) {
                    tvProcLoading.text = "读取失败：${e.message}"
                    tvProcLoading.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun filterProcesses() {
        val keyword = etProcSearch.text?.toString()?.trim().orEmpty()
        val filtered = if (keyword.isEmpty()) {
            allProcesses
        } else {
            allProcesses.filter { p ->
                p.displayName.contains(keyword, ignoreCase = true) ||
                    p.rawName.contains(keyword, ignoreCase = true) ||
                    (p.packageName?.contains(keyword, ignoreCase = true) == true)
            }
        }
        android.util.Log.d(
            "MemToolDebug",
            "filter keyword='$keyword' in=${allProcesses.size} out=${filtered.size} " +
                "adapter=${processAdapter?.size}"
        )
        processAdapter?.submit(filtered)
    }

    private fun selectProcess(p: ProcessInfo) {
        ScanSession.target = p
        ScanSession.clearHits()
        hitAdapter?.submit(emptyList())
        renderHits(emptyList())
        updateTargetLabel()
        hideProcessPicker()
        toast("已选择：${p.displayName}")
    }

    // ------------------------------------------------------------ 输入模式

    private fun showKeyPanel(edit: EditText) {
        activeEdit = edit
        val isWrite = edit.id == R.id.etWriteValue
        // 键盘右下角主键随场景切换：搜索 / 写入
        keyPanel.findViewById<TextView>(R.id.keySearch)?.text = if (isWrite) "写入" else "搜索"
        keyPanel.visibility = View.VISIBLE
    }

    private fun setupKeypad(root: View) {
        val digitMap = mapOf(
            R.id.key0 to "0", R.id.key1 to "1", R.id.key2 to "2",
            R.id.key3 to "3", R.id.key4 to "4", R.id.key5 to "5",
            R.id.key6 to "6", R.id.key7 to "7", R.id.key8 to "8",
            R.id.key9 to "9", R.id.keyDot to ".",
            R.id.keyMinus to "-", R.id.keyColon to ":", R.id.keyComma to ",",
        )
        digitMap.forEach { (id, ch) ->
            root.findViewById<View>(id).setOnClickListener {
                android.util.Log.d("MemToolDebug", "key=$ch active=${activeEdit?.id}")
                activeEdit?.append(ch)
            }
        }
        root.findViewById<View>(R.id.keyDel).setOnClickListener {
            val e = activeEdit ?: return@setOnClickListener
            val t = e.text.toString()
            if (t.isNotEmpty()) {
                e.setText(t.dropLast(1))
                e.setSelection(e.text.length)
            }
        }
        root.findViewById<View>(R.id.keyClear).setOnClickListener {
            activeEdit?.setText("")
        }
        root.findViewById<View>(R.id.keySearch).setOnClickListener {
            val writing = activeEdit?.id == R.id.etWriteValue
            keyPanel.visibility = View.GONE
            if (writing) doWrite() else doScan(first = true)
        }
        root.findViewById<View>(R.id.keyHide).setOnClickListener {
            keyPanel.visibility = View.GONE
        }
    }

    private fun enterInputMode(edit: EditText) {
        if (inputMode) {
            edit.requestFocus()
            return
        }
        inputMode = true
        val params = panelParams ?: return
        params.flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        windowManager.updateViewLayout(panelView, params)
        edit.requestFocus()
        val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.showSoftInput(edit, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
    }

    private fun scheduleExitInputMode() {
        panelView?.postDelayed({
            if (!inputMode) return@postDelayed
            if (panelView?.findFocus() == null) exitInputMode()
        }, 120)
    }

    private fun exitInputMode() {
        if (!inputMode) return
        inputMode = false
        val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(panelView?.windowToken, 0)
        val params = panelParams ?: return
        params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        windowManager.updateViewLayout(panelView, params)
    }

    // ------------------------------------------------------------ 拖动

    private fun makeDraggable(handle: View, params: WindowManager.LayoutParams, root: View) {
        if (params.width == WindowManager.LayoutParams.MATCH_PARENT &&
            params.height == WindowManager.LayoutParams.MATCH_PARENT
        ) {
            // 全屏面板不做拖动，仅保留触摸透传
            return
        }
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false

        handle.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    moved = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchX
                    val dy = event.rawY - touchY
                    if (kotlin.math.abs(dx) > 8 || kotlin.math.abs(dy) > 8) moved = true
                    params.x = startX + dx.toInt()
                    params.y = startY + dy.toInt()
                    windowManager.updateViewLayout(root, params)
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (!moved) v.performClick()
                    false
                }

                else -> false
            }
        }
    }

    // ------------------------------------------------------------ 扫描

    // ------------------------------------------------------------ 指针链

    private fun showPointerChain() {
        if (busy) {
            toast("正在查找中，请稍候…")
            return
        }
        val target = ScanSession.target
        if (target == null) {
            toast("请先选择进程")
            return
        }
        val hits = hitAdapter?.checkedItems().orEmpty().ifEmpty { ScanSession.hits }
        if (hits.isEmpty()) {
            toast("没有可用的地址，先搜索")
            return
        }
        val addr = hits.first().address

        busy = true
        if (::btnChainView.isInitialized) {
            btnChainView.text = "查找中…"
            btnChainView.isEnabled = false
        }
        tvStatus.text = "正在查找指针链，可能需要几秒…"
        val loading = WashiDialog(
            themedContext,
            title = "请稍候",
            message = "正在查找指针链，可能需要几秒…",
            positiveText = "",
            overlayType = overlayType(),
        ).apply {
            setCancelable(false)
            show()
        }
        scope.launch {
            try {
            val scanner = ScanSession.ensure(this@OverlayService)
            val (module, links) = try {
                scanner.findChain(target.pid, addr)
            } catch (e: Exception) {
                null to emptyList()
            }

            val body = buildString {
                appendLine("目标：0x${addr.toString(16)}")
                appendLine()
                if (module == null || links.isEmpty()) {
                    append("没找到能落到模块的指针链。\n")
                    append("可能原因：这个值是临时变量/数组元素，或链超过 8 层。")
                } else {
                    appendLine("基址模块：")
                    appendLine(module.substringAfterLast('/'))
                    appendLine()
                    links.forEachIndexed { i, link ->
                        appendLine(
                            "${i + 1}. 0x${link.addr.toString(16)}" +
                                "  +0x${link.offset.toString(16)}"
                        )
                    }
                    appendLine()
                    append("最后一层在模块内，这条链重启后仍有效。")
                }
            }
            tvStatus.text = if (module != null) "指针链：找到基址" else "指针链：未找到"
            WashiDialog(
                themedContext,
                title = "指针链",
                message = body,
                positiveText = "知道了",
                neutralText = if (module == null) "手动逐层" else null,
                onNeutral = {
                    chainPath.clear()
                    chainMaps = emptyList()
                    startManualChain(addr)
                },
                overlayType = overlayType(),
            ).show()
            } finally {
                busy = false
                if (::btnChainView.isInitialized) {
                    btnChainView.text = "指针"
                    btnChainView.isEnabled = true
                }
                loading.dismiss()
            }
        }
    }

    private fun startManualChain(addr: Long) {
        chainPath.clear()
        busy = true
        tvStatus.text = "正在准备手动查找…"
        val loading = WashiDialog(
            themedContext,
            title = "请稍候",
            message = "正在读取内存布局…",
            positiveText = "",
            overlayType = overlayType(),
        ).apply {
            setCancelable(false)
            show()
        }
        scope.launch {
            try {
                chainMaps = try {
                    ScanSession.ensure(this@OverlayService)
                        .dumpMaps(ScanSession.target?.pid ?: return@launch)
                } catch (e: Exception) {
                    emptyList()
                }
                loading.dismiss()
                showChainLevel(addr, 1)
            } finally {
                busy = false
            }
        }
    }

    private fun showChainLevel(current: Long, depth: Int) {
        val target = ScanSession.target ?: return
        if (depth > 12) {
            toast("层数太深，停止")
            return
        }
        scope.launch {
            val ptrs = try {
                ScanSession.ensure(this@OverlayService)
                    .findPtr(target.pid, current, max = 8, range = 0x2000)
            } catch (e: Exception) {
                emptyList()
            }
            if (ptrs.isEmpty()) {
                WashiDialog(
                    themedContext,
                    title = "第 $depth 层",
                    message = "没有指针指向 0x${current.toString(16)}，链到此结束（可能是栈变量或值类型）",
                    positiveText = "知道了",
                    overlayType = overlayType(),
                ).show()
                return@launch
            }

            WashiMenuDialog(
                themedContext,
                title = "第 $depth 层",
                subtitle = "点一个继续",
                items = ptrs.map { p ->
                    val inModule = isModuleAddress(p.addr, chainMaps)
                    ("0x${p.addr.toString(16)}  +0x${p.offset.toString(16)}") to
                        (if (inModule) "模块内" else null)
                },
                onSelect = { which ->
                    val chosen = ptrs[which]
                    chainPath.add(chosen)
                    if (isModuleAddress(chosen.addr, chainMaps)) {
                        showChainResult(chosen.addr)
                    } else {
                        showChainLevel(chosen.addr, depth + 1)
                    }
                },
                overlayType = overlayType(),
            ).show()
        }
    }

    private fun showChainPath() {
        if (chainPath.isEmpty()) {
            toast("还没有选择")
            return
        }
        val body = buildString {
            appendLine("已选路径：")
            chainPath.forEachIndexed { i, link ->
                appendLine("${i + 1}. 0x${link.addr.toString(16)}  +0x${link.offset.toString(16)}")
            }
        }
        WashiDialog(
            themedContext,
            title = "当前链",
            message = body,
            positiveText = "关闭",
            overlayType = overlayType(),
        ).show()
    }

    private fun showChainResult(base: Long) {
        val body = buildString {
            appendLine("基址：0x${base.toString(16)}（模块内）")
            appendLine()
            appendLine("完整链：")
            chainPath.forEachIndexed { i, link ->
                appendLine("${i + 1}. 0x${link.addr.toString(16)}  +0x${link.offset.toString(16)}")
            }
            appendLine()
            append("这条链在游戏重启后仍有效。")
        }
        tvStatus.text = "指针链：手动找到基址"
        WashiDialog(
            themedContext,
            title = "指针链",
            message = body,
            positiveText = "知道了",
            neutralText = "存入地址簿",
            onNeutral = { saveChain(base) },
            overlayType = overlayType(),
        ).show()
    }

    private fun saveChain(base: Long) {
        val target = ScanSession.target ?: return
        val pkg = target.packageName ?: target.rawName
        val note = "链@0x${base.toString(16)}"
        val existing = SavedStore.load(this).toMutableList()
        val offsets = chainPath.joinToString(",") { it.offset.toString(16) }
        existing.add(
            SavedStore.Item(pkg, "chain", base, note, offsets)
        )
        SavedStore.save(this, existing)
        toast("链已保存（${chainPath.size} 层）")
    }

    private fun isModuleAddress(addr: Long, maps: List<String>): Boolean {
        for (line in maps) {
            val parts = line.split(Regex("\\s+"))
            if (parts.size < 5) continue
            val range = parts[0].split("-")
            if (range.size != 2) continue
            val start = range[0].toLongOrNull(16) ?: continue
            val end = range[1].toLongOrNull(16) ?: continue
            if (addr in start until end) {
                return parts[parts.size - 1].startsWith("/")
            }
        }
        return false
    }

    // ------------------------------------------------------------ 变速

    private fun showSpeedDialog() {
        val target = ScanSession.target
        if (target == null) {
            toast("请先选择进程")
            return
        }
        WashiMenuDialog(
            themedContext,
            title = "变速",
            subtitle = "改 Unity 时间缩放",
            items = listOf(
                "0.5 倍" to "慢动作",
                "1.0 倍" to "恢复正常",
                "2.0 倍" to "加速",
                "3.0 倍" to "更快",
            ),
            onSelect = { which ->
                val v = when (which) {
                    0 -> "0.5"
                    1 -> "1.0"
                    2 -> "2.0"
                    else -> "3.0"
                }
                applySpeed(v)
            },
            overlayType = overlayType(),
        ).show()
    }

    private fun applySpeed(value: String) {
        val target = ScanSession.target ?: return
        scope.launch {
            tvStatus.text = "正在搜索 timeScale（float 1.0）…"
            try {
                val scanner = ScanSession.ensure(this@OverlayService)
                val hits = scanner.scan(target.pid, ScanType.F32, "1.0", 5000)
                if (hits.isEmpty()) {
                    tvStatus.text = "没找到 float 1.0，游戏可能不是 Unity"
                    return@launch
                }
                val (ok, total) = scanner.writeAll(
                    target.pid, ScanType.F32, value, hits.map { it.address }
                )
                val msg = "已把 $ok/$total 个 float 1.0 改为 $value，回游戏看速度变化"
                tvStatus.text = msg
                toast(msg)
            } catch (e: Exception) {
                tvStatus.text = "变速失败：${e.message}"
                toast("变速失败：${e.message}")
            }
        }
    }

    // ------------------------------------------------------------ 模糊搜索

    private fun showFuzzyMenu() {
        val target = ScanSession.target
        if (target == null) {
            toast("请先选择进程")
            return
        }
        val options = arrayOf(
            "1. 记录当前所有值（初始化）",
            "2. 数值变大了",
            "3. 数值变小了",
            "4. 数值没变化",
            "5. 数值变化了",
        )
        WashiMenuDialog(
            themedContext,
            title = "模糊搜索",
            subtitle = "当前类型：${currentType.label}",
            items = listOf(
                "记录当前所有值" to "先做一次",
                "数值变大了" to null,
                "数值变小了" to null,
                "数值没变化" to null,
                "数值变化了" to null,
            ),
            onSelect = { which ->
                if (which == 0) fuzzyInit() else fuzzyFilterBy(which)
            },
            overlayType = overlayType(),
        ).show()
    }

    private fun fuzzyInit() {
        val target = ScanSession.target ?: return
        scope.launch {
            tvStatus.text = "正在记录当前所有值…"
            try {
                val n = ScanSession.ensure(this@OverlayService).fuzzyInit(target.pid, currentType)
                val msg = "已记录 $n 个值。去游戏里让数值变化，再选「变大了/变小了」"
                tvStatus.text = msg
                toast(msg)
            } catch (e: Exception) {
                tvStatus.text = "初始化失败：${e.message}"
                toast("初始化失败：${e.message}")
            }
        }
    }

    private fun fuzzyFilterBy(which: Int) {
        val target = ScanSession.target ?: return
        val mode = when (which) {
            1 -> "bigger"
            2 -> "smaller"
            3 -> "unchanged"
            else -> "changed"
        }
        val modeName = when (which) {
            1 -> "变大了"
            2 -> "变小了"
            3 -> "没变化"
            else -> "变化了"
        }
        scope.launch {
            tvStatus.text = "正在对比（$modeName）…"
            try {
                val hits = ScanSession.ensure(this@OverlayService)
                    .fuzzyFilter(target.pid, currentType, mode)
                ScanSession.hits = hits
                ScanSession.lastValue = "fuzzy:$mode"
                renderHits(hits)
                val msg = if (hits.isEmpty()) {
                    "没有命中。如果还没初始化，先选「1. 记录当前所有值」"
                } else {
                    "模糊结果（$modeName）：${hits.size} 个。再让数值变化一次，继续筛"
                }
                tvStatus.text = msg
                if (hits.isNotEmpty()) toast(msg)
            } catch (e: Exception) {
                tvStatus.text = "对比失败：${e.message}"
                toast("对比失败：${e.message}")
            }
        }
    }

    private fun doScan(first: Boolean) {
        if (::keyPanel.isInitialized) keyPanel.visibility = View.GONE
        val target = ScanSession.target
        if (target == null) {
            toast("请先选择进程")
            showProcessPicker()
            return
        }
        val value = etValue.text.toString().trim()
        if (value.isEmpty()) {
            toast("请输入数值")
            return
        }
        if (!first && ScanSession.hits.isEmpty()) {
            toast("还没有首扫结果")
            return
        }
        if (!first && value == ScanSession.lastValue) {
            toast("搜索值和上次相同，请先改成游戏里变化后的新值")
            return
        }

        val scanner = ScanSession.ensure(this)
        val previous = ScanSession.hits
        val type = currentType
        scope.launch {
            setBusy(true, if (first) "搜索中…" else "过滤中…")
            try {
                val result = if (first) {
                    scanner.scan(target.pid, type, value)
                } else {
                    scanner.filter(target.pid, type, value, previous.map { it.address })
                }
                ScanSession.type = type
                ScanSession.lastValue = value
                ScanSession.hits = result
                renderHits(result)
                if (::tvStatus.isInitialized) {
                    tvStatus.text = if (result.isEmpty()) "没有找到" else "找到 ${result.size} 个，勾选后点「修改」"
                }
                // 全选输入框：下次直接输入新值就会覆盖，避免拼接
                etValue.setSelection(0, etValue.text.length)
                if (result.isEmpty()) {
                    diagnoseEmpty(target.pid, type, previous, value)
                }
            } catch (e: Exception) {
                tvStatus.text = "失败: ${e.message}"
            } finally {
                setBusy(false)
            }
        }
    }

    /** 继续搜索为空时，读几个旧地址判断是失效还是值不对 */
    private fun diagnoseEmpty(pid: Int, type: ScanType, previous: List<ScanHit>, value: String) {
        if (previous.isEmpty()) {
            toast("无命中（搜索值：$value）")
            return
        }
        scope.launch {
            val sample = previous.take(5)
            val current = try {
                ScanSession.ensure(this@OverlayService).readAll(pid, type, sample.map { it.address })
            } catch (e: Exception) {
                emptyList()
            }
            val msg = if (current.isEmpty()) {
                "地址已失效（对象可能被 GC 移动），请重新首次搜索"
            } else {
                "地址还在，但当前值是 ${current.joinToString(",") { it.value }}，不是 $value"
            }
            toast(msg)
        }
    }

    private fun renderHits(hits: List<ScanHit>) {
        if (!::tvResult.isInitialized) return
        val visible = hits.isNotEmpty()
        resultBar.visibility = if (visible) View.VISIBLE else View.GONE
        rvHits.visibility = if (visible) View.VISIBLE else View.GONE
        if (::btnNext.isInitialized) btnNext.visibility = if (visible) View.VISIBLE else View.GONE
        if (::tvEmptyHint.isInitialized) {
            tvEmptyHint.visibility = if (visible) View.GONE else View.VISIBLE
        }
        // 状态文字必须始终可见（提示操作结果）
        if (::tvStatus.isInitialized) tvStatus.visibility = View.VISIBLE
        tvResult.text = if (visible) "命中 ${hits.size} 个" else ""
        hitAdapter?.submit(hits)
        syncSelectAll()
    }

    private fun setBusy(value: Boolean, message: String? = null) {
        if (message != null && ::tvStatus.isInitialized) tvStatus.text = message
        if (::tvStatus.isInitialized) tvStatus.alpha = if (value) 0.5f else 1f
    }

    private fun clearResults() {
        // 整套流程归零：结果、输入、选中、按钮、状态提示
        ScanSession.clearHits()
        ScanSession.lastValue = ""
        hitAdapter?.submit(emptyList())
        renderHits(emptyList())
        syncSelectAll()
        if (::etValue.isInitialized) {
            etValue.setText("")
            etValue.setSelection(0)
        }
        if (::tvStatus.isInitialized) tvStatus.text = "就绪"
        if (::keyPanel.isInitialized) keyPanel.visibility = View.GONE
        if (::tvEmptyHint.isInitialized) tvEmptyHint.visibility = View.VISIBLE
        if (::writeRow.isInitialized) writeRow.visibility = View.GONE
    }

    // ------------------------------------------------------------ 改写

    private fun showWriteRow(hit: ScanHit) {
        writeAddress = hit.address
        tvWriteAddr.text = "0x${hit.address.toString(16)}"
        etWriteValue.setText(hit.value)
        etWriteValue.setSelection(etWriteValue.text.length)
        writeRow.visibility = View.VISIBLE
    }

    private fun hideWriteRow() {
        if (::writeRow.isInitialized) writeRow.visibility = View.GONE
    }

    private fun doWrite() {
        val target = ScanSession.target ?: return
        val hit = ScanSession.hits.firstOrNull { it.address == writeAddress }
        if (hit == null) {
            val msg = "地址已不在当前结果里，请重新搜索"
            toast(msg)
            tvStatus.text = msg
            hideWriteRow()
            return
        }
        val value = etWriteValue.text.toString().trim()
        if (value.isEmpty()) {
            toast("请输入数值")
            return
        }
        // 用搜索时的类型，避免用户中途切换类型导致按错误宽度写入
        val type = ScanSession.type ?: currentType

        scope.launch {
            val scanner = ScanSession.ensure(this@OverlayService)
            val ok = scanner.write(target.pid, type, writeAddress, value)
            if (!ok) {
                val msg = "写入失败：地址 0x${writeAddress.toString(16)} 可能已失效"
                toast(msg)
                tvStatus.text = msg
                return@launch
            }
            val back = scanner.read(target.pid, type, writeAddress)
            val msg = if (back == null) "已写入 0x${writeAddress.toString(16)}" else "已写入，当前值 $back"
            toast(msg)
            tvStatus.text = msg
            hideWriteRow()
            if (::keyPanel.isInitialized) keyPanel.visibility = View.GONE
            if (back != null) {
                val updated = ScanSession.hits.map {
                    if (it.address == writeAddress) it.copy(value = back) else it
                }
                ScanSession.hits = updated
                renderHits(updated)
            }
        }
    }

    private fun showWriteAllDialog() {
        val target = ScanSession.target
        if (target == null) {
            toast("请先选择进程")
            return
        }
        val checked = hitAdapter?.checkedItems().orEmpty()
        val hits = if (checked.isNotEmpty()) checked else ScanSession.hits
        if (hits.isEmpty()) {
            toast("还没有命中结果")
            return
        }
        val scopeText = if (checked.isNotEmpty()) "选中的 ${hits.size} 个" else "全部 ${hits.size} 个"
        WashiDialog(
            themedContext,
            title = "修改",
            message = "把这 $scopeText 地址改成",
            inputHint = "新数值",
            inputValue = hits.first().value,
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or
                android.text.InputType.TYPE_NUMBER_FLAG_SIGNED,
            positiveText = "修改",
            onPositive = { text -> doWriteAll(hits, text) },
            negativeText = "取消",
            onNegative = {
                if (::tvStatus.isInitialized) {
                    tvStatus.text = "已取消，命中 ${hits.size} 个"
                }
            },
            overlayType = overlayType(),
        ).show()
    }

    private fun doWriteAll(hits: List<ScanHit>, text: String?) {
        val target = ScanSession.target
        val v = text?.trim().orEmpty()
        if (target == null) {
            toast("请先选择进程")
            return
        }
        if (v.isEmpty()) {
            toast("请输入数值")
            return
        }
        val type = currentType
        busy = true
        tvStatus.text = "正在修改 ${hits.size} 个地址…"
        scope.launch {
            try {
                val scanner = ScanSession.ensure(this@OverlayService)
                val (ok, total) = scanner.writeAll(target.pid, type, v, hits.map { it.address })
                val refreshed = scanner.readAll(target.pid, type, hits.map { it.address })
                ScanSession.hits = refreshed
                renderHits(refreshed)
                toast("已修改 $ok / $total")
            } finally {
                busy = false
            }
        }
    }

    private fun refreshValues() {
        android.util.Log.d("MemToolDebug", "refreshValues clicked")
        if (busy) {
            toast("正在处理中，请稍候…")
            return
        }
        val target = ScanSession.target
        if (target == null) {
            toast("请先选择进程")
            tvStatus.text = "请先选择进程"
            return
        }
        val hits = ScanSession.hits
        if (hits.isEmpty()) {
            toast("还没有搜索结果")
            tvStatus.text = "还没有搜索结果"
            return
        }
        val type = currentType

        busy = true
        if (::btnRefreshView.isInitialized) {
            btnRefreshView.text = "刷新中…"
            btnRefreshView.isEnabled = false
        }
        tvStatus.text = "正在刷新 ${hits.size} 个地址…"
        val loading = WashiDialog(
            themedContext,
            title = "请稍候",
            message = "正在刷新 ${hits.size} 个地址…",
            positiveText = "",
            overlayType = overlayType(),
        ).apply {
            setCancelable(false)
            show()
        }
        scope.launch {
            try {
            val refreshed = try {
                ScanSession.ensure(this@OverlayService)
                    .readAll(target.pid, type, hits.map { it.address })
            } catch (e: Exception) {
                android.util.Log.e("MemToolDebug", "refresh failed", e)
                emptyList()
            }
            ScanSession.hits = refreshed
            renderHits(refreshed)
            val msg = if (refreshed.isEmpty()) {
                "地址已全部失效，请重新搜索"
            } else {
                "已刷新：${refreshed.size} 个有效，当前值 " +
                    refreshed.take(3).joinToString(", ") { it.value }
            }
            tvStatus.text = msg
            toast(msg)
            } finally {
                busy = false
                if (::btnRefreshView.isInitialized) {
                    btnRefreshView.text = "刷新"
                    btnRefreshView.isEnabled = true
                }
                loading.dismiss()
            }
        }
    }

    // ------------------------------------------------------------ 地址簿

    private fun saveAddresses() {
        val target = ScanSession.target
        if (target == null) {
            toast("请先选择进程")
            return
        }
        val hits = hitAdapter?.checkedItems().orEmpty().ifEmpty { ScanSession.hits }
        if (hits.isEmpty()) {
            toast("没有可保存的地址，请先搜索")
            return
        }
        WashiDialog(
            themedContext,
            title = "保存",
            message = "把这 ${hits.size} 个地址存入地址簿",
            inputHint = "起个名字，例如：金币",
            positiveText = "存入",
            onPositive = { text ->
                val note = text?.trim().orEmpty()
                val type = currentType.cli
                val pkg = target.packageName ?: target.rawName
                val existing = SavedStore.load(this).toMutableList()
                if (note.isNotEmpty()) {
                    existing.removeAll { it.pkg == pkg && it.type == type && it.note == note }
                }
                var added = 0
                hits.take(100).forEach { h ->
                    if (existing.none { it.pkg == pkg && it.type == type && it.addr == h.address }) {
                        existing.add(SavedStore.Item(pkg, type, h.address, note, h.value))
                        added++
                    }
                }
                SavedStore.save(this, existing)
                toast("已存入 ${added} 个，地址簿共 ${existing.size} 条")
            },
            negativeText = "取消",
            overlayType = overlayType(),
        ).show()
    }

    private fun showSavedList() {
        val target = ScanSession.target
        val pkg = target?.packageName ?: target?.rawName
        val all = SavedStore.load(this)
        val items = if (pkg != null) all.filter { it.pkg == pkg } else all
        if (items.isEmpty()) {
            val msg = if (pkg != null) {
                "当前对象（${target?.displayName ?: "未选择"}）还没有保存过地址。\n\n" +
                    "先搜索数值，再点「保存」存入地址簿。"
            } else {
                "还没有选择对象，地址簿里也没有任何记录。\n\n" +
                    "先点「切换」选择游戏，搜索后点「保存」。"
            }
            WashiDialog(
                themedContext,
                title = "地址簿是空的",
                message = msg,
                positiveText = "知道了",
                overlayType = overlayType(),
            ).show()
            tvStatus.text = "地址簿为空"
            return
        }
        scanPanel.visibility = View.GONE
        processPicker.visibility = View.GONE
        savedPanel.visibility = View.VISIBLE
        savedAdapter?.submit(items)
        updateSavedCount()
    }

    private fun updateSavedCount() {
        if (!::tvSavedCount.isInitialized) return
        val total = savedAdapter?.items()?.size ?: 0
        val checked = savedAdapter?.checkedCount() ?: 0
        tvSavedCount.text = "共 ${total} 条，已选 $checked"
        val all = savedAdapter?.isAllChecked() ?: false
        if (cbSavedAll.isChecked != all) {
            cbSavedAll.setOnCheckedChangeListener(null)
            cbSavedAll.isChecked = all
            cbSavedAll.setOnCheckedChangeListener { _, c ->
                savedAdapter?.setAllChecked(c)
            }
        }
    }

    private fun deleteSavedSelected() {
        val items = savedAdapter?.checkedItems().orEmpty()
        if (items.isEmpty()) {
            toast("没有选中地址")
            return
        }
        val remaining = SavedStore.load(this).filterNot { r ->
            items.any { it.pkg == r.pkg && it.type == r.type && it.addr == r.addr }
        }
        SavedStore.save(this, remaining)
        toast("已删除 ${items.size} 条")
        val target = ScanSession.target
        val pkg = target?.packageName ?: target?.rawName
        val list = if (pkg != null) SavedStore.load(this).filter { it.pkg == pkg } else SavedStore.load(this)
        savedAdapter?.submit(list)
        updateSavedCount()
    }

    private fun refreshSaved() {
        val target = ScanSession.target
        if (target == null) {
            toast("请先选择进程再刷新")
            return
        }
        val items = savedAdapter?.items().orEmpty()
        if (items.isEmpty()) return
        scope.launch {
            toast("正在检查 ${items.size} 条地址…")
            val scanner = ScanSession.ensure(this@OverlayService)
            val updated = items.map { item ->
                val type = ScanType.entries.firstOrNull { it.cli == item.type } ?: currentType
                val v = try { scanner.read(target.pid, type, item.addr) } catch (e: Exception) { null }
                if (v == null) item.copy(value = "失效") else item.copy(value = v)
            }
            savedAdapter?.submit(updated)
            updateSavedCount()
            val dead = updated.count { it.value == "失效" }
            toast(if (dead == 0) "全部有效" else "$dead 条已失效")
        }
    }

    private fun batchEditSaved() {
        val items = savedAdapter?.checkedItems().orEmpty()
        if (items.isEmpty()) {
            toast("没有选中地址")
            return
        }
        WashiDialog(
            themedContext,
            title = "批量修改",
            message = "把选中的 ${items.size} 条地址改成",
            inputHint = "新数值",
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or
                android.text.InputType.TYPE_NUMBER_FLAG_SIGNED,
            positiveText = "写入",
            onPositive = { text -> doBatchEditSaved(items, text) },
            negativeText = "取消",
            overlayType = overlayType(),
        ).show()
    }

    private fun doBatchEditSaved(items: List<SavedStore.Item>, text: String?) {
        val target = ScanSession.target
        val v = text?.trim().orEmpty()
        if (target == null) {
            toast("请先选择进程再写入")
            return
        }
        if (v.isEmpty()) return
        busy = true
        tvStatus.text = "正在写入 ${items.size} 条…"
        scope.launch {
            try {
                val scanner = ScanSession.ensure(this@OverlayService)
                var ok = 0
                items.forEach { item ->
                    val type = ScanType.entries.firstOrNull { it.cli == item.type } ?: currentType
                    if (scanner.write(target.pid, type, item.addr, v)) ok++
                }
                toast("已写入 $ok / ${items.size}")
            } finally {
                busy = false
            }
        }
    }

    private fun showSavedEdit(item: SavedStore.Item) {
        WashiDialog(
            themedContext,
            title = "修改",
            message = "地址 0x${item.addr.toString(16)}，当前值 ${item.value}",
            inputHint = "输入新的数值",
            inputValue = item.value,
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or
                android.text.InputType.TYPE_NUMBER_FLAG_SIGNED,
            positiveText = "写入",
            onPositive = { text -> doSavedWrite(item, text) },
            negativeText = "取消",
            neutralText = "删除",
            onNeutral = { doSavedDelete(item) },
            overlayType = overlayType(),
        ).show()
    }

    private fun doSavedWrite(item: SavedStore.Item, text: String?) {
        val target = ScanSession.target
        val v = text?.trim().orEmpty()
        if (target == null) {
            toast("请先选择进程再写入")
            return
        }
        if (v.isEmpty()) {
            toast("请输入数值")
            return
        }
        val type = ScanType.entries.firstOrNull { it.cli == item.type } ?: currentType
        scope.launch {
            val scanner = ScanSession.ensure(this@OverlayService)
            val ok = scanner.write(target.pid, type, item.addr, v)
            if (!ok) {
                toast("写入失败：地址可能已失效")
                return@launch
            }
            val back = scanner.read(target.pid, type, item.addr)
            toast(if (back == null) "写入成功（读回失败）" else "已写入，当前值 $back")
            if (back != null) {
                val updated = savedAdapter?.items().orEmpty().map {
                    if (it.pkg == item.pkg && it.type == item.type && it.addr == item.addr)
                        it.copy(value = back) else it
                }
                savedAdapter?.submit(updated)
                updateSavedCount()
            }
        }
    }

    private fun doSavedDelete(item: SavedStore.Item) {
        val list = SavedStore.load(this).filterNot {
            it.pkg == item.pkg && it.type == item.type && it.addr == item.addr
        }
        SavedStore.save(this, list)
        toast("已删除")
        val target = ScanSession.target
        val pkg = target?.packageName ?: target?.rawName
        val remain = if (pkg != null) SavedStore.load(this).filter { it.pkg == pkg }
                     else SavedStore.load(this)
        savedAdapter?.submit(remain)
        updateSavedCount()
    }

    // ------------------------------------------------------------ 工具

    private fun overlayType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }
}
