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
                "shaders/pole.vert",
                "shaders/pole.frag",
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
             start: Vec3,
             end: Vec3,
             viewMatrix: FloatArray,
             projectionMatrix: FloatArray,
            uColor: FloatArray = floatArrayOf(0.0f, 0.45f, 0.15f, 1.0f),
             asset: String){
//        if (asset == "wires"){
//            radius = 0.005f;
//        }
        cylinderShader.setVec3("u_Start", floatArrayOf(start.x, start.y, start.z))
        cylinderShader.setVec3("u_End", floatArrayOf(end.x, end.y, end.z))
        cylinderShader.setMat4("u_View", viewMatrix)
        cylinderShader.setMat4("u_Proj", projectionMatrix)
        cylinderShader.setVec4("u_Color", uColor)
        cylinderShader.setVec3("uPointLightingLocation", floatArrayOf(0.8f, 0.8f, 0.0f))
        cylinderShader.setFloat("u_Radius", radius)

        cylinderShader.setVec3("uAmbientColor", floatArrayOf(0.4f, 0.4f, 0.4f))

        // attenuation (same as sphere)
        cylinderShader.setVec3("uAttenuation", floatArrayOf(1f, 0.14f, 0.07f))

        GLES30.glEnable(GLES30.GL_DEPTH_TEST)

        render.draw(cylinderMesh, cylinderShader)

    }

    fun computeCrossArms(start: Vec3, end: Vec3): List<CrossArm> {
        val dir = floatArrayOf(
            end.x - start.x,
            end.y - start.y,
            end.z - start.z
        )

        val len = kotlin.math.sqrt(dir[0]*dir[0] + dir[1]*dir[1] + dir[2]*dir[2])
        for (i in 0..2) dir[i] /= len

        val (right, _) = computeBasis(dir)

        val top = end
        val armLength = 0.4f

        val arm1Start = Vec3(
            top.x - right[0] * armLength,
            top.y - right[1] * armLength,
            top.z - right[2] * armLength
        )

        val arm1End = Vec3(
            top.x + right[0] * armLength,
            top.y + right[1] * armLength,
            top.z + right[2] * armLength
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

    fun drawInsulator(
        render: SampleRender,
        attachPoint: Vec3,
        dir: FloatArray, // pole direction
        view: FloatArray,
        proj: FloatArray,
        uColor: FloatArray,
        asset: String
    ) {
        val boltHeight = 0.05f
        val insulatorHeight = 0.08f

        // 🔩 Bolt (small vertical piece)
        val boltEnd = Vec3(
            attachPoint.x + dir[0] * boltHeight,
            attachPoint.y + dir[1] * boltHeight,
            attachPoint.z + dir[2] * boltHeight
        )

        setRadius(0.005f)
        draw(render, attachPoint, boltEnd, view, proj,
            floatArrayOf(0.2f, 0.2f, 0.2f, 1f), asset) // metallic

        // ⚪ Insulator (on top of bolt)
        val insStart = boltEnd
        val insEnd = Vec3(
            insStart.x + dir[0] * insulatorHeight,
            insStart.y + dir[1] * insulatorHeight,
            insStart.z + dir[2] * insulatorHeight
        )

        setRadius(0.015f)
        draw(render, insStart, insEnd, view, proj,
            uColor, asset) // ceramic look
    }

    fun generateCylinderMeshWorld(
        start: Vec3,
        end: Vec3,
        radius: Float = 0.05f,
        segments: Int = 24,
        cap: Boolean = true
    ): Pair<List<Vertex>, List<Int>> {

        val vertices = mutableListOf<Vertex>()
        val indices = mutableListOf<Int>()

        // -----------------------------
        // Direction & basis
        // -----------------------------
        val dir = floatArrayOf(
            end.x - start.x,
            end.y - start.y,
            end.z - start.z
        )

        val len = kotlin.math.sqrt(
            dir[0]*dir[0] +
                    dir[1]*dir[1] +
                    dir[2]*dir[2]
        )

        for (i in 0..2) dir[i] /= len

        val (right, forward) = computeBasis(dir)

        // -----------------------------
        // Ring generation (shared verts)
        // -----------------------------
        val bottomRing = mutableListOf<Int>()
        val topRing = mutableListOf<Int>()

        for (i in 0 until segments) {

            val angle = (2.0 * Math.PI * i / segments).toFloat()

            val cosA = kotlin.math.cos(angle)
            val sinA = kotlin.math.sin(angle)

            val circle = floatArrayOf(
                cosA * right[0] + sinA * forward[0],
                cosA * right[1] + sinA * forward[1],
                cosA * right[2] + sinA * forward[2]
            )

            // -------------------------
            // NORMAL (radial)
            // -------------------------
            val normal = MathUtils.normalize(circle)

            // -------------------------
            // UV (wrap around cylinder)
            // -------------------------
            val u = i.toFloat() / segments
            val vBottom = 0f
            val vTop = 1f

            val bottomPos = floatArrayOf(
                start.x + circle[0] * radius,
                start.y + circle[1] * radius,
                start.z + circle[2] * radius
            )

            val topPos = floatArrayOf(
                end.x + circle[0] * radius,
                end.y + circle[1] * radius,
                end.z + circle[2] * radius
            )

            val bottomIndex = vertices.size
            vertices.add(
                Vertex(bottomPos, normal, floatArrayOf(u, vBottom))
            )
            bottomRing.add(bottomIndex)

            val topIndex = vertices.size
            vertices.add(
                Vertex(topPos, normal, floatArrayOf(u, vTop))
            )
            topRing.add(topIndex)
        }

        // -----------------------------
        // Side faces (smooth shading)
        // -----------------------------
        for (i in 0 until segments) {

            val next = (i + 1) % segments

            val b1 = bottomRing[i]
            val b2 = bottomRing[next]
            val t1 = topRing[i]
            val t2 = topRing[next]

            indices.addAll(listOf(b1, t1, b2))
            indices.addAll(listOf(t1, t2, b2))
        }

        // -----------------------------
        // Caps (flat shading normals)
        // -----------------------------
        if (cap) {

            val bottomCenterIndex = vertices.size
            vertices.add(
                Vertex(
                    floatArrayOf(start.x, start.y, start.z),
                    floatArrayOf(-dir[0], -dir[1], -dir[2]),
                    floatArrayOf(0.5f, 0.5f)
                )
            )

            val topCenterIndex = vertices.size
            vertices.add(
                Vertex(
                    floatArrayOf(end.x, end.y, end.z),
                    dir,
                    floatArrayOf(0.5f, 0.5f)
                )
            )

            // bottom cap
            for (i in 0 until segments) {
                val next = (i + 1) % segments
                indices.addAll(
                    listOf(bottomCenterIndex, bottomRing[next], bottomRing[i])
                )
            }

            // top cap
            for (i in 0 until segments) {
                val next = (i + 1) % segments
                indices.addAll(
                    listOf(topCenterIndex, topRing[i], topRing[next])
                )
            }
        }

        return Pair(vertices, indices)
    }

    fun generateInsulatorMesh(
        base: Vec3,
        dir: FloatArray
    ): Pair<List<Vertex>, List<Int>> {

        val vertices = mutableListOf<Vertex>()
        val faces = mutableListOf<Int>()

        var vertexOffset = 0

        val boltHeight = 0.05f
        val insHeight = 0.08f

        // ---------------- BOLT ----------------
        val boltEnd = Vec3(
            base.x + dir[0] * boltHeight,
            base.y + dir[1] * boltHeight,
            base.z + dir[2] * boltHeight
        )

        val (bVerts, bFaces) = generateCylinderMeshWorld(start = base, end = boltEnd, radius = 0.012f, segments = 12)

        val boltOffset = vertexOffset
        vertices.addAll(bVerts)

        bFaces.forEach {
            faces.add(it + boltOffset)
        }
//        faces.addAll(bFaces)

        vertexOffset += bVerts.size

        // ---------------- INSULATOR ----------------
        val insEnd = Vec3(
            boltEnd.x + dir[0] * insHeight,
            boltEnd.y + dir[1] * insHeight,
            boltEnd.z + dir[2] * insHeight
        )

        val (iVerts, iFaces) = generateCylinderMeshWorld(start = boltEnd, end = insEnd,radius = 0.012f, segments = 16)

        val insOffset = vertexOffset
        vertices.addAll(iVerts)

        iFaces.forEach {
            faces.add(it + insOffset)
        }

//        faces.addAll(iFaces)

        return Pair(vertices, faces)
    }

    fun generateWireSplineMesh(
        points: List<Vec3>,
        radius: Float = 0.01f,
        segments: Int = 10,
        resolution: Int = 8
    ): Pair<List<Vertex>, List<Int>> {

        val vertices = mutableListOf<Vertex>()
        val indices = mutableListOf<Int>()

        var offset = 0

        for (i in 0 until points.size - 1) {

            val p0 = points[i]
            val p1 = points[i + 1]

            val (segVerts, segFaces) = generateCylinderMeshWorld(
                p0,
                p1,
                radius = radius,
                segments = segments,
                cap = false
            )

            vertices.addAll(segVerts)

            segFaces.forEach {
                indices.add(it + offset)
            }

            indices.addAll(segFaces)

            offset += segVerts.size
        }

        return Pair(vertices, indices)
    }

}

data class Vertex(
    val position: FloatArray,
    val normal: FloatArray,
    val uv: FloatArray
)