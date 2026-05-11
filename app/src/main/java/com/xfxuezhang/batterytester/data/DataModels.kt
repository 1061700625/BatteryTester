package com.xfxuezhang.batterytester.data

import com.xfxuezhang.batterytester.battery.BatteryMode
import com.xfxuezhang.batterytester.battery.StopReason

data class BatterySession(
    val id: String,
    val mode: BatteryMode,
    val startSource: String,
    val startTime: Long,
    val endTime: Long?,
    val stopReason: StopReason?,
    val deviceBrand: String,
    val deviceModel: String,
    val manufacturer: String,
    val androidVersion: String,
    val startLevel: Int?,
    val endLevel: Int?,
    val appVersion: String
)

data class BatterySample(
    val id: Long = 0L,
    val sessionId: String,
    val timestamp: Long,
    val levelPercent: Int?,
    val currentNowMa: Double?,
    val currentAverageMa: Double?,
    val chargeCounterMah: Double?,
    val energyCounterNWh: Long?,
    val voltageV: Double?,
    val temperatureC: Double?,
    val health: Int?,
    val status: Int?,
    val plugged: Int?,
    val thermalStatus: Int?,
    val isPowerSaveMode: Boolean?
)
