package com.wsp.plugins.spatialvision.helloar
//
//import android.opengl.GLES30
//import android.opengl.GLException
//import android.opengl.Matrix
//import android.util.Log
//import androidx.lifecycle.DefaultLifecycleObserver
//import androidx.lifecycle.LifecycleOwner
//import com.google.ar.core.Anchor
//import com.google.ar.core.Camera
//import com.google.ar.core.DepthPoint
//import com.google.ar.core.Frame
//import com.google.ar.core.InstantPlacementPoint
//import com.google.ar.core.LightEstimate
//import com.google.ar.core.Plane
//import com.google.ar.core.Point
//import com.google.ar.core.Session
//import com.google.ar.core.Trackable
//import com.google.ar.core.TrackingFailureReason
//import com.google.ar.core.TrackingState
//import com.wsp.plugins.spatialvision.common.helpers.DisplayRotationHelper
//import com.wsp.plugins.spatialvision.common.helpers.TrackingStateHelper
//import com.wsp.plugins.spatialvision.common.samplerender.Framebuffer
//import com.wsp.plugins.spatialvision.common.samplerender.GLError
//import com.wsp.plugins.spatialvision.common.samplerender.Mesh
//import com.wsp.plugins.spatialvision.common.samplerender.SampleRender
//import com.wsp.plugins.spatialvision.common.samplerender.Shader
//import com.wsp.plugins.spatialvision.common.samplerender.Texture
//import com.wsp.plugins.spatialvision.common.samplerender.VertexBuffer
//import com.wsp.plugins.spatialvision.common.samplerender.arcore.BackgroundRenderer
//import com.wsp.plugins.spatialvision.common.samplerender.arcore.PlaneRenderer
//import com.wsp.plugins.spatialvision.common.samplerender.arcore.SpecularCubemapFilter
//import com.google.ar.core.exceptions.CameraNotAvailableException
//import com.google.ar.core.exceptions.NotYetAvailableException
//import com.wsp.plugins.spatialvision.GeoSpatial
//import com.wsp.plugins.spatialvision.helloar.HelloArRenderer.Companion.CUBEMAP_NUMBER_OF_IMPORTANCE_SAMPLES
//import com.wsp.plugins.spatialvision.helloar.HelloArRenderer.Companion.CUBEMAP_RESOLUTION
//import java.io.IOException
//import java.nio.ByteBuffer
//
//class BasicArRenderer(val activity: GeoSpatial) :
//    SampleRender.Renderer, DefaultLifecycleObserver {
//
//    companion object {
//        const val TAG = "HelloArRenderer"
//        const val Z_NEAR = 0.1f
//        const val Z_FAR = 100f
//    }
//
//    lateinit var render: SampleRender
//    lateinit var backgroundRenderer: BackgroundRenderer
//    lateinit var planeRenderer: PlaneRenderer
//
//    lateinit var cubemapFilter: SpecularCubemapFilter
//
//    lateinit var dfgTexture: Texture
//
//    lateinit var virtualObjectMesh: Mesh
//    lateinit var virtualObjectShader: Shader
//    lateinit var virtualObjectTexture: Texture
//
////    lateinit var virtualObjectAlbedoInstantPlacementTexture: Texture
//
//
//    private val anchors = mutableListOf<WrappedAnchor>()
//
//    private var hasSetTextureNames = false
//    private var lastFrameTimestamp = 0L
//
//    val session
//        get() = activity.arCoreSessionHelper.session
//
//    val displayRotationHelper = DisplayRotationHelper(activity.requireContext())
//
//    private val modelMatrix = FloatArray(16)
//    private val viewMatrix = FloatArray(16)
//    private val projectionMatrix = FloatArray(16)
//    private val modelViewProjectionMatrix = FloatArray(16)
//
//    override fun onResume(owner: LifecycleOwner) {
//        displayRotationHelper.onResume()
//        hasSetTextureNames = false
//    }
//
//    override fun onPause(owner: LifecycleOwner) {
//        displayRotationHelper.onPause()
//    }
//
//    override fun onSurfaceCreated(render: SampleRender) {
//        this.render = render
//        backgroundRenderer = BackgroundRenderer(render)
//        planeRenderer = PlaneRenderer(render)
//
//        cubemapFilter =
//            SpecularCubemapFilter(render, CUBEMAP_RESOLUTION, CUBEMAP_NUMBER_OF_IMPORTANCE_SAMPLES)
//        // Load environmental lighting values lookup table
//        dfgTexture =
//            Texture(
//                render,
//                Texture.Target.TEXTURE_2D,
//                Texture.WrapMode.CLAMP_TO_EDGE,
//                /*useMipmaps=*/ false
//            )
//
//        virtualObjectTexture = Texture.createFromAsset(
//            render,
//            "models/pawn_albedo.png",
//            Texture.WrapMode.CLAMP_TO_EDGE,
//            Texture.ColorFormat.SRGB
//        )
//
//        val virtualObjectPbrTexture =
//            Texture.createFromAsset(
//                render,
//                "models/pawn_roughness_metallic_ao.png",
//                Texture.WrapMode.CLAMP_TO_EDGE,
//                Texture.ColorFormat.LINEAR
//            )
//
//        virtualObjectMesh = Mesh.createFromAsset(render, "models/pawn.obj")
//        virtualObjectShader = Shader.createFromAssets(
//            render,
//            "shaders/environmental_hdr.vert",
//            "shaders/environmental_hdr.frag",
//            mapOf("NUMBER_OF_MIPMAP_LEVELS" to "1") // reduce load
//        ).setTexture("u_AlbedoTexture", virtualObjectTexture)
//            .setTexture("u_RoughnessMetallicAmbientOcclusionTexture", virtualObjectPbrTexture)
//            .setTexture("u_Cubemap", cubemapFilter.filteredCubemapTexture)
//            .setTexture("u_DfgTexture", dfgTexture)
//
//    }
//
//    override fun onSurfaceChanged(render: SampleRender, width: Int, height: Int) {
//        displayRotationHelper.onSurfaceChanged(width, height)
//    }
//
//    private fun showError(errorMessage: String) {
//        activity.view.snackbarHelper.showError(activity, errorMessage)
//    }
//
//    override fun onDrawFrame(render: SampleRender) {
//        val session = session ?: return
//
//        if (!hasSetTextureNames) {
//            session.setCameraTextureNames(intArrayOf(backgroundRenderer.cameraColorTexture.textureId))
//            hasSetTextureNames = true
//        }
//
//        displayRotationHelper.updateSessionIfNeeded(session)
//
//        val frame = try {
//            session.update()
//        } catch (e: CameraNotAvailableException) {
//            Log.e(TAG, "Camera not available", e)
//            return
//        }
//
//        // 🚨 FRAME THROTTLING (CRITICAL FIX)
//        if (frame.timestamp == lastFrameTimestamp) return
//        lastFrameTimestamp = frame.timestamp
//
//        val camera = frame.camera
//
//        // IMPORTANT: Initialize the background shaders
//        try {
//            // Initialize background shader (choose between camera view or depth visualization)
//            backgroundRenderer.setUseDepthVisualization(render, false) // false = show camera, true = show depth visualization
//
//            // Initialize occlusion shader (false = no occlusion)
//            backgroundRenderer.setUseOcclusion(render, false)
//
//            Log.d(SimpleArRenderer.Companion.TAG, "Background shaders initialized successfully")
//        } catch (e: IOException) {
//            Log.e(SimpleArRenderer.Companion.TAG, "Failed to initialize background shaders", e)
//            showError("Failed to initialize background shaders: $e")
////            isInitialized = false
//            return
//        }
//
//        backgroundRenderer.updateDisplayGeometry(frame)
//
//        if (frame.timestamp != 0L) {
//            backgroundRenderer.drawBackground(render)
//        }
//
//        // 🚨 DO NOT RENDER IF NOT TRACKING
//        if (camera.trackingState != TrackingState.TRACKING) {
//            return
//        }
//
//        // Handle tap
//        handleTap(frame, camera)
//
//        camera.getProjectionMatrix(projectionMatrix, 0, Z_NEAR, Z_FAR)
//        camera.getViewMatrix(viewMatrix, 0)
//
//        // Draw planes (lightweight)
//        planeRenderer.drawPlanes(
//            render,
//            session.getAllTrackables(Plane::class.java),
//            camera.displayOrientedPose,
//            projectionMatrix
//        )
//
//        for (anchor in anchors) {
//            if (anchor.anchor.trackingState != TrackingState.TRACKING) continue
//
//            anchor.anchor.pose.toMatrix(modelMatrix, 0)
//
//            // Scale instant placement anchors BEFORE MVP
//            val trackable = anchor.trackable
//            if (trackable is InstantPlacementPoint &&
//                trackable.trackingMethod == InstantPlacementPoint.TrackingMethod.SCREENSPACE_WITH_APPROXIMATE_DISTANCE
//            ) {
//                Matrix.scaleM(modelMatrix, 0, 0.5f, 0.5f, 0.5f)
//            }
//
//            // Compute final ModelViewProjection
//            Matrix.multiplyMM(modelViewProjectionMatrix, 0, projectionMatrix, 0, viewMatrix, 0)
//            Matrix.multiplyMM(modelViewProjectionMatrix, 0, modelViewProjectionMatrix, 0, modelMatrix, 0)
//
//            // Set uniforms
//            virtualObjectShader.setMat4("u_ModelViewProjection", modelViewProjectionMatrix)
//            virtualObjectShader.setBool("u_LightEstimateIsValid", false)
//
//            // Safe draw
//            try {
//                render.draw(virtualObjectMesh, virtualObjectShader)
//            } catch (e: GLException) {
//                Log.e(TAG, "Failed to draw virtual object", e)
//            }
//        }
//    }
//
//    private fun handleTap(frame: Frame, camera: Camera) {
//        val tap = activity.view.tapHelper.poll() ?: return
//
//        if (camera.trackingState != TrackingState.TRACKING) return
//
//        // ✅ Choose hit test mode based on setting
//        val hitResults = if (activity.instantPlacementSettings.isInstantPlacementEnabled) {
//            frame.hitTestInstantPlacement(tap.x, tap.y, 2.0f)
//        } else {
//            frame.hitTest(tap)
//        }
//
//        // ✅ Filter best hit (same as original but simplified)
//        val hit = hitResults.firstOrNull { hit ->
//            val trackable = hit.trackable
//
//            when (trackable) {
//                is Plane -> {
//                    trackable.isPoseInPolygon(hit.hitPose) &&
//                            PlaneRenderer.calculateDistanceToPlane(hit.hitPose, camera.pose) > 0
//                }
//
//                is Point -> {
//                    trackable.orientationMode ==
//                            Point.OrientationMode.ESTIMATED_SURFACE_NORMAL
//                }
//
//                is InstantPlacementPoint -> true
//
//                is DepthPoint -> true // safe to keep, not heavy unless depth enabled
//
//                else -> false
//            }
//        } ?: return
//
//        // ✅ Limit anchors (prevent overload)
//        if (anchors.size >= 10) {
//            anchors[0].anchor.detach()
//            anchors.removeAt(0)
//        }
//
//        anchors.add(WrappedAnchor(hit.createAnchor(), hit.trackable))
//
//    }
//
//    private data class WrappedAnchor(
//        val anchor: Anchor,
//        val trackable: Trackable
//    )
//}