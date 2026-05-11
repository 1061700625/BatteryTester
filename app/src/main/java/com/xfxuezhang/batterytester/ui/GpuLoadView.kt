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
    private var frame = 0f
    private var running = true

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

        repeat(620) { i ->
            val phase = frame * 0.018f + i * 0.17f
            val rawX = (sin(phase) + 1f) * 0.5f * w + (i * 11 % w.toInt())
            val rawY = (cos(phase * 0.73f) + 1f) * 0.5f * h + (i * 17 % h.toInt())
            val x = ((rawX % w) + w) % w
            val y = ((rawY % h) + h) % h
            val radius = 3f + (i % 9)
            paint.alpha = 36 + (i % 72)
            paint.color = when (i % 4) {
                0 -> Color.parseColor("#93C5FD")
                1 -> Color.parseColor("#C4B5FD")
                2 -> Color.parseColor("#86EFAC")
                else -> Color.parseColor("#FDBA74")
            }
            canvas.drawCircle(x, y, radius, paint)
        }

        frame += 1f
        if (running) postInvalidateOnAnimation()
    }
}
