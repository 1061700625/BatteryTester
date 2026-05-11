package com.xfxuezhang.batterytester.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import com.xfxuezhang.batterytester.data.BatterySample
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class ChartMetric(val displayName: String, val unit: String) {
    LEVEL("电量", "%"),
    CURRENT("瞬时电流", "mA"),
    TEMPERATURE("温度", "℃"),
    VOLTAGE("电压", "V"),
    POWER("估算功率", "W")
}

class ChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private var samples: List<BatterySample> = emptyList()
    private var metric: ChartMetric = ChartMetric.LEVEL

    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(156, 163, 175)
        strokeWidth = 2f
        textSize = 26f
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(229, 231, 235)
        strokeWidth = 1f
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(37, 99, 235)
        strokeWidth = 5f
        style = Paint.Style.STROKE
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(17, 24, 39)
        textSize = 34f
        isFakeBoldText = true
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(107, 114, 128)
        textSize = 28f
    }

    fun setData(samples: List<BatterySample>, metric: ChartMetric) {
        this.samples = samples
        this.metric = metric
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = 84f
        val top = 72f
        val right = width - 32f
        val bottom = height - 64f
        canvas.drawText("${metric.displayName}曲线", left, 42f, textPaint)

        canvas.drawLine(left, bottom, right, bottom, axisPaint)
        canvas.drawLine(left, top, left, bottom, axisPaint)
        for (i in 1..4) {
            val y = top + (bottom - top) * i / 5f
            canvas.drawLine(left, y, right, y, gridPaint)
        }

        if (samples.size < 2) {
            canvas.drawText("暂无足够采样数据", left + 24f, (top + bottom) / 2f, hintPaint)
            return
        }

        val points = samples.mapNotNull { sample -> valueOf(sample)?.let { sample.timestamp to it } }
        if (points.size < 2) {
            canvas.drawText("当前设备未提供该指标，已跳过此曲线", left + 24f, (top + bottom) / 2f, hintPaint)
            return
        }

        val start = samples.first().timestamp
        val end = max(samples.last().timestamp, start + 1)
        var minV = points.minOf { it.second }
        var maxV = points.maxOf { it.second }
        if (abs(maxV - minV) < 0.00001) {
            minV -= 1.0
            maxV += 1.0
        }
        val padding = (maxV - minV) * 0.1
        minV -= padding
        maxV += padding

        canvas.drawText(formatAxis(maxV), 8f, top + 12f, axisPaint)
        canvas.drawText(formatAxis(minV), 8f, bottom, axisPaint)
        val totalSeconds = (end - start) / 1000
        canvas.drawText("0s", left, height - 20f, axisPaint)
        canvas.drawText("${totalSeconds}s", right - 90f, height - 20f, axisPaint)

        val path = Path()
        var started = false
        var previousTimestamp: Long? = null
        points.forEach { (timestamp, value) ->
            val x = left + (timestamp - start).toFloat() / (end - start).toFloat() * (right - left)
            val y = bottom - ((value - minV) / (maxV - minV)).toFloat() * (bottom - top)
            val gap = previousTimestamp?.let { timestamp - it > 2_500L } ?: false
            if (!started || gap) {
                path.moveTo(x, y)
                started = true
            } else {
                path.lineTo(x, y)
            }
            previousTimestamp = timestamp
        }
        canvas.drawPath(path, linePaint)
    }

    private fun valueOf(sample: BatterySample): Double? {
        return when (metric) {
            ChartMetric.LEVEL -> sample.levelPercent?.toDouble()
            ChartMetric.CURRENT -> sample.currentNowMa
            ChartMetric.TEMPERATURE -> sample.temperatureC
            ChartMetric.VOLTAGE -> sample.voltageV
            ChartMetric.POWER -> {
                val current = sample.currentNowMa
                val voltage = sample.voltageV
                if (current != null && voltage != null) abs(current / 1000.0) * voltage else null
            }
        }
    }

    private fun formatAxis(value: Double): String {
        return when (metric) {
            ChartMetric.LEVEL -> "%.0f%s".format(value, metric.unit)
            ChartMetric.CURRENT -> "%.0f".format(value)
            ChartMetric.TEMPERATURE -> "%.1f".format(value)
            ChartMetric.VOLTAGE -> "%.2f".format(value)
            ChartMetric.POWER -> "%.2f".format(value)
        }
    }
}
