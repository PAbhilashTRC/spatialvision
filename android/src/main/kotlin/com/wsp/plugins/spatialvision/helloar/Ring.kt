package com.wsp.plugins.spatialvision.helloar

import android.opengl.GLES30
import android.util.Log
import com.google.ar.core.Pose
import com.wsp.plugins.spatialvision.common.samplerender.Framebuffer
import com.wsp.plugins.spatialvision.common.samplerender.Mesh
import com.wsp.plugins.spatialvision.common.samplerender.SampleRender
import com.wsp.plugins.spatialvision.common.samplerender.Shader
import com.wsp.plugins.spatialvision.common.samplerender.VertexBuffer
import com.wsp.plugins.spatialvision.common.samplerender.IndexBuffer
import com.wsp.plugins.spatialvision.helloar.HelloArRenderer.Companion.TAG
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

class Ring {

    private lateinit var ringShader: Shader
    private lateinit var ringMesh: Mesh
    private lateinit var ringVertexBuffer: VertexBuffer
    private lateinit var ringIndexBuffer: IndexBuffer

    private var innerRadius: Float = 0.015f
    private var outerRadius: Float = 0.025f

    fun onSurfaceCreated(render: SampleRender) {
        try {
            ringShader = Shader.createFromAssets(
                render,
                "shaders/ring.vert",
                "shaders/ring.frag",
                null
            )

            // Ring Geometry (angle segments)
            val segments = 64
            val vertexCount = (segments + 1) * 2

            val data = FloatArray(vertexCount * 3)
            var index = 0

            for (i in 0..segments) {
                val angle = (2.0 * Math.PI * i / segments).toFloat()
                val cosAngle = Math.cos(angle.toDouble()).toFloat()
                val sinAngle = Math.sin(angle.toDouble()).toFloat()

                // Inner vertex
                data[index++] = innerRadius * cosAngle
                data[index++] = 0f
                data[index++] = innerRadius * sinAngle

                // Outer vertex
                data[index++] = outerRadius * cosAngle
                data[index++] = 0f
                data[index++] = outerRadius * sinAngle
            }

            val ringBuffer = ByteBuffer.allocateDirect(data.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
            ringBuffer.put(data).position(0)

            ringVertexBuffer = VertexBuffer(render, 3, ringBuffer)

            // Generate indices
            val indices = IntArray(segments * 6)
            var idxIndex = 0

            for (i in 0 until segments) {
                val inner1 = i * 2
                val outer1 = i * 2 + 1
                val inner2 = (i + 1) * 2
                val outer2 = (i + 1) * 2 + 1

                indices[idxIndex++] = inner1
                indices[idxIndex++] = outer1
                indices[idxIndex++] = inner2

                indices[idxIndex++] = outer1
                indices[idxIndex++] = outer2
                indices[idxIndex++] = inner2
            }

            // Create IntBuffer properly for IndexBuffer
            val indexIntBuffer = ByteBuffer.allocateDirect(indices.size * 4)
                .order(ByteOrder.nativeOrder())
                .asIntBuffer()
            indexIntBuffer.put(indices)
            indexIntBuffer.position(0)

            ringIndexBuffer = IndexBuffer(render, indexIntBuffer)

            ringMesh = Mesh(
                render,
                Mesh.PrimitiveMode.TRIANGLES,
                ringIndexBuffer,
                arrayOf(ringVertexBuffer)
            )

        } catch (e: IOException) {
            Log.e(TAG, "Failed to read a required asset file for ring", e)
        }
    }

    fun draw(render: SampleRender,
             position: FloatArray,
             rotationMatrix: FloatArray? = null,
             viewMatrix: FloatArray,
             projectionMatrix: FloatArray,
             framebuffer: Framebuffer? = null
    ) {

        ringShader.setMat4("u_View", viewMatrix)
        ringShader.setMat4("u_Proj", projectionMatrix)

        val modelMatrix = FloatArray(16)
        android.opengl.Matrix.setIdentityM(modelMatrix, 0)
        android.opengl.Matrix.translateM(modelMatrix, 0, position[0], position[1], position[2])

        if (rotationMatrix != null) {
            android.opengl.Matrix.multiplyMM(modelMatrix, 0, modelMatrix, 0, rotationMatrix, 0)
        }

        ringShader.setMat4("u_Model", modelMatrix)
//        ringShader.setVec4("u_Color", floatArrayOf(1.0f, 0.2f, 0.2f, 0.9f)) // Red ring for reticle

        val depthWasEnabled = GLES30.glIsEnabled(GLES30.GL_DEPTH_TEST)


        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)

//        render.draw(ringMesh, ringShader)
        if (framebuffer != null) {
            render.draw(ringMesh, ringShader, framebuffer)
        } else {
            render.draw(ringMesh, ringShader)
        }

        GLES30.glDisable(GLES30.GL_BLEND)
        if (!depthWasEnabled) {
            GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        }
    }

    fun setInnerRadius(newRadius: Float) {
        innerRadius = newRadius.coerceAtLeast(0.005f)
    }

    fun setOuterRadius(newRadius: Float) {
        outerRadius = newRadius.coerceAtLeast(innerRadius + 0.005f)
    }

    fun setRadius(radius: Float) {
        val thickness = 0.008f // 8mm fixed thickness for reticle
        innerRadius = (radius - thickness).coerceAtLeast(0.001f)
        outerRadius = radius
    }

    fun setRadius(innerRad: Float, outerRad: Float) {
        innerRadius = innerRad.coerceAtLeast(0.001f)
        outerRadius = outerRad.coerceAtLeast(innerRadius + 0.001f)
    }
}