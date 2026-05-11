package com.xfxuezhang.batterytester.battery

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import com.xfxuezhang.batterytester.load.CpuUsageSampler
import kotlin.math.roundToInt

class BatterySampler(private val context: Context) {
    private val appContext = context.applicationContext
    private val batteryManager = appContext.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    private val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val cpuUsageSampler = CpuUsageSampler()

    fun sample(cpuLoadTargetPercent: Double? = null): BatterySnapshot {
        val intent = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, INVALID_INT)
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, INVALID_INT)
        val voltageMv = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, INVALID_INT)
        val tempTenthC = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, INVALID_INT)

        return BatterySnapshot(
            timestamp = System.currentTimeMillis(),
            deviceProfile = DeviceProfile(),
            levelPercent = computeLevel(level, scale)
                ?: readIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY),
            currentNowMa = readIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)?.let { it / 1000.0 },
            currentAverageMa = readIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE)?.let { it / 1000.0 },
            chargeCounterMah = readIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)?.let { it / 1000.0 },
            energyCounterNWh = readLongProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER),
            voltageV = voltageMv?.takeIf { it > 0 }?.let { it / 1000.0 },
            temperatureC = tempTenthC?.takeIf { it > 0 }?.let { it / 10.0 },
            health = intent?.getIntExtra(BatteryManager.EXTRA_HEALTH, INVALID_INT)?.validExtra(),
            status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, INVALID_INT)?.validExtra(),
            plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, INVALID_INT)?.validExtra(),
            thermalStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                powerManager.currentThermalStatus
            } else {
                null
            },
            isPowerSaveMode = powerManager.isPowerSaveMode,
            cpuUsagePercent = cpuUsageSampler.samplePercent(),
            cpuLoadTargetPercent = cpuLoadTargetPercent
        )
    }

    private fun computeLevel(level: Int?, scale: Int?): Int? {
        if (level == null || scale == null || level < 0 || scale <= 0) return null
        return ((level * 100f) / scale).roundToInt().coerceIn(0, 100)
    }

    private fun readIntProperty(propertyId: Int): Int? {
        val value = batteryManager.getIntProperty(propertyId)
        return value.takeIf { it != Int.MIN_VALUE }
    }

    private fun readLongProperty(propertyId: Int): Long? {
        val value = batteryManager.getLongProperty(propertyId)
        return value.takeIf { it != Long.MIN_VALUE }
    }

    private fun Int.validExtra(): Int? = takeIf { it != INVALID_INT && it >= 0 }

    companion object {
        private const val INVALID_INT = Int.MIN_VALUE
    }
}
