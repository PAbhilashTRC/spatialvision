package com.wsp.plugins.spatialvision.helloar

import android.opengl.GLES30
import android.util.Log
import com.wsp.plugins.spatialvision.common.samplerender.Mesh
import com.wsp.plugins.spatialvision.common.samplerender.SampleRender
import com.wsp.plugins.spatialvision.common.samplerender.Shader
import com.wsp.plugins.spatialvision.common.samplerender.VertexBuffer
import com.wsp.plugins.spatialvision.helloar.HelloArRenderer.Companion.TAG
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

class Cylinder {

    private lateinit var cylinderShader: Shader
    private lateinit var cylinderMesh: Mesh
    private lateinit var cylinderVertexBuffer: VertexBuffer

    fun onSurfaceCreated(render: SampleRender) {
        try{
            cylinderShader = Shader.createFromAssets(
                render,
                "shaders/cylinder.vert",
                "shaders/cylinder.frag",
                null
            ).setFloat("u_Radius", 0.01f) // base radius

            // --- Cylinder Geometry (angle, height) ---
            val segments = 24
            val vertexCount = segments * 2

            val data = FloatArray(vertexCount * 2) // angle + height

            var index = 0
            for (i in 0 until segments) {
                val angle = (2.0 * Math.PI * i / segments).toFloat()

                // bottom
                data[index++] = angle
                data[index++] = 0f

                // top
                data[index++] = angle
                data[index++] = 1f
            }

            val cylinderBuffer = ByteBuffer.allocateDirect(data.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
            cylinderBuffer.put(data).position(0)

            cylinderVertexBuffer = VertexBuffer(render, 2, cylinderBuffer)

            cylinderMesh = Mesh(
                render,
                Mesh.PrimitiveMode.TRIANGLE_STRIP,
                null,
                arrayOf(cylinderVertexBuffer)
            )
        } catch (e: IOException){
            Log.e(TAG, "Failed to read a required asset file", e)
        }

    }

    fun draw(render: SampleRender,
             obj1Pos: FloatArray,
             obj2Pos: FloatArray,
             viewMatrix: FloatArray,
             projectionMatrix: FloatArray){
        cylinderShader.setVec3("u_Start", obj1Pos)
        cylinderShader.setVec3("u_End", obj2Pos)
        cylinderShader.setMat4("u_View", viewMatrix)
        cylinderShader.setMat4("u_Proj", projectionMatrix)
        cylinderShader.setVec4("u_Color", floatArrayOf(0.0f, 0.45f, 0.15f, 1.0f))
        cylinderShader.setVec3("uPointLightingLocation", floatArrayOf(0.8f, 0.8f, 0.0f))

        cylinderShader.setVec3("uAmbientColor", floatArrayOf(0.4f, 0.4f, 0.4f))

        cylinderShader.setVec4("uDiffuseColor", floatArrayOf(1f, 1f, 1f, 1f))
        cylinderShader.setVec4("uSpecularColor", floatArrayOf(1f, 1f, 1f, 1f))

        cylinderShader.setFloat("uMaterialShininess", 16f)

        // attenuation (same as sphere)
        cylinderShader.setVec3("uAttenuation", floatArrayOf(1f, 0.14f, 0.07f))

        GLES30.glEnable(GLES30.GL_DEPTH_TEST)

        render.draw(cylinderMesh, cylinderShader)

    }

}