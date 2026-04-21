package com.wsp.plugins.spatialvision.helloar

import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

class GlbExporter {

    private val positions = mutableListOf<Float>()
    private val indices = mutableListOf<Int>()

    private var vertexOffset = 0

    fun addMesh(verts: List<FloatArray>, faces: List<IntArray>) {

        // vertices
        verts.forEach {
            positions.add(it[0])
            positions.add(it[1])
            positions.add(it[2])
        }

        // indices (triangles)
        faces.forEach {
            indices.add(it[0] + vertexOffset)
            indices.add(it[1] + vertexOffset)
            indices.add(it[2] + vertexOffset)
        }

        vertexOffset += verts.size
    }

    fun buildGLB(): ByteArray {
        val vertexBuffer = floatsToByteArray(positions)
        val indexBuffer  = intsToByteArray(indices)

        val vertexCount = positions.size / 3   // because VEC3
        val indexCount = indices.size
        val vertexBytes = vertexCount * 3 * 4
        val indexBytes  = indexCount * 4
        Log.d("GLB", "vertices: ${positions.size / 3}")
        Log.d("GLB", "indices: ${indices.size}")
        Log.d("GLB", "vertexBytes: ${vertexBuffer.size}")
        Log.d("GLB", "indexBytes: ${indexBuffer.size}")
        val gltfJson = buildGltfJson(vertexBytes,
            indexBytes,
            vertexCount,
            indexCount
            )
        // 🔥 COMBINE BOTH (CRITICAL)
        val combined = ByteBuffer
            .allocate(vertexBuffer.size + indexBuffer.size)
            .order(ByteOrder.LITTLE_ENDIAN)

        combined.put(vertexBuffer)
        combined.put(indexBuffer)

        return packGLB(gltfJson, combined.array())
    }

//    private fun toByteArray(floats: List<Float>): ByteArray {
//        val buffer = ByteBuffer.allocate(floats.size * 4)
//            .order(ByteOrder.LITTLE_ENDIAN)
//
//        floats.forEach { buffer.putFloat(it) }
//        return buffer.array()
//    }
//
//    private fun toByteArray(ints: List<Int>): ByteArray {
//        val buffer = ByteBuffer.allocate(ints.size * 4)
//            .order(ByteOrder.LITTLE_ENDIAN)
//
//        ints.forEach { buffer.putInt(it) }
//        return buffer.array()
//    }

    private fun floatsToByteArray(floats: List<Float>): ByteArray {
        val buffer = ByteBuffer.allocate(floats.size * 4)
            .order(ByteOrder.LITTLE_ENDIAN)

        floats.forEach { buffer.putFloat(it) }
        return buffer.array()
    }

    private fun intsToByteArray(ints: List<Int>): ByteArray {
        val buffer = ByteBuffer.allocate(ints.size * 4)
            .order(ByteOrder.LITTLE_ENDIAN)

        ints.forEach { buffer.putInt(it) }
        return buffer.array()
    }

    private fun packGLB(json: String, bin: ByteArray): ByteArray {

        val jsonBytes = json.toByteArray()
        val jsonPadded = pad4Json(jsonBytes)
        val binPadded = pad4Bin(bin)

        val totalLength = 12 +
                8 + jsonPadded.size +
                8 + binPadded.size

        val buffer = ByteBuffer.allocate(totalLength)
            .order(ByteOrder.LITTLE_ENDIAN)

        // Header
        buffer.put(byteArrayOf('g'.code.toByte(), 'l'.code.toByte(), 'T'.code.toByte(), 'F'.code.toByte()))
        buffer.putInt(2)
        buffer.putInt(totalLength)

        // JSON chunk
        buffer.putInt(jsonPadded.size)
        buffer.put(byteArrayOf('J'.code.toByte(), 'S'.code.toByte(), 'O'.code.toByte(), 'N'.code.toByte()))
        buffer.put(jsonPadded)

        // BIN chunk
        buffer.putInt(binPadded.size)
//        buffer.put("BIN\0".toByteArray())
        buffer.put(byteArrayOf('B'.code.toByte(), 'I'.code.toByte(), 'N'.code.toByte(), 0))
        buffer.put(binPadded)

        return buffer.array()
    }

//    private fun pad4(data: ByteArray): ByteArray {
//        val pad = (4 - data.size % 4) % 4
//        return data + ByteArray(pad)
//    }

    private fun pad4Json(data: ByteArray): ByteArray {
        val pad = (4 - data.size % 4) % 4
        val padding = ByteArray(pad) { 0x20 } // ✅ SPACE, not 0
        return data + padding
    }

    private fun pad4Bin(data: ByteArray): ByteArray {
        val pad = (4 - data.size % 4) % 4
        return data + ByteArray(pad) // ✅ 0x00 is correct for BIN
    }

//    private fun buildGltfJson(vertexCount: Int, indexCount: Int): String {
//
//        return """
//{
//  "asset": { "version": "2.0" },
//  "buffers": [
//    { "byteLength": ${vertexCount * 4 + indexCount * 4} }
//  ],
//  "bufferViews": [
//    {
//      "buffer": 0,
//      "byteOffset": 0,
//      "byteLength": ${vertexCount * 4},
//      "target": 34962
//    },
//    {
//      "buffer": 0,
//      "byteOffset": ${vertexCount * 4},
//      "byteLength": ${indexCount * 4},
//      "target": 34963
//    }
//  ],
//  "accessors": [
//    {
//      "bufferView": 0,
//      "componentType": 5126,
//      "count": ${vertexCount / 3},
//      "type": "VEC3"
//    },
//    {
//      "bufferView": 1,
//      "componentType": 5125,
//      "count": ${indexCount},
//      "type": "SCALAR"
//    }
//  ],
//  "meshes": [
//    {
//      "primitives": [
//        {
//          "attributes": {
//            "POSITION": 0
//          },
//          "indices": 1
//        }
//      ]
//    }
//  ],
//  "nodes": [
//    { "mesh": 0 }
//  ],
//  "scenes": [
//    { "nodes": [0] }
//  ],
//  "scene": 0
//}
//"""
//    }

    private fun buildGltfJson(
        vertexBytes: Int,
        indexBytes: Int,
        vertexCount: Int,
        indexCount: Int
    ): String {

        val indexOffset = vertexBytes

        return """
{
  "asset": { "version": "2.0" },

  "buffers": [
    { "byteLength": ${vertexBytes + indexBytes} }
  ],

  "bufferViews": [
    {
      "buffer": 0,
      "byteOffset": 0,
      "byteLength": $vertexBytes,
      "target": 34962
    },
    {
      "buffer": 0,
      "byteOffset": $indexOffset,
      "byteLength": $indexBytes,
      "target": 34963
    }
  ],

  "accessors": [
    {
      "bufferView": 0,
      "componentType": 5126,
      "count": $vertexCount,
      "type": "VEC3"
    },
    {
      "bufferView": 1,
      "componentType": 5125,
      "count": $indexCount,
      "type": "SCALAR"
    }
  ],

  "meshes": [
    {
      "primitives": [
        {
          "attributes": {
            "POSITION": 0
          },
          "indices": 1
        }
      ]
    }
  ],

  "nodes": [{ "mesh": 0 }],
  "scenes": [{ "nodes": [0] }],
  "scene": 0
}
"""
    }
}