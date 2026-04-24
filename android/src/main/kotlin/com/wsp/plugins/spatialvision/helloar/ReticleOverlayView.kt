package com.wsp.plugins.spatialvision.helloar

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator

class ReticleOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var isSurfaceDetected = false
    private var reticleState = ReticleState.SEARCHING

    // Smaller, more precise paints
    private var dotPaint: Paint
    private var ringPaint: Paint
    private var outerRingPaint: Paint
    private var cornerPaint: Paint

    private var pulseAnimator: ValueAnimator? = null
    private var currentPulseScale = 1.0f

    enum class ReticleState {
        SEARCHING,    // No surface detected
        READY,        // Good surface detected
        PLACING,      // About to place anchor
        PLACED        // Anchor placed successfully
    }

    init {
        // Center dot - very small and semi-transparent
        dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
            alpha = 180 // 70% opacity
        }

        // Inner ring - thin and subtle
        ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 1.5f
            alpha = 150 // 60% opacity
        }

        // Outer ring - very subtle
        outerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#80FFFFFF") // White with 50% opacity
            style = Paint.Style.STROKE
            strokeWidth = 0.8f
            alpha = 100
        }

        // Corner markers for precise placement
        cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 2f
            alpha = 200
        }

        setBackgroundColor(Color.TRANSPARENT)

        setupPulseAnimation()
    }

    private fun setupPulseAnimation() {
        pulseAnimator = ValueAnimator.ofFloat(1.0f, 1.15f).apply {
            duration = 800
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                currentPulseScale = it.animatedValue as Float
                invalidate()
            }
        }
    }

    fun updateSurfaceDetection(detected: Boolean, isValidSurface: Boolean = true) {
        isSurfaceDetected = detected

        reticleState = when {
            !detected -> ReticleState.SEARCHING
            isValidSurface -> ReticleState.READY
            else -> ReticleState.SEARCHING
        }

        // Update colors based on state
        when (reticleState) {
            ReticleState.SEARCHING -> {
                ringPaint.color = Color.parseColor("#FF9800") // Orange
                ringPaint.alpha = 120
                dotPaint.alpha = 100
                if (pulseAnimator?.isRunning == false) pulseAnimator?.start()
            }
            ReticleState.READY -> {
                ringPaint.color = Color.parseColor("#4CAF50") // Green
                ringPaint.alpha = 180
                dotPaint.alpha = 200
                if (pulseAnimator?.isRunning == false) pulseAnimator?.start()
            }
            ReticleState.PLACING -> {
                ringPaint.color = Color.parseColor("#2196F3") // Blue
                ringPaint.alpha = 255
                dotPaint.alpha = 255
                pulseAnimator?.cancel()
            }
            ReticleState.PLACED -> {
                ringPaint.color = Color.parseColor("#00FF00") // Bright green
                ringPaint.alpha = 255
                dotPaint.alpha = 255
                pulseAnimator?.cancel()
            }
        }

        invalidate()
    }

    fun showPlacingFeedback() {
        reticleState = ReticleState.PLACING
        invalidate()
        // Reset after animation
        postDelayed({
            if (isSurfaceDetected) {
                reticleState = ReticleState.READY
                invalidate()
            }
        }, 200)
    }

    fun showPlacedFeedback() {
        reticleState = ReticleState.PLACED
        invalidate()
        // Fade out after successful placement
        postDelayed({
            if (reticleState == ReticleState.PLACED) {
                animate().alpha(0f).setDuration(300).withEndAction {
                    visibility = View.GONE
                    alpha = 1f
                }.start()
            }
        }, 500)
    }

    fun resetAndShow() {
        visibility = View.VISIBLE
        alpha = 1f
        reticleState = ReticleState.SEARCHING
        invalidate()
    }

    fun showReticle(show: Boolean) {
        if (show) {
            if (visibility != View.VISIBLE) {
                visibility = View.VISIBLE
                resetAndShow()
            }
        } else {
            visibility = View.GONE
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val centerX = (width / 2).toFloat()
        val centerY = (height / 2).toFloat()

        // Apply pulse scale when in searching/ready state
        val scale = if (reticleState == ReticleState.SEARCHING || reticleState == ReticleState.READY) {
            currentPulseScale
        } else {
            1.0f
        }

        // Very small ring sizes for precision
        val innerRingRadius = 15f * scale
        val outerRingRadius = 22f * scale
        val dotRadius = 3f // Tiny center dot

        // Draw outer subtle ring (pulsing)
        if (reticleState != ReticleState.PLACED) {
            canvas.drawCircle(centerX, centerY, outerRingRadius, outerRingPaint)
        }

        // Draw inner ring
        canvas.drawCircle(centerX, centerY, innerRingRadius, ringPaint)

        // Draw tiny center dot for precise placement
        canvas.drawCircle(centerX, centerY, dotRadius, dotPaint)

        // Draw corner markers for alignment (like a camera reticle)
        val cornerSize = 12f
        val cornerOffset = innerRingRadius + 4f

        // Top-left corner
        canvas.drawLine(
            centerX - cornerOffset, centerY - cornerOffset - cornerSize,
            centerX - cornerOffset, centerY - cornerOffset,
            cornerPaint
        )
        canvas.drawLine(
            centerX - cornerOffset - cornerSize, centerY - cornerOffset,
            centerX - cornerOffset, centerY - cornerOffset,
            cornerPaint
        )

        // Top-right corner
        canvas.drawLine(
            centerX + cornerOffset, centerY - cornerOffset - cornerSize,
            centerX + cornerOffset, centerY - cornerOffset,
            cornerPaint
        )
        canvas.drawLine(
            centerX + cornerOffset + cornerSize, centerY - cornerOffset,
            centerX + cornerOffset, centerY - cornerOffset,
            cornerPaint
        )

        // Bottom-left corner
        canvas.drawLine(
            centerX - cornerOffset, centerY + cornerOffset + cornerSize,
            centerX - cornerOffset, centerY + cornerOffset,
            cornerPaint
        )
        canvas.drawLine(
            centerX - cornerOffset - cornerSize, centerY + cornerOffset,
            centerX - cornerOffset, centerY + cornerOffset,
            cornerPaint
        )

        // Bottom-right corner
        canvas.drawLine(
            centerX + cornerOffset, centerY + cornerOffset + cornerSize,
            centerX + cornerOffset, centerY + cornerOffset,
            cornerPaint
        )
        canvas.drawLine(
            centerX + cornerOffset + cornerSize, centerY + cornerOffset,
            centerX + cornerOffset, centerY + cornerOffset,
            cornerPaint
        )

        // Optional: Add subtle crosshair (very light)
        val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 0.8f
            alpha = 80
        }

        // Short crosshair lines
        val crosshairLength = 8f
        canvas.drawLine(centerX - crosshairLength, centerY, centerX - dotRadius - 2, centerY, crossPaint)
        canvas.drawLine(centerX + dotRadius + 2, centerY, centerX + crosshairLength, centerY, crossPaint)
        canvas.drawLine(centerX, centerY - crosshairLength, centerX, centerY - dotRadius - 2, crossPaint)
        canvas.drawLine(centerX, centerY + dotRadius + 2, centerX, centerY + crosshairLength, crossPaint)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        pulseAnimator?.cancel()
    }
}