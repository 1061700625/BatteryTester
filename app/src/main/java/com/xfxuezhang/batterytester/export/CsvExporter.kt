package com.xfxuezhang.batterytester.export

import android.content.Context
import android.content.Intent
import android.os.Environment
import androidx.core.content.FileProvider
import com.xfxuezhang.batterytester.data.BatterySample
import com.xfxuezhang.batterytester.data.BatterySession
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CsvExporter {
    fun export(context: Context, session: BatterySession, samples: List<BatterySample>): File {
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "BatteryTester")
        if (!dir.exists()) dir.mkdirs()
        val safeStart = FILE_TIME.format(Date(session.startTime))
        val file = File(dir, "battery_${session.mode.name.lowercase()}_${safeStart}.csv")

        file.bufferedWriter(Charsets.UTF_8).use { out ->
            out.appendLine("session_id,mode,start_time,end_time,stop_reason,device_brand,device_model,manufacturer,android_version,start_level,end_level")
            out.appendLine(listOf(
                session.id,
                session.mode.name,
                session.startTime.toString(),
                session.endTime?.toString().orEmpty(),
                session.stopReason?.name.orEmpty(),
                session.deviceBrand,
                session.deviceModel,
                session.manufacturer,
                session.androidVersion,
                session.startLevel?.toString().orEmpty(),
                session.endLevel?.toString().orEmpty()
            ).joinToString(",") { it.csvEscape() })
            out.appendLine()
            out.appendLine("timestamp,elapsed_seconds,level_percent,current_now_ma,current_average_ma,charge_counter_mah,energy_counter_nwh,voltage_v,temperature_c,health,status,plugged,thermal_status,is_power_save_mode,cpu_usage_percent,cpu_load_target_percent")
            samples.forEach { s ->
                val elapsed = (s.timestamp - session.startTime) / 1000.0
                out.appendLine(listOf(
                    s.timestamp.toString(),
                    "%.3f".format(Locale.US, elapsed),
                    s.levelPercent?.toString().orEmpty(),
                    s.currentNowMa?.format3().orEmpty(),
                    s.currentAverageMa?.format3().orEmpty(),
                    s.chargeCounterMah?.format3().orEmpty(),
                    s.energyCounterNWh?.toString().orEmpty(),
                    s.voltageV?.format3().orEmpty(),
                    s.temperatureC?.format2().orEmpty(),
                    s.health?.toString().orEmpty(),
                    s.status?.toString().orEmpty(),
                    s.plugged?.toString().orEmpty(),
                    s.thermalStatus?.toString().orEmpty(),
                    s.isPowerSaveMode?.toString().orEmpty(),
                    s.cpuUsagePercent?.format2().orEmpty(),
                    s.cpuLoadTargetPercent?.format2().orEmpty()
                ).joinToString(",") { it.csvEscape() })
            }
        }
        return file
    }

    fun shareIntent(context: Context, file: File): Intent {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun String.csvEscape(): String {
        val escaped = replace("\"", "\"\"")
        return if (contains(',') || contains('\n') || contains('"')) "\"$escaped\"" else escaped
    }

    private fun Double.format3(): String = "%.3f".format(Locale.US, this)
    private fun Double.format2(): String = "%.2f".format(Locale.US, this)

    private val FILE_TIME = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
}
