import android.graphics.Bitmap
import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ARCaptureHelper(private val width: Int, private val height: Int) {
    private var pboIds = IntArray(2)
    private var currentPboIndex = 0
    private var isPboInitialized = false

    fun initialize() {
        if (isPboInitialized) return

        // Generate PBOs
        GLES30.glGenBuffers(2, pboIds, 0)
        if (pboIds[0] == 0 || pboIds[1] == 0) {
            android.util.Log.e("ARCapture", "Failed to generate PBOs")
            return
        }

        val bufferSize = width * height * 4 // RGBA = 4 bytes per pixel

        for (i in 0..1) {
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, pboIds[i])
            GLES30.glBufferData(GLES30.GL_PIXEL_PACK_BUFFER, bufferSize, null, GLES30.GL_STREAM_READ)
            checkGlError("glBufferData PBO $i")
        }

        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
        isPboInitialized = true
    }

    fun startAsyncCapture() {
        if (!isPboInitialized) {
            initialize()
            if (!isPboInitialized) return
        }

        // Bind the current PBO
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, pboIds[currentPboIndex])

        // Read pixels into PBO (null pointer means write to bound PBO)
        GLES30.glReadPixels(0, 0, width, height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        checkGlError("glReadPixels")

        // Unbind PBO
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)

        // Swap to next PBO for next frame
        currentPboIndex = (currentPboIndex + 1) % 2
    }

    fun getCapturedBitmap(): Bitmap? {
        // Read from the previous PBO (the one that should have data)
        val readIndex = if (currentPboIndex == 0) 1 else 0

        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, pboIds[readIndex])

        // Map the buffer to get data
        val buffer = GLES30.glMapBufferRange(
            GLES30.GL_PIXEL_PACK_BUFFER,
            0,
            (width * height * 4L).toInt(), // Use Long for size
            GLES30.GL_MAP_READ_BIT
        )

        if (buffer != null) {
            // Create a copy of the data (buffer will be unmapped)
            val byteBuffer = ByteBuffer.allocateDirect(width * height * 4)
            byteBuffer.order(ByteOrder.nativeOrder())
            val mappedBuffer = buffer as java.nio.Buffer
            val sourceBuffer = mappedBuffer as java.nio.ByteBuffer
            sourceBuffer.rewind()
            byteBuffer.put(sourceBuffer)
            byteBuffer.rewind()

            // Unmap the buffer
            GLES30.glUnmapBuffer(GLES30.GL_PIXEL_PACK_BUFFER)

            // Process the copied buffer
            val bitmap = processBufferWithColorCorrection(byteBuffer, width, height)

            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
            return bitmap
        }

        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
        return null
    }

    private fun processBufferWithColorCorrection(buffer: ByteBuffer, w: Int, h: Int): Bitmap {
        val pixels = IntArray(w * h)

        for (i in 0 until w * h) {
            val r = buffer.get().toInt() and 0xFF
            val g = buffer.get().toInt() and 0xFF
            val b = buffer.get().toInt() and 0xFF
            val a = buffer.get().toInt() and 0xFF

            // Un-premultiply alpha (fix for AR colors)
            val finalR = if (a > 0) (r * 255 / a).coerceIn(0, 255) else r
            val finalG = if (a > 0) (g * 255 / a).coerceIn(0, 255) else g
            val finalB = if (a > 0) (b * 255 / a).coerceIn(0, 255) else b

            // Pack as ARGB
            pixels[i] = (a shl 24) or (finalR shl 16) or (finalG shl 8) or finalB
        }

        // Flip vertically (OpenGL reads from bottom-left)
        val flipped = IntArray(w * h)
        for (y in 0 until h) {
            val srcRowStart = y * w
            val dstRowStart = (h - y - 1) * w
            System.arraycopy(pixels, srcRowStart, flipped, dstRowStart, w)
        }

        return Bitmap.createBitmap(flipped, w, h, Bitmap.Config.ARGB_8888)
    }

    fun cleanup() {
        if (isPboInitialized) {
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
            GLES30.glDeleteBuffers(2, pboIds, 0)
            isPboInitialized = false
        }
    }

    private fun checkGlError(op: String) {
        val error = GLES30.glGetError()
        if (error != GLES30.GL_NO_ERROR) {
            android.util.Log.e("ARCapture", "$op: glError 0x${Integer.toHexString(error)}")
        }
    }
}

class ARSimpleCapture {

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