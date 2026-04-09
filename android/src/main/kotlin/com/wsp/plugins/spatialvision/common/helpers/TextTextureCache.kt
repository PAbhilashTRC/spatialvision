package com.wsp.plugins.spatialvision.common.helpers

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.opengl.GLES30
import com.wsp.plugins.spatialvision.common.samplerender.GLError
import com.wsp.plugins.spatialvision.common.samplerender.SampleRender
import java.nio.ByteBuffer
import com.wsp.plugins.spatialvision.common.samplerender.Texture
import androidx.core.graphics.createBitmap

class TextTextureCache {
    companion object {
        private const val TAG = "TextTextureCache"
    }

    private val cacheMap = mutableMapOf<String, Texture>()

    /**
     * Get a texture for a given string. If that string hasn't been used yet, create a texture for it
     * and cache the result.
     */
//    fun get(render: SampleRender, string: String): Texture {
//        return cacheMap.computeIfAbsent(string) {
//            generateTexture(render, string)
//        }
//    }

    fun getSimpleText(render: SampleRender, measurement: String): Texture {
        return cacheMap.computeIfAbsent(measurement) {
            generateTexture(render, measurement)
        }
    }

    fun get(render: SampleRender, data: ARLabelData): Texture {
        return cacheMap.computeIfAbsent(data.measurement) {
            generateTextureForCard(render, data)
        }
    }

    private fun generateTextureForCard(render: SampleRender, cardData: ARLabelData): Texture{
        val bitmap = generateBitmap(cardData)
        return passTextureToOpenGL( render, bitmap)
    }

    private fun generateTexture(render: SampleRender, measurement: String): Texture {
        val bitmap = generateBitmapFromString(measurement)
        return passTextureToOpenGL( render, bitmap)
    }

    private fun passTextureToOpenGL(render: SampleRender, bitmap: Bitmap): Texture{
        val texture = Texture(render, Texture.Target.TEXTURE_2D, Texture.WrapMode.CLAMP_TO_EDGE)
        val buffer = ByteBuffer.allocateDirect(bitmap.byteCount)
        bitmap.copyPixelsToBuffer(buffer)
        buffer.rewind()

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture.textureId)
        GLError.maybeThrowGLException("Failed to bind texture", "glBindTexture")
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D,
            0,
            GLES30.GL_RGBA8,
            bitmap.width,
            bitmap.height,
            0,
            GLES30.GL_RGBA,
            GLES30.GL_UNSIGNED_BYTE,
            buffer
        )
        GLError.maybeThrowGLException("Failed to populate texture data", "glTexImage2D")
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
        GLError.maybeThrowGLException("Failed to generate mipmaps", "glGenerateMipmap")

        return texture
    }

    val textPaint = Paint().apply {
        textSize = 26f
        setARGB(0xff, 0xff, 0x00, 0x00) // pure red
        style = Paint.Style.FILL
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    val shadowPaint = Paint().apply {
        textSize = 26f
        color = android.graphics.Color.BLACK
        alpha = 140
        style = Paint.Style.FILL
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    val outlinePaint = Paint().apply {
        textSize = 26f
        color = android.graphics.Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 3.0f
        alpha = 200
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    private fun generateBitmapFromString(string: String): Bitmap {
        val w = 256
        val h = 256

        return createBitmap(w, h).apply {
            eraseColor(0)

            val canvas = Canvas(this)

            val shader = android.graphics.LinearGradient(
                0f, 0f, 0f, h.toFloat(),
                intArrayOf(
                    android.graphics.Color.parseColor("#FF8A80"), // light red highlight
                    android.graphics.Color.parseColor("#FF1744"), // strong red mid
                    android.graphics.Color.parseColor("#B71C1C")  // dark red shadow
                ),
                floatArrayOf(0f, 0.5f, 1f),
                android.graphics.Shader.TileMode.CLAMP
            )

            textPaint.shader = shader

            canvas.drawText(
                string,
                w / 2f + 2f,
                h / 2f + 2f,
                shadowPaint
            )

            canvas.drawText(
                string,
                w / 2f,
                h / 2f,
                outlinePaint
            )

            canvas.drawText(
                string,
                w / 2f,
                h / 2f,
                textPaint
            )

            textPaint.shader = null
        }
    }

    private fun generateBitmap(data: ARLabelData): Bitmap {

        val labelPaint = Paint().apply {
            textSize = 18f
            isAntiAlias = true
            color = android.graphics.Color.LTGRAY
            typeface = Typeface.DEFAULT
        }

        val valuePaint = Paint().apply {
            textSize = 18f
            isAntiAlias = true
            color = android.graphics.Color.WHITE
            typeface = Typeface.DEFAULT_BOLD
        }

        val padding = 16
        val lineHeight = 38

        val rows = 5

        val width = 320
        val height = padding * 2 + lineHeight * rows

        return createBitmap(width, height).apply {

            eraseColor(android.graphics.Color.argb(160, 0, 0, 0)) // slightly lighter overlay

            val canvas = Canvas(this)

            var y = padding + 28f

            // Row 1
            canvas.drawText("Title:", 16f, y, labelPaint)
            canvas.drawText(data.title, 150f, y, valuePaint)
            y += lineHeight

            // Row 2
            canvas.drawText("Measure:", 16f, y, labelPaint)
            canvas.drawText(data.measurement, 150f, y, valuePaint)
            y += lineHeight

            // Row 3
            canvas.drawText("Source A:", 16f, y, labelPaint)
            canvas.drawText(data.sourceA, 150f, y, valuePaint)
            y += lineHeight

            // Row 4
            canvas.drawText("Source B:", 16f, y, labelPaint)
            canvas.drawText(data.sourceB, 150f, y, valuePaint)
            y += lineHeight

            // Row 5
            canvas.drawText("Confidence:", 16f, y, labelPaint)
            canvas.drawText(data.confidence, 150f, y, valuePaint)
        }
    }

}
data class ARLabelData(
    val title: String,
    val measurement: String,
    val sourceA: String,
    val sourceB: String,
    val confidence: String
)