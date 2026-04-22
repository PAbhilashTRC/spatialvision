package com.wsp.plugins.spatialvision.helloar

import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

class GlbExporter {
    data class Primitive(
        val positions: MutableList<Float>,
        val normals: MutableList<Float>,
        val indices: MutableList<Int>,
        val materialIndex: Int,
        var vertexOffset: Int = 0
    )

    data class Material(
        val baseColor: FloatArray,
        val metallic: Float,
        val roughness: Float
    )

    private val primitives = mutableListOf<Primitive>()
    private val materials = mutableListOf<Material>()
    var globalVertexOffset = 0

    fun addMaterial(
        baseColor: FloatArray,
        metallic: Float,
        roughness: Float
    ): Int {
        materials.add(Material(baseColor, metallic, roughness))
        return materials.lastIndex
    }

    fun addMesh(
        verts: List<FloatArray>,
        faces: List<Int>,
        normals: List<FloatArray>,
        materialIndex: Int
    ) {
        val pos = mutableListOf<Float>()
        val nor = mutableListOf<Float>()
        val idx = mutableListOf<Int>()

        verts.forEach {
            pos.add(it[0])
            pos.add(it[1])
            pos.add(it[2])
        }

        normals.forEach {
            nor.add(it[0])
            nor.add(it[1])
            nor.add(it[2])
        }

        // Process indices - ensure they're valid
        for (i in 0 until faces.size step 3) {
            if (i + 2 < faces.size) {
                val a = faces[i]
                val b = faces[i + 1]
                val c = faces[i + 2]

                // Validate indices are within vertex range
                if (a >= 0 && a < verts.size &&
                    b >= 0 && b < verts.size &&
                    c >= 0 && c < verts.size) {
                    idx.add(a)
                    idx.add(b)
                    idx.add(c)
                }
            }
        }

        primitives.add(
            Primitive(pos, nor, idx, materialIndex)
        )
    }

    fun buildGLB(): ByteArray {
        // Calculate total buffer size first
        var totalBufferSize = 0
        primitives.forEach { prim ->
            totalBufferSize += prim.positions.size * 4  // 4 bytes per float
            totalBufferSize += prim.normals.size * 4
            totalBufferSize += prim.indices.size * 2   // Use UInt16 for indices if possible
        }

        // Allocate buffer with padding
        val buffer = ByteBuffer.allocate(totalBufferSize + 1024) // Extra space for padding
            .order(ByteOrder.LITTLE_ENDIAN)

        val bufferViews = mutableListOf<String>()
        val accessors = mutableListOf<String>()
        val primitivesJson = mutableListOf<String>()

        var byteOffset = 0
        var accessorIndex = 0

        primitives.forEach { prim ->
            // Skip empty primitives
            if (prim.positions.isEmpty() || prim.indices.isEmpty()) {
                return@forEach
            }

            // POSITION buffer view
            val posOffset = byteOffset
            prim.positions.forEach {
                buffer.putFloat(it)
                byteOffset += 4
            }

            // Ensure 4-byte alignment
            while (byteOffset % 4 != 0) {
                buffer.put(0.toByte())
                byteOffset++
            }

            val posView = bufferViews.size
            bufferViews.add("""
                {"buffer":0,"byteOffset":$posOffset,"byteLength":${prim.positions.size * 4},"target":34962}
            """.trimIndent())

            val posAccessor = accessorIndex++
            accessors.add("""
                {"bufferView":$posView,"componentType":5126,"count":${prim.positions.size / 3},"type":"VEC3"}
            """.trimIndent())

            // NORMAL buffer view
            val norOffset = byteOffset
            prim.normals.forEach {
                buffer.putFloat(it)
                byteOffset += 4
            }

            while (byteOffset % 4 != 0) {
                buffer.put(0.toByte())
                byteOffset++
            }

            val norView = bufferViews.size
            bufferViews.add("""
                {"buffer":0,"byteOffset":$norOffset,"byteLength":${prim.normals.size * 4},"target":34962}
            """.trimIndent())

            val norAccessor = accessorIndex++
            accessors.add("""
                {"bufferView":$norView,"componentType":5126,"count":${prim.normals.size / 3},"type":"VEC3"}
            """.trimIndent())

            // INDICES buffer view - Use USHORT if indices < 65535, otherwise UINT
            val useUShort = prim.indices.maxOrNull() ?: 0 < 65535
            val idxOffset = byteOffset

            if (useUShort) {
                prim.indices.forEach {
                    buffer.putShort(it.toShort())
                    byteOffset += 2
                }
            } else {
                prim.indices.forEach {
                    buffer.putInt(it)
                    byteOffset += 4
                }
            }

            while (byteOffset % 4 != 0) {
                buffer.put(0.toByte())
                byteOffset++
            }

            val idxView = bufferViews.size
            bufferViews.add("""
                {"buffer":0,"byteOffset":$idxOffset,"byteLength":${if (useUShort) prim.indices.size * 2 else prim.indices.size * 4},"target":34963}
            """.trimIndent())

            val componentType = if (useUShort) 5123 else 5125  // 5123 = UNSIGNED_SHORT, 5125 = UNSIGNED_INT
            val idxAccessor = accessorIndex++
            accessors.add("""
                {"bufferView":$idxView,"componentType":$componentType,"count":${prim.indices.size},"type":"SCALAR"}
            """.trimIndent())

            // PRIMITIVE
            primitivesJson.add("""
                {
                    "attributes": {
                        "POSITION": $posAccessor,
                        "NORMAL": $norAccessor
                    },
                    "indices": $idxAccessor,
                    "material": ${prim.materialIndex}
                }
            """.trimIndent())
        }

        // Materials
        val materialsJson = materials.mapIndexed { index, material ->
            """
                {
                    "pbrMetallicRoughness": {
                        "baseColorFactor": [${material.baseColor.joinToString()}],
                        "metallicFactor": ${material.metallic},
                        "roughnessFactor": ${material.roughness}
                    },
                    "name": "Material_$index"
                }
            """.trimIndent()
        }

        val gltfJson = """
            {
                "asset": { "version": "2.0", "generator": "AR Digital Twin Exporter" },
                "buffers": [
                    { "byteLength": $byteOffset }
                ],
                "bufferViews": [
                    ${bufferViews.joinToString(",\n")}
                ],
                "accessors": [
                    ${accessors.joinToString(",\n")}
                ],
                "materials": [
                    ${materialsJson.joinToString(",\n")}
                ],
                "meshes": [
                    {
                        "primitives": [
                            ${primitivesJson.joinToString(",\n")}
                        ]
                    }
                ],
                "nodes": [
                    { "mesh": 0, "name": "DigitalTwin" }
                ],
                "scenes": [
                    { "nodes": [0], "name": "Scene" }
                ],
                "scene": 0
            }
        """.trimIndent()

        // Verify JSON is valid
        val jsonBytes = gltfJson.toByteArray()
        val bin = ByteArray(byteOffset)
        buffer.rewind()
        buffer.get(bin)

        return packGLB(gltfJson, bin)
    }

    private fun packGLB(json: String, bin: ByteArray): ByteArray {
        val jsonBytes = json.toByteArray(Charsets.UTF_8)

        // Calculate padding
        val jsonPadding = (4 - (jsonBytes.size % 4)) % 4
        val binPadding = (4 - (bin.size % 4)) % 4

        val totalLength = 12 + 8 + jsonBytes.size + jsonPadding + 8 + bin.size + binPadding

        val buffer = ByteBuffer.allocate(totalLength)
            .order(ByteOrder.LITTLE_ENDIAN)

        // Header
        buffer.put(0x67) // 'g'
        buffer.put(0x6C) // 'l'
        buffer.put(0x54) // 'T'
        buffer.put(0x46) // 'F'
        buffer.putInt(2)  // Version
        buffer.putInt(totalLength) // Total length

        // JSON chunk
        buffer.putInt(jsonBytes.size + jsonPadding) // Chunk length
        buffer.put(0x4A) // 'J'
        buffer.put(0x53) // 'S'
        buffer.put(0x4F) // 'O'
        buffer.put(0x4E) // 'N'
        buffer.put(jsonBytes)
        repeat(jsonPadding) { buffer.put(0x20) } // Space padding for JSON

        // BIN chunk
        buffer.putInt(bin.size + binPadding) // Chunk length
        buffer.put(0x42) // 'B'
        buffer.put(0x49) // 'I'
        buffer.put(0x4E) // 'N'
        buffer.put(0x00) // null terminator
        buffer.put(bin)
        repeat(binPadding) { buffer.put(0x00) } // Zero padding for BIN

        return buffer.array()
    }
}
