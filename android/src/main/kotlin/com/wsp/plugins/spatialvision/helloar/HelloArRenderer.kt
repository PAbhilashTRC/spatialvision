package com.wsp.plugins.spatialvision.helloar

import android.annotation.SuppressLint
import android.media.Image
import android.opengl.GLES30
import android.opengl.Matrix
import android.util.Log
import android.view.MotionEvent
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
//import com.wsp.plugins.spatialvision.common.samplerender.arcore.PlaneRenderer
import com.wsp.plugins.spatialvision.common.samplerender.arcore.SpecularCubemapFilter
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.NotYetAvailableException
//import com.wsp.plugins.spatialvision.GeoSpatial
import com.wsp.plugins.spatialvision.R
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

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

        // Assumed distance from the device camera to the surface on which user will try to place
        // objects.
        // This value affects the apparent scale of objects while the tracking method of the
        // Instant Placement point is SCREENSPACE_WITH_APPROXIMATE_DISTANCE.
        // Values in the [0.2, 2.0] meter range are a good choice for most AR experiences. Use lower
        // values for AR experiences where users are expected to place objects on surfaces close to the
        // camera. Use larger values for experiences where the user will likely be standing and trying
        // to
        // place an object on the ground or floor in front of them.
        val APPROXIMATE_DISTANCE_METERS = 2.0f

        val CUBEMAP_RESOLUTION = 16
        val CUBEMAP_NUMBER_OF_IMPORTANCE_SAMPLES = 32
    }

    lateinit var render: SampleRender
//    lateinit var planeRenderer: PlaneRenderer
    lateinit var backgroundRenderer: BackgroundRenderer
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

    private lateinit var cylinderShader: Shader
    private lateinit var cylinderMesh: Mesh
    private lateinit var cylinderVertexBuffer: VertexBuffer
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

    val modelViewProjectionMatrix = FloatArray(16) // projection x view x model

    val sphericalHarmonicsCoefficients = FloatArray(9 * 3)
    val viewInverseMatrix = FloatArray(16)
    val worldLightDirection = floatArrayOf(0.0f, 0.0f, 0.0f, 0.0f)
    val viewLightDirection = FloatArray(4) // view x world light direction

    val session
        get() = activity.arCoreSessionHelper.session

    val displayRotationHelper = DisplayRotationHelper(activity)
    val trackingStateHelper = TrackingStateHelper(activity)

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
            virtualObjectMesh = Mesh.createFromAsset(render, "models/pawn.obj")
//            virtualObjectMesh = Mesh.createFromAsset(render, "models/pawn_ring3.obj")
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

            // --- Cylinder Shader ---
            cylinderShader = Shader.createFromAssets(
                render,
                "shaders/cylinder.vert",
                "shaders/cylinder.frag",
                null
            ).setFloat("u_Radius", 0.01f) // base radius

// --- Cylinder Geometry (angle, height) ---
            val segments = 24
            val vertexCount = segments * 2

            val data = FloatArray(vertexCount * 2) // angle + height

            var index = 0
            for (i in 0 until segments) {
                val angle = (2.0 * Math.PI * i / segments).toFloat()

                // bottom
                data[index++] = angle
                data[index++] = 0f

                // top
                data[index++] = angle
                data[index++] = 1f
            }

            val cylinderBuffer = ByteBuffer.allocateDirect(data.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
            cylinderBuffer.put(data).position(0)

            cylinderVertexBuffer = VertexBuffer(render, 2, cylinderBuffer)

            cylinderMesh = Mesh(
                render,
                Mesh.PrimitiveMode.TRIANGLE_STRIP,
                null,
                arrayOf(cylinderVertexBuffer)
            )
//            labelRenderer.onSurfaceCreated(render)
            labelRenderer = LabelRender().also {
                it.onSurfaceCreated(render)
            }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to read a required asset file", e)
            showError("Failed to read a required asset file: $e")
        }
    }

    override fun onSurfaceChanged(render: SampleRender, width: Int, height: Int) {
        displayRotationHelper.onSurfaceChanged(width, height)
        virtualSceneFramebuffer.resize(width, height)
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

        // Handle one tap per frame.
        handleTap(frame, camera)

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

        // Visualize anchors created by touch.
        render.clear(virtualSceneFramebuffer, 0f, 0f, 0f, 0f)
        var obj1Pos: FloatArray? = null
        var obj2Pos: FloatArray? = null

        for ((anchor, trackable) in
        wrappedAnchors.filter { it.anchor.trackingState == TrackingState.TRACKING }) {
            // Get the current pose of an Anchor in world space. The Anchor pose is updated
            // during calls to session.update() as ARCore refines its estimate of the world.
            anchor.pose.toMatrix(modelMatrix, 0)

            // Calculate model/view/projection matrices
            Matrix.multiplyMM(modelViewMatrix, 0, viewMatrix, 0, modelMatrix, 0)
            Matrix.multiplyMM(modelViewProjectionMatrix, 0, projectionMatrix, 0, modelViewMatrix, 0)

            // Update shader properties and draw
            virtualObjectShader.setMat4("u_ModelView", modelViewMatrix)
            virtualObjectShader.setMat4("u_ModelViewProjection", modelViewProjectionMatrix)
            val texture =
                if ((trackable as? InstantPlacementPoint)?.trackingMethod ==
                    InstantPlacementPoint.TrackingMethod.SCREENSPACE_WITH_APPROXIMATE_DISTANCE
                ) {
                    virtualObjectAlbedoInstantPlacementTexture
                } else {
                    virtualObjectAlbedoTexture
                }
            virtualObjectShader.setTexture("u_AlbedoTexture", texture)
            render.draw(virtualObjectMesh, virtualObjectShader, virtualSceneFramebuffer)
            // Store positions for line & distance
            if (obj1Pos == null) obj1Pos = floatArrayOf(anchor.pose.tx(), anchor.pose.ty(), anchor.pose.tz())
            else if (obj2Pos == null) obj2Pos = floatArrayOf(anchor.pose.tx(), anchor.pose.ty(), anchor.pose.tz())
        }

        // --- Draw line between first 2 anchors ---
        if (obj1Pos != null && obj2Pos != null) {
            cylinderShader.setVec3("u_Start", obj1Pos)
            cylinderShader.setVec3("u_End", obj2Pos)
            cylinderShader.setMat4("u_View", viewMatrix)
            cylinderShader.setMat4("u_Proj", projectionMatrix)

            GLES30.glEnable(GLES30.GL_DEPTH_TEST)

            render.draw(cylinderMesh, cylinderShader)
//            GLES30.glEnable(GLES30.GL_DEPTH_TEST)
            GLES30.glLineWidth(1f)
            GLES30.glDepthFunc(GLES30.GL_LESS) // Restore default depth function

            // --- Distance updates ---
            val distObjToObj = distance(obj1Pos, obj2Pos)
            val distCamToObj1 = distance(cameraPos, obj1Pos)
            val distCamToObj2 = distance(cameraPos, obj2Pos)
            val midpoint = getMidPoint(obj1Pos, obj2Pos)

            val up = FloatArray(3)
            cameraPose.getTransformedAxis(1, 1.0f, up, 0)

            midpoint[0] += up[0] * 0.05f
            midpoint[1] += up[1] * 0.05f
            midpoint[2] += up[2] * 0.05f
            val labelPose = Pose(midpoint, floatArrayOf(0f, 0f, 0f, 1f))
//            val labelAnchor = session.createAnchor(labelPose)
            val labelText = String.format("%.2f m", distObjToObj)
            Matrix.multiplyMM(labelViewProjectionMatrix, 0, projectionMatrix, 0, viewMatrix, 0)

            // ---------------------------
            // DRAW LABEL (SAFE PATH)
            // ---------------------------
            labelRenderer?.let { lr ->

                // IMPORTANT: use separate matrix (no overwriting shared one)

                    lr.draw(
                        render = render,
                        viewProjectionMatrix = labelViewProjectionMatrix,
                        cameraPose = camera.displayOrientedPose,
                        pose = labelPose,
                        label = labelText
                    )
            }
            activity.updateDistances(distObjToObj, distCamToObj1, distCamToObj2)
        }

         // --- Compose virtual scene with background ---

        // Compose the virtual scene with the background.
        backgroundRenderer.drawVirtualScene(render, virtualSceneFramebuffer, Z_NEAR, Z_FAR)
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

        val tap = activity.view.tapHelper.poll() ?: return

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

            wrappedAnchors.add(WrappedAnchor(finalAnchor, trackable))

            activity.runOnUiThread {
                activity.view.showOcclusionDialogIfNeeded()
            }
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

    private fun showError(errorMessage: String) =
        activity.view.snackbarHelper.showError(activity, errorMessage)
}

/**
 * Associates an Anchor with the trackable it was attached to. This is used to be able to check
 * whether or not an Anchor originally was attached to an {@link InstantPlacementPoint}.
 */
private data class WrappedAnchor(
    val anchor: Anchor,
    val trackable: Trackable?,
)