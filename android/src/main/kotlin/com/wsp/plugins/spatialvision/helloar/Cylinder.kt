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

// Define Face data class if needed for GLB export
data class Face(val indices: IntArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as Face
        return indices.contentEquals(other.indices)
    }
    override fun hashCode(): Int {
        return indices.contentHashCode()
    }
}

class Cylinder {

    private lateinit var cylinderShader: Shader
    private lateinit var cylinderMesh: Mesh
    private lateinit var cylinderVertexBuffer: VertexBuffer

    fun onSurfaceCreated(render: SampleRender) {
        try{
            cylinderShader = Shader.createFromAssets(
                render,
                "shaders/pole.vert",
                "shaders/pole.frag",
                null
            )

            val segments = 24
            val vertexCount = segments * 2

            val data = FloatArray(vertexCount * 2)

            var index = 0
            for (i in 0 until segments) {
                val angle = (2.0 * Math.PI * i / segments).toFloat()

                data[index++] = angle
                data[index++] = 0f

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
             radius: Float,
             asset: String){
        cylinderShader.setVec3("u_Start", floatArrayOf(start.x, start.y, start.z))
        cylinderShader.setVec3("u_End", floatArrayOf(end.x, end.y, end.z))
        cylinderShader.setMat4("u_View", viewMatrix)
        cylinderShader.setMat4("u_Proj", projectionMatrix)
        cylinderShader.setVec4("u_Color", uColor)
        cylinderShader.setVec3("uPointLightingLocation", floatArrayOf(0.8f, 0.8f, 0.0f))
        cylinderShader.setFloat("u_Radius", radius)

        cylinderShader.setVec3("uAmbientColor", floatArrayOf(0.4f, 0.4f, 0.4f))

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

    private fun computeBasis(dir: FloatArray): Pair<FloatArray, FloatArray> {
        val up = floatArrayOf(0f, 1f, 0f)

        val dot = dir[0]*up[0] + dir[1]*up[1] + dir[2]*up[2]
        val safeUp = if (kotlin.math.abs(dot) > 0.99f) {
            floatArrayOf(1f, 0f, 0f)
        } else up

        val right = floatArrayOf(
            dir[1]*safeUp[2] - dir[2]*safeUp[1],
            dir[2]*safeUp[0] - dir[0]*safeUp[2],
            dir[0]*safeUp[1] - dir[1]*safeUp[0]
        )

        val rLen = kotlin.math.sqrt(right[0]*right[0] + right[1]*right[1] + right[2]*right[2])
        for (i in 0..2) right[i] /= rLen

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
        dir: FloatArray,
        view: FloatArray,
        proj: FloatArray,
        uColor: FloatArray,
        asset: String
    ) {
        val boltHeight = 0.05f
        val insulatorHeight = 0.08f

        val boltEnd = Vec3(
            attachPoint.x + dir[0] * boltHeight,
            attachPoint.y + dir[1] * boltHeight,
            attachPoint.z + dir[2] * boltHeight
        )

        draw(render, attachPoint, boltEnd, view, proj,
            floatArrayOf(0.2f, 0.2f, 0.2f, 1f), radius=0.005f, asset)

        val insStart = boltEnd
        val insEnd = Vec3(
            insStart.x + dir[0] * insulatorHeight,
            insStart.y + dir[1] * insulatorHeight,
            insStart.z + dir[2] * insulatorHeight
        )

        draw(render, insStart, insEnd, view, proj,
            uColor, radius=0.015f, asset)
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

        val dir = floatArrayOf(
            end.x - start.x,
            end.y - start.y,
            end.z - start.z
        )

        val len = kotlin.math.sqrt(
            dir[0]*dir[0] + dir[1]*dir[1] + dir[2]*dir[2]
        )

        for (i in 0..2) dir[i] /= len

        val (right, forward) = computeBasis(dir)

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

            val normal = MathUtils.normalize(circle)

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

        for (i in 0 until segments) {

            val next = (i + 1) % segments

            val b1 = bottomRing[i]
            val b2 = bottomRing[next]
            val t1 = topRing[i]
            val t2 = topRing[next]

            indices.addAll(listOf(b1, t1, b2))
            indices.addAll(listOf(t1, t2, b2))
        }

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

            for (i in 0 until segments) {
                val next = (i + 1) % segments
                indices.addAll(
                    listOf(bottomCenterIndex, bottomRing[next], bottomRing[i])
                )
            }

            for (i in 0 until segments) {
                val next = (i + 1) % segments
                indices.addAll(
                    listOf(topCenterIndex, topRing[i], topRing[next])
                )
            }
        }

        return Pair(vertices, indices)
    }

    // RENAMED: generateInsulatorMeshLegacy to avoid conflict
    fun generateInsulatorMeshLegacy(
        base: Vec3,
        dir: FloatArray
    ): Pair<List<Vertex>, List<Int>> {

        val vertices = mutableListOf<Vertex>()
        val faces = mutableListOf<Int>()

        var vertexOffset = 0

        val boltHeight = 0.05f
        val insHeight = 0.08f

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

        vertexOffset += bVerts.size

        val insEnd = Vec3(
            boltEnd.x + dir[0] * insHeight,
            boltEnd.y + dir[1] * insHeight,
            boltEnd.z + dir[2] * insHeight
        )

        val (iVerts, iFaces) = generateCylinderMeshWorld(start = boltEnd, end = insEnd, radius = 0.012f, segments = 16)

        val insOffset = vertexOffset
        vertices.addAll(iVerts)

        iFaces.forEach {
            faces.add(it + insOffset)
        }

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

            offset += segVerts.size
        }

        return Pair(vertices, indices)
    }

    /**
     * Generate insulator mesh between two points (base and tip) - Returns List<Int> for indices
     * to be compatible with the rest of the class
     */
    fun generateInsulatorMeshWithPoints(
        base: Vec3,
        tip: Vec3
    ): Pair<List<Vertex>, List<Int>> {

        val direction = floatArrayOf(
            tip.x - base.x,
            tip.y - base.y,
            tip.z - base.z
        )
        val height = MathUtils.distance(base, tip)

        if (height <= 0) return Pair(emptyList(), emptyList())

        val len = kotlin.math.sqrt(direction[0]*direction[0] + direction[1]*direction[1] + direction[2]*direction[2])
        direction[0] /= len
        direction[1] /= len
        direction[2] /= len

        val segments = 16
        val radiusBottom = 0.012f
        val radiusTop = 0.008f
        val radiusMid = 0.015f

        val vertices = mutableListOf<Vertex>()
        val indices = mutableListOf<Int>()

        // Store ring vertices for each level
        val rings = mutableListOf<MutableList<Int>>()

        for (i in 0..segments) {
            val t = i.toFloat() / segments
            val y = t * height

            val radius = when {
                t < 0.2f -> radiusBottom + (radiusMid - radiusBottom) * (t / 0.2f)
                t < 0.8f -> radiusMid
                else -> radiusMid + (radiusTop - radiusMid) * ((t - 0.8f) / 0.2f)
            }

            val pos = Vec3(
                base.x + direction[0] * y,
                base.y + direction[1] * y,
                base.z + direction[2] * y
            )

            val ringVertices = mutableListOf<Int>()
            for (j in 0..segments) {
                val angle = 2.0f * Math.PI.toFloat() * j / segments
                val nx = kotlin.math.cos(angle)
                val nz = kotlin.math.sin(angle)

                val (right, forward ) = computeBasis(direction);

                val offset = floatArrayOf(
                    right[0] * nx * radius + forward[0] * nz * radius,
                    right[1] * nx * radius + forward[1] * nz * radius,
                    right[2] * nx * radius + forward[2] * nz * radius
                )

                val vertex = Vec3(
                    pos.x + offset[0],
                    pos.y + offset[1],
                    pos.z + offset[2]
                )

                val normal = MathUtils.normalize(floatArrayOf(offset[0], offset[1], offset[2]))

                vertices.add(Vertex(
                    floatArrayOf(vertex.x, vertex.y, vertex.z),
                    floatArrayOf(normal[0], normal[1], normal[2]),
                    floatArrayOf(j.toFloat() / segments, t)
                ))
                ringVertices.add(vertices.size - 1)
            }
            rings.add(ringVertices)
        }

        // Generate faces (triangles) between rings
        for (i in 0 until segments) {
            val currentRing = rings[i]
            val nextRing = rings[i + 1]

            for (j in 0 until segments) {
                val nextJ = (j + 1) % segments

                val a = currentRing[j]
                val b = currentRing[nextJ]
                val c = nextRing[j]
                val d = nextRing[nextJ]

                // Triangle 1: a, b, c
                indices.add(a)
                indices.add(b)
                indices.add(c)

                // Triangle 2: b, d, c
                indices.add(b)
                indices.add(d)
                indices.add(c)
            }
        }

        return Pair(vertices, indices)
    }

    fun generateBoltMeshAligned(
        center: Vec3,
        direction: FloatArray,
        radius: Float,
        height: Float
    ): Pair<List<Vertex>, List<Int>> {

        val half = height / 2f

        val start = Vec3(
            center.x - direction[0] * half,
            center.y - direction[1] * half,
            center.z - direction[2] * half
        )

        val end = Vec3(
            center.x + direction[0] * half,
            center.y + direction[1] * half,
            center.z + direction[2] * half
        )

        return generateCylinderMeshWorld(start, end, radius, 12, true)
    }

    /**
     * Generate bolt mesh at a point - Returns List<Int> for indices
     */
    fun generateBoltMeshWithPoints(
        center: Vec3,
        radius: Float = 0.008f,
        height: Float = 0.012f
    ): Pair<List<Vertex>, List<Int>> {
        val segments = 12
        val vertices = mutableListOf<Vertex>()
        val indices = mutableListOf<Int>()

        val headRadius = radius * 1.5f
        val headHeight = height * 0.6f
        val shaftRadius = radius
        val shaftHeight = height * 0.4f

        var offset = 0

        // Bolt head
        val (headVerts, headFaces) = generateCylinderMeshWorld(
            Vec3(center.x, center.y + shaftHeight/2, center.z),
            Vec3(center.x, center.y + shaftHeight/2 + headHeight, center.z),
            headRadius,
            segments,
            true
        )

        vertices.addAll(headVerts)
        headFaces.forEach { indices.add(it + offset) }
        offset += headVerts.size

        // Bolt shaft
        val (shaftVerts, shaftFaces) = generateCylinderMeshWorld(
            Vec3(center.x, center.y - shaftHeight/2, center.z),
            Vec3(center.x, center.y + shaftHeight/2, center.z),
            shaftRadius,
            segments,
            true
        )

        vertices.addAll(shaftVerts)
        shaftFaces.forEach { indices.add(it + offset) }

        return Pair(vertices, indices)
    }
}

data class Vertex(
    val position: FloatArray,
    val normal: FloatArray,
    val uv: FloatArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as Vertex
        return position.contentEquals(other.position) &&
                normal.contentEquals(other.normal) &&
                uv.contentEquals(other.uv)
    }
    override fun hashCode(): Int {
        var result = position.contentHashCode()
        result = 31 * result + normal.contentHashCode()
        result = 31 * result + uv.contentHashCode()
        return result
    }
}
