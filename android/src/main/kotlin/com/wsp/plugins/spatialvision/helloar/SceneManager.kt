
package com.wsp.plugins.spatialvision.helloar

class SceneManager(private val cylinder: Cylinder) {

    val poles = mutableListOf<Pole>()
    val wires = mutableListOf<Wire>()

    val poleWire = PoleWire()

    var pendingPoleBase: AnchorPoint? = null
    var selectedArm: CrossArm? = null

    var currentConfig = PhaseConfig.VERTICAL

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
            if(poles.size % 2 == 0 && poles[poles.size - 1].crossArms.isNotEmpty() && poles[poles.size - 2].crossArms.isNotEmpty()){
                createWire(poles[poles.size-2].crossArms[0], poles[poles.size-1].crossArms[0])
            }
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
    // WIRE CREATION (FIXED - using insulators)
    // =========================================================
    private fun createWire(a: CrossArm, b: CrossArm) {

        // Get the pole for each cross arm to know the direction
        val poleA = findPoleForCrossArm(a)
        val poleB = findPoleForCrossArm(b)

        if (poleA == null || poleB == null) return

        // Get pole direction (from base to top)
        val dirA = getPoleDirection(poleA)
        val dirB = getPoleDirection(poleB)

        val pointsA = getInsulatorTipPoints(a, dirA)
        val pointsB = getInsulatorTipPoints(b, dirB)

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

    private fun findPoleForCrossArm(arm: CrossArm): Pole? {
        for (pole in poles) {
            if (pole.crossArms.contains(arm)) {
                return pole
            }
        }
        return null
    }

    fun getPoleDirection(pole: Pole): FloatArray {
        return floatArrayOf(
            pole.top.position.x - pole.base.position.x,
            pole.top.position.y - pole.base.position.y,
            pole.top.position.z - pole.base.position.z
        ).also { dir ->
            val len = kotlin.math.sqrt(dir[0]*dir[0] + dir[1]*dir[1] + dir[2]*dir[2])
            if (len > 0) {
                dir[0] /= len
                dir[1] /= len
                dir[2] /= len
            }
        }
    }

    /**
     * Get points where insulators ATTACH to cross arm (base of insulator)
     * Used for drawing insulators
     */
    fun getInsulatorBasePoints(arm: CrossArm): List<Vec3> {
        return getAttachmentPointsOnArm(arm)
    }
    /**
     * Get points at the TIP of insulators (where wires connect)
     */
    fun getInsulatorTipPoints(arm: CrossArm, poleDir: FloatArray): List<Vec3> {
        val basePoints = getInsulatorBasePoints(arm)
        val insulatorHeight = 0.08f

        return basePoints.mapIndexed { index, point ->
            val direction = getInsulatorDirection(arm, poleDir, point, index)
            Vec3(
                point.x + direction[0] * insulatorHeight,
                point.y + direction[1] * insulatorHeight,
                point.z + direction[2] * insulatorHeight
            )
        }
    }

    /**
     * Get direction for insulator based on configuration
     */
    fun getInsulatorDirection(arm: CrossArm, poleDir: FloatArray, basePoint: Vec3, index: Int): FloatArray {
        return when (currentConfig) {
            PhaseConfig.HORIZONTAL -> {
                // Horizontal: Insulators point in wire direction (along cross arm)
                val armDir = floatArrayOf(
                    arm.localEnd.x - arm.localStart.x,
                    arm.localEnd.y - arm.localStart.y,
                    arm.localEnd.z - arm.localStart.z
                ).let { dir ->
                    val len = kotlin.math.sqrt(dir[0]*dir[0] + dir[1]*dir[1] + dir[2]*dir[2])
                    if (len > 0) {
                        dir[0] /= len
                        dir[1] /= len
                        dir[2] /= len
                    }
                    dir
                }

                // Point in the direction of the wire (along cross arm)
                normalize(armDir)
            }

            PhaseConfig.VERTICAL -> {
                // Vertical: Insulators point UP (along pole) with slight outward tilt
                val armCenter = Vec3(
                    (arm.localStart.x + arm.localEnd.x) / 2f,
                    (arm.localStart.y + arm.localEnd.y) / 2f,
                    (arm.localStart.z + arm.localEnd.z) / 2f
                )

                val outwardDir = floatArrayOf(
                    basePoint.x - armCenter.x,
                    basePoint.y - armCenter.y,
                    basePoint.z - armCenter.z
                ).let { dir ->
                    val len = kotlin.math.sqrt(dir[0]*dir[0] + dir[1]*dir[1] + dir[2]*dir[2])
                    if (len > 0) {
                        dir[0] /= len
                        dir[1] /= len
                        dir[2] /= len
                    }
                    dir
                }

                // Up direction with slight outward tilt
                val tilt = 0.15f
                floatArrayOf(
                    poleDir[0] + outwardDir[0] * tilt,
                    poleDir[1] + outwardDir[1] * tilt,
                    poleDir[2] + outwardDir[2] * tilt
                ).let { dir ->
                    normalize(dir)
                }
            }

            PhaseConfig.DELTA -> {
                // Delta: Ends point DOWN, center points UP
                val armLength = MathUtils.distance(arm.localStart, arm.localEnd)
                val armCenter = Vec3(
                    (arm.localStart.x + arm.localEnd.x) / 2f,
                    (arm.localStart.y + arm.localEnd.y) / 2f,
                    (arm.localStart.z + arm.localEnd.z) / 2f
                )

                // Check if this is center point or end point
                val distToCenter = MathUtils.distance(basePoint, armCenter)
                val isCenter = distToCenter < armLength * 0.25f

                if (isCenter) {
                    // Center insulator points UP
                    floatArrayOf(0f, 1f, 0f)
                } else {
                    floatArrayOf(0f, -1f, 0f)
                }
            }
        }
    }

//    fun getAttachmentPointsOnArm(arm: CrossArm): List<Vec3> {
//
//        val start = arm.localStart
//        val end = arm.localEnd
//
//        val dir = MathUtils.normalize(
//            floatArrayOf(
//                end.x - start.x,
//                end.y - start.y,
//                end.z - start.z
//            )
//        )
//
//        val length = MathUtils.distance(
//            start,
//            end
//        )
//
//        val mid = Vec3(
//            (start.x + end.x) * 0.5f,
//            (start.y + end.y) * 0.5f,
//            (start.z + end.z) * 0.5f
//        )
//
//        val up = floatArrayOf(0f, 1f, 0f)
//
//        return when (currentConfig) {
//
//            // =========================
//            // HORIZONTAL (existing)
//            // =========================
//            PhaseConfig.HORIZONTAL -> {
////                val spacing = length / 4f
////
////                (0..2).map { i ->
////                    val p = pointOnLine(start, dir, spacing * (i + 1))
////
////                    if (i == 1) p.y += 0.02f // optional lift
////
////                    p
////                }
//                val spacing = length / 4f  // Positions at 1/4, 2/4, 3/4
//
//                listOf(
//                    pointOnLine(start, dir, spacing * 1f),      // 1/4 position
//                    pointOnLine(start, dir, spacing * 2f),      // 2/4 position (center)
//                    pointOnLine(start, dir, spacing * 3f)       // 3/4 position
//                )
//            }
//
//            // =========================
//            // VERTICAL STACK
//            // =========================
//            PhaseConfig.VERTICAL -> {
//
//                val verticalSpacing = length * 0.25f
//
//                listOf(
//                    Vec3(mid.x, mid.y + verticalSpacing, mid.z), // top
//                    Vec3(mid.x, mid.y, mid.z),                  // middle
//                    Vec3(mid.x, mid.y - verticalSpacing, mid.z) // bottom
//                )
//            }
//
//            // =========================
//            // DELTA (triangle)
//            // =========================
//            PhaseConfig.DELTA -> {
//
//                val size = length * 0.25f
//
//                // get perpendicular direction
//                val right = MathUtils.normalize(
//                    MathUtils.cross(dir, up)
//                )
//
//                listOf(
//                    // top
//                    Vec3(mid.x, mid.y + size, mid.z),
//
//                    // bottom left
//                    Vec3(
//                        mid.x - right[0] * size * 1.5f,
//                        mid.y - size * 0.8f,
//                        mid.z - right[2] * size * 1.5f
//                    ),
//
//                    // bottom right
//                    Vec3(
//                        mid.x + right[0] * size * 1.5f,
//                        mid.y - size * 0.8f,
//                        mid.z + right[2] * size * 1.5f
//                    )
//                )
//            }
//        }
//    }

    fun getAttachmentPointsOnArm(arm: CrossArm): List<Vec3> {
        val start = arm.localStart
        val end = arm.localEnd

        val dir = MathUtils.normalize(
            floatArrayOf(
                end.x - start.x,
                end.y - start.y,
                end.z - start.z
            )
        )

        val length = MathUtils.distance(start, end)
        val mid = Vec3(
            (start.x + end.x) * 0.5f,
            (start.y + end.y) * 0.5f,
            (start.z + end.z) * 0.5f
        )
        val up = floatArrayOf(0f, 1f, 0f)
        return when (currentConfig) {
            // =========================
            // HORIZONTAL - 3 insulators along the arm (horizontal)
            // Direction: along the wire (same as cross arm)
            // =========================
            PhaseConfig.HORIZONTAL -> {
                val spacing = length / 4f  // Positions at 1/4, 2/4, 3/4

                listOf(
                    pointOnLine(start, dir, spacing * 1f),      // 1/4 position
                    pointOnLine(start, dir, spacing * 2f),      // 2/4 position (center)
                    pointOnLine(start, dir, spacing * 3f)       // 3/4 position
                )
            }

            // =========================
            // VERTICAL - 3 insulators along the arm (horizontal spacing)
            // BUT each insulator points UPWARDS
            // =========================
            PhaseConfig.VERTICAL -> {
                val spacing = length / 4f  // Same horizontal spacing as HORIZONTAL

                listOf(
                    pointOnLine(start, dir, spacing * 1f),      // Left position
                    pointOnLine(start, dir, spacing * 2f),      // Center position
                    pointOnLine(start, dir, spacing * 3f)       // Right position
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
                        mid.x - right[0] * size * 1.5f,
                        mid.y - size * 0.8f,
                        mid.z - right[2] * size * 1.5f
                    ),

                    // bottom right
                    Vec3(
                        mid.x + right[0] * size * 1.5f,
                        mid.y - size * 0.8f,
                        mid.z + right[2] * size * 1.5f
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
        threshold: Float = 1.0f
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

    fun calculateInsulatorDirection(
        config: PhaseConfig,
        poleDir: FloatArray,
        armDir: FloatArray,
        arm: CrossArm,
        attachmentIndex: Int,
        attachmentPoint: Vec3
    ): FloatArray {

        return when (config) {
            PhaseConfig.HORIZONTAL -> {
                // Horizontal: Insulators point UP (along pole direction)
                // with slight outward tilt
                val outwardDir = getOutwardDirection(arm, attachmentPoint, armDir)
                val tilt = 0.15f // Slight outward tilt

                floatArrayOf(
                    poleDir[0] + outwardDir[0] * tilt,
                    poleDir[1] + outwardDir[1] * tilt,
                    poleDir[2] + outwardDir[2] * tilt
                ).let { dir ->
                    normalize(dir)
                }
            }

            PhaseConfig.VERTICAL -> {
                // Vertical: Insulators point OUTWARD from pole
                // For vertical stack, insulators should face away from pole center
                val poleCenter = getPoleCenter(arm)
                val outwardDir = floatArrayOf(
                    attachmentPoint.x - poleCenter.x,
                    attachmentPoint.y - poleCenter.y,
                    attachmentPoint.z - poleCenter.z
                ).let { dir ->
                    normalize(dir)
                }

                // Add slight upward tilt
                floatArrayOf(
                    outwardDir[0] + poleDir[0] * 0.1f,
                    outwardDir[1] + poleDir[1] * 0.1f,
                    outwardDir[2] + poleDir[2] * 0.1f
                ).let { dir ->
                    normalize(dir)
                }
            }

            PhaseConfig.DELTA -> {
                // Delta: Insulators point outward and slightly down
                val poleCenter = getPoleCenter(arm)
                val outwardDir = floatArrayOf(
                    attachmentPoint.x - poleCenter.x,
                    attachmentPoint.y - poleCenter.y,
                    attachmentPoint.z - poleCenter.z
                ).let { dir ->
                    normalize(dir)
                }

                // For bottom insulators in delta, point more outward
                val downwardTilt = if (attachmentIndex == 0) {
                    // Top insulator - slight downward
                    floatArrayOf(0f, -0.2f, 0f)
                } else {
                    // Bottom insulators - more outward and downward
                    floatArrayOf(0f, -0.3f, 0f)
                }

                floatArrayOf(
                    outwardDir[0] + downwardTilt[0],
                    outwardDir[1] + downwardTilt[1],
                    outwardDir[2] + downwardTilt[2]
                ).let { dir ->
                    normalize(dir)
                }
            }
        }
    }

    private fun getOutwardDirection(
        arm: CrossArm,
        attachmentPoint: Vec3,
        armDir: FloatArray
    ): FloatArray {
        val armCenter = Vec3(
            (arm.localStart.x + arm.localEnd.x) / 2f,
            (arm.localStart.y + arm.localEnd.y) / 2f,
            (arm.localStart.z + arm.localEnd.z) / 2f
        )

        return floatArrayOf(
            attachmentPoint.x - armCenter.x,
            attachmentPoint.y - armCenter.y,
            attachmentPoint.z - armCenter.z
        ).let { dir ->
            normalize(dir)
        }
    }

    private fun getPoleCenter(arm: CrossArm): Vec3 {
        // Calculate approximate pole center at the height of the cross arm
        return Vec3(
            (arm.localStart.x + arm.localEnd.x) / 2f,
            (arm.localStart.y + arm.localEnd.y) / 2f,
            (arm.localStart.z + arm.localEnd.z) / 2f
        )
    }

    private fun normalize(dir: FloatArray): FloatArray {
        val len = kotlin.math.sqrt(dir[0]*dir[0] + dir[1]*dir[1] + dir[2]*dir[2])
        return if (len > 0) {
            floatArrayOf(dir[0]/len, dir[1]/len, dir[2]/len)
        } else {
            floatArrayOf(0f, 1f, 0f) // Default to up
        }
    }
}