package com.wsp.plugins.spatialvision.helloar

import android.graphics.Bitmap
import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ARCaptureHelper {
    fun captureScreen(width: Int, height: Int): Bitmap {
        // Force rendering to complete
        GLES30.glFinish()

        // Create buffer
        val buffer = ByteBuffer.allocateDirect(width * height * 4)
        buffer.order(ByteOrder.nativeOrder())

        // Read pixels directly
        GLES30.glReadPixels(0, 0, width, height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buffer)

        // Check for errors
        val error = GLES30.glGetError()
        if (error != GLES30.GL_NO_ERROR) {
            throw Exception("GL Error: $error")
        }

        // Process with color correction
        val pixels = IntArray(width * height)
        buffer.rewind()

        for (i in 0 until width * height) {
            val r = buffer.get().toInt() and 0xFF
            val g = buffer.get().toInt() and 0xFF
            val b = buffer.get().toInt() and 0xFF
            val a = buffer.get().toInt() and 0xFF

            // Un-premultiply alpha
            val finalR = if (a > 0) (r * 255 / a).coerceIn(0, 255) else r
            val finalG = if (a > 0) (g * 255 / a).coerceIn(0, 255) else g
            val finalB = if (a > 0) (b * 255 / a).coerceIn(0, 255) else b

            pixels[i] = (a shl 24) or (finalR shl 16) or (finalG shl 8) or finalB
        }

        // Flip vertically
        val flipped = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                flipped[(height - y - 1) * width + x] = pixels[y * width + x]
            }
        }

        return Bitmap.createBitmap(flipped, width, height, Bitmap.Config.ARGB_8888)
    }
}