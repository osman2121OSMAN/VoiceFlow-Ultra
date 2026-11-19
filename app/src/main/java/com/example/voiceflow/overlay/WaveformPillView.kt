package com.example.voiceflow.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import kotlin.math.sin

class WaveformPillView(
    context: Context,
    private val onIdlePillTapped: () -> Unit,
) : View(context) {

    private val density = resources.displayMetrics.density
    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PILL_BG_COLOR
        style = Paint.Style.FILL
    }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = BAR_COLOR
        style = Paint.Style.FILL
    }

    private val pillRect = RectF()
    private val barRect = RectF()

    private val barCount = 9
    private val idleProfile = floatArrayOf(0.14f, 0.14f, 0.16f, 0.25f, 0.36f, 0.25f, 0.16f, 0.14f, 0.14f)
    private val currentHeights = idleProfile.copyOf()

    private var isListening = false
    private var canStartFromTap = true
    private var targetLevel = 0f
    private var smoothedLevel = 0f
    private var animPhase = 0f
    private var lastFrameMs = 0L
    private var downInBounds = false

    init {
        isClickable = true
        isFocusable = false
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val radius = view.height / 2f
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
    }

    fun setMode(listening: Boolean, rmsDb: Float, idleDragAllowed: Boolean = !listening) {
        val wasListening = isListening
        isListening = listening
        canStartFromTap = idleDragAllowed
        targetLevel = if (listening) {
            ((rmsDb + 2f) / 12f).coerceIn(0.08f, 1f)
        } else {
            0f
        }
        if (listening && !wasListening) {
            lastFrameMs = SystemClock.uptimeMillis()
            postInvalidateOnAnimation()
        } else if (!listening && wasListening) {
            postInvalidateOnAnimation()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val inBounds = event.x in 0f..width.toFloat() && event.y in 0f..height.toFloat()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downInBounds = inBounds
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (downInBounds && inBounds && !isListening && canStartFromTap) {
                    onIdlePillTapped()
                }
                downInBounds = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                // Triggered cleanly when DraggableWindowWrapper intercepts a slide gesture for dragging.
                downInBounds = false
                return true
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val cornerRadius = h / 2f
        pillRect.set(0f, 0f, w, h)
        canvas.drawRoundRect(pillRect, cornerRadius, cornerRadius, pillPaint)

        val now = SystemClock.uptimeMillis()
        val dt = ((now - lastFrameMs).coerceIn(8L, 50L)) / 1000f
        lastFrameMs = now

        smoothedLevel += (targetLevel - smoothedLevel) * (dt * 12f).coerceIn(0.15f, 0.7f)
        if (isListening) {
            animPhase += dt * 7.5f
        }

        val barWidth = 3.5f * density
        val barGap = 5.5f * density
        val maxBarHeight = h * 0.56f
        val minBarHeight = barWidth
        val totalBarsWidth = barCount * barWidth + (barCount - 1) * barGap
        val startX = (w - totalBarsWidth) / 2f
        val centerY = h / 2f

        var needsNextFrame = false

        for (i in 0 until barCount) {
            val targetRatio = if (isListening) {
                val centerWeight = 1f - (kotlin.math.abs(i - 4) * 0.13f)
                val wave = 0.5f + 0.5f * sin(animPhase + i * 0.85f)
                (0.16f + smoothedLevel * centerWeight * (0.45f + 0.55f * wave)).coerceIn(0.14f, 1f)
            } else {
                idleProfile[i]
            }

            currentHeights[i] += (targetRatio - currentHeights[i]) * 0.25f
            if (isListening || kotlin.math.abs(currentHeights[i] - idleProfile[i]) > 0.01f) {
                needsNextFrame = true
            }

            val barH = (minBarHeight + (maxBarHeight - minBarHeight) * currentHeights[i])
                .coerceIn(minBarHeight, maxBarHeight)
            val left = startX + i * (barWidth + barGap)
            val top = centerY - barH / 2f
            barRect.set(left, top, left + barWidth, top + barH)
            canvas.drawRoundRect(barRect, barWidth / 2f, barWidth / 2f, barPaint)
        }

        if (needsNextFrame && isAttachedToWindow) {
            postInvalidateOnAnimation()
        }
    }

    companion object {
        const val PILL_BG_COLOR = 0xFFB8B4C4.toInt()
        const val BAR_COLOR = 0xFF1C1B1F.toInt()
    }
}
