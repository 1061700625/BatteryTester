package com.xfxuezhang.batterytester.load

import java.io.File
import kotlin.math.roundToInt

class CpuUsageSampler {
    private var previous: CpuTicks? = null

    fun samplePercent(): Double? {
        val current = readCpuTicks() ?: return null
        val last = previous
        previous = current
        if (last == null) return null

        val totalDelta = current.total - last.total
        val idleDelta = current.idleAll - last.idleAll
        if (totalDelta <= 0L) return null

        val busy = (totalDelta - idleDelta).coerceAtLeast(0L)
        return (busy.toDouble() * 100.0 / totalDelta.toDouble()).coerceIn(0.0, 100.0)
    }

    private fun readCpuTicks(): CpuTicks? {
        val line = runCatching { File("/proc/stat").useLines { lines -> lines.firstOrNull { it.startsWith("cpu ") } } }
            .getOrNull()
            ?: return null
        val parts = line.trim().split(Regex("\\s+"))
        if (parts.size < 8 || parts[0] != "cpu") return null
        val values = parts.drop(1).mapNotNull { it.toLongOrNull() }
        if (values.size < 7) return null

        val user = values.getOrElse(0) { 0L }
        val nice = values.getOrElse(1) { 0L }
        val system = values.getOrElse(2) { 0L }
        val idle = values.getOrElse(3) { 0L }
        val iowait = values.getOrElse(4) { 0L }
        val irq = values.getOrElse(5) { 0L }
        val softirq = values.getOrElse(6) { 0L }
        val steal = values.getOrElse(7) { 0L }
        val total = user + nice + system + idle + iowait + irq + softirq + steal
        return CpuTicks(total = total, idleAll = idle + iowait)
    }

    private data class CpuTicks(
        val total: Long,
        val idleAll: Long
    )
}

fun Double?.formatCpuPercent(): String = this?.let { "${(it * 10).roundToInt() / 10.0}%" } ?: "不可用"
