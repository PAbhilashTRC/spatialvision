package com.wsp.plugins.spatialvision.helloar

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.View.OnTouchListener

class DragHelper(context: Context) : OnTouchListener {

    @Volatile
    var currentEvent: MotionEvent? = null
        private set

    @Volatile
    var isDragging: Boolean = false
        private set

    private var lastX = 0f
    private var lastY = 0f

    var deltaX = 0f
        private set

    var deltaY = 0f
        private set

    override fun onTouch(view: View, event: MotionEvent): Boolean {

        currentEvent = MotionEvent.obtain(event)

        when (event.actionMasked) {

            MotionEvent.ACTION_DOWN -> {
                isDragging = true
                lastX = event.x
                lastY = event.y
                deltaX = 0f
                deltaY = 0f
            }

            MotionEvent.ACTION_MOVE -> {
                isDragging = true

                deltaX = event.x - lastX
                deltaY = event.y - lastY

                lastX = event.x
                lastY = event.y
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                currentEvent = null
                deltaX = 0f
                deltaY = 0f
            }
        }

        return true
    }

    fun poll(): MotionEvent? {
        return currentEvent
    }
}