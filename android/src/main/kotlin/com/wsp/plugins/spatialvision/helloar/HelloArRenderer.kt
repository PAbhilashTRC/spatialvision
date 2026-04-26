package com.wsp.plugins.spatialvision.helloar

import android.annotation.SuppressLint
import android.opengl.GLES30
import android.opengl.Matrix
import android.util.Log
import android.view.MotionEvent
import android.widget.SeekBar
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.ar.core.Anchor
import com.google.ar.core.Camera
import com.google.ar.core.Coordinates2d
import com.google.ar.core.DepthPoint
import com.google.ar.core.Frame
import com.google.ar.core.InstantPlacementPoint
import com.google.ar.core.LightEstimate
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.Trackable
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.NotYetAvailableException
import com.wsp.plugins.spatialvision.R
import com.wsp.plugins.spatialvision.common.helpers.ARLabelData
import com.wsp.plugins.spatialvision.common.helpers.DisplayRotationHelper
import com.wsp.plugins.spatialvision.common.helpers.TrackingStateHelper
import com.wsp.plugins.spatialvision.common.samplerender.Framebuffer
import com.wsp.plugins.spatialvision.common.samplerender.GLError
import com.wsp.plugins.spatialvision.common.samplerender.Mesh
import com.wsp.plugins.spatialvision.common.samplerender.SampleRender
import com.wsp.plugins.spatialvision.common.samplerender.Shader
import com.wsp.plugins.spatialvision.common.samplerender.Texture
import com.wsp.plugins.spatialvision.common.samplerender.VertexBuffer
import com.wsp.plugins.spatialvision.common.samplerender.arcore.BackgroundRenderer
import com.wsp.plugins.spatialvision.common.samplerender.arcore.SpecularCubemapFilter
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.properties.Delegates

/** Renders the HelloAR application using our example Renderer. */
class HelloArRenderer(val activity: HelloArActivity) :
    SampleRender.Renderer, DefaultLifecycleObserver {
    companion object {
        val TAG = "HelloArRenderer"

        // See the definition of updateSphericalHarmonicsCoefficients for an explanation of these
        // constants.
        private val sphericalHarmonicFactors =
            floatArrayOf(
                0.282095f,
                -0.325735f,
                0.325735f,
                -0.325735f,
                0.273137f,
                -0.273137f,
                0.078848f,
                -0.273137f,
                0.136569f
            )

        private val Z_NEAR = 0.1f
        private val Z_FAR = 100f
        val CUBEMAP_RESOLUTION = 16
        val CUBEMAP_NUMBER_OF_IMPORTANCE_SAMPLES = 32
        private const val MAX_ANCHORS = 50  // Support up to 25 measurements
        private const val MIN_DISTANCE_TO_EXISTING_ANCHOR = 0.05f // Minimum distance to existing anchors in meters
    }

    lateinit var render: SampleRender
//    lateinit var planeRenderer: PlaneRenderer
    lateinit var backgroundRenderer: BackgroundRenderer
    private val rawDepthManager = RawDepthPointCloudManager()
    lateinit var virtualSceneFramebuffer: Framebuffer
    var hasSetTextureNames = false

    // Point Cloud
    lateinit var pointCloudVertexBuffer: VertexBuffer
    lateinit var pointCloudMesh: Mesh
    lateinit var pointCloudShader: Shader

    // Keep track of the last point cloud rendered to avoid updating the VBO if point cloud
    // was not changed.  Do this using the timestamp since we can't compare PointCloud objects.
    var lastPointCloudTimestamp: Long = 0

    // Virtual object (ARCore pawn)
    lateinit var virtualObjectMesh: Mesh
    lateinit var virtualObjectShader: Shader

    lateinit var virtualObjectAlbedoTexture: Texture
    lateinit var virtualObjectAlbedoInstantPlacementTexture: Texture

    private val wrappedAnchors = mutableListOf<WrappedAnchor>()

    // Environmental HDR
    lateinit var dfgTexture: Texture
    lateinit var cubemapFilter: SpecularCubemapFilter

    // Temporary matrix allocated here to reduce number of allocations for each frame.
    val modelMatrix = FloatArray(16)
    val viewMatrix = FloatArray(16)
    val projectionMatrix = FloatArray(16)
    val modelViewMatrix = FloatArray(16) // view x model

    val labelViewProjectionMatrix  = FloatArray(16) // view x projection

    private var labelRenderer: LabelRender? = null

    val isCard = activity.view.showCardLabel
    var depthConfidence: Int? = null;

    private var cylinder: Cylinder? = null
    private var ring: Ring? = null

    val modelViewProjectionMatrix = FloatArray(16) // projection x view x model

    val sphericalHarmonicsCoefficients = FloatArray(9 * 3)
    val viewInverseMatrix = FloatArray(16)
    val worldLightDirection = floatArrayOf(0.0f, 0.0f, 0.0f, 0.0f)
    val viewLightDirection = FloatArray(4) // view x world light direction

    val session
        get() = activity.arCoreSessionHelper.session

    val displayRotationHelper = DisplayRotationHelper(activity)
    val trackingStateHelper = TrackingStateHelper(activity)

    var currentMode = InteractionMode.NONE
    var selectedAnchor: WrappedAnchor? = null

    private var lastUpdateTime = 0L

    @Volatile
    var captureHighRes = false

    private var viewportWidth = 1
    private var viewportHeight = 1

    // New variables for continuous ray casting
    private var currentReticlePose: Pose? = null
    private var isSurfaceDetected = false
    private var lastPlacementTime = 0L
    private var autoAnchor: Anchor? = null
    private var isAutoPlacementEnabled = true
    private var previewAnchor: WrappedAnchor? = null

    // Track if we've placed the first two anchors automatically
    private var autoPlacedCount = 0
    private val maxAnchors = 50

    // Replace the 3D ring with canvas overlay
    private lateinit var reticleOverlay: ReticleOverlayView

    // Track valid surface for better UX
    private var isValidSurface = false

    override fun onResume(owner: LifecycleOwner) {
        displayRotationHelper.onResume()
        hasSetTextureNames = false
    }

    override fun onPause(owner: LifecycleOwner) {
        displayRotationHelper.onPause()
    }

    override fun onSurfaceCreated(render: SampleRender) {
        // Prepare the rendering objects.
        // This involves reading shaders and 3D model files, so may throw an IOException.
        try {
//            planeRenderer = PlaneRenderer(render)
            backgroundRenderer = BackgroundRenderer(render)
            virtualSceneFramebuffer = Framebuffer(render, /*width=*/ 1, /*height=*/ 1)

            cubemapFilter =
                SpecularCubemapFilter(render, CUBEMAP_RESOLUTION, CUBEMAP_NUMBER_OF_IMPORTANCE_SAMPLES)
            // Load environmental lighting values lookup table
            dfgTexture =
                Texture(
                    render,
                    Texture.Target.TEXTURE_2D,
                    Texture.WrapMode.CLAMP_TO_EDGE,
                    /*useMipmaps=*/ false
                )
            // The dfg.raw file is a raw half-float texture with two channels.
            val dfgResolution = 64
            val dfgChannels = 2
            val halfFloatSize = 2

            val buffer: ByteBuffer =
                ByteBuffer.allocateDirect(dfgResolution * dfgResolution * dfgChannels * halfFloatSize)
            activity.assets.open("models/dfg.raw").use { it.read(buffer.array()) }

            // SampleRender abstraction leaks here.
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, dfgTexture.textureId)
            GLError.maybeThrowGLException("Failed to bind DFG texture", "glBindTexture")
            GLES30.glTexImage2D(
                GLES30.GL_TEXTURE_2D,
                /*level=*/ 0,
                GLES30.GL_RG16F,
                /*width=*/ dfgResolution,
                /*height=*/ dfgResolution,
                /*border=*/ 0,
                GLES30.GL_RG,
                GLES30.GL_HALF_FLOAT,
                buffer
            )
            GLError.maybeThrowGLException("Failed to populate DFG texture", "glTexImage2D")

            // Point cloud
            pointCloudShader =
                Shader.createFromAssets(
                    render,
                    "shaders/point_cloud.vert",
                    "shaders/point_cloud.frag",
                    /*defines=*/ null
                )
                    .setVec4("u_Color", floatArrayOf(31.0f / 255.0f, 188.0f / 255.0f, 210.0f / 255.0f, 1.0f))
                    .setFloat("u_PointSize", 5.0f)

            // four entries per vertex: X, Y, Z, confidence
            pointCloudVertexBuffer =
                VertexBuffer(render, /*numberOfEntriesPerVertex=*/ 4, /*entries=*/ null)
            val pointCloudVertexBuffers = arrayOf(pointCloudVertexBuffer)
            pointCloudMesh =
                Mesh(render, Mesh.PrimitiveMode.POINTS, /*indexBuffer=*/ null, pointCloudVertexBuffers)

            // Virtual object to render (ARCore pawn)
            virtualObjectAlbedoTexture =
                Texture.createFromAsset(
                    render,
                    "models/pawn_albedo.png",
                    Texture.WrapMode.CLAMP_TO_EDGE,
                    Texture.ColorFormat.SRGB
                )

            virtualObjectAlbedoInstantPlacementTexture =
                Texture.createFromAsset(
                    render,
                    "models/pawn_albedo_instant_placement.png",
                    Texture.WrapMode.CLAMP_TO_EDGE,
                    Texture.ColorFormat.SRGB
                )

            val virtualObjectPbrTexture =
                Texture.createFromAsset(
                    render,
                    "models/pawn_roughness_metallic_ao.png",
                    Texture.WrapMode.CLAMP_TO_EDGE,
                    Texture.ColorFormat.LINEAR
                )
//            virtualObjectMesh = Mesh.createFromAsset(render, "models/pawn.obj")
            virtualObjectMesh = Mesh.createFromAsset(render, "models/pawn_ring4.obj")
//            virtualObjectMesh = Mesh.createFromAsset(render, "models/pole.obj")
            virtualObjectShader =
                Shader.createFromAssets(
                    render,
                    "shaders/environmental_hdr.vert",
                    "shaders/environmental_hdr.frag",
                    mapOf("NUMBER_OF_MIPMAP_LEVELS" to cubemapFilter.numberOfMipmapLevels.toString())
                )
                    .setTexture("u_AlbedoTexture", virtualObjectAlbedoTexture)
                    .setTexture("u_RoughnessMetallicAmbientOcclusionTexture", virtualObjectPbrTexture)
                    .setTexture("u_Cubemap", cubemapFilter.filteredCubemapTexture)
                    .setTexture("u_DfgTexture", dfgTexture)
//            ring = Ring().also{
//                it.onSurfaceCreated(render)
//            }

            // Initialize reticle overlay
            reticleOverlay = activity.findViewById(R.id.reticle_overlay)
            reticleOverlay.showReticle(true)

            cylinder = Cylinder().also{
                it.onSurfaceCreated(render)
            }

            labelRenderer = LabelRender().also {
                it.onSurfaceCreated(render)
            }
            activity.view.captureBtn.setOnClickListener {
                activity.view.surfaceView.queueEvent {
                    captureHighRes = true
                }
            }

            activity.view.slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {

                @SuppressLint("SetTextI18n")
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val minRadius = 0.01f   // 1 cm
                    val maxRadius = 0.5f    // 50 cm

                    val t = progress / 100f
                    val radiusMeters = minRadius + t * (maxRadius - minRadius) // 0.01 + ( progress/100) * (0.5-0.01)
                    val radiusCentimeters = minRadius + progress * (maxRadius - minRadius) // 0.01 + ( progress/100) * (0.5-0.01)
//                activity.view.pipeRadius.text = "Radius: $radiusCentimeters cm"
//                activity.view.slider_value.text = "Slider Value : $progress"
//                val radius = progress / 1000f  // scale factor
                    cylinder?.setRadius(radiusMeters)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {}

                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        } catch (e: IOException) {
            Log.e(TAG, "Failed to read a required asset file", e)
            showError("Failed to read a required asset file: $e")
        }
    }

    override fun onSurfaceChanged(render: SampleRender, width: Int, height: Int) {
        displayRotationHelper.onSurfaceChanged(width, height)
        virtualSceneFramebuffer.resize(width, height)
        viewportWidth = width
        viewportHeight = height
    }

    override fun onDrawFrame(render: SampleRender) {
        val session = session ?: return

        // Texture names should only be set once on a GL thread unless they change. This is done during
        // onDrawFrame rather than onSurfaceCreated since the session is not guaranteed to have been
        // initialized during the execution of onSurfaceCreated.
        if (!hasSetTextureNames) {
            session.setCameraTextureNames(intArrayOf(backgroundRenderer.cameraColorTexture.textureId))
            hasSetTextureNames = true
        }

        // -- Update per-frame state

        // Notify ARCore session that the view size changed so that the perspective matrix and
        // the video background can be properly adjusted.
        displayRotationHelper.updateSessionIfNeeded(session)

        // Obtain the current frame from ARSession. When the configuration is set to
        // UpdateMode.BLOCKING (it is by default), this will throttle the rendering to the
        // camera framerate.
        val frame =
            try {
                session.update()
            } catch (e: CameraNotAvailableException) {
                Log.e(TAG, "Camera not available during onDrawFrame", e)
                showError("Camera not available. Try restarting the app.")
                return
            }

        val camera = frame.camera

        // Update BackgroundRenderer state to match the depth settings.
        try {
            backgroundRenderer.setUseDepthVisualization(
                render,
                activity.depthSettings.depthColorVisualizationEnabled()
            )
            backgroundRenderer.setUseOcclusion(render, activity.depthSettings.useDepthForOcclusion())
        } catch (e: IOException) {
            Log.e(TAG, "Failed to read a required asset file", e)
            showError("Failed to read a required asset file: $e")
            return
        }

        // BackgroundRenderer.updateDisplayGeometry must be called every frame to update the coordinates
        // used to draw the background camera image.
        backgroundRenderer.updateDisplayGeometry(frame)
        val shouldGetDepthImage =
            activity.depthSettings.useDepthForOcclusion() ||
                    activity.depthSettings.depthColorVisualizationEnabled()
        if (camera.trackingState == TrackingState.TRACKING && shouldGetDepthImage) {
            try {
                val depthImage = frame.acquireDepthImage16Bits()
                backgroundRenderer.updateCameraDepthTexture(depthImage)
                depthImage.close()
            } catch (e: NotYetAvailableException) {
                // This normally means that depth data is not available yet. This is normal so we will not
                // spam the logcat with this.
            }
        }

        // ========== NEW: CONTINUOUS RAY CASTING ==========
        // Perform continuous hit-test from screen center
        if (camera.trackingState == TrackingState.TRACKING) {
            performContinuousCenterHitTest(frame)
        }

        // Handle one tap per frame.
        handleTap(frame, camera)
        handleDrag(frame, camera)

        // Keep the screen unlocked while tracking, but allow it to lock when tracking stops.
        trackingStateHelper.updateKeepScreenOnFlag(camera.trackingState)

        // Show a message based on whether tracking has failed, if planes are detected, and if the user
        // has placed any objects.
        val message: String? =
            when {
                camera.trackingState == TrackingState.PAUSED &&
                        camera.trackingFailureReason == TrackingFailureReason.NONE ->
                    activity.getString(R.string.searching_planes)
                camera.trackingState == TrackingState.PAUSED ->
                    TrackingStateHelper.getTrackingFailureReasonString(camera)
                session.hasTrackingPlane() && wrappedAnchors.isEmpty() ->
                    activity.getString(R.string.waiting_taps)
                session.hasTrackingPlane() && wrappedAnchors.isNotEmpty() -> null
                else -> activity.getString(R.string.searching_planes)
            }
        if (message == null) {
            activity.view.snackbarHelper.hide(activity)
        } else {
            activity.view.snackbarHelper.showMessage(activity, message)
        }

        // -- Draw background
        if (frame.timestamp != 0L) {
            // Suppress rendering if the camera did not produce the first frame yet. This is to avoid
            // drawing possible leftover data from previous sessions if the texture is reused.
            backgroundRenderer.drawBackground(render)
        }

        // If not tracking, don't draw 3D objects.
        if (camera.trackingState == TrackingState.PAUSED) {
            return
        }

        // -- Draw non-occluded virtual objects (planes, point cloud)

        // Get projection matrix.
        camera.getProjectionMatrix(projectionMatrix, 0, Z_NEAR, Z_FAR)

        // Get camera matrix and draw.
        camera.getViewMatrix(viewMatrix, 0)

        val cameraPose = camera.pose
        val cameraPos = floatArrayOf(cameraPose.tx(), cameraPose.ty(), cameraPose.tz())

        frame.acquirePointCloud().use { pointCloud ->
            if (pointCloud.timestamp > lastPointCloudTimestamp) {
                pointCloudVertexBuffer.set(pointCloud.points)
                lastPointCloudTimestamp = pointCloud.timestamp
            }
            Matrix.multiplyMM(modelViewProjectionMatrix, 0, projectionMatrix, 0, viewMatrix, 0)
            pointCloudShader.setMat4("u_ModelViewProjection", modelViewProjectionMatrix)
            render.draw(pointCloudMesh, pointCloudShader)
        }

        // Visualize planes.
//        planeRenderer.drawPlanes(
//            render,
//            session.getAllTrackables<Plane>(Plane::class.java),
//            camera.displayOrientedPose,
//            projectionMatrix
//        )

        // -- Draw occluded virtual objects

        // Update lighting parameters in the shader
        updateLightEstimation(frame.lightEstimate, viewMatrix)
//        drawReticle(render, viewMatrix, projectionMatrix, virtualSceneFramebuffer)

        // Update the overlay with current detection status on UI thread
        activity.runOnUiThread {
            if (wrappedAnchors.size < maxAnchors) {
                reticleOverlay.showReticle(true)
                reticleOverlay.updateSurfaceDetection(isSurfaceDetected, isValidSurface)
            } else {
                reticleOverlay.showReticle(false)
            }
        }

        // Visualize anchors created by touch.
        render.clear(virtualSceneFramebuffer, 0f, 0f, 0f, 0f)

//        var obj1Pos: FloatArray? = null
//        var obj2Pos: FloatArray? = null
        // Draw all anchor points
        for (wrappedAnchor in wrappedAnchors.filter { it.anchor.trackingState == TrackingState.TRACKING }) {
            val anchor = wrappedAnchor.anchor
            anchor.pose.toMatrix(modelMatrix, 0)

            // Calculate model/view/projection matrices
            Matrix.multiplyMM(modelViewMatrix, 0, viewMatrix, 0, modelMatrix, 0)
            Matrix.multiplyMM(modelViewProjectionMatrix, 0, projectionMatrix, 0, modelViewMatrix, 0)

            // Update shader properties and draw
            virtualObjectShader.setMat4("u_ModelView", modelViewMatrix)
            virtualObjectShader.setMat4("u_ModelViewProjection", modelViewProjectionMatrix)
            val texture = if ((wrappedAnchor.trackable as? InstantPlacementPoint)?.trackingMethod ==
                InstantPlacementPoint.TrackingMethod.SCREENSPACE_WITH_APPROXIMATE_DISTANCE
            ) {
                virtualObjectAlbedoInstantPlacementTexture
            } else {
                virtualObjectAlbedoTexture
            }
            virtualObjectShader.setTexture("u_AlbedoTexture", texture)
            render.draw(virtualObjectMesh, virtualObjectShader, virtualSceneFramebuffer)
        }

    // --- Draw cylinders between every pair of anchors (0-1, 2-3, 4-5, etc.) ---
        val trackingAnchors = wrappedAnchors.filter { it.anchor.trackingState == TrackingState.TRACKING }
        val anchorPositions = trackingAnchors.map {
            floatArrayOf(it.anchor.pose.tx(), it.anchor.pose.ty(), it.anchor.pose.tz())
        }

    // Draw cylinders for each pair
        for (i in 0 until anchorPositions.size - 1 step 2) {
            if (i + 1 < anchorPositions.size) {
                val startPos = anchorPositions[i]
                val endPos = anchorPositions[i + 1]

                // Draw cylinder between this pair
                cylinder?.draw(
                    render,
                    startPos,
                    endPos,
                    viewMatrix,
                    projectionMatrix
                )

                // Calculate and display measurement for this pair
                val distance = distance(startPos, endPos)
                val midpoint = getMidPoint(startPos, endPos)

                val up = FloatArray(3)
                cameraPose.getTransformedAxis(1, 1.0f, up, 0)

                // Offset label above the line
                midpoint[0] += up[0] * 0.05f
                midpoint[1] += up[1] * 0.05f
                midpoint[2] += up[2] * 0.05f

                val labelPose = Pose(midpoint, floatArrayOf(0f, 0f, 0f, 1f))
                val measurementNumber = (i / 2) + 1
                val labelText = String.format("#%d: %.2f m", measurementNumber, distance)

                Matrix.multiplyMM(labelViewProjectionMatrix, 0, projectionMatrix, 0, viewMatrix, 0)

                val obj1Formatted = startPos.joinToString(", ") { "%.2f".format(it) }
                val obj2Formatted = endPos.joinToString(", ") { "%.2f".format(it) }
                var confidence = ""
                if(depthConfidence != null){
                    confidence = depthConfidence.toString()
                }

                val measureWithMetadata = ARLabelData(
                    title = "Measurement $measurementNumber",
                    measurement = labelText,
                    sourceA = obj1Formatted,
                    sourceB = obj2Formatted,
                    confidence = confidence
                )

                labelRenderer?.let { lr ->
                    lr.draw(
                        render = render,
                        viewProjectionMatrix = labelViewProjectionMatrix,
                        cameraPose = camera.displayOrientedPose,
                        pose = labelPose,
                        data = measureWithMetadata,
                        showCard = activity.view.showCardLabel
                    )
                }

                // Update UI with latest measurement
                activity.updateDistances(distance, 0f, 0f, depthConfidence)
            }
        }

         // --- Compose virtual scene with background ---

        // Compose the virtual scene with the background.
        backgroundRenderer.drawVirtualScene(render, virtualSceneFramebuffer, Z_NEAR, Z_FAR)

        // Simple direct capture (RECOMMENDED for your use case)
        if (captureHighRes) {
            captureHighRes = false

            try {
                // Give GPU a moment to finish
                GLES30.glFinish()

                val simpleCapture = ARCaptureHelper()
                val bitmap = simpleCapture.captureScreen(viewportWidth, viewportHeight)
                activity.view.saveBitmap(context = this.activity, bitmap = bitmap)
            } catch (e: Exception) {
                Log.e("Capture", "Failed to capture: ${e.message}")
            }

            virtualSceneFramebuffer.resize(viewportWidth, viewportHeight)
        }
    }

    /** Checks if we detected at least one plane. */
    private fun Session.hasTrackingPlane() =
        getAllTrackables(Plane::class.java).any { it.trackingState == TrackingState.TRACKING }

    /** Update state based on the current frame's light estimation. */
    private fun updateLightEstimation(lightEstimate: LightEstimate, viewMatrix: FloatArray) {
        if (lightEstimate.state != LightEstimate.State.VALID) {
            virtualObjectShader.setBool("u_LightEstimateIsValid", false)
            return
        }
        virtualObjectShader.setBool("u_LightEstimateIsValid", true)
        Matrix.invertM(viewInverseMatrix, 0, viewMatrix, 0)
        virtualObjectShader.setMat4("u_ViewInverse", viewInverseMatrix)
        updateMainLight(
            lightEstimate.environmentalHdrMainLightDirection,
            lightEstimate.environmentalHdrMainLightIntensity,
            viewMatrix
        )
        updateSphericalHarmonicsCoefficients(lightEstimate.environmentalHdrAmbientSphericalHarmonics)
        cubemapFilter.update(lightEstimate.acquireEnvironmentalHdrCubeMap())
    }

    private fun updateMainLight(
        direction: FloatArray,
        intensity: FloatArray,
        viewMatrix: FloatArray
    ) {
        // We need the direction in a vec4 with 0.0 as the final component to transform it to view space
        worldLightDirection[0] = direction[0]
        worldLightDirection[1] = direction[1]
        worldLightDirection[2] = direction[2]
        Matrix.multiplyMV(viewLightDirection, 0, viewMatrix, 0, worldLightDirection, 0)
        virtualObjectShader.setVec4("u_ViewLightDirection", viewLightDirection)
        virtualObjectShader.setVec3("u_LightIntensity", intensity)
    }

    private fun updateSphericalHarmonicsCoefficients(coefficients: FloatArray) {
        // Pre-multiply the spherical harmonics coefficients before passing them to the shader. The
        // constants in sphericalHarmonicFactors were derived from three terms:
        //
        // 1. The normalized spherical harmonics basis functions (y_lm)
        //
        // 2. The lambertian diffuse BRDF factor (1/pi)
        //
        // 3. A <cos> convolution. This is done to so that the resulting function outputs the irradiance
        // of all incoming light over a hemisphere for a given surface normal, which is what the shader
        // (environmental_hdr.frag) expects.
        //
        // You can read more details about the math here:
        // https://google.github.io/filament/Filament.html#annex/sphericalharmonics
        require(coefficients.size == 9 * 3) {
            "The given coefficients array must be of length 27 (3 components per 9 coefficients"
        }

        // Apply each factor to every component of each coefficient
        for (i in 0 until 9 * 3) {
            sphericalHarmonicsCoefficients[i] = coefficients[i] * sphericalHarmonicFactors[i / 3]
        }
        virtualObjectShader.setVec3Array(
            "u_SphericalHarmonicsCoefficients",
            sphericalHarmonicsCoefficients
        )
    }

    // Handle only one tap per frame, as taps are usually low frequency compared to frame rate.
    private fun handleTap(frame: Frame, camera: Camera) {
        if (camera.trackingState != TrackingState.TRACKING) return

        val tapHelper = activity.view.tapHelper
        if (tapHelper.isDragging()) return

        val tap = activity.view.tapHelper.pollTap() ?: return

        val selected = findSelectedAnchor(frame, tap)
        if(selected != null){
            selectedAnchor = selected
            currentMode = InteractionMode.SELECTED
            return
        }
        activity.view.surfaceView.queueEvent {


            val createdAnchor = getStableDepthAnchor(frame, camera, tap)

            val finalAnchor: Anchor?
            var trackable: Trackable? = null

            if (createdAnchor != null) {
                finalAnchor = createdAnchor
            } else {

                val hit = frame.hitTest(tap.x, tap.y).firstOrNull {
                    it.trackable is DepthPoint ||
                            it.trackable is Plane ||
                            it.trackable is InstantPlacementPoint
                }

                finalAnchor = hit?.createAnchor()
                trackable = hit?.trackable
            }

            if (finalAnchor != null) {

                if (wrappedAnchors.size >= 2) {
                    wrappedAnchors[0].anchor.detach()
                    wrappedAnchors.removeAt(0)
                }

                wrappedAnchors.add(WrappedAnchor(finalAnchor, trackable, false))
            }
        }
    }

    private fun handleDrag(frame: Frame, camera: Camera) {

        if (camera.trackingState != TrackingState.TRACKING) return

        val tapHelper = activity.view.tapHelper

        // 👉 Not dragging → reset state safely
        if (!tapHelper.isDragging()) {
            if (currentMode == InteractionMode.DRAGGING ||
                currentMode == InteractionMode.SELECTED) {

                selectedAnchor?.isSelected = false
                selectedAnchor = null
                currentMode = InteractionMode.NONE
            }
            return
        }

        val event = tapHelper.pollDrag() ?: return

        when (event.actionMasked) {

            MotionEvent.ACTION_DOWN -> {
                val selected = findSelectedAnchor(frame, event)

                if (selected != null) {
                    selectedAnchor = selected
                    selected.isSelected = true
                    currentMode = InteractionMode.SELECTED
                }
            }

            MotionEvent.ACTION_MOVE -> {

                if (currentMode == InteractionMode.SELECTED && selectedAnchor != null) {
                    currentMode = InteractionMode.DRAGGING
                }

                if (currentMode == InteractionMode.DRAGGING && selectedAnchor != null) {

                    // 🔥 THROTTLE (critical for stability)
                    if (System.currentTimeMillis() - lastUpdateTime < 50) return

                    val newAnchor =
                        getStableDepthAnchor(frame, camera, event)
                            ?: frame.hitTest(event.x, event.y)
                                .firstOrNull {
                                    it.trackable is DepthPoint ||
                                            it.trackable is Plane
                                }?.createAnchor()

                    if (newAnchor != null) {
                        // 🔥 APPLY DRAG
                        selectedAnchor?.anchor?.detach()
                        selectedAnchor?.anchor = newAnchor

                        lastUpdateTime = System.currentTimeMillis()
                    }
                }
            }
        }
    }

    /**
     * NEW METHOD: Reset auto-placement state
     * Call this when you want to start a new measurement
     */
    fun resetAutoPlacement() {
        // Clear all anchors
        for (wrappedAnchor in wrappedAnchors) {
            wrappedAnchor.anchor.detach()
        }
        wrappedAnchors.clear()

        // Reset state
        autoPlacedCount = 0
        isAutoPlacementEnabled = true
        previewAnchor?.anchor?.detach()
        previewAnchor = null
        currentReticlePose = null
        isSurfaceDetected = false
        lastPlacementTime = 0L

        Log.d(TAG, "Auto-placement reset")
    }

    /**
     * NEW METHOD: Toggle auto-placement on/off
     */
    fun setAutoPlacementEnabled(enabled: Boolean) {
        isAutoPlacementEnabled = enabled
        if (!enabled) {
            previewAnchor?.anchor?.detach()
            previewAnchor = null
        }
    }

    private fun getStableDepthAnchor(
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
                        depthConfidence = confidence

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

                    return session?.createAnchor(
                        Pose(avg, floatArrayOf(0f, 0f, 0f, 1f))
                    )
                }
            }
        } catch (e: Exception) {
            return null
        }
    }

    private fun distance(p1: FloatArray, p2: FloatArray): Float {
        val dx = p1[0] - p2[0]
        val dy = p1[1] - p2[1]
        val dz = p1[2] - p2[2]
        return Math.sqrt((dx * dx + dy * dy + dz * dz).toDouble()).toFloat()
    }

    fun getMidPoint(p1: FloatArray, p2: FloatArray): FloatArray {
        return floatArrayOf(
            (p1[0] + p2[0]) / 2f,
            (p1[1] + p2[1]) / 2f,
            (p1[2] + p2[2]) / 2f
        )
    }

    fun findSelectedAnchor(frame: Frame, tap: MotionEvent): WrappedAnchor? {

        val thresholdPx = 500f

        val screenWidth = activity.view.surfaceView.width
        val screenHeight = activity.view.surfaceView.height

        for (wa in wrappedAnchors) {

            val pose = wa.anchor.pose
            val world = floatArrayOf(pose.tx(), pose.ty(), pose.tz())

            val screen = worldToScreen(
                frame,
                world,
                screenWidth,
                screenHeight
            )

            val dx = screen[0] - tap.x
            val dy = screen[1] - tap.y

            if (dx * dx + dy * dy < thresholdPx * thresholdPx) {
                return wa
            }
        }
        return null
    }

    fun worldToScreen(
        frame: Frame,
        world: FloatArray,
        screenWidth: Int,
        screenHeight: Int
    ): FloatArray {

        val view = FloatArray(16)
        val proj = FloatArray(16)
        val vp = FloatArray(16)
        val temp = FloatArray(4)

        frame.camera.getViewMatrix(view, 0)
        frame.camera.getProjectionMatrix(proj, 0, 0.1f, 100f)

        // VP = Projection * View
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0)

        val input = floatArrayOf(world[0], world[1], world[2], 1f)

        Matrix.multiplyMV(temp, 0, vp, 0, input, 0)

        if (temp[3] == 0f) return floatArrayOf(-1f, -1f)

        val ndcX = temp[0] / temp[3]
        val ndcY = temp[1] / temp[3]

        val x = ((ndcX + 1f) / 2f) * screenWidth
        val y = ((1f - ndcY) / 2f) * screenHeight

        return floatArrayOf(x, y)
    }

    // Simplified reset method
    fun resetMeasurements() {
        activity.view.surfaceView.queueEvent {
            for (anchor in wrappedAnchors) {
                anchor.anchor.detach()
            }

            activity.runOnUiThread {
                wrappedAnchors.clear()
                autoPlacedCount = 0
                isAutoPlacementEnabled = true

                reticleOverlay.resetAndShow()
                reticleOverlay.showReticle(true)
                reticleOverlay.updateSurfaceDetection(false, false)

                activity.view.snackbarHelper.showMessage(activity, "All points cleared")
                activity.view.updateModeText(true) // Reset button text
            }
        }
    }

    // In HelloArRenderer, add this method to report UI updates:
    private fun updateUIWithMeasurementStats() {
        val pointsCount = wrappedAnchors.filter { it.anchor.trackingState == TrackingState.TRACKING }.size
        val measurementsCount = pointsCount / 2

        var totalDistance = 0f
        var lastDistance = 0f
        var lastMeasurementNumber = 0

        val anchorPositions = wrappedAnchors.map {
            floatArrayOf(it.anchor.pose.tx(), it.anchor.pose.ty(), it.anchor.pose.tz())
        }

        for (i in 0 until anchorPositions.size - 1 step 2) {
            if (i + 1 < anchorPositions.size) {
                val distance = distance(anchorPositions[i], anchorPositions[i + 1])
                totalDistance += distance
                lastDistance = distance
                lastMeasurementNumber = (i / 2) + 1
            }
        }

        activity.updateMeasurementData(pointsCount, measurementsCount, totalDistance, lastDistance, lastMeasurementNumber)
    }

// In HelloArRenderer class, update the performContinuousCenterHitTest method:

    private fun performContinuousCenterHitTest(frame: Frame) {
        val view = activity.view.surfaceView
        val centerX = view.width / 2f
        val centerY = view.height / 2f

        // ENHANCED: Use improved depth point detection
        val depthResult = rawDepthManager.findBestPointAtScreenCenter(
            frame, centerX, centerY, frame.camera.pose
        )

        if (depthResult != null && depthResult.confidence >= 0.6f) { // Higher confidence threshold
            if (!isSurfaceDetected) {
                isSurfaceDetected = true
                isValidSurface = !depthResult.hasHole

                val surfaceType = if (depthResult.hasHole) "edge surface" else "stable surface"
                val confidencePercent = (depthResult.confidence * 100).toInt()

                activity.runOnUiThread {
                    reticleOverlay.updateSurfaceDetection(true, isValidSurface)
                    if (wrappedAnchors.isEmpty()) {
                        activity.view.snackbarHelper.showMessage(
                            activity,
                            "$surfaceType detected (${confidencePercent}% confidence) - tap to place"
                        )
                    }
                }
            }

            // Apply stability check before using pose
            if (isValidSurface && depthResult.confidence >= 0.7f) {
                currentReticlePose = Pose(
                    depthResult.worldPosition,
                    floatArrayOf(0f, 0f, 0f, 1f)
                )
            } else {
                currentReticlePose = null
            }
        } else {
            // Enhanced fallback with plane detection
            val hitResults = frame.hitTest(centerX, centerY)
            val bestHit = hitResults.firstOrNull { hit ->
                when (hit.trackable) {
                    is Plane -> {
                        val plane = hit.trackable as Plane
                        plane.trackingState == TrackingState.TRACKING
                    }
                    else -> false
                }
            }

            if (bestHit != null) {
                if (!isSurfaceDetected) {
                    isSurfaceDetected = true
                    isValidSurface = true
                    activity.runOnUiThread {
                        reticleOverlay.updateSurfaceDetection(true, true)
                    }
                }
                currentReticlePose = bestHit.hitPose
            } else {
                if (isSurfaceDetected) {
                    isSurfaceDetected = false
                    isValidSurface = false
                    activity.runOnUiThread {
                        reticleOverlay.updateSurfaceDetection(false, false)
                    }
                }
                currentReticlePose = null
            }
        }
    }

    // Update addMeasurementPoint method to use enhanced anchor creation:
    fun addMeasurementPoint() {
        if (wrappedAnchors.size >= MAX_ANCHORS) {
            activity.runOnUiThread {
                activity.view.snackbarHelper.showMessage(
                    activity,
                    "Maximum points ($MAX_ANCHORS) reached. Reset to add more."
                )
            }
            return
        }

        if (currentReticlePose != null && isSurfaceDetected) {
            activity.runOnUiThread {
                reticleOverlay.showPlacingFeedback()
            }

            activity.view.surfaceView.queueEvent {
                // ENHANCED: Try point cloud anchor creation first
                var anchor = rawDepthManager.createAnchorFromPointCloud(
                    frame = session?.update() ?: return@queueEvent,
                    screenX = activity.view.surfaceView.width / 2f,
                    screenY = activity.view.surfaceView.height / 2f,
                    session = session ?: return@queueEvent
                )

                // Fall back to standard pose anchor
                if (anchor == null) {
                    anchor = session?.createAnchor(currentReticlePose!!)
                }

                anchor?.let {
                    // Check distance to existing anchors before adding
                    var tooClose = false
                    for (existing in wrappedAnchors) {
                        val existingPose = existing.anchor.pose
//                        val dx = it.pose.tx() - existingPose.tx()
//                        val dy = it.pose.ty() - existingPose.ty()
//                        val dz = it.pose.tz() - existingPose.tz()
//                        val distance = Math.sqrt(dx * dx + dy * dy + dz * dz)
                        val distance = distance(floatArrayOf(it.pose.tx(), it.pose.ty(), it.pose.tz()),
                            floatArrayOf(existingPose.tx(), existingPose.ty(), existingPose.tz()))

                        if (distance < MIN_DISTANCE_TO_EXISTING_ANCHOR) {
                            tooClose = true
                            break
                        }
                    }

                    if (!tooClose) {
                        wrappedAnchors.add(WrappedAnchor(it, null, false))

                        val pointNumber = wrappedAnchors.size
                        activity.runOnUiThread {
                            val message = if (pointNumber % 2 == 0) {
                                "Point $pointNumber placed - Measurement ${pointNumber / 2} created!"
                            } else {
                                "Point $pointNumber placed - Select point ${pointNumber + 1} to complete measurement"
                            }
                            activity.view.snackbarHelper.showMessage(activity, message)
                            reticleOverlay.showPlacedFeedback()
                            activity.view.updateModeText(wrappedAnchors.size % 2 == 0)
                        }
                    } else {
                        activity.runOnUiThread {
                            activity.view.snackbarHelper.showMessage(activity, "Point too close to existing anchor")
                        }
                        it.detach()
                    }
                }
            }
        } else {
            activity.runOnUiThread {
                activity.view.snackbarHelper.showMessage(activity, "Move phone to detect stable surface")
                reticleOverlay.updateSurfaceDetection(false, false)
            }
        }
    }

    // Add this helper method to get current frame (add to HelloArRenderer class):
    private fun getCurrentFrame(): Frame? {
        return try {
            session?.update()
        } catch (e: Exception) {
            null
        }
    }


    fun enableContinuousPlacement() {
        isAutoPlacementEnabled = true
    }

    fun disableContinuousPlacement() {
        isAutoPlacementEnabled = false
    }


    private fun showError(errorMessage: String) =
        activity.view.snackbarHelper.showError(activity, errorMessage)
}

/**
 * Associates an Anchor with the trackable it was attached to. This is used to be able to check
 * whether or not an Anchor originally was attached to an {@link InstantPlacementPoint}.
 */
public data class WrappedAnchor(
    var anchor: Anchor,
    val trackable: Trackable?,
    var isSelected: Boolean = false
)

enum class InteractionMode {
    NONE,
    ADD,
    SELECTED,
    DRAGGING
}