package com.gexrture.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View

/** Phone-side touchpad: one-finger drag moves the panel, two-finger pinch resizes it. */
class ControlPadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** Pixel deltas of a one-finger drag. */
    var onDrag: ((dx: Float, dy: Float) -> Unit)? = null
    /** Incremental pinch scale factor. */
    var onPinch: ((factor: Float) -> Unit)? = null

    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            onPinch?.invoke(detector.scaleFactor)
            return true
        }
    })

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(30, 30, 36) }
    private val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(140, 140, 150); textSize = 40f; textAlign = Paint.Align.CENTER
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), 32f, 32f, bg)
        canvas.drawText("drag = move   ·   pinch = resize", width / 2f, height / 2f, hint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x; lastY = event.y; dragging = true
            }
            MotionEvent.ACTION_POINTER_DOWN -> dragging = false // pinch takes over
            MotionEvent.ACTION_MOVE -> if (dragging && event.pointerCount == 1 && !scaleDetector.isInProgress) {
                onDrag?.invoke(event.x - lastX, event.y - lastY)
                lastX = event.x; lastY = event.y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = false
        }
        return true
    }
}
