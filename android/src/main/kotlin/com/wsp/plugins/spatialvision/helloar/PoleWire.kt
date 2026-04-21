package com.wsp.plugins.spatialvision.helloar

class PoleWire {

    fun computeSag(
        start: FloatArray,
        end: FloatArray,
        tension: Float = 1.0f
    ): Float {

        val dx = end[0] - start[0]
        val dy = end[1] - start[1]
        val dz = end[2] - start[2]

        val length = kotlin.math.sqrt(dx*dx + dy*dy + dz*dz)

        // vertical difference matters a LOT in real wires
        val verticalDrop = kotlin.math.abs(dy)

        // sag increases with span, decreases with tension
        val baseSag = length * 0.04f

        // 🔥 slope should REDUCE sag stability slightly (not amplify it wildly)
        val slopeFactor = 1f + (verticalDrop / (length + 0.0001f)) * 0.3f

        // 🔥 strong tension control (this is what you were missing)
        val tensionFactor = kotlin.math.max(0.2f, tension)

        // 🔥 final sag
        val sag = (baseSag * slopeFactor) / tensionFactor

        // 🔥 HARD CAP (VERY IMPORTANT)
        return kotlin.math.min(sag, length * 0.08f)
    }

    fun generateWire(
        start: FloatArray,
        end: FloatArray,
        sag: Float,
        segments: Int = 20
    ): List<FloatArray> {

        val points = mutableListOf<FloatArray>()

        val dir = floatArrayOf(
            end[0] - start[0],
            end[1] - start[1],
            end[2] - start[2]
        )

        val length = kotlin.math.sqrt(dir[0]*dir[0] + dir[1]*dir[1] + dir[2]*dir[2])

        // normalize direction
        for (i in 0..2) dir[i] /= length

        // gravity direction (world Y)
        val gravity = floatArrayOf(0f, -1f, 0f)
//        val gravity = floatArrayOf(
//            -cameraPose.yAxis[0],
//            -cameraPose.yAxis[1],
//            -cameraPose.yAxis[2]
//        )

        // perpendicular direction for sag plane
        val side = MathUtils.cross(dir, gravity)
        val sideLen = MathUtils.length(side)
        if (sideLen < 0.0001f) {
            // fallback if parallel
            side[0] = 1f; side[1] = 0f; side[2] = 0f
        } else {
            for (i in 0..2) side[i] /= sideLen
        }

        for (i in 0..segments) {

            val t = i / segments.toFloat()

            // linear interpolation
            val x = start[0] + dir[0] * length * t
            val y = start[1] + dir[1] * length * t
            val z = start[2] + dir[2] * length * t

            // parabolic sag (physically stable)
            val sagFactor = 4f * t * (1f - t)  // smoother than sin()

            val sagOffset = sag * sagFactor

            val final = floatArrayOf(
                x + side[0] * sagOffset,
                y - sagOffset,
                z + side[2] * sagOffset
            )

            points.add(final)
        }

        return points
    }

//    fun calculateWireLength(points: List<FloatArray>): Float {
//        var total = 0f
//
//        for (i in 0 until points.size - 1) {
//            total += MathUtils.distance(points[i], points[i + 1])
//        }
//
//        return total
//    }

}