package com.xfxuezhang.batterytester.battery

import android.os.Build

enum class BatteryMode {
    DISCHARGE,
    CHARGE_RECORD
}

enum class StopReason {
    USER_STOPPED,
    LOW_BATTERY,
    HIGH_TEMPERATURE,
    THERMAL_SEVERE,
    MAX_DURATION_REACHED,
    SERVICE_DESTROYED,
    REPLACED_BY_NEW_TEST,
    UNKNOWN
}

data class DeviceProfile(
    val brand: String = Build.BRAND.orEmpty(),
    val manufacturer: String = Build.MANUFACTURER.orEmpty(),
    val model: String = Build.MODEL.orEmpty(),
    val device: String = Build.DEVICE.orEmpty(),
    val product: String = Build.PRODUCT.orEmpty(),
    val sdkInt: Int = Build.VERSION.SDK_INT,
    val release: String = Build.VERSION.RELEASE.orEmpty()
)

data class BatterySnapshot(
    val timestamp: Long,
    val deviceProfile: DeviceProfile,
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

data class BatteryCapability(
    val currentNowSupported: Boolean,
    val currentAverageSupported: Boolean,
    val chargeCounterSupported: Boolean,
    val energyCounterSupported: Boolean,
    val voltageSupported: Boolean,
    val temperatureSupported: Boolean,
    val thermalStatusSupported: Boolean
) {
    companion object {
        fun fromSamples(samples: List<BatterySnapshot>): BatteryCapability {
            return BatteryCapability(
                currentNowSupported = samples.any { it.currentNowMa != null },
                currentAverageSupported = samples.any { it.currentAverageMa != null },
                chargeCounterSupported = samples.any { it.chargeCounterMah != null },
                energyCounterSupported = samples.any { it.energyCounterNWh != null },
                voltageSupported = samples.any { it.voltageV != null },
                temperatureSupported = samples.any { it.temperatureC != null },
                thermalStatusSupported = samples.any { it.thermalStatus != null }
            )
        }
    }
}

data class DischargeConfig(
    val sampleIntervalMs: Long = 1000L,
    val cpuLoadRatio: Float = 0.65f,
    val keepScreenOn: Boolean = true,
    val maxBrightness: Boolean = true,
    val stopAtBatteryPercent: Int = 15,
    val reduceLoadAtTempC: Double = 43.0,
    val stopAtTempC: Double = 48.0,
    val maxDurationMinutes: Int = 60
)
