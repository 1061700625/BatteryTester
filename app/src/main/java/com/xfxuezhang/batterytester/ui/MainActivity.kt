package com.xfxuezhang.batterytester.ui

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.xfxuezhang.batterytester.battery.BatteryCapability
import com.xfxuezhang.batterytester.battery.BatteryMode
import com.xfxuezhang.batterytester.battery.BatterySampler
import com.xfxuezhang.batterytester.battery.BatterySnapshot
import com.xfxuezhang.batterytester.battery.StopReason
import com.xfxuezhang.batterytester.data.BatteryRepository
import com.xfxuezhang.batterytester.data.BatterySample
import com.xfxuezhang.batterytester.data.BatterySession
import com.xfxuezhang.batterytester.export.CsvExporter
import com.xfxuezhang.batterytester.load.NetworkTransferLoad
import com.xfxuezhang.batterytester.service.BatteryTestService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.abs

class MainActivity : ComponentActivity() {
    private lateinit var repository: BatteryRepository
    private lateinit var sampler: BatterySampler
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var refreshJob: Job? = null
    private var currentScreen: Screen = Screen.HOME
    private var currentDetailSessionId: String? = null
    private var originalBrightness: Float? = null
    private var loadCpuChecked: Boolean = true
    private var loadGpuChecked: Boolean = true
    private var loadBrightnessChecked: Boolean = true
    private var loadNetworkChecked: Boolean = false
    private var networkUrlValue: String = NetworkTransferLoad.DEFAULT_DOWNLOAD_URL
    private var networkLimitOptionValue: String = LIMIT_UNLIMITED
    private var networkCustomLimitMbValue: String = "500"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = BatteryRepository.get(this)
        sampler = BatterySampler(this)
        loadOptionsFromPrefs()
        requestNotificationPermissionIfNeeded()
        window.statusBarColor = color("#F7FAFC")
        window.navigationBarColor = color("#F7FAFC")
        showHome()
    }

    override fun onResume() {
        super.onResume()
        applyScreenLoadIfNeeded()
    }

    override fun onPause() {
        super.onPause()
        clearScreenLoad()
    }

    override fun onDestroy() {
        refreshJob?.cancel()
        uiScope.cancel()
        clearScreenLoad()
        super.onDestroy()
    }

    override fun onBackPressed() {
        when (currentScreen) {
            Screen.HOME -> super.onBackPressed()
            Screen.HISTORY -> showHome()
            Screen.DETAIL -> showHistory()
        }
    }

    private fun showHome() {
        currentScreen = Screen.HOME
        currentDetailSessionId = null
        refreshJob?.cancel()

        val root = verticalRoot()
        root.addView(heroHeader())

        val statusCard = card()
        val statusText = body("正在读取电池状态...")
        statusCard.addView(sectionTitle("当前电池状态"))
        statusCard.addView(statusText)
        root.addView(statusCard)

        val activeCard = card()
        val activeText = body("正在检测测试状态...")
        activeCard.addView(sectionTitle("当前测试"))
        activeCard.addView(activeText)
        val stopButton = primaryButton("停止当前测试") { stopCurrentTest() }
        activeCard.addView(stopButton)
        root.addView(activeCard)

        val capabilityCard = card()
        val capabilityText = body("正在检测兼容性...")
        capabilityCard.addView(sectionTitle("指标兼容性"))
        capabilityCard.addView(capabilityText)
        root.addView(capabilityCard)

        val actionCard = card()
        actionCard.addView(sectionTitle("测试入口"))
        actionCard.addView(body("选择测试模式后，应用会保持当前测试页常亮，采样曲线会持续写入本地数据库。"))
        actionCard.addView(space(10))
        actionCard.addView(loadOptionsView())
        actionCard.addView(space(12))
        actionCard.addView(primaryButton("⚡ 开始放电测试") { confirmStartDischarge() })
        actionCard.addView(space(10))
        actionCard.addView(accentButton("🔌 开始充电记录") { confirmStartChargeRecord() })
        actionCard.addView(space(10))
        actionCard.addView(secondaryButton("📈 查看历史曲线") { showHistory() })
        root.addView(actionCard)

        val safetyCard = card()
        safetyCard.addView(sectionTitle("安全策略"))
        safetyCard.addView(body("放电测试支持 CPU 计算、GPU 计算、屏幕亮度、网络传输四类负载组件。CPU 和网络负载会按温度自动降载或停止；GPU 和屏幕亮度仅在 App 前台界面生效。电量低于 15%、电池温度达到 48℃、或系统热状态达到严重级别时会自动停止。"))
        root.addView(safetyCard)

        setContentView(scroll(root))

        refreshJob = uiScope.launch {
            val capabilitySamples = mutableListOf<BatterySnapshot>()
            while (isActive) {
                val snapshot = withContext(Dispatchers.Default) { sampler.sample() }
                if (capabilitySamples.size < 5) capabilitySamples += snapshot
                val activeSession = withContext(Dispatchers.IO) { repository.getActiveSession() }
                val latestActiveSample = activeSession?.let { withContext(Dispatchers.IO) { repository.getLatestSample(it.id) } }
                statusText.text = snapshot.toDisplayText()
                activeText.text = activeSession?.toActiveDisplayText(latestActiveSample) ?: "没有正在运行的测试。"
                stopButton.visibility = if (activeSession != null) View.VISIBLE else View.GONE
                capabilityText.text = BatteryCapability.fromSamples(capabilitySamples).toDisplayText()
                applyScreenLoadIfNeeded(activeSession)
                delay(1000)
            }
        }
    }

    private fun showHistory() {
        currentScreen = Screen.HISTORY
        currentDetailSessionId = null
        refreshJob?.cancel()
        clearScreenLoad()

        val root = verticalRoot()
        root.addView(title("历史记录"))
        root.addView(space(8))
        root.addView(secondaryButton("返回首页") { showHome() })
        root.addView(space(12))

        val sessions = repository.getSessions()
        if (sessions.isEmpty()) {
            root.addView(body("暂无测试记录。"))
        } else {
            root.addView(body("长按某一条历史记录可删除该记录和对应采样数据。"))
            root.addView(space(8))
            sessions.forEach { session ->
                val c = historyCard(session)
                root.addView(c)
            }
        }
        setContentView(scroll(root))
        applyScreenLoadIfNeeded()
    }

    private fun historyCard(session: BatterySession): LinearLayout {
        return card().apply {
            isLongClickable = true
            addView(sectionTitle(session.mode.displayName()))
            addView(body(session.toHistoryText()))
            addView(secondaryButton("查看曲线") { showDetail(session.id) })
            setOnLongClickListener {
                confirmDeleteSession(session)
                true
            }
        }
    }

    private fun confirmDeleteSession(session: BatterySession) {
        val activeSession = repository.getActiveSession()
        if (activeSession?.id == session.id || session.endTime == null) {
            Toast.makeText(this, "正在进行的测试不能删除，请先停止测试。", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("删除历史记录")
            .setMessage("确定删除这条${session.mode.displayName()}记录吗？对应采样数据也会一起删除，删除后无法恢复。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                uiScope.launch {
                    val deleted = withContext(Dispatchers.IO) { repository.deleteSession(session.id) }
                    if (deleted) {
                        Toast.makeText(this@MainActivity, "记录已删除", Toast.LENGTH_SHORT).show()
                        showHistory()
                    } else {
                        Toast.makeText(this@MainActivity, "删除失败，记录可能已不存在", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .show()
    }

    private fun showDetail(sessionId: String) {
        currentScreen = Screen.DETAIL
        currentDetailSessionId = sessionId
        refreshJob?.cancel()
        clearScreenLoad()

        val root = verticalRoot()
        root.addView(title("测试详情"))
        root.addView(space(8))
        root.addView(secondaryButton("返回历史记录") { showHistory() })
        root.addView(space(12))

        val sessionText = body("正在读取...")
        val summaryText = body("正在统计...")
        val infoCard = card()
        infoCard.addView(sectionTitle("摘要"))
        infoCard.addView(sessionText)
        infoCard.addView(summaryText)
        infoCard.addView(space(8))
        infoCard.addView(primaryButton("导出 CSV") { exportSession(sessionId) })
        root.addView(infoCard)
        if (shouldShowGpuLoad(sessionId)) {
            val gpuCard = card()
            gpuCard.addView(sectionTitle("GPU 动画负载"))
            gpuCard.addView(body("GPU 计算已启用。此区域会持续绘制粒子动画，用于增加前台图形渲染负载；温度升高时请优先停止测试或取消 GPU 负载。"))
            gpuCard.addView(space(8))
            gpuCard.addView(GpuLoadView(this).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(132))
                background = roundedBackground("#F8FBFF", dp(12), strokeColor = "#E2E8F0", strokeWidth = 1)
            })
            root.addView(gpuCard)
        }

        val levelChart = chartView()
        val currentChart = chartView()
        val tempChart = chartView()
        val voltageChart = chartView()
        val powerChart = chartView()
        val cpuUsageChart = chartView()
        val cpuLoadChart = chartView()
        val networkDownloadedChart = chartView()

        root.addView(chartCard(levelChart))
        root.addView(chartCard(currentChart))
        root.addView(chartCard(tempChart))
        root.addView(chartCard(voltageChart))
        root.addView(chartCard(powerChart))
        root.addView(chartCard(cpuUsageChart))
        root.addView(chartCard(cpuLoadChart))
        root.addView(chartCard(networkDownloadedChart))

        setContentView(detailContentView(root, sessionId))

        refreshJob = uiScope.launch {
            while (isActive) {
                val session = withContext(Dispatchers.IO) { repository.getSession(sessionId) }
                val samples = withContext(Dispatchers.IO) { repository.getSamples(sessionId) }
                val activeSession = withContext(Dispatchers.IO) { repository.getActiveSession() }
                applyScreenLoadIfNeeded(activeSession)
                if (session == null) {
                    sessionText.text = "正在等待测试记录创建..."
                    delay(500)
                    continue
                }
                if (session.endTime != null && shouldShowGpuLoad(sessionId)) {
                    clearActiveLoadOptions()
                    showDetail(sessionId)
                    return@launch
                }
                sessionText.text = session.toDetailText(samples)
                summaryText.text = samples.summaryText(session)
                levelChart.setData(samples, ChartMetric.LEVEL)
                currentChart.setData(samples, ChartMetric.CURRENT)
                tempChart.setData(samples, ChartMetric.TEMPERATURE)
                voltageChart.setData(samples, ChartMetric.VOLTAGE)
                powerChart.setData(samples, ChartMetric.POWER)
                cpuUsageChart.setData(samples, ChartMetric.CPU_USAGE)
                cpuLoadChart.setData(samples, ChartMetric.CPU_LOAD_TARGET)
                networkDownloadedChart.setData(samples, ChartMetric.NETWORK_DOWNLOADED_MB)
                delay(if (session.endTime == null) 1500 else 5000)
            }
        }
    }

    private fun confirmStartDischarge() {
        saveLoadOptionsToPrefs()
        if (loadNetworkChecked) {
            val validationError = validateNetworkConfig()
            if (validationError != null) {
                Toast.makeText(this, validationError, Toast.LENGTH_LONG).show()
                return
            }
            AlertDialog.Builder(this)
                .setTitle("确认网络传输负载")
                .setMessage(networkConfirmMessage())
                .setPositiveButton("确认启用") { _, _ -> showDischargeConfirmDialog() }
                .setNegativeButton("取消", null)
                .show()
        } else {
            showDischargeConfirmDialog()
        }
    }

    private fun showDischargeConfirmDialog() {
        AlertDialog.Builder(this)
            .setTitle("开始放电测试")
            .setMessage(dischargeConfirmMessage())
            .setPositiveButton("开始") { _, _ -> startTest(BatteryMode.DISCHARGE) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmStartChargeRecord() {
        AlertDialog.Builder(this)
            .setTitle("开始充电记录")
            .setMessage("充电记录只采集电量、电流、电压、温度等数据，不会主动制造负载。部分设备可能不提供某些指标，相关字段会显示为空。")
            .setPositiveButton("开始") { _, _ -> startTest(BatteryMode.CHARGE_RECORD) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun startTest(mode: BatteryMode) {
        val sessionId = UUID.randomUUID().toString()
        val loadCpu = mode == BatteryMode.DISCHARGE && loadCpuChecked
        val loadGpu = mode == BatteryMode.DISCHARGE && loadGpuChecked
        val loadBrightness = mode == BatteryMode.DISCHARGE && loadBrightnessChecked
        val loadNetwork = mode == BatteryMode.DISCHARGE && loadNetworkChecked
        val networkLimitBytes = if (loadNetwork) networkLimitBytesFromInput() else null
        saveActiveLoadOptions(sessionId, loadCpu, loadGpu, loadBrightness, loadNetwork)
        ContextCompat.startForegroundService(
            this,
            BatteryTestService.startIntent(
                this,
                mode,
                sessionId,
                loadCpu = loadCpu,
                loadGpu = loadGpu,
                loadScreenBrightness = loadBrightness,
                loadNetwork = loadNetwork,
                networkUrl = networkUrlValue.trim().ifBlank { NetworkTransferLoad.DEFAULT_DOWNLOAD_URL },
                networkLimitBytes = networkLimitBytes
            )
        )
        keepScreenAwakeImmediately(mode)
        Toast.makeText(this, "测试已启动，运行期间将保持屏幕常亮", Toast.LENGTH_SHORT).show()
        showDetail(sessionId)
    }

    private fun stopCurrentTest() {
        startService(BatteryTestService.stopIntent(this))
        clearScreenLoad()
        clearActiveLoadOptions()
        Toast.makeText(this, "已请求停止测试", Toast.LENGTH_SHORT).show()
    }

    private fun exportSession(sessionId: String) {
        val session = repository.getSession(sessionId)
        if (session == null) {
            Toast.makeText(this, "未找到测试记录", Toast.LENGTH_SHORT).show()
            return
        }
        val samples = repository.getSamples(sessionId)
        val file = CsvExporter.export(this, session, samples)
        val intent = CsvExporter.shareIntent(this, file)
        startActivity(Intent.createChooser(intent, "分享 CSV"))
    }

    private fun loadOptionsView(): LinearLayout {
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedBackground("#F8FAFC", dp(12), strokeColor = "#E2E8F0", strokeWidth = 1)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 0)
        }
        val headerText = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val summaryText = TextView(this).apply {
            textSize = 12f
            setTextColor(color("#64748B"))
        }
        val toggleText = TextView(this).apply {
            text = "展开"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(color("#0369A1"))
            setPadding(dp(10), dp(5), dp(10), dp(5))
            background = roundedBackground("#DDF3FF", dp(999))
        }
        val detail = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }

        fun refreshSummary() {
            summaryText.text = "当前选择：${selectedLoadOptionsText()}"
        }

        fun toggle() {
            val expanded = detail.visibility != View.VISIBLE
            detail.visibility = if (expanded) View.VISIBLE else View.GONE
            toggleText.text = if (expanded) "收起" else "展开"
        }

        headerText.addView(TextView(this).apply {
            text = "放电负载组件"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(color("#1E293B"))
        })
        headerText.addView(summaryText)
        header.addView(headerText)
        header.addView(toggleText)
        header.setOnClickListener { toggle() }
        toggleText.setOnClickListener { toggle() }

        detail.addView(space(10))
        detail.addView(loadCheckBox("CPU 计算", "浮点与混合计算负载，按温度自动调节。", loadCpuChecked) {
            loadCpuChecked = it
            saveLoadOptionsToPrefs()
            refreshSummary()
        })
        detail.addView(loadCheckBox("GPU 计算", "前台粒子动画渲染，接近游戏类图形负载。", loadGpuChecked) {
            loadGpuChecked = it
            saveLoadOptionsToPrefs()
            refreshSummary()
        })
        detail.addView(loadCheckBox("屏幕亮度", "放电测试期间保持屏幕常亮，并将当前测试页亮度提升到最高。", loadBrightnessChecked) {
            loadBrightnessChecked = it
            saveLoadOptionsToPrefs()
            refreshSummary()
        })
        detail.addView(loadCheckBox("网络传输", "默认关闭。启用后会反复下载小数据块，可能消耗移动流量。", loadNetworkChecked) {
            loadNetworkChecked = it
            saveLoadOptionsToPrefs()
            refreshSummary()
        })
        detail.addView(space(8))
        detail.addView(networkConfigView())

        refreshSummary()
        outer.addView(header)
        outer.addView(detail)
        return outer
    }

    private fun networkConfigView(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = roundedBackground("#FFFFFF", dp(10), strokeColor = "#E2E8F0", strokeWidth = 1)
        addView(TextView(this@MainActivity).apply {
            text = "网络传输设置"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(color("#334155"))
        })
        addView(space(6))
        addView(smallLabel("下载地址"))
        addView(EditText(this@MainActivity).apply {
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(networkUrlValue)
            textSize = 13f
            setTextColor(color("#1E293B"))
            setHintTextColor(color("#94A3B8"))
            hint = NetworkTransferLoad.DEFAULT_DOWNLOAD_URL
            background = roundedBackground("#F8FAFC", dp(8), strokeColor = "#CBD5E1", strokeWidth = 1)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            addTextChangedListener(simpleWatcher {
                networkUrlValue = it
                saveLoadOptionsToPrefs()
            })
        })
        addView(space(8))
        addView(smallLabel("流量上限"))

        val radios = mutableMapOf<String, RadioButton>()
        lateinit var customInput: EditText

        fun updateLimitSelection() {
            radios.forEach { (option, radio) -> radio.isChecked = option == networkLimitOptionValue }
            customInput.visibility = if (networkLimitOptionValue == LIMIT_CUSTOM) View.VISIBLE else View.GONE
        }

        fun addLimitOption(option: String, title: String, description: String) {
            val row = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), 0, dp(4))
            }
            val radio = RadioButton(this@MainActivity).apply {
                isChecked = option == networkLimitOptionValue
                setOnClickListener {
                    networkLimitOptionValue = option
                    updateLimitSelection()
                    saveLoadOptionsToPrefs()
                }
            }
            radios[option] = radio
            val textColumn = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            textColumn.addView(TextView(this@MainActivity).apply {
                text = title
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(color("#1E293B"))
            })
            textColumn.addView(TextView(this@MainActivity).apply {
                text = description
                textSize = 12f
                setTextColor(color("#64748B"))
            })
            row.setOnClickListener { radio.performClick() }
            row.addView(radio)
            row.addView(textColumn)
            addView(row)
        }

        addLimitOption(LIMIT_UNLIMITED, "不限制", "默认选项。网络负载会持续运行，直到温控或手动停止。")
        addLimitOption(LIMIT_100_MB, "100 MB", "轻量测试，适合移动网络下谨慎使用。")
        addLimitOption(LIMIT_500_MB, "500 MB", "中等测试，适合 Wi-Fi 下观察网络负载曲线。")
        addLimitOption(LIMIT_1_GB, "1 GB", "较长测试，建议仅在 Wi-Fi 或不限流量网络下使用。")
        addLimitOption(LIMIT_CUSTOM, "自定义", "输入自定义 MB 数值。")

        customInput = EditText(this@MainActivity).apply {
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(networkCustomLimitMbValue)
            textSize = 13f
            setTextColor(color("#1E293B"))
            setHintTextColor(color("#94A3B8"))
            hint = "500"
            background = roundedBackground("#F8FAFC", dp(8), strokeColor = "#CBD5E1", strokeWidth = 1)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            addTextChangedListener(simpleWatcher {
                networkCustomLimitMbValue = it
                saveLoadOptionsToPrefs()
            })
        }
        addView(customInput)
        addView(space(6))
        addView(TextView(this@MainActivity).apply {
            text = "网络传输默认关闭。启用前会再次确认，达到流量上限后只停止网络负载，不停止整次放电测试。"
            textSize = 12f
            setLineSpacing(3f, 1.05f)
            setTextColor(color("#64748B"))
        })
        updateLimitSelection()
    }

    private fun smallLabel(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 12f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(color("#64748B"))
        setPadding(0, 0, 0, dp(4))
    }

    private fun simpleWatcher(onText: (String) -> Unit): TextWatcher {
        return object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { onText(s?.toString().orEmpty()) }
            override fun afterTextChanged(s: Editable?) = Unit
        }
    }

    private fun validateNetworkConfig(): String? {
        val url = networkUrlValue.trim()
        if (url.isBlank()) return "网络传输地址不能为空"
        if (!url.startsWith("https://") && !url.startsWith("http://")) return "网络传输地址必须以 http:// 或 https:// 开头"
        if (networkLimitOptionValue == LIMIT_CUSTOM) {
            val limitText = networkCustomLimitMbValue.trim()
            if (limitText.isBlank()) return "自定义流量上限不能为空"
            if (limitText.toDoubleOrNull() == null) return "自定义流量上限必须是数字"
            if ((limitText.toDoubleOrNull() ?: 0.0) <= 0.0) return "自定义流量上限必须大于 0"
        }
        return null
    }

    private fun networkLimitBytesFromInput(): Long? {
        val mb = when (networkLimitOptionValue) {
            LIMIT_UNLIMITED -> return null
            LIMIT_100_MB -> 100.0
            LIMIT_500_MB -> 500.0
            LIMIT_1_GB -> 1024.0
            LIMIT_CUSTOM -> networkCustomLimitMbValue.trim().toDoubleOrNull() ?: return null
            else -> return null
        }
        return (mb * 1024.0 * 1024.0).toLong()
    }

    private fun networkLimitDisplayText(): String {
        return networkLimitBytesFromInput()?.let { formatBytes(it) } ?: "不限制"
    }

    private fun networkConfirmMessage(): String {
        val limitText = networkLimitDisplayText()
        return "网络传输负载会持续从指定地址下载数据，用于增加耗电。\n\n下载地址：${networkUrlValue.trim()}\n流量上限：$limitText\n\n如果当前使用移动网络，可能产生流量费用。达到上限后只会停止网络负载，CPU、GPU、屏幕亮度等其他负载会继续按温度策略运行。"
    }

    private fun loadCheckBox(title: String, description: String, checked: Boolean, onChanged: (Boolean) -> Unit): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        val checkBox = CheckBox(this).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, isChecked -> onChanged(isChecked) }
        }
        val textColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        textColumn.addView(TextView(this).apply {
            text = title
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(color("#1E293B"))
        })
        textColumn.addView(TextView(this).apply {
            text = description
            textSize = 12f
            setLineSpacing(3f, 1.05f)
            setTextColor(color("#64748B"))
        })
        row.addView(checkBox)
        row.addView(textColumn)
        return row
    }

    private fun dischargeConfirmMessage(): String {
        val selected = selectedLoadOptionsText()
        val networkText = if (loadNetworkChecked) "网络传输地址：${networkUrlValue.trim()}，流量上限：${networkLimitDisplayText()}。" else "网络传输未启用。"
        return "放电测试将启用：$selected。CPU 和网络负载会按温度自动降载；GPU 负载仅在 App 前台绘制；屏幕亮度负载会提升当前测试页亮度。$networkText 你可以随时在页面或通知栏停止测试。建议在 20% 以上电量开始。"
    }

    private fun selectedLoadOptionsText(): String {
        val items = mutableListOf<String>()
        if (loadCpuChecked) items += "CPU 计算"
        if (loadGpuChecked) items += "GPU 计算"
        if (loadBrightnessChecked) items += "屏幕亮度"
        if (loadNetworkChecked) items += "网络传输"
        return if (items.isEmpty()) "仅采样，不制造主动负载" else items.joinToString("、")
    }

    private fun activeLoadOptionsText(): String {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val items = mutableListOf<String>()
        if (prefs.getBoolean(PREF_ACTIVE_CPU, false)) items += "CPU 计算"
        if (prefs.getBoolean(PREF_ACTIVE_GPU, false)) items += "GPU 计算"
        if (prefs.getBoolean(PREF_ACTIVE_BRIGHTNESS, false)) items += "屏幕亮度"
        if (prefs.getBoolean(PREF_ACTIVE_NETWORK, false)) items += "网络传输"
        return if (items.isEmpty()) "仅采样" else items.joinToString("、")
    }

    private fun loadOptionsFromPrefs() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadCpuChecked = prefs.getBoolean(PREF_LOAD_CPU, true)
        loadGpuChecked = prefs.getBoolean(PREF_LOAD_GPU, true)
        loadBrightnessChecked = prefs.getBoolean(PREF_LOAD_BRIGHTNESS, true)
        loadNetworkChecked = prefs.getBoolean(PREF_LOAD_NETWORK, false)
        networkUrlValue = prefs.getString(PREF_NETWORK_URL, NetworkTransferLoad.DEFAULT_DOWNLOAD_URL) ?: NetworkTransferLoad.DEFAULT_DOWNLOAD_URL
        networkLimitOptionValue = prefs.getString(PREF_NETWORK_LIMIT_OPTION, LIMIT_UNLIMITED) ?: LIMIT_UNLIMITED
        if (networkLimitOptionValue !in setOf(LIMIT_UNLIMITED, LIMIT_100_MB, LIMIT_500_MB, LIMIT_1_GB, LIMIT_CUSTOM)) {
            networkLimitOptionValue = LIMIT_UNLIMITED
        }
        networkCustomLimitMbValue = prefs.getString(PREF_NETWORK_CUSTOM_LIMIT_MB, "500") ?: "500"
    }

    private fun saveLoadOptionsToPrefs() {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(PREF_LOAD_CPU, loadCpuChecked)
            .putBoolean(PREF_LOAD_GPU, loadGpuChecked)
            .putBoolean(PREF_LOAD_BRIGHTNESS, loadBrightnessChecked)
            .putBoolean(PREF_LOAD_NETWORK, loadNetworkChecked)
            .putString(PREF_NETWORK_URL, networkUrlValue.trim().ifBlank { NetworkTransferLoad.DEFAULT_DOWNLOAD_URL })
            .putString(PREF_NETWORK_LIMIT_OPTION, networkLimitOptionValue)
            .putString(PREF_NETWORK_CUSTOM_LIMIT_MB, networkCustomLimitMbValue.trim())
            .apply()
    }

    private fun saveActiveLoadOptions(sessionId: String, cpu: Boolean, gpu: Boolean, brightness: Boolean, network: Boolean) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(PREF_ACTIVE_SESSION_ID, sessionId)
            .putBoolean(PREF_ACTIVE_CPU, cpu)
            .putBoolean(PREF_ACTIVE_GPU, gpu)
            .putBoolean(PREF_ACTIVE_BRIGHTNESS, brightness)
            .putBoolean(PREF_ACTIVE_NETWORK, network)
            .apply()
    }

    private fun clearActiveLoadOptions() {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .remove(PREF_ACTIVE_SESSION_ID)
            .remove(PREF_ACTIVE_CPU)
            .remove(PREF_ACTIVE_GPU)
            .remove(PREF_ACTIVE_BRIGHTNESS)
            .remove(PREF_ACTIVE_NETWORK)
            .apply()
    }

    private fun shouldShowGpuLoad(sessionId: String): Boolean {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val activeSessionId = repository.getActiveSession()?.id
        return activeSessionId == sessionId &&
            prefs.getString(PREF_ACTIVE_SESSION_ID, null) == sessionId &&
            prefs.getBoolean(PREF_ACTIVE_GPU, false)
    }

    private fun isActiveBrightnessLoadEnabled(): Boolean {
        return getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(PREF_ACTIVE_BRIGHTNESS, false)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
            }
        }
    }

    private fun keepScreenAwakeImmediately(mode: BatteryMode) {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val attrs = window.attributes
        if (originalBrightness == null) originalBrightness = attrs.screenBrightness
        if (mode == BatteryMode.DISCHARGE && isActiveBrightnessLoadEnabled()) attrs.screenBrightness = 1.0f
        window.attributes = attrs
    }

    private fun applyScreenLoadIfNeeded(activeSession: BatterySession? = repository.getActiveSession()) {
        if (activeSession == null) {
            clearScreenLoad()
            return
        }

        // 测试运行期间保持屏幕常亮。Android 官方建议在 Activity 中使用 FLAG_KEEP_SCREEN_ON。
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val attrs = window.attributes
        if (originalBrightness == null) originalBrightness = attrs.screenBrightness

        if (activeSession.mode == BatteryMode.DISCHARGE && isActiveBrightnessLoadEnabled()) {
            attrs.screenBrightness = 1.0f
        } else {
            originalBrightness?.let { attrs.screenBrightness = it }
        }
        window.attributes = attrs
    }

    private fun clearScreenLoad() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        originalBrightness?.let {
            val attrs = window.attributes
            attrs.screenBrightness = it
            window.attributes = attrs
        }
        originalBrightness = null
    }

    private fun scroll(root: LinearLayout): ScrollView = ScrollView(this).apply {
        setBackgroundColor(color("#F7FAFC"))

        val baseLeft = root.paddingLeft
        val baseTop = root.paddingTop
        val baseRight = root.paddingRight
        val baseBottom = root.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val safeInsets = insets.getInsets(
                WindowInsetsCompat.Type.statusBars() or
                    WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.navigationBars()
            )
            root.setPadding(
                baseLeft,
                baseTop + safeInsets.top,
                baseRight,
                baseBottom + safeInsets.bottom
            )
            insets
        }

        addView(root)
        ViewCompat.requestApplyInsets(this)
    }

    private fun detailContentView(root: LinearLayout, sessionId: String): View {
        val content = scroll(root)
        if (!shouldShowGpuLoad(sessionId)) return content

        return FrameLayout(this).apply {
            setBackgroundColor(color("#F7FAFC"))
            addView(GpuLoadView(this@MainActivity).apply {
                alpha = 0.22f
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                isClickable = false
            })
            addView(content, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
        }
    }

    private fun verticalRoot(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(28))
    }

    private fun heroHeader(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(18), dp(18), dp(16))
        background = roundedBackground("#EAF4FF", dp(16), strokeColor = "#D7EAFE", strokeWidth = 1)
        elevation = dp(2).toFloat()
        layoutParams = blockLayout(bottom = 12)

        addView(TextView(this@MainActivity).apply {
            text = "充放电监测助手"
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(color("#0F172A"))
            setPadding(0, 0, 0, dp(4))
        })
        addView(TextView(this@MainActivity).apply {
            text = "作者：小锋学长生活大爆炸"
            textSize = 12.5f
            setLineSpacing(3f, 1.03f)
            setTextColor(color("#0F766E"))
            setPadding(0, 0, 0, dp(2))
        })
        addView(TextView(this@MainActivity).apply {
            text = "项目：https://github.com/1061700625/BatteryTester"
            textSize = 12.5f
            setLineSpacing(3f, 1.03f)
            setTextColor(color("#2563EB"))
            setPadding(0, 0, 0, dp(8))
            setOnClickListener {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/1061700625/BatteryTester")))
            }
        })
        addView(TextView(this@MainActivity).apply {
            text = "快速放电、充电曲线、温度与电流采样，一站式记录手机电池表现。"
            textSize = 14f
            setLineSpacing(4f, 1.06f)
            setTextColor(color("#475569"))
        })
        addView(space(12))
        val chips = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
        }
        chips.addView(label("实时曲线", "#0369A1", "#DDF3FF"))
        chips.addView(spaceWidth(8))
        chips.addView(label("温控负载", "#7C2D12", "#FFEDD5"))
        chips.addView(spaceWidth(8))
        chips.addView(label("CPU 监测", "#5B21B6", "#EDE9FE"))
        addView(chips)
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = roundedBackground("#FFFFFF", dp(14), strokeColor = "#EEF2F7", strokeWidth = 1)
        layoutParams = blockLayout(bottom = 10)
        elevation = dp(2).toFloat()
        translationZ = dp(1).toFloat()
    }


    private fun chartCard(chart: ChartView): LinearLayout = card().apply {
        addView(chart)
    }

    private fun chartView(): ChartView = ChartView(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(260))
    }

    private fun title(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 28f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(color("#0F172A"))
        setPadding(0, 0, 0, dp(4))
    }

    private fun subtitle(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(color("#64748B"))
    }

    private fun sectionTitle(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 16f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(color("#1E293B"))
        setPadding(0, 0, 0, dp(8))
    }

    private fun body(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 14f
        setLineSpacing(4f, 1.08f)
        setTextColor(color("#475569"))
    }

    private fun label(text: String, textColor: String, bgColor: String = "#22FFFFFF"): TextView = TextView(this).apply {
        this.text = text
        textSize = 12f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(color(textColor))
        setPadding(dp(10), dp(5), dp(10), dp(5))
        background = roundedBackground(bgColor, dp(999))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    }

    private fun primaryButton(text: String, onClick: () -> Unit): Button = styledButton(text, "#DDF3FF", "#0369A1", onClick)

    private fun accentButton(text: String, onClick: () -> Unit): Button = styledButton(text, "#EDE9FE", "#5B21B6", onClick)

    private fun secondaryButton(text: String, onClick: () -> Unit): Button = styledButton(text, "#F1F5F9", "#334155", onClick)

    private fun styledButton(text: String, bgColor: String, textColor: String, onClick: () -> Unit): Button = Button(this).apply {
        this.text = text
        isAllCaps = false
        gravity = Gravity.CENTER
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(color(textColor))
        background = roundedBackground(bgColor, dp(12))
        setPadding(dp(12), dp(10), dp(12), dp(10))
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48))
        stateListAnimator = null
    }

    private fun roundedBackground(
        fillColor: String,
        radius: Int,
        strokeColor: String? = null,
        strokeWidth: Int = 0
    ): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radius.toFloat()
        setColor(color(fillColor))
        if (strokeColor != null && strokeWidth > 0) setStroke(dp(strokeWidth), color(strokeColor))
    }

    private fun blockLayout(bottom: Int): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, 0, 0, dp(bottom))
        }
    }

    private fun space(heightDp: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(heightDp))
    }

    private fun spaceWidth(widthDp: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(dp(widthDp), 1)
    }

    private fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun color(hex: String): Int = android.graphics.Color.parseColor(hex)

    private fun BatterySnapshot.toDisplayText(): String {
        return buildString {
            appendLine("设备：${deviceProfile.manufacturer} ${deviceProfile.model}")
            appendLine("Android：${deviceProfile.release} / SDK ${deviceProfile.sdkInt}")
            appendLine("电量：${levelPercent?.let { "$it%" } ?: "不可用"}")
            appendLine("瞬时电流：${currentNowMa.formatMa()}")
            appendLine("平均电流：${currentAverageMa.formatMa()}")
            appendLine("剩余容量：${chargeCounterMah?.let { "%.0f mAh".format(it) } ?: "不可用"}")
            appendLine("电压：${voltageV?.let { "%.3f V".format(it) } ?: "不可用"}")
            appendLine("温度：${temperatureC?.let { "%.1f ℃".format(it) } ?: "不可用"}")
            appendLine("CPU 使用率：${cpuUsagePercent.formatPercent()}")
            appendLine("负载目标：${cpuLoadTargetPercent.formatPercent()}")
            appendLine("充电状态：${status.statusText()}")
            append("接入状态：${plugged.pluggedText()}")
        }
    }

    private fun BatterySession.toActiveDisplayText(latestSample: BatterySample?): String {
        return buildString {
            appendLine("正在运行：${mode.displayName()}")
            appendLine("启动时间：${TIME.format(Date(startTime))}")
            appendLine("设备：$manufacturer $deviceModel")
            if (latestSample != null) {
                appendLine("温度：${latestSample.temperatureC?.let { "%.1f ℃".format(it) } ?: "不可用"}")
                appendLine("CPU 使用率：${latestSample.cpuUsagePercent.formatPercent()}")
                appendLine("负载目标：${latestSample.cpuLoadTargetPercent.formatPercent()}")
                if (latestSample.networkDownloadedBytes != null) {
                    val limit = latestSample.networkLimitBytes?.let { "/${formatBytes(it)}" }.orEmpty()
                    appendLine("网络下载：${formatBytes(latestSample.networkDownloadedBytes)}$limit")
                }
            }
            appendLine("负载组件：${activeLoadOptionsText()}")
            append("启动方式：用户点击")
        }
    }

    private fun BatterySession.toHistoryText(): String {
        return buildString {
            appendLine("开始：${TIME.format(Date(startTime))}")
            appendLine("结束：${endTime?.let { TIME.format(Date(it)) } ?: "进行中"}")
            appendLine("电量：${startLevel?.let { "$it%" } ?: "--"} → ${endLevel?.let { "$it%" } ?: "--"}")
            appendLine("停止原因：${stopReason?.displayName() ?: "--"}")
            append("设备：$manufacturer $deviceModel")
        }
    }

    private fun BatterySession.toDetailText(samples: List<BatterySample> = emptyList()): String {
        val latestLevel = samples.lastOrNull { it.levelPercent != null }?.levelPercent
        val displayEndLevel = endLevel ?: latestLevel
        return buildString {
            appendLine("模式：${mode.displayName()}")
            appendLine("开始时间：${TIME.format(Date(startTime))}")
            appendLine("结束时间：${endTime?.let { TIME.format(Date(it)) } ?: "进行中"}")
            appendLine("停止原因：${stopReason?.displayName() ?: "--"}")
            appendLine("设备：$manufacturer $deviceModel")
            appendLine("Android：$androidVersion")
            append("电量：${startLevel?.let { "$it%" } ?: "--"} → ${displayEndLevel?.let { "$it%" } ?: "--"}")
        }
    }

    private fun List<BatterySample>.summaryText(session: BatterySession): String {
        if (isEmpty()) return "暂无采样点。"
        val durationSeconds = ((last().timestamp - first().timestamp) / 1000).coerceAtLeast(0)
        val avgCurrent = mapNotNull { it.currentNowMa }.takeIf { it.isNotEmpty() }?.average()
        val maxAbsCurrent = mapNotNull { it.currentNowMa }.maxOfOrNull { abs(it) }
        val startTemp = firstOrNull { it.temperatureC != null }?.temperatureC
        val maxTemp = mapNotNull { it.temperatureC }.maxOrNull()
        val startLevel = firstOrNull { it.levelPercent != null }?.levelPercent
        val endLevel = lastOrNull { it.levelPercent != null }?.levelPercent
        val levelDelta = if (startLevel != null && endLevel != null) endLevel - startLevel else null
        val avgCpu = mapNotNull { it.cpuUsagePercent }.takeIf { it.isNotEmpty() }?.average()
        val maxCpu = mapNotNull { it.cpuUsagePercent }.maxOrNull()
        val avgTarget = mapNotNull { it.cpuLoadTargetPercent }.takeIf { it.isNotEmpty() }?.average()
        val networkDownloaded = mapNotNull { it.networkDownloadedBytes }.maxOrNull()
        val networkLimit = lastOrNull { it.networkLimitBytes != null }?.networkLimitBytes
        return buildString {
            appendLine("采样点：${size}")
            appendLine("时长：${durationSeconds}s")
            appendLine("电量变化：${levelDelta?.let { "$it%" } ?: "不可用"}")
            appendLine("平均瞬时电流：${avgCurrent.formatMa()}")
            appendLine("最大绝对电流：${maxAbsCurrent.formatMa()}")
            appendLine("最高温度：${maxTemp?.let { "%.1f ℃".format(it) } ?: "不可用"}")
            appendLine("温升：${if (startTemp != null && maxTemp != null) "%.1f ℃".format(maxTemp - startTemp) else "不可用"}")
            appendLine("平均 CPU 使用率：${avgCpu.formatPercent()}")
            appendLine("最高 CPU 使用率：${maxCpu.formatPercent()}")
            appendLine("平均负载目标：${avgTarget.formatPercent()}")
            append("网络下载：${networkDownloaded?.let { formatBytes(it) } ?: "未启用"}${networkLimit?.let { "/${formatBytes(it)}" } ?: ""}")
        }
    }

    private fun BatteryCapability.toDisplayText(): String {
        fun yes(value: Boolean) = if (value) "支持" else "当前不可用"
        return buildString {
            appendLine("瞬时电流：${yes(currentNowSupported)}")
            appendLine("平均电流：${yes(currentAverageSupported)}")
            appendLine("剩余容量：${yes(chargeCounterSupported)}")
            appendLine("剩余能量：${yes(energyCounterSupported)}")
            appendLine("电压：${yes(voltageSupported)}")
            appendLine("温度：${yes(temperatureSupported)}")
            append("系统热状态：${yes(thermalStatusSupported)}")
        }
    }

    private fun BatteryMode.displayName(): String = when (this) {
        BatteryMode.DISCHARGE -> "放电测试"
        BatteryMode.CHARGE_RECORD -> "充电记录"
    }

    private fun StopReason.displayName(): String = when (this) {
        StopReason.USER_STOPPED -> "用户手动停止"
        StopReason.LOW_BATTERY -> "电量过低自动停止"
        StopReason.HIGH_TEMPERATURE -> "温度过高自动停止"
        StopReason.THERMAL_SEVERE -> "系统热状态严重自动停止"
        StopReason.MAX_DURATION_REACHED -> "达到最长测试时间"
        StopReason.SERVICE_DESTROYED -> "服务被系统结束"
        StopReason.REPLACED_BY_NEW_TEST -> "被新测试替换"
        StopReason.UNKNOWN -> "未知"
    }

    private fun Int?.statusText(): String = when (this) {
        android.os.BatteryManager.BATTERY_STATUS_CHARGING -> "充电中"
        android.os.BatteryManager.BATTERY_STATUS_DISCHARGING -> "放电中"
        android.os.BatteryManager.BATTERY_STATUS_FULL -> "已充满"
        android.os.BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "未充电"
        android.os.BatteryManager.BATTERY_STATUS_UNKNOWN -> "未知"
        else -> "不可用"
    }

    private fun Int?.pluggedText(): String = when (this) {
        0 -> "未接入"
        android.os.BatteryManager.BATTERY_PLUGGED_AC -> "AC"
        android.os.BatteryManager.BATTERY_PLUGGED_USB -> "USB"
        android.os.BatteryManager.BATTERY_PLUGGED_WIRELESS -> "无线"
        android.os.BatteryManager.BATTERY_PLUGGED_DOCK -> "底座"
        else -> "不可用"
    }

    private fun Double?.formatMa(): String = this?.let { "%.0f mA".format(it) } ?: "不可用"
    private fun Double?.formatPercent(): String = this?.let { "%.1f%%".format(it) } ?: "不可用"

    private fun formatBytes(bytes: Long): String {
        val mb = bytes / 1024.0 / 1024.0
        return if (mb >= 1024.0) "%.2f GB".format(mb / 1024.0) else "%.1f MB".format(mb)
    }

    private enum class Screen { HOME, HISTORY, DETAIL }

    companion object {
        private val TIME = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        private const val PREFS_NAME = "load_options"
        private const val PREF_LOAD_CPU = "load_cpu"
        private const val PREF_LOAD_GPU = "load_gpu"
        private const val PREF_LOAD_BRIGHTNESS = "load_brightness"
        private const val PREF_LOAD_NETWORK = "load_network"
        private const val PREF_NETWORK_URL = "network_url"
        private const val PREF_NETWORK_LIMIT_OPTION = "network_limit_option"
        private const val PREF_NETWORK_CUSTOM_LIMIT_MB = "network_custom_limit_mb"
        private const val LIMIT_UNLIMITED = "unlimited"
        private const val LIMIT_100_MB = "100mb"
        private const val LIMIT_500_MB = "500mb"
        private const val LIMIT_1_GB = "1gb"
        private const val LIMIT_CUSTOM = "custom"
        private const val PREF_ACTIVE_SESSION_ID = "active_session_id"
        private const val PREF_ACTIVE_CPU = "active_cpu"
        private const val PREF_ACTIVE_GPU = "active_gpu"
        private const val PREF_ACTIVE_BRIGHTNESS = "active_brightness"
        private const val PREF_ACTIVE_NETWORK = "active_network"
    }
}
