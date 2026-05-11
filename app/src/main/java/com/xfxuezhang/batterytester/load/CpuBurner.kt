package com.xfxuezhang.batterytester.load

import android.os.Process
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

class CpuBurner {
    private val jobs = mutableListOf<Job>()
    @Volatile
    private var targetRatio: Float = 0f

    val currentTargetRatio: Float
        get() = targetRatio

    val currentTargetPercent: Double
        get() = targetRatio * 100.0

    fun start(scope: CoroutineScope, intensity: Float) {
        setTargetLoad(scope, intensity)
    }

    fun setTargetLoad(scope: CoroutineScope, intensity: Float) {
        val newRatio = intensity.coerceIn(0.0f, 0.95f)
        targetRatio = newRatio

        val available = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val desiredWorkers = if (newRatio <= 0f) {
            0
        } else {
            max(1, ceil(available * newRatio).toInt()).coerceAtMost(available)
        }

        while (jobs.size > desiredWorkers) {
            jobs.removeAt(jobs.lastIndex).cancel()
        }

        while (jobs.size < desiredWorkers) {
            jobs += scope.launch(Dispatchers.Default) {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                burnLoop()
            }
        }
    }

    fun reduce(scope: CoroutineScope) {
        val reduced = if (targetRatio > 0.55f) 0.45f else 0.20f
        setTargetLoad(scope, reduced)
    }

    fun stop() {
        targetRatio = 0f
        jobs.forEach { it.cancel() }
        jobs.clear()
    }

    private suspend fun burnLoop() {
        var x = 0.123456789
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            val busyStartNs = System.nanoTime()
            repeat(220_000) {
                x = sin(x) * cos(x) + sqrt(abs(x) + 0.000001)
            }

            val busyNs = System.nanoTime() - busyStartNs
            val dutyRatio = dutyRatioPerWorker()
            val idleNs = if (dutyRatio < 0.999f) {
                (busyNs * (1.0 - dutyRatio) / dutyRatio).toLong()
            } else {
                0L
            }

            if (idleNs >= 1_000_000L) {
                delay(idleNs / 1_000_000L)
            } else {
                yield()
            }
        }
    }

    private fun dutyRatioPerWorker(): Float {
        val available = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val workerCount = jobs.size.coerceAtLeast(1)
        return ((targetRatio * available) / workerCount)
            .coerceIn(0.05f, 0.95f)
    }
}
