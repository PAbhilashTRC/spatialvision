package com.wsp.plugins.spatialvision.common.helpers;

import android.content.Context;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnTouchListener;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

public final class TapHelper implements OnTouchListener {

    private final GestureDetector gestureDetector;

    // Tap queue (unchanged)
    private final BlockingQueue<MotionEvent> queuedSingleTaps = new ArrayBlockingQueue<>(16);

    // Drag state
    private volatile MotionEvent currentDragEvent = null;
    private volatile boolean isDragging = false;

    private float lastX = 0f;
    private float lastY = 0f;

    private float deltaX = 0f;
    private float deltaY = 0f;

    public TapHelper(Context context) {
        gestureDetector =
                new GestureDetector(
                        context,
                        new GestureDetector.SimpleOnGestureListener() {

                            @Override
                            public boolean onSingleTapUp(MotionEvent e) {
                                queuedSingleTaps.offer(e);
                                return true;
                            }

                            @Override
                            public boolean onDown(MotionEvent e) {
                                return true;
                            }
                        });
    }

    // -------------------------
    // TAP API
    // -------------------------
    public MotionEvent pollTap() {
        return queuedSingleTaps.poll();
    }

    // -------------------------
    // DRAG API
    // -------------------------
    public MotionEvent pollDrag() {
        return currentDragEvent;
    }

    public boolean isDragging() {
        return isDragging;
    }

    public float getDeltaX() {
        return deltaX;
    }

    public float getDeltaY() {
        return deltaY;
    }

    // -------------------------
    // TOUCH HANDLING
    // -------------------------
    @Override
    public boolean onTouch(View view, MotionEvent event) {

        // Let GestureDetector handle tap
        gestureDetector.onTouchEvent(event);

        switch (event.getActionMasked()) {

            case MotionEvent.ACTION_DOWN:
                isDragging = true;
                lastX = event.getX();
                lastY = event.getY();
                deltaX = 0f;
                deltaY = 0f;
                currentDragEvent = MotionEvent.obtain(event);
                break;

            case MotionEvent.ACTION_MOVE:
                isDragging = true;

                deltaX = event.getX() - lastX;
                deltaY = event.getY() - lastY;

                lastX = event.getX();
                lastY = event.getY();

                currentDragEvent = MotionEvent.obtain(event);
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                isDragging = false;
                currentDragEvent = null;
                deltaX = 0f;
                deltaY = 0f;
                break;
        }

        return true;
    }
}