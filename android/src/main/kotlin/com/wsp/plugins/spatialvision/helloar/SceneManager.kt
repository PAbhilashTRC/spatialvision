package com.wsp.plugins.spatialvision.helloar

class SceneManager(private val cylinder: Cylinder) {

    val poles = mutableListOf<Pole>()
    val wires = mutableListOf<Wire>()

    val poleWire = PoleWire()

    var pendingPoleBase: AnchorPoint? = null
    var selectedArm: CrossArm? = null

    var currentConfig = PhaseConfig.HORIZONTAL
    // =========================================================
    // TAP HANDLING
    // =========================================================

    fun handleTap(tap: TapResult) {

        val world = tap.world
        val anchor = tap.anchor

        val arm = findNearbyCrossArm(floatArrayOf(world.x, world.y, world.z))

        if (arm != null) {

            if (selectedArm == null) {
                selectedArm = arm
            } else {
                createWire(selectedArm!!, arm)
                selectedArm = null
            }
            return
        }

        val point = AnchorPoint(anchor, world)

        if (pendingPoleBase == null) {
            pendingPoleBase = point
        } else {
            poles.add(createPole(pendingPoleBase!!, point))
            pendingPoleBase = null
        }
    }

    // =========================================================
    // POLE CREATION
    // =========================================================

    private fun createPole(base: AnchorPoint, top: AnchorPoint): Pole {

        val crossArms = cylinder.computeCrossArms(
            base.position,
            top.position
        ).map {
            CrossArm(
                localStart = it.localStart,
                localEnd = it.localEnd
            )
        }

        return Pole(base, top, crossArms.toMutableList())
    }

    // =========================================================
    // WIRE CREATION (FIXED)
    // =========================================================
    private fun createWire(a: CrossArm, b: CrossArm) {

        val pointsA = getAttachmentPoints(a)
        val pointsB = getAttachmentPoints(b)

        for (i in 0..2) {

            val start = pointsA[i]
            val end = pointsB[i]

            val sag = poleWire.computeSag(
                floatArrayOf(start.x, start.y, start.z),
                floatArrayOf(end.x, end.y, end.z),
                0.1f
            )

            val pts = poleWire.generateWire(
                floatArrayOf(start.x, start.y, start.z),
                floatArrayOf(end.x, end.y, end.z),
                sag
            )

            wires.add(Wire(start, end, pts.map { Vec3(it[0], it[1], it[2]) }))
        }
    }

    fun getAttachmentPoints(arm: CrossArm): List<Vec3> {

        val start = arm.localStart
        val end = arm.localEnd

        val dir = MathUtils.normalize(
            floatArrayOf(
                end.x - start.x,
                end.y - start.y,
                end.z - start.z
            )
        )

        val length = MathUtils.distance(
            start,
            end
        )

        val mid = Vec3(
            (start.x + end.x) * 0.5f,
            (start.y + end.y) * 0.5f,
            (start.z + end.z) * 0.5f
        )

        val up = floatArrayOf(0f, 1f, 0f)

        return when (currentConfig) {

            // =========================
            // HORIZONTAL (existing)
            // =========================
            PhaseConfig.HORIZONTAL -> {
                val spacing = length / 4f

                (0..2).map { i ->
                    val p = pointOnLine(start, dir, spacing * (i + 1))

                    if (i == 1) p.y += 0.02f // optional lift

                    p
                }
            }

            // =========================
            // VERTICAL STACK
            // =========================
            PhaseConfig.VERTICAL -> {

                val verticalSpacing = length * 0.25f

                listOf(
                    Vec3(mid.x, mid.y + verticalSpacing, mid.z), // top
                    Vec3(mid.x, mid.y, mid.z),                  // middle
                    Vec3(mid.x, mid.y - verticalSpacing, mid.z) // bottom
                )
            }

            // =========================
            // DELTA (triangle)
            // =========================
            PhaseConfig.DELTA -> {

                val size = length * 0.25f

                // get perpendicular direction
                val right = MathUtils.normalize(
                    MathUtils.cross(dir, up)
                )

                listOf(
                    // top
                    Vec3(mid.x, mid.y + size, mid.z),

                    // bottom left
                    Vec3(
                        mid.x - right[0] * size,
                        mid.y - size * 0.5f,
                        mid.z - right[2] * size
                    ),

                    // bottom right
                    Vec3(
                        mid.x + right[0] * size,
                        mid.y - size * 0.5f,
                        mid.z + right[2] * size
                    )
                )
            }
        }
    }

    fun pointOnLine(start: Vec3, dir: FloatArray, dist: Float): Vec3 {
        return Vec3(
            start.x + dir[0] * dist,
            start.y + dir[1] * dist,
            start.z + dir[2] * dist
        )
    }

    // =========================================================
    // CROSS ARM PICKING (FIXED ALIGNMENT)
    // =========================================================

    private fun findNearbyCrossArm(
        tapWorld: FloatArray,
        threshold: Float = 0.7f
    ): CrossArm? {

        var closest: CrossArm? = null
        var minDist = Float.MAX_VALUE

        for (pole in poles) {
            for (arm in pole.crossArms) {

                val dist = distancePointToSegment(
                    tapWorld,
                    floatArrayOf(arm.localStart.x, arm.localStart.y, arm.localStart.z),
                    floatArrayOf(arm.localEnd.x, arm.localEnd.y, arm.localEnd.z)
                )

                if (dist < threshold && dist < minDist) {
                    minDist = dist
                    closest = arm
                }
            }
        }

        return closest
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
}