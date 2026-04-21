package com.wsp.plugins.spatialvision.helloar

import com.google.ar.core.Pose
import com.wsp.plugins.spatialvision.common.helpers.ARLabelData
import com.wsp.plugins.spatialvision.common.helpers.TextTextureCache
import com.wsp.plugins.spatialvision.common.samplerender.Mesh
import com.wsp.plugins.spatialvision.common.samplerender.SampleRender
import com.wsp.plugins.spatialvision.common.samplerender.Shader
import com.wsp.plugins.spatialvision.common.samplerender.VertexBuffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

class LabelRender {

    companion object {
        private const val SIZE = 0.2f  // ✔ AR world-space label size

        private val labelViewProjectionMatrix = FloatArray(16)

        // 2 triangles (stable, no strip issues)
        private val QUAD_COORDS: FloatBuffer =
            ByteBuffer.allocateDirect(6 * 2 * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply {
                    put(
                        floatArrayOf(
                            -SIZE, -SIZE,
                            SIZE, -SIZE,
                            -SIZE,  SIZE,

                            -SIZE,  SIZE,
                            SIZE, -SIZE,
                            SIZE,  SIZE
                        )
                    )
                    position(0)
                }

        // UVs
        private val UV_COORDS: FloatBuffer =
            ByteBuffer.allocateDirect(6 * 2 * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply {
                    put(
                        floatArrayOf(
                            0f, 0f,
                            1f, 0f,
                            0f, 1f,

                            0f, 1f,
                            1f, 0f,
                            1f, 1f
                        )
                    )
                    position(0)
                }
    }

    private val cache = TextTextureCache()

    private lateinit var mesh: Mesh
    private lateinit var shader: Shader

    private val labelOrigin = FloatArray(3)
    /**
     * Initialize shader + mesh
     */
    fun onSurfaceCreated(render: SampleRender) {

        shader = Shader.createFromAssets(
            render,
            "shaders/label.vert",
            "shaders/label.frag",
            null
        )
            .setBlend(
                Shader.BlendFactor.ONE,
                Shader.BlendFactor.ONE_MINUS_SRC_ALPHA
            )
            .setDepthTest(false)
            .setDepthWrite(false)

        val vertexBuffers = arrayOf(
            VertexBuffer(render, 2, QUAD_COORDS),
            VertexBuffer(render, 2, UV_COORDS)
        )

        mesh = Mesh(
            render,
            Mesh.PrimitiveMode.TRIANGLES,
            null,
            vertexBuffers
        )
    }

    /**
     * Draw label at AR world pose
     */
    fun draw(
        render: SampleRender,
        viewProjectionMatrix: FloatArray,
        pose: Pose,
        cameraPose: Pose,
        data: ARLabelData,
        showCard: Boolean
    ) {

        // label position in world space
        labelOrigin[0] = pose.tx()
        labelOrigin[1] = pose.ty()
        labelOrigin[2] = pose.tz()

        val texture = if (showCard) {
            // ✅ Full card (existing behavior)
            cache.get(render, data)
        } else {
            // ✅ Simple label (only text)
            cache.getSimpleText(render, data.measurement) // 👈 new method
        }

        shader
            .setMat4("u_ViewProjection", viewProjectionMatrix)
            .setVec3("u_LabelOrigin", labelOrigin)
            .setVec3("u_CameraPos", cameraPose.translation)
            .setTexture("uTexture", texture)
            .setFloat("u_Scale", 1.8f)

        render.draw(mesh, shader)
    }

    fun calculateDistance(start: Vec3,
                          end: Vec3): ARLabelData{
        // --- Draw line between first 2 anchors ---
        val distObjToObj = MathUtils.distance(start, end)
        val labelText = String.format("%.2f m", distObjToObj)

        // ---------------------------
        // DRAW LABEL (SAFE PATH)
        // ---------------------------
        val obj1Formatted = floatArrayOf(start.x,start.y, start.z).joinToString(", ") { "%.2f".format(it) }
        val obj2Formatted = floatArrayOf(end.x,end.y, end.z).joinToString(", ") { "%.2f".format(it) }
        return ARLabelData(title ="Measuring Tool",
            measurement = labelText,
            sourceA = obj1Formatted,
            sourceB = obj2Formatted,
            confidence = "unknow"
        )
    }
}