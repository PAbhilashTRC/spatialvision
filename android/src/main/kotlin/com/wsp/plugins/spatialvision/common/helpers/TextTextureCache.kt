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
    fun get(render: SampleRender, string: String): Texture {
        return cacheMap.computeIfAbsent(string) {
            generateTexture(render, string)
        }
    }

    private fun generateTexture(render: SampleRender, string: String): Texture {
        val texture = Texture(render, Texture.Target.TEXTURE_2D, Texture.WrapMode.CLAMP_TO_EDGE)

        val bitmap = generateBitmapFromString(string)
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

//    val textPaint = Paint().apply {
//        textSize = 26f
//        setARGB(0xff, 0xff, 0x00, 0x00)
//        style = Paint.Style.FILL
//        isAntiAlias = true
//        textAlign = Paint.Align.CENTER
//        typeface = Typeface.DEFAULT_BOLD
//        strokeWidth = 2f
//    }

    val textPaint = Paint().apply {
        textSize = 26f
        setARGB(0xff, 0xff, 0x00, 0x00) // pure red
        style = Paint.Style.FILL
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    val strokePaint = Paint(textPaint).apply {
        setARGB(0xff, 0x00, 0x00, 0x00)
        style = Paint.Style.STROKE
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

    val highlightPaint = Paint().apply {
        textSize = 26f
        color = android.graphics.Color.WHITE
        style = Paint.Style.STROKE
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
        strokeWidth = 1.2f
        alpha = 80
    }

    private fun generateBitmapFromString(string: String): Bitmap {
        val w = 256
        val h = 256

        return createBitmap(w, h).apply {
            eraseColor(0)

            val canvas = Canvas(this)

            // Metallic gradient paint
//            val shader = android.graphics.LinearGradient(
//                0f, 0f, 0f, h.toFloat(),
//                intArrayOf(
//                    android.graphics.Color.parseColor("#F5F5F5"), // highlight
//                    android.graphics.Color.parseColor("#B0B0B0"), // mid silver
//                    android.graphics.Color.parseColor("#6E6E6E")  // shadow
//                ),
//                floatArrayOf(0f, 0.5f, 1f),
//                android.graphics.Shader.TileMode.CLAMP
//            )

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

}