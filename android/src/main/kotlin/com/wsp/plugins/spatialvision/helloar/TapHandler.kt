package com.wsp.plugins.spatialvision.helloar

import android.view.MotionEvent
import com.google.ar.core.Anchor
import com.google.ar.core.Camera
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import java.nio.ByteOrder

class TapHandler(
    private val sceneManager: SceneManager
) {

    fun onTap(session: Session, frame: Frame, camera: Camera, tap: MotionEvent) {

        val anchor = getStableDepthAnchor(session, frame, camera, tap)
            ?: frame.hitTest(tap.x, tap.y)
                .firstOrNull()?.createAnchor()
            ?: return

        val pose = anchor.pose
        val world = Vec3(pose.tx(), pose.ty(), pose.tz())

        sceneManager.handleTap(TapResult(anchor, world))
    }

    fun getStableDepthAnchor(
        session: Session,
        frame: Frame,
        camera: Camera,
        tap: MotionEvent
    ): Anchor? {

        if (camera.trackingState != TrackingState.TRACKING) return null

        try {
            frame.acquireRawDepthImage16Bits().use { depthImage ->
                frame.acquireRawDepthConfidenceImage().use { confidenceImage ->

                    // ✅ Ensure fresh depth (VERY IMPORTANT)
                    val isFresh = frame.timestamp == depthImage.timestamp
                    if (!isFresh) return null

                    val width = depthImage.width
                    val height = depthImage.height

                    val depthBuffer =
                        depthImage.planes[0].buffer.order(ByteOrder.nativeOrder())
                    val confBuffer = confidenceImage.planes[0].buffer

                    // ✅ Convert tap → depth pixel coordinates
                    val coords = FloatArray(2)
                    frame.transformCoordinates2d(
                        Coordinates2d.VIEW,
                        floatArrayOf(tap.x, tap.y),
                        Coordinates2d.IMAGE_PIXELS,
                        coords
                    )

                    val cx = coords[0].toInt()
                    val cy = coords[1].toInt()

                    if (cx !in 0 until width || cy !in 0 until height) return null

                    // ✅ Multi-sample (critical for stability)
                    val samples = mutableListOf<FloatArray>()

                    val offsets = listOf(
                        0 to 0,
                        -2 to 0, 2 to 0,
                        0 to -2, 0 to 2
                    )

                    val intrinsics = camera.textureIntrinsics
                    val fx = intrinsics.focalLength[0]
                    val fy = intrinsics.focalLength[1]
                    val px = intrinsics.principalPoint[0]
                    val py = intrinsics.principalPoint[1]

                    for ((ox, oy) in offsets) {
                        val x = cx + ox
                        val y = cy + oy

                        if (x !in 0 until width || y !in 0 until height) continue

                        val index = x + y * width

                        val depthMm =
                            depthBuffer.getShort(index * 2).toInt() and 0xFFFF
                        val confidence = confBuffer.get(index).toInt() and 0xFF

                        // ✅ Strong filtering
                        if (depthMm < 300 || depthMm > 4000) continue
                        if (confidence < 80) continue

                        val z = depthMm / 1000f

                        // ✅ Correct projection (CRITICAL FIX)
                        val X = (x - px) / fx * z
                        val Y = (y - py) / fy * z

                        val cameraPoint = floatArrayOf(X, Y, z)
                        val worldPoint = FloatArray(3)

                        camera.pose.transformPoint(cameraPoint, 0, worldPoint, 0)

                        samples.add(worldPoint)
                    }

                    if (samples.size < 3) return null

                    // ✅ Average = stable anchor
                    val avg = FloatArray(3)

                    for (p in samples) {
                        avg[0] += p[0]
                        avg[1] += p[1]
                        avg[2] += p[2]
                    }

                    val size = samples.size.toFloat()

                    avg[0] = avg[0] / size
                    avg[1] = avg[1] / size
                    avg[2] = avg[2] / size

                    return session.createAnchor(
                        Pose(avg, floatArrayOf(0f, 0f, 0f, 1f))
                    )
                }
            }
        } catch (e: Exception) {
            return null
        }
    }
}

data class TapResult(
    val anchor: Anchor,
    val world: Vec3
)