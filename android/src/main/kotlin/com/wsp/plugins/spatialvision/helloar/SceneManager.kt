package com.wsp.plugins.spatialvision.helloar

class SceneManager(private val cylinder: Cylinder) {

    val poles = mutableListOf<Pole>()
    val wires = mutableListOf<Wire>()

    var pendingPoleBase: AnchorPoint? = null
    var selectedArm: CrossArm? = null

    fun handleTap(tap: TapResult) {

        val world = tap.world
        val anchor = tap.anchor

        val arm = findNearbyCrossArm(world)

        if (arm != null) {
            if (selectedArm == null) {
                selectedArm = arm
            } else {
                createWire(selectedArm!!, arm)
                selectedArm = null
            }
            return
        }

        val point = AnchorPoint(
            anchor = anchor,
            position = world
        )

        if (pendingPoleBase == null) {
            pendingPoleBase = point
        } else {
            poles.add(createPole(pendingPoleBase!!, point))
            pendingPoleBase = null
        }
    }

    private fun findNearbyCrossArm(
        tapWorld: FloatArray,
        threshold: Float = 0.15f
    ): CrossArm? {

        var closest: CrossArm? = null
        var minDist = Float.MAX_VALUE

        for (pole in poles) {
            for (arm in pole.crossArms) {

                val dist = distancePointToSegment(
                    tapWorld,
                    arm.start,
                    arm.end
                )

                if (dist < threshold && dist < minDist) {
                    minDist = dist
                    closest = arm
                }
            }
        }

        return closest
    }

    private fun createWire(a: CrossArm, b: CrossArm) {

        val start = getMidPoint(a.start, a.end)
        val end = getMidPoint(b.start, b.end)

        val length = distance(start, end)
        val sag = cylinder?.computeSag(length) ?: 0.1f

        val points = cylinder?.generateWire(start, end, sag) ?: return

        wires.add(Wire(a, b, points))
    }

    private fun createPole(base: AnchorPoint, top: AnchorPoint): Pole {

        val crossArms = cylinder?.computeCrossArms(
            base.position,
            top.position
        ) ?: emptyList()

        return Pole(base, top, crossArms.toMutableList())
    }


    private fun distancePointToSegment(p: FloatArray, a: FloatArray, b: FloatArray): Float {

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

    fun getMidPoint(p1: FloatArray, p2: FloatArray): FloatArray {
        return floatArrayOf(
            (p1[0] + p2[0]) / 2f,
            (p1[1] + p2[1]) / 2f,
            (p1[2] + p2[2]) / 2f
        )
    }
    private fun distance(p1: FloatArray, p2: FloatArray): Float {
        val dx = p1[0] - p2[0]
        val dy = p1[1] - p2[1]
        val dz = p1[2] - p2[2]
        return Math.sqrt((dx * dx + dy * dy + dz * dz).toDouble()).toFloat()
    }


}