package com.xfxuezhang.batterytester.load

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

class CpuBurner {
    private val jobs = mutableListOf<Job>()

    fun start(scope: CoroutineScope, intensity: Float) {
        stop()
        val available = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val workers = max(1, (available * intensity.coerceIn(0.1f, 1.0f)).roundToInt())
        repeat(workers) {
            jobs += scope.launch(Dispatchers.Default) {
                var x = 0.123456789
                while (isActive) {
                    repeat(250_000) {
                        x = sin(x) * cos(x) + sqrt(abs(x) + 0.000001)
                    }
                    yield()
                }
            }
        }
    }

    fun reduce(scope: CoroutineScope) {
        if (jobs.size <= 1) return
        val keep = (jobs.size / 2).coerceAtLeast(1)
        val toCancel = jobs.drop(keep)
        toCancel.forEach { it.cancel() }
        jobs.removeAll(toCancel.toSet())
        if (jobs.isEmpty()) start(scope, 0.25f)
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
    }
}
