package com.xfxuezhang.batterytester.ui

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.xfxuezhang.batterytester.battery.BatteryCapability
import com.xfxuezhang.batterytester.battery.BatteryMode
import com.xfxuezhang.batterytester.battery.BatterySampler
import com.xfxuezhang.batterytester.battery.BatterySnapshot
import com.xfxuezhang.batterytester.battery.StopReason
import com.xfxuezhang.batterytester.data.BatteryRepository
import com.xfxuezhang.batterytester.data.BatterySample
import com.xfxuezhang.batterytester.data.BatterySession
import com.xfxuezhang.batterytester.export.CsvExporter
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = BatteryRepository.get(this)
        sampler = BatterySampler(this)
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
        actionCard.addView(space(12))
        actionCard.addView(primaryButton("⚡ 开始放电测试") { confirmStartDischarge() })
        actionCard.addView(space(10))
        actionCard.addView(accentButton("🔌 开始充电记录") { confirmStartChargeRecord() })
        actionCard.addView(space(10))
        actionCard.addView(secondaryButton("📈 查看历史曲线") { showHistory() })
        root.addView(actionCard)

        val safetyCard = card()
        safetyCard.addView(sectionTitle("安全策略"))
        safetyCard.addView(body("放电测试会按温度自动调整 CPU 负载：低温使用约 92% 高负载，避免 100% 满载导致界面刷新卡顿；中温降到中/低负载，接近高温阈值进入冷却负载。电量低于 15%、电池温度达到 48℃、或系统热状态达到严重级别时会自动停止。"))
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
            sessions.forEach { session ->
                val c = card()
                c.addView(sectionTitle(session.mode.displayName()))
                c.addView(body(session.toHistoryText()))
                c.addView(secondaryButton("查看曲线") { showDetail(session.id) })
                root.addView(c)
            }
        }
        setContentView(scroll(root))
        applyScreenLoadIfNeeded()
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

        val levelChart = chartView()
        val currentChart = chartView()
        val tempChart = chartView()
        val voltageChart = chartView()
        val powerChart = chartView()
        val cpuUsageChart = chartView()
        val cpuLoadChart = chartView()

        root.addView(chartCard(levelChart))
        root.addView(chartCard(currentChart))
        root.addView(chartCard(tempChart))
        root.addView(chartCard(voltageChart))
        root.addView(chartCard(powerChart))
        root.addView(chartCard(cpuUsageChart))
        root.addView(chartCard(cpuLoadChart))

        setContentView(scroll(root))

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
                sessionText.text = session.toDetailText()
                summaryText.text = samples.summaryText(session)
                levelChart.setData(samples, ChartMetric.LEVEL)
                currentChart.setData(samples, ChartMetric.CURRENT)
                tempChart.setData(samples, ChartMetric.TEMPERATURE)
                voltageChart.setData(samples, ChartMetric.VOLTAGE)
                powerChart.setData(samples, ChartMetric.POWER)
                cpuUsageChart.setData(samples, ChartMetric.CPU_USAGE)
                cpuLoadChart.setData(samples, ChartMetric.CPU_LOAD_TARGET)
                delay(if (session.endTime == null) 1500 else 5000)
            }
        }
    }

    private fun confirmStartDischarge() {
        AlertDialog.Builder(this)
            .setTitle("开始放电测试")
            .setMessage("放电测试会提高屏幕亮度并启动计算负载，以加快耗电速度。温度较低时会使用约 92% 高负载，不再占满 100%，以减少界面刷新卡顿；温度升高后会自动降负载，接近高温阈值时会进入冷却负载。你可以随时在页面或通知栏停止测试。建议在 20% 以上电量开始。")
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
        ContextCompat.startForegroundService(this, BatteryTestService.startIntent(this, mode, sessionId))
        keepScreenAwakeImmediately(mode)
        Toast.makeText(this, "测试已启动，运行期间将保持屏幕常亮", Toast.LENGTH_SHORT).show()
        showDetail(sessionId)
    }

    private fun stopCurrentTest() {
        startService(BatteryTestService.stopIntent(this))
        clearScreenLoad()
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
        if (mode == BatteryMode.DISCHARGE) attrs.screenBrightness = 1.0f
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

        if (activeSession.mode == BatteryMode.DISCHARGE) {
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
        addView(root)
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

        addView(label("作者：小锋学长生活大爆炸", "#0F766E", "#DDF7F0"))
        addView(space(10))
        addView(TextView(this@MainActivity).apply {
            text = "充放电监测助手"
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(color("#0F172A"))
            setPadding(0, 0, 0, dp(4))
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
            }
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

    private fun BatterySession.toDetailText(): String {
        return buildString {
            appendLine("模式：${mode.displayName()}")
            appendLine("开始时间：${TIME.format(Date(startTime))}")
            appendLine("结束时间：${endTime?.let { TIME.format(Date(it)) } ?: "进行中"}")
            appendLine("停止原因：${stopReason?.displayName() ?: "--"}")
            appendLine("设备：$manufacturer $deviceModel")
            appendLine("Android：$androidVersion")
            append("电量：${startLevel?.let { "$it%" } ?: "--"} → ${endLevel?.let { "$it%" } ?: "--"}")
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
            append("平均负载目标：${avgTarget.formatPercent()}")
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

    private enum class Screen { HOME, HISTORY, DETAIL }

    companion object {
        private val TIME = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    }
}
