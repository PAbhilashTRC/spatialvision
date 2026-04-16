package com.wsp.plugins.spatialvision.helloar

import com.wsp.plugins.spatialvision.common.samplerender.Mesh
import com.wsp.plugins.spatialvision.common.samplerender.SampleRender
import com.wsp.plugins.spatialvision.common.samplerender.Shader
import com.wsp.plugins.spatialvision.common.samplerender.VertexBuffer
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PoleWire {

    private lateinit var wireShader: Shader
    private lateinit var wireVertexBuffer: VertexBuffer
    private lateinit var wireMesh: Mesh

    fun onSurfaceCreated(render: SampleRender) {
        try {
            wireShader = Shader.createFromAssets(
                render,
                "shaders/wire.vert",   // ✅ use wire shader
                "shaders/wire.frag",
                null
            )

            // dummy init (will update later)
            val dummy = FloatArray(3)

            val buffer = ByteBuffer.allocateDirect(dummy.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
            buffer.put(dummy).position(0)

            wireVertexBuffer = VertexBuffer(render, 3, buffer)

            wireMesh = Mesh(
                render,
                Mesh.PrimitiveMode.LINE_STRIP,
                null,
                arrayOf(wireVertexBuffer)
            )

        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    fun drawWire(
        render: SampleRender,
        points: FloatArray,
        viewMatrix: FloatArray,
        projectionMatrix: FloatArray
    ) {

        if (points.isEmpty()) return

        // ✅ UPDATE BUFFER EVERY DRAW (important)
        val buffer = ByteBuffer.allocateDirect(points.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()

        buffer.put(points).position(0)

        wireVertexBuffer.set(buffer)

        wireShader.setMat4("u_View", viewMatrix)
        wireShader.setMat4("u_Proj", projectionMatrix)
        wireShader.setVec4("u_Color", floatArrayOf(0f, 0f, 0f, 1f))

        render.draw(wireMesh, wireShader)
    }

    fun computeSag(length: Float): Float {
        return length * 0.05f  // 5% sag (realistic default)
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

    fun distance(a: FloatArray, b: FloatArray): Float{
        val dx = a[0]-b[0];
        val dy = a[1]-b[1];
        val dz = a[2]-b[2];
        return  kotlin.math.sqrt(dx*dx+dy*dy+dz*dz);
    }

}