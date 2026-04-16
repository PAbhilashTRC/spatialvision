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

    private var radius: Float = 0.01f

    fun onSurfaceCreated(render: SampleRender) {
        try{
            cylinderShader = Shader.createFromAssets(
                render,
                "shaders/cylinder.vert",
                "shaders/cylinder.frag",
                null
            ); // base radius

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
             start: FloatArray,
             end: FloatArray,
             viewMatrix: FloatArray,
             projectionMatrix: FloatArray){
        cylinderShader.setVec3("u_Start", start)
        cylinderShader.setVec3("u_End", end)
        cylinderShader.setMat4("u_View", viewMatrix)
        cylinderShader.setMat4("u_Proj", projectionMatrix)
        cylinderShader.setVec4("u_Color", floatArrayOf(0.0f, 0.45f, 0.15f, 1.0f))
        cylinderShader.setVec3("uPointLightingLocation", floatArrayOf(0.8f, 0.8f, 0.0f))
        cylinderShader.setFloat("u_Radius", radius)

        cylinderShader.setVec3("uAmbientColor", floatArrayOf(0.4f, 0.4f, 0.4f))

        cylinderShader.setVec4("uDiffuseColor", floatArrayOf(1f, 1f, 1f, 1f))
        cylinderShader.setVec4("uSpecularColor", floatArrayOf(1f, 1f, 1f, 1f))

        cylinderShader.setFloat("uMaterialShininess", 16f)

        // attenuation (same as sphere)
        cylinderShader.setVec3("uAttenuation", floatArrayOf(1f, 0.14f, 0.07f))

        GLES30.glEnable(GLES30.GL_DEPTH_TEST)

        render.draw(cylinderMesh, cylinderShader)

        // ---- CROSS ARM CALCULATION ----
//        val dir = floatArrayOf(
//            end[0] - start[0],
//            end[1] - start[1],
//            end[2] - start[2]
//        )
//
//        val len = kotlin.math.sqrt(dir[0]*dir[0] + dir[1]*dir[1] + dir[2]*dir[2])
//        for (i in 0..2) dir[i] /= len
//
//        val (right, forward) = computeBasis(dir)
//
//        // Top point of pole
//        val top = end
//
//        val armLength = 0.4f   // 40 cm each side
//        val armRadius = radius * 0.4f
//
//        // ---- CROSS ARM 1 (LEFT ↔ RIGHT) ----
//        val arm1Start = floatArrayOf(
//            top[0] - right[0] * armLength,
//            top[1] - right[1] * armLength,
//            top[2] - right[2] * armLength
//        )
//
//        val arm1End = floatArrayOf(
//            top[0] + right[0] * armLength,
//            top[1] + right[1] * armLength,
//            top[2] + right[2] * armLength
//        )
//
//        cylinderShader.setVec3("u_Start", arm1Start)
//        cylinderShader.setVec3("u_End", arm1End)
//        cylinderShader.setFloat("u_Radius", armRadius)
//        cylinderShader.setVec4("u_Color", floatArrayOf(0.4f, 0.3f, 0.2f, 1f))
//
//        render.draw(cylinderMesh, cylinderShader)
    }
//
//    fun drawPole(
//        render: SampleRender,
//        pole: Pole,
//        viewMatrix: FloatArray,
//        projectionMatrix: FloatArray
//    ) {
//        // Draw main pole
//        drawCylinder(pole.base.position, pole.top.position)
//
//        // Draw cross arms
//        pole.crossArms.forEach {
//            drawCylinder(it.start, it.end)
//        }
//    }



    fun computeCrossArms(start: FloatArray, end: FloatArray): List<CrossArm> {
        val dir = floatArrayOf(
            end[0] - start[0],
            end[1] - start[1],
            end[2] - start[2]
        )

        val len = kotlin.math.sqrt(dir[0]*dir[0] + dir[1]*dir[1] + dir[2]*dir[2])
        for (i in 0..2) dir[i] /= len

        val (right, _) = computeBasis(dir)

        val top = end
        val armLength = 0.4f

        val arm1Start = floatArrayOf(
            top[0] - right[0] * armLength,
            top[1] - right[1] * armLength,
            top[2] - right[2] * armLength
        )

        val arm1End = floatArrayOf(
            top[0] + right[0] * armLength,
            top[1] + right[1] * armLength,
            top[2] + right[2] * armLength
        )

        return listOf(CrossArm(arm1Start, arm1End))
    }

    fun setRadius(newRadius: Float) {
        radius = newRadius.coerceAtLeast(0.01f)
    }

    private fun computeBasis(dir: FloatArray): Pair<FloatArray, FloatArray> {
        val up = floatArrayOf(0f, 1f, 0f)

        // if parallel, switch axis (0,1,0)
        val dot = dir[0]*up[0] + dir[1]*up[1] + dir[2]*up[2]
        val safeUp = if (kotlin.math.abs(dot) > 0.99f) {
            floatArrayOf(1f, 0f, 0f)
        } else up

        // right = dir x up
        val right = floatArrayOf(
            dir[1]*safeUp[2] - dir[2]*safeUp[1],
            dir[2]*safeUp[0] - dir[0]*safeUp[2],
            dir[0]*safeUp[1] - dir[1]*safeUp[0]
        )

        val rLen = kotlin.math.sqrt(right[0]*right[0] + right[1]*right[1] + right[2]*right[2])
        for (i in 0..2) right[i] /= rLen

        // forward = right x dir
        val forward = floatArrayOf(
            right[1]*dir[2] - right[2]*dir[1],
            right[2]*dir[0] - right[0]*dir[2],
            right[0]*dir[1] - right[1]*dir[0]
        )

        return Pair(right, forward)
    }

    fun generateWire(
        start: FloatArray,
        end: FloatArray,
        sag: Float,
        segments: Int = 20
    ): FloatArray {

        val points = FloatArray((segments + 1) * 3)

        val dx = end[0] - start[0]
        val dy = end[1] - start[1]
        val dz = end[2] - start[2]

        val length = kotlin.math.sqrt(dx*dx + dy*dy + dz*dz)

        for (i in 0..segments) {
            val t = i / segments.toFloat()

            // Linear interpolation
            val x = start[0] + dx * t
            val y = start[1] + dy * t
            val z = start[2] + dz * t

            // Sag (parabolic)
            val sagOffset = sag * (t - 0.5f) * (t - 0.5f) * -4f

            val index = i * 3
            points[index] = x
            points[index + 1] = y + sagOffset
            points[index + 2] = z
        }

        return points
    }

    fun computeSag(length: Float): Float {
        return length * 0.05f  // 5% sag (realistic default)
    }



}