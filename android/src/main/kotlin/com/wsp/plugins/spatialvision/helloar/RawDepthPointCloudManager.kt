package com.wsp.plugins.spatialvision.helloar

import android.media.Image
import android.opengl.Matrix
import com.google.ar.core.*
import com.google.ar.core.exceptions.NotYetAvailableException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import kotlin.math.*

class RawDepthPointCloudManager {

    companion object {
        private const val TAG = "RawDepthPointCloud"
        private const val FLOATS_PER_POINT = 4 // X, Y, Z, confidence
        private const val MIN_CONFIDENCE = 0.4f // Increased to 40% for better accuracy
        private const val MAX_DEPTH_METERS = 30.0f
        private const val MIN_DEPTH_METERS = 0.15f
        private const val MAX_POINTS_TO_RENDER = 50000

        // NEW: Enhanced stability parameters
        private const val TEMPORAL_CONSISTENCY_FRAMES = 5
        private const val MAX_POSITION_VARIANCE = 0.005f // 5mm max variance
        private const val MIN_VALID_NEIGHBORS = 5
        private const val HOLE_DETECTION_THRESHOLD = 0.6f // 60% valid neighbors required
    }

    // NEW: Temporal consistency tracking
    private data class TemporalPosition(
        val position: FloatArray,
        val timestamp: Long
    )

    private val positionHistory = mutableMapOf<String, MutableList<TemporalPosition>>()
    private val lastValidPoint = mutableMapOf<String, DepthPointResult>()

    /**
     * Enhanced: Convert raw depth image to 3D point cloud with better filtering
     */
    fun convertDepthImageToPointCloud(
        frame: Frame,
        cameraPose: Pose
    ): FloatBuffer? {
        return try {
            val depthImage = frame.acquireRawDepthImage16Bits()
            val confidenceImage = frame.acquireRawDepthConfidenceImage()
            val intrinsics = frame.camera.textureIntrinsics

            val modelMatrix = FloatArray(16)
            cameraPose.toMatrix(modelMatrix, 0)

            val pointBuffer = convertRawDepthTo3DPointsEnhanced(
                depthImage,
                confidenceImage,
                intrinsics,
                modelMatrix,
                frame.timestamp
            )

            depthImage.close()
            confidenceImage.close()

            pointBuffer
        } catch (e: NotYetAvailableException) {
            null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * NEW: Enhanced conversion with outlier removal and smoothing
     */
    private fun convertRawDepthTo3DPointsEnhanced(
        depthImage: Image,
        confidenceImage: Image,
        intrinsics: CameraIntrinsics,
        modelMatrix: FloatArray,
        timestamp: Long
    ): FloatBuffer {
        val depthPlane = depthImage.planes[0]
        val depthBuffer = convertToShortBuffer(depthPlane.buffer)

        val confidencePlane = confidenceImage.planes[0]
        val confidenceBuffer = convertToByteBuffer(confidencePlane.buffer)

        val intrinsicsDims = intrinsics.imageDimensions
        val depthWidth = depthImage.width
        val depthHeight = depthImage.height

        val fx = intrinsics.focalLength[0] * depthWidth / intrinsicsDims[0]
        val fy = intrinsics.focalLength[1] * depthHeight / intrinsicsDims[1]
        val cx = intrinsics.principalPoint[0] * depthWidth / intrinsicsDims[0]
        val cy = intrinsics.principalPoint[1] * depthHeight / intrinsicsDims[1]

        val totalPixels = depthWidth * depthHeight
        val step = ceil(sqrt(totalPixels.toDouble() / MAX_POINTS_TO_RENDER)).toInt()

        val maxPoints = (depthWidth / step) * (depthHeight / step)
        val pointBuffer = FloatBuffer.allocate(maxPoints * FLOATS_PER_POINT)

        val pointCamera = FloatArray(4)
        val pointWorld = FloatArray(4)

        // NEW: Collect points for outlier detection
        val rawPoints = mutableListOf<FloatArray>()

        for (y in 0 until depthHeight step step) {
            for (x in 0 until depthWidth step step) {
                val index = y * depthWidth + x

                val depthMM = depthBuffer.get(index).toInt() and 0xFFFF
                if (depthMM == 0) continue

                val depthMeters = depthMM / 1000.0f
                if (depthMeters < MIN_DEPTH_METERS || depthMeters > MAX_DEPTH_METERS) continue

                val confidenceByte = confidenceBuffer.get(index)
                val confidence = (confidenceByte.toInt() and 0xFF) / 255.0f
                if (confidence < MIN_CONFIDENCE) continue

                // Check for local consistency
                if (!isLocallyConsistent(x, y, depthWidth, depthHeight, depthBuffer, confidenceBuffer)) {
                    continue
                }

                pointCamera[0] = depthMeters * (x - cx) / fx
                pointCamera[1] = depthMeters * (cy - y) / fy
                pointCamera[2] = -depthMeters
                pointCamera[3] = 1.0f

                Matrix.multiplyMV(pointWorld, 0, modelMatrix, 0, pointCamera, 0)

                rawPoints.add(floatArrayOf(pointWorld[0], pointWorld[1], pointWorld[2], confidence))
            }
        }

        // NEW: Remove outliers using statistical filtering
        val filteredPoints = removeStatisticalOutliers(rawPoints)

        // NEW: Apply temporal smoothing
        val smoothedPoints = applyTemporalSmoothing(filteredPoints, timestamp)

        // Store final points
        for (point in smoothedPoints) {
            pointBuffer.put(point[0]) // X
            pointBuffer.put(point[1]) // Y
            pointBuffer.put(point[2]) // Z
            pointBuffer.put(point[3]) // Confidence
        }

        pointBuffer.rewind()
        return pointBuffer
    }

    /**
     * NEW: Check if a point is locally consistent with neighbors
     */
    private fun isLocallyConsistent(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        depthBuffer: ShortBuffer,
        confidenceBuffer: ByteBuffer
    ): Boolean {
        var validNeighbors = 0
        var depthSum = 0.0

        for (dy in -1..1) {
            for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue

                val nx = x + dx
                val ny = y + dy

                if (nx in 0 until width && ny in 0 until height) {
                    val index = ny * width + nx
                    val depthMM = depthBuffer.get(index).toInt() and 0xFFFF
                    val confidence = (confidenceBuffer.get(index).toInt() and 0xFF) / 255.0f

                    if (depthMM != 0 && confidence >= MIN_CONFIDENCE * 0.8f) {
                        validNeighbors++
                        depthSum += depthMM / 1000.0
                    }
                }
            }
        }

        if (validNeighbors < MIN_VALID_NEIGHBORS) return false

        val avgDepth = depthSum / validNeighbors
        val currentDepth = (depthBuffer.get(y * width + x).toInt() and 0xFFFF) / 1000.0

        // Point should be within 20% of neighbor average
        return abs(currentDepth - avgDepth) / avgDepth < 0.2
    }

    /**
     * NEW: Remove statistical outliers using median absolute deviation
     */
    private fun removeStatisticalOutliers(points: List<FloatArray>): List<FloatArray> {
        if (points.size < 10) return points

        // Calculate median of each dimension
        val xs = points.map { it[0] }.sorted()
        val ys = points.map { it[1] }.sorted()
        val zs = points.map { it[2] }.sorted()

        val medianX = xs[xs.size / 2]
        val medianY = ys[ys.size / 2]
        val medianZ = zs[zs.size / 2]

        // Calculate median absolute deviations
        val deviationsX = xs.map { abs(it - medianX) }.sorted()
        val deviationsY = ys.map { abs(it - medianY) }.sorted()
        val deviationsZ = zs.map { abs(it - medianZ) }.sorted()

        val madX = deviationsX[deviationsX.size / 2]
        val madY = deviationsY[deviationsY.size / 2]
        val madZ = deviationsZ[deviationsZ.size / 2]

        // Keep points within 3 median absolute deviations
        return points.filter { point ->
            abs(point[0] - medianX) <= 3 * madX &&
                    abs(point[1] - medianY) <= 3 * madY &&
                    abs(point[2] - medianZ) <= 3 * madZ
        }
    }

    /**
     * NEW: Apply temporal smoothing to reduce jitter
     */
    private fun applyTemporalSmoothing(
        points: List<FloatArray>,
        timestamp: Long
    ): List<FloatArray> {
        // Simplified smoothing - average with previous frame's points
        // In production, you'd match points by position proximity
        return points.map { point ->
            val key = "${point[0].toInt()},${point[1].toInt()},${point[2].toInt()}"
            val history = positionHistory[key] ?: mutableListOf()

            history.add(TemporalPosition(point, timestamp))

            // Keep only recent history
            while (history.size > TEMPORAL_CONSISTENCY_FRAMES) {
                history.removeAt(0)
            }

            positionHistory[key] = history

            if (history.size > 1) {
                // Apply exponential moving average
                val alpha = 0.7f // Weight for new point
                val smoothed = FloatArray(4)

                for (i in 0..3) {
                    var weightedSum = 0.0
                    var totalWeight = 0.0

                    for (j in history.indices) {
//                        val weight = alpha.pow((history.size - 1 - j).toDouble())
                        val weight = Math.pow(alpha.toDouble(), (history.size - 1 - j).toDouble())
                        weightedSum += history[j].position[i] * weight
                        totalWeight += weight
                    }

                    smoothed[i] = (weightedSum / totalWeight).toFloat()
                }

                smoothed
            } else {
                point
            }
        }
    }

    /**
     * ENHANCED: Find best point with better stability checks
     */
    fun findBestPointAtScreenCenter(
        frame: Frame,
        screenCenterX: Float,
        screenCenterY: Float,
        cameraPose: Pose
    ): DepthPointResult? {
        try {
            val depthImage = frame.acquireRawDepthImage16Bits()
            val confidenceImage = frame.acquireRawDepthConfidenceImage()
            val intrinsics = frame.camera.textureIntrinsics

            val depthPoint = FloatArray(2)
            frame.transformCoordinates2d(
                Coordinates2d.VIEW,
                floatArrayOf(screenCenterX, screenCenterY),
                Coordinates2d.IMAGE_PIXELS,
                depthPoint
            )

            val x = depthPoint[0].toInt()
            val y = depthPoint[1].toInt()

            if (x in 0 until depthImage.width && y in 0 until depthImage.height) {
                val depthBuffer = convertToShortBuffer(depthImage.planes[0].buffer)
                val confidenceBuffer = convertToByteBuffer(confidenceImage.planes[0].buffer)

                // Sample multiple points around center for better accuracy
                val points = mutableListOf<Triple<Int, Int, Float>>()

                // Sample in a 5x5 grid around center
                for (dy in -2..2) {
                    for (dx in -2..2) {
                        val nx = x + dx
                        val ny = y + dy

                        if (nx in 0 until depthImage.width && ny in 0 until depthImage.height) {
                            val index = ny * depthImage.width + nx
                            val depthMM = depthBuffer.get(index).toInt() and 0xFFFF
                            val confidence = (confidenceBuffer.get(index).toInt() and 0xFF) / 255.0f

                            if (depthMM != 0 && confidence >= MIN_CONFIDENCE) {
                                val weight = 1.0f / (1.0f + sqrt((dx*dx + dy*dy).toFloat()) / 2.0f)
                                points.add(Triple(nx, ny, depthMM / 1000.0f * weight))
                            }
                        }
                    }
                }

                if (points.isNotEmpty()) {
                    // Weighted average depth
//                    val avgDepth = points.sumOf { it.third } / points.size
                    val avgDepth = points.map { it.third }.sum() / points.size

                    val confidence = points.map { (confidenceBuffer.get(it.second * depthImage.width + it.first).toInt() and 0xFF) / 255.0f }.average().toFloat()

                    if (avgDepth > 0 && confidence >= MIN_CONFIDENCE) {
                        val hasHole = detectHoleAtPointEnhanced(x, y, depthImage.width, depthImage.height, depthBuffer)

                        // Check temporal consistency
                        val key = "${x},${y}"
                        val temporalConsistent = isTemporallyConsistent(key, avgDepth, frame.timestamp)

                        if (temporalConsistent) {
                            val intrinsicsDims = intrinsics.imageDimensions
                            val fx = intrinsics.focalLength[0] * depthImage.width / intrinsicsDims[0]
                            val fy = intrinsics.focalLength[1] * depthImage.height / intrinsicsDims[1]
                            val cx = intrinsics.principalPoint[0] * depthImage.width / intrinsicsDims[0]
                            val cy = intrinsics.principalPoint[1] * depthImage.height / intrinsicsDims[1]

                            val pointCamera = floatArrayOf(
                                avgDepth * (x - cx) / fx,
                                avgDepth * (cy - y) / fy,
                                -avgDepth,
                                1f
                            )

                            val pointWorld = FloatArray(4)
                            val modelMatrix = FloatArray(16)
                            cameraPose.toMatrix(modelMatrix, 0)
                            Matrix.multiplyMV(pointWorld, 0, modelMatrix, 0, pointCamera, 0)

                            val result = DepthPointResult(
                                worldPosition = floatArrayOf(pointWorld[0], pointWorld[1], pointWorld[2]),
                                confidence = confidence,
                                hasHole = hasHole,
                                depthMeters = avgDepth
                            )

                            lastValidPoint[key] = result

                            depthImage.close()
                            confidenceImage.close()
                            return result
                        }
                    }
                }
            }

            depthImage.close()
            confidenceImage.close()

            // Fall back to last valid point if available and recent
            val lastPoint = lastValidPoint.values.lastOrNull()
            if (lastPoint != null && (System.currentTimeMillis() - lastPoint.hashCode().toLong()) < 100) {
                return lastPoint
            }
        } catch (e: Exception) {
            // Log error if needed
        }

        return null
    }

    /**
     * NEW: Enhanced hole detection with gradient analysis
     */
    private fun detectHoleAtPointEnhanced(
        centerX: Int,
        centerY: Int,
        width: Int,
        height: Int,
        depthBuffer: ShortBuffer
    ): Boolean {
        var validNeighbors = 0
        var totalNeighbors = 0
        var depthVariance = 0.0

        val depths = mutableListOf<Double>()

        // Check 5x5 neighborhood
        for (dy in -2..2) {
            for (dx in -2..2) {
                if (dx == 0 && dy == 0) continue

                val nx = centerX + dx
                val ny = centerY + dy

                if (nx in 0 until width && ny in 0 until height) {
                    totalNeighbors++
                    val depthMM = depthBuffer.get(ny * width + nx).toInt() and 0xFFFF
                    if (depthMM != 0) {
                        validNeighbors++
                        val depthMeters = depthMM / 1000.0
                        depths.add(depthMeters)
                    }
                }
            }
        }

        if (totalNeighbors == 0) return true

        val validRatio = validNeighbors.toFloat() / totalNeighbors
        if (validRatio < HOLE_DETECTION_THRESHOLD) return true

        // Check depth variance - high variance indicates edge/hole boundary
        if (depths.isNotEmpty()) {
            val mean = depths.average()
            val variance = depths.map { (it - mean).pow(2) }.average()
            if (variance > 0.05) return true // High variance means depth discontinuity
        }

        return false
    }

    /**
     * NEW: Check temporal consistency of depth value
     */
    private val depthHistory = mutableMapOf<String, MutableList<Pair<Long, Float>>>()

    private fun isTemporallyConsistent(key: String, depth: Float, timestamp: Long): Boolean {
        val history = depthHistory.getOrPut(key) { mutableListOf() }

        history.add(Pair(timestamp, depth))

        // Keep only last 5 frames
        while (history.size > TEMPORAL_CONSISTENCY_FRAMES) {
            history.removeAt(0)
        }

        if (history.size < 3) return true

        // Calculate variance
        val depths = history.map { it.second }
        val mean = depths.average()
        val variance = depths.map { (it - mean).pow(2) }.average()

        return variance <= MAX_POSITION_VARIANCE
    }

    /**
     * ENHANCED: Create anchor with weighted averaging and boundary checking
     */
    fun createAnchorFromPointCloud(
        frame: Frame,
        screenX: Float,
        screenY: Float,
        session: Session
    ): Anchor? {
        val points = getPointsInRegionEnhanced(frame, screenX, screenY, radiusPx = 30)
        if (points.size < 3) return null

        // Calculate weighted average with confidence-based weighting
        var totalWeight = 0f
        val avgPosition = FloatArray(3)

        // Use inverse distance weighting
        for (point in points) {
            val dx = point.worldPosition[0] - points.first().worldPosition[0]
            val dy = point.worldPosition[1] - points.first().worldPosition[1]
            val dz = point.worldPosition[2] - points.first().worldPosition[2]
            val distance = sqrt(dx * dx + dy * dy + dz * dz)

            // Weight by confidence and inverse distance
            val weight = point.confidence / (distance + 0.01f)

            avgPosition[0] += point.worldPosition[0] * weight
            avgPosition[1] += point.worldPosition[1] * weight
            avgPosition[2] += point.worldPosition[2] * weight
            totalWeight += weight
        }

        if (totalWeight > 0) {
            avgPosition[0] /= totalWeight
            avgPosition[1] /= totalWeight
            avgPosition[2] /= totalWeight

            // Check if anchor would be placed on valid surface
            if (isValidSurfacePoint(points, avgPosition)) {
                return session.createAnchor(Pose(avgPosition, floatArrayOf(0f, 0f, 0f, 1f)))
            }
        }

        return null
    }

    /**
     * NEW: Validate that point lies on a consistent surface
     */
    private fun isValidSurfacePoint(points: List<DepthPointResult>, center: FloatArray): Boolean {
        if (points.size < 3) return false

        // Calculate surface normal
        val v1 = floatArrayOf(
            points[1].worldPosition[0] - points[0].worldPosition[0],
            points[1].worldPosition[1] - points[0].worldPosition[1],
            points[1].worldPosition[2] - points[0].worldPosition[2]
        )

        val v2 = floatArrayOf(
            points[2].worldPosition[0] - points[0].worldPosition[0],
            points[2].worldPosition[1] - points[0].worldPosition[1],
            points[2].worldPosition[2] - points[0].worldPosition[2]
        )

        val normal = floatArrayOf(
            v1[1] * v2[2] - v1[2] * v2[1],
            v1[2] * v2[0] - v1[0] * v2[2],
            v1[0] * v2[1] - v1[1] * v2[0]
        )

        // Normalize
        val length = sqrt(normal[0] * normal[0] + normal[1] * normal[1] + normal[2] * normal[2])
        if (length > 0) {
            normal[0] /= length
            normal[1] /= length
            normal[2] /= length
        }

        // Check if surface is reasonably horizontal or vertical
        val dotWithUp = abs(normal[1]) // Y component
        return dotWithUp > 0.3f || dotWithUp < 0.7f
    }

    /**
     * ENHANCED: Get points with better sampling strategy
     */
    private fun getPointsInRegionEnhanced(
        frame: Frame,
        screenX: Float,
        screenY: Float,
        radiusPx: Int
    ): List<DepthPointResult> {
        val points = mutableListOf<DepthPointResult>()

        try {
            val depthImage = frame.acquireRawDepthImage16Bits()
            val confidenceImage = frame.acquireRawDepthConfidenceImage()
            val intrinsics = frame.camera.textureIntrinsics

            val centerDepthCoord = FloatArray(2)
            frame.transformCoordinates2d(
                Coordinates2d.VIEW,
                floatArrayOf(screenX, screenY),
                Coordinates2d.IMAGE_PIXELS,
                centerDepthCoord
            )

            val centerX = centerDepthCoord[0].toInt()
            val centerY = centerDepthCoord[1].toInt()

            val depthBuffer = convertToShortBuffer(depthImage.planes[0].buffer)
            val confidenceBuffer = convertToByteBuffer(confidenceImage.planes[0].buffer)

            val intrinsicsDims = intrinsics.imageDimensions
            val fx = intrinsics.focalLength[0] * depthImage.width / intrinsicsDims[0]
            val fy = intrinsics.focalLength[1] * depthImage.height / intrinsicsDims[1]
            val cx = intrinsics.principalPoint[0] * depthImage.width / intrinsicsDims[0]
            val cy = intrinsics.principalPoint[1] * depthImage.height / intrinsicsDims[1]

            val modelMatrix = FloatArray(16)
            frame.camera.pose.toMatrix(modelMatrix, 0)

            // Adaptive sampling based on radius
            val step = max(1, radiusPx / 15)

            for (dy in -radiusPx..radiusPx step step) {
                for (dx in -radiusPx..radiusPx step step) {
                    val x = centerX + dx
                    val y = centerY + dy

                    if (x in 0 until depthImage.width && y in 0 until depthImage.height) {
                        val index = y * depthImage.width + x
                        val depthMM = depthBuffer.get(index).toInt() and 0xFFFF
                        val confidence = (confidenceBuffer.get(index).toInt() and 0xFF) / 255.0f

                        if (depthMM != 0 && confidence >= MIN_CONFIDENCE) {
                            val depthMeters = depthMM / 1000.0f

                            val pointCamera = floatArrayOf(
                                depthMeters * (x - cx) / fx,
                                depthMeters * (cy - y) / fy,
                                -depthMeters,
                                1f
                            )

                            val pointWorld = FloatArray(4)
                            Matrix.multiplyMV(pointWorld, 0, modelMatrix, 0, pointCamera, 0)

                            // Only add if point is within reasonable range
                            val distance = sqrt(
                                pointWorld[0] * pointWorld[0] +
                                        pointWorld[1] * pointWorld[1] +
                                        pointWorld[2] * pointWorld[2]
                            )

                            if (distance <= MAX_DEPTH_METERS && distance >= MIN_DEPTH_METERS) {
                                points.add(
                                    DepthPointResult(
                                        worldPosition = floatArrayOf(pointWorld[0], pointWorld[1], pointWorld[2]),
                                        confidence = confidence,
                                        hasHole = false,
                                        depthMeters = depthMeters
                                    )
                                )
                            }
                        }
                    }
                }
            }

            depthImage.close()
            confidenceImage.close()
        } catch (e: Exception) {
            // Handle error
        }

        return points
    }

    private fun convertToShortBuffer(buffer: ByteBuffer): ShortBuffer {
        val byteBuffer = ByteBuffer.allocate(buffer.capacity())
        byteBuffer.order(ByteOrder.LITTLE_ENDIAN)
        while (buffer.hasRemaining()) {
            byteBuffer.put(buffer.get())
        }
        byteBuffer.rewind()
        return byteBuffer.asShortBuffer()
    }

    private fun convertToByteBuffer(buffer: ByteBuffer): ByteBuffer {
        val byteBuffer = ByteBuffer.allocate(buffer.capacity())
        byteBuffer.order(ByteOrder.LITTLE_ENDIAN)
        while (buffer.hasRemaining()) {
            byteBuffer.put(buffer.get())
        }
        byteBuffer.rewind()
        return byteBuffer
    }
}

data class DepthPointResult(
    val worldPosition: FloatArray,
    val confidence: Float,
    val hasHole: Boolean,
    val depthMeters: Float
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as DepthPointResult

        if (!worldPosition.contentEquals(other.worldPosition)) return false
        if (confidence != other.confidence) return false
        if (hasHole != other.hasHole) return false
        if (depthMeters != other.depthMeters) return false

        return true
    }

    override fun hashCode(): Int {
        var result = worldPosition.contentHashCode()
        result = 31 * result + confidence.hashCode()
        result = 31 * result + hasHole.hashCode()
        result = 31 * result + depthMeters.hashCode()
        return result
    }
}