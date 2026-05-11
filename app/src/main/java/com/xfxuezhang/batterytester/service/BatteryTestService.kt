package com.xfxuezhang.batterytester.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.xfxuezhang.batterytester.R
import com.xfxuezhang.batterytester.battery.BatteryMode
import com.xfxuezhang.batterytester.battery.BatterySampler
import com.xfxuezhang.batterytester.battery.DischargeConfig
import com.xfxuezhang.batterytester.battery.StopReason
import com.xfxuezhang.batterytester.data.BatteryRepository
import com.xfxuezhang.batterytester.data.toSession
import com.xfxuezhang.batterytester.load.CpuBurner
import com.xfxuezhang.batterytester.load.NetworkTransferLoad
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

class BatteryTestService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var sampler: BatterySampler
    private lateinit var repository: BatteryRepository
    private val cpuBurner = CpuBurner()
    private val networkTransferLoad = NetworkTransferLoad()
    private var sampleJob: Job? = null
    private var currentSessionId: String? = null
    private var currentMode: BatteryMode? = null
    private var sessionFinished = false
    private val config = DischargeConfig()
    private var cpuLoadEnabled = true
    private var gpuLoadEnabled = true
    private var screenBrightnessLoadEnabled = true
    private var networkTransferEnabled = false
    private var networkTransferUrl: String = NetworkTransferLoad.DEFAULT_DOWNLOAD_URL
    private var networkTransferLimitBytes: Long? = null

    override fun onCreate() {
        super.onCreate()
        sampler = BatterySampler(this)
        repository = BatteryRepository.get(this)
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val mode = intent.getStringExtra(EXTRA_MODE)?.let { BatteryMode.valueOf(it) } ?: BatteryMode.DISCHARGE
                val sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: UUID.randomUUID().toString()
                val source = intent.getStringExtra(EXTRA_START_SOURCE) ?: "USER"
                cpuLoadEnabled = intent.getBooleanExtra(EXTRA_LOAD_CPU, true)
                gpuLoadEnabled = intent.getBooleanExtra(EXTRA_LOAD_GPU, true)
                screenBrightnessLoadEnabled = intent.getBooleanExtra(EXTRA_LOAD_SCREEN_BRIGHTNESS, true)
                networkTransferEnabled = intent.getBooleanExtra(EXTRA_LOAD_NETWORK, false)
                networkTransferUrl = intent.getStringExtra(EXTRA_NETWORK_URL)?.takeIf { it.isNotBlank() } ?: NetworkTransferLoad.DEFAULT_DOWNLOAD_URL
                networkTransferLimitBytes = intent.getLongExtra(EXTRA_NETWORK_LIMIT_BYTES, 0L).takeIf { it > 0L }
                startTest(sessionId, mode, source)
            }
            ACTION_STOP -> stopTest(StopReason.USER_STOPPED)
            else -> Unit
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (!sessionFinished && currentSessionId != null) {
            finishSession(StopReason.SERVICE_DESTROYED)
        }
        cpuBurner.stop()
        networkTransferLoad.stop()
        serviceScope.coroutineContext.cancelChildren()
        super.onDestroy()
    }

    private fun startTest(sessionId: String, mode: BatteryMode, source: String) {
        repository.closeActiveAsReplaced()
        stopRunningLoopOnly()
        currentMode = mode

        val probeSnapshot = sampler.sample()
        if (mode == BatteryMode.DISCHARGE) {
            val initialLoadRatio = loadRatioFor(probeSnapshot.temperatureC, probeSnapshot.thermalStatus)
            if (cpuLoadEnabled) cpuBurner.setTargetLoad(serviceScope, initialLoadRatio) else cpuBurner.stop()
            networkTransferLoad.resetStats()
            updateNetworkLoad(initialLoadRatio)
        }

        val firstSnapshot = probeSnapshot.copy(
            cpuLoadTargetPercent = if (mode == BatteryMode.DISCHARGE && cpuLoadEnabled) cpuBurner.currentTargetPercent else null,
            networkDownloadedBytes = if (mode == BatteryMode.DISCHARGE && networkTransferEnabled) networkTransferLoad.totalDownloadedBytes else null,
            networkLimitBytes = if (mode == BatteryMode.DISCHARGE && networkTransferEnabled) networkTransferLimitBytes else null
        )
        repository.insertSession(firstSnapshot.toSession(sessionId, mode, source, APP_VERSION))
        repository.insertSample(sessionId, firstSnapshot)

        currentSessionId = sessionId
        currentMode = mode
        sessionFinished = false

        startInForeground(mode, firstSnapshot.levelPercent, firstSnapshot.temperatureC)
        updateNotification(mode, firstSnapshot)

        sampleJob = serviceScope.launch {
            val startMs = System.currentTimeMillis()
            while (isActive) {
                delay(config.sampleIntervalMs)

                val targetBeforeSample = if (mode == BatteryMode.DISCHARGE && cpuLoadEnabled) cpuBurner.currentTargetPercent else null
                val snapshot = sampler.sample(
                    cpuLoadTargetPercent = targetBeforeSample,
                    networkDownloadedBytes = if (mode == BatteryMode.DISCHARGE && networkTransferEnabled) networkTransferLoad.totalDownloadedBytes else null,
                    networkLimitBytes = if (mode == BatteryMode.DISCHARGE && networkTransferEnabled) networkTransferLimitBytes else null
                )
                repository.insertSample(sessionId, snapshot)

                if (mode == BatteryMode.DISCHARGE) {
                    val reason = stopReasonForDischarge(snapshot.levelPercent, snapshot.temperatureC, snapshot.thermalStatus)
                    if (reason != null) {
                        updateNotification(mode, snapshot)
                        stopTest(reason)
                        break
                    }

                    val nextLoadRatio = loadRatioFor(snapshot.temperatureC, snapshot.thermalStatus)
                    if (cpuLoadEnabled) {
                        if (kotlin.math.abs(cpuBurner.currentTargetRatio - nextLoadRatio) >= 0.01f) {
                            cpuBurner.setTargetLoad(this, nextLoadRatio)
                        }
                    } else {
                        cpuBurner.stop()
                    }
                    updateNetworkLoad(nextLoadRatio)

                    if (System.currentTimeMillis() - startMs >= config.maxDurationMinutes * 60_000L) {
                        updateNotification(mode, snapshot)
                        stopTest(StopReason.MAX_DURATION_REACHED)
                        break
                    }
                }

                updateNotification(mode, snapshot)
            }
        }
    }

    private fun stopRunningLoopOnly() {
        sampleJob?.cancel()
        sampleJob = null
        cpuBurner.stop()
        networkTransferLoad.stop()
    }

    private fun stopTest(reason: StopReason) {
        finishSession(reason)
        stopRunningLoopOnly()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun finishSession(reason: StopReason) {
        val id = currentSessionId ?: return
        if (sessionFinished) return
        val snapshot = sampler.sample(
            cpuLoadTargetPercent = if (currentMode == BatteryMode.DISCHARGE && cpuLoadEnabled) cpuBurner.currentTargetPercent else null,
            networkDownloadedBytes = if (currentMode == BatteryMode.DISCHARGE && networkTransferEnabled) networkTransferLoad.totalDownloadedBytes else null,
            networkLimitBytes = if (currentMode == BatteryMode.DISCHARGE && networkTransferEnabled) networkTransferLimitBytes else null
        )
        repository.insertSample(id, snapshot)
        repository.finishSession(id, snapshot.levelPercent, reason)
        sessionFinished = true
    }

    private fun loadRatioFor(tempC: Double?, thermalStatus: Int?): Float {
        if (thermalStatus != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE) return config.coolDownLoadRatio
            if (thermalStatus >= PowerManager.THERMAL_STATUS_MODERATE) return config.lowLoadRatio
            if (thermalStatus >= PowerManager.THERMAL_STATUS_LIGHT) return config.mediumLoadRatio
        }

        return when {
            tempC == null -> config.mediumLoadRatio
            tempC < config.highLoadBelowTempC -> config.highLoadRatio
            tempC < config.mediumLoadBelowTempC -> config.mediumLoadRatio
            tempC < config.lowLoadBelowTempC -> config.lowLoadRatio
            tempC < config.stopAtTempC -> config.coolDownLoadRatio
            else -> 0f
        }
    }

    private fun stopReasonForDischarge(level: Int?, tempC: Double?, thermalStatus: Int?): StopReason? {
        if (level != null && level <= config.stopAtBatteryPercent) return StopReason.LOW_BATTERY
        if (tempC != null && tempC >= config.stopAtTempC) return StopReason.HIGH_TEMPERATURE
        if (thermalStatus != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE) {
            return StopReason.THERMAL_SEVERE
        }
        return null
    }

    private fun updateNetworkLoad(loadRatio: Float) {
        if (currentMode != BatteryMode.DISCHARGE && currentMode != null) {
            networkTransferLoad.stop()
            return
        }
        val limitReached = networkTransferLimitBytes?.let { it > 0L && networkTransferLoad.totalDownloadedBytes >= it } == true
        val shouldRun = networkTransferEnabled && !limitReached && loadRatio >= config.lowLoadRatio
        if (shouldRun) networkTransferLoad.start(serviceScope, networkTransferUrl, networkTransferLimitBytes) else networkTransferLoad.stop()
    }

    private fun startInForeground(mode: BatteryMode, level: Int?, tempC: Double?) {
        val notification = buildNotification(mode, level, tempC, null, null, null, null, null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(mode: BatteryMode, snapshot: com.xfxuezhang.batterytester.battery.BatterySnapshot) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(
            NOTIFICATION_ID,
            buildNotification(
                mode = mode,
                level = snapshot.levelPercent,
                tempC = snapshot.temperatureC,
                currentMa = snapshot.currentNowMa,
                cpuUsagePercent = snapshot.cpuUsagePercent,
                cpuLoadTargetPercent = if (mode == BatteryMode.DISCHARGE && cpuLoadEnabled) cpuBurner.currentTargetPercent else null,
                networkDownloadedBytes = snapshot.networkDownloadedBytes,
                networkLimitBytes = snapshot.networkLimitBytes
            )
        )
    }

    private fun buildNotification(
        mode: BatteryMode,
        level: Int?,
        tempC: Double?,
        currentMa: Double?,
        cpuUsagePercent: Double?,
        cpuLoadTargetPercent: Double?,
        networkDownloadedBytes: Long?,
        networkLimitBytes: Long?
    ): Notification {
        val title = if (mode == BatteryMode.DISCHARGE) "正在进行放电测试" else "正在记录充电曲线"
        val current = currentMa?.let { String.format("%.0f mA", it) } ?: "电流不可用"
        val cpuText = if (mode == BatteryMode.DISCHARGE) {
            val usage = cpuUsagePercent?.let { String.format("%.1f%%", it) } ?: "--"
            val target = cpuLoadTargetPercent?.let { String.format("%.0f%%", it) } ?: "--"
            val network = if (networkTransferEnabled) "，网络 ${formatBytes(networkDownloadedBytes)}${networkLimitBytes?.let { "/${formatBytes(it)}" } ?: ""}" else ""
            "，CPU $usage，负载目标 $target$network，组件 ${loadOptionsText()}"
        } else {
            ""
        }
        val content = "电量 ${level?.let { "$it%" } ?: "--"}，温度 ${tempC?.let { String.format("%.1f℃", it) } ?: "--"}，$current$cpuText"
        val stopIntent = Intent(this, BatteryTestService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1001,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openIntent = packageManager.getLaunchIntentForPackage(packageName) ?: Intent()
        val openPendingIntent = PendingIntent.getActivity(
            this,
            1002,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(content)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openPendingIntent)
            .addAction(R.drawable.ic_launcher, "停止测试", stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun formatBytes(bytes: Long?): String {
        if (bytes == null) return "--"
        val mb = bytes / 1024.0 / 1024.0
        return if (mb >= 1024.0) String.format("%.2fGB", mb / 1024.0) else String.format("%.1fMB", mb)
    }

    private fun loadOptionsText(): String {
        val items = mutableListOf<String>()
        if (cpuLoadEnabled) items += "CPU"
        if (gpuLoadEnabled) items += "GPU"
        if (screenBrightnessLoadEnabled) items += "亮度"
        if (networkTransferEnabled) items += "网络"
        return if (items.isEmpty()) "仅采样" else items.joinToString("/")
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "显示正在运行的用户主动电池测试"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val ACTION_START = "com.xfxuezhang.batterytester.action.START"
        const val ACTION_STOP = "com.xfxuezhang.batterytester.action.STOP"
        const val EXTRA_MODE = "extra_mode"
        const val EXTRA_SESSION_ID = "extra_session_id"
        const val EXTRA_START_SOURCE = "extra_start_source"
        const val EXTRA_LOAD_CPU = "extra_load_cpu"
        const val EXTRA_LOAD_GPU = "extra_load_gpu"
        const val EXTRA_LOAD_SCREEN_BRIGHTNESS = "extra_load_screen_brightness"
        const val EXTRA_LOAD_NETWORK = "extra_load_network"
        const val EXTRA_NETWORK_URL = "extra_network_url"
        const val EXTRA_NETWORK_LIMIT_BYTES = "extra_network_limit_bytes"
        private const val CHANNEL_ID = "battery_test_channel"
        private const val NOTIFICATION_ID = 22001
        private const val APP_VERSION = "1.0.1"

        fun startIntent(
            context: Context,
            mode: BatteryMode,
            sessionId: String,
            loadCpu: Boolean = true,
            loadGpu: Boolean = true,
            loadScreenBrightness: Boolean = true,
            loadNetwork: Boolean = false,
            networkUrl: String = NetworkTransferLoad.DEFAULT_DOWNLOAD_URL,
            networkLimitBytes: Long? = null
        ): Intent {
            return Intent(context, BatteryTestService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_MODE, mode.name)
                putExtra(EXTRA_SESSION_ID, sessionId)
                putExtra(EXTRA_START_SOURCE, "USER_CLICK")
                putExtra(EXTRA_LOAD_CPU, loadCpu)
                putExtra(EXTRA_LOAD_GPU, loadGpu)
                putExtra(EXTRA_LOAD_SCREEN_BRIGHTNESS, loadScreenBrightness)
                putExtra(EXTRA_LOAD_NETWORK, loadNetwork)
                putExtra(EXTRA_NETWORK_URL, networkUrl)
                putExtra(EXTRA_NETWORK_LIMIT_BYTES, networkLimitBytes ?: 0L)
            }
        }

        fun stopIntent(context: Context): Intent {
            return Intent(context, BatteryTestService::class.java).apply { action = ACTION_STOP }
        }
    }
}
