package com.xfxuezhang.batterytester.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

class GpuLoadView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val palette = intArrayOf(
        Color.parseColor("#93C5FD"),
        Color.parseColor("#C4B5FD"),
        Color.parseColor("#86EFAC"),
        Color.parseColor("#FDBA74"),
        Color.parseColor("#F9A8D4")
    )
    private var frame = 0f
    private var running = true

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun setRunning(value: Boolean) {
        running = value
        if (running) postInvalidateOnAnimation()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        running = true
        postInvalidateOnAnimation()
    }

    override fun onDetachedFromWindow() {
        running = false
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.coerceAtLeast(1).toFloat()
        val h = height.coerceAtLeast(1).toFloat()
        canvas.drawColor(Color.parseColor("#F8FBFF"))

        repeat(1800) { i ->
            val phase = frame * 0.026f + i * 0.137f
            val rawX = (sin(phase) + 1f) * 0.5f * w + (i * 13 % w.toInt())
            val rawY = (cos(phase * 0.71f) + 1f) * 0.5f * h + (i * 19 % h.toInt())
            val x = ((rawX % w) + w) % w
            val y = ((rawY % h) + h) % h
            val radius = 2.5f + (i % 11)
            paint.alpha = 26 + (i % 80)
            paint.color = palette[i % palette.size]
            canvas.drawCircle(x, y, radius, paint)
        }

        paint.strokeWidth = 1.2f
        repeat(160) { i ->
            val phase = frame * 0.035f + i * 0.23f
            val x1 = ((sin(phase) + 1f) * 0.5f * w)
            val y1 = ((cos(phase * 0.84f) + 1f) * 0.5f * h)
            val x2 = ((sin(phase + 1.7f) + 1f) * 0.5f * w)
            val y2 = ((cos(phase * 0.67f + 2.1f) + 1f) * 0.5f * h)
            paint.alpha = 20 + (i % 36)
            paint.color = palette[(i + 2) % palette.size]
            canvas.drawLine(x1, y1, x2, y2, paint)
        }

        frame += 1f
        if (running) postInvalidateOnAnimation()
    }
}
