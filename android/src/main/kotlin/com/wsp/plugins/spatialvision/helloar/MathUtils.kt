package com.wsp.plugins.spatialvision.helloar


object MathUtils {
    fun getMidPoint(p1: Vec3, p2: Vec3): FloatArray {
        return floatArrayOf(
            (p1.x + p2.x) / 2f,
            (p1.y + p2.y) / 2f,
            (p1.z + p2.z) / 2f
        )
    }

    fun distancePointToSegment(p: FloatArray, a: FloatArray, b: FloatArray): Float {

        val ab = floatArrayOf(b[0]-a[0], b[1]-a[1], b[2]-a[2])
        val ap = floatArrayOf(p[0]-a[0], p[1]-a[1], p[2]-a[2])

        val abLen2 = ab[0]*ab[0] + ab[1]*ab[1] + ab[2]*ab[2]
        val dot = ap[0]*ab[0] + ap[1]*ab[1] + ap[2]*ab[2]

        val t = (dot / abLen2).coerceIn(0f, 1f)

        val closest = floatArrayOf(
            a[0] + ab[0]*t,
            a[1] + ab[1]*t,
            a[2] + ab[2]*t
        )

        val dx = p[0] - closest[0]
        val dy = p[1] - closest[1]
        val dz = p[2] - closest[2]

        return kotlin.math.sqrt(dx*dx + dy*dy + dz*dz)
    }



    fun distance(p1: Vec3, p2: Vec3): Float {
        val dx = p2.x - p1.x
        val dy = p2.y - p1.y
        val dz = p2.z - p1.z
        return Math.sqrt((dx * dx + dy * dy + dz * dz).toDouble()).toFloat()
    }

    fun normalize(v: FloatArray): FloatArray {
        val len = kotlin.math.sqrt(
            v[0] * v[0] +
                    v[1] * v[1] +
                    v[2] * v[2]
        )

        if (len == 0f) return floatArrayOf(0f, 0f, 0f)

        return floatArrayOf(
            v[0] / len,
            v[1] / len,
            v[2] / len
        )
    }

    fun cross(a: FloatArray, b: FloatArray): FloatArray {
        return floatArrayOf(
            a[1] * b[2] - a[2] * b[1],
            a[2] * b[0] - a[0] * b[2],
            a[0] * b[1] - a[1] * b[0]
        )
    }

    fun length(v: FloatArray): Float {
        return kotlin.math.sqrt(
            v[0] * v[0] +
                    v[1] * v[1] +
                    v[2] * v[2]
        )
    }

    fun dot(a: FloatArray, b: FloatArray): Float = a[0]*b[0] + a[1]*b[1] + a[2]*b[2]

}