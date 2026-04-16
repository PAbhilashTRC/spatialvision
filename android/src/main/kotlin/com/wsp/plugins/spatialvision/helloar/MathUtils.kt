package com.wsp.plugins.spatialvision.helloar


object MathUtils {
    fun getMidPoint(p1: FloatArray, p2: FloatArray): FloatArray {
        return floatArrayOf(
            (p1[0] + p2[0]) / 2f,
            (p1[1] + p2[1]) / 2f,
            (p1[2] + p2[2]) / 2f
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



    fun distance(p1: FloatArray, p2: FloatArray): Float {
        val dx = p1[0] - p2[0]
        val dy = p1[1] - p2[1]
        val dz = p1[2] - p2[2]
        return Math.sqrt((dx * dx + dy * dy + dz * dz).toDouble()).toFloat()
    }

}