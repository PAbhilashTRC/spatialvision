package com.wsp.plugins.spatialvision.helloar

class ObjExporter {

    private val vertices = mutableListOf<Vec3>()
    private val faces = mutableListOf<IntArray>()
    private val lines = mutableListOf<IntArray>()
    private val materials = mutableSetOf<String>()

    fun vertexCount(): Int = vertices.size

    fun addVertex(v: Vec3): Int {
        vertices.add(v)
        return vertices.size // 1-based for OBJ
    }

    fun addFace(i1: Int, i2: Int, i3: Int) {
        faces.add(intArrayOf(i1, i2, i3))
    }

    fun addLine(p1: Vec3, p2: Vec3) {
        val i1 = addVertex(p1)
        val i2 = addVertex(p2)
        lines.add(intArrayOf(i1, i2))
    }

    fun buildObj(): String {

        val sb = StringBuilder()

        // vertices
        for (v in vertices) {
            sb.append("v ${v.x} ${v.y} ${v.z}\n")
        }

        sb.append("\n")

        // faces
        for (f in faces) {
            sb.append("f ${f[0]} ${f[1]} ${f[2]}\n")
        }

        // lines
        for (l in lines) {
            sb.append("l ${l[0]} ${l[1]}\n")
        }

        return sb.toString()
    }
}

//import androidx.compose.remote.creation.step

//class ObjExporter {
//
//    private val vertices = mutableListOf<FloatArray>()
//    private val faces = mutableListOf<IntArray>()
//    private val lines = mutableListOf<IntArray>()
//
//    private val verticesObj = mutableListOf<Vec3>()
//    private val facesObj = mutableListOf<String>()
//    private val materials = mutableSetOf<String>()
//
//    fun addVertex(v: FloatArray): Int {
//        vertices.add(v)
//        return vertices.size // OBJ is 1-based index
//    }
//
//    fun vertexCount(): Int = vertices.size
//
//    fun addFace(i1: Int, i2: Int, i3: Int) {
//        faces.add(intArrayOf(i1, i2, i3))
//    }
//
//    fun addLine(p1: FloatArray, p2: FloatArray) {
//        val i1 = addVertex(p1)
//        val i2 = addVertex(p2)
//        lines.add(intArrayOf(i1, i2))
//    }
//
//    fun addTriangle(v1: FloatArray, v2: FloatArray, v3: FloatArray) {
//        val i1 = addVertex(v1)
//        val i2 = addVertex(v2)
//        val i3 = addVertex(v3)
//        faces.add(intArrayOf(i1, i2, i3))
//    }
//
//    fun buildObj(): String {
//        val sb = StringBuilder()
//
//        // vertices
//        for (v in vertices) {
//            sb.append("v ${v[0]} ${v[1]} ${v[2]}\n")
//        }
//
//        // faces
//        for (f in faces) {
//            sb.append("f ${f[0]} ${f[1]} ${f[2]}\n")
//        }
//
//        // lines (wires)
//        for (l in lines) {
//            sb.append("l ${l[0]} ${l[1]}\n")
//        }
//
//        return sb.toString()
//    }
//
//        fun addMesh(name: String, verts: List<Vec3>, indices: List<Int>, material: String) {
//
//            materials.add(material)
//
//            val baseIndex = vertices.size + 1
//            verticesObj.addAll(verts)
//
//            facesObj.add("o $name")
//            facesObj.add("usemtl $material")
//
//            for (i in 0 until indices.size step 3) {
//
//                facesObj.add(
//                    "f ${baseIndex + indices[i]} " +
//                            "${baseIndex + indices[i + 1]} " +
//                            "${baseIndex + indices[i + 2]}"
//                )
//            }
//        }
//
//        fun buildOBJ(): String {
//
//            val sb = StringBuilder()
//
//            sb.append("mtllib scene.mtl\n\n")
//
//            verticesObj.forEach {
//                sb.append("v ${it.x} ${it.y} ${it.z}\n")
//            }
//
//            sb.append("\n")
//            facesObj.forEach { sb.append(it).append("\n") }
//
//            return sb.toString()
//        }
//
//        fun buildMTL(): String {
//
//            val sb = StringBuilder()
//
//            for (m in materials) {
//                sb.append("""
//                newmtl $m
//                Ka 0.2 0.2 0.2
//                Kd 0.6 0.6 0.6
//                Ks 0.1 0.1 0.1
//                d 1.0
//
//            """.trimIndent())
//            }
//
//            return sb.toString()
//        }
//}
//
//data class ExportMesh(
//    val name: String,
//    val vertices: List<Vec3>,
//    val indices: List<Int>,
//    val material: String
//)
//
//data class ExportNode(
//    val name: String,
//    val meshes: List<ExportMesh>,
//    val children: List<ExportNode> = emptyList()
//)